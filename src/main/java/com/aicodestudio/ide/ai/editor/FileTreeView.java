package com.aicodestudio.ide.ai.editor;

import com.aicodestudio.ide.ai.ui.Theme;
import com.aicodestudio.ide.ai.ui.UiKit;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 项目文件树面板
 * 实现文档 5.2 页面1-区域2 左侧项目树：
 * - 展示项目目录结构
 * - 长按菜单：新建文件、删除、重命名、在编辑器打开
 * - 文件夹可折叠/展开
 *
 * 纯 Android SDK 实现。
 */
public class FileTreeView extends ScrollView {

    private LinearLayout container;
    private String rootPath = "";
    private FileTreeListener listener;
    private Context ctx;

    /** 已展开目录的绝对路径集合（render 全量重建时恢复展开状态） */
    private final java.util.Set<String> expandedPaths = new java.util.HashSet<>();

    /** 树节点 */
    public static class TreeNode {
        public File file;
        public int depth;
        public boolean expanded = false;
        public TreeNode(File f, int d) { file = f; depth = d; }
        public boolean isDir() { return file != null && file.isDirectory(); }
        public String name() { return file == null ? "" : file.getName(); }
    }

    public interface FileTreeListener {
        void onFileClick(File file);
        void onFileLongClick(File file);
        void onTreeChanged();
    }

    public FileTreeView(Context c) {
        super(c);
        init(c);
    }

    public FileTreeView(Context c, AttributeSet attrs) {
        super(c, attrs);
        init(c);
    }

    private void init(Context c) {
        this.ctx = c;
        setBackgroundColor(Theme.BG_PRIMARY);
        container = new LinearLayout(c);
        container.setOrientation(LinearLayout.VERTICAL);
        // 宽度 WRAP：长文件名可撑宽内容，由 HorizontalScrollView 横向拖动查看完整名称
        container.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // 外层 ScrollView（纵向滚动）内套 HorizontalScrollView（横向拖动）
        HorizontalScrollView hsv = new HorizontalScrollView(c);
        hsv.setHorizontalScrollBarEnabled(true);
        hsv.setFillViewport(false);
        hsv.setLayoutParams(new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        hsv.addView(container);
        addView(hsv);
    }

    public void setListener(FileTreeListener l) { this.listener = l; }

    public String getRootPath() { return rootPath; }

    /**
     * 加载项目根目录
     */
    public void loadProject(String path) {
        this.rootPath = path;
        // 切换项目时清空展开状态，并默认展开根目录第一层（用户可自行折叠）
        expandedPaths.clear();
        if (path != null && path.length() > 0) {
            expandedPaths.add(path);
        }
        render();
    }

    public void refresh() { render(); }

    private void render() {
        container.removeAllViews();
        if (rootPath == null || rootPath.length() == 0) {
            addHint("未打开项目");
            return;
        }
        File root = new File(rootPath);
        if (!root.exists()) {
            addHint("项目目录不存在");
            return;
        }

        // 项目名标题
        TextView title = new TextView(ctx);
        title.setText(root.getName());
        title.setTextColor(Theme.TEXT_PRIMARY);
        title.setTextSize(14);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(dp(12), dp(12), dp(12), dp(8));
        title.setBackgroundColor(Theme.BG_SECONDARY);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        container.addView(title);

        addNodes(root, 0);
    }

    /**
     * 渲染目录下的节点（迭代实现，用显式任务栈模拟深度优先前序遍历）
     * 避免递归渲染在目录层级很深时导致栈溢出。
     * 渲染顺序与原递归版本完全一致：父节点在前，展开目录的子节点紧随其后（深度优先），同层按排序顺序。
     *
     * 任务类型：
     * - TYPE_DIR  (0)：处理目录内容，列出文件后逆序压入子节点渲染任务
     * - TYPE_NODE (1)：渲染一个节点，若是展开目录则压入其子目录处理任务
     */
    private void addNodes(File dir, int depth) {
        final int TYPE_DIR = 0, TYPE_NODE = 1;
        java.util.ArrayDeque<Object[]> stack = new java.util.ArrayDeque<>();
        stack.push(new Object[]{TYPE_DIR, dir, depth});

        while (!stack.isEmpty()) {
            Object[] cur = stack.pop();
            int type = (Integer) cur[0];

            if (type == TYPE_DIR) {
                // 处理目录内容
                File d = (File) cur[1];
                int dth = (Integer) cur[2];

                File[] files = d.listFiles();
                if (files == null || files.length == 0) {
                    if (dth == 0) addHint("空目录");
                    continue;
                }

                // 排序：目录在前，其次按名称
                List<File> list = new ArrayList<File>(Arrays.asList(files));
                java.util.Collections.sort(list, new Comparator<File>() {
                    @Override
                    public int compare(File a, File b) {
                        boolean da = a.isDirectory(), db = b.isDirectory();
                        if (da && !db) return -1;
                        if (!da && db) return 1;
                        return a.getName().compareToIgnoreCase(b.getName());
                    }
                });

                // 栈是 LIFO：逆序压入渲染任务，弹出顺序才为正序（f1, f2, f3...）
                for (int i = list.size() - 1; i >= 0; i--) {
                    File f = list.get(i);
                    // 跳过隐藏文件和构建目录
                    String n = f.getName();
                    if (n.startsWith(".") || n.equals("build") || n.equals(".gradle")) continue;
                    stack.push(new Object[]{TYPE_NODE, f, dth});
                }
            } else {
                // 渲染节点
                File f = (File) cur[1];
                int dth = (Integer) cur[2];

                final TreeNode node = new TreeNode(f, dth);
                // 从展开状态集合恢复：render 重建后仍保持用户之前展开/折叠的选择
                node.expanded = f.isDirectory() && expandedPaths.contains(f.getAbsolutePath());
                container.addView(buildNodeView(node));

                // 展开目录：压入子目录处理任务，栈顶优先处理 → 先父后子（深度优先）
                if (f.isDirectory() && node.expanded) {
                    stack.push(new Object[]{TYPE_DIR, f, dth + 1});
                }
            }
        }
    }

    private View buildNodeView(final TreeNode node) {
        final File f = node.file;
        final boolean isDir = f.isDirectory();

        LinearLayout row = new LinearLayout(ctx);
        // 宽度 WRAP：文件名长时行可撑宽，配合 HorizontalScrollView 横向拖动查看
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)));
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8) + node.depth * dp(16), 0, dp(8), 0);

        // 图标（矢量图标，非 emoji）
        int iconColor = isDir ? Theme.ACCENT_ORANGE : getFileIconColor(f.getName());
        android.widget.ImageView icon = new android.widget.ImageView(ctx);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(28), dp(36)));
        icon.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        android.graphics.drawable.Drawable d = loadIcon(
                isDir ? (node.expanded ? "ic_folder_open" : "ic_folder")
                      : getFileIcon(f.getName()));
        if (d != null) {
            d = d.mutate();
            d.setTint(iconColor);
            icon.setImageDrawable(d);
        }
        row.addView(icon);

        // 文件名（WRAP：不压缩不换行，超长时撑宽行以便横向拖动查看）
        TextView name = new TextView(ctx);
        name.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        name.setText(f.getName());
        name.setTextColor(isDir
                ? Theme.TEXT_PRIMARY : Theme.ICON_IDLE);
        name.setTextSize(13);
        name.setSingleLine(true);
        row.addView(name);

        row.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isDir) {
                    // 持久化展开/折叠状态：点击后刷新，render 重建时据此恢复
                    String p = f.getAbsolutePath();
                    if (expandedPaths.contains(p)) {
                        expandedPaths.remove(p);
                    } else {
                        expandedPaths.add(p);
                    }
                    render();
                } else {
                    if (listener != null) listener.onFileClick(f);
                }
            }
        });

        row.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (listener != null) listener.onFileLongClick(f);
                return true;
            }
        });

        return row;
    }

    /** 返回文件类型对应的矢量图标资源名 */
    private String getFileIcon(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".java")) return "ic_code";
        if (n.endsWith(".kt")) return "ic_code";
        if (n.endsWith(".xml")) return "ic_code";
        if (n.endsWith(".gradle") || n.endsWith(".gradle.kts")) return "ic_build";
        if (n.endsWith(".py")) return "ic_terminal";
        if (n.endsWith(".cpp") || n.endsWith(".c") || n.endsWith(".h")) return "ic_code";
        if (n.endsWith(".json")) return "ic_code";
        if (n.endsWith(".md")) return "ic_file";
        if (n.endsWith(".sh")) return "ic_terminal";
        return "ic_file";
    }

    /** 按资源名加载矢量图标 */
    private android.graphics.drawable.Drawable loadIcon(String name) {
        try {
            int id = ctx.getResources().getIdentifier(name, "drawable", ctx.getPackageName());
            if (id == 0) return null;
            return ctx.getResources().getDrawable(id);
        } catch (Throwable t) {
            return null;
        }
    }

    private int getFileIconColor(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".java") || n.endsWith(".kt")) return Theme.FILE_JAVA;
        if (n.endsWith(".xml")) return Theme.ACCENT_ORANGE;
        if (n.endsWith(".gradle") || n.endsWith(".gradle.kts")) return Theme.FILE_GRADLE;
        if (n.endsWith(".py")) return Theme.FILE_PYTHON;
        if (n.endsWith(".cpp") || n.endsWith(".c") || n.endsWith(".h"))
            return Theme.FILE_XML;
        if (n.endsWith(".json")) return Theme.CLOSE_RED;
        return Theme.TEXT_SECOND;
    }

    private void addHint(String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(Theme.TEXT_TERTIARY);
        tv.setTextSize(12);
        tv.setPadding(dp(16), dp(16), dp(16), dp(16));
        tv.setGravity(Gravity.CENTER);
        container.addView(tv);
    }

    /**
     * 新建文件（文档 5.2 右键菜单：新建文件）
     */
    public boolean createFile(File parentDir, String name) {
        try {
            if (parentDir == null || !parentDir.exists()) return false;
            File nf = new File(parentDir, name);
            if (nf.exists()) return false;
            boolean ok = nf.createNewFile();
            if (ok) render();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 新建文件夹
     */
    public boolean createDir(File parentDir, String name) {
        try {
            if (parentDir == null) return false;
            File nd = new File(parentDir, name);
            if (nd.exists()) return false;
            boolean ok = nd.mkdirs();
            if (ok) render();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 删除文件/目录（文档 5.2 右键菜单：删除）
     */
    public boolean deleteFile(File f) {
        try {
            if (f == null || !f.exists()) return false;
            boolean ok = f.isDirectory() ? deleteRecursive(f) : f.delete();
            if (ok) render();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        return f.delete();
    }

    /**
     * 重命名（文档 5.2 右键菜单：重命名）
     */
    public boolean renameFile(File f, String newName) {
        try {
            if (f == null || !f.exists()) return false;
            File nf = new File(f.getParentFile(), newName);
            if (nf.exists()) return false;
            boolean ok = f.renameTo(nf);
            if (ok) render();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    public void showToast(String msg) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        // 统一委托 UiKit，实现全项目只保留一份
        return UiKit.dp(ctx, v);
    }
}
