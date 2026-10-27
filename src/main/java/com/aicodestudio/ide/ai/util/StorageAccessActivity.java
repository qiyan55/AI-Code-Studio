package com.aicodestudio.ide.ai.util;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * StorageAccessActivity —— 存储访问代理（透明 Activity）
 *
 * 存在的理由：
 *   弹窗（Dialog）拿不到 onActivityResult / onRequestPermissionsResult 回调，
 *   而宿主 Activity 有十几个，逐个重写转发既不现实也容易漏。
 *   所以用一个透明 Activity 统一代理「申请权限」「调系统文件选择器」，
 *   拿到结果后通过静态回调把结果交还给调用方，自身立即 finish。
 *
 * 三种模式：
 *   MODE_PERMISSION    申请 READ_EXTERNAL_STORAGE（仅 Android 12 及以下有效）
 *   MODE_PICK_MODEL    ACTION_OPEN_DOCUMENT 选单个模型文件（**不需要任何权限**）
 *   MODE_PICK_SO_DIR   ACTION_OPEN_DOCUMENT_TREE 选引擎 .so 目录（同样免权限）
 *
 * 关于 Android 11+ 的分区存储：
 *   targetSdk 30 起，READ_EXTERNAL_STORAGE 只能读到媒体文件，
 *   .gguf / .bin 这类「非媒体」文件用 File 直读会被拒绝；
 *   Android 13（API 33）起该权限甚至不再授予。
 *   因此 SAF（Storage Access Framework）是唯一在所有版本上都成立的通用路径：
 *   用户亲自选文件 → 系统授予该 URI 的读权限 → 我们读取并落到 App 内部目录。
 */
public class StorageAccessActivity extends Activity {

    private static final String TAG = "StorageAccess";

    public static final String EXTRA_MODE = "mode";
    public static final int MODE_PERMISSION = 1;
    public static final int MODE_PICK_MODEL = 2;
    public static final int MODE_PICK_SO_DIR = 3;

    private static final int REQ_PERMISSION = 4101;
    private static final int REQ_PICK_MODEL = 4102;
    private static final int REQ_PICK_DIR = 4103;

    /** 回调接口（调用方在 startActivity 前设置，结果回来后自动清空） */
    public interface Callback {
        void onPicked(String path);
        void onDirectoryPicked(Uri treeUri);
        void onError(String message);
        void onCancelled();
    }

    private static Callback sCallback;

    public static void setCallback(Callback cb) {
        sCallback = cb;
    }

    private int mode = MODE_PICK_MODEL;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 透明无界面：只负责起 Intent 与收结果
        setTheme(android.R.style.Theme_Translucent_NoTitleBar);

        Intent src = getIntent();
        mode = src == null ? MODE_PICK_MODEL : src.getIntExtra(EXTRA_MODE, MODE_PICK_MODEL);

        try {
            if (mode == MODE_PERMISSION) {
                requestStoragePermission();
            } else if (mode == MODE_PICK_MODEL) {
                launchPickModel();
            } else if (mode == MODE_PICK_SO_DIR) {
                launchPickDir();
            } else {
                fail("未知的访问模式");
            }
        } catch (Throwable t) {
            Log.e(TAG, "launch failed", t);
            fail("无法打开系统文件选择器：" + t.getMessage());
        }
    }

    // ================= 权限申请（仅 API <= 32 有意义） =================

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{
                    android.Manifest.permission.READ_EXTERNAL_STORAGE
            }, REQ_PERMISSION);
        } else {
            // 6.0 以下是安装时授权
            deliverGranted();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMISSION) return;
        boolean granted = grantResults != null && grantResults.length > 0 && grantResults[0] == 0;
        if (granted) {
            deliverGranted();
        } else {
            Callback cb = takeCallback();
            if (cb != null) cb.onError("存储权限被拒绝");
            finish();
        }
    }

    private void deliverGranted() {
        Callback cb = takeCallback();
        if (cb != null) cb.onPicked("");
        finish();
    }

    // ================= 系统文件选择器（免权限） =================

    private void launchPickModel() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        // 允许选择任意类型；部分 ROM 会据此在文件管理器里高亮
        try {
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "application/octet-stream",
                    "application/zip",
                    "*/*"
            });
        } catch (Throwable ignored) {
        }
        // 优先定位到 Downloads，省去用户一层层翻
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                i.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                        Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload"));
            }
        } catch (Throwable ignored) {
        }
        startActivityForResult(Intent.createChooser(i, "选择模型文件（.gguf / .ggml / .bin）"), REQ_PICK_MODEL);
    }

    private void launchPickDir() {
        if (Build.VERSION.SDK_INT < 21) {
            fail("系统版本过低，不支持目录选择");
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            i.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                    Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload"));
        } catch (Throwable ignored) {
        }
        startActivityForResult(Intent.createChooser(i, "选择 .so 所在目录"), REQ_PICK_DIR);
    }

    // ================= 结果处理 =================

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            Callback cb = takeCallback();
            if (cb != null) cb.onCancelled();
            finish();
            return;
        }

        if (requestCode == REQ_PICK_DIR) {
            Uri tree = data.getData();
            if (tree == null) {
                fail("未取到目录");
                return;
            }
            try {
                getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) {
            }
            Callback cb = takeCallback();
            if (cb != null) cb.onDirectoryPicked(tree);
            finish();
            return;
        }

        if (requestCode == REQ_PICK_MODEL) {
            Uri uri = data.getData();
            if (uri == null) {
                fail("未取到文件");
                return;
            }
            handleModelUri(uri);
        }
    }

    /**
     * 处理选中的模型 URI：
     *   1. 先尝试解析成真实文件路径且可直接读 → 直接用，不占双倍空间
     *   2. 拿不到路径（分区存储限制）→ 拷贝到 App 内部目录后使用
     */
    private void handleModelUri(final Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) {
        }

        String name = queryDisplayName(uri);
        long size = querySize(uri);

        // 路径 1：直接解析真实路径
        String realPath = resolveRealPath(uri);
        if (!TextUtils.isEmpty(realPath)) {
            File f = new File(realPath);
            if (f.isFile() && f.canRead()) {
                Callback cb = takeCallback();
                if (cb != null) cb.onPicked(f.getAbsolutePath());
                finish();
                return;
            }
        }

        // 路径 2：拷贝到内部存储
        if (TextUtils.isEmpty(name)) name = "model.gguf";
        if (size <= 0) size = -1;
        copyToInternalAsync(uri, name, size);
    }

    private void copyToInternalAsync(final Uri uri, final String name, final long size) {
        final File dir = new File(getFilesDir(), "models");
        if (!dir.exists() && !dir.mkdirs()) {
            fail("无法创建内部目录");
            return;
        }

        final ProgressDialog pd = new ProgressDialog(this);
        pd.setTitle("正在导入模型");
        pd.setMessage(name);
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(100);
        pd.setCancelable(false);
        pd.setCanceledOnTouchOutside(false);
        try {
            pd.show();
        } catch (Throwable ignored) {
        }

        final File dst = new File(dir, name);

        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    ContentResolver cr = getContentResolver();
                    InputStream in = cr.openInputStream(uri);
                    if (in == null) throw new java.io.IOException("无法打开源文件");
                    OutputStream out = new FileOutputStream(dst);
                    byte[] buf = new byte[1024 * 1024];
                    long done = 0;
                    int lastPct = -1;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        if (size > 0) {
                            int pct = (int) (done * 100 / size);
                            if (pct != lastPct) {
                                lastPct = pct;
                                final int p = pct;
                                runOnUiThread(new Runnable() {
                                    @Override public void run() {
                                        try { pd.setProgress(p); } catch (Throwable ignored) {}
                                    }
                                });
                            }
                        }
                    }
                    out.flush();
                    out.close();
                    in.close();

                    final long bytes = dst.length();
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            try { pd.dismiss(); } catch (Throwable ignored) {}
                            Callback cb = takeCallback();
                            if (cb != null) cb.onPicked(dst.getAbsolutePath());
                            finish();
                        }
                    });
                } catch (final Throwable t) {
                    Log.e(TAG, "copy failed", t);
                    if (dst.exists()) dst.delete();
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            try { pd.dismiss(); } catch (Throwable ignored) {}
                            Toast.makeText(StorageAccessActivity.this,
                                    "导入失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
                            Callback cb = takeCallback();
                            if (cb != null) cb.onError("读取失败：" + t.getMessage());
                            finish();
                        }
                    });
                }
            }
        }).start();
    }

    // ================= 工具方法 =================

    /** 从 DocumentsContract / MediaStore 反查真实路径；失败返回 null */
    private String resolveRealPath(Uri uri) {
        if (uri == null) return null;
        String scheme = uri.getScheme();
        if ("file".equalsIgnoreCase(scheme)) {
            return uri.getPath();
        }
        if (!"content".equalsIgnoreCase(scheme)) return null;

        // 外部存储提供者形如 content://.../document/primary:Download/xx.gguf
        try {
            String id = null;
            if (Build.VERSION.SDK_INT >= 19 && isExternalStorageDocument(uri)) {
                String whole = DocumentsContract.getDocumentId(uri);
                if (whole != null && whole.startsWith("primary:")) {
                    id = whole.substring("primary:".length());
                    return EnvironmentPath() + "/" + id;
                }
            }
        } catch (Throwable ignored) {
        }

        // 兜底：查 _data 列
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{"_data"}, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex("_data");
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) { try { c.close(); } catch (Throwable ignored) {} }
        }
        return null;
    }

    private static String EnvironmentPath() {
        return android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
    }

    private static boolean isExternalStorageDocument(Uri uri) {
        return "com.android.externalstorage.documents".equals(uri.getAuthority());
    }

    private String queryDisplayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) { try { c.close(); } catch (Throwable ignored) {} }
        }
        // 兜底：取 URI 最后一段
        try {
            String last = uri.getLastPathSegment();
            if (last != null && last.contains("/")) last = last.substring(last.lastIndexOf('/') + 1);
            return last;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private long querySize(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE);
                if (idx >= 0) return c.getLong(idx);
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) { try { c.close(); } catch (Throwable ignored) {} }
        }
        return -1;
    }

    private Callback takeCallback() {
        Callback cb = sCallback;
        sCallback = null;
        return cb;
    }

    private void fail(String msg) {
        Callback cb = takeCallback();
        if (cb != null) cb.onError(msg);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Activity 被系统回收时避免残留回调造成内存泄漏
        sCallback = null;
    }

    /** 是否拥有「所有文件访问权限」（Android 11+ 免 SAF 直读的前提） */
    public static boolean hasAllFilesAccess() {
        if (Build.VERSION.SDK_INT < 30) return true;
        try {
            return android.os.Environment.isExternalStorageManager();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 是否已被授予 READ_EXTERNAL_STORAGE（Android 12 及以下有效） */
    public static boolean hasLegacyReadPermission(android.content.Context ctx) {
        if (Build.VERSION.SDK_INT < 23) return true;
        try {
            return ctx.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 已授予的持久化 URI 权限（用于目录树） */
    public static boolean hasPersistedUri(android.content.Context ctx) {
        try {
            List<UriPermission> ps = ctx.getContentResolver().getPersistedUriPermissions();
            return ps != null && !ps.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }
}
