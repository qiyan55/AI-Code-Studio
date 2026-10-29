package com.aicodestudio.ide.ai.engine;

/**
 * InferenceEngine - 推理引擎抽象接口
 *
 * 设计意图：引擎是这个项目最核心的部分，所以从一开始就不绑死 llama.cpp。
 * 今天用 llama.cpp，明天想试 MNN / ncnn / MediaPipe，只需实现这个接口，
 * 上层（对话面板、编译失败分析、代码补全）一行都不用改。
 *
 * 关键约定：
 *   1. available() 返回 false 时，所有方法必须安全降级，不得抛异常。
 *      原因：没有 .so 的构建（比如现在）App 也必须能正常跑。
 *   2. 生成接口同时提供同步和流式两种，流式用于对话 UI 的逐字输出。
 *   3. 所有耗时方法由调用方放到后台线程，接口本身不做线程管理。
 */
public interface InferenceEngine {

    /** 引擎标识，例如 "llama.cpp" */
    String name();

    /** 引擎版本，例如 "b3600" */
    String version();

    /**
     * native 库是否可用。
     * 未打包 .so、ABI 不匹配、加载失败都返回 false。
     */
    boolean available();

    /** 不可用时的人话说明，UI 直接展示 */
    String unavailableReason();

    // ==================== 模型生命周期 ====================

    class LoadOptions {
        public int   contextSize = 16384;
        // v1.61：从 4 线程降到 2 线程，降温
        public int   threads     = 2;
        /** KV cache 精度：2=FP16, 1=Q8, 0.5=Q4。越小越省内存 */
        public float kvBytes     = 2f;
        /** mmap 加载。省内存但冷启动慢，配合 prefetch 使用 */
        public boolean mmap      = true;
        /** 是否允许使用 KleidiAI 等厂商优化内核 */
        public boolean fastKernels = true;
        /** 预填充/推理的物理批大小 */
        public int   ubatch      = 128;
    }

    /**
     * 加载模型。
     * @return 0 成功，非 0 为错误码（见 ERROR_* 常量）
     */
    int load(String modelPath, LoadOptions opts);

    void unload();

    boolean isLoaded();

    /** 当前加载的模型路径，未加载返回 null */
    String loadedPath();

    // ==================== 生成 ====================

    class GenOptions {
        // v1.61：从 512 降到 256，Agent 循环每轮只输出一个命令，不需要太长
        public int   maxTokens     = 256;
        public float temperature   = 0.7f;
        public int   topK          = 40;
        public float topP          = 0.95f;
        public float repeatPenalty = 1.1f;
        /** 停止词序列 */
        public String[] stop       = null;
    }

    /** 一次性生成，返回完整文本 */
    String generate(String prompt, GenOptions opts);

    /** 流式生成，逐 token 回调 */
    void generateStream(String prompt, GenOptions opts, TokenCallback cb);

    /** 中断当前生成 */
    void stop();

    interface TokenCallback {
        void onToken(String piece);
        void onComplete(String fullText);
        void onError(String message);
    }

    // ==================== 优化相关 ====================

    /**
     * 预热前缀缓存（prefix caching）。
     *
     * 这是本工具回报最高的一项优化：系统提示词 + 仓库上下文通常几千 token，
     * 每轮对话都重算一遍。缓存住之后，后续请求只需计算新增部分。
     */
    void warmPrefix(String prefix);

    /**
     * 预热系统前缀（发热优化 A）：把 L1 系统提示词 + L2 项目记忆 prefill 进 KV，
     * 之后 generateStream 检测到 prompt 以该前缀开头时直接复用。
     * 前缀内容变化时自动清缓存重新预热。
     * @return true=预热成功或已是最新
     */
    boolean warmSystemPrefix(String prefix);

    /** 清空前缀缓存（切换模型或项目时调用） */
    void clearPrefixCache();

    /** 缓存命中信息，例如 "prefix 1024/1024 tok cached" */
    String cacheStats();

    /** 系统前缀（L1+L2+L3）PIN 住的 token 数；未预热返回 0 */
    int getPrefixTokens();

    /** KV cache 总上下文长度（n_ctx） */
    int getContextSize();

    /** v1.124: 用模型自带chat template格式化messages */
    String applyChatTemplate(String[] roles, String[] contents, boolean addAssistant);

    /**
     * 顺序预热模型文件页。
     * mmap 模式下首次推理会触发大量缺页中断，冷启动慢；
     * 提前顺序触碰一遍可显著改善首 token 延迟。
     */
    void prefetchModel();

    // ==================== 诊断 ====================

    /**
     * 实际生效的后端与 SIMD 内核。
     * 例如 "CPU[aarch64] KleidiAI=on dotprod=on i8mm=on"
     * 由 native 层汇报，比 Java 侧读 /proc/cpuinfo 可靠。
     */
    String backendInfo();

    /** 最近的 native 错误，无错误返回 null */
    String lastError();

    // ==================== 错误码 ====================
    int ERROR_NONE      = 0;
    int ERROR_OOM       = -1;   // 内存不足
    int ERROR_BAD_FILE  = -2;   // 文件损坏或格式不支持
    int ERROR_NO_LIB    = -3;   // native 库不可用
    int ERROR_ABORTED   = -4;   // 被用户中断
    int ERROR_UNKNOWN   = -99;
}
