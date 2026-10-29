package com.aicodestudio.ide.ai.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.aicodestudio.ide.ai.core.AppConfig;

/**
 * 系统全局设置弹窗（原 SettingsActivity 的弹窗版）
 *
 * 顶部四个分类标签：AI 联动 / 编辑器 / 编译 / 外观
 * 点标签切换内容区，内容区由 SettingsDialogBuilder 生成并自动限高滚动。
 *
 * 覆盖文档第 9 章全部配置项 + 第 10 章非功能性开关。
 */
public class GlobalSettingsDialog {

    private static final int BG = 0xFF0D1117;
    private static final int TAB_BG = 0xFF161B22;
    private static final int TEXT_MAIN = 0xFFE6EDF3;
    private static final int TEXT_SUB = 0xFF8B949E;
    private static final int ACCENT = 0xFF2196F3;

    private static final String[] TABS = {"AI 联动", "编辑器", "编译", "外观"};

    private final Activity act;
    private final AppConfig cfg;

    private AlertDialog dialog;
    private LinearLayout contentBox;
    private TextView[] tabViews;
    private int current = 0;

    public GlobalSettingsDialog(Activity act) {
        this.act = act;
        this.cfg = AppConfig.getInstance(act);
    }

    public void show() {
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(10), dp(4), dp(10), dp(2));

        // ===== 分类标签 =====
        LinearLayout tabBar = new LinearLayout(act);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setGravity(Gravity.CENTER);
        tabBar.setBackgroundDrawable(rounded(TAB_BG, dp(8)));
        tabBar.setPadding(dp(3), dp(3), dp(3), dp(3));
        tabBar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        tabViews = new TextView[TABS.length];
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            TextView tv = new TextView(act);
            tv.setText(TABS[i]);
            tv.setTextSize(12);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(dp(8), dp(7), dp(8), dp(7));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.leftMargin = dp(2);
            lp.rightMargin = dp(2);
            tv.setLayoutParams(lp);
            tv.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { switchTab(idx); }
            });
            tabViews[i] = tv;
            tabBar.addView(tv);
        }
        root.addView(tabBar);

        // ===== 内容区（切换时整体替换）=====
        contentBox = new LinearLayout(act);
        contentBox.setOrientation(LinearLayout.VERTICAL);
        contentBox.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(contentBox);

        dialog = new AppleDialog.Builder(act)
                .setTitle("系统设置")
                .setView(root)
                .setNegativeButton("关闭", null)
                .show();

        switchTab(0);
    }

    private void switchTab(int idx) {
        current = idx;
        for (int i = 0; i < tabViews.length; i++) {
            boolean sel = (i == idx);
            tabViews[i].setTextColor(sel ? Color.WHITE : TEXT_SUB);
            tabViews[i].setBackgroundDrawable(sel ? rounded(ACCENT, dp(6)) : null);
            tabViews[i].setTypeface(null, sel ? android.graphics.Typeface.BOLD
                    : android.graphics.Typeface.NORMAL);
        }

        contentBox.removeAllViews();
        SettingsDialogBuilder b = new SettingsDialogBuilder(act);

        switch (idx) {
            case 0: buildAiSection(b); break;
            case 1: buildEditorSection(b); break;
            case 2: buildCompileSection(b); break;
            default: buildAppearanceSection(b); break;
        }

        // SettingsDialogBuilder 自身带滚动容器，这里直接取它的内容视图
        contentBox.addView(b.build());
    }

    // ==================================================================
    // 分类 1：AI 联动（文档 9.1 ~ 9.6、9.10）
    // ==================================================================
    private void buildAiSection(SettingsDialogBuilder b) {
        b.section("代码检测")
         .toggle("函数完成自动检测", "写完一个函数后自动检查语法",
                 cfg.isDetectOnFunctionFinish(),
                 new SettingsDialogBuilder.OnToggleChanged() {
                     @Override public void onChanged(boolean v) {
                         cfg.setDetectOnFunctionFinish(v);
                     }
                 })
         .toggle("文件完成整体检测", "整个文件写完后做一次完整检查",
                 cfg.isDetectOnFileFinish(),
                 new SettingsDialogBuilder.OnToggleChanged() {
                     @Override public void onChanged(boolean v) {
                         cfg.setDetectOnFileFinish(v);
                     }
                 });

        b.section("AI 联动")
         .toggle("代码出错自动发给 AI", "检测到语法错误时自动推送，无需手动复制",
                 cfg.isAutoSendErrorToAI(),
                 new SettingsDialogBuilder.OnToggleChanged() {
                     @Override public void onChanged(boolean v) {
                         cfg.setAutoSendErrorToAI(v);
                     }
                 })
         .toggle("编译失败自动推送日志", "编译报错时自动把日志交给 AI 分析",
                 cfg.isAutoSendCompileLog(),
                 new SettingsDialogBuilder.OnToggleChanged() {
                     @Override public void onChanged(boolean v) {
                         cfg.setAutoSendCompileLog(v);
                     }
                 })
         .toggle("AI 修复自动覆盖源码", "关闭后每次覆盖前会弹窗确认",
                 cfg.isAutoOverwriteFix(),
                 new SettingsDialogBuilder.OnToggleChanged() {
                     @Override public void onChanged(boolean v) {
                         cfg.setAutoOverwriteFix(v);
                     }
                 });

        b.section("AI 权限")
         .toggle("允许 AI 自动新增编译工具",
                 "AI 可自行创建项目所需的编译工具链",
                 cfg.isAllowAIAutoCreateTool(),
                 new SettingsDialogBuilder.OnToggleChanged() {
                     @Override public void onChanged(boolean v) {
                         cfg.setAllowAIAutoCreateTool(v);
                     }
                 })
         .toggle("创建工具前先确认",
                 "AI 创建编译工具时弹出确认，而不是直接写入",
                 cfg.isAIToolCreateConfirm(),
                 new SettingsDialogBuilder.OnToggleChanged() {
                     @Override public void onChanged(boolean v) {
                         cfg.setAIToolCreateConfirm(v);
                     }
                 })
         .toggle("允许 AI 下载编译二进制",
                 "允许联网下载编译所需的工具包",
                 cfg.isAllowAIDownloadBinary(),
                 new SettingsDialogBuilder.OnToggleChanged() {
                     @Override public void onChanged(boolean v) {
                         cfg.setAllowAIDownloadBinary(v);
                     }
                 });
    }

    // ==================================================================
    // 分类 2：编辑器
    // ==================================================================
    private void buildEditorSection(SettingsDialogBuilder b) {
        b.section("显示")
         .toggle("自动换行", "长行自动折行，避免横向滚动",
                 cfg.isWordWrap(),
                 new SettingsDialogBuilder.OnToggleChanged() {
                     @Override public void onChanged(boolean v) {
                         cfg.setWordWrap(v);
                     }
                 })
         .toggle("显示行号", "左侧显示行号栏",
                 cfg.isShowLineNumber(),
                 new SettingsDialogBuilder.OnToggleChanged() {
                     @Override public void onChanged(boolean v) {
                         cfg.setShowLineNumber(v);
                     }
                 })
         .toggle("语法高亮", "关键字、字符串、注释分色显示",
                 cfg.isSyntaxHighlight(),
                 new SettingsDialogBuilder.OnToggleChanged() {
                     @Override public void onChanged(boolean v) {
                         cfg.setSyntaxHighlight(v);
                     }
                 });

        b.section("高亮与补全")
         .choose("语法高亮引擎",
                 new String[]{"内置高亮", "Tree-sitter", "自定义"},
                 new String[]{"builtin", "treesitter", "custom"},
                 engineValue(cfg.getHighlightEngine()),
                 new SettingsDialogBuilder.OnTextChanged() {
                     @Override public void onChanged(String v) {
                         cfg.setHighlightEngine("treesitter".equals(v)
                                 ? AppConfig.ENGINE_THIRD_PARTY
                                 : AppConfig.ENGINE_BUILTIN);
                     }
                 })
         .choose("自动补全模式",
                 new String[]{"关闭", "本地关键词", "AI 智能补全"},
                 new String[]{"off", "local", "ai"},
                 completeValue(cfg.getAutoCompleteMode()),
                 new SettingsDialogBuilder.OnTextChanged() {
                     @Override public void onChanged(String v) {
                         if ("off".equals(v)) cfg.setAutoCompleteMode(AppConfig.COMPLETE_OFF);
                         else if ("ai".equals(v)) cfg.setAutoCompleteMode(AppConfig.COMPLETE_AI);
                         else cfg.setAutoCompleteMode(AppConfig.COMPLETE_LOCAL);
                     }
                 });

        b.section("缩进")
         .slider("缩进宽度", 2, 8, cfg.getIndentWidth(), " 空格",
                 new SettingsDialogBuilder.OnValueChanged() {
                     @Override public void onChanged(int v) { cfg.setIndentWidth(v); }
                 })
         .toggle("Tab 自动转空格", "按 Tab 键插入空格而非制表符",
                 cfg.isTabToSpace(),
                 new SettingsDialogBuilder.OnToggleChanged() {
                     @Override public void onChanged(boolean v) {
                         cfg.setTabToSpace(v);
                     }
                 });
    }

    // ==================================================================
    // 分类 3：编译
    // ==================================================================
    private void buildCompileSection(SettingsDialogBuilder b) {
        b.section("编译行为")
         .toggle("编译前自动保存", "运行编译前先保存所有已修改文件",
                 cfg.isAutoSaveBeforeCompile(),
                 new SettingsDialogBuilder.OnToggleChanged() {
                     @Override public void onChanged(boolean v) {
                         cfg.setAutoSaveBeforeCompile(v);
                     }
                 })
         .slider("日志最大行数", 500, 10000, cfg.getLogMaxLines(), " 行",
                 new SettingsDialogBuilder.OnValueChanged() {
                     @Override public void onChanged(int v) { cfg.setLogMaxLines(v); }
                 });

        b.note("日志行数上限用于防止长时间编译把内存占满，"
                + "超出后自动丢弃最早的日志。");
    }

    // ==================================================================
    // 分类 4：外观
    // ==================================================================
    private void buildAppearanceSection(SettingsDialogBuilder b) {
        b.section("主题")
         .choose("界面主题",
                 new String[]{"深色", "浅色", "跟随系统"},
                 new String[]{"dark", "light", "system"},
                 themeValue(cfg.getTheme()),
                 new SettingsDialogBuilder.OnTextChanged() {
                     @Override public void onChanged(String v) {
                         if ("light".equals(v)) cfg.setTheme(AppConfig.THEME_LIGHT);
                         else if ("system".equals(v)) cfg.setTheme(AppConfig.THEME_SYSTEM);
                         else cfg.setTheme(AppConfig.THEME_DARK);
                     }
                 });

        b.section("字体")
         .slider("编辑器字号", 10, 22, cfg.getFontSize(), " sp",
                 new SettingsDialogBuilder.OnValueChanged() {
                     @Override public void onChanged(int v) { cfg.setFontSize(v); }
                 });

        b.note("浅色与跟随系统主题将在后续版本生效，当前仅深色主题完整适配。");
    }

    // ================= 值映射 =================

    private String engineValue(int engine) {
        return engine == AppConfig.ENGINE_THIRD_PARTY ? "treesitter" : "builtin";
    }

    private String completeValue(int mode) {
        if (mode == AppConfig.COMPLETE_OFF) return "off";
        if (mode == AppConfig.COMPLETE_AI) return "ai";
        return "local";
    }

    private String themeValue(int theme) {
        if (theme == AppConfig.THEME_LIGHT) return "light";
        if (theme == AppConfig.THEME_SYSTEM) return "system";
        return "dark";
    }

    // ================= 工具 =================

    private android.graphics.drawable.GradientDrawable rounded(int color, float r) {
        // 统一委托 UiKit（半径按 dp 计）
        return UiKit.rounded(act, color, r);
    }

    private int dp(int v) {
        // 统一委托 UiKit，实现全项目只保留一份
        return UiKit.dp(act, v);
    }

    /** dp 转像素（浮点，用于圆角等需要小数的场景） */
    private float dpf(float v) {
        // 统一委托 UiKit
        return UiKit.dpF(act, v);
    }
}
