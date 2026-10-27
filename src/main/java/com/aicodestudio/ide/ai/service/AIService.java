package com.aicodestudio.ide.ai.service;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI Service - AI服务层（核心调度）
 * 实现文档: 
 * - 4.2.3 代码错误检测与自动上报
 * - 4.4.2 AI联动逻辑
 * - 8. 核心业务执行流程
 * 
 * 这是AI Code Studio的大脑 - 协调所有模块
 */
public class AIService {
    
    private static final String TAG = "AIService";
    private static AIService instance;
    
    // 错误检测模式（文档 4.2.3）
    public static final int DETECT_MODE_FUNCTION = 0;  // 函数完成检测
    public static final int DETECT_MODE_FILE = 1;      // 文件完成检测
    public static final int DETECT_MODE_REAL_TIME = 2;  // 实时检测（带防抖）
    
    // 配置项（文档 9. 全局系统配置项）
    private boolean autoSendErrors = true;       // 9.1 代码错误自动发送AI
    private boolean detectOnFunctionEnd = true;  // 9.2 函数完成自动检测
    private boolean detectOnFileEnd = true;      // 9.3 文件完成整体检测
    private boolean autoPushCompileErrors = true;// 9.5 编译失败自动推送
    private boolean allowAutoCreateTool = true;  // 9.6 允许AI自动创建编译工具
    private boolean autoApplyFix = false;        // 9.4 AI修复自动覆盖源码
    private boolean confirmBeforeApply = true;   // 覆盖前弹窗确认
    
    // 防抖延迟（文档 10.3）
    private static final long DEBOUNCE_DELAY = 2000; // 2秒
    
    // 当前检测模式
    private int detectMode = DETECT_MODE_REAL_TIME;
    
    private final Handler mainHandler;
    private final ExecutorService executor;
    private final List<AIServiceListener> listeners;
    
    // 依赖模块（通过依赖注入）
    private AICodeAnalyzer codeAnalyzer;
    private AIErrorReporter errorReporter;
    
    public interface AIServiceListener {
        void onAutoFixProposed(String originalCode, String fixedCode, String explanation);
        void onCompileErrorAutoPushed(String log);
        void onToolAutoCreated(String toolConfig);
        void onStatusChanged(String status);
    }
    
    private AIService() {
        mainHandler = new Handler(Looper.getMainLooper());
        executor = Executors.newSingleThreadExecutor();
        listeners = new ArrayList<>();
    }
    
    public static synchronized AIService getInstance() {
        if (instance == null) {
            instance = new AIService();
        }
        return instance;
    }
    
    // ===== 初始化依赖 =====
    public void setCodeAnalyzer(AICodeAnalyzer analyzer) {
        this.codeAnalyzer = analyzer;
    }
    
    public void setErrorReporter(AIErrorReporter reporter) {
        this.errorReporter = reporter;
    }
    
    // ===== 4.2.3 代码错误检测 =====
    
    /**
     * 函数编辑完成检测（文档 4.2.3 模式1）
     * 当光标离开函数区块时调用
     */
    public void onFunctionEditComplete(String filePath, String functionCode, int lineStart, int lineEnd) {
        if (!detectOnFunctionEnd) return;
        
        notifyStatus("检测函数: " + getFunctionName(functionCode));
        
        executor.execute(new Runnable() {
            @Override
            public void run() {
                if (codeAnalyzer == null) return;
                
                List<String> errors = codeAnalyzer.analyzeFunction(functionCode);
                if (!errors.isEmpty() && autoSendErrors) {
                    pushErrorsToAI(filePath, errors, "function_finish");
                }
            }
        });
    }
    
    /**
     * 文件编辑完成检测（文档 4.2.3 模式2）
     * 当文件保存时调用
     */
    public void onFileEditComplete(String filePath, String fullCode) {
        if (!detectOnFileEnd) return;
        
        notifyStatus("扫描文件: " + filePath);
        
        // 防抖（文档 10.3）
        mainHandler.removeCallbacksAndMessages(null);
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        if (codeAnalyzer == null) return;
                        
                        List<String> errors = codeAnalyzer.analyzeFile(fullCode);
                        if (!errors.isEmpty()) {
                            notifyStatus("发现 " + errors.size() + " 个问题");
                            if (autoSendErrors) {
                                pushErrorsToAI(filePath, errors, "file_finish");
                            }
                        } else {
                            notifyStatus("[通过] 未发现问题");
                        }
                    }
                });
            }
        }, DEBOUNCE_DELAY);
    }
    
    /**
     * 实时检测（带防抖）
     */
    public void onCodeChanged(String filePath, String code) {
        if (detectMode != DETECT_MODE_REAL_TIME) return;
        
        mainHandler.removeCallbacksAndMessages(null);
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        if (codeAnalyzer == null) return;
                        List<String> errors = codeAnalyzer.quickCheck(code);
                        if (!errors.isEmpty() && autoSendErrors) {
                            // 只发送前3个错误（文档 10.1 大文件优化）
                            List<String> subset = errors.size() > 3 ? 
                                errors.subList(0, 3) : errors;
                            pushErrorsToAI(filePath, subset, "realtime");
                        }
                    }
                });
            }
        }, DEBOUNCE_DELAY);
    }
    
    // ===== 错误推送给AI =====
    private void pushErrorsToAI(String filePath, List<String> errors, String triggerType) {
        notifyStatus("正在推送错误给AI...");
        
        try {
            JSONObject msg = new JSONObject();
            msg.put("file_path", filePath);
            msg.put("trigger_type", triggerType);
            
            // 文档 6. 统一消息传输JSON结构
            JSONObject cursor = new JSONObject();
            cursor.put("row", 0);
            cursor.put("col", 0);
            msg.put("cursor_position", cursor);
            
            msg.put("model_mode", "cloud"); // or "local"
            
            // 构建错误列表
            StringBuilder sb = new StringBuilder();
            for (String e : errors) sb.append(e).append("\n");
            msg.put("error_info", new org.json.JSONArray(errors));
            msg.put("source_code", ""); // 可选：附上源码片段
            msg.put("project_type", "Android");
            
            // 发送（通过 errorReporter）
            if (errorReporter != null) {
                errorReporter.reportErrors(msg, new AIErrorReporter.ReportCallback() {
                    @Override
                    public void onFixReceived(String fixedCode, String explanation) {
                        notifyStatus("收到AI修复建议");
                        notifyAutoFix(fixedCode, explanation);
                    }
                    @Override
                    public void onError(String msg) {
                        notifyStatus("AI请求失败: " + msg);
                    }
                });
            }
            
        } catch (Exception e) {
            Log.e(TAG, "pushErrorsToAI failed", e);
        }
    }
    
    // ===== 8. 流程2: 代码编辑器自动检测错误，AI自动修复 =====
    public void requestFix(String code, String errorMsg) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                if (errorReporter == null) return;
                
                errorReporter.requestFix(code, errorMsg, new AIErrorReporter.ReportCallback() {
                    @Override
                    public void onFixReceived(String fixedCode, String explanation) {
                        if (autoApplyFix && !confirmBeforeApply) {
                            // 自动覆盖
                            notifyAutoFix(fixedCode, explanation);
                        } else {
                            // 需要确认
                            notifyAutoFix(fixedCode, explanation);
                        }
                    }
                    @Override
                    public void onError(String msg) {
                        notifyStatus("修复请求失败: " + msg);
                    }
                });
            }
        });
    }
    
    // ===== 8. 流程3: 编译报错联动AI =====
    public void onCompileError(String log, String sourceCode) {
        if (!autoPushCompileErrors) return;
        
        notifyStatus("编译失败，推送日志给AI...");
        notifyCompilePushed(log);
        
        executor.execute(new Runnable() {
            @Override
            public void run() {
                if (errorReporter == null) return;
                
                errorReporter.reportCompileError(log, sourceCode, new AIErrorReporter.ReportCallback() {
                    @Override
                    public void onFixReceived(String fixedCode, String explanation) {
                        notifyStatus("AI已生成修复方案");
                        notifyAutoFix(fixedCode, explanation);
                    }
                    @Override
                    public void onError(String msg) {
                        notifyStatus("AI分析失败: " + msg);
                    }
                });
            }
        });
    }
    
    // ===== 8. 流程4: AI自动配置编译工具链 =====
    public void autoConfigureToolchain(String projectType) {
        if (!allowAutoCreateTool) {
            notifyStatus("AI自动创建工具已禁用");
            return;
        }
        
        notifyStatus("AI正在配置编译工具...");
        
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    // 根据项目类型生成工具配置
                    JSONObject config = generateToolConfig(projectType);
                    
                    // 通知工具链模块创建
                    notifyToolCreated(config.toString());
                    
                } catch (Exception e) {
                    Log.e(TAG, "autoConfigureToolchain failed", e);
                }
            }
        });
    }
    
    private JSONObject generateToolConfig(String projectType) {
        JSONObject config = new JSONObject();
        try {
            switch (projectType.toLowerCase()) {
                case "android":
                    config.put("tool_name", "Android Build Tool");
                    config.put("project_type", "Android");
                    config.put("compile_command", "./gradlew assembleDebug");
                    config.put("run_command", "adb install -r {outpath}/app-debug.apk");
                    config.put("download_url", "");
                    break;
                case "java":
                    config.put("tool_name", "Java Compiler");
                    config.put("project_type", "Java");
                    config.put("compile_command", "javac {file} -d {outpath}");
                    config.put("run_command", "java -cp {outpath} Main");
                    break;
                case "cpp":
                case "c++":
                    config.put("tool_name", "G++ Compiler");
                    config.put("project_type", "C++");
                    config.put("compile_command", "g++ {file} -o {outpath}/a.out");
                    config.put("run_command", "{outpath}/a.out");
                    break;
                case "python":
                    config.put("tool_name", "Python Runner");
                    config.put("project_type", "Python");
                    config.put("compile_command", "python3 -c \"import py_compile; py_compile.compile('{file}')\"");
                    config.put("run_command", "python3 {file}");
                    break;
                default:
                    config.put("tool_name", "Custom Tool");
                    config.put("project_type", "custom");
                    config.put("compile_command", "");
                    config.put("run_command", "");
            }
            config.put("auto_created", true);
            config.put("enabled", true);
        } catch (Exception e) {}
        return config;
    }
    
    // ===== 8. 流程5: 全自动开发闭环 =====
    public void fullAutoLoop(String requirement, String projectType) {
        notifyStatus("[运行] 启动全自动开发闭环");
        
        // Step 1: 检查/创建编译工具
        autoConfigureToolchain(projectType);
        
        // Step 2: 请求AI生成代码
        // (由调用方通过AIChatManager.generateCode()实现)
        
        // Step 3: 编译
        // (由调用方通过CompileExecutor.startCompile()实现)
        
        // Step 4-7: 循环迭代在调用方实现
        // 当编译失败时，onCompileError()会自动推送给AI
    }
    
    // ===== 配置项 Getters/Setters（文档 9.） =====
    public void setAutoSendErrors(boolean enabled) { this.autoSendErrors = enabled; }
    public void setDetectOnFunctionEnd(boolean enabled) { this.detectOnFunctionEnd = enabled; }
    public void setDetectOnFileEnd(boolean enabled) { this.detectOnFileEnd = enabled; }
    public void setAutoPushCompileErrors(boolean enabled) { this.autoPushCompileErrors = enabled; }
    public void setAllowAutoCreateTool(boolean enabled) { this.allowAutoCreateTool = enabled; }
    public void setAutoApplyFix(boolean enabled) { this.autoApplyFix = enabled; }
    public void setConfirmBeforeApply(boolean enabled) { this.confirmBeforeApply = enabled; }
    
    public boolean isAutoSendErrors() { return autoSendErrors; }
    public boolean isDetectOnFunctionEnd() { return detectOnFunctionEnd; }
    public boolean isDetectOnFileEnd() { return detectOnFileEnd; }
    public boolean isAutoPushCompileErrors() { return autoPushCompileErrors; }
    public boolean isAllowAutoCreateTool() { return allowAutoCreateTool; }
    
    // ===== 监听器 =====
    public void addListener(AIServiceListener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }
    public void removeListener(AIServiceListener l) {
        listeners.remove(l);
    }
    
    private void notifyAutoFix(String fixedCode, String explanation) {
        for (AIServiceListener l : listeners) {
            l.onAutoFixProposed(null, fixedCode, explanation);
        }
    }
    private void notifyCompilePushed(String log) {
        for (AIServiceListener l : listeners) l.onCompileErrorAutoPushed(log);
    }
    private void notifyToolCreated(String config) {
        for (AIServiceListener l : listeners) l.onToolAutoCreated(config);
    }
    private void notifyStatus(String status) {
        Log.d(TAG, status);
        for (AIServiceListener l : listeners) l.onStatusChanged(status);
    }
    
    // ===== 辅助方法 =====
    private String getFunctionName(String code) {
        // 简单提取函数名
        int idx = code.indexOf("void ");
        if (idx < 0) idx = code.indexOf("int ");
        if (idx < 0) idx = code.indexOf("String ");
        if (idx < 0) return "unknown";
        int start = idx + 5;
        int end = code.indexOf('(', start);
        if (end > start) return code.substring(start, end).trim();
        return "unknown";
    }
    
    // ===== 代码分析器接口 =====
    public interface AICodeAnalyzer {
        List<String> analyzeFunction(String functionCode);
        List<String> analyzeFile(String fullCode);
        List<String> quickCheck(String code);
    }
    
    // ===== 错误报告器接口 =====
    public interface AIErrorReporter {
        void reportErrors(JSONObject errorData, ReportCallback cb);
        void reportCompileError(String log, String sourceCode, ReportCallback cb);
        void requestFix(String code, String errorMsg, ReportCallback cb);
        
        interface ReportCallback {
            void onFixReceived(String fixedCode, String explanation);
            void onError(String msg);
        }
    }
    
    public void shutdown() {
        executor.shutdownNow();
    }
}
