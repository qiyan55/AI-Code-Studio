package com.aicodestudio.ide.ai;

import com.aicodestudio.ide.ai.ui.Theme;
import com.aicodestudio.ide.ai.ui.UiKit;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.aicodestudio.ide.ai.compile.ApkInstaller;
import com.aicodestudio.ide.ai.compile.ArtifactManager;
import com.aicodestudio.ide.ai.core.AppConfig;
import com.aicodestudio.ide.ai.models.ModelManageDialog;
import com.aicodestudio.ide.ai.ui.AISettingsDialog;
import com.aicodestudio.ide.ai.ui.GlobalSettingsDialog;
import com.aicodestudio.ide.ai.service.SettingsActivity;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import com.aicodestudio.ide.ai.ui.AppleDialog;

/**
 * 欢迎 / 主页界面（应用启动页）
 *
 * 1:1 复刻设计截图：
 *   1. 顶部栏：菜单图标 + 标题 + 设置图标
 *   2. 蓝色渐变 Logo 方块（内含 </> ）
 *   3. 应用标题 + 副标题 + 版本号
 *   4. 三张功能卡片：AI 对话 / 代码编辑 / 代码运行（左侧色条 + 圆形图标）
 *   5. API Key 警告条（未配置时显示，含「去设置」按钮）
 *   6. 蓝色「创建新项目」按钮 + 深色「打开已有项目」按钮
 *   7. 底部三 Tab 导航：对话 / 编辑器 / 运行
 *
 * 加固：onCreate 整体 try-catch，任何异常都把堆栈渲染到屏幕上，
 *       避免出现"空白界面但看不到原因"的情况。
 */
public class WelcomeActivity extends Activity {

    /** 版本号水印：用于确认手机上装的是哪一版 APK（v1.48f：同步为最新迭代版本） */
    private static final String VERSION_TAG = "v1.76-fix2 (2026-10-28)";

    // ===== 配色（与设计稿 / colors.xml 完全一致）=====
    private static final int BG_PRIMARY    = Theme.BG_PRIMARY;
    private static final int BG_SECONDARY  = Theme.BG_SECONDARY;
    private static final int BG_CARD       = Theme.BG_CARD;
    private static final int ACCENT_BLUE   = Theme.ACCENT_BLUE;
    private static final int ACCENT_GREEN  = Theme.ACCENT_GREEN;
    private static final int ACCENT_ORANGE = Theme.ACCENT_ORANGE;
    private static final int TEXT_PRIMARY  = Theme.TEXT_PRIMARY;
    private static final int TEXT_SECOND   = Theme.TEXT_SECOND;
    private static final int TEXT_TERTIARY = Theme.TEXT_TERTIARY;

    // ===== 图标配色（欢迎页专用，与工作区分开）=====
    // 只提亮工作区三个界面的图标，欢迎页维持原亮度
    private static final int ICON_BAR      = Theme.ICON_BAR_SOFT; // 顶栏图标（原档）
    private static final int ICON_IDLE     = Theme.ICON_IDLE_SOFT; // 箭头等辅助图标（原档）
    private static final int BORDER        = Theme.STROKE;

    private static final String[] LANGUAGES = {
            "Android 项目", "Java", "Kotlin", "Python", "JavaScript", "C++", "自定义项目"
    };

    private AppConfig config;
    private LinearLayout warnBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            config = AppConfig.getInstance(this);
        } catch (Throwable t) {
            android.util.Log.e("Welcome", "config init failed", t);
        }

        // 检测上次语音引擎是否中断（native 崩溃无 Java 日志，靠文件日志定位）。
        // 必须在 init 之前读（init 会把当前 voice_engine.log 转存为 .prev）
        checkLastVoiceLog();

        // v1.51ci：严格按官方示例开机加载语音引擎
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Thread.sleep(1000);
                    com.aicodestudio.ide.ai.voice.VoiceEngine.get().init(getApplicationContext());
                } catch (Throwable ignored) {}
            }
        }).start();

        // 第一步：立刻渲染一个极简界面（大标题 + 版本徽章）。
        // 这样即使后续完整 UI 构建失败，屏幕上也一定有东西，
        // 且用户能凭版本徽章确认装的是哪一版 APK。
        showSplash();

        // 第二步：在主线程下一帧构建完整欢迎页并替换
        new android.os.Handler(android.os.Looper.getMainLooper())
                .post(new Runnable() {
                    @Override public void run() {
                        try {
                            buildUI();
                            android.util.Log.i("Welcome", "buildUI OK");
                        } catch (Throwable t) {
                            android.util.Log.e("Welcome", "buildUI FAILED", t);
                            showError(t);
                        }
                    }
                });
    }

    /** 极简启动界面：只要 Activity 活着就一定看得见 */
    private void showSplash() {
        try {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setBackgroundColor(BG_PRIMARY);
            box.setGravity(Gravity.CENTER);
            box.setPadding(dp(24), dp(24), dp(24), dp(24));
            box.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            TextView t = new TextView(this);
            t.setText("AI Code Studio");
            t.setTextSize(26);
            t.setTextColor(TEXT_PRIMARY);
            t.setTypeface(null, android.graphics.Typeface.BOLD);
            t.setGravity(Gravity.CENTER);
            box.addView(t);

            TextView badge = new TextView(this);
            badge.setText("  " + VERSION_TAG + "  ");
            badge.setTextSize(13);
            badge.setTextColor(0xFFFFFFFF);
            badge.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(12);
            badge.setLayoutParams(lp);
            badge.setBackgroundDrawable(rounded(ACCENT_BLUE, dp(12)));
            badge.setPadding(dp(14), dp(6), dp(14), dp(6));
            box.addView(badge);

            setContentView(box);
        } catch (Throwable t) {
            android.util.Log.e("Welcome", "showSplash FAILED", t);
        }
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // v1.74：singleTask 模式下从聊天页过来，走这里
        if (intent.getBooleanExtra("auto_create_project", false)) {
            intent.removeExtra("auto_create_project");
            showCreateProjectDialog();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            refreshWarnBar();
            // v1.73：从聊天页侧边栏点"新建项目"过来，自动弹出创建项目 Dialog
            if (getIntent().getBooleanExtra("auto_create_project", false)) {
                getIntent().removeExtra("auto_create_project");  // 只弹一次
                showCreateProjectDialog();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 构建失败时的兜底界面：直接把异常堆栈渲染到屏幕上 */
    private void showError(Throwable t) {
        try {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            String stack = sw.toString();

            ScrollView sv = new ScrollView(this);
            sv.setBackgroundColor(BG_PRIMARY);
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(16), dp(16), dp(16), dp(16));

            TextView head = new TextView(this);
            head.setText("界面构建失败（" + VERSION_TAG + "）\n请把这段内容截图发我：");
            head.setTextSize(15);
            head.setTextColor(0xFFF85149);
            box.addView(head);

            TextView body = new TextView(this);
            body.setText(stack);
            body.setTextSize(11);
            body.setTextColor(0xFFE6EDF3);
            body.setTypeface(android.graphics.Typeface.MONOSPACE);
            box.addView(body);

            sv.addView(box);
            setContentView(sv);
        } catch (Throwable ignored) {
        }
    }

    // ================= 界面构建 =================

    private void buildUI() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG_PRIMARY);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(buildTopBar());

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG_PRIMARY);
        scroll.addView(buildCenterContent());
        root.addView(scroll);

        setContentView(root);
    }

    /** 1. 顶部栏：菜单图标 + 标题 + 设置图标 */
    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(BG_SECONDARY);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));

        android.widget.ImageView menu = new android.widget.ImageView(this);
        menu.setImageDrawable(icon("ic_menu", ICON_BAR));
        menu.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        menu.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        menu.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSideMenu(); }
        });
        bar.addView(menu);

        TextView title = new TextView(this);
        title.setText("AI Code Studio");
        title.setTextSize(16);
        title.setTextColor(TEXT_PRIMARY);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, dp(48), 1f));
        bar.addView(title);

        android.widget.ImageView settings = new android.widget.ImageView(this);
        settings.setImageDrawable(icon("ic_settings", ICON_BAR));
        settings.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        settings.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        settings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                new GlobalSettingsDialog(WelcomeActivity.this).show();
            }
        });
        bar.addView(settings);

        return bar;
    }

    /** 中间内容：Logo + 标题 + 卡片 + 警告条 + 按钮 */
    private View buildCenterContent() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(20), dp(20), dp(20), dp(20));
        c.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // --- 2. 蓝色渐变 Logo 方块（居中）---
        LinearLayout logoWrap = new LinearLayout(this);
        logoWrap.setOrientation(LinearLayout.HORIZONTAL);
        logoWrap.setGravity(Gravity.CENTER);
        logoWrap.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView logo = new TextView(this);
        logo.setText("</>");
        logo.setTextSize(26);
        logo.setTextColor(0xFFFFFFFF);
        logo.setGravity(Gravity.CENTER);
        logo.setLayoutParams(new LinearLayout.LayoutParams(dp(64), dp(64)));
        logo.setBackgroundDrawable(gradientLogo());
        logoWrap.addView(logo);
        c.addView(logoWrap);

        c.addView(gap(dp(12)));

        // --- 3. 标题 + 副标题 + 版本号 ---
        TextView appName = new TextView(this);
        appName.setText("AI Code Studio");
        appName.setTextSize(24);
        appName.setTextColor(TEXT_PRIMARY);
        appName.setTypeface(null, android.graphics.Typeface.BOLD);
        appName.setGravity(Gravity.CENTER);
        appName.setLayoutParams(fullWidth());
        c.addView(appName);

        c.addView(gap(dp(6)));

        TextView sub = new TextView(this);
        sub.setText("手机端 AI 编程工具 — 对话式开发，智能编码，实时预览");
        sub.setTextSize(12);
        sub.setTextColor(TEXT_SECOND);
        sub.setGravity(Gravity.CENTER);
        sub.setLayoutParams(fullWidth());
        c.addView(sub);

        c.addView(gap(dp(2)));

        TextView ver = new TextView(this);
        ver.setText("  " + VERSION_TAG + "  ");
        ver.setTextSize(11);
        ver.setTextColor(0xFFFFFFFF);
        ver.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        vlp.gravity = Gravity.CENTER;
        vlp.topMargin = dp(6);
        ver.setLayoutParams(vlp);
        ver.setBackgroundDrawable(rounded(ACCENT_BLUE, dp(10)));
        ver.setPadding(dp(12), dp(4), dp(12), dp(4));
        c.addView(ver);

        c.addView(gap(dp(20)));

        // --- 4. 三张功能卡片 → 对应三个工作区 ---
        c.addView(buildWorkspaceCard("ic_chat", "对话",
                "AI 对话 · 需求分析 · 智能生成代码", ACCENT_BLUE, 0));
        c.addView(gap(dp(10)));
        c.addView(buildWorkspaceCard("ic_edit", "编辑器",
                "代码编辑 · 语法高亮 · 文件树管理", ACCENT_GREEN, 1));
        c.addView(gap(dp(10)));
        c.addView(buildWorkspaceCard("ic_play", "编译器",
                "编译运行 · 实时日志 · 工具链配置", ACCENT_ORANGE, 2));

        c.addView(gap(dp(18)));

        // --- 5. API Key 警告条 ---
        warnBar = buildWarnBar();
        c.addView(warnBar);
        refreshWarnBar();

        c.addView(gap(dp(14)));

        // --- 6. 底部双按钮 ---
        Button create = new Button(this);
        create.setText("创建新项目");
        create.setTextSize(15);
        create.setAllCaps(false);
        create.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams blp = fullWidth();
        blp.height = dp(46);
        blp.bottomMargin = dp(10);
        create.setLayoutParams(blp);
        create.setBackgroundDrawable(rounded(ACCENT_BLUE, dp(8)));
        create.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showCreateProjectDialog(); }
        });
        c.addView(create);

        Button open = new Button(this);
        open.setText("打开已有项目");
        open.setTextSize(15);
        open.setAllCaps(false);
        open.setTextColor(TEXT_PRIMARY);
        LinearLayout.LayoutParams olp = fullWidth();
        olp.height = dp(46);
        open.setLayoutParams(olp);
        open.setBackgroundDrawable(bordered(BG_SECONDARY, BORDER, dp(8)));
        open.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showOpenProjectDialog(); }
        });
        c.addView(open);

        return c;
    }

    /**
     * 4. 功能卡片：左侧 4dp 色条 + 圆形图标 + 标题 + 描述 + 右箭头
     * 用嵌套 LinearLayout 实现，外层负责圆角背景，内层负责内容，
     * 避免色条被圆角裁掉。
     */
    /**
     * 功能卡片：左色条 + 圆形矢量图标 + 标题/描述 + 右箭头
     * 点击直接进入对应的工作区界面
     */
    private View buildWorkspaceCard(String iconName, String title,
                                    String desc, int accent, final int target) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackgroundDrawable(rounded(BG_CARD, dp(8)));
        LinearLayout.LayoutParams clp = fullWidth();
        clp.height = dp(72);
        card.setLayoutParams(clp);

        // 左侧色条
        View bar = new View(this);
        bar.setBackgroundColor(accent);
        bar.setLayoutParams(new LinearLayout.LayoutParams(dp(4),
                LinearLayout.LayoutParams.MATCH_PARENT));
        card.addView(bar);

        // 圆形图标底
        LinearLayout iconWrap = new LinearLayout(this);
        iconWrap.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams iwlp = new LinearLayout.LayoutParams(dp(44), dp(44));
        iwlp.leftMargin = dp(14);
        iwlp.rightMargin = dp(12);
        iconWrap.setLayoutParams(iwlp);
        iconWrap.setBackgroundDrawable(circleBg(accent));

        android.widget.ImageView iv = new android.widget.ImageView(this);
        android.graphics.drawable.Drawable d = icon(iconName, 0xFFFFFFFF);
        if (d != null) iv.setImageDrawable(d);
        iv.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(22), dp(22)));
        iconWrap.addView(iv);
        card.addView(iconWrap);

        // 标题 + 描述
        LinearLayout textBox = new LinearLayout(this);
        textBox.setOrientation(LinearLayout.VERTICAL);
        textBox.setGravity(Gravity.CENTER_VERTICAL);
        textBox.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextSize(15);
        tvTitle.setTextColor(TEXT_PRIMARY);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        textBox.addView(tvTitle);

        TextView tvDesc = new TextView(this);
        tvDesc.setText(desc);
        tvDesc.setTextSize(11);
        tvDesc.setTextColor(TEXT_SECOND);
        tvDesc.setSingleLine(true);
        textBox.addView(tvDesc);

        card.addView(textBox);

        // 右侧箭头
        android.widget.ImageView arrow = new android.widget.ImageView(this);
        android.graphics.drawable.Drawable ad = icon("ic_chevron", ICON_IDLE);
        if (ad != null) arrow.setImageDrawable(ad);
        arrow.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        arrow.setLayoutParams(new LinearLayout.LayoutParams(dp(28), dp(28)));
        card.addView(arrow);

        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { enterWorkspace(target); }
        });

        return card;
    }

    /** 圆形图标背景 */
    private android.graphics.drawable.GradientDrawable circleBg(int color) {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    /** 5. API Key 警告条 */
    private LinearLayout buildWarnBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(10), dp(10), dp(10));
        bar.setLayoutParams(fullWidth());
        bar.setBackgroundDrawable(bordered(0x1AF0883E, 0x4DF0883E, dp(8)));

        TextView tv = new TextView(this);
        tv.setText("尚未配置 AI 模型，无法使用对话与自动修复");
        tv.setTextSize(12);
        tv.setTextColor(ACCENT_ORANGE);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(tv);

        Button go = new Button(this);
        go.setText("模型管理");
        go.setTextSize(12);
        go.setAllCaps(false);
        go.setTextColor(0xFFFFFFFF);
        go.setPadding(dp(12), 0, dp(12), 0);
        go.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(30)));
        go.setBackgroundDrawable(rounded(ACCENT_BLUE, dp(6)));
        go.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                new AISettingsDialog(WelcomeActivity.this,
                        new AISettingsDialog.OnChangedListener() {
                            @Override public void onChanged() { refreshWarnBar(); }
                        }).show();
            }
        });
        bar.addView(go);

        return bar;
    }

    private void refreshWarnBar() {
        if (warnBar == null) return;
        boolean ready = false;
        try {
            // 云端配了 Key，或本地已加载模型，都算就绪
            boolean cloud = config != null && config.hasApiKey();
            boolean local = false;
            try {
                local = com.aicodestudio.ide.ai.models.LocalModelManager
                        .getInstance(this).hasLoadedModel();
            } catch (Throwable ignored) {}
            boolean localMode = config != null
                    && config.getModelMode() == com.aicodestudio.ide.ai.core.AppConfig.MODE_LOCAL;
            ready = localMode ? local : cloud;
        } catch (Throwable t) {
            ready = false;
        }
        warnBar.setVisibility(ready ? View.GONE : View.VISIBLE);
    }

    // ================= 交互 =================

    /**
     * 进入工作区。
     * 三个工作区是彼此独立的界面：
     *   0 → ChatActivity   AI 对话
     *   1 → EditorActivity 代码编辑器
     *   2 → RunActivity    编译运行
     */
    private void enterWorkspace(int target) {
        Class<?> cls;
        if (target == 0) cls = com.aicodestudio.ide.ai.ChatActivity.class;
        else if (target == 2) cls = com.aicodestudio.ide.ai.RunActivity.class;
        else cls = com.aicodestudio.ide.ai.EditorActivity.class;
        Intent intent = new Intent(this, cls);

        // v1.67：会话管理——点对话按钮时，先找最近的空白对话
        // 如果是空的（0 条消息）→ 复用，不创建新的
        // 如果有历史 → 创建新的空白对话
        if (target == 0) {
            com.aicodestudio.ide.ai.core.SessionManager sm =
                    com.aicodestudio.ide.ai.core.SessionManager.get(this);
            com.aicodestudio.ide.ai.core.ProjectSession latestBlank = sm.getLatestBlankSession();
            if (latestBlank != null && sm.isBlankSessionEmpty(latestBlank.sessionId)) {
                // 最近的空白对话是空的，复用它
                intent.putExtra("session_id", latestBlank.sessionId);
                sm.touchSession(latestBlank.sessionId);
            } else {
                // 创建新的空白对话
                com.aicodestudio.ide.ai.core.ProjectSession newSession = sm.createBlankSession();
                intent.putExtra("session_id", newSession.sessionId);
            }
        }

        // v1.51ar：把已有 ChatActivity 放到前面，不销毁重建
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        overridePendingTransition(0, 0);
    }

    /** 新建项目弹窗（复刻设计稿：名称 / 描述 / 语言下拉） */
    private void showCreateProjectDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(2));

        final EditText etName = new EditText(this);
        etName.setText("MyProject");
        etName.setHint("项目名称");
        etName.setTextSize(14);
        etName.setTextColor(TEXT_PRIMARY);
        etName.setHintTextColor(TEXT_TERTIARY);
        etName.setSingleLine(true);
        etName.setPadding(dp(11), dp(8), dp(11), dp(8));
        etName.setBackgroundDrawable(bordered(BG_PRIMARY, BORDER, dp(6)));
        box.addView(etName);

        box.addView(gap(dp(8)));

        final EditText etDesc = new EditText(this);
        etDesc.setHint("一个待办事项应用...");
        etDesc.setTextSize(14);
        etDesc.setTextColor(TEXT_PRIMARY);
        etDesc.setHintTextColor(TEXT_TERTIARY);
        etDesc.setSingleLine(true);
        etDesc.setPadding(dp(11), dp(8), dp(11), dp(8));
        etDesc.setBackgroundDrawable(bordered(BG_PRIMARY, BORDER, dp(6)));
        box.addView(etDesc);

        box.addView(gap(dp(8)));

        TextView lbLang = new TextView(this);
        lbLang.setText("主要语言");
        lbLang.setTextSize(12);
        lbLang.setTextColor(TEXT_SECOND);
        box.addView(lbLang);

        box.addView(gap(dp(4)));

        final int[] langIdx = new int[]{0};
        final Button btnLang = new Button(this);
        btnLang.setText(LANGUAGES[0]);
        applyDropArrow(btnLang);
        btnLang.setTextSize(14);
        btnLang.setAllCaps(false);
        btnLang.setTextColor(TEXT_PRIMARY);
        btnLang.setPadding(dp(11), dp(8), dp(11), dp(8));
        btnLang.setBackgroundDrawable(bordered(BG_PRIMARY, BORDER, dp(6)));
        btnLang.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final int[] tmp = langIdx;
                new AppleDialog.Builder(WelcomeActivity.this)
                        .setTitle("选择主要语言")
                        .setSingleChoiceItems(LANGUAGES, tmp[0],
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int which) {
                                        tmp[0] = which;
                                        btnLang.setText(LANGUAGES[which]);
                                        applyDropArrow(btnLang);
                                        d.dismiss();
                                    }
                                })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
        box.addView(btnLang);

        final AlertDialog dialog = new AppleDialog.Builder(this)
                .setTitle("创建新项目")
                .setView(box)
                .setPositiveButton("创建", null)
                .setNegativeButton("取消", null)
                .create();

        // ⚠ 必须用 chain() 而不是 dialog.setOnShowListener()：
        // 后者会覆盖掉 AppleDialog 注入的样式回调，弹窗会退回原生样式。
        new AppleDialog(this).chain(dialog, new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface d) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(new View.OnClickListener() {
                            @Override public void onClick(View v) {
                                String name = etName.getText().toString().trim();
                                if (name.length() == 0) {
                                    toast("请输入项目名称");
                                    return;
                                }
                                final String fname = name;
                                final String fdesc = etDesc.getText().toString().trim();
                                final String flang = LANGUAGES[langIdx[0]];
                                // 项目默认保存到存储卡「AI Code Studio」文件夹（文件管理器可见、便于管理）：
                                // Android 11+ 写入公共目录需要"所有文件访问"权限，未授权时引导授权或回退内部存储
                                if (Build.VERSION.SDK_INT >= 30
                                        && !ApkInstaller.hasAllFilesAccess(WelcomeActivity.this)) {
                                    new AlertDialog.Builder(WelcomeActivity.this)
                                            .setTitle("存储权限")
                                            .setMessage("项目将保存到存储卡「AI Code Studio」文件夹，"
                                                    + "需要「所有文件访问」权限。\n\n"
                                                    + "未授权时项目将保存在应用内部目录（文件管理器不可见）。")
                                            .setPositiveButton("去授权",
                                                    new DialogInterface.OnClickListener() {
                                                        @Override public void onClick(DialogInterface d, int w) {
                                                            ApkInstaller.openAllFilesSettings(
                                                                    WelcomeActivity.this);
                                                        }
                                                    })
                                            .setNegativeButton("仍继续",
                                                    new DialogInterface.OnClickListener() {
                                                        @Override public void onClick(DialogInterface d, int w) {
                                                            createAndOpen(dialog, fname, fdesc, flang);
                                                        }
                                                    })
                                            .show();
                                    return;
                                }
                                createAndOpen(dialog, name,
                                        etDesc.getText().toString().trim(),
                                        LANGUAGES[langIdx[0]]);
                            }
                        });
            }
        });
        dialog.show();
    }

    /** 创建项目并进入编辑器（授权后 / 仍继续 共用入口） */
    private void createAndOpen(final AlertDialog dialog, String name, String desc, String language) {
        // 统一走 ProjectService：模板生成 + 会话绑定 + 最近列表 + 工作区设置
        com.aicodestudio.ide.ai.core.ProjectService.CreateResult r =
                com.aicodestudio.ide.ai.core.ProjectService.createProject(
                        WelcomeActivity.this, name, desc, language);
        if (r.projectPath == null) {
            toast("创建失败，请检查存储空间");
            return;
        }
        dialog.dismiss();
        // 新建项目 → 直接进对话页（已绑定会话，传 session_id）
        Intent it = new Intent(WelcomeActivity.this,
                com.aicodestudio.ide.ai.ChatActivity.class);
        it.addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        it.addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
        it.putExtra("session_id", r.session.sessionId);
        startActivity(it);
    }

    /** 打开项目弹窗 = 已有项目列表 + 从 SD 卡导入 */
    private AlertDialog openDialog;

    /** 统一的项目打开入口：校验 → 记录最近 → 关闭弹窗 → 进对话页工作区 */
    private void openProject(String path) {
        com.aicodestudio.ide.ai.core.ProjectSession session =
                com.aicodestudio.ide.ai.core.ProjectService.openProject(
                        WelcomeActivity.this, path);
        if (session == null) {
            toast("项目目录不存在");
            return;
        }

        if (openDialog != null && openDialog.isShowing()) openDialog.dismiss();
        if (menuDialog != null && menuDialog.isShowing()) menuDialog.dismiss();

        // 打开项目 → 进对话页，绑定这个会话
        Intent intent = new Intent(this, com.aicodestudio.ide.ai.ChatActivity.class);
        intent.putExtra("session_id", session.sessionId);
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        overridePendingTransition(0, 0);
    }

    private void showOpenProjectDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(6), dp(12), dp(4));

        final Runnable refresh = new Runnable() {
            @Override public void run() {
                showOpenProjectDialog();
            }
        };

        // v1.76：项目列表从 SessionManager 读，和对话页侧边栏同步
        com.aicodestudio.ide.ai.core.SessionManager sm =
                com.aicodestudio.ide.ai.core.SessionManager.get(this);
        java.util.List<com.aicodestudio.ide.ai.core.ProjectSession> projectSessions =
                sm.getProjectSessions();
        final java.util.List<String> projects = new java.util.ArrayList<String>();
        for (com.aicodestudio.ide.ai.core.ProjectSession s : projectSessions) {
            projects.add(s.projectPath);
        }

        // --- 分区标题 ---
        TextView sec = new TextView(this);
        sec.setText("已有项目");
        sec.setTextSize(12);
        sec.setTextColor(ACCENT_BLUE);
        sec.setTypeface(null, android.graphics.Typeface.BOLD);
        sec.setPadding(0, dp(4), 0, dp(6));
        box.addView(sec);

        if (projects.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有项目，可从下方「从 SD 卡导入」已有工程，或回主界面新建");
            empty.setTextSize(12);
            empty.setTextColor(TEXT_TERTIARY);
            empty.setLineSpacing(dp(2), 1.3f);
            empty.setPadding(dp(2), dp(6), dp(2), dp(10));
            box.addView(empty);
        } else {
            for (int i = 0; i < projects.size(); i++) {
                box.addView(buildProjectItem(projects.get(i), refresh));
            }
        }

        box.addView(dividerLine(dp(10), dp(6)));

        // --- 从 SD 卡导入 ---
        box.addView(menuAction("从 SD 卡导入", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (openDialog != null) openDialog.dismiss();
                showSdCardImportDialog();
            }
        }, "ic_import"));

        openDialog = new AppleDialog.Builder(this)
                .setTitle("打开项目")
                .setView(box)
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 关闭承载项目列表的弹窗（侧边菜单 / 打开项目），刷新前调用避免叠层 */
    private void dismissListHosts() {
        try {
            if (menuDialog != null && menuDialog.isShowing()) menuDialog.dismiss();
        } catch (Throwable ignored) {
        }
        try {
            if (openDialog != null && openDialog.isShowing()) openDialog.dismiss();
        } catch (Throwable ignored) {
        }
    }

    /** 一条横向分隔线 */
    private View dividerLine(int topMargin, int bottomMargin) {
        View line = new View(this);
        line.setBackgroundColor(BORDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        lp.topMargin = dp(topMargin);
        lp.bottomMargin = dp(bottomMargin);
        line.setLayoutParams(lp);
        return line;
    }

    // ================= SD 卡目录浏览器 =================

    /**
     * 从 SD 卡导入：在弹窗内逐级浏览目录，选中后直接作为项目打开。
     * 内容区由 AppleDialog 自动包限高滚动容器，目录再多也能滚。
     */
    private void showSdCardImportDialog() {
        // --- 起始目录：按可用性依次尝试 ---
        File start = null;
        File[] candidates = new File[]{
                android.os.Environment.getExternalStorageDirectory(),
                new File("/sdcard"),
                new File("/storage/emulated/0"),
                new File("/storage"),
                com.aicodestudio.ide.ai.core.ProjectManager.getProjectsRoot(this),
        };
        for (File f : candidates) {
            if (f != null && f.exists() && f.isDirectory() && f.canRead()) {
                start = f;
                break;
            }
        }
        if (start == null) start = com.aicodestudio.ide.ai.core.ProjectManager.getProjectsRoot(this);

        final File[] cur = new File[]{start};

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(6), dp(12), dp(2));

        // --- 当前路径 ---
        final TextView tvPath = new TextView(this);
        tvPath.setTextSize(11);
        tvPath.setTextColor(TEXT_TERTIARY);
        tvPath.setSingleLine(true);
        tvPath.setPadding(dp(2), dp(2), dp(2), dp(6));
        box.addView(tvPath);

        // --- 返回上级 ---
        final View upRow = menuAction("返回上级目录", null, "ic_back");
        box.addView(upRow);

        box.addView(dividerLine(dp(6), dp(4)));

        // --- 目录列表容器 ---
        final LinearLayout listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(listBox);

        final Runnable[] rebuild = new Runnable[1];
        rebuild[0] = new Runnable() {
            @Override public void run() {
                tvPath.setText(cur[0].getAbsolutePath());

                // 顶层时隐藏「返回上级」
                boolean canUp = cur[0].getParentFile() != null;
                upRow.setVisibility(canUp ? View.VISIBLE : View.GONE);
                upRow.setEnabled(canUp);

                listBox.removeAllViews();

                File[] all = cur[0].listFiles();
                java.util.List<File> dirs = new java.util.ArrayList<File>();
                if (all != null) {
                    for (File f : all) {
                        if (f.isDirectory() && !f.isHidden()) dirs.add(f);
                    }
                    java.util.Collections.sort(dirs,
                            new java.util.Comparator<File>() {
                                @Override public int compare(File a, File b) {
                                    return a.getName().compareToIgnoreCase(b.getName());
                                }
                            });
                }

                if (dirs.isEmpty()) {
                    TextView empty = new TextView(WelcomeActivity.this);
                    empty.setText(all == null
                            ? "无法读取该目录（可能缺少存储权限）"
                            : "该目录下没有子文件夹");
                    empty.setTextSize(12);
                    empty.setTextColor(TEXT_TERTIARY);
                    empty.setPadding(dp(2), dp(10), dp(2), dp(10));
                    listBox.addView(empty);
                    return;
                }

                for (File d : dirs) {
                    listBox.addView(buildDirRow(d, cur, rebuild));
                }
            }
        };

        upRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                File parent = cur[0].getParentFile();
                if (parent != null && parent.canRead()) {
                    cur[0] = parent;
                    rebuild[0].run();
                }
            }
        });

        rebuild[0].run();

        new AppleDialog.Builder(this)
                .setTitle("选择项目目录")
                .setView(box)
                .setPositiveButton("选择此目录",
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                openProject(cur[0].getAbsolutePath());
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 目录浏览器中的一行：点击进入，右侧箭头；整行保持 Apple 风格 */
    private View buildDirRow(final File dir, final File[] cur,
                             final Runnable[] rebuild) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(10), dp(6), dp(10));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        android.widget.ImageView iv = new android.widget.ImageView(this);
        android.graphics.drawable.Drawable d = icon("ic_folder", ACCENT_ORANGE);
        if (d != null) iv.setImageDrawable(d);
        iv.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(22), dp(22)));
        row.addView(iv);

        TextView name = new TextView(this);
        name.setText(dir.getName());
        name.setTextSize(14);
        name.setTextColor(TEXT_PRIMARY);
        name.setSingleLine(true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(8);
        name.setLayoutParams(tlp);
        row.addView(name);

        android.widget.ImageView arrow = new android.widget.ImageView(this);
        android.graphics.drawable.Drawable ad = icon("ic_chevron", ICON_IDLE);
        if (ad != null) arrow.setImageDrawable(ad);
        arrow.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        arrow.setLayoutParams(new LinearLayout.LayoutParams(dp(18), dp(18)));
        row.addView(arrow);

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!dir.canRead()) {
                    toast("无法读取该目录");
                    return;
                }
                cur[0] = dir;
                rebuild[0].run();
            }
        });
        return row;
    }

    /**
     * 侧边菜单 = 项目列表
     *
     * 欢迎界面是入口，所以汉堡菜单直接给项目列表：
     *   最近项目（点击直接进入编辑器工作区，长按可移除/删除）
     *   + 新建项目 / 打开已有项目
     *   + 模型管理 / 系统设置 / 关于
     */

    /** 给按钮右侧加一个矢量下拉箭头（不用 ▾ 字符） */
    private void applyDropArrow(android.widget.TextView tv) {
        try {
            android.graphics.drawable.Drawable d = icon("ic_chevron_down", ICON_BAR);
            if (d == null) return;
            int size = dp(16);
            d.setBounds(0, 0, size, size);
            tv.setCompoundDrawables(null, null, d, null);
            tv.setCompoundDrawablePadding(dp(6));
        } catch (Throwable ignored) {
        }
    }

    private void showSideMenu() {
        final java.util.List<String> projects =
                com.aicodestudio.ide.ai.core.AppConfig.getInstance(this)
                        .getAllProjects();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(BG_SECONDARY);
        box.setPadding(dp(16), dp(12), dp(16), dp(8));

        // --- 项目列表标题 ---
        TextView secTitle = new TextView(this);
        secTitle.setText("项目列表");
        secTitle.setTextSize(12);
        secTitle.setTextColor(ACCENT_BLUE);
        secTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        secTitle.setPadding(0, 0, 0, dp(6));
        box.addView(secTitle);

        if (projects.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无项目，点击下方「新建项目」开始");
            empty.setTextSize(12);
            empty.setTextColor(TEXT_TERTIARY);
            empty.setPadding(0, dp(8), 0, dp(8));
            box.addView(empty);
        } else {
            for (int i = 0; i < projects.size(); i++) {
                final String path = projects.get(i);
                box.addView(buildProjectItem(path));
            }
        }

        // --- 分隔 ---
        View line = new View(this);
        line.setBackgroundColor(BORDER);
        line.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        LinearLayout.LayoutParams llp = (LinearLayout.LayoutParams) line.getLayoutParams();
        llp.topMargin = dp(10);
        llp.bottomMargin = dp(6);
        line.setLayoutParams(llp);
        box.addView(line);

        // --- 操作项 ---
        box.addView(menuAction("新建项目", new View.OnClickListener() {
            @Override public void onClick(View v) {
                menuDialog.dismiss();
                showCreateProjectDialog();
            }
        }));
        box.addView(menuAction("打开已有项目", new View.OnClickListener() {
            @Override public void onClick(View v) {
                menuDialog.dismiss();
                showOpenProjectDialog();
            }
        }));
        box.addView(menuAction("本地模型管理", new View.OnClickListener() {
            @Override public void onClick(View v) {
                menuDialog.dismiss();
                new AISettingsDialog(WelcomeActivity.this).show();
            }
        }));
        box.addView(menuAction("系统设置", new View.OnClickListener() {
            @Override public void onClick(View v) {
                menuDialog.dismiss();
                new GlobalSettingsDialog(WelcomeActivity.this).show();
            }
        }));
        box.addView(menuAction("远程连接器", new View.OnClickListener() {
            @Override public void onClick(View v) {
                toggleRemoteConnector();
            }
        }, "ic_chevron"));
        box.addView(menuAction("关于", new View.OnClickListener() {
            @Override public void onClick(View v) {
                menuDialog.dismiss();
                new AppleDialog.Builder(WelcomeActivity.this)
                        .setTitle("关于")
                        .setMessage("AI Code Studio\n"
                                + "手机端 AI 编程工具\n"
                                + "版本 " + VERSION_TAG + "\n\n"
                                + "对话式开发 · 智能编码 · 实时预览")
                        .setPositiveButton("确定", null)
                        .show();
            }
        }));

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setBackgroundColor(BG_SECONDARY);
        sv.addView(box);

        menuDialog = new AppleDialog.Builder(this)
                .setTitle("AI Code Studio")
                .setView(sv)
                .setNegativeButton("关闭", null)
                .show();
    }

    private AlertDialog menuDialog;

    /** 项目列表中的一条（侧边菜单用，变更后刷新菜单） */
    private View buildProjectItem(final String path) {
        return buildProjectItem(path, new Runnable() {
            @Override public void run() {
                showSideMenu();
            }
        });
    }

    /** 项目列表中的一条；afterChange 决定列表变动后刷新哪个弹窗 */
    private View buildProjectItem(final String path, final Runnable afterChange) {
        final File dir = new File(path);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundDrawable(rounded(BG_CARD, dp(6)));
        row.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        row.setLayoutParams(lp);

        android.widget.ImageView iv = new android.widget.ImageView(this);
        android.graphics.drawable.Drawable d = icon("ic_folder", ACCENT_ORANGE);
        if (d != null) iv.setImageDrawable(d);
        iv.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(30), dp(30)));
        row.addView(iv);

        LinearLayout textBox = new LinearLayout(this);
        textBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(8);
        textBox.setLayoutParams(tlp);

        TextView name = new TextView(this);
        name.setText(dir.getName());
        name.setTextSize(14);
        name.setTextColor(TEXT_PRIMARY);
        name.setSingleLine(true);
        textBox.addView(name);

        TextView pathTv = new TextView(this);
        pathTv.setText(path);
        pathTv.setTextSize(10);
        pathTv.setTextColor(TEXT_TERTIARY);
        pathTv.setSingleLine(true);
        textBox.addView(pathTv);

        row.addView(textBox);

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!dir.exists()) {
                    toast("项目目录已不存在");
                    return;
                }
                openProject(path);
            }
        });

        row.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                new AppleDialog.Builder(WelcomeActivity.this)
                        .setTitle(dir.getName())
                        .setItems(new String[]{"从列表移除", "删除项目文件"},
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int which) {
                                        if (which == 0) {
                                            // 从列表移除（不删磁盘文件）：统一走 ProjectService
                                            com.aicodestudio.ide.ai.core.ProjectService
                                                    .removeFromList(WelcomeActivity.this, path);
                                            dismissListHosts();
                                            if (afterChange != null) afterChange.run();
                                        } else {
                                            new AppleDialog.Builder(WelcomeActivity.this)
                                                    .setTitle("删除项目")
                                                    .setMessage("将永久删除该目录下所有文件，确定继续？")
                                                    .setPositiveButton("删除",
                                                            new DialogInterface.OnClickListener() {
                                                                @Override
                                                                public void onClick(
                                                                        DialogInterface d2, int w2) {
                                                                    // 彻底删除：目录 + 会话 + 最近列表 + 聊天历史
                                                                    com.aicodestudio.ide.ai.core.ProjectService
                                                                            .deleteProject(WelcomeActivity.this, path);
                                                                    dismissListHosts();
                                                                    if (afterChange != null) {
                                                                        afterChange.run();
                                                                    }
                                                                }
                                                            })
                                                    .setNegativeButton("取消", null)
                                                    .show();
                                        }
                                    }
                                })
                        .show();
                return true;
            }
        });

        return row;
    }

    /** 菜单中的操作项（右侧箭头） */
    private View menuAction(String label, View.OnClickListener l) {
        return menuAction(label, l, "ic_chevron");
    }

    /** 菜单中的操作项（右侧图标可指定） */
    private View menuAction(String label, View.OnClickListener l, String iconName) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(11), dp(6), dp(11));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(14);
        tv.setTextColor(TEXT_PRIMARY);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tv);

        android.widget.ImageView arrow = new android.widget.ImageView(this);
        android.graphics.drawable.Drawable ad = icon(iconName, ICON_BAR);
        if (ad != null) arrow.setImageDrawable(ad);
        arrow.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        arrow.setLayoutParams(new LinearLayout.LayoutParams(dp(20), dp(20)));
        row.addView(arrow);

        if (l != null) row.setOnClickListener(l);
        return row;
    }

    // ================= 绘图 / 布局工具 =================

    /** 加载矢量图标并染色 */
    private android.graphics.drawable.Drawable icon(String name, int color) {
        // 统一委托 UiKit（资源查找 / 判空 / 染色 / 异常兜底都在里面）
        return UiKit.icon(this, name, color);
    }

    private GradientDrawable gradientLogo() {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF1F6FEB, 0xFF2196F3, 0xFF56B6C2});
        g.setCornerRadius(dp(14));
        return g;
    }

    private android.graphics.drawable.GradientDrawable rounded(int color, float r) {
        // 统一委托 UiKit。
        // 注意：此处 r 是【像素】而非 dp，原实现直接 setCornerRadius(r)，
        // 所以委托到 roundedPx（不乘 density），否则圆角会放大约 3 倍。
        return UiKit.roundedPx(color, r);
    }

    private GradientDrawable bordered(int bg, int stroke, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(r);
        g.setStroke(Math.max(1, (int) dp(1)), stroke);
        return g;
    }

    private GradientDrawable circle(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setShape(GradientDrawable.OVAL);
        return g;
    }

    /** 在深色背景上生成该颜色的低透明度版本（用于图标圆形底） */
    private int fade(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return 0xFF000000
                | (((int) (0x1C + r * 0.12f)) << 16)
                | (((int) (0x23 + g * 0.12f)) << 8)
                | ((int) (0x33 + b * 0.12f));
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private View gap(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, h));
        return v;
    }

    private int dp(float v) {
        // 统一委托 UiKit，实现全项目只保留一份
        return UiKit.dp(this, v);
    }

    private void toast(String msg) {
        // 统一委托 UiKit
        UiKit.toast(this, msg);
    }

    /**
     * 检查上次语音引擎加载日志：如果上次运行在引擎 READY 前中断（native 崩溃），
     * 弹出对话框显示最后几步，方便定位崩溃点（无需 adb/root）。
     */
    private void checkLastVoiceLog() {
        try {
            java.io.File log = new java.io.File(getFilesDir(), "voice_engine.log");
            if (!log.exists() || log.length() == 0) return;
            String content = readTail(log, 30);
            if (content.contains("READY")) return; // 上次正常就绪，无崩溃
            if (content.contains("init begin")) {
                showVoiceLogDialog("上次启动语音引擎中断，日志如下（截图发给开发者）：", content);
            }
        } catch (Throwable ignored) {}
    }

    private String readTail(java.io.File f, int maxLines) {
        java.util.List<String> lines = new java.util.ArrayList<String>();
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) lines.add(line);
            br.close();
        } catch (Throwable ignored) {}
        if (lines.size() <= maxLines) {
            StringBuilder sb = new StringBuilder();
            for (String l : lines) sb.append(l).append('\n');
            return sb.toString();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = lines.size() - maxLines; i < lines.size(); i++) sb.append(lines.get(i)).append('\n');
        return sb.toString();
    }

    private void showVoiceLogDialog(String title, String content) {
        try {
            com.aicodestudio.ide.ai.ui.AppleDialog.message(this, title, content);
        } catch (Throwable ignored) {}
    }

    // ================= 远程连接器开关 =================
    private android.content.SharedPreferences remotePrefs;
    private boolean isRemoteRunning() {
        if (remotePrefs == null) remotePrefs = getSharedPreferences("remote", MODE_PRIVATE);
        return remotePrefs.getBoolean("running", false);
    }
    private void setRemoteRunning(boolean r) {
        if (remotePrefs == null) remotePrefs = getSharedPreferences("remote", MODE_PRIVATE);
        remotePrefs.edit().putBoolean("running", r).apply();
    }

    private void toggleRemoteConnector() {
        boolean running = isRemoteRunning();
        if (running) {
            // 停止
            try {
                Intent svc = new Intent(this, com.aicodestudio.ide.ai.remote.RemoteConnectorService.class);
                svc.setAction(com.aicodestudio.ide.ai.remote.RemoteConnectorService.ACTION_STOP);
                startService(svc);
                setRemoteRunning(false);
                toast("远程连接器已停止");
            } catch (Throwable t) {
                toast("停止失败: " + t.getMessage());
            }
        } else {
            // 启动
            try {
                Intent svc = new Intent(this, com.aicodestudio.ide.ai.remote.RemoteConnectorService.class);
                svc.setAction(com.aicodestudio.ide.ai.remote.RemoteConnectorService.ACTION_START);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svc);
                } else {
                    startService(svc);
                }
                setRemoteRunning(true);
                // 获取本机 IP 显示给用户
                String ip = getLocalIp();
                toast("远程连接器已启动，端口 7860\n电脑连接: " + ip + ":7860");
            } catch (Throwable t) {
                toast("启动失败: " + t.getMessage());
            }
        }
        menuDialog.dismiss();
    }

    private String getLocalIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifaces =
                java.net.NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                java.net.NetworkInterface iface = ifaces.nextElement();
                java.util.Enumeration<java.net.InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) {}
        return "unknown";
    }
}