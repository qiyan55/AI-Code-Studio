package com.aicodestudio.ide.ai.chat;

import android.content.Context;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.aicodestudio.ide.ai.agent.AgentExecCLI;
import com.aicodestudio.ide.ai.agent.AgentSystemPrompt;
import com.aicodestudio.ide.ai.agent.StreamingToolDetector;
import com.aicodestudio.ide.ai.agent.ShellCommandExecutor;
import com.aicodestudio.ide.ai.agent.ShellCommandParser;
import com.aicodestudio.ide.ai.core.WorkspaceState;
import com.aicodestudio.ide.ai.engine.EngineManager;
import com.aicodestudio.ide.ai.core.AppConfig;
import com.aicodestudio.ide.ai.engine.InferenceEngine;

/**
 * AI Chat Manager - Core AI调度模块
 * 负责云端大模型API调用、本地量化模型推理调度
 * 实现文档: 4.1 AI对话调度模块
 */
public class AIChatManager {
    
    private static final String TAG = "AIChatManager";
    // v1.58：统一用 ## 标题格式，和主系统提示词风格一致，小模型更容易区分
    private static final String PREFIX_MEM = "## 项目记忆";
    /** v1.51i：场景A 工作空间已创建文件清单（L2 注入，系统确定性扫描，防 AI 重复创建） */
    private static final String PREFIX_FILES = "## 当前文件结构";
    /** v1.51p：工具结果回填前缀（system 角色 + 目录树，MiniCPM5 云端实测有效） */
    private static final String PREFIX_TOOL = "## 工具执行结果";
    /**
     * v1.51i：GBNF grammar——只允许普通文本或 ```bash/shell/sh 代码块，
     * 其他语言代码块（```java/```xml/```gradle 等）被语法直接拒绝。
     * 治本：1.5B"贴代码"是能力上限，提示词只能改善；grammar 是确定性钳制，
     * 模型想输出代码块只能走 bash 分支，否则解码被拒。
     * 云端实测（3/3）：只输出 bash 命令块、零其他代码块、零死循环。
     * 注：本地 JNI 引擎暂未封装 grammar 接口，仅云端启用。
     */
    private static final String GBNF_PROMPT = "root ::= (any | bashblock | shellblock | shblock)*\n"
            + "any ::= [^`] | \"`\" [^`] | \"``\" [^`]\n"
            + "bashblock ::= \"```\" \"bash\" [^`]* \"```\"\n"
            + "shellblock ::= \"```\" \"shell\" [^`]* \"```\"\n"
            + "shblock ::= \"```\" \"sh\" [^`]* \"```\"\n";
    private static AIChatManager instance;
    private static android.content.Context sCtx;

    /** 注入 App 上下文（AICodeStudioApp.onCreate 调用） */
    public static void attach(android.content.Context ctx) { sCtx = ctx.getApplicationContext(); }
    
    // 推理模式
    public static final int MODE_CLOUD = 0;
    public static final int MODE_LOCAL = 1;
    
    private int currentMode = MODE_CLOUD;
    private String apiKey = "";
    private String apiBaseUrl = "https://api.openai.com/v1";
    private String modelName = "gpt-4o";
    private String localModelPath = "";
    
    // 推理参数（v1.50b：对齐云端实测最优配置——温度0.3 命令命中12/12 vs 0.7 随机复述；
    // maxOutputTokens 700 命令直接 vs 2048 输出4000+字教程）
    private int contextLength = 4096;
    // v1.110: temperature 从 0.9 降到 0.7——1B 小模型需要更低温度来遵守指令
    // v1.154: 降到 0.3——写代码/遵守指令需要低温度，减少教程漂移和system复述
    private float temperature = 0.3f;
    private int maxOutputTokens = 2048;
    private int threadCount = 4;
    
    private final ExecutorService executor;
    private final List<ChatMessage> messageHistory;
    private AIResponseListener responseListener;

    // v1.91：流式工具识别与即时执行
    private StreamingToolDetector detector;                     // 当前轮的工具块识别器
    private final List<AgentExecCLI.ExecResult> roundResults = new ArrayList<>(); // 本轮已即时执行的工具结果
    // v1.91g：累积本对话所有轮的原始完整回复（含思考块+代码块+命令块），用于历史保存，
    //         保证重开历史能还原全部样式与 AI 原始输出顺序
    private final StringBuilder rawFullAll = new StringBuilder();
    
    public interface AIResponseListener {
        void onResponseStart();
        void onResponseChunk(String chunk);
        void onResponseComplete(String fullResponse);
        void onError(String errorMsg);
        // v1.91：流式工具调用事件（读/写/执行 即时卡片）
        void onToolStart(StreamingToolDetector.ToolBlock block);
        void onToolUpdate(String content);
        void onToolEnd(StreamingToolDetector.ToolBlock block, boolean ok, String summary);
    }
    
    public static class ChatMessage {
        public final String role;  // "user", "assistant", "system"
        public final String content;
        public final long ts;      // v1.135：消息时间戳，用于跨模式合并显示时按时间排序
        public ChatMessage(String role, String content) {
            this(role, content, System.currentTimeMillis());
        }
        public ChatMessage(String role, String content, long ts) {
            this.role = role;
            this.content = content;
            this.ts = ts;
        }
    }
    
    private AIChatManager() {
        executor = Executors.newSingleThreadExecutor();
        messageHistory = new ArrayList<>();
        // 系统提示词占位（每次生成前由 injectSystemPrompt 动态刷新，反映当前项目路径）
        messageHistory.add(new ChatMessage("system", ""));
    }

    // ===== v1.37 聊天记录持久化：App 重启不丢对话（用户反馈"代码全部没有了"根因①）=====
    private volatile Context appCtx;

    /**
     * v1.51l：当前会话历史 key（按项目隔离，治"切项目串台"）。
     * 原实现：全局单例 + 单一 chat_history.json——切换项目只切 UI 气泡，
     * AI 上下文仍是所有项目消息混合，模型带着项目A历史写项目B。
     * 修复：每个项目独立 key → 独立历史文件 → switchConversation 切换。
     */
    private volatile String currentHistoryKey = "default";

    /** v1.151：当前会话 key（ChatPanel 判断同会话切换用） */
    public String getCurrentHistoryKey() {
        return currentHistoryKey;
    }

    /** v1.151：把任务提交到 AI 管理器的单线程执行器（与生成串行，KV 切换不撞生成） */
    public void runOnExecutor(Runnable r) {
        try {
            executor.execute(r);
        } catch (Throwable t) {
            try { r.run(); } catch (Throwable ignored) {}
        }
    }

    // ===== v1.135 对话模式：闲聊 / 工作任务 =====
    // 同一对话内两套历史分开存（工作: xxx.json；闲聊: xxx_chat.json），
    // 切换模式=切历史文件+清KV，互不污染，避免闲聊内容干扰小模型开发。
    public static final int MODE_WORK = 1;   // 工作任务：三阶段开发、命令执行、文档规范注入
    public static final int MODE_CHAT = 0;   // 普通对话：不注入文档规范、不锁阶段、不执行命令
    private volatile int chatMode = MODE_WORK;
    private volatile String modeSuffix = ""; // 闲聊模式为 "_chat"，工作模式为空（向后兼容）

    /** 由 UI 层在启动时调用一次，恢复上次会话历史 */
    public synchronized void attachContext(Context ctx) {
        if (appCtx == null && ctx != null) {
            appCtx = ctx.getApplicationContext();
            // v1.51l：旧版单一 chat_history.json → 迁移到 default key 分文件（升级不丢历史）
            try {
                File legacy = new File(appCtx.getFilesDir(), "chat_history.json");
                File cur = historyFile();
                if (legacy.exists() && !cur.exists()) {
                    File dir = cur.getParentFile();
                    if (dir != null && !dir.exists()) dir.mkdirs();
                    if (legacy.renameTo(cur)) {
                        Log.i(TAG, "旧版 chat_history.json 已迁移到 " + cur.getName());
                    }
                }
            } catch (Throwable ignored) {
            }
            loadHistory();
        }
    }

    /**
     * v1.51l：切换对话（按项目隔离 AI 上下文，治"切项目串台"）。
     * 原实现：全局单例 + 单一 chat_history.json——切换项目只切 UI 气泡，
     * AI 上下文仍是所有项目消息混合，模型带着项目A历史写项目B。
     * 修复：每个项目独立历史文件（文件名=项目名_哈希，精确识别），
     * ChatPanel.setCurrentProject 时调用本方法：保存当前 → 清空上下文 → 加载新项目历史。
     * projectPath 为空（场景A 空白对话）→ default。
     */
    /** v1.153x：切换会话分步进度回调（在后台切换线程同步调用，调用方负责转 UI 线程） */
    public interface SwitchProgressListener {
        void onSwitchStep(String step);
    }

    public synchronized void switchConversation(String convKey) {
        switchConversation(convKey, -1, null);
    }

    public synchronized void switchConversation(String convKey, SwitchProgressListener progress) {
        switchConversation(convKey, -1, progress);
    }

    /**
     * v1.153az：带目标会话模式的切换。
     * 历史文件是 {key}{modeSuffix}.json——必须在 loadHistory() 之前把 chatMode/modeSuffix
     * 切到目标会话的模式，才读对文件。旧流程切换完成后 UI 回调里再 setChatMode，
     * 内部 clear()+loadHistory() 会把刚加载好的目标会话内存历史清掉重渲染，
     * 表现为"切过去历史空白，切新对话再切回才正常"。
     * targetChatMode = -1 表示不改模式（保持当前）。
     */
    public synchronized void switchConversation(String convKey, int targetChatMode,
                                                SwitchProgressListener progress) {
        long tSwitch = System.currentTimeMillis();
        com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                "switchConversation enter: " + currentHistoryKey + " -> " + convKey
                + " sessionBusy=" + sessionBusy + " stopRequested=" + stopRequested
                + " targetMode=" + targetChatMode);
        // v1.149：切换优先——强制停止正在进行的生成，并 ++genSeq 使旧 Agent 循环
        // 的 finally 无法覆盖切换后的新状态
        stopGeneration();
        genSeq++;
        // v1.153ah：关键——等生成线程真正退出（而不是 abort 标志刚设就导出 KV）。
        // abort 是异步的，native decode 要到下个采样点才停；立刻导出会拿到 prefill 中途的
        // 不一致 KV。v1.153aq：executor 单线程串行，能轮到 switchConversation 说明生成线程
        // 已退出；bumpGenSeq 后旧 finally 不复位 sessionBusy，死等 sessionBusy 会卡满 3 秒
        // （进度条停在 40%）。改为查 native 引擎 isGenerating()——引擎真停了就立即继续。
        long stopDeadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < stopDeadline) {
            InferenceEngine e = EngineManager.get();
            boolean engIdle = (e == null) || !e.isLoaded();
            if (!engIdle && e instanceof com.aicodestudio.ide.ai.engine.LlamaCppEngine) {
                engIdle = !((com.aicodestudio.ide.ai.engine.LlamaCppEngine) e).isGenerating();
            }
            if (engIdle) break;
            try { Thread.sleep(50); } catch (InterruptedException ex) { break; }
        }
        sessionState = SessionState.IDLE;
        sessionBusy = false;
        try {
            if (appCtx == null) return;
            com.aicodestudio.ide.ai.util.DebugLogger.log("KV", "switchConversation: " + currentHistoryKey + " -> " + convKey);
            if (progress != null) progress.onSwitchStep("保存会话记录中");
            persistHistory();  // 先保存当前项目历史
            if (progress != null) progress.onSwitchStep("保存会话 KV 中");
            long savedKvBytes = saveSessionKv();   // v1.150：切走前保存当前会话 KV（增量续跑用）
            if (progress != null && savedKvBytes > 0) {
                progress.onSwitchStep("保存 KV 完成 (" + fmtKvSize(savedKvBytes) + ")");
            }
            // v1.51ca：传进来的convKey就是ChatPanel生成好的唯一对话key，不要再historyKey()重新哈希
            // 之前这里又重新哈希，导致新对话加载到旧Snake项目的历史文件，严重串台
            String key = convKey;
            if (key == null || key.isEmpty()) key = "default";
            if (key.equals(currentHistoryKey)) {
                // 同一会话不切换：重载本会话 KV（幂等；若指纹不符则自动清缓存全量重建）
                if (progress != null) progress.onSwitchStep("加载会话 KV 中");
                loadSessionKv();
                loadSnapshotIndex();
                if (progress != null) progress.onSwitchStep("会话切换完成");
                return;
            }
            if (progress != null) progress.onSwitchStep("关闭当前会话中");
            currentHistoryKey = key;
            synchronized (messageHistory) {
                messageHistory.clear();
                messageHistory.add(new ChatMessage("system", ""));
            }
            // v1.105：切换会话清空 L2/L3 运行态
            planDoc = "";
            progressSummary = "";
            chatLongTermMemory = "";
            kvLoadedThisSession = false;
            // v1.153az：加载历史前先切到目标会话的模式——历史/KV 文件按 key+modeSuffix 命名，
            // 模式切对了 loadHistory/loadSessionKv 才读到该会话自己那套（chat/work 分开存）。
            if (targetChatMode == MODE_CHAT || targetChatMode == MODE_WORK) {
                chatMode = targetChatMode;
                modeSuffix = (targetChatMode == MODE_CHAT) ? "_chat" : "";
            }
            if (progress != null) progress.onSwitchStep("加载新会话历史中");
            loadHistory();     // 加载新对话历史（key+模式 已切换）
            // v1.153ap：加载 KV 前上报文件大小，让用户直观看到要导入多少 MB
            File kf = kvFile();
            long kvSize = (kf != null && kf.exists()) ? kf.length() : 0;
            if (progress != null) {
                progress.onSwitchStep(kvSize > 0
                        ? ("加载 KV 中 (" + fmtKvSize(kvSize) + ")")
                        : "加载会话 KV 中");
            }
            loadSessionKv();   // v1.150：加载目标会话 KV（指纹匹配则导入增量；否则清缓存全量）
            // v2.0：加载L3快照索引（不加载KV，只加载元数据用于检索）
            loadSnapshotIndex();
            if (progress != null) progress.onSwitchStep("会话切换完成");
            com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                    "switchConversation done: newKey=" + currentHistoryKey
                    + " totalElapsed=" + (System.currentTimeMillis() - tSwitch) + "ms");
        } catch (Throwable t) {
            Log.w(TAG, "switchConversation failed", t);
            com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                    "switchConversation EXCEPTION: " + t.getMessage()
                    + " totalElapsed=" + (System.currentTimeMillis() - tSwitch) + "ms");
        }
    }

    /**
     * v1.51o：显式清空本地推理引擎的 KV 缓存（新建对话/切换项目/切换会话时调用）。
     * 历史不需要持久化 KV——聊天记录已落盘 chat_history/*.json，恢复时经 L1-L4
     * （系统前缀+记忆+摘要+最近窗口）整体重建 prompt 重新 prefill。
     */
    public synchronized void clearKvCache() {
        try {
            InferenceEngine engine = EngineManager.get();
            if (engine != null && engine.available() && engine.isLoaded()) {
                engine.clearPrefixCache();
                lastWarmedPrefix = null; // 防下次 ensurePrefixWarmed 误判前缀仍有效
                if (engine instanceof com.aicodestudio.ide.ai.engine.LlamaCppEngine) {
                    ((com.aicodestudio.ide.ai.engine.LlamaCppEngine) engine).clearSavedKvState(); // v1.150
                }
                Log.i(TAG, "KV 缓存已清空（对话/项目切换）");
            }
        } catch (Throwable t) {
            Log.w(TAG, "clearKvCache failed", t);
        }
    }

    // ===== v1.150：会话级 KV 持久化（导出/加载）=====
    // KV 是模型预计算结果（几 MB 级二进制），落盘独立 .kv 文件（不走数据库，SQLite 大 blob 无优势）。
    // meta 记录模型指纹 + 导出时的完整 prompt 文本——导入后若新 prompt 以其开头则增量 prefill。
    /** 生成时记录的"上一轮完整 prompt"（导出 KV 时写入 meta，供导入后增量判断） */
    private volatile String lastBuiltPrompt = null;

    /**
     * v1.153bb：KV 文件名只绑会话 ID（currentHistoryKey），不再带 modeSuffix。
     * 一个会话一个固定 KV 文件，创建会话时定死，以后永远读写同一个文件——
     * 不管闲聊/工作任务怎么切，都不会因 modeSuffix 变化找错文件。
     * （旧版 key_chat.kv / key.kv 双后缀并存，导致重开后找错文件、40MB KV 显示 0KB）
     */
    private File kvFile() {
        return new File(new File(appCtx.getFilesDir(), "chat_history"),
                currentHistoryKey + ".kv");
    }

    /** v1.153ap：把字节数格式化成直白大小（KB/MB），遮罩进度显示用 */
    private static String fmtKvSize(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0);
        }
        return String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0);
    }
    private File kvMetaFile() {
        return new File(new File(appCtx.getFilesDir(), "chat_history"),
                currentHistoryKey + ".kv.meta");
    }

    /** 模型指纹：路径 + 大小 + 修改时间（换模型/模板即失效，自动丢弃旧 KV） */
    private String kvFingerprint() {
        try {
            InferenceEngine engine = EngineManager.get();
            if (engine == null || !engine.isLoaded()) return null;
            String p = engine.loadedPath();
            if (p == null || p.isEmpty()) return null;
            File f = new File(p);
            if (!f.exists()) return null;
            return p + "|" + f.length() + "|" + f.lastModified();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 保存当前会话 KV（离开对话页/切对话/切模式时调用）。导出失败静默跳过，不影响主流程。 */
    // v1.153ab：KV 全量导出耗时数秒（日志实测 13MB/4.3s）。切换线程与 onPause/onDestroy
    // 常同时触发保存同一 key，串行重复导出导致切换卡顿。同一 key 8s 内只导出一次。
    private volatile String lastKvSaveKey = null;
    private volatile long lastKvSaveStartTs = 0;

    public synchronized long saveSessionKv() {
        try {
            long now = System.currentTimeMillis();
            if (currentHistoryKey.equals(lastKvSaveKey) && now - lastKvSaveStartTs < 8000) {
                com.aicodestudio.ide.ai.util.DebugLogger.log("KV",
                        "saveSessionKv 跳过重复保存（8s 内同 key）: key=" + currentHistoryKey);
                return 0;
            }
            lastKvSaveKey = currentHistoryKey;
            lastKvSaveStartTs = now;
            if (appCtx == null) return 0;
            InferenceEngine engine = EngineManager.get();
            if (engine == null || !engine.isLoaded()) return 0;
            if (!(engine instanceof com.aicodestudio.ide.ai.engine.LlamaCppEngine)) return 0;
            com.aicodestudio.ide.ai.util.DebugLogger.log("KV", "saveSessionKv 开始: key=" + currentHistoryKey
                    + " mode=" + (chatMode == MODE_CHAT ? "chat" : "work"));
            com.aicodestudio.ide.ai.engine.LlamaCppEngine lengine =
                    (com.aicodestudio.ide.ai.engine.LlamaCppEngine) engine;
            // v4.3：用官方 llama_state_save_file，同时存 KV + token IDs，恢复时严格一致
            int[] tokens = lengine.getKvTokenIds();
            if (tokens == null || tokens.length == 0) {
                com.aicodestudio.ide.ai.util.DebugLogger.log("KV", "saveSessionKv: kvTokenIds 为空，不保存");
                return 0;
            }
            File dir = kvFile().getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            boolean ok = false;
            try {
                ok = lengine.saveSessionFile(kvFile().getAbsolutePath(), tokens);
            } catch (Throwable t) {
                com.aicodestudio.ide.ai.util.DebugLogger.log("KV", "saveSessionKv 异常: " + t);
            }
            String fp = kvFingerprint();
            org.json.JSONObject metaJo = new org.json.JSONObject();
            metaJo.put("fingerprint", fp == null ? "" : fp);
            metaJo.put("kvBytes", kvFile().length());
            metaJo.put("t", System.currentTimeMillis());
            try {
                java.io.FileOutputStream mfos = new java.io.FileOutputStream(kvMetaFile());
                mfos.write(metaJo.toString().getBytes("UTF-8"));
                mfos.close();
            } catch (Throwable ignored) {}
            com.aicodestudio.ide.ai.util.DebugLogger.log("KV",
                    "saveSessionKv " + (ok ? "成功" : "失败") + ": " + tokens.length + " tokens");
            return ok ? 1 : 0;
        } catch (Throwable t) {
            Log.w(TAG, "saveSessionKv failed: " + t.getMessage());
            com.aicodestudio.ide.ai.util.DebugLogger.log("KV", "saveSessionKv 失败: " + t);
        }
        return 0;
    }

    /** 加载当前会话 KV：指纹匹配则导入（增量 prefill 起点）；否则清缓存全量重建 */
    public synchronized void loadSessionKv() {
        try {
            if (appCtx == null) return;
            File kf = kvFile();
            File mf = kvMetaFile();
            com.aicodestudio.ide.ai.util.DebugLogger.log("KV", "loadSessionKv 开始: " + kf.getName()
                    + " exists=" + kf.exists());
            if (!kf.exists() || !mf.exists() || kf.length() == 0) {
                com.aicodestudio.ide.ai.util.DebugLogger.log("KV", "loadSessionKv 无KV文件，全量重建");
                clearKvCache();
                return;
            }
            // 指纹校验
            String fp = kvFingerprint();
            String savedFp = "";
            try {
                String metaStr = new String(
                        java.nio.file.Files.readAllBytes(mf.toPath()), "UTF-8");
                org.json.JSONObject jo = new org.json.JSONObject(metaStr);
                savedFp = jo.optString("fingerprint", "");
            } catch (Throwable ignored) {}
            if (fp == null || fp.isEmpty()) {
                com.aicodestudio.ide.ai.util.DebugLogger.log("KV",
                        "loadSessionKv: 模型指纹未就绪(加载中)，保留 KV 文件不丢弃");
                return;
            }
            if (!fp.equals(savedFp)) {
                Log.i(TAG, "loadSessionKv: 模型指纹不匹配，丢弃旧KV全量重建");
                com.aicodestudio.ide.ai.util.DebugLogger.log("KV", "loadSessionKv 指纹不匹配: 当前=" + fp
                        + " saved=" + savedFp + "，全量重建");
                clearKvCache();
                return;
            }
            // v4.3：用官方 llama_state_load_file，直接恢复 KV+tokens
            InferenceEngine engine = EngineManager.get();
            if (engine instanceof com.aicodestudio.ide.ai.engine.LlamaCppEngine) {
                com.aicodestudio.ide.ai.engine.LlamaCppEngine lengine =
                        (com.aicodestudio.ide.ai.engine.LlamaCppEngine) engine;
                int[] tokens = null;
                try {
                    tokens = lengine.loadSessionFile(kf.getAbsolutePath());
                } catch (Throwable nerr) {
                    com.aicodestudio.ide.ai.util.DebugLogger.log("KV",
                            "loadSessionKv native异常: " + nerr);
                }
                if (tokens != null && tokens.length > 0) {
                    lengine.applyLoadedTokens(tokens);
                    com.aicodestudio.ide.ai.util.DebugLogger.log("KV", "loadSessionKv 成功: "
                            + tokens.length + " tokens, nPast=" + lengine.nKvUsedTokens());
                } else {
                    String diag = "";
                    try {
                        java.io.FileInputStream fis = new java.io.FileInputStream(kf);
                        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(12);
                        bb.order(java.nio.ByteOrder.LITTLE_ENDIAN);
                        fis.getChannel().read(bb);
                        fis.close();
                        bb.flip();
                        diag = String.format(" magic=0x%08x ver=%d ntok=%d", bb.getInt(), bb.getInt(), bb.getInt());
                    } catch (Exception de) {}
                    com.aicodestudio.ide.ai.util.DebugLogger.log("KV", "loadSessionKv 失败: tokens=null/empty, fileLen="
                            + kf.length() + " nKvUsed=" + lengine.nKvUsedTokens() + diag);
                    clearKvCache();
                }
            } else {
                clearKvCache();
            }
        } catch (Throwable t) {
            Log.w(TAG, "loadSessionKv failed: " + t.getMessage());
            com.aicodestudio.ide.ai.util.DebugLogger.log("KV", "loadSessionKv 异常: " + t);
            clearKvCache();
        }
    }

    // ===== v1.152：历史 KV 查看器数据源 =====
    /** 列出所有会话 KV 文件：[文件名, 大小文本, 修改时间文本]，按时间倒序 */
    public java.util.List<String[]> listSessionKvFiles() {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        try {
            if (appCtx == null) return out;
            File dir = new File(appCtx.getFilesDir(), "chat_history");
            File[] files = dir.listFiles(new java.io.FilenameFilter() {
                @Override public boolean accept(File d, String name) {
                    return name.endsWith(".kv") && !name.endsWith(".kv.meta");
                }
            });
            if (files == null) return out;
            java.util.Arrays.sort(files, new java.util.Comparator<File>() {
                @Override public int compare(File a, File b) {
                    return Long.compare(b.lastModified(), a.lastModified());
                }
            });
            java.text.SimpleDateFormat sdf =
                    new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault());
            for (File f : files) {
                String size;
                long len = f.length();
                if (len > 1024 * 1024) {
                    size = String.format(java.util.Locale.getDefault(), "%.1f MB", len / (1024f * 1024f));
                } else {
                    size = String.format(java.util.Locale.getDefault(), "%.1f KB", len / 1024f);
                }
                // v1.153bb：把文件名（convKey.kv）映射成会话标题，KV 列表前台显示对话名
                String convKey = f.getName();
                if (convKey.endsWith(".kv")) convKey = convKey.substring(0, convKey.length() - 3);
                String title = convKey;
                try {
                    com.aicodestudio.ide.ai.core.ProjectSession ps =
                            com.aicodestudio.ide.ai.core.SessionManager.get(appCtx).getSession(convKey);
                    if (ps != null && ps.title != null && !ps.title.isEmpty()) {
                        title = ps.title;
                    }
                } catch (Throwable ignored) {}
                out.add(new String[]{f.getName(), size,
                        sdf.format(new java.util.Date(f.lastModified())), title});
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /** 删除单个会话 KV 文件（含 .meta） */
    public boolean deleteSessionKvFile(String fileName) {
        try {
            if (appCtx == null || fileName == null) return false;
            File dir = new File(appCtx.getFilesDir(), "chat_history");
            boolean ok = new File(dir, fileName).delete();
            new File(dir, fileName + ".meta").delete();
            return ok;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 清空所有会话 KV 文件（含 .meta），返回删除文件数 */
    public int deleteAllSessionKvFiles() {
        int n = 0;
        try {
            if (appCtx == null) return 0;
            File dir = new File(appCtx.getFilesDir(), "chat_history");
            File[] files = dir.listFiles(new java.io.FilenameFilter() {
                @Override public boolean accept(File d, String name) {
                    return name.endsWith(".kv") || name.endsWith(".kv.meta");
                }
            });
            if (files == null) return 0;
            for (File f : files) { if (f.delete()) n++; }
        } catch (Throwable ignored) {}
        return n;
    }

    /**
     * 会话 key：项目名_路径哈希（可读 + 精确识别，防不同路径同名冲突）。
     * 例：/.../projects/Weather → "Weather_a1b2c3d4"；空（场景A）→ "default"。
     */
    private static String historyKey(String projectPath) {
        if (projectPath == null || projectPath.isEmpty()) return "default";
        // v1.51aa：ChatPanel 新建对话传的是 chat_时间戳 key，直接用，不加 hash
        if (projectPath.startsWith("chat_")) return projectPath;
        String name = projectPath;
        int idx = Math.max(projectPath.lastIndexOf('/'), projectPath.lastIndexOf('\\'));
        if (idx >= 0) name = projectPath.substring(idx + 1);
        if (name.isEmpty()) name = "default";
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        String hash = Integer.toHexString(projectPath.hashCode() & 0x7fffffff);
        return safe + "_" + hash;
    }

    /** 当前会话的历史文件：filesDir/chat_history/{项目名_哈希}{模式后缀}.json（闲聊=_chat，工作=空） */
    private File historyFile() {
        return new File(new File(appCtx.getFilesDir(), "chat_history"),
                currentHistoryKey + modeSuffix + ".json");
    }

    /** v1.135：切换闲聊/工作任务模式——保存当前历史+KV → 切到另一套 → 加载对应 KV（指纹匹配增量，否则重建） */
    public synchronized void setChatMode(int mode) {
        if (mode != MODE_WORK && mode != MODE_CHAT) return;
        if (mode == chatMode) return;
        try {
            if (appCtx == null) return;
            persistHistory();                     // 先存当前模式的对话
            saveSessionKv();                     // v1.150：存当前模式 KV
            chatMode = mode;
            modeSuffix = (mode == MODE_CHAT) ? "_chat" : "";
            synchronized (messageHistory) {
                messageHistory.clear();
                messageHistory.add(new ChatMessage("system", ""));
            }
            planDoc = "";
            progressSummary = "";
            loadHistory();                       // 加载另一套历史
            loadSessionKv();                     // v1.150：加载另一套 KV（指纹不符自动全量重建）
            Log.i(TAG, "对话模式切换: " + (mode == MODE_CHAT ? "闲聊" : "工作任务"));
        } catch (Throwable t) {
            Log.w(TAG, "setChatMode failed", t);
        }
    }

    public int getChatMode() { return chatMode; }

    /** 持久化 user/assistant 消息到当前会话历史文件（跳过 system，system 由 injectSystemPrompt 动态生成） */
    private synchronized void persistHistory() {
        if (appCtx == null) return;
        try {
            JSONArray arr = new JSONArray();
            synchronized (messageHistory) {
                for (ChatMessage m : messageHistory) {
                    if ("system".equals(m.role)) continue;
                    JSONObject o = new JSONObject();
                    o.put("role", m.role);
                    o.put("content", m.content);
                    o.put("ts", m.ts);
                    arr.put(o);
                }
            }
            File f = historyFile();
            if (!f.getParentFile().exists()) f.getParentFile().mkdirs();
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
            try { fos.write(arr.toString().getBytes(StandardCharsets.UTF_8)); } finally { fos.close(); }
            // 持久化闲聊 L2 摘要（退出重开后直接读回，不重建，保证 KV 前缀一致）
            if (chatMode == MODE_CHAT && chatLongTermMemory != null && !chatLongTermMemory.isEmpty()) {
                File mf = new File(f.getParentFile(), currentHistoryKey + "_chatmemory.txt");
                try (java.io.FileOutputStream mfos = new java.io.FileOutputStream(mf)) {
                    mfos.write(chatLongTermMemory.getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "persistHistory failed", t);
        }
    }

    private synchronized void loadHistory() {
        if (appCtx == null) return;
        try {
            File f = historyFile();
            if (!f.exists()) return;
            byte[] buf = new byte[(int) Math.min(f.length(), 8 * 1024 * 1024)];
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            int n;
            try { n = fis.read(buf); } finally { fis.close(); }
            if (n <= 0) return;
            JSONArray arr = new JSONArray(new String(buf, 0, n, StandardCharsets.UTF_8));
            synchronized (messageHistory) {
                messageHistory.clear();
                messageHistory.add(new ChatMessage("system", ""));
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String role = o.optString("role", "user");
                    String content = o.optString("content", "");
                    long ts = o.optLong("ts", 0L); // v1.135：旧数据无 ts 用 0（排最前）
                    if (role.equals("system") || content.isEmpty()) continue;
                    // v1.51z：过滤 agent 循环残留的假摘要消息（"请整理摘要"/"这一轮做完了"）
                    if (role.equals("user") && (content.contains("请整理摘要")
                            || content.contains("这一轮做完了") || content.contains("请总结摘要"))) continue;
                    messageHistory.add(new ChatMessage(role, content, ts));
                }
            }
            Log.i(TAG, "已恢复聊天记录 " + arr.length() + " 条");
            // 闲聊模式：直接读回持久化的 L2 摘要，不从历史重建（保证 KV 前缀一致）
            if (chatMode == MODE_CHAT) {
                File mf = new File(f.getParentFile(), currentHistoryKey + "_chatmemory.txt");
                if (mf.exists()) {
                    byte[] mbuf = new byte[(int) Math.min(mf.length(), 64 * 1024)];
                    java.io.FileInputStream mfos = new java.io.FileInputStream(mf);
                    try { int rn = mfos.read(mbuf); if (rn > 0) chatLongTermMemory = new String(mbuf, 0, rn, StandardCharsets.UTF_8); } finally { mfos.close(); }
                    com.aicodestudio.ide.ai.util.DebugLogger.log("KV",
                            "闲聊L2摘要已读回: " + chatLongTermMemory.length() + "字");
                } else {
                    chatLongTermMemory = "";
                }
            }
            // v1.105：从恢复的历史里重新提取 L2/L3（工作模式）
            rebuildLayersFromHistory();
        } catch (Throwable t) {
            Log.w(TAG, "loadHistory failed", t);
        }
    }

    /** v1.105：从已加载的历史消息里重扫锚点，恢复 L2 规划文档 / L3 进度摘要 */
    private void rebuildLayersFromHistory() {
        planDoc = "";
        progressSummary = "";
        // 闲聊模式 chatLongTermMemory 已在 loadHistory 直接读回，不重建
        if (chatMode == MODE_CHAT) return;
        try {
            for (ChatMessage m : messageHistory) {
                if (!"assistant".equals(m.role) || m.content == null) continue;
                extractLayeredMarkers(m.content);
            }
        } catch (Throwable ignored) {}
    }
    
    public static synchronized AIChatManager getInstance() {
        if (instance == null) {
            instance = new AIChatManager();
        }
        return instance;
    }
    
    // ===== 核心方法：发送消息（Agent 循环：模型→命令→执行→回填→再生成）=====
    // v1.60：从 4 轮加到 10 轮，完整安卓项目需要多步命令
    private static final int MAX_AGENT_ROUNDS = 10;

    // v1.137：停止生成——UI 停止按钮调用，中断引擎生成并终止 Agent 循环
    private volatile boolean stopRequested = false;
    private volatile boolean isBackgroundSummary = false; // 总结轮：屏蔽UI流式回调
    // v1.153bf：本地流式期间 parser 已直接转发正文/代码给 UI，detector.onBody 不再重复转发
    private volatile boolean localStreamingActive = false;
    // ===== v2.0：L3 KV快照检索融合 =====
    /** 快照自增序号 */
    private int snapSeq = 0;
    /** 内存快照索引：snapId -> 元数据json（不加载KV） */
    private final java.util.Map<String, org.json.JSONObject> snapIndex = new java.util.concurrent.ConcurrentHashMap<>();
    /** 本轮是否触发了快照召回（融合后需清除旧快照） */
    private volatile boolean snapshotRecalledThisRound = false;
    /** 召回的快照ID */
    private volatile String recalledSnapId = null;
    private volatile HttpURLConnection cloudConn = null; // v1.137：云端流式连接，停止时 disconnect 中断

    // ===== v1.149 P0：会话状态机 =====
    /** 会话状态——所有会话操作（发送/重新生成/切换）据此校验，防止并发竞态 */
    public enum SessionState { IDLE, GENERATING, INTERRUPTED, ERROR }
    private volatile SessionState sessionState = SessionState.IDLE;
    /** 会话操作锁：生成进行中时拒绝新的发送/重新生成（切换会话除外，切换优先） */
    private volatile boolean sessionBusy = false;
    /** 生成序列号：切换会话/新一轮生成时 ++，使旧 Agent 循环的 finally 不覆盖新状态 */
    private volatile long genSeq = 0;

    public SessionState getSessionState() { return sessionState; }

    /** v1.153au：生成序列号快照（日志用） */
    public long getGenSeq() { return genSeq; }

    /** v1.153ag：让旧 Agent 生成循环的序列号失效（中途新建/切对话时调用），
     *  使旧循环后续的 token 回调/finally 不再覆盖新对话状态 */
    public void bumpGenSeq() { genSeq++; }

    /** 是否正在生成（供 UI 判断切换时是否需要完整遮罩流程） */
    public boolean isBusy() { return sessionBusy; }

    /** 停止当前生成：置标志 + 中断推理引擎（UI 停止按钮调用） */
    public void stopGeneration() {
        stopRequested = true;
        sessionState = SessionState.INTERRUPTED; // v1.149：即时置中断状态（runAgentLoop finally 会最终确认）
        try {
            HttpURLConnection c = cloudConn;
            cloudConn = null; // v1.147：断开后清引用，禁止后续复用
            if (c != null) c.disconnect(); // 中断云端 SSE 流
        } catch (Throwable ignored) {}
        try {
            InferenceEngine e = EngineManager.get();
            // v1.153ao：无条件 stop（nativeHandle==0 时 nStop 内部直接 return，安全）。
            // 之前依赖 isLoaded() 判定——若生成中途 handle 状态异常判定为 false，
            // abort 不会下发，native 会一直跑到自然结束，切换任务排队数十秒。
            if (e != null) e.stop(); // 中断本地推理（native abort，warm/采样点即停）
        } catch (Throwable ignored) {}
        Log.i(TAG, "stopGeneration: 生成服务已停止（云端断开 / 本地引擎 abort / Agent循环退出）");
    }

    /**
     * v1.146：重新生成辅助——取最后一条用户消息原文（ChatPanel 重发用）。
     * 删除最后整轮（半截 assistant / system / 工具结果尾部消息 + 最后一条 user），
     * 由 ChatPanel 重新 sendToAI，保证 UI 流式渲染完整初始化且历史不重复。
     */
    public synchronized String getLastUserMessage() {
        for (int i = messageHistory.size() - 1; i >= 0; i--) {
            ChatMessage m = messageHistory.get(i);
            if ("user".equals(m.role)) return m.content;
        }
        return null;
    }

    /** v1.146：删除最后一条 user 消息及其之后所有消息（半截 assistant/system/工具结果） */
    public synchronized void removeLastRound() {
        for (int i = messageHistory.size() - 1; i >= 0; i--) {
            ChatMessage m = messageHistory.get(i);
            messageHistory.remove(i);
            if ("user".equals(m.role)) break;
        }
        persistHistory();
    }

    // ===== v1.49 对话工作目录（场景A/B，取代旧的 boundToProject 绑定状态机）=====
    // 场景B：对话绑定了项目（欢迎页创建/打开项目后进入）→ workDir=项目根目录，
    //         系统提示词注入「当前工作目录+项目文件树」（每轮刷新，AI 不失忆）
    // 场景A：未绑定任何项目 → workDir=null，由 resolveWorkDir() 兜底到全局工作空间
    //         （AI Code Studio/projects），系统提示词只写「当前工作空间」
    private volatile String workDir = null;

    public void setWorkDir(String d) {
        workDir = (d == null || d.isEmpty()) ? null : d;
    }

    public String getWorkDir() {
        return workDir;
    }

    /** 解析当前对话应使用的命令工作目录：workDir → WorkspaceState 项目 → 全局工作空间 */
    private String resolveWorkDir() {
        if (workDir != null && !workDir.isEmpty()) return workDir;
        try {
            com.aicodestudio.ide.ai.core.WorkspaceState ws = com.aicodestudio.ide.ai.core.WorkspaceState.get();
            if (ws != null && ws.getProjectPath() != null && !ws.getProjectPath().isEmpty()) {
                return ws.getProjectPath();
            }
        } catch (Throwable ignored) {
        }
        // 场景A：全局工作空间（AI Code Studio/projects），AI 在此从零创建项目
        // （sCtx 为空时用 appCtx 兜底，保证任何入口都能解析到正确工作空间）
        android.content.Context ctx = sCtx != null ? sCtx : appCtx;
        if (ctx != null) {
            try {
                return com.aicodestudio.ide.ai.core.ProjectManager.getProjectsRoot(ctx).getAbsolutePath();
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    public void sendMessage(String userMessage) {
        com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                "AIChatManager.sendMessage enter: sessionBusy=" + sessionBusy
                + " stopRequested=" + stopRequested
                + " sessionState=" + sessionState
                + " currentHistoryKey=" + currentHistoryKey);
        if (responseListener == null) {
            Log.w(TAG, "No response listener set");
            com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                    "sendMessage abort: responseListener == null");
            return;
        }
        if (sessionBusy) { // v1.149：会话忙（生成中/切换中），拒绝并发发送，防多流叠加
            try { responseListener.onError("上一轮任务尚未结束，请稍候再发送。"); } catch (Throwable ignored) {}
            com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                    "sendMessage REJECTED: sessionBusy=true, onError sent");
            return;
        }
        stopRequested = false; // v1.137：新一轮生成重置停止标志
        final long mySeq = ++genSeq; // v1.149：本轮生成序列号，结束时校验防止旧循环覆盖新状态
        sessionBusy = true;
        sessionState = SessionState.GENERATING;
        com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                "sendMessage accepted: genSeq=" + mySeq + " sessionBusy=true");
        // v1.50d：系统注入项目目录名/包名（1.5B 无法可靠把中文需求翻译成英文项目名，
        // 实测会错用规则示例名 Calculator）。命中内置映射表时，把确定的名字附加到用户消息。
        // P2-1: 注入内容不拼进持久化用户消息（UI/历史/上下文不污染），存临时变量在 buildPromptFromHistory 里临时加
        pendingProjectInjection = injectProjectName(userMessage);
        messageHistory.add(new ChatMessage("user", userMessage));
        persistHistory();
        // v1.108: 每轮对话开新日志文件
        com.aicodestudio.ide.ai.util.DebugLogger.newRound(userMessage);
        
        executor.execute(new Runnable() {
            @Override
            public void run() {
                long t0 = System.currentTimeMillis();
                com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                        "sendMessage executor task START genSeq=" + mySeq);
                try {
                    // v2.0：L3快照检索——闲聊模式下，用户提问前检索历史快照
                    // 命中则加载快照KV（覆盖当前KV），在历史上下文基础上继续推理
                    snapshotRecalledThisRound = false;
                    recalledSnapId = null;
                    if (chatMode == MODE_CHAT && !snapIndex.isEmpty()) {
                        String hitId = searchSnapshot(userMessage);
                        if (hitId != null) {
                            loadSnapshotKv(hitId);
                        }
                    }
                    // v1.60：启用 Agent 循环——多轮执行命令 → 回填结果 → 继续下一步
                    runAgentLoop();
                    com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                            "sendMessage runAgentLoop done, elapsed=" + (System.currentTimeMillis() - t0) + "ms");
                    // v1.61：L4 滑动窗口管理——循环结束后截断历史，10 条（5 轮对话）
                    trimHistory(10);
                    persistHistory();
                    // v2.0：L3快照保存——闲聊模式下，每轮对话结束后保存KV快照（带标题）
                    if (chatMode == MODE_CHAT && !stopRequested) {
                        saveConversationSnapshot();
                    }
                    // v2.0：融合后处理——如果本轮触发了快照召回，保存新主KV并清除旧快照
                    if (snapshotRecalledThisRound) {
                        mergeAndCleanSnapshots();
                    }
                } catch (Throwable t) {
                    try { responseListener.onError("生成失败: " + t.getMessage()); } catch (Throwable ignored) {}
                    com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                            "sendMessage task ERROR: " + t.getMessage());
                } finally {
                    // v1.149：仅当仍是本轮时复位会话状态（切换会话后旧循环不得覆盖）
                    if (mySeq == genSeq) {
                        sessionState = stopRequested ? SessionState.INTERRUPTED : SessionState.IDLE;
                        sessionBusy = false;
                        com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                                "sendMessage task DONE: sessionBusy=false stopRequested=" + stopRequested
                                + " elapsed=" + (System.currentTimeMillis() - t0) + "ms");
                    } else {
                        com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                                "sendMessage task DONE but stale (mySeq=" + mySeq + " != genSeq=" + genSeq
                                + "), 不复位 sessionBusy");
                    }
                }
            }
        });
    }

    /** 中文项目类型 → 英文项目目录名（v1.50d 系统注入，避免 1.5B 自己定名错用示例名） */
    private static final String[][] PROJECT_NAME_MAP = {
        {"计算器", "Calculator"},
        {"番茄钟", "Pomodoro"},
        {"天气", "Weather"},
        {"手电筒", "Flashlight"},
        {"记账本", "Ledger"},
        {"待办", "Todo"},
        {"清单", "Todo"},
        {"闹钟", "Alarm"},
        {"时钟", "Clock"},
        {"计时", "Timer"},
        {"播放器", "Player"},
        {"音乐", "Music"},
        {"相机", "Camera"},
        {"相册", "Gallery"},
        {"笔记", "Notes"},
        {"备忘录", "Memo"},
        {"日历", "Calendar"},
        {"换算", "Converter"},
        {"单位", "Converter"},
        {"骰子", "Dice"},
        {"随机", "Dice"},
        {"计步", "Health"},
        {"健康", "Health"},
        {"字典", "Dictionary"},
        {"阅读", "Reader"},
        {"聊天", "Chat"},
        {"文件管理", "FileManager"},
        {"设置", "Settings"},
    };

    /** 命中返回英文项目名，否则 null（不注入，让模型自定） */
    private static String injectProjectName(String text) {
        if (text == null) return null;
        for (String[] kv : PROJECT_NAME_MAP) {
            if (text.contains(kv[0])) return kv[1];
        }
        return null;
    }

    // ===== v1.49 已彻底移除旧的系统侧意图识别 =====
    // 旧逻辑：tryAutoCreateProject（用户消息命中"写一个/创建XX" → 系统自动生成项目模板并绑定）
    // 已删除。原因（用户反馈）：
    //   ① 手动创建项目后进入对话，再提需求时系统又自动创建一次 → 目录下出现两套项目模板错乱；
    //   ② 1.5B 意图词误判率高，普通聊天（"你好/你叫什么名字"）也会被卷入项目逻辑。
    // 新逻辑：App 不做任何创建意图预判。AI 输出 shell 命令（mkdir/cat），系统只解析执行；
    //   场景A（无项目）AI 从零搭建；场景B（已有项目）AI 直接修改。聊天消息保留、不触发任何创建。

    /**
     * Agent 循环：调用模型拿到回复 → 若含 ```bash 命令块则逐条映射到 App 接口执行
     * → 把工具结果作为新消息回填 → 再调用模型，直到无命令块或达到轮次上限。
     * 流式输出保持：模型回复与执行状态都通过 onResponseChunk 实时显示。
     */
    private void runAgentLoop() {
        com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                "runAgentLoop START: stopRequested=" + stopRequested + " genSeq=" + genSeq
                + " mode=" + currentMode);
        try {
            responseListener.onResponseStart();

            String lastReply = "";
            rawFullAll.setLength(0);  // v1.91g：新对话清空原始完整累积
            String prevCommand = null;  // v1.60：防死循环——上一轮的命令
            int sameCommandCount = 0;   // 连续相同命令计数
            for (int round = 0; round < MAX_AGENT_ROUNDS; round++) {
                if (stopRequested) { // v1.137：用户点停止，立即终止 Agent 循环
                    Log.i(TAG, "Agent循环：用户请求停止");
                    com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                            "runAgentLoop break@round=" + round + ": stopRequested=true");
                    break;
                }
                // v1.116: 每轮打印当前阶段状态，便于调试
                String curStage = (planDoc == null || planDoc.trim().isEmpty()) ? "STAGE1" : "STAGE2/3";
                com.aicodestudio.ide.ai.util.DebugLogger.log("AGENT",
                        "[AGENT-STATE] round=" + round + " current_state=" + curStage
                        + " planDocLen=" + (planDoc == null ? 0 : planDoc.trim().length()));
                // v1.51k：每轮生成前刷新 L1/L2（含 [已创建文件] 清单）——
                // 原实现 injectSystemPrompt 只在循环开头跑一次，第 2 轮起模型看不到
                // 上一轮刚创建的文件，导致重复覆盖写已存在文件。每轮刷新后模型永远看到最新文件树。
                injectSystemPrompt();
                // v1.91：每轮新建流式工具识别器（清空上一轮即时执行结果）
                setupDetector();
                String reply = generateOnce();
                if (reply == null) return;  // 内部已 onError
                if (reply.trim().isEmpty()) {
                    // v1.51x：模型只输出了 think 块没出正文——提示用户，不静默结束
                    String tip = "\n\n（模型只输出了思考过程，未生成实际内容。可能需要调大最大 Token 或切换到云端模式）";
                    try { responseListener.onResponseChunk(tip); } catch (Throwable ignored) {}
                    lastReply = tip;
                    break;
                }
                lastReply = reply;
                // v1.153bi：去掉 assistant 回复末尾的 eos/结束标记。
                // 这些 token 是模型生成的结束符，不是回复正文——存进 messageHistory 后，
                // 下次 buildPromptFromHistory 把它当消息正文拼进 prompt，导致 savedPrompt 末尾多了 eos 文本，
                // 而 KV cache 里 eos 是模型生成 token（位置/计数和文本拼接差一个 token），
                // 增量 prefill 时 nPast 错位 → 模型没真正看到新 user 消息，继续讲上一个话题。
                reply = stripTrailingEos(reply);
                // v1.154b：assistant历史只存think之后的正式输出，think内容不入库
                // 否则上一轮think里复读的system文本会被下一轮prompt喂回去，恶性循环越复读越严重
                reply = stripThinkBlocks(reply).trim();
                // v1.152：关键修复——assistant 回复必须入 messageHistory。
                // 之前只存 user 消息、不存 assistant 回复，导致下一轮模型只看到连续两条 user，
                // 看不到自己上一轮说过什么 → 失忆/幻觉。
                messageHistory.add(new ChatMessage("assistant", reply));
                com.aicodestudio.ide.ai.util.DebugLogger.log("AGENT",
                        "assistant入库: " + reply.length() + "字符 (本轮messageHistory共" + messageHistory.size() + "条)");
                // v1.105：从本轮输出提取 L2 规划文档 / L3 进度摘要
                extractLayeredMarkers(reply);
                // 闲聊模式：回答完用户后，后台让 AI 自己判断本轮是否有值得记住的事实
                if (chatMode == MODE_CHAT && !stopRequested) {
                    try {
                        messageHistory.add(new ChatMessage("user",
                                "判断上一轮对话是否有值得长期记住的新事实（如用户姓名、兴趣、重要决定、关键信息）。\n"
                                + "如果有，输出要点列表（每行一条，以-开头），只记事实，禁止编造。\n"
                                + "如果只是寒暄、问候、没有实质内容，直接输出：- 无新事实，不要展开思考。"));
                        isBackgroundSummary = true;
                        String summaryReply = generateOnce();
                        isBackgroundSummary = false;
                        messageHistory.remove(messageHistory.size() - 1); // 移除临时 user
                        if (summaryReply != null && !summaryReply.isEmpty()) {
                            String summary = stripThinkBlocks(summaryReply).trim();
                            // 过滤"无新事实"占位
                            if (summary.length() > 5 && !summary.contains("无新事实")) {
                                chatLongTermMemory = chatLongTermMemory.isEmpty()
                                        ? summary : chatLongTermMemory + "\n" + summary;
                                if (chatLongTermMemory.length() > 2000) {
                                    chatLongTermMemory = chatLongTermMemory.substring(chatLongTermMemory.length() - 2000);
                                }
                                com.aicodestudio.ide.ai.util.DebugLogger.log("KV",
                                        "闲聊L2 AI总结已压入: 总长=" + chatLongTermMemory.length() + "字");
                                // L2变了，标记前缀失效（但保留kvTokenIds供saveSessionKv保存）
                                InferenceEngine e = EngineManager.get();
                                if (e instanceof com.aicodestudio.ide.ai.engine.LlamaCppEngine) {
                                    ((com.aicodestudio.ide.ai.engine.LlamaCppEngine) e).invalidateWarmPrefix();
                                }
                            }
                        }
                    } catch (Throwable t) {
                        isBackgroundSummary = false;
                        com.aicodestudio.ide.ai.util.DebugLogger.log("KV", "闲聊总结异常: " + t.getMessage());
                    }
                }
                // v1.91：先 flush 流末尾未闭合的命令块
                if (detector != null) detector.flush();
                // v1.124: 剥离think块后的正文，用于阶段判断
                String bodyForCheck = stripThinkBlocks(reply);
                // v1.109: 业务阶段锁——如果还没完成规划（planDoc 为空），但模型已经输出代码块/命令块，
                // 说明模型跳过了阶段1。记录警告，清空本轮命令不执行。（闲聊模式不锁）
                if (chatMode != MODE_CHAT
                        && (planDoc == null || planDoc.trim().isEmpty())
                        && bodyForCheck.contains("```")) {
                    com.aicodestudio.ide.ai.util.DebugLogger.log("STAGE",
                            "⚠️ 阶段锁：模型未完成规划就直接输出代码块（planDoc 为空），跳过本轮命令执行");
                    roundResults.clear(); // 不执行未规划的代码块
                }

                // v1.91：命令块已在流式过程中被 detector 即时识别并即时执行（roundResults 已收集）
                if (roundResults.isEmpty()) {
                    boolean justFinishedPlan = chatMode != MODE_CHAT
                            && isPlanComplete(bodyForCheck) && planDoc != null && !planDoc.isEmpty();
                    if (justFinishedPlan && round < MAX_AGENT_ROUNDS - 1) {
                        messageHistory.add(new ChatMessage("user", "阶段2：根据上面的项目开发文档文件清单，用shell命令创建空文件骨架。"));
                        Log.i(TAG, "Agent循环：规划完成，自动触发阶段2");
                        roundResults.clear();
                        round++;
                        continue;
                    }
                    break;
                }

                // v1.60：防死循环检测——连续 2 轮输出相同命令就停止
                String currentCmd = roundResults.get(0).raw;
                if (currentCmd != null && currentCmd.equals(prevCommand)) {
                    sameCommandCount++;
                    if (sameCommandCount >= 1) {  // 连续 2 轮相同（第2轮 + 第3轮）
                        Log.w(TAG, "Agent 循环：连续相同命令，终止");
                        String tip = "\n\n（检测到连续重复命令，自动停止，防止死循环）";
                        try { responseListener.onResponseChunk(tip); } catch (Throwable ignored) {}
                        break;
                    }
                } else {
                    sameCommandCount = 0;
                }
                prevCommand = currentCmd;

                if (round >= MAX_AGENT_ROUNDS - 1) break;

                // 逐条执行命令/落盘，结果合并为一条消息回填给模型（v1.51k）
                // v1.51k：原来每条结果单独 add user 消息，最近 6 条窗口被工具结果占满；
                // 合并为一条 + 末尾追加用户口吻"下一步"指令（与测试脚本一致），
                // 模型明确知道"命令已执行完、继续下一步开发"，不再自己猜。
                StringBuilder toolSb = new StringBuilder();
                for (AgentExecCLI.ExecResult er : roundResults) {
                    ShellCommandExecutor.ToolResult r = er.result;
                    // v1.38：工具结果截断，防止完整 stdout/stderr 撑爆 4096 上下文导致 decode 溢出
                    String toolBlock = r.toPromptBlock();
                    if (toolBlock.length() > 400) toolBlock = toolBlock.substring(0, 400) + "\n…(截断)";
                    toolSb.append("命令: ").append(er.raw).append("\n").append(toolBlock).append("\n\n");
                }
                if (toolSb.length() > 0) {
                    // v1.51p：回填改为 system 角色 + 附当前文件树（MiniCPM5 云端实测）：
                    //   - user/assistant 回填会被模型复述；system 角色回填模型正常继续
                    //   - 回填带目录树，模型每轮看到最新落盘，不再失忆/换目录重来
                    if (round < MAX_AGENT_ROUNDS - 1) {
                        try {
                            String tree;
                            if (workDir != null && !workDir.isEmpty()) {
                                // 场景B：绑定了项目，显示当前项目文件树
                                tree = com.aicodestudio.ide.ai.agent.AgentSystemPrompt.buildProjectTree(workDir);
                            } else {
                                // 场景A：空白对话，不扫描全局旧项目，只告诉AI当前工作空间是空的
                                tree = "（当前工作空间是空的，你刚才创建的文件就在工作空间根目录下）";
                            }
                            toolSb.append("\n\n当前文件树：\n").append(tree);
                        } catch (Throwable ignored) {
                        }
                        toolSb.append("\n命令已执行完成。请在这个文件树基础上继续下一步开发，每轮只做一个文件，不要重新创建已存在的文件。如果项目已全部完成，请回复\"项目完成\"。");
                    }
                    messageHistory.add(new ChatMessage("system", PREFIX_TOOL + "\n" + toolSb.toString().trim()));
                    persistHistory();
                }

                // v1.109：不再每轮强制"请整理摘要"——小模型会重新输出整个规划文档导致死循环。
                // 改为靠系统提示词要求 AI 每轮末尾自带【更新进度摘要】标记，extractLayeredMarkers 自动提取 L3。
                // L2/L3 已在 buildPromptFromHistory 里拼进 system 块。
                trimHistory(10);  // v1.61：10 条消息（5 轮对话）
            }

            try { responseListener.onResponseComplete(rawFullAll.toString()); } catch (Throwable ignored) {}
            // v1.151：不再每轮保存 KV——统一在"离开对话页/切对话/切模式"时保存，
            // 避免多轮对话后 KV 反复全量写盘；窗口大小由保存前 trimKvToWindow 控制
            Log.i(TAG, "Agent 循环结束，最终回复 " + rawFullAll.length() + " 字符");
        } catch (Throwable t) {
            Log.e(TAG, "Agent loop failed", t);
            try { responseListener.onError("对话流程异常: " + t.getMessage()); } catch (Throwable ignored) {}
        }
    }

    // ===== v1.91：流式工具识别与即时执行 =====

    /** 每轮生成前初始化流式工具识别器，并清空上一轮即时执行结果 */
    private void setupDetector() {
        roundResults.clear();
        detector = new StreamingToolDetector(new StreamingToolDetector.Listener() {
            @Override public void onBody(String text) {
                if (text != null && !text.isEmpty()) {
                    // v1.153bf：本地流式期间 parser.onText/onCodeContent 已直接转发 UI，
                    // 这里不重复转发，否则每个字/代码块内容被收两遍导致叠字。
                    // 云端模式没有直接转发路径，仍走这里唯一转发。
                    if (localStreamingActive) return;
                    try { responseListener.onResponseChunk(text); } catch (Throwable ignored) {}
                }
            }
            @Override public void onToolStart(StreamingToolDetector.ToolBlock block) {
                try { responseListener.onToolStart(block); } catch (Throwable ignored) {}
            }
            @Override public void onToolUpdate(String content) {
                try { responseListener.onToolUpdate(content); } catch (Throwable ignored) {}
            }
            @Override public void onToolEnd(StreamingToolDetector.ToolBlock block) {
                execToolBlock(block);
            }
        });
    }

    /** 命令块闭合时即时执行：写文件落盘 / 读文件 / 执行命令，并把结果收集回填模型 */
    private void execToolBlock(StreamingToolDetector.ToolBlock block) {
        if (chatMode == MODE_CHAT) return; // v1.135：闲聊模式不执行任何命令，AI 输出的代码块仅作文本显示
        boolean ok = false;
        String summary = "";
        try {
            String execText;
            if (block.type == StreamingToolDetector.TYPE_WRITE) {
                execText = "cat > " + block.path + "\n" + block.content;
            } else if (block.type == StreamingToolDetector.TYPE_READ) {
                execText = "cat " + block.path;
            } else {
                // EXEC：多行命令整体执行（content 可能是后续命令）
                execText = (block.content != null && block.content.length() > 0)
                        ? block.cmd + "\n" + block.content : block.cmd;
            }
            List<ShellCommandParser.Command> cmds = new ArrayList<>();
            ShellCommandParser.parseBlock(execText, cmds);
            String raw = block.type == StreamingToolDetector.TYPE_WRITE ? ("cat > " + block.path)
                        : (block.type == StreamingToolDetector.TYPE_READ ? ("cat " + block.path) : block.cmd);
            for (ShellCommandParser.Command c : cmds) {
                ShellCommandExecutor.ToolResult r = ShellCommandExecutor.execute(c);
                roundResults.add(new AgentExecCLI.ExecResult(raw, r));
                if (r.ok()) {
                    ok = true;
                    summary = (r.stdout != null && !r.stdout.isEmpty()) ? r.stdout : summary;
                } else {
                    ok = false;
                    summary = (r.stderr != null && !r.stderr.isEmpty()) ? r.stderr : "执行失败";
                }
            }
        } catch (Throwable t) {
            ok = false;
            summary = "执行异常: " + t.getMessage();
        }
        try { responseListener.onToolEnd(block, ok, summary); } catch (Throwable ignored) {}
    }

    /**
     * v1.51：让模型自己整理摘要（L3 模型自压缩）。
     * 临时追加"请总结"指令 → 模型输出总结 → 移除指令消息，只保留总结文本返回。
     * 不污染 L4 轮次（总结指令轮次不进最近窗口）。
     * v1.51b：摘要指令去掉格式模板（【已落盘】/【未落盘】）——1.5B 会把模板原样复述成空壳。
     * v1.51c：用户口吻明确"不用总结我发的消息，只总结你做过的"——1.5B 把系统提示词/用户消息
     * 当成总结对象复述进摘要，必须点明总结范围是"自己做过的事"。
     */
    private String requestModelSummary() {
        try {
            // v1.51k：摘要指令——用户口吻、无格式模板。实测对比：
            //   仅"总结你做过的"（v1.51c）→ 摘要漏"下一步"；
            //   "写了什么内容"（v1.51k 初版）→ 1.5B 把代码块/文件内容塞进摘要复述；
            //   现版：引导"已做/未做/下一步"但明确"不用列文件内容"，防复述。
            messageHistory.add(new ChatMessage("user",
                    "这一轮做完了。请整理摘要：你创建或修改了哪些文件、还有哪些没做、下一步做什么。不用列文件内容，不用总结我发的消息，只总结你做过的。事情"));
            persistHistory();
            String out = generateOnce();
            // 移除刚追加的指令（无论成功与否），保持 L4 轮次干净
            for (int i = messageHistory.size() - 1; i >= 0; i--) {
                ChatMessage m = messageHistory.get(i);
                if ("user".equals(m.role) && m.content != null
                        && (m.content.contains("请整理摘要") || m.content.contains("请总结摘要"))) {
                    messageHistory.remove(i);
                    break;
                }
            }
            if (out == null) return null;
            // 去掉可能的命令块/复述开头，只留总结正文（截断到 800 字符防膨胀）
            String s = out.replaceAll("(?s)```.*?```", "").trim();
            if (s.length() > 800) s = s.substring(0, 800) + "\n…(摘要截断)";
            if (s.isEmpty()) return null;
            return s;
        } catch (Throwable t) {
            Log.w(TAG, "requestModelSummary failed", t);
            return null;
        }
    }

    /** 更新 L3 摘要消息（[历史摘要]）：已有则替换为最新一份，否则追加到 system 区尾部 */
    private void upsertSummary(String summary) {
        String content = PREFIX_SUMMARY + "\n" + summary;
        for (int i = 0; i < messageHistory.size(); i++) {
            ChatMessage m = messageHistory.get(i);
            if ("system".equals(m.role) && m.content != null && m.content.startsWith(PREFIX_SUMMARY)) {
                messageHistory.set(i, new ChatMessage("system", content));
                persistHistory();
                return;
            }
        }
        // 无摘要消息：追加到最后一个 system 之后
        int idx = messageHistory.size();
        for (int i = 0; i < messageHistory.size(); i++) {
            if (!"system".equals(messageHistory.get(i).role)) { idx = i; break; }
        }
        messageHistory.add(idx, new ChatMessage("system", content));
        persistHistory();
    }

    /** 刷新系统提示词（L1）+ 项目记忆（L2，每次生成前调用，反映最新记忆文件） */
    private void injectSystemPrompt() {
        try {
            // 闲聊模式：L1 只有角色设定
            if (chatMode == MODE_CHAT) {
                String chatSp = "你是小星，一个友好、亲切的AI助手，用简洁自然的中文和用户聊天。你可以参考【长期记忆】里面记录的用户信息。";
                synchronized (messageHistory) {
                    for (int i = messageHistory.size() - 1; i >= 0; i--) {
                        if ("system".equals(messageHistory.get(i).role)) {
                            messageHistory.remove(i);
                        }
                    }
                    messageHistory.add(0, new ChatMessage("system", chatSp));
                }
                return;
            }
            // L1：系统提示词（index 0，物理唯一）。v1.49：按场景构建——
            //   场景B（对话绑定项目）：注入「当前工作目录 + 项目文件树」（每轮实时刷新，AI 不失忆）
            //   场景A（未绑定项目）：只写「当前工作空间」，AI 用 mkdir/cat 从零搭建
            //   先删除历史残留的所有 L1 system（排除 [项目记忆] [已创建文件] [历史摘要]），再在最前插入最新一份，
            //   保证「系统提示词永远只有最近的一份」，不会叠加旧版本
            boolean withProject = (workDir != null && !workDir.isEmpty());
            String sp = AgentSystemPrompt.build(appCtx, workDir, withProject);
            for (int i = messageHistory.size() - 1; i >= 0; i--) {
                if ("system".equals(messageHistory.get(i).role)
                        && !messageHistory.get(i).content.startsWith(PREFIX_MEM)
                        && !messageHistory.get(i).content.startsWith(PREFIX_FILES)
                        && !messageHistory.get(i).content.startsWith(PREFIX_SUMMARY)
                        && !messageHistory.get(i).content.startsWith(PREFIX_TOOL)) {
                    messageHistory.remove(i);
                }
            }
            messageHistory.add(0, new ChatMessage("system", sp));
            // L2a：场景A 新对话 = 空工作区，不扫描全局旧项目文件，避免AI看到之前Snake等残留项目
            if (!withProject) {
                String filesMsg = PREFIX_FILES + "\n当前工作空间是空的，没有任何已创建文件。";
                int filesIdx = -1;
                for (int i = 1; i < messageHistory.size(); i++) {
                    if ("system".equals(messageHistory.get(i).role)
                            && messageHistory.get(i).content.startsWith(PREFIX_FILES)) {
                        filesIdx = i;
                        break;
                    }
                }
                if (filesIdx >= 0) {
                    messageHistory.set(filesIdx, new ChatMessage("system", filesMsg));
                } else {
                    messageHistory.add(1, new ChatMessage("system", filesMsg));
                }
            } else {
                // 场景B：移除旧的场景A 文件清单消息
                for (int i = messageHistory.size() - 1; i >= 0; i--) {
                    if ("system".equals(messageHistory.get(i).role)
                            && messageHistory.get(i).content.startsWith(PREFIX_FILES)) {
                        messageHistory.remove(i);
                    }
                }
            }
            // L2b：项目记忆（index 1 附近，紧跟系统提示词/文件清单，空则不注入）
            String mem = readProjectMemory();
            if (mem == null || mem.isEmpty()) {
                // 移除旧的 L2 消息
                for (int i = messageHistory.size() - 1; i >= 0; i--) {
                    if ("system".equals(messageHistory.get(i).role)
                            && messageHistory.get(i).content.startsWith(PREFIX_MEM)) {
                        messageHistory.remove(i);
                    }
                }
            } else {
                String memMsg = PREFIX_MEM + "\n" + mem;
                int memIdx = -1;
                for (int i = 1; i < messageHistory.size(); i++) {
                    if ("system".equals(messageHistory.get(i).role)
                            && messageHistory.get(i).content.startsWith(PREFIX_MEM)) {
                        memIdx = i;
                        break;
                    }
                }
                if (memIdx >= 0) {
                    messageHistory.set(memIdx, new ChatMessage("system", memMsg));
                } else {
                    messageHistory.add(1, new ChatMessage("system", memMsg));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "inject system prompt failed", t);
        }
    }

    /** 读项目记忆文件 .aics/memory.md（aics mem 命令管理），返回去掉前缀的行文本 */
    private String readProjectMemory() {
        try {
            WorkspaceState ws = WorkspaceState.get();
            String root = ws != null ? ws.getProjectPath() : null;
            if (root == null || root.isEmpty()) return "";
            File f = new File(root, ".aics/memory.md");
            if (!f.isFile()) return "";
            String all = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder();
            for (String line : all.split("\n")) {
                String t = line.trim();
                if (t.isEmpty()) continue;
                if (t.startsWith("- ")) t = t.substring(2).trim();
                if (!t.isEmpty()) sb.append(t).append("\n");
            }
            return sb.toString().trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 截断过长的历史（保留最近 n 条，system 保留在首位） */
    private void trimHistory(int keep) {
        if (messageHistory.size() <= keep) return;
        List<ChatMessage> kept = new ArrayList<>();
        // P1-3: system 消息分类保留——L1/L2/L3 全保留，但 [工具结果] 只保留最近 1 条（防无限累积）
        boolean toolResultKept = false;
        // 先收集非工具结果的 system 消息
        for (ChatMessage m : messageHistory) {
            if ("system".equals(m.role) && m.content != null
                    && !m.content.startsWith(PREFIX_TOOL)) {
                kept.add(m);
            }
        }
        // 再找最后一条 [工具结果]
        for (int i = messageHistory.size() - 1; i >= 0 && !toolResultKept; i--) {
            ChatMessage m = messageHistory.get(i);
            if ("system".equals(m.role) && m.content != null
                    && m.content.startsWith(PREFIX_TOOL)) {
                kept.add(m);
                toolResultKept = true;
            }
        }
        // v1.124: 至少保留最近 4 条 user/assistant 消息，防止 system 消息占满导致对话断裂
        int room = Math.max(4, keep - kept.size());
        List<ChatMessage> tail = new ArrayList<>();
        for (int i = messageHistory.size() - 1; i >= 0 && tail.size() < room; i--) {
            ChatMessage m = messageHistory.get(i);
            if (!"system".equals(m.role)) tail.add(0, m);
        }
        kept.addAll(tail);
        messageHistory.clear();
        messageHistory.addAll(kept);
    }

    /** 调用一次模型（云端/本地），流式输出，返回完整文本；出错时 onError 并返回 null */
    // ===== v1.151：统一滑动窗口（prompt 侧）=====
    // prompt 历史段按 token 预算从后往前收（对齐 n_ctx），超出预算的旧消息从模型上下文丢弃
    // （JSON 消息记录仍完整保留，界面可回看；模型只看到窗口内——与 KV 滑动窗口一致）

    /** 历史窗口起始下标：从最近的 user/assistant 消息往前累加 token 估算，预算用尽即停 */
    private int historyWindowStartIndex(int firstNonSystemIdx) {
        // 闲聊模式：不截断，全部对话留在上下文里
        if (chatMode == MODE_CHAT) return firstNonSystemIdx;
        int budget = historyTokenBudget();
        int used = 0;
        int start = messageHistory.size();
        for (int j = messageHistory.size() - 1; j >= firstNonSystemIdx; j--) {
            ChatMessage m = messageHistory.get(j);
            if (!"user".equals(m.role) && !"assistant".equals(m.role)) continue;
            int t = estimateTokens(m.content);
            if (used + t > budget && used > 0) break; // 至少保留 1 条消息
            used += t;
            start = j;
        }
        com.aicodestudio.ide.ai.util.DebugLogger.log("KV", "窗口裁剪: budget=" + budget
                + "tok 总消息=" + messageHistory.size() + " 从index=" + start
                + " 起保留(前面" + (start - firstNonSystemIdx) + "条被裁掉)");
        return start;
    }

    /** 历史 token 预算 = n_ctx - 系统前缀(L1+L2+L3) - 生成预留与安全余量 */
    private int historyTokenBudget() {
        try {
            InferenceEngine e = EngineManager.get();
            if (e != null && e.available() && e.isLoaded()) {
                int total = e.getContextSize();     // 默认 4096
                int sysTok = e.getPrefixTokens();   // 系统前缀 PIN token 数
                return Math.max(256, total - sysTok - 1024);
            }
        } catch (Throwable ignored) {}
        return 2048;
    }

    /** 粗略 token 估算：中文 1 字符≈1 token，英文/代码/符号≈0.35 token/字符（保守） */
    private static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int cn = 0, other = 0;
        for (int k = 0; k < text.length(); k++) {
            char c = text.charAt(k);
            if (c >= 0x4E00 && c <= 0x9FFF) cn++;
            else other++;
        }
        return cn + (int) (other * 0.35f) + 1;
    }

    private String generateOnce() {        if (currentMode == MODE_CLOUD) {
            return generateCloud();
        }
        return generateLocal();
    }
    
    // ===== 云端API调用（流式 SSE，增强容错；返回完整文本给 AgentLoop）=====
    private String generateCloud() {
        HttpURLConnection conn = null;
        StringBuilder fullResponse = new StringBuilder();
        boolean timedOut = false;
        boolean inReasoningBlock = false;  // 是否在 reasoning_content 思考过程中（DeepSeek-R1 等）
        try {
            URL url = new URL(apiBaseUrl + "/chat/completions");
                    conn = (HttpURLConnection) url.openConnection();
                    cloudConn = conn; // v1.137：记录当前云端连接，供 stopGeneration 中断
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                    conn.setRequestProperty("Accept", "text/event-stream");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(30000);
                    conn.setReadTimeout(45000);  // 45秒无数据则超时，避免长时间卡住

                    // 构建请求体
                    JSONObject requestBody = new JSONObject();
                    requestBody.put("model", modelName);
                    requestBody.put("temperature", temperature);
                    requestBody.put("max_tokens", maxOutputTokens);
                    requestBody.put("stream", true);
                    // v1.120: 去掉 enable_thinking=false 限制，原始输出完全透传
                    // v1.51p：停用 1.5B GBNF 语法钳制——它只允许 ```bash 块，拒绝 MiniCPM5 的 XML 工具调用，会卡死
                    // requestBody.put("grammar", GBNF_PROMPT);
                    // v1.51j：采样参数实测（场景B Weather 各2次对比）——
                    //   A 默认(1.1)：11/5 写入稳定；B rp1.2+min_p0.1：出现"0 写入"（min_p 过度收窄采样，弃用）；
                    //   C rp1.3：首轮正好 3 个目标文件。结论：不加 min_p，repeat_penalty 取温和 1.15
                    //   （治极端循环复述，接近 llama.cpp 默认 1.1，不破坏正常命令输出）
                    requestBody.put("repeat_penalty", 1.15f);
                    // v1.124: 删掉 cache_prompt（llama.cpp server特有参数，主流OpenAI兼容API自动缓存，不需要传）

                    // v1.124: 云端和本地统一用 buildSystemContent() 拼 system（L1+L2+L3）
                    JSONArray messages = new JSONArray();
                    String sysContent = buildSystemContent();
                    if (sysContent != null && !sysContent.isEmpty()) {
                        JSONObject m = new JSONObject();
                        m.put("role", "system");
                        m.put("content", sysContent);
                        messages.put(m);
                    }
                    // L4：最近 RECENT_ROUNDS*2 条非 system 消息（跳过所有开头system消息）
                    int i = 0;
                    while (i < messageHistory.size() && "system".equals(messageHistory.get(i).role)) i++;
                    int start = Math.max(i, messageHistory.size() - RECENT_ROUNDS * 2);
                    for (int j = start; j < messageHistory.size(); j++) {
                        ChatMessage msg = messageHistory.get(j);
                        if (!"user".equals(msg.role) && !"assistant".equals(msg.role)) continue;
                        JSONObject m = new JSONObject();
                        m.put("role", msg.role);
                        m.put("content", msg.content);
                        messages.put(m);
                    }
                    requestBody.put("messages", messages);

                    // 发送请求
                    OutputStream os = conn.getOutputStream();
                    os.write(requestBody.toString().getBytes("UTF-8"));
                    os.flush();
                    os.close();

                    // 读取响应码
                    int responseCode = conn.getResponseCode();
                    if (responseCode != 200) {
                        String errorMsg = "API Error: HTTP " + responseCode;
                        try {
                            BufferedReader errorReader = new BufferedReader(
                                new InputStreamReader(conn.getErrorStream(), "UTF-8"));
                            StringBuilder errorSb = new StringBuilder();
                            String errorLine;
                            while ((errorLine = errorReader.readLine()) != null) {
                                errorSb.append(errorLine);
                            }
                            errorReader.close();
                            JSONObject errorJson = new JSONObject(errorSb.toString());
                            if (errorJson.has("error")) {
                                errorMsg = errorJson.getJSONObject("error").optString("message", errorMsg);
                            }
                        } catch (Exception ignored) {}
                        responseListener.onError(errorMsg);
                        return null;
                    }

                    // 流式读取 SSE 响应
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    String line;
                    int emptyLineCount = 0;
                    while (true) {
                        try {
                            line = reader.readLine();
                        } catch (java.net.SocketTimeoutException ste) {
                            // 读取超时：如果已经有内容，就结束流返回已生成的内容
                            Log.w(TAG, "SSE read timeout, returning partial response: " + fullResponse.length() + " chars");
                            timedOut = true;
                            break;
                        }
                        if (line == null) {
                            // 流结束
                            break;
                        }
                        line = line.trim();
                        if (line.isEmpty()) {
                            emptyLineCount++;
                            continue;
                        }
                        emptyLineCount = 0;
                        // v1.153an：云端 SSE 同样拦截——停止/切换后立即断开，残留数据不再进渲染管道
                        if (stopRequested) {
                            try { conn.disconnect(); } catch (Throwable ignored) {}
                            break;
                        }

                        // 提取 data 部分（兼容标准 SSE "data: xxx" 和非标准裸 JSON）
                        String data = line;
                        if (line.startsWith("data:")) {
                            data = line.substring(5).trim();
                        } else if (line.startsWith(":")) {
                            // SSE 注释/心跳，跳过
                            continue;
                        }

                        if ("[DONE]".equals(data)) {
                            break;
                        }

                        // 尝试解析 JSON
                        try {
                            JSONObject json = new JSONObject(data);
                            // 检查是否有错误字段（某些 API 在流中间返回错误）
                            if (json.has("error")) {
                                String errMsg = json.getJSONObject("error").optString("message", "API 错误");
                                responseListener.onError("云端 API 错误: " + errMsg);
                                return null;
                            }
                            JSONArray choices = json.optJSONArray("choices");
                            if (choices != null && choices.length() > 0) {
                                JSONObject choice = choices.getJSONObject(0);
                                // 兼容 delta（流式）和 message（非流式）两种格式
                                String content = "";
                                JSONObject delta = choice.optJSONObject("delta");
                                if (delta != null) {
                                    content = delta.optString("content", "");
                                    // 处理思考过程（reasoning_content 字段，如 DeepSeek-R1、Qwen3 等思考模型）
                                    // 把思考过程包装成 <think>...</think> 标签，供前端渲染成可收缩的思考过程卡片
                                    String reasoningContent = delta.optString("reasoning_content", "");
                                    if (reasoningContent != null && !reasoningContent.isEmpty()) {
                                        if (!inReasoningBlock) {
                                            // 思考过程开始
                                            String thinkStart = "<think>\n";
                                            fullResponse.append(thinkStart);
                                            try { responseListener.onResponseChunk(thinkStart); } catch (Throwable ignored) {}
                                            inReasoningBlock = true;
                                        }
                                        // 输出思考内容
                                        fullResponse.append(reasoningContent);
                                        try { responseListener.onResponseChunk(reasoningContent); } catch (Throwable ignored) {}
                                    } else if (inReasoningBlock) {
                                        // 思考过程结束（reasoning_content 为空，说明开始输出实际内容了）
                                        String thinkEnd = "\n</think>\n";
                                        fullResponse.append(thinkEnd);
                                        try { responseListener.onResponseChunk(thinkEnd); } catch (Throwable ignored) {}
                                        inReasoningBlock = false;
                                    }
                                } else {
                                    JSONObject message = choice.optJSONObject("message");
                                    if (message != null) {
                                        content = message.optString("content", "");
                                    }
                                }
                                if (content != null && !content.isEmpty()) {
                                    fullResponse.append(content);
                                    try {
                                        // v1.91：正文经流式工具识别器剥离命令块后转发渲染
                                        if (detector != null) detector.feed(content);
                                    } catch (Throwable t) {
                                        Log.e(TAG, "detector feed error", t);
                                    }
                                }
                                // 检查结束原因
                                String finishReason = choice.optString("finish_reason", "");
                                if ("length".equals(finishReason)) {
                                    String tip = "\n\n⚠️ 生成被截断（达到最大 Token 限制），"
                                            + "请在 AI 设置 → 云端模型 → 生成参数中调大\"最大 Token\"。";
                                    fullResponse.append(tip);
                                    try { responseListener.onResponseChunk(tip); } catch (Throwable ignored) {}
                                    break;
                                } else if ("stop".equals(finishReason)) {
                                    break;
                                }
                            }
                        } catch (Exception e) {
                            // 解析失败，跳过这一行（可能是心跳或非标准格式）
                            Log.d(TAG, "Skip non-JSON SSE line: " + line.substring(0, Math.min(80, line.length())));
                        }
                    }
                    // 流结束时如果还在思考过程中，关闭思考标签
                    if (inReasoningBlock) {
                        String thinkEnd = "\n</think>\n";
                        fullResponse.append(thinkEnd);
                        try { responseListener.onResponseChunk(thinkEnd); } catch (Throwable ignored) {}
                        inReasoningBlock = false;
                    }
                    reader.close();

                    String response = fullResponse.toString();
                    if (response.isEmpty()) {
                        responseListener.onError("云端 API 返回空响应。可能原因：API Key 无效、模型名称错误、或该模型不支持流式输出。请检查配置后重试。");
                        return null;
                    }
                    // v1.91g：累积云端原始完整回复（含 think 标签+代码块）用于历史保存
                    if (response.length() > 0) rawFullAll.append(response.trim());
                    if (timedOut) {
                        String tip = "\n\n⚠️ 响应超时（45秒无数据），已返回部分内容。网络不稳定或模型生成较慢时可能出现此情况。";
                        response += tip;
                        try { responseListener.onResponseChunk(tip); } catch (Throwable ignored) {}
                    }
                    return response;

                } catch (Exception e) {
                    Log.e(TAG, "Cloud API call failed", e);
                    // 如果已经有部分内容，就返回部分内容而不是报错
                    String partial = fullResponse.toString();
                    if (!partial.isEmpty()) {
                        String tip = "\n\n⚠️ 连接中断，已返回部分内容：" + e.getMessage();
                        partial += tip;
                        try { responseListener.onResponseChunk(tip); } catch (Throwable ignored) {}
                        return partial;
                    } else {
                        responseListener.onError("网络错误: " + e.getMessage());
                        return null;
                    }
                } finally {
                    cloudConn = null; // v1.137：连接已结束，清理引用
                    if (conn != null) {
                        conn.disconnect();
                    }
                }
    }
    
    // ===== 本地量化模型推理（直连 llama.cpp JNI；同步等待后返回完整文本给 AgentLoop）=====
    private String generateLocal() {
        com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                "generateLocal START: stopRequested=" + stopRequested + " genSeq=" + genSeq);
        try {
            // v1.153an：本轮生成序列号快照——停止/切换后 genSeq 变化，
            // token 回调据此丢弃残留 token（防止旧输出继续灌进渲染管道/新对话）
            final long localSeq = genSeq;
            // 语音引擎已改为懒加载（首次长按语音时才初始化，发热优化 B），
            // 本地大模型推理不再串行等待语音引擎
            InferenceEngine engine = EngineManager.get();
            if (!engine.available()) {
                responseListener.onError("推理引擎未就绪: " + engine.unavailableReason());
                com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                        "generateLocal abort: engine not available: " + engine.unavailableReason());
                return null;
            }
            if (!engine.isLoaded()) {
                responseListener.onError("未加载本地模型，请先在模型管理页面导入并加载GGUF模型");
                com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                        "generateLocal abort: engine not loaded");
                return null;
            }

            // 模型就绪后补一次 KV 导入（仅一次，避免同会话内重复加载覆盖内存状态）
            if (!kvLoadedThisSession) {
                try { loadSessionKv(); } catch (Throwable ignored) {}
                kvLoadedThisSession = true;
            }

            Log.i(TAG, "本地模型推理开始，已加载: " + engine.loadedPath());

            // v1.46：改为纯代码层上下文管理——每次发消息整体重建 prompt（L1 系统提示词 + L2 记忆 +
            // L3 压缩摘要 + 最近轮次 + 当前用户消息），不依赖底层 KV 缓存前缀预热（warmSystemPrefix 不再调用）
            // v1.129：但系统前缀的 PIN 锁定必须接回——否则 KV 写满时 trimContext 从 slot 0 开始淘汰，
            // 系统提示词（恰在最前）会在第一轮就被挤掉。这里用 system 纯文本预热一次，
            // warmSystemPrefix 内部会算出它的 token 数并 nSetPrefixKeep；前缀不变时直接命中缓存，不重复 prefill。
            try {
                String sysContent = buildSystemContent();
                if (sysContent != null && !sysContent.isEmpty()) {
                    // v1.133：用 chat template 包装系统段，使其与完整 prompt 开头逐字一致，
                    // generateStream 才能用 prompt.startsWith(warmPrefixText) 命中增量 KV 复用。
                    String sysSegment = sysContent;
                    try {
                        String wrapped = engine.applyChatTemplate(
                                new String[]{"system"}, new String[]{sysContent}, false);
                        if (wrapped != null && !wrapped.isEmpty()) sysSegment = wrapped;
                    } catch (Throwable ignored) {}
                    long tw = System.currentTimeMillis();
                    com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                            "generateLocal warmSystemPrefix call, sysLen=" + sysContent.length()
                            + " sysSegmentLen=" + sysSegment.length());
                    engine.warmSystemPrefix(sysSegment);
                    com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                            "generateLocal warmSystemPrefix done, elapsed="
                            + (System.currentTimeMillis() - tw) + "ms");
                }
            } catch (Throwable pinIgnored) {
                com.aicodestudio.ide.ai.util.DebugLogger.log("AGENT",
                        "warmSystemPrefix(skip): " + pinIgnored.getMessage());
            }

            // v1.153bd：warmSystemPrefix 是 17 秒级长 native 操作，期间用户可能点停止/切会话。
            // 它跑完后必须立刻检查 stopRequested——不检查就会继续 build prompt 并调 nGenerate，
            // 开一个新的全量 prefill（3389 token 要 60 秒），把切换任务卡死。
            if (stopRequested) {
                com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                        "generateLocal: warmSystemPrefix 后检测到 stopRequested=true，中断本轮生成");
                return null;
            }

            // 构建 prompt
            String prompt = buildPromptFromHistory();
            com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                    "generateLocal prompt built: len=" + prompt.length());
            lastBuiltPrompt = prompt; // v1.150：记录本轮完整 prompt，KV 导出时写入 meta 供增量判断

            // 配置生成参数
            InferenceEngine.GenOptions opts = new InferenceEngine.GenOptions();
            // v1.132：动态预算生成长度——不写死，按 KV 实际占用算。
            // 总盘子 n_ctx，系统前缀 PIN 占 sysTokens（L1+L2+L3），预留一段给最近2轮历史，
            // 剩下的空间给生成。系统提示词越长，自动给生成越少；短则放宽（写文件不被截断）。
            // 终止顺序：EOS 自然停 > 重复检测主动停 > 这里的预算上限硬停。
            int dynamicMax;
            try {
                int total = engine.getContextSize();
                int sysTokens = engine.getPrefixTokens();
                int historyReserve = 1024;          // 给 L4 最近2轮留的空间
                int budget = total - sysTokens - historyReserve;
                dynamicMax = Math.max(384, Math.min(budget, 1792)); // 跑命令≥384，写文件≤1792
                com.aicodestudio.ide.ai.util.DebugLogger.log("AGENT",
                    "动态预算: ctx=" + total + " sysTok=" + sysTokens
                    + " 预生成maxTokens=" + dynamicMax);
            } catch (Throwable ignored) {
                dynamicMax = maxOutputTokens > 0 ? Math.min(maxOutputTokens, 1024) : 512;
            }
            opts.maxTokens = dynamicMax;
            opts.temperature = temperature;
            opts.topK = 40;
            opts.topP = 0.95f;
            // v1.109f: 回退 repeat_penalty 到 config 默认（1.1）。
            // rp=1.3 太激进，惩罚系统提示词里的高频词（文件/代码/项目），
            // 导致模型不输出规划文档、直接甩代码。v11.39 用 1.1 基本正确。
            opts.repeatPenalty = AppConfig.getInstance(appCtx).getRepeatPenalty();

            final StringBuilder fullResponse = new StringBuilder();
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            final String[] result = {null};
            final boolean[] failed = {false};

            // v1.124: think内容转发给ChatPanel渲染，但不喂detector；超长强制退出think
            final StringBuilder visibleResponse = new StringBuilder();
            long tg = System.currentTimeMillis();
            // v1.153bd：进 nGenerate 前最后一道中断检查——build prompt/动态预算期间也可能被停止
            if (stopRequested) {
                com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                        "generateLocal: nGenerate 前检测到 stopRequested=true，放弃本轮生成");
                return null;
            }
            com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                    "generateLocal -> engine.generateStream call: promptLen=" + prompt.length()
                    + " maxTokens=" + opts.maxTokens + " mode=" + currentMode
                    + " stopRequested=" + stopRequested);
            // v1.153bf：标记本地流式进行中——detector.onBody 不重复转发（parser 已直接转发）
            localStreamingActive = true;
            engine.generateStream(prompt, opts, new InferenceEngine.TokenCallback() {
                // think 状态机：v1.123 prompt预填了<think>\n，模型输出时已在think块内
                // v1.124: 用UnifiedStreamParser统一处理think/code块（初始在think块内）
                private com.aicodestudio.ide.ai.agent.UnifiedStreamParser parser =
                    new com.aicodestudio.ide.ai.agent.UnifiedStreamParser(new com.aicodestudio.ide.ai.agent.UnifiedStreamParser.Listener() {
                        @Override public void onText(String text) {
                            visibleResponse.append(text);
                            // v1.153bf 叠字修复：本地 UnifiedStreamParser 已把正文与代码块分离，
                            // 正文直接转发给 UI 即可；不再喂 detector——否则 detector.onBody 会
                            // 把同一段正文再回调一次 responseListener.onResponseChunk，导致 ChatPanel
                            // 每个字收到两遍、textBuf 逐字翻倍（“系系统统要求要求”叠字）。
                            // detector 仍通过 onCodeContent（代码块内容）识别 shell 工具块。
                            if (!isBackgroundSummary) try { responseListener.onResponseChunk(text); } catch (Throwable ignored) {}
                        }
                        @Override public void onThinkStart() {
                            visibleResponse.append("<think>\n");
                            if (!isBackgroundSummary) try { responseListener.onResponseChunk("<think>\n"); } catch (Throwable ignored) {}
                        }
                        @Override public void onThinkContent(String text) {
                            visibleResponse.append(text);
                            if (!isBackgroundSummary) try { responseListener.onResponseChunk(text); } catch (Throwable ignored) {}
                        }
                        @Override public void onThinkEnd() {
                            visibleResponse.append("</think>\n");
                            if (!isBackgroundSummary) try { responseListener.onResponseChunk("</think>\n"); } catch (Throwable ignored) {}
                        }
                        @Override public void onCodeStart(String lang) {
                            visibleResponse.append("```").append(lang).append("\n");
                            // v1.136：代码围栏即时转发，UI 立即创建代码框（普通代码块）或工具卡片（shell块由detector处理）
                            try { responseListener.onResponseChunk("```" + lang + "\n"); } catch (Throwable ignored) {}
                        }
                        @Override public void onCodeContent(String text) {
                            visibleResponse.append(text);
                            // v1.153bf：代码内容直接转发 UI（ChatPanel streamParser 处于 CODE 态会进代码框）；
                            // detector.feed 仍喂入以识别 shell 命令并执行，但 onBody 被 localStreamingActive 抑制不重复转发。
                            try { if (detector != null) detector.feed(text); } catch (Throwable ignored) {}
                            if (!isBackgroundSummary) try { responseListener.onResponseChunk(text); } catch (Throwable ignored) {}
                        }
                        @Override public void onCodeEnd() {
                            visibleResponse.append("```\n");
                            try { responseListener.onResponseChunk("```\n"); } catch (Throwable ignored) {}
                        }
                    }, false); // startInThink=false：普通模型不输出think标签时直接当正文；思考型模型输出<think>时parser自动识别

                private void feedBody(String body) {
                    if (body == null || body.isEmpty()) return;
                    // v1.153an：停止/切换后丢弃残留 token——不进 detector、不转发 UI
                    if (stopRequested || localSeq != genSeq) return;
                    fullResponse.append(body);
                    parser.feed(body);
                }

                @Override
                public void onToken(String piece) {
                    if (piece == null || piece.isEmpty()) return;
                    com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                            "generateStream first-token elapsed=" + (System.currentTimeMillis() - tg) + "ms"
                            + " pieceLen=" + piece.length());
                    // v1.128 修复：只调 feedBody(piece)——它内部已 fullResponse.append(body) 并喂 parser；
                    // 原先这里多 append 一次 piece，导致 fullResponse/rawFullAll 逐字符翻倍，
                    // 表现为“实时渲染正常、切到原始模式后每个字重复两遍”。
                    feedBody(piece);
                }

                @Override
                public void onComplete(String fullText) {
                    localStreamingActive = false;
                    com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                            "generateStream onComplete: elapsed=" + (System.currentTimeMillis() - tg)
                            + "ms fullTextLen=" + (fullText == null ? 0 : fullText.length()));
                    // v1.141：流结束 flush 解析器——把残留尾字符转发给 UI、
                    // 未闭合的 think 块强制收尾（否则思考卡片不关闭、尾部内容丢失）
                    try { parser.flush(); } catch (Throwable ignored) {}
                    // v1.122: 如果模型只输出了 </think> 没输出 <think>，自动补开始标签
                    String vis = visibleResponse.toString();
                    if (vis.contains("</think>") && !vis.contains("<think>")) {
                        vis = "<think>\n" + vis;
                    }
                    result[0] = vis.trim();
                    // v1.91g：累积原始完整回复（含 think+代码块+命令块）用于历史保存
                    if (fullResponse.length() > 0) rawFullAll.append(fullResponse.toString().trim());
                    // v1.108: 记录完整输出 + 阶段检测
                    com.aicodestudio.ide.ai.util.DebugLogger.dumpOutput(rawFullAll.toString());
                    String out = rawFullAll.toString();
                    if (out.contains("【文档开发完成】")) {
                        com.aicodestudio.ide.ai.util.DebugLogger.log("STAGE", "检测到【文档开发完成】标记");
                    }
                    if (out.contains("【更新进度摘要】")) {
                        com.aicodestudio.ide.ai.util.DebugLogger.log("STAGE", "检测到【更新进度摘要】标记");
                    }
                    latch.countDown();
                }

                @Override
                public void onError(String message) {
                    localStreamingActive = false;
                    com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                            "generateStream onError: elapsed=" + (System.currentTimeMillis() - tg)
                            + "ms msg=" + message);
                    failed[0] = true;
                    result[0] = message;
                    latch.countDown();
                }
            });

            try {
                latch.await(3, java.util.concurrent.TimeUnit.MINUTES);  // P2-4: 3分钟超时，防 native 卡死静默等待
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                responseListener.onError("本地推理被中断");
                return null;
            }

            if (failed[0]) {
                Log.e(TAG, "本地模型推理错误: " + result[0]);
                responseListener.onError("本地推理错误: " + result[0]);
                return null;
            }

            Log.i(TAG, "本地模型推理完成，生成 " + result[0].length() + " 字符");
            return result[0];
        } catch (Exception e) {
            Log.e(TAG, "Local inference failed", e);
            responseListener.onError("本地推理错误: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 构建 prompt。v1.124: 优先用模型自带chat template，失败回退手动拼ChatML。
     */
    /** v1.153bi：去掉 assistant 回复末尾的特殊 token 标记（模型 eos/控制符，不是正文）。
     *  不硬编码具体 token 字符串——任何 <...> 或 〈...〉 包裹的短标记(<=40字符)都视为特殊 token。 */
    private static String stripTrailingEos(String s) {
        if (s == null) return "";
        String trimmed = s.trim();
        // 循环去掉末尾的特殊 token 标记（可能连续多个）
        boolean changed = true;
        while (changed) {
            changed = false;
            // 匹配末尾的 <...> 或 〈...〉 或 |...| 形式的短标记
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(<[^<>]{1,40}>|〈[^〈〉]{1,40}〉)$")
                .matcher(trimmed);
            if (m.find()) {
                trimmed = trimmed.substring(0, m.start()).trim();
                changed = true;
            }
        }
        return trimmed;
    }

    private String buildPromptFromHistory() {
        return buildPromptFromHistory(true);
    }

    /**
     * v1.153bf：addAssistant=false 时末尾不加 <|im_start|>assistant\n<think>\n。
     * 保存 KV 时必须用 false——否则 savedPrompt 带了 assistant 开头，下次加新 user 消息后
     * 新 prompt 不以 savedPrompt 开头（新消息插在 assistant 标记之前），startsWith 判断失败，
     * 又走全量 prefill（77 秒）。存到"上一条消息结束"为止，下次新 prompt 正好以它为前缀。
     */
    private String buildPromptFromHistory(boolean addAssistant) {
        final String IM_START = "<|im_start|>";
        final String IM_END = "<|im_end|>";

        // v1.124: 优先用模型自带chat template
        InferenceEngine engine = EngineManager.get();
        if (engine != null && engine.available() && engine.isLoaded()) {
            try {
                // 收集messages
                java.util.List<String> roles = new java.util.ArrayList<>();
                java.util.List<String> contents = new java.util.ArrayList<>();
                String sysContent = buildSystemContent();
                if (sysContent != null && !sysContent.isEmpty()) {
                    roles.add("system");
                    contents.add(sysContent);
                }
                int i = 0;
                while (i < messageHistory.size() && "system".equals(messageHistory.get(i).role)) i++;
                int startIdx = historyWindowStartIndex(i); // v1.151：token 预算窗口（对齐 n_ctx）
                for (int j = startIdx; j < messageHistory.size(); j++) {
                    ChatMessage msg = messageHistory.get(j);
                    if (!"user".equals(msg.role) && !"assistant".equals(msg.role)) continue;
                    roles.add(msg.role);
                    contents.add(msg.content);
                }
                String[] roleArr = roles.toArray(new String[0]);
                String[] contentArr = contents.toArray(new String[0]);
                String prompt = engine.applyChatTemplate(roleArr, contentArr, addAssistant);
                if (prompt != null && !prompt.isEmpty()) {
                    Log.i(TAG, "使用模型chat template，prompt长度=" + prompt.length()
                            + " addAssistant=" + addAssistant);
                    return prompt;
                }
            } catch (Throwable t) {
                Log.w(TAG, "chat template失败，回退手动拼", t);
            }
        }

        // 回退：手动拼ChatML
        StringBuilder sb = new StringBuilder();
        sb.append("<s>");
        sb.append(buildSystemPrefix());
        int i = 0;
        while (i < messageHistory.size() && "system".equals(messageHistory.get(i).role)) i++;
        int startIdx = historyWindowStartIndex(i); // v1.151：token 预算窗口（对齐 n_ctx）
        for (int j = startIdx; j < messageHistory.size(); j++) {
            ChatMessage msg = messageHistory.get(j);
            String role = msg.role;
            if (!"user".equals(role) && !"assistant".equals(role)) continue;
            sb.append(IM_START).append(role).append("\n");
            sb.append(msg.content).append(IM_END).append("\n");
        }
        if (addAssistant) {
            sb.append(IM_START).append("assistant\n");
        }

        // 超长截断
        final int MAX_PROMPT_CHARS = 7500;
        if (sb.length() > MAX_PROMPT_CHARS) {
            String kept = sb.substring(0, MAX_PROMPT_CHARS);
            int cut = kept.lastIndexOf('\n');
            if (cut > 0) kept = kept.substring(0, cut);
            sb = new StringBuilder(kept).append("\n").append(IM_START).append("assistant\n");
            Log.w(TAG, "prompt 超长兜底截断至 " + MAX_PROMPT_CHARS + " 字符");
        }
        // v1.108: dump 最终拼接的 prompt
        String finalPrompt = sb.toString();
        com.aicodestudio.ide.ai.util.DebugLogger.log("AGENT",
                "L2 planDoc len=" + (planDoc == null ? 0 : planDoc.trim().length())
                + ", L3 progressSummary len=" + (progressSummary == null ? 0 : progressSummary.trim().length())
                + ", prompt total=" + finalPrompt.length());
        com.aicodestudio.ide.ai.util.DebugLogger.dumpPrompt(finalPrompt);
        return finalPrompt;
    }
    // v1.51：L3 每轮都压缩（阈值 0），摘要由模型自己整理（系统每轮执行完发"请整理摘要"，
    // 模型输出【已落盘】【未落盘】完整状态）；L4 窗口缩到 3 轮（用户实测 8 轮上下文太大）。
    private static final int SUMMARY_THRESHOLD = 0;    // 每轮启用摘要压缩
    private static final int RECENT_ROUNDS     = 2;    // v1.105：L4 只保留最近 2 轮原文（规划文档+进度摘要已在 L2/L3）
    private static final int MIN_RECENT_ROUNDS = 2;    // 上下文压力大时最少保留 2 轮
    private static final int SOFT_WINDOW_CHARS = 3000; // 软窗口字符阈值（前缀+摘要超此值则减轮）
    private static final String PREFIX_SUMMARY = "## 对话历史摘要";
    private String pendingProjectInjection = null;  // P2-1: 临时项目名注入（不持久化到用户消息） // L3 摘要消息标记（system 角色）

    // v1.105：四层 KV 分层——
    //   L1 固定系统提示词（buildSystemPrefix，永不变）
    //   L2 开发规划文档（第一轮 AI 输出，检测【文档开发完成】后提取，常驻）
    //   L3 最新进度摘要（每轮 AI 输出，检测【更新进度摘要】后覆盖替换）
    //   L4 对话原文滑动窗口（只留最近 RECENT_ROUNDS=2 轮）
    private String planDoc = "";           // L2：开发规划文档
    private String progressSummary = "";   // L3：最新进度摘要（覆盖式）
    private String chatLongTermMemory = ""; // 闲聊 L2：累积对话记录（只追加，截断保留最新2000字）
    private boolean kvLoadedThisSession = false; // 当前会话是否已补加载过KV

    /**
     * v1.105：从一轮 assistant 输出里提取分层标记：
     *  - 【文档开发完成】→ 标记之前全文存入 L2 planDoc（常驻）
     *  - 【更新进度摘要】→ 标记之前的摘要存入 L3 progressSummary（覆盖式）
     * 提取在本轮生成结束后做，避免半截标记误提取。
     */
    private void extractLayeredMarkers(String text) {
        if (text == null || text.isEmpty()) return;
        // v1.124: 先剥离 <think>...</think> 块，防止思考内容里的标记被误提取
        String body = stripThinkBlocks(text);
        // L2：开发规划文档（模糊匹配括号变体）
        java.util.regex.Matcher mPlan = java.util.regex.Pattern.compile(
                "[【\\[（(]\\s*文档开发完成\\s*[】\\])）]").matcher(body);
        if (mPlan.find()) {
            String doc = body.substring(0, mPlan.start()).trim();
            if (doc.length() > 20 && !doc.equals(planDoc)) {
                planDoc = doc;
                Log.i(TAG, "L2 开发规划文档已提取: " + doc.length() + " 字符");
            }
        }
        // L3：进度摘要（覆盖）
        java.util.regex.Matcher mSum = java.util.regex.Pattern.compile(
                "[【\\[（(]\\s*更新进度摘要\\s*[】\\])）]").matcher(body);
        if (mSum.find()) {
            String before = body.substring(0, mSum.start());
            int start = before.lastIndexOf("✅");
            String sum = (start >= 0 ? before.substring(start) : before).trim();
            if (sum.length() > 5) {
                progressSummary = sum;
                Log.i(TAG, "L3 进度摘要已更新: " + sum.length() + " 字符");
            }
        }
    }

    /** v1.124: 剥离所有 <think>...</think> 块，只返回正文 */
    private String stripThinkBlocks(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder();
        int pos = 0;
        while (pos < text.length()) {
            int open = text.indexOf("<think>", pos);
            if (open < 0) { sb.append(text.substring(pos)); break; }
            sb.append(text, pos, open);
            int close = text.indexOf("</think>", open + 7);
            if (close < 0) break; // 未闭合的think块，丢弃后续
            pos = close + 8;
        }
        return sb.toString();
    }

    /** v1.124: 模糊匹配"文档开发完成"标记，支持【】[]（）等括号变体 */
    private boolean isPlanComplete(String text) {
        if (text == null) return false;
        return text.matches("(?s).*[【\\[（(]\\s*文档开发完成\\s*[】\\])）].*");
    }

    /**
     * 生成历史对话摘要（L3 层）。
     * v1.51 优先返回「模型自整理摘要」（[历史摘要] system 消息，由 runAgentLoop 每轮
     * 执行完后发"请整理摘要"让模型生成，信息完整、每轮更新）；
     * 摘要缺失时降级为系统截断兜底（防止模型摘要失败时上下文爆炸）。
     */
    private String buildHistorySummary() {
        try {
            // 优先：模型自整理摘要
            for (int j = 0; j < messageHistory.size(); j++) {
                ChatMessage m = messageHistory.get(j);
                if ("system".equals(m.role) && m.content != null && m.content.startsWith(PREFIX_SUMMARY)) {
                    String s = m.content.substring(PREFIX_SUMMARY.length()).trim();
                    if (!s.isEmpty()) return s;
                    break;
                }
            }
            // 兜底：系统截断拼接（仅当模型摘要缺失且轮次超窗时）
            int i = 0;
            while (i < messageHistory.size() && "system".equals(messageHistory.get(i).role)) i++;
            int rounds = 0;
            for (int j = i; j < messageHistory.size(); j++) {
                if ("user".equals(messageHistory.get(j).role)) rounds++;
            }
            if (rounds <= SUMMARY_THRESHOLD) return ""; // 未超窗，无需压缩
            int end = messageHistory.size() - RECENT_ROUNDS * 2; // 保留最近 3 轮原文
            if (end <= i) return "";
            StringBuilder sb = new StringBuilder();
            sb.append("【历史对话摘要（旧对话已压缩，重要细节请看近期对话）】\n");
            int lines = 0;
            for (int j = i; j < end; j++) {
                ChatMessage m = messageHistory.get(j);
                if (!"user".equals(m.role) && !"assistant".equals(m.role)) continue;
                String body = m.content == null ? "" : m.content.replace('\n', ' ').trim();
                if (body.length() > 80) body = body.substring(0, 80) + "…";
                sb.append(body).append("\n");
                if (++lines >= 60) break; // 摘要行数上限，防撑爆上下文
            }
            return sb.toString().trim();
        } catch (Throwable t) {
            Log.w(TAG, "buildHistorySummary failed", t);
            return "";
        }
    }

    /**
     * 构建系统前缀（L1 系统提示词 + L2 项目记忆，chat template 格式）。
     * v1.51：排除 L3 摘要消息（[历史摘要]），摘要由 buildHistorySummary 单独放 L3 位置。
     * v1.52：合并所有 system 内容到一个 ChatML system 块，避免多 system 块导致模型复述。
     */
    private String buildSystemPrefix() {
        StringBuilder sb = new StringBuilder();
        final String IM_START = "<|im_start|>";
        final String IM_END = "<|im_end|>";
        String content = buildSystemContent();
        if (!content.isEmpty()) {
            sb.append(IM_START).append("system").append("\n");
            sb.append(content).append(IM_END).append("\n");
        }
        return sb.toString();
    }

    /** v1.109: 收集 system 纯文本（不含 chat 标签），供 buildSystemPrefix 和 buildPromptFromHistory 共用。 */
    private String buildSystemContent() {
        StringBuilder combined = new StringBuilder();
        int i = 0;
        while (i < messageHistory.size() && "system".equals(messageHistory.get(i).role)) {
            if (messageHistory.get(i).content != null
                    && (messageHistory.get(i).content.startsWith(PREFIX_SUMMARY)
                        || messageHistory.get(i).content.startsWith(PREFIX_FILES))) {
                i++;
                continue;
            }
            String content = messageHistory.get(i).content;
            if (content != null && !content.isEmpty()) {
                if (combined.length() > 0) combined.append("\n\n");
                combined.append(content);
            }
            i++;
        }
        // 闲聊模式：L1 角色设定 + L2 累积对话记录（只追加不替换，代码直接拼接）
        if (chatMode == MODE_CHAT) {
            if (chatLongTermMemory != null && !chatLongTermMemory.trim().isEmpty()) {
                combined.append("\n\n## 对话记录（长期记忆）\n").append(chatLongTermMemory.trim());
            }
            return combined.toString();
        }
        // v1.134: 阶段1——还没产出文档(planDoc为空)时，临时注入写文档规范；
        // 文档一旦产出(planDoc非空)即丢弃这段，不再常驻，省token、让模型专注开发。
        if (planDoc == null || planDoc.trim().isEmpty()) {
            combined.append("\n\n").append(com.aicodestudio.ide.ai.agent.AgentSystemPrompt.docGuide(sCtx));
        }
        // v1.105: L2 开发规划文档（常驻）+ L3 最新进度摘要（覆盖式）
        if (planDoc != null && !planDoc.trim().isEmpty()) {
            combined.append("\n\n## 当前进度（重要！）\n");
            combined.append("开发规划已完成。本轮禁止输出规划文档、禁止输出【文档开发完成】标记。");
            combined.append("\n\n## 项目开发文档（仅供参考，不要重复输出）\n").append(planDoc.trim());
        }
        if (progressSummary != null && !progressSummary.trim().isEmpty()) {
            combined.append("\n\n## 最新开发进度\n").append(progressSummary.trim());
        }
        return combined.toString();
    }

    // ===== 系统前缀 KV 预热（发热优化 A）=====
    private String lastWarmedPrefix = null;

    /** 系统提示词被用户编辑保存后调用：清掉旧前缀缓存，下一轮按新提示词重新 prefill，立即生效。 */
    public static void invalidatePrefixCache() {
        try {
            AIChatManager m = instance;
            if (m != null) m.lastWarmedPrefix = null;
        } catch (Throwable ignored) {}
    }

    /** 首次对话/系统提示词变化时，把系统前缀 prefill 进 KV，后续轮次复用 */
    private void ensurePrefixWarmed() {
        try {
            String prefix = buildSystemPrefix();
            if (prefix == null || prefix.isEmpty()) return;
            if (prefix.equals(lastWarmedPrefix)) return; // 未变化，KV 仍有效
            InferenceEngine engine = EngineManager.get();
            if (engine == null || !engine.available() || !engine.isLoaded()) return;
            boolean ok = engine.warmSystemPrefix(prefix);
            if (ok) {
                lastWarmedPrefix = prefix;
                Log.i(TAG, "系统前缀已预热，长度 " + prefix.length() + " 字符");
            }
        } catch (Throwable t) {
            Log.w(TAG, "ensurePrefixWarmed failed", t);
        }
    }

    // ===== 代码生成（供编辑器调用） =====
    public void generateCode(String requirement, String language, CodeGenCallback callback) {
        String prompt = String.format(
            "请生成%s代码，需求如下：\n%s\n\n只输出代码，不要解释。",
            language, requirement);
        
        AIResponseListener listener = new AIResponseListener() {
            @Override public void onResponseStart() {}
            @Override public void onResponseChunk(String chunk) {}
            @Override public void onResponseComplete(String fullResponse) {
                // 提取代码块
                String code = extractCodeBlock(fullResponse, language);
                callback.onCodeGenerated(code);
            }
            @Override public void onError(String errorMsg) {
                callback.onError(errorMsg);
            }
            @Override public void onToolStart(StreamingToolDetector.ToolBlock block) {}
            @Override public void onToolUpdate(String content) {}
            @Override public void onToolEnd(StreamingToolDetector.ToolBlock block, boolean ok, String summary) {}
        };
        
        setResponseListener(listener);
        sendMessage(prompt);
    }
    
    private String extractCodeBlock(String response, String language) {
        // 尝试提取 ```language ... ``` 代码块
        String pattern = "```" + language.toLowerCase();
        int start = response.indexOf(pattern);
        if (start >= 0) {
            start = response.indexOf('\n', start) + 1;
            int end = response.indexOf("```", start);
            if (end > start) {
                return response.substring(start, end).trim();
            }
        }
        // 尝试通用代码块
        start = response.indexOf("```");
        if (start >= 0) {
            start = response.indexOf('\n', start) + 1;
            int end = response.indexOf("```", start);
            if (end > start) {
                return response.substring(start, end).trim();
            }
        }
        return response.trim();
    }
    
    public interface CodeGenCallback {
        void onCodeGenerated(String code);
        void onError(String error);
    }
    
    // ===== Getters & Setters =====
    public void setResponseListener(AIResponseListener listener) {
        this.responseListener = listener;
    }
    
    public void setMode(int mode) { this.currentMode = mode; }
    public int getMode() { return currentMode; }
    
    public void setApiKey(String key) { this.apiKey = key; }
    public void setApiBaseUrl(String url) { this.apiBaseUrl = url; }
    public void setModelName(String name) { this.modelName = name; }
    public void setLocalModelPath(String path) { this.localModelPath = path; }
    
    public void setContextLength(int len) { this.contextLength = len; }
    public void setTemperature(float temp) { this.temperature = temp; }
    public void setMaxOutputTokens(int tokens) { this.maxOutputTokens = tokens; }
    public void setThreadCount(int threads) { this.threadCount = threads; }
    
    public List<ChatMessage> getMessageHistory() { return new ArrayList<>(messageHistory); }

    /**
     * v1.135：跨模式合并显示历史——同一对话的工作(_chat=空后缀) + 闲聊(_chat后缀)
     * 两套文件全部读出，按时间戳排序合并，返回 user/assistant 消息。
     * 仅用于 UI 渲染完整时间线；发给模型的 prompt 仍走 getMessageHistory()（当前模式隔离）。
     */
    public List<ChatMessage> getMergedDisplayHistory() {
        List<ChatMessage> all = new ArrayList<>();
        if (appCtx == null) return all;
        try {
            String key = currentHistoryKey;
            all.addAll(readHistoryMessages(new File(new File(appCtx.getFilesDir(), "chat_history"), key + ".json")));
            all.addAll(readHistoryMessages(new File(new File(appCtx.getFilesDir(), "chat_history"), key + "_chat.json")));
        } catch (Throwable t) {
            Log.w(TAG, "getMergedDisplayHistory failed", t);
        }
        java.util.Collections.sort(all, new java.util.Comparator<ChatMessage>() {
            @Override public int compare(ChatMessage a, ChatMessage b) {
                if (a.ts != b.ts) return a.ts < b.ts ? -1 : 1;
                return 0;
            }
        });
        return all;
    }

    /** 读单个历史文件，返回 user/assistant 消息（带时间戳，不过滤 agent 残留——UI 原样显示） */
    private List<ChatMessage> readHistoryMessages(File f) {
        List<ChatMessage> list = new ArrayList<>();
        if (!f.exists()) return list;
        try {
            byte[] buf = new byte[(int) Math.min(f.length(), 8 * 1024 * 1024)];
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            int n;
            try { n = fis.read(buf); } finally { fis.close(); }
            if (n <= 0) return list;
            JSONArray arr = new JSONArray(new String(buf, 0, n, StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String role = o.optString("role", "user");
                String content = o.optString("content", "");
                long ts = o.optLong("ts", 0L);
                if (role.equals("system") || content.isEmpty()) continue;
                list.add(new ChatMessage(role, content, ts));
            }
        } catch (Throwable ignored) {
        }
        return list;
    }
    
    public void clearHistory() {
        messageHistory.clear();
        messageHistory.add(new ChatMessage("system", ""));  // P2-2: 空 system，injectSystemPrompt 会注入新提示词
        persistHistory();
    }
    
    public void shutdown() {
        executor.shutdownNow();
    }

    // ===== v2.0：L3 KV快照检索融合 =====

    /** 快照目录：{filesDir}/chat_history/{convKey}_snap/ */
    private File snapDir() {
        File d = new File(new File(appCtx.getFilesDir(), "chat_history"),
                currentHistoryKey + "_snap");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** L2文本哈希：用于校验快照KV是否可复用 */
    private String l2Hash() {
        String l2 = (chatMode == MODE_CHAT) ? chatLongTermMemory : (planDoc == null ? "" : planDoc);
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(l2.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) { return String.valueOf(l2.hashCode()); }
    }

    /**
     * 保存本轮对话KV快照（每轮AI回答完成后调用）
     * 1. 生成快照标题（后台调用MiniCPM5）
     * 2. 保存当前完整KV为 snap_NNN.kv
     * 3. 保存元数据 snap_NNN.meta.json
     * 4. 更新内存索引
     */
    private void saveConversationSnapshot() {
        try {
            // 从messageHistory读取最后一条user和assistant消息
            String lastUserMsg = "";
            String lastAssistantReply = "";
            synchronized (messageHistory) {
                for (int i = messageHistory.size() - 1; i >= 0; i--) {
                    ChatMessage m = messageHistory.get(i);
                    if ("assistant".equals(m.role) && lastAssistantReply.isEmpty()) {
                        lastAssistantReply = m.content;
                    } else if ("user".equals(m.role) && lastUserMsg.isEmpty()) {
                        lastUserMsg = m.content;
                    }
                    if (!lastUserMsg.isEmpty() && !lastAssistantReply.isEmpty()) break;
                }
            }
            if (appCtx == null) return;
            InferenceEngine engine = EngineManager.get();
            if (engine == null || !engine.isLoaded()) return;
            if (!(engine instanceof com.aicodestudio.ide.ai.engine.LlamaCppEngine)) return;
            com.aicodestudio.ide.ai.engine.LlamaCppEngine lengine =
                    (com.aicodestudio.ide.ai.engine.LlamaCppEngine) engine;
            int[] tokens = lengine.getKvTokenIds();
            if (tokens == null || tokens.length == 0) return;

            snapSeq++;
            String snapId = String.format("snap_%03d", snapSeq);
            File kvFile = new File(snapDir(), snapId + ".kv");
            File metaFile = new File(snapDir(), snapId + ".meta.json");

            // 保存KV
            boolean ok = lengine.saveSessionFile(kvFile.getAbsolutePath(), tokens);
            if (!ok) {
                com.aicodestudio.ide.ai.util.DebugLogger.log("SNAP", "快照KV保存失败: " + snapId);
                return;
            }

            // 生成标题（后台轻量调用，超时5秒）
            String title = generateSnapshotTitle(lastUserMsg, lastAssistantReply);

            // 保存元数据
            org.json.JSONObject meta = new org.json.JSONObject();
            meta.put("snapId", snapId);
            meta.put("title", title);
            meta.put("userMsg", lastUserMsg == null ? "" : lastUserMsg);
            meta.put("assistantReply", lastAssistantReply == null ? "" :
                    (lastAssistantReply.length() > 500 ? lastAssistantReply.substring(0, 500) : lastAssistantReply));
            meta.put("l2Hash", l2Hash());
            meta.put("tokenCount", tokens.length);
            meta.put("createTime", System.currentTimeMillis());
            meta.put("kvFile", kvFile.getName());
            try {
                java.io.FileWriter fw = new java.io.FileWriter(metaFile);
                fw.write(meta.toString(2));
                fw.close();
            } catch (Throwable ignored) {}

            // 更新内存索引
            snapIndex.put(snapId, meta);
            com.aicodestudio.ide.ai.util.DebugLogger.log("SNAP",
                    "快照已保存: " + snapId + " 标题=[" + title + "] tokens=" + tokens.length
                            + " kvSize=" + fmtKvSize(kvFile.length()));
        } catch (Throwable t) {
            com.aicodestudio.ide.ai.util.DebugLogger.log("SNAP", "保存快照异常: " + t.getMessage());
        }
    }

    /** 后台生成快照标题（轻量调用，超时5秒，失败则用用户消息前15字） */
    private String generateSnapshotTitle(String userMsg, String assistantReply) {
        try {
            if (userMsg == null || userMsg.trim().isEmpty()) return "未命名对话";
            String shortUser = userMsg.length() > 30 ? userMsg.substring(0, 30) : userMsg;
            // 简单标题：取用户消息前15字（不调模型，避免耗时）
            String title = shortUser.trim().replaceAll("\\s+", " ");
            if (title.length() > 15) title = title.substring(0, 15) + "...";
            return title;
        } catch (Throwable t) {
            return "未命名对话";
        }
    }

    /**
     * 检索历史快照：基于用户消息关键词匹配
     * 返回最相关的快照ID，无匹配返回null
     */
    private String searchSnapshot(String userMsg) {
        try {
            if (userMsg == null || userMsg.trim().isEmpty()) return null;
            if (snapIndex.isEmpty()) return null;

            // 提取用户消息关键词（简单分词：按空格和标点拆分，取长度>=2的词）
            java.util.Set<String> keywords = new java.util.HashSet<>();
            String[] parts = userMsg.split("[\\s,，。.！!？?、；;:：\"'\\[\\]（）()]+");
            for (String p : parts) {
                if (p.length() >= 2) keywords.add(p.toLowerCase());
            }
            // 额外加入整句前4字和后4字作为模糊匹配
            if (userMsg.length() >= 4) {
                keywords.add(userMsg.substring(0, 4).toLowerCase());
                keywords.add(userMsg.substring(userMsg.length() - 4).toLowerCase());
            }
            if (keywords.isEmpty()) return null;

            // 遍历快照，计算关键词重合度
            String bestId = null;
            int bestScore = 0;
            for (java.util.Map.Entry<String, org.json.JSONObject> e : snapIndex.entrySet()) {
                org.json.JSONObject meta = e.getValue();
                String title = meta.optString("title", "").toLowerCase();
                String uMsg = meta.optString("userMsg", "").toLowerCase();
                String aReply = meta.optString("assistantReply", "").toLowerCase();
                int score = 0;
                for (String kw : keywords) {
                    if (title.contains(kw)) score += 3; // 标题匹配权重高
                    if (uMsg.contains(kw)) score += 2;
                    if (aReply.contains(kw)) score += 1;
                }
                if (score > bestScore) {
                    bestScore = score;
                    bestId = e.getKey();
                }
            }

            // 阈值：至少2分才召回
            if (bestScore >= 2 && bestId != null) {
                com.aicodestudio.ide.ai.util.DebugLogger.log("SNAP",
                        "快照检索命中: " + bestId + " 得分=" + bestScore + " 用户消息=[" + userMsg + "]");
                return bestId;
            }
            return null;
        } catch (Throwable t) {
            com.aicodestudio.ide.ai.util.DebugLogger.log("SNAP", "检索快照异常: " + t.getMessage());
            return null;
        }
    }

    /**
     * 加载召回快照的KV（覆盖当前KV），在快照基础上继续推理
     * 返回true表示加载成功
     */
    private boolean loadSnapshotKv(String snapId) {
        try {
            org.json.JSONObject meta = snapIndex.get(snapId);
            if (meta == null) return false;

            // 校验L2哈希
            String savedL2Hash = meta.optString("l2Hash", "");
            String currentL2Hash = l2Hash();
            if (!savedL2Hash.equals(currentL2Hash)) {
                com.aicodestudio.ide.ai.util.DebugLogger.log("SNAP",
                        "快照L2哈希不匹配，跳过KV加载（降级文本prefill）: " + snapId);
                return false;
            }

            File kvFile = new File(snapDir(), meta.optString("kvFile", snapId + ".kv"));
            if (!kvFile.exists() || kvFile.length() == 0) return false;

            InferenceEngine engine = EngineManager.get();
            if (!(engine instanceof com.aicodestudio.ide.ai.engine.LlamaCppEngine)) return false;
            com.aicodestudio.ide.ai.engine.LlamaCppEngine lengine =
                    (com.aicodestudio.ide.ai.engine.LlamaCppEngine) engine;

            int[] tokens = lengine.loadSessionFile(kvFile.getAbsolutePath());
            if (tokens != null && tokens.length > 0) {
                lengine.applyLoadedTokens(tokens);
                snapshotRecalledThisRound = true;
                recalledSnapId = snapId;
                com.aicodestudio.ide.ai.util.DebugLogger.log("SNAP",
                        "快照KV加载成功: " + snapId + " tokens=" + tokens.length);
                return true;
            }
            return false;
        } catch (Throwable t) {
            com.aicodestudio.ide.ai.util.DebugLogger.log("SNAP", "加载快照KV异常: " + t.getMessage());
            return false;
        }
    }

    /**
     * 融合后处理：保存新主KV，清除所有旧快照
     * 在AI回答完成后、如果本轮触发了快照召回时调用
     */
    private void mergeAndCleanSnapshots() {
        try {
            if (!snapshotRecalledThisRound) return;

            com.aicodestudio.ide.ai.util.DebugLogger.log("SNAP",
                    "融合后处理: 保存主KV + 清除旧快照 (recalled=" + recalledSnapId + ")");

            // 1. 保存当前完整KV为主KV（覆盖旧主KV）
            saveSessionKv();

            // 2. 清除snap/目录下所有旧快照文件
            File dir = snapDir();
            File[] files = dir.listFiles();
            int deleted = 0;
            if (files != null) {
                for (File f : files) {
                    if (f.delete()) deleted++;
                }
            }
            // 3. 清空内存索引
            snapIndex.clear();
            snapSeq = 0;
            snapshotRecalledThisRound = false;
            recalledSnapId = null;

            com.aicodestudio.ide.ai.util.DebugLogger.log("SNAP",
                    "融合完成: 已清除旧快照文件 " + deleted + " 个，索引已清空");
        } catch (Throwable t) {
            com.aicodestudio.ide.ai.util.DebugLogger.log("SNAP", "融合后处理异常: " + t.getMessage());
        }
    }

    /** 加载会话时扫描snap/目录，重建内存索引（不加载KV） */
    private void loadSnapshotIndex() {
        try {
            snapIndex.clear();
            snapSeq = 0;
            File dir = snapDir();
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File f : files) {
                if (!f.getName().endsWith(".meta.json")) continue;
                try {
                    String content = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
                    org.json.JSONObject meta = new org.json.JSONObject(content);
                    String snapId = meta.optString("snapId", "");
                    if (!snapId.isEmpty()) {
                        snapIndex.put(snapId, meta);
                        // 更新snapSeq
                        try {
                            int seq = Integer.parseInt(snapId.replace("snap_", ""));
                            if (seq > snapSeq) snapSeq = seq;
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            }
            com.aicodestudio.ide.ai.util.DebugLogger.log("SNAP",
                    "快照索引已加载: " + snapIndex.size() + " 个快照, snapSeq=" + snapSeq);
        } catch (Throwable t) {
            com.aicodestudio.ide.ai.util.DebugLogger.log("SNAP", "加载快照索引异常: " + t.getMessage());
        }
    }

    /** L1 KV文件路径 */
    private File l1KvFile() {
        return new File(new File(appCtx.getFilesDir(), "chat_history"),
                currentHistoryKey + ".l1.kv");
    }

    /** L2 KV文件路径（包含L1+L2基准） */
    private File l2KvFile() {
        return new File(new File(appCtx.getFilesDir(), "chat_history"),
                currentHistoryKey + ".l2.kv");
    }

    /** L2哈希文件路径 */
    private File l2HashFile() {
        return new File(new File(appCtx.getFilesDir(), "chat_history"),
                currentHistoryKey + ".l2.hash");
    }

    /**
     * 保存L1 KV（纯L1系统提示词）
     * 在首次prefill L1后调用
     */
    private void saveL1Kv(int[] l1Tokens) {
        try {
            InferenceEngine engine = EngineManager.get();
            if (!(engine instanceof com.aicodestudio.ide.ai.engine.LlamaCppEngine)) return;
            com.aicodestudio.ide.ai.engine.LlamaCppEngine lengine =
                    (com.aicodestudio.ide.ai.engine.LlamaCppEngine) engine;
            if (l1Tokens == null || l1Tokens.length == 0) return;
            boolean ok = lengine.saveSessionFile(l1KvFile().getAbsolutePath(), l1Tokens);
            com.aicodestudio.ide.ai.util.DebugLogger.log("KV",
                    "L1 KV保存: " + (ok ? "成功" : "失败") + " tokens=" + l1Tokens.length);
        } catch (Throwable t) {
            com.aicodestudio.ide.ai.util.DebugLogger.log("KV", "保存L1 KV异常: " + t.getMessage());
        }
    }

    /**
     * 保存L2 KV（L1+L2基准）+ L2哈希
     * 在prefill L2后调用
     */
    private void saveL2Kv(int[] l1L2Tokens) {
        try {
            InferenceEngine engine = EngineManager.get();
            if (!(engine instanceof com.aicodestudio.ide.ai.engine.LlamaCppEngine)) return;
            com.aicodestudio.ide.ai.engine.LlamaCppEngine lengine =
                    (com.aicodestudio.ide.ai.engine.LlamaCppEngine) engine;
            if (l1L2Tokens == null || l1L2Tokens.length == 0) return;
            boolean ok = lengine.saveSessionFile(l2KvFile().getAbsolutePath(), l1L2Tokens);
            // 保存L2哈希
            try {
                java.io.FileWriter fw = new java.io.FileWriter(l2HashFile());
                fw.write(l2Hash());
                fw.close();
            } catch (Throwable ignored) {}
            com.aicodestudio.ide.ai.util.DebugLogger.log("KV",
                    "L2 KV保存: " + (ok ? "成功" : "失败") + " tokens=" + l1L2Tokens.length);
        } catch (Throwable t) {
            com.aicodestudio.ide.ai.util.DebugLogger.log("KV", "保存L2 KV异常: " + t.getMessage());
        }
    }
}
