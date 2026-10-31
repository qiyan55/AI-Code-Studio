package com.aicodestudio.ide.ai;

import com.aicodestudio.ide.ai.ui.Theme;
import com.aicodestudio.ide.ai.ui.UiKit;
import android.app.Activity;
import android.os.Build;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.aicodestudio.ide.ai.core.WorkspaceState;

/**
 * 工作区界面基类
 *
 * 手机端三个工作区（对话 / 编辑器 / 编译运行）各自独立成 Activity，
 * 共用同一套外壳：
 *   ┌─────────────────────┐
 *   │  顶部栏（子类构建）    │
 *   ├─────────────────────┤
 *   │                     │
 *   │  内容区（子类填充）   │  ← weight=1
 *   │                     │
 *   ├─────────────────────┤
 *   │  底部三 Tab 导航      │
 *   └─────────────────────┘
 *
 * 底部导航在三个界面间互相切换，用 singleTask 复用已存在的实例。
 */
public abstract class BaseWorkspaceActivity extends Activity {

    // 配色
    protected static final int BG_PRIMARY    = Theme.BG_PRIMARY;
    protected static final int BG_SECONDARY  = Theme.BG_SECONDARY;
    protected static final int BG_CARD       = Theme.BG_CARD;
    protected static final int ACCENT_BLUE   = Theme.ACCENT_BLUE;
    protected static final int ACCENT_GREEN  = Theme.ACCENT_GREEN;
    protected static final int ACCENT_ORANGE = Theme.ACCENT_ORANGE;
    protected static final int ACCENT_RED    = Theme.ACCENT_RED;
    protected static final int TEXT_PRIMARY  = Theme.TEXT_PRIMARY;
    protected static final int TEXT_SECOND   = Theme.TEXT_SECOND;
    protected static final int TEXT_TERTIARY = Theme.TEXT_TERTIARY;
    protected static final int BORDER        = Theme.STROKE;

    // ===== 图标配色（仅用于顶栏 / 底部导航）=====
    // 深色底上，颜色要"够亮 + 够饱和"。
    // 只提亮度会把颜色推向白色导致发粉发灰，所以这里控制 L 不要过高、
    // 同时把饱和度拉满，保持和欢迎页色条一致的鲜艳度。
    //   ICON_BAR  #E6EDF3  中性灰白，无饱和度概念，仅提亮
    //   ICON_IDLE #C9D1D9  同上
    //   TAB_BLUE  #3D9BFF  S=100% L=62%
    //   TAB_GREEN #26D962  S= 70% L=50%（纯绿 S=100 会荧光，取 70 更耐看）
    //   TAB_ORANGE#FF9238  S=100% L=61%
    protected static final int ICON_BAR      = Theme.ICON_BAR; // 顶栏图标
    protected static final int ICON_IDLE     = Theme.ICON_IDLE; // 底部未选中
    protected static final int TAB_BLUE      = Theme.TAB_BLUE; // 对话（鲜艳蓝）
    protected static final int TAB_GREEN     = Theme.TAB_GREEN; // 编辑器（鲜艳绿）
    protected static final int TAB_ORANGE    = Theme.TAB_ORANGE; // 运行（鲜艳橙）

    /** 底部 Tab 编号 */
    protected static final int TAB_CHAT   = 0;
    protected static final int TAB_EDITOR = 1;
    protected static final int TAB_RUN    = 2;

    protected LinearLayout rootLayout;
    protected LinearLayout contentArea;
    // v1.51bj：底部 tab 引用（用于禁用/启用）
    protected View navTabEditor;
    protected View navTabRun;

    // ===== 左右滑动返回主页 =====
    private GestureDetector swipeDetector;
    /** 触发返回的最小横向位移 */
    private static final float SWIPE_MIN_DISTANCE_DP = 90f;
    /** 触发返回的最小横向速度（像素/秒） */
    private static final float SWIPE_MIN_VELOCITY_DP = 400f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);


        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(BG_PRIMARY);
        rootLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // 顶部栏
        View top = buildTopBar();
        if (top != null) rootLayout.addView(top);

        // 内容区
        contentArea = new LinearLayout(this);
        contentArea.setOrientation(LinearLayout.VERTICAL);
        contentArea.setBackgroundColor(BG_PRIMARY);
        contentArea.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        rootLayout.addView(contentArea);

        // 底部导航
        rootLayout.addView(buildBottomNav());

        setContentView(rootLayout);

        try {
            buildContent(contentArea);
        } catch (Throwable t) {
            android.util.Log.e(getClass().getSimpleName(), "buildContent failed", t);
            showBuildError(t);
        }

        setupSwipeBack();
    }

    // ==================================================================
    // 左右滑动返回主页
    //
    // 三个工作区（对话 / 编辑器 / 编译运行）都继承本基类，
    // 所以在这里统一实现，一次性覆盖。
    //
    // 触发条件（三个同时满足才返回）：
    //   ① 横向位移 > 90dp              —— 排除小幅抖动
    //   ② 横向速度 > 400dp/s           —— 必须是"甩"而不仅是"拖"
    //   ③ |vx| > |vy| 且 |dx| > |dy|   —— 排除垂直滚动误触
    //
    // 用 dispatchTouchEvent 而非给根布局设 OnTouchListener：
    // 后者会被子 View（ScrollView / 编辑器 / 日志区）拦截，
    // 内容区滑动时收不到事件；前者在分发链最上层，一定能收到。
    // ==================================================================

    private void setupSwipeBack() {
        final float minDist = SWIPE_MIN_DISTANCE_DP
                * getResources().getDisplayMetrics().density;
        final float minVel = SWIPE_MIN_VELOCITY_DP
                * getResources().getDisplayMetrics().density;
        // 左边缘判定阈值：从屏幕最左 1/6 起滑才算返回手势，
        // 避免在列表/代码区横向滑动查看内容时误触发返回（v1.48f fix）
        final float edgeLimit = getResources().getDisplayMetrics().widthPixels / 6f;

        swipeDetector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2,
                                   float velocityX, float velocityY) {
                if (e1 == null || e2 == null) return false;
                if (!swipeBackEnabled()) return false;

                // ④ 必须从屏幕左缘起滑（边缘手势），中部横滑不返回
                if (e1.getX() > edgeLimit) return false;

                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();

                // ③ 必须是横向为主的滑动
                if (Math.abs(dx) <= Math.abs(dy)) return false;
                // ① 位移够大
                if (Math.abs(dx) < minDist) return false;
                // ② 速度够快
                if (Math.abs(velocityX) < minVel) return false;

                goHome();
                return true;
            }
        });
    }

    /**
     * 子类可重写以关闭滑动返回。
     * 例如编辑器处于文本选择或多指缩放状态时可临时关闭。
     */
    protected boolean swipeBackEnabled() {
        return true;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // 先喂给手势识别器，再走正常分发；
        // 手势未命中不影响子 View 正常消费事件。
        if (swipeDetector != null) {
            try {
                swipeDetector.onTouchEvent(ev);
            } catch (Throwable ignored) {
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    /** 防抖：避免一次滑动触发多次返回 */
    private long lastGoHomeMs = 0L;

    /**
     * 返回主页（三个工作区共用，与顶栏返回按钮同一行为）
     *
     * 关键点：用 CLEAR_TOP 而不是 REORDER_TO_FRONT。
     *
     * 三个工作区在 Manifest 里是 singleTask，正常情况下 singleTask 启动时
     * 会自动销毁目标之上的所有 Activity。但 Tab 切换用了
     * FLAG_ACTIVITY_REORDER_TO_FRONT，这个标志会【覆盖】singleTask 的清栈
     * 语义——它只把目标实例提到栈顶，不销毁任何 Activity。
     *
     * 于是在 Tab 间来回点几次后，任务栈会变成：
     *     [Editor, Run, Chat, ...]  ← 工作区实例互相堆叠
     * 此时返回若仍用 REORDER_TO_FRONT 启动 Welcome，只是把 Welcome 提到栈顶，
     * 底下那一串工作区实例全部保留，变成 Welcome 的"返回历史"。
     * 结果就是：再返回时会依次经过刚才点过的每个界面，退完才到主页。
     *
     * CLEAR_TOP 的行为是：在栈中找到 WelcomeActivity，
     * 【销毁它上面的所有 Activity】，然后复用该实例。
     * 配合 SINGLE_TOP（目标在栈顶时复用而不重建，这里因 CLEAR_TOP 它必然到栈顶）
     * 即可做到"不论之前切过多少次，一次返回直接清空所有工作区、直达主页"。
     *
     * 注：WorkspaceState 是进程级单例，不随 Activity 销毁而丢失，
     * 所以对话记录、编辑器内容等数据不会因此清空。
     */
    protected void goHome() {
        com.aicodestudio.ide.ai.core.AppConfig.getInstance(this).clearActiveState();
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastGoHomeMs < 400L) return;   // 400ms 防抖
        lastGoHomeMs = now;

        Intent it = new Intent(this, WelcomeActivity.class);
        it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        it.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(it);
        overridePendingTransition(0, 0);   // 与 Tab 切换一致，不播转场
    }

    /**
     * 物理返回键也直达主页。
     *
     * 默认的 finish() 只是销毁当前 Activity，会回到任务栈的上一个界面
     * ——也就是刚才点过的其它工作区，同样出现"要按好几次才回主页"。
     */
    @Override
    public void onBackPressed() {
        // v1.48f fix：先收起软键盘再返回，避免键盘残留/输入框焦点错乱
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(
                    getWindow().getDecorView().getWindowToken(), 0);
        } catch (Throwable ignored) {}
        goHome();
    }

    // ===== 子类实现 =====

    /** 构建顶部栏（返回 null 则不显示） */
    protected abstract View buildTopBar();

    /** 填充内容区 */
    protected abstract void buildContent(LinearLayout container);

    /** 当前属于哪个 Tab（用于底部高亮） */
    protected abstract int currentTab();

    // ===== 底部三 Tab 导航 =====

    private View buildBottomNav() {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        View line = new View(this);
        line.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        line.setBackgroundColor(BORDER);
        wrapper.addView(line);

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setBackgroundColor(BG_SECONDARY);
        nav.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54)));

        int cur = currentTab();
        // 三个 Tab 各自的主题色，与欢迎页三张卡片的色条一一对应
        nav.addView(navTab("对话", "ic_chat", TAB_CHAT, cur == TAB_CHAT, TAB_BLUE));
        navTabEditor = navTab("编辑器", "ic_edit", TAB_EDITOR, cur == TAB_EDITOR, TAB_GREEN);
        nav.addView(navTabEditor);
        navTabRun = navTab("运行", "ic_play", TAB_RUN, cur == TAB_RUN, TAB_ORANGE);
        nav.addView(navTabRun);

        wrapper.addView(nav);
        return wrapper;
    }

    private View navTab(String label, String iconName, final int tab,
                        boolean active, int accent) {
        LinearLayout tabView = new LinearLayout(this);
        tabView.setOrientation(LinearLayout.VERTICAL);
        tabView.setGravity(Gravity.CENTER);
        tabView.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        ImageView iv = new ImageView(this);
        Drawable d = icon(iconName, active ? accent : ICON_IDLE);
        if (d != null) iv.setImageDrawable(d);
        iv.setScaleType(ImageView.ScaleType.CENTER);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(24), dp(24));
        iv.setLayoutParams(ilp);
        tabView.addView(iv);

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(11);
        tv.setTextColor(active ? accent : ICON_IDLE);
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(2);
        tv.setLayoutParams(tlp);
        tabView.addView(tv);

        if (!active) {
            tabView.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { switchToTab(tab); }
            });
        }
        return tabView;
    }

    /** v1.51bj：根据是否绑定项目，启用/禁用编辑器和运行 tab（子类可重写） */
    protected void updateTabEnabled() {
        boolean hasProject = state().getProjectPath() != null && !state().getProjectPath().isEmpty();
        if (navTabEditor != null) {
            navTabEditor.setAlpha(hasProject ? 1f : 0.3f);
            navTabEditor.setEnabled(hasProject);
        }
        if (navTabRun != null) {
            navTabRun.setAlpha(hasProject ? 1f : 0.3f);
            navTabRun.setEnabled(hasProject);
        }
    }

    protected void switchToTab(int tab) {
        Class<?> cls;
        if (tab == TAB_CHAT) cls = ChatActivity.class;
        else if (tab == TAB_EDITOR) cls = EditorActivity.class;
        else cls = RunActivity.class;

        if (cls.equals(getClass())) return;

        Intent it = new Intent(this, cls);
        it.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        it.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(it);
        // 关闭切换动画：底部三 Tab 之间直接切换，不播转场
        overridePendingTransition(0, 0);
    }

    // ===== 顶部栏工具 =====

    /** 标准顶部栏：左图标 + 标题（+ 副标题）+ 若干右侧图标按钮 */
    protected LinearLayout makeTopBar(String title, String subtitle,
                                      String leftIcon, View.OnClickListener leftAction,
                                      TopAction[] actions) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(BG_SECONDARY);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50)));

        // 左侧图标按钮
        if (leftIcon != null) {
            ImageView left = new ImageView(this);
            Drawable d = icon(leftIcon, ICON_BAR);
            if (d != null) left.setImageDrawable(d);
            left.setScaleType(ImageView.ScaleType.CENTER);
            left.setLayoutParams(new LinearLayout.LayoutParams(dp(46), dp(50)));
            if (leftAction != null) left.setOnClickListener(leftAction);
            bar.addView(left);
        } else {
            View pad = new View(this);
            pad.setLayoutParams(new LinearLayout.LayoutParams(dp(12), dp(50)));
            bar.addView(pad);
        }

        // 标题区
        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setGravity(Gravity.CENTER_VERTICAL);
        titleBox.setLayoutParams(new LinearLayout.LayoutParams(0, dp(50), 1f));

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextSize(15);
        tvTitle.setTextColor(TEXT_PRIMARY);
        tvTitle.setSingleLine(true);
        tvTitle.setGravity(Gravity.CENTER_VERTICAL);
        titleBox.addView(tvTitle);

        if (subtitle != null && subtitle.length() > 0) {
            TextView tvSub = new TextView(this);
            tvSub.setText(subtitle);
            tvSub.setTextSize(10);
            tvSub.setTextColor(TEXT_SECOND);
            tvSub.setSingleLine(true);
            tvSub.setGravity(Gravity.CENTER_VERTICAL);
            titleBox.addView(tvSub);
        }

        bar.addView(titleBox);

        // 右侧动作按钮
        if (actions != null) {
            for (int i = 0; i < actions.length; i++) {
                final TopAction a = actions[i];
                if (a == null) continue;
                ImageView iv = new ImageView(this);
                Drawable d = icon(a.icon, a.tint != 0 ? brighten(a.tint) : ICON_BAR);
                if (d != null) iv.setImageDrawable(d);
                iv.setScaleType(ImageView.ScaleType.CENTER);
                iv.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(50)));
                if (a.action != null) iv.setOnClickListener(a.action);
                bar.addView(iv);
            }
        }

        return bar;
    }

    /** 顶部栏右侧动作 */
    protected static class TopAction {
        String icon;
        int tint;
        View.OnClickListener action;
        public TopAction(String icon, int tint, View.OnClickListener action) {
            this.icon = icon;
            this.tint = tint;
            this.action = action;
        }
    }

    // ===== 通用工具 =====

    /**
     * 顶栏动作图标若指定了主题色，映射到提亮档，
     * 避免描边图标在深色底上发暗。
     */
    private int brighten(int color) {
        if (color == ACCENT_BLUE)   return TAB_BLUE;
        if (color == ACCENT_GREEN)  return TAB_GREEN;
        if (color == ACCENT_ORANGE) return TAB_ORANGE;
        if (color == ACCENT_RED)    return 0xFFFFA198;
        if (color == TEXT_SECOND)   return TEXT_PRIMARY;
        if (color == TEXT_TERTIARY) return TEXT_SECOND;
        return color;
    }

    /** 加载矢量图标并染色 */
    protected android.graphics.drawable.Drawable icon(String name, int color) {
        // 统一委托 UiKit（资源查找 / 判空 / 染色 / 异常兜底都在里面）
        return UiKit.icon(this, name, color);
    }

    protected android.graphics.drawable.GradientDrawable rounded(int color, float r) {
        // 统一委托 UiKit（半径按 dp 计）
        return UiKit.rounded(this, color, r);
    }

    protected GradientDrawable bordered(int bg, int stroke, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(dp(r));
        g.setStroke(Math.max(1, dp(1)), stroke);
        return g;
    }

    protected LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    protected View gap(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(h)));
        return v;
    }

    protected int dp(float v) {
        // 统一委托 UiKit，实现全项目只保留一份
        return UiKit.dp(this, v);
    }

    protected void toast(String msg) {
        // 统一委托 UiKit
        UiKit.toast(this, msg);
    }

    protected WorkspaceState state() {
        return WorkspaceState.get();
    }

    /** 构建异常时把堆栈显示出来，避免"空白界面看不出原因" */
    protected void showBuildError(Throwable t) {
        try {
            contentArea.removeAllViews();
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));

            android.widget.ScrollView sv = new android.widget.ScrollView(this);
            sv.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT));

            TextView tv = new TextView(this);
            tv.setText("界面构建失败：\n\n" + sw.toString());
            tv.setTextSize(11);
            tv.setTextColor(ACCENT_RED);
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            tv.setPadding(dp(14), dp(14), dp(14), dp(14));
            sv.addView(tv);
            contentArea.addView(sv);
        } catch (Throwable ignored) {
        }
    }
}
