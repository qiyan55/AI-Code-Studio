package com.aicodestudio.ide.ai.tools;

import com.aicodestudio.ide.ai.ui.Theme;
import com.aicodestudio.ide.ai.ui.UiKit;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.aicodestudio.ide.ai.core.AppConfig;

import java.io.File;
import java.io.FileWriter;
import java.util.List;
import com.aicodestudio.ide.ai.ui.AppleDialog;

/**
 * 弹窗1：编译工具管理弹窗（文档 5.2）
 * 入口：顶部工具栏 [编译工具]
 * - 列表区：展示所有已创建编译工具，区分 [手动创建] / [AI自动创建] 标签
 * - 功能按钮：新增工具、编辑、删除、导入导出配置
 * - 点击 [新增工具] 弹出 [新增自定义编译工具表单对话框]
 */
public class BuildToolManageDialog extends Dialog {

    private LinearLayout toolListContainer;
    private Button btnExport;
    private Button btnImport;
    private TextView tvCount;

    private BuildToolManager toolManager;
    private ToolDialogListener listener;

    public interface ToolDialogListener {
        void onToolSelected(String toolId);
        void onToolsChanged();
        void onAddTool();
        void onEditTool(BuildToolManager.BuildTool tool);
    }

    public BuildToolManageDialog(Context ctx) {
        super(ctx);
        this.toolManager = BuildToolManager.getInstance(ctx);
    }

    public void setListener(ToolDialogListener l) { this.listener = l; }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        // 背景色交给 window 的圆角 drawable，root 必须透明，
        // 否则 root 的矩形会把圆角盖掉（之前弹窗是方角就是这个原因）
        root.setBackgroundColor(Color.TRANSPARENT);
        root.setPadding(dp(16), dp(14), dp(16), dp(12));

        // ===== 标题（居中，与 AppleDialog 一致）=====
        TextView title = new TextView(getContext());
        title.setText("编译工具管理");
        title.setTextColor(Theme.TEXT_PRIMARY);
        title.setTextSize(17);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(android.view.Gravity.CENTER);
        title.setSingleLine(true);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title);

        tvCount = new TextView(getContext());
        tvCount.setText("共 0 个工具");
        tvCount.setTextColor(Theme.TEXT_SECOND);
        tvCount.setTextSize(12);
        tvCount.setPadding(0, 0, 0, dp(10));
        root.addView(tvCount);

        // ===== 列表区 =====
        ScrollView scroll = new ScrollView(getContext());
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        scroll.setBackgroundColor(Theme.BG_PRIMARY);

        toolListContainer = new LinearLayout(getContext());
        toolListContainer.setOrientation(LinearLayout.VERTICAL);
        toolListContainer.setPadding(dp(8), dp(8), dp(8), dp(8));
        scroll.addView(toolListContainer);
        root.addView(scroll);

        // ===== 操作区（两层：次要操作 → 关闭）=====
        // 新增入口统一交给列表底部的圆形加号卡片（见 makeAddCard），
        // 去掉原来全宽的方形按钮，避免和加号卡片重复。

        // 次要操作：导出 / 导入，并排一行
        LinearLayout btnRow = new LinearLayout(getContext());
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38));
        rlp.setMargins(0, dp(8), 0, 0);
        btnRow.setLayoutParams(rlp);

        btnExport = makeActionButton("导出配置", "#8B949E", false, null);
        btnExport.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { exportConfig(); }
        });
        btnRow.addView(btnExport);

        btnImport = makeActionButton("导入配置", "#8B949E", false, null);
        btnImport.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { importConfig(); }
        });
        btnRow.addView(btnImport);

        root.addView(btnRow);

        // ===== 关闭：纯文字 + 小红叉，无边框无底色 =====
        root.addView(makeCloseText());

        setContentView(root);

        // 弹窗宽度
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
            wlp.dimAmount = 0.55f;          // 与 AppleDialog 同遮罩深度
            getWindow().setAttributes(wlp);
        }

        refreshList();
    }

    /**
     * 关闭：纯文字 + 左侧小红叉，无边框、无底色。
     * 用 TextView 而非 Button，避免任何默认背景；
     * 上下留 12dp padding 保证点击区域够大（约 46dp 高）。
     */
    private TextView makeCloseText() {
        TextView tv = new TextView(getContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(10), 0, 0);
        lp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        tv.setLayoutParams(lp);

        tv.setText("关闭");
        tv.setTextColor(Theme.CLOSE_RED);
        tv.setTextSize(15);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setPadding(dp(20), dp(12), dp(20), dp(12));
        tv.setClickable(true);
        tv.setFocusable(true);

        // 左侧小红叉
        Drawable d = loadIcon("ic_circle_close");
        if (d != null) {
            d = d.mutate();
            d.setBounds(0, 0, dp(18), dp(18));
            d.setTint(Theme.CLOSE_RED);
            tv.setCompoundDrawables(d, null, null, null);
            tv.setCompoundDrawablePadding(dp(6));
        }

        tv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dismiss(); }
        });
        return tv;
    }

    /**
     * 圆角矩形按钮（圆角 8dp，与 AppleDialog 按钮一致，非胶囊）
     *   filled=true  → 实心底 + 白字 + 白色图标（主操作，如新增）
     *   filled=false → 黑底 + 同色描边 + 同色文字与图标（次要 / 危险操作）
     *
     * @param iconName 左侧圆形图标：ic_circle_check（勾）/ ic_circle_close（叉）/ null
     */
    private Button makeActionButton(String text, String colorHex,
                                    boolean filled, String iconName) {
        int h = 38;
        int color = Color.parseColor(colorHex);
        int iconColor = filled ? Color.WHITE : color;

        Button b = new Button(getContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(h), 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        b.setLayoutParams(lp);

        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(dp(8));               // 圆角矩形，非胶囊
        if (filled) {
            g.setColor(color);
            b.setTextColor(Color.WHITE);
        } else {
            g.setColor(Theme.BG_PRIMARY);
            g.setStroke(Math.max(1, dp(1)), color);
            b.setTextColor(color);
        }
        b.setBackgroundDrawable(g);

        b.setText(text);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(dp(h));
        b.setGravity(android.view.Gravity.CENTER);

        // 左侧圆形勾 / 叉（与其他弹窗的完成按钮一致）
        if (iconName != null) {
            Drawable d = loadIcon(iconName);
            if (d != null) {
                d = d.mutate();
                d.setBounds(0, 0, dp(18), dp(18));
                d.setTint(iconColor);
                b.setCompoundDrawables(d, null, null, null);
                b.setCompoundDrawablePadding(dp(6));
            }
        }
        return b;
    }

    /** 按资源名加载矢量图标 */
    private Drawable loadIcon(String name) {
        try {
            int id = getContext().getResources()
                    .getIdentifier(name, "drawable", getContext().getPackageName());
            if (id == 0) return null;
            return getContext().getResources().getDrawable(id);
        } catch (Throwable t) {
            return null;
        }
    }

    /** dp 转像素（浮点） */
    private float dpF(float v) {
        // 统一委托 UiKit
        return UiKit.dpF(getContext(), v);
    }

    /** 圆角卡片背景（与 AppleDialog 的 BG_DIALOG + 14dp 圆角一致） */
    private android.graphics.drawable.GradientDrawable roundedCard(int color, float rDp) {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dpF(rDp));
        return g;
    }

    private void refreshList() {
        if (toolListContainer == null) return;
        toolListContainer.removeAllViews();

        List<BuildToolManager.BuildTool> tools = toolManager.getAllTools();
        tvCount.setText("共 " + tools.size() + " 个工具");

        for (final BuildToolManager.BuildTool tool : tools) {
            toolListContainer.addView(buildToolRow(tool));
        }

        // 列表底部常驻添加卡片：空态用大版，有工具时用紧凑版。
        // 这样去掉底部方形按钮后，任何情况都有新增入口。
        toolListContainer.addView(makeAddCard(tools.size() == 0));
    }

    private View buildToolRow(final BuildToolManager.BuildTool tool) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(8));
        row.setLayoutParams(lp);
        // 圆角卡片背景（8dp，与弹窗按钮圆角一致）
        android.graphics.drawable.GradientDrawable rowBg =
                new android.graphics.drawable.GradientDrawable();
        rowBg.setColor(Theme.BG_PRIMARY);
        rowBg.setCornerRadius(dp(8));
        rowBg.setStroke(Math.max(1, dp(1)), Theme.BG_TERTIARY);
        row.setBackgroundDrawable(rowBg);
        row.setPadding(dp(10), dp(10), dp(10), dp(10));

        // 左侧启用状态色条
        View bar = new View(getContext());
        bar.setLayoutParams(new LinearLayout.LayoutParams(dp(4), dp(38)));
        bar.setBackgroundColor(Color.parseColor(tool.enabled ? "#2196F3" : "#484F58"));
        row.addView(bar);

        // 信息区
        LinearLayout info = new LinearLayout(getContext());
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lpInfo = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lpInfo.setMargins(dp(10), 0, 0, 0);
        info.setLayoutParams(lpInfo);

        // 名称 + 标签
        LinearLayout nameRow = new LinearLayout(getContext());
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(getContext());
        name.setText(tool.name);
        name.setTextColor(Color.parseColor(tool.enabled ? "#E6EDF3" : "#484F58"));
        name.setTextSize(14);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        nameRow.addView(name);

        // 区分 [手动创建] / [AI自动创建] 标签
        TextView tag = new TextView(getContext());
        tag.setText(tool.autoCreated ? "AI自动创建" : "手动创建");
        tag.setTextColor(Color.parseColor(tool.autoCreated ? "#2196F3" : "#98C379"));
        tag.setTextSize(9);
        tag.setPadding(dp(5), dp(1), dp(5), dp(1));
        LinearLayout.LayoutParams lpTag = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lpTag.setMargins(dp(6), 0, 0, 0);
        tag.setLayoutParams(lpTag);
        nameRow.addView(tag);

        info.addView(nameRow);

        TextView type = new TextView(getContext());
        type.setText(tool.projectType + "  |  " + tool.compileCommand);
        type.setTextColor(Theme.TEXT_SECOND);
        type.setTextSize(11);
        type.setSingleLine(true);
        info.addView(type);

        row.addView(info);

        // 操作区：横排四个图标按钮（编辑 / 选用 / 启停 / 删除）
        LinearLayout ops = new LinearLayout(getContext());
        ops.setOrientation(LinearLayout.HORIZONTAL);
        ops.setGravity(Gravity.CENTER_VERTICAL);

        // 编辑（铅笔，蓝）
        ops.addView(makeOpIcon("ic_edit", "#2196F3", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (listener != null) listener.onEditTool(tool);
            }
        }));

        // 选用（勾，绿）
        ops.addView(makeOpIcon("ic_check", "#26D962", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (listener != null) listener.onToolSelected(tool.id);
                Toast.makeText(getContext(), "已选用工具: " + tool.name,
                        Toast.LENGTH_SHORT).show();
                dismiss();
            }
        }));

        // 启用 / 禁用（开关，橙）
        ops.addView(makeOpIcon(tool.enabled ? "ic_toggle_on" : "ic_toggle_off",
                "#F0883E", new View.OnClickListener() {
            @Override public void onClick(View v) {
                toolManager.setEnabled(tool.id, !tool.enabled);
                refreshList();
                if (listener != null) listener.onToolsChanged();
            }
        }));

        // 删除（垃圾桶，红）
        ops.addView(makeOpIcon("ic_delete", "#F85149", new View.OnClickListener() {
            @Override public void onClick(View v) { confirmDelete(tool); }
        }));

        row.addView(ops);
        return row;
    }

    /**
     * 行内圆形图标操作按钮（34dp 圆，图标 18dp）
     * 图标比文字紧凑，四个操作一行放得下，触控热区也够大。
     */
    private ImageView makeOpIcon(String iconName, String colorHex,
                                 View.OnClickListener l) {
        int size = 34;
        ImageView iv = new ImageView(getContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(size), dp(size));
        lp.setMargins(dp(2), 0, dp(2), 0);
        iv.setLayoutParams(lp);
        iv.setScaleType(ImageView.ScaleType.CENTER);
        iv.setPadding(0, 0, 0, 0);
        iv.setClickable(true);
        iv.setFocusable(true);

        Drawable d = loadIcon(iconName);
        if (d != null) {
            d = d.mutate();
            d.setBounds(0, 0, dp(18), dp(18));
            d.setTint(Color.parseColor(colorHex));
            iv.setImageDrawable(d);
        }

        // 按压反馈：半透明圆底
        android.graphics.drawable.StateListDrawable sd =
                new android.graphics.drawable.StateListDrawable();
        android.graphics.drawable.GradientDrawable gp =
                new android.graphics.drawable.GradientDrawable();
        gp.setColor(0x22FFFFFF);
        gp.setCornerRadius(dpF(size / 2f));
        android.graphics.drawable.GradientDrawable gn =
                new android.graphics.drawable.GradientDrawable();
        gn.setColor(Color.TRANSPARENT);
        gn.setCornerRadius(dpF(size / 2f));
        sd.addState(new int[]{android.R.attr.state_pressed}, gp);
        sd.addState(new int[]{}, gn);
        iv.setBackgroundDrawable(sd);

        iv.setOnClickListener(l);
        return iv;
    }

    /**
     * 添加卡片：虚线圆角边框 + 圆形加号 + 说明文字，整张卡片可点。
     *
     * @param empty true=空态大版（56dp 加号 + 两行说明，列表里最显眼）
     *              false=紧凑版（40dp 加号 + 单行文字，常驻列表底部）
     */
    private View makeAddCard(boolean empty) {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        card.setLayoutParams(lp);

        // 虚线边框（dashWidth/dashGap 实现）
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(Theme.BG_PRIMARY);
        g.setCornerRadius(dp(10));
        // setStroke(width:int, color:int, dashWidth:float, dashGap:float)
        g.setStroke(Math.max(1, dp(2)), Theme.TEXT_TERTIARY, dpF(5), dpF(4));
        card.setBackgroundDrawable(g);
        card.setPadding(dp(16), empty ? dp(28) : dp(16), dp(16), empty ? dp(28) : dp(16));
        card.setClickable(true);
        card.setFocusable(true);

        // 圆形加号背景（空态 56dp / 紧凑 40dp，绿底圆形）
        int plusSize = empty ? 56 : 40;
        int plusIcon = empty ? 28 : 20;
        ImageView plusBg = new ImageView(getContext());
        LinearLayout.LayoutParams plp =
                new LinearLayout.LayoutParams(dp(plusSize), dp(plusSize));
        plp.setMargins(0, 0, 0, empty ? dp(12) : dp(8));
        plusBg.setLayoutParams(plp);
        plusBg.setScaleType(ImageView.ScaleType.CENTER);
        plusBg.setPadding(0, 0, 0, 0);
        android.graphics.drawable.GradientDrawable pg =
                new android.graphics.drawable.GradientDrawable();
        pg.setColor(Theme.OK_GREEN);
        pg.setCornerRadius(dpF(plusSize / 2f));
        plusBg.setBackgroundDrawable(pg);

        Drawable d = loadIcon("ic_plus");
        if (d != null) {
            d = d.mutate();
            d.setBounds(0, 0, dp(plusIcon), dp(plusIcon));
            d.setTint(Color.WHITE);
            plusBg.setImageDrawable(d);
        }
        card.addView(plusBg);

        TextView t1 = new TextView(getContext());
        t1.setText(empty ? "暂无编译工具" : "新增编译工具");
        t1.setTextColor(Color.parseColor(empty ? "#8B949E" : "#E6EDF3"));
        t1.setTextSize(empty ? 14 : 13);
        t1.setGravity(Gravity.CENTER);
        card.addView(t1);

        // 副说明只在空态显示，有工具时保持紧凑
        if (empty) {
            TextView t2 = new TextView(getContext());
            t2.setText("点击创建，或让 AI 自动识别项目类型");
            t2.setTextColor(Theme.TEXT_TERTIARY);
            t2.setTextSize(12);
            t2.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams t2lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            t2lp.setMargins(0, dp(6), 0, 0);
            t2.setLayoutParams(t2lp);
            card.addView(t2);
        }

        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (listener != null) listener.onAddTool();
            }
        });
        return card;
    }

    private void confirmDelete(final BuildToolManager.BuildTool tool) {
        new AppleDialog.Builder(getContext())
                .setTitle("确认删除")
                .setMessage("确定删除工具「" + tool.name + "」吗？")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        toolManager.deleteTool(tool.id);
                        refreshList();
                        if (listener != null) listener.onToolsChanged();
                        Toast.makeText(getContext(), "已删除",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 导出配置（文档 11.1 预留扩展） */
    private void exportConfig() {
        try {
            String json = toolManager.exportConfig();
            File dir = getContext().getExternalFilesDir(null);
            if (dir == null) dir = getContext().getFilesDir();
            File f = new File(dir, "build_tools_config.json");
            FileWriter fw = new FileWriter(f);
            fw.write(json);
            fw.close();
            Toast.makeText(getContext(), "已导出到:\n" + f.getAbsolutePath(),
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(getContext(), "导出失败: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** 导入配置 */
    private void importConfig() {
        try {
            File dir = getContext().getExternalFilesDir(null);
            if (dir == null) dir = getContext().getFilesDir();
            File f = new File(dir, "build_tools_config.json");
            if (!f.exists()) {
                Toast.makeText(getContext(), "未找到配置文件:\n" + f.getAbsolutePath(),
                        Toast.LENGTH_LONG).show();
                return;
            }
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
            fis.close();
            int count = toolManager.importConfig(new String(bos.toByteArray(), "UTF-8"));
            refreshList();
            if (listener != null) listener.onToolsChanged();
            Toast.makeText(getContext(), "已导入 " + count + " 个工具配置",
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(getContext(), "导入失败: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(int v) {
        // 统一委托 UiKit，实现全项目只保留一份
        return UiKit.dp(getContext(), v);
    }
}
