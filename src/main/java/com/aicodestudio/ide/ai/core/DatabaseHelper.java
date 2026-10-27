package com.aicodestudio.ide.ai.core;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * SQLite 数据库帮助类（v1.90）
 *
 * 表结构：
 * - sessions：会话元数据（每个对话/项目一行）
 * - messages：聊天消息（用户消息、AI 回复含思考过程、系统提示）
 * - kv_cache：KV 缓存文件元数据（二进制文件仍在文件系统，这里记路径）
 */
public class DatabaseHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "aicode.db";
    private static final int DB_VERSION = 2; // v1.139：sessions 表新增 chat_mode 列（会话模式：0闲聊/1工作任务）

    public DatabaseHelper(Context ctx) {
        super(ctx.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE sessions (" +
                "session_id TEXT PRIMARY KEY, " +
                "title TEXT, " +
                "project_path TEXT, " +
                "preview TEXT, " +
                "is_blank INTEGER, " +
                "message_count INTEGER, " +
                "kv_cache_path TEXT, " +
                "chat_mode INTEGER DEFAULT 1, " +   // v1.139：会话模式 0=闲聊 1=工作任务
                "created_at INTEGER, " +
                "last_opened_at INTEGER)");

        db.execSQL("CREATE TABLE messages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "session_id TEXT, " +
                "type INTEGER, " +
                "text TEXT, " +
                "timestamp INTEGER)");

        db.execSQL("CREATE INDEX idx_messages_session ON messages(session_id)");

        db.execSQL("CREATE TABLE kv_cache (" +
                "session_id TEXT PRIMARY KEY, " +
                "file_path TEXT, " +
                "size_bytes INTEGER, " +
                "updated_at INTEGER)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // v1.139：v1→v2 给 sessions 表补 chat_mode 列（旧会话默认工作任务模式）
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE sessions ADD COLUMN chat_mode INTEGER DEFAULT 1");
            } catch (Throwable ignored) {}
        }
    }

    // ===== sessions 表 CRUD =====

    public void insertSession(ProjectSession s) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("session_id", s.sessionId);
        cv.put("title", s.title);
        cv.put("project_path", s.projectPath == null ? "" : s.projectPath);
        cv.put("preview", s.preview == null ? "" : s.preview);
        cv.put("is_blank", s.isBlank ? 1 : 0);
        cv.put("message_count", s.messageCount);
        cv.put("kv_cache_path", "");
        cv.put("chat_mode", s.chatMode);
        cv.put("created_at", s.createdAt);
        cv.put("last_opened_at", s.lastOpenedAt);
        db.insertWithOnConflict("sessions", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void updateSession(ProjectSession s) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("title", s.title);
        cv.put("project_path", s.projectPath == null ? "" : s.projectPath);
        cv.put("preview", s.preview == null ? "" : s.preview);
        cv.put("is_blank", s.isBlank ? 1 : 0);
        cv.put("message_count", s.messageCount);
        cv.put("chat_mode", s.chatMode);
        cv.put("last_opened_at", s.lastOpenedAt);
        db.update("sessions", cv, "session_id=?", new String[]{s.sessionId});
    }

    public void deleteSession(String sessionId) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("sessions", "session_id=?", new String[]{sessionId});
        db.delete("messages", "session_id=?", new String[]{sessionId});
        db.delete("kv_cache", "session_id=?", new String[]{sessionId});
    }

    public List<ProjectSession> getAllSessions() {
        List<ProjectSession> list = new ArrayList<ProjectSession>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT session_id, title, project_path, preview, is_blank, message_count, " +
                "created_at, last_opened_at, chat_mode FROM sessions ORDER BY last_opened_at DESC", null);
        while (c.moveToNext()) {
            ProjectSession s = new ProjectSession();
            s.sessionId = c.getString(0);
            s.title = c.getString(1);
            s.projectPath = c.getString(2);
            s.preview = c.getString(3);
            s.isBlank = c.getInt(4) == 1;
            s.messageCount = c.getInt(5);
            s.createdAt = c.getLong(6);
            s.lastOpenedAt = c.getLong(7);
            s.chatMode = c.getInt(8);
            list.add(s);
        }
        c.close();
        return list;
    }

    public ProjectSession getSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return null;
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT session_id, title, project_path, preview, is_blank, message_count, " +
                "created_at, last_opened_at, chat_mode FROM sessions WHERE session_id=?",
                new String[]{sessionId});
        ProjectSession s = null;
        if (c.moveToNext()) {
            s = new ProjectSession();
            s.sessionId = c.getString(0);
            s.title = c.getString(1);
            s.projectPath = c.getString(2);
            s.preview = c.getString(3);
            s.isBlank = c.getInt(4) == 1;
            s.messageCount = c.getInt(5);
            s.createdAt = c.getLong(6);
            s.lastOpenedAt = c.getLong(7);
            s.chatMode = c.getInt(8);
        }
        c.close();
        return s;
    }

    // ===== messages 表 CRUD =====

    /** 保存一条消息 */
    public void insertMessage(String sessionId, int type, String text) {
        if (sessionId == null || sessionId.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("session_id", sessionId);
        cv.put("type", type);
        cv.put("text", text == null ? "" : text);
        cv.put("timestamp", System.currentTimeMillis());
        db.insert("messages", null, cv);
    }

    /** 替换整个会话的所有消息（保存历史时用） */
    public void replaceMessages(String sessionId, List<int[]> types, List<String> texts) {
        if (sessionId == null || sessionId.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("messages", "session_id=?", new String[]{sessionId});
            long now = System.currentTimeMillis();
            for (int i = 0; i < types.size(); i++) {
                ContentValues cv = new ContentValues();
                cv.put("session_id", sessionId);
                cv.put("type", types.get(i)[0]);
                cv.put("text", texts.get(i) == null ? "" : texts.get(i));
                cv.put("timestamp", now + i);
                db.insert("messages", null, cv);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** 读取一个会话的所有消息 */
    public List<Object[]> getMessages(String sessionId) {
        List<Object[]> list = new ArrayList<Object[]>();
        if (sessionId == null || sessionId.isEmpty()) return list;
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT type, text FROM messages WHERE session_id=? ORDER BY id ASC",
                new String[]{sessionId});
        while (c.moveToNext()) {
            int type = c.getInt(0);
            String text = c.getString(1);
            list.add(new Object[]{type, text == null ? "" : text});
        }
        c.close();
        return list;
    }

    // ===== kv_cache 表 =====

    public void updateKvCache(String sessionId, String filePath, long sizeBytes) {
        if (sessionId == null || sessionId.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("session_id", sessionId);
        cv.put("file_path", filePath);
        cv.put("size_bytes", sizeBytes);
        cv.put("updated_at", System.currentTimeMillis());
        db.insertWithOnConflict("kv_cache", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public String getKvCachePath(String sessionId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT file_path FROM kv_cache WHERE session_id=?",
                new String[]{sessionId});
        String path = null;
        if (c.moveToNext()) path = c.getString(0);
        c.close();
        return path;
    }
}
