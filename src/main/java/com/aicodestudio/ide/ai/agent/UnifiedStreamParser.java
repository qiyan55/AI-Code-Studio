package com.aicodestudio.ide.ai.agent;

/**
 * UnifiedStreamParser — 统一流式解析器
 */
public class UnifiedStreamParser {

    public interface Listener {
        void onText(String text);
        void onThinkStart();
        void onThinkContent(String text);
        void onThinkEnd();
        void onCodeStart(String lang);
        void onCodeContent(String text);
        void onCodeEnd();
    }

    private final Listener listener;

    private enum State { NORMAL, THINK, CODE }
    private State state = State.NORMAL;

    private StringBuilder pending = new StringBuilder();
    private String codeLang = "";
    private final StringBuilder codeContent = new StringBuilder();
    private int thinkCharCount = 0;
    private static final int THINK_TIMEOUT = 2000;

    public UnifiedStreamParser(Listener listener) {
        this(listener, false);
    }

    public UnifiedStreamParser(Listener listener, boolean startInThink) {
        this.listener = listener;
        if (startInThink) {
            state = State.THINK;
            listener.onThinkStart();
        }
    }

    public void feed(String chunk) {
        if (chunk == null || chunk.isEmpty()) return;
        pending.append(chunk);
        String remaining = pending.toString();
        pending.setLength(0);

        while (!remaining.isEmpty()) {
            switch (state) {
                case NORMAL: remaining = parseNormal(remaining); break;
                case THINK:  remaining = parseThink(remaining); break;
                case CODE:   remaining = parseCode(remaining); break;
            }
        }
    }

    public void flush() {
        if (pending.length() > 0) {
            if (state == State.THINK) {
                String remain = stripInnerThinkOpen(pending.toString());
                if (!remain.isEmpty()) listener.onThinkContent(remain);
            } else if (state == State.CODE) {
                codeContent.append(pending);
            } else {
                String clean = pending.toString().replace("</think>", "");
                if (!clean.isEmpty()) listener.onText(clean);
            }
            pending.setLength(0);
        }
        if (state == State.THINK) {
            listener.onThinkEnd();
            state = State.NORMAL;
        }
    }

    // 修复核心：只保留可能是标签前缀的尾部，普通文本一律吐出
    private static int tagTailLen(String text) {
        int maxLen = Math.min(8, text.length());
        for (int k = maxLen; k > 0; k--) {
            String tail = text.substring(text.length() - k);
            if ("<think>".startsWith(tail) || "</think>".startsWith(tail) || "```".startsWith(tail)) {
                return k;
            }
        }
        return 0;
    }

    private String parseNormal(String text) {
        int thinkStart = text.indexOf("<think>");
        int codeStart = findCodeFence(text, 0);

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
            int keep = tagTailLen(text); // 改用 tagTailLen
            String tail = text.substring(text.length() - keep);
            String head = text.substring(0, text.length() - keep);
            if (!head.isEmpty()) {
                String clean = head.replace("</think>", "");
                if (!clean.isEmpty()) listener.onText(clean);
            }
            pending.append(tail);
            return "";
        }

        String before = text.substring(0, nextMarker);
        if (!before.isEmpty()) {
            String clean = before.replace("</think>", "");
            if (!clean.isEmpty()) listener.onText(clean);
        }

        if (isThink) {
            listener.onThinkStart();
            state = State.THINK;
            thinkCharCount = 0;
            return text.substring(nextMarker + 7);
        } else {
            String rest = text.substring(codeStart + 3);
            int nl = rest.indexOf('\n');
            if (nl >= 0) {
                codeLang = rest.substring(0, nl).trim();
                rest = rest.substring(nl + 1);
            } else {
                codeLang = rest.trim();
                rest = "";
            }
            codeContent.setLength(0);
            listener.onCodeStart(codeLang);
            state = State.CODE;
            return rest;
        }
    }

    private String parseThink(String text) {
        int endIdx = text.indexOf("</think>");
        if (endIdx >= 0) {
            String content = text.substring(0, endIdx);
            content = stripInnerThinkOpen(content);
            if (!content.isEmpty()) listener.onThinkContent(content);
            listener.onThinkEnd();
            state = State.NORMAL;
            thinkCharCount = 0;
            return text.substring(endIdx + 8);
        }

        int keep = tagTailLen(text); // 改用 tagTailLen
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

        if (thinkCharCount > THINK_TIMEOUT) {
            listener.onThinkEnd();
            state = State.NORMAL;
            thinkCharCount = 0;
        }
        return "";
    }

    private String stripInnerThinkOpen(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.replace("<think>", "");
    }

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
