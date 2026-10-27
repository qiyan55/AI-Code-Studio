package com.aicodestudio.ide.ai.chat;

import com.aicodestudio.ide.ai.ui.UiKit;
import com.aicodestudio.ide.ai.ui.Theme;
import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.aicodestudio.ide.R;
import com.aicodestudio.ide.ai.models.LocalModelManager;
import com.aicodestudio.ide.ai.models.ModelManageDialog;
import com.aicodestudio.ide.ai.service.SettingsActivity;
import com.aicodestudio.ide.ai.ui.AppleDialog;
import com.aicodestudio.ide.ai.voice.VoiceInputManager;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 对话界面 Activity（纯 Android SDK 实现，零 AndroidX 依赖）
 *
 * 改造记录：
 * 1. 移除 androidx.recyclerview.widget.RecyclerView —— AndroidX 库的 aar 资源未合并进
 *    本 APK，运行时 inflate 会因资源 ID 不匹配而崩溃。改用 ScrollView + LinearLayout
 *    动态构建消息，纯原生实现，彻底规避该问题。
 * 2. onCreate 各步骤独立 try-catch + 控件 null 保护：任一环节异常都保证界面可见。
 */
public class AIChatActivity extends Activity {

    private ScrollView scrollMessages;
    private LinearLayout containerMessages;
    private EditText etMessageInput;
    private ImageButton btnSend;
    private Button btnSendSelection;
    private Button btnClearChat;
    private TextView tvModeIndicator;

    private List<ChatMessageUI> messages;
    private Handler mainHandler;
    private AIChatManager chatManager;

    // ===== 语音输入（sherpa-onnx 离线流式，按需加载释放）=====
    private static final int REQ_RECORD_AUDIO = 4001;
    private VoiceInputManager voiceInput;
    private android.app.AlertDialog voiceDialog;
    private TextView voiceResultText;
    private String voiceFinalText = "";
    private boolean voiceFilled = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_chat);

        mainHandler = new Handler(Looper.getMainLooper());
        messages = new ArrayList<ChatMessageUI>();

        try {
            chatManager = AIChatManager.getInstance();
            // v1.49：AIChatActivity 不经过 ChatPanel，主动恢复 App 上下文
            // （保证场景A/B 的工作目录解析正确：sCtx/appCtx 兜底到全局工作空间）
            chatManager.attachContext(this);
        } catch (Throwable e) {
            android.util.Log.e("AIChatActivity", "chatManager init failed", e);
        }

        try {
            initViews();
        } catch (Throwable e) {
            android.util.Log.e("AIChatActivity", "initViews failed", e);
        }

        try {
            setupListeners();
        } catch (Throwable e) {
            android.util.Log.e("AIChatActivity", "setupListeners failed", e);
        }

        try {
            updateModeIndicator();
        } catch (Throwable e) {
            android.util.Log.e("AIChatActivity", "updateModeIndicator failed", e);
        }

        // 添加欢迎消息
        if (containerMessages != null && messages.isEmpty()) {
            addMessage(new ChatMessageUI(
                "你好！我是 AI Code Studio 的 AI 助手。\n\n我可以帮你：\n• 编写代码片段\n• 配置编译工具链\n• 修复编译错误\n• 分析项目结构\n\n请输入你的需求开始对话。",
                ChatMessageUI.TYPE_AI
            ));
        }
    }

    private void initViews() {
        scrollMessages = (ScrollView) findViewById(R.id.scroll_messages);
        containerMessages = (LinearLayout) findViewById(R.id.container_messages);
        etMessageInput = (EditText) findViewById(R.id.et_message_input);
        btnSend = (ImageButton) findViewById(R.id.btn_send);
        btnSendSelection = (Button) findViewById(R.id.btn_send_selection);
        btnClearChat = (Button) findViewById(R.id.btn_clear_chat);
        tvModeIndicator = (TextView) findViewById(R.id.tv_mode_indicator);
    }

    /**
     * 动态构建一条消息视图并加入容器
     */
    private void addMessage(ChatMessageUI msg) {
        if (containerMessages == null) return;
        messages.add(msg);
        containerMessages.addView(buildMessageView(msg));
        scrollToBottom();
    }

    private View buildMessageView(ChatMessageUI msg) {
        LinearLayout row = new LinearLayout(this);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 6, 0, 6);

        // AI 消息左侧加一个小圆点指示器
        if (msg.type == ChatMessageUI.TYPE_AI
                || msg.type == ChatMessageUI.TYPE_AI_LOADING
                || msg.type == ChatMessageUI.TYPE_AI_ERROR) {

            View dot = new View(this);
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(16, 16);
            dp.gravity = Gravity.TOP;
            dp.setMargins(0, 12, 12, 0);
            dot.setLayoutParams(dp);
            dot.setBackgroundResource(R.drawable.bg_send_button);
            row.addView(dot);
        }

        TextView tv = new TextView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        // 用户气泡按内容自适应；AI 输出占满宽度
        if (msg.type == ChatMessageUI.TYPE_USER) {
            lp.gravity = Gravity.END;
            lp.width = LinearLayout.LayoutParams.WRAP_CONTENT;
        } else {
            lp.gravity = Gravity.START;
            lp.width = LinearLayout.LayoutParams.MATCH_PARENT;
        }
        tv.setLayoutParams(lp);
        tv.setText(msg.text);
        tv.setTextSize(14);
        tv.setLineSpacing(4f, 1f);
        if (msg.type == ChatMessageUI.TYPE_USER) {
            // 用户气泡：上限屏幕 78%，避免短句也占满
            tv.setMaxWidth((int) (UiKit.screenWidth(this) * Theme.BUBBLE_MAX_W_RATIO));
        } else {
            // AI 输出：占满可用宽度
            tv.setMaxWidth(Integer.MAX_VALUE);
        }
        tv.setPadding(24, 20, 24, 20);

        if (msg.type == ChatMessageUI.TYPE_USER) {
            tv.setBackgroundResource(R.drawable.bg_bubble_user);
            // 气泡已从蓝实底改为绿描边半透明，文字同步改为主文字色
            tv.setTextColor(Theme.TEXT_PRIMARY);
        } else if (msg.type == ChatMessageUI.TYPE_AI_ERROR) {
            tv.setBackgroundResource(R.drawable.bg_bubble_ai);
            tv.setTextColor(Theme.CLOSE_RED);
        } else {
            tv.setBackgroundResource(R.drawable.bg_bubble_ai);
            tv.setTextColor(Theme.TEXT_PRIMARY);
        }

        row.addView(tv);
        return row;
    }

    private void scrollToBottom() {
        if (scrollMessages == null) return;
        scrollMessages.post(new Runnable() {
            @Override
            public void run() {
                scrollMessages.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private void removeLastMessage() {
        if (containerMessages == null || messages.isEmpty()) return;
        containerMessages.removeViewAt(containerMessages.getChildCount() - 1);
        messages.remove(messages.size() - 1);
    }

    private void setupListeners() {
        if (etMessageInput != null) {
            // 长按输入框 → 离线语音输入（sherpa-onnx 流式）
            etMessageInput.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    if (checkRecordPermission()) showVoiceInputDialog();
                    return true; // 拦截系统文本选择菜单
                }
            });
        }
        if (btnSend != null && etMessageInput != null) {
            btnSend.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String text = etMessageInput.getText().toString().trim();
                    if (text.isEmpty()) return;

                    addMessage(new ChatMessageUI(text, ChatMessageUI.TYPE_USER));
                    etMessageInput.setText("");

                    if (chatManager == null) {
                        addMessage(new ChatMessageUI("AI 服务未初始化完成，请重启应用。",
                                ChatMessageUI.TYPE_AI_ERROR));
                        return;
                    }

                    final ChatMessageUI loading =
                            new ChatMessageUI("正在思考...", ChatMessageUI.TYPE_AI_LOADING);
                    addMessage(loading);

                    // v1.49：发送前同步 API 配置（与 ChatPanel 一致，否则云端模式用默认地址发不出去）
                    try {
                        com.aicodestudio.ide.ai.core.AppConfig cfg =
                                com.aicodestudio.ide.ai.core.AppConfig.getInstance(AIChatActivity.this);
                        chatManager.setApiKey(cfg.getApiKey());
                        chatManager.setApiBaseUrl(cfg.getApiBase());
                        chatManager.setModelName(cfg.getModelName());
                        chatManager.setMode(cfg.getModelMode() == com.aicodestudio.ide.ai.core.AppConfig.MODE_LOCAL
                                ? AIChatManager.MODE_LOCAL : AIChatManager.MODE_CLOUD);
                    } catch (Throwable ignored) {
                    }

                    chatManager.setResponseListener(new AIChatManager.AIResponseListener() {
                        @Override
                        public void onResponseStart() {
                        }

                        @Override
                        public void onResponseChunk(String chunk) {
                        }

                        @Override
                        public void onResponseComplete(final String fullResponse) {
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    removeLastMessage();
                                    addMessage(new ChatMessageUI(fullResponse,
                                            ChatMessageUI.TYPE_AI));
                                }
                            });
                        }

                        @Override
                        public void onError(final String errorMsg) {
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    removeLastMessage();
                                    addMessage(new ChatMessageUI("错误: " + errorMsg,
                                            ChatMessageUI.TYPE_AI_ERROR));
                                }
                            });
                        }

                        @Override
                        public void onToolStart(com.aicodestudio.ide.ai.agent.StreamingToolDetector.ToolBlock block) {}

                        @Override
                        public void onToolUpdate(String content) {}

                        @Override
                        public void onToolEnd(com.aicodestudio.ide.ai.agent.StreamingToolDetector.ToolBlock block,
                                              boolean ok, String summary) {}
                    });

                    chatManager.sendMessage(text);
                }
            });
        }

        if (btnSendSelection != null) {
            btnSendSelection.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent src = getIntent();
                    String selectedCode = src == null ? null : src.getStringExtra("selected_code");
                    if (selectedCode != null && !selectedCode.isEmpty()) {
                        etMessageInput.setText("请分析以下代码并给出改进建议：\n```\n"
                                + selectedCode + "\n```");
                    } else {
                        Toast.makeText(AIChatActivity.this, "请先在编辑器中选择代码",
                                Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        if (btnClearChat != null) {
            btnClearChat.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (containerMessages != null) containerMessages.removeAllViews();
                    messages.clear();
                    if (chatManager != null) chatManager.clearHistory();
                    addMessage(new ChatMessageUI("对话已清空，请输入新需求。", ChatMessageUI.TYPE_AI));
                }
            });
        }
    }

    // ================= 语音输入（长按输入框触发，离线流式）=================

    private boolean checkRecordPermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) return true;
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showVoiceInputDialog();
            } else {
                Toast.makeText(this, "需要录音权限才能使用语音输入", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** 语音输入面板：按住说话 → 实时识别 → 填入输入框 */
    private void showVoiceInputDialog() {
        if (voiceDialog != null && voiceDialog.isShowing()) return;
        final int dp = UiKit.dp(this, 1);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp * 14, dp * 6, dp * 14, dp * 6);

        // 识别结果实时显示区
        voiceResultText = new TextView(this);
        voiceResultText.setText("按住下方按钮开始说话");
        voiceResultText.setTextSize(15);
        voiceResultText.setTextColor(Theme.TEXT_PRIMARY);
        voiceResultText.setLineSpacing(dp * 3, 1.0f);
        voiceResultText.setMinHeight(dp * 56);
        voiceResultText.setGravity(Gravity.CENTER_VERTICAL);
        voiceResultText.setBackgroundColor(Theme.BG_SECONDARY);
        voiceResultText.setPadding(dp * 10, dp * 8, dp * 10, dp * 8);
        box.addView(voiceResultText);

        box.addView(gap(dp * 12));

        // 按住说话按钮
        final Button holdBtn = new Button(this);
        holdBtn.setText("按住说话");
        holdBtn.setTextSize(15);
        holdBtn.setAllCaps(false);
        holdBtn.setTextColor(Color.WHITE);
        android.graphics.drawable.GradientDrawable holdBg =
                new android.graphics.drawable.GradientDrawable();
        holdBg.setColor(Color.parseColor("#3B82F6"));
        holdBg.setCornerRadius(UiKit.dpF(this, dp * 22));
        holdBtn.setBackgroundDrawable(holdBg);
        holdBtn.setMinHeight(dp * 44);
        holdBtn.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startVoiceRecognition();
                        holdBtn.setText("松开发送文字");
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (voiceInput != null) voiceInput.stop();
                        holdBtn.setText("按住说话");
                        return true;
                }
                return false;
            }
        });
        box.addView(holdBtn);

        // 底部操作：填入 / 取消
        LinearLayout ops = new LinearLayout(this);
        ops.setOrientation(LinearLayout.HORIZONTAL);
        ops.setGravity(Gravity.CENTER);
        ops.setPadding(0, dp * 12, 0, 0);

        Button btnCancel = new Button(this);
        btnCancel.setText("取消");
        btnCancel.setTextSize(13);
        btnCancel.setAllCaps(false);
        btnCancel.setTextColor(Theme.TEXT_SECOND);
        btnCancel.setBackgroundDrawable(bordered(Theme.BG_SECONDARY, AppleDialog.BORDER, dp * 18));
        btnCancel.setPadding(dp * 22, dp * 6, dp * 22, dp * 6);
        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (voiceInput != null) voiceInput.cancel();
                if (voiceDialog != null) voiceDialog.dismiss();
            }
        });
        ops.addView(btnCancel);

        ops.addView(gap(dp * 16));

        final Button btnFill = new Button(this);
        btnFill.setText("填入");
        btnFill.setTextSize(13);
        btnFill.setAllCaps(false);
        btnFill.setTextColor(Color.WHITE);
        android.graphics.drawable.GradientDrawable fillBg =
                new android.graphics.drawable.GradientDrawable();
        fillBg.setColor(Color.parseColor("#22C55E"));
        fillBg.setCornerRadius(UiKit.dpF(this, dp * 18));
        btnFill.setBackgroundDrawable(fillBg);
        btnFill.setPadding(dp * 26, dp * 6, dp * 26, dp * 6);
        btnFill.setEnabled(false);
        btnFill.setAlpha(0.5f);
        btnFill.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (voiceFinalText != null && voiceFinalText.length() > 0) {
                    insertIntoInput(voiceFinalText);
                }
                voiceFilled = true;
                if (voiceDialog != null) voiceDialog.dismiss();
            }
        });
        ops.addView(btnFill);

        box.addView(ops);

        voiceDialog = new AppleDialog.Builder(this)
                .setTitle("语音输入（离线）")
                .setView(box)
                .create();
        voiceDialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface d) {
                if (voiceInput != null) {
                    voiceInput.cancel();
                    voiceInput = null;
                }
                voiceDialog = null;
            }
        });
        voiceDialog.show();
    }

    private void startVoiceRecognition() {
        voiceFinalText = "";
        voiceFilled = false;
        if (voiceResultText != null) voiceResultText.setText("正在聆听...");
        if (voiceInput == null) voiceInput = new VoiceInputManager();
        try {
            voiceInput.start(this, new VoiceInputManager.Listener() {
                @Override public void onPartial(String text) {
                    if (voiceResultText != null && text != null && text.length() > 0) {
                        voiceResultText.setText(text);
                    }
                }

                @Override public void onFinal(String text) {
                    if (text != null && text.length() > 0) {
                        voiceFinalText = text;
                        if (voiceResultText != null) voiceResultText.setText(text);
                        // 自动填入（若用户已松开），并允许再次按住补充
                    }
                }

                @Override public void onError(String msg) {
                    if (voiceResultText != null) voiceResultText.setText("识别失败：" + msg);
                }
            });
        } catch (Throwable t) {
            if (voiceResultText != null) voiceResultText.setText("语音启动失败：" + t.getMessage());
        }
    }

    /** 把文字插入输入框光标处 */
    private void insertIntoInput(String text) {
        if (etMessageInput == null || text == null || text.length() == 0) return;
        int idx = etMessageInput.getSelectionStart();
        if (idx < 0) idx = etMessageInput.length();
        String cur = etMessageInput.getText().toString();
        String nv = cur.substring(0, idx) + text + cur.substring(idx);
        etMessageInput.setText(nv);
        etMessageInput.setSelection(idx + text.length());
    }

    private View gap(int h) {
        View v = new View(this);
        v.setLayoutParams(new ViewGroup.LayoutParams(1, h));
        return v;
    }

    private android.graphics.drawable.Drawable bordered(int fill, int stroke, int radius) {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(UiKit.dpF(this, radius));
        g.setStroke(Math.max(1, UiKit.dp(this, 1)), stroke);
        return g;
    }

    // v1.144：退出聊天页时终止正在进行的生成（本地推理/云端 SSE），避免后台继续跑导致发热
    @Override
    public void onBackPressed() {
        if (chatManager != null) {
            try { chatManager.stopGeneration(); } catch (Throwable ignored) {}
            try { chatManager.saveSessionKv(); } catch (Throwable ignored) {} // v1.150：返回主界面/离开前保存当前对话KV
        }
        super.onBackPressed();
    }

    // v1.150：离开对话页（切后台/进设置/回主界面/跳其他页）统一保存当前对话 KV，
    // 再进入该对话时由 openConversation→switchConversation→loadSessionKv 恢复增量续跑
    @Override
    protected void onPause() {
        super.onPause();
        if (chatManager != null) {
            try { chatManager.stopGeneration(); } catch (Throwable ignored) {}
            try { chatManager.saveSessionKv(); } catch (Throwable ignored) {}
        }
    }

    private void updateModeIndicator() {
        if (tvModeIndicator == null) return;
        try {
            LocalModelManager modelMgr = LocalModelManager.getInstance(this);
            if (modelMgr.hasLoadedModel()) {
                tvModeIndicator.setText("本地");
                tvModeIndicator.setTextColor(Theme.FILE_GRADLE);
            } else {
                tvModeIndicator.setText("云端");
                tvModeIndicator.setTextColor(Theme.ACCENT_BLUE);
            }
        } catch (Throwable e) {
            tvModeIndicator.setText("云端");
        }
    }

    // ===== 消息数据模型 =====
    public static class ChatMessageUI {
        public static final int TYPE_USER = 0;
        public static final int TYPE_AI = 1;
        public static final int TYPE_AI_LOADING = 2;
        public static final int TYPE_AI_ERROR = 3;

        public String text;
        public int type;

        public ChatMessageUI(String text, int type) {
            this.text = text;
            this.type = type;
        }
    }
}
