package com.aicodestudio.ide.ai.ui;

import android.app.Activity;
import android.content.Context;
import android.view.View;

import com.aicodestudio.ide.ai.core.AppConfig;

/**
 * 云端模型配置弹窗
 *
 * 对应文档 9 全局系统配置项中的云端部分：
 *   9.10 API Key / Base URL / 模型选择
 *   （9.9 模型运行模式由 AISettingsDialog 顶部 Tab 统一控制）
 *   生成参数：Temperature / 最大 Token / 上下文长度
 *   9.1  代码出错自动发给 AI
 *   9.4  AI 修复代码自动覆盖
 *
 * 由 AISettingsDialog 的「云端模型管理」入口唤起。
 */
public class CloudModelDialog {

    public interface OnSavedListener {
        void onSaved();
    }

    private static final String[] MODEL_NAMES = new String[]{
            "GPT-4o", "GPT-4o mini", "Claude 3.5 Sonnet",
            "DeepSeek-V3", "通义千问", "自定义"
    };

    private static final String[] MODEL_VALUES = new String[]{
            "gpt-4o", "gpt-4o-mini", "claude-3-5-sonnet",
            "deepseek-v3", "qwen-max", "custom"
    };

    private final Activity act;
    private final AppConfig cfg;
    private final OnSavedListener onSaved;
    private String currentModelValue;  // 当前选择的模型值，用于判断是否为自定义模式

    public CloudModelDialog(Context ctx) {
        this(ctx, null);
    }

    public CloudModelDialog(Context ctx, OnSavedListener onSaved) {
        // 宿主可能传 Application / 非 Activity 的 Context，
        // 这里做安全转换，避免直接强转抛 ClassCastException
        this.act = (ctx instanceof Activity) ? (Activity) ctx : null;
        Context base = (this.act != null) ? this.act : ctx;
        this.cfg = AppConfig.getInstance(base);
        this.onSaved = onSaved;
        this.currentModelValue = normalizeModel(this.cfg.getModelName());
    }

    /** 当前是否可构建视图（需要 Activity 上下文） */
    public boolean isAvailable() {
        return act != null && cfg != null;
    }

    public void show() {
        SettingsDialogBuilder b = new SettingsDialogBuilder(act);
        fill(b);

        b.show("云端模型", new SettingsDialogBuilder.OnConfirmListener() {
            @Override public void onConfirm() {
                if (onSaved != null) onSaved.onSaved();
            }
        });
    }

    /**
     * 只构建内容视图，不弹窗。
     * 供 AISettingsDialog 把云端配置嵌进「云端模型」Tab 里。
     */
    public View buildContent() {
        SettingsDialogBuilder b = new SettingsDialogBuilder(act);
        fill(b);
        return b.build();
    }

    /** 填充所有云端配置项 */
    private void fill(SettingsDialogBuilder b) {
        // 注：推理引擎（云端 / 本地）的选择已上移到 AISettingsDialog 的顶部 Tab，
        //     此处不再重复放「运行模式」选项，避免两处设置互相打架。

        // ===== 云端接口 =====
        b.section("云端接口")
         .input("API Key", "sk-...", cfg.getApiKey(), true,
                 new SettingsDialogBuilder.OnTextChanged() {
                     @Override public void onChanged(String v) { cfg.setApiKey(v); }
                 })
         .input("Base URL", "https://api.openai.com/v1", cfg.getApiBase(), false,
                 new SettingsDialogBuilder.OnTextChanged() {
                     @Override public void onChanged(String v) { cfg.setApiBase(v); }
                 })
         .choose("模型", MODEL_NAMES, MODEL_VALUES,
                 normalizeModel(cfg.getModelName()),
                 new SettingsDialogBuilder.OnTextChanged() {
                     @Override public void onChanged(String v) {
                         currentModelValue = v;
                         // 选择预设模型时直接设置模型名；选择"自定义"时不设置，等待用户输入
                         if (!"custom".equals(v)) {
                             cfg.setModelName(v);
                         }
                     }
                 })
         .input("自定义模型名称", "例如：gpt-4o-mini、deepseek-chat",
                 "custom".equals(currentModelValue) ? cfg.getModelName() : "",
                 false,
                 new SettingsDialogBuilder.OnTextChanged() {
                     @Override public void onChanged(String v) {
                         // 只有选择"自定义"时，输入框的内容才会作为模型名
                         if ("custom".equals(currentModelValue) && v != null && v.length() > 0) {
                             cfg.setModelName(v);
                         }
                     }
                 });

        // ===== 生成参数 =====
        b.section("生成参数")
         .slider("Temperature", 0, 20, (int) (cfg.getTemperature() * 10), "",
                 new SettingsDialogBuilder.OnValueChanged() {
                     @Override public void onChanged(int v) {
                         cfg.setTemperature(v / 10f);
                     }
                 })
         .slider("最大 Token", 256, 16384, cfg.getMaxTokens(), "",
                 new SettingsDialogBuilder.OnValueChanged() {
                     @Override public void onChanged(int v) { cfg.setMaxTokens(v); }
                 })
         .slider("上下文长度", 1024, 32768, cfg.getContextLength(), "",
                 new SettingsDialogBuilder.OnValueChanged() {
                     @Override public void onChanged(int v) {
                         cfg.setContextLength(v);
                     }
                 });

        // ===== AI 联动 =====
        b.section("AI 联动")
         .toggle("代码出错自动发给 AI", "检测到语法错误时自动推送，无需手动复制",
                 cfg.isAutoSendErrorToAI(),
                 new SettingsDialogBuilder.OnToggleChanged() {
                     @Override public void onChanged(boolean v) {
                         cfg.setAutoSendErrorToAI(v);
                     }
                 })
         .toggle("AI 修复自动覆盖源码", "关闭后每次覆盖前会弹窗确认",
                 cfg.isAutoOverwriteFix(),
                 new SettingsDialogBuilder.OnToggleChanged() {
                     @Override public void onChanged(boolean v) {
                         cfg.setAutoOverwriteFix(v);
                     }
                 });

        b.note("任何兼容 OpenAI 格式的接口都可填自定义 Base URL，"
                + "例如 DeepSeek、通义千问、本地 Ollama 等。");
    }

    /** 老版本可能存了不在列表里的模型名，回退到「自定义」 */
    private String normalizeModel(String name) {
        if (name == null || name.length() == 0) return MODEL_VALUES[0];
        for (String v : MODEL_VALUES) {
            if (v.equals(name)) return v;
        }
        return "custom";
    }
}
