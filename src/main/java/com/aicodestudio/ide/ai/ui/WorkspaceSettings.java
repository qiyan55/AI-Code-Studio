package com.aicodestudio.ide.ai.ui;

import com.aicodestudio.ide.ai.ui.AISettingsDialog;
import android.app.Activity;

import com.aicodestudio.ide.ai.core.AppConfig;
import com.aicodestudio.ide.ai.core.WorkspaceState;
import com.aicodestudio.ide.ai.models.ModelManageDialog;
import com.aicodestudio.ide.ai.tools.BuildToolManager;

/**
 * 三个工作区各自的设置弹窗
 *
 *   对话界面   → showModelSettings    模型 / API / 推理参数
 *   编辑器界面 → showEditorSettings   字号 / 换行 / 行号 / 高亮 / 缩进 / 检测
 *   编译界面   → showCompileSettings  工具链 / 自动保存 / 日志 / AI联动
 *
 * 每个弹窗只管自己那一摊，和底部三 Tab 的界面一一对应。
 */
public class WorkspaceSettings {

    // ==================================================================
    // 对话界面设置：模型相关
    // ==================================================================
    public static void showModelSettings(final Activity act) {
        final AppConfig cfg = AppConfig.getInstance(act);

        SettingsDialogBuilder b = new SettingsDialogBuilder(act);

        b.section("运行模式")
         .choose("模型运行模式",
                 new String[]{"云端 API", "本地模型"},
                 new String[]{"cloud", "local"},
                 cfg.getModelMode() == AppConfig.MODE_LOCAL ? "local" : "cloud",
                 new SettingsDialogBuilder.OnTextChanged() {
                     @Override public void onChanged(String v) {
                         cfg.setModelMode("local".equals(v)
                                 ? AppConfig.MODE_LOCAL : AppConfig.MODE_CLOUD);
                     }
                 });

        b.section("云端 API")
         .input("API Key", "sk-...", cfg.getApiKey(), true,
                 new SettingsDialogBuilder.OnTextChanged() {
                     @Override public void onChanged(String v) { cfg.setApiKey(v); }
                 })
         .input("Base URL", "https://api.openai.com/v1", cfg.getApiBase(), false,
                 new SettingsDialogBuilder.OnTextChanged() {
                     @Override public void onChanged(String v) { cfg.setApiBase(v); }
                 })
         .choose("模型",
                 new String[]{"GPT-4o", "GPT-4o mini", "Claude 3.5 Sonnet", "DeepSeek-V3", "通义千问"},
                 new String[]{"gpt-4o", "gpt-4o-mini", "claude-3-5-sonnet", "deepseek-v3", "qwen-max"},
                 cfg.getModelName(),
                 new SettingsDialogBuilder.OnTextChanged() {
                     @Override public void onChanged(String v) { cfg.setModelName(v); }
                 });

        b.section("生成参数")
         .slider("Temperature", 0, 20, (int) (cfg.getTemperature() * 10), "",
                 new SettingsDialogBuilder.OnValueChanged() {
                     @Override public void onChanged(int v) { cfg.setTemperature(v / 10f); }
                 })
         .slider("最大 Token", 256, 8192, cfg.getMaxTokens(), "",
                 new SettingsDialogBuilder.OnValueChanged() {
                     @Override public void onChanged(int v) { cfg.setMaxTokens(v); }
                 })
         .slider("上下文长度", 1024, 32768, cfg.getContextLength(), "",
                 new SettingsDialogBuilder.OnValueChanged() {
                     @Override public void onChanged(int v) { cfg.setContextLength(v); }
                 });

        b.section("AI 联动")
         .toggle("代码出错自动发给 AI", "检测到语法错误时自动推送，无需手动复制",
                 cfg.isAutoSendErrorToAI(),
                 new SettingsDialogBuilder.OnToggleChanged() {
                     @Override public void onChanged(boolean v) { cfg.setAutoSendErrorToAI(v); }
                 })
         .toggle("AI 修复自动覆盖源码", "关闭后每次覆盖前会弹窗确认",
                 cfg.isAutoOverwriteFix(),
                 new SettingsDialogBuilder.OnToggleChanged() {
                     @Override public void onChanged(boolean v) { cfg.setAutoOverwriteFix(v); }
                 });

        b.note("本地模型需要导入 GGUF 文件，点「模型管理」进入。\n"
                + "任何兼容 OpenAI 格式的接口都可填自定义 Base URL。");

        b.show("对话设置", new SettingsDialogBuilder.OnConfirmListener() {
            @Override public void onConfirm() {
                new AISettingsDialog(act).show();
            }
        });
    }

    // ==================================================================
    // 编辑器界面设置：编辑器相关
    // ==================================================================
    public static void showEditorSettings(final Activity act) {
        final AppConfig cfg = AppConfig.getInstance(act);

        SettingsDialogBuilder b = new SettingsDialogBuilder(act);

        b.section("显示")
         .slider("字体大小", 10, 22, cfg.getFontSize(), " sp",
                 new SettingsDialogBuilder.OnValueChanged() {
                     @Override public void onChanged(int v) { cfg.setFontSize(v); }
                 })
         .toggle("自动换行", "长代码行自动折行，避免横向滚动",
                 cfg.isWordWrap(),
                 new SettingsDialogBuilder.OnToggleChanged() {
                     @Override public void onChanged(boolean v) { cfg.setWordWrap(v); }
                 })
         .toggle("显示行号", "左侧显示行号栏",
                 cfg.isShowLineNumber(),
                 new SettingsDialogBuilder.OnToggleChanged() {
                     @Override public void onChanged(boolean v) { cfg.setShowLineNumber(v); }
                 });

        b.section("语法")
         .toggle("语法高亮", "关键字、字符串、注释分色显示",
                 cfg.isSyntaxHighlight(),
                 new SettingsDialogBuilder.OnToggleChanged() {
                     @Override public void onChanged(boolean v) { cfg.setSyntaxHighlight(v); }
                 })
         .choose("高亮引擎",
                 new String[]{"内置引擎", "第三方工具链（预留）"},
                 new String[]{"0", "1"},
                 String.valueOf(cfg.getHighlightEngine()),
                 new SettingsDialogBuilder.OnTextChanged() {
                     @Override public void onChanged(String v) {
                         try { cfg.setHighlightEngine(Integer.parseInt(v)); }
                         catch (Throwable ignored) {}
                     }
                 })
         .choose("代码自动补全",
                 new String[]{"关闭", "本地关键词", "AI 智能补全"},
                 new String[]{"0", "1", "2"},
                 String.valueOf(cfg.getAutoCompleteMode()),
                 new SettingsDialogBuilder.OnTextChanged() {
                     @Override public void onChanged(String v) {
                         try { cfg.setAutoCompleteMode(Integer.parseInt(v)); }
                         catch (Throwable ignored) {}
                     }
                 });

        b.section("缩进")
         .slider("缩进宽度", 2, 8, cfg.getIndentWidth(), " 空格",
                 new SettingsDialogBuilder.OnValueChanged() {
                     @Override public void onChanged(int v) { cfg.setIndentWidth(v); }
                 })
         .toggle("Tab 转空格", "按 Tab 键输入空格而非制表符",
                 cfg.isTabToSpace(),
                 new SettingsDialogBuilder.OnToggleChanged() {
                     @Override public void onChanged(boolean v) { cfg.setTabToSpace(v); }
                 });

        b.section("语法检测")
         .toggle("函数写完自动检测", "3.1 输入完成停顿后自动检测当前函数体",
                 cfg.isDetectOnFunctionFinish(),
                 new SettingsDialogBuilder.OnToggleChanged() {
                     @Override public void onChanged(boolean v) { cfg.setDetectOnFunctionFinish(v); }
                 })
         .toggle("文件编辑完成整体检测", "对整个文件做一次完整分析",
                 cfg.isDetectOnFileFinish(),
                 new SettingsDialogBuilder.OnToggleChanged() {
                     @Override public void onChanged(boolean v) { cfg.setDetectOnFileFinish(v); }
                 })
         .slider("检测防抖延迟", 200, 3000, cfg.getDebounceMs(), " ms",
                 new SettingsDialogBuilder.OnValueChanged() {
                     @Override public void onChanged(int v) { cfg.setDebounceMs(v); }
                 })
         .toggle("文件快照", "保存编辑历史，可回滚到上一版本",
                 cfg.isSnapshotEnabled(),
                 new SettingsDialogBuilder.OnToggleChanged() {
                     @Override public void onChanged(boolean v) { cfg.setSnapshotEnabled(v); }
                 });

        b.show("编辑器设置");
    }

    // ==================================================================
    // 编译界面设置：编译相关
    // ==================================================================
    public static void showCompileSettings(final Activity act) {
        final AppConfig cfg = AppConfig.getInstance(act);

        SettingsDialogBuilder b = new SettingsDialogBuilder(act);

        // 当前工具链概况
        String toolName = "未配置";
        try {
            BuildToolManager mgr = BuildToolManager.getInstance(act);
            String id = WorkspaceState.get().getActiveToolId();
            if (id.length() > 0) {
                BuildToolManager.BuildTool t = mgr.getToolById(id);
                if (t != null) toolName = t.name;
            }
        } catch (Throwable ignored) {}

        b.section("工具链")
         .note("当前工具：" + toolName
                 + "\n项目类型：" + WorkspaceState.get().getProjectType()
                 + "\n在编译界面点右上角工具图标可管理工具链。");

        b.section("编译行为")
         .toggle("编译前自动保存", "避免编译的是未保存的旧代码",
                 cfg.isAutoSaveBeforeCompile(),
                 new SettingsDialogBuilder.OnToggleChanged() {
                     @Override public void onChanged(boolean v) { cfg.setAutoSaveBeforeCompile(v); }
                 })
         .slider("日志最大行数", 500, 10000, cfg.getLogMaxLines(), " 行",
                 new SettingsDialogBuilder.OnValueChanged() {
                     @Override public void onChanged(int v) { cfg.setLogMaxLines(v); }
                 });

        b.section("AI 联动")
         .toggle("编译失败自动推送日志给 AI", "9.5 失败后自动把日志交给 AI 分析修复",
                 cfg.isAutoSendCompileLog(),
                 new SettingsDialogBuilder.OnToggleChanged() {
                     @Override public void onChanged(boolean v) { cfg.setAutoSendCompileLog(v); }
                 })
         .toggle("允许 AI 自动创建编译工具", "9.6 AI 可自行下载配置缺失的工具链",
                 cfg.isAllowAIAutoCreateTool(),
                 new SettingsDialogBuilder.OnToggleChanged() {
                     @Override public void onChanged(boolean v) { cfg.setAllowAIAutoCreateTool(v); }
                 })
         .toggle("AI 创建工具需确认", "关闭后 AI 直接创建，不再弹窗",
                 cfg.isAIToolCreateNeedConfirm(),
                 new SettingsDialogBuilder.OnToggleChanged() {
                     @Override public void onChanged(boolean v) { cfg.setAIToolCreateNeedConfirm(v); }
                 })
         .toggle("允许 AI 下载二进制文件", "涉及体积较大的工具包下载",
                 cfg.isAllowAIDownloadBinary(),
                 new SettingsDialogBuilder.OnToggleChanged() {
                     @Override public void onChanged(boolean v) { cfg.setAllowAIDownloadBinary(v); }
                 });

        b.show("编译设置");
    }
}
