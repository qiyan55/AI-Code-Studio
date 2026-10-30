package com.aicodestudio.ide.ai;

import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.aicodestudio.ide.ai.chat.ChatPanel;
import com.aicodestudio.ide.ai.core.AppConfig;
import com.aicodestudio.ide.ai.core.WorkspaceState;

import java.io.File;

/**
 * 工作区 ①：AI 对话界面
 *
 * 独立全屏的对话工作区，不再和编辑器、日志挤在一个屏幕里。
 * 顶部：返回主页 / 标题+项目名 / 项目列表（侧边栏） / 设置
 * 中部：ChatPanel 全屏撑满
 *
 * 每个项目绑定独立的对话历史：
 * - 顶部「项目列表」按钮展开右侧侧边栏，显示所有最近项目
 * - 点击项目 → 保存当前项目历史 → 切换项目 → 加载该项目的历史聊天
 * - 聊天历史按项目路径分开保存在应用私有目录 chat_history/
 */
public class ChatActivity extends BaseWorkspaceActivity {

    private ChatPanel chatPanel;

    // 顶栏
    private TextView tvSubTitle;

    // 侧边栏（覆盖式，加到 DecorView 上）
    private boolean drawerSetup = false;
    private boolean drawerOpen = false;
    private View drawerScrim;        // 半透明遮罩
    private FrameLayout drawerPanel; // 右侧面板（v1.72：改成 FrameLayout 放 FAB）
    private LinearLayout drawerList;  // 项目列表容器
    private static final int DRAWER_WIDTH_DP = 300;

    private boolean forceNewConversation = false;  // v1.51ah：欢迎页点对话卡片 = 新建空白对话
    private String newProjectPath = null;  // v1.51bi：欢迎页新建项目后传进来的路径
    private boolean needSyncExternal = false;  // v1.51bn：只有从其他页面回来时才同步项目

    private String currentSessionId = null;  // v1.67：当前会话 ID

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        // v1.67：接收 session_id 参数
        currentSessionId = intent.getStringExtra("session_id");
        forceNewConversation = intent.getBooleanExtra("new_conversation", false);
        newProjectPath = intent.getStringExtra("new_project");
        setIntent(intent);
    }

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // v1.67：接收 session_id 参数
        currentSessionId = getIntent().getStringExtra("session_id");
        forceNewConversation = getIntent().getBooleanExtra("new_conversation", false);
        newProjectPath = getIntent().getStringExtra("new_project");
        // 消费 AI 在别处生成、等待插入的代码
        flushPendingInsert();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (chatPanel != null) {
            chatPanel.updateModeIndicator();
            updateTabEnabled();  // v1.51bj：根据是否绑定项目，启用/禁用编辑器和运行 tab
            needSyncExternal = true;  // v1.51bn：onCreate/onNewIntent 时需要同步
            if (newProjectPath != null && !newProjectPath.isEmpty()) {
                // v1.51bi：欢迎页新建项目 → 调 bindNewProject() 新建项目对话
                chatPanel.bindNewProject(newProjectPath);
                updateTabEnabled();  // v1.51bq：绑定项目后启用编辑器
                newProjectPath = null;  // 只生效一次
            } else if (currentSessionId != null && !currentSessionId.isEmpty()) {
                // v1.88：带了 session_id → 真正切换到对应会话（加载历史）
                switchToSession(currentSessionId);
                updateTabEnabled();
            } else if (forceNewConversation) {
                // v1.51aj：先清空 WorkspaceState 里的项目路径，再创建新对话
                com.aicodestudio.ide.ai.core.WorkspaceState.get().setProjectPath("");
                // v1.90：创建空白会话并切换，确保 currentConvKey = sessionId
                com.aicodestudio.ide.ai.core.SessionManager sm =
                        com.aicodestudio.ide.ai.core.SessionManager.get(this);
                com.aicodestudio.ide.ai.core.ProjectSession s = sm.createBlankSession();
                currentSessionId = s.sessionId;
                switchToSession(currentSessionId);
                forceNewConversation = false;
            } else {
                // v1.90：兜底——没有 session_id 也没有 newProjectPath，创建空白会话
                com.aicodestudio.ide.ai.core.WorkspaceState.get().setProjectPath("");
                com.aicodestudio.ide.ai.core.SessionManager sm =
                        com.aicodestudio.ide.ai.core.SessionManager.get(this);
                com.aicodestudio.ide.ai.core.ProjectSession s = sm.createBlankSession();
                currentSessionId = s.sessionId;
                switchToSession(currentSessionId);
            }
        }
        flushPendingInsert();
    }

    @Override
    protected void onDestroy() {
        // 退出时保存当前会话的历史聊天（v1.51n：按当前会话 key 保存，含 AI 新建项目自动绑定）
        if (chatPanel != null) {
            chatPanel.saveCurrentHistory();
            // v1.153ab：切换进行中跳过——切换线程会保存旧 KV，避免重复导出 13MB
            if (!chatPanel.isSwitching()) {
                // v1.150：返回主界面 → 先落盘当前会话 KV（再进入该对话时 loadSessionKv 恢复增量续跑）
                chatPanel.saveSessionKv();
            }
            // v1.51o：回到主界面 → 显式清空模型 KV 内存，防残留历史影响下次对话
            chatPanel.clearKvCache();
        }
        super.onDestroy();
    }

    // v1.150：切后台（home键/被其他App覆盖）也保存当前会话 KV，防进程被杀丢失最新增量
    // v1.153ab：切换会话进行中跳过——切换线程自己会保存旧 KV，onPause 再存会重复导出 13MB 卡顿
    @Override
    protected void onPause() {
        super.onPause();
        if (chatPanel != null && !chatPanel.isSwitching()) {
            try { chatPanel.saveSessionKv(); } catch (Throwable ignored) {}
        }
    }

    @Override
    protected int currentTab() { return TAB_CHAT; }

    @Override
    protected void updateTabEnabled() {
        boolean hasProject = false;
        if (chatPanel != null) {
            String pp = chatPanel.getCurrentProjectPath();
            hasProject = pp != null && !pp.isEmpty();
        }
        if (navTabEditor != null) {
            navTabEditor.setAlpha(hasProject ? 1f : 0.3f);
            navTabEditor.setEnabled(hasProject);
        }
        if (navTabRun != null) {
            navTabRun.setAlpha(hasProject ? 1f : 0.3f);
            navTabRun.setEnabled(hasProject);
        }
    }

    @Override
    protected View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(BG_SECONDARY);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50)));

        // 左侧返回主页
        ImageView left = new ImageView(this);
        Drawable d = icon("ic_home", ICON_BAR);
        if (d != null) left.setImageDrawable(d);
        left.setScaleType(ImageView.ScaleType.CENTER);
        left.setLayoutParams(new LinearLayout.LayoutParams(dp(46), dp(50)));
        left.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                goHome();
                overridePendingTransition(0, 0);
            }
        });
        bar.addView(left);

        // 标题 + 项目名
        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setGravity(Gravity.CENTER_VERTICAL);
        titleBox.setLayoutParams(new LinearLayout.LayoutParams(0, dp(50), 1f));

        TextView tvTitle = new TextView(this);
        tvTitle.setText("AI 对话");
        tvTitle.setTextSize(15);
        tvTitle.setTextColor(TEXT_PRIMARY);
        tvTitle.setSingleLine(true);
        tvTitle.setGravity(Gravity.CENTER_VERTICAL);
        titleBox.addView(tvTitle);

        tvSubTitle = new TextView(this);
        tvSubTitle.setText(state().getProjectName());
        tvSubTitle.setTextSize(10);
        tvSubTitle.setTextColor(TEXT_SECOND);
        tvSubTitle.setSingleLine(true);
        tvSubTitle.setGravity(Gravity.CENTER_VERTICAL);
        titleBox.addView(tvSubTitle);

        bar.addView(titleBox);

        // 右侧动作：列表（对话/项目双列表入口，点击展开侧边栏；v1.51n 图标由文件夹改为列表样式）
        bar.addView(makeActionIcon("ic_list", TAB_BLUE, new View.OnClickListener() {
            @Override public void onClick(View v) { openDrawer(); }
        }));
        bar.addView(makeActionIcon("ic_settings", 0, new View.OnClickListener() {
            @Override public void onClick(View v) {
                // 与欢迎页共用同一个 AI 设置弹窗（云端 / 本地两层入口）
                new com.aicodestudio.ide.ai.ui.AISettingsDialog(
                        ChatActivity.this,
                        new com.aicodestudio.ide.ai.ui
                                .AISettingsDialog.OnChangedListener() {
                            @Override public void onChanged() {
                                if (chatPanel != null)
                                    chatPanel.updateModeIndicator();
                            }
                        }).show();
            }
        }));

        return bar;
    }

    private View makeActionIcon(String iconName, int tint, View.OnClickListener action) {
        ImageView iv = new ImageView(this);
        Drawable d = icon(iconName, tint != 0 ? tint : ICON_BAR);
        if (d != null) iv.setImageDrawable(d);
        iv.setScaleType(ImageView.ScaleType.CENTER);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(50)));
        if (action != null) iv.setOnClickListener(action);
        return iv;
    }

    @Override
    protected void buildContent(LinearLayout container) {
        chatPanel = new ChatPanel(this);
        chatPanel.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));
        // v1.51cg：绑定状态变化自动更新按钮
        chatPanel.setBindingListener(new ChatPanel.BindingListener() {
            @Override public void onProjectBound(String projectPath) {
                runOnUiThread(new Runnable() { public void run() { updateTabEnabled(); } });
            }
            @Override public void onConversationReset() {
                runOnUiThread(new Runnable() { public void run() { updateTabEnabled(); } });
            }
        });
        chatPanel.setChatPanelListener(new ChatPanel.ChatPanelListener() {
            @Override
            public void onInsertCodeToEditor(String code) {
                if (code == null || code.length() == 0) return;
                // 存入共享状态，切到编辑器界面时自动插入
                state().setPendingInsert(code);
                toast("代码已保存，切到编辑器界面即可插入");
                switchToTab(TAB_EDITOR);
            }

            @Override
            public String getEditorSelection() {
                return state().getSelectedCode();
            }

            @Override
            public void onConversationReset() {
                // v1.51al：新建对话后清空项目名显示
                if (tvSubTitle != null) tvSubTitle.setText("未打开项目");
            }

            @Override
            public void onProjectBound(String projectPath) {
                // v1.51aq：绑定项目后更新项目名显示
                if (tvSubTitle != null) {
                    if (projectPath == null || projectPath.isEmpty()) {
                        tvSubTitle.setText("未打开项目");
                    } else {
                        tvSubTitle.setText(new java.io.File(projectPath).getName());
                    }
                }
            }

            @Override
            public void onFirstUserMessage(String text) {
                // v1.89：用户首条消息 → 一次性更新会话配置（isBlank=false / title / preview / messageCount）
                if (text == null || text.trim().length() == 0) return;
                if (currentSessionId == null) return;
                com.aicodestudio.ide.ai.core.SessionManager sm =
                        com.aicodestudio.ide.ai.core.SessionManager.get(ChatActivity.this);                com.aicodestudio.ide.ai.core.ProjectSession s = sm.getSession(currentSessionId);
                if (s == null) return;
                // 绑定项目的会话标题=项目名，不覆盖
                if (s.isBoundProject()) return;

                // 统计当前消息总数（含本条）
                int total = chatPanel != null ? chatPanel.getMessageCount() : 1;
                sm.markSessionAsActive(currentSessionId, text, total);

                final String newTitle = s.title;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (tvSubTitle != null && newTitle != null) tvSubTitle.setText(newTitle);
                        rebuildChatList();
                    }
                });
            }

            @Override
            public void onChatModeChanged(int mode) {
                // v1.139：模式切换 → 持久化到当前会话，对话列表徽标下次打开生效
                if (currentSessionId == null) return;
                com.aicodestudio.ide.ai.core.SessionManager sm =
                        com.aicodestudio.ide.ai.core.SessionManager.get(ChatActivity.this);
                sm.updateChatMode(currentSessionId, mode);
            }
        });
        container.addView(chatPanel);

        // v1.90：不在 onCreate 末尾调 newConversation()——
        // 那会把 currentConvKey 设成一个临时时间戳，和 SQLite 里的 sessionId 不对应。
        // 初始化统一由 onResume 处理：带 session_id 就 switchToSession，否则新建空白会话。
    }

    /** 把编辑器里选中的代码发送给 AI（侧边栏项目切换后不再占用顶栏，保留此能力给外部调用） */
    public void sendSelectionToAI() {
        String sel = state().getSelectedCode();
        if (sel.length() == 0) {
            toast("请先在编辑器界面选中代码");
            return;
        }
        if (chatPanel != null) {
            chatPanel.requestSend("请帮我检查并优化这段代码：\n\n" + sel);
        }
    }

    /** 消费待插入代码（从其他界面带过来的） */
    private void flushPendingInsert() {
        if (state().hasPendingInsert()) {
            String code = state().takePendingInsert();
            if (chatPanel != null) {
                String fileName = state().getCurrentFileName();
                chatPanel.requestSend(
                        "以下是来自 " + fileName + " 的待处理代码：\n\n" + code);
            }
        }
    }

    // ================= 会话/项目双列表侧边栏（v1.51n） =================

    private TextView drawerTabChat;
    private TextView drawerTabProject;
    private TextView drawerTitle;  // v1.51at：侧边栏标题
    private int currentDrawerTab = 0; // 0=对话 1=项目

    private void ensureDrawer() {
        if (drawerSetup) return;
        drawerSetup = true;

        FrameLayout decor = (FrameLayout) getWindow().getDecorView();

        // 半透明遮罩
        drawerScrim = new View(this);
        drawerScrim.setBackgroundColor(0x66000000);
        drawerScrim.setAlpha(0f);
        drawerScrim.setVisibility(View.GONE);
        drawerScrim.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        drawerScrim.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeDrawer(); }
        });
        decor.addView(drawerScrim);

        // 右侧面板（初始在屏幕外）
        drawerPanel = new FrameLayout(this);
        drawerPanel.setBackgroundColor(0xFF0D1117);  // v1.51az：和设置页一致的深色背景
        drawerPanel.setVisibility(View.GONE);
        drawerPanel.setTranslationX(dp(DRAWER_WIDTH_DP));
        // v1.51ay：侧边栏占满屏幕高度（除了状态栏），从状态栏下方开始
        int statusBarHeight = 0;
        int resId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resId > 0) statusBarHeight = getResources().getDimensionPixelSize(resId);
        FrameLayout.LayoutParams drawerLp = new FrameLayout.LayoutParams(
                dp(DRAWER_WIDTH_DP),
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.RIGHT | Gravity.TOP);
        drawerLp.topMargin = statusBarHeight;
        drawerPanel.setLayoutParams(drawerLp);
        decor.addView(drawerPanel);

        buildDrawerContent();
    }

    private void buildDrawerContent() {
        // v1.72：把所有内容包在一个 LinearLayout 里，然后 FAB 浮在上面
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // 头部
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(BG_PRIMARY);
        header.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));
        header.setPadding(dp(14), 0, dp(4), 0);

        drawerTitle = new TextView(this);
        drawerTitle.setText("会话列表");
        drawerTitle.setTextSize(15);
        drawerTitle.setTextColor(TEXT_PRIMARY);
        drawerTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        drawerTitle.setGravity(Gravity.CENTER_VERTICAL);
        drawerTitle.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        header.addView(drawerTitle);

        ImageView close = new ImageView(this);
        Drawable cd = icon("ic_close", ICON_BAR);
        if (cd != null) close.setImageDrawable(cd);
        close.setScaleType(ImageView.ScaleType.CENTER);
        close.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(52)));
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeDrawer(); }
        });
        header.addView(close);
        content.addView(header);

        // tab 切换：对话 | 项目
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setBackgroundColor(0xFF1C2333);
        tabRow.setPadding(dp(8), dp(6), dp(8), dp(6));
        tabRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));

        drawerTabChat = makeTabButton("对话", 0);
        drawerTabProject = makeTabButton("项目", 1);
        tabRow.addView(drawerTabChat);
        tabRow.addView(drawerTabProject);
        content.addView(tabRow);

        // 列表（可滚动）
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        drawerList = new LinearLayout(this);
        drawerList.setOrientation(LinearLayout.VERTICAL);
        drawerList.setPadding(0, dp(4), 0, dp(8));
        sv.addView(drawerList);
        content.addView(sv);

        // 底部操作区
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setBackgroundColor(BG_PRIMARY);
        footer.setPadding(dp(4), dp(4), dp(4), dp(8));
        footer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        footer.addView(makeFooterItem("ic_delete", "清空当前对话", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (chatPanel != null) chatPanel.clearChat();
                closeDrawer();
            }
        }));
        content.addView(footer);

        drawerPanel.addView(content);

        updateTabHighlight();
    }

    private TextView makeTabButton(String label, final int tab) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(13);
        tv.setGravity(Gravity.CENTER);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, dp(32), 1f));
        tv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                currentDrawerTab = tab;
                updateTabHighlight();
                rebuildList();
            }
        });
        return tv;
    }

    private void updateTabHighlight() {
        if (drawerTabChat == null || drawerTabProject == null) return;
        // v1.51bb：选中 tab = 蓝色背景 + 白色文字
        drawerTabChat.setTextColor(currentDrawerTab == 0 ? 0xFFFFFFFF : 0xFF8B949E);
        drawerTabProject.setTextColor(currentDrawerTab == 1 ? 0xFFFFFFFF : 0xFF8B949E);
        drawerTabChat.setBackground(currentDrawerTab == 0
                ? rounded(0xFF2196F3, dp(8)) : rounded(0x00000000, dp(8)));
        drawerTabProject.setBackground(currentDrawerTab == 1
                ? rounded(0xFF2196F3, dp(8)) : rounded(0x00000000, dp(8)));
    }

    private View makeFooterItem(String iconName, String label, View.OnClickListener action) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(12), dp(10), dp(12), dp(10));
        item.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        ImageView iv = new ImageView(this);
        Drawable d = icon(iconName, TEXT_SECOND);
        if (d != null) iv.setImageDrawable(d);
        iv.setScaleType(ImageView.ScaleType.CENTER);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(22), dp(22)));
        item.addView(iv);

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(13);
        tv.setTextColor(TEXT_SECOND);
        tv.setPadding(dp(10), 0, 0, 0);
        item.addView(tv);

        item.setOnClickListener(action);
        return item;
    }

    /** 重建侧边栏列表（按当前 tab：对话列表 / 项目列表） */
    private void rebuildList() {
        if (drawerList == null) return;
        // v1.51at：标题跟着 tab 切换
        if (drawerTitle != null) {
            drawerTitle.setText(currentDrawerTab == 0 ? "会话列表" : "项目列表");
        }
        drawerList.removeAllViews();
        if (currentDrawerTab == 0) rebuildChatList();
        else rebuildProjectList();
        updateFab();
    }

    /** v1.70：右下角悬浮加号按钮（FAB） */
    private TextView fabButton;
    private void ensureFab() {
        if (fabButton != null) return;
        fabButton = new TextView(this);
        int size = dp(56);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        lp.setMargins(0, 0, dp(16), dp(80));  // v1.73：往上挪一点，避开底部栏
        fabButton.setLayoutParams(lp);
        fabButton.setBackground(rounded(0xFF2196F3, dp(28)));
        fabButton.setText("＋");
        fabButton.setTextSize(24);
        fabButton.setTextColor(0xFFFFFFFF);
        fabButton.setGravity(Gravity.CENTER);

        fabButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (currentDrawerTab == 0) {
                    // v1.79：新建对话 → 先找最近的空白对话，空的就复用，有历史才新建
                    // v1.153at：先关抽屉——否则遮罩切换流程在抽屉后面跑，用户看不到
                    // "已跳到新对话"，像没反应
                    closeDrawer();
                    com.aicodestudio.ide.ai.core.SessionManager sm =
                            com.aicodestudio.ide.ai.core.SessionManager.get(ChatActivity.this);
                    com.aicodestudio.ide.ai.core.ProjectSession latestBlank = sm.getLatestBlankSession();
                    // v1.153al：新建路径日志——记录复用/新建哪个会话，便于排查"新对话搬旧内容"
                    com.aicodestudio.ide.ai.util.DebugLogger.log("AGENT", "新建对话: latestBlank="
                            + (latestBlank == null ? "null" : latestBlank.sessionId)
                            + " isBlank=" + (latestBlank == null ? "-" : latestBlank.isBlank)
                            + " msgCount=" + (latestBlank == null ? "-" : latestBlank.messageCount));
                    if (latestBlank != null && sm.isBlankSessionEmpty(latestBlank.sessionId)) {
                        // 最近的空白对话是空的，复用它
                        switchToSession(latestBlank.sessionId);
                        toast("已在新对话中");
                    } else {
                        // 有历史了，新建空白对话
                        com.aicodestudio.ide.ai.core.ProjectSession s = sm.createBlankSession();
                        switchToSession(s.sessionId);
                        toast("已创建新对话");
                    }
                    rebuildList();
                } else {
                    // v1.75：新建项目 → 直接在当前页弹 Dialog，不跳回欢迎页
                    closeDrawer();
                    showCreateProjectDialog();
                }
            }
        });
        // v1.72：加到侧边栏 drawerPanel 里，定位到右下角
        drawerPanel.addView(fabButton);
        fabButton.setVisibility(View.GONE);
    }

    /** 根据侧边栏是否打开显示/隐藏 FAB */
    private void updateFab() {
        ensureFab();
        if (drawerOpen && fabButton != null) {
            fabButton.setVisibility(View.VISIBLE);
            // 对话 tab 绿色，项目 tab 蓝色
            int color = currentDrawerTab == 0 ? 0xFF4CAF50 : 0xFF2196F3;
            fabButton.setBackground(rounded(color, dp(28)));
        } else if (fabButton != null) {
            fabButton.setVisibility(View.GONE);
        }
    }

    /** v1.75：创建项目 Dialog（简化版，直接在对话页弹） */
    /** v1.77：创建项目 Dialog（调用公共类，和欢迎页一模一样） */
    private void showCreateProjectDialog() {
        com.aicodestudio.ide.ai.core.ProjectDialogHelper.show(
                this,
                new com.aicodestudio.ide.ai.core.ProjectDialogHelper.DpConverter() {
                    @Override public int convert(int dpVal) { return dp(dpVal); }
                    @Override public android.content.Context getContext() { return ChatActivity.this; }
                },
                new com.aicodestudio.ide.ai.core.ProjectDialogHelper.OnProjectCreatedListener() {
                    @Override public void onProjectCreated(String projectPath, String projectName) {
                        // 创建完后切换到这个新会话
                        com.aicodestudio.ide.ai.core.SessionManager sm =
                                com.aicodestudio.ide.ai.core.SessionManager.get(ChatActivity.this);
                        com.aicodestudio.ide.ai.core.ProjectSession s =
                                sm.getSessionByProjectPath(projectPath);
                        if (s != null) {
                            switchToSession(s.sessionId);
                        }
                        rebuildList();
                        toast("项目已创建");
                    }
                }
        );
    }

    /** v1.67：切换到指定会话 */
    private void switchToSession(String sessionId) {
        com.aicodestudio.ide.ai.core.SessionManager sm =
                com.aicodestudio.ide.ai.core.SessionManager.get(this);
        com.aicodestudio.ide.ai.core.ProjectSession s = sm.getSession(sessionId);
        if (s == null) return;

        // 更新当前会话 ID
        currentSessionId = sessionId;

        // 如果绑定了项目，设置到 WorkspaceState
        if (s.isBoundProject()) {
            com.aicodestudio.ide.ai.core.WorkspaceState.get().setProjectPath(s.projectPath);
            com.aicodestudio.ide.ai.core.WorkspaceState.get().setProjectType(
                    com.aicodestudio.ide.ai.core.WorkspaceState.detectProjectType(s.projectPath));
        } else {
            com.aicodestudio.ide.ai.core.WorkspaceState.get().setProjectPath("");
        }

        // v1.140：不再 touchSession——切换会话时更新 lastOpenedAt 并重排，
        // 会导致"选择后面的对话/项目跳到最前面"。切换不改变列表位置。

        // v1.88：用 sessionId 作为 ChatPanel 的 convKey，openConversation 会先 saveHistory 当前对话，
        // 再 loadHistory 加载目标 session 的历史，真正实现会话切换
        if (chatPanel != null) {
            // v1.153az：把该会话绑定的模式一起传入——切换任务内部在加载历史前就切好
            // modeSuffix，按 目标key+目标模式 读它自己的历史/KV。
            // onDone 只同步顶栏按钮文字，不再调 applySessionMode（其内部 setChatMode
            // 会 clear+loadHistory，把刚加载好的目标会话内存历史清掉——"切过去历史空白"根因）。
            chatPanel.openConversation(sessionId, s.projectPath, s.chatMode, new Runnable() {
                @Override public void run() {
                    try {
                        chatPanel.syncChatModeIndicator();
                    } catch (Throwable ignored) {}
                    // v1.81：更新顶部标题显示项目名/对话名（切换完成后同步）
                    if (tvSubTitle != null) {
                        tvSubTitle.setText(s.title != null && !s.title.isEmpty() ? s.title : "未打开项目");
                    }
                    // 【修复】切换会话后更新底部 Tab 的启用状态
                    updateTabEnabled();
                }
            });
        }

        // v1.84：不调用 rebuildList()，避免列表重新排序跳来跳去
        // rebuildList();
    }

    /** 对话列表：纯对话（未绑定项目）历史，长按删除 */
    private void rebuildChatList() {
        // v1.67：用新的 SessionManager 数据源
        com.aicodestudio.ide.ai.core.SessionManager sm =
                com.aicodestudio.ide.ai.core.SessionManager.get(this);
        java.util.List<com.aicodestudio.ide.ai.core.ProjectSession> items = sm.getBlankChatSessions();

        String curId = currentSessionId != null ? currentSessionId : "";
        boolean any = false;
        for (final com.aicodestudio.ide.ai.core.ProjectSession s : items) {
            boolean isCurrent = s.sessionId.equals(curId);
            drawerList.addView(makeSessionItem(s, isCurrent));
            any = true;
        }
        if (!any) {
            drawerList.addView(emptyHint("还没有历史对话\n在对话页输入需求即可开始新的对话"));
        }
    }

    /** v1.67：单个会话项（对话/项目通用），点击切换，长按删除 */
    private View makeSessionItem(final com.aicodestudio.ide.ai.core.ProjectSession s, boolean current) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        int cardColor = current ? 0xFF1E3A5F : 0xFF1C2333;
        row.setBackground(rounded(cardColor, dp(8)));
        lp.setMargins(dp(8), dp(4), dp(8), dp(4));
        row.setLayoutParams(lp);

        // v1.72：圆形图标 + 复用主界面的图标
        FrameLayout iconWrap = new FrameLayout(this);
        int iconBgColor = s.isBoundProject() ? 0xFF2196F3 : 0xFF4CAF50;
        iconWrap.setBackground(rounded(iconBgColor, dp(18)));
        iconWrap.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));

        // 里面的小图标：复用主界面的 ic_chat / ic_folder
        ImageView innerIcon = new ImageView(this);
        String iconName = s.isBoundProject() ? "ic_folder" : "ic_chat";
        Drawable ic = icon(iconName, 0xFFFFFFFF);
        if (ic != null) innerIcon.setImageDrawable(ic);
        innerIcon.setScaleType(ImageView.ScaleType.CENTER);
        FrameLayout.LayoutParams innerLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        innerIcon.setLayoutParams(innerLp);
        iconWrap.addView(innerIcon);
        row.addView(iconWrap);

        // 文字区：两行，第一行标题，第二行日期
        LinearLayout textBox = new LinearLayout(this);
        textBox.setOrientation(LinearLayout.VERTICAL);
        textBox.setPadding(dp(12), 0, 0, 0);
        textBox.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(this);
        name.setText(s.title);
        name.setTextSize(14);
        name.setTextColor(0xFFE6EDF3);
        name.setSingleLine(true);

        // v1.139：模式徽标（闲聊/工作任务）——和顶栏切换按钮同款配色，绑定会话模式
        TextView modeBadge = new TextView(this);
        boolean isChatMode = (s.chatMode == com.aicodestudio.ide.ai.chat.AIChatManager.MODE_CHAT);
        modeBadge.setText(isChatMode ? "闲聊" : "工作任务");
        modeBadge.setTextSize(10);
        modeBadge.setSingleLine(true);
        modeBadge.setTextColor(isChatMode ? 0xFFFFCC80 : 0xFFA5D6A7);
        modeBadge.setBackground(rounded(isChatMode ? 0xFFF57C00 : 0xFF388E3C, dp(8)));
        modeBadge.setPadding(dp(6), dp(2), dp(6), dp(2));
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        badgeLp.setMargins(dp(6), 0, 0, 0);
        modeBadge.setLayoutParams(badgeLp);

        // 标题行：标题 + 模式徽标
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(name);
        titleRow.addView(modeBadge);
        textBox.addView(titleRow);

        // v1.86：第二行显示日期
        TextView date = new TextView(this);
        date.setText(formatDate(s.createdAt));
        date.setTextSize(11);
        date.setTextColor(0xFF8B949E);
        date.setSingleLine(true);
        date.setPadding(0, dp(2), 0, 0);
        textBox.addView(date);

        row.addView(textBox);

        // 点击切换会话
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                switchToSession(s.sessionId);
                closeDrawer();  // v1.82：切换后自动关闭侧边栏，看得到聊天内容
                // v1.84：不调用 rebuildList()，避免列表重新排序跳来跳去
                // 列表位置保持不变，只等下次打开侧边栏时再刷新
            }
        });

        // 长按删除
        row.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                confirmDeleteSession(s);
                return true;
            }
        });
        return row;
    }

    /** v1.86：格式化时间戳为日期字符串 */
    private String formatDate(long timestamp) {
        if (timestamp <= 0) return "";
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault());
        return sdf.format(new java.util.Date(timestamp));
    }

    /** v1.67：长按删除会话（统一对话/项目） */
    private void confirmDeleteSession(final com.aicodestudio.ide.ai.core.ProjectSession s) {
        String msg = s.isBoundProject()
                ? "删除项目「" + s.title + "」？聊天历史会一起删除。"
                : "删除对话「" + s.title + "」？";
        new com.aicodestudio.ide.ai.ui.AppleDialog.Builder(this)
                .setTitle("删除")
                .setMessage(msg)
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        com.aicodestudio.ide.ai.core.SessionManager sm =
                                com.aicodestudio.ide.ai.core.SessionManager.get(ChatActivity.this);
                        sm.deleteSession(s.sessionId);
                        // v1.153bb：删除会话时连带清理它的 KV 文件（会话ID.kv / .kv.meta）
                        try {
                            com.aicodestudio.ide.ai.chat.AIChatManager.getInstance()
                                    .deleteSessionKvFile(s.sessionId + ".kv");
                        } catch (Throwable ignored) {}
                        rebuildList();
                        toast("已删除");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private TextView emptyHint(String text) {
        TextView empty = new TextView(this);
        empty.setText(text);
        empty.setTextSize(12);
        empty.setTextColor(TEXT_TERTIARY);
        empty.setLineSpacing(dp(2), 1.3f);
        empty.setPadding(dp(14), dp(12), dp(14), dp(12));
        return empty;
    }

    /** 单个对话项：圆形对话图标 + 名称 + 预览，点击打开；长按删除 */
    private View makeChatItem(final ChatPanel.ConversationItem it, boolean current) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        // v1.51bb：选中状态背景变浅
        int cardColor = current ? 0xFF1E3A5F : 0xFF1C2333;
        row.setBackground(rounded(cardColor, dp(8)));
        LinearLayout.LayoutParams rowLp = (LinearLayout.LayoutParams) row.getLayoutParams();
        rowLp.setMargins(dp(8), dp(4), dp(8), dp(4));
        row.setLayoutParams(rowLp);

        // 圆形对话图标
        ImageView iconBox = new ImageView(this);
        Drawable ic = icon("ic_chat", 0xFFFFFFFF);
        if (ic != null) iconBox.setImageDrawable(ic);
        iconBox.setScaleType(ImageView.ScaleType.CENTER);
        iconBox.setBackground(rounded(0xFF2196F3, dp(18)));  // 蓝色圆形对话图标
        iconBox.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
        row.addView(iconBox);

        // 右侧文字区
        LinearLayout textBox = new LinearLayout(this);
        textBox.setOrientation(LinearLayout.VERTICAL);
        textBox.setPadding(dp(12), 0, 0, 0);
        textBox.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(this);
        name.setText(it.name);
        name.setTextSize(14);
        name.setTextColor(0xFFE6EDF3);  // 主文字（和设置页一致）
        name.setSingleLine(true);
        textBox.addView(name);

        if (it.preview != null && !it.preview.isEmpty()) {
            TextView pre = new TextView(this);
            pre.setText(it.preview);
            pre.setTextSize(11);
            pre.setTextColor(0xFF8B949E);  // 次要文字（和设置页一致）
            pre.setSingleLine(true);
            pre.setPadding(0, dp(2), 0, 0);
            textBox.addView(pre);
        }
        row.addView(textBox);

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                openConversationItem(it);
            }
        });
        row.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                confirmDeleteChat(it);
                return true;
            }
        });
        return row;
    }

    /** 打开历史对话（纯对话 → 场景A 加载该会话） */
    private void openConversationItem(ChatPanel.ConversationItem it) {
        if (chatPanel == null) return;
        chatPanel.openConversation(it.key, it.projectPath);
        updateTabEnabled();  // v1.51bq：切换对话后更新按钮状态
        if (tvSubTitle != null) {
            tvSubTitle.setText(it.projectPath == null || it.projectPath.isEmpty()
                    ? "" : new File(it.projectPath).getName());
        }
        closeDrawer();
    }

    /** 长按删除对话（确认后删除历史文件 + 新会话管理器） */
    private void confirmDeleteChat(final ChatPanel.ConversationItem it) {
        new com.aicodestudio.ide.ai.ui.AppleDialog.Builder(this)
                .setTitle("删除对话")
                .setMessage("确定删除「" + it.name + "」这条对话吗？")
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        boolean isCurrentDeleted = it.key.equals(chatPanel.getCurrentConvKey());

                        // v1.67：同时删除新会话管理器里的记录
                        com.aicodestudio.ide.ai.core.SessionManager sm =
                                com.aicodestudio.ide.ai.core.SessionManager.get(ChatActivity.this);
                        // 从旧 key 找对应的 sessionId（暂时先用旧逻辑删）
                        chatPanel.deleteConversation(it.key);
                        // v1.153bb：连带清理该会话 KV 文件（会话ID.kv / .kv.meta）
                        try {
                            com.aicodestudio.ide.ai.chat.AIChatManager.getInstance()
                                    .deleteSessionKvFile(it.key + ".kv");
                        } catch (Throwable ignored) {}

                        drawerList.post(new Runnable() {
                            @Override public void run() { rebuildList(); }
                        });
                        // 如果删的是当前对话，自动加载下一个或新建空白对话
                        if (isCurrentDeleted) {
                            java.util.List<ChatPanel.ConversationItem> remaining =
                                    ChatPanel.listChatConversations(ChatActivity.this);
                            if (remaining.isEmpty()) {
                                chatPanel.newConversation();
                            } else {
                                openConversationItem(remaining.get(0));
                            }
                        }
                        toast("对话已删除");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** v1.51cj：长按删除项目（和欢迎页删除项目Dialog风格一致） */
    private void confirmDeleteProject(final String projectPath) {
        if (projectPath == null || projectPath.isEmpty()) return;
        final java.io.File dir = new java.io.File(projectPath);
        new com.aicodestudio.ide.ai.ui.AppleDialog.Builder(this)
                .setTitle("删除项目")
                .setMessage("将永久删除该目录下所有文件和聊天历史，确定继续？")
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        // 递归删本地项目目录
                        deleteRecursive(dir);
                        // 从最近项目列表移除
                        AppConfig.getInstance(ChatActivity.this).removeRecentProject(projectPath);
                        // 删对应的聊天历史文件
                        try {
                            String key = com.aicodestudio.ide.ai.chat.ChatPanel
                                    .historyFileNameBaseStatic(projectPath);
                            java.io.File histDir = new java.io.File(getFilesDir(), "chat_history");
                            new java.io.File(histDir, key + ".json").delete();
                            new java.io.File(histDir, key + ".meta.json").delete();
                        } catch (Throwable ignored) {}
                        // 如果当前正在这个项目对话，切到空白对话
                        if (chatPanel.getCurrentProjectPath() != null
                                && chatPanel.getCurrentProjectPath().equals(projectPath)) {
                            chatPanel.newConversation();
                            // 同步清空WorkspaceState的项目路径，不然底部按钮状态不对
                            state().setProjectPath("");
                            state().setProjectType("");
                            if (tvSubTitle != null) tvSubTitle.setText("");
                        }
                        rebuildList();
                        toast("项目已删除");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteRecursive(java.io.File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            java.io.File[] children = f.listFiles();
            if (children != null) for (java.io.File c : children) deleteRecursive(c);
        }
        f.delete();
    }

    /** 项目列表：绑定项目的对话（点击打开项目对话）+ 项目目录（无对话则打开项目空对话） */
    private void rebuildProjectList() {
        // v1.67：用新的 SessionManager 数据源
        com.aicodestudio.ide.ai.core.SessionManager sm =
                com.aicodestudio.ide.ai.core.SessionManager.get(this);
        java.util.List<com.aicodestudio.ide.ai.core.ProjectSession> items = sm.getProjectSessions();

        String currentPath = state().getProjectPath();
        boolean any = false;
        for (final com.aicodestudio.ide.ai.core.ProjectSession s : items) {
            boolean isCurrent = s.projectPath != null && s.projectPath.equals(currentPath);
            drawerList.addView(makeSessionItem(s, isCurrent));
            any = true;
        }
        if (!any) {
            drawerList.addView(emptyHint("还没有项目\n请回到主页新建项目或打开已有项目"));
        }
    }

    private boolean hasConvFor(java.util.List<ChatPanel.ConversationItem> convs, String path) {
        if (path == null) return false;
        for (ChatPanel.ConversationItem it : convs) {
            if (path.equals(it.projectPath)) return true;
        }
        return false;
    }

    /** 项目对话项：圆形项目图标 + 项目名 + 预览，点击打开项目对话 */
    private View makeProjectConvItem(final ChatPanel.ConversationItem it, boolean current) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        // v1.51bd：选中状态和对话列表一致
        int cardColor = current ? 0xFF1E3A5F : 0xFF1C2333;
        row.setBackground(rounded(cardColor, dp(8)));
        LinearLayout.LayoutParams rowLp = (LinearLayout.LayoutParams) row.getLayoutParams();
        rowLp.setMargins(dp(8), dp(4), dp(8), dp(4));
        row.setLayoutParams(rowLp);

        // 圆形项目图标（绿色）
        ImageView iconBox = new ImageView(this);
        Drawable ic = icon("ic_folder", 0xFFFFFFFF);
        if (ic != null) iconBox.setImageDrawable(ic);
        iconBox.setScaleType(ImageView.ScaleType.CENTER);
        iconBox.setBackground(rounded(0xFF2EA043, dp(18)));  // 绿色圆形项目图标
        iconBox.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
        row.addView(iconBox);

        // 右侧文字区
        LinearLayout textBox = new LinearLayout(this);
        textBox.setOrientation(LinearLayout.VERTICAL);
        textBox.setPadding(dp(12), 0, 0, 0);
        textBox.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(this);
        name.setText(it.name);
        name.setTextSize(14);
        name.setTextColor(0xFFE6EDF3);  // 主文字（和设置页一致）
        name.setSingleLine(true);
        textBox.addView(name);

        TextView sub = new TextView(this);
        sub.setText((it.preview == null || it.preview.isEmpty()) ? it.projectPath : it.preview);
        sub.setTextSize(10);
        sub.setTextColor(0xFF8B949E);  // 次要文字（和设置页一致）
        sub.setSingleLine(true);
        sub.setPadding(0, dp(2), 0, 0);
        textBox.addView(sub);
        row.addView(textBox);

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                openProjectConversation(it);
            }
        });
        row.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                confirmDeleteProject(it.projectPath);
                return true;
            }
        });
        return row;
    }

    /** 打开项目对话：切换绑定到项目并加载其历史 */
    private void openProjectConversation(ChatPanel.ConversationItem it) {
        if (chatPanel == null || it.projectPath == null) return;
        chatPanel.setCurrentProject(it.projectPath);
        // 更新共享状态
        state().setProjectPath(it.projectPath);
        state().setProjectType(WorkspaceState.detectProjectType(it.projectPath));
        AppConfig.getInstance(this).addRecentProject(it.projectPath);
        if (tvSubTitle != null) tvSubTitle.setText(state().getProjectName());
        // 【修复】打开项目后更新底部 Tab 的启用状态
        updateTabEnabled();
        closeDrawer();
        toast("已打开项目：" + state().getProjectName());
    }

    /** 单个项目项：圆形项目图标 + 项目名 + 路径，当前项目高亮 */
    private View makeProjectItem(final String path, boolean current) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        // v1.51az：选中状态背景变浅
        int cardColor = current ? 0xFF1E3A5F : 0xFF1C2333;
        row.setBackground(rounded(cardColor, dp(8)));
        LinearLayout.LayoutParams rowLp = (LinearLayout.LayoutParams) row.getLayoutParams();
        rowLp.setMargins(dp(8), dp(4), dp(8), dp(4));
        row.setLayoutParams(rowLp);

        // 圆形项目图标
        ImageView iconBox = new ImageView(this);
        Drawable ic = icon("ic_folder", 0xFFFFFFFF);
        if (ic != null) iconBox.setImageDrawable(ic);
        iconBox.setScaleType(ImageView.ScaleType.CENTER);
        iconBox.setBackground(rounded(0xFF2EA043, dp(18)));
        iconBox.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
        row.addView(iconBox);

        // 右侧文字区
        LinearLayout textBox = new LinearLayout(this);
        textBox.setOrientation(LinearLayout.VERTICAL);
        textBox.setPadding(dp(12), 0, 0, 0);
        textBox.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(this);
        name.setText(current ? state().getProjectName() : new File(path).getName());
        name.setTextSize(14);
        name.setTextColor(0xFFE6EDF3);
        name.setSingleLine(true);
        textBox.addView(name);

        TextView sub = new TextView(this);
        sub.setText((current ? "当前项目 · " : "") + new File(path).getName());
        sub.setTextSize(10);
        sub.setTextColor(0xFF8B949E);
        sub.setSingleLine(true);
        sub.setPadding(0, dp(2), 0, 0);
        textBox.addView(sub);
        row.addView(textBox);

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                switchProject(path);
            }
        });
        row.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                confirmDeleteProject(path);
                return true;
            }
        });
        return row;
    }

    /** 切换项目：保存旧项目历史 → 更新共享状态 → 加载新项目历史 → 更新标题 */
    private void switchProject(String path) {
        if (path == null || path.length() == 0) return;
        if (path.equals(state().getProjectPath())) {
            closeDrawer();
            return;
        }
        // ChatPanel 内部会先保存旧项目历史，再加载新项目历史
        chatPanel.setCurrentProject(path);
        // 更新共享状态
        state().setProjectPath(path);
        state().setProjectType(WorkspaceState.detectProjectType(path));
        AppConfig.getInstance(this).addRecentProject(path);
        // 更新顶部项目名
        if (tvSubTitle != null) tvSubTitle.setText(state().getProjectName());
        closeDrawer();
        toast("已切换到项目：" + state().getProjectName());
    }

    private void openDrawer() {
        ensureDrawer();
        if (drawerOpen) return;
        drawerOpen = true;
        rebuildList();
        drawerScrim.setVisibility(View.VISIBLE);
        drawerPanel.setVisibility(View.VISIBLE);
        drawerPanel.setTranslationX(dp(DRAWER_WIDTH_DP));
        drawerScrim.setAlpha(0f);
        drawerPanel.animate().translationX(0).setDuration(220).start();
        drawerScrim.animate().alpha(1f).setDuration(220).start();
    }

    private void closeDrawer() {
        if (!drawerOpen) return;
        drawerOpen = false;
        drawerPanel.animate().translationX(dp(DRAWER_WIDTH_DP)).setDuration(180)
                .withEndAction(new Runnable() {
                    @Override public void run() {
                        drawerPanel.setVisibility(View.GONE);
                        drawerScrim.setVisibility(View.GONE);
                    }
                }).start();
        drawerScrim.animate().alpha(0f).setDuration(180).start();
    }

    @Override
    public void onBackPressed() {
        if (drawerOpen) {
            closeDrawer();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // 转发语音输入权限结果给 ChatPanel
        if (requestCode == com.aicodestudio.ide.ai.chat.ChatPanel.REQ_RECORD_AUDIO
                && chatPanel != null) {
            try { chatPanel.onVoicePermissionResult(grantResults); } catch (Throwable ignored) {}
        }
    }
}
