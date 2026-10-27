package com.aicodestudio.ide.ai.agent;

import android.content.Context;
import android.util.Log;

import com.aicodestudio.ide.ai.AICodeStudioApp;
import com.aicodestudio.ide.ai.compile.CompileExecutor;
import com.aicodestudio.ide.ai.tools.BuildToolManager;
import com.aicodestudio.ide.ai.core.WorkspaceState;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * ShellCommandExecutor —— 把模型的 shell 命令映射到 App 内部接口执行（v11.30a）
 *
 * 安全模型：
 * - 所有路径解析后必须落在项目根目录内（绝对路径前缀校验 + 规范化去 ../）
 * - rm 禁止删除项目根本身；命令白名单之外一律拒绝
 * - 不真正 fork shell，全部用 Java 文件 API 实现
 *
 * 支持的 App 接口命令：
 *   ls / cat / cat>heredoc / mkdir / rm / grep / find / pwd / aics compile / aics run
 */
public class ShellCommandExecutor {
    private static final String TAG = "ShellExecutor";

    public static class ToolResult {
        public final int exitCode;
        public final String stdout;
        public final String stderr;

        public ToolResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
        }

        public boolean ok() { return exitCode == 0; }

        /** 回填给模型的消息格式 */
        public String toPromptBlock() {
            StringBuilder sb = new StringBuilder();
            sb.append("[工具结果 exit=").append(exitCode).append("]\n");
            if (!stdout.isEmpty()) sb.append("标准输出:\n").append(stdout).append('\n');
            if (!stderr.isEmpty()) sb.append("错误输出:\n").append(stderr).append('\n');
            if (stdout.isEmpty() && stderr.isEmpty()) {
                sb.append("(无输出)\n");
            }
            return sb.toString().trim();
        }
    }

    private static volatile Context appContext;

    // ===== v1.49 对话工作目录（场景A/B）=====
    // AIChatManager 每次发送消息前 setWorkDir：
    //   场景B（对话绑定项目）→ 项目根目录；场景A（无项目）→ 全局工作空间（AI Code Studio/projects）
    // 所有命令的相对路径都基于此目录解析；cd 命令可切换（限制在全局工作空间内）。
    private static volatile String sWorkDir = null;

    public static void setWorkDir(String d) {
        sWorkDir = (d == null || d.isEmpty()) ? null : d;
    }

    public static String getWorkDir() {
        return sWorkDir;
    }

    /**
     * v1.51m：当前正在开发的项目名（AgentExecCLI 代码块兜底 prepend 用）。
     * 场景B（workDir=项目根，父目录为空间根）→ 返回 null（兜底路径相对项目根即可，不 prepend）；
     * 场景A（workDir=全局工作空间根）→ 返回最近修改的项目目录名（1.5B 代码块兜底路径常丢项目名前缀）。
     */
    public static String currentProjectName() {
        if (sWorkDir == null || sWorkDir.isEmpty() || appContext == null) return null;
        try {
            java.io.File root = com.aicodestudio.ide.ai.core.ProjectManager.getProjectsRoot(appContext);
            java.io.File wd = new java.io.File(sWorkDir);
            if (!wd.getCanonicalPath().equals(root.getCanonicalPath())) return null; // 场景B
            java.io.File[] dirs = root.listFiles(java.io.File::isDirectory);
            if (dirs == null) return null;
            java.io.File best = null;
            for (java.io.File d : dirs) {
                if (d.getName().startsWith(".")) continue;
                if (best == null || d.lastModified() > best.lastModified()) best = d;
            }
            return best != null ? best.getName() : null;
        } catch (Throwable ignored) {}
        return null;
    }

    /** Application 启动时注入（由 AICodeStudioApp 调用） */
    public static void attach(Context ctx) {
        if (ctx != null) appContext = ctx.getApplicationContext();
    }

    /** 当前项目根：对话工作目录 → WorkspaceState → 最近项目兜底 → 默认目录 */
    private static String projectRoot() {
        if (sWorkDir != null && !sWorkDir.isEmpty()) {
            return sWorkDir;
        }
        WorkspaceState ws = WorkspaceState.get();
        if (ws != null && ws.getProjectPath() != null && !ws.getProjectPath().isEmpty()) {
            return ws.getProjectPath();
        }
        // 兜底：App 重启后 WorkspaceState 丢失时，恢复最近项目（避免 agent 写到默认目录）
        if (appContext != null) {
            try {
                java.util.List<String> recents =
                        com.aicodestudio.ide.ai.core.AppConfig.getInstance(appContext).getRecentProjects();
                if (!recents.isEmpty()) {
                    ws.setProjectPath(recents.get(0));
                    return recents.get(0);
                }
            } catch (Throwable ignored) {
            }
        }
        return "/sdcard/Download/AI_Code_Studio";
    }

    /** 执行一条解析后的命令，返回工具结果（不抛异常） */
    public static ToolResult execute(ShellCommandParser.Command cmd) {
        try {
            if (cmd == null || cmd.argv.length == 0) {
                return new ToolResult(0, "", "");
            }
            // 管道/重定向处理（仅非 heredoc 命令；heredoc 的 >/>> 已由解析器专用处理）
            if (cmd.heredocContent == null) {
                ToolResult pipe = tryPipe(cmd);
                if (pipe != null) return pipe;
                ToolResult redir = tryRedirect(cmd);
                if (redir != null) return redir;
            }
            String name = cmd.first();
            switch (name) {
                case "ls":      return doLs(cmd);
                case "cat":     return doCat(cmd);
                case "mkdir":   return doMkdir(cmd);
                case "touch":   return doTouch(cmd);
                case "rm":      return doRm(cmd);
                case "cp":      return doCp(cmd);
                case "mv":      return doMv(cmd);
                case "grep":    return doGrep(cmd);
                case "find":    return doFind(cmd);
                case "sed":     return doSed(cmd);
                case "head":    return doHeadTail(cmd, true);
                case "tail":    return doHeadTail(cmd, false);
                case "wc":      return doWc(cmd);
                case "echo":    return doEcho(cmd);
                case "cd":      return doCd(cmd);
                case "pwd":     return new ToolResult(0, projectRoot(), "");
                case "aics":    return doAics(cmd);
                default:
                    return new ToolResult(1, "", "不支持的命令: " + name
                            + "（仅支持 ls/cat/mkdir/touch/rm/cp/mv/grep/find/sed/head/tail/wc/echo/cd/pwd/aics）");
            }
        } catch (Throwable t) {
            Log.e(TAG, "execute failed", t);
            return new ToolResult(1, "", "执行异常: " + t.getMessage());
        }
    }

    // ===== 路径安全解析 =====
    /** 把命令参数解析为项目内绝对路径；越界返回 null。
     *  路径通常位于命令尾部且可能含空格（如 /storage/emulated/0/AI Code Studio/...），
     *  故将 idx 之后的参数用空格重新连接为完整路径。 */
    private static File resolve(ShellCommandParser.Command cmd, int idx) {
        String root = projectRoot();
        if (idx >= cmd.argv.length) return new File(root);
        StringBuilder sb = new StringBuilder();
        for (int i = idx; i < cmd.argv.length; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(cmd.argv[i]);
        }
        String arg = sb.toString();
        if (arg.isEmpty()) return new File(root);
        File base = new File(root);
        File target;
        if (arg.startsWith("/")) {
            target = new File(arg);
        } else {
            target = new File(base, arg);
        }
        // 规范化，防 ../ 越权
        String canon = null;
        try {
            canon = target.getCanonicalPath();
        } catch (Throwable t) {
            return null;
        }
        String rootCanon = null;
        try {
            rootCanon = base.getCanonicalPath();
        } catch (Throwable t) {
            rootCanon = root;
        }
        if (canon.equals(rootCanon)) return target; // 允许项目根本身
        if (!canon.startsWith(rootCanon + File.separator)) {
            // v1.49 路径容错：1.5B 常把工作空间名/绝对路径写进相对命令
            // （如 "AI Code Studio/projects/2048"、"projects/2048"），剥掉这些前缀锚回工作空间
            File fixed = stripSpacePrefix(canon, rootCanon, base);
            if (fixed == null) return null; // 越界
            return fixed;
        }
        return target;
    }

    /** 剥掉命令路径中误带的工作空间前缀（大小写不敏感匹配根目录名），锚回工作空间内；无法归位返回 null */
    private static File stripSpacePrefix(String canon, String rootCanon, File base) {
        try {
            String rootName = base.getName(); // 工作空间根目录名（如 projects）
            if (rootName.isEmpty()) return null;
            // 找 canon 中 rootName（不区分大小写）最后一次出现，取其后的子路径拼回 rootCanon
            int idx = canon.toLowerCase().lastIndexOf(rootName.toLowerCase());
            if (idx < 0) return null;
            String tail = canon.substring(idx + rootName.length());
            // tail 形如 "/2048/app" 或 "/2048"
            if (!tail.startsWith(File.separator)) return null;
            File fixed = new File(rootCanon, tail);
            String fCanon = fixed.getCanonicalPath();
            if (!fCanon.startsWith(rootCanon + File.separator)) return null;
            return fixed;
        } catch (Throwable t) {
            return null;
        }
    }

    // ===== v1.49 cd：切换对话工作目录（限制在全局工作空间 AI Code Studio/projects 内）=====
    private static ToolResult doCd(ShellCommandParser.Command cmd) {
        if (cmd.argv.length < 2 || cmd.argv[1].isEmpty()) {
            return new ToolResult(0, projectRoot(), ""); // 无参 cd → 显示当前目录
        }
        String arg = cmd.argv[1];
        File base = new File(projectRoot());
        File target = arg.startsWith("/") ? new File(arg) : new File(base, arg);
        try {
            String canon = target.getCanonicalPath();
            // v1.49 路径容错：模型可能 cd 带工作空间前缀的路径（如 "AI Code Studio/projects/2048"）
            File spaceRoot = null;
            if (appContext != null) {
                try {
                    spaceRoot = com.aicodestudio.ide.ai.core.ProjectManager
                            .getProjectsRoot(appContext).getCanonicalFile();
                } catch (Throwable ignored) {
                }
            }
            if (spaceRoot != null) {
                String sp = spaceRoot.getAbsolutePath();
                if (!canon.equals(sp) && !canon.startsWith(sp + File.separator)) {
                    File fixed = stripSpacePrefix(canon, sp, spaceRoot);
                    if (fixed == null) {
                        return new ToolResult(1, "", "不允许跳出工作空间: " + arg);
                    }
                    canon = fixed.getCanonicalPath();
                    target = fixed;
                }
            }
            File f = new File(canon);
            if (!f.exists()) return new ToolResult(1, "", "不存在: " + arg);
            if (!f.isDirectory()) return new ToolResult(1, "", "不是目录: " + arg);
            setWorkDir(canon);
            return new ToolResult(0, "已进入目录: " + canon, "");
        } catch (Throwable t) {
            return new ToolResult(1, "", "cd 失败: " + t.getMessage());
        }
    }

    // ===== ls =====
    private static ToolResult doLs(ShellCommandParser.Command cmd) {
        boolean recursive = false;
        int idx = 1;
        while (idx < cmd.argv.length && cmd.argv[idx].startsWith("-")) {
            String opt = cmd.argv[idx];
            if (opt.contains("R") || opt.contains("r")) recursive = true;
            idx++;
        }
        File dir = resolve(cmd, idx);
        if (dir == null) return new ToolResult(1, "", "路径越界或无效");
        if (!dir.exists()) return new ToolResult(1, "", "不存在: " + dir.getPath());
        if (dir.isFile()) return new ToolResult(0, dir.getPath() + " (文件)", "");

        StringBuilder sb = new StringBuilder();
        if (recursive) {
            listRecursive(dir, "", sb);
        } else {
            File[] files = dir.listFiles();
            if (files == null) return new ToolResult(0, "(空目录)", "");
            for (File f : files) {
                String type = f.isDirectory() ? "d" : "-";
                sb.append(type).append(' ').append(f.isDirectory() ? "[dir] " : "")
                  .append(f.getName()).append('\n');
            }
        }
        return new ToolResult(0, sb.toString().trim(), "");
    }

    private static void listRecursive(File dir, String prefix, StringBuilder sb) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            sb.append(prefix).append(f.getName());
            if (f.isDirectory()) {
                sb.append('/');
                sb.append('\n');
                listRecursive(f, prefix + f.getName() + "/", sb);
            } else {
                sb.append('\n');
            }
        }
    }

    // ===== cat =====
    private static ToolResult doCat(ShellCommandParser.Command cmd) {
        // heredoc 写入
        if (cmd.heredocPath != null) {
            File target = resolvePath(projectRoot(), cmd.heredocPath);
            if (target == null) return new ToolResult(1, "", "路径越界: " + cmd.heredocPath);
            if (isProtectedMem(target)) {
                return new ToolResult(1, "", "禁止直接写入记忆保护区 .aics，请用 aics mem add / replace 管理项目记忆");
            }
            try {
                File parent = target.getParentFile();
                if (parent != null) parent.mkdirs();
                String content = cmd.heredocContent == null ? "" : cmd.heredocContent;
                if (!content.endsWith("\n")) content += "\n";
                FileOutputStream fos = new FileOutputStream(target, false);
                fos.write(content.getBytes(StandardCharsets.UTF_8));
                fos.close();
                return new ToolResult(0, "已写入 " + target.getPath() + " (" + content.length() + " 字符)", "");
            } catch (Throwable t) {
                return new ToolResult(1, "", "写入失败: " + t.getMessage());
            }
        }
        // 读文件
        if (cmd.argv.length < 2) return new ToolResult(1, "", "用法: cat <文件路径>");
        File f = resolve(cmd, 1);
        if (f == null) return new ToolResult(1, "", "路径越界或无效");
        if (!f.exists() || f.isDirectory()) return new ToolResult(1, "", "不存在或为目录: " + f.getPath());
        try {
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            byte[] buf = new byte[(int) Math.min(f.length(), 512 * 1024)];
            int n = fis.read(buf);
            fis.close();
            String text = n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8) : "";
            return new ToolResult(0, text, "");
        } catch (Throwable t) {
            return new ToolResult(1, "", "读取失败: " + t.getMessage());
        }
    }

    private static File resolvePath(String root, String arg) {
        File base = new File(root);
        File target = arg.startsWith("/") ? new File(arg) : new File(base, arg);
        try {
            String canon = target.getCanonicalPath();
            String rootCanon = base.getCanonicalPath();
            if (canon.equals(rootCanon) || canon.startsWith(rootCanon + File.separator)) {
                // v1.51l 双前缀容错（云端完整复刻测试发现）：
                // 场景B workDir=项目根，提示词示例是"项目名/app/..."（带项目名），
                // 1.5B 照抄示例 → 命令路径 root/项目名/...（如 Weather/Weather/app/...），
                // 在 rootCanon 内所以旧逻辑直接返回 → 产生双前缀目录。
                // 检测"root 名重复段"（root/rootName/...）并剥掉 → root/...（单前缀）。
                String rootName = base.getName();
                if (!rootName.isEmpty()) {
                    String tail = canon.substring(rootCanon.length());
                    if (tail.startsWith(File.separator + rootName + File.separator)) {
                        String fixedTail = tail.substring(rootName.length() + 1);
                        File fixed = new File(rootCanon, fixedTail);
                        if (fixed.getCanonicalPath().startsWith(rootCanon + File.separator)) {
                            return fixed;
                        }
                    }
                }
                return target;
            }
            // v1.49 路径容错：1.5B 常把工作空间名/绝对路径写进相对命令（如 "com/aicodestudio/ide/projects/2048"），
            // 剥掉这些前缀锚回工作空间
            File fixed = stripSpacePrefix(canon, rootCanon, base);
            if (fixed == null) return null; // 越界
            return fixed;
        } catch (Throwable ignored) {}
        return null;
    }

    // ===== mkdir =====
    // ===== 管道（cmd1 | cmd2）与重定向（> file / >> file）支持 =====
    /** 按 | 拆分命令行（跳过引号内），返回 null 表示无管道 */
    private static List<String> splitPipe(String raw) {
        List<String> segs = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inSingle = false, inDouble = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inSingle) { if (c == '\'') inSingle = false; else cur.append(c); }
            else if (inDouble) { if (c == '"') inDouble = false; else cur.append(c); }
            else if (c == '\'') inSingle = true;
            else if (c == '"') inDouble = true;
            else if (c == '|') { segs.add(cur.toString().trim()); cur.setLength(0); }
            else cur.append(c);
        }
        segs.add(cur.toString().trim());
        // 去掉空段
        List<String> out = new ArrayList<>();
        for (String t : segs) if (!t.isEmpty()) out.add(t);
        return out.size() > 1 ? out : null;
    }

    private static ToolResult tryPipe(ShellCommandParser.Command cmd) {
        List<String> segs = splitPipe(cmd.raw);
        if (segs == null) return null;
        String cur = "";
        for (int i = 0; i < segs.size(); i++) {
            String seg = segs.get(i);
            if (i == 0) {
                ToolResult r = execute(ShellCommandParser.simple(seg));
                if (!r.ok()) return new ToolResult(1, "", "管道前段失败: " + seg + "\n" + r.stderr);
                cur = r.stdout == null ? "" : r.stdout;
            } else {
                List<String> a = ShellCommandParser.tokenize(seg);
                if (a.isEmpty()) return new ToolResult(1, "", "管道段为空");
                String n = a.get(0);
                if ("grep".equals(n)) {
                    if (a.size() < 2) return new ToolResult(1, "", "grep 用法: grep <关键词>（管道后段）");
                    String pattern = a.get(1);
                    boolean ignoreCase = false;
                    for (int k = 1; k < a.size(); k++) {
                        if (a.get(k).equals("-i") || a.get(k).equals("-rn") || a.get(k).equals("-r")) {
                            if (a.get(k).contains("i") && !a.get(k).equals("-r")) ignoreCase = true;
                        }
                    }
                    StringBuilder out = new StringBuilder();
                    for (String line : cur.split("\n")) {
                        boolean hit = ignoreCase ? line.toLowerCase().contains(pattern.toLowerCase())
                                                 : line.contains(pattern);
                        if (hit) out.append(line).append("\n");
                    }
                    cur = out.toString();
                } else if ("cat".equals(n) && a.size() == 1) {
                    // 透传
                } else if ("wc".equals(n)) {
                    int lines = cur.isEmpty() ? 0 : cur.split("\n").length;
                    int chars = cur.length();
                    cur = lines + " " + chars;
                } else {
                    return new ToolResult(1, "", "管道暂不支持: " + seg + "（支持 | grep <关键词> / | cat / | wc）");
                }
            }
        }
        return new ToolResult(0, cur.trim(), "");
    }

    /** 重定向：cmd > file / cmd >> file（从 argv 剥离后执行并写文件），无重定向返回 null */
    private static ToolResult tryRedirect(ShellCommandParser.Command cmd) {
        String[] argv = cmd.argv;
        int redirIdx = -1;
        boolean append = false;
        for (int i = 1; i < argv.length; i++) {
            if (argv[i].equals(">") || argv[i].equals(">>")) {
                redirIdx = i;
                append = argv[i].equals(">>");
                break;
            }
        }
        if (redirIdx < 0) return null;
        if (redirIdx + 1 >= argv.length) {
            return new ToolResult(1, "", "重定向缺少目标文件: " + cmd.raw);
        }
        String path = argv[redirIdx + 1];
        // 检查后续是否还有参数（如 <<EOF 等非法组合）
        if (redirIdx + 2 < argv.length) {
            return new ToolResult(1, "", "重定向后不应再有参数（heredoc 请用 cat > 路径 <<'EOF' 格式）: " + cmd.raw);
        }
        String[] newArgv = new String[redirIdx];
        System.arraycopy(argv, 0, newArgv, 0, redirIdx);
        ShellCommandParser.Command base = new ShellCommandParser.Command(cmd.raw, newArgv, null, null);
        ToolResult r = execute(base);
        if (!r.ok()) return r;
        // 写文件
        File target = resolve(new ShellCommandParser.Command(cmd.raw, new String[]{">", path}, null, null), 1);
        if (target == null) return new ToolResult(1, "", "重定向路径越界: " + path);
        if (isProtectedMem(target)) {
            return new ToolResult(1, "", "禁止写入记忆保护区 .aics，请用 aics mem 管理项目记忆");
        }
        try {
            File parent = target.getParentFile();
            if (parent != null) parent.mkdirs();
            String content = r.stdout == null ? "" : r.stdout;
            FileOutputStream fos = new FileOutputStream(target, append);
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            fos.close();
            String act = append ? "追加" : "写入";
            return new ToolResult(0, "已" + act + " " + target.getPath() + " (" + content.length() + " 字符)", "");
        } catch (Throwable t) {
            return new ToolResult(1, "", "重定向写文件失败: " + t.getMessage());
        }
    }

    private static ToolResult doTouch(ShellCommandParser.Command cmd) {
        if (cmd.argv.length < 2) return new ToolResult(1, "", "用法: touch <文件路径>");
        File f = resolve(cmd, 1);
        if (f == null) return new ToolResult(1, "", "路径越界或无效");
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                return new ToolResult(1, "", "创建父目录失败: " + parent.getPath());
            }
            if (f.createNewFile()) {
                return new ToolResult(0, "已创建空文件: " + f.getPath(), "");
            }
            return new ToolResult(0, "文件已存在: " + f.getPath(), "");
        } catch (Throwable t) {
            return new ToolResult(1, "", "创建失败: " + t.getMessage());
        }
    }

    private static ToolResult doMkdir(ShellCommandParser.Command cmd) {
        int idx = 1;
        boolean parent = false;
        while (idx < cmd.argv.length && cmd.argv[idx].startsWith("-")) {
            if (cmd.argv[idx].contains("p")) parent = true;
            idx++;
        }
        if (idx >= cmd.argv.length) return new ToolResult(1, "", "用法: mkdir [-p] <路径...>");
        // v1.42：支持多路径参数（mkdir -p a b c），逐个创建。
        // 注意：不能用 resolve(cmd, i)（它会把剩余参数拼成一个带空格的路径），必须单参数解析。
        String root = projectRoot();
        StringBuilder created = new StringBuilder();
        for (int i = idx; i < cmd.argv.length; i++) {
            File dir = resolvePath(root, cmd.argv[i]);
            if (dir == null) return new ToolResult(1, "", "路径越界或无效: " + cmd.argv[i]);
            try {
                boolean ok = parent
                        ? (dir.mkdirs() || dir.isDirectory())
                        : (dir.mkdir());
                if (!ok) return new ToolResult(1, "", "创建失败（目录已存在？）: " + dir.getPath());
                if (created.length() > 0) created.append("\n");
                created.append("已创建: ").append(dir.getPath());
            } catch (Throwable t) {
                return new ToolResult(1, "", "创建失败: " + t.getMessage());
            }
        }
        return new ToolResult(0, created.toString(), "");
    }

    // ===== rm =====
    private static ToolResult doRm(ShellCommandParser.Command cmd) {
        boolean recursive = false;
        int idx = 1;
        while (idx < cmd.argv.length && cmd.argv[idx].startsWith("-")) {
            if (cmd.argv[idx].contains("r") || cmd.argv[idx].contains("R")) recursive = true;
            idx++;
        }
        if (idx >= cmd.argv.length) return new ToolResult(1, "", "用法: rm [-r] <路径>");
        File target = resolve(cmd, idx);
        if (target == null) return new ToolResult(1, "", "路径越界或无效");
        if (isProtectedMem(target)) {
            return new ToolResult(1, "", "禁止删除记忆保护区 .aics（项目记忆不可删除，只能 aics mem replace 修改）");
        }
        // 保护项目根
        String root = projectRoot();
        try {
            if (target.getCanonicalPath().equals(new File(root).getCanonicalPath())) {
                return new ToolResult(1, "", "禁止删除项目根目录");
            }
        } catch (Throwable ignored) {}
        if (!target.exists()) return new ToolResult(1, "", "不存在: " + target.getPath());
        try {
            if (target.isDirectory() && !recursive) {
                return new ToolResult(1, "", "是目录，需加 -r: " + target.getPath());
            }
            boolean ok = deleteRecursive(target);
            return ok ? new ToolResult(0, "已删除: " + target.getPath(), "")
                      : new ToolResult(1, "", "删除失败: " + target.getPath());
        } catch (Throwable t) {
            return new ToolResult(1, "", "删除失败: " + t.getMessage());
        }
    }

    private static boolean deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        return f.delete();
    }

    // ===== grep =====
    private static ToolResult doGrep(ShellCommandParser.Command cmd) {
        boolean recursive = false;
        int idx = 1;
        while (idx < cmd.argv.length && cmd.argv[idx].startsWith("-")) {
            String opt = cmd.argv[idx];
            if (opt.contains("r") || opt.contains("R")) recursive = true;
            idx++;
        }
        if (idx >= cmd.argv.length) return new ToolResult(1, "", "用法: grep [-rn] <关键词> [路径]");
        String keyword = cmd.argv[idx++];
        File dir = idx < cmd.argv.length ? resolve(cmd, idx) : new File(projectRoot());
        if (dir == null) return new ToolResult(1, "", "路径越界或无效");
        if (!dir.exists()) return new ToolResult(1, "", "不存在: " + dir.getPath());
        StringBuilder sb = new StringBuilder();
        int hits = 0;
        List<File> files = new ArrayList<>();
        collectFiles(dir, recursive, files);
        for (File f : files) {
            try {
                if (f.length() > 1024 * 1024) continue; // 跳过大文件
                java.io.FileInputStream fis = new java.io.FileInputStream(f);
                byte[] buf = new byte[(int) Math.min(f.length(), 512 * 1024)];
                int n = fis.read(buf);
                fis.close();
                if (n <= 0) continue;
                String text = new String(buf, 0, n, StandardCharsets.UTF_8);
                String[] lines = text.split("\n", -1);
                for (int li = 0; li < lines.length; li++) {
                    if (lines[li].contains(keyword)) {
                        hits++;
                        if (hits <= 200) {
                            sb.append(f.getPath()).append(':').append(li + 1).append(": ")
                              .append(lines[li].trim()).append('\n');
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        if (hits == 0) return new ToolResult(0, "未找到匹配: " + keyword, "");
        return new ToolResult(0, "匹配 " + hits + " 处:\n" + sb.toString().trim(), "");
    }

    private static void collectFiles(File dir, boolean recursive, List<File> out) {
        if (dir.isFile()) { out.add(dir); return; }
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File c : children) {
            if (c.isDirectory()) {
                if (recursive) collectFiles(c, true, out);
            } else {
                out.add(c);
            }
        }
    }

    // ===== find =====
    private static ToolResult doFind(ShellCommandParser.Command cmd) {
        // 支持: find [路径] [-name <模式>]（-name 过滤文件名，模式支持 * 通配）
        String namePattern = null;
        int argIdx = 1;
        for (int i = 1; i + 1 < cmd.argv.length; i++) {
            if (cmd.argv[i].equals("-name")) { namePattern = cmd.argv[i + 1]; argIdx = i; break; }
        }
        // 路径只取 argv[1]（不含 -name 等选项），且支持 find . （项目根）
        File dir;
        if (cmd.argv.length > 1) {
            String arg = cmd.argv[1];
            if (arg.equals(".") || arg.isEmpty()) dir = new File(projectRoot());
            else dir = resolvePath(projectRoot(), arg);
        } else {
            dir = new File(projectRoot());
        }
        if (dir == null) return new ToolResult(1, "", "路径越界或无效");
        if (!dir.exists()) return new ToolResult(1, "", "不存在: " + dir.getPath());
        String root = projectRoot();
        StringBuilder sb = new StringBuilder();
        walkFind(dir, root, sb, namePattern);
        return new ToolResult(0, sb.toString().trim(), "");
    }

    private static void walkFind(File dir, String root, StringBuilder sb) {
        walkFind(dir, root, sb, null);
    }

    private static void walkFind(File dir, String root, StringBuilder sb, String namePattern) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (namePattern != null) {
                // 简化通配：* 前缀 / * 后缀 / 包含
                String n = f.getName();
                boolean hit;
                if (namePattern.startsWith("*") && namePattern.endsWith("*")) {
                    hit = n.contains(namePattern.substring(1, namePattern.length() - 1));
                } else if (namePattern.startsWith("*")) {
                    hit = n.endsWith(namePattern.substring(1));
                } else if (namePattern.endsWith("*")) {
                    hit = n.startsWith(namePattern.substring(0, namePattern.length() - 1));
                } else {
                    hit = n.equals(namePattern);
                }
                if (!hit) continue;
            }
            String rel = f.getPath();
            try {
                String canon = f.getCanonicalPath();
                String rootCanon = new File(root).getCanonicalPath();
                if (canon.startsWith(rootCanon)) {
                    rel = canon.substring(rootCanon.length());
                    if (rel.isEmpty()) rel = "/";
                }
            } catch (Throwable ignored) {}
            sb.append(rel).append('\n');
            if (f.isDirectory()) walkFind(f, root, sb);
        }
    }

    // ===== aics 子命令 =====

    // ===== sed 行级编辑（sed -i 'Ns/旧/新/' file / sed -i 's/旧/新/g' file / sed -i 'Nc\新内容' file）=====
    private static ToolResult doSed(ShellCommandParser.Command cmd) {
        String[] argv = cmd.argv;
        if (argv.length < 4 || !"-i".equals(argv[1])) {
            return new ToolResult(1, "", "用法: sed -i '<表达式>' <文件>（仅支持 -i 原地编辑）");
        }
        String expr = argv[2];
        File f = resolve(cmd, 3);
        if (f == null) return new ToolResult(1, "", "路径越界或无效");
        if (!f.exists() || f.isDirectory()) return new ToolResult(1, "", "不存在或为目录: " + f.getPath());
        try {
            String text = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            // 表达式: [N]s/旧/新/[g] 或 [N]c\新内容 / [N]c 新内容
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(?:(\\d+))?(s|s\\\\)([\\s\\S]*)$").matcher(expr);
            String result;
            if (expr.startsWith("s/")) {
                // s/旧/新/ 或 s/旧/新/g
                int gIdx = expr.lastIndexOf("/");
                int slash1 = expr.indexOf("/", 2);
                if (slash1 < 0 || gIdx <= slash1) return new ToolResult(1, "", "sed 表达式格式错误: " + expr);
                String oldPat = expr.substring(2, slash1);
                String rest = expr.substring(slash1 + 1);
                int g2 = rest.lastIndexOf("/");
                String newText = g2 >= 0 ? rest.substring(0, g2) : rest;
                boolean global = rest.endsWith("/g") || rest.endsWith("/G");
                String q = java.util.regex.Pattern.quote(oldPat);
                if (global) result = text.replaceAll(q, newText);
                else {
                    int idx = text.indexOf(oldPat);
                    if (idx < 0) return new ToolResult(0, "未找到匹配: " + oldPat, "");
                    result = text.substring(0, idx) + newText + text.substring(idx + oldPat.length());
                }
            } else if (expr.contains("c\\") || expr.contains("c ")) {
                // Nc\新内容 或 Nc 新内容：替换第 N 行
                java.util.regex.Matcher cm = java.util.regex.Pattern
                    .compile("^(\\d+)c(?:\\\\|\\s)([\\s\\S]*)$").matcher(expr);
                if (!cm.matches()) return new ToolResult(1, "", "sed 行替换格式错误（期望 Nc\\新内容）: " + expr);
                int line = Integer.parseInt(cm.group(1));
                String newLine = cm.group(2);
                String[] lines = text.split("\\n", -1);
                if (line < 1 || line > lines.length) return new ToolResult(1, "", "行号越界: " + line + "（共 " + lines.length + " 行）");
                lines[line - 1] = newLine;
                result = String.join("\n", lines);
            } else {
                return new ToolResult(1, "", "sed 表达式不支持: " + expr + "（支持 s/旧/新/、s/旧/新/g、Nc\\新内容）");
            }
            java.nio.file.Files.write(f.toPath(), result.getBytes(StandardCharsets.UTF_8));
            return new ToolResult(0, "已编辑 " + f.getName(), "");
        } catch (Throwable t) {
            return new ToolResult(1, "", "sed 失败: " + t.getMessage());
        }
    }

    // ===== echo：输出文本（配合 > / >> 重定向写文件）=====
    private static ToolResult doEcho(ShellCommandParser.Command cmd) {
        if (cmd.argv.length < 2) return new ToolResult(0, "", "");
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < cmd.argv.length; i++) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(cmd.argv[i]);
        }
        return new ToolResult(0, sb.toString(), "");
    }

    // ===== cp 复制 =====
    private static ToolResult doCp(ShellCommandParser.Command cmd) {
        if (cmd.argv.length < 3) return new ToolResult(1, "", "用法: cp <源> <目标>");
        File src = resolvePath(projectRoot(), cmd.argv[1]);
        File dst = resolvePath(projectRoot(), cmd.argv[2]);
        if (src == null || dst == null) return new ToolResult(1, "", "路径越界或无效");
        if (!src.exists()) return new ToolResult(1, "", "源不存在: " + src.getPath());
        try {
            if (src.isDirectory()) {
                return new ToolResult(1, "", "暂不支持复制目录，请用 cp 复制单个文件");
            }
            File parent = dst.getParentFile();
            if (parent != null) parent.mkdirs();
            java.nio.file.Files.copy(src.toPath(), dst.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return new ToolResult(0, "已复制: " + dst.getName(), "");
        } catch (Throwable t) {
            return new ToolResult(1, "", "复制失败: " + t.getMessage());
        }
    }

    // ===== mv 重命名/移动（仅限项目内；不允许跨目录移动，保持项目结构稳定）=====
    private static ToolResult doMv(ShellCommandParser.Command cmd) {
        if (cmd.argv.length < 3) return new ToolResult(1, "", "用法: mv <旧名> <新名>（仅改文件名，不能移动目录）");
        File src = resolvePath(projectRoot(), cmd.argv[1]);
        File dst = resolvePath(projectRoot(), cmd.argv[2]);
        if (src == null || dst == null) return new ToolResult(1, "", "路径越界或无效");
        if (!src.exists()) return new ToolResult(1, "", "源不存在: " + src.getPath());
        if (!src.getParentFile().equals(dst.getParentFile())) {
            return new ToolResult(1, "", "禁止移动文件目录，只能重命名（mv 源文件与目标必须在同一目录）");
        }
        try {
            java.nio.file.Files.move(src.toPath(), dst.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return new ToolResult(0, "已重命名: " + src.getName() + " -> " + dst.getName(), "");
        } catch (Throwable t) {
            return new ToolResult(1, "", "重命名失败: " + t.getMessage());
        }
    }

    // ===== head / tail 查看文件开头/结尾 =====
    private static ToolResult doHeadTail(ShellCommandParser.Command cmd, boolean isHead) {
        int n = 10;
        int fileIdx = 1;
        if (cmd.argv.length >= 3 && cmd.argv[1].startsWith("-n")) {
            try { n = Integer.parseInt(cmd.argv[2]); } catch (Throwable ignored) {}
            fileIdx = 3;
        } else if (cmd.argv.length >= 2 && cmd.argv[1].startsWith("-")) {
            try { n = Integer.parseInt(cmd.argv[1].substring(1)); } catch (Throwable ignored) {}
            fileIdx = 2;
        }
        File f = resolve(cmd, fileIdx);
        if (f == null) return new ToolResult(1, "", "路径越界或无效");
        if (!f.exists() || f.isDirectory()) return new ToolResult(1, "", "不存在或为目录: " + f.getPath());
        try {
            String text = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            String[] lines = text.split("\n", -1);
            StringBuilder sb = new StringBuilder();
            if (isHead) {
                for (int i = 0; i < Math.min(n, lines.length); i++) sb.append(lines[i]).append("\n");
            } else {
                for (int i = Math.max(0, lines.length - n); i < lines.length; i++) sb.append(lines[i]).append("\n");
            }
            return new ToolResult(0, sb.toString().trim(), "");
        } catch (Throwable t) {
            return new ToolResult(1, "", "读取失败: " + t.getMessage());
        }
    }

    // ===== wc 行数/字节统计 =====
    private static ToolResult doWc(ShellCommandParser.Command cmd) {
        boolean linesOnly = cmd.argv.length >= 2 && "-l".equals(cmd.argv[1]);
        int idx = linesOnly ? 2 : 1;
        File f = resolve(cmd, idx);
        if (f == null) return new ToolResult(1, "", "路径越界或无效");
        if (!f.exists() || f.isDirectory()) return new ToolResult(1, "", "不存在或为目录: " + f.getPath());
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
            if (linesOnly) {
                String text = new String(bytes, StandardCharsets.UTF_8);
                int lines = text.split("\n", -1).length - (text.endsWith("\n") ? 1 : 0);
                return new ToolResult(0, lines + " " + f.getName(), "");
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            int lines = text.split("\n", -1).length - (text.endsWith("\n") ? 1 : 0);
            return new ToolResult(0, lines + " " + bytes.length + " " + f.getName(), "");
        } catch (Throwable t) {
            return new ToolResult(1, "", "统计失败: " + t.getMessage());
        }
    }

    private static ToolResult doAics(ShellCommandParser.Command cmd) {
        if (cmd.argv.length < 2) {
            return new ToolResult(1, "", "用法: aics compile / aics run / aics mem [add|replace|list]");
        }
        String sub = cmd.argv[1];
        switch (sub) {
            case "compile": return doCompile(false);
            case "run":     return doCompile(true);
            case "mem":     return doMem(cmd);
            case "create-project":
                // v1.49：已禁用。创建项目一律由 AI 用 mkdir/cat 在当前工作空间一步步搭建，
                // 避免系统模板创建导致"用户建一个、AI 又建一个"的目录错乱。
                return new ToolResult(1, "", "aics create-project 已停用：请在当前工作空间用 mkdir -p 建目录、cat > 写文件，一步步搭建项目。");
            default:
                return new ToolResult(1, "", "未知 aics 子命令: " + sub + "（支持 compile / run / mem）");
        }
    }

    // ===== aics mem：第二层记忆（L2，AI 可写可改不可删，token 预算约束 + 超预算自动压缩摘要） =====
    private static final int MEM_BUDGET_CHARS = 800;   // ≈300 token（Qwen 中文约 1.7 token/字）
    private static final int MEM_MAX_ITEMS = 8;        // 条目数上限，超出触发自动压缩
    private static final int MEM_KEEP_NEWEST = 2;      // 压缩时保留的最近条目数
    private static final String MEM_BUDGET_TIP =
            "记忆区预算约 300 token（≈800 字符，6~8 条）";

    /**
     * L2 自动压缩摘要：预算超限时保留最近 MEM_KEEP_NEWEST 条，
     * 其余合并为一条 [摘要] 条目（每条截断 60 字符，旧摘要不再展开防嵌套），
     * 最后强约束总字符 ≤ MEM_BUDGET_CHARS。
     */
    private static List<String> compressMem(List<String> lines, String newContent) {
        if (lines.size() <= MEM_MAX_ITEMS && totalChars(lines) + newContent.length() <= MEM_BUDGET_CHARS) {
            lines.add(newContent);
            return lines;
        }
        int keep = Math.min(MEM_KEEP_NEWEST, lines.size());
        List<String> recent = new ArrayList<>(lines.subList(lines.size() - keep, lines.size()));
        List<String> old = new ArrayList<>(lines.subList(0, lines.size() - keep));
        StringBuilder summary = new StringBuilder("[摘要] ");
        int budget = MEM_BUDGET_CHARS - totalChars(recent) - newContent.length() - 10;
        for (int i = 0; i < old.size() && summary.length() < budget; i++) {
            String item = old.get(i);
            if (item.startsWith("[摘要]") || item.startsWith("[自动摘要]")) {
                item = "旧摘要";
            } else {
                item = item.length() > 60 ? item.substring(0, 60) + "…" : item;
            }
            String part = "·" + item;
            if (summary.length() + part.length() > budget) break;
            summary.append(part);
        }
        List<String> out = new ArrayList<>();
        if (summary.length() > "[摘要] ".length()) out.add(summary.toString());
        out.addAll(recent);
        out.add(newContent);
        // 最终强约束：总字符 ≤ 预算（从最末条目截断）
        int over = totalChars(out) - MEM_BUDGET_CHARS;
        int guard = 0;
        while (over > 0 && out.size() > 1 && guard++ < 50) {
            int idx = out.size() - 1;
            String last = out.get(idx);
            int cut = Math.min(over + 5, Math.max(1, last.length()));
            out.set(idx, last.substring(0, Math.max(1, last.length() - cut)));
            over = totalChars(out) - MEM_BUDGET_CHARS;
        }
        return out;
    }

    private static int totalChars(List<String> lines) {
        int n = 0;
        for (String l : lines) n += l.length();
        return n;
    }

    /** .aics 记忆保护区：禁止 rm / cat> 直接操作（记忆只能经 aics mem 命令管理） */
    private static boolean isProtectedMem(File f) {
        try {
            String canon = f.getCanonicalPath();
            File root = new File(projectRoot());
            File aics = new File(root, ".aics");
            String aicsCanon = aics.getCanonicalPath();
            return canon.equals(aicsCanon) || canon.startsWith(aicsCanon + File.separator);
        } catch (Throwable t) {
            return false;
        }
    }

    private static File memFile() {
        return new File(projectRoot(), ".aics/memory.md");
    }

    private static List<String> readMem() {
        List<String> out = new ArrayList<>();
        try {
            File f = memFile();
            if (!f.isFile()) return out;
            String all = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            for (String line : all.split("\n")) {
                String t = line.trim();
                if (t.isEmpty()) continue;
                if (t.startsWith("- ")) t = t.substring(2).trim();
                if (t.startsWith("* ")) t = t.substring(2).trim();
                if (!t.isEmpty()) out.add(t);
            }
        } catch (Throwable t) {
            // 读失败按空处理
        }
        return out;
    }

    private static boolean writeMem(List<String> lines) {
        try {
            File f = memFile();
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return false;
            StringBuilder sb = new StringBuilder();
            for (String line : lines) sb.append("- ").append(line).append("\n");
            java.nio.file.Files.write(f.toPath(),
                    sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static ToolResult doMem(ShellCommandParser.Command cmd) {
        File f = memFile();
        String sub = cmd.argv.length > 2 ? cmd.argv[2] : "";
        if (sub.isEmpty() || sub.equals("list")) {
            List<String> lines = readMem();
            if (lines.isEmpty()) {
                return new ToolResult(0, "（当前无记忆条目）\n" + MEM_BUDGET_TIP
                        + "。用法：aics mem add <内容> 保存；aics mem replace <序号> <新内容> 修改（不可删除）。", "");
            }
            StringBuilder sb = new StringBuilder("当前项目记忆（" + MEM_BUDGET_TIP + "）：\n");
            for (int i = 0; i < lines.size(); i++) sb.append((i + 1)).append(". ").append(lines.get(i)).append("\n");
            sb.append("修改用：aics mem replace <序号> <新内容>（不可删除）");
            return new ToolResult(0, sb.toString().trim(), "");
        }
        if (sub.equals("add")) {
            String content = joinArgs(cmd.argv, 3).trim();
            if (content.isEmpty()) return new ToolResult(1, "", "用法: aics mem add <内容>（一行一条，简明扼要）");
            List<String> lines = readMem();
            boolean compressed = !(lines.size() <= MEM_MAX_ITEMS && totalChars(lines) + content.length() <= MEM_BUDGET_CHARS);
            lines = compressMem(lines, content);
            if (!writeMem(lines)) return new ToolResult(1, "", "写入记忆文件失败: " + f.getPath());
            String note = compressed
                    ? "已保存记忆（预算超限，已自动压缩旧条目为 [自动摘要]，保留最近 " + MEM_KEEP_NEWEST + " 条）: " + content
                    : "已保存记忆（" + MEM_BUDGET_TIP + "，当前 " + lines.size() + " 条）: " + content;
            return new ToolResult(0, note, "");
        }
        if (sub.equals("replace")) {
            List<String> lines = readMem();
            if (cmd.argv.length < 5) {
                return new ToolResult(1, "", "用法: aics mem replace <序号> <新内容>（序号见 aics mem 列表）");
            }
            int idx;
            try { idx = Integer.parseInt(cmd.argv[3]); } catch (Throwable t) {
                return new ToolResult(1, "", "序号必须是数字: " + cmd.argv[3]);
            }
            if (idx < 1 || idx > lines.size()) {
                return new ToolResult(1, "", "序号越界（当前共 " + lines.size() + " 条）: " + idx);
            }
            String content = joinArgs(cmd.argv, 4).trim();
            if (content.isEmpty()) return new ToolResult(1, "", "新内容不能为空");
            int used = 0;
            for (int i = 0; i < lines.size(); i++) {
                if (i != idx - 1) used += lines.get(i).length();
            }
            if (used + content.length() > MEM_BUDGET_CHARS) {
                return new ToolResult(1, "", MEM_BUDGET_TIP + "，替换后超上限。请精简内容后重试。");
            }
            lines.set(idx - 1, content);
            if (!writeMem(lines)) return new ToolResult(1, "", "写入记忆文件失败: " + f.getPath());
            return new ToolResult(0, "已修改记忆第 " + idx + " 条（" + MEM_BUDGET_TIP + "）: " + content, "");
        }
        return new ToolResult(1, "", "未知 mem 子命令: " + sub + "（支持 add / replace / list）");
    }

    private static String joinArgs(String[] argv, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < argv.length; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(argv[i]);
        }
        return sb.toString();
    }

    private static ToolResult doCompile(final boolean run) {
        final Context ctx = appContext;
        if (ctx == null) return new ToolResult(1, "", "App 上下文未就绪");
        WorkspaceState ws = WorkspaceState.get();
        final String projectPath = ws != null ? ws.getProjectPath() : "";
        if (projectPath == null || projectPath.isEmpty()) {
            return new ToolResult(1, "", "未打开项目，无法编译（请先在 App 里创建/打开项目）");
        }
        // 取内置编译工具
        BuildToolManager.BuildTool tool = null;
        try {
            tool = BuildToolManager.getInstance(ctx).getBuiltinTool();
        } catch (Throwable ignored) {}
        final String toolId = tool != null ? tool.id : "internal:ecj";
        final String mainFile = ws != null && ws.getCurrentFilePath() != null
                ? ws.getCurrentFilePath() : "";

        final CountDownLatch latch = new CountDownLatch(1);
        final StringBuilder log = new StringBuilder();
        final int[] exit = { -1 };

        CompileExecutor ce = CompileExecutor.getInstance();
        ce.setListener(new CompileExecutor.CompileListener() {
            @Override public void onCompileStart() {}
            @Override public void onLogOutput(CompileExecutor.LogEntry entry) {
                if (entry != null && entry.message != null) log.append(entry.message).append('\n');
            }
            @Override public void onCompileSuccess(String s) {
                exit[0] = 0;
                log.append(s == null ? "" : s).append('\n');
                latch.countDown();
            }
            @Override public void onCompileFailed(String s, int code) {
                exit[0] = code != 0 ? code : 1;
                log.append(s == null ? "" : s).append('\n');
                latch.countDown();
            }
            @Override public void onCompileCancelled() {
                exit[0] = 130;
                log.append("编译已取消\n");
                latch.countDown();
            }
        });

        try {
            ce.startCompile(toolId, projectPath, mainFile);
            latch.await(10, TimeUnit.MINUTES);
        } catch (Throwable t) {
            return new ToolResult(1, "", "编译启动失败: " + t.getMessage());
        } finally {
            ce.setListener(null);
        }

        String out = log.toString().trim();
        if (exit[0] == 0 && run) {
            File apk = ce.getLastApkFile();
            if (apk != null && apk.exists()) {
                // 触发安装（返回 APK 路径，安装由 App 侧处理）
                String pkg = ce.getLastApkPackageName();
                return new ToolResult(0, "编译成功: " + apk.getPath()
                        + (pkg != null ? "\n包名: " + pkg : "")
                        + "\n(请用 App 的“产物安装”安装运行)", "");
            }
            return new ToolResult(0, "编译成功，但未找到 APK 产物", out);
        }
        if (exit[0] == 0) {
            String tail = out.length() > 2000 ? out.substring(out.length() - 2000) : out;
            return new ToolResult(0, "编译成功 ✅\n" + tail, "");
        }
        String tail = out.length() > 2000 ? out.substring(out.length() - 2000) : out;
        return new ToolResult(exit[0], "", "编译失败(exit=" + exit[0] + ")\n" + tail);
    }
}
