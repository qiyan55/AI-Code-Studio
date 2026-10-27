package com.aicodestudio.ide.ai.core;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.ClipDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;

/**
 * 创建项目 Dialog 公共类
 * 欢迎页和对话页都调用这个，统一界面和逻辑
 */
public class ProjectDialogHelper {

    // 颜色常量（和 WelcomeActivity 保持一致）
    private static final int BG_PRIMARY = 0xFF0D1117;
    private static final int BORDER = 0xFF30363D;
    private static final int TEXT_PRIMARY = 0xFFE6EDF3;
    private static final int TEXT_TERTIARY = 0xFF8B949E;

    public static final String[] LANGUAGES = {
            "Android (Java)", "Kotlin", "C / C++", "Python", "其他"
    };

    public interface OnProjectCreatedListener {
        void onProjectCreated(String projectPath, String projectName);
    }

    /**
     * 弹出创建项目 Dialog
     * @param ctx 上下文
     * @param dp 密度转换函数（传 Activity 的 dp 方法）
     * @param listener 创建完成回调
     */
    public static void show(Context ctx, DpConverter dp, OnProjectCreatedListener listener) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp.convert(16), dp.convert(8), dp.convert(16), dp.convert(2));

        final EditText etName = new EditText(ctx);
        etName.setText("MyProject");
        etName.setHint("项目名称");
        etName.setTextSize(14);
        etName.setTextColor(TEXT_PRIMARY);
        etName.setHintTextColor(TEXT_TERTIARY);
        etName.setSingleLine(true);
        etName.setPadding(dp.convert(11), dp.convert(8), dp.convert(11), dp.convert(8));
        etName.setBackgroundDrawable(bordered(dp, BG_PRIMARY, BORDER, dp.convert(6)));
        box.addView(etName);

        box.addView(gap(dp, 8));

        final EditText etDesc = new EditText(ctx);
        etDesc.setHint("一个待办事项应用...");
        etDesc.setTextSize(14);
        etDesc.setTextColor(TEXT_PRIMARY);
        etDesc.setHintTextColor(TEXT_TERTIARY);
        etDesc.setSingleLine(true);
        etDesc.setPadding(dp.convert(11), dp.convert(8), dp.convert(11), dp.convert(8));
        etDesc.setBackgroundDrawable(bordered(dp, BG_PRIMARY, BORDER, dp.convert(6)));
        box.addView(etDesc);

        box.addView(gap(dp, 8));

        TextView lbLang = new TextView(ctx);
        lbLang.setText("主要语言");
        lbLang.setTextSize(12);
        lbLang.setTextColor(TEXT_TERTIARY);
        box.addView(lbLang);

        box.addView(gap(dp, 4));

        final int[] langIdx = new int[]{0};
        final Button btnLang = new Button(ctx);
        btnLang.setText(LANGUAGES[0]);
        applyDropArrow(dp, btnLang);
        btnLang.setTextSize(14);
        btnLang.setAllCaps(false);
        btnLang.setTextColor(TEXT_PRIMARY);
        btnLang.setPadding(dp.convert(11), dp.convert(8), dp.convert(11), dp.convert(8));
        btnLang.setBackgroundDrawable(bordered(dp, BG_PRIMARY, BORDER, dp.convert(6)));
        btnLang.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                new com.aicodestudio.ide.ai.ui.AppleDialog.Builder(ctx)
                        .setTitle("选择主要语言")
                        .setSingleChoiceItems(LANGUAGES, langIdx[0],
                                new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d, int which) {
                                        langIdx[0] = which;
                                        btnLang.setText(LANGUAGES[which]);
                                        applyDropArrow(dp, btnLang);
                                        d.dismiss();
                                    }
                                })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
        box.addView(btnLang);

        new com.aicodestudio.ide.ai.ui.AppleDialog.Builder(ctx)
                .setTitle("创建新项目")
                .setView(box)
                .setPositiveButton("创建", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String name = etName.getText().toString().trim();
                        if (name.isEmpty()) {
                            android.widget.Toast.makeText(ctx, "请输入项目名称", android.widget.Toast.LENGTH_SHORT).show();
                            return;
                        }
                        String desc = etDesc.getText().toString().trim();
                        String lang = LANGUAGES[langIdx[0]];

                        // 创建项目目录
                        File projDir = new File(ctx.getFilesDir(), "projects/" + name);
                        projDir.mkdirs();
                        String path = projDir.getAbsolutePath();

                        // 创建项目会话
                        SessionManager sm = SessionManager.get(ctx);
                        sm.createProjectSession(path, name);

                        // 设置到 WorkspaceState
                        WorkspaceState.get().setProjectPath(path);
                        WorkspaceState.get().setProjectType("android");

                        // 同步到 AppConfig
                        AppConfig.getInstance(ctx).addRecentProject(path);

                        // 回调
                        if (listener != null) {
                            listener.onProjectCreated(path, name);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ===== 样式工具方法（和 WelcomeActivity 保持一致）=====

    /** 画圆角边框背景 */
    private static android.graphics.drawable.Drawable bordered(DpConverter dp, int bg, int border, int radius) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bg);
        gd.setStroke(dp.convert(1), border);
        gd.setCornerRadius(radius);
        return gd;
    }

    /** 给按钮右边加下拉箭头 */
    private static void applyDropArrow(DpConverter dp, Button btn) {
        // 简单实现：右边加 padding，用文字表示
        btn.setPadding(dp.convert(11), dp.convert(8), dp.convert(28), dp.convert(8));
        // 右边加个向下的箭头符号
        CharSequence text = btn.getText();
        if (text != null && !text.toString().endsWith(" ▼")) {
            btn.setText(text + " ▼");
        }
    }

    private static View gap(DpConverter dp, int dpSize) {
        View v = new View(dp.getContext());
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp.convert(dpSize)));
        return v;
    }

    /** dp 转换接口，Activity 实现它把 dp(xx) 方法传进来 */
    public interface DpConverter {
        int convert(int dp);
        Context getContext();
    }
}
