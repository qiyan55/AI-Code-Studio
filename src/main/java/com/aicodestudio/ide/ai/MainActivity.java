package com.aicodestudio.ide.ai;

import com.aicodestudio.ide.ai.ui.Theme;
import com.aicodestudio.ide.ai.ui.UiKit;
import com.aicodestudio.ide.ai.ui.AISettingsDialog;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.aicodestudio.ide.ai.api.AIApiBridge;
import com.aicodestudio.ide.ai.chat.ChatPanel;
import com.aicodestudio.ide.ai.compile.CompileExecutor;
import com.aicodestudio.ide.ai.compile.CompileLogPanel;
import com.aicodestudio.ide.ai.core.AppConfig;
import com.aicodestudio.ide.ai.editor.CodeEditorView;
import com.aicodestudio.ide.ai.editor.FileTreeView;
import com.aicodestudio.ide.ai.models.LocalModelManager;
import com.aicodestudio.ide.ai.models.ModelManageDialog;
import com.aicodestudio.ide.ai.service.AIService;
import com.aicodestudio.ide.ai.tools.BuildToolManager;
import com.aicodestudio.ide.ai.tools.BuildToolManageDialog;
import com.aicodestudio.ide.ai.tools.EditBuildToolDialog;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import com.aicodestudio.ide.ai.ui.AppleDialog;

/**
 * 主工作界面（核心界面）
 * 实现文档 5.2 页面1 全部区域：
 * 1. 顶部工具栏：新建文件、打开项目、保存、保存全部、编译运行、终止编译、
 *    编译工具管理器、系统设置、本地模型管理
 * 2. 左侧项目树：目录结构 + 右键菜单（新建/删除/重命名/打开）
 * 3. 中部代码编辑器：多标签切换、行号、语法高亮、错误波浪线
 * 4. 右侧上方 AI 聊天面板
 * 5. 右侧下方 编译日志面板
 * 6. 底部状态栏：项目类型、激活工具、模型模式、光标位置
 *
 * 同时注册文档第7章全部开放 API 到 AIApiBridge。
 */
public class MainActivity extends Activity {

    // ===== 区域容器 =====
    private LinearLayout rootLayout;
    private HorizontalScrollView toolbarScroll;
    private LinearLayout toolbar;
    private FileTreeView fileTreeView;
    private LinearLayout tabBar;
    private CodeEditorView editorView;
    private LinearLayout bottomPanel;
    private LinearLayout bottomTabBar;
    private FrameLayout bottomContent;
    private LinearLayout statusBar;

    private ChatPanel chatPanel;
    private CompileLogPanel logPanel;

    // ===== 业务状态 =====
    private String projectPath = "";
    private String projectType = "custom";
    private String activeToolId = "";
    private List<TabInfo> openTabs = new ArrayList<TabInfo>();
    private int currentTab = -1;
    private boolean treeVisible = true;
    private int bottomMode = 0; // 0=AI对话, 1=编译日志
    private Button[] bottomTabs; // 底部面板 Tab 按钮引用

    private Handler mainHandler;
    private Runnable detectRunnable;

    private AppConfig config;
    private BuildToolManager toolManager;
    private CompileExecutor compileExecutor;

    // 状态栏文本
    private TextView tvStatusProject;
    private TextView tvStatusTool;
    private TextView tvStatusModel;
    private TextView tvStatusCursor;

    /** 标签页信息 */
    static class TabInfo {
        String filePath;
        String content;
        boolean dirty;
        TabInfo(String p, String c) {
            filePath = p; content = c; dirty = false;
        }
        String name() {
            if (filePath == null) return "Untitled";
            int i = filePath.lastIndexOf('/');
            return i >= 0 ? filePath.substring(i + 1) : filePath;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mainHandler = new Handler(Looper.getMainLooper());

        try {
            config = AppConfig.getInstance(this);
            toolManager = BuildToolManager.getInstance(this);
            compileExecutor = CompileExecutor.getInstance();
        } catch (Throwable t) {
            android.util.Log.e("MainActivity", "init manager failed", t);
        }

        buildUI();

        // 优先打开欢迎页传入的项目，否则加载默认示例项目
        String path = null;
        int initBottom = 0;
        try {
            android.content.Intent it = getIntent();
            if (it != null) {
                path = it.getStringExtra("project_path");
                initBottom = it.getIntExtra("initial_bottom", 0);
            }
        } catch (Throwable t) {
            android.util.Log.e("MainActivity", "read intent failed", t);
        }

        if (savedInstanceState != null) {
            // v1.48f fix：进程重建时恢复标签页状态，而不是重新走默认项目
            restoreTabs(savedInstanceState);
        } else if (path != null && path.length() > 0 && new File(path).isDirectory()) {
            openProject(path);
        } else {
            initDefaultProject();
        }

        setBottomTab(initBottom);

        registerApiControllers();
        setupCompileListener();
        updateStatusBar();
    }

    // ================= 界面构建 =================

    private void buildUI() {
        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(Theme.BG_PRIMARY);
        rootLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(rootLayout);

        buildToolbar();      // 区域1：顶部工具栏
        buildMainArea();     // 区域2+3：文件树 + 编辑器
        buildBottomPanel();  // 区域4+5：AI面板 + 日志面板
        buildStatusBar();    // 区域6：底部状态栏
    }

    /** 区域1：顶部工具栏（文档 5.2） */
    private void buildToolbar() {
        toolbarScroll = new HorizontalScrollView(this);
        toolbarScroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));
        toolbarScroll.setBackgroundColor(Theme.BG_SECONDARY);
        toolbarScroll.setHorizontalScrollBarEnabled(false);

        toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(6), 0, dp(6), 0);
        toolbarScroll.addView(toolbar);

        addToolbarButton("ic_home", "主页", new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, WelcomeActivity.class));
            }
        });
        addToolbarButton("ic_new_file", "新建", new View.OnClickListener() {
            @Override public void onClick(View v) { actionNewFile(); }
        });
        addToolbarButton("ic_open", "打开项目", new View.OnClickListener() {
            @Override public void onClick(View v) { actionOpenProject(); }
        });
        addToolbarButton("ic_save", "保存", new View.OnClickListener() {
            @Override public void onClick(View v) { actionSave(); }
        });
        addToolbarButton("ic_save_all", "保存全部", new View.OnClickListener() {
            @Override public void onClick(View v) { actionSaveAll(); }
        });
        addToolbarButton("ic_build", "编译工具", new View.OnClickListener() {
            @Override public void onClick(View v) { actionOpenToolManager(); }
        });
        addToolbarButton("ic_play", "编译运行", new View.OnClickListener() {
            @Override public void onClick(View v) { actionRunCompile(); }
        });
        addToolbarButton("ic_stop", "终止", new View.OnClickListener() {
            @Override public void onClick(View v) { actionStopCompile(); }
        });
        addToolbarButton("ic_chip", "模型", new View.OnClickListener() {
            @Override public void onClick(View v) {
                new AISettingsDialog(MainActivity.this).show();
            }
        });
        addToolbarButton("ic_settings", "设置", new View.OnClickListener() {
            @Override public void onClick(View v) { actionOpenSettings(); }
        });
        addToolbarButton("ic_tree", "文件树", new View.OnClickListener() {
            @Override public void onClick(View v) {
                treeVisible = !treeVisible;
                fileTreeView.setVisibility(treeVisible ? View.VISIBLE : View.GONE);
            }
        });

        rootLayout.addView(toolbarScroll);
    }

    /** 工具栏按钮：矢量图标（上）+ 文字（下），不使用 emoji */
    private void addToolbarButton(String iconName, String label, View.OnClickListener l) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                dp(52), dp(40));
        lp.setMargins(dp(2), 0, dp(2), 0);
        item.setLayoutParams(lp);
        item.setBackgroundColor(Theme.BG_TERTIARY);

        android.widget.ImageView iv = new android.widget.ImageView(this);
        android.graphics.drawable.Drawable d = loadIcon(iconName);
        if (d != null) {
            d = d.mutate();
            d.setTint(Theme.TEXT_PRIMARY);
            iv.setImageDrawable(d);
        }
        iv.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(20), dp(18)));
        item.addView(iv);

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(9);
        tv.setTextColor(Theme.TEXT_PRIMARY);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        item.addView(tv);

        item.setOnClickListener(l);
        toolbar.addView(item);
    }

    /** 按资源名加载矢量图标 */
    private android.graphics.drawable.Drawable loadIcon(String name) {
        try {
            int id = getResources().getIdentifier(name, "drawable", getPackageName());
            if (id == 0) return null;
            return getResources().getDrawable(id);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 区域2 + 区域3：文件树 + 多标签编辑器 */
    private void buildMainArea() {
        LinearLayout mainArea = new LinearLayout(this);
        mainArea.setOrientation(LinearLayout.HORIZONTAL);
        mainArea.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- 左侧项目树 ----
        fileTreeView = new FileTreeView(this);
        fileTreeView.setLayoutParams(new LinearLayout.LayoutParams(dp(150),
                LinearLayout.LayoutParams.MATCH_PARENT));
        fileTreeView.setBackgroundColor(Theme.BG_PRIMARY);
        fileTreeView.setListener(new FileTreeView.FileTreeListener() {
            @Override
            public void onFileClick(final File file) {
                if (file.isFile()) openFileInTab(file.getAbsolutePath());
            }

            @Override
            public void onFileLongClick(final File file) {
                showFileContextMenu(file);
            }

            @Override
            public void onTreeChanged() {}
        });
        mainArea.addView(fileTreeView);

        // 分隔线
        View div = new View(this);
        div.setLayoutParams(new LinearLayout.LayoutParams(1,
                LinearLayout.LayoutParams.MATCH_PARENT));
        div.setBackgroundColor(Theme.STROKE);
        mainArea.addView(div);

        // ---- 右侧：标签栏 + 编辑器 ----
        LinearLayout editorArea = new LinearLayout(this);
        editorArea.setOrientation(LinearLayout.VERTICAL);
        editorArea.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        // 多标签栏
        android.widget.HorizontalScrollView tabScroll =
                new android.widget.HorizontalScrollView(this);
        tabScroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34)));
        tabScroll.setBackgroundColor(Theme.BG_SECONDARY);
        tabScroll.setHorizontalScrollBarEnabled(false);

        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setGravity(Gravity.CENTER_VERTICAL);
        tabScroll.addView(tabBar);
        editorArea.addView(tabScroll);

        // 编辑器
        editorView = new CodeEditorView(this);
        editorView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        editorView.setEditorListener(new CodeEditorView.EditorListener() {
            @Override
            public void onTextChanged(String code) { scheduleErrorDetect(); }

            @Override
            public void onCursorChanged(int row, int col) { updateCursorStatus(row, col); }

            @Override
            public void onErrorClick(CodeEditorView.CodeError error) {
                showErrorPopup(error);
            }

            @Override
            public void onModifyStateChanged(boolean dirty) {
                if (currentTab >= 0 && currentTab < openTabs.size()) {
                    openTabs.get(currentTab).dirty = dirty;
                    refreshTabBar();
                }
            }
        });
        editorArea.addView(editorView);

        mainArea.addView(editorArea);
        rootLayout.addView(mainArea);
    }

    /** 区域4 + 区域5：AI 面板 / 编译日志面板（Tab 切换，手机竖屏适配） */
    private void buildBottomPanel() {
        bottomPanel = new LinearLayout(this);
        bottomPanel.setOrientation(LinearLayout.VERTICAL);
        bottomPanel.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.85f));
        bottomPanel.setBackgroundColor(Theme.BG_PRIMARY);

        // Tab 切换栏
        bottomTabBar = new LinearLayout(this);
        bottomTabBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomTabBar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(32)));
        bottomTabBar.setBackgroundColor(Theme.BG_SECONDARY);

        bottomTabs = new Button[2];
        final Button[] tabs = bottomTabs;
        final Button tabAI = new Button(this);
        tabs[0] = tabAI;
        tabAI.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        tabAI.setText("AI 对话");
        tabAI.setTextSize(12);
        tabAI.setAllCaps(false);
        tabAI.setBackgroundColor(Theme.BG_SECONDARY);
        tabAI.setTextColor(Theme.ACCENT_BLUE);
        tabAI.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                bottomMode = 0;
                showBottomContent();
                tabs[0].setTextColor(Theme.ACCENT_BLUE);
                tabs[1].setTextColor(Theme.TEXT_SECOND);
            }
        });
        bottomTabBar.addView(tabAI);

        final Button tabLog = new Button(this);
        tabs[1] = tabLog;
        tabLog.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        tabLog.setText("编译日志");
        tabLog.setTextSize(12);
        tabLog.setAllCaps(false);
        tabLog.setBackgroundColor(Theme.BG_SECONDARY);
        tabLog.setTextColor(Theme.TEXT_SECOND);
        tabLog.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                bottomMode = 1;
                showBottomContent();
                tabs[1].setTextColor(Theme.ACCENT_BLUE);
                tabs[0].setTextColor(Theme.TEXT_SECOND);
            }
        });
        bottomTabBar.addView(tabLog);

        bottomPanel.addView(bottomTabBar);

        // 内容容器
        bottomContent = new FrameLayout(this);
        bottomContent.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        chatPanel = new ChatPanel(this);
        chatPanel.setChatPanelListener(new ChatPanel.ChatPanelListener() {
            @Override
            public void onInsertCodeToEditor(String code) {
                // 文档 流程1 + 5.3规则1：插入并滚动高亮
                if (editorView != null) {
                    editorView.insertCode(code, -1);
                }
            }

            @Override
            public String getEditorSelection() {
                return editorView == null ? "" : editorView.getSelection();
            }

            @Override
            public void onConversationReset() {
                // 空实现（MainActivity 不需要更新项目名）
            }

            @Override
            public void onProjectBound(String projectPath) {
                // 空实现（MainActivity 不需要更新项目名）
            }

            @Override
            public void onFirstUserMessage(String text) {
                // 空实现（MainActivity 会话绑定项目，标题为项目名，不覆盖）
            }

            @Override
            public void onChatModeChanged(int mode) {
                // v1.139：MainActivity 无会话列表持久化需求，空实现
            }
        });
        // 绑定当前项目，加载该项目的历史聊天（与 AI 对话页保持同一份历史）
        chatPanel.setCurrentProject(com.aicodestudio.ide.ai.core.WorkspaceState.get().getProjectPath());

        logPanel = new CompileLogPanel(this);
        logPanel.setLogPanelListener(new CompileLogPanel.LogPanelListener() {
            @Override
            public void onSendLogToAI(String log) {
                // 文档 4.4.2-1 一键发送日志给 AI
                bottomMode = 0;
                showBottomContent();
                chatPanel.sendCompileLogToAI(log);
            }

            @Override
            public void onCompileClick() {
                // 主界面底部日志面板不承担编译触发（编译入口在「编译运行」页日志面板头部）
                // 提示用户前往编译运行页
                android.widget.Toast.makeText(MainActivity.this,
                        "请到「编译运行」页点击编译", android.widget.Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onArtifactsClick() {
                com.aicodestudio.ide.ai.compile.ArtifactListDialog.show(MainActivity.this);
            }
        });

        bottomContent.addView(chatPanel);
        bottomContent.addView(logPanel);
        logPanel.setVisibility(View.GONE);

        bottomPanel.addView(bottomContent);
        rootLayout.addView(bottomPanel);
    }

    private void showBottomContent() {
        if (bottomMode == 0) {
            chatPanel.setVisibility(View.VISIBLE);
            logPanel.setVisibility(View.GONE);
        } else {
            chatPanel.setVisibility(View.GONE);
            logPanel.setVisibility(View.VISIBLE);
        }
    }

    /** 切换底部面板并同步 Tab 文字颜色（供外部/Intent 调用） */
    private void setBottomTab(int mode) {
        bottomMode = (mode == 1) ? 1 : 0;
        showBottomContent();
        if (bottomTabs != null && bottomTabs.length >= 2
                && bottomTabs[0] != null && bottomTabs[1] != null) {
            // v1.48f fix：统一走 Theme 常量，不用硬编码色值
            bottomTabs[0].setTextColor(
                    bottomMode == 0 ? Theme.ACCENT_BLUE : Theme.TEXT_SECOND);
            bottomTabs[1].setTextColor(
                    bottomMode == 1 ? Theme.ACCENT_BLUE : Theme.TEXT_SECOND);
        }
    }

    /** 区域6：底部状态栏（文档 5.2） */
    private void buildStatusBar() {
        statusBar = new LinearLayout(this);
        statusBar.setOrientation(LinearLayout.HORIZONTAL);
        statusBar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(26)));
        statusBar.setBackgroundColor(Theme.BG_SECONDARY);
        statusBar.setGravity(Gravity.CENTER_VERTICAL);
        statusBar.setPadding(dp(8), 0, dp(8), 0);

        tvStatusProject = makeStatusText("项目: 未打开");
        statusBar.addView(tvStatusProject);

        tvStatusTool = makeStatusText("工具: 无");
        statusBar.addView(tvStatusTool);

        tvStatusModel = makeStatusText("模型: 云端");
        tvStatusModel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleModelMode(); }
        });
        statusBar.addView(tvStatusModel);

        tvStatusCursor = makeStatusText("行1 列1");
        tvStatusCursor.setGravity(Gravity.END);
        statusBar.addView(tvStatusCursor);

        rootLayout.addView(statusBar);
    }

    private TextView makeStatusText(String text) {
        TextView tv = new TextView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tv.setLayoutParams(lp);
        tv.setText(text);
        tv.setTextColor(Theme.TEXT_SECOND);
        tv.setTextSize(10);
        tv.setSingleLine(true);
        return tv;
    }

    // ================= 文档第7章 API 注册 =================

    private void registerApiControllers() {
        try {
            AIApiBridge bridge = AIApiBridge.getInstance();

            // 编辑器接口（7.1）
            bridge.setEditorController(new AIApiBridge.EditorController() {
                @Override public void insertCode(String text, int position) {
                    mainHandler.post(new Runnable() {
                        @Override public void run() {
                            if (editorView != null) editorView.insertCode(text, position);
                        }
                    });
                }
                @Override public void replaceSelection(String newText) {
                    mainHandler.post(new Runnable() {
                        @Override public void run() {
                            if (editorView != null) editorView.replaceSelection(newText);
                        }
                    });
                }
                @Override public String getCurrentFileCode() {
                    return editorView == null ? "" : editorView.getCode();
                }
                @Override public String getCodeSelection() {
                    return editorView == null ? "" : editorView.getSelection();
                }
                @Override public List<String> getCodeErrors() {
                    return editorView == null
                            ? new ArrayList<String>() : editorView.getErrorMessages();
                }
                @Override public void openFile(final String filePath) {
                    mainHandler.post(new Runnable() {
                        @Override public void run() { openFileInTab(filePath); }
                    });
                }
                @Override public void saveCurrentFile() {
                    mainHandler.post(new Runnable() {
                        @Override public void run() { actionSave(); }
                    });
                }
                @Override public int getCursorRow() {
                    return editorView == null ? 1 : editorView.getCursorPosition()[0];
                }
                @Override public int getCursorCol() {
                    return editorView == null ? 1 : editorView.getCursorPosition()[1];
                }
                @Override public String getCurrentFilePath() {
                    return editorView == null ? "" : editorView.getFilePath();
                }
            });

            // 编译接口（7.2）
            bridge.setCompileController(new AIApiBridge.CompileController() {
                @Override public void runCompile(String toolId, String projectPath) {
                    final String t = toolId, p = projectPath;
                    mainHandler.post(new Runnable() {
                        @Override public void run() { doCompile(t, p); }
                    });
                }
                @Override public void stopCompile() { compileExecutor.stopCompile(); }
                @Override public String getCompileLog() { return compileExecutor.getFullLog(); }
                @Override public void clearLog() {
                    mainHandler.post(new Runnable() {
                        @Override public void run() { logPanel.clearLog(); }
                    });
                }
                @Override public boolean isCompiling() { return compileExecutor.isCompiling(); }
            });

            // 工具链接口（7.3）
            bridge.setToolchainController(new AIApiBridge.ToolchainController() {
                @Override
                public void autoCreateBuildTool(JSONObject toolConfig) {
                    final JSONObject cfg = toolConfig;
                    mainHandler.post(new Runnable() {
                        @Override public void run() { doAutoCreateTool(cfg); }
                    });
                }
                @Override
                public List<JSONObject> listBuildTools() {
                    List<JSONObject> list = new ArrayList<JSONObject>();
                    try {
                        for (BuildToolManager.BuildTool t : toolManager.getAllTools()) {
                            list.add(t.toJSON());
                        }
                    } catch (Throwable ignored) {}
                    return list;
                }
                @Override
                public void deleteBuildTool(String toolId) { toolManager.deleteTool(toolId); }
                @Override
                public void enableBuildTool(String toolId, boolean enabled) {
                    toolManager.setEnabled(toolId, enabled);
                }
            });
        } catch (Throwable t) {
            android.util.Log.e("MainActivity", "register api failed", t);
        }
    }

    // ================= 编译联动 =================

    private void setupCompileListener() {
        compileExecutor.setListener(new CompileExecutor.CompileListener() {
            @Override
            public void onCompileStart() {
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        logPanel.clearLog();
                        logPanel.appendLine("开始编译...", CompileExecutor.LOG_INFO);
                        bottomMode = 1;
                        showBottomContent();
                        setStatusBarTip("编译中...");
                    }
                });
            }

            @Override
            public void onLogOutput(final CompileExecutor.LogEntry entry) {
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        logPanel.appendLine(entry.message, entry.level);
                    }
                });
            }

            @Override
            public void onCompileSuccess(final String log) {
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        logPanel.appendLine("编译完成", CompileExecutor.LOG_SUCCESS);
                        chatPanel.notifyCompileResult(true, "");
                        setStatusBarTip("编译成功");
                    }
                });
            }

            @Override
            public void onCompileFailed(final String log, final int exitCode) {
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        logPanel.appendLine("编译错误 (exit=" + exitCode + ")",
                                CompileExecutor.LOG_ERROR);
                        chatPanel.notifyCompileResult(false, "退出码 " + exitCode);
                        setStatusBarTip("编译失败");
                        // 文档 4.4.2-2 编译失败自动推送日志至 AI
                        if (config.isAutoSendCompileLog()) {
                            chatPanel.sendCompileLogToAI(log);
                        }
                    }
                });
            }

            @Override
            public void onCompileCancelled() {
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        logPanel.appendLine("编译已终止", CompileExecutor.LOG_WARN);
                        setStatusBarTip("已终止");
                    }
                });
            }
        });
    }

    // ================= 工具栏动作 =================

    private void actionNewFile() {
        if (projectPath == null || projectPath.length() == 0) {
            toast("请先打开项目");
            return;
        }
        final EditText et = new EditText(this);
        et.setHint("文件名，如 MainActivity.java");
        et.setTextColor(Theme.TEXT_PRIMARY);
        et.setHintTextColor(Theme.TEXT_TERTIARY);
        et.setPadding(dp(16), dp(12), dp(16), dp(12));
        new AppleDialog.Builder(this)
                .setTitle("新建文件")
                .setView(et)
                .setPositiveButton("创建", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String name = et.getText().toString().trim();
                        if (name.length() == 0) return;
                        File dir = new File(projectPath);
                        if (!dir.exists()) dir.mkdirs();
                        File nf = new File(dir, name);
                        try {
                            if (nf.exists()) {
                                toast("文件已存在");
                                return;
                            }
                            nf.createNewFile();
                            fileTreeView.refresh();
                            openFileInTab(nf.getAbsolutePath());
                            toast("已创建 " + name);
                        } catch (Exception e) {
                            toast("创建失败: " + e.getMessage());
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void actionOpenProject() {
        final EditText et = new EditText(this);
        et.setHint("项目路径，如 /sdcard/MyProject");
        et.setTextColor(Theme.TEXT_PRIMARY);
        et.setHintTextColor(Theme.TEXT_TERTIARY);
        et.setPadding(dp(16), dp(12), dp(16), dp(12));
        if (projectPath.length() > 0) et.setText(projectPath);

        new AppleDialog.Builder(this)
                .setTitle("打开项目")
                .setView(et)
                .setPositiveButton("打开", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String path = et.getText().toString().trim();
                        File f = new File(path);
                        if (!f.exists() || !f.isDirectory()) {
                            toast("目录不存在");
                            return;
                        }
                        openProject(path);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void openProject(String path) {
        // 有未保存修改时先询问，避免切换项目丢代码（v1.48f fix）
        boolean anyDirty = false;
        for (int i = 0; i < openTabs.size(); i++) {
            if (openTabs.get(i).dirty) { anyDirty = true; break; }
        }
        if (anyDirty) {
            final String target = path;
            new AppleDialog.Builder(this)
                    .setTitle("未保存的修改")
                    .setMessage("当前有未保存的文件，切换项目前是否保存？")
                    .setPositiveButton("保存并切换",
                            new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface d, int w) {
                                    for (int i = 0; i < openTabs.size(); i++) {
                                        if (openTabs.get(i).dirty) saveTab(i);
                                    }
                                    doOpenProject(target);
                                }
                            })
                    .setNegativeButton("不保存", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            doOpenProject(target);
                        }
                    })
                    .setNeutralButton("取消", null)
                    .show();
            return;
        }
        doOpenProject(path);
    }

    /** openProject 的实际切换动作（已通过未保存确认，仅供 openProject 内部调用） */
    private void doOpenProject(String path) {
        projectPath = path;
        projectType = detectProjectType(path);
        fileTreeView.loadProject(path);
        openTabs.clear();
        currentTab = -1;
        refreshTabBar();
        // 自动加载项目类型对应的编译工具
        autoSelectTool();
        updateStatusBar();
        toast("已打开项目: " + new File(path).getName());
    }

    /** 自动识别项目类型（文档 4.1.3） */
    private String detectProjectType(String path) {
        try {
            File dir = new File(path);
            if (new File(dir, "build.gradle").exists()
                    || new File(dir, "build.gradle.kts").exists()
                    || new File(dir, "AndroidManifest.xml").exists()) return "Android";
            if (new File(dir, "pom.xml").exists()) return "Java";
            if (new File(dir, "CMakeLists.txt").exists()
                    || new File(dir, "Makefile").exists()) return "C++";
            if (new File(dir, "requirements.txt").exists()
                    || new File(dir, "setup.py").exists()) return "Python";
        } catch (Throwable ignored) {}
        return "custom";
    }

    private void autoSelectTool() {
        try {
            List<BuildToolManager.BuildTool> tools =
                    toolManager.getToolsByType(projectType);
            if (tools != null && tools.size() > 0) {
                activeToolId = tools.get(0).id;
            } else {
                activeToolId = "";
            }
        } catch (Throwable t) {
            activeToolId = "";
        }
        updateStatusBar();
    }

    private void actionSave() {
        if (currentTab < 0 || currentTab >= openTabs.size()) {
            toast("没有打开的文件");
            return;
        }
        saveTab(currentTab);
    }

    private void actionSaveAll() {
        int n = 0;
        for (int i = 0; i < openTabs.size(); i++) {
            if (openTabs.get(i).dirty) { saveTab(i); n++; }
        }
        toast(n > 0 ? "已保存 " + n + " 个文件" : "无修改需要保存");
    }

    private void saveTab(int index) {
        try {
            TabInfo tab = openTabs.get(index);
            if (tab.filePath == null || tab.filePath.length() == 0) {
                toast("文件路径为空，无法保存");
                return;
            }
            String content = (index == currentTab && editorView != null)
                    ? editorView.getCode() : tab.content;
            java.io.FileWriter fw = new java.io.FileWriter(tab.filePath);
            fw.write(content);
            fw.close();
            tab.content = content;
            tab.dirty = false;
            if (index == currentTab && editorView != null) editorView.markSaved();
            refreshTabBar();
            toast("已保存 " + tab.name());
        } catch (Exception e) {
            toast("保存失败: " + e.getMessage());
        }
    }

    private void actionOpenToolManager() {
        BuildToolManageDialog dlg = new BuildToolManageDialog(this);
        dlg.setListener(new BuildToolManageDialog.ToolDialogListener() {
            @Override public void onToolSelected(String toolId) {
                activeToolId = toolId;
                updateStatusBar();
            }
            @Override public void onToolsChanged() { updateStatusBar(); }
            @Override public void onAddTool() { showEditToolDialog(null); }
            @Override public void onEditTool(BuildToolManager.BuildTool tool) {
                showEditToolDialog(tool);
            }
        });
        dlg.show();
    }

    private void showEditToolDialog(BuildToolManager.BuildTool tool) {
        EditBuildToolDialog dlg = new EditBuildToolDialog(this, tool);
        dlg.setListener(new EditBuildToolDialog.OnSaveListener() {
            @Override
            public void onSaved(BuildToolManager.BuildTool tool) {
                if (tool.autoCreated) {
                    chatPanel.notifyToolCreated(tool.name);
                }
                updateStatusBar();
                toast("工具已保存");
            }
        });
        dlg.show();
    }

    private void actionRunCompile() {
        if (projectPath.length() == 0) {
            toast("请先打开项目");
            return;
        }
        if (activeToolId.length() == 0) {
            List<BuildToolManager.BuildTool> tools =
                    toolManager.getToolsByType(projectType);
            if (tools == null || tools.size() == 0) {
                toast("未配置编译工具，请先在「编译工具」中添加，或让 AI 自动配置");
                // 提示用户可以让 AI 配置
                new AppleDialog.Builder(this)
                        .setTitle("缺少编译工具")
                        .setMessage("当前项目未绑定编译工具。是否让 AI 自动识别并配置？")
                        .setPositiveButton("让AI配置",
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int w) {
                                        letAIConfigureToolchain();
                                    }
                                })
                        .setNegativeButton("手动配置",
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int w) {
                                        actionOpenToolManager();
                                    }
                                })
                        .show();
                return;
            }
            activeToolId = tools.get(0).id;
        }
        doCompile(activeToolId, projectPath);
    }

    private void doCompile(String toolId, String projectPath) {
        try {
            // 编译前先保存所有未保存的标签，避免编译旧代码（v1.48f fix）
            for (int i = 0; i < openTabs.size(); i++) {
                if (openTabs.get(i).dirty) saveTab(i);
            }
            if (config.isSnapshotEnabled() && editorView != null) {
                editorView.createSnapshot();
            }
            String mainFile = (currentTab >= 0 && currentTab < openTabs.size())
                    ? openTabs.get(currentTab).filePath : "";
            compileExecutor.startCompile(toolId, projectPath, mainFile);
        } catch (Throwable t) {
            logPanel.appendLine("启动编译失败: " + t.getMessage(),
                    CompileExecutor.LOG_ERROR);
        }
    }

    private void actionStopCompile() {
        compileExecutor.stopCompile();
        toast("已发送终止信号");
    }

    /** 文档 流程4：AI 自动配置编译工具链 */
    private void letAIConfigureToolchain() {
        chatPanel.requestSend("我的项目是 " + projectType
                + " 类型，缺少编译环境，请帮我分析并自动创建合适的编译工具配置。");
        bottomMode = 0;
        showBottomContent();
        toast("已请求 AI 配置编译工具");
    }

    private void doAutoCreateTool(JSONObject cfg) {
        try {
            if (!config.isAllowAIAutoCreateTool()) {
                toast("已禁止 AI 自动创建编译工具");
                return;
            }
            BuildToolManager.BuildTool t = toolManager.createAutoTool(cfg);
            if (t != null) {
                chatPanel.notifyToolCreated(t.name);
                autoSelectTool();
                toast("AI 已创建工具: " + t.name);
            }
        } catch (Throwable e) {
            toast("创建失败: " + e.getMessage());
        }
    }

    private void actionOpenSettings() {
        com.aicodestudio.ide.ai.service.SettingsDialog dlg =
                new com.aicodestudio.ide.ai.service.SettingsDialog(this);
        dlg.setListener(new com.aicodestudio.ide.ai.service.SettingsDialog.OnConfigChangedListener() {
            @Override public void onConfigChanged() {
                updateStatusBar();
                chatPanel.updateModeIndicator();
            }
        });
        dlg.show();
    }

    // ================= 文件树菜单（文档 5.2） =================

    private void showFileContextMenu(final File file) {
        String[] items;
        if (file.isDirectory()) {
            items = new String[]{"新建文件", "新建文件夹", "重命名", "删除"};
        } else {
            items = new String[]{"在编辑器打开", "重命名", "删除"};
        }
        new AppleDialog.Builder(this)
                .setTitle(file.getName())
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (file.isDirectory()) {
                            switch (which) {
                                case 0: showInputDialog("新建文件", "文件名", file, true); break;
                                case 1: showInputDialog("新建文件夹", "文件夹名", file, false); break;
                                case 2: showRenameDialog(file); break;
                                case 3: confirmDelete(file); break;
                            }
                        } else {
                            switch (which) {
                                case 0: openFileInTab(file.getAbsolutePath()); break;
                                case 1: showRenameDialog(file); break;
                                case 2: confirmDelete(file); break;
                            }
                        }
                    }
                })
                .show();
    }

    private void showInputDialog(final String title, final String hint,
                                 final File parent, final boolean isFile) {
        final EditText et = new EditText(this);
        et.setHint(hint);
        et.setTextColor(Theme.TEXT_PRIMARY);
        et.setHintTextColor(Theme.TEXT_TERTIARY);
        et.setPadding(dp(16), dp(12), dp(16), dp(12));
        new AppleDialog.Builder(this)
                .setTitle(title)
                .setView(et)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String name = et.getText().toString().trim();
                        if (name.length() == 0) return;
                        boolean ok = isFile
                                ? fileTreeView.createFile(parent, name)
                                : fileTreeView.createDir(parent, name);
                        toast(ok ? "创建成功" : "创建失败");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showRenameDialog(final File file) {
        final EditText et = new EditText(this);
        et.setText(file.getName());
        et.setTextColor(Theme.TEXT_PRIMARY);
        et.setPadding(dp(16), dp(12), dp(16), dp(12));
        new AppleDialog.Builder(this)
                .setTitle("重命名")
                .setView(et)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String n = et.getText().toString().trim();
                        if (n.length() == 0) return;
                        boolean ok = fileTreeView.renameFile(file, n);
                        if (ok) {
                            // v1.48f fix：同步已打开标签的路径，避免标签指向旧路径
                            String oldPath = file.getAbsolutePath();
                            File dest = new File(file.getParentFile(), n);
                            for (int i = 0; i < openTabs.size(); i++) {
                                if (oldPath.equals(openTabs.get(i).filePath)) {
                                    openTabs.get(i).filePath = dest.getAbsolutePath();
                                    if (i == currentTab && editorView != null) {
                                        editorView.openFile(dest.getAbsolutePath(),
                                                editorView.getCode());
                                    }
                                }
                            }
                            refreshTabBar();
                            toast("重命名成功");
                        } else {
                            toast("重命名失败");
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDelete(final File file) {
        new AppleDialog.Builder(this)
                .setTitle("确认删除")
                .setMessage("确定删除 " + file.getName() + " 吗？")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        boolean ok = fileTreeView.deleteFile(file);
                        if (ok) {
                            // v1.48f fix：删除后关闭指向该文件的标签，避免操作已删除文件
                            String path = file.getAbsolutePath();
                            for (int i = openTabs.size() - 1; i >= 0; i--) {
                                if (path.equals(openTabs.get(i).filePath)) {
                                    removeTab(i);
                                }
                            }
                            toast("已删除");
                        } else {
                            toast("删除失败");
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ================= 多标签管理 =================

    private void openFileInTab(String path) {
        if (path == null) return;
        // 已打开则切换
        for (int i = 0; i < openTabs.size(); i++) {
            if (path.equals(openTabs.get(i).filePath)) {
                switchToTab(i);
                return;
            }
        }
        try {
            String content = readFile(path);
            openTabs.add(new TabInfo(path, content));
            switchToTab(openTabs.size() - 1);
        } catch (Exception e) {
            toast("打开失败: " + e.getMessage());
        }
    }

    private void switchToTab(int index) {
        if (index < 0 || index >= openTabs.size()) return;
        // 保存当前标签内容 + 同步 dirty 标记（editorView.openFile 会重置 dirty，
        // 切出前必须把编辑器真实状态写回标签，否则脏标记丢失）（v1.48f fix）
        if (currentTab >= 0 && currentTab < openTabs.size() && editorView != null) {
            TabInfo cur = openTabs.get(currentTab);
            cur.content = editorView.getCode();
            cur.dirty = editorView.isDirty();
        }
        currentTab = index;
        TabInfo tab = openTabs.get(index);
        editorView.openFile(tab.filePath, tab.content);
        refreshTabBar();
        updateStatusBar();
    }

    private void closeTab(int index) {
        if (index < 0 || index >= openTabs.size()) return;
        if (openTabs.get(index).dirty) {
            final int idx = index;
            new AppleDialog.Builder(this)
                    .setTitle("未保存")
                    .setMessage(openTabs.get(index).name() + " 有未保存的修改，是否保存？")
                    .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            saveTab(idx);
                            removeTab(idx);
                        }
                    })
                    .setNegativeButton("不保存", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            removeTab(idx);
                        }
                    })
                    .setNeutralButton("取消", null)
                    .show();
        } else {
            removeTab(index);
        }
    }

    private void removeTab(int index) {
        openTabs.remove(index);
        if (currentTab == index) {
            currentTab = openTabs.size() > 0 ? 0 : -1;
            if (currentTab >= 0) {
                editorView.openFile(openTabs.get(0).filePath, openTabs.get(0).content);
            } else {
                editorView.openFile("", "");
            }
        } else if (currentTab > index) {
            currentTab--;
        }
        refreshTabBar();
    }

    private void refreshTabBar() {
        tabBar.removeAllViews();
        for (int i = 0; i < openTabs.size(); i++) {
            final int index = i;
            final TabInfo tab = openTabs.get(i);
            final boolean active = (i == currentTab);

            LinearLayout tabView = new LinearLayout(this);
            tabView.setOrientation(LinearLayout.HORIZONTAL);
            tabView.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(30));
            lp.setMargins(dp(2), 0, dp(2), 0);
            tabView.setLayoutParams(lp);
            tabView.setPadding(dp(8), 0, dp(6), 0);
            tabView.setBackgroundColor(active
                    ? Theme.BG_PRIMARY : Theme.BG_TERTIARY);

            TextView name = new TextView(this);
            name.setText(tab.name() + (tab.dirty ? " *" : ""));
            name.setTextColor(active
                    ? Theme.TEXT_PRIMARY : Theme.TEXT_SECOND);
            name.setTextSize(12);
            name.setSingleLine(true);
            tabView.addView(name);

            TextView close = new TextView(this);
            close.setText(" X");
            close.setTextColor(Theme.TEXT_SECOND);
            close.setTextSize(12);
            close.setPadding(dp(6), 0, 0, 0);
            close.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { closeTab(index); }
            });
            tabView.addView(close);

            tabView.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { switchToTab(index); }
            });

            tabBar.addView(tabView);
        }
        if (openTabs.size() == 0) {
            TextView hint = new TextView(this);
            hint.setText("未打开文件");
            hint.setTextColor(Theme.TEXT_TERTIARY);
            hint.setTextSize(12);
            hint.setPadding(dp(10), 0, dp(10), 0);
            tabBar.addView(hint);
        }
    }

    // ================= 语法错误检测（文档 4.2.3） =================

    private void scheduleErrorDetect() {
        mainHandler.removeCallbacks(detectRunnable);
        detectRunnable = new Runnable() {
            @Override public void run() { runErrorDetect(); }
        };
        mainHandler.postDelayed(detectRunnable, config.getDebounceMs());
    }

    /** 简易语法检测：括号配对、未闭合字符串等 */
    private void runErrorDetect() {
        if (editorView == null) return;
        String code = editorView.getCode();
        List<CodeEditorView.CodeError> errors = new ArrayList<CodeEditorView.CodeError>();

        // 括号配对检测（v1.48f fix：动态栈，不再受 1000 深度上限限制，大文件不再静默丢括号）
        java.util.ArrayList<java.lang.Integer> stackLine = new java.util.ArrayList<java.lang.Integer>();
        java.util.ArrayList<java.lang.Character> stackCh = new java.util.ArrayList<java.lang.Character>();
        int line = 1;
        boolean inString = false, inChar = false, inLineComment = false, inBlockComment = false;

        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '\n') {
                line++;
                inLineComment = false;
                continue;
            }
            if (inLineComment) continue;
            if (inBlockComment) {
                if (c == '*' && i + 1 < code.length() && code.charAt(i + 1) == '/') {
                    inBlockComment = false; i++;
                }
                continue;
            }
            if (inString) {
                if (c == '\\') { i++; continue; }
                if (c == '"') inString = false;
                continue;
            }
            if (inChar) {
                if (c == '\\') { i++; continue; }
                if (c == '\'') inChar = false;
                continue;
            }
            if (c == '/' && i + 1 < code.length() && code.charAt(i + 1) == '/') {
                inLineComment = true; i++; continue;
            }
            if (c == '/' && i + 1 < code.length() && code.charAt(i + 1) == '*') {
                inBlockComment = true; i++; continue;
            }
            if (c == '"') { inString = true; continue; }
            if (c == '\'') { inChar = true; continue; }

            if (c == '(' || c == '[' || c == '{') {
                stackCh.add(c);
                stackLine.add(line);
            } else if (c == ')' || c == ']' || c == '}') {
                char expect = c == ')' ? '(' : (c == ']' ? '[' : '{');
                int topSize = stackCh.size();
                if (topSize > 0 && stackCh.get(topSize - 1) == expect) {
                    stackCh.remove(topSize - 1);
                    stackLine.remove(topSize - 1);
                } else {
                    errors.add(new CodeEditorView.CodeError(line, 1,
                            Math.max(0, i - 1), Math.min(code.length(), i + 1),
                            "多余的 '" + c + "'", 0));
                }
            }
        }
        for (int k = 0; k < stackCh.size(); k++) {
            errors.add(new CodeEditorView.CodeError(stackLine.get(k), 1, 0, 1,
                    "未闭合的 '" + stackCh.get(k) + "'", 0));
        }
        if (inString) {
            errors.add(new CodeEditorView.CodeError(line, 1, 0, 1, "未闭合的字符串", 0));
        }

        editorView.setErrors(errors);

        // 文档 4.2.3 自动上报 + 9.1 自动发送错误给 AI
        if (errors.size() > 0 && config.isAutoSendErrorToAI()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < errors.size() && i < 5; i++) {
                sb.append(errors.get(i).message).append(" (行").append(errors.get(i).line).append(")\n");
            }
            chatPanel.notifyErrorPushed(sb.toString());
            AIService svc = null;
            try { svc = AIService.getInstance(); } catch (Throwable ignored) {}
            if (svc != null) {
                svc.onFileEditComplete(editorView.getFilePath(), code);
            }
        }
    }

    /** 弹窗4：代码错误提示悬浮弹窗（文档 5.2） */
    private void showErrorPopup(CodeEditorView.CodeError error) {
        if (error == null) return;
        AlertDialog.Builder b = new AppleDialog.Builder(this);
        b.setTitle(error.isError() ? "[错误] 语法问题" : "[警告] 代码提示");
        b.setMessage("行 " + error.line + " 列 " + error.col + "\n\n" + error.message);
        b.setPositiveButton("发送错误至AI", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int w) {
                chatPanel.requestSend("请帮我修复这个代码错误：\n"
                        + error.message + "\n\n相关代码：\n```\n"
                        + tailCode(editorView.getCode(), 1500) + "\n```");
                bottomMode = 0;
                showBottomContent();
            }
        });
        b.setNegativeButton("关闭", null);
        b.show();
    }

    private String tailCode(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : "..." + s.substring(s.length() - max);
    }

    // ================= 状态栏 =================

    private void updateStatusBar() {
        if (tvStatusProject == null) return;
        tvStatusProject.setText("项目: " + (projectPath.length() > 0
                ? new File(projectPath).getName() + " [" + projectType + "]" : "未打开"));

        String toolName = "无";
        try {
            if (activeToolId.length() > 0) {
                BuildToolManager.BuildTool t = toolManager.getToolById(activeToolId);
                if (t != null) toolName = t.name;
            }
        } catch (Throwable ignored) {}
        tvStatusTool.setText("工具: " + toolName);

        int mode = config.getModelMode();
        tvStatusModel.setText("模型: " + (mode == AppConfig.MODE_LOCAL ? "本地" : "云端"));
    }

    private void updateCursorStatus(int row, int col) {
        if (tvStatusCursor != null) tvStatusCursor.setText("行" + row + " 列" + col);
    }

    private void setStatusBarTip(String tip) {
        if (tvStatusProject != null) tvStatusProject.setText("状态: " + tip);
        mainHandler.postDelayed(new Runnable() {
            @Override public void run() { updateStatusBar(); }
        }, 3000);
    }

    private void toggleModelMode() {
        try {
            if (config.getModelMode() == AppConfig.MODE_LOCAL) {
                config.setModelMode(AppConfig.MODE_CLOUD);
                toast("已切换到云端模型");
            } else {
                LocalModelManager mgr = LocalModelManager.getInstance(this);
                if (mgr.hasLoadedModel()) {
                    config.setModelMode(AppConfig.MODE_LOCAL);
                    toast("已切换到本地模型（离线模式）");
                } else {
                    toast("请先在模型管理页面加载本地模型");
                    return;
                }
            }
            updateStatusBar();
            chatPanel.updateModeIndicator();
        } catch (Throwable t) {
            toast("切换失败");
        }
    }

    // ================= 初始化 =================

    private void initDefaultProject() {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            File proj = new File(dir, "MyProject");
            if (!proj.exists()) proj.mkdirs();

            // 创建示例文件
            File javaFile = new File(proj, "MainActivity.java");
            if (!javaFile.exists()) {
                String demo = "package com.example.myproject;\n\n"
                        + "import android.app.Activity;\n"
                        + "import android.os.Bundle;\n\n"
                        + "/** 示例：欢迎使用 AI Code Studio */\n"
                        + "public class MainActivity extends Activity {\n\n"
                        + "    @Override\n"
                        + "    protected void onCreate(Bundle savedInstanceState) {\n"
                        + "        super.onCreate(savedInstanceState);\n"
                        + "        // TODO: 在这里编写你的代码\n"
                        + "        String msg = \"Hello AI Code Studio\";\n"
                        + "    }\n"
                        + "}\n";
                java.io.FileWriter fw = new java.io.FileWriter(javaFile);
                fw.write(demo);
                fw.close();
            }
            openProject(proj.getAbsolutePath());
            openFileInTab(javaFile.getAbsolutePath());
        } catch (Throwable t) {
            android.util.Log.e("MainActivity", "init project failed", t);
        }
    }

    private String readFile(String path) {
        try {
            java.io.FileInputStream fis = new java.io.FileInputStream(path);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
            fis.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatusBar();
        if (chatPanel != null) chatPanel.updateModeIndicator();
    }

    // ================= 进程重建状态恢复（v1.48f fix） =================

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        try {
            outState.putString("projectPath", projectPath);
            outState.putInt("currentTab", currentTab);
            // 保存前把编辑器内容回写当前标签，保证恢复的是最新代码
            if (currentTab >= 0 && currentTab < openTabs.size() && editorView != null) {
                TabInfo cur = openTabs.get(currentTab);
                cur.content = editorView.getCode();
                cur.dirty = editorView.isDirty();
            }
            ArrayList<String> paths = new ArrayList<String>();
            ArrayList<String> contents = new ArrayList<String>();
            boolean[] dirty = new boolean[openTabs.size()];
            for (int i = 0; i < openTabs.size(); i++) {
                paths.add(openTabs.get(i).filePath);
                contents.add(openTabs.get(i).content);
                dirty[i] = openTabs.get(i).dirty;
            }
            outState.putStringArrayList("tabPaths", paths);
            outState.putStringArrayList("tabContents", contents);
            outState.putBooleanArray("tabDirty", dirty);
        } catch (Throwable t) {
            android.util.Log.e("MainActivity", "save state failed", t);
        }
    }

    /** 从 savedInstanceState 恢复项目与标签页（onCreate 中调用，重建后不丢标签） */
    private void restoreTabs(Bundle state) {
        try {
            String proj = state.getString("projectPath", "");
            if (proj.length() > 0 && new File(proj).isDirectory()) {
                projectPath = proj;
                projectType = detectProjectType(proj);
                fileTreeView.loadProject(proj);
                autoSelectTool();
            } else {
                return;
            }
            openTabs.clear();
            ArrayList<String> paths = state.getStringArrayList("tabPaths");
            ArrayList<String> contents = state.getStringArrayList("tabContents");
            boolean[] dirty = state.getBooleanArray("tabDirty");
            int cur = state.getInt("currentTab", -1);
            if (paths != null) {
                for (int i = 0; i < paths.size(); i++) {
                    TabInfo t = new TabInfo(paths.get(i),
                            contents != null && i < contents.size()
                                    ? contents.get(i) : "");
                    if (dirty != null && i < dirty.length) t.dirty = dirty[i];
                    openTabs.add(t);
                }
            }
            currentTab = (cur >= 0 && cur < openTabs.size()) ? cur : -1;
            if (currentTab >= 0 && editorView != null) {
                editorView.openFile(openTabs.get(currentTab).filePath,
                        openTabs.get(currentTab).content);
            }
            refreshTabBar();
            updateStatusBar();
        } catch (Throwable t) {
            android.util.Log.e("MainActivity", "restore state failed", t);
        }
    }

    private void toast(String msg) {
        // 统一委托 UiKit
        UiKit.toast(this, msg);
    }

    private int dp(int v) {
        // 统一委托 UiKit，实现全项目只保留一份
        return UiKit.dp(this, v);
    }
}
