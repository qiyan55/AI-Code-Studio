package com.aicodestudio.ide.ai.engine;

import android.content.Context;
import android.util.Log;

/**
 * LlamaCppEngine —— llama.cpp 推理引擎实现（自研 JNI 直连版）。
 *
 * 本版本直接通过 tools/llama_jni.c 编译的 libllama_jni.so 调用 llama.cpp 核心 C API，
 * 不再经过第三方 SmolLM 封装层。换来的能力：
 *   - prefix cache（warmPrefix / clearPrefixCache / cacheStats）：KV 缓存复用
 *   - KV cache 精度调节（kvBytes：FP16 / Q8 / Q4）
 *   - 生成期温度/top-k/top-p 实时调节（GenOptions 每次生成生效）
 *   - mmap 预热（prefetchModel）
 *   - native 层直接汇报后端信息（编译期宏，比 Java 侧读 /proc/cpuinfo 可靠）
 *
 * native 方法签名必须与 tools/llama_jni.c 中的 JNI 导出符号一一对应，
 * 任一变化都会导致 UnsatisfiedLinkError。
 *
 * 库加载策略：
 *   1. APK 内置 jniLibs（若已打包 libllama_jni.so）
 *   2. App 私有内部目录（用户导入过的引擎）
 *   3. 常见 Termux 输出目录 / 外部存储
 *   详见 NativeLibraryLoader。
 */
public class LlamaCppEngine implements InferenceEngine {

    private static final String TAG = "LlamaCppEngine";

    // ==================== native 方法声明 ====================
    // 签名与 tools/llama_jni.c 中的 JNI 函数严格对应

    /** 设置 native 层日志文件路径（SD 卡 App 专属目录） */
    private static native void nSetLogFile(String path);

    /** llama.cpp 版本号（commit hash） */
    private static native String nVersion();

    /** 后端信息：CPU 架构 + 编译期启用的指令集/内核 */
    private static native String nBackendInfo();

    /**
     * 加载模型，返回 native 会话句柄（0 表示失败）。
     *
     * @param path        模型文件绝对路径
     * @param ctxSize     上下文长度（tokens）
     * @param threads     推理线程数
     * @param kvBytes     KV cache 精度：2=FP16, 1=Q8, 0.5=Q4
     * @param ubatch      物理批大小
     * @param mmap        是否使用 mmap 加载
     * @param fastKernels 是否允许快速内核（ggml 内部按编译期宏分派，此处仅透传）
     * @return native 句柄，0 表示失败
     */
    private static native long nLoad(String path, int ctxSize, int threads,
                                      float kvBytes, int ubatch,
                                      boolean mmap, boolean fastKernels);

    /** 卸载模型，释放 native 资源 */
    private static native void nUnload(long handle);

    /** 中断当前生成（设置 abort 标志位） */
    private static native void nStop(long handle);

    /**
     * 同步生成文本。
     *
     * @param handle        native 会话句柄
     * @param prompt        输入提示词
     * @param maxTokens     最大生成 token 数
     * @param temperature   温度（<=0 为贪心解码）
     * @param topK          top-k 采样
     * @param topP          top-p (nucleus) 采样
     * @param repeatPenalty 重复惩罚（当前版本暂未在 native 侧实现，保留参数）
     * @param stop          停止词序列（当前版本暂未在 native 侧实现，保留参数）
     * @param out           输出 StringBuilder，native 层会 append 每个 token 片段
     * @param nPast         KV cache 中已有的 token 数（增量 prefill 时从该位置继续）
     * @return 生成的 token 数（>=0），或负数错误码
     */
    private static native int nGenerate(long handle, String prompt, int maxTokens,
                                         float temperature, int topK, float topP,
                                         float repeatPenalty, String[] stop,
                                         StringBuilder out, int nPast);

    /** v2：直接传 token 数组 prefill，不做字符串 tokenize，避免切分错位 */
    private static native int nGenerateTokens(long handle, int[] tokens, int nPast,
                                               int maxTokens, float temperature, int topK, float topP,
                                               float repeatPenalty, StringBuilder out,
                                               int[] outGenTokens);

    /** 预热前缀缓存：把 prefix  tokenize 后送入 KV cache，后续生成可复用 */
    private static native void nWarmPrefix(long handle, String prefix);

    /** 清空 KV cache */
    private static native void nClearCache(long handle);

    /** v1.107: 设置前缀 PIN 锁定长度（L1+L2+L3 token 数），淘汰时永久保留 */
    private static native void nSetPrefixKeep(long handle, int nKeep);

    /** v1.133: 把 KV 重置回系统前缀位置（删前缀之后所有对话KV，前缀常驻） */
    private static native void nResetToPrefix(long handle);

    // ===== v1.150：KV 状态导出/导入（会话级 KV 持久化）=====
    /** 导出完整 KV 状态 byte[]（llama_state_get_data）；失败返回 null */
    private static native byte[] nKvExport(long handle);
    /** 导入 KV 状态（llama_state_set_data）；成功返回 true */
    private static native boolean nKvImport(long handle, byte[] data);
    /** KV 当前已用 token 数（seq0 最大位置+1），导入后供增量 prefill 定位 */
    private static native int nKvUsedTokens(long handle);
    private static native int nWasTrimmed(long handle);
    private static native void nClearTrimmedFlag(long handle);

    /** v4.3：官方文件方式保存 KV+tokens */
    private static native boolean nSaveSessionFile(long handle, String path, int[] tokens);
    /** v4.3：官方文件方式加载，直接返回恢复的 token IDs */
    private static native int[] nLoadSessionFile(long handle, String path);
    /** v1.151：保存前把 KV 裁到滑动窗口（系统前缀+最近 keepRecent token） */
    private static native void nTrimToWindow(long handle, int keepRecent);
    /** v1.153bj：tokenize 文本为 token IDs 数组（token 级前缀匹配用） */
    private static native int[] nTokenize(long handle, String prompt, boolean addSpecial);

    /** KV cache 使用统计，例如 "KV 1024/4096 cells" */
    private static native String nCacheStats(long handle);

    /** 顺序预热模型文件页（mmap 模式下改善冷启动首 token 延迟） */
    private static native void nPrefetch(long handle);

    /** v1.124: 用模型自带chat template格式化messages */
    private static native String nApplyChatTemplate(long handle, String[] roles, String[] contents, boolean addAssistant);

    // ==================== native → Java 回调 ====================

    /**
     * native 层每生成一个 token 片段就回调此方法。
     * 通过 ThreadLocal 将当前线程的 TokenCallback 传递进来，
     * 因为 native 侧是静态方法调用，无法持有 Java 对象引用。
     */
    private static final ThreadLocal<TokenCallback> sCurrentCallback = new ThreadLocal<>();

    public static void onToken(String piece) {
        TokenCallback cb = sCurrentCallback.get();
        if (cb != null && piece != null) {
            try {
                cb.onToken(piece);
            } catch (Throwable t) {
                Log.w(TAG, "onToken callback failed", t);
            }
        }
    }

    // ==================== 引擎加载状态 ====================

    private static volatile boolean libLoaded = false;
    private static volatile String libError = null;
    private static volatile String libSource = "未加载";
    private static volatile java.io.File sLogFile = null;

    /**
     * 加载引擎 native 库。由 App 启动时调用（EngineManager.bootstrap）。
     *
     * 加载顺序：APK 内置 → App 内部目录 → Termux 输出目录 → 外部存储。
     * 详见 NativeLibraryLoader。
     */
    public static synchronized void init(Context ctx) {
        if (libLoaded) return;
        try {
            NativeLibraryLoader.Result r = NativeLibraryLoader.load(ctx, "llama_jni");
            if (r.ok) {
                libLoaded = true;
                libError = null;
                libSource = r.sourceDir;
                Log.i(TAG, "引擎就绪: " + r.loadedFrom + " (" + r.loadedLibs.size() + " 个库)");

                // 设置 native 层日志文件到 SD 卡公共目录的 "AI Code Studio" 文件夹
                try {
                    java.io.File logDir = new java.io.File(
                            android.os.Environment.getExternalStorageDirectory(),
                            "AI Code Studio");
                    if (!logDir.exists()) logDir.mkdirs();
                    sLogFile = new java.io.File(logDir, "llama_jni_log.txt");
                    nSetLogFile(sLogFile.getAbsolutePath());
                    Log.i(TAG, "native 日志文件: " + sLogFile.getAbsolutePath());
                    // Java 层也写一条日志
                    appendJavaLog("引擎加载成功，来源: " + r.sourceDir
                            + "，加载库数: " + r.loadedLibs.size());
                } catch (Throwable t) {
                    Log.w(TAG, "设置日志文件失败: " + t.getMessage());
                }
            } else {
                libError = r.error;
                libLoaded = false;
                Log.w(TAG, "引擎加载失败: " + r.error);
            }
        } catch (Throwable t) {
            libLoaded = false;
            libError = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.e(TAG, "引擎加载异常", t);
        }
    }

    /** Java 层追加日志到 SD 卡文件 */
    private static void appendJavaLog(String msg) {
        if (sLogFile == null) return;
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MM-dd HH:mm:ss");
            String line = sdf.format(new java.util.Date()) + " I [Java] " + msg + "\n";
            java.io.FileOutputStream fos = new java.io.FileOutputStream(sLogFile, true);
            fos.write(line.getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) {}
    }

    /** 引擎来源，供 UI 显示 */
    public static String libSource() {
        return libLoaded ? libSource : null;
    }

    /** 静态版 available()：UI 在没有引擎实例时判断库是否就绪 */
    public static boolean isEngineReady() {
        return libLoaded;
    }

    /** 静态版 version()：与实例版同逻辑，供 UI 直接取用 */
    public static String staticVersion() {
        if (!libLoaded) return "未加载";
        try {
            return nVersion();
        } catch (Throwable t) {
            return "llama.cpp (版本查询失败: " + t.getMessage() + ")";
        }
    }

    /**
     * 静态版后端信息，供没有引擎实例的场景（如设置页按钮）调用。
     * 已加载模型时信息与实例版一致；未加载模型时只报引擎本身的状态。
     */
    public static String backendInfoSafe() {
        if (!libLoaded) {
            return "推理引擎未就绪\n" + (libError == null ? "未知原因" : libError);
        }
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("引擎：").append(nVersion()).append('\n');
        } catch (Throwable t) {
            sb.append("引擎：llama.cpp\n");
        }
        sb.append("来源：").append(libSource).append('\n');
        try {
            sb.append("后端：").append(nBackendInfo()).append('\n');
        } catch (Throwable ignored) {
        }
        sb.append("\n说明：自研 JNI 直连 llama.cpp 核心 API，\n")
          .append("支持 prefix cache / KV 精度调节 / mmap 预热。");
        return sb.toString();
    }

    // ==================== 实例状态 ====================

    private long nativeHandle = 0;
    private String modelPath = null;
    private String lastErr = null;
    private volatile boolean generating = false;
    private int nCtx = 4096; // 模型上下文长度（load 时记录，用于溢出预检）

    // ==================== 增量 KV cache 状态 ====================
    // 系统提示词永久保留 + 多轮对话增量 prefill
    private String lastPrompt = null;       // 上次完整 prefill 的 prompt
    private int lastPromptTokens = 0;       // 上次 prefill 的 token 数
    private static final int INVALID_TOKEN_COUNT = -1; // token 计数失败标记

    // ==================== 系统前缀 KV 缓存（发热优化 A）====================
    // 每次对话首轮前把「系统提示词 L1+L2」prefill 进 KV 并记录 token 数，
    // 之后 generateStream 检测到 prompt 以该前缀开头时直接复用，避免反复全量 Prefill。
    private String warmPrefixText = null;   // 已预热的系统前缀原文
    private int warmPrefixTokens = 0;       // 已预热的 token 数
    private boolean warmPrefixValid = false;

    // ==================== 基本信息 ====================

    @Override
    public String name() {
        return "llama.cpp";
    }

    @Override
    public String version() {
        if (!libLoaded) return "未加载";
        try {
            return nVersion();
        } catch (Throwable t) {
            return "llama.cpp (查询失败)";
        }
    }

    @Override
    public boolean available() {
        return libLoaded;
    }

    @Override
    public String unavailableReason() {
        if (libLoaded) return null;
        return "推理引擎未就绪\n" + libError;
    }

    // ==================== 生命周期 ====================

    @Override
    public synchronized int load(String path, LoadOptions opts) {
        if (!libLoaded) return ERROR_NO_LIB;
        if (path == null) return ERROR_BAD_FILE;
        if (nativeHandle != 0) unload();

        LoadOptions o = (opts != null) ? opts : new LoadOptions();
        try {
            int ctxSize = o.contextSize > 0 ? o.contextSize : 4096;
            nCtx = ctxSize;
            int threads = o.threads > 0 ? o.threads : 2;  // v1.61：降温，默认 2 线程
            int ubatch = o.ubatch > 0 ? o.ubatch : 128;

            appendJavaLog("开始加载模型: path=" + path
                    + ", ctx=" + ctxSize + ", threads=" + threads
                    + ", kvBytes=" + o.kvBytes + ", ubatch=" + ubatch
                    + ", mmap=" + o.mmap);

            // 检查文件是否存在
            java.io.File modelFile = new java.io.File(path);
            if (!modelFile.exists()) {
                appendJavaLog("模型文件不存在: " + path);
                lastErr = "模型文件不存在";
                return ERROR_BAD_FILE;
            }
            appendJavaLog("模型文件存在，大小: " + (modelFile.length() / 1024 / 1024) + "MB");

            long handle = nLoad(
                    path, ctxSize, threads, o.kvBytes, ubatch, o.mmap, o.fastKernels
            );
            if (handle == 0) {
                lastErr = "模型加载失败（内存不足或文件损坏）";
                appendJavaLog("nLoad 返回 0，模型加载失败");
                return ERROR_OOM;
            }
            nativeHandle = handle;
            modelPath = path;
            lastErr = null;
            // 重置 KV 复用状态
            kvValid = false;
            kvTokenIds = null;
            kvPromptLen = 0;
            invalidateWarmPrefix();
            appendJavaLog("模型加载成功，handle=" + handle);
            return ERROR_NONE;
        } catch (Throwable t) {
            lastErr = t.getMessage();
            Log.e(TAG, "load failed", t);
            appendJavaLog("load 异常: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return ERROR_UNKNOWN;
        }
    }

    @Override
    public synchronized void unload() {
        if (nativeHandle != 0) {
            try {
                nStop(nativeHandle);
                nUnload(nativeHandle);
            } catch (Throwable t) {
                Log.w(TAG, "unload failed", t);
            }
            nativeHandle = 0;
        }
        modelPath = null;
        generating = false;
        invalidateWarmPrefix();
    }

    @Override
    public boolean isLoaded() {
        return nativeHandle != 0;
    }

    @Override
    public String loadedPath() {
        return modelPath;
    }

    // ==================== 生成 ====================

    @Override
    public String generate(String prompt, GenOptions opts) {
        if (!libLoaded || nativeHandle == 0) {
            lastErr = !libLoaded ? unavailableReason() : "模型未加载";
            return "";
        }
        GenOptions o = (opts != null) ? opts : new GenOptions();
        StringBuilder out = new StringBuilder();
        try {
            // 同步生成：每次全量 prefill（清空 KV cache）
            nClearCache(nativeHandle);
            kvValid = false;
            kvTokenIds = null;
            int rc = nGenerate(nativeHandle, prompt,
                    o.maxTokens > 0 ? o.maxTokens : 512,
                    o.temperature, o.topK, o.topP,
                    o.repeatPenalty, o.stop, out, 0);
            if (rc < 0) {
                lastErr = generateErrorText(rc);
                return "";
            }
            lastErr = null;
            return out.toString();
        } catch (Throwable t) {
            lastErr = t.getMessage();
            Log.e(TAG, "generate failed", t);
            return "";
        }
    }

    @Override
    public void generateStream(final String prompt, GenOptions opts, final TokenCallback cb) {
        if (!libLoaded) {
            if (cb != null) cb.onError(unavailableReason());
            return;
        }
        if (nativeHandle == 0) {
            if (cb != null) cb.onError("模型未加载");
            return;
        }
        final GenOptions o = (opts != null) ? opts : new GenOptions();
        final StringBuilder out = new StringBuilder();

        // ==================== KV cache 增量复用（v2 纯 token 级）====================
        // 完全去掉字符串 substring 切分——那是之前上下文错乱的根因。
        // 流程：
        //   1. tokenize 完整 prompt → newIds
        //   2. 和 kvTokenIds 逐 token 对比公共前缀 common
        //   3. 命中：把 newIds[common..] 直接传 native prefill（nPast = KV 当前位置）
        //   4. 未命中：清 KV，全量 prefill newIds
        int nPast = 0;
        boolean incremental = false;
        int[] prefillTokens = null;

        try {
            int[] newIds = nTokenize(nativeHandle, prompt, true);
            if (newIds != null && newIds.length > 0) {
                // v4.2：以 KV 实际 token 数为准，不要求 kvTokenIds.length == actualKv。
                // export/import 后可能差几个 EOS/special token，只要前 actualKv 个 token
                // 和新 prompt 前缀匹配，就从 actualKv 继续 prefill。
                int actualKv = nKvUsedTokens(nativeHandle);
                int common = 0;
                if (kvValid && kvTokenIds != null && kvTokenIds.length > 0 && actualKv > 0) {
                    int maxCommon = Math.min(newIds.length, Math.min(kvTokenIds.length, actualKv));
                    while (common < maxCommon && newIds[common] == kvTokenIds[common]) common++;
                }
                if (common == actualKv && actualKv > 0 && newIds.length > actualKv) {
                    nPast = actualKv;
                    prefillTokens = java.util.Arrays.copyOfRange(newIds, actualKv, newIds.length);
                    incremental = true;
                    com.aicodestudio.ide.ai.util.DebugLogger.log("KV",
                            "增量prefill命中: nPast=" + nPast
                                    + " actualKv=" + actualKv
                                    + " savedTokens=" + kvTokenIds.length
                                    + " common=" + common
                                    + " newTokens=" + newIds.length
                                    + " 新增tokens=" + prefillTokens.length);
                } else {
                    com.aicodestudio.ide.ai.util.DebugLogger.log("KV",
                            "全量prefill: common=" + common
                                    + "/" + actualKv
                                    + " savedTokens=" + (kvTokenIds == null ? 0 : kvTokenIds.length)
                                    + " newTokens=" + newIds.length);
                    try { nClearCache(nativeHandle); } catch (Throwable ignored) {}
                    nPast = 0;
                    prefillTokens = newIds;
                }
            } else {
                // tokenize 失败，兜底全量
                try { nClearCache(nativeHandle); } catch (Throwable ignored) {}
                prefillTokens = newIds;
            }
        } catch (Throwable t) {
            com.aicodestudio.ide.ai.util.DebugLogger.log("KV", "KV决策异常: " + t);
            try { nClearCache(nativeHandle); } catch (Throwable ignored) {}
            nPast = 0;
            prefillTokens = nTokenize(nativeHandle, prompt, true);
        }

        // ==================== 主动停止兜底（v1.130）====================
        // native 层 v1.51u 只靠 llama_vocab_is_eog 识别 EOS，删掉了 stop 字符串检测。
        // 小模型常出现：内容已写完、输出了 <|im_end|>，但该 token 没注册为 EOS，
        // native 不停 → 模型退化成把最后几段反复输出，一路撑到 max_tokens。
        // 这里在 Java 流式层补三道主动停：
        //   1) 输出里出现结束标记文本 → 立即 stop()
        //   2) 检测到连续重复退化（同一长片段在最近窗口里反复出现）→ 立即 stop()
        // max_tokens 仍保留为最终安全网，但正常情况应被上面命中。
        final StringBuilder streamed = new StringBuilder();
        final boolean[] heuristicStopped = {false};
        final TokenCallback wrapped = (cb == null) ? null : new TokenCallback() {
            @Override public void onToken(String piece) {
                if (heuristicStopped[0] || piece == null) return;
                streamed.append(piece);
                String s = streamed.toString();
                // 1) 结束标记（万一模型以普通文本形式输出了 ChatML 结束符）
                if (s.contains("<|im_end|>") || s.contains("<|endoftext|>")) {
                    heuristicStopped[0] = true;
                    Log.i(TAG, "主动停止：命中结束标记");
                    stop();
                    return;
                }
                // 2) 重复退化检测：最近 400 字符内，同一 40 字符片段出现 >=3 次
                if (s.length() > 400) {
                    String win = s.substring(s.length() - 400);
                    String unit = win.substring(win.length() - 40);
                    int count = 0, idx = 0;
                    while ((idx = win.indexOf(unit, idx)) != -1) { count++; idx += unit.length(); }
                    if (count >= 3) {
                        heuristicStopped[0] = true;
                        Log.i(TAG, "主动停止：检测到重复退化（同一40字符片段出现" + count + "次）");
                        stop();
                        return;
                    }
                }
                cb.onToken(piece);
            }
            @Override public void onComplete(String full) { cb.onComplete(full); }
            @Override public void onError(String message) { cb.onError(message); }
        };

        // 设置当前线程的回调，native 层通过 onToken 静态方法回调
        sCurrentCallback.set(wrapped != null ? wrapped : cb);
        generating = true;
        try {
            long tn = System.currentTimeMillis();
            int maxTok = o.maxTokens > 0 ? o.maxTokens : 512;
            com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                    "nGenerateTokens call: promptLen=" + prompt.length()
                    + " prefillTokens=" + (prefillTokens == null ? 0 : prefillTokens.length)
                    + " maxTokens=" + maxTok
                    + " nPast=" + nPast + " incremental=" + incremental);
            int[] outGenTokens = new int[maxTok];
            int rc = nGenerateTokens(nativeHandle, prefillTokens, nPast,
                    maxTok, o.temperature, o.topK, o.topP,
                    o.repeatPenalty, out, outGenTokens);
            com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                    "nGenerateTokens done: rc=" + rc + " outLen=" + out.length()
                    + " elapsed=" + (System.currentTimeMillis() - tn) + "ms");
            if (rc < 0 && rc != -4 && incremental) {
                try {
                    Log.w(TAG, "增量prefill失败 rc=" + rc + "，回退全量重 prefill");
                    com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                            "nGenerateTokens incremental failed rc=" + rc + ", 回退全量 prefill");
                    nClearCache(nativeHandle);
                    out.setLength(0);
                    int[] allTokens = nTokenize(nativeHandle, prompt, true);
                    rc = nGenerateTokens(nativeHandle, allTokens, 0,
                            maxTok, o.temperature, o.topK, o.topP,
                            o.repeatPenalty, out, outGenTokens);
                } catch (Throwable re) {
                    rc = -99;
                }
            }
            if (rc < 0) {
                String err = generateErrorText(rc);
                lastErr = err;
                kvValid = false;
                kvTokenIds = null;
                if (cb != null) cb.onError(err);
            } else {
                lastErr = null;
                String generatedText = out.toString();
                // v4.2：直接拼 prefillTokens + outGenTokens，不重新 tokenize。
                // 这样 kvTokenIds 和 KV 里实际 token 序列完全一致（包括 special/EOS），
                // 下一轮 common 能正确匹配到 actualKv。
                try {
                    // v4.5：如果 generate 过程中 KV 被 trim（滑动窗口淘汰），
                    // KV 里的 L4 历史位置已经平移，Java 层 kvTokenIds 不可信。
                    // 重置标记，下一轮全量 prefill 重建（L1+L2+L3 仍在 KV 里，很快）。
                    boolean trimmed = (nativeHandle != 0 && nWasTrimmed(nativeHandle) != 0);
                    if (trimmed) {
                        nClearTrimmedFlag(nativeHandle);
                        com.aicodestudio.ide.ai.util.DebugLogger.log("KV",
                                "KV trim 发生，重置 kvTokenIds，下一轮全量 prefill");
                        kvTokenIds = null;
                        kvValid = false;
                        return; // out 已通过 onComplete 回调，不需要更新记账
                    }
                    // rc = nPast + prefillTokens.length + generatedTokens
                    // 实际生成 token 数 = rc - nPast - prefillTokens.length
                    int generatedCount = rc - nPast - prefillTokens.length;
                    if (generatedCount < 0) generatedCount = 0;
                    int oldLen = (kvTokenIds != null && incremental) ? kvTokenIds.length : 0;
                    int newLen = oldLen + prefillTokens.length + generatedCount;
                    int[] combined = new int[newLen];
                    int pos = 0;
                    if (kvTokenIds != null && incremental) {
                        System.arraycopy(kvTokenIds, 0, combined, 0, oldLen);
                        pos = oldLen;
                    }
                    System.arraycopy(prefillTokens, 0, combined, pos, prefillTokens.length);
                    pos += prefillTokens.length;
                    if (generatedCount > 0) {
                        System.arraycopy(outGenTokens, 0, combined, pos, generatedCount);
                    }
                    kvTokenIds = combined;
                    kvValid = true;
                    com.aicodestudio.ide.ai.util.DebugLogger.log("KV",
                            "KV状态已更新: tokens=" + kvTokenIds.length
                                    + " nPast=" + rc
                                    + " prefill+" + prefillTokens.length
                                    + " gen+" + generatedCount
                                    + " (回复" + generatedText.length() + "c)");
                } catch (Throwable ignored) {}
                Log.i(TAG, "生成完成: " + generatedText.length() + " 字符");
                if (cb != null) cb.onComplete(generatedText);
            }
        } catch (Throwable t) {
            Log.e(TAG, "stream failed", t);
            lastErr = t.getMessage();
            // 异常时清 KV 复用状态，下次全量重建
            kvValid = false;
            kvTokenIds = null;
            if (cb != null) cb.onError(t.getMessage());
        } finally {
            sCurrentCallback.remove();
            generating = false;
        }
    }

    @Override
    public void stop() {
        if (nativeHandle != 0) {
            try {
                nStop(nativeHandle);
            } catch (Throwable t) {
                Log.w(TAG, "stop failed", t);
            }
        }
    }

    public boolean isGenerating() {
        return generating;
    }

    // ==================== KV cache 增量复用（统一状态机）====================
    // 一套状态记录"当前 KV 里存的是哪段 prompt"：
    //   kvTokenIds  = 最近一次成功 prefill 的完整 prompt 的 token IDs
    //   kvNpast     = 当前 KV 已用 token 数（每轮现读 nKvUsedTokens，不信缓存）
    //   kvPromptLen = 对应完整 prompt 的字符长度（substring 出新增段用）
    // 新 prompt 来了做 token 前缀匹配：
    //   完全匹配 → 从 kvNpast 继续 prefill 新增段（多轮增量复用）
    //   不匹配   → 清 KV 全量 prefill（兜底），然后更新这组状态
    // warmSystemPrefix 预热后也写入这组状态，系统前缀自动参与复用。
    private volatile boolean kvValid = false;
    private volatile int[] kvTokenIds = null;
    private volatile int kvPromptLen = 0;

    /** 导出当前 KV 状态 byte[]（供会话切换落盘） */
    public synchronized byte[] exportKvState() {
        if (nativeHandle == 0) return null;
        try {
            return nKvExport(nativeHandle);
        } catch (Throwable t) {
            Log.w(TAG, "exportKvState failed", t);
            return null;
        }
    }

    /** v4.3：官方文件方式保存 session（KV+tokens） */
    public synchronized boolean saveSessionFile(String path, int[] tokens) {
        if (nativeHandle == 0 || tokens == null) return false;
        try { return nSaveSessionFile(nativeHandle, path, tokens); }
        catch (Throwable t) { Log.w(TAG, "saveSessionFile failed", t); return false; }
    }

    /** v4.3：官方文件方式加载 session，返回恢复的 tokens */
    public synchronized int[] loadSessionFile(String path) {
        if (nativeHandle == 0) return null;
        try { return nLoadSessionFile(nativeHandle, path); }
        catch (Throwable t) { Log.w(TAG, "loadSessionFile failed", t); return null; }
    }

    /** v1.151：保存前把 KV 裁到滑动窗口（系统前缀+最近 keepRecent token），快照不随历史膨胀 */
    public void trimKvToWindow(int keepRecent) {
        if (nativeHandle == 0) return;
        try {
            nTrimToWindow(nativeHandle, keepRecent);
        } catch (Throwable t) {
            Log.w(TAG, "trimKvToWindow failed", t);
        }
    }

    /** v1.153bj：tokenize 文本（供 Java 层做 token 级前缀匹配） */
    public int[] tokenizeForKv(String text, boolean addSpecial) {
        if (nativeHandle == 0 || text == null) return null;
        try { return nTokenize(nativeHandle, text, addSpecial); }
        catch (Throwable t) { return null; }
    }

    /** 导入 KV 状态，并记录 KV 位置 + 保存时的 token IDs（token 级前缀匹配用） */
    public synchronized boolean importKvState(byte[] data, int[] savedTokenIds, int savedPromptLen) {
        if (nativeHandle == 0 || data == null || data.length == 0) return false;
        try {
            boolean ok = nKvImport(nativeHandle, data);
            if (ok) {
                int npast = nKvUsedTokens(nativeHandle);
                kvTokenIds = savedTokenIds;
                kvPromptLen = savedPromptLen;
                kvValid = (savedTokenIds != null && savedTokenIds.length > 0);
                Log.i(TAG, "importKvState ok: nPast=" + npast
                        + " savedTokens=" + (savedTokenIds == null ? 0 : savedTokenIds.length)
                        + " savedPromptLen=" + savedPromptLen);
                com.aicodestudio.ide.ai.util.DebugLogger.log("KV",
                        "KV导入内存完成: nPast=" + npast
                        + " tokens, savedTokens=" + (savedTokenIds == null ? 0 : savedTokenIds.length)
                        + " savedPromptLen=" + savedPromptLen
                        + ", kvValid=" + kvValid);
            } else {
                kvValid = false;
                com.aicodestudio.ide.ai.util.DebugLogger.log("KV",
                        "KV导入内存失败: nKvImport 返回 false");
            }
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "importKvState failed", t);
            kvValid = false;
            return false;
        }
    }

    /** 清除导入的 KV 状态标记（会话切换/清缓存后调用，防止误用旧位置） */
    public synchronized void clearSavedKvState() {
        kvValid = false;
        kvTokenIds = null;
        kvPromptLen = 0;
    }

    public boolean hasSavedKvState() { return kvValid; }

    /** v4.3：直接拿当前 kvTokenIds（保存 session 用） */
    public synchronized int[] getKvTokenIds() { return kvTokenIds; }

    /** v4.3：加载官方 session 文件后，直接设置恢复的 token IDs */
    public synchronized void applyLoadedTokens(int[] tokens) {
        kvTokenIds = tokens;
        kvValid = (tokens != null && tokens.length > 0);
        kvPromptLen = 0;
        com.aicodestudio.ide.ai.util.DebugLogger.log("KV",
                "applyLoadedTokens: nPast=" + nKvUsedTokens(nativeHandle)
                        + " tokens=" + (tokens == null ? 0 : tokens.length)
                        + " kvValid=" + kvValid);
    }

    /** v4.3：public wrapper，返回当前 KV 已用 token 数 */
    public int nKvUsedTokens() {
        if (nativeHandle == 0) return 0;
        return nKvUsedTokens(nativeHandle);
    }

    // ==================== 优化接口（自研 JNI 已完整支持）====================

    @Override
    public void warmPrefix(String prefix) {
        if (!libLoaded || nativeHandle == 0 || prefix == null) return;
        try {
            nWarmPrefix(nativeHandle, prefix);
        } catch (Throwable t) {
            Log.w(TAG, "warmPrefix failed", t);
        }
    }

    /**
     * 预热系统前缀（发热优化 A）：把系统提示词 prefill 进 KV 并记录 token 数，
     * 之后 generateStream 检测到 prompt 以该前缀开头时直接复用，避免反复全量 Prefill。
     * 前缀内容变化时（项目树/记忆更新）会自动清缓存重新预热。
     * @return true=预热成功（或已是最新前缀）
     */
    public synchronized boolean warmSystemPrefix(String prefixText) {
        if (!libLoaded || nativeHandle == 0) return false;
        if (prefixText == null || prefixText.isEmpty()) return false;
        if (warmPrefixValid && prefixText.equals(warmPrefixText)) return true; // 已预热且未变
        long tw = System.currentTimeMillis();
        com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                "warmSystemPrefix enter: len=" + prefixText.length()
                + " warmPrefixValid=" + warmPrefixValid
                + " warmPrefixTokens=" + warmPrefixTokens
                + " prefixChanged=" + (warmPrefixValid && !prefixText.equals(warmPrefixText)));
        try {
            // 如果 KV 已经从 session 文件加载了历史内容（nPast > system prefix），
            // 不要清 KV——直接设 prefixKeep，保留加载的历史 KV 供增量复用
            int existingN = nKvUsedTokens(nativeHandle);
            int prefixTokens = nTokenize(nativeHandle, prefixText, false).length;
            if (existingN >= prefixTokens && kvValid && kvTokenIds != null) {
                // KV 里已有内容，检查开头是否匹配 system prefix
                boolean match = true;
                if (kvTokenIds.length < prefixTokens) match = false;
                else {
                    int[] prefixIds = nTokenize(nativeHandle, prefixText, false);
                    for (int k = 0; k < prefixTokens && k < kvTokenIds.length; k++) {
                        if (kvTokenIds[k] != prefixIds[k]) { match = false; break; }
                    }
                }
                if (match) {
                    com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                            "warmSystemPrefix: KV已有" + existingN + "token历史，跳过clear，直接PIN prefixKeep=" + prefixTokens);
                    warmPrefixText = prefixText;
                    warmPrefixTokens = prefixTokens;
                    warmPrefixValid = true;
                    try { nSetPrefixKeep(nativeHandle, prefixTokens); } catch (Throwable ignored) {}
                    com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                            "warmSystemPrefix done(skip-clear): tokens=" + existingN
                                    + " elapsed=" + (System.currentTimeMillis() - tw) + "ms");
                    return true;
                }
            }
            // 校准法：清 KV → 全量 prefill 系统前缀 → maxTokens=0 → rc=前缀 token 数
            nClearCache(nativeHandle);
            StringBuilder out = new StringBuilder();
            int rc = nGenerate(nativeHandle, prefixText,
                    0, 0.1f, 1, 1.0f, 1.0f, null, out, 0);
            if (rc <= 0) { // 0/失败（prefill 失败或前缀超 n_ctx）
                Log.w(TAG, "warmSystemPrefix: 校准 prefill 失败 rc=" + rc);
                warmPrefixValid = false;
                return false;
            }
            int tokens = rc; // 无占位 token，rc 即前缀精确 token 数
            warmPrefixText = prefixText;
            warmPrefixTokens = tokens;
            warmPrefixValid = true;
            // v1.129: 关键一步——把系统前缀 token 数 PIN 进 session。
            // nClearCache 只清 KV token、不清 n_prefix_keep 字段；之后全量 prefill 时，
            // KV 写满 native 层 trimContext 会保留前 tokens 个 token（L1+L2+L3），
            // 只淘汰中间历史轮次，系统提示词永不被挤掉。
            try {
                nSetPrefixKeep(nativeHandle, tokens);
                Log.i(TAG, "系统前缀已 PIN: n_prefix_keep=" + tokens + " tokens（KV 淘汰时永久保留）");
            } catch (Throwable pe) {
                Log.w(TAG, "nSetPrefixKeep failed", pe);
            }
            // 预热后 KV 里就是系统前缀，写入 kv 复用状态：
            // 后续对话第一轮 token 匹配命中后，只 prefill 对话段（系统前缀复用）
            try {
                kvTokenIds = nTokenize(nativeHandle, prefixText, true);
                kvPromptLen = prefixText.length();
                kvValid = (kvTokenIds != null && kvTokenIds.length > 0);
            } catch (Throwable ignored) {}
            Log.i(TAG, "系统前缀已预热: " + tokens + " tokens（校准法，纯 prefill 无占位），KV增量状态已同步");
            com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                    "warmSystemPrefix done: tokens=" + tokens + " elapsed="
                    + (System.currentTimeMillis() - tw) + "ms");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "warmSystemPrefix failed", t);
            com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                    "warmSystemPrefix EXCEPTION: " + t.getMessage());
            warmPrefixValid = false;
            return false;
        }
    }

    /** 使系统前缀缓存失效（模型切换/项目切换时由上层调用） */
    public synchronized void invalidateWarmPrefix() {
        warmPrefixText = null;
        warmPrefixTokens = 0;
        warmPrefixValid = false;
        kvValid = false;
        // 注意：不清 kvTokenIds，保留供 saveSessionKv 保存退出重开后复用。
        // 下次 generateStream 时 kvValid=false → common=0 → 全量 prefill，这是预期行为。
        kvPromptLen = 0;
    }

    /** v1.124: 用模型自带chat template格式化messages，返回完整prompt字符串 */
    public String applyChatTemplate(String[] roles, String[] contents, boolean addAssistant) {
        if (nativeHandle == 0) return null;
        try {
            return nApplyChatTemplate(nativeHandle, roles, contents, addAssistant);
        } catch (Throwable t) {
            Log.e(TAG, "applyChatTemplate failed", t);
            return null;
        }
    }

    /** 从 "KV 1024/4096 cells" 解析已用 cells */
    private static int parseUsedCells(String stats) {
        if (stats == null) return 0;
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(\\d+)\\s*/\\s*\\d+").matcher(stats);
            return m.find() ? Integer.parseInt(m.group(1)) : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    @Override
    public void clearPrefixCache() {
        if (!libLoaded || nativeHandle == 0) return;
        try {
            nClearCache(nativeHandle);
            // 重置 KV 复用状态
            kvValid = false;
            kvTokenIds = null;
            kvPromptLen = 0;
        } catch (Throwable t) {
            Log.w(TAG, "clearPrefixCache failed", t);
        }
    }

    @Override
    public String cacheStats() {
        if (!libLoaded || nativeHandle == 0) return "";
        try {
            return nCacheStats(nativeHandle);
        } catch (Throwable t) {
            return "";
        }
    }

    @Override
    public int getPrefixTokens() {
        return warmPrefixValid ? warmPrefixTokens : 0;
    }

    @Override
    public int getContextSize() {
        return nCtx > 0 ? nCtx : 4096;
    }

    @Override
    public void prefetchModel() {
        if (!libLoaded || nativeHandle == 0) return;
        try {
            nPrefetch(nativeHandle);
        } catch (Throwable t) {
            Log.w(TAG, "prefetchModel failed", t);
        }
    }

    // ==================== 诊断 ====================

    @Override
    public String backendInfo() {
        if (!libLoaded) return "引擎未加载";
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("版本: ").append(nVersion()).append('\n');
        } catch (Throwable ignored) {
        }
        try {
            sb.append("后端: ").append(nBackendInfo()).append('\n');
        } catch (Throwable ignored) {
        }
        if (nativeHandle != 0) {
            try {
                String stats = nCacheStats(nativeHandle);
                if (stats != null && stats.length() > 0) {
                    sb.append("缓存: ").append(stats).append('\n');
                }
            } catch (Throwable ignored) {
            }
        }
        String result = sb.toString().trim();
        return result.length() > 0 ? result : "llama.cpp (自研 JNI)";
    }

    @Override
    public String lastError() {
        return lastErr;
    }

    // ==================== 内部工具 ====================

    /** native 返回的负数错误码转成人话 */
    private String generateErrorText(int rc) {
        switch (rc) {
            case -1:  return "内存不足（OOM）";
            case -2:  return "输入参数错误";
            case -3:  return "引擎未就绪或模型未加载";
            case -4:  return "生成被用户中断";
            case -99: return "native 推理异常（llama_decode 失败）";
            default:  return "生成失败 (rc=" + rc + ")";
        }
    }
}
