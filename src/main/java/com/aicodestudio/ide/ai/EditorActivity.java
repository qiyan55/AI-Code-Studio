package com.aicodestudio.ide.ai;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.aicodestudio.ide.ai.editor.CodeEditorView;
import com.aicodestudio.ide.ai.editor.FileTreeView;
import com.aicodestudio.ide.ai.remote.RemoteBridge;

import java.io.File;
import java.util.List;
import com.aicodestudio.ide.ai.ui.AppleDialog;

/**
 * 工作区 ②：代码编辑器界面
 *
 * 手机端独立编辑器工作区：
 *   顶部：菜单 / 项目名+文件名 / 文件树开关 / 新建文件 / 保存
 *   左侧：可折叠文件树（点击顶部文件树图标滑出/收起）
 *   右侧：代码编辑器（行号 + 语法高亮）
 */
public class EditorActivity extends BaseWorkspaceActivity {

    private FileTreeView fileTreeView;
    private CodeEditorView editorView;
    private TextView tabBar;

    private boolean treeVisible = true;
    private static final int TREE_WIDTH_DP = 150;
    private static final int TREE_MIN_WIDTH_DP = 80;
    private static final String PREFS_NAME = "editor_ui";
    private static final String PREFS_TREE_WIDTH = "tree_width_px";
    private float dragDownX = 0;
    private int dragStartWidth = 0;

    @Override
    protected int currentTab() { return TAB_EDITOR; }

    /**
     * 禁用滑动返回手势：代码编辑器内快速横向滑动（查看长代码）会被
     * 基类的 fling 检测误判为"返回主界面"。顶部已有 ic_home 按钮，足够。
     */
    @Override
    protected boolean swipeBackEnabled() { return false; }

    @Override
    protected View buildTopBar() {
        TopAction[] actions = new TopAction[]{
                new TopAction("ic_tree", 0, new View.OnClickListener() {
                    @Override public void onClick(View v) { toggleTree(); }
                }),
                new TopAction("ic_new_file", 0, new View.OnClickListener() {
                    @Override public void onClick(View v) { showNewFileDialog(); }
                }),
                new TopAction("ic_save", ACCENT_GREEN, new View.OnClickListener() {
                    @Override public void onClick(View v) { saveCurrentFile(); }
                }),
                new TopAction("ic_settings", 0, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        com.aicodestudio.ide.ai.ui.WorkspaceSettings
                                .showEditorSettings(EditorActivity.this);
                    }
                }),
        };

        return makeTopBar("代码编辑器", state().getProjectName(),
                "ic_home", new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        saveSilently();
                        goHome();
                        overridePendingTransition(0, 0);
                    }
                }, actions);
    }

    @Override
    protected void buildContent(LinearLayout container) {
        // 文件标签栏
        tabBar = new TextView(this);
        tabBar.setTextSize(11);
        tabBar.setTextColor(TEXT_SECOND);
        tabBar.setBackgroundColor(BG_SECONDARY);
        tabBar.setGravity(Gravity.CENTER_VERTICAL);
        tabBar.setSingleLine(true);
        tabBar.setPadding(dp(12), dp(6), dp(12), dp(6));
        tabBar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        container.addView(tabBar);

        // 主体：文件树 + 编辑器
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // --- 文件树 ---
        fileTreeView = new FileTreeView(this);
        // 恢复上次拖动的侧边栏宽度（未设置过则用默认 150dp）
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int treeWidthPx = prefs.getInt(PREFS_TREE_WIDTH, 0);
        if (treeWidthPx < dp(TREE_MIN_WIDTH_DP)) treeWidthPx = dp(TREE_WIDTH_DP);
        fileTreeView.setLayoutParams(new LinearLayout.LayoutParams(
                treeWidthPx, LinearLayout.LayoutParams.MATCH_PARENT));
        fileTreeView.setListener(new FileTreeView.FileTreeListener() {
            @Override
            public void onFileClick(File file) {
                if (file.isDirectory()) return;
                openFile(file);
            }

            @Override
            public void onFileLongClick(File file) {
                showFileMenu(file);
            }

            @Override
            public void onTreeChanged() {
                // 目录变化后刷新
            }
        });
        body.addView(fileTreeView);

        // 分隔线 + 拖动柄：按住左右拖动可调整侧边栏宽度
        // 柄加宽到 26dp，中央显示左右箭头（◄►），点击/按住整条即可拖动，灵活不误触
        FrameLayout dividerHandle = new FrameLayout(this);
        dividerHandle.setLayoutParams(new LinearLayout.LayoutParams(
                dp(26), LinearLayout.LayoutParams.MATCH_PARENT));
        dividerHandle.setBackgroundColor(0x11000000);
        View line = new View(this);
        line.setBackgroundColor(BORDER);
        line.setLayoutParams(new FrameLayout.LayoutParams(
                dp(1), FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER_HORIZONTAL));
        dividerHandle.addView(line);
        // 实心正三角形箭头（左◀ + 右▶）：自绘，比字体符号更醒目，垂直拉高
        View arrow = new View(this) {
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                int w = getWidth(), h = getHeight();
                if (w <= 0 || h <= 0) return;
                int L = Math.max(8, (int) (h * 0.40));      // 三角形边长（底边竖直长度）
                int depth = (int) (L * 0.866f);              // 等边三角形高 ≈ 0.866L
                int gap = (int) (L * 0.5f);
                int totalW = depth * 2 + gap;
                int cx = w / 2, cy = h / 2;
                Paint p = new Paint();
                p.setColor(0x88FFFFFF);
                p.setStyle(Paint.Style.FILL);
                p.setAntiAlias(true);
                // 左三角：尖朝左
                Path pl = new Path();
                pl.moveTo(cx - totalW / 2f, cy);
                pl.lineTo(cx - totalW / 2f + depth, cy - L / 2f);
                pl.lineTo(cx - totalW / 2f + depth, cy + L / 2f);
                pl.close();
                canvas.drawPath(pl, p);
                // 右三角：尖朝右
                Path pr = new Path();
                pr.moveTo(cx + totalW / 2f, cy);
                pr.lineTo(cx + totalW / 2f - depth, cy - L / 2f);
                pr.lineTo(cx + totalW / 2f - depth, cy + L / 2f);
                pr.close();
                canvas.drawPath(pr, p);
            }
        };
        arrow.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        dividerHandle.addView(arrow);
        dividerHandle.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dragDownX = event.getRawX();
                        dragStartWidth = fileTreeView.getWidth();
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int delta = (int) (event.getRawX() - dragDownX);
                        int newW = dragStartWidth + delta;
                        int min = dp(TREE_MIN_WIDTH_DP);
                        int max = getResources().getDisplayMetrics().widthPixels * 3 / 5;
                        if (newW < min) newW = min;
                        if (newW > max) newW = max;
                        if (newW != fileTreeView.getWidth()) {
                            LinearLayout.LayoutParams lp =
                                    (LinearLayout.LayoutParams) fileTreeView.getLayoutParams();
                            lp.width = newW;
                            fileTreeView.setLayoutParams(lp);
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                                .edit().putInt(PREFS_TREE_WIDTH, fileTreeView.getWidth())
                                .apply();
                        return true;
                }
                return false;
            }
        });
        body.addView(dividerHandle);

        // --- 编辑器 ---
        editorView = new CodeEditorView(this);
        editorView.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        editorView.setEditorListener(new CodeEditorView.EditorListener() {
            @Override
            public void onTextChanged(String code) {
                state().setCurrentCode(code);
            }

            @Override
            public void onCursorChanged(int row, int col) {
                // 光标位置：手机端从简，在标签栏体现
            }

            @Override
            public void onErrorClick(CodeEditorView.CodeError error) {
                if (error == null) return;
                String msg = error.message;
                if (msg == null || msg.length() == 0) msg = "第 " + error.line + " 行存在问题";
                new AppleDialog.Builder(EditorActivity.this)
                        .setTitle("代码问题")
                        .setMessage(msg)
                        .setPositiveButton("发给 AI", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                String sel = state().getSelectedCode();
                                if (sel.length() == 0) sel = state().getCurrentCode();
                                state().setSelectedCode(sel);
                                switchToTab(TAB_CHAT);
                            }
                        })
                        .setNegativeButton("关闭", null)
                        .show();
            }

            @Override
            public void onModifyStateChanged(boolean dirty) {
                if (tabBar != null) {
                    tabBar.setText(dirty
                            ? dirtyLabel(state().getCurrentFileName())
                            : state().getCurrentFileName());
                }
            }
        });
        body.addView(editorView);

        container.addView(body);

        // 载入项目与上次打开的文件
        restoreState();

        // === RemoteConnector：注册编辑器到远程桥接 ===
        final RemoteBridge.EditorOps ops = new RemoteBridge.EditorOps() {
            @Override public boolean remoteOpenFile(String relPath) {
                try {
                    String proj = state().getProjectPath();
                    if (proj == null || proj.length() == 0) return false;
                    File f = new File(proj, relPath);
                    if (!f.exists()) return false;
                    byte[] data = java.nio.file.Files.readAllBytes(f.toPath());
                    String content = new String(data, "UTF-8");
                    editorView.openFile(f.getAbsolutePath(), content);
                    state().setCurrentFile(f.getAbsolutePath(), content);
                    return true;
                } catch (Throwable t) { return false; }
            }
            @Override public void remoteSetContent(String content) {
                if (editorView != null) {
                    String path = editorView.getFilePath();
                    if (path != null && !path.isEmpty()) {
                        editorView.openFile(path, content);
                        state().setCurrentFile(path, content);
                    }
                }
            }
            @Override public String remoteGetContent() {
                return editorView != null ? editorView.getCode() : "";
            }
            @Override public boolean remoteSave() {
                saveCurrentFile();
                return true;
            }
            @Override public String remoteGetCurrentPath() {
                return editorView != null ? editorView.getFilePath() : "";
            }
            @Override public String remoteGetProjectPath() { return state().getProjectPath(); }
        };
        RemoteBridge.get().attachEditor(ops);
        editorOps = ops;
    }

    private RemoteBridge.EditorOps editorOps;

    @Override protected void onDestroy() {
        if (editorOps != null) RemoteBridge.get().detachEditor(editorOps);
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 同步选中代码，供对话界面「发送选中代码」使用
        try {
            if (editorView != null) {
                state().setSelectedCode(editorView.getSelection());
            }
        } catch (Throwable t) {
            android.util.Log.e("EditorActivity", "sync selection failed", t);
        }
        saveSilently();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyPendingInsert();
        restoreState();
    }

    // ================= 状态恢复 =================

    private void restoreState() {
        String proj = state().getProjectPath();
        if (proj.length() == 0) {
            // 未打开项目，加载默认示例项目
            initDefaultProject();
            return;
        }
        fileTreeView.loadProject(proj);

        String file = state().getCurrentFilePath();
        if (file.length() > 0 && new File(file).exists()) {
            String code = state().getCurrentCode();
            editorView.openFile(file, code);
            String lang = CodeEditorView.detectLanguage(file);
            String n = state().getCurrentFileName();
            tabBar.setText(state().isDirty() ? dirtyLabel(n) : n);
            tabBar.append("    " + lang.toUpperCase());
            tabBar.append("    选中代码可直接到对话界面询问 AI");
        } else {
            tabBar.setText("未打开文件 · 点左侧文件树选择 · 长按文件可新建/重命名/删除");
        }
    }


    /**
     * 未保存标记：用矢量圆点图标（ImageSpan）代替 ● 字符，
     * 字体变化时不会错位，也跟其他图标风格统一。
     */
    private CharSequence dirtyLabel(String name) {
        try {
            android.graphics.drawable.Drawable dot = icon("ic_dot", ACCENT_ORANGE);
            if (dot == null) return name + " 未保存";
            int size = (int) (tabBar.getTextSize() * 0.62f);
            dot.setBounds(0, 0, size, size);
            android.text.SpannableStringBuilder sb =
                    new android.text.SpannableStringBuilder();
            sb.append(name).append(" ");
            sb.append("　", new android.text.style.ImageSpan(
                    dot, android.text.style.ImageSpan.ALIGN_BASELINE), 0);
            sb.append(" 未保存");
            return sb;
        } catch (Throwable t) {
            return name + " 未保存";
        }
    }

    private void initDefaultProject() {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            File proj = new File(dir, "MyProject");
            if (!proj.exists()) proj.mkdirs();

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
                state().writeFile(javaFile.getAbsolutePath(), demo);
            }

            state().setProjectPath(proj.getAbsolutePath());
            state().setProjectType("Android");
            fileTreeView.loadProject(proj.getAbsolutePath());
            openFile(javaFile);
        } catch (Throwable t) {
            android.util.Log.e("EditorActivity", "init project failed", t);
        }
    }

    // ================= 文件操作 =================

    private void openFile(File file) {
        String code = state().readFile(file.getAbsolutePath());
        state().setCurrentFile(file.getAbsolutePath(), code);
        editorView.openFile(file.getAbsolutePath(), code);

        String lang = CodeEditorView.detectLanguage(file.getName());
        tabBar.setText(file.getName() + "    " + lang.toUpperCase()
                + "    选中代码可直接到对话界面询问 AI");
    }

    /** 应用从 AI 对话界面带回来的待插入代码 */
    private void applyPendingInsert() {
        if (!state().hasPendingInsert()) return;
        String code = state().takePendingInsert();
        if (editorView == null) return;

        String cur = editorView.getCode();
        if (cur.length() > 0 && !cur.endsWith("\n")) code = "\n" + code;
        editorView.insertCode(code, -1);
        state().setCurrentCode(editorView.getCode());
        toast("AI 生成的代码已插入");
    }

    private void saveCurrentFile() {
        if (editorView == null) return;
        String path = editorView.getFilePath();
        if (path == null || path.length() == 0) {
            toast("没有打开的文件");
            return;
        }
        state().setCurrentFile(path, editorView.getCode());
        boolean ok = state().saveCurrentFile();
        if (ok) {
            editorView.markSaved();
            toast("已保存 " + new File(path).getName());
            if (tabBar != null) {
                tabBar.setText(state().getCurrentFileName() + "    已保存");
            }
        } else {
            toast("保存失败");
        }
    }

    /** 静默保存（切界面/退出时自动调用） */
    private void saveSilently() {
        try {
            if (editorView == null || !state().isDirty()) return;
            String path = editorView.getFilePath();
            if (path == null || path.length() == 0) return;
            state().setCurrentFile(path, editorView.getCode());
            state().saveCurrentFile();
        } catch (Throwable t) {
            android.util.Log.e("EditorActivity", "auto save failed", t);
        }
    }

    // ================= 交互 =================

    private void toggleTree() {
        treeVisible = !treeVisible;
        fileTreeView.setVisibility(treeVisible ? View.VISIBLE : View.GONE);
    }

    private void showNewFileDialog() {
        String proj = state().getProjectPath();
        if (proj.length() == 0) {
            toast("请先打开项目");
            return;
        }

        final EditText et = new EditText(this);
        et.setHint("文件名，如 Utils.java");
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setTextSize(14);
        et.setTextColor(TEXT_PRIMARY);
        et.setHintTextColor(TEXT_TERTIARY);
        et.setSingleLine(true);
        et.setPadding(dp(16), dp(12), dp(16), dp(12));

        new AppleDialog.Builder(this)
                .setTitle("新建文件")
                .setView(et)
                .setPositiveButton("创建", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String name = et.getText().toString().trim();
                        if (name.length() == 0) return;
                        File f = new File(state().getProjectPath(), name);
                        if (f.exists()) {
                            toast("文件已存在");
                            return;
                        }
                        if (state().writeFile(f.getAbsolutePath(), "")) {
                            fileTreeView.refresh();
                            openFile(f);
                        } else {
                            toast("创建失败");
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showFileMenu(final File file) {
        String[] items = {"打开", "重命名", "删除", "新建文件(同级)", "新建文件夹"};
        new AppleDialog.Builder(this)
                .setTitle(file.getName())
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        switch (which) {
                            case 0:
                                if (!file.isDirectory()) openFile(file);
                                break;
                            case 1: renameFile(file); break;
                            case 2: deleteFile(file); break;
                            case 3: newFileIn(file.isDirectory() ? file : file.getParentFile()); break;
                            case 4: newDirIn(file.isDirectory() ? file : file.getParentFile()); break;
                        }
                    }
                })
                .show();
    }

    private void renameFile(final File file) {
        final EditText et = new EditText(this);
        et.setText(file.getName());
        et.setTextSize(14);
        et.setTextColor(TEXT_PRIMARY);
        et.setSingleLine(true);
        et.setPadding(dp(16), dp(12), dp(16), dp(12));

        new AppleDialog.Builder(this)
                .setTitle("重命名")
                .setView(et)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String name = et.getText().toString().trim();
                        if (name.length() == 0) return;
                        File dest = new File(file.getParentFile(), name);
                        if (file.renameTo(dest)) {
                            fileTreeView.refresh();
                            // v1.48f fix：重命名的是当前打开的文件时同步编辑器与 state，
                            // 否则保存会写回旧路径（文件丢失）或编辑器显示失效路径
                            String oldPath = file.getAbsolutePath();
                            if (oldPath.equals(state().getCurrentFilePath())) {
                                String code = editorView != null
                                        ? editorView.getCode()
                                        : state().getCurrentCode();
                                state().setCurrentFile(dest.getAbsolutePath(), code);
                                if (editorView != null) {
                                    editorView.openFile(dest.getAbsolutePath(), code);
                                }
                                tabBar.setText(dest.getName() + "    已重命名");
                            }
                            toast("已重命名");
                        } else {
                            toast("重命名失败");
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteFile(final File file) {
        new AppleDialog.Builder(this)
                .setTitle("删除")
                .setMessage("确定删除 " + file.getName() + " ？")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        deleteRecursive(file);
                        fileTreeView.refresh();
                        // v1.48f fix：删除的是当前打开的文件时清空编辑器与 state，
                        // 否则保存会自动重建该文件，与"删除"意图矛盾
                        String path = file.getAbsolutePath();
                        if (path.equals(state().getCurrentFilePath())) {
                            state().setCurrentFile("", "");
                            if (editorView != null) editorView.openFile("", "");
                            tabBar.setText("文件已删除 · 点左侧文件树选择");
                        }
                        toast("已删除");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        f.delete();
    }

    private void newFileIn(final File dir) {
        if (dir == null) return;
        final EditText et = new EditText(this);
        et.setHint("文件名");
        et.setTextSize(14);
        et.setTextColor(TEXT_PRIMARY);
        et.setHintTextColor(TEXT_TERTIARY);
        et.setSingleLine(true);
        et.setPadding(dp(16), dp(12), dp(16), dp(12));

        new AppleDialog.Builder(this)
                .setTitle("在 " + dir.getName() + " 中新建文件")
                .setView(et)
                .setPositiveButton("创建", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String name = et.getText().toString().trim();
                        if (name.length() == 0) return;
                        File f = new File(dir, name);
                        if (state().writeFile(f.getAbsolutePath(), "")) {
                            fileTreeView.refresh();
                            openFile(f);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void newDirIn(final File dir) {
        if (dir == null) return;
        final EditText et = new EditText(this);
        et.setHint("文件夹名");
        et.setTextSize(14);
        et.setTextColor(TEXT_PRIMARY);
        et.setHintTextColor(TEXT_TERTIARY);
        et.setSingleLine(true);
        et.setPadding(dp(16), dp(12), dp(16), dp(12));

        new AppleDialog.Builder(this)
                .setTitle("在 " + dir.getName() + " 中新建文件夹")
                .setView(et)
                .setPositiveButton("创建", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String name = et.getText().toString().trim();
                        if (name.length() == 0) return;
                        File f = new File(dir, name);
                        if (f.mkdirs()) {
                            fileTreeView.refresh();
                        } else {
                            toast("创建失败");
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
