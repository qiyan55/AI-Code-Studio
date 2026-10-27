package com.aicodestudio.ide.ai.core;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 项目/对话会话结构体
 *
 * 一个会话 = 一个对话 + 可选绑定一个项目。
 *
 * 两种类型：
 * - 绑定项目：projectPath 非空，显示在"项目列表"里
 * - 空白对话：projectPath 为空，显示在"对话列表"里
 *
 * v1.89：每个会话独立配置文件 sessions/{sessionId}.json
 * 历史消息独立文件 chat_history/{sessionId}.json
 */
public class ProjectSession {
    public String sessionId;        // 唯一 ID（同时作为 ChatPanel convKey 和历史文件名）
    public String title;            // 标题（项目名 或 对话首条消息前16字）
    public String projectPath;       // 项目路径（空 = 空白对话）
    public String preview;           // 列表预览（首条用户消息前28字）
    public boolean isBlank;         // 是否空对话（没发过用户消息）
    public int messageCount;        // 消息总数
    public long createdAt;           // 创建时间
    public long lastOpenedAt;        // 最后打开时间
    public int chatMode = 1;         // v1.139：会话模式 0=闲聊(MODE_CHAT) 1=工作任务(MODE_WORK)，默认工作任务

    public ProjectSession() {}

    /** 是否绑定了项目 */
    public boolean isBoundProject() {
        return projectPath != null && !projectPath.isEmpty();
    }

    /** 转 JSON */
    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("sessionId", sessionId);
        o.put("title", title);
        o.put("projectPath", projectPath == null ? "" : projectPath);
        o.put("preview", preview == null ? "" : preview);
        o.put("isBlank", isBlank);
        o.put("messageCount", messageCount);
        o.put("createdAt", createdAt);
        o.put("lastOpenedAt", lastOpenedAt);
        o.put("chatMode", chatMode);
        return o;
    }

    /** 从 JSON 加载 */
    public static ProjectSession fromJson(JSONObject o) {
        ProjectSession s = new ProjectSession();
        try {
            s.sessionId = o.optString("sessionId", "");
            s.title = o.optString("title", "");
            s.projectPath = o.optString("projectPath", "");
            s.preview = o.optString("preview", "");
            s.isBlank = o.optBoolean("isBlank", true);
            s.messageCount = o.optInt("messageCount", 0);
            s.createdAt = o.optLong("createdAt", 0);
            s.lastOpenedAt = o.optLong("lastOpenedAt", 0);
            s.chatMode = o.optInt("chatMode", 1);
        } catch (Throwable t) {
            // ignore
        }
        return s;
    }
}
