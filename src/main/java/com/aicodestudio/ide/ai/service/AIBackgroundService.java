package com.aicodestudio.ide.ai.service;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;
import com.aicodestudio.ide.ai.compile.CompileExecutor;
import com.aicodestudio.ide.ai.models.LocalModelManager;

/**
 * AI 后台服务 - 保持AI模型和编译任务在后台运行
 * 文档: AI服务后台化
 */
public class AIBackgroundService extends Service {

    private static final String TAG = "AIBackgroundService";

    public static final String ACTION_START = "action_start";
    public static final String ACTION_STOP = "action_stop";
    public static final String ACTION_KEEP_MODEL = "action_keep_model";

    private LocalModelManager modelMgr;
    private AIService aiService;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "AI Background Service created");
        modelMgr = LocalModelManager.getInstance(this);
        aiService = AIService.getInstance();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        String action = intent.getAction();
        if (ACTION_START.equals(action)) {
            Log.d(TAG, "AI service starting...");
            CompileExecutor compileExec = CompileExecutor.getInstance();
            setupAIServiceListeners();
        } else if (ACTION_STOP.equals(action)) {
            Log.d(TAG, "AI service stopping...");
            stopSelf();
        } else if (ACTION_KEEP_MODEL.equals(action)) {
            Log.d(TAG, "Keeping model loaded in background");
        }

        return START_STICKY;
    }

    private void setupAIServiceListeners() {
        aiService.addListener(new AIService.AIServiceListener() {
            @Override
            public void onAutoFixProposed(String originalCode, String fixedCode, String explanation) {
                Log.d(TAG, "Auto-fix proposed: " + explanation);
                Intent broadcast = new Intent("com.aicodestudio.ide.ai.FIX_PROPOSED");
                broadcast.putExtra("explanation", explanation);
                sendBroadcast(broadcast);
            }

            @Override
            public void onCompileErrorAutoPushed(String log) {
                Log.d(TAG, "Compile error auto-pushed to AI");
                Intent broadcast = new Intent("com.aicodestudio.ide.ai.COMPILE_ERROR");
                broadcast.putExtra("log", log);
                sendBroadcast(broadcast);
            }

            @Override
            public void onToolAutoCreated(String toolConfig) {
                Log.d(TAG, "AI auto-created tool: " + toolConfig);
                Intent broadcast = new Intent("com.aicodestudio.ide.ai.TOOL_CREATED");
                broadcast.putExtra("config", toolConfig);
                sendBroadcast(broadcast);
            }

            @Override
            public void onStatusChanged(String status) {
                Log.d(TAG, "AI status: " + status);
            }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "AI Background Service destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
