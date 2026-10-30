package com.aicodestudio.ide.ai.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/**
 * 全局系统配置项
 * 实现文档 第9章 全局系统配置项 (10项) + 第10章 非功能性需求相关开关
 *
 * 所有配置持久化到 SharedPreferences，全应用共享读写。
 */
public class AppConfig {

    private static final String PREFS = "aicodestudio_config";
    private static AppConfig instance;
    private final SharedPreferences prefs;
    private final Context appContext;

    // ===== 文档 9.1 代码检测出错后，自动发送错误信息给AI =====
    private static final String K_AUTO_SEND_ERROR = "auto_send_error_to_ai";
    // ===== 文档 9.2 函数编写完成自动执行语法检测 =====
    private static final String K_DETECT_FUNCTION = "detect_on_function_finish";
    // ===== 文档 9.3 文件编辑完成后整体检测全部代码 =====
    private static final String K_DETECT_FILE = "detect_on_file_finish";
    // ===== 文档 9.4 AI修复代码自动覆盖源码 / 需要弹窗确认 =====
    private static final String K_AUTO_OVERWRITE = "auto_overwrite_fix";
    // ===== 文档 9.5 编译失败自动推送日志至AI =====
    private static final String K_AUTO_SEND_LOG = "auto_send_compile_log";
    // ===== 文档 9.6 允许AI自动新增编译工具配置 =====
    private static final String K_ALLOW_AI_TOOL = "allow_ai_auto_create_tool";
    // ===== 文档 9.7 语法高亮引擎切换选项 =====
    private static final String K_HIGHLIGHT_ENGINE = "highlight_engine";
    // ===== 文档 9.8 代码自动补全开关 =====
    private static final String K_AUTOCOMPLETE = "autocomplete_mode";
    // ===== 文档 9.9 模型运行模式切换 =====
    private static final String K_MODEL_MODE = "model_mode";
    // ===== 文档 9.10 本地模型推理参数 =====
    private static final String K_CTX_LEN = "local_ctx_length";
    private static final String K_TEMPERATURE = "local_temperature";
    private static final String K_MAX_TOKENS = "local_max_tokens";
    private static final String K_THREADS = "local_threads";

    // ===== 第10章 非功能性需求相关 =====
    private static final String K_DEBOUNCE = "detect_debounce_ms";
    private static final String K_SNAPSHOT = "enable_file_snapshot";
    private static final String K_ARCH = "cpu_arch";
    private static final String K_ALLOW_DOWNLOAD = "allow_ai_download_binary";
    private static final String K_AI_AUTO_CREATE_CONFIRM = "ai_tool_create_confirm";

    // ===== AI 接口配置 =====
    private static final String K_API_KEY = "ai_api_key";
    private static final String K_API_BASE = "ai_api_base";
    private static final String K_MODEL_NAME = "ai_model_name";

    // ===== 编辑器界面设置 =====
    private static final String K_FONT_SIZE = "editor_font_size";
    private static final String K_WORD_WRAP = "editor_word_wrap";
    private static final String K_LINE_NUMBER = "editor_line_number";
    private static final String K_SYNTAX_HL = "editor_syntax_highlight";
    private static final String K_INDENT_WIDTH = "editor_indent_width";
    private static final String K_TAB_SPACE = "editor_tab_to_space";

    // ===== 外观设置 =====
    private static final String K_THEME = "ui_theme";

    // ===== 编译界面设置 =====
    private static final String K_AUTO_SAVE = "compile_auto_save";
    private static final String K_LOG_LINES = "compile_log_max_lines";

    // ===== 最近项目列表 =====
    private static final String K_RECENT_PROJECTS = "recent_projects";

    // 语法高亮引擎常量
    public static final int ENGINE_BUILTIN = 0;   // 内置高亮引擎
    public static final int ENGINE_THIRD_PARTY = 1; // 第三方工具链（预留扩展）

    // 补全模式常量
    public static final int COMPLETE_OFF = 0;
    public static final int COMPLETE_LOCAL = 1;   // 本地静态关键词补全
    public static final int COMPLETE_AI = 2;      // AI 智能补全

    // 主题常量
    public static final int THEME_DARK = 0;
    public static final int THEME_LIGHT = 1;
    public static final int THEME_SYSTEM = 2;

    // 模型模式常量
    public static final int MODE_CLOUD = 0;
    public static final int MODE_LOCAL = 1;

    private AppConfig(Context ctx) {
        appContext = ctx.getApplicationContext();
        prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized AppConfig getInstance(Context ctx) {
        if (instance == null) instance = new AppConfig(ctx);
        return instance;
    }


    // ===== 应用状态恢复（防系统杀后台丢进度）=====
    public void saveActiveState(int tab, String sessionId, String filePath, String code) {
        prefs.edit()
             .putInt("last_active_tab", tab)
             .putString("last_session_id", sessionId)
             .putString("last_file_path", filePath)
             .putString("last_file_code", code)
             .apply();
    }
    public int getLastActiveTab() { return prefs.getInt("last_active_tab", -1); }
    public String getLastSessionId() { return prefs.getString("last_session_id", ""); }
    public String getLastFilePath() { return prefs.getString("last_file_path", ""); }
    public String getLastFileCode() { return prefs.getString("last_file_code", ""); }
    public void clearActiveState() { prefs.edit().putInt("last_active_tab", -1).apply(); }

    // ===== 9.1 代码错误自动发送AI =====
    public boolean isAutoSendErrorToAI() {
        return prefs.getBoolean(K_AUTO_SEND_ERROR, true);
    }
    public void setAutoSendErrorToAI(boolean v) {
        prefs.edit().putBoolean(K_AUTO_SEND_ERROR, v).apply();
    }

    // ===== 9.2 函数完成自动检测 =====
    public boolean isDetectOnFunctionFinish() {
        return prefs.getBoolean(K_DETECT_FUNCTION, true);
    }
    public void setDetectOnFunctionFinish(boolean v) {
        prefs.edit().putBoolean(K_DETECT_FUNCTION, v).apply();
    }

    // ===== 9.3 文件完成整体检测 =====
    public boolean isDetectOnFileFinish() {
        return prefs.getBoolean(K_DETECT_FILE, true);
    }
    public void setDetectOnFileFinish(boolean v) {
        prefs.edit().putBoolean(K_DETECT_FILE, v).apply();
    }

    // ===== 9.4 AI修复代码自动覆盖 =====
    public boolean isAutoOverwriteFix() {
        return prefs.getBoolean(K_AUTO_OVERWRITE, false);
    }
    public void setAutoOverwriteFix(boolean v) {
        prefs.edit().putBoolean(K_AUTO_OVERWRITE, v).apply();
    }

    // ===== 9.5 编译失败自动推送日志 =====
    public boolean isAutoSendCompileLog() {
        return prefs.getBoolean(K_AUTO_SEND_LOG, true);
    }
    public void setAutoSendCompileLog(boolean v) {
        prefs.edit().putBoolean(K_AUTO_SEND_LOG, v).apply();
    }

    // ===== 9.6 允许AI自动新增编译工具 =====
    public boolean isAllowAIAutoCreateTool() {
        return prefs.getBoolean(K_ALLOW_AI_TOOL, true);
    }
    public void setAllowAIAutoCreateTool(boolean v) {
        prefs.edit().putBoolean(K_ALLOW_AI_TOOL, v).apply();
    }

    // ===== 9.7 语法高亮引擎 =====
    public int getHighlightEngine() {
        return prefs.getInt(K_HIGHLIGHT_ENGINE, ENGINE_BUILTIN);
    }
    public void setHighlightEngine(int engine) {
        prefs.edit().putInt(K_HIGHLIGHT_ENGINE, engine).apply();
    }

    // ===== 9.8 自动补全模式 =====
    public int getAutoCompleteMode() {
        return prefs.getInt(K_AUTOCOMPLETE, COMPLETE_LOCAL);
    }
    public void setAutoCompleteMode(int mode) {
        prefs.edit().putInt(K_AUTOCOMPLETE, mode).apply();
    }

    // ===== 9.9 模型运行模式 =====
    public int getModelMode() {
        return prefs.getInt(K_MODEL_MODE, MODE_CLOUD);
    }
    public void setModelMode(int mode) {
        prefs.edit().putInt(K_MODEL_MODE, mode).apply();
    }

    // ===== 9.10 本地模型推理参数 =====
    public int getContextLength() {
        return prefs.getInt(K_CTX_LEN, 4096);
    }
    public void setContextLength(int v) {
        prefs.edit().putInt(K_CTX_LEN, v).apply();
    }

    public float getTemperature() {
        return prefs.getFloat(K_TEMPERATURE, 0.8f);  // v1.51ab：llama.cpp 官方默认 0.8
    }
    public void setTemperature(float v) {
        prefs.edit().putFloat(K_TEMPERATURE, v).apply();
    }

    // v1.51ab：repeat_penalty（llama.cpp 官方默认 1.1）
    private static final String K_REPEAT_PENALTY = "local_repeat_penalty";
    public float getRepeatPenalty() {
        return prefs.getFloat(K_REPEAT_PENALTY, 1.0f);  // llama.cpp 源码默认 1.0（关闭）
    }
    public void setRepeatPenalty(float v) {
        prefs.edit().putFloat(K_REPEAT_PENALTY, v).apply();
    }

    public int getMaxTokens() {
        return prefs.getInt(K_MAX_TOKENS, 8192);
    }
    public void setMaxTokens(int v) {
        prefs.edit().putInt(K_MAX_TOKENS, v).apply();
    }

    // ===== v1.46 系统提示词自定义（本地模型配置界面可编辑）=====
    // null/空 = 使用代码内置默认提示词；非空 = 用户自定义，完整替换内置（不合并）
    private static final String K_SYSTEM_PROMPT = "system_prompt_override";

    public String getSystemPromptOverride() {
        String v = prefs.getString(K_SYSTEM_PROMPT, "");
        return (v == null || v.trim().isEmpty()) ? null : v;
    }
    public void setSystemPromptOverride(String v) {
        prefs.edit().putString(K_SYSTEM_PROMPT, v == null ? "" : v).apply();
    }

    // ===== v1.134 分阶段提示词可编辑：L1常驻 / 写文档规范 =====
    // 空 = 用代码内置默认；非空 = 用户自定义
    private static final String K_L1_PROMPT   = "l1_prompt_override";
    private static final String K_DOC_GUIDE   = "doc_guide_override";

    public String getL1PromptOverride() {
        String v = prefs.getString(K_L1_PROMPT, "");
        return (v == null || v.trim().isEmpty()) ? null : v;
    }
    public void setL1PromptOverride(String v) {
        prefs.edit().putString(K_L1_PROMPT, v == null ? "" : v).apply();
    }
    public String getDocGuideOverride() {
        String v = prefs.getString(K_DOC_GUIDE, "");
        return (v == null || v.trim().isEmpty()) ? null : v;
    }
    public void setDocGuideOverride(String v) {
        prefs.edit().putString(K_DOC_GUIDE, v == null ? "" : v).apply();
    }

    public int getThreadCount() {
        return prefs.getInt(K_THREADS, 4);
    }
    public void setThreadCount(int v) {
        prefs.edit().putInt(K_THREADS, v).apply();
    }

    // ===== 第10章 非功能性 =====
    public int getDebounceMs() {
        return prefs.getInt(K_DEBOUNCE, 1500);
    }
    public void setDebounceMs(int ms) {
        prefs.edit().putInt(K_DEBOUNCE, ms).apply();
    }

    public boolean isSnapshotEnabled() {
        return prefs.getBoolean(K_SNAPSHOT, true);
    }
    public void setSnapshotEnabled(boolean v) {
        prefs.edit().putBoolean(K_SNAPSHOT, v).apply();
    }

    public String getCpuArch() {
        return prefs.getString(K_ARCH, detectArch());
    }
    public void setCpuArch(String arch) {
        prefs.edit().putString(K_ARCH, arch).apply();
    }

    public boolean isAllowAIDownloadBinary() {
        return prefs.getBoolean(K_ALLOW_DOWNLOAD, false);
    }
    public void setAllowAIDownloadBinary(boolean v) {
        prefs.edit().putBoolean(K_ALLOW_DOWNLOAD, v).apply();
    }

    /** AI自动创建工具策略：true=预览确认后创建，false=静默自动创建 */
    public boolean isAIToolCreateNeedConfirm() {
        return prefs.getBoolean(K_AI_AUTO_CREATE_CONFIRM, true);
    }
    public void setAIToolCreateNeedConfirm(boolean v) {
        prefs.edit().putBoolean(K_AI_AUTO_CREATE_CONFIRM, v).apply();
    }

    // ===== AI 接口配置 =====
    public String getApiKey() {
        return prefs.getString(K_API_KEY, "");
    }
    public void setApiKey(String key) {
        prefs.edit().putString(K_API_KEY, key).apply();
    }
    public boolean hasApiKey() {
        String k = getApiKey();
        return k != null && k.length() > 0;
    }

    public String getApiBase() {
        return prefs.getString(K_API_BASE, "https://api.openai.com/v1");
    }
    public void setApiBase(String url) {
        prefs.edit().putString(K_API_BASE, url).apply();
    }

    public String getModelName() {
        return prefs.getString(K_MODEL_NAME, "gpt-4o");
    }
    public void setModelName(String name) {
        prefs.edit().putString(K_MODEL_NAME, name).apply();
    }

    // ===== 编辑器界面设置 =====

    public int getFontSize() {
        return prefs.getInt(K_FONT_SIZE, 13);
    }
    public void setFontSize(int size) {
        prefs.edit().putInt(K_FONT_SIZE, size).apply();
    }

    public boolean isWordWrap() {
        return prefs.getBoolean(K_WORD_WRAP, true);
    }
    public void setWordWrap(boolean v) {
        prefs.edit().putBoolean(K_WORD_WRAP, v).apply();
    }

    public boolean isShowLineNumber() {
        return prefs.getBoolean(K_LINE_NUMBER, true);
    }
    public void setShowLineNumber(boolean v) {
        prefs.edit().putBoolean(K_LINE_NUMBER, v).apply();
    }

    public boolean isSyntaxHighlight() {
        return prefs.getBoolean(K_SYNTAX_HL, true);
    }
    public void setSyntaxHighlight(boolean v) {
        prefs.edit().putBoolean(K_SYNTAX_HL, v).apply();
    }

    public int getIndentWidth() {
        return prefs.getInt(K_INDENT_WIDTH, 4);
    }
    public void setIndentWidth(int v) {
        prefs.edit().putInt(K_INDENT_WIDTH, v).apply();
    }

    public boolean isTabToSpace() {
        return prefs.getBoolean(K_TAB_SPACE, true);
    }
    public void setTabToSpace(boolean v) {
        prefs.edit().putBoolean(K_TAB_SPACE, v).apply();
    }

    // ===== 外观设置 =====
    public int getTheme() {
        return prefs.getInt(K_THEME, THEME_DARK);
    }
    public void setTheme(int theme) {
        prefs.edit().putInt(K_THEME, theme).apply();
    }

    // ===== 9.6 AI 自动创建编译工具是否需要确认 =====
    public boolean isAIToolCreateConfirm() {
        return prefs.getBoolean(K_AI_AUTO_CREATE_CONFIRM, true);
    }
    public void setAIToolCreateConfirm(boolean v) {
        prefs.edit().putBoolean(K_AI_AUTO_CREATE_CONFIRM, v).apply();
    }

    // ===== 编译界面设置 =====

    public boolean isAutoSaveBeforeCompile() {
        return prefs.getBoolean(K_AUTO_SAVE, true);
    }
    public void setAutoSaveBeforeCompile(boolean v) {
        prefs.edit().putBoolean(K_AUTO_SAVE, v).apply();
    }

    public int getLogMaxLines() {
        return prefs.getInt(K_LOG_LINES, 2000);
    }
    public void setLogMaxLines(int v) {
        prefs.edit().putInt(K_LOG_LINES, v).apply();
    }

    // ===== 最近项目列表 =====

    /** 返回最近项目路径列表（最新的在前） */
    public java.util.List<String> getRecentProjects() {
        String raw = prefs.getString(K_RECENT_PROJECTS, "");
        java.util.List<String> list = new java.util.ArrayList<String>();
        if (raw == null || raw.length() == 0) return list;
        String[] parts = raw.split("\\|");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].length() > 0 && new java.io.File(parts[i]).exists()) {
                list.add(parts[i]);
            }
        }
        return list;
    }

    /** 记录一个项目到最近列表（最多 10 个） */
    public void addRecentProject(String path) {
        if (path == null || path.length() == 0) return;
        java.util.List<String> list = getRecentProjects();
        list.remove(path);
        list.add(0, path);
        while (list.size() > 10) list.remove(list.size() - 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append("|");
            sb.append(list.get(i));
        }
        prefs.edit().putString(K_RECENT_PROJECTS, sb.toString()).apply();
    }

    /**
     * 返回所有项目路径列表（最新使用的在前）：
     * 1. 最近项目记录（SharedPreferences，最新在前）
     * 2. 扫描项目根目录自动发现（应用专属目录 projects/ + 公共目录 AI Code Studio/projects/）
     *
     * 旧版本创建项目时没有写入最近记录，只扫描目录才能把它们找回来；
     * 也兼容卸载重装后记录丢失但目录仍在的情况。
     */
    public java.util.List<String> getAllProjects() {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<String>();

        // 1. 最近项目记录（保持"最新在前"顺序）
        for (String p : getRecentProjects()) {
            if (p != null && p.length() > 0) set.add(p);
        }

        // 2. 扫描项目根目录（旧应用专属目录 + 公共 SD 卡目录）
        java.util.List<File> roots = new java.util.ArrayList<File>();
        try {
            File appDir = appContext.getExternalFilesDir(null);
            if (appDir != null) roots.add(new File(appDir, "projects"));
        } catch (Throwable ignored) {
        }
        try {
            roots.add(new File(android.os.Environment.getExternalStorageDirectory(),
                    "AI Code Studio/projects"));
        } catch (Throwable ignored) {
        }
        for (File root : roots) {
            try {
                if (root == null || !root.exists() || !root.isDirectory()) continue;
                File[] children = root.listFiles();
                if (children == null) continue;
                for (File c : children) {
                    if (c.isDirectory()) set.add(c.getAbsolutePath());
                }
            } catch (Throwable ignored) {
            }
        }

        // 3. 只保留仍然存在的目录
        java.util.List<String> result = new java.util.ArrayList<String>();
        for (String p : set) {
            try {
                if (p != null && p.length() > 0 && new java.io.File(p).exists()) {
                    result.add(p);
                }
            } catch (Throwable ignored) {
            }
        }
        return result;
    }

    public void removeRecentProject(String path) {
        java.util.List<String> list = getRecentProjects();
        list.remove(path);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append("|");
            sb.append(list.get(i));
        }
        prefs.edit().putString(K_RECENT_PROJECTS, sb.toString()).apply();
    }

    private static String detectArch() {
        String abi = android.os.Build.SUPPORTED_ABIS != null
                && android.os.Build.SUPPORTED_ABIS.length > 0
                ? android.os.Build.SUPPORTED_ABIS[0] : "arm64-v8a";
        return abi;
    }
}
