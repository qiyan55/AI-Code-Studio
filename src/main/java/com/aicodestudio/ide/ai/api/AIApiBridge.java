package com.aicodestudio.ide.ai.api;

import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * AI API Bridge - 跨模块统一通信接口层
 * 实现文档: 6.统一通信数据结构 & 7.AI可调用全部开放API
 * 
 * 这是AI调用所有系统功能的统一入口
 */
public class AIApiBridge {
    
    private static final String TAG = "AIApiBridge";
    private static AIApiBridge instance;
    
    // 编辑器接口实现
    private EditorController editorController;
    // 编译接口实现
    private CompileController compileController;
    // 工具链接口实现
    private ToolchainController toolchainController;
    
    public interface EditorController {
        void insertCode(String text, int position);
        void replaceSelection(String newText);
        String getCurrentFileCode();
        String getCodeSelection();
        List<String> getCodeErrors();
        void openFile(String filePath);
        void saveCurrentFile();
        int getCursorRow();
        int getCursorCol();
        String getCurrentFilePath();
    }
    
    public interface CompileController {
        void runCompile(String toolId, String projectPath);
        void stopCompile();
        String getCompileLog();
        void clearLog();
        boolean isCompiling();
    }
    
    public interface ToolchainController {
        void autoCreateBuildTool(JSONObject toolConfig);
        List<JSONObject> listBuildTools();
        void deleteBuildTool(String toolId);
        void enableBuildTool(String toolId, boolean enabled);
    }
    
    private AIApiBridge() {}
    
    public static synchronized AIApiBridge getInstance() {
        if (instance == null) {
            instance = new AIApiBridge();
        }
        return instance;
    }
    
    // ===== 绑定控制器（由各模块在初始化时调用） =====
    public void setEditorController(EditorController c) { this.editorController = c; }
    public void setCompileController(CompileController c) { this.compileController = c; }
    public void setToolchainController(ToolchainController c) { this.toolchainController = c; }
    
    // ===== 7. AI可调用全部开放API =====
    
    // --- 编辑器接口 ---
    public void insertCode(String text, int position) {
        if (editorController != null) {
            editorController.insertCode(text, position);
            Log.d(TAG, "AI inserted code at position " + position);
        }
    }
    
    public void replaceSelection(String newText) {
        if (editorController != null) {
            editorController.replaceSelection(newText);
            Log.d(TAG, "AI replaced selection");
        }
    }
    
    public String getCurrentFileCode() {
        return editorController != null ? editorController.getCurrentFileCode() : "";
    }
    
    public String getCodeSelection() {
        return editorController != null ? editorController.getCodeSelection() : "";
    }
    
    public List<String> getCodeErrors() {
        return editorController != null ? editorController.getCodeErrors() : new ArrayList<String>();
    }
    
    public void openFile(String filePath) {
        if (editorController != null) {
            editorController.openFile(filePath);
            Log.d(TAG, "AI opened file: " + filePath);
        }
    }
    
    public void saveCurrentFile() {
        if (editorController != null) {
            editorController.saveCurrentFile();
        }
    }
    
    // --- 编译运行接口 ---
    public void runCompile(String toolId, String projectPath) {
        if (compileController != null) {
            compileController.runCompile(toolId, projectPath);
            Log.d(TAG, "AI started compile: " + toolId);
        }
    }
    
    public void stopCompile() {
        if (compileController != null) {
            compileController.stopCompile();
        }
    }
    
    public String getCompileLog() {
        return compileController != null ? compileController.getCompileLog() : "";
    }
    
    public void clearLog() {
        if (compileController != null) {
            compileController.clearLog();
        }
    }
    
    // --- 编译工具链接口 ---
    public void autoCreateBuildTool(JSONObject toolConfig) {
        if (toolchainController != null) {
            toolchainController.autoCreateBuildTool(toolConfig);
            Log.d(TAG, "AI auto-created build tool");
        }
    }
    
    // ===== 6. 统一消息传输JSON结构 =====
    public JSONObject buildErrorReport() {
        JSONObject msg = new JSONObject();
        try {
            msg.put("file_path", editorController != null ? 
                editorController.getCurrentFilePath() : "");
            msg.put("project_type", detectProjectType());
            msg.put("source_code", getCurrentFileCode());
            
            JSONArray errors = new JSONArray();
            List<String> errList = getCodeErrors();
            for (String e : errList) errors.put(e);
            msg.put("error_info", errors);
            
            JSONObject cursor = new JSONObject();
            cursor.put("row", editorController != null ? editorController.getCursorRow() : 0);
            cursor.put("col", editorController != null ? editorController.getCursorCol() : 0);
            msg.put("cursor_position", cursor);
            
            msg.put("model_mode", "cloud"); // or "local"
            msg.put("trigger_type", "compile_error");
        } catch (Exception e) {
            Log.e(TAG, "buildErrorReport failed", e);
        }
        return msg;
    }
    
    private String detectProjectType() {
        // 根据文件结构和build.gradle检测项目类型
        // 简化实现
        return "Android";
    }
    
    // ===== 8. 核心业务执行流程 =====
    
    /**
     * 流程1: 聊天下发需求 -> AI自动插入代码
     */
    public void flow_ChatToCode(String generatedCode) {
        if (editorController == null) return;
        String currentCode = editorController.getCurrentFileCode();
        // 在光标位置插入
        int pos = currentCode.length(); // 默认末尾
        editorController.insertCode(generatedCode, pos);
    }
    
    /**
     * 流程4: AI自动配置编译工具链
     */
    public void flow_AutoConfigureToolchain(String projectType) {
        try {
            JSONObject config = new JSONObject();
            config.put("tool_name", "auto-detected-tool");
            config.put("project_type", projectType);
            config.put("compile_command", detectCompileCommand(projectType));
            config.put("run_command", detectRunCommand(projectType));
            config.put("auto_created", true);
            autoCreateBuildTool(config);
        } catch (Exception e) {
            Log.e(TAG, "flow_AutoConfigureToolchain failed", e);
        }
    }
    
    private String detectCompileCommand(String type) {
        switch (type) {
            case "Android": return "./gradlew assembleDebug";
            case "Java": return "javac {file} -d {outpath}";
            case "C++": return "g++ {file} -o {outpath}";
            case "Python": return "python3 {file}";
            default: return "";
        }
    }
    
    private String detectRunCommand(String type) {
        switch (type) {
            case "Android": return "adb install -r {outpath}/app-debug.apk";
            case "Java": return "java -cp {outpath} Main";
            case "C++": return "{outpath}/a.out";
            case "Python": return "python3 {file}";
            default: return "";
        }
    }
}
