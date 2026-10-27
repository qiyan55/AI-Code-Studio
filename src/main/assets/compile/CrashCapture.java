package com.aicodestudio.toolchain;

import android.app.Application;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 崩溃日志采集（由 AI Code Studio 编译链自动注入产物 APK，v11.19 增强版）。
 *
 * 功能：
 * 1. Application.onCreate 时先写一条「启动标记」日志（app_started），用于区分
 *    崩溃发生在 Application 之前（无标记）还是之后（有标记）。
 * 2. 捕获未处理异常，把崩溃栈写入公共目录：
 *    - Android 10+（API 29+）：MediaStore Downloads/AI Code Studio/crash_logs/（免存储权限）
 *    - Android 9 及以下：/storage/emulated/0/AI Code Studio/crash_logs/（尽力而为）
 *    - 始终输出到 Logcat（TAG=CrashCapture），adb logcat 可抓取
 * 3. 记录设备 ROM、系统版本、包名、进程信息，便于定位 ROM 兼容问题。
 */
public class CrashCapture extends Application {
    private static final String TAG = "CrashCapture";
    private static final String DIR = "AI Code Studio/crash_logs";

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            saveText("app_started", buildHeader("应用启动（Application.onCreate 已执行）"));
            Log.i(TAG, "app_started: " + getPackageName());
        } catch (Throwable ignored) {
        }
        final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    String text = buildHeader("捕获到未处理崩溃")
                            + "线程: " + (t == null ? "?" : t.getName()) + "\n\n"
                            + stackOf(e);
                    saveText("crash", text);
                    Log.e(TAG, "crash captured: " + text);
                    // 同时把根因异常也展开（Android 经常包装在 RuntimeException 里）
                    if (e != null && e.getCause() != null) {
                        Log.e(TAG, "caused by: " + stackOf(e.getCause()));
                    }
                } catch (Throwable ignored) {
                }
                if (def != null) def.uncaughtException(t, e);
            }
        });
    }

    private String buildHeader(String phase) {
        return "设备: " + Build.MANUFACTURER + " " + Build.MODEL
                + " (Android " + Build.VERSION.RELEASE + ", API " + Build.VERSION.SDK_INT + ")"
                + "\nROM: " + (Build.DISPLAY == null ? "?" : Build.DISPLAY)
                + "\n包名: " + getPackageName()
                + "\n阶段: " + phase
                + "\n时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                + "\n";
    }

    private String stackOf(Throwable e) {
        if (e == null) return "(null)";
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private void saveText(String kind, String text) throws Exception {
        String name = kind + "_" + getPackageName().replace('.', '_')
                + "_" + System.currentTimeMillis() + ".txt";
        byte[] bytes = text.getBytes("UTF-8");
        boolean saved = false;
        // 通道1：MediaStore（API 29+，免存储权限）
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/" + DIR);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri != null) {
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    if (os != null) {
                        os.write(bytes);
                        os.close();
                        Log.i(TAG, kind + " log saved via MediaStore: " + name);
                        saved = true;
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "MediaStore save failed", t);
            }
        }
        // 通道2：直接文件（API 28 及以下，或 MediaStore 失败时尽力而为）
        if (!saved) {
            try {
                File dir = new File(Environment.getExternalStorageDirectory(), DIR);
                if (dir.exists() || dir.mkdirs()) {
                    File f = new File(dir, name);
                    FileOutputStream fos = new FileOutputStream(f);
                    fos.write(bytes);
                    fos.close();
                    Log.i(TAG, kind + " log saved: " + f.getAbsolutePath());
                    saved = true;
                }
            } catch (Throwable t) {
                Log.w(TAG, "file save failed", t);
            }
        }
        if (!saved) {
            Log.e(TAG, kind + " log NOT saved to disk, content:\n" + text);
        }
    }
}
