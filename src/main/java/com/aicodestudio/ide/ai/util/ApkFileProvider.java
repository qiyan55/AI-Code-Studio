package com.aicodestudio.ide.ai.util;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * APK 安装专用 ContentProvider（自研，无第三方依赖）。
 *
 * 用途：把私有缓存目录里的 APK 通过 content:// URI 暴露给系统安装器
 * （ACTION_VIEW + application/vnd.android.package-archive）。
 *
 * 背景：PackageInstaller Session 自动安装在这台设备（国产 ROM 安全守护）上
 * 会被静默拦截、确认框不弹、长时间无结果；而手动点 APK（系统安装器）可用。
 * 因此「直接安装」改为打开系统安装器，与本 provider 配合实现与手动安装
 * 完全一致的可靠路径。
 *
 * 安全：provider exported=false + grantUriPermissions=true，仅本 App 构造的
 * URI 配合 Intent FLAG_GRANT_READ_URI_PERMISSION 授予系统安装器临时读权限；
 * 且路径固定为 cacheDir/install_apk.apk，拒绝任何其他路径。
 */
public class ApkFileProvider extends ContentProvider {

    private static final String TAG = "ApkFileProvider";
    /** 固定暴露的文件名（安装前由 ApkInstaller 复制 APK 到此） */
    public static final String FILE_NAME = "install_apk.apk";
    /** authority，需与 AndroidManifest 中 provider 的 authorities 一致 */
    public static final String AUTHORITY = "com.aicodestudio.ide.apk";

    @Override
    public boolean onCreate() {
        return true;
    }

    /** 构造指向待安装 APK 的 content URI：content://<AUTHORITY>/install_apk.apk */
    public static Uri buildUri() {
        return Uri.parse("content://" + AUTHORITY + "/" + FILE_NAME);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (uri == null || uri.getLastPathSegment() == null
                || !FILE_NAME.equals(uri.getLastPathSegment())) {
            Log.w(TAG, "reject uri: " + uri);
            throw new FileNotFoundException("invalid uri");
        }
        File f = new File(getContext().getCacheDir(), FILE_NAME);
        if (!f.exists() || f.length() == 0) {
            Log.w(TAG, "apk not ready: " + f.getAbsolutePath());
            throw new FileNotFoundException("apk not ready");
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    // ---- 其余 ContentProvider 方法不适用，置空 ----
    @Override public String getType(Uri uri) { return "application/vnd.android.package-archive"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) { return 0; }
}
