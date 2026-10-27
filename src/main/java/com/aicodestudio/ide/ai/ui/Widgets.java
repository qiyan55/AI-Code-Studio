package com.aicodestudio.ide.ai.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * 通用控件工厂 —— 把反复手写的控件构建代码收敛成一处。
 *
 * 重构前，下面这些结构在不同文件里各写了一遍：
 *   卡片行容器     WelcomeActivity / BuildToolManageDialog / ModelManageDialog…
 *   开关行         EditBuildToolDialog 手写 vs SettingsDialogBuilder.toggle()
 *   动作按钮       BuildToolManageDialog / EditBuildToolDialog / AppleDialog…
 *   输入框         EditBuildToolDialog / AISettingsDialog / AppleDialog…
 *   分区标题       SettingsDialogBuilder / ModelManageDialog…
 * 同样的 padding、字号、配色在每处都要重新写一遍，改样式时极易漏改。
 *
 * 这里统一提供构造入口，样式参数全部取自 {@link Theme}，
 * 改主题即可全局生效。
 */
public final class Widgets {

    private Widgets() {}

    // ==================== 卡片行容器 ====================

    /**
     * 卡片行容器：圆角深色卡片，横向布局，垂直居中。
     * 列表项、设置项、表单分组都用它。
     */
    public static LinearLayout cardRow(Context ctx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(UiKit.dp(ctx, 11), UiKit.dp(ctx, 11),
                UiKit.dp(ctx, 11), UiKit.dp(ctx, 11));
        row.setBackgroundDrawable(UiKit.rounded(ctx, Theme.BG_CARD, Theme.RADIUS_CARD));
        row.setLayoutParams(UiKit.fullWidth());
        return row;
    }

    /** 竖向卡片容器 */
    public static LinearLayout cardColumn(Context ctx) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiKit.dp(ctx, 12), UiKit.dp(ctx, 12),
                UiKit.dp(ctx, 12), UiKit.dp(ctx, 12));
        box.setBackgroundDrawable(UiKit.rounded(ctx, Theme.BG_CARD, Theme.RADIUS_CARD));
        box.setLayoutParams(UiKit.fullWidth());
        return box;
    }

    // ==================== 分区标题 ====================

    /**
     * 分组小标题（如"云端接口""推理参数"）。
     * 加粗主色小字，上间距略大以和上一组区分。
     */
    public static TextView sectionTitle(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(Theme.ACCENT_BLUE);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = UiKit.fullWidth();
        lp.topMargin = UiKit.dp(ctx, 10);
        lp.bottomMargin = UiKit.dp(ctx, 3);
        tv.setLayoutParams(lp);
        return tv;
    }

    /** 次级说明文字（放在设置项下方或分组末尾） */
    public static TextView hint(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(11);
        tv.setTextColor(Theme.TEXT_TERTIARY);
        LinearLayout.LayoutParams lp = UiKit.fullWidth();
        lp.topMargin = UiKit.dp(ctx, 6);
        tv.setLayoutParams(lp);
        return tv;
    }

    // ==================== 开关行 ====================

    /**
     * 开关行：左侧标题（+可选描述），右侧 Switch。
     *
     * EditBuildToolDialog 里曾手写过一份同样结构，
     * 现统一走这里，与 SettingsDialogBuilder.toggle() 视觉一致。
     */
    public static LinearLayout toggleRow(Context ctx, String title, String desc,
                                          boolean checked,
                                          final CompoundButton.OnCheckedChangeListener l) {
        LinearLayout row = cardRow(ctx);

        LinearLayout textBox = new LinearLayout(ctx);
        textBox.setOrientation(LinearLayout.VERTICAL);
        textBox.setLayoutParams(UiKit.weighted());

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(title);
        tvTitle.setTextSize(14);
        tvTitle.setTextColor(Theme.TEXT_PRIMARY);
        textBox.addView(tvTitle);

        if (desc != null && desc.length() > 0) {
            TextView tvDesc = new TextView(ctx);
            tvDesc.setText(desc);
            tvDesc.setTextSize(11);
            tvDesc.setTextColor(Theme.TEXT_SECOND);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = UiKit.dp(ctx, 1);
            tvDesc.setLayoutParams(lp);
            textBox.addView(tvDesc);
        }
        row.addView(textBox);

        Switch sw = new Switch(ctx);
        sw.setChecked(checked);
        sw.setTag(TAG_SWITCH);          // 便于调用方取回实例
        sw.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        if (l != null) sw.setOnCheckedChangeListener(l);
        row.addView(sw);

        return row;
    }

    /** toggleRow() 内部 Switch 的 tag，供 {@link #getSwitch} 取回 */
    private static final String TAG_SWITCH = "widgets_switch";

    /**
     * 从 toggleRow() 生成的行里取回 Switch 实例。
     * 调用方需要读写开关状态（如回显配置）时用。
     */
    public static Switch getSwitch(LinearLayout row) {
        if (row == null) return null;
        Object o = row.findViewWithTag(TAG_SWITCH);
        return (o instanceof Switch) ? (Switch) o : null;
    }

    // ==================== 输入框 ====================

    /**
     * 标准输入框：深色底 + 描边 + 圆角。
     *
     * @param hint      占位提示
     * @param password  是否密码框（API Key 用）
     */
    public static EditText input(Context ctx, String hint, boolean password) {
        EditText et = new EditText(ctx);
        et.setHint(hint);
        et.setHintTextColor(Theme.TEXT_TERTIARY);
        et.setTextColor(Theme.TEXT_PRIMARY);
        et.setTextSize(14);
        et.setSingleLine(true);
        et.setPadding(UiKit.dp(ctx, 11), UiKit.dp(ctx, 8),
                UiKit.dp(ctx, 11), UiKit.dp(ctx, 8));
        et.setBackgroundDrawable(
                UiKit.bordered(ctx, Theme.BG_PRIMARY, Theme.STROKE, Theme.RADIUS_INPUT));
        if (password) {
            et.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        et.setLayoutParams(UiKit.fullWidth());
        return et;
    }

    /** 带标题标签的输入框（标题在上、输入框在下） */
    public static LinearLayout labeledInput(Context ctx, String label,
                                             String hint, boolean password) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setLayoutParams(UiKit.fullWidth());

        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextSize(12);
        tv.setTextColor(Theme.TEXT_SECOND);
        LinearLayout.LayoutParams lp = UiKit.fullWidth();
        lp.bottomMargin = UiKit.dp(ctx, 4);
        tv.setLayoutParams(lp);
        box.addView(tv);

        box.addView(input(ctx, hint, password));
        return box;
    }

    // ==================== 动作按钮 ====================

    /**
     * 动作按钮（圆角矩形）。
     *
     * @param text      按钮文字
     * @param style     样式：STYLE_OK / STYLE_DANGER / STYLE_NEUTRAL
     * @param iconName  左侧图标资源名（可为 null）
     */
    public static Button actionButton(Context ctx, String text, int style, String iconName) {
        Button btn = new Button(ctx);
        btn.setText(text);
        btn.setTextSize(13);
        btn.setAllCaps(false);
        btn.setMinHeight(0);
        btn.setMinimumHeight(0);
        btn.setMinWidth(0);
        btn.setMinimumWidth(0);
        btn.setPadding(UiKit.dp(ctx, 12), 0, UiKit.dp(ctx, 12), 0);

        int bg, stroke, textColor, iconColor;
        if (style == STYLE_OK) {
            // 完成 / 新增：绿色实底 + 白字
            bg = Theme.OK_GREEN;
            stroke = Theme.OK_GREEN;
            textColor = Theme.TEXT_ON_ACCENT;
            iconColor = Color.WHITE;
        } else if (style == STYLE_DANGER) {
            // 取消 / 删除：黑底 + 红描边 + 红字
            bg = Theme.BG_PRIMARY;
            stroke = Theme.CLOSE_RED;
            textColor = Theme.CLOSE_RED;
            iconColor = Theme.CLOSE_RED;
        } else {
            // 次要操作：黑底 + 灰描边 + 灰字
            bg = Theme.BG_PRIMARY;
            stroke = Theme.STROKE;
            textColor = Theme.TEXT_SECOND;
            iconColor = Theme.TEXT_SECOND;
        }

        btn.setTextColor(textColor);
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(UiKit.dpF(ctx, Theme.RADIUS_CARD));
        g.setStroke(Math.max(1, UiKit.dp(ctx, 1)), stroke);
        btn.setBackgroundDrawable(g);

        if (iconName != null) {
            Drawable d = UiKit.icon(ctx, iconName, iconColor);
            if (d != null) {
                d.setBounds(0, 0, UiKit.dp(ctx, 18), UiKit.dp(ctx, 18));
                btn.setCompoundDrawables(d, null, null, null);
                btn.setCompoundDrawablePadding(UiKit.dp(ctx, 6));
            }
        }
        return btn;
    }

    public static final int STYLE_OK      = 0;  // 绿底白字（完成/新增）
    public static final int STYLE_DANGER  = 1;  // 黑底红描边（取消/关闭）
    public static final int STYLE_NEUTRAL = 2;  // 黑底灰描边（次要操作）

    /**
     * 并排的「取消 / 确定」按钮行。
     * 弹窗底部通用结构，两个按钮等分宽度。
     */
    public static LinearLayout okCancelRow(Context ctx,
                                            String okText, View.OnClickListener ok,
                                            String cancelText, View.OnClickListener cancel) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(UiKit.fullWidth());

        Button btnCancel = actionButton(ctx, cancelText, STYLE_DANGER, "ic_circle_close");
        LinearLayout.LayoutParams lpC = UiKit.weighted();
        lpC.height = UiKit.dp(ctx, Theme.BTN_HEIGHT);
        lpC.setMargins(0, 0, UiKit.dp(ctx, 5), 0);
        btnCancel.setLayoutParams(lpC);
        btnCancel.setOnClickListener(cancel);
        row.addView(btnCancel);

        Button btnOk = actionButton(ctx, okText, STYLE_OK, "ic_circle_check");
        LinearLayout.LayoutParams lpO = UiKit.weighted();
        lpO.height = UiKit.dp(ctx, Theme.BTN_HEIGHT);
        lpO.setMargins(UiKit.dp(ctx, 5), 0, 0, 0);
        btnOk.setLayoutParams(lpO);
        btnOk.setOnClickListener(ok);
        row.addView(btnOk);

        return row;
    }

    // ==================== 图标按钮（圆形） ====================

    /**
     * 圆形图标按钮，用于列表行内的操作（编辑/选用/启停/删除）。
     *
     * @param iconName 图标资源名
     * @param color    图标颜色
     * @param sizeDp   按钮直径
     */
    public static ImageView iconButton(Context ctx, String iconName, int color,
                                        int sizeDp, int iconDp,
                                        final View.OnClickListener l) {
        ImageView iv = new ImageView(ctx);
        iv.setLayoutParams(new LinearLayout.LayoutParams(
                UiKit.dp(ctx, sizeDp), UiKit.dp(ctx, sizeDp)));
        iv.setScaleType(ImageView.ScaleType.CENTER);
        iv.setPadding(0, 0, 0, 0);
        iv.setBackgroundDrawable(UiKit.circlePressed(Color.TRANSPARENT, 0x22FFFFFF));

        Drawable d = UiKit.icon(ctx, iconName, color);
        if (d != null) {
            d.setBounds(0, 0, UiKit.dp(ctx, iconDp), UiKit.dp(ctx, iconDp));
            iv.setImageDrawable(d);
        }
        if (l != null) iv.setOnClickListener(l);
        return iv;
    }

    // ==================== 空态 / 占位 ====================

    /**
     * 空态提示（居中灰色文字）。
     * 列表无数据时用它，避免显示一片空白。
     */
    public static TextView emptyHint(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(Theme.TEXT_TERTIARY);
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = UiKit.fullWidth();
        lp.topMargin = UiKit.dp(ctx, 24);
        lp.bottomMargin = UiKit.dp(ctx, 24);
        tv.setLayoutParams(lp);
        return tv;
    }
}
