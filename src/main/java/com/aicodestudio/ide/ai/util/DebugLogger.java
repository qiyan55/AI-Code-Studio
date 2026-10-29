package com.aicodestudio.ide.ai.util;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * v1.108: 调试日志系统
 * 每轮对话一个新文件，存到 SD 卡 AI Code Studio/log 目录。
 * 分类：RENDER（流式渲染）、AGENT（prompt拼接/agent循环）、STREAM（token流）、STAGE（阶段检测）。
 */
public final class DebugLogger {
    private static final String TAG = "DebugLogger";
    private static File logDir;
    private static File currentFile;
    private static final Object lock = new Object();
    private static final SimpleDateFormat FILE_FMT =
            new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US);
    private static final SimpleDateFormat TIME_FMT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static final AtomicInteger roundSeq = new AtomicInteger(0);

    private DebugLogger() {}

    /** 初始化日志目录（在 Application.onCreate 调用）。 */
    public static void init(Context ctx) {
        synchronized (lock) {
            // v1.108b: 公共目录 /sdcard/AI Code Studio/log/（文件管理器可见）
            File base = new File(android.os.Environment.getExternalStorageDirectory(), "AI Code Studio");
            logDir = new File(base, "log");
            if (!logDir.exists()) logDir.mkdirs();
            currentFile = null;
            roundSeq.set(0);
        }
    }

    /** 每轮对话开始时调用，创建新文件。 */
    public static void newRound(String userQuery) {
        synchronized (lock) {
            if (logDir == null) return;
            String name = "round_" + FILE_FMT.format(new Date()) + "_"
                    + String.format(Locale.US, "%03d", roundSeq.incrementAndGet()) + ".log";
            currentFile = new File(logDir, name);
            String header = "=== AI Code Studio Debug Log ===\n"
                    + "round: " + roundSeq.get() + "\n"
                    + "user: " + userQuery + "\n"
                    + "================================\n\n";
            writeRaw(header);
        }
    }

    /**
     * v1.153au：确保日志文件已存在。
     * 切换会话/打开历史/发送前的流程日志，若还没到 newRound（发消息）就会没有文件而丢弃。
     * 这里兜底创建一个"boot"日志文件，让切换流程的 FLOW 日志能落盘。
     */
    public static void ensureFile() {
        synchronized (lock) {
            if (logDir == null || currentFile != null) return;
            String name = "round_" + FILE_FMT.format(new Date()) + "_"
                    + String.format(Locale.US, "%03d", roundSeq.incrementAndGet()) + ".log";
            currentFile = new File(logDir, name);
            String header = "=== AI Code Studio Debug Log ===\n"
                    + "round: " + roundSeq.get() + "\n"
                    + "user: (boot / no message yet)\n"
                    + "================================\n\n";
            writeRaw(header);
        }
    }

    /** 写一行日志。category: RENDER/AGENT/STREAM/STAGE/ERROR。 */
    public static void log(String category, String msg) {
        synchronized (lock) {
            if (logDir == null || currentFile == null) return;
            String line = "[" + TIME_FMT.format(new Date()) + "] [" + category + "] " + msg + "\n";
            writeRaw(line);
        }
    }

    /** 记录完整 prompt 拼接内容。 */
    public static void dumpPrompt(String prompt) {
        log("AGENT", "===== PROMPT DUMP (len=" + prompt.length() + ") =====");
        log("AGENT", prompt);
        log("AGENT", "===== END PROMPT DUMP =====");
    }

    /** 记录 AI 完整输出。 */
    public static void dumpOutput(String output) {
        log("STREAM", "===== AI OUTPUT (len=" + output.length() + ") =====");
        log("STREAM", output);
        log("STREAM", "===== END AI OUTPUT =====");
    }

    private static void writeRaw(String s) {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(currentFile, true);
            fos.write(s.getBytes("UTF-8"));
            fos.flush();
        } catch (Throwable ignored) {
        } finally {
            if (fos != null) {
                try { fos.close(); } catch (Throwable ignored) {}
            }
        }
    }
}
