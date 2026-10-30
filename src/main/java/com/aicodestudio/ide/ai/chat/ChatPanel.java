package com.aicodestudio.ide.ai.chat;

import com.aicodestudio.ide.ai.ui.Theme;
import com.aicodestudio.ide.ai.ui.UiKit;
import com.aicodestudio.ide.ai.voice.VoiceEngine;
import com.aicodestudio.ide.ai.voice.VoiceInputManager;
import com.aicodestudio.ide.ai.voice.VoiceLog;
import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.Spanned;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.webkit.WebView;
import android.webkit.WebSettings;

import com.aicodestudio.ide.ai.core.AppConfig;
import com.aicodestudio.ide.ai.core.WorkspaceState;
import com.aicodestudio.ide.ai.models.LocalModelManager;
import java.io.File;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 聊天对话面板组件
 * 实现文档 5.2 页面1-区域4 右侧上方 AI 聊天面板：
 * - 聊天消息列表
 * - 底部输入框 + 发送按钮
 * - 快捷按钮：将选中代码发送 AI
 *
 * 文档 5.3 UI交互规则：
 * - 规则2：AI自动创建编译工具完成后，在聊天面板推送通知消息
 * - 规则4：自动发送错误给AI时，聊天面板展示系统提示
 */
public class ChatPanel extends LinearLayout {

    /** 代码框状态：保存 WebView 引用、当前代码内容、页面加载状态 */
    private static class CodeBoxTag {
        WebView webView;
        TextView degradedTv;      // 降级后的纯文本 TextView（webView 被回收销毁后使用）
        boolean degraded;         // 是否已降级（WebView 已销毁，防止长时间对话累积 WebView 进程导致发热）
        LinearLayout contentContainer; // 代码内容容器（WebView/降级 TextView 的直接父容器）
        String currentCode;
        String currentLang;       // 当前 WebView 中设置的语言（用于检测语言变化后重新设置）
        boolean pageLoaded;       // 页面是否加载完成
        String pendingCode;       // 页面加载完成前待设置的代码
        CodeBoxTag(WebView webView, String currentCode) {
            this.webView = webView;
            this.currentCode = currentCode;
            this.currentLang = null;
            this.pageLoaded = false;
            this.pendingCode = null;
            this.degraded = false;
            this.degradedTv = null;
            this.contentContainer = null;
        }
    }

    private ScrollView scrollView;
    private LinearLayout messageContainer;
    private EditText etInput;
    private ImageButton btnSend;
    // v1.137：发送/停止二合一按钮——生成中变红色圆底白色圆角方形停止按钮，外圈描边旋转
    private android.widget.FrameLayout sendWrap;          // 按钮容器（叠旋转环）
    private android.view.View spinRingView;               // 旋转描边圆环
    private android.view.animation.RotateAnimation spinAnim;
    private TextView tvMode;
    private TextView tvRawToggle; // 原始消息开关按钮
    private TextView tvChatMode;  // v1.135：闲聊 / 工作任务切换按钮
    private boolean showRawMessage = false; // 是否显示原始消息（关闭所有 markdown 和代码框渲染）
    private View activeStreamingRow = null; // v1.136：正在流式输出的消息行，刷新原始/渲染时跳过，防清空丢内容
    private static final String TOOL_CARD_TAG = "ai_code_tool_card"; // v1.136：工具卡片标识，重建时保留
    private static final String STREAM_CARD_TAG = "ai_code_stream_card"; // v1.142：流式卡片统一标识（思考块/工具卡片），切换可见性统一管理
    private static final String RAW_TV_TAG = "ai_code_raw_tv";       // v1.136：原始模式文本视图标识
    private static final String REGEN_BTN_TAG = "ai_code_regen_btn"; // v1.146：重新生成按钮标识（防重复添加）
    // v1.136：当前流式消息的工具卡片按创建顺序存于列表，切换渲染/原始时按序恢复，不丢不漏
    private final java.util.List<BaseStreamCard> streamingToolCards = new java.util.ArrayList<>(); // v1.149 P1：卡片体系统一列表
    // v1.136：流式增量渲染状态（事件驱动），切换原始/渲染重建后重置，防新 token 写到已删除 View
    private final TextView[] thinkTvArr = {null};
    // v1.153ay：思考卡片内用户是否手动上滑（true=用户在看前面内容，暂停自动滚动跟随；
    // 滚到底松手后自动恢复跟随）。与卡片"先内滚、滚到底外层消息接管"的嵌套滚动时序配合。
    private final boolean[] thinkCardUserScrolled = {false};
    private final TextView[] textViewArr = {null};
    private final StringBuilder[] textBufArr = {new StringBuilder()};
    private final View[] codeViewArr = {null};
    private final StringBuilder[] codeBufArr = {new StringBuilder()};
    private final String[] codeLangArr = {""};
    private final boolean[] shellBlockArr = {false};
    // v1.152：卡片延时创建缓冲——先攒 N 个字符，确认有实际内容产出才建卡片（防空 think/空标签闪烁）
    private final StringBuilder thinkPendingBuf = new StringBuilder();
    private static final int THINK_CREATE_MIN_CHARS = 4;
    private final StringBuilder codePendingBuf = new StringBuilder();
    private static final int CODE_CREATE_MIN_CHARS = 2;

    // v1.153i：三点依次跳动动画
    private final android.os.Handler thinkingHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private android.widget.LinearLayout thinkingDotsLayout = null;
    private View[] thinkingDots = new View[3];
    private int thinkingDotIndex = 0;
    private final Runnable thinkingDotsRunnable = new Runnable() {
        @Override public void run() {
            if (thinkingDotsLayout == null) return;
            int prev = (thinkingDotIndex + 2) % 3;
            thinkingDots[prev].animate().translationY(0).scaleX(1f).scaleY(1f).setDuration(150).start();
            thinkingDots[thinkingDotIndex].animate().translationY(-dp(6)).scaleX(1.15f).scaleY(1.15f).setDuration(150).start();
            thinkingDotIndex = (thinkingDotIndex + 1) % 3;
            thinkingHandler.postDelayed(this, 250);
        }
    };

    /** 启动三点动画——动画 TextView 作为卡片列表(content)第一个 View */
    /** 停止三点动画，移除 dotsLayout，恢复气泡背景 */
    private void stopThinkingDots() {
        thinkingHandler.removeCallbacks(thinkingDotsRunnable);
        if (thinkingDotsLayout != null) {
            try {
                android.view.ViewParent p = thinkingDotsLayout.getParent();
                if (p instanceof android.widget.LinearLayout) {
                    android.widget.LinearLayout content = (android.widget.LinearLayout) p;
                    content.removeView(thinkingDotsLayout);
                    android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                    bg.setColor(0xFF1E1B2E);
                    bg.setCornerRadius(dp(14));
                    content.setBackground(bg);
                    content.setPadding(dp(12), dp(9), dp(12), dp(9));
                }
            } catch (Throwable ignored) {}
        }
        thinkingDotsLayout = null;
    }

    /** 从消息行取出正文 TextView（"正在思考"文本所在） */
    private android.widget.TextView extractContentTv(View row) {
        try {
            Object tag = row.getTag();
            if (tag instanceof ToolHostTag) tag = ((ToolHostTag) tag).content;
            if (tag instanceof android.widget.TextView) return (android.widget.TextView) tag;
            if (tag instanceof LinearLayout) {
                LinearLayout c = (LinearLayout) tag;
                if (c.getChildCount() > 0 && c.getChildAt(0) instanceof android.widget.TextView)
                    return (android.widget.TextView) c.getChildAt(0);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ================= v1.149 P1：卡片抽象体系 =================
    // 思考卡片/工具卡片/代码卡片统一为 BaseStreamCard 子类：
    //   - 统一生命周期（创建/追加/可见性/释放），新增卡片类型只需新增子类，不改主渲染流程
    //   - cardType：1=思考 2=工具 3=代码
    private abstract class BaseStreamCard {
        final String cardId;
        final int cardType;
        View rootView;
        BaseStreamCard(String cardId, int cardType) {
            this.cardId = cardId;
            this.cardType = cardType;
        }
        void setVisible(boolean v) {
            if (rootView != null) rootView.setVisibility(v ? View.VISIBLE : View.GONE);
        }
        void release() { rootView = null; }
        /** 追加流式内容（卡片内部实现） */
        abstract void appendContent(String text);
    }

    /** 思考卡片：折叠标题栏 + 内容 TextView（createThinkBlock 渲染） */
    private class ThinkStreamCard extends BaseStreamCard {
        TextView contentTv;
        ThinkStreamCard() { super("think_" + (++streamCardSeq), 1); }
        void build() {
            rootView = createThinkBlock("");
            if (rootView instanceof android.view.ViewGroup) {
                contentTv = findThinkContentTv((android.view.ViewGroup) rootView);
            }
        }
        @Override void appendContent(String text) {
            if (contentTv != null) {
                contentTv.setText(contentTv.getText() + text);
            }
        }
    }

    /** 工具卡片：读/写/执行 卡片（现有 addToolCard 渲染） */
    private class ToolStreamCard extends BaseStreamCard {
        ToolStreamCard() { super("tool_" + (++streamCardSeq), 2); }
        @Override void appendContent(String text) { /* 工具内容更新走 activeToolCard 机制 */ }
    }

    /** 代码卡片：WebView 高亮代码块（createCodeBlock 渲染） */
    private class CodeStreamCard extends BaseStreamCard {
        String lang = "";
        CodeStreamCard() { super("code_" + (++streamCardSeq), 3); }
        void build(String lang) {
            this.lang = lang == null ? "" : lang;
            rootView = createCodeBlock("", this.lang, false);
        }
        @Override void appendContent(String text) {
            // 内容通过 codeBufArr + updateCodeBlockContent 增量更新（保持现有机制）
        }
    }
    private long streamCardSeq = 0; // 卡片序列号，保证 cardId 全局唯一

    /** v1.149 P1：统一翻转当前流式所有卡片的可见性（供后续扩展/批量管理） */
    private void setAllStreamCardsVisible(boolean v) {
        for (BaseStreamCard c : streamingToolCards) {
            try { c.setVisible(v); } catch (Throwable ignored) {}
        }
    }

    private void resetStreamingState() {
        thinkTvArr[0] = null;
        textViewArr[0] = null;
        textBufArr[0].setLength(0);
        codeViewArr[0] = null;
        codeBufArr[0].setLength(0);
        codeLangArr[0] = "";
        shellBlockArr[0] = false;
        // v1.149 P1：释放所有卡片对象（View 由父容器统一清理，这里只清引用防泄漏）
        for (BaseStreamCard c : streamingToolCards) {
            try { c.release(); } catch (Throwable ignored) {}
        }
        streamingToolCards.clear(); // v1.136：新一轮生成开始，清空工具卡片列表
    }

    // ===== 语音输入（长按输入框即听，sherpa-onnx 常驻引擎）=====
    public static final int REQ_RECORD_AUDIO = 4101;
    private VoiceInputManager voiceInput;
    private TextView voiceStatusText; // 输入区上方引擎状态条
    private boolean voiceListening = false;   // 是否正在聆听
    private Runnable longPressRunnable;       // 长按计时
    private Runnable pendingVoiceStart;       // 权限授权后待执行的开始回调
    private float voiceStartRawY;             // 触发聆听时手指 Y（上滑取消判断）
    // 空输入框长按时阻止系统粘贴/插入菜单（有文字时恢复系统菜单）
    private static final android.view.ActionMode.Callback EMPTY_INSERTION_CB =
            new android.view.ActionMode.Callback() {
                @Override public boolean onCreateActionMode(android.view.ActionMode mode, android.view.Menu menu) { return false; }
                @Override public boolean onPrepareActionMode(android.view.ActionMode mode, android.view.Menu menu) { return false; }
                @Override public boolean onActionItemClicked(android.view.ActionMode mode, android.view.MenuItem item) { return false; }
                @Override public void onDestroyActionMode(android.view.ActionMode mode) {}
            };
    // 豆包式语音遮罩面板（覆盖输入区，不参与外层 LinearLayout 布局）
    private FrameLayout inputArea;            // 输入区容器（FrameLayout，遮罩覆盖输入框）
    private FrameLayout voiceOverlay;
    private TextView overlayText;
    private View ripple1, ripple2;
    private android.animation.ObjectAnimator rippleAnim1a, rippleAnim1b, rippleAnim2a, rippleAnim2b;
    private android.animation.ObjectAnimator rippleAnimA1, rippleAnimA2; // alpha 渐隐动画（同样要 cancel，防残留空转发热）

    // 滚动控制 + 悬浮按钮
    private android.widget.FrameLayout scrollContainer;
    private TextView btnScrollDown;      // 悬浮向下箭头按钮
    private boolean programScroll = false; // v1.138：程序平滑滚动中（点箭头），checkScrollPosition 不干预
    private boolean userScrolledUp = false;  // 用户是否上滑了（暂停自动滚动）
    private boolean isAiGenerating = false;  // AI 是否正在生成回复
    private volatile boolean generationStopped = false; // v1.146：用户点停止后立即丢弃后续流式增量，UI 冻结

    private AIChatManager chatManager;
    private Handler mainHandler;
    private ChatPanelListener listener;

    // ===== 安全的 WebView 池 =====
    // 只预创建、不回收（避免错误回收正在使用的 WebView 导致代码被刷没的 bug）
    // 池中的 WebView 都是预创建的、加载好基础 HTML 的、从未被使用过的
    // 池用完后创建新的 WebView
    // 目的：避免新代码框创建时调用 loadDataWithBaseURL 触发 Chromium 全局操作导致已有 WebView 闪烁
    private static final java.util.List<WebView> sWebViewPool = new java.util.ArrayList<>();
    private static final int WEBVIEW_POOL_SIZE = 4; // v1.124: 8→4，降低低端设备内存压力
    private static boolean sPoolInitialized = false;

    /** 活跃代码框数量上限：超过后把最旧的代码框降级为纯文本并销毁其 WebView，
     *  防止长对话累积大量 WebView 独立渲染进程导致内存膨胀和发热 */
    private static final int MAX_ACTIVE_WEBVIEWS = 10;
    /** 活跃代码框创建顺序队列（队首最旧），用于降级回收 */
    private static final java.util.LinkedList<CodeBoxTag> sCodeBoxOrder = new java.util.LinkedList<>();

    // v1.91：工具调用卡片即时渲染（读=书页 / 写=铅笔 / 执行=尖括号斜杠）
    private LinearLayout currentToolHost;                      // 当前流式 AI 消息的工具卡片容器
    private LinearLayout activeToolCard;                       // 当前活动工具卡片（执行中）
    private android.animation.ObjectAnimator activeToolAnimator; // 当前卡片呼吸动画
    private android.view.View activeCodeView;                 // 当前工具卡片的代码框（createCodeBlock 返回容器）
    private String activeCodeLang;                             // 当前工具卡片代码框的语言标签
    private final StringBuilder currentToolContent = new StringBuilder(); // 当前写文件内容累积
    // 流式工具卡片内容节流：onToolUpdate 高频触发，先累积增量，由 handler 每 ~100ms
    // 合并 flush 一次，避免每个 token 都构造全量字符串 + 走一次 WebView JS 桥（大文件 O(n²)）。
    private final StringBuilder pendingToolDelta = new StringBuilder();
    private final android.os.Handler toolUpdateHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable toolUpdateFlusher;
    private static final long TOOL_UPDATE_INTERVAL_MS = 100;
    // v1.91e：生成中切走时补存 AI 回复（避免历史只剩用户消息）
    private String currentPartialReply = "";   // 当前正在生成的回复（已生成部分）
    private String generatingConvKey = "";     // 生成所在会话 key

    private static final int TYPE_USER = 0;
    private static final int TYPE_AI = 1;
    private static final int TYPE_SYSTEM = 2;
    private static final int TYPE_ERROR = 3;
    private static final int TYPE_LOADING = 4;

    /** 一条聊天消息记录（用于按项目持久化历史） */
    private static class ChatMessage {
        int type;
        String text;
        ChatMessage(int type, String text) {
            this.type = type;
            this.text = text == null ? "" : text;
        }
    }

    /** 当前会话的消息记录（重建界面 / 按项目保存历史用） */
    private final java.util.List<ChatMessage> mMessages = new java.util.ArrayList<>();
    /** 当前绑定的项目路径（历史按项目分开保存） */
    private String currentProjectPath = "";

    // ===== v1.51n 多会话支持（欢迎页进对话=新建对话；对话/项目双列表）=====
    /** 当前会话历史文件基名（不含 .json）：chat_时间戳=纯对话；MD5(项目路径)=项目对话；null=未初始化 */
    private String currentConvKey = null;
    // v1.51bi：项目路径 → 对话 key 映射表（避免串台）
    private static final java.util.HashMap<String, String> projectConvMap = new java.util.HashMap<>();
    // v1.51ci：映射持久化，App重启不丢
    private static File getConvMapFile(Context ctx) {
        return new File(ctx.getFilesDir(), "chat_history/project_conv_map.json");
    }
    private static void loadConvMap(Context ctx) {
        try {
            File f = getConvMapFile(ctx);
            if (!f.exists()) return;
            org.json.JSONObject obj = new org.json.JSONObject(new String(
                java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"));
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String path = keys.next();
                projectConvMap.put(path, obj.getString(path));
            }
        } catch (Throwable ignored) {}
    }
    private static void saveConvMap(Context ctx) {
        try {
            org.json.JSONObject obj = new org.json.JSONObject();
            for (String path : projectConvMap.keySet()) {
                obj.put(path, projectConvMap.get(path));
            }
            File f = getConvMapFile(ctx);
            f.getParentFile().mkdirs();
            java.nio.file.Files.write(f.toPath(), obj.toString().getBytes("UTF-8"));
        } catch (Throwable ignored) {}
    }
    /** 对话开始时的空间项目集合（检测 AI 在对话中新建的项目，用于自动绑定） */
    private java.util.Set<String> scenarioAStartProjects = new java.util.HashSet<>();
    /** 防重入：bindToProject 内 saveHistory 再次触发检测 */
    private boolean bindingProject = false;

    /** 欢迎语（新项目无历史时显示） */
    private static final String WELCOME_TEXT =
            "你好！我是 AI Code Studio 的 AI 助手。\n\n我可以帮你：\n"
            + "• 编写代码片段\n• 配置编译工具链\n• 修复编译错误\n"
            + "• 分析项目结构\n\n请输入你的需求开始对话。";

    // ===== 聊天气泡配色 =====
    // AI 气泡   → 蓝色描边（主题蓝）
    // 用户气泡 → 绿色描边（主题绿）
    // 填充都走低透明度深色，避免大色块刺眼，描边承担色彩识别
    private static final int BUBBLE_STROKE_AI   = 0xFF3D9BFF; // 蓝
    private static final int BUBBLE_STROKE_USER = 0xFF26D962; // 绿
    private static final int BUBBLE_FILL_AI     = 0x1A3D9BFF; // 蓝 10%
    private static final int BUBBLE_FILL_USER   = 0x1A26D962; // 绿 10%
    private static final float BUBBLE_STROKE_W  = 1.0f;

    public interface ChatPanelListener {
        /** 需要把 AI 生成的代码插入编辑器 */
        void onInsertCodeToEditor(String code);
        /** 获取当前编辑器选中的代码 */
        String getEditorSelection();
        /** v1.51al：新建对话后回调（通知外部清空项目名显示） */
        void onConversationReset();
        /** v1.51aq：绑定项目后回调（通知外部更新项目名显示） */
        void onProjectBound(String projectPath);
        /** v1.87：用户发出第一条消息时回调（用于把"新对话"标题替换为消息前几个字） */
        void onFirstUserMessage(String text);
        /** v1.139：对话模式切换回调（0=闲聊 / 1=工作任务），供外部持久化到会话 */
        void onChatModeChanged(int mode);
    }

    // ==================== Markdown 段落类型 ====================
    private static class TextSegment {
        final String text;
        TextSegment(String text) { this.text = text; }
    }

    /** 流式 AI 消息行的双容器 tag：正文容器 + 工具卡片容器 */
    private static class ToolHostTag {
        final LinearLayout content;   // 正文（markdown / 代码块），供 updateLoadingMessage 增量渲染
        final LinearLayout toolHost;  // 工具调用卡片容器（读/写/执行即时卡片）
        ToolHostTag(LinearLayout content, LinearLayout toolHost) {
            this.content = content;
            this.toolHost = toolHost;
        }
    }

    private static class CodeSegment {
        final String code;
        final String language;
        CodeSegment(String code, String language) {
            this.code = code;
            this.language = language;
        }
    }

    /** 思考过程片段（思考模型的 <think>...</think> 内容） */
    private static class ThinkSegment {
        final String content;
        ThinkSegment(String content) { this.content = content; }
    }

    public ChatPanel(Context ctx) {
        super(ctx);
        init(ctx);
    }

    public ChatPanel(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        init(ctx);
    }

    private void init(Context ctx) {
        loadConvMap(ctx);
        setOrientation(VERTICAL);
        setBackgroundColor(Theme.BG_PRIMARY);
        mainHandler = new Handler(Looper.getMainLooper());

        try {
            chatManager = AIChatManager.getInstance();
            chatManager.attachContext(ctx); // v1.37：恢复上次会话历史（重启不丢对话）
            AppConfig cfg = AppConfig.getInstance(ctx);
            // 始终同步所有配置，不依赖 hasApiKey 判断
            chatManager.setApiKey(cfg.getApiKey());
            chatManager.setApiBaseUrl(cfg.getApiBase());
            chatManager.setModelName(cfg.getModelName());
            chatManager.setMode(cfg.getModelMode() == AppConfig.MODE_LOCAL
                    ? AIChatManager.MODE_LOCAL : AIChatManager.MODE_CLOUD);
        } catch (Throwable t) {
            // AI 管理器未就绪不阻塞 UI
        }

        // ===== 标题栏 =====
        LinearLayout header = new LinearLayout(ctx);
        header.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, dp(36)));
        header.setOrientation(HORIZONTAL);
        header.setBackgroundColor(Theme.BG_SECONDARY);
        header.setGravity(Gravity.CENTER_VERTICAL);
        // v1.153s：左右 padding 对称，配合三等分槽位，中间按钮才真正居中
        header.setPadding(dp(12), 0, dp(12), 0);

        // v1.153s：顶部整条三等分——左槽靠左 / 中槽居中 / 右槽靠右，
        // 中间"云端/本地"永远在 header 正中，不受两侧文字宽度影响
        LinearLayout slotLeft = new LinearLayout(ctx);
        slotLeft.setOrientation(HORIZONTAL);
        slotLeft.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        header.addView(slotLeft, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
        LinearLayout slotCenter = new LinearLayout(ctx);
        slotCenter.setOrientation(HORIZONTAL);
        slotCenter.setGravity(Gravity.CENTER);
        header.addView(slotCenter, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
        LinearLayout slotRight = new LinearLayout(ctx);
        slotRight.setOrientation(HORIZONTAL);
        slotRight.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        header.addView(slotRight, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));

        // v1.136：工作任务/闲聊切换移到最左（AI 对话标题位），模式一目了然
        tvChatMode = new TextView(ctx);
        tvChatMode.setText("工作任务");
        tvChatMode.setTextColor(0xFFA5D6A7);
        tvChatMode.setTextSize(14);
        tvChatMode.setPadding(dp(6), dp(2), dp(6), dp(2));
        tvChatMode.setBackground(makeChipBg(0xFF388E3C));
        // v1.153s：槽内靠左、自然宽度（"工作任务/闲聊"不必同宽）
        tvChatMode.setLayoutParams(new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        tvChatMode.setGravity(Gravity.CENTER);
        tvChatMode.setSingleLine(true);
        tvChatMode.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                toggleChatMode();
            }
        });
        slotLeft.addView(tvChatMode);
        updateChatModeIndicator(); // 同步当前模式（默认工作任务）

        // v1.139：标题"AI 对话"删除——主页图标下方已有全局标题，不再重复显示
        // 模型模式指示器（文档 5.2 底部状态栏也有，此处为面板内快捷显示）
        tvMode = new TextView(ctx);
        tvMode.setText("云端");
        tvMode.setTextColor(Theme.ACCENT_BLUE);
        tvMode.setTextSize(14);
        tvMode.setPadding(dp(6), dp(2), dp(6), dp(2));
        tvMode.setBackground(makeChipBg(0xFF2196F3));
        tvMode.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { toggleModelMode(); }
        });
        slotCenter.addView(tvMode);

        // 原始消息开关按钮（关闭所有 markdown 和代码框渲染，显示原始文本）
        tvRawToggle = new TextView(ctx);
        tvRawToggle.setLayoutParams(new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        tvRawToggle.setGravity(Gravity.CENTER);
        tvRawToggle.setText("渲染");
        tvRawToggle.setTextColor(0xFF90CAF9);
        tvRawToggle.setTextSize(14);
        tvRawToggle.setPadding(dp(6), dp(2), dp(6), dp(2));
        tvRawToggle.setBackground(makeChipBg(0xFF607D8B));
        tvRawToggle.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                showRawMessage = !showRawMessage;
                if (showRawMessage) {
                    tvRawToggle.setText("原始");
                    tvRawToggle.setTextColor(0xFFFFAB91);
                } else {
                    tvRawToggle.setText("渲染");
                    tvRawToggle.setTextColor(0xFF90CAF9);
                }
                // 刷新所有消息
                refreshAllMessages();
            }
        });
        slotRight.addView(tvRawToggle);

        addView(header);

        // ===== 消息列表（FrameLayout 包裹 ScrollView + 悬浮按钮）=====
        scrollContainer = new android.widget.FrameLayout(ctx);
        scrollContainer.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        scrollView = new ScrollView(ctx);
        scrollView.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        scrollView.setFillViewport(true);

        messageContainer = new LinearLayout(ctx);
        messageContainer.setOrientation(LinearLayout.VERTICAL);
        messageContainer.setLayoutParams(new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        messageContainer.setPadding(dp(8), dp(8), dp(8), dp(8));
        scrollView.addView(messageContainer);
        scrollContainer.addView(scrollView);

        // 悬浮向下箭头按钮
        btnScrollDown = new TextView(ctx);
        btnScrollDown.setText("↓");
        btnScrollDown.setTextColor(Color.WHITE);
        btnScrollDown.setTextSize(18);
        btnScrollDown.setGravity(Gravity.CENTER);
        btnScrollDown.setBackground(makeCircleButtonBg(0xFF607D8B));
        btnScrollDown.setElevation(dp(4));
        android.widget.FrameLayout.LayoutParams btnLp =
                new android.widget.FrameLayout.LayoutParams(dp(40), dp(40));
        btnLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL; // v1.137：向下箭头悬浮在底部中央
        btnLp.setMargins(0, 0, 0, dp(12));
        btnScrollDown.setLayoutParams(btnLp);
        btnScrollDown.setVisibility(View.GONE);
        btnScrollDown.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                // 用户点击向下按钮：恢复自动滚动，平滑滚动到底部
                userScrolledUp = false;
                btnScrollDown.setVisibility(View.GONE);
                // v1.138：用 smoothScrollTo 接管滚动——内部 startScroll 会立即终止
                // 正在进行的惯性滚动（fling），否则点击会被惯性滚动覆盖，必须等滚动停才生效
                programScroll = true;   // 平滑期间 checkScrollPosition 不干预
                smoothScrollToBottom();
                mainHandler.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (programScroll) {
                            programScroll = false; // 兜底：动画结束清除标志
                            checkScrollPosition();
                        }
                    }
                }, 900);
            }
        });
        scrollContainer.addView(btnScrollDown);

        // 监听 ScrollView 滚动事件，判断用户是否上滑
        scrollView.getViewTreeObserver().addOnScrollChangedListener(
                new android.view.ViewTreeObserver.OnScrollChangedListener() {
                    @Override public void onScrollChanged() {
                        checkScrollPosition();
                    }
                });

        addView(scrollContainer);


        // ===== 输入区 =====
        // v1.51bt：语音模块已移除，直接隐藏状态条，不显示任何报错
        voiceStatusText = new TextView(ctx);
        voiceStatusText.setVisibility(View.GONE);
        addView(voiceStatusText);

        LinearLayout inputBar = new LinearLayout(ctx);
        inputBar.setLayoutParams(new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        inputBar.setOrientation(HORIZONTAL);
        inputBar.setBackgroundColor(Theme.BG_SECONDARY);
        inputBar.setPadding(dp(8), dp(8), dp(8), dp(8));
        inputBar.setGravity(Gravity.CENTER_VERTICAL);

        // 输入区用 FrameLayout 包裹：语音遮罩覆盖输入框、不参与外层 LinearLayout 排列
        inputArea = new FrameLayout(ctx);
        inputArea.setLayoutParams(new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        inputArea.addView(inputBar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        etInput = new EditText(ctx);
        LinearLayout.LayoutParams lpIn =
                new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        etInput.setLayoutParams(lpIn);
        etInput.setHint("输入开发需求...");
        etInput.setHintTextColor(Theme.TEXT_TERTIARY);
        etInput.setTextColor(Theme.TEXT_PRIMARY);
        etInput.setTextSize(13);
        etInput.setBackground(makeInputBg());
        etInput.setPadding(dp(10), dp(10), dp(10), dp(10));
        etInput.setMaxLines(4);
        etInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        // 长按输入框 → 输入框为空：屏蔽系统菜单并触发语音（500ms）；有文字：保留系统选择/复制/粘贴
        etInput.setOnTouchListener(new OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                int act = e.getAction();
                if (act == MotionEvent.ACTION_DOWN) {
                    voiceStartRawY = e.getRawY();
                    final boolean hasText = etInput.getText() != null && etInput.getText().length() > 0;
                    VoiceLog.input("touch down hasText=" + hasText + " listening=" + voiceListening);
                    if (!hasText) {
                        // 空输入框：阻止系统粘贴/插入菜单，长按走语音
                        etInput.setCustomInsertionActionModeCallback(EMPTY_INSERTION_CB);
                    } else {
                        // 有文字：恢复系统菜单（选择/复制/粘贴），长按不触发语音
                        etInput.setCustomInsertionActionModeCallback(null);
                        etInput.setCustomSelectionActionModeCallback(null);
                    }
                    longPressRunnable = new Runnable() {
                        @Override public void run() {
                            VoiceLog.input("longpress fired hasText=" + hasText + " listening=" + voiceListening);
                            if (!voiceListening && !hasText) startVoiceListeningIfPermitted();
                        }
                    };
                    etInput.postDelayed(longPressRunnable, 500);
                    return false;
                }
                if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                    if (longPressRunnable != null) {
                        etInput.removeCallbacks(longPressRunnable);
                        longPressRunnable = null;
                    }
                    if (voiceListening) stopVoiceListening(e.getRawY());
                    // 恢复系统默认插入菜单，下次长按再按文字有无重新判断
                    etInput.setCustomInsertionActionModeCallback(null);
                    return false;
                }
                return false;
            }
        });
        inputBar.addView(etInput);

        btnSend = new ImageButton(ctx);
        LinearLayout.LayoutParams lpS = new LinearLayout.LayoutParams(dp(48), dp(48));
        lpS.setMargins(dp(8), 0, 0, 0);
        // v1.137：发送按钮包进 FrameLayout，停止状态叠旋转描边环
        // v1.153p：红圆放大到 44dp，方块 42dp 撑满；描边环 48dp 罩圆外
        sendWrap = new android.widget.FrameLayout(ctx);
        sendWrap.setLayoutParams(lpS);
        btnSend.setLayoutParams(new android.widget.FrameLayout.LayoutParams(dp(44), dp(44),
                android.view.Gravity.CENTER));
        btnSend.setImageDrawable(getResources().getDrawable(
                com.aicodestudio.ide.R.drawable.ic_send));
        btnSend.setBackground(makeCircleButtonBg(0xFF2196F3));
        btnSend.setPadding(dp(9), dp(9), dp(9), dp(9));
        btnSend.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        btnSend.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                // v1.137：生成中点击 = 停止生成；平时 = 发送
                if (isAiGenerating) {
                    if (chatManager != null) chatManager.stopGeneration();
                    // v1.147：停止=彻底关掉生成服务——工具卡片动画/定时器立即停
                    cancelToolCardFlush();
                    if (activeToolAnimator != null) {
                        try { activeToolAnimator.cancel(); } catch (Throwable ignored) {}
                        activeToolAnimator = null;
                    }
                    pendingToolDelta.setLength(0);
                    activeToolCard = null;
                    // v1.146：立即复位状态并冻结 UI——不等引擎回调，
                    // 界面立刻停住（后续 native 把当前 batch decode 完期间也不再 feed 新内容）
                    isAiGenerating = false;
                    generationStopped = true;
                    // 立即恢复发送样式（引擎中断后的 onError/onComplete 回调也会再恢复一次，双保险）
                    stopSpinAnimation();
                    btnSend.setImageDrawable(getResources().getDrawable(
                            com.aicodestudio.ide.R.drawable.ic_send));
                    btnSend.setBackground(makeCircleButtonBg(0xFF2196F3));
                    btnSend.setPadding(dp(9), dp(9), dp(9), dp(9));
                    // v1.146：无论有没有半截，立即给当前流式行加重生成按钮
                    if (activeStreamingRow != null) {
                        addRegenerateButton(activeStreamingRow);
                    }
                    return;
                }
                sendCurrentInput();
            }
        });
        sendWrap.addView(btnSend);
        spinRingView = new android.widget.ImageView(ctx);
        android.graphics.drawable.Drawable ringD = getResources().getDrawable(
                com.aicodestudio.ide.R.drawable.ic_spin_ring);
        if (ringD != null) {
            ((android.widget.ImageView) spinRingView).setImageDrawable(ringD);
            // v1.153t：环矢量 48dp 满幅圆，罩在 44dp 红圆外沿；CENTER 不缩放原样显示
            ((android.widget.ImageView) spinRingView).setScaleType(android.widget.ImageView.ScaleType.CENTER);
        }
        spinRingView.setLayoutParams(new android.widget.FrameLayout.LayoutParams(dp(48), dp(48),
                android.view.Gravity.CENTER));
        spinRingView.setVisibility(View.GONE);
        spinRingView.setClickable(false);
        sendWrap.addView(spinRingView);
        inputBar.addView(sendWrap);

        addView(inputArea);

        updateModeIndicator();
        addMessageNoRecord(WELCOME_TEXT, TYPE_AI);

        // 初始化 WebView 池（预创建 WebView，避免新代码框创建时 loadDataWithBaseURL 导致闪烁）
        initWebViewPool(ctx);
    }

    /**
     * 初始化 WebView 池：预创建 WebView 并加载基础 HTML
     * 只预创建、不回收，池中的 WebView 都是从未被使用过的
     */
    private void initWebViewPool(final Context ctx) {
        if (sPoolInitialized) return;
        sPoolInitialized = true;
        if (mainHandler == null) mainHandler = new Handler(android.os.Looper.getMainLooper());
        mainHandler.post(new Runnable() {
            @Override public void run() {
                try {
                    for (int i = 0; i < WEBVIEW_POOL_SIZE; i++) {
                        final WebView wv = new WebView(ctx);
                        WebSettings ws = wv.getSettings();
                        ws.setJavaScriptEnabled(true);
                        ws.setDomStorageEnabled(false);
                        ws.setCacheMode(WebSettings.LOAD_NO_CACHE);
                        // v1.153bf：Android 14 默认禁止 file:// 访问，必须显式开启才能加载 highlight.min.js
                        ws.setAllowFileAccess(true);
                        ws.setAllowUniversalAccessFromFileURLs(true);
                        wv.setBackgroundColor(0xFF282C34);
                        wv.setVerticalScrollBarEnabled(true);
                        wv.setHorizontalScrollBarEnabled(true);
                        // v1.153bf：等页面（含 highlight.min.js）加载完成后再入池，
                        // 否则取出时 hljs 可能未就绪，setCode 走 catch 纯文本分支
                        wv.setWebViewClient(new android.webkit.WebViewClient() {
                            @Override public void onPageFinished(WebView view, String url) {
                                synchronized (sWebViewPool) {
                                    sWebViewPool.add(wv);
                                }
                            }
                        });
                        wv.loadDataWithBaseURL(
                                "file:///android_asset/highlight/",
                                buildStreamingHtml(""),
                                "text/html", "UTF-8", null);
                    }
                } catch (Throwable ignored) {}
            }
        });
    }

    /** 创建一个池用的 WebView（已加载基础 HTML） */
    private WebView createPoolWebView(Context ctx) {
        WebView wv = new WebView(ctx);
        WebSettings webSettings = wv.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(false);
        webSettings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowUniversalAccessFromFileURLs(true);
        wv.setBackgroundColor(0xFF282C34);
        wv.setVerticalScrollBarEnabled(true);
        wv.setHorizontalScrollBarEnabled(true);
        // 加载基础 HTML（包含 highlight.js 和 setCode/appendCode 函数）
        // 注意：池用 WebView 加载的是通用基础 HTML，语言 class 后续通过 evaluateJavascript 修改
        wv.loadDataWithBaseURL(
                "file:///android_asset/highlight/",
                buildStreamingHtml(""),
                "text/html", "UTF-8", null);
        return wv;
    }

    /**
     * 从 WebView 池获取一个已加载基础 HTML 的 WebView
     * 池用完后创建新的 WebView
     * 注意：池中的 WebView 都是从未被使用过的，不会有重复使用的 bug
     */
    private WebView obtainPoolWebView(Context ctx) {
        synchronized (sWebViewPool) {
            if (!sWebViewPool.isEmpty()) {
                return sWebViewPool.remove(0);
            }
        }
        // 池用完了，创建新的 WebView（这种情况很少见）
        return createPoolWebView(ctx);
    }

    /**
     * 应用进入后台时释放空闲 WebView 池（省内存 / 省电）。
     * 只销毁池中未使用的 WebView（每个 WebView 是一个独立渲染进程），
     * 正在显示的代码框 WebView 不受影响；下次进入对话页会按需重建。
     */
    public static void releaseWebViewPool() {
        synchronized (sWebViewPool) {
            for (WebView wv : sWebViewPool) {
                try {
                    wv.stopLoading();
                    wv.loadUrl("about:blank");
                    wv.destroy();
                } catch (Throwable ignored) {
                }
            }
            sWebViewPool.clear();
        }
        sPoolInitialized = false; // 下次 initWebViewPool 重建
    }

    // ================= 交互 =================

    private void sendCurrentInput() {
        String text = etInput.getText().toString().trim();
        if (text.length() == 0) return;
        etInput.setText("");
        sendToAI(text);
    }

    /**
     * 把消息发送给 AI
     */
    public void sendToAI(String text) {
        if (text == null || text.length() == 0) return;
        com.aicodestudio.ide.ai.util.DebugLogger.ensureFile();
        com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                "sendToAI enter: text=\"" + (text.length() > 30 ? text.substring(0, 30) + "..." : text)
                + "\" currentConvKey=" + currentConvKey
                + " managerBusy=" + (chatManager == null ? "null" : chatManager.isBusy())
                + " isAiGenerating=" + isAiGenerating
                + " generationStopped=" + generationStopped
                + " mode=" + (chatManager == null ? "-" : chatManager.getMode()));
        // v1.153：用户发新消息时，清掉上一轮遗留的所有"重新生成"按钮（像豆包一样——
        // 上轮的重新生成按钮随新一轮对话自动消失）
        removeAllRegenButtons();
        // v1.87：判断这是否是当前会话的第一条用户消息（mMessages 里 TYPE_USER 数为 0）
        // 注意：必须在 addMessage(text, TYPE_USER) 之前判断，否则本次消息已入列，判断永远为 false
        boolean isFirstUserMessage = true;
        synchronized (mMessages) {
            for (ChatMessage m : mMessages) {
                if (m.type == TYPE_USER) { isFirstUserMessage = false; break; }
            }
        }
        syncProjectBinding();
        addMessage(text, TYPE_USER);
        // 首条消息：回调外部把会话标题从"新对话"改为消息前几个字
        if (isFirstUserMessage && listener != null) {
            try { listener.onFirstUserMessage(text); } catch (Throwable ignored) {}
        }

        if (chatManager == null) {
            try {
                chatManager = AIChatManager.getInstance();
            }
            catch (Throwable t) { chatManager = null; }
        }
        if (chatManager == null) {
            addMessage("AI 服务未初始化完成，请重启应用。", TYPE_ERROR);
            return;
        }
        // v1.49：把当前对话工作目录同步给 AI 调度器（场景B=项目目录；空白对话=null→场景A 工作空间）
        try {
            chatManager.setWorkDir(currentProjectPath);
        } catch (Throwable ignored) {
        }

        // 同步 API 配置
        try {
            AppConfig cfg = AppConfig.getInstance(getContext());
            chatManager.setApiKey(cfg.getApiKey());
            chatManager.setApiBaseUrl(cfg.getApiBase());
            chatManager.setModelName(cfg.getModelName());
            chatManager.setMode(cfg.getModelMode() == AppConfig.MODE_LOCAL
                    ? AIChatManager.MODE_LOCAL : AIChatManager.MODE_CLOUD);
        } catch (Throwable ignored) {
        }

        // 使用 TYPE_AI 类型，这样流式输出过程中就能渲染 Markdown（代码框、粗体、列表等）
        final View[] loadingViewRef = new View[]{addMessage("正在思考...", TYPE_AI)};
        activeStreamingRow = loadingViewRef[0]; // v1.136：标记流式行，刷新原始/渲染时跳过重建
        // v1.153e：动画已在 addMessageInternal 创建行时直接建好，无需事后启动
        // v1.91e：记录本次生成，切走时用已生成内容补存
        currentPartialReply = "";
        generatingConvKey = currentConvKey == null ? "" : currentConvKey;
        // v1.91：记录当前流式消息的工具卡片容器
        Object _ltag = loadingViewRef[0].getTag();
        if (_ltag instanceof ToolHostTag) currentToolHost = ((ToolHostTag) _ltag).toolHost;
        final StringBuilder streamBuffer = new StringBuilder();
        final boolean[] pendingUpdate = {false};  // 合并更新：是否已有待处理的 UI 更新
        final int[] streamFedLen = {0};           // v1.136：已喂给 streamParser 的字符数，防丢 chunk
        isAiGenerating = true;
        generationStopped = false; // v1.146：新一轮生成复位停止标志
        userScrolledUp = false;  // 新对话开始时重置滚动状态
        programScroll = false;   // v1.153i：新一轮开始，清除程序滚动标志
        updateSendButtonState(); // v1.137：发送按钮 → 红色停止按钮 + 描边旋转

        // v1.125: 事件驱动流式渲染——不再每次chunk都parseMarkdown全文
        final LinearLayout streamContent;
        Object ctag = loadingViewRef[0].getTag();
        if (ctag instanceof ToolHostTag) streamContent = ((ToolHostTag) ctag).content;
        else if (ctag instanceof LinearLayout) streamContent = (LinearLayout) ctag;
        else streamContent = null;
        // v1.126: 工具卡片直接加到content流里，不再放单独的toolHost
        currentToolHost = streamContent;

        // v1.136：流式增量渲染状态改为成员数组，切换原始/渲染重建后由 resetStreamingState() 重置
        resetStreamingState();

        final com.aicodestudio.ide.ai.agent.UnifiedStreamParser streamParser =
            new com.aicodestudio.ide.ai.agent.UnifiedStreamParser(new com.aicodestudio.ide.ai.agent.UnifiedStreamParser.Listener() {
                @Override public void onText(String text) {
                    // v1.153g：只有收到非空白正文才停动画（空行/\n 不算）
                    if (text != null && text.trim().length() > 0) stopThinkingDots();
                    if (streamContent == null) return;
                    // 如果正在代码块，代码块结束后才会到这里
                    if (textViewArr[0] == null) {
                        textViewArr[0] = new TextView(getContext());
                        textViewArr[0].setTextColor(Theme.TEXT_PRIMARY);
                        textViewArr[0].setTextSize(14);
                        textViewArr[0].setPadding(0, dp(2), 0, dp(2));
                        textViewArr[0].setTextIsSelectable(true);
                        textViewArr[0].setLineSpacing(0, 1.3f);
                        // v1.153m：正文与思考卡片同宽（MATCH_PARENT），短行也不窄于卡片
                        streamContent.addView(textViewArr[0], new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT));
                        textBufArr[0].setLength(0);
                    }
                    textBufArr[0].append(text);
                    try {
                        String html = markdownToHtml(textBufArr[0].toString());
                        textViewArr[0].setText(android.text.Html.fromHtml(html));
                    } catch (Throwable e) {
                        textViewArr[0].setText(textBufArr[0].toString());
                    }
                }
                @Override public void onThinkStart() {
                    // v1.153g：空 think 标签不停动画，等思考内容真正开始才停
                    textViewArr[0] = null;
                    textBufArr[0].setLength(0);
                    thinkPendingBuf.setLength(0); // v1.152：重置延时缓冲
                }
                @Override public void onThinkContent(String text) {
                    if (streamContent == null) return;
                    if (thinkTvArr[0] == null) {
                        // v1.152：延时创建——先攒够最小字符数，确认思考有实际内容才建卡片
                        thinkPendingBuf.append(text);
                        if (thinkPendingBuf.length() < THINK_CREATE_MIN_CHARS) return;
                        stopThinkingDots(); // 思考卡片真正要创建了，停三点动画
                        thinkCardUserScrolled[0] = false; // v1.153ay：新卡片重置手动上滑标志
                        ThinkStreamCard card = new ThinkStreamCard();
                        card.build();
                        streamContent.addView(card.rootView);
                        card.rootView.setTag(STREAM_CARD_TAG);
                        streamingToolCards.add(card);
                        thinkTvArr[0] = card.contentTv;
                        thinkTvArr[0].setText(thinkPendingBuf.toString()); // 把缓冲一次性补上
                    } else {
                        CharSequence cur = thinkTvArr[0].getText();
                        thinkTvArr[0].setText(cur + text);
                    }
                    // v1.153ay：输出中思考卡片自动滚动到底跟随新内容；用户手动上滑
                    // 查看前面内容时（thinkCardUserScrolled=true）不打扰，滚到底松手自动恢复。
                    try {
                        if (!thinkCardUserScrolled[0]
                                && thinkTvArr[0].getParent() instanceof ScrollView) {
                            final ScrollView sv = (ScrollView) thinkTvArr[0].getParent();
                            sv.post(new Runnable() {
                                @Override public void run() {
                                    if (!thinkCardUserScrolled[0]) sv.fullScroll(ScrollView.FOCUS_DOWN);
                                }
                            });
                        }
                    } catch (Throwable ignored) {}
                }
                @Override public void onThinkEnd() {
                    thinkTvArr[0] = null;
                    thinkPendingBuf.setLength(0); // 没攒够就结束的空思考：丢弃缓冲，不建卡片
                }
                @Override public void onCodeStart(String lang) {
                    if (streamContent == null) return;
                    textViewArr[0] = null;
                    textBufArr[0].setLength(0);
                    codeLangArr[0] = lang == null ? "" : lang;
                    codeBufArr[0].setLength(0);
                    codePendingBuf.setLength(0); // v1.152：重置延时缓冲
                    // v1.126: shell块由detector创建工具卡片，不创建普通代码框
                    String l = codeLangArr[0].toLowerCase();
                    shellBlockArr[0] = l.contains("shell") || l.contains("bash") || l.equals("sh");
                }
                @Override public void onCodeContent(String text) {
                    if (streamContent == null) return;
                    if (shellBlockArr[0]) return; // shell块不渲染普通代码框
                    if (codeViewArr[0] == null) {
                        // v1.152：延时创建——先攒够最小字符数，确认代码有实际内容才建卡片
                        codePendingBuf.append(text);
                        if (codePendingBuf.length() < CODE_CREATE_MIN_CHARS) return;
                        CodeStreamCard card = new CodeStreamCard();
                        card.build(codeLangArr[0]);
                        streamContent.addView(card.rootView);
                        // v1.153bf：不覆盖 rootView 的 tag——createCodeBlock 已把 CodeBoxTag 设为 tag，
                        // updateCodeBlockContent / isCodeBlockView 都靠 getTag() 识别 WebView 代码框。
                        // （STREAM_CARD_TAG 未被任何 getTag 读取，卡片可见性由 streamingToolCards 列表管理）
                        streamingToolCards.add(card);
                        codeViewArr[0] = card.rootView;
                        codeBufArr[0].append(codePendingBuf.toString()); // 缓冲内容一次性补上
                    } else {
                        codeBufArr[0].append(text);
                    }
                    updateCodeBlockContent(codeViewArr[0], codeBufArr[0].toString(), codeLangArr[0]);
                }
                @Override public void onCodeEnd() {
                    codeViewArr[0] = null;
                    codeBufArr[0].setLength(0);
                    codePendingBuf.setLength(0); // 没攒够就结束的空代码块：丢弃，不建卡片
                    shellBlockArr[0] = false;
                }
            });

        chatManager.setResponseListener(new AIChatManager.AIResponseListener() {
            @Override public void onResponseStart() {
                com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                        "ChatPanel.onResponseStart: isAiGenerating=" + isAiGenerating
                        + " currentConvKey=" + currentConvKey);
            }

            @Override
            public void onResponseChunk(final String chunk) {
                if (chunk == null || chunk.isEmpty()) return;
                if (generationStopped) { // v1.146：用户已点停止，丢弃后续增量，UI 冻结不再变化
                    com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                            "ChatPanel.onResponseChunk DROPPED: generationStopped=true chunkLen="
                            + chunk.length());
                    return;
                }
                if (streamBuffer.length() == 0) {
                    com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                            "ChatPanel.onResponseChunk FIRST: len=" + chunk.length()
                            + " isAiGenerating=" + isAiGenerating);
                }
                streamBuffer.append(chunk);
                currentPartialReply = streamBuffer.toString();
                // v1.136：实时把完整文本写进 content 的 tag，切换原始/渲染时能拿到当前全部内容重建
                try {
                    if (streamContent != null) streamContent.setTag(streamBuffer.toString());
                } catch (Throwable ignored) {}
                // v1.140：无论渲染/原始模式都喂事件驱动渲染器——
                // 后台照常创建工具卡片/代码块等控件（原始模式下隐藏），
                // 切回渲染时控件已存在、立即可显示，避免一次性重建卡顿/内容空白
                if (!pendingUpdate[0]) {
                    pendingUpdate[0] = true;
                    mainHandler.post(new Runnable() {
                        @Override public void run() {
                            pendingUpdate[0] = false;
                            try {
                                String full = streamBuffer.toString();
                                if (full.length() > streamFedLen[0]) {
                                    String inc = full.substring(streamFedLen[0]);
                                    streamFedLen[0] = full.length();
                                    streamParser.feed(inc);
                                }
                            } catch (Throwable ignored) {}
                            // v1.140：原始模式——隐藏渲染控件（卡片/代码块后台保留），仅显示原始文本全文
                            if (showRawMessage) {
                                try {
                                    applyModeVisibility(streamContent, streamBuffer.toString());
                                } catch (Throwable ignored) {}
                            }
                            // v1.153j：流式输出每帧渲染后自动滚动到底部（用户没上滑、非程序平滑滚动时）
                            try {
                                if (!userScrolledUp && !programScroll) scrollToBottomAfterLayout();
                            } catch (Throwable ignored) {}
                        }
                    });
                }
            }

            @Override
            public void onResponseComplete(final String fullResponse) {
                isAiGenerating = false;
                com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                        "ChatPanel.onResponseComplete enter: fullLen="
                        + (fullResponse == null ? 0 : fullResponse.length())
                        + " generatingConvKey=" + generatingConvKey
                        + " currentConvKey=" + currentConvKey);
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        updateSendButtonState(); // v1.137：完成 → 恢复蓝色发送按钮
                        // v1.153ar：关键——生成中途已切走会话时，这个收尾回调必须早退。
                        // 否则 updateLoadingMessageRecord 会把旧对话 AI 回复写进 mMessages，
                        // 而 currentConvKey 已是新对话；后续 onPause/切后台自动 saveHistory
                        // 就把旧内容用新 key 写进 SQLite（"新对话搬旧内容"的根因）。
                        // 旧对话内容已在切换时遮罩路径 saveHistory(旧key) 保存过，无需再补。
                        boolean sameConv = (generatingConvKey == null || generatingConvKey.isEmpty())
                                || generatingConvKey.equals(currentConvKey);
                        if (!sameConv) {
                            com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                                    "onResponseComplete EARLY-RETURN: 会话已切换 sameConv=false");
                            loadingViewRef[0] = null;
                            activeStreamingRow = null;
                            currentToolHost = null;
                            return;
                        }
                        com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                                "onResponseComplete post run: sameConv=true");
                        // v1.136：先把尚未 feed 的 streamBuffer 增量补喂给 parser，再 flush 收尾
                        try {
                            String full = streamBuffer.toString();
                            if (full.length() > streamFedLen[0]) {
                                String inc = full.substring(streamFedLen[0]);
                                streamFedLen[0] = full.length();
                                streamParser.feed(inc);
                            }
                        } catch (Throwable ignored) {}
                        try { streamParser.flush(); } catch (Throwable ignored) {}
                        // v1.153j：输出完成，确保滚动到底部
                        try {
                            if (!userScrolledUp && !programScroll) scrollToBottomAfterLayout();
                        } catch (Throwable ignored) {}
                        // v1.91：回复结束，清理工具卡片状态
                        currentToolHost = null;
                        cancelToolCardFlush();
                        pendingToolDelta.setLength(0);
                        if (activeToolAnimator != null) {
                            try { activeToolAnimator.cancel(); } catch (Throwable ignored) {}
                            activeToolAnimator = null;
                        }
                        activeToolCard = null;
                        // v1.125: activeCodeView/activeCodeLang改为局部数组，不需要这里清空
                        currentToolContent.setLength(0);
                        syncProjectBinding();
                        // v1.90：UI 正文优先用 streamBuffer（含完整思考过程+正文），不用 AIChatManager 过滤后的 visibleResponse。
                        // v1.91f：streamBuffer 经 detector.onBody 已剥离 ```shell 命令块（单独走工具卡片），
                        //         只用于 UI 正文渲染（避免与工具卡片双显）；历史保存必须用 fullResponse（含代码块），
                        //         否则重开历史代码框/命令块消失、AI 原始输出顺序被打乱。
                        String uiResp = streamBuffer.toString();
                        if (uiResp == null || uiResp.isEmpty()) {
                            uiResp = (fullResponse != null && !fullResponse.isEmpty()) ? fullResponse : "";
                        }
                        // 用于历史保存/记录的完整正文（含代码块）
                        String saveResp = (fullResponse != null && !fullResponse.isEmpty())
                                ? fullResponse : uiResp;
                        // 非流式调用（如云端 API）不会触发 onResponseChunk，streamBuffer 为空，
                        // 此时必须调用 updateLoadingMessage 把完整回复渲染到 UI，
                        // 否则 loadingView 会永远停留在"正在思考..."
                        if (streamBuffer.length() == 0 && loadingViewRef[0] != null) {
                            updateLoadingMessage(loadingViewRef[0], uiResp);
                            try { if (!userScrolledUp && !programScroll) scrollToBottomAfterLayout(); } catch (Throwable ignored) {}
                        }
                        // 流式输出（本地模型）每 100ms 已经更新过，内容已经完整，直接保留当前状态
                        // 不删除 loadingView，直接保留为正式消息
                        // v1.100：流式完成后，把这条 AI 消息从双容器重排为单流内联——
                        // 工具卡片从 toolHost 末尾按原文顺序归位到 content 正确位置，思考/正文/工具顺序与原文一致。
                        if (loadingViewRef[0] != null) {
                            finalizeHistoricalRow(loadingViewRef[0], saveResp);
                            // v1.147：生成正常完成也给"重新生成"按钮（与停止/中断一致）
                            addRegenerateButton(loadingViewRef[0]);
                        }
                        loadingViewRef[0] = null; // 清理引用，避免后续更新影响已完成的消息
                        activeStreamingRow = null; // v1.136：生成完成，流式行可参与后续重建
                        // v1.149 P1：释放卡片对象引用后清空列表（View 已归位到 content，仅清引用）
                        for (BaseStreamCard _c : streamingToolCards) { try { _c.release(); } catch (Throwable ignored) {} }
                        streamingToolCards.clear(); // v1.136：卡片已归位到 content，列表使命完成
                        // 更新消息记录：把最后一条"正在思考..."替换为完整回复（含代码块）
                        updateLoadingMessageRecord(saveResp);
                        currentPartialReply = saveResp;  // v1.91e：完整回复
                        // v1.153ar：到这里 sameConv 必为 true（false 已在开头早退），
                        // 回复完成自动保存历史（防止进程被杀丢失对话）
                        saveHistory(currentProjectPath);
                        // 自动提取代码块插入编辑器（文档 流程1）——用含代码块的完整正文
                        autoInsertCodeIfNeeded(saveResp);
                    }
                });
            }

            @Override
            public void onError(final String errorMsg) {
                isAiGenerating = false;
                com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                        "ChatPanel.onError: msg=" + errorMsg
                        + " generatingConvKey=" + generatingConvKey
                        + " currentConvKey=" + currentConvKey);
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        updateSendButtonState(); // v1.137：中断/出错 → 恢复蓝色发送按钮
                        com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                                "ChatPanel.onError post run: streamBufferLen=" + streamBuffer.length());
                        // v1.37 修复：流式输出中途报错时，已显示的内容（代码）必须保留，
                        // 只追加错误提示，不能删气泡（旧行为 removeMessageView 把已显示的代码全删了）。
                        if (streamBuffer.length() > 0) {
                            String kept = streamBuffer.toString() + "\n\n[生成中断: " + errorMsg + "]";
                            final View abortedRow = loadingViewRef[0];
                            if (abortedRow != null) {
                                updateLoadingMessage(abortedRow, kept);
                                addRegenerateButton(abortedRow); // v1.145：中断后给半截消息加重生成按钮
                            }
                            updateLoadingMessageRecord(kept);
                            currentPartialReply = kept;  // v1.91e
                            loadingViewRef[0] = null;
                            activeStreamingRow = null; // v1.136：错误中断后标记清除
                        } else {
                            // v1.146：无半截（思考阶段就中断）——保留"正在思考"行改为中断提示 + 重新生成按钮，
                            // 不再删行（用户要的：没有生成出半截也要出现重新生成按钮）
                            if (loadingViewRef[0] != null) {
                                updateLoadingMessage(loadingViewRef[0], "[生成中断: " + errorMsg + "]");
                                addRegenerateButton(loadingViewRef[0]);
                            } else {
                                addMessage("错误: " + errorMsg, TYPE_ERROR);
                            }
                            removeLoadingMessageRecord(); // 从历史记录移除"正在思考..."
                            currentPartialReply = "";    // v1.91e
                            activeStreamingRow = null;   // v1.136
                        }
                        saveHistory(currentProjectPath);
                    }
                });
            }

            // v1.91：流式工具调用即时卡片（读=书页 / 写=铅笔 / 执行=尖括号斜杠）
            @Override
            public void onToolStart(final com.aicodestudio.ide.ai.agent.StreamingToolDetector.ToolBlock block) {
                mainHandler.post(new Runnable() {
                    @Override public void run() { addToolCard(block); }
                });
            }

            @Override
            public void onToolUpdate(final String content) {
                mainHandler.post(new Runnable() {
                    @Override public void run() { updateToolCardContent(content); }
                });
            }

            @Override
            public void onToolEnd(final com.aicodestudio.ide.ai.agent.StreamingToolDetector.ToolBlock block,
                                  final boolean ok, final String summary) {
                mainHandler.post(new Runnable() {
                    @Override public void run() { endToolCard(block, ok, summary); }
                });
            }
        });

        com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                "sendToAI -> chatManager.sendMessage call: busy=" + chatManager.isBusy()
                + " genSeq=" + chatManager.getGenSeq());
        chatManager.sendMessage(text);
    }

    /**
     * 文档 流程1：AI 生成代码 → 自动插入编辑器
     */
    /**
     * 自动插入代码到编辑器。
     * 暂时禁用：编辑器页面尚未正确实现，自动插入会导致聊天内容被清理。
     * 后续编辑器功能完善后再启用。
     */
    private void autoInsertCodeIfNeeded(String response) {
        // 暂时禁用自动插入代码
        return;
        /*
        if (response == null) return;
        String code = extractCodeBlock(response);
        if (code == null || code.length() == 0) return;
        if (listener != null) {
            listener.onInsertCodeToEditor(code);
            addMessage("已自动将代码插入编辑器光标位置。", TYPE_SYSTEM);
        }
        */
    }

    /** 提取 Markdown 代码块 */
    public static String extractCodeBlock(String text) {
        if (text == null) return null;
        int s = text.indexOf("```");
        if (s < 0) return null;
        int lineEnd = text.indexOf('\n', s);
        if (lineEnd < 0) return null;
        int e = text.indexOf("```", lineEnd);
        if (e < 0) return null;
        return text.substring(lineEnd + 1, e);
    }

    /**
     * 快捷按钮：将选中代码发送 AI（文档 5.2）
     */
    private void sendSelectionToAI() {
        String sel = listener == null ? null : listener.getEditorSelection();
        if (sel == null || sel.length() == 0) {
            Toast.makeText(getContext(), "请先在编辑器中选中代码",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        sendToAI("请分析以下代码：\n```\n" + sel + "\n```");
    }

    /**
     * 文档 5.3 规则2：AI 创建编译工具完成后推送通知
     */
    public void notifyToolCreated(String toolName) {
        addMessage("[成功] AI 已自动创建编译工具「" + toolName + "」并绑定当前项目。",
                TYPE_SYSTEM);
    }

    /**
     * 文档 5.3 规则4：自动推送错误的系统提示
     */
    public void notifyErrorPushed(String errorSummary) {
        addMessage("[警告] 已自动推送代码错误信息，请等待 AI 分析修复。\n" + errorSummary,
                TYPE_SYSTEM);
    }

    public void notifyCompileResult(boolean success, String summary) {
        if (success) {
            addMessage("[成功] 编译成功！" + summary, TYPE_SYSTEM);
        } else {
            addMessage("[失败] 编译失败：" + summary + "\n已自动推送日志给 AI 分析。",
                    TYPE_SYSTEM);
        }
    }

    /** 外部触发：把编译日志发送给 AI */
    public void sendCompileLogToAI(String log) {
        if (log == null || log.length() == 0) return;
        String msg = "以下是编译日志，请分析报错原因并给出修复方案：\n```\n"
                + tail(log, 4000) + "\n```";
        sendToAI(msg);
    }

    private String tail(String s, int max) {
        if (s.length() <= max) return s;
        return "..." + s.substring(s.length() - max);
    }

    public void clearChat() {
        messageContainer.removeAllViews();
        mMessages.clear();
        if (chatManager != null) chatManager.clearHistory();
        // 重置滚动状态
        userScrolledUp = false;
        isAiGenerating = false;
        updateSendButtonState(); // v1.137：清空对话 → 恢复蓝色发送按钮
        if (btnScrollDown != null) btnScrollDown.setVisibility(View.GONE);
        addMessageNoRecord("对话已清空，请输入新需求。", TYPE_AI);
        // 清空历史文件（该项目/该会话的历史聊天被删除）
        if (currentProjectPath != null && !currentProjectPath.isEmpty()) {
            deleteHistory(currentProjectPath);
        } else if (currentConvKey != null) {
            deleteConversation(currentConvKey);
        }
    }

    // ================= 聊天历史持久化（按项目分开保存 + v1.51n 多会话） =================

    /** 会话条目（对话列表 / 项目列表共用） */
    public static class ConversationItem {
        public String key;          // 历史文件基名（不含 .json）
        public String name;         // 显示名（对话时间 / 项目名）
        public String projectPath;  // 绑定项目路径（纯对话为空）
        public long time;
        public String preview;
        public boolean isBlank;     // v1.51bu：是否为空白对话（没有用户消息）
        public ConversationItem(String key, String name, String projectPath, long time, String preview) {
            this.key = key; this.name = name; this.projectPath = projectPath;
            this.time = time; this.preview = preview;
            this.isBlank = false;
        }
    }

    /** 历史文件目录：应用私有目录 filesDir/chat_history/ */
    private File historyDir() {
        File dir = new File(getContext().getFilesDir(), "chat_history");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** 项目路径 → 历史文件名（MD5 哈希，避免路径中的特殊字符/中文） */
    private String historyFileName(String projectPath) {
        return historyFileNameBase(projectPath) + ".json";
    }

    /** 项目路径 → 历史文件基名（MD5） */
    private String historyFileNameBase(String projectPath) {
        try {
            java.security.MessageDigest md =
                    java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest((projectPath == null ? "" : projectPath)
                    .getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (Throwable t) {
            return "default";
        }
    }

    /** 判断某项目是否已有历史聊天 */
    public boolean hasHistory(String projectPath) {
        try {
            return new File(historyDir(), historyFileName(projectPath)).exists();
        } catch (Throwable t) {
            return false;
        }
    }

    // ===== v1.51n 新建对话 / 打开历史对话 =====

    /** 新建空白对话（欢迎页进入对话页时调用）：场景A，新会话文件 chat_时间戳 */
    /** v1.51am：当前对话是否是空白对话（只有欢迎消息，没发过任何 user 消息） */
    public boolean isBlankConversation() {
        if (currentProjectPath != null && !currentProjectPath.isEmpty()) return false;  // 绑定了项目就不是空白
        if (currentConvKey == null) return false;  // v1.51ap：还没初始化过 key = 第一次启动，不算空白
        // 检查消息列表：只有欢迎消息（AI），没有 user 消息
        synchronized (mMessages) {
            for (ChatMessage m : mMessages) {
                if (m.type == TYPE_USER) return false;  // 有用户消息 = 不是空白
            }
        }
        return true;
    }

    /** v1.89：当前消息总数（供 SessionManager 标记会话用） */
    public int getMessageCount() {
        synchronized (mMessages) {
            return mMessages.size();
        }
    }

    public void newConversation() {
        newConversation(false);
    }

    /** v1.51bs：forceNew=true 时直接新建，不检查空白对话（欢迎页点对话卡片用） */
    public void newConversation(boolean forceNew) {
        com.aicodestudio.ide.ai.util.DebugLogger.ensureFile();
        com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                "newConversation enter: forceNew=" + forceNew
                + " isBlank=" + isBlankConversation()
                + " currentConvKey=" + currentConvKey
                + " isAiGenerating=" + isAiGenerating
                + " managerBusy=" + (chatManager == null ? "null" : chatManager.isBusy()));
        // v1.51am：如果当前已经是空白对话，就不重复新建（避免用户点多次对话卡片产生一堆空对话）
        if (!forceNew && isBlankConversation()) {
            // 只是清空 UI 重新显示欢迎消息，不换 key
            clearMessagesUi();
            addMessageNoRecord(WELCOME_TEXT, TYPE_AI);
            scrollToBottom();
            // 也要通知外部更新 UI（清空项目名显示）
            if (listener != null) {
                try { listener.onConversationReset(); } catch (Throwable ignored) {}
            }
            return;
        }
        // v1.51bu：直接读列表里最近一项的isBlank标记
        // 最近的是空白的 → 复用；不是空白的（有历史聊天）→ 新建
        if (!forceNew) {
            java.util.List<ConversationItem> recent = listChatConversations(getContext());
            if (!recent.isEmpty()) {
                ConversationItem last = recent.get(0);  // 最近的一个
                if (last.isBlank) {
                    openConversation(last.key, "");
                    return;
                }
            }
        }
        // v1.153af：AI 输出中途点新建——必须先立即停生成+序列号失效，
        // 否则旧生成线程的 token 会继续写进新对话界面（串台）
        stopGenerationForSwitch();
        if (chatManager != null) chatManager.bumpGenSeq();
        // v1.153af：换 key 之前先把旧对话历史存好（此时 currentConvKey 还是旧的），
        // 否则旧对话内容丢失/与新对话混在一起
        saveHistory(currentProjectPath);
        // v1.153ag：点新建也走完整遮罩切换（停→遮罩→存旧对话→后台切KV→渲染空新对话）。
        // 之前直接清UI切走、KV保存后台排队，马上切回时两次切换乱序把旧对话覆盖成空白。
        showSwitchingOverlay();
        updateSwitchProgress("停止当前对话中");
        updateSwitchProgress("保存聊天历史中");
        final String newKey = "chat_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(new java.util.Date());
        // v1.153at：严格串行——UI 层 key 等后台任务完成后在 finally 里再换，
        // 遮罩期间 currentConvKey 保持旧 key，收尾回调正确落旧对话
        if (chatManager == null) {
            currentProjectPath = "";
            currentConvKey = newKey;
            scenarioAStartProjects = snapshotSpaceProjects();
            clearMessagesUi();
            addMessageNoRecord(WELCOME_TEXT, TYPE_AI);
            hideSwitchingOverlay();
            if (listener != null) { try { listener.onConversationReset(); } catch (Throwable ignored) {} }
            scrollToBottom();
            return;
        }
        chatManager.setWorkDir("");
        final com.aicodestudio.ide.ai.chat.AIChatManager.SwitchProgressListener prog =
                new com.aicodestudio.ide.ai.chat.AIChatManager.SwitchProgressListener() {
            @Override public void onSwitchStep(String step) {
                post(new Runnable() { @Override public void run() { updateSwitchProgress(step); } });
            }
        };
        final long mySeq = ++switchSeq;
        chatManager.runOnExecutor(new Runnable() {
            @Override public void run() {
                try { chatManager.switchConversation(newKey, prog); } catch (Throwable ignored) {}
                finally {
                    post(new Runnable() {
                        @Override public void run() {
                            if (mySeq != switchSeq) return;
                            // v1.153at：后台 KV 切完后才换 UI key
                            currentProjectPath = "";
                            currentConvKey = newKey;
                            scenarioAStartProjects = snapshotSpaceProjects();
                            try {
                                clearMessagesUi();
                                addMessageNoRecord(WELCOME_TEXT, TYPE_AI);
                            } catch (Throwable ignored) {}
                            saveHistory("");
                            updateSwitchProgress("会话切换完成");
                            hideSwitchingOverlay();
                            if (listener != null) { try { listener.onConversationReset(); } catch (Throwable ignored) {} }
                            scrollToBottom();
                        }
                    });
                }
            }
        });
    }

    /** v1.153z：判断目标对话是否有实质内容（历史聊天或 KV），空对话打开时不显示切换进度遮罩 */
    private boolean conversationHasContent(String key) {
        try {
            com.aicodestudio.ide.ai.core.SessionManager sm =
                    com.aicodestudio.ide.ai.core.SessionManager.get(getContext());
            java.util.List<Object[]> msgs = sm.loadMessages(key);
            if (msgs != null && !msgs.isEmpty()) return true;
        } catch (Throwable ignored) {}
        try {
            java.io.File kv = new java.io.File(historyDir(), key + ".kv");
            if (kv.exists() && kv.length() > 0) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    /** 打开历史对话：key=历史文件基名；projectPath 非空=绑定项目 */
    public void openConversation(String key, String projectPath) {
        openConversation(key, projectPath, -1, null);
    }

    /**
     * v1.153ax：打开历史对话（切换严格串行）。
     * onDone 在后台切换（停→存→切→开→载）全部完成后、UI key 换好、遮罩隐藏前回调（UI 线程）。
     * v1.153az：targetChatMode 为目标会话模式（闲聊/工作任务），在切换任务内部、加载历史前
     * 就切好（AIChatManager.switchConversation 带模式重载），按 目标key+目标模式 读该会话自己
     * 的历史/KV。onDone 只更新顶栏按钮文字，绝不再调 setChatMode（其内部 clear+loadHistory
     * 会把刚加载的目标会话历史清掉重渲染——旧"切过去历史空白"根因）。
     */
    public void openConversation(String key, String projectPath, int targetChatMode,
                                 final Runnable onDone) {
        if (key == null || key.isEmpty()) return;
        if (switching) return; // v1.151：切换进行中，忽略重复点击
        com.aicodestudio.ide.ai.util.DebugLogger.ensureFile();
        com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                "openConversation enter: key=" + key + " projectPath=" + projectPath
                + " currentConvKey=" + currentConvKey
                + " switching=" + switching
                + " isAiGenerating=" + isAiGenerating
                + " managerBusy=" + (chatManager == null ? "null" : chatManager.isBusy()));
        // v1.153ak：无条件先完整执行"停止按钮"逻辑——引擎 abort + token 回调复位 +
        // 按钮/动画/卡片清理 + genSeq 作废旧循环。绝不允许旧生成继续跑（会串到新会话）。
        // 之后再判断：空对话且未生成 → 快速切（不遮罩）；否则遮罩完整流程。
        stopGenerationForSwitch();
        if (chatManager != null) chatManager.bumpGenSeq();
        boolean targetEmpty = !conversationHasContent(key);
        // v1.153al：切换加载日志——记录目标 key、是否空对话、SQLite 实际消息数，排查"新对话搬旧内容"
        try {
            com.aicodestudio.ide.ai.core.SessionManager sm =
                    com.aicodestudio.ide.ai.core.SessionManager.get(getContext());
            java.util.List<Object[]> msgs = sm.loadMessages(key);
            com.aicodestudio.ide.ai.util.DebugLogger.log("AGENT", "openConversation: key=" + key
                    + " targetEmpty=" + targetEmpty + " sqliteMsgs=" + (msgs == null ? 0 : msgs.size()));
        } catch (Throwable ignored) {}
        boolean currentlyGenerating = isAiGenerating
                || (chatManager != null && chatManager.isBusy());
        com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                "openConversation branch: targetEmpty=" + targetEmpty
                + " currentlyGenerating=" + currentlyGenerating);
        if (targetEmpty && !currentlyGenerating) {
            saveHistory(currentProjectPath);
            currentProjectPath = (projectPath == null) ? "" : projectPath;
            currentConvKey = key;
            scenarioAStartProjects = snapshotSpaceProjects();
            loadHistory(currentProjectPath); // UI 立即渲染新对话内容（SQLite 快）
            if (chatManager != null) {
                chatManager.setWorkDir(currentProjectPath);
                // v1.153ad：switchConversation 内含 13MB KV 导出(4秒+)，必须后台跑——
                // 之前主线程同步执行导致界面冻结 4-8 秒，用户以为"卡死/点哪个对话都不换"
                final String fastKey = currentConvKey;
                final Runnable done = onDone;
                final int targetMode = targetChatMode;
                chatManager.runOnExecutor(new Runnable() {
                    @Override public void run() {
                        try { chatManager.switchConversation(fastKey, targetMode, null); } catch (Throwable ignored) {}
                        finally {
                            // v1.153ax：后台 KV 重载完成后再回调（严格串行，避免模式 KV 并发错位）
                            if (done != null) {
                                post(new Runnable() { @Override public void run() { done.run(); } });
                            }
                        }
                    }
                });
            } else if (onDone != null) {
                onDone.run();
            }
            return;
        }
        // v1.153w：分步切换——先停输出 → 遮罩 → 保存历史 → 后台切 KV → 渲染 → 完成
        stopGenerationForSwitch();
        if (chatManager != null) chatManager.bumpGenSeq(); // v1.153af：旧生成 token 不进新对话
        showSwitchingOverlay();
        updateSwitchProgress("停止当前对话中");
        updateSwitchProgress("保存聊天历史中");
        saveHistory(currentProjectPath); // 用旧 key 保存当前会话（SQLite 快，遮罩已挡住界面）
        final String oldProject = currentProjectPath;
        final String newProject = (projectPath == null) ? "" : projectPath;
        final String newKey = key;
        // v1.153as：严格串行——UI 层 currentConvKey 必须保持旧 key，直到后台任务
        // （停生成→存旧KV→载新KV）全部完成后，在 finally post 里才换。
        // 提前换 key 会导致遮罩期间 onResponseComplete 收尾回调的 sameConv 判断失真、
        // 且 UI 层"以为已切新对话"而 KV 还在跑（半并行错位）。
        if (chatManager == null) {
            currentProjectPath = newProject;
            currentConvKey = newKey;
            scenarioAStartProjects = snapshotSpaceProjects();
            loadHistory(newProject);
            hideSwitchingOverlay();
            if (onDone != null) { try { onDone.run(); } catch (Throwable ignored) {} }
            return;
        }
        chatManager.setWorkDir(newProject);
        // v1.153x：manager 内部步骤（保存记录/保存KV/关闭会话/加载历史/加载KV）逐个上报
        final com.aicodestudio.ide.ai.chat.AIChatManager.SwitchProgressListener prog =
                new com.aicodestudio.ide.ai.chat.AIChatManager.SwitchProgressListener() {
            @Override public void onSwitchStep(String step) {
                post(new Runnable() {
                    @Override public void run() { updateSwitchProgress(step); }
                });
            }
        };
        // v1.151：同会话（从设置/后台/编辑器返回）——KV 幂等重载（快），仍在后台执行不卡 UI
        if (key.equals(chatManager.getCurrentHistoryKey())) {
            updateSwitchProgress("加载会话 KV 中");
            final long mySeq = ++switchSeq;
            // v1.153ao：超时不再提前隐藏遮罩——生成线程刚被 stop，native abort 生效需
            // 1-2 秒（warm 已加 abort 检查），切换任务在 executor 排队等待；提前 hide 会
            // 让用户看到"遮罩没了但还显示旧对话"的割裂。改为：25s 只更新进度提示，
            // 45s 极端情况也只提示不切 UI——严格串行：保存未完成绝不提前切 UI/换 key，
            // 收尾完全由后台任务 finally 完成（rc=-4 不回退修复后任务 1-2 秒必完成）。
            final Runnable unlock = new Runnable() {
                @Override public void run() {
                    if (switching) {
                        com.aicodestudio.ide.ai.util.DebugLogger.log("AGENT",
                                "switch(same) timeout, keep overlay");
                        updateSwitchProgress("切换仍在进行中，请稍候…");
                        postDelayed(new Runnable() {
                            @Override public void run() {
                                if (!switching) return;
                                // v1.153aw：不再 force complete——保存未完成前换 UI key/加载
                                // 会把旧内容带进新对话（违反"保存完整后才切"的严格串行）。
                                // 继续等后台任务 finally 收尾；rc=-4 中断不回退后任务必完成。
                                com.aicodestudio.ide.ai.util.DebugLogger.log("AGENT",
                                        "switch(same) 45s 未完成：继续等待后台任务收尾（不提前切UI）");
                                updateSwitchProgress("切换超时，正在等待任务完成…");
                            }
                        }, 20000);
                    }
                }
            };
            postDelayed(unlock, 25000);
            chatManager.runOnExecutor(new Runnable() {
                @Override public void run() {
                    try {
                        chatManager.switchConversation(newKey, targetChatMode, prog);
                    } catch (Throwable t) {
                        com.aicodestudio.ide.ai.util.DebugLogger.log("AGENT",
                                "switchConversation(same) err: " + t.getMessage());
                    } finally {
                        post(new Runnable() {
                            @Override public void run() {
                                if (mySeq != switchSeq) return;
                                removeCallbacks(unlock);
                                // v1.153as：后台 KV 全部就绪后才换 UI key，再渲染新对话
                                com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                                        "switch(same) finally post: 换UI key " + newKey);
                                currentConvKey = newKey;
                                currentProjectPath = newProject;
                                scenarioAStartProjects = snapshotSpaceProjects();
                                loadHistory(newProject);
                                updateSwitchProgress("会话切换完成");
                                hideSwitchingOverlay();
                                // v1.153ax：后台切换全部完成后回调（恢复会话模式等）
                                if (onDone != null) { try { onDone.run(); } catch (Throwable ignored) {} }
                            }
                        });
                    }
                }
            });
            return;
        }
        // v1.151：真正切换窗口——KV 保存+加载放后台线程（滑后台也继续，不被生命周期打断）
        updateSwitchProgress("切换会话缓存中");
        final long mySeq = ++switchSeq;
        // v1.153aw：超时只提示不切 UI——严格串行：停→存→切→开→载，保存未完成
        // 绝不提前换 UI key/加载（否则旧内容污染新对话）。后台任务 1-2 秒必完成，
        // 收尾完全由 finally 执行；此处仅为极端情况（原生死锁）留提示，不强行切。
        final Runnable unlock = new Runnable() {
            @Override public void run() {
                if (switching) {
                    com.aicodestudio.ide.ai.util.DebugLogger.log("AGENT",
                            "switch timeout, keep overlay");
                    updateSwitchProgress("切换仍在进行中，请稍候…");
                    postDelayed(new Runnable() {
                        @Override public void run() {
                            if (!switching) return;
                            com.aicodestudio.ide.ai.util.DebugLogger.log("AGENT",
                                    "switch 45s 未完成：继续等待后台任务收尾（不提前切UI）");
                            updateSwitchProgress("切换超时，正在等待任务完成…");
                        }
                    }, 20000);
                }
            }
        };
        postDelayed(unlock, 25000);
        chatManager.runOnExecutor(new Runnable() {
            @Override public void run() {
                try {
                    // v1.153az：带目标模式——内部加载历史前切好 modeSuffix
                    chatManager.switchConversation(newKey, targetChatMode, prog); // 内部：save旧KV+load新KV，步骤逐条上报
                } catch (Throwable t) {
                    com.aicodestudio.ide.ai.util.DebugLogger.log("AGENT",
                            "switchConversation err: " + t.getMessage());
                } finally {
                    post(new Runnable() {
                        @Override public void run() {
                            if (mySeq != switchSeq) return; // 序列号防旧任务解锁新切换
                            removeCallbacks(unlock);
                            // v1.153as：严格串行——后台 KV（停→存旧→载新）全部完成后，
                            // 才换 UI 层 key、渲染新对话、消失遮罩。此前 currentConvKey 一直是旧 key，
                            // onResponseComplete 收尾正确落旧 key，不会污染新对话。
                            com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                                    "switch(real) finally post: 后台KV已完成，换UI key old="
                                    + currentConvKey + " new=" + newKey
                                    + " switching=" + switching);
                            currentConvKey = newKey;
                            currentProjectPath = newProject;
                            scenarioAStartProjects = snapshotSpaceProjects();
                            try {
                                loadHistory(newProject); // UI 线程渲染新对话历史
                            } catch (Throwable ignored) {}
                            updateSwitchProgress("会话切换完成");
                            hideSwitchingOverlay();
                            com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                                    "switch(real) UI切换完成，遮罩已隐藏");
                            // v1.153ax：后台切换（停→存→切→开→载）全部完成后回调
                            if (onDone != null) { try { onDone.run(); } catch (Throwable ignored) {} }
                        }
                    });
                }
            }
        });
    }

    // ===== v1.151：切换窗口遮罩（锁操作 + "正在切换"提示）=====
    private FrameLayout switchOverlay;
    private TextView switchOverlayText;
    private ProgressBar switchProgressBar;   // v1.153ap：横向进度条
    private TextView switchPercentText;      // v1.153ap：百分比
    private long switchSeq = 0;
    private volatile boolean switching = false;
    // v1.153ap：KV 保存/加载是 native 阻塞操作（几秒），期间无分块回调。
    // 用"蠕动动画"让进度条缓慢前进，避免看起来卡死；收到下一步 step 立即停动画跳真实进度。
    private Runnable switchDriftRunnable = null;
    private int driftTargetPct = 0;

    /** v1.153w：切换进行中（外部/生命周期判断用，切换线程在后台继续不受影响） */
    public boolean isSwitching() { return switching; }

    private void stopDrift() {
        if (switchDriftRunnable != null) {
            removeCallbacks(switchDriftRunnable);
            switchDriftRunnable = null;
        }
    }

    /** v1.153ap：长耗时阶段（KV 保存/加载）启动进度条缓慢蠕动，上限 driftTarget */
    private void startDrift(int targetPct) {
        stopDrift();
        driftTargetPct = targetPct;
        switchDriftRunnable = new Runnable() {
            @Override public void run() {
                if (switchProgressBar == null || !switching) return;
                int cur = switchProgressBar.getProgress();
                if (cur < driftTargetPct) {
                    switchProgressBar.setProgress(cur + 1);
                    if (switchPercentText != null) switchPercentText.setText(cur + 1 + "%");
                    postDelayed(this, 250); // 每 250ms 推 1%，KV 阶段看起来在持续跑
                }
            }
        };
        postDelayed(switchDriftRunnable, 250);
    }

    /** v1.153ap：按步骤文字映射进度百分比（0-100） */
    private static int percentForStep(String step) {
        if (step == null) return 5;
        if (step.startsWith("会话切换完成")) return 100;
        if (step.startsWith("停止当前对话")) return 8;
        if (step.startsWith("保存聊天历史")) return 18;
        // v1.153aq：UI 线程同步打"切换会话缓存中"时后台还在排队等生成停止，
        // 不能直接跳 70%（用户会以为一下就完成了）——给 30% 起步并启动蠕动
        if (step.startsWith("切换会话缓存")) return 30;
        if (step.startsWith("保存会话记录")) return 36;
        if (step.startsWith("保存会话 KV")) return 42;
        if (step.startsWith("保存 KV 完成")) return 58;
        if (step.startsWith("关闭当前会话")) return 68;
        if (step.startsWith("加载新会话历史")) return 80;
        if (step.startsWith("加载 KV") || step.startsWith("加载会话 KV")) return 90;
        if (step.startsWith("加载会话缓存")) return 75;
        return 10; // 其他步骤：保持小步推进，不退
    }

    /** v1.153w：更新切换遮罩的分步进度文字（停止→保存→切换缓存→完成） */
    private void updateSwitchProgress(String msg) {
        if (switchOverlayText != null) switchOverlayText.setText(msg);
        // v1.153ap：同步推进进度条与百分比
        int pct = percentForStep(msg);
        // v1.153aq：长耗时阶段启动蠕动动画——
        //   "切换会话缓存中"=后台排队等生成停止（可能 1-2 秒）→ 30%→40% 蠕动
        //   "保存会话 KV 中"=native 导出（几秒）→ 42%→52% 蠕动
        //   "加载 KV 中"=native 导入 → 90%→96% 蠕动
        boolean drift = false;
        int driftTarget = 0;
        if (msg != null && msg.startsWith("切换会话缓存")) {
            drift = true; driftTarget = 40;
        } else if (msg != null && msg.startsWith("保存会话 KV")) {
            drift = true; driftTarget = 52;
        } else if (msg != null && (msg.startsWith("加载 KV") || msg.startsWith("加载会话 KV"))) {
            drift = true; driftTarget = 96;
        }
        if (drift) {
            stopDrift();
            if (switchProgressBar != null) switchProgressBar.setProgress(pct);
            if (switchPercentText != null) switchPercentText.setText(pct + "%");
            startDrift(driftTarget);
        } else {
            stopDrift();
            if (switchProgressBar != null) {
                switchProgressBar.setProgress(Math.max(switchProgressBar.getProgress(), pct));
            }
            if (switchPercentText != null) {
                switchPercentText.setText(switchProgressBar != null ? switchProgressBar.getProgress() + "%" : pct + "%");
            }
        }
    }

    /** v1.153v：向上找宿主 Activity（遮罩要挂到 Activity 根视图，全屏覆盖且不挤乱 ChatPanel 内部布局） */
    private android.app.Activity findHostActivity() {
        android.content.Context c = getContext();
        while (c instanceof android.content.ContextWrapper) {
            if (c instanceof android.app.Activity) return (android.app.Activity) c;
            c = ((android.content.ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    private void showSwitchingOverlay() {
        if (switchOverlay == null) {
            switchOverlay = new FrameLayout(getContext());
            switchOverlay.setBackgroundColor(0x66000000); // 半透明黑，拦截所有点击
            switchOverlay.setClickable(true);
            LinearLayout box = new LinearLayout(getContext());
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            int pad = dp(24);
            box.setPadding(pad, pad, pad, pad);
            // v1.153y：第一行固定大字"正在切换会话"（切换全程不变）
            TextView titleTv = new TextView(getContext());
            titleTv.setText("正在切换会话");
            titleTv.setTextColor(0xFFFFFFFF);
            titleTv.setTextSize(17);
            titleTv.setGravity(Gravity.CENTER);
            // v1.153ap：横向进度条（determinate，0-100）替代原来的转圈
            ProgressBar pb = new ProgressBar(getContext(), null, android.R.attr.progressBarStyleHorizontal);
            pb.setMax(100);
            pb.setProgress(0);
            pb.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFB388FF)); // 紫色进度
            pb.getProgressDrawable().setAlpha(230);
            // v1.153ap：百分比文字（居中，16sp 小一号）
            TextView pctTv = new TextView(getContext());
            pctTv.setText("0%");
            pctTv.setTextColor(0xFFFFFFFF);
            pctTv.setTextSize(16);
            pctTv.setGravity(Gravity.CENTER);
            pctTv.setPadding(0, dp(6), 0, dp(4));
            // v1.153y：第三行小字分步进度（停止→保存→关闭→加载，逐个刷新，含 KV 大小）
            TextView tv = new TextView(getContext());
            tv.setText("正在准备…");
            tv.setTextColor(0xFFB0BEC5);
            tv.setTextSize(12);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(0, dp(4), 0, 0);
            switchOverlayText = tv; // v1.153w：分步进度提示复用此文字
            switchProgressBar = pb;
            switchPercentText = pctTv;
            // 卡片背景：圆角深色底，遮住后面界面更干净
            android.widget.LinearLayout card = new android.widget.LinearLayout(getContext());
            card.setOrientation(android.widget.LinearLayout.VERTICAL);
            card.setBackground(new android.graphics.drawable.GradientDrawable() {{
                setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                setColor(0xE6263238);
                setCornerRadius(dp(14));
            }});
            card.setPadding(dp(28), dp(22), dp(28), dp(22));
            card.addView(titleTv, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            card.addView(pctTv, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            card.addView(pb, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(10)));
            card.addView(tv, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            box.addView(card, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
            switchOverlay.addView(box, flp);
        }
        // v1.153ap：每次显示时重置进度到 0%（新一轮切换）
        if (switchProgressBar != null) switchProgressBar.setProgress(0);
        if (switchPercentText != null) switchPercentText.setText("0%");
        if (switchOverlayText != null) switchOverlayText.setText("正在准备…");
        android.app.Activity host = findHostActivity();
        if (switchOverlay.getParent() == null) {
            if (host != null) {
                // 挂到 Activity 根视图：全屏覆盖，不影响 ChatPanel 内部 LinearLayout 测量
                android.view.ViewGroup root = (android.view.ViewGroup) host.getWindow().getDecorView();
                root.addView(switchOverlay, new android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            } else {
                // 兜底：挂到 ChatPanel，放在最前并置顶绘制
                addView(switchOverlay, 0, new LayoutParams(
                        LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
                switchOverlay.bringToFront();
            }
        }
        switchOverlay.setVisibility(View.VISIBLE);
        switchOverlay.bringToFront();
        switching = true;
    }

    private void hideSwitchingOverlay() {
        switching = false;
        stopDrift(); // v1.153ap：停止进度条蠕动
        if (switchOverlay != null) switchOverlay.setVisibility(View.GONE);
    }

    /** 删除指定历史对话（对话列表长按删除） */
    public void deleteConversation(String key) {
        if (key == null || key.isEmpty()) return;
        try {
            // v1.51bx：如果删的是当前正在打开的对话，立刻把currentConvKey置空
            // 这样后续openConversation()里的saveHistory()不会把刚删的内容写回已删文件
            if (key.equals(currentConvKey)) {
                currentConvKey = null;
                currentProjectPath = "";
                clearMessagesUi();
                mMessages.clear();
            }
            new File(historyDir(), key + ".json").delete();
            new File(historyDir(), key + ".meta.json").delete();
            // v1.152：同步删除该对话关联的 KV 缓存（工作模式 + 闲聊模式两份，含 .meta）
            new File(historyDir(), key + ".kv").delete();
            new File(historyDir(), key + ".kv.meta").delete();
            new File(historyDir(), key + "_chat.kv").delete();
            new File(historyDir(), key + "_chat.kv.meta").delete();
        } catch (Throwable ignored) {
        }
    }

    /** 保存当前会话历史（退出 / 切换时调用；含 AI 新建项目自动绑定） */
    /** v1.51bb：获取当前对话 key（侧边栏高亮用） */
    public String getCurrentConvKey() {
        return currentConvKey;
    }

    /** v1.51cg：绑定状态变化回调 */
    public interface BindingListener {
        void onProjectBound(String projectPath);
        void onConversationReset();
    }
    private BindingListener bindingListener;
    public void setBindingListener(BindingListener l) { this.bindingListener = l; }

    /** v1.51ce：获取当前对话绑定的项目路径 */
    public String getCurrentProjectPath() {
        return currentProjectPath;
    }

    public void saveCurrentHistory() {
        saveHistory(currentProjectPath);
    }

    /** v1.150：保存当前会话 KV 落盘（返回主界面/离开对话页前调用，再进入该对话时恢复增量续跑） */
    public void saveSessionKv() {
        if (chatManager != null) chatManager.saveSessionKv();
    }

    /** v1.51o：显式清空本地模型 KV 缓存（回主界面 / 切换对话 / 切换项目 / 新建项目后进入时调用） */
    public void clearKvCache() {
        if (chatManager != null) chatManager.clearKvCache();
    }

    /**
     * v1.51o：从编辑器/欢迎页切回本对话页时同步外部项目上下文。
     * enterProject = 本 Activity 创建时 WorkspaceState 里的项目（进入时的状态）。
     * 仅当 WorkspaceState 的项目路径已改变（用户在编辑器创建/打开了新项目）且与当前会话不同时，
     * 才重新绑定该项目并清 KV；其余情况（含欢迎页直接进对话）保持场景A 空白，绝不自动绑残留项目。
     */
    /** 打开项目后切到对话页时调用：读 WorkspaceState，绑定项目（如果和当前不同） */
    public void syncExternalProject() {
        try {
            String wsPath = com.aicodestudio.ide.ai.core.WorkspaceState.get().getProjectPath();
            if (wsPath == null || wsPath.isEmpty()) return;
            if (wsPath.equals(currentProjectPath)) return;        // 已绑定该项目：无需切换
            setCurrentProject(wsPath);                            // 内部 switchConversation → clearKvCache
        } catch (Throwable ignored) {
        }
    }

    /** v1.153u：切换对话前完整停止生成——引擎停止 + 工具卡片动画/定时器取消 + 按钮复位。
     *  所有切换入口（新建/打开/绑定项目/渲染历史）都经过 clearMessagesUi，在此统一兜底。 */
    private void stopGenerationForSwitch() {
        com.aicodestudio.ide.ai.util.DebugLogger.log("FLOW",
                "stopGenerationForSwitch: managerBusy=" + (chatManager == null ? "null" : chatManager.isBusy())
                + " isAiGenerating=" + isAiGenerating);
        try {
            if (chatManager != null) chatManager.stopGeneration();
        } catch (Throwable ignored) {}
        cancelToolCardFlush();
        if (activeToolAnimator != null) {
            try { activeToolAnimator.cancel(); } catch (Throwable ignored) {}
            activeToolAnimator = null;
        }
        pendingToolDelta.setLength(0);
        activeToolCard = null;
        isAiGenerating = false;
        generationStopped = true;
        stopSpinAnimation();
        if (btnSend != null) {
            try {
                btnSend.setImageDrawable(getResources().getDrawable(
                        com.aicodestudio.ide.R.drawable.ic_send));
                btnSend.setBackground(makeCircleButtonBg(0xFF2196F3));
                btnSend.setPadding(dp(9), dp(9), dp(9), dp(9));
            } catch (Throwable ignored) {}
        }
    }

    private void clearMessagesUi() {
        // v1.153u：切换前先彻底停止 AI 输出（防止旧流式回调串台/推理线程继续跑导致卡顿发热）
        stopGenerationForSwitch();
        messageContainer.removeAllViews();
        mMessages.clear();
        userScrolledUp = false;
        isAiGenerating = false;
        updateSendButtonState(); // v1.137：切换对话 → 恢复蓝色发送按钮
        if (btnScrollDown != null) btnScrollDown.setVisibility(View.GONE);
    }

    /** 当前消息首条 user 文本（列表预览用） */
    private String firstTextPreview() {
        for (ChatMessage m : mMessages) {
            if (m.type == TYPE_USER && m.text != null && !m.text.trim().isEmpty()) {
                String t = m.text.trim().replace('\n', ' ');
                return t.length() > 28 ? t.substring(0, 28) + "…" : t;
            }
        }
        return "";
    }

    /** v1.51cj：调试用 - 清空所有聊天历史文件 */
    public void clearAllHistory() {
        try {
            java.io.File dir = historyDir();
            if (dir.exists() && dir.isDirectory()) {
                File[] files = dir.listFiles();
                if (files != null) for (File f : files) f.delete();
            }
            clearMessagesUi();
            addMessageNoRecord("所有历史已清空，请输入新需求开始对话。", TYPE_AI);
            currentConvKey = null;
            currentProjectPath = "";
            if (bindingListener != null) bindingListener.onConversationReset();
        } catch (Throwable t) {}
    }

    /** 保存当前会话历史到指定项目（纯对话且 AI 已建项目 → 自动绑定项目） */
    public void saveHistory(String projectPath) {
        if (projectPath == null) return;
        try {
            // v1.51n：场景A（未绑定项目）但 AI 在本对话中新建了项目 → 自动绑定并归入项目列表
            if (projectPath.isEmpty() && !bindingProject) {
                String bound = detectAiCreatedProject();
                if (bound != null) {
                    bindToProject(bound);
                    return;
                }
            }
            String key = currentConvKey;
            if (key == null || key.isEmpty()) key = "default";
            saveToFile(key, projectPath);
        } catch (Throwable ignored) {
        }
    }

    /** 写历史文件 + meta（key 已定） */
    /** v1.90：保存消息到 SQLite（key = sessionId） */
    private void saveToFile(String key, String projectPath) {
        try {
            java.util.List<Integer> types = new java.util.ArrayList<Integer>();
            java.util.List<String> texts = new java.util.ArrayList<String>();
            for (ChatMessage m : mMessages) {
                // 过滤临时消息（正在思考 / 空文本）
                if (m.type == TYPE_AI
                        && (m.text.length() == 0
                            || m.text.equals("正在思考...")
                            || m.text.startsWith("正在思考"))) {
                    // v1.91e：生成中切走时 record 仍是"正在思考"，用已生成内容补存，
                    // 否则历史只剩用户消息、AI 完整回复丢失
                    if (currentPartialReply != null && !currentPartialReply.isEmpty()
                            && generatingConvKey != null && generatingConvKey.equals(key)) {
                        types.add(TYPE_AI);
                        texts.add(currentPartialReply);
                    }
                    continue;
                }
                types.add(m.type);
                texts.add(m.text);
            }
            // 存到 SQLite messages 表
            com.aicodestudio.ide.ai.core.SessionManager sm =
                    com.aicodestudio.ide.ai.core.SessionManager.get(getContext());
            sm.saveMessages(key, types, texts);
        } catch (Throwable ignored) {
        }
    }

    /** 清空当前界面并加载指定会话的历史聊天（v1.90：从 SQLite 读） */
    public void loadHistory(String projectPath) {
        clearMessagesUi();
        try {
            String key = (currentConvKey != null && !currentConvKey.isEmpty())
                    ? currentConvKey
                    : ((projectPath == null || projectPath.isEmpty()) ? "default" : historyFileNameBase(projectPath));
            com.aicodestudio.ide.ai.core.SessionManager sm =
                    com.aicodestudio.ide.ai.core.SessionManager.get(getContext());
            java.util.List<Object[]> msgs = sm.loadMessages(key);
            boolean any = false;
            for (Object[] row : msgs) {
                int type = (Integer) row[0];
                String text = (String) row[1];
                if (text == null) text = "";
                if (type == TYPE_AI && (text.length() == 0
                        || text.equals("正在思考...") || text.startsWith("正在思考"))) {
                    continue;
                }
                View histRow = addMessageInternal(text, type, true);
                // v1.100：历史 AI 消息立即定型为单流内联（思考/正文/工具卡片按原文顺序）
                if (type == TYPE_AI) finalizeHistoricalRow(histRow, text);
                any = true;
            }
            if (!any) {
                // v1.153ba：SQLite 被旧 bug 空覆盖时，回退从 manager.messageHistory 恢复渲染。
                // v1.153bc：必须校验 manager 的 currentHistoryKey 已切到本会话——
                // 快速切分支里 UI key 先换、后台 switchConversation 还没跑，此时 manager.messageHistory
                // 还是上一个会话的内容，不校验就会把旧消息搬进新对话。
                try {
                    if (chatManager != null
                            && currentConvKey != null
                            && currentConvKey.equals(chatManager.getCurrentHistoryKey())) {
                        java.util.List<com.aicodestudio.ide.ai.chat.AIChatManager.ChatMessage> hist =
                                chatManager.getMessageHistory();
                        for (com.aicodestudio.ide.ai.chat.AIChatManager.ChatMessage m : hist) {
                            if (m == null || "system".equals(m.role)) continue;
                            if (m.content == null || m.content.isEmpty()) continue;
                            if (m.content.startsWith("正在思考")) continue;
                            int type = "user".equals(m.role) ? TYPE_USER : TYPE_AI;
                            View histRow = addMessageInternal(m.content, type, true);
                            if (type == TYPE_AI) finalizeHistoricalRow(histRow, m.content);
                            any = true;
                        }
                    }
                } catch (Throwable ignored) {}
            }
            if (!any) {
                addMessageNoRecord(WELCOME_TEXT, TYPE_AI);
            }
        } catch (Throwable t) {
            addMessageNoRecord(WELCOME_TEXT, TYPE_AI);
        }
        scrollToBottom();
    }

    /** 删除某项目的历史聊天文件（清空对话时调用） */
    public void deleteHistory(String projectPath) {
        if (projectPath == null || projectPath.length() == 0) return;
        try {
            File f = new File(historyDir(), historyFileName(projectPath));
            if (f.exists()) f.delete();
            File m = new File(historyDir(), historyFileNameBase(projectPath) + ".meta.json");
            if (m.exists()) m.delete();
            // v1.51ci：从映射表移除并保存
            projectConvMap.remove(projectPath);
            saveConvMap(getContext());
        } catch (Throwable ignored) {
        }
    }

    /** 切换当前绑定项目：保存旧项目历史 → 绑定并加载新项目历史 */
    /** v1.51bi：用户主动创建项目 → 新建项目对话并绑定 */
    public void bindNewProject(String projectPath) {
        if (projectPath == null || projectPath.isEmpty()) return;
        saveHistory(currentProjectPath);
        // 新建项目对话 key
        currentProjectPath = projectPath;
        currentConvKey = historyFileNameBase(projectPath);
        // 存储映射
        projectConvMap.put(projectPath, currentConvKey);
        saveConvMap(getContext());  // v1.51ci：保存映射
        // 初始化历史文件
        scenarioAStartProjects = snapshotSpaceProjects();
        clearMessagesUi();
        addMessageNoRecord(WELCOME_TEXT, TYPE_AI);
        if (chatManager != null) {
            chatManager.setWorkDir(currentProjectPath);
            final String bindKey = currentConvKey;
            chatManager.runOnExecutor(new Runnable() {
                @Override public void run() {
                    try { chatManager.switchConversation(bindKey); } catch (Throwable ignored) {}
                }
            });
        }
        // 通知外部更新 UI
        if (listener != null) {
            try { listener.onProjectBound(projectPath); } catch (Throwable ignored) {}
        }
    }

    public void setCurrentProject(String projectPath) {
        // v1.51an：如果已经绑定了同一个项目，就不重复切换（避免多次打开同一项目产生多个对话）
        String normalized = (projectPath == null) ? "" : projectPath;
        if (normalized.equals(currentProjectPath)) return;
        currentProjectPath = normalized;
        if (bindingListener != null) bindingListener.onProjectBound(normalized);
        saveHistory(currentProjectPath);
        currentConvKey = (currentProjectPath.isEmpty()) ? null : historyFileNameBase(currentProjectPath);
        scenarioAStartProjects = snapshotSpaceProjects();
        loadHistory(currentProjectPath);
        // v1.51ch：绑定项目后刷新UI消息气泡，不然聊天界面还是上一个对话的内容
        // v1.135：改用合并历史（工作+闲聊按时间戳排序）渲染完整时间线
        renderMergedHistory();
        // v1.49：同步对话工作目录给 AI 调度器（场景B=项目路径；空=场景A，AI 在全局工作空间创建）
        if (chatManager != null) {
            chatManager.setWorkDir(currentProjectPath);
            // v1.51ch：传MD5后的文件名基名，和保存的历史文件名一致，不然加载错文件
            final String convKey = (currentProjectPath.isEmpty()) ? "default" : historyFileNameBase(currentProjectPath);
            // v1.153ad：后台执行，避免 13MB KV 导出冻结 UI
            chatManager.runOnExecutor(new Runnable() {
                @Override public void run() {
                    try { chatManager.switchConversation(convKey); } catch (Throwable ignored) {}
                }
            });
        }
        // v1.51aq：通知外部更新 UI（项目名显示）
        if (listener != null) {
            try { listener.onProjectBound(currentProjectPath); } catch (Throwable ignored) {}
        }
    }

    // ===== v1.51n AI 新建项目检测 + 自动绑定 =====

    /** 扫描全局工作空间的项目目录名集合 */
    private java.util.Set<String> snapshotSpaceProjects() {
        java.util.Set<String> set = new java.util.HashSet<>();
        try {
            java.io.File root = com.aicodestudio.ide.ai.core.ProjectManager.getProjectsRoot(getContext());
            java.io.File[] ds = root.listFiles();
            if (ds != null) for (java.io.File d : ds) {
                if (d.isDirectory() && !d.getName().startsWith(".")) set.add(d.getName());
            }
        } catch (Throwable ignored) {
        }
        return set;
    }

    /** 检测本对话中 AI 新建的项目（对比对话开始时空间项目集合）→ 返回最新项目路径；无则 null */
    private String detectAiCreatedProject() {
        try {
            java.util.Set<String> now = snapshotSpaceProjects();
            java.util.List<String> news = new ArrayList<>();
            for (String p : now) {
                if (!scenarioAStartProjects.contains(p)) news.add(p);
            }
            if (news.isEmpty()) return null;
            java.io.File root = com.aicodestudio.ide.ai.core.ProjectManager.getProjectsRoot(getContext());
            String best = null;
            long bestT = -1;
            for (String p : news) {
                java.io.File d = new java.io.File(root, p);
                if (d.lastModified() > bestT) { bestT = d.lastModified(); best = d.getAbsolutePath(); }
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 场景A 对话中 AI 创建了项目 → 绑定项目：迁移消息到项目历史，切换会话 */
    private void bindToProject(String path) {
        if (path == null || bindingProject) return;
        bindingProject = true;
        try {
            String key = historyFileNameBase(path);
            saveToFile(key, path);
            if (currentConvKey != null && !currentConvKey.equals(key)) {
                deleteConversation(currentConvKey); // 删除纯对话旧文件，防列表重复
            }
            currentProjectPath = path;
            currentConvKey = key;
            projectConvMap.put(path, key);  // v1.51bi：存入映射表
            scenarioAStartProjects = snapshotSpaceProjects();
            loadHistory(path);
            if (chatManager != null) {
                chatManager.setWorkDir(path);
                // v1.51ci：传MD5后的key，和保存的历史文件名一致，不要传原始path
                // v1.153ad：后台执行，避免 13MB KV 导出冻结 UI
                final String bindKey2 = key;
                chatManager.runOnExecutor(new Runnable() {
                    @Override public void run() {
                        try { chatManager.switchConversation(bindKey2); } catch (Throwable ignored) {}
                    }
                });
            }
        } catch (Throwable ignored) {
        } finally {
            bindingProject = false;
        }
    }

    // ===== v1.51n 列表数据源（ChatActivity 侧边栏用） =====

    /** 读会话 meta（无则返回空 JSONObject） */
    private static org.json.JSONObject readMeta(File dir, String key) {
        try {
            File mf = new File(dir, key + ".meta.json");
            if (!mf.exists()) return new org.json.JSONObject();
            String content = "";
            java.io.FileInputStream fis = new java.io.FileInputStream(mf);
            byte[] buf = new byte[(int) mf.length()];
            int read = 0;
            while (read < buf.length) {
                int n = fis.read(buf, read, buf.length - read);
                if (n < 0) break;
                read += n;
            }
            fis.close();
            return new org.json.JSONObject(new String(buf, 0, read, "UTF-8"));
        } catch (Throwable t) {
            return new org.json.JSONObject();
        }
    }

    private static java.util.List<ConversationItem> sChatCache = null;
    private static java.util.List<ConversationItem> sProjCache = null;
    public static void invalidateConvCache() { sChatCache = null; sProjCache = null; }
    /** 纯对话列表 */
    public static java.util.List<ConversationItem> listChatConversations(Context ctx) {
        if (sChatCache != null) return new ArrayList<>(sChatCache);
        java.util.List<ConversationItem> out = new ArrayList<>();
        try {
            File dir = new File(ctx.getFilesDir(), "chat_history");
            if (!dir.exists()) return out;
            File[] fs = dir.listFiles();
            if (fs == null) return out;
            for (File f : fs) {
                String n = f.getName();
                if (!n.endsWith(".json") || n.endsWith(".meta.json")) continue;
                String key = n.substring(0, n.length() - 5);
                org.json.JSONObject meta = readMeta(dir, key);
                String pp = meta.optString("projectPath", "");
                if (!pp.isEmpty()) continue; // 绑定项目 → 项目列表
                // 纯对话：只显示 chat_* 开头的新会话（v1.51bg：去掉旧的 default/MD5 文件）
                if (!key.startsWith("chat_")) continue;
                long t = meta.optLong("time", 0);  // v1.51be：只用 meta 里的创建时间，不用文件最后修改时间
                if (t <= 0) continue;  // v1.51bt：过滤掉无有效时间的异常空文件（1970/1月1日那种）
                String preview = meta.optString("preview", "");
                if (preview.isEmpty()) preview = firstLineOf(f);
                String name = key.startsWith("chat_") ? formatConvTime(t) : "历史对话";
                ConversationItem item = new ConversationItem(key, name, "", t, preview);
                // v1.51bv：直接从meta读isBlank标记，不用再读整个json
                item.isBlank = meta.optBoolean("isBlank", true);
                out.add(item);
            }
            out.sort((a, b) -> Long.compare(b.time, a.time));
        } catch (Throwable ignored) {
        }
        sChatCache = new ArrayList<>(out);
        return out;
    }

    /** v1.51bp：判断某个 key 对应的对话是不是空白的（没有用户消息） */
    private static boolean isBlankConversationKey(android.content.Context ctx, String key) {
        try {
            File dir = new File(ctx.getFilesDir(), "chat_history");
            File f = new File(dir, key + ".json");
            if (!f.exists() || f.length() == 0) return true;
            org.json.JSONArray arr = new org.json.JSONArray(
                    new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject obj = arr.getJSONObject(i);
                if (obj.optInt("type", 0) == TYPE_USER) return false;  // 有用户消息 = 不是空白
            }
            return true;  // 没有用户消息 = 空白
        } catch (Throwable t) {
            return true;
        }
    }

    /** v1.51as：找上一个对话——如果是空白的就复用，不是就新建（只看最近的一个，不遍历所有） */
    public static String findBlankConversation(Context ctx) {
        try {
            File dir = new File(ctx.getFilesDir(), "chat_history");
            if (!dir.exists()) return null;
            File[] fs = dir.listFiles();
            if (fs == null) return null;
            // 找最近修改的 chat_* 文件
            File latest = null;
            for (File f : fs) {
                String n = f.getName();
                if (!n.endsWith(".json") || n.endsWith(".meta.json")) continue;
                String key = n.substring(0, n.length() - 5);
                if (!key.startsWith("chat_")) continue;
                // 排除绑定项目的
                org.json.JSONObject meta = readMeta(dir, key);
                if (!meta.optString("projectPath", "").isEmpty()) continue;
                if (latest == null || f.lastModified() > latest.lastModified()) {
                    latest = f;
                }
            }
            if (latest == null) return null;
            // 检查这个最近的对话是不是空白
            String key = latest.getName().substring(0, latest.getName().length() - 5);
            if (isChatFileBlank(latest)) return key;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** v1.51as：检查聊天文件是不是空白（没有 user 消息） */
    private static boolean isChatFileBlank(File f) {
        try {
            byte[] data = new byte[(int) f.length()];
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            try { fis.read(data); } finally { fis.close(); }
            String content = new String(data, java.nio.charset.StandardCharsets.UTF_8);
            org.json.JSONArray arr = new org.json.JSONArray(content);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                if ("user".equals(o.optString("role"))) return false;
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 项目对话列表（绑定项目的会话）：meta.projectPath 非空 */
    public static java.util.List<ConversationItem> listProjectConversations(Context ctx) {
        java.util.List<ConversationItem> out = new ArrayList<>();
        try {
            File dir = new File(ctx.getFilesDir(), "chat_history");
            if (!dir.exists()) return out;
            File[] fs = dir.listFiles();
            if (fs == null) return out;
            for (File f : fs) {
                String n = f.getName();
                if (!n.endsWith(".json") || n.endsWith(".meta.json")) continue;
                String key = n.substring(0, n.length() - 5);
                org.json.JSONObject meta = readMeta(dir, key);
                String pp = meta.optString("projectPath", "");
                if (pp.isEmpty()) continue;
                long t = meta.optLong("time", 0);  // v1.51be：只用 meta 里的创建时间，不用文件最后修改时间
                String name = meta.optString("projectName", new File(pp).getName());
                if (name.isEmpty()) name = new File(pp).getName();
                String preview = meta.optString("preview", "");
                if (preview.isEmpty()) preview = firstLineOf(f);
                out.add(new ConversationItem(key, name, pp, t, preview));
            }
            out.sort((a, b) -> Long.compare(b.time, a.time));
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static String historyFileNameBaseStatic(String projectPath) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest((projectPath == null ? "" : projectPath).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (Throwable t) {
            return "default";
        }
    }

    private static String firstLineOf(File f) {
        try {
            String content = "";
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            byte[] buf = new byte[(int) Math.min(f.length(), 2048)];
            int read = 0;
            while (read < buf.length) {
                int n = fis.read(buf, read, buf.length - read);
                if (n < 0) break;
                read += n;
            }
            fis.close();
            content = new String(buf, 0, read, "UTF-8");
            org.json.JSONArray arr = new org.json.JSONArray(content);
            for (int i = 0; i < arr.length(); i++) {
                String text = arr.getJSONObject(i).optString("text", "");
                if (!text.trim().isEmpty()) {
                    String t = text.trim().replace('\n', ' ');
                    return t.length() > 28 ? t.substring(0, 28) + "…" : t;
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static String formatConvTime(long t) {
        try {
            return new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
                    .format(new java.util.Date(t));
        } catch (Throwable e) {
            return "对话";
        }
    }

    /**
     * 从 WorkspaceState 同步项目：AI 在对话中通过 aics create-project 创建项目后，
     * 系统已设置 WorkspaceState.projectPath，这里把当前对话绑定到该项目（历史按项目分开保存）。
     * 独立对话（未创建/未手动绑定项目）不受影响。
     */
    private void syncProjectBinding() {
        try {
            // v1.49：只有对话已绑定项目时才同步 WorkspaceState 的最新项目路径；
            // 空白对话（场景A）绝不自动绑 WorkspaceState 里残留的旧/最近项目（否则点对话就串项目）
            if (chatManager == null || currentProjectPath == null || currentProjectPath.isEmpty()) return;
            String wsPath = WorkspaceState.get().getProjectPath();
            if (wsPath != null && !wsPath.isEmpty() && !wsPath.equals(currentProjectPath)) {
                setCurrentProject(wsPath);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 更新消息记录：把最后一条"正在思考..."替换为完整回复 */
    private void updateLoadingMessageRecord(String resp) {
        if (resp == null) return;
        for (int i = mMessages.size() - 1; i >= 0; i--) {
            ChatMessage m = mMessages.get(i);
            if (m.type == TYPE_AI
                    && (m.text.equals("正在思考...") || m.text.startsWith("正在思考"))) {
                m.text = resp;
                return;
            }
        }
    }

    /** 移除消息记录中最后一条"正在思考..."（生成失败时） */
    private void removeLoadingMessageRecord() {
        for (int i = mMessages.size() - 1; i >= 0; i--) {
            ChatMessage m = mMessages.get(i);
            if (m.type == TYPE_AI
                    && (m.text.equals("正在思考...") || m.text.startsWith("正在思考"))) {
                mMessages.remove(i);
                return;
            }
        }
    }

    /**
     * v1.146：重新生成——删掉半截 AI 行 + 最后一条用户行（UI 与记录），
     * AIChatManager 删除历史里最后整轮后，重新 sendToAI 完整重跑：
     * 流式渲染全套初始化（思考行/parser/卡片）由 sendToAI 统一完成，历史 user 不重复。
     */
    private void regenerateCurrent() {
        if (chatManager == null) return;
        String lastUser = chatManager.getLastUserMessage();
        if (lastUser == null || lastUser.isEmpty()) return;
        // 1. 删最后一条 AI 半截消息行 + 记录
        for (int i = mMessages.size() - 1; i >= 0; i--) {
            if (mMessages.get(i).type == TYPE_AI) { mMessages.remove(i); break; }
        }
        View lastAiRow = null;
        for (int i = messageContainer.getChildCount() - 1; i >= 0; i--) {
            View r = messageContainer.getChildAt(i);
            Object t = r.getTag();
            if (t instanceof ToolHostTag) { lastAiRow = r; break; }
        }
        if (lastAiRow != null) messageContainer.removeView(lastAiRow);
        // 2. 删最后一条用户消息行 + 记录（避免 UI 重复）
        for (int i = mMessages.size() - 1; i >= 0; i--) {
            if (mMessages.get(i).type == TYPE_USER) { mMessages.remove(i); break; }
        }
        for (int i = messageContainer.getChildCount() - 1; i >= 0; i--) {
            View r = messageContainer.getChildAt(i);
            Object t = r.getTag();
            if (t instanceof TextView && lastUser.equals(((TextView) t).getText().toString())) {
                messageContainer.removeView(r);
                break;
            }
        }
        // 3. 历史删最后整轮，然后完整重跑
        chatManager.removeLastRound();
        // v1.148：重新生成=历史变了（删掉半截轮）——强制清 KV 缓存，
        // 让模型从新的 messageHistory（含之前所有历史消息/内容）整体重建 prompt 重新 prefill，
        // 避免引擎沿用旧 KV 增量续算导致历史错位串台（QQ 号等历史内容必须按新顺序正确处理）
        try { chatManager.clearKvCache(); } catch (Throwable ignored) {}
        generationStopped = false;
        sendToAI(lastUser);
    }

    /**
     * v1.145/v1.146：给被中断的 AI 半截消息加"重新生成"按钮（防重复添加）。
     * 点击：删半截 UI + 记录，AIChatManager.regenerateLast() 带完整历史重跑最后一轮。
     */
    private void addRegenerateButton(View row) {
        try {
            if (!(row instanceof LinearLayout)) return;
            // v1.153：按钮加进气泡内部（row 的第一个子 content 容器），而不是 row 末尾外面
            LinearLayout parent = (LinearLayout) row;
            if (parent.getChildCount() > 0 && parent.getChildAt(0) instanceof LinearLayout) {
                parent = (LinearLayout) parent.getChildAt(0);
            }
            for (int i = 0; i < parent.getChildCount(); i++) {
                if (REGEN_BTN_TAG.equals(parent.getChildAt(i).getTag())) return; // 已有按钮
            }
            // v1.153c：ImageView 放矢量刷新图标——严格居中，无字符 baseline 偏移
            android.widget.ImageView btn = new android.widget.ImageView(getContext());
            btn.setTag(REGEN_BTN_TAG);
            btn.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
            android.graphics.drawable.Drawable icon =
                    com.aicodestudio.ide.ai.ui.UiKit.icon(getContext(), "ic_refresh", 0xFF7C8CFF);
            if (icon != null) btn.setImageDrawable(icon);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(0x337C8CFF);
            bg.setCornerRadius(dp(8));
            btn.setBackground(bg);
            btn.setPadding(dp(8), dp(8), dp(8), dp(8));
            btn.setClickable(true);
            btn.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) {
                    regenerateCurrent();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(34), dp(34));
            lp.topMargin = dp(6);
            parent.addView(btn, lp);
        } catch (Throwable ignored) {}
    }

    /** v1.153：遍历消息列表，移除所有遗留的"重新生成"按钮（用户发新消息/重新生成后调用） */
    private void removeAllRegenButtons() {
        try {
            for (int i = 0; i < messageContainer.getChildCount(); i++) {
                View row = messageContainer.getChildAt(i);
                removeRegenFromRow(row);
            }
        } catch (Throwable ignored) {}
    }

    /** 递归在 row（及其内部 content 气泡）里找并删除重新生成按钮 */
    private void removeRegenFromRow(View v) {
        try {
            if (REGEN_BTN_TAG.equals(v.getTag())) {
                if (v.getParent() instanceof ViewGroup) {
                    ((ViewGroup) v.getParent()).removeView(v);
                }
                return;
            }
            if (v instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) v;
                for (int j = vg.getChildCount() - 1; j >= 0; j--) {
                    removeRegenFromRow(vg.getChildAt(j));
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * v1.135：渲染合并历史（工作+闲聊按时间戳排序），切模式/绑项目后重建气泡。
     * 显示完整对话时间线；AI 上下文仍由 AIChatManager 按当前模式隔离。
     */
    private void renderMergedHistory() {
        if (chatManager == null) return;
        try {
            clearMessagesUi();
            for (com.aicodestudio.ide.ai.chat.AIChatManager.ChatMessage m
                    : chatManager.getMergedDisplayHistory()) {
                if (m.role.equals("user")) addMessageNoRecord(m.content, TYPE_USER);
                else if (m.role.equals("assistant")) addMessageNoRecord(m.content, TYPE_AI);
            }
        } catch (Throwable t) {
            android.util.Log.w("ChatPanel", "renderMergedHistory failed", t);
        }
    }

    /**
     * 刷新所有 AI 消息（切换原始/渲染模式时调用）
     * 遍历所有消息，根据当前 showRawMessage 状态重新渲染
     */
    private void refreshAllMessages() {
        com.aicodestudio.ide.ai.util.DebugLogger.log("ChatPanel", "refreshAllMessages start, showRaw=" + showRawMessage + " rows=" + messageContainer.getChildCount());
        for (int i = 0; i < messageContainer.getChildCount(); i++) {
            View row = messageContainer.getChildAt(i);
            Object tag = row.getTag();
            LinearLayout content = null;
            if (tag instanceof ToolHostTag) {
                content = ((ToolHostTag) tag).content;
            } else if (tag instanceof LinearLayout) {
                content = (LinearLayout) tag;
            }
            if (content != null) {
                Object rawText = content.getTag();
                if (rawText instanceof String) {
                    String text = (String) rawText;
                    // v1.141：正在流式输出的行——后台控件（think块/代码块/文本段/工具卡片）
                    // 已经在流式期间创建好，切换原始/渲染只需翻转可见性，
                    // 绝不 removeAllViews 重建、绝不 resetStreamingState：
                    // 否则重建一份思考块后，后续新 chunk 因状态被重置又新建一份 → 出现两个思考块。
                    if (row == activeStreamingRow) {
                        applyModeVisibility(content, text);
                        continue;
                    }
                    // v1.136：历史完成行——摘出工具卡片，重建正文后再按原顺序插回
                    java.util.List<View> toolCards = new java.util.ArrayList<>();
                    for (int c = 0; c < content.getChildCount(); c++) {
                        View child = content.getChildAt(c);
                        if (TOOL_CARD_TAG.equals(child.getTag())) toolCards.add(child);
                    }
                    content.removeAllViews();
                    try {
                        if (showRawMessage) {
                            TextView tv = new TextView(getContext());
                            tv.setText(text);
                            tv.setTextColor(Theme.TEXT_PRIMARY);
                            tv.setTextSize(14);
                            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
                            tv.setTextIsSelectable(true);
                            content.addView(tv);
                        } else {
                            renderAiMessageInline(content, text);
                        }
                    } catch (Throwable t) {
                        com.aicodestudio.ide.ai.util.DebugLogger.log("ChatPanel", "rebuild row " + i + " failed: " + t.getMessage());
                    }
                    // 工具卡片插回（渲染模式：追加到末尾；原始模式不显示卡片）
                    if (!showRawMessage) {
                        for (View card : toolCards) {
                            card.setVisibility(View.VISIBLE); // v1.140：后台创建的卡片恢复显示
                            content.addView(card);
                        }
                    }
                    // 兜底：重建后若容器为空（渲染异常/解析出空段），至少显示原文，不允许空白
                    if (content.getChildCount() == 0 && text != null && !text.isEmpty()) {
                        TextView tv = new TextView(getContext());
                        tv.setText(text);
                        tv.setTextColor(Theme.TEXT_PRIMARY);
                        tv.setTextSize(14);
                        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
                        tv.setTextIsSelectable(true);
                        content.addView(tv);
                    }
                } else {
                    com.aicodestudio.ide.ai.util.DebugLogger.log("ChatPanel", "row " + i + " SKIP, tag=" + (rawText == null ? "null" : rawText.getClass().getSimpleName()));
                }
            }
        }
        com.aicodestudio.ide.ai.util.DebugLogger.log("ChatPanel", "refreshAllMessages done");
    }

    // ================= 消息渲染 =================

    private View addMessage(String text, int type) {
        return addMessageInternal(text, type, true);
    }

    /** 不写入历史记录的 addMessage（欢迎语 / 系统提示等临时消息） */
    private View addMessageNoRecord(String text, int type) {
        return addMessageInternal(text, type, false);
    }

    private View addMessageInternal(String text, int type, boolean record) {
        if (text == null) text = "";
        LinearLayout row = new LinearLayout(getContext());
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        // v1.91：AI 消息行改为纵向，正文容器 + 工具卡片容器 并列
        row.setOrientation(type == TYPE_AI ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(4), 0, dp(4));

        if (type == TYPE_AI) {
            // AI 消息：渲染 Markdown（普通文本 + 代码块），工具调用走独立卡片
            LinearLayout content = new LinearLayout(getContext());
            content.setOrientation(LinearLayout.VERTICAL);
            content.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            // v1.153：AI 气泡加纯色背景——微紫黑色，圆角
            android.graphics.drawable.GradientDrawable aiBubbleBg = new android.graphics.drawable.GradientDrawable();
            aiBubbleBg.setColor(0xFF1E1B2E);
            aiBubbleBg.setCornerRadius(dp(14));
            content.setBackground(aiBubbleBg);
            content.setPadding(dp(12), dp(9), dp(12), dp(9));
            content.setTag(text);  // 保存原始文本，用于切换原始/渲染模式时重新渲染
            // 工具调用卡片容器（读/写/执行）——历史与流式共用同一套卡片样式
            LinearLayout toolHost = new LinearLayout(getContext());
            toolHost.setOrientation(LinearLayout.VERTICAL);
            toolHost.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            toolHost.setPadding(0, dp(2), 0, 0);
            if ("正在思考...".equals(text)) {
                // v1.153h：三个真正的圆点 View，横向排列，visibility 控制循环
                content.setBackground(null);
                content.setPadding(0, 0, 0, 0);
                android.widget.LinearLayout dotsLayout = new android.widget.LinearLayout(getContext());
                dotsLayout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                int dotSize = dp(8);
                for (int i = 0; i < 3; i++) {
                    View dot = new View(getContext());
                    android.graphics.drawable.GradientDrawable dotBg = new android.graphics.drawable.GradientDrawable();
                    dotBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                    dotBg.setColor(Theme.TEXT_SECOND);
                    dot.setBackground(dotBg);
                    android.widget.LinearLayout.LayoutParams dotLp = new android.widget.LinearLayout.LayoutParams(dotSize, dotSize);
                    dotLp.rightMargin = dp(5);
                    dotsLayout.addView(dot, dotLp);
                    thinkingDots[i] = dot;
                }
                // 容器高度加高，三个点垂直居中，跳动位置保持
                dotsLayout.setGravity(android.view.Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams dotsLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, dp(32));
                dotsLp.topMargin = dp(4);
                dotsLp.leftMargin = dp(16); // v1.153m：朝中间靠一点
                content.addView(dotsLayout, dotsLp);
                thinkingDotsLayout = dotsLayout;
                thinkingDotIndex = 0;
                thinkingHandler.postDelayed(thinkingDotsRunnable, 200);
            } else if (showRawMessage) {
                // 原始消息模式：显示纯文本，不渲染 markdown、代码框与工具卡片
                TextView tv = new TextView(getContext());
                tv.setText(text);
                tv.setTextColor(Theme.TEXT_PRIMARY);
                tv.setTextSize(14);
                tv.setTypeface(android.graphics.Typeface.MONOSPACE);
                tv.setTextIsSelectable(true);
                content.addView(tv);
            } else {
                // v1.100：流式增量阶段 content 只渲染正文；工具卡片走 toolHost（流式实时）。
                // 历史加载 / 流式完成 / 切换重建统一由 finalizeHistoricalRow 做单流内联，
                // 把思考、正文、工具卡片按原文顺序重排，不再堆末尾。
                renderMarkdown(content, text, true);
            }
            row.addView(content);
            row.addView(toolHost);
            row.setTag(new ToolHostTag(content, toolHost));  // 双容器 tag
        } else {
            // 其他类型：用 TextView
            TextView tv = new TextView(getContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);

            if (type == TYPE_USER) {
                lp.gravity = Gravity.END;
                row.setGravity(Gravity.END);
                lp.width = LinearLayout.LayoutParams.WRAP_CONTENT;
                tv.setBackground(makeBubble(BUBBLE_FILL_USER, BUBBLE_STROKE_USER, 2));  // v1.91h：恢复用户气泡描边
                tv.setTextColor(Theme.TEXT_PRIMARY);
            } else if (type == TYPE_SYSTEM) {
                lp.gravity = Gravity.CENTER;
                row.setGravity(Gravity.CENTER);
                tv.setBackground(makeBubble(0x20F0883E, 0));
                tv.setTextColor(Theme.TAB_ORANGE);
            } else if (type == TYPE_ERROR) {
                lp.gravity = Gravity.START;
                lp.width = LinearLayout.LayoutParams.WRAP_CONTENT;
                tv.setBackground(makeBubble(0x20F85149, 0));
                tv.setTextColor(Theme.CLOSE_RED);
            } else {
                // TYPE_LOADING
                lp.gravity = Gravity.START;
                lp.width = LinearLayout.LayoutParams.MATCH_PARENT;
                tv.setBackground(null);  // v1.91h：AI loading 无气泡
                tv.setTextColor(Theme.TEXT_TERTIARY);
            }

            tv.setLayoutParams(lp);
            tv.setText(text);
            tv.setTextSize(14);
            tv.setMaxWidth((int) (UiKit.screenWidth(getContext()) * Theme.BUBBLE_MAX_W_RATIO));
            tv.setPadding(dp(12), dp(9), dp(12), dp(9));
            tv.setTextIsSelectable(true);
            tv.setHorizontallyScrolling(false);
            row.addView(tv);
            row.setTag(tv);
        }

        messageContainer.addView(row);
        if (record) {
            mMessages.add(new ChatMessage(type, text));
        }
        // 只有用户没有上滑、且不是程序平滑滚动中时才自动滚动到底部（等布局完成再滚）
        if (!userScrolledUp && !programScroll) {
            scrollToBottomAfterLayout();
        }
        return row;
    }

    /**
     * 流式更新 loading 消息的文本（增量渲染）
     *
     * 核心逻辑：
     * - parseMarkdown 把文本分割成 segments（普通文本 + 代码块）
     * 比较当前 container 中的 View 数量和新的 segments 数量：
     *   1. segments 增加了：先更新最后一个已有 View（因为代码框结束标记出现时，
     *      上一个代码框的内容从"包含后续内容"变成"只有自己的代码"，需要更新），
     *      然后创建新的 View
     *   2. segments 数量不变：只更新最后一个 View（只有最后一个 segment 的内容会变化）
     *   3. segments 减少了：全量重建（很少见）
     * - updateSegmentView 内部有内容比较，相同则跳过，不会导致不必要的视觉刷新
     */
    private void updateLoadingMessage(View row, String text) {
        if (row == null || text == null) return;
        Object tag = row.getTag();
        // v1.91：AI 消息行 tag 为双容器，取正文容器
        if (tag instanceof ToolHostTag) tag = ((ToolHostTag) tag).content;
        if (tag instanceof TextView) {
            ((TextView) tag).setText(text);
        } else if (tag instanceof LinearLayout) {
            LinearLayout content = (LinearLayout) tag;
            content.setTag(text);  // 更新保存的原始文本

            if (showRawMessage) {
                // v1.140/v1.141：原始模式——渲染控件 GONE 后台保留，仅显示原始文本全文；
                // 切回渲染时控件已存在、立即可复用显示
                applyModeVisibility(content, text);
            } else {
                // 渲染模式：正常渲染 markdown 和代码框
                // 如果当前是原始模式的 TextView，先清空，切换到渲染模式
                if (content.getChildCount() == 1 && content.getChildAt(0) instanceof TextView
                        && ((TextView) content.getChildAt(0)).getTypeface() == android.graphics.Typeface.MONOSPACE) {
                    content.removeAllViews();
                }

                List<Object> segments = parseMarkdown(text);
                int childCount = content.getChildCount();
                int segCount = segments.size();

                // v1.48f：单一路径增量渲染（修复流式叠字 + 避免整块重建闪烁）
                // 根因：旧三分支只更新"最后一个 segment"，分段边界漂移时（如 "Hello\n```"
                // → "Hello\n```\ncode"）前面 segment 的内容增长却从不更新，旧文本残留形成叠字；
                // 而"数量减少→全量重建"会在实时流式中造成整块闪烁。
                // 现在逐段对比：类型匹配 → 增量更新（setText/appendCode，不重建）；
                // 类型不匹配（边界漂移）→ 仅替换这一个节点；数量不足 → 末尾追加；尾部多余 → 移除。
                // 全程不 removeAllViews，用户无感知。
                int common = Math.min(childCount, segCount);
                for (int i = 0; i < common; i++) {
                    View child = content.getChildAt(i);
                    Object seg = segments.get(i);
                    if (checkSegmentTypeMatch(child, seg)) {
                        updateSegmentView(child, seg);
                    } else {
                        // 分段边界漂移导致类型变化：仅替换该节点（保持位置）
                        View newView = createSegmentView(seg, false);
                        if (newView != null) {
                            content.removeViewAt(i);
                            content.addView(newView, i);
                        } else {
                            // 新段无可渲染内容（空文本）：删除旧节点
                            content.removeViewAt(i);
                            common--;
                            i--;
                        }
                    }
                }
                // 末尾追加新增的 segment
                for (int i = common; i < segCount; i++) {
                    renderSegment(content, segments.get(i), false);
                }
                // 尾部多余的旧 View 移除（文本收缩场景）
                while (content.getChildCount() > segCount) {
                    content.removeViewAt(content.getChildCount() - 1);
                }
            }
        }
        // 自动滚动到底部（用户没有上滑、且非程序平滑滚动中）
        if (!userScrolledUp && !programScroll) {
            scrollToBottom();
        }
    }

    // ===== v1.153db：调试日志写文件 =====
    private static void dbgLog(String msg) {
        String line = new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(new java.util.Date()) + " " + msg + "\n";
        try {
            java.io.File dir = new java.io.File(android.os.Environment.getExternalStorageDirectory(), "Ai code studio/log");
            dir.mkdirs();
            java.io.File f = new java.io.File(dir, "highlight_debug.txt");
            java.io.FileWriter fw = new java.io.FileWriter(f, true);
            fw.write(line);
            fw.close();
        } catch (Throwable t) {}
    }

    /** JS 字符串转义（复制代码编辑器 escapeJs，逐字符转义） */
    private static String escapeJs(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 32);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '\'': sb.append("\\'"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '<': sb.append("\\u003c"); break;
                case '>': sb.append("\\u003e"); break;
                case '&': sb.append("\\u0026"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 完全复制代码编辑器 doSetCode：先 setLanguage 再 setCode，统一做语言映射 */
    private void doSetCode(WebView webView, String language, String code) {
        if (webView == null || code == null) return;
        try {
            String hlLang = mapToHighlightJsLanguage(language);
            String js = "setLanguage('" + hlLang + "');setCode('" + escapeJs(code) + "');";
            webView.evaluateJavascript(js, null);
        } catch (Throwable ignored) {}
    }

    /** 增量更新：全量重设代码（流式输出时每次都重设，确保高亮） */
    private void setCodeWithLangToWebView(WebView webView, String code, String hlLang) {
        doSetCode(webView, hlLang, code);
    }

    private void setCodeToWebView(WebView webView, String code) {
        // 只更新代码，语言由调用方 setLanguage 设好
        if (webView == null || code == null) return;
        try {
            webView.evaluateJavascript("setCode('" + escapeJs(code) + "');", null);
        } catch (Throwable ignored) {}
    }

    /** 追加代码到 WebView（只追加新增部分，不重新设置全部代码，性能更好） */
    private void appendCodeToWebView(WebView webView, String code) {
        if (webView == null || code == null || code.isEmpty()) return;
        try {
            String escaped = code.replace("\\", "\\\\")
                    .replace("'", "\\'")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r");
            webView.evaluateJavascript("appendCode('" + escaped + "'); window.scrollTo(0, document.body.scrollHeight);", null);
        } catch (Throwable ignored) {}
    }

    /**
     * 检查 View 和 segment 的类型是否匹配
     * 用于增量渲染时判断是否需要移除旧 View 重新创建
     */
    /**
     * v1.140/v1.141：按当前 showRawMessage 翻转一行 content 内控件的可见性——
     * 渲染模式：原始文本视图(rawTv) GONE，其余控件(think块/文本段/代码块/工具卡片) VISIBLE；
     * 原始模式：原始文本视图 VISIBLE(显示完整输出全文)，其余控件 GONE 后台保留。
     * 控件不销毁不重建，切来切去瞬间切换，且后续流式增量继续写现存控件，不会重复创建。
     */
    private void applyModeVisibility(LinearLayout content, String fullText) {
        if (content == null) return;
        TextView rawTv = null;
        for (int i = 0; i < content.getChildCount(); i++) {
            View ch = content.getChildAt(i);
            if (ch instanceof TextView && RAW_TV_TAG.equals(ch.getTag())) {
                rawTv = (TextView) ch;
            }
        }
        if (showRawMessage) {
            // 原始模式：渲染控件 GONE，rawTv VISIBLE 置顶显示全文
            for (int i = 0; i < content.getChildCount(); i++) {
                View ch = content.getChildAt(i);
                if (ch == rawTv) ch.setVisibility(View.VISIBLE);
                else ch.setVisibility(View.GONE);
            }
            if (rawTv == null) {
                rawTv = new TextView(getContext());
                rawTv.setTag(RAW_TV_TAG);
                rawTv.setTextColor(Theme.TEXT_PRIMARY);
                rawTv.setTextSize(14);
                rawTv.setTypeface(android.graphics.Typeface.MONOSPACE);
                rawTv.setTextIsSelectable(true);
                content.addView(rawTv, 0);
            }
            rawTv.setText(fullText == null ? "" : fullText);
        } else {
            // 渲染模式：渲染控件 VISIBLE；原始视图直接移除（不占 childCount，
            // 避免增量渲染把 rawTv 算进 childCount 导致全量重建 → 重复创建思考块）
            if (rawTv != null) content.removeView(rawTv);
            for (int i = 0; i < content.getChildCount(); i++) {
                content.getChildAt(i).setVisibility(View.VISIBLE);
            }
        }
    }

    private boolean checkSegmentTypeMatch(View view, Object seg) {
        if (seg instanceof TextSegment) {
            // 普通文本：外层就是 TextView
            return view instanceof TextView;
        } else if (seg instanceof CodeSegment) {
            // 代码框：tag 是 CodeBoxTag
            return view.getTag() instanceof CodeBoxTag;
        } else if (seg instanceof ThinkSegment) {
            // 思考过程卡片：外层是 LinearLayout，tag 是内容 TextView
            return (view instanceof LinearLayout) && (view.getTag() instanceof TextView);
        }
        return false;
    }

    /**
     * 更新单个 segment 对应的 View（增量更新，不重建已有 View）
     *
     * - 代码框：通过 evaluateJavascript 更新代码内容（不重新加载页面）
     *   比较新旧内容，相同则跳过，避免不必要的视觉刷新
     *   如果页面还没加载完成，保存到 pendingCode，等页面加载完成后再设置
     * - 普通文本：更新 TextView 内容
     */
    private void updateSegmentView(View view, Object seg) {
        if (seg instanceof CodeSegment && view.getTag() instanceof CodeBoxTag) {
            CodeSegment cs = (CodeSegment) seg;
            CodeBoxTag tag = (CodeBoxTag) view.getTag();

            // 降级模式：WebView 已销毁，直接更新纯文本 TextView
            if (tag.degraded) {
                if (tag.degradedTv != null && cs.code != null && !cs.code.equals(tag.currentCode)) {
                    tag.degradedTv.setText(cs.code);
                    tag.currentCode = cs.code;
                }
                return;
            }

            // 语言标签变化：重新设置 WebView 语言并全量重高亮（关键修复）
            // 流式输出过程中语言标签可能先解析成空/错误值，代码完整后解析出正确语言，
            // 必须检测到变化并同步到 WebView，否则永远灰色无高亮
            String segLang = (cs.language == null) ? "" : cs.language;
            if (!segLang.equals(tag.currentLang == null ? "" : tag.currentLang)) {
                tag.currentLang = segLang;
                final String hlLang = mapToHighlightJsLanguage(segLang);
                if (tag.pageLoaded) {
                    try {
                        tag.webView.evaluateJavascript("setLanguage('" + hlLang + "');", null);
                        // 语言变了，全量重新设置代码触发完整高亮
                        setCodeToWebView(tag.webView, cs.code);
                        tag.currentCode = cs.code;
                    } catch (Throwable ignored) {}
                }
                // 页面未加载完成时，语言会在 setCodeRunnable 中设置（pendingCode 会触发全量设置）
                return;
            }
            // 比较新旧内容，相同则跳过
            if (cs.code != null && cs.code.equals(tag.currentCode)) {
                return;
            }
            if (tag.pageLoaded) {
                // 页面已加载完成
                String oldCode = tag.currentCode;
                String newCode = cs.code;
                if (oldCode != null && newCode != null
                        && newCode.startsWith(oldCode)
                        && newCode.length() > oldCode.length()) {
                    // 新代码以旧代码开头，只追加新增部分（性能更好，不重新高亮全部代码）
                    String append = newCode.substring(oldCode.length());
                    appendCodeToWebView(tag.webView, append);
                } else {
                    // 否则设置全部代码
                    setCodeToWebView(tag.webView, newCode);
                }
                tag.currentCode = newCode;
            } else {
                // 页面还没加载完成，保存到 pendingCode，等 onPageFinished 后再设置
                tag.pendingCode = cs.code;
                tag.currentCode = cs.code; // 也更新 currentCode，避免重复比较
            }
        } else if (seg instanceof TextSegment && view instanceof TextView) {
            TextSegment ts = (TextSegment) seg;
            String t = ts.text;
            if (t != null) {
                t = t.replaceAll("^\\n+|\\n+$", "");
                if (!t.isEmpty()) {
                    try {
                        String html = markdownToHtml(t);
                        ((TextView) view).setText(Html.fromHtml(html));
                    } catch (Throwable e) {
                        ((TextView) view).setText(t);
                    }
                }
            }
        } else if (seg instanceof ThinkSegment && view.getTag() instanceof TextView) {
            // 思考过程卡片：tag 是内容 TextView，内容渲染 Markdown
            ThinkSegment ts = (ThinkSegment) seg;
            TextView contentTv = (TextView) view.getTag();
            // v1.91d：无条件刷新（去掉 getText().equals 误判——HTML 渲染后文本可能与原文不等，
            // 若误判相等会跳过更新，导致思考内容不实时显示）
            if (ts.content != null) {
                try {
                    String html = com.aicodestudio.ide.ai.util.MarkdownUtil.markdownToHtml(ts.content);
                    contentTv.setText(android.text.Html.fromHtml(html));
                } catch (Throwable e) {
                    contentTv.setText(ts.content);
                }
                // v1.55：思考内容更新后自动滚动到底部（流式输出时跟着最新内容走）
                if (contentTv.getParent() instanceof ScrollView) {
                    final ScrollView sv = (ScrollView) contentTv.getParent();
                    sv.post(new Runnable() {
                        @Override public void run() {
                            sv.fullScroll(ScrollView.FOCUS_DOWN);
                        }
                    });
                }
            }
        }
    }

    /** 检查滚动位置，判断用户是否上滑，控制悬浮按钮显示 */
    private void checkScrollPosition() {
        if (scrollView == null || messageContainer == null) return;
        // v1.138：程序平滑滚动中（点箭头到底）——不干预，到达底部后释放标志
        if (programScroll) {
            int sY = scrollView.getScrollY();
            int maxS = messageContainer.getHeight() - scrollView.getHeight();
            if (sY >= maxS - dp(4)) {
                programScroll = false;
            } else {
                return;
            }
        }
        int scrollY = scrollView.getScrollY();
        int maxScroll = messageContainer.getHeight() - scrollView.getHeight();
        if (maxScroll <= 0) {
            // 内容不足一屏，不需要悬浮按钮
            if (btnScrollDown != null) btnScrollDown.setVisibility(View.GONE);
            userScrolledUp = false;
            return;
        }
        // 距离底部超过 20dp 认为用户上滑了
        int threshold = dp(20);
        if (scrollY < maxScroll - threshold) {
            if (!userScrolledUp) {
                userScrolledUp = true;
                if (btnScrollDown != null) btnScrollDown.setVisibility(View.VISIBLE);
            }
        } else {
            if (userScrolledUp) {
                userScrolledUp = false;
                if (btnScrollDown != null) btnScrollDown.setVisibility(View.GONE);
            }
        }
    }

    // ==================== 工具调用卡片（v1.91）====================

    /** onToolStart：创建工具调用卡片（图标 + 呼吸动画 + 状态文案），写文件附带代码内容区 */
    private void addToolCard(com.aicodestudio.ide.ai.agent.StreamingToolDetector.ToolBlock block) {
        if (currentToolHost == null || block == null) return;
        // v1.140：原始模式不再 return——后台照常创建（GONE 隐藏），切回渲染立即可显示
        // 若上一个卡片未结束（异常），先停掉动画
        if (activeToolAnimator != null) {
            try { activeToolAnimator.cancel(); } catch (Throwable ignored) {}
            activeToolAnimator = null;
        }

        // v1.140：原始模式也照常创建工具卡片（后台 GONE 隐藏），切回渲染时控件已存在、立即显示
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setTag(TOOL_CARD_TAG); // v1.136：标记为工具卡片，切换原始/渲染重建时保留
        card.setVisibility(showRawMessage ? View.GONE : View.VISIBLE); // v1.140
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Theme.BG_CARD);
        bg.setCornerRadius(dp(10));
        card.setBackground(bg);
        card.setPadding(dp(10), dp(8), dp(10), dp(8));

        // 顶部行：图标 + 状态文案
        LinearLayout head = new LinearLayout(getContext());
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);

        android.widget.ImageView icon = new android.widget.ImageView(getContext());
        int iconRes = block.type == com.aicodestudio.ide.ai.agent.StreamingToolDetector.TYPE_READ
                ? com.aicodestudio.ide.R.drawable.ic_tool_read
                : (block.type == com.aicodestudio.ide.ai.agent.StreamingToolDetector.TYPE_WRITE
                    ? com.aicodestudio.ide.R.drawable.ic_tool_write
                    : com.aicodestudio.ide.R.drawable.ic_tool_exec);
        int color = block.type == com.aicodestudio.ide.ai.agent.StreamingToolDetector.TYPE_READ
                ? Theme.ACCENT_GREEN
                : (block.type == com.aicodestudio.ide.ai.agent.StreamingToolDetector.TYPE_WRITE
                    ? Theme.ACCENT_BLUE : Theme.ACCENT_ORANGE);
        icon.setImageResource(iconRes);
        try { icon.getDrawable().mutate().setTint(color); } catch (Throwable ignored) {}
        int iconSize = dp(18);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconLp.setMargins(0, 0, dp(6), 0);
        icon.setLayoutParams(iconLp);
        // 呼吸动画（执行中）
        android.animation.ObjectAnimator breathe = android.animation.ObjectAnimator.ofFloat(
                icon, "alpha", 1.0f, 0.4f, 1.0f);
        breathe.setDuration(1200);
        breathe.setRepeatCount(android.animation.ObjectAnimator.INFINITE);
        breathe.start();
        activeToolAnimator = breathe;
        head.addView(icon);

        TextView status = new TextView(getContext());
        status.setText(toolRunningText(block));
        status.setTextColor(color);
        status.setTextSize(13);
        status.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        status.setLayoutParams(stLp);
        head.addView(status);
        card.addView(head);

        // 代码块（复用 createCodeBlock 高亮渲染：每个工具开头后面跟着它自己的代码块）
        String lang = langForToolBlock(block);
        String displayCode;
        if (block.type == com.aicodestudio.ide.ai.agent.StreamingToolDetector.TYPE_WRITE) {
            displayCode = "";            // 写文件：内容流式填充
        } else if (block.type == com.aicodestudio.ide.ai.agent.StreamingToolDetector.TYPE_READ) {
            displayCode = "cat " + block.path;   // 读文件：显示命令
        } else {
            displayCode = block.cmd;             // 执行：显示命令
        }
        View codeView = createCodeBlock(displayCode, lang, false);
        card.addView(codeView);
        activeCodeView = codeView;
        activeCodeLang = lang;
        cancelToolCardFlush();
        currentToolContent.setLength(0);
        pendingToolDelta.setLength(0);
        // READ/EXEC 以已显示的命令为基准（后续命令行会追加）；WRITE 从空开始流式填充
        if (block.type != com.aicodestudio.ide.ai.agent.StreamingToolDetector.TYPE_WRITE) {
            currentToolContent.append(displayCode);
        }

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, dp(4), 0, dp(4));
        currentToolHost.addView(card, cardLp);
        // v1.149 P1：工具卡片统一为 BaseStreamCard 子类入列
        ToolStreamCard tsc = new ToolStreamCard();
        tsc.rootView = card;
        streamingToolCards.add(tsc); // v1.136：按创建顺序入列，切换模式时按序恢复
        activeToolCard = card;
        scrollToBottom();
    }

    /** 工具执行中的状态文案 */
    private String toolRunningText(com.aicodestudio.ide.ai.agent.StreamingToolDetector.ToolBlock block) {
        String label = block.label();
        switch (block.type) {
            case com.aicodestudio.ide.ai.agent.StreamingToolDetector.TYPE_READ:
                return "正在读取: " + label;
            case com.aicodestudio.ide.ai.agent.StreamingToolDetector.TYPE_WRITE:
                return "正在写入: " + label;
            default:
                return label.isEmpty() ? "执行命令" : "执行: " + label;
        }
    }

    /** onToolUpdate：写文件内容流式填充（节流合并后复用正文代码框的增量 append / 高亮机制） */
    private void updateToolCardContent(String inc) {
        if (activeCodeView == null || inc == null) return;
        pendingToolDelta.append(inc);
        // 已有排队 flush 就由它统一刷；否则排一个，把 100ms 内的多次增量合并成一次刷新
        if (toolUpdateFlusher == null) {
            toolUpdateFlusher = new Runnable() {
                @Override public void run() { flushToolCardContent(); }
            };
            toolUpdateHandler.postDelayed(toolUpdateFlusher, TOOL_UPDATE_INTERVAL_MS);
        }
    }

    /** 把累积的增量合并进 currentToolContent 并刷新代码框（updateSegmentView 内部走增量 append） */
    private void flushToolCardContent() {
        toolUpdateFlusher = null;
        if (activeCodeView == null) { pendingToolDelta.setLength(0); return; }
        if (pendingToolDelta.length() == 0) return;
        currentToolContent.append(pendingToolDelta);
        pendingToolDelta.setLength(0);
        updateSegmentView(activeCodeView,
                new CodeSegment(currentToolContent.toString(), activeCodeLang == null ? "shell" : activeCodeLang));
        scrollToBottom();
    }

    /** 终止流式刷新并刷出剩余增量（开始新卡片 / 结束卡片 / 回复收尾时调用，避免丢尾） */
    private void cancelToolCardFlush() {
        if (toolUpdateFlusher != null) {
            toolUpdateHandler.removeCallbacks(toolUpdateFlusher);
            toolUpdateFlusher = null;
        }
    }

    /** 根据工具块推导代码块语言标签（写文件按扩展名，读/执行用 shell） */
    private String langForToolBlock(com.aicodestudio.ide.ai.agent.StreamingToolDetector.ToolBlock block) {
        if (block.type == com.aicodestudio.ide.ai.agent.StreamingToolDetector.TYPE_WRITE) {
            String p = block.path == null ? "" : block.path;
            int dot = p.lastIndexOf('.');
            if (dot >= 0 && dot < p.length() - 1) {
                String ext = p.substring(dot + 1).toLowerCase();
                switch (ext) {
                    case "java": return "java";
                    case "kt": case "kts": return "kotlin";
                    case "py": return "python";
                    case "json": return "json";
                    case "xml": return "xml";
                    case "md": case "markdown": return "markdown";
                    case "html": case "htm": return "html";
                    case "css": return "css";
                    case "js": case "mjs": return "javascript";
                    case "ts": return "typescript";
                    default: return "shell";
                }
            }
            return "shell";
        }
        return "shell";
    }

    /** onToolEnd：停止呼吸动画，刷新状态（写入完成/读取完成/执行成功/失败） */
    private void endToolCard(com.aicodestudio.ide.ai.agent.StreamingToolDetector.ToolBlock block,
                             boolean ok, String summary) {
        if (activeToolAnimator != null) {
            try { activeToolAnimator.cancel(); } catch (Throwable ignored) {}
            activeToolAnimator = null;
        }
        // 结束前先把节流队列里尚未刷出的尾段内容同步刷进代码框，避免最后几行丢失
        cancelToolCardFlush();
        flushToolCardContent();
        if (activeToolCard == null) return;
        try {
            android.view.View head = activeToolCard.getChildAt(0);
            if (head instanceof LinearLayout && ((LinearLayout) head).getChildCount() > 1) {
                TextView status = (TextView) ((LinearLayout) head).getChildAt(1);
                String label = (block != null) ? block.label() : "";
                String text;
                if (block != null && block.type == com.aicodestudio.ide.ai.agent.StreamingToolDetector.TYPE_WRITE) {
                    text = ok ? "已写入: " + label : "写入失败: " + label;
                } else if (block != null && block.type == com.aicodestudio.ide.ai.agent.StreamingToolDetector.TYPE_READ) {
                    text = ok ? "已读取: " + label : "读取失败: " + label;
                } else {
                    text = ok ? "执行成功" : "执行失败";
                }
                if (!ok && summary != null && !summary.isEmpty()) {
                    String s = summary.length() > 60 ? summary.substring(0, 60) + "…" : summary;
                    text += "\n" + s;
                }
                status.setText(text);
                status.setTextColor(ok ? Theme.ACCENT_GREEN : Theme.DANGER_RED);
                status.setTypeface(null, ok ? android.graphics.Typeface.NORMAL : android.graphics.Typeface.BOLD);
            }
        } catch (Throwable ignored) {}
        // v1.127：write 块用剥掉 heredoc 定界符后的完整文件内容最终定格一次代码框，
        // 覆盖中途流式可能一闪而过的 EOF 行，与历史卡片显示一致
        if (block != null && block.type == com.aicodestudio.ide.ai.agent.StreamingToolDetector.TYPE_WRITE
                && activeCodeView != null) {
            try {
                String finalCode = block.content.toString();
                currentToolContent.setLength(0);
                currentToolContent.append(finalCode);
                updateSegmentView(activeCodeView, new CodeSegment(finalCode,
                        activeCodeLang == null ? "shell" : activeCodeLang));
            } catch (Throwable ignored) {}
        }
        activeToolCard = null;
        activeCodeView = null;
        activeCodeLang = null;
        currentToolContent.setLength(0);
        scrollToBottom();
    }

    /**
     * v1.101：单流内联渲染一条完整 AI 消息（历史加载 / 流式完成后 / 切换重建统一调用）。
     *
     * 关键：用 parseMarkdown 切分全文，而不是直接喂 StreamingToolDetector——
     *   parseMarkdown 会把 <think>...</think> 整块收进 ThinkSegment，think 内部的 ```shell 命令
     *   只作为思考内容（留在思考卡片里），绝不会被抽成工具卡片。
     * 切出来的 CodeSegment 全部位于 think 块之外：
     *   - CodeSegment(lang=shell/bash/sh) → 离线索引为读/写/执行，生成小书页/小铅笔/`</>` 工具卡片；
     *   - 其他 CodeSegment（```java 等）→ 普通高亮代码框；
     *   - TextSegment → 普通正文。
     * 这样思考过程里规划用的命令不建卡片、思考内容完整；只有 think 外真正执行的命令才出工具卡片。
     */
    private void renderAiMessageInline(final LinearLayout container, String fullText) {
        if (container == null || fullText == null || fullText.isEmpty()) return;

        java.util.List<Object> segs = parseMarkdown(fullText);
        // v1.127：与流式 detector.recentText 对齐——累积正文文本，用于识别“我来写 Xxx.java”
        // 后跟非 shell 围栏（```java 等）的写文件块，保证流式/历史渲染对称。
        final StringBuilder recentText = new StringBuilder();
        for (Object seg : segs) {
            if (seg instanceof ThinkSegment) {
                // 思考卡片：内部 shell 命令留在思考里，不建工具卡片；思考内容也不计入 recentText，
                // 避免思考过程里的“我来写”误触发写文件卡片（v1.101：think 内规划命令不建卡）
                View thinkBlock = createThinkBlock(((ThinkSegment) seg).content);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                tlp.setMargins(0, dp(4), 0, dp(4));
                thinkBlock.setLayoutParams(tlp);
                container.addView(thinkBlock);
            } else if (seg instanceof CodeSegment) {
                CodeSegment cs = (CodeSegment) seg;
                String lang = cs.language == null ? "" : cs.language.toLowerCase();
                if (lang.equals("shell") || lang.equals("bash") || lang.equals("sh")) {
                    // think 块外的 shell 命令 → 工具卡片
                    com.aicodestudio.ide.ai.agent.StreamingToolDetector.ToolBlock b =
                            com.aicodestudio.ide.ai.agent.StreamingToolDetector.classifyShellCode(
                                    cs.code, cs.language);
                    addHistoricalToolCard(container, b);
                } else {
                    // v1.127：非 shell 围栏（```java 等）若前文是“我来写 Xxx”，按写文件工具卡片渲染，
                    // 与流式 decideFenceLang 行为一致；否则按普通高亮代码框
                    com.aicodestudio.ide.ai.agent.StreamingToolDetector.ToolBlock wb =
                            com.aicodestudio.ide.ai.agent.StreamingToolDetector.matchWriteFence(
                                    cs.code, cs.language, recentText.toString());
                    if (wb != null) {
                        addHistoricalToolCard(container, wb);
                    } else {
                        renderSegment(container, seg, true);
                    }
                }
            } else {
                renderSegment(container, seg, true);
                // 正文文本累积进 recentText（供紧随其后的“我来写 Xxx”写文件块识别）
                if (seg instanceof TextSegment && ((TextSegment) seg).text != null) {
                    recentText.append(((TextSegment) seg).text);
                    if (recentText.length() > 200) recentText.delete(0, recentText.length() - 200);
                }
            }
        }
    }

    /**
     * v1.100：把一条 AI 消息 row 从流式双容器（content+toolHost）定型为单流内联。
     * 用于：历史加载后、流式回复完成后、切换渲染/原始后。
     * 用完整原始文本 fullText 全量重建，并把 row.tag 收敛为 content（content.tag=fullText），
     * 后续切换重建直接从 content.tag 取原文，杜绝在残缺渲染态上硬改 DOM。
     */
    private void finalizeHistoricalRow(View row, String fullText) {
        if (row == null || fullText == null) return;
        Object tag = row.getTag();
        LinearLayout content;
        LinearLayout toolHost = null;
        if (tag instanceof ToolHostTag) {
            content = ((ToolHostTag) tag).content;
            toolHost = ((ToolHostTag) tag).toolHost;
        } else if (tag instanceof LinearLayout) {
            content = (LinearLayout) tag;
        } else {
            return;
        }
        // v1.126: 不再removeAllViews重建——保留流式事件驱动渲染的View。
        // 只把toolHost里的工具卡片追加到content末尾，然后移除toolHost。
        content.setTag(fullText);
        if (showRawMessage) {
            content.removeAllViews();
            TextView tv = new TextView(getContext());
            tv.setText(fullText);
            tv.setTextColor(Theme.TEXT_PRIMARY);
            tv.setTextSize(14);
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            tv.setTextIsSelectable(true);
            content.addView(tv);
        } else if (content.getChildCount() == 0) {
            // 流式没渲染过（云端非流式），才走完整重建
            renderAiMessageInline(content, fullText);
        }
        if (toolHost != null) {
            // 把工具卡片移到content末尾
            while (toolHost.getChildCount() > 0) {
                View child = toolHost.getChildAt(0);
                toolHost.removeViewAt(0);
                if (showRawMessage) child.setVisibility(View.GONE); // v1.140：原始模式后台保留
                content.addView(child);
            }
            ((LinearLayout) row).removeView(toolHost);
        }
        row.setTag(content);
    }

    /**
     * v1.99：历史工具卡片——与流式 addToolCard 同外观（图标 + 圆角卡片 + 内嵌高亮代码框），
     * 但为静态完成态：图标不跑呼吸动画，状态文案直接显示“已读取/已写入/执行完成”。
     */
    private void addHistoricalToolCard(LinearLayout host,
                                       com.aicodestudio.ide.ai.agent.StreamingToolDetector.ToolBlock block) {
        if (host == null || block == null) return;
        Context ctx = getContext();

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Theme.BG_CARD);
        bg.setCornerRadius(dp(10));
        card.setBackground(bg);
        card.setPadding(dp(10), dp(8), dp(10), dp(8));

        // 顶部行：图标 + 完成态文案
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);

        android.widget.ImageView icon = new android.widget.ImageView(ctx);
        int iconRes = block.type == com.aicodestudio.ide.ai.agent.StreamingToolDetector.TYPE_READ
                ? com.aicodestudio.ide.R.drawable.ic_tool_read
                : (block.type == com.aicodestudio.ide.ai.agent.StreamingToolDetector.TYPE_WRITE
                    ? com.aicodestudio.ide.R.drawable.ic_tool_write
                    : com.aicodestudio.ide.R.drawable.ic_tool_exec);
        int color = block.type == com.aicodestudio.ide.ai.agent.StreamingToolDetector.TYPE_READ
                ? Theme.ACCENT_GREEN
                : (block.type == com.aicodestudio.ide.ai.agent.StreamingToolDetector.TYPE_WRITE
                    ? Theme.ACCENT_BLUE : Theme.ACCENT_ORANGE);
        icon.setImageResource(iconRes);
        try { icon.getDrawable().mutate().setTint(color); } catch (Throwable ignored) {}
        int iconSize = dp(18);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconLp.setMargins(0, 0, dp(6), 0);
        icon.setLayoutParams(iconLp);
        head.addView(icon);  // 历史不跑呼吸动画，图标静态

        TextView status = new TextView(ctx);
        status.setText(toolDoneText(block));
        status.setTextColor(Theme.ACCENT_GREEN);  // 历史视为已完成，绿色
        status.setTextSize(13);
        status.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        status.setLayoutParams(stLp);
        head.addView(status);
        card.addView(head);

        // 内嵌代码框：写文件=完整文件内容；读=cat 路径；执行=命令
        String lang = langForToolBlock(block);
        String displayCode;
        if (block.type == com.aicodestudio.ide.ai.agent.StreamingToolDetector.TYPE_READ) {
            displayCode = "cat " + block.path;
        } else if (block.type == com.aicodestudio.ide.ai.agent.StreamingToolDetector.TYPE_WRITE) {
            displayCode = block.content.toString().trim();
        } else {
            String cmd = (block.cmd == null ? "" : block.cmd.trim());
            String rest = block.content.toString().trim();
            displayCode = rest.isEmpty() ? cmd : (cmd + "\n" + rest);
        }
        View codeView = createCodeBlock(displayCode, lang, false);
        card.addView(codeView);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, dp(4), 0, dp(4));
        host.addView(card, cardLp);
    }

    /** v1.99：历史工具卡片完成态文案 */
    private String toolDoneText(com.aicodestudio.ide.ai.agent.StreamingToolDetector.ToolBlock block) {
        String label = block.label();
        switch (block.type) {
            case com.aicodestudio.ide.ai.agent.StreamingToolDetector.TYPE_READ:
                return "已读取: " + label;
            case com.aicodestudio.ide.ai.agent.StreamingToolDetector.TYPE_WRITE:
                return "已写入: " + label;
            default:
                return label.isEmpty() ? "执行完成" : "执行: " + label;
        }
    }

    // ==================== Markdown 渲染 ====================

    /**
     * 渲染 Markdown 内容到容器中
     * 支持：普通文本段落、代码块（``` 包裹）
     */
    private void renderMarkdown(LinearLayout container, String text, boolean useHighlight) {
        if (text == null || text.isEmpty()) return;

        // v1.98：移除旧版“文本 > 30000 字符整体降级为纯文本”的保护。
        // 根因：v1.91g 之后历史存的是含完整 <think> 思考过程 + 全部 ``` 代码块 + ```shell 命令块
        //       的原始完整 Markdown，一条完整 Agent 回复很容易超过 30000 字符；历史打开时
        //       renderMarkdown 被这道阈值一刀切降级成纯等宽 TextView，所有代码框/命令框/思考框
        //       都不渲染，只剩一坨带 ``` 标记的原文。而流式实时路径 updateLoadingMessage →
        //       parseMarkdown 没有这道阈值，逐段渲染正常 —— 即“实时正常、重开历史不渲染”的不对称点。
        // 现在历史与流式共用同一套 parseMarkdown 分段渲染：
        //   - 代码块 WebView 已有 MAX_ACTIVE_WEBVIEWS=10 自动降级为纯文本 TextView，不会无限累积；
        //   - 普通文本 / 思考过程块均为轻量 TextView，超长也不会崩溃。
        List<Object> segments = parseMarkdown(text);
        for (Object seg : segments) {
            renderSegment(container, seg, useHighlight);
        }
    }

    /**
     * 创建单个 segment 对应的 View（不挂载到容器）。
     * v1.48f：从 renderSegment 提取，供增量渲染替换/追加复用，
     * 保证"只重建变化节点、其余增量更新"的单一路径。
     * 返回 null 表示该段无可渲染内容（空文本等）。
     *
     * - 普通文本：创建 TextView，渲染 Markdown
     * - 代码块：调用 createCodeBlock 创建代码框
     * - 思考过程：调用 createThinkBlock 创建思考卡片
     */
    private View createSegmentView(Object seg, boolean useHighlight) {
        if (seg instanceof TextSegment) {
            String t = ((TextSegment) seg).text;
            if (t == null || t.isEmpty()) return null;
            t = t.replaceAll("^\\n+|\\n+$", "");
            if (t.isEmpty()) return null;

            TextView tv = new TextView(getContext());
            try {
                String html = markdownToHtml(t);
                Spanned spanned = Html.fromHtml(html);
                tv.setText(spanned);
            } catch (Throwable e) {
                tv.setText(t);
            }
            tv.setTextColor(Theme.TEXT_PRIMARY);
            tv.setTextSize(14);
            tv.setPadding(0, dp(2), 0, dp(2));
            tv.setTextIsSelectable(true);
            tv.setHorizontallyScrolling(false);
            tv.setLineSpacing(0, 1.3f);
            // v1.153m：正文与思考卡片同宽（MATCH_PARENT），短行也不窄于卡片
            tv.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            return tv;
        } else if (seg instanceof CodeSegment) {
            CodeSegment cs = (CodeSegment) seg;
            // v1.91c：不再走 v1.94 事后 renderCatWriteBlock 旧卡片（与流式即时卡片打架），
            // 一律用普通高亮代码框渲染；shell cat> 块的即时卡片由 detector 独占
            View rendered = createCodeBlock(cs.code, cs.language, useHighlight);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(4), 0, dp(4));
            rendered.setLayoutParams(lp);
            return rendered;
        } else if (seg instanceof ThinkSegment) {
            ThinkSegment ts = (ThinkSegment) seg;
            View thinkBlock = createThinkBlock(ts.content);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(4), 0, dp(4));
            thinkBlock.setLayoutParams(lp);
            return thinkBlock;
        }
        return null;
    }

    /**
     * 渲染单个 segment 到容器中（用于增量添加，不重建已有 View）
     *
     * - 普通文本：创建 TextView，渲染 Markdown
     * - 代码块：调用 createCodeBlock 创建代码框
     */
    private void renderSegment(LinearLayout container, Object seg, boolean useHighlight) {
        View v = createSegmentView(seg, useHighlight);
        if (v != null) container.addView(v);
    }

    /**
     * 从代码块标记行提取语言标签。
     * 输入：```java、```java // 主代码、``` java-kotlin、```xml 等
     * 规则：跳过开头连续反引号，取第一个空白字符（空格/制表符/换行）之前的单词作为语言标签，
     *       其余内容（注释、多余文字）全部丢弃。
     * 兼容本地小模型输出的各种不规范格式。
     */
    private String extractLanguageTag(String trimmed, int backtickCount) {
        if (trimmed == null || backtickCount >= trimmed.length()) return "";
        String rest = trimmed.substring(backtickCount).trim();
        if (rest.isEmpty()) return "";
        // 取第一个空白字符之前的部分作为语言标签
        int end = 0;
        while (end < rest.length()) {
            char c = rest.charAt(end);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') break;
            end++;
        }
        String lang = rest.substring(0, end).trim();
        // 过滤掉明显不是语言名的杂质：反引号、井号、星号、括号
        lang = lang.replaceAll("[`#*()\\[\\]{}]", "");
        return lang;
    }

    /**
     * v1.143：think 标签兜底规整——连续重复的开始/闭合标签合并为一个。
     * 本地小模型在开头可能连打多个 <think>（prompt 预填 + 模型自己输出），
     * 也可能多打 </think>，这里统一清理，保证只渲染一个思考块。
     */
    private String normalizeThinkTags(String text) {
        if (text == null || text.isEmpty()) return text;
        // 连续多个 <think>（中间允许空白/换行）→ 一个
        text = text.replaceAll("(?:<think>\\s*){2,}", "<think>\n");
        // 连续多个 </think>（中间允许空白/换行）→ 一个
        text = text.replaceAll("(?:</think>\\s*){2,}", "</think>\n");
        return text;
    }

    /**
     * 解析 Markdown，分割成普通文本段落、代码块、思考过程
     * 基于逐行解析，更可靠
     * 支持：普通文本、```代码块```、<think>思考过程</think>
     */
    private List<Object> parseMarkdown(String text) {
        List<Object> segments = new ArrayList<>();
        if (text == null || text.isEmpty()) return segments;

        // v1.143：兜底容错——本地小模型可能在开头连打多个 <think> 标签（prompt 预填一个 +
        // 模型自己再输出一个），渲染前先把连续重复的开始/闭合标签合并成一个，
        // 避免出现两个思考块/两个 think 标签。
        text = normalizeThinkTags(text);

        String[] lines = text.split("\n", -1);
        StringBuilder currentText = new StringBuilder();
        StringBuilder currentCode = new StringBuilder();
        String currentLang = "";
        boolean inCodeBlock = false;
        StringBuilder currentThink = new StringBuilder();
        boolean inThinkBlock = false;

        // 辅助方法：保存当前普通文本
        java.lang.Runnable flushText = () -> {
            if (currentText.length() > 0) {
                String t = currentText.toString();
                if (!t.trim().isEmpty()) {
                    segments.add(new TextSegment(t));
                }
                currentText.setLength(0);
            }
        };

        for (String line : lines) {
            String trimmed = line.trim();

            // ===== 处理思考过程 <think>...</think> =====
            if (inThinkBlock) {
                int thinkEnd = line.indexOf("</think>");
                if (thinkEnd >= 0) {
                    currentThink.append(line, 0, thinkEnd);
                    inThinkBlock = false;
                    String thinkContent = currentThink.toString().trim();
                    if (!thinkContent.isEmpty()) {
                        flushText.run();
                        segments.add(new ThinkSegment(thinkContent));
                    }
                    currentThink.setLength(0);
                    String rest = line.substring(thinkEnd + 8);
                    if (!rest.isEmpty()) {
                        currentText.append(rest).append("\n");
                    }
                } else {
                    currentThink.append(line).append("\n");
                }
                continue;
            }

            int thinkStart = line.indexOf("<think>");
            if (thinkStart >= 0) {
                String beforeThink = line.substring(0, thinkStart);
                if (!beforeThink.isEmpty()) {
                    currentText.append(beforeThink).append("\n");
                }
                flushText.run();
                int thinkEnd = line.indexOf("</think>", thinkStart + 7);
                if (thinkEnd >= 0) {
                    String thinkContent = line.substring(thinkStart + 7, thinkEnd).trim();
                    if (!thinkContent.isEmpty()) {
                        segments.add(new ThinkSegment(thinkContent));
                    }
                    String rest = line.substring(thinkEnd + 8);
                    if (!rest.isEmpty()) {
                        currentText.append(rest).append("\n");
                    }
                } else {
                    inThinkBlock = true;
                    currentThink.setLength(0);
                    String thinkContent = line.substring(thinkStart + 7);
                    if (!thinkContent.trim().isEmpty()) {
                        currentThink.append(thinkContent).append("\n");
                    }
                }
                continue;
            }

            // ===== 处理代码块 ``` =====
            // 本地小模型可能输出 4/5/7 个反引号，缺失或变多都要能准确识别
            // 用 startsWith("```") 比正则 matches 更可靠（4个反引号也以3个开头）
            if (trimmed.startsWith("```")) {
                // 计算反引号数量
                int backtickCount = 0;
                while (backtickCount < trimmed.length() && trimmed.charAt(backtickCount) == '`') {
                    backtickCount++;
                }
                if (!inCodeBlock) {
                    // 开始代码块
                    flushText.run();
                    // 语言标签提取：只取反引号后第一个空白字符之前的单词
                    // 兼容 ```java、```java // 注释、``` java 等格式，避免把杂质读进语言名
                    currentLang = extractLanguageTag(trimmed, backtickCount);
                    currentCode.setLength(0);
                    inCodeBlock = true;
                } else {
                    // v1.103：已在代码块内又遇到一行 ``` 开头。
                    // 区分两种写法：
                    //   (a) 单独 ```            → 普通闭合当前块；
                    //   (b) ```java / ```xml …  → “闭合上一块 + 立即开新块”。
                    // 小模型常输出连续块：```shell\ncat > X.java\n```java\npackage…\n```，
                    // 旧逻辑把 ```java 当成普通闭合吞掉、不开新块，导致后面的代码段落进普通文本而丢失。
                    String afterFence = trimmed.substring(backtickCount).trim();
                    // 先把当前正在收的块收尾
                    String code = currentCode.toString().replaceAll("^\\n+|\\n+$", "");
                    flushText.run();
                    segments.add(new CodeSegment(code, currentLang));
                    currentCode.setLength(0);
                    if (afterFence.isEmpty()) {
                        // (a) 单独 ``` → 闭合，不在代码块内
                        inCodeBlock = false;
                        currentLang = "";
                    } else {
                        // (b) ```java/```xml → 同一行立即开始下一个新代码块
                        currentLang = extractLanguageTag(trimmed, backtickCount);
                        // inCodeBlock 保持 true
                    }
                }
            } else {
                if (inCodeBlock) {
                    currentCode.append(line).append("\n");
                } else {
                    currentText.append(line).append("\n");
                }
            }
        }

        // 处理未结束的思考过程
        if (inThinkBlock) {
            String thinkContent = currentThink.toString().trim();
            if (thinkContent.isEmpty()) {
                thinkContent = "思考中...";
            }
            flushText.run();
            segments.add(new ThinkSegment(thinkContent));
        }

        // 处理未结束的代码块（流式输出中只有开始标记没有结束标记）
        // 即使代码内容为空，也立即创建代码框，避免用户等待
        if (inCodeBlock) {
            String code = currentCode.toString().replaceAll("^\\n+|\\n+$", "");
            flushText.run();
            segments.add(new CodeSegment(code, currentLang));
        }

        // 处理剩余的普通文本
        if (!inCodeBlock && !inThinkBlock && currentText.length() > 0) {
            String t = currentText.toString();
            if (!t.trim().isEmpty()) {
                segments.add(new TextSegment(t));
            }
        }

        return segments;
    }

    // ==================== Markdown 到 HTML 转换 ====================

    /**
     * 将 Markdown 文本转换为 HTML，供 Html.fromHtml() 渲染。
     * 支持：标题、粗体、斜体、行内代码、链接、无序列表、有序列表、分割线、段落。
     */
    private String markdownToHtml(String text) {
        // 统一委托 MarkdownUtil（支持 1-6 级标题宽松匹配、粗体、斜体、行内代码、链接、删除线、列表、分割线）
        return com.aicodestudio.ide.ai.util.MarkdownUtil.markdownToHtml(text);
    }

    /** 渲染行内 Markdown：粗体、斜体、行内代码、链接、删除线 */
    private String renderInlineMarkdown(String text) {
        return com.aicodestudio.ide.ai.util.MarkdownUtil.renderInlineMarkdown(text);
    }

    /** HTML 转义 */
    private String escapeHtml(String text) {
        return com.aicodestudio.ide.ai.util.MarkdownUtil.escapeHtml(text);
    }

    /**
     * 创建代码框 View
     * 统一实现：每个代码框独立创建新的 WebView，加载基础 HTML，通过 evaluateJavascript 设置代码
     * - 固定高度 200dp，可上下滑动
     * - 顶部显示语言名称 + 展开/收起按钮
     * - 点击展开/收起按钮切换固定高度和自适应高度
     */

    /** 文件后缀 → highlight.js 语言名 */
    private String mapExtToLanguage(String ext) {
        if (ext == null) return "plaintext";
        switch (ext) {
            case "java": return "java";
            case "xml": return "xml";
            case "gradle": case "kts": return "groovy";
            case "kt": return "kotlin";
            case "py": return "python";
            case "js": return "javascript";
            case "html": return "xml";
            case "css": return "css";
            case "json": return "json";
            case "md": return "markdown";
            case "sh": case "bash": return "bash";
            case "c": case "h": return "c";
            case "cpp": case "hpp": return "cpp";
            default: return "plaintext";
        }
    }

    private View createCodeBlock(String code, String language, boolean useHighlight) {
        final Context ctx = getContext();
        // v1.55：收起高度从 200dp 降到 80dp，短代码（如单行 bash）不会显得空，长代码点展开看
        final int collapsedHeight = dp(80);

        // 外层容器
        final LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackground(makeCodeBlockBg());
        container.setPadding(dp(2), dp(2), dp(2), dp(2));

        // 标题栏
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), dp(4), dp(8), dp(4));

        TextView langTv = new TextView(ctx);
        langTv.setText(language.isEmpty() ? "代码" : language);
        langTv.setTextColor(0xFFB0BEC5);
        langTv.setTextSize(11);
        langTv.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams langLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(langTv, langLp);

        // 展开/收起按钮
        final Button toggleBtn = new Button(ctx);
        toggleBtn.setText("展开");
        toggleBtn.setTextColor(0xFF90CAF9);
        toggleBtn.setTextSize(11);
        toggleBtn.setBackgroundColor(Color.TRANSPARENT);
        toggleBtn.setPadding(dp(8), dp(2), dp(8), dp(2));
        toggleBtn.setMinWidth(0);
        toggleBtn.setMinHeight(0);
        toggleBtn.setMinimumWidth(0);
        toggleBtn.setMinimumHeight(0);
        header.addView(toggleBtn);

        container.addView(header);

        // 分割线
        View divider = new View(ctx);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        divider.setBackgroundColor(0xFF3E4451);
        container.addView(divider);

        // v1.153bf：每次创建新 WebView（和代码编辑器一致），等 onPageFinished 后再设代码
        // 池化 WebView 存在 JS 时序问题导致 hljs 不可用
        final WebView codeWebView = new WebView(ctx);
        dbgLog("createCodeBlock: new WebView, lang=" + language + ", codeLen=" + (code != null ? code.length() : 0));
        codeWebView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, collapsedHeight));

        WebSettings webSettings = codeWebView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(false);
        webSettings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        webSettings.setDefaultTextEncodingName("UTF-8");
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN) {
            webSettings.setAllowUniversalAccessFromFileURLs(true);
        }
        codeWebView.setBackgroundColor(0xFF282C34);
        codeWebView.setVerticalScrollBarEnabled(true);
        codeWebView.setHorizontalScrollBarEnabled(true);

        // 保存状态到 tag：WebView 引用 + 当前代码内容（用于增量更新时比较）
        final CodeBoxTag boxTag = new CodeBoxTag(codeWebView, code);
        boxTag.contentContainer = container;
        container.setTag(boxTag);

        // 完全复制代码编辑器的方式：onPageFinished 立即设置代码，不延迟
        final Runnable whenReady = new Runnable() {
            @Override public void run() {
                if (boxTag.degraded || boxTag.webView == null) return;
                boxTag.pageLoaded = true;
                String toSet = (boxTag.pendingCode != null) ? boxTag.pendingCode : code;
                boxTag.pendingCode = null;
                dbgLog("whenReady: toSet=" + (toSet != null ? toSet.length() : 0) + " chars");
                if (toSet != null && !toSet.isEmpty()) {
                    String langToUse = (boxTag.currentLang != null && !boxTag.currentLang.isEmpty())
                            ? boxTag.currentLang : language;
                    boxTag.currentLang = langToUse;
                    dbgLog("whenReady: doSetCode lang=" + langToUse);
                    doSetCode(codeWebView, langToUse, toSet);
                    boxTag.currentCode = toSet;
                    // 检查 hljs 是否存在
                    codeWebView.evaluateJavascript(
                        "(typeof hljs !== 'undefined' ? 'hljs=YES' : 'hljs=NO') + ' currentLang=' + (typeof currentLang !== 'undefined' ? currentLang : 'undef')",
                        new android.webkit.ValueCallback<String>() {
                            @Override public void onReceiveValue(String value) {
                                dbgLog("JS check: " + value);
                            }
                        });
                }
            }
        };
        codeWebView.setWebViewClient(new android.webkit.WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                dbgLog("onPageFinished: url=" + url);
                whenReady.run();
            }
        });
        codeWebView.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override public boolean onConsoleMessage(android.webkit.ConsoleMessage cm) {
                dbgLog("JS console: " + cm.message() + " @" + cm.lineNumber());
                return true;
            }
        });
        codeWebView.loadDataWithBaseURL(
                "file:///android_asset/highlight/",
                buildStreamingHtml(language),
                "text/html", "UTF-8", null);

        // 展开/收起逻辑（兼容降级模式：WebView 被回收后操作降级 TextView）
        final boolean[] expanded = {false};
        toggleBtn.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                View contentView = boxTag.degraded ? boxTag.degradedTv : boxTag.webView;
                if (contentView == null) return;
                if (expanded[0]) {
                    contentView.setLayoutParams(new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, collapsedHeight));
                    toggleBtn.setText("展开");
                    expanded[0] = false;
                } else {
                    contentView.setLayoutParams(new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT));
                    toggleBtn.setText("收起");
                    expanded[0] = true;
                }
            }
        });

        container.addView(codeWebView);

        // 记录活跃代码框并降级回收最旧的（防止 WebView 进程无限累积导致发热）
        synchronized (sCodeBoxOrder) {
            sCodeBoxOrder.addLast(boxTag);
        }
        degradeOldestCodeBoxes();

        return container;
    }

    /**
     * 降级回收最旧的代码框：当活跃 WebView 数量超过上限时，
     * 把最旧的代码框从 WebView 替换为纯文本 TextView 并销毁其 WebView，
     * 释放 WebView 独立渲染进程（内存 + GPU 占用），防止长对话发热。
     * 降级后的代码框仍可正常展开/收起和查看代码（纯文本显示）。
     */
    private void degradeOldestCodeBoxes() {
        try {
            synchronized (sCodeBoxOrder) {
                while (sCodeBoxOrder.size() > MAX_ACTIVE_WEBVIEWS) {
                    CodeBoxTag oldest = sCodeBoxOrder.pollFirst();
                    if (oldest == null) break;
                    if (oldest.degraded || oldest.webView == null) continue;
                    degradeCodeBox(oldest);
                }
            }
        } catch (Throwable ignored) {}
    }

    /** 把单个代码框降级为纯文本 TextView 并销毁 WebView */
    private void degradeCodeBox(final CodeBoxTag tag) {
        try {
            final WebView wv = tag.webView;
            final LinearLayout container = tag.contentContainer;
            if (wv == null || container == null) return;

            // 创建纯文本 TextView 显示当前代码（替换 WebView）
            final TextView tv = new TextView(getContext());
            tv.setTextColor(0xFFABB2BF);
            tv.setTextSize(12);
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            tv.setPadding(dp(10), dp(8), dp(10), dp(8));
            tv.setTextIsSelectable(true);
            tv.setLineSpacing(0, 1.3f);
            tv.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            tv.setText(tag.currentCode != null ? tag.currentCode : "");

            // 用 TextView 替换 WebView 位置
            if (wv.getParent() == container) {
                int idx = container.indexOfChild(wv);
                container.removeViewAt(idx);
                container.addView(tv, idx);
            }
            tag.degradedTv = tv;
            tag.degraded = true;

            // 销毁 WebView，释放其渲染进程
            wv.setWebViewClient(null);
            wv.loadUrl("about:blank");
            wv.destroy();
            tag.webView = null;
        } catch (Throwable ignored) {}
    }

    /**
     * 创建思考过程卡片（思考模型的 <think>...</think> 内容）
     * 类似代码框，可展开/收起，顶部显示"思考过程"，默认收起
     */
    /** v1.125: 从think block View里递归找到内容TextView */
    private TextView findThinkContentTv(android.view.ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof TextView) {
                TextView tv = (TextView) child;
                if (tv.getCurrentTextColor() == 0xFF90A4AE) return tv;
            } else if (child instanceof android.view.ViewGroup) {
                TextView found = findThinkContentTv((android.view.ViewGroup) child);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** v1.125: 更新已存在的code block View的内容 */
    private void updateCodeBlockContent(View codeView, String code, String lang) {
        if (codeView == null || code == null) return;
        try {
            Object tag = codeView.getTag();
            if (tag instanceof CodeBoxTag) {
                CodeBoxTag boxTag = (CodeBoxTag) tag;
                if (boxTag.degraded) {
                    if (boxTag.degradedTv != null && !code.equals(boxTag.currentCode)) {
                        boxTag.degradedTv.setText(code);
                        boxTag.currentCode = code;
                    }
                    return;
                }
                WebView wv = boxTag.webView;
                if (wv == null) return;
                String hlLang = mapToHighlightJsLanguage(lang);
                if (boxTag.pageLoaded) {
                    // v1.153bf：每次都全量 setLanguage+setCode，确保 JS currentLang 正确、
                    // hljs.highlight 用正确语言高亮。appendCode 路径下 currentLang 可能未就绪。
                    setCodeWithLangToWebView(wv, code, hlLang);
                    boxTag.currentCode = code;
                    boxTag.currentLang = lang;
                } else {
                    boxTag.pendingCode = code;
                    boxTag.currentCode = code;
                    boxTag.currentLang = lang;
                }
            }
        } catch (Throwable ignored) {}
    }

    private TextView findCodeTextView(android.view.ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof TextView && ((TextView) child).getTypeface() == android.graphics.Typeface.MONOSPACE) {
                return (TextView) child;
            } else if (child instanceof android.view.ViewGroup) {
                TextView found = findCodeTextView((android.view.ViewGroup) child);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * v1.153ay：沿父链逐级 requestDisallowInterceptTouchEvent。
     * 卡片内部 ScrollView 滚动时，只对 direct parent 设 flag 锁不住外层消息列表
     * ScrollView（flag 不会自动向上传播）；必须从卡片的直接父容器一路设到外层
     * scrollView，才能保证"卡片内滚动优先"，滚到底释放后外层自然接管。
     */
    private void disallowParentScrollChain(View v, boolean disallow) {
        try {
            android.view.ViewParent p = v.getParent();
            while (p instanceof android.view.ViewGroup) {
                p.requestDisallowInterceptTouchEvent(disallow);
                p = p.getParent();
            }
        } catch (Throwable ignored) {}
    }

    private View createThinkBlock(String content) {
        Context ctx = getContext();
        final int collapsedHeight = dp(36);  // 收起时只显示标题栏

        // 外层容器
        final LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackground(makeCodeBlockBg());
        container.setPadding(dp(2), dp(2), dp(2), dp(2));

        // 标题栏
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), dp(4), dp(8), dp(4));

        // v1.136：思考图标（灯泡）+ 文字，替换原 emoji
        android.widget.ImageView thinkIcon = new android.widget.ImageView(ctx);
        android.graphics.drawable.Drawable thinkD = UiKit.icon(ctx, "ic_think", 0xFFB0BEC5);
        if (thinkD != null) {
            thinkIcon.setImageDrawable(thinkD);
            thinkIcon.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        }
        thinkIcon.setLayoutParams(new LinearLayout.LayoutParams(dp(18), dp(18)));
        header.addView(thinkIcon);

        TextView titleTv = new TextView(ctx);
        titleTv.setText(" 思考过程");
        titleTv.setTextColor(0xFFB0BEC5);
        titleTv.setTextSize(11);
        titleTv.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(titleTv, titleLp);

        // 展开/收起按钮
        final Button toggleBtn = new Button(ctx);
        toggleBtn.setText("展开");
        toggleBtn.setTextColor(0xFF90CAF9);
        toggleBtn.setTextSize(11);
        toggleBtn.setBackgroundColor(Color.TRANSPARENT);
        toggleBtn.setPadding(dp(8), dp(2), dp(8), dp(2));
        toggleBtn.setMinWidth(0);
        toggleBtn.setMinHeight(0);
        toggleBtn.setMinimumWidth(0);
        toggleBtn.setMinimumHeight(0);
        header.addView(toggleBtn);

        container.addView(header);

        // 分割线
        View divider = new View(ctx);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        divider.setBackgroundColor(0xFF3E4451);
        container.addView(divider);

        // 内容区域（ScrollView + TextView）
        // 内容区域（ScrollView + TextView）
        // v1.153az：ScrollView 子类化——忽略子 TextView(textIsSelectable) 的
        // requestDisallowInterceptTouchEvent 请求。selectable 文本为支持选择拖动会请求
        // 父容器禁拦截，卡片 ScrollView 因此收不到滑动 → 输出完成后思考内容滑不动
        // （只有长按出复制框后拖手柄能滚）。忽略后卡片正常拦截滚动，长按复制仍可用。
        final ScrollView contentScroll = new ScrollView(ctx) {
            @Override public void requestDisallowInterceptTouchEvent(boolean disallow) {
                // 故意忽略：selectable TextView 的选择手势不应锁死卡片滚动
            }
        };
        contentScroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(180)));  // v1.55：高度从 300dp 降到 180dp，不占太多屏幕
        contentScroll.setVisibility(View.VISIBLE);  // 默认展开，让用户直接看到思考内容

        final TextView contentTv = new TextView(ctx);
        // 思考过程内容渲染 Markdown（粗体、列表、代码等）
        try {
            String html = com.aicodestudio.ide.ai.util.MarkdownUtil.markdownToHtml(content == null ? "" : content);
            contentTv.setText(android.text.Html.fromHtml(html));
        } catch (Throwable e) {
            contentTv.setText(content == null ? "" : content);
        }
        contentTv.setTextColor(0xFF90A4AE);
        contentTv.setTextSize(14);
        // v1.153n：文本贴近思考框边缘，从框开始充满显示
        contentTv.setPadding(dp(4), dp(4), dp(4), dp(4));
        contentTv.setTextIsSelectable(true);
        contentTv.setLineSpacing(0, 1.3f);
        contentScroll.addView(contentTv);
        // v1.153az：手势区分——滑动（MOVE 超 touchSlop）=卡片内滚动；短按/长按=TextView 文本选择复制。
        // selectable TextView 会 requestDisallowInterceptTouchEvent 阻止 ScrollView 收滚动事件，
        // 直接在 TextView 触摸层手动 scrollBy：滑动归卡片、长按归复制，互不抢。
        // 卡片滚到边界后释放外层，外层消息列表自然接管后续滑动（嵌套滚动时序）。
        final int touchSlop = android.view.ViewConfiguration.get(ctx).getScaledTouchSlop();
        contentTv.setOnTouchListener(new android.view.View.OnTouchListener() {
            private float downY = 0, lastY = 0;
            private boolean scrolling = false;
            @Override public boolean onTouch(View v, android.view.MotionEvent ev) {
                float y = ev.getY();
                int a = ev.getAction();
                if (a == android.view.MotionEvent.ACTION_DOWN) {
                    downY = lastY = y;
                    scrolling = false;
                    return false; // DOWN 放行：TextView 收到才会长按出复制手柄
                }
                if (a == android.view.MotionEvent.ACTION_MOVE) {
                    if (!scrolling) {
                        if (Math.abs(y - downY) < touchSlop) return false; // 还没形成滑动手势
                        scrolling = true;
                        // 滑动意图确认：锁住外层消息列表滚动，手势从此归卡片
                        disallowParentScrollChain(contentScroll, true);
                    }
                    int dy = (int) (lastY - y);
                    // 卡片内已滚到边界：释放外层接管后续滑动
                    if ((dy > 0 && !contentScroll.canScrollVertically(1))
                            || (dy < 0 && !contentScroll.canScrollVertically(-1))) {
                        disallowParentScrollChain(contentScroll, false);
                        return false;
                    }
                    contentScroll.scrollBy(0, dy);
                    lastY = y;
                    return true; // 消费：不让 TextView 进入文本选择拖动
                }
                // UP / CANCEL
                if (scrolling) {
                    disallowParentScrollChain(contentScroll, false);
                    // 松手后还能继续下滚=用户停在中部看前面 → 暂停自动跟随；滚到底=恢复跟随
                    thinkCardUserScrolled[0] = contentScroll.canScrollVertically(1);
                    scrolling = false;
                    return true;
                }
                return false;
            }
        });
        container.addView(contentScroll);

        // 展开/收起逻辑（默认展开）
        final boolean[] expanded = {true};
        toggleBtn.setText("收起");
        toggleBtn.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                if (expanded[0]) {
                    contentScroll.setVisibility(View.GONE);
                    toggleBtn.setText("展开");
                    expanded[0] = false;
                } else {
                    contentScroll.setVisibility(View.VISIBLE);
                    toggleBtn.setText("收起");
                    expanded[0] = true;
                }
            }
        });

        // 保存内容 TextView 引用到 tag，用于增量更新
        container.setTag(contentTv);
        return container;
    }

    /**
     * 生成 highlight.js 语法高亮的 HTML
     * 支持 190+ 编程语言，GitHub Dark 主题
     */
    private String buildHighlightHtml(String code, String language) {
        // 语言名称映射到 highlight.js 支持的语言
        String hlLang = mapToHighlightJsLanguage(language);

        // HTML 转义
        String escapedCode = code
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");

        return "<!DOCTYPE html>\n" +
                "<html>\n" +
                "<head>\n" +
                "<meta charset=\"UTF-8\">\n" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "<style>\n" +
                "  /* One Dark 主题 - 内联 CSS，避免外部文件加载失败 */\n" +
                "  body { margin: 0; padding: 0; background: #282c34; color: #abb2bf; }\n" +
                "  pre { margin: 0; padding: 10px; overflow-x: auto; background: #282c34; }\n" +
                "  pre code { font-family: 'Courier New', monospace; font-size: 12px; line-height: 1.5; color: #abb2bf; background: #282c34; }\n" +
                "  .hljs { color: #abb2bf; background: #282c34; }\n" +
                "  .hljs-keyword, .hljs-selector-tag, .hljs-literal, .hljs-section, .hljs-link { color: #c678dd; }\n" +
                "  .hljs-string, .hljs-attr, .hljs-symbol, .hljs-bullet, .hljs-addition { color: #98c379; }\n" +
                "  .hljs-comment, .hljs-quote { color: #7f848e; font-style: italic; }\n" +
                "  .hljs-number, .hljs-regexp, .hljs-variable, .hljs-template-variable { color: #d19a66; }\n" +
                "  .hljs-function .hljs-title, .hljs-title, .hljs-name { color: #61afef; }\n" +
                "  .hljs-type, .hljs-class .hljs-title, .hljs-built_in { color: #e5c07b; }\n" +
                "  .hljs-operator, .hljs-meta, .hljs-deletion { color: #56b6c2; }\n" +
                "  .hljs-attribute { color: #d19a66; }\n" +
                "  .hljs-tag { color: #e06c75; }\n" +
                "  .hljs-emphasis { font-style: italic; }\n" +
                "  .hljs-strong { font-weight: bold; }\n" +
                "  ::-webkit-scrollbar { width: 8px; height: 8px; }\n" +
                "  ::-webkit-scrollbar-track { background: #282c34; }\n" +
                "  ::-webkit-scrollbar-thumb { background: #3e4451; border-radius: 4px; }\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<pre><code class=\"language-" + hlLang + "\">" + escapedCode + "</code></pre>\n" +
                "<script>" + getInlineHighlightJs() + "</script>\n" +
                "<script>\n" +
                "  try { hljs.highlightAll(); } catch(e) {}\n" +
                "</script>\n" +
                "</body>\n" +
                "</html>";
    }

    /** 内联 highlight.min.js 内容（缓存一次） */
    private static String inlineHighlightJs = null;
    private String getInlineHighlightJs() {
        if (inlineHighlightJs != null) return inlineHighlightJs;
        try {
            java.io.InputStream is = getContext().getAssets().open("highlight/highlight.min.js");
            byte[] buf = new byte[is.available()];
            is.read(buf);
            is.close();
            String src = new String(buf, "UTF-8");
            // 卡点1：minified JS 里可能含 </script 序列，HTML 解析器会截断 script 块
            src = src.replace("</script", "<\\/script").replace("<!--", "<\\!--");
            inlineHighlightJs = src;
        } catch (Throwable t) {
            inlineHighlightJs = "";
        }
        return inlineHighlightJs;
    }

    /**
     * 构建流式输出用的基础 HTML（空代码框，支持 JavaScript 局部追加代码）
     */
    private String buildStreamingHtml(String language) {
        String hlLang = mapToHighlightJsLanguage(language);
        return "<!DOCTYPE html>\n" +
                "<html>\n" +
                "<head>\n" +
                "<meta charset=\"UTF-8\">\n" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "<style>\n" +
                "  body { margin: 0; padding: 0; background: #282c34; color: #abb2bf; }\n" +
                "  pre { margin: 0; padding: 10px; overflow-x: auto; background: #282c34; }\n" +
                "  pre code { font-family: 'Courier New', monospace; font-size: 12px; line-height: 1.5; color: #abb2bf; background: #282c34; white-space: pre-wrap; word-wrap: break-word; }\n" +
                "  .hljs { color: #abb2bf; background: #282c34; }\n" +
                "  .hljs-keyword, .hljs-selector-tag, .hljs-literal, .hljs-section, .hljs-link { color: #c678dd; }\n" +
                "  .hljs-string, .hljs-attr, .hljs-symbol, .hljs-bullet, .hljs-addition { color: #98c379; }\n" +
                "  .hljs-comment, .hljs-quote { color: #7f848e; font-style: italic; }\n" +
                "  .hljs-number, .hljs-regexp, .hljs-variable, .hljs-template-variable { color: #d19a66; }\n" +
                "  .hljs-function .hljs-title, .hljs-title, .hljs-name { color: #61afef; }\n" +
                "  .hljs-type, .hljs-class .hljs-title, .hljs-built_in { color: #e5c07b; }\n" +
                "  .hljs-operator, .hljs-meta, .hljs-deletion { color: #56b6c2; }\n" +
                "  .hljs-attribute { color: #d19a66; }\n" +
                "  .hljs-tag { color: #e06c75; }\n" +
                "  .hljs-emphasis { font-style: italic; }\n" +
                "  .hljs-strong { font-weight: bold; }\n" +
                "  ::-webkit-scrollbar { width: 8px; height: 8px; }\n" +
                "  ::-webkit-scrollbar-track { background: #282c34; }\n" +
                "  ::-webkit-scrollbar-thumb { background: #3e4451; border-radius: 4px; }\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<pre><code id=\"code\" class=\"language-" + hlLang + "\"></code></pre>\n" +
                "<script src=\"highlight.min.js\"></script>\n" +
                "<script>\n" +
                "  var codeEl = document.getElementById('code');\n" +
                "  var currentLang = '" + hlLang + "';\n" +
                "  var pendingText = null;\n" +
                "  function doHighlightNow(text){\n" +
                "    codeEl.textContent = text;\n" +
                "    if (typeof hljs === 'undefined' || !hljs.highlight) { pendingText = null; return; }\n" +
                "    try {\n" +
                "      var r;\n" +
                "      if (currentLang && currentLang !== 'plaintext' && currentLang !== 'none') {\n" +
                "        r = hljs.highlight(text, {language: currentLang});\n" +
                "      } else {\n" +
                "        r = hljs.highlightAuto(text);\n" +
                "      }\n" +
                "      codeEl.innerHTML = r.value;\n" +
                "    } catch(e) {}\n" +
                "    pendingText = null;\n" +
                "  }\n" +
                "  function tryHighlight(){\n" +
                "    if (typeof hljs !== 'undefined' && hljs.highlight) {\n" +
                "      if (pendingText != null) { doHighlightNow(pendingText); pendingText = null; }\n" +
                "    } else {\n" +
                "      setTimeout(tryHighlight, 50);\n" +
                "    }\n" +
                "  }\n" +
                "  function setLanguage(lang) { currentLang = lang; codeEl.className = 'language-' + lang; }\n" +
                "  function setCode(text) {\n" +
                "    pendingText = text;\n" +
                "    if (typeof hljs !== 'undefined' && hljs.highlight) {\n" +
                "      doHighlightNow(text);\n" +
                "    } else {\n" +
                "      tryHighlight();\n" +
                "    }\n" +
                "  }\n" +
                "  function appendCode(text) {\n" +
                "    var cur = (pendingText != null) ? pendingText : codeEl.textContent;\n" +
                "    pendingText = cur + text;\n" +
                "    if (typeof hljs !== 'undefined' && hljs.highlight) {\n" +
                "      doHighlightNow(pendingText);\n" +
                "    } else {\n" +
                "      tryHighlight();\n" +
                "    }\n" +
                "  }\n" +
                "</script>\n" +
                "</body>\n" +
                "</html>";
    }

    /**
     * 语言名称映射到 highlight.js 支持的语言
     */
    private String mapToHighlightJsLanguage(String language) {
        if (language == null || language.isEmpty()) {
            return "plaintext";
        }
        String lang = language.toLowerCase().trim();

        // 常见语言映射
        java.util.Map<String, String> map = new java.util.HashMap<>();
        map.put("js", "javascript");
        map.put("ts", "typescript");
        map.put("py", "python");
        map.put("c++", "cpp");
        map.put("c#", "csharp");
        map.put("cs", "csharp");
        map.put("shell", "bash");
        map.put("sh", "bash");
        map.put("yml", "yaml");
        map.put("htm", "xml");
        map.put("html", "xml");
        map.put("svg", "xml");
        map.put("kt", "kotlin");
        map.put("gradle", "groovy");
        map.put("dockerfile", "dockerfile");
        map.put("docker", "dockerfile");
        map.put("make", "makefile");
        map.put("md", "markdown");
        map.put("rb", "ruby");
        map.put("rs", "rust");
        map.put("golang", "go");
        map.put("objective-c", "objectivec");
        map.put("objc", "objectivec");
        map.put("pl", "perl");
        map.put("php3", "php");
        map.put("php4", "php");
        map.put("php5", "php");
        map.put("r", "r");
        map.put("scala", "scala");
        map.put("swift", "swift");
        map.put("vb", "vbnet");
        map.put("lua", "lua");
        map.put("matlab", "matlab");
        map.put("tex", "latex");
        map.put("latex", "latex");
        map.put("diff", "diff");
        map.put("patch", "diff");
        map.put("ini", "ini");
        map.put("conf", "ini");
        map.put("properties", "properties");
        map.put("toml", "toml");
        map.put("haskell", "haskell");
        map.put("hs", "haskell");
        map.put("elixir", "elixir");
        map.put("ex", "elixir");
        map.put("erlang", "erlang");
        map.put("clojure", "clojure");
        map.put("dart", "dart");
        map.put("julia", "julia");
        map.put("powershell", "powershell");
        map.put("ps", "powershell");
        map.put("cmake", "cmake");
        map.put("nginx", "nginx");
        map.put("apache", "apache");
        map.put("http", "http");
        map.put("graphql", "graphql");
        map.put("protobuf", "protobuf");
        map.put("scss", "scss");
        map.put("sass", "sass");
        map.put("less", "less");
        map.put("stylus", "stylus");
        map.put("vue", "vue");
        map.put("jsx", "javascript");
        map.put("tsx", "typescript");
        map.put("svelte", "svelte");
        map.put("solidity", "solidity");
        map.put("wasm", "wasm");
        map.put("webassembly", "wasm");
        map.put("yaml", "yaml");
        map.put("json", "json");
        map.put("xml", "xml");
        map.put("css", "css");
        map.put("java", "java");
        map.put("kotlin", "kotlin");
        map.put("c", "c");
        map.put("cpp", "cpp");
        map.put("javascript", "javascript");
        map.put("typescript", "typescript");
        map.put("python", "python");
        map.put("ruby", "ruby");
        map.put("go", "go");
        map.put("rust", "rust");
        map.put("php", "php");
        map.put("perl", "perl");
        map.put("bash", "bash");
        map.put("sql", "sql");
        map.put("groovy", "groovy");
        map.put("markdown", "markdown");
        map.put("makefile", "makefile");

        String mapped = map.get(lang);
        if (mapped != null) return mapped;

        // 找不到完整匹配：尝试按 -、+、空白、. 分割，取第一段再匹配（兼容 "java-kotlin" 这类连字）
        String[] parts = lang.split("[-+\\s.]+");
        if (parts.length > 1) {
            for (String part : parts) {
                String m = map.get(part.trim());
                if (m != null) return m;
            }
        }
        // 找不到映射：返回 plaintext（纯文本模式），避免 highlight.js 因未知语言抛异常走纯文本分支
        return "plaintext";
    }

    /** 代码块背景 */
    private GradientDrawable makeCodeBlockBg() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF282C34);  // One Dark 背景
        g.setCornerRadius(dp(6));
        g.setStroke(1, 0xFF3E4451);
        return g;
    }

    private void removeMessageView(View v) {
        if (v != null) messageContainer.removeView(v);
    }

    private void scrollToBottom() {
        // 使用 scrollTo 立即定位，不用 smoothScrollTo 平滑滚动
        // smoothScrollTo 的滚动动画会持续触发重绘，导致所有 WebView 闪烁
        // 特别是新代码框出现时 container 高度变化，滚动位置突变，所有已有代码框都闪一下
        int bottom = messageContainer.getHeight();
        if (bottom > scrollView.getHeight()) {
            scrollView.scrollTo(0, bottom - scrollView.getHeight());
        } else {
            scrollView.scrollTo(0, 0);
        }
    }

    /** v1.153k：等布局完成后再滚动——addView/setText 后立即 getHeight 是旧值，滚动永远差一点 */
    private void scrollToBottomAfterLayout() {
        try {
            if (messageContainer == null) return;
            messageContainer.post(new Runnable() {
                @Override public void run() {
                    if (!userScrolledUp && !programScroll) scrollToBottom();
                }
            });
        } catch (Throwable ignored) {}
    }

    /** v1.138：点击向下箭头时平滑滚动到底部——smoothScrollTo 内部 startScroll 会中断当前惯性滚动，立即接管 */
    private void smoothScrollToBottom() {
        int bottom = messageContainer.getHeight();
        if (bottom > scrollView.getHeight()) {
            scrollView.smoothScrollTo(0, bottom - scrollView.getHeight());
        } else {
            scrollView.smoothScrollTo(0, 0);
        }
    }

    // v1.135：闲聊 / 工作任务切换
    private void toggleChatMode() {
        try {
            if (chatManager == null) return;
            int cur = chatManager.getChatMode();
            int next = (cur == AIChatManager.MODE_CHAT)
                    ? AIChatManager.MODE_WORK : AIChatManager.MODE_CHAT;
            chatManager.setChatMode(next);
            updateChatModeIndicator();
            // v1.139：通知外部持久化当前会话模式（对话列表徽标用）
            if (listener != null) {
                try { listener.onChatModeChanged(next); } catch (Throwable ignored) {}
            }
            renderMergedHistory(); // 重建气泡：显示工作+闲聊合并时间线
            Toast.makeText(getContext(),
                    next == AIChatManager.MODE_CHAT ? "已切换：普通对话（不执行命令）"
                            : "已切换：工作任务（三阶段开发）",
                    Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(getContext(), "切换失败", Toast.LENGTH_SHORT).show();
        }
    }

    public void updateChatModeIndicator() {
        if (tvChatMode == null || chatManager == null) return;
        if (chatManager.getChatMode() == AIChatManager.MODE_CHAT) {
            tvChatMode.setText("闲聊");
            tvChatMode.setTextColor(0xFFFFCC80);
            tvChatMode.setBackground(makeChipBg(0xFFF57C00));
        } else {
            tvChatMode.setText("工作任务");
            tvChatMode.setTextColor(0xFFA5D6A7);
            tvChatMode.setBackground(makeChipBg(0xFF388E3C));
        }
    }

    /** v1.139：打开会话时应用该会话保存的模式（切换会话调用，同步顶栏标识）。
     *  v1.153az：切换会话不再走这里——模式已在 switchConversation 内部按 目标key+目标模式
     *  切好并加载历史；这里只保留给手动切换按钮场景。 */
    public void applySessionMode(int mode) {
        if (chatManager == null) return;
        if (mode != AIChatManager.MODE_WORK && mode != AIChatManager.MODE_CHAT) return;
        boolean changed = (mode != chatManager.getChatMode());
        chatManager.setChatMode(mode);
        updateChatModeIndicator();
        if (changed) {
            try { renderMergedHistory(); } catch (Throwable ignored) {}
        }
    }

    /** v1.153az：切换完成后只同步顶栏模式按钮文字（manager 层模式/历史已在切换任务内切好），
     *  绝不调用 setChatMode（其内部 clear+loadHistory 会清掉刚加载的目标会话历史）。 */
    public void syncChatModeIndicator() {
        try { updateChatModeIndicator(); } catch (Throwable ignored) {}
    }

    private void toggleModelMode() {
        try {
            AppConfig cfg = AppConfig.getInstance(getContext());
            if (cfg.getModelMode() == AppConfig.MODE_LOCAL) {
                cfg.setModelMode(AppConfig.MODE_CLOUD);
            } else {
                LocalModelManager mgr = LocalModelManager.getInstance(getContext());
                if (mgr.hasLoadedModel()) {
                    cfg.setModelMode(AppConfig.MODE_LOCAL);
                } else {
                    Toast.makeText(getContext(), "请先在模型管理页面加载本地模型",
                            Toast.LENGTH_LONG).show();
                    return;
                }
            }
            updateModeIndicator();
            if (listener != null) {
                // 通知主界面刷新状态栏
            }
            Toast.makeText(getContext(), "模型模式已切换，状态栏同步更新",
                    Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(getContext(), "切换失败", Toast.LENGTH_SHORT).show();
        }
    }

    public void updateModeIndicator() {
        if (tvMode == null) return;
        try {
            AppConfig cfg = AppConfig.getInstance(getContext());
            if (cfg.getModelMode() == AppConfig.MODE_LOCAL) {
                tvMode.setText("本地");
                tvMode.setTextColor(Theme.FILE_GRADLE);
                tvMode.setBackground(makeChipBg(0xFF98C379));
            } else {
                tvMode.setText("云端");
                tvMode.setTextColor(Theme.ACCENT_BLUE);
                tvMode.setBackground(makeChipBg(0xFF2196F3));
            }
        } catch (Throwable ignored) {
        }
    }

    // ================= 绘制辅助 =================

    /**
     * 气泡背景
     *
     * @param fill      填充色（带 alpha 的半透明深色）
     * @param stroke    描边色；传 0 表示不描边
     * @param squareAt  哪个角做成方角（直角）；传 -1 则四角全圆
     */
    private GradientDrawable makeBubble(int fill, int stroke, int squareAt) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);

        if (squareAt >= 0) {
            // setCornerRadii 需要 8 个值，顺序固定为：
            //   左上X, 左上Y, 右上X, 右上Y, 右下X, 右下Y, 左下X, 左下Y
            float r = dp(12);
            float[] radii = new float[]{r, r, r, r, r, r, r, r};
            // 每个角占两个连续下标（X 和 Y）
            radii[squareAt * 2] = 0f;
            radii[squareAt * 2 + 1] = 0f;
            g.setCornerRadii(radii);
        } else {
            g.setCornerRadius(dp(12));
        }

        if (stroke != 0) {
            g.setStroke(Math.max(1, dpF(BUBBLE_STROKE_W)), stroke);
        } else {
            g.setStroke(0, Color.TRANSPARENT);
        }
        return g;
    }

    /** 四角全圆的气泡（系统 / 错误提示用） */
    private GradientDrawable makeBubble(int fill, int stroke) {
        return makeBubble(fill, stroke, -1);
    }

    private GradientDrawable makeChipBg(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.TRANSPARENT);
        g.setCornerRadius(dp(10));
        g.setStroke(1, color);
        return g;
    }

    private GradientDrawable makeInputBg() {
        return makeInputBg(0);
    }

    private GradientDrawable makeInputBg(int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Theme.BG_PRIMARY);
        g.setCornerRadius(dp(8));
        g.setStroke(strokeColor == 0 ? 1 : 2, strokeColor == 0 ? Theme.STROKE : strokeColor);
        return g;
    }

    private GradientDrawable makeCircleButtonBg(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setShape(GradientDrawable.OVAL);
        return g;
    }

    // ================= v1.137 发送/停止二合一按钮 =================

    /** 根据生成状态切换按钮：生成中=红色圆+白色圆角方形停止+描边旋转；空闲=蓝色发送 */
    private void updateSendButtonState() {
        if (btnSend == null) return;
        try {
            if (isAiGenerating) {
                btnSend.setImageDrawable(getResources().getDrawable(
                        com.aicodestudio.ide.R.drawable.ic_stop_square));
                btnSend.setBackground(makeCircleButtonBg(0xFFE53935)); // 外圆红色
                btnSend.setPadding(dp(0), dp(0), dp(0), dp(0));    // v1.153p：42dp 方块撑满 44dp 红圆
                startSpinAnimation();
            } else {
                btnSend.setImageDrawable(getResources().getDrawable(
                        com.aicodestudio.ide.R.drawable.ic_send));
                btnSend.setBackground(makeCircleButtonBg(0xFF2196F3)); // 蓝色发送
                btnSend.setPadding(dp(9), dp(9), dp(9), dp(9));
                stopSpinAnimation();
            }
        } catch (Throwable ignored) {}
    }

    /** 生成中：白色开口圆环描边旋转（AI 输出过程中可见） */
    private void startSpinAnimation() {
        if (spinRingView == null) return;
        spinRingView.setVisibility(View.VISIBLE);
        if (spinAnim == null) {
            spinAnim = new android.view.animation.RotateAnimation(
                    0f, 360f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f);
            spinAnim.setDuration(1100);
            spinAnim.setRepeatCount(android.view.animation.Animation.INFINITE);
            spinAnim.setInterpolator(new android.view.animation.LinearInterpolator());
        }
        spinRingView.startAnimation(spinAnim);
    }

    /** 停止旋转并隐藏描边环 */
    private void stopSpinAnimation() {
        if (spinRingView == null) return;
        spinRingView.clearAnimation();
        spinRingView.setVisibility(View.GONE);
    }

    public void setChatPanelListener(ChatPanelListener l) { this.listener = l; }

    /** 供外部（主界面）触发插入代码 */
    public void requestSend(String text) { sendToAI(text); }

    private int dp(int v) {
        // 统一委托 UiKit，实现全项目只保留一份
        return UiKit.dp(getContext(), v);
    }

    /** 浮点 dp（用于描边宽度等需要小数的场景） */
    private int dpF(float v) {
        // 统一委托 UiKit
        return (int) (UiKit.dpF(getContext(), v) + 0.5f);
    }

    // ================= 语音输入（长按即听，松手发送，豆包式遮罩面板）=================

    private boolean hasRecordPermission() {
        Context c = getContext();
        return !(c instanceof android.app.Activity)
                || ((android.app.Activity) c).checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 长按触发：有权限直接开始聆听；无权限请求（授权后提示再次长按） */
    private void startVoiceListeningIfPermitted() {
        VoiceLog.input("startVoiceListeningIfPermitted begin");
        if (hasRecordPermission()) {
            VoiceLog.input("has permission, call startVoiceListening");
            startVoiceListening();
            return;
        }
        VoiceLog.input("no permission, request");
        pendingVoiceStart = new Runnable() {
            @Override public void run() {
                toast("录音权限已授予，请再次长按输入框开始语音");
            }
        };
        Context c = getContext();
        if (c instanceof android.app.Activity) {
            ((android.app.Activity) c).requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
        }
    }

    /** 由宿主 Activity 的 onRequestPermissionsResult 转发 */
    public boolean onVoicePermissionResult(int[] grantResults) {
        if (grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (pendingVoiceStart != null) {
                Runnable r = pendingVoiceStart;
                pendingVoiceStart = null;
                r.run();
            }
            return true;
        }
        toast("需要录音权限才能使用语音输入");
        return false;
    }

    /** 开始聆听 */
    private void startVoiceListening() {
        try {
            if (voiceListening) return;
            VoiceLog.input("startVoiceListening begin");
            VoiceEngine engine = VoiceEngine.get();
            if (!engine.isReady()) {
                int st = engine.getStatus();
                if (st == VoiceEngine.STATUS_LOADING) {
                    toast("语音引擎加载中，请等几秒再试");
                } else {
                    toast("语音引擎加载失败，请重启App或查看日志");
                }
                return;
            }
            voiceListening = true;
            hideIme();
            if (etInput != null) {
                etInput.setFocusable(false);
                etInput.setCursorVisible(false);
            }
            showVoiceOverlay();
            VoiceLog.input("overlay shown, about to start voiceInput");
            if (voiceInput == null) voiceInput = new VoiceInputManager();
            try {
                voiceInput.start(getContext(), new VoiceInputManager.Listener() {
                    @Override public void onPartial(String text) {
                        if (overlayText != null && text != null && text.length() > 0) {
                            overlayText.setText(text);
                        }
                    }
                    @Override public void onFinal(String text) {
                        handleVoiceFinal(text);
                    }
                    @Override public void onError(String msg) {
                        handleVoiceError(msg);
                    }
                });
            } catch (Throwable t) {
                handleVoiceError(t.getMessage());
            }
        } catch (Throwable t) {
            VoiceLog.input("startVoiceListening EXCEPTION: " + android.util.Log.getStackTraceString(t));
            try { hideVoiceOverlay(); } catch (Throwable ignored) {}
            try { restoreInputUi(); } catch (Throwable ignored) {}
            toast("语音启动异常: " + t.getMessage());
        }
    }

    /** 松手结束聆听 */
    private void stopVoiceListening(float upRawY) {
        if (!voiceListening) return;
        VoiceLog.input("stopVoiceListening upY=" + upRawY + " startY=" + voiceStartRawY);
        // 上滑取消判断：往上滑超过 80dp 视为取消
        float dy = voiceStartRawY - upRawY; // 正数=上滑
        boolean cancel = dy > dp(80);
        VoiceLog.input("stopVoiceListening cancel=" + cancel + " dy=" + dy);

        if (voiceInput != null) {
            if (cancel) {
                voiceInput.cancel();
            } else {
                voiceInput.stop();
            }
        }

        if (cancel) {
            // 取消：直接关闭面板，不发送结果
            hideVoiceOverlay();
            restoreInputUi();
            voiceListening = false;
            toast("已取消");
        } else {
            // 正常松手：面板上显示"识别中..."，等 onFinal 回调后再关闭
            if (overlayText != null) overlayText.setText("识别中...");
        }
    }

    /** 识别结束 */
    private void handleVoiceFinal(String text) {
        VoiceLog.input("handleVoiceFinal text=" + (text == null ? "null" : text.length()) + "chars");
        // 无论结果如何，先关闭语音面板、恢复输入框
        hideVoiceOverlay();
        restoreInputUi();
        voiceListening = false;

        if (text == null || text.trim().length() == 0) {
            toast("未识别到语音内容");
            return;
        }
        // 识别成功：自动发送给 AI
        sendToAI(text.trim());
    }

    private void handleVoiceError(String msg) {
        hideVoiceOverlay();
        restoreInputUi();
        toast("语音失败：" + msg);
    }

    /** 恢复输入框 UI */
    private void restoreInputUi() {
        if (etInput != null) {
            etInput.setFocusable(true);
            etInput.setCursorVisible(true);
            etInput.setHint("输入开发需求...");
        }
        if (voiceStatusText != null) {
            // v1.51ci：暂时关闭语音状态显示，避免触发native加载闪退
            voiceStatusText.setVisibility(View.GONE);
        }
    }

    // ================= 豆包式语音遮罩面板 =================

    private void showVoiceOverlay() {
        final Context c = getContext();
        if (c == null || inputArea == null) return;
        if (voiceOverlay == null) {
            voiceOverlay = new FrameLayout(c);
            voiceOverlay.setBackgroundColor(0xF014151A); // 无边框，贴合输入区底色，覆盖整个输入区
            voiceOverlay.setClipChildren(false); // 波纹可略溢出面板边缘

            // 波纹层：96dp 正圆居中（约等于输入框/面板高度），涟漪从中心扩散正好填满输入框区域
            ripple1 = makeRipple(c);
            ripple2 = makeRipple(c);
            FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(dp(96), dp(96), Gravity.CENTER);
            voiceOverlay.addView(ripple1, rp);
            voiceOverlay.addView(ripple2, rp);

            // 文字层（透明，浮在波纹上方）：实时字 + 提示，竖排居中（无麦克风白点）
            LinearLayout center = new LinearLayout(c);
            center.setOrientation(LinearLayout.VERTICAL);
            center.setGravity(Gravity.CENTER);
            center.setPadding(dp(16), dp(8), dp(16), dp(8));

            overlayText = new TextView(c);
            overlayText.setTextSize(14);
            overlayText.setTextColor(Color.WHITE);
            overlayText.setGravity(Gravity.CENTER);
            overlayText.setMaxLines(2);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            center.addView(overlayText, tl);

            center.addView(gapV(dp(2)));

            TextView hint = new TextView(c);
            hint.setText("松开发送 · 上滑取消");
            hint.setTextSize(11);
            hint.setTextColor(Color.parseColor("#B0FFFFFF"));
            hint.setGravity(Gravity.CENTER);
            center.addView(hint);

            FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER);
            voiceOverlay.addView(center, cl);
        }
        if (voiceOverlay.getParent() == null) {
            // 高度 WRAP_CONTENT：输入区临时展开为语音面板（微信/豆包按住说话式），inputBar 仍留在底部被覆盖
            inputArea.addView(voiceOverlay, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM));
        }
        if (overlayText != null) overlayText.setText("正在聆听...");
        // 渐变淡入（短，150ms）
        voiceOverlay.setAlpha(0f);
        voiceOverlay.animate().alpha(1f).setDuration(150).start();
        startRippleAnim();
    }

    private View makeRipple(Context c) {
        View v = new View(c);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0x2200BFFF);
        g.setStroke(dp(2), Color.parseColor("#00BFFF"));
        v.setBackgroundDrawable(g);
        return v;
    }

    private void startRippleAnim() {
        if (ripple1 == null || ripple2 == null) return;
        stopRippleAnim();
        // 正圆波纹：scale 0.5→1.1（48→105dp）从中心扩散到输入框区域边界（alpha 同步渐隐）
        ripple1.setScaleX(0.5f); ripple1.setScaleY(0.5f); ripple1.setAlpha(0.8f);
        ripple2.setScaleX(0.5f); ripple2.setScaleY(0.5f); ripple2.setAlpha(0.8f);
        rippleAnim1a = android.animation.ObjectAnimator.ofFloat(ripple1, "scaleX", 0.5f, 1.1f);
        rippleAnim1b = android.animation.ObjectAnimator.ofFloat(ripple1, "scaleY", 0.5f, 1.1f);
        android.animation.ObjectAnimator a1 = android.animation.ObjectAnimator.ofFloat(ripple1, "alpha", 0.8f, 0f);
        rippleAnimA1 = a1;
        rippleAnim1a.setDuration(1200);
        rippleAnim1b.setDuration(1200);
        a1.setDuration(1200);
        rippleAnim1a.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        rippleAnim1b.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        a1.setRepeatCount(android.animation.ValueAnimator.INFINITE);

        rippleAnim2a = android.animation.ObjectAnimator.ofFloat(ripple2, "scaleX", 0.5f, 1.1f);
        rippleAnim2b = android.animation.ObjectAnimator.ofFloat(ripple2, "scaleY", 0.5f, 1.1f);
        android.animation.ObjectAnimator a2 = android.animation.ObjectAnimator.ofFloat(ripple2, "alpha", 0.8f, 0f);
        rippleAnimA2 = a2;
        rippleAnim2a.setDuration(1200);
        rippleAnim2b.setDuration(1200);
        a2.setDuration(1200);
        rippleAnim2a.setStartDelay(600);
        rippleAnim2b.setStartDelay(600);
        a2.setStartDelay(600);
        rippleAnim2a.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        rippleAnim2b.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        a2.setRepeatCount(android.animation.ValueAnimator.INFINITE);

        rippleAnim1a.start(); rippleAnim1b.start(); a1.start();
        rippleAnim2a.start(); rippleAnim2b.start(); a2.start();
    }

    private void stopRippleAnim() {
        android.animation.Animator[] arr = {rippleAnim1a, rippleAnim1b, rippleAnim2a, rippleAnim2b,
                rippleAnimA1, rippleAnimA2};
        for (android.animation.Animator an : arr) {
            if (an != null) { try { an.cancel(); } catch (Throwable ignored) {} }
        }
    }

    private void hideVoiceOverlay() {
        stopRippleAnim();
        if (voiceOverlay != null && voiceOverlay.getParent() != null) {
            final android.view.ViewParent parent = voiceOverlay.getParent();
            voiceOverlay.animate().alpha(0f).setDuration(120)
                    .withEndAction(new Runnable() {
                        @Override public void run() {
                            try { ((android.view.ViewGroup) parent).removeView(voiceOverlay); }
                            catch (Throwable ignored) {}
                            voiceOverlay.setAlpha(1f);
                        }
                    }).start();
        }
    }

    /** 收起输入法（聆听时避免遮挡） */
    private void hideIme() {
        try {
            Context c = getContext();
            if (!(c instanceof android.app.Activity)) return;
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            c.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(
                        ((android.app.Activity) c).getWindow().getDecorView().getWindowToken(), 0);
            }
        } catch (Throwable ignored) {}
    }

    private void toast(String msg) {
        Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
    }

    private View gapV(int h) {
        View v = new View(getContext());
        v.setLayoutParams(new ViewGroup.LayoutParams(1, h));
        return v;
    }

    private View gapH(int w) {
        View v = new View(getContext());
        v.setLayoutParams(new ViewGroup.LayoutParams(w, 1));
        return v;
    }
}
