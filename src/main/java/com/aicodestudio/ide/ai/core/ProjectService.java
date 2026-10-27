package com.aicodestudio.ide.ai.core;

import android.content.Context;

import java.io.File;
import java.util.List;

/**
 * 项目统一操作层（唯一数据源 + 唯一写入入口）。
 *
 * <p>所有 UI 入口（欢迎页 WelcomeActivity、汉堡菜单、对话侧边栏、MainActivity、
 * AI 自动创建）都必须通过本类来创建 / 删除 / 移除 / 列出项目，
 * 禁止任何 UI 自己再写一套文件创建、目录删除、会话绑定逻辑。</p>
 *
 * <p>职责：
 * <ul>
 *   <li>创建项目：委托 ProjectManager 生成模板文件 → 绑定会话 → 加入最近列表</li>
 *   <li>删除项目：删目录 → 删会话记录 → 删最近列表 → 删聊天历史文件</li>
 *   <li>从列表移除：只删会话记录和最近列表，不动磁盘文件</li>
 *   <li>打开项目：设置 WorkspaceState → 记录最近 → 绑定/取会话</li>
 *   <li>列出项目：只读快照（唯一数据源是 SessionManager）</li>
 * </ul>
 * </p>
 *
 * <p>UI 层只负责弹窗、确认、Toast；业务规则全部收敛在这里。</p>
 */
public class ProjectService {

    /** 创建项目结果 */
    public static class CreateResult {
        public final String projectPath;      // 项目目录绝对路径；null=失败
        public final ProjectSession session;  // 绑定的会话（创建成功时非空）
        public CreateResult(String path, ProjectSession s) {
            this.projectPath = path; this.session = s;
        }
    }

    /**
     * 创建项目并完成全部关联动作（模板文件 + 会话绑定 + 最近列表）。
     * 任何入口（欢迎页、菜单、AI）都调这一个。
     */
    public static CreateResult createProject(Context ctx, String name, String desc, String language) {
        // 1. 生成模板文件（ProjectManager 是唯一模板生成器）
        String path = ProjectManager.create(ctx, name, desc, language);
        if (path == null) return new CreateResult(null, null);

        // 2. 绑定会话（项目 ↔ 对话，唯一绑定入口）
        SessionManager sm = SessionManager.get(ctx);
        String projectName = new File(path).getName();
        ProjectSession session = sm.createProjectSession(path, projectName);
        sm.touchSession(session.sessionId);

        // 3. 记录到最近项目
        AppConfig.getInstance(ctx).addRecentProject(path);

        // 4. 设置当前工作区
        WorkspaceState.get().setProjectPath(path);
        WorkspaceState.get().setProjectType(WorkspaceState.detectProjectType(path));

        return new CreateResult(path, session);
    }

    /**
     * 打开已有项目：设置工作区 → 记录最近 → 绑定/取会话。
     * 返回绑定的会话；目录不存在返回 null。
     */
    public static ProjectSession openProject(Context ctx, String path) {
        File f = new File(path);
        if (!f.exists() || !f.isDirectory()) return null;

        WorkspaceState.get().setProjectPath(path);
        WorkspaceState.get().setProjectType(WorkspaceState.detectProjectType(path));
        AppConfig.getInstance(ctx).addRecentProject(path);

        SessionManager sm = SessionManager.get(ctx);
        String projectName = f.getName();
        ProjectSession session = sm.createProjectSession(path, projectName);
        sm.touchSession(session.sessionId);
        return session;
    }

    /**
     * 从项目列表移除（不删磁盘文件）：删会话记录 + 删最近列表项。
     */
    public static void removeFromList(Context ctx, String path) {
        SessionManager sm = SessionManager.get(ctx);
        ProjectSession s = sm.getSessionByProjectPath(path);
        if (s != null) sm.deleteSession(s.sessionId);
        AppConfig.getInstance(ctx).removeRecentProject(path);
    }

    /**
     * 彻底删除项目：删磁盘目录 + 删会话记录 + 删最近列表 + 删聊天历史文件。
     */
    public static void deleteProject(Context ctx, String path) {
        // 1. 删磁盘目录
        deleteRecursive(new File(path));
        // 2. 删会话记录（之前各入口漏了这步，导致删了项目列表里还残留）
        SessionManager sm = SessionManager.get(ctx);
        ProjectSession s = sm.getSessionByProjectPath(path);
        if (s != null) sm.deleteSession(s.sessionId);
        // 3. 删最近列表
        AppConfig.getInstance(ctx).removeRecentProject(path);
        // 4. 删聊天历史 JSON 文件
        deleteChatHistoryFiles(ctx, path);
    }

    /**
     * 获取项目列表（只读快照，数据源是 SessionManager）。
     * UI 不持有长期缓存，每次渲染调这个拿最新快照。
     */
    public static List<ProjectSession> getProjectList(Context ctx) {
        return SessionManager.get(ctx).getProjectSessions();
    }

    // ===== 内部工具（这些不再暴露给 UI，统一在这里）=====

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        f.delete();
    }

    private static void deleteChatHistoryFiles(Context ctx, String projectPath) {
        try {
            String key = com.aicodestudio.ide.ai.chat.ChatPanel
                    .historyFileNameBaseStatic(projectPath);
            File dir = new File(ctx.getFilesDir(), "chat_history");
            new File(dir, key + ".json").delete();
            new File(dir, key + ".meta.json").delete();
        } catch (Throwable ignored) {}
    }
}
