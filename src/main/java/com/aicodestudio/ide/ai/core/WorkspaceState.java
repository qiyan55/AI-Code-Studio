package com.aicodestudio.ide.ai.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * 工作区共享状态（进程内单例）
 *
 * 三个工作区界面（对话 / 编辑器 / 编译运行）是独立的 Activity，
 * 通过这个单例共享：项目路径、当前打开的文件、编辑器内容、选中代码。
 *
 * 这样：
 *  - 编辑器界面保存的代码，对话界面能拿到并发送给 AI
 *  - AI 返回的代码，通过 pendingInsert 标记，编辑器界面回来后自动插入
 */
public class WorkspaceState {

    private static WorkspaceState instance;

    public static synchronized WorkspaceState get() {
        if (instance == null) instance = new WorkspaceState();
        return instance;
    }

    private WorkspaceState() {}

    // ===== 项目 =====
    private String projectPath = "";
    private String projectType = "custom";
    private String activeToolId = "";

    // ===== 当前文件 =====
    private String currentFilePath = "";
    private String currentCode = "";
    private boolean dirty = false;

    // ===== 选中代码 =====
    private String selectedCode = "";

    // ===== 待插入代码（AI 返回，等待编辑器界面消费）=====
    private String pendingInsert = "";

    // ===== 打开的文件（多标签）=====
    private final List<String> openFiles = new ArrayList<String>();

    // ===== 最近日志（编译界面写，对话界面读）=====
    private String lastCompileLog = "";

    // ===== 监听：编辑器内容被 AI 修改时通知界面刷新 =====
    public interface OnCodeChangedListener {
        void onCodeChanged(String newCode);
    }
    private OnCodeChangedListener codeChangedListener;

    public void setCodeChangedListener(OnCodeChangedListener l) {
        this.codeChangedListener = l;
    }
    public OnCodeChangedListener getCodeChangedListener() {
        return codeChangedListener;
    }

    // ===== 项目 =====
    public String getProjectPath() { return projectPath; }
    public void setProjectPath(String p) { this.projectPath = p == null ? "" : p; }

    public String getProjectType() { return projectType; }
    public void setProjectType(String t) { this.projectType = t == null ? "" : t; }

    public String getActiveToolId() { return activeToolId; }
    public void setActiveToolId(String id) { this.activeToolId = id == null ? "" : id; }

    public String getProjectName() {
        if (projectPath.length() == 0) return "未打开项目";
        return new File(projectPath).getName();
    }

    // ===== 当前文件 =====
    public String getCurrentFilePath() { return currentFilePath; }
    public String getCurrentFileName() {
        if (currentFilePath.length() == 0) return "未打开文件";
        return new File(currentFilePath).getName();
    }

    public String getCurrentCode() { return currentCode == null ? "" : currentCode; }

    public void setCurrentFile(String path, String code) {
        this.currentFilePath = path == null ? "" : path;
        this.currentCode = code == null ? "" : code;
        this.dirty = false;
        if (currentFilePath.length() > 0 && !openFiles.contains(currentFilePath)) {
            openFiles.add(currentFilePath);
        }
    }

    public void setCurrentCode(String code) {
        if (code == null) code = "";
        if (!code.equals(this.currentCode)) {
            this.currentCode = code;
            this.dirty = true;
        }
    }

    public boolean isDirty() { return dirty; }
    public void markSaved() { this.dirty = false; }

    // ===== 文件读写 =====
    public String readFile(String path) {
        try {
            File f = new File(path);
            if (!f.exists() || !f.isFile()) return "";
            FileInputStream fis = new FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int read = 0;
            while (read < buf.length) {
                int n = fis.read(buf, read, buf.length - read);
                if (n < 0) break;
                read += n;
            }
            fis.close();
            return new String(buf, 0, read, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    public boolean writeFile(String path, String content) {
        try {
            File f = new File(path);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            FileWriter fw = new FileWriter(f, false);
            fw.write(content == null ? "" : content);
            fw.flush();
            fw.close();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean saveCurrentFile() {
        if (currentFilePath.length() == 0) return false;
        boolean ok = writeFile(currentFilePath, currentCode);
        if (ok) dirty = false;
        return ok;
    }

    // ===== 选中代码 =====
    public String getSelectedCode() { return selectedCode == null ? "" : selectedCode; }
    public void setSelectedCode(String s) { this.selectedCode = s == null ? "" : s; }

    // ===== 待插入代码 =====
    public String takePendingInsert() {
        String s = pendingInsert;
        pendingInsert = "";
        return s == null ? "" : s;
    }
    public void setPendingInsert(String code) {
        this.pendingInsert = code == null ? "" : code;
    }
    public boolean hasPendingInsert() {
        return pendingInsert != null && pendingInsert.length() > 0;
    }

    // ===== 打开文件列表 =====
    public List<String> getOpenFiles() { return openFiles; }

    public void closeFile(String path) {
        openFiles.remove(path);
    }

    // ===== 编译日志 =====
    public String getLastCompileLog() { return lastCompileLog == null ? "" : lastCompileLog; }
    public void setLastCompileLog(String log) { this.lastCompileLog = log == null ? "" : log; }

    /** 识别项目类型 */
    public static String detectProjectType(String path) {
        try {
            File dir = new File(path);
            if (!dir.exists() || !dir.isDirectory()) return "custom";
            if (new File(dir, "build.gradle").exists()
                    || new File(dir, "build.gradle.kts").exists()
                    || new File(dir, "AndroidManifest.xml").exists()) return "Android";
            if (new File(dir, "pom.xml").exists()) return "Java";
            if (new File(dir, "CMakeLists.txt").exists()
                    || new File(dir, "Makefile").exists()) return "C++";
            if (new File(dir, "requirements.txt").exists()
                    || new File(dir, "setup.py").exists()) return "Python";
            if (new File(dir, "package.json").exists()) return "JavaScript";
        } catch (Throwable t) {
            // ignore
        }
        return "custom";
    }
}
