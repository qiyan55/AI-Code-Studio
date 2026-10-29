package com.aicodestudio.ide.ai.models;

import com.aicodestudio.ide.ai.ui.Theme;
import com.aicodestudio.ide.ai.ui.UiKit;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.aicodestudio.ide.ai.core.AppConfig;

import java.io.File;
import java.util.List;
import com.aicodestudio.ide.ai.ui.AppleDialog;

/**
 * 页面2：本地量化模型管理页面（文档 5.2）
 * 入口：顶部菜单 [模型] / 设置页 [本地模型推理参数配置入口]
 *
 * - 模型列表：已导入本地模型，显示大小、量化精度、状态（已加载/未加载）
 * - 功能按钮：导入 GGUF 模型、删除、加载、卸载
 * - 推理参数配置：上下文长度、温度、线程数（滑块 + 输入框）
 * - 模型切换开关：云端模式 / 本地模型模式
 *
 * 实现文档 4.1.2 本地量化大模型调度子模块
 */
public class ModelManageActivity extends Activity {

    private LinearLayout modelListContainer;
    private Button btnImport;
    private TextView tvModelModeStatus;
    private Switch swLocalMode;

    // 推理参数
    private SeekBar seekCtx;
    private TextView tvCtxValue;
    private SeekBar seekTemp;
    private TextView tvTempValue;
    private SeekBar seekThreads;
    private TextView tvThreadsValue;
    private SeekBar seekMaxTokens;
    private TextView tvMaxTokensValue;

    private LocalModelManager modelManager;
    private AppConfig config;
    private Handler mainHandler;

    private static final int REQ_IMPORT = 3001;

    private android.graphics.drawable.Drawable ic(String name, int color) {
        try {
            int id = getResources().getIdentifier(name, "drawable", getPackageName());
            if (id == 0) return null;
            android.graphics.drawable.Drawable d = getResources().getDrawable(id);
            if (d == null) return null;
            d = d.mutate();
            d.setTint(color);
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    private void setBackIcon(android.widget.TextView tv) {
        android.graphics.drawable.Drawable d = ic("ic_back", 0xFFE6EDF3);
        if (d == null) { tv.setText("返回"); return; }
        int s = UiKit.dp(this, 20);
        d.setBounds(0, 0, s, s);
        tv.setCompoundDrawables(d, null, null, null);
        tv.setCompoundDrawablePadding(UiKit.dp(this, 4));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mainHandler = new Handler(Looper.getMainLooper());

        try {
            modelManager = LocalModelManager.getInstance(this);
            config = AppConfig.getInstance(this);
        } catch (Throwable t) {
            android.util.Log.e("ModelManage", "init failed", t);
        }

        buildUI();
        refreshModelList();
    }

    private void buildUI() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.BG_PRIMARY);

        // ===== 标题栏 =====
        LinearLayout titleBar = new LinearLayout(this);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        titleBar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        titleBar.setBackgroundColor(Theme.BG_SECONDARY);
        titleBar.setPadding(dp(12), 0, dp(12), 0);

        Button btnBack = new Button(this);
        btnBack.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(36)));
        btnBack.setText("");
        setBackIcon(btnBack);
        btnBack.setTextSize(16);
        btnBack.setTextColor(Theme.ICON_IDLE);
        btnBack.setBackgroundColor(Theme.BG_TERTIARY);
        btnBack.setAllCaps(false);
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        titleBar.addView(btnBack);

        TextView title = new TextView(this);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        title.setText("本地量化模型管理");
        title.setTextColor(Theme.TEXT_PRIMARY);
        title.setTextSize(16);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(dp(10), 0, 0, 0);
        titleBar.addView(title);

        root.addView(titleBar);

        // ===== 可滚动内容 =====
        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        scroll.setFillViewport(true);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(12), dp(14), dp(20));
        scroll.addView(content);
        root.addView(scroll);

        // ===== 模型运行模式切换（文档 5.2 页面2）=====
        addSectionTitle(content, "模型运行模式");

        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setGravity(Gravity.CENTER_VERTICAL);
        modeRow.setPadding(0, dp(8), 0, dp(8));

        TextView modeLabel = new TextView(this);
        modeLabel.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        modeLabel.setText("使用本地模型（离线推理）");
        modeLabel.setTextColor(Theme.ICON_IDLE);
        modeLabel.setTextSize(13);
        modeRow.addView(modeLabel);

        swLocalMode = new Switch(this);
        swLocalMode.setChecked(config != null
                && config.getModelMode() == AppConfig.MODE_LOCAL);
        swLocalMode.setOnCheckedChangeListener(
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(
                            android.widget.CompoundButton b, final boolean checked) {
                        if (checked) {
                            if (modelManager != null && modelManager.hasLoadedModel()) {
                                if (config != null)
                                    config.setModelMode(AppConfig.MODE_LOCAL);
                                updateModeStatus();
                                toast("已切换到本地模型（离线模式）");
                            } else {
                                swLocalMode.setChecked(false);
                                toast("请先加载一个本地模型");
                            }
                        } else {
                            if (config != null)
                                config.setModelMode(AppConfig.MODE_CLOUD);
                            updateModeStatus();
                            toast("已切换到云端模型");
                        }
                    }
                });
        modeRow.addView(swLocalMode);
        content.addView(modeRow);

        tvModelModeStatus = new TextView(this);
        tvModelModeStatus.setTextColor(Theme.TEXT_SECOND);
        tvModelModeStatus.setTextSize(11);
        tvModelModeStatus.setPadding(0, 0, 0, dp(8));
        content.addView(tvModelModeStatus);

        // ===== 已导入模型列表 =====
        addSectionTitle(content, "已导入模型");

        modelListContainer = new LinearLayout(this);
        modelListContainer.setOrientation(LinearLayout.VERTICAL);
        modelListContainer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        content.addView(modelListContainer);

        btnImport = new Button(this);
        LinearLayout.LayoutParams lpImp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        lpImp.setMargins(0, dp(10), 0, 0);
        btnImport.setLayoutParams(lpImp);
        btnImport.setText("+ 导入 GGUF / GGML 模型");
        btnImport.setTextColor(Theme.ACCENT_BLUE);
        btnImport.setBackgroundColor(Theme.BG_TERTIARY);
        btnImport.setAllCaps(false);
        btnImport.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickModelFile(); }
        });
        content.addView(btnImport);

        // ===== 推理参数配置（文档 4.1.2-4）=====
        addSectionTitle(content, "推理参数配置");

        // 上下文长度
        seekCtx = addSeekRow(content, "上下文长度", 512, 32768,
                config == null ? 4096 : config.getContextLength(),
                new SeekListener() {
                    @Override public void onValue(int v) {
                        if (config != null) config.setContextLength(v);
                        if (modelManager != null) modelManager.setContextLength(v);
                        tvCtxValue.setText(String.valueOf(v));
                    }
                });
        tvCtxValue = (TextView) seekCtx.getTag();

        // 温度 (x100)
        seekTemp = addSeekRow(content, "温度 (0 ~ 2.0)", 0, 200,
                (int) ((config == null ? 0.8f : config.getTemperature()) * 100),
                new SeekListener() {
                    @Override public void onValue(int v) {
                        float t = v / 100f;
                        if (config != null) config.setTemperature(t);
                        if (modelManager != null) modelManager.setTemperature(t);
                        tvTempValue.setText(String.format("%.2f", t));
                    }
                });
        tvTempValue = (TextView) seekTemp.getTag();
        tvTempValue.setText(String.format("%.2f",
                config == null ? 0.8f : config.getTemperature()));

        // 重复惩罚 (x10)
        final TextView[] tvRepeatPenaltyValue = new TextView[1];
        SeekBar seekRepeatPenalty = addSeekRow(content, "重复惩罚 (1.0 ~ 2.0)", 10, 20,
                (int) ((config == null ? 1.0f : config.getRepeatPenalty()) * 10),
                new SeekListener() {
                    @Override public void onValue(int v) {
                        float rp = v / 10f;
                        if (config != null) config.setRepeatPenalty(rp);
                        if (tvRepeatPenaltyValue[0] != null)
                            tvRepeatPenaltyValue[0].setText(String.format("%.1f", rp));
                    }
                });
        tvRepeatPenaltyValue[0] = (TextView) seekRepeatPenalty.getTag();
        tvRepeatPenaltyValue[0].setText(String.format("%.1f",
                config == null ? 1.0f : config.getRepeatPenalty()));

        // 线程数
        int maxThreads = Math.max(1, Runtime.getRuntime().availableProcessors());
        seekThreads = addSeekRow(content, "推理线程数", 1, maxThreads,
                config == null ? 4 : config.getThreadCount(),
                new SeekListener() {
                    @Override public void onValue(int v) {
                        if (config != null) config.setThreadCount(v);
                        if (modelManager != null) modelManager.setThreadCount(v);
                        tvThreadsValue.setText(String.valueOf(v));
                    }
                });
        tvThreadsValue = (TextView) seekThreads.getTag();

        // 最大输出 Token
        seekMaxTokens = addSeekRow(content, "最大输出 Token", 128, 8192,
                config == null ? 2048 : config.getMaxTokens(),
                new SeekListener() {
                    @Override public void onValue(int v) {
                        if (config != null) config.setMaxTokens(v);
                        if (modelManager != null) modelManager.setMaxOutputTokens(v);
                        tvMaxTokensValue.setText(String.valueOf(v));
                    }
                });
        tvMaxTokensValue = (TextView) seekMaxTokens.getTag();

        // ===== v1.46 系统提示词自定义（编辑后完整替换内置，实时生效） =====
        addSectionTitle(content, "系统提示词");

        TextView tvSysPreview = new TextView(this);
        tvSysPreview.setTextColor(Theme.TEXT_SECOND);
        tvSysPreview.setTextSize(11);
        tvSysPreview.setPadding(0, 0, 0, dp(6));
        content.addView(tvSysPreview);
        refreshSysPromptPreview(tvSysPreview);

        Button btnEditSys = new Button(this);
        LinearLayout.LayoutParams lpSys = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        lpSys.setMargins(0, dp(4), 0, 0);
        btnEditSys.setLayoutParams(lpSys);
        btnEditSys.setText("编辑系统提示词（替换默认，发送消息时生效）");
        btnEditSys.setTextColor(Theme.ACCENT_BLUE);
        btnEditSys.setBackgroundColor(Theme.BG_TERTIARY);
        btnEditSys.setAllCaps(false);
        btnEditSys.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSysPromptEditor(tvSysPreview); }
        });
        content.addView(btnEditSys);

        Button btnResetSys = new Button(this);
        LinearLayout.LayoutParams lpReset = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38));
        lpReset.setMargins(0, dp(6), 0, 0);
        btnResetSys.setLayoutParams(lpReset);
        btnResetSys.setText("恢复默认系统提示词");
        btnResetSys.setTextColor(Theme.TEXT_SECOND);
        btnResetSys.setBackgroundColor(Theme.BG_SECONDARY);
        btnResetSys.setAllCaps(false);
        btnResetSys.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (config != null) config.setSystemPromptOverride(null);
                toast("已恢复默认系统提示词");
                refreshSysPromptPreview(tvSysPreview);
            }
        });
        content.addView(btnResetSys);

        // v1.51ab：恢复默认推理参数按钮
        Button btnResetParams = new Button(this);
        LinearLayout.LayoutParams lpResetParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38));
        lpResetParams.setMargins(0, dp(6), 0, 0);
        btnResetParams.setLayoutParams(lpResetParams);
        btnResetParams.setText("恢复默认推理参数（温度0.8 / 重复惩罚1.0）");
        btnResetParams.setTextColor(Theme.ACCENT_BLUE);
        btnResetParams.setBackgroundColor(Theme.BG_TERTIARY);
        btnResetParams.setAllCaps(false);
        btnResetParams.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (config != null) {
                    config.setTemperature(0.8f);
                    config.setRepeatPenalty(1.1f);
                }
                toast("已恢复默认推理参数，请重进页面生效");
            }
        });
        content.addView(btnResetParams);

        // 说明
        TextView tip = new TextView(this);
        tip.setText("说明：选用本地模型时，全部代码业务无需网络（文档 4.1.2-5）。\n"
                + "本地模型负责基础代码编写、语法修复；复杂项目可切换云端模型。\n"
                + "系统提示词支持 {projectPath} 占位符（自动替换为当前项目路径），"
                + "修改后在用户下一次发送消息时实时生效，不会合并到默认提示词。");
        tip.setTextColor(Theme.TEXT_TERTIARY);
        tip.setTextSize(11);
        tip.setPadding(0, dp(14), 0, 0);
        content.addView(tip);

        setContentView(root);
        updateModeStatus();
    }

    /** 刷新系统提示词预览（显示当前生效内容前 150 字） */
    private void refreshSysPromptPreview(TextView tv) {
        try {
            String cur = com.aicodestudio.ide.ai.agent.AgentSystemPrompt.build(this);
            String head = cur.length() > 150 ? cur.substring(0, 150) + "…" : cur;
            tv.setText("当前生效（" + (config != null && config.getSystemPromptOverride() != null
                    ? "自定义" : "内置默认") + "）：\n" + head);
        } catch (Throwable t) {
            tv.setText("（读取系统提示词失败）");
        }
    }

    /** 编辑系统提示词：预填当前生效内容，保存后完整替换（不合并） */
    private void showSysPromptEditor(final TextView previewTv) {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        et.setTextSize(12);
        et.setSingleLine(false);
        et.setMinLines(10);
        et.setText(com.aicodestudio.ide.ai.agent.AgentSystemPrompt.build(this));
        et.setTextColor(Theme.TEXT_PRIMARY);
        et.setHintTextColor(Theme.TEXT_TERTIARY);
        et.setBackgroundColor(Theme.BG_CARD);
        int pad = dp(10);
        et.setPadding(pad, pad, pad, pad);

        // v1.48：统一使用项目 AppleDialog 样式
        new AppleDialog.Builder(this)
                .setTitle("编辑系统提示词")
                .setMessage("修改后完整替换默认提示词（不合并）。支持 {projectPath} 占位符。")
                .setView(et)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String v = et.getText() == null ? "" : et.getText().toString();
                        if (config != null) config.setSystemPromptOverride(v.trim().isEmpty() ? null : v.trim());
                        toast("系统提示词已更新，下一次发送消息时生效");
                        refreshSysPromptPreview(previewTv);
                    }
                })
                .setNegativeButton("取消", null)
                .setNeutralButton("恢复默认", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (config != null) config.setSystemPromptOverride(null);
                        toast("已恢复默认系统提示词");
                        refreshSysPromptPreview(previewTv);
                    }
                })
                .show();
    }

    private void addSectionTitle(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Theme.ACCENT_BLUE);
        tv.setTextSize(13);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(0, dp(16), 0, dp(6));
        parent.addView(tv);

        View line = new View(this);
        line.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        line.setBackgroundColor(Theme.STROKE);
        parent.addView(line);
    }

    interface SeekListener { void onValue(int v); }

    /** 滑块 + 数值显示行 */
    private SeekBar addSeekRow(LinearLayout parent, String label,
                               int min, int max, int value,
                               final SeekListener cb) {
        // 标签 + 当前值
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, dp(12), 0, dp(2));

        TextView tvLabel = new TextView(this);
        tvLabel.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tvLabel.setText(label);
        tvLabel.setTextColor(Theme.ICON_IDLE);
        tvLabel.setTextSize(13);
        head.addView(tvLabel);

        final TextView tvValue = new TextView(this);
        tvValue.setText(String.valueOf(value));
        tvValue.setTextColor(Theme.ACCENT_BLUE);
        tvValue.setTextSize(13);
        tvValue.setTypeface(null, Typeface.BOLD);
        head.addView(tvValue);
        parent.addView(head);

        // 滑块
        final int offset = min;
        SeekBar seek = new SeekBar(this);
        seek.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        seek.setMax(max - min);
        seek.setProgress(Math.max(0, Math.min(max - min, value - min)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                int v = p + offset;
                tvValue.setText(String.valueOf(v));
                if (fromUser && cb != null) cb.onValue(v);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        seek.setTag(tvValue); // 保存数值 TextView 引用
        parent.addView(seek);
        return seek;
    }

    // ================= 模型列表 =================

    private void refreshModelList() {
        if (modelListContainer == null) return;
        modelListContainer.removeAllViews();

        List<LocalModelManager.ModelInfo> models =
                modelManager == null ? null : modelManager.getAllModels();

        if (models == null || models.size() == 0) {
            TextView empty = new TextView(this);
            empty.setText("暂无本地模型\n\n点击下方按钮导入 GGUF / GGML 量化模型");
            empty.setTextColor(Theme.TEXT_TERTIARY);
            empty.setTextSize(12);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(20), 0, dp(20));
            modelListContainer.addView(empty);
            updateModeStatus();
            return;
        }

        for (final LocalModelManager.ModelInfo m : models) {
            modelListContainer.addView(buildModelRow(m));
        }
        updateModeStatus();
    }

    private View buildModelRow(final LocalModelManager.ModelInfo m) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(lp);
        card.setBackgroundColor(Theme.BG_CARD);
        card.setPadding(dp(12), dp(12), dp(12), dp(10));

        // 第一行：名称 + 状态
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(this);
        name.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        name.setText(m.name);
        name.setTextColor(Theme.TEXT_PRIMARY);
        name.setTextSize(14);
        name.setTypeface(null, Typeface.BOLD);
        name.setSingleLine(true);
        row1.addView(name);

        // 状态标签
        TextView state = new TextView(this);
        state.setText(getStateText(m.state));
        state.setTextColor(Color.parseColor(getStateColor(m.state)));
        state.setTextSize(10);
        state.setPadding(dp(6), dp(2), dp(6), dp(2));
        row1.addView(state);
        card.addView(row1);

        // 第二行：大小 + 量化精度 + 格式
        TextView info = new TextView(this);
        info.setText(m.getSizeReadable() + "  |  " + m.quantization
                + "  |  " + m.format);
        info.setTextColor(Theme.TEXT_SECOND);
        info.setTextSize(11);
        info.setPadding(0, dp(4), 0, dp(4));
        card.addView(info);

        // 第三行：路径
        TextView path = new TextView(this);
        path.setText(m.filePath);
        path.setTextColor(Theme.TEXT_TERTIARY);
        path.setTextSize(10);
        path.setSingleLine(true);
        card.addView(path);

        // 操作按钮
        LinearLayout ops = new LinearLayout(this);
        ops.setOrientation(LinearLayout.HORIZONTAL);
        ops.setPadding(0, dp(10), 0, 0);

        // 加载 / 卸载
        if (m.state == LocalModelManager.STATE_LOADED) {
            addOpButton(ops, "卸载", "#F0883E", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    modelManager.unloadModel(m.id);
                    refreshModelList();
                    toast("已卸载: " + m.name);
                }
            });
        } else {
            addOpButton(ops, "加载", "#2EA043", new View.OnClickListener() {
                @Override public void onClick(View v) { loadModel(m); }
            });
        }

        addOpButton(ops, "删除", "#F85149", new View.OnClickListener() {
            @Override public void onClick(View v) { confirmDelete(m); }
        });

        card.addView(ops);
        return card;
    }

    private void addOpButton(LinearLayout parent, String text,
                             String color, View.OnClickListener l) {
        Button b = new Button(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(34));
        lp.setMargins(0, 0, dp(8), 0);
        b.setLayoutParams(lp);
        b.setText(text);
        b.setTextSize(12);
        b.setTextColor(Color.parseColor(color));
        b.setBackgroundColor(Theme.BG_TERTIARY);
        b.setAllCaps(false);
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setOnClickListener(l);
        parent.addView(b);
    }

    private String getStateText(int state) {
        switch (state) {
            case LocalModelManager.STATE_LOADING: return "加载中";
            case LocalModelManager.STATE_LOADED:  return "已加载";
            case LocalModelManager.STATE_ERROR:   return "错误";
            case LocalModelManager.STATE_NOT_LOADED:
            default:                              return "未加载";
        }
    }

    private String getStateColor(int state) {
        switch (state) {
            case LocalModelManager.STATE_LOADING: return "#F0883E";
            case LocalModelManager.STATE_LOADED:  return "#3FB950";
            case LocalModelManager.STATE_ERROR:   return "#F85149";
            case LocalModelManager.STATE_NOT_LOADED:
            default:                              return "#8B949E";
        }
    }

    /** 加载模型（文档 5.3 规则5：耗时操作展示加载进度） */
    private void loadModel(final LocalModelManager.ModelInfo m) {
        final AlertDialog progress = new AppleDialog.Builder(this)
                .setTitle("加载模型")
                .setMessage("正在加载 " + m.name + "...")
                .setCancelable(false)
                .show();

        modelManager.loadModel(m.id, new LocalModelManager.LoadCallback() {
            @Override
            public void onSuccess() {
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        try { progress.dismiss(); } catch (Throwable ignored) {}
                        refreshModelList();
                        toast("模型加载成功: " + m.name);
                    }
                });
            }

            @Override
            public void onError(final String msg) {
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        try { progress.dismiss(); } catch (Throwable ignored) {}
                        refreshModelList();
                        toast("加载失败: " + msg);
                    }
                });
            }
        });
    }

    private void confirmDelete(final LocalModelManager.ModelInfo m) {
        new AppleDialog.Builder(this)
                .setTitle("确认删除")
                .setMessage("确定删除模型「" + m.name + "」吗？\n（仅移除记录，不删除源文件）")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        modelManager.deleteModel(m.id);
                        refreshModelList();
                        toast("已删除");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ================= 导入模型 =================

    private void pickModelFile() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQ_IMPORT);
        } catch (Exception e) {
            // 降级：手动输入路径
            showManualImportDialog();
        }
    }

    private void showManualImportDialog() {
        final EditText et = new EditText(this);
        et.setHint("模型文件完整路径，如 /sdcard/model.gguf");
        et.setTextColor(Theme.TEXT_PRIMARY);
        et.setHintTextColor(Theme.TEXT_TERTIARY);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setPadding(dp(16), dp(12), dp(16), dp(12));

        new AppleDialog.Builder(this)
                .setTitle("导入模型")
                .setMessage("无法打开文件选择器，请手动输入模型文件路径")
                .setView(et)
                .setPositiveButton("导入", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String path = et.getText().toString().trim();
                        doImport(path);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_IMPORT && resultCode == RESULT_OK && data != null) {
            android.net.Uri uri = data.getData();
            if (uri == null) return;
            String path = resolvePath(uri);
            if (path == null || path.length() == 0) {
                // 复制到私有目录后导入
                path = copyToPrivate(uri);
            }
            if (path != null) doImport(path);
            else toast("无法读取所选文件，请改用手动输入路径");
        }
    }

    private String resolvePath(android.net.Uri uri) {
        try {
            if ("file".equalsIgnoreCase(uri.getScheme())) return uri.getPath();
            String[] proj = {android.provider.MediaStore.MediaColumns.DATA};
            android.database.Cursor c = getContentResolver()
                    .query(uri, proj, null, null, null);
            if (c != null) {
                int idx = c.getColumnIndexOrThrow(proj[0]);
                c.moveToFirst();
                String p = c.getString(idx);
                c.close();
                return p;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private String copyToPrivate(android.net.Uri uri) {
        try {
            String name = "model_" + System.currentTimeMillis() + ".gguf";
            File out = new File(getExternalFilesDir("models"), name);
            File dir = out.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();

            java.io.InputStream is = getContentResolver().openInputStream(uri);
            if (is == null) return null;
            java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
            fos.close();
            is.close();
            return out.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    private void doImport(String path) {
        if (path == null || path.length() == 0) return;
        File f = new File(path);
        if (!f.exists()) {
            toast("文件不存在: " + path);
            return;
        }
        try {
            LocalModelManager.ModelInfo m = modelManager.importModel(path);
            if (m != null) {
                refreshModelList();
                toast("已导入: " + m.name + " (" + m.getSizeReadable() + ")");
            } else {
                toast("导入失败：不支持的格式或读取失败");
            }
        } catch (Throwable t) {
            toast("导入失败: " + t.getMessage());
        }
    }

    private void updateModeStatus() {
        if (tvModelModeStatus == null) return;
        boolean local = config != null
                && config.getModelMode() == AppConfig.MODE_LOCAL;
        boolean loaded = modelManager != null && modelManager.hasLoadedModel();
        String status;
        int color;
        if (local && loaded) {
            LocalModelManager.ModelInfo m = modelManager.getActiveModel();
            status = "当前：本地离线模式 · " + (m == null ? "" : m.name);
            color = Theme.STATE_LOADED;
        } else if (local) {
            status = "当前：本地模式（但未加载模型，将回退云端）";
            color = Theme.ACCENT_ORANGE;
        } else {
            status = "当前：云端模型模式";
            color = Theme.ACCENT_BLUE;
        }
        tvModelModeStatus.setText(status);
        tvModelModeStatus.setTextColor(color);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshModelList();
    }

    private void toast(String msg) {
        // 统一委托 UiKit
        UiKit.toast(this, msg);
    }

    private int dp(int v) {
        // 统一委托 UiKit，实现全项目只保留一份
        return UiKit.dp(this, v);
    }
}
