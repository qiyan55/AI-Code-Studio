package com.aicodestudio.ide.ai.remote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RemoteConnectorService — 基于 ntfy.sh 消息中继的远程控制
 *
 * 手机长轮询 ntfy.sh 命令主题，收到命令执行后把结果 POST 回结果主题。
 * 不需要公网 IP，不需要端口映射，只要手机能上网就行。
 */
public class RemoteConnectorService extends Service {
    private static final String TAG = "RemoteConnector";
    private static final String NTFY = "https://ntfy.sh";
    // v28 全新主题名，清空所有旧消息
    private static final String TOPIC_CMD = "aics-cmd-v28-qy";
    private static final String TOPIC_RESULT = "aics-result-v28-qy";

    private static final String CHANNEL_ID = "remote_connector";
    private static final int NOTIF_ID = 7860;

    public static final String ACTION_START = "com.aicodestudio.ide.ai.remote.START";
    public static final String ACTION_STOP  = "com.aicodestudio.ide.ai.remote.STOP";

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread workerThread;
    private android.os.PowerManager.WakeLock wakeLock;
    private final java.util.Set<String> processedIds = java.util.Collections.synchronizedSet(
        new java.util.LinkedHashSet<String>());
    private static final int MAX_PROCESSED = 200;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, buildNotification("远程连接器运行中",
            "正在连接 " + NTFY));
        startWorker();
        return START_STICKY;
    }

    private void startWorker() {
        if (running.get()) return;
        running.set(true);
        // WakeLock 防止 CPU 休眠
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "AICS:RemoteConnector");
            wakeLock.acquire(24*60*60*1000L);
        } catch (Exception ignored) {}
        workerThread = new Thread(new Runnable() {
            @Override public void run() {
                Log.i(TAG, "开始长轮询 " + NTFY + "/" + TOPIC_CMD);
                while (running.get()) {
                    try {
                        // 长轮询拉取命令（超时 50 秒）
                        URL url = new URL(NTFY + "/" + TOPIC_CMD + "/json?poll=1");
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setConnectTimeout(15000);
                        conn.setReadTimeout(60000);
                        conn.setRequestMethod("GET");

                        BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), "UTF-8"));
                        String line;
                        while (running.get() && (line = reader.readLine()) != null) {
                            line = line.trim();
                            if (!line.startsWith("{")) continue;
                            try {
                                JSONObject env = new JSONObject(line);
                                // ntfy 的消息体在 message 字段
                                String msg = env.optString("message", "");
                                if (msg.isEmpty()) continue;
                                // 去重：跳过已处理的 req_id
                                String rid = "";
                                try { rid = new JSONObject(msg).optString("req_id",""); } catch (Exception ignored) {}
                                if (!rid.isEmpty()) {
                                    if (processedIds.contains(rid)) {
                                        Log.i(TAG, "跳过重复命令: " + rid);
                                        continue;
                                    }
                                    synchronized (processedIds) {
                                        processedIds.add(rid);
                                        while (processedIds.size() > MAX_PROCESSED) {
                                            String first = processedIds.iterator().next();
                                            processedIds.remove(first);
                                        }
                                    }
                                }
                                Log.i(TAG, "收到命令: " + msg);
                                String resp = processCommand(msg);
                                // POST 结果回结果主题
                                postResult(resp);
                            } catch (Exception e) {
                                Log.w(TAG, "解析命令失败", e);
                            }
                        }
                        reader.close();
                        conn.disconnect();
                    } catch (Exception e) {
                        if (running.get()) {
                            Log.w(TAG, "轮询出错,3秒后重试", e);
                            try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                        }
                    }
                }
            }
        }, "remote-worker");
        workerThread.start();
    }

    private void postResult(String result) {
        try {
            URL url = new URL(NTFY + "/" + TOPIC_RESULT);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            byte[] data = result.getBytes(StandardCharsets.UTF_8);
            conn.getOutputStream().write(data);
            conn.getOutputStream().flush();
            conn.getResponseCode();
            conn.disconnect();
        } catch (Exception e) {
            Log.w(TAG, "发送结果失败", e);
        }
    }

    String processCommand(String line) {
        JSONObject req;
        String reqId = "unknown";
        try {
            req = new JSONObject(line);
            reqId = req.optString("req_id", reqId);
            String cmd = req.optString("cmd", "");
            JSONObject payload = req.optJSONObject("payload");
            if (payload == null) payload = new JSONObject();

            JSONObject data = new JSONObject();
            String msg = "ok";
            RemoteBridge bridge = RemoteBridge.get();

            switch (cmd) {
                case "heartbeat":
                    data.put("time", System.currentTimeMillis());
                    break;
                case "status":
                    data.put("raw", bridge.getStatus());
                    break;
                case "list_files":
                    data.put("files", bridge.listFiles(payload.optString("dir", "")));
                    break;
                case "navigate":
                    data.put("result", bridge.navigate(payload.optString("target", "home")));
                    break;
                case "screenshot":
                    data.put("image_base64", bridge.screenshot());
                    break;
                case "editor_open":
                    data.put("result", bridge.openFile(payload.optString("path", "")));
                    break;
                case "editor_set":
                    bridge.setContent(payload.optString("content", ""));
                    break;
                case "editor_get":
                    data.put("content", bridge.getContent() != null ? bridge.getContent() : "");
                    break;
                case "editor_save":
                    data.put("result", bridge.save());
                    break;
                case "current_path":
                    data.put("path", bridge.getCurrentPath());
                    break;
                case "project_path":
                    data.put("path", bridge.getProjectPath());
                    break;
                case "compile": {
                    // 异步编译，立即返回，完成后主动推送结果
                    final String rid = reqId;
                    new Thread(new Runnable() {
                        @Override public void run() {
                            String log = bridge.compile();
                            try {
                                JSONObject r = new JSONObject();
                                r.put("req_id", rid);
                                r.put("code", 0);
                                r.put("msg", "compile done");
                                r.put("data", new JSONObject().put("log", log));
                                postResult(r.toString());
                            } catch (Exception ignored) {}
                        }
                    }, "compile-worker").start();
                    data.put("result", "compiling...");
                    break;
                }
                case "chat_send":
                    data.put("result", bridge.chatSend(payload.optString("prompt", "")));
                    break;
                default:
                    msg = "unknown cmd: " + cmd;
                    break;
            }

            JSONObject resp = new JSONObject();
            resp.put("req_id", reqId);
            resp.put("code", "ok".equals(msg) ? 0 : 1);
            resp.put("msg", msg);
            resp.put("data", data);
            return resp.toString();
        } catch (Exception e) {
            Log.e(TAG, "process error", e);
            try {
                JSONObject resp = new JSONObject();
                resp.put("req_id", reqId);
                resp.put("code", -1);
                resp.put("msg", e.getMessage());
                return resp.toString();
            } catch (Exception ignored) {
                return "{\"code\":-1,\"msg\":\"error\"}";
            }
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "远程连接器", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("远程连接器");
            ch.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String title, String text) {
        Intent stopIntent = new Intent(this, RemoteConnectorService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle(title).setContentText(text)
         .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
         .setOngoing(true)
         .addAction(0, "停止", stopPi);
        return b.build();
    }

    @Override public void onDestroy() {
        running.set(false);
        if (wakeLock != null && wakeLock.isHeld()) { try { wakeLock.release(); } catch (Exception ignored) {} }
        if (workerThread != null) workerThread.interrupt();
        super.onDestroy();
    }
}
