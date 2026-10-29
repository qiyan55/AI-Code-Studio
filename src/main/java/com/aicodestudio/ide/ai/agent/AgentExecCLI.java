package com.aicodestudio.ide.ai.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * AgentExecCLI —— 模型回复执行器（v1.51p 为 MiniCPM5 重写，删掉全部 1.5B 时代冗余）。
 *
 * MiniCPM5 输出两种格式（实测都会出现）：
 *  1. XML 工具调用：&lt;function name="bash"&gt;&lt;param name="command"&gt;&lt;![CDATA[cmd]]&gt;&lt;/param&gt;&lt;/function&gt;
 *  2. bash 代码块：```bash ... ```（markdown 围栏）
 * 两者都提取执行。命令支持 && 串联、heredoc（cat > path &lt;&lt; EOF）。
 * 其余文本（计划/总结/思考）不执行，交由上层 Agent 循环决定是否继续。
 */
public class AgentExecCLI {

    /** 单条执行结果（供 App 端回填工具结果） */
    public static class ExecResult {
        public final String raw;
        public final ShellCommandExecutor.ToolResult result;
        public ExecResult(String raw, ShellCommandExecutor.ToolResult result) {
            this.raw = raw;
            this.result = result;
        }
    }

    /** MiniCPM5 XML 工具调用：&lt;function name="x"&gt;&lt;param name="command"&gt;CDATA 或文本&lt;/param&gt; */
    static final Pattern FUNC = Pattern.compile(
            "<function\\s+name=\"[^\"]*\"[^>]*>\\s*<param\\s+name=\"[^\"]*\"[^>]*>\\s*(?:<!\\[CDATA\\[([\\s\\S]*?)\\]\\]>|([\\s\\S]*?))\\s*</param>\\s*",
            Pattern.MULTILINE);

    /** bash 代码块：```bash ... ``` */
    static final Pattern FENCE = Pattern.compile(
            "```(bash|sh|shell)?\\s*\\n?([\\s\\S]*?)(?:```|\\z)",
            Pattern.MULTILINE);

    /** heredoc 检测：cat > path &lt;&lt; EOF（含 &gt; 或 &gt;&gt;） */
    private static final Pattern HEREDOC_CMD = Pattern.compile(
            "(?s)^cat\\s+(>|>>).*<<.*$");

    public static void main(String[] args) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line).append('\n');
        }
        String input = sb.toString();
        if (input.endsWith("__END__\n")) {
            input = input.substring(0, input.length() - "__END__\n".length());
        }
        String result = run(input);
        System.out.print(result);
    }

    /** 文本入口（CLI）：逐条执行并打印结果 */
    public static String run(String reply) {
        List<ExecResult> results = runWithResults(reply);
        StringBuilder sb = new StringBuilder();
        for (ExecResult r : results) {
            sb.append("$ ").append(r.raw).append('\n');
            if (r.result.stdout != null && !r.result.stdout.isEmpty()) sb.append(r.result.stdout).append('\n');
            if (r.result.stderr != null && !r.result.stderr.isEmpty()) sb.append(r.result.stderr).append('\n');
        }
        return sb.toString();
    }

    /** App 端入口：处理一条完整回复，返回全部执行结果 */
    public static List<ExecResult> runWithResults(String reply) {
        List<ExecResult> out = new ArrayList<>();
        if (reply == null || reply.isEmpty()) return out;

        // 1) XML 工具调用（MiniCPM5 主格式）
        Matcher fm = FUNC.matcher(reply);
        while (fm.find()) {
            String cmd = (fm.group(1) != null) ? fm.group(1)
                    : (fm.group(2) != null ? fm.group(2) : "");
            if (cmd == null || cmd.trim().isEmpty()) continue;
            execCommand(cmd, "工具调用", out);
        }

        // 2) bash 代码块（MiniCPM5 也会输出；若已被 XML 提取则跳过重复）
        Matcher fenceM = FENCE.matcher(reply);
        while (fenceM.find()) {
            String body = fenceM.group(2);
            if (body == null || body.trim().isEmpty()) continue;
            execBlock(body, "bash 代码块", out);
        }
        return out;
    }

    /** 执行一条命令串：heredoc 整体 / 普通命令按 && 拆分 */
    private static void execCommand(String cmd, String tag, List<ExecResult> out) {
        String line = cmd.trim();
        if (line.isEmpty()) return;
        if (HEREDOC_CMD.matcher(line).matches()) {
            // heredoc：内容里的 && 属于文件代码，不能拆
            List<ShellCommandParser.Command> cmds = new ArrayList<>();
            ShellCommandParser.parseBlock(line, cmds);
            for (ShellCommandParser.Command c : cmds) {
                out.add(new ExecResult(tag + ": " + c.raw, ShellCommandExecutor.execute(c)));
            }
            return;
        }
        // 普通命令可能含 && 串联（mkdir -p x && cd x）
        for (String part : line.split("&&")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            List<ShellCommandParser.Command> cmds = new ArrayList<>();
            ShellCommandParser.parseBlock(p, cmds);
            for (ShellCommandParser.Command c : cmds) {
                out.add(new ExecResult(tag + ": " + c.raw, ShellCommandExecutor.execute(c)));
            }
        }
    }

    /** 执行 bash 代码块内多行命令 */
    private static void execBlock(String block, String tag, List<ExecResult> out) {
        List<ShellCommandParser.Command> cmds = new ArrayList<>();
        ShellCommandParser.parseBlock(block, cmds);
        for (ShellCommandParser.Command c : cmds) {
            out.add(new ExecResult(tag + ": " + c.raw, ShellCommandExecutor.execute(c)));
        }
    }
}
