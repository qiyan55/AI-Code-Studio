package com.aicodestudio.ide.ai.compile;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * APK 安装器 —— 基于 PackageInstaller Session（Android 8.0+）
 *
 * 特点：
 * - APK 内容流式写入安装会话，无需落到外部存储（不依赖存储权限）
 * - 安装结果通过回调返回（本 App 内动态广播接收）
 * - 安装前检查「安装未知应用」授权，未授权则引导用户去系统设置开启
 * - 安装超时兜底：部分国产 ROM（vivo/OPPO 等安全守护）会静默拦截 PackageInstaller
 *   的确认流程导致长时间无结果，超过超时时间（默认 90 秒）主动回调失败并给出
 *   「导出后手动安装」引导，避免用户无反馈干等。
 */
public class ApkInstaller {

    private static final String TAG = "ApkInstaller";
    private static final String ACTION_INSTALL_RESULT = "com.aicodestudio.ide.INSTALL_RESULT";
    /** 安装超时（毫秒）：超过仍未收到最终结果，判定为被系统拦截 */
    private static final long INSTALL_TIMEOUT_MS = 90_000L;

    public interface InstallCallback {
        void onInstallSuccess(String packageName);
        void onInstallFailed(String message);
        void onNeedPermission(); // 需要先去系统设置开启「安装未知应用」
    }

    /** 检查是否已授权安装未知应用（Android 8.0+） */
    public static boolean canRequestInstalls(Context ctx) {
        if (Build.VERSION.SDK_INT >= 26) {
            return ctx.getPackageManager().canRequestPackageInstalls();
        }
        return true;
    }

    /** 跳转系统「安装未知应用」设置页 */
    public static void openInstallSettings(Context ctx) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "open install settings failed", e);
        }
    }

    /**
     * 安装 APK。
     *
     * @param apk       签名 APK 文件
     * @param pkg       目标包名（可为 null，仅用于日志/回调）
     * @param callback  安装结果回调（主线程）
     */
    public static void install(final Context ctx, final File apk, final String pkg,
                               final InstallCallback callback) {
        if (apk == null || !apk.exists()) {
            if (callback != null) callback.onInstallFailed("APK 文件不存在");
            return;
        }
        if (!canRequestInstalls(ctx)) {
            if (callback != null) callback.onNeedPermission();
            return;
        }

        final Handler timeoutHandler = new Handler(Looper.getMainLooper());
        final BroadcastReceiver[] receiverRef = new BroadcastReceiver[1];
        final Runnable timeoutTask = new Runnable() {
            @Override
            public void run() {
                // 长时间无最终结果：国产 ROM 安全守护可能静默拦截了安装确认流程
                try {
                    if (receiverRef[0] != null) ctx.unregisterReceiver(receiverRef[0]);
                } catch (Throwable ignored) {}
                Log.w(TAG, "install timeout (" + INSTALL_TIMEOUT_MS + "ms), likely blocked by system");
                if (callback != null) callback.onInstallFailed(
                        "安装长时间无响应（可能被系统安全守护拦截）。\n"
                                + "建议改用「导出并安装」后，在文件管理器中手动点击导出的 APK 安装。");
            }
        };

        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    // Android 10+（及 MIUI/ColorOS/HyperOS 等 ROM）安装 APK 需要系统确认。
                    // 此时 status=-1 并非失败，而是「等待用户确认」：取出系统确认 Intent 弹出，
                    // 保持接收器注册，用户确认/取消后系统会再发一次最终结果。
                    Log.i(TAG, "pending user action, launching system confirm UI");
                    Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) {
                        try {
                            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            c.startActivity(confirm);
                            // 用户正在确认，重置超时计时
                            timeoutHandler.removeCallbacks(timeoutTask);
                            timeoutHandler.postDelayed(timeoutTask, INSTALL_TIMEOUT_MS);
                            return; // 不注销接收器，继续等待最终安装结果
                        } catch (Throwable t) {
                            Log.w(TAG, "launch confirm ui failed", t);
                        }
                    }
                    // 取不到确认 Intent 也按等待处理，不误报失败
                    timeoutHandler.removeCallbacks(timeoutTask);
                    timeoutHandler.postDelayed(timeoutTask, INSTALL_TIMEOUT_MS);
                    return;
                }
                timeoutHandler.removeCallbacks(timeoutTask);
                c.unregisterReceiver(this);
                if (status == PackageInstaller.STATUS_SUCCESS) {
                    if (callback != null) callback.onInstallSuccess(pkg == null ? "" : pkg);
                } else {
                    if (callback != null) callback.onInstallFailed(
                            "安装失败（" + status + "）" + (msg != null ? ": " + msg : ""));
                }
            }
        };
        receiverRef[0] = receiver;
        try {
            ctx.registerReceiver(receiver,
                    new IntentFilter(ACTION_INSTALL_RESULT));
        } catch (Throwable t) {
            Log.w(TAG, "register receiver failed", t);
        }
        timeoutHandler.postDelayed(timeoutTask, INSTALL_TIMEOUT_MS);

        final PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params;
        try {
            params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            if (pkg != null && pkg.length() > 0) params.setAppPackageName(pkg);
            int sessionId = pi.createSession(params);
            final PackageInstaller.Session session = pi.openSession(sessionId);
            OutputStream out = session.openWrite("base.apk", 0, apk.length());
            InputStream in = new FileInputStream(apk);
            byte[] buf = new byte[65536];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            session.fsync(out);
            out.close();
            in.close();

            Intent intent = new Intent(ACTION_INSTALL_RESULT);
            PendingIntent pi2 = PendingIntent.getBroadcast(ctx, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            session.commit(pi2.getIntentSender());
            session.close();
            Log.i(TAG, "install session committed, apk=" + total + " bytes");
        } catch (Throwable t) {
            Log.e(TAG, "install failed", t);
            try {
                timeoutHandler.removeCallbacks(timeoutTask);
                ctx.unregisterReceiver(receiver);
            } catch (Throwable ignored) {}
            if (callback != null) callback.onInstallFailed("安装调用失败: " + t.getMessage());
        }
    }

    /**
     * 通过系统安装器安装（推荐）。
     *
     * PackageInstaller Session 自动安装在部分国产 ROM（vivo/OPPO 安全守护）上会被
     * 静默拦截——确认框不弹、长时间无结果。系统安装器（ACTION_VIEW）与手动点击 APK
     * 完全同路径，实测可靠。本方法：
     * 1. 把 APK 复制到私有缓存固定名 install_apk.apk，经自研 ApkFileProvider 暴露
     *    content:// URI（无需存储权限、无需第三方 FileProvider 依赖）
     * 2. ACTION_VIEW 打开系统安装器（带 GRANT_READ_URI_PERMISSION）
     * 3. 已知包名时轮询 PackageManager：检测到 lastUpdateTime 变化即判定安装完成；
     *    超时（120 秒）未变则提示失败（可能被拦截或用户取消）
     */
    public static void installWithSystemInstaller(final Context ctx, final File apk,
                                                  final String pkg,
                                                  final InstallCallback callback) {
        if (apk == null || !apk.exists()) {
            if (callback != null) callback.onInstallFailed("APK 文件不存在");
            return;
        }
        if (!canRequestInstalls(ctx)) {
            if (callback != null) callback.onNeedPermission();
            return;
        }
        try {
            // 1. 复制到固定名（ApkFileProvider 只暴露这个文件）
            File target = new File(ctx.getCacheDir(),
                    com.aicodestudio.ide.ai.util.ApkFileProvider.FILE_NAME);
            copyFile(apk, target);
            // 2. 打开系统安装器
            Uri uri = com.aicodestudio.ide.ai.util.ApkFileProvider.buildUri();
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(i);
            Log.i(TAG, "system installer launched, uri=" + uri);
        } catch (Throwable t) {
            Log.e(TAG, "launch system installer failed", t);
            if (callback != null) callback.onInstallFailed(
                    "无法打开系统安装器：" + t.getMessage()
                            + "\n可在文件管理器中手动点击导出的 APK 安装。");
            return;
        }

        // 3. 已知包名 → 轮询安装结果（覆盖安装同版本时 lastUpdateTime 也会变化）
        if (pkg == null || pkg.length() == 0) {
            if (callback != null) callback.onInstallSuccess("");
            return;
        }
        final Handler h = new Handler(Looper.getMainLooper());
        final long beforeUpdate = getLastUpdateTime(ctx, pkg);
        final Runnable poll = new Runnable() {
            int tries = 0;
            @Override
            public void run() {
                tries++;
                long now = getLastUpdateTime(ctx, pkg);
                if (now != beforeUpdate) {
                    Log.i(TAG, "install detected: " + pkg + " updated at " + now);
                    if (callback != null) callback.onInstallSuccess(pkg);
                    return;
                }
                if (tries >= 60) { // 120 秒
                    if (callback != null) callback.onInstallFailed(
                            "未检测到安装完成（可能被安全守护拦截或已取消）。\n"
                                    + "请在系统安装器中确认安装；若被拦截，"
                                    + "可手动点击导出的 APK 安装。");
                    return;
                }
                h.postDelayed(this, 2000L);
            }
        };
        h.postDelayed(poll, 2000L);
    }

    private static long getLastUpdateTime(Context ctx, String pkg) {
        try {
            android.content.pm.PackageInfo pi = ctx.getPackageManager().getPackageInfo(pkg, 0);
            return pi.lastUpdateTime;
        } catch (Throwable t) {
            return -1L; // 未安装
        }
    }

    private static void copyFile(File src, File dst) throws java.io.IOException {
        InputStream in = new FileInputStream(src);
        OutputStream out = new java.io.FileOutputStream(dst);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.flush();
        out.close();
        in.close();
    }

    /** 启动已安装应用（安装成功后可选调用） */
    public static void launchApp(Context ctx, String packageName) {
        if (packageName == null || packageName.length() == 0) return;
        try {
            Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(packageName);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(launch);
            }
        } catch (Throwable t) {
            Log.w(TAG, "launch failed", t);
        }
    }

    /** 检查是否已授予「所有文件访问」（Android 11+ 导出产物到公共目录需要） */
    public static boolean hasAllFilesAccess(Context ctx) {
        if (Build.VERSION.SDK_INT >= 30) {
            return android.os.Environment.isExternalStorageManager();
        }
        return ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 跳转「所有文件访问」设置页 */
    public static void openAllFilesSettings(Context ctx) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Exception e2) {
                Log.w(TAG, "open all files settings failed", e2);
            }
        }
    }
}
