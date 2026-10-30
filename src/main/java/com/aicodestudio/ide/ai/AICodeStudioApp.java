package com.aicodestudio.ide.ai;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.aicodestudio.ide.ai.chat.ChatPanel;
import com.aicodestudio.ide.ai.compile.CompileExecutor;
import com.aicodestudio.ide.ai.models.LocalModelManager;
import com.aicodestudio.ide.ai.service.AIService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * AI Code Studio Application 入口
 *
 * 修复记录：
 * 1. 移除 onCreate 中的 startService —— Android 8.0+ 后台启动服务会抛
 *    IllegalStateException，导致启动即崩（黑屏）。
 * 2. 修复全局异常处理器无限递归 —— 原实现调用 getDefaultUncaughtExceptionHandler()
 *    拿到的是刚设置的自己，造成 StackOverflowError。现改为转发系统默认处理器。
 * 3. 崩溃时把堆栈写入 /sdcard/Android/data/<pkg>/files/aicode_crash.log
 *    （该目录无需任何存储权限即可写入，用户可用文件管理器直接查看）
 * 4. 后台感知省电：应用进入后台一段时间后自动卸载本地模型（释放 ~1.4GB 内存
 *    和 native 资源）并释放空闲 WebView 池；回到前台自动重新加载上次模型。
 *    解决模型常驻导致系统后台耗电统计占用过久的问题。
 */
public class AICodeStudioApp extends Application {

    /** 进入后台后延迟卸载模型的时长：短时间切回秒回，3 分钟后仍在后台则释放模型省内存 */
    private static final long BACKGROUND_UNLOAD_DELAY_MS = 3 * 60 * 1000L;

    private int startedActivityCount = 0;   // 前台 Activity 计数（>0 表示在前台）
    private boolean backgroundUnloadScheduled = false;
    private final Handler bgHandler = new Handler(Looper.getMainLooper());

    private static AICodeStudioApp sApp;

    /** 全局 App 实例（供 agent 命令执行器等需要 Context 的组件使用） */
    public static AICodeStudioApp getApp() {
        return sApp;
    }

    /** 后台延迟卸载任务：3 分钟后仍在后台则释放模型 + WebView 池 */
    private final Runnable bgUnloadTask = new Runnable() {
        @Override public void run() {
            backgroundUnloadScheduled = false;
            if (startedActivityCount > 0) return; // 期间已回到前台
            try {
                LocalModelManager.getInstance(AICodeStudioApp.this)
                        .unloadModelForBattery();
            } catch (Throwable ignored) {
            }
            try {
                ChatPanel.releaseWebViewPool();
            } catch (Throwable ignored) {
            }
        }
    };


    public static void requestIgnoreBatteryOptimizations(android.app.Activity activity) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.os.PowerManager pm = (android.os.PowerManager) activity.getSystemService(android.content.Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(activity.getPackageName())) {
                try {
                    android.content.Intent intent = new android.content.Intent();
                    intent.setAction(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(android.net.Uri.parse("package:" + activity.getPackageName()));
                    activity.startActivity(intent);
                } catch (Throwable ignored) {}
            }
        }
    }


    /** 后台任务完成时发送系统通知 */
    public static void showTaskNotification(String title, String message) {
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager) sApp.getSystemService(android.content.Context.NOTIFICATION_SERVICE);
            String channelId = "task_done_channel";
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                android.app.NotificationChannel ch = new android.app.NotificationChannel(channelId, "后台任务完成", android.app.NotificationManager.IMPORTANCE_DEFAULT);
                if (nm != null) nm.createNotificationChannel(ch);
            }
            android.content.Intent intent = new android.content.Intent(sApp, WelcomeActivity.class);
            intent.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(sApp, 0, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
            
            android.app.Notification.Builder builder;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                builder = new android.app.Notification.Builder(sApp, channelId);
            } else {
                builder = new android.app.Notification.Builder(sApp);
            }
            builder.setSmallIcon(android.R.drawable.stat_notify_sync)
                   .setContentTitle(title)
                   .setContentText(message)
                   .setContentIntent(pi)
                   .setAutoCancel(true);
            if (nm != null) nm.notify((int) System.currentTimeMillis(), builder.build());
        } catch (Throwable ignored) {}
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sApp = this;
        // v1.108: 调试日志系统初始化
        try {
            com.aicodestudio.ide.ai.util.DebugLogger.init(this);
        } catch (Throwable ignored) {}
        // 注入 agent 命令执行器的 App 上下文（编译接口需要）
        try {
            com.aicodestudio.ide.ai.agent.ShellCommandExecutor.attach(this);
        } catch (Throwable ignored) {}
        // 注入 AI 调度器上下文（系统侧意图识别自动创建项目需要）
        try {
            com.aicodestudio.ide.ai.chat.AIChatManager.attach(this);
        } catch (Throwable ignored) {}
        // RemoteBridge 初始化（远程连接器）
        try {
            com.aicodestudio.ide.ai.remote.RemoteBridge.get().init(this);
        } catch (Throwable ignored) {}

        // 项目路径恢复：App 重启后 WorkspaceState 是进程内单例会丢路径，
        // 从最近项目记录恢复（最新的在前），保证对话页/agent 始终写当前项目
        try {
            com.aicodestudio.ide.ai.core.WorkspaceState ws =
                    com.aicodestudio.ide.ai.core.WorkspaceState.get();
            if (ws.getProjectPath() == null || ws.getProjectPath().isEmpty()) {
                java.util.List<String> recents =
                        com.aicodestudio.ide.ai.core.AppConfig.getInstance(this).getRecentProjects();
                if (!recents.isEmpty()) {
                    ws.setProjectPath(recents.get(0));
                    ws.setProjectType(
                            com.aicodestudio.ide.ai.core.WorkspaceState.detectProjectType(recents.get(0)));
                }
            }
        } catch (Throwable ignored) {}

        // 语音引擎改由 WelcomeActivity.onCreate 触发预加载（避开 Application 早期 native 初始化，
        // 确保界面先起来；即使引擎加载异常也不影响 App 启动）

        // 注册前后台感知：进入后台延迟卸载模型，回到前台自动重载
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityStarted(Activity a) {
                onForegroundEntered();
            }
            @Override public void onActivityResumed(Activity a) {
                try { com.aicodestudio.ide.ai.remote.RemoteBridge.get().attachActivity(a); } catch (Throwable ignored) {}
            }
            @Override public void onActivityPaused(Activity a) {
                // 不 detach，后台保留引用以便远程截图
            }
            @Override public void onActivityStopped(Activity a) {
                onBackgroundEntered();
            }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {
                try { com.aicodestudio.ide.ai.remote.RemoteBridge.get().detachActivity(a); } catch (Throwable ignored) {}
            }
        });

        // 先保存系统默认异常处理器（必须在设置自定义处理器之前）
        final Thread.UncaughtExceptionHandler defaultHandler =
                Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    writeCrashLog(t, e);
                } catch (Throwable ignored) {
                }
                android.util.Log.e("AICodeStudio", "FATAL crash", e);
                if (defaultHandler != null) {
                    defaultHandler.uncaughtException(t, e);
                }
            }
        });

        // 各模块独立 try-catch 隔离：任一模块失败都不影响应用启动
        try {
            AIService.getInstance();
        } catch (Throwable e) {
            android.util.Log.e("AICodeStudio", "AIService init failed", e);
        }

        try {
            LocalModelManager.getInstance(this);
        } catch (Throwable e) {
            android.util.Log.e("AICodeStudio", "LocalModelManager init failed", e);
        }

        // 引擎 .so 可能不在 APK 内，启动时去手机上的候选目录搜索并加载。
        // 放在最后，且独立 try-catch：加载失败只影响本地推理，不影响 App 启动。
        try {
            com.aicodestudio.ide.ai.engine.EngineManager.bootstrap(this);
        } catch (Throwable e) {
            android.util.Log.w("AICodeStudio", "Engine bootstrap failed", e);
        }

        // 自动加载上次使用的模型（覆盖安装 / 进程重启后无需手动重新加载）
        // 在后台线程中加载，不阻塞应用启动
        try {
            LocalModelManager.getInstance(this).autoLoadLastModel();
        } catch (Throwable e) {
            android.util.Log.w("AICodeStudio", "Auto load model failed", e);
        }

        try {
            CompileExecutor.getInstance();
        } catch (Throwable e) {
            android.util.Log.e("AICodeStudio", "CompileExecutor init failed", e);
        }
    }

    /** 有 Activity 回到前台：取消后台卸载任务；若模型已被后台卸载则自动重载；停保活服务 */
    private void onForegroundEntered() {
        startedActivityCount++;
        if (backgroundUnloadScheduled) {
            backgroundUnloadScheduled = false;
            bgHandler.removeCallbacks(bgUnloadTask);
        }
        // v1.153ae：回到前台 → 停止保活前台服务（不需要常驻通知了）
        try { StayAliveService.stop(this); } catch (Throwable ignored) {}
        try {
            LocalModelManager mgr = LocalModelManager.getInstance(this);
            if (!mgr.isModelLoaded() && mgr.hasLastModelRecord()) {
                mgr.autoLoadLastModel();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 所有 Activity 进入后台：启动保活服务 + 安排延迟卸载（30 分钟），期间回到前台则取消 */
    private void onBackgroundEntered() {
        if (startedActivityCount > 0) {
            startedActivityCount--;
        }
        if (startedActivityCount == 0) {
            // v1.153ae：全部退后台 → 起轻量前台服务保活进程（十几分钟内切回不被系统杀）
            try { StayAliveService.start(this); } catch (Throwable ignored) {}
            if (!backgroundUnloadScheduled) {
                backgroundUnloadScheduled = true;
                bgHandler.postDelayed(bgUnloadTask, BACKGROUND_UNLOAD_DELAY_MS);
            }
        }
    }

    private void writeCrashLog(Thread t, Throwable e) {
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            pw.flush();

            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(new Date());

            StringBuilder sb = new StringBuilder();
            sb.append("========== CRASH ==========\n");
            sb.append("Time  : ").append(time).append("\n");
            sb.append("Thread: ").append(t.getName()).append("\n");
            sb.append("Exception: ").append(e.getClass().getName()).append("\n");
            sb.append("Message  : ").append(e.getMessage()).append("\n");
            sb.append("----------------------------\n");
            sb.append(sw.toString());
            sb.append("\n\n");

            Throwable cause = e.getCause();
            while (cause != null) {
                sb.append("Caused by: ").append(cause.getClass().getName()).append("\n");
                sb.append("  ").append(cause.getMessage()).append("\n");
                StringWriter csw = new StringWriter();
                cause.printStackTrace(new PrintWriter(csw));
                sb.append(csw.toString()).append("\n");
                cause = cause.getCause();
            }

            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            if (!dir.exists()) dir.mkdirs();
            File logFile = new File(dir, "aicode_crash.log");

            FileOutputStream fos = new FileOutputStream(logFile, true);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.flush();
            fos.close();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        try {
            AIService.getInstance().shutdown();
        } catch (Throwable ignored) {
        }
        try {
            CompileExecutor.getInstance().shutdown();
        } catch (Throwable ignored) {
        }
    }
}
