package com.aicodestudio.ide.ai.agent;

/**
 * StreamingToolDetector —— 流式增量识别模型回复中的工具调用命令块（v1.91）。
 *
 * 在 generateOnce 的流式回调中逐块喂入 chunk，识别 ```shell / ```bash / ```sh 围栏，
 * 并区分三类工具调用：
 *   - READ  读文件：cat 路径（不带 > / <<）
 *   - WRITE 写文件：cat > 路径 / cat >> 路径（含 heredoc cat <<EOF > path）
 *   - EXEC  执行命令：其余所有命令（mkdir / aics compile / ls / echo ...）
 *
 * 职责：
 *   1. 把围栏外的普通正文透传给 onBody（用于转发 onResponseChunk 渲染 markdown）；
 *   2. 识别到命令块头（首行命令确定）时触发 onToolStart（卡片"执行中"动画出现）；
 *   3. 块内容流式累积时触发 onToolUpdate（代码框实时填充）；
 *   4. 围栏闭合时触发 onToolEnd（交给上层执行 + 动画消失）。
 *
 * 线程安全：非线程安全，需在单一线程（generateOnce 的流式回调线程）中顺序调用 feed。
 * 跨 chunk 容错：反引号、语言标签、命令首行都可能被拆到不同 chunk，用状态机 + 计数缓冲处理。
 */
public class StreamingToolDetector {

    // 工具类型
    public static final int TYPE_READ = 0;
    public static final int TYPE_WRITE = 1;
    public static final int TYPE_EXEC = 2;

    /** 一个已识别/识别中的工具调用块 */
    public static class ToolBlock {
        public int type;         // TYPE_READ / TYPE_WRITE / TYPE_EXEC
        public String lang;      // 围栏语言标签（shell/bash/sh）
        public String path = "";       // 读/写目标文件路径
        public String cmd = "";        // 命令首行（EXEC 用）
        public final StringBuilder content = new StringBuilder(); // 块内容（写文件时是文件内容）
        public int sentLen = 0;   // 已通过 onToolUpdate 发出的内容长度（增量用）

        ToolBlock(int type, String lang) {
            this.type = type;
            this.lang = lang;
        }

        /** 卡片标题文案：写/读显示文件名，执行显示命令 */
        public String label() {
            if (type == TYPE_WRITE || type == TYPE_READ) {
                String name = path;
                int slash = path.lastIndexOf('/');
                if (slash >= 0 && slash < path.length() - 1) name = path.substring(slash + 1);
                if (name.isEmpty()) name = path;
                return name;
            }
            return cmd.isEmpty() ? "执行命令" : cmd;
        }

        /** 写文件的路径（含目录），执行/读为 null */
        public String writePath() { return type == TYPE_WRITE ? path : null; }
        public String readPath()  { return type == TYPE_READ ? path : null; }
    }

    /** 事件回调 */
    public interface Listener {
        void onBody(String text);            // 围栏外普通正文（转发 onResponseChunk）
        void onToolStart(ToolBlock block);   // 命令块头识别（卡片执行中）
        void onToolUpdate(String content);   // 块内容增量
        void onToolEnd(ToolBlock block);     // 围栏闭合
    }

    private final Listener listener;

    // 状态
    private static final int ST_TEXT = 0;     // 围栏外
    private static final int ST_FENCE = 1;    // 围栏内（shell 工具块）
    private static final int ST_PASS = 2;     // 围栏内（非 shell，整体透传为正文）

    private int state = ST_TEXT;
    private final StringBuilder body = new StringBuilder();   // 待透传正文
    private ToolBlock current;                                 // 当前工具块
    private final StringBuilder pass = new StringBuilder();    // 透传围栏内容（非 shell）
    private String passLang = "";
    private int bodyTick = 0;       // TEXT 态连续反引号计数（跨 chunk）
    private int closeTick = 0;      // FENCE/PASS 态连续反引号计数（闭合检测）
    private boolean headDone = false;   // 命令首行是否已确定
    private boolean langDone = false;   // 语言标签是否已读取
    private final StringBuilder recentText = new StringBuilder();  // 最近纯文本（用于识别"我来写Xxx.java"）

    public StreamingToolDetector(Listener listener) {
        this.listener = listener;
    }

    /** 喂入一个流式 chunk；逐字符状态机处理，跨 chunk 安全。 */
    public void feed(String chunk) {
        if (chunk == null) return;
        for (int i = 0; i < chunk.length(); i++) {
            char c = chunk.charAt(i);
            switch (state) {
                case ST_TEXT: handleTextChar(c); break;
                case ST_FENCE: handleFenceChar(c); break;
                case ST_PASS: handlePassChar(c); break;
            }
        }
        // chunk 处理完：shell 围栏内有新增内容 → 发一次增量
        if (state == ST_FENCE && current != null) {
            int len = current.content.length();
            if (len > current.sentLen) {
                String inc = current.content.substring(current.sentLen);
                current.sentLen = len;
                if (listener != null) listener.onToolUpdate(inc);
            }
        }
        // v1.105：正文态（含 think 文本）也每 chunk 实时 flush 增量，避免整段 think 攒到
        // 第一个代码块才整块冒出。bodyTick>0 说明 chunk 末尾正好是 1~2 个悬空反引号
        // （跨 chunk 的 ``` 开头），留到下一 chunk 凑齐再发，不吐半个围栏标记。
        if (state == ST_TEXT && bodyTick == 0 && body.length() > 0) {
            emitBody();
        }
    }

    /** 流结束调用：刷新未闭合状态，保证不丢正文/内容。 */
    public void flush() {
        // TEXT 态残余反引号
        if (state == ST_TEXT) {
            for (int k = 0; k < bodyTick; k++) body.append('`');
            bodyTick = 0;
            emitBody();
        } else if (state == ST_FENCE) {
            // 未闭合的 shell 块：内容有效则作为写文件内容发出（容错），否则丢弃围栏标记
            if (current != null && current.content.length() > 0) {
                if (current.content.length() > current.sentLen) {
                    listener.onToolUpdate(current.content.substring(current.sentLen));
                    current.sentLen = current.content.length();
                }
                emitBody();
                listener.onToolEnd(current);
            } else {
                // 只有 ```shell 头没有内容：把已消耗的 ``` 补回正文透传
                body.append("```").append(langToStr(current)).append("\n");
                emitBody();
            }
            current = null;
            headDone = false;
            langDone = false;
            state = ST_TEXT;
        } else if (state == ST_PASS) {
            // 未闭合的非 shell 围栏：整体透传为正文
            body.append("```").append(passLang).append("\n").append(pass);
            if (closeTick > 0) { for (int k = 0; k < closeTick; k++) body.append('`'); closeTick = 0; }
            pass.setLength(0);
            emitBody();
            state = ST_TEXT;
        }
    }

    // ===== TEXT 态：检测 ``` 开围栏，其余为正文 =====
    private void handleTextChar(char c) {
        if (c == '`') {
            bodyTick++;
            // 达到 3 个反引号 → 进入围栏（反引号尚未 append 进 body，无需回退）
            if (bodyTick >= 3) {
                bodyTick = 0;
                startFence();
            }
        } else {
            for (int k = 0; k < bodyTick; k++) body.append('`');
            bodyTick = 0;
            body.append(c);
            // 记录最近文本（最多200字符），用于识别"我来写Xxx.java"
            recentText.append(c);
            if (recentText.length() > 200) recentText.delete(0, recentText.length() - 200);
        }
    }

    private void startFence() {
        state = ST_FENCE;
        com.aicodestudio.ide.ai.util.DebugLogger.log("RENDER", "startFence: 进入代码块");
        langDone = false;
        headDone = false;
        closeTick = 0;
        current = new ToolBlock(TYPE_EXEC, "");
        // 语言标签紧跟 ``` 之后，由后续字符解析
    }

    // ===== FENCE 态：读语言标签 → 读首行命令 → 累积内容，检测 ``` 闭合 =====
    private void handleFenceChar(char c) {
        // 闭合检测：3 个连续反引号
        if (c == '`') {
            closeTick++;
            if (closeTick >= 3) {
                closeFence();
            }
            return;
        }
        // 反引号序列中断 → 是内容
        for (int k = 0; k < closeTick; k++) current.content.append('`');
        closeTick = 0;

        if (!langDone) {
            // 读语言标签：``` 之后到换行前（可能为空，如 ```\n）
            if (c == '\n') {
                langDone = true;
                decideFenceLang();
            } else {
                current.content.append(c);
            }
            return;
        }
        if (!headDone) {
            // 读首行命令（到换行）
            if (c == '\n') {
                headDone = true;
                parseHeadAndStart();
            } else {
                current.cmd += c;
            }
            return;
        }
        // 内容累积
        current.content.append(c);
    }

    /** 语言标签读完后判断：非 shell → 转透传；shell → 确定工具块类型前继续读首行 */
    private void decideFenceLang() {
        String rawLang = current.content.toString().trim();
        current.content.setLength(0);
        current.lang = rawLang;
        if (rawLang.isEmpty()
                || (!rawLang.equalsIgnoreCase("shell")
                && !rawLang.equalsIgnoreCase("bash")
                && !rawLang.equalsIgnoreCase("sh"))) {
            // 非 shell 围栏（```java、无语言``` 等）
            // 检查前面是否有"我来写 Xxx.java"——如果有，当作写文件处理
            String pendingPath = detectWritePathFromRecentText();
            if (pendingPath != null) {
                current.type = TYPE_WRITE;
                current.path = pendingPath;
                headDone = true;  // 跳过读首行命令，直接读代码块内容
                com.aicodestudio.ide.ai.util.DebugLogger.log("RENDER", "decideFenceLang: 识别为写文件 path=" + pendingPath);
                listener.onToolStart(current);
                return;
            }
            // 否则整体透传为正文
            state = ST_PASS;
            pass.setLength(0);
            passLang = rawLang;
            body.append("```").append(rawLang).append("\n");
            return;
        }
        // shell 块：继续读首行命令
    }

    /** 首行命令读完：解析读/写/执行，触发 onToolStart */
    private void parseHeadAndStart() {
        String head = current.cmd.trim();
        // heredoc 写：cat > path <<EOF / cat <<EOF > path
        boolean isWrite = head.matches("^cat\\s+(>|>>)\\s+.*") || head.matches("^cat\\s*<<.*");
        boolean isRead = !isWrite && head.matches("^cat\\s+[^|>]+$");
        int type;
        if (isWrite) {
            type = TYPE_WRITE;
            current.path = extractCatPath(head);
        } else if (isRead) {
            type = TYPE_READ;
            current.path = extractCatPath(head);
        } else {
            type = TYPE_EXEC;
        }
        current.type = type;
        com.aicodestudio.ide.ai.util.DebugLogger.log("RENDER", "onToolStart: type=" + type + " path=" + current.path);
        listener.onToolStart(current);
    }

    private void closeFence() {
        if (current != null) {
            String content = current.content.toString();
            while (content.endsWith("`")) content = content.substring(0, content.length() - 1);
            // v1.127：write 块剥掉末尾残留的 heredoc 定界符行（EOF），不显示在卡片代码框里
            if (current.type == TYPE_WRITE) {
                content = stripHeredocTrailer(content, current.cmd);
            }
            current.content.setLength(0);
            current.content.append(content);
            if (current.sentLen > content.length()) current.sentLen = content.length();
            // 发剩余增量
            if (current.content.length() > current.sentLen) {
                listener.onToolUpdate(current.content.substring(current.sentLen));
                current.sentLen = current.content.length();
            }
            emitBody();
            com.aicodestudio.ide.ai.util.DebugLogger.log("RENDER", "onToolEnd: type=" + current.type + " contentLen=" + current.content.length());
            listener.onToolEnd(current);
        }
        current = null;
        headDone = false;
        langDone = false;
        closeTick = 0;
        state = ST_TEXT;
    }

    // ===== PASS 态：非 shell 围栏透传，遇 ``` 结束 =====
    private void handlePassChar(char c) {
        if (c == '`') {
            closeTick++;
            if (closeTick >= 3) {
                body.append("```");
                for (int k = 0; k < pass.length(); k++) body.append(pass.charAt(k));
                closeTick = 0;
                pass.setLength(0);
                emitBody();
                state = ST_TEXT;
            }
            return;
        }
        for (int k = 0; k < closeTick; k++) pass.append('`');
        closeTick = 0;
        pass.append(c);
    }

    private void emitBody() {
        if (body.length() > 0) {
            String t = body.toString();
            body.setLength(0);
            // v1.109: 记录分发到 UI 的普通文本片段，区分"模型没输出"还是"状态机吞了"
            com.aicodestudio.ide.ai.util.DebugLogger.log("RENDER_PLAIN",
                    "len=" + t.length() + " preview=" + (t.length() > 60 ? t.substring(0, 60) : t).replace("\n", "\\n"));
            if (listener != null) listener.onBody(t);
        }
    }

    /** 从 cat 命令提取目标路径（去引号、去 heredoc 定界符） */
    private String extractCatPath(String head) {
        String h = head.trim();
        // heredoc 两种顺序：cat > path <<EOF / cat <<EOF > path
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "^cat\\s+(>|>>)\\s+(['\"]?)(.+?)\\2(\\s*<<\\s*(['\"]?)[A-Za-z0-9_]+\\5)?$").matcher(h);
        if (m.matches()) {
            return m.group(3).trim().replaceAll("^['\"]|['\"]$", "");
        }
        java.util.regex.Matcher m2 = java.util.regex.Pattern.compile(
                "^cat\\s*<<\\s*(['\"]?)[A-Za-z0-9_]+\\1\\s*(>|>>)\\s*(['\"]?)(.+?)\\3$").matcher(h);
        if (m2.matches()) {
            return m2.group(4).trim().replaceAll("^['\"]|['\"]$", "");
        }
        // 纯 cat 路径
        java.util.regex.Matcher m3 = java.util.regex.Pattern.compile(
                "^cat\\s+([^|<>]+)$").matcher(h);
        if (m3.matches()) {
            return m3.group(1).trim().replaceAll("^['\"]|['\"]$", "");
        }
        return h.replaceFirst("^cat\\s+", "").trim();
    }

    private String langToStr(ToolBlock b) {
        return (b != null && b.lang != null && !b.lang.isEmpty()) ? b.lang : "";
    }

    /**
     * v1.101：从一个 **think 块之外** 的 ```shell 代码块内容离线识别工具类型（读/写/执行），
     * 供历史/切换渲染判断该 shell 块是否该生成工具卡片。首行规则与流式状态机一致。
     * 注意：仅对 think 块外的 shell 块调用；think 内的命令留在思考卡片里，不走这里。
     */
    public static ToolBlock classifyShellCode(String code, String lang) {
        if (code == null) code = "";
        int nl = code.indexOf('\n');
        String rest = (nl >= 0 && nl < code.length() - 1) ? code.substring(nl + 1) : "";
        String head = code.split("\n", 2)[0].trim();
        boolean isWrite = head.matches("^cat\\s+(>|>>)\\s+.*") || head.matches("^cat\\s*<<.*");
        boolean isRead = !isWrite && head.matches("^cat\\s+[^|>]+$");
        int type = isWrite ? TYPE_WRITE : (isRead ? TYPE_READ : TYPE_EXEC);
        ToolBlock b = new ToolBlock(type, lang);
        if (isWrite || isRead) {
            b.path = extractCatPathStatic(head);
        } else {
            b.cmd = head;
        }
        // v1.127：write 块剥掉末尾残留的 heredoc 定界符行（EOF），不进卡片代码框
        b.content.append(isWrite ? stripHeredocTrailer(rest, head) : rest);
        return b;
    }

    /** extractCatPath 的 static 复刻（供 classifyShellCode 离线调用） */
    private static String extractCatPathStatic(String h) {
        h = h.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "^cat\\s+(>|>>)\\s+(['\"]?)(.+?)\\2(\\s*<<\\s*(['\"]?)[A-Za-z0-9_]+\\5)?$").matcher(h);
        if (m.matches()) return m.group(3).trim().replaceAll("^['\"]|['\"]$", "");
        java.util.regex.Matcher m2 = java.util.regex.Pattern.compile(
                "^cat\\s*<<\\s*(['\"]?)[A-Za-z0-9_]+\\1\\s*(>|>>)\\s*(['\"]?)(.+?)\\3$").matcher(h);
        if (m2.matches()) return m2.group(4).trim().replaceAll("^['\"]|['\"]$", "");
        java.util.regex.Matcher m3 = java.util.regex.Pattern.compile("^cat\\s+([^|<>]+)$").matcher(h);
        if (m3.matches()) return m3.group(1).trim().replaceAll("^['\"]|['\"]$", "");
        return h.replaceFirst("^cat\\s+", "").trim();
    }

    /**
     * v1.127：历史/离线路径专用。判断一个“非 shell”代码块（如 ```java）是否因前文出现
     * “我来写 Xxx.java”而应当作为写文件工具卡片渲染。规则与流式 decideFenceLang →
     * detectWritePathFromRecentText 完全一致，保证流式与历史渲染对称。
     * 命中返回 WRITE 类型 ToolBlock（content=代码块全文），否则返回 null（按普通代码框渲染）。
     */
    public static ToolBlock matchWriteFence(String code, String lang, String recentText) {
        if (code == null || recentText == null || recentText.isEmpty()) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "我来写[：:]?\\s*([\\w/\\.\\-]+\\.(java|xml|gradle|txt|md|json))").matcher(recentText);
        if (!m.find()) return null;
        ToolBlock b = new ToolBlock(TYPE_WRITE, lang == null ? "" : lang);
        b.path = m.group(1);
        b.content.append(code);
        return b;
    }

    /**
     * 去掉 write 块内容末尾残留的 heredoc 定界符行（如单独一行 EOF）。
     * heredoc 体末尾的 EOF 会被当文件内容显示在卡片代码框里，这里按首行命令里 << 后的标记名剥掉。
     */
    public static String stripHeredocTrailer(String content, String head) {
        if (content == null || content.isEmpty()) return content;
        String delim = null;
        if (head != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "<<\\s*['\"]?([A-Za-z0-9_]+)").matcher(head);
            if (m.find()) delim = m.group(1);
        }
        String[] lines = content.split("\n", -1);
        int end = lines.length;
        // 跳过末尾空行
        while (end > 0 && lines[end - 1].trim().isEmpty()) end--;
        // 末行恰好是 heredoc 定界符（独立成行）→ 删掉
        if (end > 0 && delim != null && lines[end - 1].trim().equals(delim)) end--;
        if (end == lines.length) return content; // 没有可剥的
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < end; i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    /**
     * 从最近文本中识别"我来写 Xxx.java"模式，提取文件路径。
     * 支持：我来写 MainActivity.java / 我来写 activity_main.xml / 写 MainActivity.java
     */
    private String detectWritePathFromRecentText() {
        String txt = recentText.toString();
        if (txt == null || txt.isEmpty()) return null;
        // 匹配"我来写 xxx"或"我来写：xxx"
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "我来写[：:]?\\s*([\\w/\\.\\-]+\\.(java|xml|gradle|txt|md|json))").matcher(txt);
        if (m.find()) return m.group(1);
        return null;
    }
}
