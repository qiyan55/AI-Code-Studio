package com.aicodestudio.ide.ai.tools;

import com.aicodestudio.ide.ai.ui.Widgets;
import com.aicodestudio.ide.ai.ui.Theme;
import com.aicodestudio.ide.ai.ui.UiKit;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.aicodestudio.ide.ai.core.AppConfig;

import java.util.Arrays;
import java.util.List;

/**
 * 弹窗2：新增/编辑自定义编译工具对话框（文档 5.2 / 4.3.1）
 *
 * 表单内容：
 * 1. 工具名称（文本输入）
 * 2. 工具下载链接（文本输入）
 * 3. 适用项目类型（单选）：Android / C++ / Python / Java / 自定义
 * 4. 编译命令模板（支持占位符 {file} {outpath} {project}）
 * 5. 运行命令模板
 * 6. 启用/禁用开关
 * 7. CPU 架构选择（文档 10.4 架构兼容）
 * 底部：[确认保存] [取消]
 */
public class EditBuildToolDialog extends Dialog {

    private EditText etName;
    private EditText etUrl;
    private Spinner spType;
    private Spinner spArch;
    private EditText etCompile;
    private EditText etRun;
    private Switch swEnable;

    private BuildToolManager.BuildTool editingTool; // null = 新增
    private BuildToolManager toolManager;
    private OnSaveListener listener;

    private static final String[] PROJECT_TYPES =
            {"Android", "C++", "Python", "Java", "custom"};
    private static final String[] ARCHS =
            {"arm64-v8a", "armeabi-v7a", "x86_64", "x86", "all"};

    public interface OnSaveListener {
        void onSaved(BuildToolManager.BuildTool tool);
    }

    public EditBuildToolDialog(Context ctx, BuildToolManager.BuildTool tool) {
        super(ctx);
        this.editingTool = tool;
        this.toolManager = BuildToolManager.getInstance(ctx);
    }

    public void setListener(OnSaveListener l) { this.listener = l; }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        // 背景色交给 window 的圆角 drawable，root 必须透明，
        // 否则 root 的矩形会把圆角盖掉
        root.setBackgroundColor(Color.TRANSPARENT);
        root.setPadding(dp(16), dp(14), dp(16), dp(12));

        // 标题（居中，与 AppleDialog 一致）
        TextView title = new TextView(getContext());
        title.setText(editingTool == null ? "新增自定义编译工具" : "编辑编译工具");
        title.setTextColor(Theme.TEXT_PRIMARY);
        title.setTextSize(17);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setPadding(0, 0, 0, dp(12));
        root.addView(title);

        // 表单容器（可滚动）
        android.widget.ScrollView scroll = new android.widget.ScrollView(getContext());
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout form = new LinearLayout(getContext());
        form.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(form);
        root.addView(scroll);

        // 1. 工具名称
        etName = addEditField(form, "工具名称", "如：Android Gradle 编译",
                editingTool == null ? "" : editingTool.name);

        // 2. 下载链接
        etUrl = addEditField(form, "工具下载链接",
                "https://... (可选)",
                editingTool == null ? "" : editingTool.downloadUrl);

        // 3. 项目类型（单选）
        addLabel(form, "适用项目类型");
        spType = new Spinner(getContext());
        spType.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));
        ArrayAdapter<String> typeAdapter = new ArrayAdapter<String>(
                getContext(), android.R.layout.simple_spinner_dropdown_item,
                Arrays.asList(PROJECT_TYPES));
        spType.setAdapter(typeAdapter);
        form.addView(spType);

        // 4. 编译命令模板
        etCompile = addEditField(form, "编译命令模板",
                "支持占位符 {file} {outpath} {project}",
                editingTool == null ? "" : editingTool.compileCommand);
        etCompile.setSingleLine(false);
        etCompile.setLines(2);

        // 5. 运行命令模板
        etRun = addEditField(form, "运行命令模板",
                "如：python {file}",
                editingTool == null ? "" : editingTool.runCommand);
        etRun.setSingleLine(false);
        etRun.setLines(2);

        // 占位符提示
        TextView hint = new TextView(getContext());
        hint.setText("占位符说明：{file}=当前文件  {outpath}=输出目录  {project}=项目根目录");
        hint.setTextColor(Theme.TEXT_TERTIARY);
        hint.setTextSize(10);
        hint.setPadding(0, dp(4), 0, dp(10));
        form.addView(hint);

        // 6. CPU 架构（文档 10.4）
        addLabel(form, "CPU 架构");
        spArch = new Spinner(getContext());
        spArch.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));
        ArrayAdapter<String> archAdapter = new ArrayAdapter<String>(
                getContext(), android.R.layout.simple_spinner_dropdown_item,
                Arrays.asList(ARCHS));
        spArch.setAdapter(archAdapter);
        form.addView(spArch);

        // 7. 启用开关
        //
        // 原本这里手写了一份「标签 + Switch」的行结构，
        // 与 SettingsDialogBuilder.toggle() 重复。
        // 现统一走 Widgets.toggleRow()，样式自动与设置弹窗一致。
        LinearLayout switchRow = Widgets.toggleRow(
                getContext(), "启用此工具", null,
                editingTool == null ? true : editingTool.enabled, null);
        // 取回工厂内部创建的 Switch，供后续保存时读取状态
        swEnable = Widgets.getSwitch(switchRow);
        form.addView(switchRow);

        // 回显已有数据
        if (editingTool != null) {
            int ti = indexOf(PROJECT_TYPES, editingTool.projectType);
            if (ti >= 0) spType.setSelection(ti);
            int ai = indexOf(ARCHS, editingTool.archFilter);
            if (ai >= 0) spArch.setSelection(ai);
        } else {
            spArch.setSelection(indexOf(ARCHS, "all"));
        }

        // ===== 底部按钮 =====
        LinearLayout btnRow = new LinearLayout(getContext());
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(12), 0, 0);

        Button btnCancel = makeActionButton("取消", "#FF7B72", false, "ic_circle_close");
        LinearLayout.LayoutParams lpC =
                new LinearLayout.LayoutParams(0, dp(38), 1f);
        lpC.setMargins(0, 0, dp(5), 0);
        btnCancel.setLayoutParams(lpC);
        btnCancel.setMinHeight(dp(38));
        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dismiss(); }
        });
        btnRow.addView(btnCancel);

        Button btnSave = makeActionButton("确认保存", "#26D962", true, "ic_circle_check");
        LinearLayout.LayoutParams lpS =
                new LinearLayout.LayoutParams(0, dp(38), 1f);
        lpS.setMargins(dp(5), 0, 0, 0);
        btnSave.setLayoutParams(lpS);
        btnSave.setMinHeight(dp(38));
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(); }
        });
        btnRow.addView(btnSave);

        root.addView(btnRow);

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
    }

    /**
     * 圆角矩形按钮（圆角 8dp，与 AppleDialog 按钮一致，非胶囊）
     *   filled=true  → 实心底 + 白字 + 白色图标
     *   filled=false → 黑底 + 同色描边 + 同色文字与图标
     *
     * @param iconName 左侧圆形图标：ic_circle_check（勾）/ ic_circle_close（叉）/ null
     */
    private Button makeActionButton(String text, String colorHex,
                                    boolean filled, String iconName) {
        int h = 38;
        int color = Color.parseColor(colorHex);
        int iconColor = filled ? Color.WHITE : color;

        Button b = new Button(getContext());
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
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(dp(h));
        b.setGravity(Gravity.CENTER);

        // 左侧圆形勾 / 叉
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

    private void save() {
        String name = etName.getText().toString().trim();
        if (name.length() == 0) {
            Toast.makeText(getContext(), "请填写工具名称", Toast.LENGTH_SHORT).show();
            return;
        }
        String type = (String) spType.getSelectedItem();
        String compileCmd = etCompile.getText().toString().trim();
        if (compileCmd.length() == 0) {
            Toast.makeText(getContext(), "请填写编译命令模板", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            BuildToolManager.BuildTool tool;
            if (editingTool == null) {
                tool = toolManager.createManualTool(name,
                        etUrl.getText().toString().trim(),
                        type,
                        compileCmd,
                        etRun.getText().toString().trim(),
                        swEnable.isChecked());
            } else {
                tool = editingTool;
                tool.name = name;
                tool.downloadUrl = etUrl.getText().toString().trim();
                tool.projectType = type;
                tool.compileCommand = compileCmd;
                tool.runCommand = etRun.getText().toString().trim();
                toolManager.updateTool(tool);
            }
            if (tool != null) {
                tool.archFilter = (String) spArch.getSelectedItem();
                tool.enabled = swEnable.isChecked();
                toolManager.updateTool(tool);
                if (listener != null) listener.onSaved(tool);
            }
            dismiss();
        } catch (Throwable t) {
            Toast.makeText(getContext(), "保存失败: " + t.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void addLabel(LinearLayout parent, String text) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextColor(Theme.TEXT_SECOND);
        tv.setTextSize(12);
        tv.setPadding(0, dp(10), 0, dp(4));
        parent.addView(tv);
    }

    private EditText addEditField(LinearLayout parent, String label,
                                  String hint, String value) {
        addLabel(parent, label);
        EditText et = new EditText(getContext());
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
        parent.addView(et);
        return et;
    }

    private int indexOf(String[] arr, String v) {
        if (v == null) return -1;
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equalsIgnoreCase(v)) return i;
        }
        return -1;
    }

    private int dp(int v) {
        // 统一委托 UiKit，实现全项目只保留一份
        return UiKit.dp(getContext(), v);
    }
}
