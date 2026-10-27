package com.aicodestudio.ide.ai.models;

import com.aicodestudio.ide.ai.ui.Theme;
import com.aicodestudio.ide.ai.ui.UiKit;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Typeface;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.aicodestudio.ide.ai.core.AppConfig;
import com.aicodestudio.ide.ai.ui.AppleDialog;
import com.aicodestudio.ide.ai.engine.DeviceProfile;
import com.aicodestudio.ide.ai.engine.EngineManager;
import com.aicodestudio.ide.ai.engine.LlamaCppEngine;
import com.aicodestudio.ide.ai.engine.NativeLibraryLoader;
import com.aicodestudio.ide.ai.ui.DialogAnim;
import com.aicodestudio.ide.ai.engine.ModelAdmission;
import com.aicodestudio.ide.ai.util.StorageAccessActivity;

import android.content.Intent;
import android.net.Uri;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 本地 AI / 本地量化模型管理 —— 弹窗版（文档 5.2 页面2 / 4.1.2）
 *
 * 原先是一个独立 Activity，现统一为 Apple 风格弹窗，
 * 与欢迎页、侧边菜单、工作区设置的其余弹窗保持一致。
 *
 * 内容：
 *   1. 模型运行模式（云端 / 本地离线）+ 当前状态
 *   2. 已导入模型列表（大小 / 量化 / 状态，加载·卸载·删除）
 *   3. 导入 GGUF / GGML（内嵌文件浏览器，不依赖系统文件选择器）
 *   4. 推理参数：上下文长度 / 温度 / 线程数 / 最大输出 Token
 *
 * 全部样式走 AppleDialog，标题自动居中、内容区自动限高滚动。
 */
public class ModelManageDialog {

    private final Context ctx;
    private AlertDialog dialog;

    private LocalModelManager modelManager;
    private AppConfig config;
    private Handler mainHandler;

    private LinearLayout modelListContainer;
    private TextView tvModelModeStatus;
    private Switch swLocalMode;

    public ModelManageDialog(Context ctx) {
        this.ctx = ctx;
        this.mainHandler = new Handler(Looper.getMainLooper());
        try {
            modelManager = LocalModelManager.getInstance(ctx);
            config = AppConfig.getInstance(ctx);
        } catch (Throwable t) {
            android.util.Log.e("ModelManageDialog", "init failed", t);
        }
    }

    public void show() {
        View content = buildContent();
        refreshModelList();

        dialog = new AppleDialog.Builder(ctx)
                .setTitle("本地模型")
                .setView(content)
                .setNegativeButton("关闭", null)
                .show();
    }

    // ================= 内容构建（不含 ScrollView，
    //                    AppleDialog 会自动包一层限高滚动容器）=================

    /**
     * 只构建内容视图，不弹窗。
     * 供 AISettingsDialog 把本地模型配置嵌进「本地模型」Tab 里。
     */
    public View buildContent() {
        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(6), dp(14), dp(8));

        // ===== 1. 推理引擎（只读状态）=====
        // 选择权在 AISettingsDialog 的顶部 Tab，这里只显示当前状态，
        // 避免两处都能改导致状态不一致。
        addSectionTitle(content, "推理引擎");

        tvModelModeStatus = new TextView(ctx);
        tvModelModeStatus.setTextColor(Theme.TEXT_SECOND);
        tvModelModeStatus.setTextSize(11);
        tvModelModeStatus.setLineSpacing(dp(2), 1.3f);
        tvModelModeStatus.setPadding(0, 0, 0, dp(6));
        content.addView(tvModelModeStatus);
        updateModeStatus();

        // ===== 2. 已导入模型列表 =====
        addSectionTitle(content, "已导入模型");

        modelListContainer = new LinearLayout(ctx);
        modelListContainer.setOrientation(LinearLayout.VERTICAL);
        modelListContainer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        content.addView(modelListContainer);

        Button btnImport = new Button(ctx);
        LinearLayout.LayoutParams lpImp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(IMP_BTN_H_DP));
        lpImp.setMargins(0, dp(8), 0, 0);
        btnImport.setLayoutParams(lpImp);
        btnImport.setText("导入 GGUF / GGML 模型");
        btnImport.setTextSize(13);
        // 主题蓝描边 + 半透明蓝底 + 圆角，与整体配色统一
        GradientDrawable impBg = new GradientDrawable();
        impBg.setColor(Theme.alpha(Theme.ACCENT_BLUE, 0.10f));
        // 胶囊形：圆角 = 高度的一半
        impBg.setCornerRadius(dpF(IMP_BTN_H_DP / 2f));
        impBg.setStroke(Math.max(1, dp(1)), Theme.ACCENT_BLUE);
        btnImport.setBackgroundDrawable(impBg);
        btnImport.setTextColor(Theme.ACCENT_BLUE);
        btnImport.setAllCaps(false);
        // 固定最小高度，避免圆角与高度不匹配导致胶囊变形
        btnImport.setMinHeight(dp(IMP_BTN_H_DP));
        btnImport.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showImportMenu(); }
        });
        content.addView(btnImport);

        // 引擎 .so 导入：不必重新打包 APK，直接把编译好的 .so 所在目录选进来
        Button btnEngine = new Button(ctx);
        LinearLayout.LayoutParams lpEng = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(IMP_BTN_H_DP));
        lpEng.setMargins(0, dp(6), 0, 0);
        btnEngine.setLayoutParams(lpEng);
        // 引擎通常已内置在 APK 的 jniLibs 里。内置成功时该按钮退化为
        // 状态展示（点击看后端详情），只有确实缺库时才作为导入入口使用，
        // 避免用户看到"导入引擎"却不知道引擎其实已经在了。
        final boolean engineReady = LlamaCppEngine.isEngineReady();
        if (engineReady) {
            String v = LlamaCppEngine.staticVersion();
            btnEngine.setText("推理引擎已就绪 · " + (v == null ? "llama.cpp" : v));
        } else {
            btnEngine.setText("导入推理引擎（.so 目录）");
        }
        btnEngine.setTextSize(13);
        int engColor = engineReady ? Theme.STATE_LOADED : Theme.ACCENT_ORANGE;
        GradientDrawable engBg = new GradientDrawable();
        engBg.setColor(Theme.alpha(engColor, 0.10f));
        engBg.setCornerRadius(dpF(IMP_BTN_H_DP / 2f));
        engBg.setStroke(Math.max(1, dp(1)), engColor);
        btnEngine.setBackgroundDrawable(engBg);
        btnEngine.setTextColor(engColor);
        btnEngine.setAllCaps(false);
        btnEngine.setMinHeight(dp(IMP_BTN_H_DP));
        btnEngine.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (engineReady) {
                    AlertDialog d = new AlertDialog.Builder(ctx)
                            .setTitle("推理引擎")
                            .setMessage(LlamaCppEngine.backendInfoSafe())
                            .setPositiveButton("关闭", null)
                            .create();
                    DialogAnim.apply(d);
                    d.show();
                } else {
                    showEngineImportPicker();
                }
            }
        });
        content.addView(btnEngine);

        // ===== 3. 推理参数 =====
        addSectionTitle(content, "推理参数");

        addSeekRow(content, "上下文长度", 512, 32768,
                config == null ? 4096 : config.getContextLength(),
                new SeekListener() {
                    @Override public void onValue(int v) {
                        if (config != null) config.setContextLength(v);
                        if (modelManager != null) modelManager.setContextLength(v);
                    }
                });

        addSeekRow(content, "温度 (0 ~ 2.0)", 0, 200,
                (int) ((config == null ? 0.7f : config.getTemperature()) * 100),
                new SeekListener() {
                    @Override public void onValue(int v) {
                        float t = v / 100f;
                        if (config != null) config.setTemperature(t);
                        if (modelManager != null) modelManager.setTemperature(t);
                    }
                }, true);

        // v1.51ci：重复惩罚滑块（llama.cpp默认1.0）
        addSeekRow(content, "重复惩罚 (1.0 ~ 2.0)", 100, 200,
                (int) ((config == null ? 1.1f : config.getRepeatPenalty()) * 100),
                new SeekListener() {
                    @Override public void onValue(int v) {
                        float rp = v / 100f;
                        if (config != null) config.setRepeatPenalty(rp);
                    }
                }, true);

        int maxThreads = Math.max(1, Runtime.getRuntime().availableProcessors());
        addSeekRow(content, "推理线程数", 1, maxThreads,
                config == null ? 4 : config.getThreadCount(),
                new SeekListener() {
                    @Override public void onValue(int v) {
                        if (config != null) config.setThreadCount(v);
                        if (modelManager != null) modelManager.setThreadCount(v);
                    }
                });

        addSeekRow(content, "最大输出 Token", 128, 8192,
                config == null ? 2048 : config.getMaxTokens(),
                new SeekListener() {
                    @Override public void onValue(int v) {
                        if (config != null) config.setMaxTokens(v);
                        if (modelManager != null) modelManager.setMaxOutputTokens(v);
                    }
                });

        // v1.153：旧版"编辑系统提示词"入口已移除——统一走 AISettingsDialog 的
        // 「系统提示词」卡片（常驻 L1 + 写文档规范两段独立编辑器），避免两套入口/两个字段互相覆盖。

        TextView tip = new TextView(ctx);
        tip.setText("本地模型离线运行，无需网络；复杂项目可切回云端模型。");
        tip.setTextColor(Theme.TEXT_TERTIARY);
        tip.setTextSize(11);
        tip.setPadding(0, dp(10), 0, dp(2));
        content.addView(tip);

        updateModeStatus();
        return content;
    }

    /** v1.46：刷新系统提示词预览（显示当前生效内容前 150 字 + 自定义/内置标识） */
    private void refreshSysPromptPreview(TextView tv) {
        try {
            String cur = com.aicodestudio.ide.ai.agent.AgentSystemPrompt.build(ctx);
            String head = cur.length() > 150 ? cur.substring(0, 150) + "…" : cur;
            boolean custom = config != null && config.getSystemPromptOverride() != null;
            tv.setText("当前生效（" + (custom ? "自定义" : "内置默认") + "）：\n" + head);
        } catch (Throwable t) {
            tv.setText("（读取系统提示词失败）");
        }
    }

    /** v1.46：编辑系统提示词：预填当前生效内容，保存后完整替换（不合并），支持 {projectPath} 占位符 */
    private void showSysPromptEditor(final TextView previewTv) {
        final EditText et = new EditText(ctx);
        et.setGravity(Gravity.TOP | Gravity.START);
        et.setTextSize(12);
        et.setSingleLine(false);
        et.setMinLines(10);
        et.setText(com.aicodestudio.ide.ai.agent.AgentSystemPrompt.build(ctx));
        et.setTextColor(Theme.TEXT_PRIMARY);
        et.setHintTextColor(Theme.TEXT_TERTIARY);
        et.setBackgroundColor(Theme.BG_CARD);
        int pad = dp(10);
        et.setPadding(pad, pad, pad, pad);

        // v1.48：统一使用项目 AppleDialog 样式（与全项目其他弹窗一致：iOS 风格 + 主界面配色）
        new AppleDialog.Builder(ctx)
                .setTitle("编辑系统提示词")
                .setMessage("修改后完整替换默认提示词（不合并），下一次发送消息时生效。支持 {projectPath} 占位符（自动替换为当前项目路径）。")
                .setView(et)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String v = et.getText() == null ? "" : et.getText().toString();
                        if (config != null) {
                            config.setSystemPromptOverride(v.trim().isEmpty() ? null : v.trim());
                        }
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
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(Theme.ACCENT_BLUE);
        tv.setTextSize(13);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(0, dp(12), 0, dp(5));
        parent.addView(tv);

        View line = new View(ctx);
        line.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        line.setBackgroundColor(Theme.STROKE);
        parent.addView(line);
    }

    interface SeekListener { void onValue(int v); }

    /** 滑块 + 数值显示行；floatMode 表示数值需除以 100 显示 */
    private void addSeekRow(LinearLayout parent, String label,
                            int min, int max, int value,
                            final SeekListener cb) {
        addSeekRow(parent, label, min, max, value, cb, false);
    }

    private void addSeekRow(LinearLayout parent, String label,
                            int min, int max, int value,
                            final SeekListener cb, final boolean floatMode) {
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, dp(10), 0, dp(2));

        TextView tvLabel = new TextView(ctx);
        tvLabel.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tvLabel.setText(label);
        tvLabel.setTextColor(Theme.ICON_IDLE);
        tvLabel.setTextSize(13);
        head.addView(tvLabel);

        final TextView tvValue = new TextView(ctx);
        tvValue.setText(floatMode
                ? String.format("%.2f", value / 100f) : String.valueOf(value));
        tvValue.setTextColor(Theme.ACCENT_BLUE);
        tvValue.setTextSize(13);
        tvValue.setTypeface(null, Typeface.BOLD);
        head.addView(tvValue);
        parent.addView(head);

        final int offset = min;
        SeekBar seek = new SeekBar(ctx);
        seek.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        seek.setMax(max - min);
        seek.setProgress(Math.max(0, Math.min(max - min, value - min)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                int v = p + offset;
                tvValue.setText(floatMode
                        ? String.format("%.2f", v / 100f) : String.valueOf(v));
                if (fromUser && cb != null) cb.onValue(v);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        // 滑块在滚动容器内拖动时，禁止父容器抢事件
        seek.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent ev) {
                if (v.getParent() != null) {
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                }
                return false;
            }
        });
        parent.addView(seek);
    }

    // ================= 模型列表 =================

    /** 刷新模型列表（切到本 Tab 时由宿主调用） */
    public void refreshModelList() {
        if (modelListContainer == null) return;
        modelListContainer.removeAllViews();

        List<LocalModelManager.ModelInfo> models =
                modelManager == null ? null : modelManager.getAllModels();

        if (models == null || models.size() == 0) {
            TextView empty = new TextView(ctx);
            empty.setText("暂无本地模型，点击下方按钮导入");
            empty.setTextColor(Theme.TEXT_TERTIARY);
            empty.setTextSize(12);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(14), 0, dp(14));
            modelListContainer.addView(empty);
            updateModeStatus();
            return;
        }

        for (LocalModelManager.ModelInfo m : models) {
            modelListContainer.addView(buildModelRow(m));
        }
        updateModeStatus();
    }

    private View buildModelRow(final LocalModelManager.ModelInfo m) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(8), 0, 0);
        card.setLayoutParams(lp);
        card.setBackgroundColor(Theme.BG_CARD);
        card.setPadding(dp(11), dp(10), dp(11), dp(9));

        // 第一行：名称 + 状态
        LinearLayout row1 = new LinearLayout(ctx);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(ctx);
        name.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        name.setText(m.name);
        name.setTextColor(Theme.TEXT_PRIMARY);
        name.setTextSize(14);
        name.setTypeface(null, Typeface.BOLD);
        name.setSingleLine(true);
        row1.addView(name);

        TextView state = new TextView(ctx);
        state.setText(getStateText(m.state));
        state.setTextColor(Color.parseColor(getStateColor(m.state)));
        state.setTextSize(10);
        state.setPadding(dp(6), dp(2), dp(6), dp(2));
        row1.addView(state);
        card.addView(row1);

        // 第二行：大小 | 量化 | 格式
        TextView info = new TextView(ctx);
        info.setText(m.getSizeReadable() + "  |  " + m.quantization
                + "  |  " + m.format);
        info.setTextColor(Theme.TEXT_SECOND);
        info.setTextSize(11);
        info.setPadding(0, dp(3), 0, dp(3));
        card.addView(info);

        // 第三行：路径
        TextView path = new TextView(ctx);
        path.setText(m.filePath);
        path.setTextColor(Theme.TEXT_TERTIARY);
        path.setTextSize(10);
        path.setSingleLine(true);
        card.addView(path);

        // 操作按钮
        LinearLayout ops = new LinearLayout(ctx);
        ops.setOrientation(LinearLayout.HORIZONTAL);
        ops.setPadding(0, dp(8), 0, 0);

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

    /**
     * 操作按钮（加载 / 卸载 / 删除）
     *
     * 两种样式：
     *   - 加载：蓝色实心底 + 白字 + 圆角（主操作）
     *   - 删除：黑色底 + 红色描边 + 红字 + 圆角（危险操作）
     *   - 卸载：沿用主题橙的描边样式
     */
    private void addOpButton(LinearLayout parent, String text,
                             String color, View.OnClickListener l) {
        Button b = new Button(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(OP_BTN_H_DP));
        lp.setMargins(0, 0, dp(8), 0);
        b.setLayoutParams(lp);
        b.setText(text);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setPadding(dp(18), 0, dp(18), 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        // 固定最小高度，保证胶囊形状
        b.setMinHeight(dp(OP_BTN_H_DP));
        // 去掉 Button 默认的点击灰底涟漪外的方形内边距
        b.setBackgroundDrawable(opButtonBg(text));
        b.setTextColor(opButtonText(text, color));
        b.setOnClickListener(l);
        parent.addView(b);
    }

    /** 操作按钮高度（胶囊圆角 = 高度 / 2） */
    private static final int OP_BTN_H_DP = 34;

    /** 导入按钮高度（胶囊圆角 = 高度 / 2） */
    private static final int IMP_BTN_H_DP = 42;

    /** 按按钮语义给出背景：加载为蓝实底，删除为黑底红描边 */
    private android.graphics.drawable.Drawable opButtonBg(String text) {
        GradientDrawable g = new GradientDrawable();
        // 胶囊形：圆角 = 高度的一半，两端呈半圆
        g.setCornerRadius(dpF(OP_BTN_H_DP / 2f));
        if ("加载".equals(text)) {
            // 主操作：蓝色实心
            g.setColor(Theme.LOGO_BLUE);
        } else if ("删除".equals(text)) {
            // 危险操作：黑色底 + 红色描边
            g.setColor(Theme.BG_PRIMARY);
            g.setStroke(Math.max(1, dp(1)), Theme.ACCENT_RED);
        } else {
            // 卸载等其他：深色底 + 主题色描边
            g.setColor(Theme.BG_PRIMARY);
            g.setStroke(Math.max(1, dp(1)), Theme.ACCENT_ORANGE);
        }
        return g;
    }

    /** 按按钮语义给出文字色：加载为白字，删除/卸载为对应色 */
    private int opButtonText(String text, String fallback) {
        if ("加载".equals(text)) {
            return Theme.TEXT_ON_ACCENT;
        }
        return Color.parseColor(fallback);
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

    /**
     * 加载模型（文档 5.3 规则5：耗时操作展示加载进度）
     *
     * 分三种结果处理：
     *   成功      -> 刷新列表 + toast
     *   准入拦截  -> 弹窗说明"为什么不行"+"该怎么做"，这是最关键的分支
     *   其他失败  -> toast 报错
     */
    private void loadModel(final LocalModelManager.ModelInfo m) {
        final AlertDialog progress = new AppleDialog.Builder(ctx)
                .setTitle("加载模型")
                .setMessage("正在加载 " + m.name + "...")
                .setCancelable(false)
                .show();

        modelManager.loadModel(m.id, new LocalModelManager.LoadCallbackEx() {
            @Override
            public void onSuccess() {
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        try { progress.dismiss(); } catch (Throwable ignored) {}
                        refreshModelList();
                        updateModeStatus();
                        String extra = modelManager.lastDowngraded
                                ? "（已自动调整上下文至 " + modelManager.lastEffectiveCtx + "）"
                                : "";
                        toast("模型加载成功: " + m.name + extra);
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

            @Override
            public void onBlocked(final String msg, final String advice,
                                  final ModelAdmission.AdmissionResult admission) {
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        try { progress.dismiss(); } catch (Throwable ignored) {}
                        refreshModelList();
                        showBlockedDialog(m, msg, advice, admission);
                    }
                });
            }
        });
    }

    /**
     * 准入拦截对话框。
     * 目的不是简单报错，而是告诉用户"你的设备能跑什么"——
     * 对一个要发给所有人的工具，这比崩溃友好得多。
     */
    private void showBlockedDialog(LocalModelManager.ModelInfo m, String msg,
                                   String advice,
                                   ModelAdmission.AdmissionResult admission) {
        StringBuilder sb = new StringBuilder();
        sb.append(msg);
        if (advice != null && advice.length() > 0) {
            sb.append("\n\n").append(advice);
        }
        if (admission != null && admission.bill != null) {
            sb.append("\n\n").append(admission.bill);
        }

        new AppleDialog.Builder(ctx)
                .setTitle("无法加载该模型")
                .setMessage(sb.toString())
                .setPositiveButton("知道了", null)
                .show();
    }

    private void confirmDelete(final LocalModelManager.ModelInfo m) {
        new AppleDialog.Builder(ctx)
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

    // ================= 内嵌文件浏览器（选模型文件）=================
    //
    // 不用系统 ACTION_OPEN_DOCUMENT，避免依赖 startActivityForResult
    // （弹窗拿不到结果回调），改为在弹窗内逐级浏览。

    private AlertDialog pickerDialog;

    /**
     * 导入推理引擎：浏览目录，选中含 libllama_jni.so 的文件夹。
     * 导入后会拷贝到 App 内部目录并立即尝试加载，无需重启、无需重新打包 APK。
     */
    private void showEngineImportPicker() {
        // 起点优先级：App 专属外部目录（免权限）> 共享存储
        File[] candidates = new File[]{
                ctx.getExternalFilesDir(null),
                new File("/sdcard/Download/llama-out"),
                Environment.getExternalStorageDirectory(),
                new File("/sdcard"),
                new File("/storage/emulated/0"),
                new File("/storage"),
        };
        File start = null;
        for (File f : candidates) {
            if (f != null && f.exists() && f.isDirectory() && f.canRead()) {
                start = f;
                break;
            }
        }
        if (start == null) {
            toast("无法访问存储目录");
            return;
        }

        final File[] cur = new File[]{start};

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(4), dp(12), dp(2));

        final TextView tvPath = new TextView(ctx);
        tvPath.setTextSize(11);
        tvPath.setTextColor(Theme.TEXT_TERTIARY);
        tvPath.setSingleLine(true);
        tvPath.setPadding(dp(2), dp(2), dp(2), dp(5));
        box.addView(tvPath);

        final View upRow = buildPickerRow("返回上级目录", 0, null);
        box.addView(upRow);
        box.addView(divider());

        final LinearLayout listBox = new LinearLayout(ctx);
        listBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(listBox);

        final TextView tvHint = new TextView(ctx);
        tvHint.setTextSize(11);
        tvHint.setTextColor(Theme.TEXT_TERTIARY);
        tvHint.setPadding(dp(2), dp(6), dp(2), dp(2));
        box.addView(tvHint);

        final Runnable[] rebuild = new Runnable[1];
        rebuild[0] = new Runnable() {
            @Override public void run() {
                tvPath.setText(cur[0].getAbsolutePath());

                boolean canUp = cur[0].getParentFile() != null;
                upRow.setVisibility(canUp ? View.VISIBLE : View.GONE);
                upRow.setEnabled(canUp);

                listBox.removeAllViews();

                File[] all = cur[0].listFiles();
                List<File> dirs = new ArrayList<File>();
                int soCount = 0;
                if (all != null) {
                    for (File f : all) {
                        if (f.isHidden()) continue;
                        if (f.isDirectory()) dirs.add(f);
                        else if (f.getName().endsWith(".so")) soCount++;
                    }
                    Comparator<File> cmp = new Comparator<File>() {
                        @Override public int compare(File a, File b) {
                            return a.getName().compareToIgnoreCase(b.getName());
                        }
                    };
                    Collections.sort(dirs, cmp);
                }

                boolean hasMain = new File(cur[0],
                        NativeLibraryLoader.MAIN_LIB).isFile();
                tvHint.setText(soCount > 0
                        ? ("本目录有 " + soCount + " 个 .so"
                           + (hasMain ? "，含主库 ✓" : "，但缺少 " + NativeLibraryLoader.MAIN_LIB))
                        : "本目录没有 .so 文件");

                if (all == null) {
                    listBox.addView(buildPickerRow("无法读取该目录（权限不足）", 0, null));
                } else if (dirs.isEmpty() && soCount == 0) {
                    listBox.addView(buildPickerRow("（空目录）", 0, null));
                } else {
                    for (final File d : dirs) {
                        listBox.addView(buildPickerRow(d.getName() + "  ›",
                                Theme.ACCENT_ORANGE,
                                new View.OnClickListener() {
                                    @Override public void onClick(View v) {
                                        cur[0] = d;
                                        rebuild[0].run();
                                    }
                                }));
                    }
                }
            }
        };

        upRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                File p = cur[0].getParentFile();
                if (p != null) { cur[0] = p; rebuild[0].run(); }
            }
        });
        rebuild[0].run();

        new AppleDialog.Builder(ctx)
                .setTitle("选择引擎目录")
                .setMessage("进入存放 " + NativeLibraryLoader.MAIN_LIB
                        + " 的目录，然后点「导入并加载」。")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("导入并加载",
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                doImportEngine(cur[0].getAbsolutePath());
                            }
                        })
                .show();
    }

    /** 执行引擎导入：拷贝 + 加载 + 刷新状态 */
    private void doImportEngine(String dir) {
        if (!new File(dir, NativeLibraryLoader.MAIN_LIB).isFile()) {
            toast("该目录缺少 " + NativeLibraryLoader.MAIN_LIB);
            return;
        }
        if (!NativeLibraryLoader.importFrom(ctx, dir)) {
            toast("拷贝失败，请检查存储空间");
            return;
        }
        LlamaCppEngine.init(ctx.getApplicationContext());
        modelManager.refreshEngineState();
        updateModeStatus();
        if (EngineManager.get().available()) {
            toast("引擎已加载");
        } else {
            toast("已导入，但加载失败：" + EngineManager.get().unavailableReason());
        }
    }

    private void showFilePicker() {
        File start = null;
        File[] candidates = new File[]{
                Environment.getExternalStorageDirectory(),
                new File("/sdcard"),
                new File("/storage/emulated/0"),
                new File("/storage"),
                ctx.getExternalFilesDir(null),
        };
        for (File f : candidates) {
            if (f != null && f.exists() && f.isDirectory() && f.canRead()) {
                start = f;
                break;
            }
        }
        if (start == null) {
            showManualImportDialog();
            return;
        }

        final File[] cur = new File[]{start};

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(4), dp(12), dp(2));

        final TextView tvPath = new TextView(ctx);
        tvPath.setTextSize(11);
        tvPath.setTextColor(Theme.TEXT_TERTIARY);
        tvPath.setSingleLine(true);
        tvPath.setPadding(dp(2), dp(2), dp(2), dp(5));
        box.addView(tvPath);

        final View upRow = buildPickerRow("返回上级目录", 0, null);
        box.addView(upRow);
        box.addView(divider());
        // 目录切换逻辑在下方 rebuild[0] 定义后覆写，此处先占位

        final LinearLayout listBox = new LinearLayout(ctx);
        listBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(listBox);

        final Runnable[] rebuild = new Runnable[1];
        rebuild[0] = new Runnable() {
            @Override public void run() {
                tvPath.setText(cur[0].getAbsolutePath());

                boolean canUp = cur[0].getParentFile() != null;
                upRow.setVisibility(canUp ? View.VISIBLE : View.GONE);
                upRow.setEnabled(canUp);

                listBox.removeAllViews();

                File[] all = cur[0].listFiles();
                List<File> dirs = new ArrayList<File>();
                List<File> files = new ArrayList<File>();
                if (all != null) {
                    for (File f : all) {
                        if (f.isHidden()) continue;
                        if (f.isDirectory()) {
                            dirs.add(f);
                        } else if (isModelFile(f.getName())) {
                            files.add(f);
                        }
                    }
                    Comparator<File> cmp = new Comparator<File>() {
                        @Override public int compare(File a, File b) {
                            return a.getName().compareToIgnoreCase(b.getName());
                        }
                    };
                    Collections.sort(dirs, cmp);
                    Collections.sort(files, cmp);
                }

                if (all == null) {
                    listBox.addView(pickerEmpty("无法读取该目录——存储权限未生效。\n"
                            + "返回上一步改选「① 系统文件选择器」即可，无需权限。"));
                    listBox.addView(buildPickerRow("← 改用系统文件选择器", Theme.STATE_LOADED,
                            new View.OnClickListener() {
                                @Override public void onClick(View v) {
                                    dismissPickerQuietly();
                                    pickModelViaSystem();
                                }
                            }));
                    return;
                }
                if (dirs.isEmpty() && files.isEmpty()) {
                    listBox.addView(pickerEmpty("该目录下没有子文件夹，也没有 .gguf / .ggml / .bin 模型文件。\n"
                            + "若文件确实存在却列不出来，是分区存储限制，请改用「① 系统文件选择器」。"));
                    listBox.addView(buildPickerRow("← 改用系统文件选择器", Theme.STATE_LOADED,
                            new View.OnClickListener() {
                                @Override public void onClick(View v) {
                                    dismissPickerQuietly();
                                    pickModelViaSystem();
                                }
                            }));
                    return;
                }

                // 模型文件优先显示（可直接点选导入）
                for (File f : files) {
                    final File modelFile = f;
                    listBox.addView(buildPickerRow(
                            f.getName() + "  (" + sizeText(f.length()) + ")",
                            Theme.STATE_LOADED,
                            new View.OnClickListener() {
                                @Override public void onClick(View v) {
                                    try {
                                        pickerDialog.dismiss();
                                    } catch (Throwable ignored) {}
                                    doImport(modelFile.getAbsolutePath());
                                }
                            }));
                }
                for (File d : dirs) {
                    final File subDir = d;
                    listBox.addView(buildPickerRow(
                            d.getName() + "  ›", Theme.ACCENT_ORANGE,
                            new View.OnClickListener() {
                                @Override public void onClick(View v) {
                                    if (!subDir.canRead()) {
                                        toast("无法读取该目录");
                                        return;
                                    }
                                    cur[0] = subDir;
                                    rebuild[0].run();
                                }
                            }));
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

        pickerDialog = new AppleDialog.Builder(ctx)
                .setTitle("选择模型文件")
                .setView(box)
                .setNegativeButton("手动输入路径",
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                showManualImportDialog();
                            }
                        })
                .setNeutralButton("取消", null)
                .show();
    }

    private boolean isModelFile(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.endsWith(".gguf") || n.endsWith(".ggml") || n.endsWith(".bin");
    }

    private String sizeText(long bytes) {
        if (bytes <= 0) return "0 B";
        String[] u = {"B", "KB", "MB", "GB"};
        int i = 0;
        double v = bytes;
        while (v >= 1024 && i < u.length - 1) {
            v /= 1024;
            i++;
        }
        if (i == 0) return (long) v + " " + u[i];
        return String.format("%.1f %s", v, u[i]);
    }

    private TextView pickerEmpty(String msg) {
        TextView tv = new TextView(ctx);
        tv.setText(msg);
        tv.setTextSize(12);
        tv.setTextColor(Theme.TEXT_TERTIARY);
        tv.setLineSpacing(dp(2), 1.3f);
        tv.setPadding(dp(2), dp(12), dp(2), dp(12));
        return tv;
    }

    private View divider() {
        View line = new View(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        lp.topMargin = dp(5);
        lp.bottomMargin = dp(4);
        line.setLayoutParams(lp);
        line.setBackgroundColor(Theme.STROKE);
        return line;
    }

    /** 文件浏览器中的一行；listener 决定点击行为（进入目录 / 导入文件） */
    private View buildPickerRow(String label, int nameColor,
                                View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(9), dp(4), dp(9));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextSize(13);
        tv.setTextColor(nameColor == 0 ? Theme.ICON_IDLE : nameColor);
        tv.setSingleLine(true);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tv);

        if (listener != null) row.setOnClickListener(listener);
        return row;
    }

    // ================= 导入 =================

    /**
     * 导入方式菜单。
     *
     * 为什么不能只留「内置目录浏览」：
     *   Android 11 起分区存储生效，targetSdk 30 的 App 用 File 直读
     *   /sdcard 下的 .gguf 这类非媒体文件会被拒绝；Android 13（API 33）起
     *   READ_EXTERNAL_STORAGE 甚至不再授予。表现就是「手机里明明有模型文件，
     *   浏览器却一个都列不出来」。
     *
     * 因此把「系统文件选择器（SAF）」放在首位：由用户亲自选文件，
     * 系统直接授予该文件的读权限，不依赖任何存储权限，在所有版本上都成立。
     */
    private void showImportMenu() {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(4), dp(12), dp(2));

        TextView tvHint = new TextView(ctx);
        tvHint.setTextSize(11);
        tvHint.setTextColor(Theme.TEXT_TERTIARY);
        tvHint.setLineSpacing(dp(2), 1.3f);
        tvHint.setPadding(dp(2), dp(2), dp(2), dp(8));
        box.addView(tvHint);
        box.addView(divider());

        // ① 系统文件选择器 —— 免权限，首选
        box.addView(buildPickerRow("① 系统文件选择器（推荐 · 无需权限）",
                Theme.STATE_LOADED, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        dismissPickerQuietly();
                        pickModelViaSystem();
                    }
                }));

        // ② 内置目录浏览 —— 需要存储权限，能直接读就用原文件、不占双倍空间
        box.addView(buildPickerRow("② 浏览手机目录（可直接用原文件）",
                Theme.ACCENT_ORANGE, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        dismissPickerQuietly();
                        browseWithPermissionCheck();
                    }
                }));

        box.addView(divider());

        // ③ 所有文件访问权限 —— Android 11+ 授权后内置浏览器即可直读
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            box.addView(buildPickerRow("③ 授予「所有文件访问权限」"
                            + (StorageAccessActivity.hasAllFilesAccess() ? "（已授予 ✓）" : ""),
                    StorageAccessActivity.hasAllFilesAccess()
                            ? Theme.STATE_LOADED : Theme.ACCENT_RED,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            dismissPickerQuietly();
                            openAllFilesAccessSettings();
                        }
                    }));
        }

        // ④ 手动输入路径
        box.addView(buildPickerRow("④ 手动输入完整路径",
                Theme.TEXT_SECOND, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        dismissPickerQuietly();
                        showManualImportDialog();
                    }
                }));

        TextView tvFoot = new TextView(ctx);
        tvFoot.setTextSize(11);
        tvFoot.setTextColor(Theme.TEXT_TERTIARY);
        tvFoot.setLineSpacing(dp(2), 1.3f);
        tvFoot.setPadding(dp(2), dp(8), dp(2), dp(2));
        box.addView(tvFoot);

        // 顶部提示随权限状态变化
        if (storageReadable()) {
            tvHint.setText("当前可以直读存储，方式 ② 能直接列出并导入原文件（不额外占用空间）。\n"
                    + "若方式 ② 列不出文件，说明权限实际未生效，请改用方式 ①。");
        } else {
            tvHint.setText("检测到尚未获得存储访问权限。\n"
                    + "建议使用方式 ①：由你亲自选文件，无需任何权限，"
                    + "系统会把文件复制到 App 内部目录后再导入（大文件需多等一会儿）。");
        }
        tvFoot.setText("提示：模型文件建议放在「下载」目录，方便查找。");

        pickerDialog = new AppleDialog.Builder(ctx)
                .setTitle("导入模型")
                .setView(box)
                .setNegativeButton("取消", null)
                .show();
    }

    /** 当前能否直接用 File 读取外部存储 */
    private boolean storageReadable() {
        if (StorageAccessActivity.hasAllFilesAccess()) return true;
        if (android.os.Build.VERSION.SDK_INT <= 32) {
            return StorageAccessActivity.hasLegacyReadPermission(ctx);
        }
        return false;
    }

    private void dismissPickerQuietly() {
        try {
            if (pickerDialog != null) pickerDialog.dismiss();
        } catch (Throwable ignored) {
        }
    }

    /** 跳转系统「所有文件访问权限」设置页 */
    private void openAllFilesAccessSettings() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30
                    && !android.os.Environment.isExternalStorageManager()) {
                Intent it = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                it.setData(Uri.parse("package:" + ctx.getPackageName()));
                if (ctx instanceof android.app.Activity) {
                    ((android.app.Activity) ctx).startActivity(it);
                } else {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(it);
                }
                toast("请在系统设置中开启本应用的「所有文件访问权限」，然后返回重试");
                return;
            }
        } catch (Throwable t) {
            // 部分 ROM 没这个设置页，退到应用详情页
        }
        try {
            Intent it = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            it.setData(Uri.parse("package:" + ctx.getPackageName()));
            if (ctx instanceof android.app.Activity) {
                ((android.app.Activity) ctx).startActivity(it);
            } else {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(it);
            }
            toast("请在「权限」中允许存储访问，然后返回重试");
        } catch (Throwable t) {
            toast("无法打开设置页，请手动在系统设置中授权存储权限");
        }
    }

    /** 内置目录浏览：先确认有权限，没有就先申请 */
    private void browseWithPermissionCheck() {
        if (storageReadable()) {
            showFilePicker();
            return;
        }
        if (android.os.Build.VERSION.SDK_INT >= 23 && android.os.Build.VERSION.SDK_INT <= 32) {
            startStorageAccess(StorageAccessActivity.MODE_PERMISSION,
                    new StorageAccessActivity.Callback() {
                        @Override public void onPicked(String path) {
                            if (storageReadable()) showFilePicker();
                            else toast("权限仍未生效，请改用「系统文件选择器」");
                        }
                        @Override public void onDirectoryPicked(Uri treeUri) {}
                        @Override public void onError(String message) { toast(message); }
                        @Override public void onCancelled() {}
                    });
        } else {
            toast("Android 13 起已不再授予该权限，请改用「系统文件选择器」");
        }
    }

    /** 启动透明代理 Activity（弹窗拿不到 onActivityResult，必须借 Activity 中转） */
    private void startStorageAccess(int mode, StorageAccessActivity.Callback cb) {
        android.app.Activity act = (ctx instanceof android.app.Activity)
                ? (android.app.Activity) ctx : null;
        if (act == null) {
            toast("当前界面不支持打开文件选择器，请用「手动输入完整路径」");
            return;
        }
        StorageAccessActivity.setCallback(cb);
        Intent it = new Intent(ctx, StorageAccessActivity.class);
        it.putExtra(StorageAccessActivity.EXTRA_MODE, mode);
        act.startActivity(it);
        act.overridePendingTransition(0, 0);
    }

    /** 方式 ①：系统文件选择器，免权限 */
    private void pickModelViaSystem() {
        startStorageAccess(StorageAccessActivity.MODE_PICK_MODEL,
                new StorageAccessActivity.Callback() {
                    @Override public void onPicked(String path) {
                        if (path == null || path.length() == 0) return;
                        doImport(path);
                    }
                    @Override public void onDirectoryPicked(Uri treeUri) {}
                    @Override public void onError(String message) { toast(message); }
                    @Override public void onCancelled() {}
                });
    }

    private void showManualImportDialog() {
        final android.widget.EditText et = new android.widget.EditText(ctx);
        et.setHint("模型文件完整路径，如 /sdcard/model.gguf");
        et.setTextColor(Theme.TEXT_PRIMARY);
        et.setHintTextColor(Theme.TEXT_TERTIARY);
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        et.setSingleLine(true);
        et.setTextSize(13);
        et.setPadding(dp(12), dp(10), dp(12), dp(10));

        new AppleDialog.Builder(ctx)
                .setTitle("导入模型")
                .setView(et)
                .setPositiveButton("导入", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        doImport(et.getText().toString().trim());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doImport(String path) {
        if (path == null || path.length() == 0) return;
        File f = new File(path);
        if (!f.exists()) {
            toast("文件不存在");
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

    // ================= 状态 =================

    /** 只读展示当前推理引擎，并提示如何切换 */
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
            status = "当前：本地模式（未加载模型，将回退云端）";
            color = Theme.ACCENT_ORANGE;
        } else {
            status = "当前：云端模型模式";
            color = Theme.ACCENT_BLUE;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(status);
        sb.append("\n（切换引擎请在 AI 设置顶部选择）");

        // 附一行设备能力摘要，让用户知道这台机器能跑什么规模
        try {
            DeviceProfile dev = DeviceProfile.refresh(ctx);
            sb.append("\n\n设备：").append(dev.tierName())
              .append(" · 推理预算 ").append(dev.usableMB).append("MB");
            sb.append("\nCPU：").append(dev.cpuCores).append(" 核 · ")
              .append(dev.simdSummary());
        } catch (Throwable t) {
            // 探测失败不影响主流程
        }

        // 引擎状态：没有 .so 时明确告知，而不是让加载静默失败
        try {
            String reason = modelManager.engineUnavailableReason();
            if (reason != null) {
                sb.append("\n\n⚠ 引擎未就绪：").append(reason);
            } else {
                sb.append("\n引擎：").append(modelManager.engineBackendInfo());
            }
        } catch (Throwable t) {
            // ignore
        }

        tvModelModeStatus.setText(sb.toString());
        tvModelModeStatus.setTextColor(color);
    }

    private void toast(String msg) {
        // 统一委托 UiKit
        UiKit.toast(ctx, msg);
    }

    private int dp(int v) {
        // 统一委托 UiKit，实现全项目只保留一份
        return UiKit.dp(ctx, v);
    }

    /** 浮点 dp（胶囊圆角等需要小数的场景） */
    private int dpF(float v) {
        // 统一委托 UiKit
        return (int) (UiKit.dpF(ctx, v) + 0.5f);
    }
}
