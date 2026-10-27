package com.aicodestudio.ide.ai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 轻量前台保活服务：App 退到后台时启动，回前台时停止。
 *
 * 作用：让系统把本进程视为"有前台服务"，短时间（十几分钟到半小时）切回时
 * 不被回收；进程存活意味着聊天界面/历史秒回，无需重开。
 * 30 分钟后仍在后台则由 AICodeStudioApp 主动卸载模型释放内存（本服务不推理）。
 *
 * 通知：低优先级、不可滑动取消；Android 13+ 无通知权限时通知不展示，
 * 但服务仍保持前台优先级（保活效果不受影响）。
 */
public class StayAliveService extends Service {

    private static final String CHANNEL_ID = "stay_alive";
    private static final int NOTI_ID = 4101;

    public static void start(Context ctx) {
        if (ctx == null) return;
        try {
            Intent it = new Intent(ctx, StayAliveService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(it);
            } else {
                ctx.startService(it);
            }
        } catch (Throwable ignored) {}
    }

    public static void stop(Context ctx) {
        if (ctx == null) return;
        try {
            ctx.stopService(new Intent(ctx, StayAliveService.class));
        } catch (Throwable ignored) {}
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundInner();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundInner();
        return START_NOT_STICKY; // 被杀后不自动重建（回前台时 App 会重新拉起）
    }

    private void startForegroundInner() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                        "保持运行", NotificationManager.IMPORTANCE_MIN);
                ch.setDescription("切后台时保持会话状态，回到 App 继续对话");
                ch.setShowBadge(false);
                if (nm != null) nm.createNotificationChannel(ch);
            }
            Notification n;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                n = new Notification.Builder(this, CHANNEL_ID)
                        .setContentTitle("AI Code Studio")
                        .setContentText("保持会话中，回到 App 继续")
                        .setSmallIcon(android.R.drawable.ic_menu_compass)
                        .setOngoing(true)
                        .setPriority(Notification.PRIORITY_MIN)
                        .build();
            } else {
                n = new Notification.Builder(this)
                        .setContentTitle("AI Code Studio")
                        .setContentText("保持会话中")
                        .setSmallIcon(android.R.drawable.ic_menu_compass)
                        .setOngoing(true)
                        .setPriority(Notification.PRIORITY_MIN)
                        .build();
            }
            startForeground(NOTI_ID, n);
        } catch (Throwable t) {
            // 某些 ROM 限制前台服务，失败不崩溃
            android.util.Log.w("StayAlive", "startForeground failed: " + t.getMessage());
        }
    }
}
