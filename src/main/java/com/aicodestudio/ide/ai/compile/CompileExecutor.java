package com.aicodestudio.ide.ai.compile;

import android.content.Context;
import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.aicodestudio.ide.ai.tools.BuildToolManager;

/**
 * Compile Executor - 编译运行执行模块
 * 实现文档: 4.4 编译运行执行模块
 * 
 * 功能：
 * - 启动/终止编译任务
 * - 实时日志捕获（分级：info/warn/error）
 * - AI联动：编译失败自动推送日志
 * - 内置工具链：工具 compileCommand 以 internal: 开头时，走 App 内置编译引擎
 *   （阶段一：ECJ 编译检查 → 标准错误列表，无需设备安装任何编译环境）
 */
public class CompileExecutor {
    
    private static final String TAG = "CompileExecutor";
    private static CompileExecutor instance;
    
    // 日志级别
    public static final int LOG_INFO = 0;
    public static final int LOG_WARN = 1;
    public static final int LOG_ERROR = 2;
    public static final int LOG_SUCCESS = 3;
    public static final int LOG_DEBUG = 4;
    
    private final ExecutorService executor;
    private final StringBuilder logBuffer;
    private final List<LogEntry> logEntries;
    private final List<CompileError> lastErrors;
    private Process currentProcess;
    private boolean isCompiling;
    private CompileListener listener;
    private boolean autoSendToAI = true; // 文档 9.5: 编译失败自动推送
    private Context appContext;
    
    public static class LogEntry {
        public final int level;
        public final String message;
        public final long timestamp;
        public LogEntry(int level, String message) {
            this.level = level;
            this.message = message;
            this.timestamp = System.currentTimeMillis();
        }
    }
    
    public interface CompileListener {
        void onCompileStart();
        void onLogOutput(LogEntry entry);
        void onCompileSuccess(String log);
        void onCompileFailed(String log, int exitCode);
        void onCompileCancelled();
    }
    
    private CompileExecutor() {
        executor = Executors.newSingleThreadExecutor();
        logBuffer = new StringBuilder();
        logEntries = new ArrayList<>();
        lastErrors = new ArrayList<>();
    }
    
    public static synchronized CompileExecutor getInstance() {
        if (instance == null) {
            instance = new CompileExecutor();
        }
        return instance;
    }

    /** 注入 App Context（内置工具链解压/编译需要） */
    public void attach(Context ctx) {
        if (ctx != null) this.appContext = ctx.getApplicationContext();
    }

    /**
     * 启动编译（带 Context，支持内置工具链）
     */
    public void startCompile(Context ctx, String toolId, String projectPath, String mainFilePath) {
        attach(ctx);
        startCompile(toolId, projectPath, mainFilePath);
    }
    
    // ===== 4.4.1 启动编译 =====
    public void startCompile(String toolId, String projectPath, String mainFilePath) {
        if (isCompiling) {
            Log.w(TAG, "Compile already in progress");
            return;
        }
        
        // 判断是否内置工具链
        boolean internal = false;
        if (appContext != null) {
            try {
                BuildToolManager.BuildTool tool =
                        BuildToolManager.getInstance(appContext).getToolById(toolId);
                internal = tool != null && tool.isInternal();
            } catch (Throwable t) {
                Log.w(TAG, "resolve tool failed", t);
            }
        }
        
        // 获取编译工具配置
        // 实际实现中通过 BuildToolManager 获取
        String compileCmd = internal ? "internal:ecj" : resolveCompileCommand(toolId, projectPath, mainFilePath);
        final boolean internalMode = internal;

        // 同步置位 + 同步回调：让 UI（按钮切换/状态文字）在点击瞬间立即生效，
        // 而不是等后台线程执行才更新（否则编译按钮不会消失）
        isCompiling = true;
        if (listener != null) listener.onCompileStart();

        executor.execute(new Runnable() {
            @Override
            public void run() {
                logEntries.clear();
                lastErrors.clear();
                logBuffer.setLength(0);
                
                appendLog(LOG_DEBUG, "────────────────────────────────");
                appendLog(LOG_INFO, "  AI Code Studio - 编译开始");
                appendLog(LOG_INFO, "  工具ID: " + toolId);
                appendLog(LOG_INFO, "  项目路径: " + projectPath);
                appendLog(LOG_DEBUG, "────────────────────────────────");
                appendLog(LOG_DEBUG, "执行模式: " + (internalMode ? "内置工具链" : compileCmd));
                
                if (internalMode) {
                    runInternalCompile(projectPath);
                    return;
                }
                
                try {
                    ProcessBuilder pb = new ProcessBuilder();
                    pb.directory(new File(projectPath));
                    
                    // 根据工具类型选择shell
                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                        pb.command("sh", "-c", compileCmd);
                    } else {
                        pb.command("sh", "-c", compileCmd);
                    }
                    
                    pb.redirectErrorStream(true);
                    currentProcess = pb.start();
                    
                    // 读取输出流
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(currentProcess.getInputStream()));
                    
                    String line;
                    while ((line = reader.readLine()) != null) {
                        // 分级解析日志
                        int level = classifyLogLine(line);
                        appendLog(level, line);
                    }
                    
                    int exitCode = currentProcess.waitFor();
                    currentProcess = null;
                    isCompiling = false;
                    
                    if (exitCode == 0) {
                        appendLog(LOG_SUCCESS, "────────────────────────────────");
                        appendLog(LOG_SUCCESS, "编译完成 exit=0");
                        appendLog(LOG_SUCCESS, "────────────────────────────────");
                        if (listener != null) {
                            listener.onCompileSuccess(getFullLog());
                        }
                    } else {
                        appendLog(LOG_ERROR, "────────────────────────────────");
                        appendLog(LOG_ERROR, "编译错误 exit=" + exitCode);
                        appendLog(LOG_ERROR, "────────────────────────────────");
                        if (listener != null) {
                            listener.onCompileFailed(getFullLog(), exitCode);
                        }
                        
                        // 文档 4.4.2: 编译失败自动推送日志至AI
                        if (autoSendToAI) {
                            notifyAIService(getFullLog());
                        }
                    }
                    
                } catch (Exception e) {
                    isCompiling = false;
                    currentProcess = null;
                    appendLog(LOG_ERROR, "编译异常: " + e.getMessage());
                    Log.e(TAG, "Compile failed", e);
                    if (listener != null) {
                        listener.onCompileFailed(getFullLog(), -1);
                    }
                }
            }
        });
    }

    /**
     * 内置工具链编译（阶段一：ECJ 编译检查）
     */
    private void runInternalCompile(String projectPath) {
        try {
            if (appContext == null) {
                appendLog(LOG_ERROR, "内置编译需要 App Context（未注入）");
                finishInternal(false, -1);
                return;
            }
            BuiltinCompiler compiler = new BuiltinCompiler(appContext);
            appendLog(LOG_INFO, "准备内置工具链（ECJ + Android API）…");

            // 【修复】跳过 compileProject()（纯语法检查，不跑 aapt2，简易 R 不含布局 id），
            // 直接调用 buildApk() 完整流程：aapt2 生成真实 R.java → ECJ 编译 → d8 → 打包 → 签名
            lastApkFile = null;
            lastApkPackageName = null;
            BuiltinCompiler.BuildApkResult bapk = compiler.buildApk(
                    new File(projectPath), null, new BuiltinCompiler.BuildListener() {
                @Override
                public void onBuildLog(String msg, int level) {
                    appendLog(level, msg);
                }
            });
            if (bapk.success && bapk.apkFile != null && bapk.apkFile.exists()) {
                lastApkFile = bapk.apkFile;
                lastApkPackageName = bapk.packageName;
                appendLog(LOG_SUCCESS, "────────────────────────────────");
                appendLog(LOG_SUCCESS, "APK 已生成：" + bapk.apkFile.getName()
                        + "（" + ArtifactManager.formatSize(bapk.apkFile.length()) + "）");
                appendLog(LOG_DEBUG, "临时路径: " + bapk.apkFile.getAbsolutePath());
                appendLog(LOG_SUCCESS, "────────────────────────────────");
                finishInternal(true, 0);
            } else {
                appendLog(LOG_ERROR, "────────────────────────────────");
                appendLog(LOG_ERROR, "APK 构建失败：" + (bapk.error == null ? "未知错误" : bapk.error));
                appendLog(LOG_ERROR, "────────────────────────────────");
                // 输出完整构建日志（aapt2、R-debug、ECJ 等中间过程）
                if (bapk.log != null && bapk.log.length() > 0) {
                    appendLog(LOG_ERROR, "── 完整构建日志 ──");
                    String[] lines = bapk.log.split("\n");
                    for (String line : lines) {
                        appendLog(LOG_DEBUG, line);
                    }
                }
                finishInternal(false, 2);
            }
        } catch (Throwable t) {
            Log.e(TAG, "internal compile failed", t);
            appendLog(LOG_ERROR, "内置编译异常: " + t.getMessage());
            finishInternal(false, -1);
        }
    }
    
    private void finishInternal(boolean ok, int exitCode) {
        isCompiling = false;
        currentProcess = null;
        if (listener != null) {
            if (ok) {
                listener.onCompileSuccess(getFullLog());
            } else {
                listener.onCompileFailed(getFullLog(), exitCode);
                if (autoSendToAI) {
                    notifyAIService(getFullLog());
                }
            }
        }
    }

    /** 最近一次编译的标准化错误列表（供编辑器定位 / AI 改错） */
    public List<CompileError> getLastErrors() {
        return new ArrayList<>(lastErrors);
    }

    /** 最近一次编译生成的签名 APK（编译通过且 APK 构建成功时非 null；安装后应清理） */
    private File lastApkFile;
    public File getLastApkFile() {
        return lastApkFile;
    }

    /** 最近一次编译产物的包名（推断失败时为 null；安装后轮询检测安装结果用） */
    private String lastApkPackageName;
    public String getLastApkPackageName() {
        return lastApkPackageName;
    }

    // ===== 4.4.2 终止编译 =====
    public void stopCompile() {
        if (currentProcess != null) {
            currentProcess.destroy();
            currentProcess = null;
            isCompiling = false;
            appendLog(LOG_WARN, "[终止] 编译已取消");
            if (listener != null) listener.onCompileCancelled();
        }
    }
    
    // ===== 日志分级解析 =====
    private int classifyLogLine(String line) {
        String lower = line.toLowerCase();
        if (lower.contains("error") || lower.contains("fatal") || lower.contains("失败")) {
            return LOG_ERROR;
        } else if (lower.contains("warn") || lower.contains("deprecated")) {
            return LOG_WARN;
        } else if (lower.contains("success") || lower.contains("built") || lower.contains("完成")) {
            return LOG_SUCCESS;
        } else if (lower.contains("debug") || lower.contains("verbose")) {
            return LOG_DEBUG;
        }
        return LOG_INFO;
    }
    
    private void appendLog(int level, String message) {
        LogEntry entry = new LogEntry(level, message);
        logEntries.add(entry);
        logBuffer.append(message).append('\n');
        if (listener != null) {
            listener.onLogOutput(entry);
        }
    }
    
    // ===== 4.4.1 日志管理 =====
    public String getFullLog() {
        return logBuffer.toString();
    }
    
    public List<LogEntry> getLogEntries() {
        return new ArrayList<>(logEntries);
    }
    
    public void clearLog() {
        logEntries.clear();
        logBuffer.setLength(0);
        appendLog(LOG_INFO, "日志已清空");
    }
    
    public boolean isCompiling() {
        return isCompiling;
    }
    
    // ===== 配置 =====
    public void setAutoSendToAI(boolean enabled) {
        this.autoSendToAI = enabled;
    }
    
    public void setListener(CompileListener l) {
        this.listener = l;
    }
    
    // ===== 命令解析 =====
    private String resolveCompileCommand(String toolId, String projectPath, String mainFile) {
        // 实际实现中从 BuildToolManager 获取工具配置
        // 这里提供默认映射
        if (toolId.contains("gradle") || toolId.contains("android")) {
            return "./gradlew assembleDebug 2>&1";
        } else if (toolId.contains("javac") || toolId.contains("java")) {
            String outDir = projectPath + "/build/classes";
            return "mkdir -p " + outDir + " && javac -d " + outDir + " " + mainFile + " 2>&1";
        } else if (toolId.contains("g++") || toolId.contains("cpp")) {
            return "g++ -o " + projectPath + "/a.out " + mainFile + " 2>&1";
        } else if (toolId.contains("python")) {
            return "python3 -c \"import py_compile; py_compile.compile('" + mainFile + "')\" 2>&1";
        }
        return "echo 'Unknown tool: " + toolId + "' && exit 1";
    }
    
    // ===== 通知AI服务 =====
    private void notifyAIService(String log) {
        // 通过 AI 编译联动模块通知
        // 实际实现中调用 AIApiBridge.buildErrorReport()
        Log.d(TAG, "Auto-notifying AI service with compile log (" + log.length() + " chars)");
    }
    
    public void shutdown() {
        stopCompile();
        executor.shutdownNow();
    }
}
