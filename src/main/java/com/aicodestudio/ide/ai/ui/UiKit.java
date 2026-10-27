package com.aicodestudio.ide.ai.ui;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.Toast;

/**
 * 通用 UI 工具集 —— 消除各文件里重复实现的私有方法。
 *
 * 重构前：
 *   dp()          在 16 个文件中各写了一遍（WelcomeActivity、ChatPanel、
 *                 AppleDialog、BuildToolManageDialog…实现完全相同）
 *   icon()        在 3 个文件中重复
 *   rounded()     在 5 个文件中重复
 *   toast()       Toast.makeText 散落 36 处
 *   矢量图标加载   getIdentifier(...,"drawable",...) 散落 9 处
 *
 * 重构后：
 *   统一为静态方法，各文件直接调用，实现只有一份。
 */
public final class UiKit {

    private UiKit() {}

    // ==================== 单位换算 ====================

    /** dp → px（四舍五入） */
    public static int dp(Context ctx, float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** dp → px（保留小数，用于圆角半径等需要精度的场景） */
    public static float dpF(Context ctx, float v) {
        return v * ctx.getResources().getDisplayMetrics().density;
    }

    /** sp → px */
    public static int sp(Context ctx, float v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, v,
                ctx.getResources().getDisplayMetrics());
    }

    /**
     * 获取屏幕宽度（px）。
     * 弹窗宽度计算、气泡最大宽度等场景统一用这个。
     */
    public static int screenWidth(Context ctx) {
        return ctx.getResources().getDisplayMetrics().widthPixels;
    }

    /** 获取屏幕高度（px） */
    public static int screenHeight(Context ctx) {
        return ctx.getResources().getDisplayMetrics().heightPixels;
    }

    // ==================== 提示 ====================

    /** 短暂提示 */
    public static void toast(Context ctx, String msg) {
        try {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    /** 长提示（内容较长时用） */
    public static void toastLong(Context ctx, String msg) {
        try {
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }

    // ==================== 图标 ====================

    /**
     * 按资源名加载矢量图标并染色。
     *
     * 统一入口的好处：资源查找失败、Drawable 为空等异常在此兜底，
     * 调用方不必各自写 try-catch（重构前这些判空逻辑散落各处）。
     *
     * @param name  图标资源名（不含扩展名，如 "ic_build"）
     * @param color 目标颜色
     * @return 染色后的 Drawable；加载失败返回 null
     */
    public static Drawable icon(Context ctx, String name, int color) {
        try {
            int id = ctx.getResources().getIdentifier(name, "drawable", ctx.getPackageName());
            if (id == 0) return null;
            Drawable d = ctx.getResources().getDrawable(id);
            if (d == null) return null;
            d = d.mutate();          // 避免共享同一实例导致染色互相影响
            d.setTint(color);
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 加载图标并按指定像素尺寸设置 bounds。
     * 图标资源自带留白时（如 ic_build 只占画布 72.5%），
     * 调用方可配合"满幅版"资源使用。
     */
    public static Drawable iconSized(Context ctx, String name, int color, int sizePx) {
        Drawable d = icon(ctx, name, color);
        if (d != null) d.setBounds(0, 0, sizePx, sizePx);
        return d;
    }

    // ==================== 形状 ====================

    /** 纯色圆角矩形 */
    public static GradientDrawable rounded(Context ctx, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dpF(ctx, radiusDp));
        return g;
    }

    /**
     * 纯色圆角矩形 —— 半径按【像素】计，不做 dp 换算。
     *
     * 用于调用方手上传的已经是 px 的场景
     * （例如 WelcomeActivity.rounded() 原本就是 setCornerRadius(r) 直接传像素）。
     * 若误用 rounded()（会乘 density）会让圆角放大约 3 倍。
     */
    public static GradientDrawable roundedPx(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    /** 圆角矩形 + 描边 */
    public static GradientDrawable bordered(Context ctx, int bg, int stroke, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(dpF(ctx, radiusDp));
        g.setStroke(Math.max(1, dp(ctx, 1)), stroke);
        return g;
    }

    /** 圆角矩形 + 指定宽度的描边 */
    public static GradientDrawable bordered(Context ctx, int bg, int stroke,
                                            float radiusDp, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(dpF(ctx, radiusDp));
        g.setStroke(Math.max(1, dp(ctx, strokeDp)), stroke);
        return g;
    }

    /** 正圆（半径 = 宽高一半，几何上严格同心） */
    public static GradientDrawable circle(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setShape(GradientDrawable.OVAL);
        return g;
    }

    /** 胶囊形（圆角 = 高度一半，两端为完整半圆） */
    public static GradientDrawable capsule(Context ctx, int color, int heightDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dpF(ctx, heightDp / 2f));
        return g;
    }

    /** 胶囊描边按钮背景 */
    public static GradientDrawable capsuleOutline(Context ctx, int bg, int stroke, int heightDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(dpF(ctx, heightDp / 2f));
        g.setStroke(Math.max(1, dp(ctx, 1)), stroke);
        return g;
    }

    /**
     * 圆形按压反馈背景。
     *
     * 用于大圆形图标按钮（如编译按钮）：
     * 正常态与按下态各画一个 OVAL，按下时变色给出触觉反馈。
     */
    public static StateListDrawable circlePressed(int normalColor, int pressedColor) {
        StateListDrawable sld = new StateListDrawable();
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(pressedColor);
        pressed.setShape(GradientDrawable.OVAL);
        sld.addState(new int[]{android.R.attr.state_pressed}, pressed);

        GradientDrawable normal = new GradientDrawable();
        normal.setColor(normalColor);
        normal.setShape(GradientDrawable.OVAL);
        sld.addState(new int[]{}, normal);
        return sld;
    }

    /**
     * 圆角按压反馈背景（用于列表项、卡片）。
     */
    public static StateListDrawable roundedPressed(Context ctx, int normalColor,
                                                    int pressedColor, float radiusDp) {
        StateListDrawable sld = new StateListDrawable();
        GradientDrawable p = new GradientDrawable();
        p.setColor(pressedColor);
        p.setCornerRadius(dpF(ctx, radiusDp));
        sld.addState(new int[]{android.R.attr.state_pressed}, p);

        GradientDrawable n = new GradientDrawable();
        n.setColor(normalColor);
        n.setCornerRadius(dpF(ctx, radiusDp));
        sld.addState(new int[]{}, n);
        return sld;
    }

    // ==================== 布局参数 ====================

    /** 横向撑满、纵向自适应的 LinearLayout.LayoutParams */
    public static LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    /** 指定高度的横向撑满参数 */
    public static LinearLayout.LayoutParams fullWidth(int heightDp, Context ctx) {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, heightDp));
    }

    /** 等分宽度的参数（weight=1，用于并排按钮） */
    public static LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    /** 垂直占位间隔 */
    public static android.view.View gap(Context ctx, int heightDp) {
        android.view.View v = new android.view.View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, heightDp)));
        return v;
    }

    /** 1px 分隔线 */
    public static android.view.View divider(Context ctx, int color) {
        android.view.View v = new android.view.View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(ctx, 0.5f))));
        v.setBackgroundColor(color);
        return v;
    }

    // ==================== 尺寸常量（Gravity 复用） ====================

    /** 居中（LinearLayout 通用） */
    public static int center() {
        return Gravity.CENTER;
    }
}
