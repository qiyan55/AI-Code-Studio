package com.aicodestudio.ide.ai.voice;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 语音调试日志：写入公共目录 Download/AI_Code_Studio/（MediaStore 方式，无需存储权限，
 * 文件管理器可直接看到）。禁止使用私有目录（filesDir / Android/data）——用户设备上
 * 系统屏蔽私有目录，用户永远找不到日志。
 *
 * 每次 App 启动（语音引擎 init）创建一个带时间戳的会话日志：
 *   Download/AI_Code_Studio/voice_engine_yyyyMMdd_HHmmss.log   —— 引擎加载过程
 *   Download/AI_Code_Studio/voice_input_yyyyMMdd_HHmmss.log    —— 语音识别过程
 */
public class VoiceLog {

    private static Context app;
    private static Uri engineUri;
    private static Uri inputUri;
    private static String stamp;

    /** App 启动（语音引擎 init 线程）调用一次；幂等 */
    public static void init(Context ctx) {
        try {
            if (app == null) {
                app = ctx.getApplicationContext();
                stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                engineUri = createFile("voice_engine_" + stamp + ".log");
                inputUri = createFile("voice_input_" + stamp + ".log");
                engine("==== VoiceEngine 会话开始 " + stamp + " ====");
                input("==== VoiceInput 会话开始 " + stamp + " ====");
            }
        } catch (Throwable ignored) {}
    }

    public static void engine(String msg) { append(engineUri, msg); }

    public static void input(String msg) { append(inputUri, msg); }

    // ================= 内部 =================

    private static Uri createFile(String name) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/AI_Code_Studio");
            return app.getContentResolver().insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void append(Uri uri, String msg) {
        if (uri == null || app == null) return;
        try {
            ContentResolver cr = app.getContentResolver();
            OutputStream os = cr.openOutputStream(uri, "wa"); // 追加写
            if (os == null) return;
            String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date())
                    + " [" + Thread.currentThread().getName() + "] " + msg + "\n";
            os.write(line.getBytes("UTF-8"));
            os.close();
        } catch (Throwable ignored) {}
    }
}
