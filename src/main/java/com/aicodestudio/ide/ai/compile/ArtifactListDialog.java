package com.aicodestudio.ide.ai.compile;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.aicodestudio.ide.ai.ui.AppleDialog;
import com.aicodestudio.ide.ai.ui.Theme;
import com.aicodestudio.ide.ai.ui.UiKit;

import java.io.File;
import java.util.List;

/**
 * 产物列表框（统一风格 AppleDialog + 自定义视图）
 *
 * 编译页「产物」按钮入口：列出 SD 卡 AI Code Studio/ 目录下所有已导出的 APK，
 * 支持逐条删除（危险操作确认），底部显示导出目录路径。
 */
public class ArtifactListDialog {

    public static void show(Context ctx) {
        List<File> apks = ArtifactManager.listExportedApks();
        if (apks.isEmpty()) {
            AppleDialog.message(ctx, "产物",
                    "暂无导出产物\n\n导出目录：\n" + ArtifactManager.getExportDir().getAbsolutePath());
            return;
        }

        Context c = ctx;
        int dp = UiKit.dp(c, 1);

        // 持有当前列表 dialog：删除后必须关掉旧的再重开，
        // 否则新 dialog 叠在旧的上，看起来像"删了没刷新"（关闭重开才消失）
        final AlertDialog[] holder = new AlertDialog[1];

        // 删除统一入口：确认 → 删除 → 关闭当前列表 → 重开刷新
        final Runnable refresh = new Runnable() {
            @Override public void run() {
                if (holder[0] != null && holder[0].isShowing()) holder[0].dismiss();
                show(c);
            }
        };

        // ===== 列表容器（垂直）=====
        LinearLayout list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp * 8, dp * 4, dp * 8, dp * 4);

        for (final File apk : apks) {
            // 单行：左侧信息 + 右侧删除
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp * 6, 0, dp * 6);
            row.setBackgroundColor(Theme.BG_PRIMARY);

            LinearLayout info = new LinearLayout(c);
            info.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            info.setLayoutParams(infoLp);

            TextView name = new TextView(c);
            name.setText(apk.getName());
            name.setTextColor(Theme.TEXT_PRIMARY);
            name.setTextSize(13);
            name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            name.setSingleLine(true);
            info.addView(name);

            TextView meta = new TextView(c);
            meta.setText(ArtifactManager.formatSize(apk.length()) + " · " + ArtifactManager.formatTime(apk.lastModified()));
            meta.setTextColor(Theme.TEXT_SECOND);
            meta.setTextSize(11);
            info.addView(meta);

            row.addView(info);

            // 长按整行 = 删除产物（与右侧"删除"按钮等效，符合"长按删除"操作习惯）
            row.setOnLongClickListener(new android.view.View.OnLongClickListener() {
                @Override public boolean onLongClick(android.view.View v) {
                    AppleDialog.confirmDanger(c, "删除产物",
                            "确定删除「" + apk.getName() + "」？\n删除后不可恢复。",
                            "删除", new Runnable() {
                                @Override public void run() {
                                    ArtifactManager.deleteArtifact(apk);
                                    refresh.run(); // 关旧重开，立即刷新
                                }
                            });
                    return true;
                }
            });

            Button del = new Button(c);
            int h = 30;
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setColor(Theme.BG_PRIMARY);
            g.setCornerRadius(UiKit.dpF(c, h / 2f));
            g.setStroke(Math.max(1, dp), Color.parseColor("#F85149"));
            del.setBackgroundDrawable(g);
            del.setText("删除");
            del.setTextSize(11);
            del.setTextColor(Color.parseColor("#F85149"));
            del.setAllCaps(false);
            del.setMinWidth(0);
            del.setMinimumWidth(0);
            del.setMinHeight(dp * h);
            del.setPadding(dp * 14, 0, dp * 14, 0);
            LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp * h);
            delLp.leftMargin = dp * 10;
            del.setLayoutParams(delLp);
            del.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    AppleDialog.confirmDanger(c, "删除产物",
                            "确定删除「" + apk.getName() + "」？\n删除后不可恢复。",
                            "删除", new Runnable() {
                                @Override public void run() {
                                    ArtifactManager.deleteArtifact(apk);
                                    refresh.run(); // 关旧重开，立即刷新
                                }
                            });
                }
            });
            row.addView(del);

            list.addView(row);
        }

        // ===== 底部路径说明 =====
        TextView path = new TextView(c);
        path.setText("导出目录：\n" + ArtifactManager.getExportDir().getAbsolutePath());
        path.setTextColor(Theme.TEXT_TERTIARY);
        path.setTextSize(10);
        path.setPadding(dp * 4, dp * 10, dp * 4, dp * 4);
        list.addView(path);

        // ===== 可滚动容器 =====
        ScrollView sv = new ScrollView(c);
        sv.setFillViewport(true);
        sv.addView(list);

        AlertDialog d = new AppleDialog.Builder(c)
                .setTitle("产物（" + apks.size() + "）")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .create();
        holder[0] = d; // 先登记，删除回调里才能关掉它再重开
        d.show();
    }
}
