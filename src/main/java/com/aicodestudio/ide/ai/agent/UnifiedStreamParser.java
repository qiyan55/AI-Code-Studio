package com.aicodestudio.ide.ai.agent;

/**
 * UnifiedStreamParser — 统一流式解析器（v1.124）
 *
 * 替代 StreamingToolDetector + ChatPanel.parseMarkdown 的重复解析。
 * 逐chunk喂入模型原始输出，统一维护 think 块和 code 块状态机，
 * 通过事件回调告诉上层：这段文本是思考内容、代码块还是普通正文。
 *
 * 状态：NORMAL → THINK（遇到<think>）→ NORMAL（遇到</think>）
 *       NORMAL → CODE（遇到```）→ NORMAL（遇到```）
 *
 * think块内的内容不触发code识别。
 */
public class UnifiedStreamParser {

    public interface Listener {
        /** 普通正文（在think块外、代码块外） */
        void onText(String text);
        /** 思考内容开始 */
        void onThinkStart();
        /** 思考内容片段 */
        void onThinkContent(String text);
        /** 思考内容结束 */
        void onThinkEnd();
        /** 代码块开始，lang为语言标签（java/shell/...） */
        void onCodeStart(String lang);
        /** 代码块内容片段 */
        void onCodeContent(String text);
        /** 代码块结束 */
        void onCodeEnd();
    }

    private final Listener listener;

    private enum State { NORMAL, THINK, CODE }
    private State state = State.NORMAL;

    // 跨chunk缓冲：可能在think/code标签中间被拆分
    private StringBuilder pending = new StringBuilder();

    // 当前代码块语言
    private String codeLang = "";
    // 当前代码块内容累积
    private final StringBuilder codeContent = new StringBuilder();

    // think超时保护
    private int thinkCharCount = 0;
    private static final int THINK_TIMEOUT = 2000;

    public UnifiedStreamParser(Listener listener) {
        this(listener, false);
    }

    /** @param startInThink true=初始就在think块内（prompt预填了<think>） */
    public UnifiedStreamParser(Listener listener, boolean startInThink) {
        this.listener = listener;
        if (startInThink) {
            state = State.THINK;
            listener.onThinkStart();
        }
    }

    /** 喂入一个chunk */
    public void feed(String chunk) {
        if (chunk == null || chunk.isEmpty()) return;
        pending.append(chunk);

        String remaining = pending.toString();
        pending.setLength(0);

        while (!remaining.isEmpty()) {
            switch (state) {
                case NORMAL:
                    remaining = parseNormal(remaining);
                    break;
                case THINK:
                    remaining = parseThink(remaining);
                    break;
                case CODE:
                    remaining = parseCode(remaining);
                    break;
            }
        }
    }

    /** 流结束，flush残留 */
    public void flush() {
        // pending里如果有残留，当作普通文本输出
        if (pending.length() > 0) {
            if (state == State.THINK) {
                // v1.141：残留内容同样剥离块内 <think> 标签
                String remain = stripInnerThinkOpen(pending.toString());
                if (!remain.isEmpty()) listener.onThinkContent(remain);
            } else if (state == State.CODE) {
                codeContent.append(pending);
            } else {
                // v1.141：NORMAL 残留剥离孤立 </think>
                String clean = pending.toString().replace("</think>", "");
                if (!clean.isEmpty()) listener.onText(clean);
            }
            pending.setLength(0);
        }
        // 如果think没闭合，强制结束
        if (state == State.THINK) {
            listener.onThinkEnd();
            state = State.NORMAL;
        }
    }

    // ===== NORMAL状态：找<think>和``` =====
    private String parseNormal(String text) {
        int thinkStart = text.indexOf("<think>");
        int codeStart = findCodeFence(text, 0);

        // 决定先遇到哪个标记
        int nextMarker = -1;
        boolean isThink = false;

        if (thinkStart >= 0 && (codeStart < 0 || thinkStart < codeStart)) {
            nextMarker = thinkStart;
            isThink = true;
        } else if (codeStart >= 0) {
            nextMarker = codeStart;
            isThink = false;
        }

        if (nextMarker < 0) {
            // 没有标记，全部当普通文本
            // 保留末尾可能的半个标签
            int keep = Math.min(8, text.length());
            String tail = text.substring(text.length() - keep);
            String head = text.substring(0, text.length() - keep);
            // v1.141：NORMAL 状态遇到孤立 </think>（模型多打闭合标签）→ 忽略，不显示标签原文
            if (!head.isEmpty()) {
                String clean = head.replace("</think>", "");
                if (!clean.isEmpty()) listener.onText(clean);
            }
            pending.append(tail);
            return "";
        }

        // 标记之前的内容是普通文本
        String before = text.substring(0, nextMarker);
        if (!before.isEmpty()) {
            // v1.141：标记前混入的孤立 </think> 忽略（模型输出 </think></think> 或多打闭合标签）
            String clean = before.replace("</think>", "");
            if (!clean.isEmpty()) listener.onText(clean);
        }

        if (isThink) {
            listener.onThinkStart();
            state = State.THINK;
            thinkCharCount = 0;
            return text.substring(nextMarker + 7);
        } else {
            // 代码块开始，提取语言标签
            String rest = text.substring(codeStart + 3); // 跳过```
            // 提取语言标签（到行尾）
            int nl = rest.indexOf('\n');
            if (nl >= 0) {
                codeLang = rest.substring(0, nl).trim();
                rest = rest.substring(nl + 1);
                codeContent.setLength(0);
                listener.onCodeStart(codeLang);
                state = State.CODE;
                return rest;
            } else if (!rest.isEmpty()) {
                // ```java 但还没换行（chunk 截断在行尾），暂存语言名等下一个换行
                codeLang = rest.trim();
                codeContent.setLength(0);
                listener.onCodeStart(codeLang);
                state = State.CODE;
                return "";
            } else {
                // ``` 单独到达（chunk 边界），暂存 ``` 等下一个 chunk，避免语言名丢失
                pending.append("```");
                return text.substring(0, codeStart);
            }
        }
    }

    // ===== THINK状态：找</think> =====
    private String parseThink(String text) {
        int endIdx = text.indexOf("</think>");
        if (endIdx >= 0) {
            String content = text.substring(0, endIdx);
            // v1.141：剥离 think 块内重复出现的 <think>——本地模式 prompt 已预填 <think>，
            // 但小模型习惯性自己再输出一遍 <think> 标签，若不剥离会作为思考内容转发，
            // 导致界面思考卡片开头显示 think 标签原文（看起来像标签重复叠加）
            content = stripInnerThinkOpen(content);
            if (!content.isEmpty()) listener.onThinkContent(content);
            listener.onThinkEnd();
            state = State.NORMAL;
            thinkCharCount = 0;
            return text.substring(endIdx + 8);
        }

        // 没找到闭合，保留末尾8字符防跨chunk
        int keep = Math.min(8, text.length());
        String tail = text.substring(text.length() - keep);
        String head = text.substring(0, text.length() - keep);
        if (!head.isEmpty()) {
            head = stripInnerThinkOpen(head);
            if (!head.isEmpty()) {
                listener.onThinkContent(head);
                thinkCharCount += head.length();
            }
        }
        pending.append(tail);

        // think超时保护
        if (thinkCharCount > THINK_TIMEOUT) {
            listener.onThinkEnd();
            state = State.NORMAL;
            thinkCharCount = 0;
        }
        return "";
    }

    /** v1.141：剥离 think 块内容中重复出现的 <think> 开始标签（只删标签，保留正文） */
    private String stripInnerThinkOpen(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.replace("<think>", "");
    }

    // ===== CODE状态：找```闭合 =====
    private String parseCode(String text) {
        int closeIdx = findCodeFence(text, 0);
        if (closeIdx >= 0) {
            String content = text.substring(0, closeIdx);
            codeContent.append(content);
            listener.onCodeContent(content);
            listener.onCodeEnd();
            state = State.NORMAL;
            return text.substring(closeIdx + 3);
        }

        // 没找到闭合，保留末尾3字符
        int keep = Math.min(3, text.length());
        String tail = text.substring(text.length() - keep);
        String head = text.substring(0, text.length() - keep);
        if (!head.isEmpty()) {
            codeContent.append(head);
            listener.onCodeContent(head);
        }
        pending.append(tail);
        return "";
    }

    /** 找```围栏位置（至少3个反引号） */
    private int findCodeFence(String text, int from) {
        int idx = -1;
        for (int i = from; i < text.length() - 2; i++) {
            if (text.charAt(i) == '`' && text.charAt(i+1) == '`' && text.charAt(i+2) == '`') {
                idx = i;
                break;
            }
        }
        return idx;
    }

    public String getCurrentCodeLang() { return codeLang; }
    public String getCodeContent() { return codeContent.toString(); }
    public boolean isInThink() { return state == State.THINK; }
    public boolean isInCode() { return state == State.CODE; }
}
