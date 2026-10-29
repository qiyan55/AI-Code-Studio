package com.aicodestudio.ide.ai.remote;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * RemoteBridge — 远程桥接层
 *
 * 持有 ApplicationContext + 当前前台 Activity，提供：
 * - 导航（启动各 Activity）
 * - 编辑器操作（打开文件/读写/保存）
 * - 聊天（发送消息）
 * - 编译（触发自举编译）
 * - 截图（截取当前屏幕）
 */
public class RemoteBridge {
    private static final String TAG = "RemoteBridge";
    private static RemoteBridge instance;

    public static synchronized RemoteBridge get() {
        if (instance == null) instance = new RemoteBridge();
        return instance;
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Context appContext;
    private WeakReference<Activity> currentActivity = new WeakReference<>(null);
    private WeakReference<EditorOps> editorOps = new WeakReference<>(null);

    // ===== 编辑器操作回调（由 EditorActivity 注册）=====
    public interface EditorOps {
        boolean remoteOpenFile(String projectRelPath);
        void remoteSetContent(String content);
        String remoteGetContent();
        boolean remoteSave();
        String remoteGetCurrentPath();
        String remoteGetProjectPath();
    }

    public void init(Context ctx) {
        this.appContext = ctx.getApplicationContext();
    }

    public void attachActivity(Activity activity) {
        this.currentActivity = new WeakReference<>(activity);
    }

    public void detachActivity(Activity activity) {
        if (currentActivity.get() == activity) {
            this.currentActivity = new WeakReference<>(null);
        }
    }

    public void attachEditor(EditorOps ops) {
        this.editorOps = new WeakReference<>(ops);
    }

    public void detachEditor(EditorOps ops) {
        if (editorOps.get() == ops) {
            this.editorOps = new WeakReference<>(null);
        }
    }

    public boolean isReady() {
        return appContext != null;
    }

    // ===== 同步执行 =====
    private <T> T runOnMain(final MainTask<T> task) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return task.run();
        }
        final Object[] result = new Object[1];
        final Throwable[] err = new Throwable[1];
        final CountDownLatch latch = new CountDownLatch(1);
        mainHandler.post(new Runnable() {
            @Override public void run() {
                try { result[0] = task.run(); }
                catch (Throwable t) { err[0] = t; }
                finally { latch.countDown(); }
            }
        });
        try { latch.await(30, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        if (err[0] != null) {
            Log.e(TAG, "task error", err[0]);
            return null;
        }
        return (T) result[0];
    }

    public interface MainTask<T> { T run(); }

    // ===== 导航 =====
    public String navigate(String target) {
        final String t = target;
        return runOnMain(new MainTask<String>() {
            @Override public String run() {
                try {
                    Class<?> cls;
                    if ("home".equals(t) || "welcome".equals(t)) cls = com.aicodestudio.ide.ai.WelcomeActivity.class;
                    else if ("editor".equals(t)) cls = com.aicodestudio.ide.ai.EditorActivity.class;
                    else if ("chat".equals(t)) cls = com.aicodestudio.ide.ai.ChatActivity.class;
                    else if ("run".equals(t) || "compile".equals(t)) cls = com.aicodestudio.ide.ai.RunActivity.class;
                    else return "unknown target: " + t;

                    Intent it = new Intent(appContext, cls);
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    appContext.startActivity(it);
                    return "ok -> " + t;
                } catch (Exception e) {
                    return "nav error: " + e.getMessage();
                }
            }
        });
    }

    // ===== 截图（缩略，快速传输）=====
    public String screenshot() {
        return runOnMain(new MainTask<String>() {
            @Override public String run() {
                Activity act = currentActivity.get();
                if (act == null) return "{\"error\":\"no activity\"}";
                try {
                    View root = act.getWindow().getDecorView().getRootView();
                    int w = root.getWidth();
                    int h = root.getHeight();
                    // v1.48f fix：视图未完成布局时宽高可能为 0，直接除零崩溃
                    if (w <= 0 || h <= 0) return "{\"error\":\"view not ready\"}";
                    // 缩放到宽度 540
                    float scale = 540f / w;
                    int tw = 540;
                    int th = (int)(h * scale);
                    if (th <= 0) th = 1;
                    Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
                    Canvas c = new Canvas(bmp);
                    root.draw(c);
                    Bitmap small = Bitmap.createScaledBitmap(bmp, tw, th, true);
                    bmp.recycle();
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    small.compress(Bitmap.CompressFormat.JPEG, 35, baos);
                    small.recycle();
                    return android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP);
                } catch (Exception e) {
                    return "{\"error\":\"" + e.getMessage() + "\"}";
                }
            }
        });
    }

    // ===== 编辑器操作 =====
    public boolean editorReady() {
        return editorOps.get() != null;
    }

    public String openFile(String path) {
        EditorOps ops = editorOps.get();
        if (ops == null) return "editor not attached";
        return runOnMain(() -> ops.remoteOpenFile(path)) ? "ok" : "fail";
    }

    public String setContent(String content) {
        EditorOps ops = editorOps.get();
        if (ops == null) return "editor not attached";
        runOnMain(() -> { ops.remoteSetContent(content); return null; });
        return "ok";
    }

    public String getContent() {
        EditorOps ops = editorOps.get();
        if (ops == null) return "";
        String c = runOnMain(() -> ops.remoteGetContent());
        if (c == null) return "";
        // 截断到 40KB，避免走附件
        if (c.length() > 40000) c = c.substring(0, 40000) + "\n...[截断 " + c.length() + " 字符]";
        return c;
    }

    public String save() {
        EditorOps ops = editorOps.get();
        if (ops == null) return "editor not attached";
        return runOnMain(() -> ops.remoteSave()) ? "ok" : "fail";
    }

    public String getCurrentPath() {
        EditorOps ops = editorOps.get();
        if (ops == null) return "";
        return runOnMain(() -> ops.remoteGetCurrentPath());
    }

    public String getProjectPath() {
        EditorOps ops = editorOps.get();
        if (ops == null) return "";
        return runOnMain(() -> ops.remoteGetProjectPath());
    }

    // ===== 聊天 =====
    public String chatSend(final String prompt) {
        return runOnMain(() -> {
            try {
                com.aicodestudio.ide.ai.chat.AIChatManager mgr =
                    com.aicodestudio.ide.ai.chat.AIChatManager.getInstance();
                if (mgr == null) return "chat manager not ready";
                mgr.sendMessage(prompt);
                return "sent: " + prompt;
            } catch (Exception e) {
                return "chat error: " + e.getMessage();
            }
        });
    }

    // ===== 编译 =====
    public String compile() {
        // v1.48f fix：编译是分钟级重活，绝不能切主线程执行（会 ANR 卡死 UI）。
        // 编译本身不依赖 UI，直接在本线程（调用方 worker 线程）同步执行；
        // 仅当调用方恰在主线程时才起新线程执行。
        if (Looper.myLooper() == Looper.getMainLooper()) {
            final String[] out = new String[1];
            final Throwable[] err = new Throwable[1];
            final CountDownLatch latch = new CountDownLatch(1);
            new Thread(new Runnable() {
                @Override public void run() {
                    try { out[0] = doCompileSync(); }
                    catch (Throwable t) { err[0] = t; }
                    finally { latch.countDown(); }
                }
            }, "compile-offmain").start();
            try { latch.await(600, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            if (err[0] != null) return "compile error: " + err[0].getMessage();
            return out[0] != null ? out[0] : "compile timeout";
        }
        return doCompileSync();
    }

    /** 实际编译动作（后台线程执行，避免阻塞 UI） */
    private String doCompileSync() {
        try {
            EditorOps ops = editorOps.get();
            String projPath = ops != null ? ops.remoteGetProjectPath() : "";
            if (projPath == null || projPath.isEmpty()) return "no project open";
            com.aicodestudio.ide.ai.compile.BuiltinCompiler compiler =
                new com.aicodestudio.ide.ai.compile.BuiltinCompiler(appContext);
            if (!compiler.prepare()) return "prepare failed: " + compiler.getPrepareError();
            com.aicodestudio.ide.ai.compile.BuiltinCompiler.BuildApkResult r =
                compiler.buildApk(new File(projPath), "", null);
            String log = (r.success ? "BUILD OK\n" : "BUILD FAIL\n") + r.log + "\n" + r.error;
            // 截断日志
            if (log.length() > 5000) log = log.substring(log.length() - 5000);
            return log;
        } catch (Exception e) {
            return "compile error: " + e.getMessage();
        }
    }

    // ===== 列出项目文件 =====
    public String listFiles(String subdir) {
        EditorOps ops = editorOps.get();
        String proj = ops != null ? ops.remoteGetProjectPath() : null;
        if (proj == null || proj.isEmpty()) return "{\"error\":\"no project\"}";
        try {
            File dir = new File(proj, subdir != null && !subdir.isEmpty() ? subdir : ".");
            if (!dir.exists() || !dir.isDirectory()) return "{\"error\":\"dir not found\"}";
            StringBuilder sb = new StringBuilder("[");
            File[] files = dir.listFiles();
            if (files != null) {
                boolean first = true;
                for (File f : files) {
                    if (f.getName().startsWith(".")) continue;
                    if (!first) sb.append(",");
                    first = false;
                    sb.append("{\"name\":\"").append(f.getName())
                      .append("\",\"dir\":").append(f.isDirectory())
                      .append("}");
                }
            }
            sb.append("]");
            return sb.toString();
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    public String getStatus() {
        JSONObject o = new JSONObject();
        try {
            o.put("ready", appContext != null);
            o.put("editor", editorOps.get() != null);
            Activity a = currentActivity.get();
            o.put("activity", a != null ? a.getClass().getSimpleName() : "none");
            EditorOps ops = editorOps.get();
            if (ops != null) {
                o.put("project", ops.remoteGetProjectPath());
                o.put("current_file", ops.remoteGetCurrentPath());
            }
        } catch (Exception ignored) {}
        return o.toString();
    }
}
