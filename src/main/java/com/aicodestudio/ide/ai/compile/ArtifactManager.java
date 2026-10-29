package com.aicodestudio.ide.ai.compile;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * APK 产物管理器
 *
 * 两类产物目录：
 * 1. 私有临时目录（App cacheDir/compile_apk/）：每次编译生成的签名 APK，
 *    安装完成后默认立即删除，用户不感知。
 * 2. 公共导出目录（SD 卡 AI Code Studio/）：用户主动「导出 APK」时复制到此，
 *    长期保留，可通过编译页「产物」按钮查看/删除。
 *
 * 导出目录使用 MANAGE_EXTERNAL_STORAGE（App 已声明，Android 11+ 需运行时授权，
 * 见 ApkInstaller.hasAllFilesAccess / openAllFilesSettings）。
 */
public class ArtifactManager {

    private static final String TAG = "ArtifactManager";
    private static final String EXPORT_DIR_NAME = "AI Code Studio"; // SD 卡根目录下的产物目录

    /** 私有临时产物目录（每次编译的 APK 落这里，安装后删除） */
    public static File getPrivateApkDir(Context ctx) {
        File dir = new File(ctx.getCacheDir(), "compile_apk");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "无法创建私有产物目录");
        }
        return dir;
    }

    /** 公共导出目录：/storage/emulated/0/AI Code Studio/ */
    public static File getExportDir() {
        File root = Environment.getExternalStorageDirectory();
        File dir = new File(root, EXPORT_DIR_NAME);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "无法创建导出目录 " + dir);
        }
        return dir;
    }

    /** 当前私有产物（最近一次编译的 APK，最多取 1 个） */
    public static File getLatestPrivateApk(Context ctx) {
        File[] fs = getPrivateApkDir(ctx).listFiles();
        if (fs == null) return null;
        File latest = null;
        for (File f : fs) {
            if (!f.getName().endsWith(".apk")) continue;
            if (latest == null || f.lastModified() > latest.lastModified()) latest = f;
        }
        return latest;
    }

    /**
     * 导出 APK 到公共目录。
     *
     * @return 导出的文件；失败返回 null
     */
    public static File exportApk(File apk, String projectName) {
        if (apk == null || !apk.exists()) return null;
        String name = (projectName != null && projectName.length() > 0
                ? sanitize(projectName) : "app")
                + "-" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
                + ".apk";
        File target = new File(getExportDir(), name);
        try {
            copyFile(apk, target);
            Log.i(TAG, "exported -> " + target.getAbsolutePath());
            return target;
        } catch (IOException e) {
            Log.e(TAG, "export failed", e);
            return null;
        }
    }

    /** 公共导出目录下的 APK 列表（按时间倒序） */
    public static List<File> listExportedApks() {
        List<File> out = new ArrayList<>();
        File[] fs = getExportDir().listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            if (f.isFile() && f.getName().endsWith(".apk")) out.add(f);
        }
        out.sort(new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        return out;
    }

    /** 删除产物文件 */
    public static boolean deleteArtifact(File f) {
        if (f == null || !f.exists()) return false;
        boolean ok = f.delete();
        if (!ok) Log.w(TAG, "删除失败: " + f.getAbsolutePath());
        return ok;
    }

    /** 清理私有临时产物（安装完成后调用） */
    public static void clearPrivateApks(Context ctx) {
        File[] fs = getPrivateApkDir(ctx).listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isFile() && f.getName().endsWith(".apk")) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    /** 格式化文件大小 */
    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) {
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /** 格式化时间 */
    public static String formatTime(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(millis));
    }

    private static String sanitize(String s) {
        return s.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
    }

    private static void copyFile(File src, File dst) throws IOException {
        FileOutputStream fos = null;
        InputStream in = null;
        try {
            in = new FileInputStream(src);
            fos = new FileOutputStream(dst);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            fos.flush();
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
            if (fos != null) try { fos.close(); } catch (IOException ignored) {}
        }
    }

    /** 判断导出目录是否可用（有写入权限） */
    public static boolean exportDirWritable() {
        File dir = getExportDir();
        return dir.exists() && dir.canWrite();
    }
}
