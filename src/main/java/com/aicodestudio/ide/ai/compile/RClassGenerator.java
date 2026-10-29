package com.aicodestudio.ide.ai.compile;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R 类生成器（阶段一过渡方案）
 *
 * 阶段一没有 aapt2 资源编译，项目代码里的 R.layout.xxx / R.string.xxx 等引用
 * 无法解析。本生成器扫描项目 res/ 目录（布局/图片/values 等资源文件名），
 * 生成与 aapt2 同构的 R.java（包名 + 内部类 + int 常量，值不要求真实，
 * 编译期只校验符号存在），使标准 Android 项目在阶段一即可通过编译检查。
 *
 * 阶段三接入 aapt2 后由 aapt2 生成真实 R.java，本生成器停用。
 */
public class RClassGenerator {

    private static final String TAG = "RClassGenerator";

    /** 解析 values XML 标签：<string name="x">、<item type="color" name="y"> 等 */
    private static final Pattern XML_TAG =
            Pattern.compile("<\\s*([a-zA-Z][\\w.]*)([^>]*)>");
    private static final Pattern ATTR_NAME =
            Pattern.compile("\\bname\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern ATTR_TYPE =
            Pattern.compile("\\btype\\s*=\\s*[\"']([^\"']+)[\"']");

    /** Java 关键字（不能用作 R 字段名） */
    private static final String[] KEYWORDS = {
            "abstract","assert","boolean","break","byte","case","catch","char","class","const",
            "continue","default","do","double","else","enum","extends","final","finally","float",
            "for","goto","if","implements","import","instanceof","int","interface","long","native",
            "new","package","private","protected","public","return","short","static","strictfp",
            "super","switch","synchronized","this","throw","throws","transient","try","void",
            "volatile","while","true","false","null"
    };

    /**
     * 生成 R.java 内容；若项目没有 res 目录或无法确定包名，返回 null（调用方跳过）。
     *
     * @param resDir      项目 res 目录（如 app/src/main/res）
     * @param packageName 项目包名
     */
    public static String generate(File resDir, String packageName) {
        if (resDir == null || !resDir.isDirectory()) return null;
        if (packageName == null || packageName.trim().length() == 0
                || !packageName.matches("[a-zA-Z_][\\w.]*")) {
            Log.w(TAG, "包名无效，跳过 R 生成: " + packageName);
            return null;
        }

        // 按内部类名分组：values / values-night / values-v21 等限定符目录合并为一个 class；
        // 字段按 class 去重（同名资源出现在多个限定符目录时只保留一个）。
        LinkedHashMap<String, List<String[]>> groups = new LinkedHashMap<>();
        LinkedHashMap<String, Set<String>> seenFields = new LinkedHashMap<>();

        File[] subDirs = resDir.listFiles();
        if (subDirs != null) {
            for (File dir : subDirs) {
                if (!dir.isDirectory()) continue;
                String cls = sanitizeIdentifier(dir.getName().split("-")[0]);
                if (cls == null || isKeyword(cls)) continue;

                List<String[]> fields = groups.computeIfAbsent(cls, k -> new ArrayList<>());
                Set<String> seen = seenFields.computeIfAbsent(cls, k -> new HashSet<>());

                File[] files = dir.listFiles();
                if (files == null) continue;
                if (clsIsValues(dir.getName())) {
                    // values 系列目录：资源按标签名分内部类（<string>→R.string、<color>→R.color…）
                    for (File f : files) {
                        if (!f.getName().endsWith(".xml")) continue;
                        collectValueEntries(f, groups, seenFields);
                    }
                    continue;
                }
                for (File f : files) {
                    String name = f.getName();
                    if (name.startsWith(".")) continue;
                    int dot = name.lastIndexOf('.');
                    if (dot > 0) name = name.substring(0, dot);
                    String field = sanitizeIdentifier(name);
                    if (field != null && !isKeyword(field) && seen.add(field)) {
                        fields.add(new String[]{field, "0x" + Integer.toHexString(RClassGenerator.idBase++)});
                    }
                }
            }
        }

        if (groups.isEmpty()) return null;
        StringBuilder sb = new StringBuilder(4096);
        sb.append("package ").append(packageName).append(";\n\n");
        sb.append("/** 由内置编译器 RClassGenerator 生成（阶段一过渡，值无实际含义） */\n");
        sb.append("public final class R {\n");
        boolean any = false;
        for (Map.Entry<String, List<String[]>> e : groups.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            sb.append("    public static final class ").append(e.getKey()).append(" {\n");
            for (String[] kv : e.getValue()) {
                sb.append("        public static final int ")
                  .append(kv[0]).append(" = ").append(kv[1]).append(";\n");
            }
            sb.append("    }\n");
            any = true;
        }
        if (!any) return null;
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * 解析 values XML 中的资源条目，按 aapt2 规则分内部类：
     *   <string name="x">              → R.string.x
     *   <color name="y">               → R.color.y
     *   <style name="z">               → R.style.z
     *   <item type="drawable" name="w"> → R.drawable.w
     * 同 (内部类, 字段) 重复的只保留第一个；<declare-styleable>/<resources> 等不生成条目。
     */
    private static void collectValueEntries(File xml,
            LinkedHashMap<String, List<String[]>> groups,
            LinkedHashMap<String, Set<String>> seenFields) {
        StringBuilder content = new StringBuilder(8192);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(xml), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) content.append(line).append('\n');
        } catch (Exception e) {
            Log.w(TAG, "读取 values XML 失败: " + xml.getName(), e);
            return;
        }
        Matcher tm = XML_TAG.matcher(content);
        while (tm.find()) {
            String tag = tm.group(1);
            String attrs = tm.group(2);
            if ("resources".equals(tag) || "declare-styleable".equals(tag)
                    || "eat-comment".equals(tag) || "skip".equals(tag)) continue;
            Matcher nm = ATTR_NAME.matcher(attrs);
            if (!nm.find()) continue;
            String resName = nm.group(1);
            String cls = tag;
            if ("item".equals(tag)) {
                Matcher typ = ATTR_TYPE.matcher(attrs);
                if (!typ.find()) continue; // item 无 type 无法归类，跳过
                cls = typ.group(1);
            }
            String clsClean = sanitizeIdentifier(cls);
            if (clsClean == null || isKeyword(clsClean)) continue;
            String field = sanitizeIdentifier(resName);
            if (field == null || isKeyword(field)) continue;

            List<String[]> fields = groups.computeIfAbsent(clsClean, k -> new ArrayList<>());
            Set<String> seen = seenFields.computeIfAbsent(clsClean, k -> new HashSet<>());
            if (seen.add(field)) {
                fields.add(new String[]{field, "0x" + Integer.toHexString(RClassGenerator.idBase++)});
            }
        }
    }

    /** 目录名以 values 开头（values / values-night / values-v21 …）即资源值目录 */
    private static boolean clsIsValues(String dirName) {
        return dirName.equals("values") || dirName.startsWith("values-");
    }

    /**
     * 在项目目录下查找 res 目录：
     * 优先 app/src/main/res（Gradle 标准），其次 src/main/res，最后任意 res。
     */
    public static File findResDir(File projectDir) {
        if (projectDir == null || !projectDir.isDirectory()) return null;
        File std = new File(projectDir, "app/src/main/res");
        if (std.isDirectory()) return std;
        File std2 = new File(projectDir, "src/main/res");
        if (std2.isDirectory()) return std2;
        File[] res = projectDir.listFiles();
        if (res != null) {
            for (File f : res) {
                if ("res".equals(f.getName()) && f.isDirectory()) return f;
            }
        }
        // 递归 fallback：源码可能在任意子目录（如 AICS_source/src/main/res）
        return findResDirRecursive(projectDir, 0);
    }

    private static File findResDirRecursive(File dir, int depth) {
        if (depth > 4 || dir == null) return null;
        File[] kids = dir.listFiles();
        if (kids == null) return null;
        for (File f : kids) {
            if (!f.isDirectory()) continue;
            if ("res".equals(f.getName()) && new File(f, "values").isDirectory()) {
                return f;
            }
        }
        for (File f : kids) {
            if (f.isDirectory() && !"build".equals(f.getName()) && !".git".equals(f.getName())) {
                File hit = findResDirRecursive(f, depth + 1);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    /**
     * 从 AndroidManifest.xml 的 package 属性取包名；
     * 找不到则扫描项目第一个 .java 的 package 声明。都没有返回 null。
     */
    public static String findPackageName(File projectDir) {
        if (projectDir == null || !projectDir.isDirectory()) return null;
        File manifest = findFile(projectDir, "AndroidManifest.xml");
        if (manifest != null) {
            String pkg = readManifestPackage(manifest);
            if (pkg != null && pkg.length() > 0) return pkg;
        }
        String pkg = scanJavaPackage(projectDir);
        if (pkg != null && pkg.length() > 0) return pkg;
        return null;
    }

    private static String readManifestPackage(File manifest) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(manifest), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                // 兼容 package="com.xxx" 与 package = 'com.xxx'
                if (line.startsWith("<manifest")) {
                    int idx = line.indexOf("package");
                    if (idx < 0) return null;
                    String rest = line.substring(idx + "package".length()).trim();
                    if (!rest.startsWith("=")) return null;
                    rest = rest.substring(1).trim();
                    if (rest.length() < 2) return null;
                    char q = rest.charAt(0);
                    if (q != '"' && q != '\'') return null;
                    int end = rest.indexOf(q, 1);
                    if (end < 0) return null;
                    return rest.substring(1, end);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "读取 AndroidManifest 失败", e);
        }
        return null;
    }

    private static String scanJavaPackage(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isDirectory()) {
                String p = scanJavaPackage(f);
                if (p != null) return p;
            } else if (f.getName().endsWith(".java")) {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(
                        new FileInputStream(f), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        line = line.trim();
                        if (line.startsWith("package ") && line.endsWith(";")) {
                            return line.substring("package ".length(), line.length() - 1).trim();
                        }
                        if (line.startsWith("class ") || line.startsWith("public class ")) break;
                    }
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private static File findFile(File dir, String name) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isDirectory()) {
                if ("build".equals(f.getName()) || "build-merged".equals(f.getName())) continue;
                File hit = findFile(f, name);
                if (hit != null) return hit;
            } else if (name.equals(f.getName())) {
                return f;
            }
        }
        return null;
    }

    /** 转成合法 Java 标识符；非法则返回 null（. 会转 _，数字开头补 _） */
    private static String sanitizeIdentifier(String raw) {
        if (raw == null || raw.length() == 0) return null;
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isJavaIdentifierPart(c)) {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        if (sb.length() == 0) return null;
        if (!Character.isJavaIdentifierStart(sb.charAt(0))) {
            sb.insert(0, '_');
        }
        return sb.toString();
    }

    private static boolean isKeyword(String s) {
        for (String k : KEYWORDS) {
            if (k.equals(s)) return true;
        }
        return false;
    }

    private static int idBase = 0x7f010000;
}
