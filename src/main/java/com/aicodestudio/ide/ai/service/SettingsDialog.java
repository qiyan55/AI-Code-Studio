package com.aicodestudio.ide.ai.service;

import com.aicodestudio.ide.ai.ui.Theme;
import com.aicodestudio.ide.ai.ui.UiKit;
import com.aicodestudio.ide.ai.ui.AISettingsDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.aicodestudio.ide.ai.core.AppConfig;
import com.aicodestudio.ide.ai.models.ModelManageDialog;

/**
 * 弹窗3：系统全局设置弹窗（文档 5.2 / 第9章）
 *
 * 分类标签：编辑器设置 / AI联动设置 / 编译设置 / 外观设置
 *
 * AI联动设置包含全部自动推送开关（文档 5.2 弹窗3）：
 * - 代码错误自动发送AI
 * - 函数完成自动检测
 * - 编译失败自动推送日志
 * - 允许AI自动创建编译工具
 */
public class SettingsDialog extends Dialog {

    private LinearLayout tabBar;
    private ScrollView contentScroll;
    private LinearLayout contentContainer;

    private int currentTab = 1; // 默认 AI联动设置
    private AppConfig config;
    private OnConfigChangedListener listener;

    private static final String[] TABS =
            {"编辑器", "AI联动", "编译", "外观"};

    public interface OnConfigChangedListener {
        void onConfigChanged();
    }

    public SettingsDialog(Context ctx) {
        super(ctx);
        this.config = AppConfig.getInstance(ctx);
    }

    public void setListener(OnConfigChangedListener l) { this.listener = l; }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        // 背景色交给 window 的圆角 drawable，root 必须透明
        root.setBackgroundColor(Color.TRANSPARENT);

        // ===== 标题（居中，与 AppleDialog 一致）=====
        TextView title = new TextView(getContext());
        title.setText("系统设置");
        title.setTextColor(Theme.TEXT_PRIMARY);
        title.setTextSize(17);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setPadding(dp(16), dp(14), dp(16), dp(8));
        root.addView(title);

        // ===== 分类标签 =====
        tabBar = new LinearLayout(getContext());
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38)));
        tabBar.setBackgroundColor(Theme.BG_PRIMARY);
        root.addView(tabBar);

        for (int i = 0; i < TABS.length; i++) {
            final int index = i;
            final Button tab = new Button(getContext());
            tab.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
            tab.setText(TABS[i]);
            tab.setTextSize(12);
            tab.setAllCaps(false);
            tab.setBackgroundColor(Theme.BG_PRIMARY);
            tab.setTextColor(index == currentTab
                    ? Theme.ACCENT_BLUE : Theme.TEXT_SECOND);
            tab.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    currentTab = index;
                    renderTabs();
                    renderContent();
                }
            });
            tabBar.addView(tab);
        }

        // ===== 内容区 =====
        contentScroll = new ScrollView(getContext());
        contentScroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        contentScroll.setBackgroundColor(Theme.BG_SECONDARY);

        contentContainer = new LinearLayout(getContext());
        contentContainer.setOrientation(LinearLayout.VERTICAL);
        contentContainer.setPadding(dp(16), dp(8), dp(16), dp(8));
        contentScroll.addView(contentContainer);
        root.addView(contentScroll);

        // ===== 关闭按钮 =====
        Button btnClose = new Button(getContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        lp.setMargins(dp(16), dp(4), dp(16), dp(12));
        btnClose.setLayoutParams(lp);
        btnClose.setText("关闭");
        btnClose.setTextColor(Theme.TEXT_SECOND);
        btnClose.setBackgroundColor(Theme.BG_TERTIARY);
        btnClose.setAllCaps(false);
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dismiss(); }
        });
        root.addView(btnClose);

        setContentView(root);

        if (getWindow() != null) {
            // 圆角卡片背景（与 AppleDialog 完全一致：#1C2333 + 14dp 圆角）
            getWindow().setBackgroundDrawable(roundedCard(0xFF1C2333, 14));

            // 统一进出场动画（与 AppleDialog 同一套）
            com.aicodestudio.ide.ai.ui.DialogAnim.apply(getWindow());

            android.view.WindowManager.LayoutParams wlp = getWindow().getAttributes();
            android.util.DisplayMetrics dm = getContext().getResources()
                    .getDisplayMetrics();
            int maxW = (int) (Theme.DIALOG_MAX_W_DP * dm.density + 0.5f);
            int availW = dm.widthPixels - (int) (36 * dm.density + 0.5f);
            wlp.width = Math.min(maxW, availW);
            wlp.height = (int) (dm.heightPixels * Theme.DIALOG_MAX_H_RATIO);
            wlp.dimAmount = 0.55f;
            getWindow().setAttributes(wlp);
        }

        renderTabs();
        renderContent();
    }

    /** 圆角卡片背景（与 AppleDialog 的 BG_DIALOG + 14dp 圆角一致） */
    private android.graphics.drawable.GradientDrawable roundedCard(int color, float rDp) {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dpF(rDp));
        return g;
    }

    /** dp 转像素（浮点，圆角用） */
    private float dpF(float v) {
        // 统一委托 UiKit
        return UiKit.dpF(getContext(), v);
    }

    private void renderTabs() {
        for (int i = 0; i < tabBar.getChildCount(); i++) {
            Button b = (Button) tabBar.getChildAt(i);
            b.setTextColor(i == currentTab
                    ? Theme.ACCENT_BLUE : Theme.TEXT_SECOND);
        }
    }

    private void renderContent() {
        contentContainer.removeAllViews();
        switch (currentTab) {
            case 0: renderEditorSettings(); break;
            case 1: renderAILinkageSettings(); break;
            case 2: renderCompileSettings(); break;
            case 3: renderAppearanceSettings(); break;
        }
    }

    // ================= Tab1 编辑器设置 =================
    private void renderEditorSettings() {
        addSectionTitle("语法高亮");

        addSpinnerRow("语法高亮引擎",
                new String[]{"内置引擎", "第三方工具链(预留)"},
                config.getHighlightEngine(),
                new OnSpinnerSelected() {
                    @Override public void onSelected(int which) {
                        config.setHighlightEngine(which);
                        if (which == 1) {
                            Toast.makeText(getContext(),
                                    "第三方工具链接入中，当前仍使用内置引擎",
                                    Toast.LENGTH_SHORT).show();
                        }
                        notifyChanged();
                    }
                });

        addSectionTitle("代码补全");

        addSpinnerRow("自动补全模式",
                new String[]{"关闭", "本地关键词补全", "AI 智能补全"},
                config.getAutoCompleteMode(),
                new OnSpinnerSelected() {
                    @Override public void onSelected(int which) {
                        config.setAutoCompleteMode(which);
                        notifyChanged();
                    }
                });

        addSectionTitle("错误检测");

        // 9.2 函数编写完成自动检测
        addSwitchRow("函数编写完成自动执行语法检测",
                config.isDetectOnFunctionFinish(),
                new OnSwitchChanged() {
                    @Override public void onChanged(boolean v) {
                        config.setDetectOnFunctionFinish(v);
                        notifyChanged();
                    }
                });

        // 9.3 文件编辑完成后整体检测
        addSwitchRow("文件编辑完成后整体检测全部代码",
                config.isDetectOnFileFinish(),
                new OnSwitchChanged() {
                    @Override public void onChanged(boolean v) {
                        config.setDetectOnFileFinish(v);
                        notifyChanged();
                    }
                });

        // 10.3 防抖延迟
        addNumberRow("语法检测防抖延迟(ms)",
                String.valueOf(config.getDebounceMs()),
                new OnNumberChanged() {
                    @Override public void onChanged(int value) {
                        config.setDebounceMs(value);
                        notifyChanged();
                    }
                });

        // 10.2 文件快照
        addSwitchRow("AI修改代码前自动生成文件快照",
                config.isSnapshotEnabled(),
                new OnSwitchChanged() {
                    @Override public void onChanged(boolean v) {
                        config.setSnapshotEnabled(v);
                        notifyChanged();
                    }
                });
    }

    // ================= Tab2 AI联动设置 =================
    private void renderAILinkageSettings() {
        addSectionTitle("模型接口");

        addEditRow("API Key", config.getApiKey(),
                "sk-...", new OnTextChanged() {
                    @Override public void onChanged(String v) {
                        config.setApiKey(v);
                        notifyChanged();
                    }
                });

        addEditRow("API Base URL", config.getApiBase(),
                "https://api.openai.com/v1", new OnTextChanged() {
                    @Override public void onChanged(String v) {
                        config.setApiBase(v);
                        notifyChanged();
                    }
                });

        addEditRow("模型名称", config.getModelName(),
                "gpt-4o", new OnTextChanged() {
                    @Override public void onChanged(String v) {
                        config.setModelName(v);
                        notifyChanged();
                    }
                });

        addSectionTitle("自动推送开关（文档 5.2 弹窗3）");

        // 9.1 代码错误自动发送 AI
        addSwitchRow("代码检测出错后，自动发送错误信息给 AI",
                config.isAutoSendErrorToAI(),
                new OnSwitchChanged() {
                    @Override public void onChanged(boolean v) {
                        config.setAutoSendErrorToAI(v);
                        notifyChanged();
                    }
                });

        // 9.4 AI修复代码自动覆盖
        addSwitchRow("AI 修复代码自动覆盖源码（关闭则需弹窗确认）",
                config.isAutoOverwriteFix(),
                new OnSwitchChanged() {
                    @Override public void onChanged(boolean v) {
                        config.setAutoOverwriteFix(v);
                        notifyChanged();
                    }
                });

        // 9.6 允许 AI 自动新增编译工具
        addSwitchRow("允许 AI 自动新增编译工具配置",
                config.isAllowAIAutoCreateTool(),
                new OnSwitchChanged() {
                    @Override public void onChanged(boolean v) {
                        config.setAllowAIAutoCreateTool(v);
                        notifyChanged();
                    }
                });

        // 4.1.3 AI创建工具策略
        addSwitchRow("AI 创建工具前先预览确认（关闭则静默创建）",
                config.isAIToolCreateNeedConfirm(),
                new OnSwitchChanged() {
                    @Override public void onChanged(boolean v) {
                        config.setAIToolCreateNeedConfirm(v);
                        notifyChanged();
                    }
                });
    }

    // ================= Tab3 编译设置 =================
    private void renderCompileSettings() {
        addSectionTitle("编译联动");

        // 9.5 编译失败自动推送日志
        addSwitchRow("编译失败自动推送日志至 AI",
                config.isAutoSendCompileLog(),
                new OnSwitchChanged() {
                    @Override public void onChanged(boolean v) {
                        config.setAutoSendCompileLog(v);
                        notifyChanged();
                    }
                });

        addSectionTitle("架构与权限");

        // 10.4 CPU 架构
        addSpinnerRow("CPU 架构",
                new String[]{"arm64-v8a", "armeabi-v7a", "x86_64", "x86"},
                indexOfArch(config.getCpuArch()),
                new OnSpinnerSelected() {
                    @Override public void onSelected(int which) {
                        String[] archs = {"arm64-v8a", "armeabi-v7a", "x86_64", "x86"};
                        config.setCpuArch(archs[which]);
                        notifyChanged();
                    }
                });

        // 10.7 权限安全
        addSwitchRow("允许 AI 自动下载外部编译二进制文件",
                config.isAllowAIDownloadBinary(),
                new OnSwitchChanged() {
                    @Override public void onChanged(boolean v) {
                        config.setAllowAIDownloadBinary(v);
                        notifyChanged();
                    }
                });
    }

    // ================= Tab4 外观设置 =================
    private void renderAppearanceSettings() {
        addSectionTitle("模型运行模式（文档 9.9）");

        addSpinnerRow("模型运行模式",
                new String[]{"云端模型", "本地量化模型"},
                config.getModelMode(),
                new OnSpinnerSelected() {
                    @Override public void onSelected(int which) {
                        if (which == AppConfig.MODE_LOCAL) {
                            try {
                                com.aicodestudio.ide.ai.models.LocalModelManager mgr =
                                        com.aicodestudio.ide.ai.models.LocalModelManager
                                                .getInstance(getContext());
                                if (!mgr.hasLoadedModel()) {
                                    Toast.makeText(getContext(),
                                            "请先加载本地模型", Toast.LENGTH_LONG).show();
                                    return;
                                }
                            } catch (Throwable t) {
                                return;
                            }
                        }
                        config.setModelMode(which);
                        notifyChanged();
                    }
                });

        addSectionTitle("本地模型推理参数（文档 9.10）");

        // 上下文长度
        addNumberRow("上下文长度", String.valueOf(config.getContextLength()),
                new OnNumberChanged() {
                    @Override public void onChanged(int value) {
                        config.setContextLength(value);
                        notifyChanged();
                    }
                });

        // 温度
        addNumberRow("温度 (x100，如 70 = 0.7)",
                String.valueOf((int)(config.getTemperature() * 100)),
                new OnNumberChanged() {
                    @Override public void onChanged(int value) {
                        config.setTemperature(value / 100f);
                        notifyChanged();
                    }
                });

        // 最大 Token
        addNumberRow("最大输出 Token", String.valueOf(config.getMaxTokens()),
                new OnNumberChanged() {
                    @Override public void onChanged(int value) {
                        config.setMaxTokens(value);
                        notifyChanged();
                    }
                });

        // 线程数
        addNumberRow("推理线程数", String.valueOf(config.getThreadCount()),
                new OnNumberChanged() {
                    @Override public void onChanged(int value) {
                        config.setThreadCount(value);
                        notifyChanged();
                    }
                });

        // 跳转模型管理页（文档 9.10 入口 + 页面2）
        Button btnModel = new Button(getContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
        lp.setMargins(0, dp(12), 0, 0);
        btnModel.setLayoutParams(lp);
        btnModel.setText("本地量化模型管理");
        btnModel.setTextColor(Color.WHITE);
        btnModel.setBackgroundColor(Theme.ACCENT_BLUE);
        btnModel.setAllCaps(false);
        btnModel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dismiss();
                new AISettingsDialog(getContext()).show();
            }
        });
        contentContainer.addView(btnModel);
    }

    // ================= 控件构建 =================

    private void addSectionTitle(String text) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextColor(Theme.ACCENT_BLUE);
        tv.setTextSize(13);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(0, dp(14), 0, dp(6));
        contentContainer.addView(tv);

        View line = new View(getContext());
        line.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        line.setBackgroundColor(Theme.STROKE);
        contentContainer.addView(line);
    }

    private void addSwitchRow(String label, boolean checked,
                              final OnSwitchChanged cb) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));

        TextView tv = new TextView(getContext());
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tv.setText(label);
        tv.setTextColor(Theme.ICON_IDLE);
        tv.setTextSize(13);
        tv.setPadding(0, 0, dp(8), 0);
        row.addView(tv);

        Switch sw = new Switch(getContext());
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(
                            android.widget.CompoundButton b, boolean v) {
                        if (cb != null) cb.onChanged(v);
                    }
                });
        row.addView(sw);
        contentContainer.addView(row);
    }

    private void addSpinnerRow(String label, String[] options,
                               int selected, final OnSpinnerSelected cb) {
        TextView tvLabel = new TextView(getContext());
        tvLabel.setText(label);
        tvLabel.setTextColor(Theme.TEXT_SECOND);
        tvLabel.setTextSize(12);
        tvLabel.setPadding(0, dp(10), 0, dp(4));
        contentContainer.addView(tvLabel);

        final android.widget.Spinner sp = new android.widget.Spinner(getContext());
        sp.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));
        android.widget.ArrayAdapter<String> adapter =
                new android.widget.ArrayAdapter<String>(getContext(),
                        android.R.layout.simple_spinner_dropdown_item,
                        java.util.Arrays.asList(options));
        sp.setAdapter(adapter);
        if (selected >= 0 && selected < options.length) sp.setSelection(selected);
        sp.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            boolean first = true;
            @Override
            public void onItemSelected(android.widget.AdapterView<?> p,
                                       View v, int pos, long id) {
                if (first) { first = false; return; } // 忽略初始化回调
                if (cb != null) cb.onSelected(pos);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });
        contentContainer.addView(sp);
    }

    private void addEditRow(String label, String value, String hint,
                            final OnTextChanged cb) {
        TextView tvLabel = new TextView(getContext());
        tvLabel.setText(label);
        tvLabel.setTextColor(Theme.TEXT_SECOND);
        tvLabel.setTextSize(12);
        tvLabel.setPadding(0, dp(10), 0, dp(4));
        contentContainer.addView(tvLabel);

        final EditText et = new EditText(getContext());
        et.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        et.setHint(hint);
        et.setHintTextColor(Theme.TEXT_TERTIARY);
        et.setTextColor(Theme.TEXT_PRIMARY);
        et.setTextSize(13);
        et.setBackgroundColor(Theme.BG_PRIMARY);
        et.setPadding(dp(10), dp(10), dp(10), dp(10));
        if (value != null) et.setText(value);
        et.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus && cb != null) {
                    cb.onChanged(et.getText().toString().trim());
                }
            }
        });
        contentContainer.addView(et);
    }

    private void addNumberRow(String label, String value,
                              final OnNumberChanged cb) {
        TextView tvLabel = new TextView(getContext());
        tvLabel.setText(label);
        tvLabel.setTextColor(Theme.TEXT_SECOND);
        tvLabel.setTextSize(12);
        tvLabel.setPadding(0, dp(10), 0, dp(4));
        contentContainer.addView(tvLabel);

        final EditText et = new EditText(getContext());
        et.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        et.setTextColor(Theme.TEXT_PRIMARY);
        et.setTextSize(13);
        et.setBackgroundColor(Theme.BG_PRIMARY);
        et.setPadding(dp(10), dp(10), dp(10), dp(10));
        et.setText(value);
        et.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus && cb != null) {
                    try {
                        int val = Integer.parseInt(
                                et.getText().toString().trim());
                        cb.onChanged(val);
                    } catch (NumberFormatException ignored) {}
                }
            }
        });
        contentContainer.addView(et);
    }

    private int indexOfArch(String arch) {
        String[] archs = {"arm64-v8a", "armeabi-v7a", "x86_64", "x86"};
        for (int i = 0; i < archs.length; i++) {
            if (archs[i].equals(arch)) return i;
        }
        return 0;
    }

    private void notifyChanged() {
        if (listener != null) listener.onConfigChanged();
    }

    interface OnSwitchChanged { void onChanged(boolean v); }
    interface OnSpinnerSelected { void onSelected(int which); }
    interface OnTextChanged { void onChanged(String v); }
    interface OnNumberChanged { void onChanged(int value); }

    private int dp(int v) {
        // 统一委托 UiKit，实现全项目只保留一份
        return UiKit.dp(getContext(), v);
    }
}
