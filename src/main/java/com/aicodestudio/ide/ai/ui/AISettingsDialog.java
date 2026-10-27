package com.aicodestudio.ide.ai.ui;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.aicodestudio.ide.ai.core.AppConfig;
import com.aicodestudio.ide.ai.models.LocalModelManager;
import com.aicodestudio.ide.ai.models.ModelManageDialog;
import com.aicodestudio.ide.ai.chat.AIChatManager;

/**
 * AI 设置弹窗 —— 唯一入口
 *
 * 结构：
 *   ┌──────────────────────────┐
 *   │  [ 云端推理 ] [ 本地推理 ] │  ← Tab：选择对话时用哪个引擎
 *   │  当前将使用：xxx          │
 *   │  (☁) 云端模型管理      › │  ← 配置入口：点开各自的设置弹窗
 *   │  (⬢) 本地模型管理      › │
 *   └──────────────────────────┘
 *
 * 职责分离：
 *   · Tab    → 决定「对话时用哪个引擎」，写入 AppConfig.modelMode
 *   · 卡片   → 决定「引擎怎么配」，打开对应配置弹窗
 *
 * 两处的配置弹窗内都不再放运行模式开关，避免与 Tab 打架。
 */
public class AISettingsDialog {

    /** 配置发生变化时的回调 */
    public interface OnChangedListener {
        void onChanged();
    }

    private static final int BG = 0xFF0D1117;
    private static final int CARD = 0xFF1C2333;
    private static final int TEXT_MAIN = Theme.TEXT_PRIMARY;
    private static final int TEXT_SUB = Theme.TEXT_SECOND;
    private static final int BLUE = 0xFF2196F3;
    private static final int GREEN = 0xFF2EA043;

    private final Context ctx;
    private final OnChangedListener onChanged;
    private final AppConfig cfg;

    // Tab
    private TextView tabCloud;
    private TextView tabLocal;
    private TextView tvEngineStatus;

    // 两个配置入口的副标题
    private TextView tvCloudDesc;
    private TextView tvLocalDesc;

    public AISettingsDialog(Context ctx) {
        this(ctx, null);
    }

    public AISettingsDialog(Context ctx, OnChangedListener onChanged) {
        this.ctx = ctx;
        this.onChanged = onChanged;
        this.cfg = AppConfig.getInstance(ctx);
    }

    public void show() {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(8), dp(12), dp(6));

        // ===== 1. 推理引擎 Tab =====
        box.addView(buildTabBar());

        tvEngineStatus = new TextView(ctx);
        tvEngineStatus.setTextSize(11);
        tvEngineStatus.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(6);
        sp.bottomMargin = dp(8);
        tvEngineStatus.setLayoutParams(sp);
        box.addView(tvEngineStatus);

        // ===== 2. 配置入口卡片 =====
        box.addView(entry(
                "ic_cloud", BLUE,
                "云端模型管理",
                "配置 API Key / Base URL / 模型",
                new View.OnClickListener() {
                    @Override public void onClick(View v) { showCloud(); }
                },
                true));

        box.addView(entry(
                "ic_chip", GREEN,
                "本地模型管理",
                "导入 GGUF 模型与调节推理参数",
                new View.OnClickListener() {
                    @Override public void onClick(View v) { showLocal(); }
                },
                false));

        box.addView(buildPromptEntry());

        // v1.152：历史 KV 查看器入口（查看每个会话的 KV 缓存占用/删除）
        box.addView(buildKvEntry());

        // ===== 3. 说明 =====
        TextView tip = new TextView(ctx);
        tip.setText("上方 Tab 决定对话时使用哪个引擎；"
                + "下方两个入口用于配置该引擎。\n"
                + "本地引擎需先导入并加载 GGUF 模型才会生效。");
        tip.setTextSize(11);
        tip.setTextColor(TEXT_SUB);
        tip.setLineSpacing(dp(2), 1.25f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        tip.setLayoutParams(lp);
        box.addView(tip);

        // 初始化 Tab 状态
        refreshEngineState();

        new AppleDialog.Builder(ctx)
                .setTitle("AI 设置")
                .setView(box)
                .setNegativeButton("关闭", null)
                .show();
    }

    // ================= 推理引擎 Tab =================

    private View buildTabBar() {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundDrawable(rounded(BG, 10));
        bar.setPadding(dp(3), dp(3), dp(3), dp(3));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38));
        bar.setLayoutParams(lp);

        tabCloud = makeTab("云端推理", 0);
        tabLocal = makeTab("本地推理", 1);

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        tabCloud.setLayoutParams(p);
        tabLocal.setLayoutParams(p);

        bar.addView(tabCloud);
        bar.addView(tabLocal);
        return bar;
    }

    private TextView makeTab(final String text, final int index) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { selectEngine(index); }
        });
        return tv;
    }

    /** 选择推理引擎：0=云端 1=本地 */
    private void selectEngine(int index) {
        if (cfg == null) return;

        if (index == 1) {
            // 切到本地前必须先有已加载的模型，否则回退
            LocalModelManager mgr = null;
            try {
                mgr = LocalModelManager.getInstance(ctx);
            } catch (Throwable ignored) {
            }
            boolean loaded = mgr != null && mgr.hasLoadedModel();
            if (!loaded) {
                Toast.makeText(ctx,
                        "请先在「本地模型管理」中导入并加载 GGUF 模型",
                        Toast.LENGTH_LONG).show();
                refreshEngineState();
                return;
            }
            cfg.setModelMode(AppConfig.MODE_LOCAL);
        } else {
            cfg.setModelMode(AppConfig.MODE_CLOUD);
        }

        refreshEngineState();
        if (onChanged != null) onChanged.onChanged();
    }

    /** 刷新 Tab 高亮与状态文案 */
    private void refreshEngineState() {
        boolean local = cfg != null && cfg.getModelMode() == AppConfig.MODE_LOCAL;

        applyTabStyle(tabCloud, !local);
        applyTabStyle(tabLocal, local);

        if (tvEngineStatus != null) {
            if (local) {
                String name = "";
                try {
                    LocalModelManager mgr = LocalModelManager.getInstance(ctx);
                    LocalModelManager.ModelInfo m = mgr == null ? null : mgr.getActiveModel();
                    if (m != null) name = "（" + m.name + "）";
                } catch (Throwable ignored) {
                }
                tvEngineStatus.setText("当前将使用：本地离线引擎 " + name);
                tvEngineStatus.setTextColor(GREEN);
            } else {
                String model = cfg == null ? "" : cfg.getModelName();
                tvEngineStatus.setText("当前将使用：云端引擎 "
                        + (model == null || model.length() == 0 ? "" : "（" + model + "）"));
                tvEngineStatus.setTextColor(BLUE);
            }
        }

        // 入口卡片副标题跟着变，提示哪个正在生效
        if (tvCloudDesc != null) {
            tvCloudDesc.setText(local ? "未生效 · 配置 API Key / Base URL / 模型"
                                      : "生效中 · 配置 API Key / Base URL / 模型");
        }
        if (tvLocalDesc != null) {
            tvLocalDesc.setText(local ? "生效中 · 导入 GGUF 模型与调节推理参数"
                                      : "未生效 · 导入 GGUF 模型与调节推理参数");
        }
    }

    private void applyTabStyle(TextView tv, boolean selected) {
        if (tv == null) return;
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dpf(8));
        if (selected) {
            g.setColor(BLUE);
            tv.setTextColor(Color.WHITE);
        } else {
            g.setColor(Color.TRANSPARENT);
            tv.setTextColor(TEXT_SUB);
        }
        tv.setBackgroundDrawable(g);
    }

    // ================= 配置入口 =================

    /** 云端模型配置 */
    private void showCloud() {
        new CloudModelDialog(ctx, new CloudModelDialog.OnSavedListener() {
            @Override public void onSaved() {
                refreshEngineState();
                if (onChanged != null) onChanged.onChanged();
            }
        }).show();
    }

    /** 本地模型配置 */
    private void showLocal() {
        new ModelManageDialog(ctx).show();
        refreshEngineState();
        if (onChanged != null) onChanged.onChanged();
    }

    // ================= v1.134 系统提示词编辑（L1常驻 / 写文档规范） =================
    private View buildPromptEntry() {
        final int PURPLE = 0xFF8E44AD;
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundDrawable(rounded(CARD, dp(8)));
        row.setPadding(dp(11), dp(11), dp(11), dp(11));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        row.setLayoutParams(lp);

        LinearLayout iconBox = new LinearLayout(ctx);
        iconBox.setGravity(Gravity.CENTER);
        iconBox.setLayoutParams(new LinearLayout.LayoutParams(dp(38), dp(38)));
        iconBox.setBackgroundDrawable(rounded(PURPLE, dp(19)));
        ImageView iv = new ImageView(ctx);
        Drawable d = icon("ic_prompt", 0xFFFFFFFF); // v1.136：系统提示词专属图标（文本文件+内容行）
        if (d != null) { iv.setImageDrawable(d); iv.setScaleType(ImageView.ScaleType.CENTER); }
        iconBox.addView(iv);
        row.addView(iconBox);

        LinearLayout textBox = new LinearLayout(ctx);
        textBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(10);
        textBox.setLayoutParams(tlp);
        TextView tvTitle = new TextView(ctx);
        tvTitle.setText("系统提示词");
        tvTitle.setTextSize(14); tvTitle.setTextColor(TEXT_MAIN);
        tvTitle.setTypeface(null, Typeface.BOLD); tvTitle.setSingleLine(true);
        textBox.addView(tvTitle);
        TextView tvDesc = new TextView(ctx);
        tvDesc.setText("编辑常驻 L1 与写文档规范（分阶段注入）");
        tvDesc.setTextSize(11); tvDesc.setTextColor(TEXT_SUB); tvDesc.setSingleLine(true);
        textBox.addView(tvDesc);
        row.addView(textBox);

        ImageView arrow = new ImageView(ctx);
        Drawable ad = icon("ic_chevron", TEXT_SUB);
        if (ad != null) arrow.setImageDrawable(ad);
        row.addView(arrow);

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showPrompts(); }
        });
        return row;
    }

    /** v1.152：历史 KV 查看器入口卡片（查看每个会话 KV 缓存占用 / 删除） */
    private View buildKvEntry() {
        final int ORANGE = 0xFFD97706;
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundDrawable(rounded(CARD, dp(8)));
        row.setPadding(dp(11), dp(11), dp(11), dp(11));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        row.setLayoutParams(lp);

        LinearLayout iconBox = new LinearLayout(ctx);
        iconBox.setGravity(Gravity.CENTER);
        iconBox.setLayoutParams(new LinearLayout.LayoutParams(dp(38), dp(38)));
        iconBox.setBackgroundDrawable(rounded(ORANGE, dp(19)));
        ImageView iv = new ImageView(ctx);
        Drawable d = icon("ic_file", 0xFFFFFFFF); // 数据文件图标（KV 缓存列表）
        if (d != null) { iv.setImageDrawable(d); iv.setScaleType(ImageView.ScaleType.CENTER); }
        iconBox.addView(iv);
        row.addView(iconBox);

        LinearLayout textBox = new LinearLayout(ctx);
        textBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(10);
        textBox.setLayoutParams(tlp);
        TextView tvTitle = new TextView(ctx);
        tvTitle.setText("历史 KV 查看器");
        tvTitle.setTextSize(14); tvTitle.setTextColor(TEXT_MAIN);
        tvTitle.setTypeface(null, Typeface.BOLD); tvTitle.setSingleLine(true);
        textBox.addView(tvTitle);
        TextView tvDesc = new TextView(ctx);
        tvDesc.setText("查看每个会话的 KV 缓存大小 / 删除");
        tvDesc.setTextSize(11); tvDesc.setTextColor(TEXT_SUB); tvDesc.setSingleLine(true);
        textBox.addView(tvDesc);
        row.addView(textBox);

        ImageView arrow = new ImageView(ctx);
        Drawable ad = icon("ic_chevron", TEXT_SUB);
        if (ad != null) arrow.setImageDrawable(ad);
        row.addView(arrow);

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showKvViewer(); }
        });
        return row;
    }

    /** v1.152：历史 KV 查看器——列出各会话 KV 文件（大小/时间），可单个删除或清空全部 */
    private void showKvViewer() {
        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(6), dp(12), dp(6));

        TextView head = new TextView(ctx);
        head.setText("每个会话一份 KV 缓存（系统前缀+最近窗口），切换会话时保存/加载。\n删除后下次打开该会话会自动全量重建，不影响历史消息。");
        head.setTextSize(11); head.setTextColor(TEXT_SUB);
        head.setLineSpacing(dp(2), 1.25f);
        content.addView(head);

        final AIChatManager mgr = AIChatManager.getInstance();
        final java.util.List<String[]> kvs = mgr.listSessionKvFiles();

        TextView total = new TextView(ctx);
        total.setText("共 " + kvs.size() + " 个会话 KV 文件");
        total.setTextSize(12); total.setTextColor(TEXT_MAIN);
        total.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams tlp0 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp0.topMargin = dp(8); tlp0.bottomMargin = dp(4);
        total.setLayoutParams(tlp0);
        content.addView(total);

        ScrollView sv = new ScrollView(ctx);
        LinearLayout listBox = new LinearLayout(ctx);
        listBox.setOrientation(LinearLayout.VERTICAL);
        if (kvs.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("暂无 KV 缓存（本地模型生成过内容后自动产生）");
            empty.setTextSize(12); empty.setTextColor(TEXT_SUB);
            empty.setPadding(dp(4), dp(10), dp(4), dp(10));
            listBox.addView(empty);
        } else {
            for (String[] kv : kvs) {
                listBox.addView(buildKvRow(mgr, kv, listBox, total, sv));
            }
        }
        sv.addView(listBox);
        LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(240));
        svlp.topMargin = dp(4);
        sv.setLayoutParams(svlp);
        content.addView(sv);

        new AppleDialog.Builder(ctx)
                .setTitle("历史 KV 查看器")
                .setView(content)
                .setPositiveButton(kvs.isEmpty() ? "关闭" : "清空全部",
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dlg, int which) {
                                if (kvs.isEmpty()) return;
                                int n = mgr.deleteAllSessionKvFiles();
                                Toast.makeText(ctx, "已清空 " + n + " 个 KV 文件", Toast.LENGTH_SHORT).show();
                                showKvViewer(); // 刷新列表
                            }
                        })
                .setNegativeButton("关闭", null)
                .show();
    }

    /** KV 列表单行：文件名（截断）+ 大小 + 时间 + 删除按钮 */
    private View buildKvRow(final AIChatManager mgr, final String[] kv,
                            final LinearLayout listBox, final TextView total, final ScrollView sv) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundDrawable(rounded(CARD, dp(8)));
        row.setPadding(dp(8), dp(7), dp(8), dp(7));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        row.setLayoutParams(lp);

        LinearLayout textBox = new LinearLayout(ctx);
        textBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textBox.setLayoutParams(tlp);
        TextView tvName = new TextView(ctx);
        // v1.153bb：主显示对话标题（kv[3]），找不到标题时回退文件名
        String name = kv.length >= 4 && kv[3] != null && !kv[3].isEmpty() ? kv[3] : kv[0];
        if (name.length() > 42) name = name.substring(0, 40) + "…";
        tvName.setText(name);
        tvName.setTextSize(12); tvName.setTextColor(TEXT_MAIN);
        tvName.setSingleLine(true);
        textBox.addView(tvName);
        TextView tvMeta = new TextView(ctx);
        tvMeta.setText(kv[1] + " · " + kv[2]);
        tvMeta.setTextSize(10); tvMeta.setTextColor(TEXT_SUB);
        textBox.addView(tvMeta);
        row.addView(textBox);

        Button btnDel = new Button(ctx);
        btnDel.setText("删除");
        btnDel.setTextSize(11);
        btnDel.setTextColor(0xFFF85149);
        btnDel.setBackgroundDrawable(rounded(0x22F85149, dp(12)));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                dp(52), dp(30));
        btnDel.setLayoutParams(blp);
        btnDel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                mgr.deleteSessionKvFile(kv[0]);
                Toast.makeText(ctx, "已删除 " + kv[0], Toast.LENGTH_SHORT).show();
                showKvViewer(); // 整体刷新列表
            }
        });
        row.addView(btnDel);
        return row;
    }

    /** 主入口：两个卡片，点卡片进单独编辑器 */
    private void showPrompts() {
        final com.aicodestudio.ide.ai.core.AppConfig cfg =
                com.aicodestudio.ide.ai.core.AppConfig.getInstance(ctx);

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        box.setPadding(pad, pad, pad, pad);

        // 卡片1：常驻 L1
        box.addView(buildPromptCard("常驻 L1 系统提示词",
                "全程常驻：角色 / Linux环境 / 工具命令 / 规则",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        String cur = cfg.getL1PromptOverride();
                        if (cur == null) cur = com.aicodestudio.ide.ai.agent.AgentSystemPrompt
                                .build(ctx, null, false);
                        showEditor("常驻 L1 系统提示词", cur,
                                com.aicodestudio.ide.ai.agent.AgentSystemPrompt
                                        .build(ctx, null, false),
                                new java.util.function.Consumer<String>() {
                                    @Override public void accept(String s) {
                                        cfg.setL1PromptOverride(s);
                                        notifyPromptSaved();
                                    }
                                },
                                new Runnable() {
                                    @Override public void run() {
                                        cfg.setL1PromptOverride("");
                                    }
                                });
                    }
                }));

        // 卡片2：写文档规范
        box.addView(buildPromptCard("写文档规范",
                "仅第一轮临时注入，文档写完即丢弃",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        String cur = cfg.getDocGuideOverride();
                        if (cur == null) cur = com.aicodestudio.ide.ai.agent.AgentSystemPrompt.STAGE1_DOC_GUIDE;
                        showEditor("写文档规范", cur,
                                com.aicodestudio.ide.ai.agent.AgentSystemPrompt.STAGE1_DOC_GUIDE,
                                new java.util.function.Consumer<String>() {
                                    @Override public void accept(String s) {
                                        cfg.setDocGuideOverride(s);
                                        notifyPromptSaved();
                                    }
                                },
                                new Runnable() {
                                    @Override public void run() {
                                        cfg.setDocGuideOverride("");
                                    }
                                });
                    }
                }));

        new AppleDialog.Builder(ctx)
                .setTitle("系统提示词")
                .setView(box)
                .setNegativeButton("关闭", null)
                .setNeutralButton("恢复默认", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) {
                        cfg.setL1PromptOverride("");
                        cfg.setDocGuideOverride("");
                        notifyPromptSaved();
                    }
                })
                .show();
    }

    private void notifyPromptSaved() {
        if (onChanged != null) onChanged.onChanged();
        // 让 AIChatManager 旧的系统前缀 KV 缓存失效，下一轮按新提示词重新 prefill，保证立即生效
        com.aicodestudio.ide.ai.chat.AIChatManager.invalidatePrefixCache();
        android.widget.Toast.makeText(ctx, "已保存，下一轮对话生效", android.widget.Toast.LENGTH_SHORT).show();
    }

    /** 单个卡片行 */
    private View buildPromptCard(String title, String desc, View.OnClickListener l) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundDrawable(rounded(CARD, dp(8)));
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        row.setLayoutParams(lp);

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(title);
        tvTitle.setTextSize(13);
        tvTitle.setTextColor(TEXT_MAIN);
        tvTitle.setTypeface(null, Typeface.BOLD);
        row.addView(tvTitle);

        TextView tvDesc = new TextView(ctx);
        tvDesc.setText(desc);
        tvDesc.setTextSize(11);
        tvDesc.setTextColor(TEXT_SUB);
        tvDesc.setLineSpacing(dp(2), 1.2f);
        row.addView(tvDesc);

        row.setOnClickListener(l);
        return row;
    }

    /** 通用编辑器：小字体、可滚动；onReset 非空时左下角显示"恢复默认" */
    private void showEditor(String title, String current, String defaultValue,
                            final java.util.function.Consumer<String> onSave,
                            final Runnable onReset) {
        ScrollView sv = new ScrollView(ctx);
        LinearLayout v = new LinearLayout(ctx);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(10), dp(10), dp(10), dp(10));

        final EditText ed = new EditText(ctx);
        ed.setText(current);
        ed.setGravity(Gravity.TOP);
        ed.setTextSize(11);                 // 字体调小
        ed.setTypeface(android.graphics.Typeface.MONOSPACE);
        ed.setBackgroundDrawable(rounded(0xFF2A2A2A, dp(6)));
        ed.setPadding(dp(8), dp(8), dp(8), dp(8));
        ed.setHorizontallyScrolling(false); // 自动换行
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(320));
        ed.setLayoutParams(elp);
        v.addView(ed);
        sv.addView(v);

        android.app.AlertDialog.Builder b = new AppleDialog.Builder(ctx)
                .setTitle(title)
                .setView(sv)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        onSave.accept(ed.getText().toString());
                    }
                });
        // 左下角：恢复系统默认
        if (onReset != null) {
            b.setNegativeButton("恢复默认", new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface d, int w) {
                    ed.setText(defaultValue);      // 填回内置默认，再点保存生效
                    onReset.run();
                }
            });
        }
        b.show();
    }

    private View entry(String iconName, int accent, String title, String desc,
                       View.OnClickListener l, final boolean isCloud) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundDrawable(rounded(CARD, dp(8)));
        row.setPadding(dp(11), dp(11), dp(11), dp(11));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        row.setLayoutParams(lp);

        // 图标（圆形底 + 矢量图标）
        LinearLayout iconBox = new LinearLayout(ctx);
        iconBox.setGravity(Gravity.CENTER);
        iconBox.setLayoutParams(new LinearLayout.LayoutParams(dp(38), dp(38)));
        iconBox.setBackgroundDrawable(rounded(accent, dp(19)));
        ImageView iv = new ImageView(ctx);
        Drawable d = icon(iconName, 0xFFFFFFFF);
        if (d != null) {
            iv.setImageDrawable(d);
            iv.setScaleType(ImageView.ScaleType.CENTER);
        }
        iconBox.addView(iv);
        row.addView(iconBox);

        // 文字
        LinearLayout textBox = new LinearLayout(ctx);
        textBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(10);
        textBox.setLayoutParams(tlp);

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(title);
        tvTitle.setTextSize(14);
        tvTitle.setTextColor(TEXT_MAIN);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setSingleLine(true);
        textBox.addView(tvTitle);

        TextView tvDesc = new TextView(ctx);
        tvDesc.setText(desc);
        tvDesc.setTextSize(11);
        tvDesc.setTextColor(TEXT_SUB);
        tvDesc.setSingleLine(true);
        textBox.addView(tvDesc);

        // 保存引用，供 refreshEngineState 更新「生效中 / 未生效」
        if (isCloud) tvCloudDesc = tvDesc; else tvLocalDesc = tvDesc;

        row.addView(textBox);

        // 箭头
        ImageView arrow = new ImageView(ctx);
        Drawable ad = icon("ic_chevron", TEXT_SUB);
        if (ad != null) {
            arrow.setImageDrawable(ad);
            arrow.setScaleType(ImageView.ScaleType.CENTER);
        }
        arrow.setLayoutParams(new LinearLayout.LayoutParams(dp(20), dp(20)));
        row.addView(arrow);

        row.setOnClickListener(l);
        return row;
    }

    // ================= 工具 =================

    private android.graphics.drawable.Drawable icon(String name, int color) {
        // 统一委托 UiKit（资源查找 / 判空 / 染色 / 异常兜底都在里面）
        return UiKit.icon(ctx, name, color);
    }

    private Drawable rounded(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dpf(r));
        return g;
    }

    private int dp(int v) {
        // 统一委托 UiKit，实现全项目只保留一份
        return UiKit.dp(ctx, v);
    }

    /** dp 转像素（浮点，用于圆角等需要小数的场景） */
    private float dpf(float v) {
        // 统一委托 UiKit
        return UiKit.dpF(ctx, v);
    }
}
