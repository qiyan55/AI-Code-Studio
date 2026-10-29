package com.aicodestudio.ide.ai.tools;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Build Tool Manager - 自定义编译工具链管理模块
 * 实现文档: 4.3 自定义编译工具链管理模块
 * 
 * 支持手动创建 + AI自动创建双通道
 * 支持Android/C++/Python/Java/自定义项目
 */
public class BuildToolManager {
    
    private static final String PREFS = "aicodestudio_build_tools";
    private static BuildToolManager instance;
    
    private final Context context;
    private final SharedPreferences prefs;
    private final List<BuildTool> tools;
    private final List<ToolchainListener> listeners;
    
    public interface ToolchainListener {
        void onToolAdded(BuildTool tool);
        void onToolRemoved(String toolId);
        void onToolUpdated(BuildTool tool);
    }
    
    public static class BuildTool {
        public String id;
        public String name;
        public String downloadUrl;
        public String projectType; // Android/C++/Python/Java/custom
        public String compileCommand;
        public String runCommand;
        public boolean enabled;
        public boolean autoCreated; // AI自动创建 vs 手动创建
        public boolean builtin; // 内置工具链（随 App 打包，无需下载；compileCommand 以 "internal:" 开头）
        public String archFilter; // CPU架构过滤
        public long createTime;
        
        public JSONObject toJSON() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("id", id);
                obj.put("name", name);
                obj.put("download_url", downloadUrl);
                obj.put("project_type", projectType);
                obj.put("compile_command", compileCommand);
                obj.put("run_command", runCommand);
                obj.put("enabled", enabled);
                obj.put("auto_created", autoCreated);
                obj.put("builtin", builtin);
                obj.put("arch_filter", archFilter);
                obj.put("create_time", createTime);
            } catch (Exception e) {}
            return obj;
        }
        
        public static BuildTool fromJSON(JSONObject obj) {
            BuildTool t = new BuildTool();
            t.id = obj.optString("id");
            t.name = obj.optString("name");
            t.downloadUrl = obj.optString("download_url");
            t.projectType = obj.optString("project_type");
            t.compileCommand = obj.optString("compile_command");
            t.runCommand = obj.optString("run_command");
            t.enabled = obj.optBoolean("enabled", true);
            t.autoCreated = obj.optBoolean("auto_created", false);
            t.builtin = obj.optBoolean("builtin", false);
            t.archFilter = obj.optString("arch_filter", "all");
            t.createTime = obj.optLong("create_time", System.currentTimeMillis());
            return t;
        }

        /** 是否为内置工具链（compileCommand 以 internal: 开头） */
        public boolean isInternal() {
            return builtin || (compileCommand != null && compileCommand.startsWith("internal:"));
        }
        
        // 处理占位符: {file} {outpath} {project}
        public String resolveCompileCommand(String filePath, String outPath, String projectPath) {
            return compileCommand
                .replace("{file}", filePath)
                .replace("{outpath}", outPath)
                .replace("{project}", projectPath);
        }
        
        public String resolveRunCommand(String filePath, String outPath, String projectPath) {
            return runCommand
                .replace("{file}", filePath)
                .replace("{outpath}", outPath)
                .replace("{project}", projectPath);
        }
    }
    
    private BuildToolManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.tools = new ArrayList<>();
        this.listeners = new ArrayList<>();
        loadFromStorage();
        ensureBuiltinTools();
    }
    
    public static synchronized BuildToolManager getInstance(Context ctx) {
        if (instance == null) {
            instance = new BuildToolManager(ctx);
        }
        return instance;
    }
    
    // ===== 内置工具链预置（随 App 打包，无需下载） =====
    // 阶段一：内置 ECJ 编译器（Java 源码 → 编译检查 → 标准错误列表）
    private void ensureBuiltinTools() {
        boolean hasInternal = false;
        for (BuildTool t : tools) {
            if (t.isInternal()) { hasInternal = true; break; }
        }
        if (hasInternal) return;

        BuildTool ecj = new BuildTool();
        ecj.id = "builtin_ecj";
        ecj.name = "内置ECJ编译器（Java/Android）";
        ecj.projectType = "Android";
        ecj.compileCommand = "internal:ecj";
        ecj.runCommand = "internal:run";
        ecj.enabled = true;
        ecj.autoCreated = false;
        ecj.builtin = true;
        ecj.archFilter = "all";
        ecj.createTime = System.currentTimeMillis();
        tools.add(0, ecj);
        saveToStorage();
    }

    /** 查找内置工具（优先 Android 类型） */
    public BuildTool getBuiltinTool() {
        for (BuildTool t : tools) {
            if (t.isInternal() && t.enabled) return t;
        }
        return null;
    }

    // ===== 手动新增编译工具（文档 4.3.1） =====
    public BuildTool createManualTool(String name, String downloadUrl, 
                                      String projectType, String compileCmd,
                                      String runCmd, boolean enabled) {
        BuildTool tool = new BuildTool();
        tool.id = UUID.randomUUID().toString().substring(0, 8);
        tool.name = name;
        tool.downloadUrl = downloadUrl;
        tool.projectType = projectType;
        tool.compileCommand = compileCmd;
        tool.runCommand = runCmd;
        tool.enabled = enabled;
        tool.autoCreated = false;
        tool.archFilter = "all";
        tool.createTime = System.currentTimeMillis();
        
        tools.add(tool);
        saveToStorage();
        notifyAdded(tool);
        return tool;
    }
    
    // ===== AI自动创建编译工具（文档 4.1.3 + 7. autoCreateBuildTool） =====
    public BuildTool createAutoTool(JSONObject config) {
        BuildTool tool = BuildTool.fromJSON(config);
        if (tool.id == null || tool.id.isEmpty()) {
            tool.id = UUID.randomUUID().toString().substring(0, 8);
        }
        tool.autoCreated = true;
        tool.createTime = System.currentTimeMillis();
        
        // 检查是否已存在同名工具
        for (int i = 0; i < tools.size(); i++) {
            if (tools.get(i).name.equals(tool.name)) {
                tools.set(i, tool);
                saveToStorage();
                notifyUpdated(tool);
                return tool;
            }
        }
        
        tools.add(tool);
        saveToStorage();
        notifyAdded(tool);
        return tool;
    }
    
    // ===== 工具管理 =====
    public void updateTool(BuildTool tool) {
        for (int i = 0; i < tools.size(); i++) {
            if (tools.get(i).id.equals(tool.id)) {
                tools.set(i, tool);
                saveToStorage();
                notifyUpdated(tool);
                return;
            }
        }
    }
    
    public void deleteTool(String toolId) {
        for (int i = 0; i < tools.size(); i++) {
            if (tools.get(i).id.equals(toolId)) {
                if (tools.get(i).isInternal()) return; // 内置工具不可删除
                tools.remove(i);
                saveToStorage();
                notifyRemoved(toolId);
                return;
            }
        }
    }
    
    public void setEnabled(String toolId, boolean enabled) {
        for (BuildTool t : tools) {
            if (t.id.equals(toolId)) {
                t.enabled = enabled;
                saveToStorage();
                notifyUpdated(t);
                return;
            }
        }
    }
    
    // ===== 查询 =====
    public List<BuildTool> getAllTools() {
        return new ArrayList<>(tools);
    }
    
    public List<BuildTool> getToolsByType(String projectType) {
        List<BuildTool> result = new ArrayList<>();
        for (BuildTool t : tools) {
            if (t.projectType.equals(projectType) && t.enabled) {
                result.add(t);
            }
        }
        return result;
    }
    
    public BuildTool getToolById(String toolId) {
        for (BuildTool t : tools) {
            if (t.id.equals(toolId)) return t;
        }
        return null;
    }
    
    // ===== 下载工具二进制 =====
    public void downloadTool(BuildTool tool, DownloadCallback callback) {
        // 实现下载逻辑
        // 实际实现使用 HttpURLConnection 或 OkHttp
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 模拟下载
                    callback.onProgress(0);
                    // ... 实际下载代码 ...
                    callback.onProgress(100);
                    callback.onComplete();
                } catch (Exception e) {
                    callback.onError(e.getMessage());
                }
            }
        }).start();
    }
    
    public interface DownloadCallback {
        void onProgress(int percent);
        void onComplete();
        void onError(String msg);
    }
    
    // ===== 持久化 =====
    private void saveToStorage() {
        JSONArray arr = new JSONArray();
        for (BuildTool t : tools) {
            arr.put(t.toJSON());
        }
        prefs.edit().putString("tools_json", arr.toString()).apply();
    }
    
    private void loadFromStorage() {
        String json = prefs.getString("tools_json", "[]");
        try {
            JSONArray arr = new JSONArray(json);
            tools.clear();
            for (int i = 0; i < arr.length(); i++) {
                tools.add(BuildTool.fromJSON(arr.getJSONObject(i)));
            }
        } catch (Exception e) {
            // Ignore parse errors
        }
    }
    
    // ===== 导入/导出配置（文档 11.1） =====
    public String exportConfig() {
        JSONArray arr = new JSONArray();
        for (BuildTool t : tools) {
            arr.put(t.toJSON());
        }
        try {
            return arr.toString(2);
        } catch (Exception e) {
            return arr.toString();
        }
    }
    
    public int importConfig(String jsonStr) {
        int count = 0;
        try {
            JSONArray arr = new JSONArray(jsonStr);
            for (int i = 0; i < arr.length(); i++) {
                BuildTool t = BuildTool.fromJSON(arr.getJSONObject(i));
                t.id = UUID.randomUUID().toString().substring(0, 8);
                tools.add(t);
                count++;
            }
            saveToStorage();
        } catch (Exception e) {
            // Parse error
        }
        return count;
    }
    
    // ===== 监听器 =====
    public void addListener(ToolchainListener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }
    public void removeListener(ToolchainListener l) {
        listeners.remove(l);
    }
    private void notifyAdded(BuildTool t) {
        for (ToolchainListener l : listeners) l.onToolAdded(t);
    }
    private void notifyRemoved(String id) {
        for (ToolchainListener l : listeners) l.onToolRemoved(id);
    }
    private void notifyUpdated(BuildTool t) {
        for (ToolchainListener l : listeners) l.onToolUpdated(t);
    }
}
