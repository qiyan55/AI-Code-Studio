package com.aicodestudio.ide.ai.util;

/**
 * Markdown 渲染工具类
 * 封装 Markdown 到 HTML 的转换逻辑，供普通文本、思考过程等多处复用
 */
public class MarkdownUtil {

    /**
     * 将 Markdown 文本转换为 HTML，供 Html.fromHtml() 渲染。
     * 支持：标题、粗体、斜体、行内代码、链接、无序列表、有序列表、分割线、段落。
     */
    public static String markdownToHtml(String text) {
        if (text == null || text.isEmpty()) return "";

        String[] lines = text.split("\n", -1);
        StringBuilder html = new StringBuilder();
        boolean inUl = false;
        boolean inOl = false;
        boolean inPara = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.isEmpty()) {
                if (inUl) { html.append("</ul>"); inUl = false; }
                if (inOl) { html.append("</ol>"); inOl = false; }
                if (inPara) { html.append("</p>"); inPara = false; }
                continue;
            }

            // 分割线
            if (trimmed.matches("^[-*_]{3,}$")) {
                if (inUl) { html.append("</ul>"); inUl = false; }
                if (inOl) { html.append("</ol>"); inOl = false; }
                if (inPara) { html.append("</p>"); inPara = false; }
                html.append("<hr/>");
                continue;
            }

            // 标题（支持 1-6 级，宽松匹配：井号后有无空格都识别）
            // 本地小模型可能输出 ####标题（无空格）或 #### 标题（有空格），都要能识别
            int headerLevel = 0;
            if (trimmed.startsWith("#")) {
                int hashCount = 0;
                while (hashCount < trimmed.length() && hashCount < 6 && trimmed.charAt(hashCount) == '#') {
                    hashCount++;
                }
                // 井号后面必须是空格、内容或行尾，不能是其他字符（避免把 #include 当成标题）
                if (hashCount >= 1 && hashCount <= 6) {
                    if (hashCount == trimmed.length()
                        || trimmed.charAt(hashCount) == ' '
                        || trimmed.charAt(hashCount) == '\t') {
                        headerLevel = hashCount;
                    }
                }
            }

            if (headerLevel > 0) {
                if (inUl) { html.append("</ul>"); inUl = false; }
                if (inOl) { html.append("</ol>"); inOl = false; }
                if (inPara) { html.append("</p>"); inPara = false; }
                String content = trimmed.substring(headerLevel).trim();
                // v1.153l：标题不再改变字号（Android Html.fromHtml 会把 h1-h6 渲染成不同大小），
                // 统一用 颜色深浅 + 粗细 + 斜体 区分层级，字号与正文一致
                String[] headerStyles = {
                        "<b><font color=\"#F0F6FC\">", "</font></b>",      // h1 最亮最粗
                        "<b><font color=\"#D4DEE8\">", "</font></b>",      // h2
                        "<b><font color=\"#B3BCC9\">", "</font></b>",      // h3
                        "<b><font color=\"#8B949E\">", "</font></b>",      // h4
                        "<i><font color=\"#8B949E\">", "</font></i>",      // h5 斜体
                        "<i><font color=\"#6E7681\">", "</font></i>"       // h6 最暗斜体
                };
                int idx = (headerLevel - 1) * 2;
                html.append(headerStyles[idx])
                    .append(renderInlineMarkdown(content))
                    .append(headerStyles[idx + 1])
                    .append("<br/>");
                continue;
            }

            // 无序列表
            if (trimmed.matches("^[-*+]\\s+.*")) {
                if (inOl) { html.append("</ol>"); inOl = false; }
                if (inPara) { html.append("</p>"); inPara = false; }
                if (!inUl) { html.append("<ul>"); inUl = true; }
                String content = trimmed.substring(2).trim();
                html.append("<li>").append(renderInlineMarkdown(content)).append("</li>");
                continue;
            }

            // 有序列表
            if (trimmed.matches("^\\d+\\.\\s+.*")) {
                if (inUl) { html.append("</ul>"); inUl = false; }
                if (inPara) { html.append("</p>"); inPara = false; }
                if (!inOl) { html.append("<ol>"); inOl = true; }
                int dotIdx = trimmed.indexOf('.');
                String content = trimmed.substring(dotIdx + 1).trim();
                html.append("<li>").append(renderInlineMarkdown(content)).append("</li>");
                continue;
            }

            // 普通段落
            if (inUl) { html.append("</ul>"); inUl = false; }
            if (inOl) { html.append("</ol>"); inOl = false; }
            if (!inPara) { html.append("<p>"); inPara = true; }
            else { html.append("<br/>"); }
            html.append(renderInlineMarkdown(trimmed));
        }

        if (inUl) html.append("</ul>");
        if (inOl) html.append("</ol>");
        if (inPara) html.append("</p>");

        return html.toString();
    }

    /** 渲染行内 Markdown：粗体、斜体、行内代码、链接、删除线 */
    public static String renderInlineMarkdown(String text) {
        if (text == null || text.isEmpty()) return "";

        String result = escapeHtml(text);

        // 行内代码：`code`（先处理）
        result = result.replaceAll("`([^`]+)`", "<code>$1</code>");

        // 粗体：**text** 或 __text__
        result = result.replaceAll("\\*\\*([^*]+)\\*\\*", "<b>$1</b>");
        result = result.replaceAll("__([^_]+)__", "<b>$1</b>");

        // 斜体：*text* 或 _text_（加浅色区分）
        result = result.replaceAll("\\*([^*]+)\\*", "<i><font color=\"#8B949E\">$1</font></i>");
        result = result.replaceAll("(?<!_)_([^_]+)_(?!_)", "<i><font color=\"#8B949E\">$1</font></i>");

        // 删除线：~~text~~
        result = result.replaceAll("~~([^~]+)~~", "<strike>$1</strike>");

        // 链接：[text](url)
        result = result.replaceAll("\\[([^\\]]+)\\]\\(([^)]+)\\)", "<a href=\"$2\">$1</a>");

        return result;
    }

    /** HTML 转义 */
    public static String escapeHtml(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '&': sb.append("&amp;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&#39;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }
}
