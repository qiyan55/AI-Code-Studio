package com.aicodestudio.ide.ai.core;

import android.content.Context;
import android.text.TextUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 会话管理器（v1.90：SQLite 持久化）
 *
 * 数据存储：
 * - sessions 表：会话元数据
 * - messages 表：聊天消息（含 AI 完整思考过程）
 * - kv_cache 表：KV 缓存文件路径
 *
 * 二进制 KV 缓存文件仍存文件系统，这里只记路径。
 */
public class SessionManager {

    private static SessionManager instance;
    private Context appCtx;
    private DatabaseHelper db;
    private List<ProjectSession> sessions = new ArrayList<ProjectSession>();

    public static synchronized SessionManager get(Context ctx) {
        if (instance == null) {
            instance = new SessionManager();
            instance.appCtx = ctx.getApplicationContext();
            instance.db = new DatabaseHelper(instance.appCtx);
            instance.reload();
        }
        return instance;
    }

    /** v1.90：如果已初始化则返回实例，否则返回 null（避免在非 UI 上下文误初始化） */
    public static synchronized SessionManager getInstanceIfExists() {
        return instance;
    }

    /** 重新从数据库加载会话列表 */
    public void reload() {
        sessions = db.getAllSessions();
        sortByLastOpened();
    }

    private void sortByLastOpened() {
        Collections.sort(sessions, new Comparator<ProjectSession>() {
            @Override
            public int compare(ProjectSession a, ProjectSession b) {
                return Long.compare(b.lastOpenedAt, a.lastOpenedAt);
            }
        });
    }

    // ===== 查询 =====

    public List<ProjectSession> getProjectSessions() {
        List<ProjectSession> list = new ArrayList<ProjectSession>();
        for (ProjectSession s : sessions) {
            if (s.isBoundProject()) list.add(s);
        }
        return list;
    }

    public List<ProjectSession> getBlankChatSessions() {
        List<ProjectSession> list = new ArrayList<ProjectSession>();
        for (ProjectSession s : sessions) {
            if (!s.isBoundProject()) list.add(s);
        }
        return list;
    }

    public ProjectSession getSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return null;
        for (ProjectSession s : sessions) {
            if (sessionId.equals(s.sessionId)) return s;
        }
        return null;
    }

    public ProjectSession getSessionByProjectPath(String projectPath) {
        if (TextUtils.isEmpty(projectPath)) return null;
        for (ProjectSession s : sessions) {
            if (projectPath.equals(s.projectPath)) return s;
        }
        return null;
    }

    /** 找最近的空白对话（isBlank=true 且未绑定项目） */
    public ProjectSession getLatestBlankSession() {
        for (ProjectSession s : sessions) {
            if (!s.isBoundProject() && s.isBlank) return s;
        }
        return null;
    }

    // ===== 新增 =====

    public ProjectSession createBlankSession() {
        ProjectSession s = new ProjectSession();
        s.sessionId = UUID.randomUUID().toString();
        s.title = "新对话";
        s.projectPath = "";
        s.preview = "";
        s.isBlank = true;
        s.messageCount = 0;
        s.createdAt = System.currentTimeMillis();
        s.lastOpenedAt = s.createdAt;
        db.insertSession(s);
        sessions.add(s);
        sortByLastOpened();
        return s;
    }

    public ProjectSession createProjectSession(String projectPath, String title) {
        ProjectSession existing = getSessionByProjectPath(projectPath);
        if (existing != null) return existing;

        ProjectSession s = new ProjectSession();
        s.sessionId = UUID.randomUUID().toString();
        s.title = title != null ? title : new File(projectPath).getName();
        s.projectPath = projectPath;
        s.preview = "";
        s.isBlank = false;
        s.messageCount = 0;
        s.createdAt = System.currentTimeMillis();
        s.lastOpenedAt = s.createdAt;
        db.insertSession(s);
        sessions.add(s);
        sortByLastOpened();
        return s;
    }

    public ProjectSession bindSessionToProject(String sessionId, String projectPath, String title) {
        ProjectSession s = getSession(sessionId);
        if (s == null) return null;
        s.projectPath = projectPath;
        s.title = title != null ? title : new File(projectPath).getName();
        s.isBlank = false;
        s.lastOpenedAt = System.currentTimeMillis();
        db.updateSession(s);
        sortByLastOpened();
        return s;
    }

    // ===== 修改 =====

    public void touchSession(String sessionId) {
        ProjectSession s = getSession(sessionId);
        if (s != null) {
            s.lastOpenedAt = System.currentTimeMillis();
            db.updateSession(s);
            sortByLastOpened();
        }
    }

    public void updateTitle(String sessionId, String title) {
        ProjectSession s = getSession(sessionId);
        if (s != null) {
            s.title = title;
            db.updateSession(s);
        }
    }

    /** 用户发首条消息后标记会话不再空白，更新标题/预览/消息数 */
    public void markSessionAsActive(String sessionId, String firstUserText, int totalMessages) {
        ProjectSession s = getSession(sessionId);
        if (s == null) return;
        s.isBlank = false;
        s.messageCount = totalMessages;
        String preview = firstUserText == null ? "" : firstUserText.trim().replace('\n', ' ');
        if (preview.length() > 28) preview = preview.substring(0, 28) + "…";
        s.preview = preview;
        if (!s.isBoundProject() && (s.title == null || s.title.isEmpty() || "新对话".equals(s.title))) {
            String t = firstUserText == null ? "" : firstUserText.trim().replace('\n', ' ');
            if (t.length() > 16) t = t.substring(0, 16) + "…";
            if (!t.isEmpty()) s.title = t;
        }
        s.lastOpenedAt = System.currentTimeMillis();
        db.updateSession(s);
        sortByLastOpened();
    }

    public void updateMessageCount(String sessionId, int count) {
        ProjectSession s = getSession(sessionId);
        if (s != null) {
            s.messageCount = count;
            db.updateSession(s);
        }
    }

    /** v1.139：更新会话模式（0=闲聊 / 1=工作任务），对话列表徽标与切会话恢复用 */
    public void updateChatMode(String sessionId, int mode) {
        ProjectSession s = getSession(sessionId);
        if (s != null) {
            s.chatMode = mode;
            db.updateSession(s);
        }
    }

    // ===== 删除 =====

    public boolean deleteSession(String sessionId) {
        ProjectSession s = getSession(sessionId);
        if (s == null) return false;
        sessions.remove(s);
        db.deleteSession(sessionId);
        return true;
    }

    // ===== 消息读写（代理给 DatabaseHelper）=====

    public void saveMessages(String sessionId, List<Integer> types, List<String> texts) {
        List<int[]> arr = new ArrayList<int[]>();
        for (int t : types) arr.add(new int[]{t});
        db.replaceMessages(sessionId, arr, texts);
    }

    public List<Object[]> loadMessages(String sessionId) {
        return db.getMessages(sessionId);
    }

    // ===== KV 缓存元数据 =====

    public void saveKvCachePath(String sessionId, String path, long sizeBytes) {
        db.updateKvCache(sessionId, path, sizeBytes);
    }

    public String getKvCachePath(String sessionId) {
        return db.getKvCachePath(sessionId);
    }

    // ===== 空白对话判断 =====

    public boolean isBlankSessionEmpty(String sessionId) {
        ProjectSession s = getSession(sessionId);
        if (s == null) return true;
        if (s.isBoundProject()) return false;
        if (!s.isBlank) return false;
        // v1.153al：双保险——标志位之外再实查 SQLite 消息数。
        // 若某路径发消息后 isBlank 未及时置 false，这里能拦住，
        // 避免"新建对话"复用一个其实有内容的旧会话、把历史内容搬进新对话。
        try {
            List<Object[]> msgs = loadMessages(sessionId);
            return msgs == null || msgs.isEmpty();
        } catch (Throwable ignored) {
            return true;
        }
    }
}
