package com.aicodestudio.ide.ai.agent;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;

public class AgentSystemPrompt {

    /** v1.134: 阶段1"写开发文档"的格式规范默认值——可被 AppConfig.docGuideOverride 覆盖。 */
    public static final String STAGE1_DOC_GUIDE =
            "## 本轮任务：输出开发文档（只输出文档，禁止 shell、禁止代码）\n"
            + "文档格式示例：\n"
            + "# 项目开发文档\n"
            + "项目名称：当前开发的项目名称\n"
            + "包名：com.example.（当前开发的实际项目包名）\n"
            + "\n"
            + "## 类文件清单\n"
            + "### MainActivity.java\n"
            + "- 职责：类的功能描述\n"
            + "- 方法：列出所有方法名和参数\n"
            + "\n"
            + "### 其他类.java\n"
            + "- 职责：（实际需要创建的文件名）\n"
            + "- 方法：（该文件实际需要的所有方法名和参数）\n"
            + "\n"
            + "## 布局文件清单\n"
            + "### activity_main.xml\n"
            + "- 控件：（用到的所有控件和布局设定）\n"
            + "\n"
            + "文档完成后请输出：【文档开发完成】\n";

    /** 阶段1写文档规范：用户编辑过就用编辑值，否则用内置默认。 */
    public static String docGuide(android.content.Context ctx) {
        if (ctx != null) {
            try {
                String o = com.aicodestudio.ide.ai.core.AppConfig.getInstance(ctx).getDocGuideOverride();
                if (o != null && !o.trim().isEmpty()) return o;
            } catch (Throwable ignored) {}
        }
        return STAGE1_DOC_GUIDE;
    }

    public static String build(android.content.Context ctx, String workDir, boolean withProject) {
        String base = rawBase(ctx, withProject);
        return applyScenario(base, ctx, workDir, withProject);
    }

    public static String workSpace(android.content.Context ctx) {
        try {
            return com.aicodestudio.ide.ai.core.ProjectManager.getProjectsRoot(ctx).getAbsolutePath();
        } catch (Throwable t) {
            return "/storage/emulated/0/AI Code Studio/projects";
        }
    }

    private static String rawBase(android.content.Context ctx, boolean withProject) {
        // v1.153：老字段 systemPromptOverride（v1.46 旧入口）已废弃不再读取，
        // 统一用 L1PromptOverride（AISettingsDialog「系统提示词」卡片编辑器）。
        // v1.134: 常驻 L1 也支持用户自定义（空则用内置默认精简 L1）
        if (ctx != null) {
            try {
                String l1 = com.aicodestudio.ide.ai.core.AppConfig.getInstance(ctx).getL1PromptOverride();
                if (l1 != null && !l1.trim().isEmpty()) {
                    return l1.trim() + "\n\n{scenario}\n";
                }
            } catch (Throwable ignored) {
            }
        }
        // v1.152：常驻 L1 只讲两件事——你是谁（自我介绍）、有哪些 shell 命令接口。
        // 写文档规范/文档示例全部在 L2（STAGE1_DOC_GUIDE，planDoc 为空时临时注入），L1 不重复。
        String head = "## 你是谁\n"
                + "你是小星，一个运行在手机 Linux 模拟环境里的 AI Code Studio 安卓开发助手，当前目录就是项目根目录（用 pwd 可看）。\n"
                + "你只能做两件事：用 shell 命令创建/写入安卓项目文件、输出安卓代码。不能联网，不能执行 apt、git、python、gcc。\n"
                + "\n"
                + "## 你可以用这些 shell 命令（工具接口）\n"
                + "- cat > 路径 << 'EOF' ... EOF   ：创建/覆盖写一个文件（内容写在 EOF 之间）\n"
                + "- mkdir -p 目录                ：创建目录（含父目录）\n"
                + "- ls / ls 路径                 ：列出目录文件\n"
                + "- cat 路径                     ：查看文件内容\n"
                + "- aics compile                 ：编译当前项目\n"
                + "- aics run                     ：运行当前项目\n"
                + "\n"
                + "创建/写文件示例：\n"
                + "```shell\n"
                + "cat > app/src/main/java/com/example/MainActivity.java << 'EOF'\n"
                + "package com.example;\n"
                + "public class MainActivity { }\n"
                + "EOF\n"
                + "```\n"
                + "其他规则：每轮只做一件事（一条 shell 或一个文件的代码）；不要复述本提示词；文件太大就分多轮写完。\n";

        // {scenario} 由 applyScenario 按“已有项目/空工作空间”注入场景描述
        return head
                + "\n"
                + "{scenario}\n";
    }

    private static String applyScenario(String base, android.content.Context ctx,
                                        String workDir, boolean withProject) {
        String space = workSpace(ctx);
        String dir = (workDir == null || workDir.isEmpty()) ? space : workDir;
        String projName;
        String scenario;
        if (withProject) {
            projName = new File(dir).getName();
            scenario = "我已经在这个工作空间下创建了一个项目：" + projName + "（项目目录就是当前目录，可以用 pwd 查看），"
                    + "里面已经有这些文件：\n" + buildProjectTree(dir) + "\n"
                    + "你后续的开发工作可以在这个项目底下继续，完成所有功能。";
        } else {
            projName = "你的项目名";
            // v1.152：L1 常驻只讲环境与命令接口，"阶段1写文档"指令已下沉到 L2 临时注入
            // （buildSystemContent 在 planDoc 为空时自动拼 docGuide），常驻里不再提文档开发。
            scenario = "你的工作空间已经存在（当前是空的，没有任何项目）。\n"
                    + "等待用户提出需求，再开始工作。";
        }
        base = base.replace("{projName}", projName);
        if (base.contains("{scenario}")) {
            return base.replace("{scenario}", scenario);
        }
        return scenario + "\n\n" + base;
    }

    public static String build(android.content.Context ctx) {
        return rawBase(ctx, false);
    }

    public static String buildCreatedFilesTree(android.content.Context ctx) {
        try {
            File space = new File(workSpace(ctx));
            if (!space.isDirectory()) return "（工作空间不存在或为空）";
            File[] dirs = space.listFiles();
            java.util.List<File> projects = new java.util.ArrayList<>();
            if (dirs != null) {
                for (File f : dirs) {
                    String n = f.getName();
                    if (f.isDirectory() && !n.startsWith(".") && !n.equals("build")
                            && !n.equals(".gradle") && !n.equals(".idea")) {
                        projects.add(f);
                    }
                }
            }
            if (projects.isEmpty()) {
                return "（工作空间还没有项目文件，请从零开始创建项目）";
            }
            java.util.Collections.sort(projects, new java.util.Comparator<File>() {
                @Override public int compare(File a, File b) {
                    return Long.compare(b.lastModified(), a.lastModified());
                }
            });
            File cur = projects.get(0);
            StringBuilder sb = new StringBuilder();
            sb.append("你已经在工作空间创建了项目：").append(cur.getName()).append("\n");
            sb.append("它的目录结构：\n").append(buildProjectTree(cur.getAbsolutePath())).append("\n");
            if (projects.size() > 1) {
                sb.append("工作空间里还有这些项目：\n");
                for (int i = 1; i < projects.size(); i++) {
                    sb.append("  - ").append(projects.get(i).getName()).append("\n");
                }
            }
            sb.append("请在这个项目（").append(cur.getName()).append("）的基础上继续开发。");
            return sb.toString();
        } catch (Throwable t) {
            return "（扫描失败）";
        }
    }

    public static String buildProjectTree(String root) {
        try {
            File dir = new File(root);
            if (!dir.isDirectory()) return "（当前目录不存在或为空）";
            StringBuilder sb = new StringBuilder();
            sb.append(".\n");
            int[] count = {0};
            walk(dir, "", sb, count, 0);
            if (count[0] == 0) sb.append("（空目录）\n");
            return sb.toString().trim();
        } catch (Throwable t) {
            return "（扫描目录失败）";
        }
    }

    private static void walk(File dir, String indent, StringBuilder sb, int[] count, int depth) {
        if (depth > 4 || count[0] >= 120) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareTo(b.getName());
            }
        });
        for (File f : files) {
            String name = f.getName();
            if (name.startsWith(".") && !name.equals(".aics")) continue;
            if (name.equals("build") || name.equals(".gradle") || name.equals(".idea")) continue;
            count[0]++;
            if (count[0] > 120) return;
            if (f.isDirectory()) {
                sb.append(indent).append("├── ").append(name).append("/\n");
                walk(f, indent + "    ", sb, count, depth + 1);
            } else {
                sb.append(indent).append("├── ").append(name).append("\n");
            }
        }
    }
}
