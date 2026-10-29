package com.aicodestudio.ide.ai.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ShellCommandParser —— 解析模型回复中的 ```bash 命令块（v11.30a）
 *
 * 支持：
 * - ```bash ... ``` 代码块提取（块内可含多条命令，逐行解析）
 * - heredoc 写入：cat > 路径 <<'EOF' ... EOF（支持 'EOF' / "EOF" / EOF 三种定界符）
 * - 空行 / # 注释跳过
 */
public class ShellCommandParser {

    /** 单条解析后的命令 */
    public static class Command {
        public final String raw;      // 原始行（trim 后）
        public final String[] argv;   // 按空白切分的参数
        public final String heredocContent; // heredoc 内容（非 heredoc 时为 null）
        public final String heredocPath;    // heredoc 写入路径（非 heredoc 时为 null）

        Command(String raw, String[] argv, String heredocContent, String heredocPath) {
            this.raw = raw;
            this.argv = argv;
            this.heredocContent = heredocContent;
            this.heredocPath = heredocPath;
        }

        public String first() {
            return argv.length > 0 ? argv[0] : "";
        }
    }

    private static final Pattern BASH_BLOCK = Pattern.compile(
            "```(?:bash|sh|shell)?\\s*\\n?(.*?)(?:```|\\z)", Pattern.DOTALL);

    /**
     * 提取回复中所有 bash 块，并解析成命令列表。
     * 若回复中没有 bash 块，返回空列表（表示纯文本回复）。
     */
    public static List<Command> parse(String reply) {
        List<Command> cmds = new ArrayList<>();
        if (reply == null || reply.isEmpty()) return cmds;

        Matcher m = BASH_BLOCK.matcher(reply);
        while (m.find()) {
            String block = m.group(1);
            parseBlock(block, cmds);
        }
        return cmds;
    }

    /** 解析块内多行命令（含 heredoc 跨行） */
    public static void parseBlock(String block, List<Command> out) {
        String[] lines = block.split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String line = lines[i].trim();
            // 跳过空行与注释
            if (line.isEmpty() || line.startsWith("#")) {
                i++;
                continue;
            }
            // heredoc 检测：支持两种写法（依次尝试，避免交替分支 backref 组号混乱）——
            //  A) cat > path <<'EOF'  /  cat >> path <<'EOF'（标准）
            //  B) cat <<'EOF' > path  /  cat <<EOF > path（模型常用顺序：定界符在前）
            Matcher hd = Pattern.compile(
                    "^cat\\s+(>|>>)\\s+(['\"]?)(.+?)\\2\\s*<<\\s*(['\"]?)([A-Za-z0-9_]+)\\4\\s*$")
                    .matcher(line);
            String path = null;
            String delimiter = null;
            if (hd.matches()) {
                path = hd.group(3);
                delimiter = hd.group(5);
            } else {
                Matcher hdB = Pattern.compile(
                        "^cat\\s*<<\\s*(['\"]?)([A-Za-z0-9_]+)\\1\\s*(>|>>)\\s*(['\"]?)(.+?)\\4\\s*$")
                        .matcher(line);
                if (hdB.matches()) {
                    hd = hdB;
                    path = hdB.group(5);
                    delimiter = hdB.group(2);
                }
            }
            if (hd.matches()) {
                StringBuilder content = new StringBuilder();
                int j = i + 1;
                boolean closed = false;
                while (j < lines.length) {
                    if (lines[j].trim().equals(delimiter)) {
                        closed = true;
                        break;
                    }
                    content.append(lines[j]).append('\n');
                    j++;
                }
                String c = content.toString();
                // 去掉末尾多余的换行（保留原样，写入时补一个换行）
                while (c.endsWith("\n")) c = c.substring(0, c.length() - 1);
                if (!closed) {
                    // 未闭合：容错处理——把块内剩余行当作内容（模型常漏写 EOF 结束行），
                    // 遇到代码块结束标记 ``` 时截断
                    StringBuilder rest = new StringBuilder();
                    int j2 = i + 1;
                    boolean endMark = false;
                    while (j2 < lines.length) {
                        String l2 = lines[j2].trim();
                        if (l2.startsWith("```")) { endMark = true; break; }
                        rest.append(lines[j2]).append('\n');
                        j2++;
                    }
                    String rc = rest.toString();
                    while (rc.endsWith("\n")) rc = rc.substring(0, rc.length() - 1);
                    if (!rc.isEmpty() || endMark) {
                        out.add(new Command(line, line.trim().split("\\s+"), rc, path));
                        i = endMark ? j2 : lines.length;
                        continue;
                    }
                    out.add(simple(line));
                } else {
                    out.add(new Command(line, line.trim().split("\\s+"),
                            c, path));
                    i = j + 1;
                    continue;
                }
            } else if (line.matches("^cat\\s+(>|>>)\\s+.+") && !line.contains("<<")) {
                // v1.94：无定界符写文件——cat > 路径 后面直接跟文件内容，直到代码块结束
                // 不再要求 EOF 标记，AI 直接写内容即可
                Matcher pathM = Pattern.compile("^cat\\s+(>|>>)\\s+(['\"]?)(.+?)\\2\\s*$").matcher(line);
                String filePath = pathM.matches() ? pathM.group(3) : null;
                if (filePath == null) {
                    out.add(simple(line));
                    i++;
                    continue;
                }
                StringBuilder content = new StringBuilder();
                int j = i + 1;
                while (j < lines.length) {
                    String l2 = lines[j];
                    if (l2.trim().startsWith("```")) break;
                    content.append(l2).append('\n');
                    j++;
                }
                String c = content.toString();
                while (c.endsWith("\n")) c = c.substring(0, c.length() - 1);
                out.add(new Command(line, line.trim().split("\\s+"), c, filePath));
                i = (j < lines.length && lines[j].trim().startsWith("```")) ? j : lines.length;
                continue;
            } else if (line.contains("<<") && !line.trim().startsWith("cat")) {
                // 容错：非 cat 命令带 heredoc（模型误用 mkdir ... << EOF 等）——
                // 命令本身照常解析，但跳过其后内容直到定界符，避免内容行被当成命令执行
                Matcher dm = Pattern.compile("<<\\s*['\"]?([A-Za-z0-9_]+)['\"]?\\s*$").matcher(line);
                if (dm.find()) {
                    String delim = dm.group(1);
                    int j = i + 1;
                    while (j < lines.length) {
                        if (lines[j].trim().equals(delim)) { j++; break; }
                        j++;
                    }
                    out.add(simple(line));
                    i = j;
                    continue;
                }
                out.add(simple(line));
            } else {
                out.add(simple(line));
            }
            i++;
        }
    }

    public static Command simple(String line) {
        String[] argv = tokenize(line.trim()).toArray(new String[0]);
        return new Command(line, argv, null, null);
    }

    /** 智能切分参数：支持 '...' 和 "..." 引号内空格不分割（路径含空格时模型可能加引号） */
    public static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inSingle = false, inDouble = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inSingle) {
                if (c == '\'') inSingle = false; else cur.append(c);
            } else if (inDouble) {
                if (c == '"') inDouble = false; else cur.append(c);
            } else if (c == '\'') {
                inSingle = true;
            } else if (c == '"') {
                inDouble = true;
            } else if (Character.isWhitespace(c)) {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }
}
