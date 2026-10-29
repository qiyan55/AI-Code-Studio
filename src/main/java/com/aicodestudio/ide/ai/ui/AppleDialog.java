package com.aicodestudio.ide.ai.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * iOS / Apple 风格弹窗
 *
 * 设计要点（对齐 UIAlertController）：
 *   - 弹窗宽度 = min(屏幕宽 - 64dp, 340dp)，不做通屏
 *   - 圆角 14dp，纯色卡片，无边框
 *   - 标题居中、17sp 半粗体；消息居中、13sp
 *   - 内容区最大高度 = 屏幕高 * 0.62，超出自动滚动（不再细长）
 *   - 按钮区顶部一条 1px 分隔线，按钮文字蓝色、44dp 高、无大写
 *   - 按钮之间用 1px 竖线分隔，取消按钮为次要灰
 *
 * 配色沿用主界面的深色主题（欢迎页同一套）。
 */
public class AppleDialog {

    // ===== 主界面配色（与 WelcomeActivity / BaseWorkspaceActivity 一致）=====
    public static final int BG_DIALOG   = Theme.BG_CARD;
    public static final int BG_SECOND   = Theme.BG_SECONDARY;
    public static final int BG_PRIMARY  = Theme.BG_PRIMARY;
    public static final int TEXT_MAIN   = Theme.TEXT_PRIMARY;
    public static final int TEXT_SUB    = Theme.TEXT_SECOND;
    public static final int TEXT_WEAK   = Theme.TEXT_TERTIARY;
    public static final int ACCENT_BLUE = Theme.ACCENT_BLUE;
    public static final int ACCENT_RED  = 0xFFF85149;
    public static final int BORDER      = 0xFF30363D;

    // ===== 按钮语义色 =====
    // 确定 / 完成 → 绿色圈勾；取消 / 关闭 → 红色圈叉
    public static final int OK_GREEN   = 0xFF26D962;
    public static final int CLOSE_RED  = 0xFFFF7B72;

    /** 按钮栏高度（原来是 46dp，压矮以减小弹窗整体高度） */
    private static final int BUTTON_H_DP = 38;

    /** 按钮图标尺寸 */
    private static final int BTN_ICON_DP = 18;

    /** 内容区最大高度占屏幕比例 —— 控制弹窗不再"细长" */
    private static final float MAX_H_RATIO = 0.55f;

    /** 弹窗宽度上限 */
    private static final int MAX_W_DP = 380;

    /** 弹窗与屏幕左右的最小留白 */
    private static final int SIDE_MARGIN_DP = 36;

    private final Context ctx;

    public AppleDialog(Context ctx) {
        this.ctx = ctx;
    }

    // ==================================================================
    // 核心：给任意 AlertDialog 套上 iOS 样式
    // ==================================================================

    /** 在 show() 之前调用，返回一个已挂好样式的 AlertDialog */
    public AlertDialog apply(AlertDialog dialog) {
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface di) {
                try {
                    dress((AlertDialog) di);
                } catch (Throwable ignored) {
                }
            }
        });
        return dialog;
    }

    /**
     * 可直接替换 AlertDialog.Builder 的构建器。
     * 全项目把 `new AlertDialog.Builder(...)` 换成 `new AppleDialog.Builder(...)`
     * 即可让所有弹窗自动获得 iOS 风格 + 主界面配色。
     */
    public static class Builder extends AlertDialog.Builder {

        public Builder(Context c) {
            super(c);
        }

        @Override
        public AlertDialog create() {
            return new AppleDialog(getContext()).apply(super.create());
        }

        @Override
        public AlertDialog show() {
            AlertDialog d = create();
            d.show();
            return d;
        }
    }

    /**
     * 兼容需要自定义 setOnShowListener 的弹窗。
     *
     * ⚠ 直接调 dialog.setOnShowListener() 会覆盖掉 apply() 注入的样式回调，
     *    导致弹窗退回原生样式。必须用本方法替代：
     *    它会先执行 AppleDialog 的样式注入，再执行你的回调，
     *    这样你的回调里可以安全调用 dialog.getButton()。
     *
     * 用法：
     *   AlertDialog d = new AppleDialog.Builder(ctx)
     *           .setTitle(...).setView(...).create();
     *   new AppleDialog(ctx).chain(d, new OnShowListener() { ... }).show();
     */
    public AlertDialog chain(final AlertDialog d,
                             final DialogInterface.OnShowListener user) {
        d.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface di) {
                // 先注入样式（含标题居中、按钮染色、限高滚动）
                try {
                    dress(d);
                } catch (Throwable ignored) {
                }
                // 再执行业务回调
                if (user != null) {
                    try {
                        user.onShow(di);
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
        // 统一进出场动画（必须在 show 之前）
        DialogAnim.apply(d);

        return d;
    }

    /** 实际美化（在 onShow 里执行，此时按钮才已创建） */
    private void dress(AlertDialog d) {
        Window w = d.getWindow();
        if (w == null) return;

        // --- 圆角背景 + 宽度 ---
        w.setBackgroundDrawable(rounded(BG_DIALOG, 14));

        int screenW = screenWidth();
        int width = Math.min(screenW - dp(SIDE_MARGIN_DP), dp(MAX_W_DP));
        w.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);

        // 遮罩加深，突出弹窗
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.dimAmount = 0.55f;
        w.setAttributes(lp);

        // 统一进出场动画（此处再挂一次，防止外部绕过 show() 直接调 dress）
        DialogAnim.apply(w);

        // --- 标题：全宽居中 17sp 半粗体 ---
        TextView title = findTitle(d);
        if (title != null) {
            // 关键：原生 AlertDialog 的标题宽度为 WRAP_CONTENT，
            // 不撑满就 setGravity(CENTER) 是无效的，必须先改成 MATCH_PARENT
            forceMatchWidth(title);
            // 父容器若为 WRAP_CONTENT，子视图的 MATCH_PARENT 等于没生效，一并撑开
            if (title.getParent() instanceof View) {
                forceMatchWidth((View) title.getParent());
            }
            title.setGravity(Gravity.CENTER);
            title.setTextSize(17);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            title.setTextColor(TEXT_MAIN);
            title.setPadding(dp(16), dp(9), dp(16), dp(4));
            title.setAllCaps(false);
            title.setSingleLine(false);
            title.setMaxLines(2);
        }

        // --- 消息：全宽居中 13sp ---
        TextView msg = (TextView) d.findViewById(android.R.id.message);
        if (msg != null) {
            forceMatchWidth(msg);
            msg.setGravity(Gravity.CENTER);
            msg.setTextSize(13);
            msg.setTextColor(TEXT_SUB);
            msg.setLineSpacing(dp(2), 1.25f);
            msg.setPadding(dp(16), title != null ? dp(2) : dp(9), dp(16), dp(8));
        }

        // --- 自定义内容区：限制最大高度 ---
        FrameLayout custom = (FrameLayout) d.findViewById(android.R.id.custom);
        if (custom != null && custom.getChildCount() > 0) {
            custom.setPadding(0, 0, 0, 0);
            limitHeight(custom);
        }

        // 干掉 AlertDialog 按钮面板自带的内缩 padding（各版本 8~12dp 不等）
        int panelId = ctx.getResources().getIdentifier("buttonPanel", "id", "android");
        if (panelId != 0) {
            View panel = d.findViewById(panelId);
            if (panel == null && w.getDecorView() != null) {
                panel = w.getDecorView().findViewById(panelId);
            }
            if (panel != null) {
                panel.setPadding(0, 0, 0, 0);
                if (panel.getLayoutParams() != null) {
                    panel.getLayoutParams().height =
                            ViewGroup.LayoutParams.WRAP_CONTENT;
                }
            }
        }

        // --- 按钮区 ---
        styleButtons(d);
    }

    // ==================================================================
    // 按钮样式
    // ==================================================================

    private void styleButtons(AlertDialog d) {
        Button pos = d.getButton(AlertDialog.BUTTON_POSITIVE);
        Button neg = d.getButton(AlertDialog.BUTTON_NEGATIVE);
        Button neu = d.getButton(AlertDialog.BUTTON_NEUTRAL);

        // 按钮容器：顶部一条分隔线
        ViewGroup bar = null;
        if (pos != null && pos.getParent() instanceof ViewGroup) {
            bar = (ViewGroup) pos.getParent();
        } else if (neg != null && neg.getParent() instanceof ViewGroup) {
            bar = (ViewGroup) neg.getParent();
        } else if (neu != null && neu.getParent() instanceof ViewGroup) {
            bar = (ViewGroup) neu.getParent();
        }
        if (bar != null) {
            bar.setBackgroundColor(Color.TRANSPARENT);
            bar.setBackgroundDrawable(topLine());
            bar.setPadding(0, 0, 0, 0);
            // 干掉原生 AlertDialog 按钮区上下多余留白
            if (bar.getParent() instanceof View) {
                View p = (View) bar.getParent();
                p.setPadding(0, 0, 0, 0);
            }
        }

        // 依次样式化，非首个按钮加左侧竖线
        // 取消 / 关闭 → 红色圈叉；确定 / 完成 → 绿色圈勾
        int idx = 0;
        if (neg != null) styleButton(neg, CLOSE_RED, idx++, "ic_circle_close");
        if (neu != null) styleButton(neu, TEXT_SUB, idx++, null);
        if (pos != null) styleButton(pos, OK_GREEN, idx++, "ic_circle_check");
    }

    private void styleButton(Button b, int color, int index, String iconName) {
        b.setTextColor(color);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setMinHeight(dp(BUTTON_H_DP));
        b.setHeight(dp(BUTTON_H_DP));
        // Button 默认 minWidth 64dp，会撑宽按钮，这里清掉
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));

        // 左侧圆形图标（勾 / 叉），颜色与文字一致
        if (iconName != null) {
            Drawable d = loadIcon(iconName);
            if (d != null) {
                int size = dp(BTN_ICON_DP);
                d = d.mutate();
                d.setBounds(0, 0, size, size);
                d.setTint(color);
                b.setCompoundDrawables(d, null, null, null);
                b.setCompoundDrawablePadding(dp(6));
            }
        }

        // 非首个按钮加左侧竖线分隔
        b.setBackgroundDrawable(index > 0 ? leftLine() : plain());
    }

    /** 按资源名加载矢量图标 */
    private Drawable loadIcon(String name) {
        try {
            int id = ctx.getResources().getIdentifier(name, "drawable", ctx.getPackageName());
            if (id == 0) return null;
            return ctx.getResources().getDrawable(id);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 把按钮左侧图标重新染色（危险确认时把绿色勾改成红色） */
    static void tintCompound(Button b, int color) {
        if (b == null) return;
        Drawable[] ds = b.getCompoundDrawables();
        boolean changed = false;
        for (int i = 0; i < ds.length; i++) {
            if (ds[i] != null) {
                Drawable d = ds[i].mutate();
                d.setTint(color);
                ds[i] = d;
                changed = true;
            }
        }
        if (changed) b.setCompoundDrawables(ds[0], ds[1], ds[2], ds[3]);
    }

    // ==================================================================
    // 内容区高度限制（防细长）
    // ==================================================================

    private void limitHeight(View v) {
        if (!(v instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) v;
        if (group.getChildCount() == 0) return;

        View inner = group.getChildAt(0);
        // 已经包过就不重复处理
        if (inner instanceof MaxHeightScrollView) return;

        group.removeView(inner);
        MaxHeightScrollView sv = new MaxHeightScrollView(ctx,
                (int) (screenHeight() * MAX_H_RATIO));
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        sv.addView(inner);
        group.addView(sv);
    }

    /**
     * 带最大高度的 ScrollView —— 内容超过就滚动，
     * 这样弹窗永远不会被撑成"细长条"。
     */
    public static class MaxHeightScrollView extends ScrollView {
        private final int maxH;

        public MaxHeightScrollView(Context c, int maxH) {
            super(c);
            this.maxH = maxH;
            setFillViewport(false);
            setVerticalScrollBarEnabled(true);
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int h = MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST);
            super.onMeasure(wSpec, h);
        }
    }

    // ==================================================================
    // 便捷构造：把内容包装成可滚动、限高的容器
    // ==================================================================

    /**
     * 把自定义内容包进 ScrollView，并限制最大高度。
     * 供各处 setView() 前调用，避免弹窗过高过窄。
     */
    public View scrollable(View content) {
        MaxHeightScrollView sv = new MaxHeightScrollView(ctx,
                (int) (screenHeight() * MAX_H_RATIO));
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        sv.setPadding(dp(16), dp(4), dp(16), dp(8));
        sv.setClipToPadding(false);
        sv.addView(content);
        return sv;
    }

    // ==================================================================
    // 静态快捷方法
    // ==================================================================

    /** 单按钮提示 */
    public static void message(Context c, String title, String msg) {
        AppleDialog ad = new AppleDialog(c);
        ad.apply(new AlertDialog.Builder(c)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("好", null)
                .create()).show();
    }

    /** 双按钮确认 */
    public static void confirm(Context c, String title, String msg,
                               String okText, final Runnable onOk) {
        AppleDialog ad = new AppleDialog(c);
        ad.apply(new AlertDialog.Builder(c)
                .setTitle(title)
                .setMessage(msg)
                .setNegativeButton("取消", null)
                .setPositiveButton(okText, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (onOk != null) onOk.run();
                    }
                })
                .create()).show();
    }

    /** 危险操作确认（确定按钮为红色） */
    public static void confirmDanger(Context c, String title, String msg,
                                     String okText, final Runnable onOk) {
        AppleDialog ad = new AppleDialog(c);
        final AlertDialog d = new AlertDialog.Builder(c)
                .setTitle(title)
                .setMessage(msg)
                .setNegativeButton("取消", null)
                .setPositiveButton(okText, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface di, int w) {
                        if (onOk != null) onOk.run();
                    }
                })
                .create();
        // 用 chain 保证样式注入后再把「确定」染成红色
        ad.chain(d, new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface di) {
                Button p = d.getButton(AlertDialog.BUTTON_POSITIVE);
                if (p != null) {
                    p.setTextColor(ACCENT_RED);
                    // 图标同色，避免出现"绿勾 + 删除"的矛盾语义
                    tintCompound(p, ACCENT_RED);
                }
            }
        });
        d.show();
    }

    /** 列表选择 */
    public interface OnPick { void onPick(int which); }

    public static void choose(Context c, String title, String[] items,
                              final OnPick onPick) {
        AppleDialog ad = new AppleDialog(c);
        ad.apply(new AlertDialog.Builder(c)
                .setTitle(title)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (onPick != null) onPick.onPick(which);
                    }
                })
                .setNegativeButton("取消", null)
                .create()).show();
    }

    /** 单选列表 */
    public static void chooseSingle(Context c, String title, String[] items,
                                    int checked, final OnPick onPick) {
        AppleDialog ad = new AppleDialog(c);
        ad.apply(new AlertDialog.Builder(c)
                .setTitle(title)
                .setSingleChoiceItems(items, checked,
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int which) {
                                if (onPick != null) onPick.onPick(which);
                                d.dismiss();
                            }
                        })
                .create()).show();
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    /** 把视图宽度强撑为 MATCH_PARENT，让 Gravity.CENTER 真正生效 */
    private void forceMatchWidth(View v) {
        if (v == null) return;
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp == null) {
            lp = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        } else {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        }
        v.setLayoutParams(lp);
    }

    /**
     * 查找 AlertDialog 的标题 TextView。
     * 各 ROM / Material 版本内部 id 不统一，做多重兜底：
     *   1) 按已知 id 直接查
     *   2) 用 Window 的 decorView 再查一遍
     *   3) 遍历视图树，取第一个非 Button 的 TextView（通常就是标题）
     */
    private TextView findTitle(AlertDialog d) {
        int[] ids = new int[]{
                android.R.id.title,
                ctx.getResources().getIdentifier("alertTitle", "id", "android"),
                ctx.getResources().getIdentifier("title", "id", "android"),
                ctx.getResources().getIdentifier("alertTitle", "id", ctx.getPackageName()),
                ctx.getResources().getIdentifier("title", "id", ctx.getPackageName()),
        };

        for (int id : ids) {
            if (id == 0) continue;
            TextView tv = tryFindText(d.findViewById(id));
            if (tv != null) return tv;
        }

        // 兜底 2：从 Window 根部找
        Window w = d.getWindow();
        if (w != null) {
            View decor = w.getDecorView();
            for (int id : ids) {
                if (id == 0) continue;
                TextView tv = tryFindText(decor.findViewById(id));
                if (tv != null) return tv;
            }
            // 兜底 3：遍历视图树
            return scanFirstTitle(decor);
        }
        return null;
    }

    private TextView tryFindText(View v) {
        if (v instanceof TextView && !(v instanceof Button)) {
            return (TextView) v;
        }
        return null;
    }

    /** 深度优先遍历，返回第一个「非按钮、可见、有文字」的 TextView */
    private TextView scanFirstTitle(View root) {
        if (root == null) return null;
        if (root instanceof TextView && !(root instanceof Button)) {
            TextView tv = (TextView) root;
            CharSequence t = tv.getText();
            if (t != null && t.length() > 0 && tv.getVisibility() == View.VISIBLE) {
                return tv;
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView r = scanFirstTitle(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    private android.graphics.drawable.GradientDrawable rounded(int color, float r) {
        // 统一委托 UiKit（半径按 dp 计）
        return UiKit.rounded(ctx, color, r);
    }

    /**
     * 按钮区顶部细线：底层画 BORDER 色，上层用弹窗底色矩形
     * 顶部内缩 1px，只露出一条顶边。
     */
    private android.graphics.drawable.LayerDrawable topLine() {
        int line = Math.max(1, dp(1));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BORDER);
        GradientDrawable body = new GradientDrawable();
        body.setColor(BG_DIALOG);
        android.graphics.drawable.LayerDrawable ld =
                new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[]{bg, body});
        ld.setLayerInset(1, 0, line, 0, 0);
        return ld;
    }

    /** 按钮之间的竖线：只留左侧 1px */
    private android.graphics.drawable.LayerDrawable leftLine() {
        int line = Math.max(1, dp(1));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BORDER);
        GradientDrawable body = new GradientDrawable();
        body.setColor(BG_DIALOG);
        android.graphics.drawable.LayerDrawable ld =
                new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[]{bg, body});
        ld.setLayerInset(1, line, 0, 0, 0);
        return ld;
    }

    private GradientDrawable plain() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(BG_DIALOG);
        return g;
    }

    private int dp(float v) {
        // 统一委托 UiKit，实现全项目只保留一份
        return UiKit.dp(ctx, v);
    }

    private int screenWidth() {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        return dm.widthPixels;
    }

    private int screenHeight() {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        return dm.heightPixels;
    }

    /** 创建 iOS 风格的输入框 */
    public EditText makeInput(String hint, String value, boolean password) {
        EditText et = new EditText(ctx);
        et.setHint(hint);
        et.setText(value == null ? "" : value);
        et.setTextSize(14);
        et.setTextColor(TEXT_MAIN);
        et.setHintTextColor(TEXT_WEAK);
        et.setSingleLine(true);
        et.setPadding(dp(12), dp(10), dp(12), dp(10));
        if (password) {
            et.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        GradientDrawable g = new GradientDrawable();
        g.setColor(BG_PRIMARY);
        g.setCornerRadius(dp(8));
        g.setStroke(Math.max(1, dp(1)), BORDER);
        et.setBackgroundDrawable(g);
        return et;
    }
}
