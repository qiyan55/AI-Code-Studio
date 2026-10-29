package com.aicodestudio.ide.ai.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import com.aicodestudio.ide.ai.ui.AppleDialog;

/**
 * 通用设置弹窗构建器
 *
 * 三个工作区各自的设置弹窗都用它拼装，保证样式统一：
 *   - 分组标题
 *   - 开关项（Switch）
 *   - 滑块项（SeekBar + 数值显示）
 *   - 文本输入项（EditText）
 *   - 单项选择项（点击弹出选择列表）
 *   - 纯说明文字
 */
public class SettingsDialogBuilder {

    // 配色：与 AppleDialog / 主界面共用同一套，避免弹窗与页面割裂
    private static final int BG        = AppleDialog.BG_SECOND;
    private static final int CARD      = AppleDialog.BG_DIALOG;
    private static final int TEXT      = AppleDialog.TEXT_MAIN;
    private static final int TEXT_SUB  = AppleDialog.TEXT_SUB;
    private static final int ACCENT    = AppleDialog.ACCENT_BLUE;
    private static final int BORDER    = AppleDialog.BORDER;

    private final Activity act;
    private final LinearLayout container;

    public SettingsDialogBuilder(Activity act) {
        this.act = act;
        this.container = new LinearLayout(act);
        this.container.setOrientation(LinearLayout.VERTICAL);
        this.container.setBackgroundColor(BG);
        this.container.setPadding(dp(12), dp(4), dp(12), dp(4));
    }

    private int dp(float v) {
        // 统一委托 UiKit，实现全项目只保留一份
        return UiKit.dp(act, v);
    }

    // ================= 分组标题 =================

    public SettingsDialogBuilder section(String title) {
        TextView tv = new TextView(act);
        tv.setText(title);
        tv.setTextSize(12);
        tv.setTextColor(ACCENT);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        lp.bottomMargin = dp(3);
        tv.setLayoutParams(lp);
        container.addView(tv);
        return this;
    }

    // ================= 开关项 =================

    public SettingsDialogBuilder toggle(String title, String desc,
                                        boolean checked,
                                        final OnToggleChanged l) {
        LinearLayout row = cardRow();

        LinearLayout textBox = new LinearLayout(act);
        textBox.setOrientation(LinearLayout.VERTICAL);
        textBox.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvTitle = new TextView(act);
        tvTitle.setText(title);
        tvTitle.setTextSize(14);
        tvTitle.setTextColor(TEXT);
        textBox.addView(tvTitle);

        if (desc != null && desc.length() > 0) {
            TextView tvDesc = new TextView(act);
            tvDesc.setText(desc);
            tvDesc.setTextSize(11);
            tvDesc.setTextColor(TEXT_SUB);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(1);
            tvDesc.setLayoutParams(lp);
            textBox.addView(tvDesc);
        }
        row.addView(textBox);

        Switch sw = new Switch(act);
        sw.setChecked(checked);
        sw.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        if (l != null) {
            sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton b, boolean v) {
                    l.onChanged(v);
                }
            });
        }
        row.addView(sw);

        container.addView(row);
        return this;
    }

    // ================= 滑块项 =================

    public SettingsDialogBuilder slider(String title, int min, int max, int value,
                                        final String unit,
                                        final OnValueChanged l) {
        LinearLayout row = cardRow();
        row.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView tvTitle = new TextView(act);
        tvTitle.setText(title);
        tvTitle.setTextSize(14);
        tvTitle.setTextColor(TEXT);
        tvTitle.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(tvTitle);

        final TextView tvValue = new TextView(act);
        tvValue.setText(value + (unit == null ? "" : unit));
        tvValue.setTextSize(13);
        tvValue.setTextColor(ACCENT);
        head.addView(tvValue);

        row.addView(head);

        SeekBar bar = new SeekBar(act);
        bar.setMax(max - min);
        bar.setProgress(value - min);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        bar.setLayoutParams(lp);
        row.addView(bar);

        final int offset = min;
        if (l != null) {
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onStartTrackingTouch(SeekBar b) {}
                @Override public void onStopTrackingTouch(SeekBar b) {}

                @Override
                public void onProgressChanged(SeekBar b, int progress, boolean fromUser) {
                    int real = progress + offset;
                    tvValue.setText(real + (unit == null ? "" : unit));
                    l.onChanged(real);
                }
            });
        }

        container.addView(row);
        return this;
    }

    // ================= 文本输入项 =================

    public SettingsDialogBuilder input(String title, String hint, String value,
                                       boolean password, final OnTextChanged l) {
        LinearLayout row = cardRow();
        row.setOrientation(LinearLayout.VERTICAL);

        TextView tvTitle = new TextView(act);
        tvTitle.setText(title);
        tvTitle.setTextSize(14);
        tvTitle.setTextColor(TEXT);
        row.addView(tvTitle);

        final EditText et = new EditText(act);
        et.setHint(hint);
        et.setText(value == null ? "" : value);
        et.setTextSize(13);
        et.setTextColor(TEXT);
        et.setHintTextColor(0xFF484F58);
        et.setSingleLine(true);
        et.setBackgroundDrawable(inputBg());
        if (password) {
            et.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        et.setLayoutParams(lp);
        et.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.addView(et);

        if (l != null) {
            // 实时保存：TextWatcher 监听文本变化，立即保存配置
            et.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(android.text.Editable s) {
                    l.onChanged(s.toString().trim());
                }
            });
            // 失去焦点时也保存一次（兜底）
            et.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (!hasFocus) l.onChanged(et.getText().toString().trim());
                }
            });
        }

        container.addView(row);
        return this;
    }

    // ================= 单项选择项 =================

    public SettingsDialogBuilder choose(String title, final String[] options,
                                        final String[] values, String currentValue,
                                        final OnTextChanged l) {
        int idx = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(currentValue)) { idx = i; break; }
        }
        final int[] cur = {idx};

        LinearLayout row = cardRow();
        row.setOrientation(LinearLayout.VERTICAL);

        TextView tvTitle = new TextView(act);
        tvTitle.setText(title);
        tvTitle.setTextSize(14);
        tvTitle.setTextColor(TEXT);
        row.addView(tvTitle);

        final TextView tvValue = new TextView(act);
        tvValue.setText(options[idx]);
        tvValue.setTextSize(13);
        tvValue.setTextColor(ACCENT);
        tvValue.setBackgroundDrawable(inputBg());
        tvValue.setPadding(dp(10), dp(9), dp(10), dp(9));
        tvValue.setCompoundDrawablesWithIntrinsicBounds(0, 0,
                android.R.drawable.arrow_down_float, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        tvValue.setLayoutParams(lp);
        row.addView(tvValue);

        tvValue.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AppleDialog.Builder(act)
                        .setTitle(title)
                        .setSingleChoiceItems(options, cur[0],
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int which) {
                                        cur[0] = which;
                                        tvValue.setText(options[which]);
                                        if (l != null) l.onChanged(values[which]);
                                        d.dismiss();
                                    }
                                })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });

        container.addView(row);
        return this;
    }

    // ================= 说明文字 =================

    public SettingsDialogBuilder note(String text) {
        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextSize(11);
        tv.setTextColor(TEXT_SUB);
        tv.setLineSpacing(dp(2), 1.2f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        tv.setLayoutParams(lp);
        container.addView(tv);
        return this;
    }

    // ================= 显示 =================

    public void show(String title) {
        new AppleDialog.Builder(act)
                .setTitle(title)
                .setView(scrollWrap())
                .setPositiveButton("完成", null)
                .show();
    }

    public void show(String title, final OnConfirmListener confirm) {
        new AppleDialog.Builder(act)
                .setTitle(title)
                .setView(scrollWrap())
                .setPositiveButton("完成", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (confirm != null) confirm.onConfirm();
                    }
                })
                .show();
    }

    /**
     * 只构建内容视图（限高滚动容器），不弹窗。
     * 供外部把设置内容嵌进自己的布局里（如 GlobalSettingsDialog 的分类切换）。
     */
    public View build() {
        return scrollWrap();
    }

    /**
     * 内容包进限高滚动容器：
     * 设置项再多也不会把弹窗撑成细长条，超过屏幕 60% 就开始滚动。
     */
    private View scrollWrap() {
        android.util.DisplayMetrics dm = act.getResources().getDisplayMetrics();
        int maxH = (int) (dm.heightPixels * 0.60f);
        AppleDialog.MaxHeightScrollView sv =
                new AppleDialog.MaxHeightScrollView(act, maxH);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        sv.setBackgroundColor(BG);
        sv.setPadding(dp(6), dp(2), dp(6), dp(2));
        sv.setClipToPadding(false);
        sv.addView(container);
        return sv;
    }

    // ================= 内部工具 =================

    private LinearLayout cardRow() {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundDrawable(rounded(CARD, 8));
        row.setPadding(dp(11), dp(8), dp(11), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        row.setLayoutParams(lp);
        return row;
    }

    private android.graphics.drawable.GradientDrawable rounded(int color, float r) {
        // 统一委托 UiKit（半径按 dp 计）
        return UiKit.rounded(act, color, r);
    }

    private android.graphics.drawable.GradientDrawable inputBg() {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(0xFF0D1117);
        g.setCornerRadius(dp(6));
        g.setStroke(Math.max(1, dp(1)), BORDER);
        return g;
    }

    // ================= 回调 =================

    public interface OnToggleChanged { void onChanged(boolean v); }
    public interface OnValueChanged  { void onChanged(int v); }
    public interface OnTextChanged   { void onChanged(String v); }
    public interface OnConfirmListener { void onConfirm(); }
}
