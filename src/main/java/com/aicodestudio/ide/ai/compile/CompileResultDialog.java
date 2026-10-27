package com.aicodestudio.ide.ai.compile;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;

import com.aicodestudio.ide.ai.ui.AppleDialog;

/**
 * 编译成功选择框（与全项目统一风格 AppleDialog）
 *
 * 编译完成后自动弹出，用户选择：
 *  - 「导出并安装」：复制到 SD 卡 AI Code Studio/ 目录后自动安装，装完清理私有临时产物
 *  - 「直接安装」：自动安装，装完清理私有临时产物（默认，无需导出）
 *  - 「取消」：什么都不做（产物保留在私有目录，可后续通过「产物」入口处理）
 */
public class CompileResultDialog {

    public interface OnChoice {
        void onExportAndInstall(); // 导出并安装
        void onInstallOnly();      // 仅安装
        void onCancel();
    }

    public static void show(Context ctx, String apkName, long apkSize, final OnChoice cb) {
        AlertDialog d = new AppleDialog.Builder(ctx)
                .setTitle("编译成功")
                .setMessage("APK 已生成：" + apkName
                        + "（" + ArtifactManager.formatSize(apkSize) + "）\n\n"
                        + "· 导出并安装：复制到 SD 卡「AI Code Studio」目录后自动安装，"
                        + "产物长期保留\n"
                        + "· 直接安装：安装完成后自动清理临时产物，不留文件")
                .setPositiveButton("导出并安装", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (cb != null) cb.onExportAndInstall();
                    }
                })
                .setNegativeButton("直接安装", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (cb != null) cb.onInstallOnly();
                    }
                })
                .setNeutralButton("取消", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (cb != null) cb.onCancel();
                    }
                })
                .create();
        d.show();
    }
}
