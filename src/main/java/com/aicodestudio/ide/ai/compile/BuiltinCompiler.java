package com.aicodestudio.ide.ai.compile;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内置编译引擎 —— 纯 Java 工具链（无需设备安装任何编译环境）
 *
 * 工具链资源打包在 APK assets/compile/ 下：
 *   - ecj.dex      Eclipse Java 编译器（已用 d8 转为 dex，供 ART 加载代码，约 2.4MB）
 *   - ecj.jar      原始 ECJ jar：提供 .properties 消息资源（错误消息模板/本地化）。
 *                  d8 转 dex 只保留代码、丢失全部资源，故 dexPath 需同时挂载 ecj.jar
 *                  （类从 ecj.dex 加载，资源从 ecj.jar 读取）
 *   - android.jar  Android 34 平台 API（编译 classpath，约 26MB）——
 *                  AGP8 模板常见 Material 库 values-v31 引用 android:color/system_*（API33+），
 *                  必须用 API 34 平台才能编译
 *   - androidx-all.jar  依赖合并包（appcompat 1.6.1 树 + Material 1.9.0 树版本消解后合并，
 *                  含 classes 与全部依赖库，约 6MB）——编译 classpath + 打包进产物 APK 的 dex
 *   - material-res.flat.zip  Material + 依赖库资源（AGP 风格 XML 内容合并后由 aapt2 34
 *                  预编译的 flat 包，约 2.6MB）——aapt2 link 时作为 overlay 输入，
 *                  提供 Theme.Material3 / colorPrimary 等 Material 主题与属性
 *
 * 首次编译时把工具链解压到应用私有目录 filesDir/compile/，
 * 然后用 DexClassLoader 加载 ecj.dex，反射调用
 * org.eclipse.jdt.internal.compiler.batch.Main 完成编译，
 * 并把 ECJ 原生日志解析成标准 CompileError 列表。
 *
 * 链路（阶段一）：Java 源码 → ECJ 编译检查 → 标准错误列表
 * 后续阶段：d8 转 dex → aapt2 资源打包 → apksigner 签名 → APK
 */
public class BuiltinCompiler {

    private static final String TAG = "BuiltinCompiler";
    private static final String ASSET_DIR = "compile";
    private static final String TOOL_DIR = "compile";
    private static final String ECJ_DEX = "ecj.dex";
    private static final String ECJ_JAR = "ecj.jar";
    private static final String ANDROID_JAR = "android.jar";
    private static final String ANDROIDX_JAR = "androidx-all.jar";
    private static final String MAT_RES_ZIP = "material-res.flat.zip";
    private static final String D8_DEX = "d8.dex";
    private static final String APKSIGNER_DEX = "apksigner.dex";
    private static final String AAPT2_BIN = "aapt2";
    private static final String KEYSTORE = "debug.keystore";
    private static final String CRASH_CAPTURE = "CrashCapture.java"; // 注入产物 APK 的崩溃采集（Application）

    /**
     * 工具链版本标记：assets/compile 内容变化（如 android.jar 升级 API、新增依赖库）时
     * 递增此版本。prepare() 检测 files/compile/.toolchain_version 不匹配则整目录重解压，
     * 否则 extractAsset 幂等跳过会一直用旧版工具链（如旧 API30 android.jar）。
     */
    private static final String TOOLCHAIN_VERSION = "v12.1";
    private static final String VERSION_FILE = ".toolchain_version";

    /** <manifest ... package="..."> 已有 package 属性 */
    private static final Pattern MANIFEST_PACKAGE =
            Pattern.compile("<manifest\\b[^>]*\\bpackage\\s*=");
    /** <manifest ... android:versionCode="..."> 已有 versionCode */
    private static final Pattern MANIFEST_VERSION_CODE =
            Pattern.compile("<manifest\\b[^>]*\\bandroid:versionCode\\s*=");
    /** <manifest ... android:versionName="..."> 已有 versionName */
    private static final Pattern MANIFEST_VERSION_NAME =
            Pattern.compile("<manifest\\b[^>]*\\bandroid:versionName\\s*=");
    /** <manifest 开始标签（<manifest 与 > 之间为属性区） */
    private static final Pattern MANIFEST_TAG =
            Pattern.compile("<manifest\\b([^>]*?)>", Pattern.DOTALL);
    private static final String KS_ALIAS = "aicode";
    private static final String KS_PASS = "android";
    /**
     * 产物 APK 的 targetSdk：跟随设备系统版本（避免系统提示「兼容性问题」，
     * 常见于 Android 15/16 新机对 targetSdk 落后系统版本的 APK 给出警告）。
     * 上限 36：设备端工具链 android.jar 为 API34，但 aapt2 34 实测允许声明
     * targetSdk 35/36（产物不含更高版本特有资源，仅声明不影响运行）。
     */
    private static String targetSdkForDevice() {
        return "34";
    }

    // ECJ 错误块：----------\n 1. ERROR in /path/File.java (at line 12)\n\t代码行\n\t     ^\n消息\n----------
    private static final Pattern BLOCK_HEADER =
            Pattern.compile("^(\\d+)\\.\\s+(ERROR|WARNING)\\s+in\\s+(.+?)\\(at\\s+line\\s+(\\d+)\\)\\s*$");

    private final Context context;
    private File toolDir;
    private File ecjFile;      // ecj.dex（代码）
    private File ecjJarFile;   // ecj.jar（.properties 消息资源）
    private File androidJarFile;
    private File androidxJarFile;
    private File matResZipFile;     // Material+依赖库资源 flat 包（aapt2 link overlay 输入）
    private File d8DexFile;        // d8 转 dex（Java 程序，反射调用）
    private File apksignerDexFile; // APK 签名（Java 程序，反射调用）
    private File aapt2File;        // 资源打包（arm64 原生二进制，ProcessBuilder 执行）
    private File keystoreFile;     // 内置 debug keystore
    private File crashCaptureFile; // 崩溃采集源码（注入产物 APK）
    private File lambdaStubJarFile; // Java 8 lambda stubs（解决 LambdaMetafactory 找不到）
    private boolean prepared;
    private String prepareError;

    public BuiltinCompiler(Context context) {
        this.context = context.getApplicationContext();
        this.toolDir = new File(context.getFilesDir(), TOOL_DIR);
        this.ecjFile = new File(toolDir, ECJ_DEX);
        this.ecjJarFile = new File(toolDir, ECJ_JAR);
        this.androidJarFile = new File(toolDir, ANDROID_JAR);
        this.androidxJarFile = new File(toolDir, ANDROIDX_JAR);
        this.matResZipFile = new File(toolDir, MAT_RES_ZIP);
        this.d8DexFile = new File(toolDir, D8_DEX);
        this.apksignerDexFile = new File(toolDir, APKSIGNER_DEX);
        // aapt2 优先用 nativeLibraryDir（lib/arm64-v8a/libaapt2.so，安装时由系统解压到
        // /data/app/.../lib/arm64/，系统托管可执行）；Android 10+ 禁止从应用私有
        // data 目录执行二进制（W^X，error=13），故 files/compile/aapt2 仅作兜底。
        File nativeAapt2 = new File(context.getApplicationInfo().nativeLibraryDir, "libaapt2.so");
        this.aapt2File = nativeAapt2.exists() ? nativeAapt2 : new File(toolDir, AAPT2_BIN);
        this.keystoreFile = new File(toolDir, KEYSTORE);
        this.lambdaStubJarFile = new File(toolDir, "core-lambda-stubs.jar");
    }

    /**
     * 解压内置工具链到私有目录（幂等：文件已存在且非空则跳过）。
     * 供外部检查可用性；compile 内部会自动调用。
     */
    public synchronized boolean prepare() {
        if (prepared) return true;
        try {
            // 工具链版本化：旧版工具目录（不同版本或升级残留）整体删除重解压，
            // 否则 extractAsset 见文件存在即跳过，升级后继续用旧 android.jar 等
            String currentVer = readVersionFile();
            if (currentVer == null || !currentVer.equals(TOOLCHAIN_VERSION)) {
                Log.i(TAG, "toolchain version " + currentVer + " != " + TOOLCHAIN_VERSION
                        + ", re-extracting toolchain");
                deleteRecursive(toolDir);
                if (!toolDir.exists() && !toolDir.mkdirs()) {
                    prepareError = "无法创建工具目录 " + toolDir;
                    return false;
                }
            } else if (!toolDir.exists() && !toolDir.mkdirs()) {
                prepareError = "无法创建工具目录 " + toolDir;
                return false;
            }
            extractAsset(ECJ_DEX, ecjFile);
            extractAsset(ECJ_JAR, ecjJarFile);
            extractAsset(ANDROID_JAR, androidJarFile);
            extractAsset(ANDROIDX_JAR, androidxJarFile);
            extractAsset(MAT_RES_ZIP, matResZipFile);
            extractAsset(D8_DEX, d8DexFile);
            extractAsset(APKSIGNER_DEX, apksignerDexFile);
            extractAsset(KEYSTORE, keystoreFile);
            crashCaptureFile = new File(toolDir, CRASH_CAPTURE);
            extractAsset(CRASH_CAPTURE, crashCaptureFile);
            // aapt2：nativeLibraryDir 不可用时才从 assets 解压兜底（低版本/模拟器）
            if (!aapt2File.exists() || aapt2File.length() < 1_000_000) {
                File fallback = new File(toolDir, AAPT2_BIN);
                extractAsset(AAPT2_BIN, fallback);
                //noinspection ResultOfMethodCallIgnored
                fallback.setExecutable(true, false);
                aapt2File = fallback;
            }
            if (ecjFile.length() < 500_000
                    || ecjJarFile.length() < 500_000
                    || androidJarFile.length() < 1_000_000
                    || androidxJarFile.length() < 1_000_000
                    || matResZipFile.length() < 100_000
                    || d8DexFile.length() < 1_000_000
                    || apksignerDexFile.length() < 500_000
                    || aapt2File.length() < 1_000_000) {
                prepareError = "工具链文件不完整（ecj.dex=" + ecjFile.length()
                        + " ecj.jar=" + ecjJarFile.length()
                        + " android.jar=" + androidJarFile.length()
                        + " androidx-all.jar=" + androidxJarFile.length()
                        + " material-res.zip=" + matResZipFile.length()
                        + " d8.dex=" + d8DexFile.length()
                        + " apksigner.dex=" + apksignerDexFile.length()
                        + " aapt2=" + aapt2File.length() + "）";
                return false;
            }
            try {
                writeVersionFile(TOOLCHAIN_VERSION);
            } catch (Throwable ignored) {
            }
            prepared = true;
            return true;
        } catch (Throwable t) {
            prepareError = "工具链解压失败: " + describe(t);
            Log.e(TAG, "prepare failed", t);
            return false;
        }
    }

    public String getPrepareError() {
        return prepareError;
    }

    public boolean isPrepared() {
        if (prepared) return true;
        // 版本不匹配（旧工具链残留）时视为未就绪，prepare() 会重解压
        String ver = readVersionFile();
        if (ver == null || !ver.equals(TOOLCHAIN_VERSION)) return false;
        return ecjFile.exists() && ecjJarFile.exists()
                && androidJarFile.exists() && androidxJarFile.exists() && matResZipFile.exists()
                && d8DexFile.exists() && apksignerDexFile.exists() && aapt2File.exists();
    }

    private void extractAsset(String assetName, File target) throws IOException {
        boolean alreadyExists = target.exists() && target.length() > 0;
        if (!alreadyExists) {
            InputStream in = null;
            FileOutputStream out = null;
            try {
                in = context.getAssets().open(ASSET_DIR + "/" + assetName);
                out = new FileOutputStream(target);
                byte[] buf = new byte[65536];
                int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    total += n;
                }
                out.flush();
                Log.i(TAG, "extracted " + assetName + " -> " + total + " bytes");
            } finally {
                if (in != null) try { in.close(); } catch (IOException ignored) {}
                if (out != null) try { out.close(); } catch (IOException ignored) {}
            }
        }
        if (!target.setReadOnly()) {
            target.setWritable(false, false);
        }
        Log.i(TAG, "file read-only: " + assetName + " canWrite=" + target.canWrite());
    }

    /** 读取工具链版本标记文件，不存在返回 null */
    private String readVersionFile() {
        File f = new File(toolDir, VERSION_FILE);
        if (!f.exists()) return null;
        try {
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[128];
            int n = in.read(buf);
            in.close();
            if (n <= 0) return null;
            return new String(buf, 0, n, "UTF-8").trim();
        } catch (Throwable t) {
            return null;
        }
    }

    private void writeVersionFile(String ver) throws IOException {
        File f = new File(toolDir, VERSION_FILE);
        FileOutputStream out = new FileOutputStream(f);
        out.write(ver.getBytes("UTF-8"));
        out.close();
    }

    /**
     * 编译结果：是否成功 + 标准错误列表 + 原始日志
     */
    public static class CompileResult {
        public boolean success;
        public final List<CompileError> errors = new ArrayList<>();
        public String rawLog = "";
        public String errorText = ""; // 仅错误相关的原始文本
        public int errorCount;
        public int warningCount;
    }

    /** APK 构建结果 */
    public static class BuildApkResult {
        public boolean success;
        public File apkFile;      // 最终签名 APK（私有缓存目录）
        public String packageName; // 产物包名（从项目源码推断；安装后轮询检测安装结果用）
        public String log = "";   // 打包过程日志
        public String error = "";
    }

    /** 打包过程日志回调（level 用 CompileExecutor.LOG_*） */
    public interface BuildListener {
        void onBuildLog(String msg, int level);
    }

    /**
     * 完整 APK 构建：ECJ 编译 → aapt2 资源 → d8 转 dex → 合并 → apksigner 签名
     *
     * @return 成功后 apkFile 指向私有缓存目录 compile_apk/ 下的签名 APK（临时产物，安装后应删除）
     */
    public BuildApkResult buildApk(File projectDir, String extraClasspath, BuildListener listener) {
        BuildApkResult r = new BuildApkResult();
        StringBuilder log = new StringBuilder();
        if (!prepare()) {
            r.error = prepareError == null ? "内置工具链不可用" : prepareError;
            r.log = log.toString();
            return r;
        }
        // 自动探测真正的项目根：如果外层目录没有 AndroidManifest，递归找一层
        // （用户可能选了解压后的外层目录，真正源码在子目录如 AICS_source/）
        if (findManifest(projectDir) == null) {
            File deeper = findProjectRootRecursive(projectDir, 0);
            if (deeper == null && projectDir.getName().startsWith("AI_Code")) { deeper = projectDir; }
            if (deeper != null) {
                log.append("[project] 自动定位项目根: ").append(deeper.getAbsolutePath()).append('\n');
                projectDir = deeper;
            }
        }
        try {
            File work = new File(context.getCacheDir(), "compile_apk_build");
            deleteRecursive(work);
            if (!work.mkdirs()) {
                r.error = "无法创建工作目录";
                r.log = log.toString();
                return r;
            }
            File resZip = new File(work, "res.zip");
            File baseApk = new File(work, "base.apk");
            File genDir = new File(work, "gen");
            //noinspection ResultOfMethodCallIgnored
            genDir.mkdirs();
            File classesDir = new File(work, "classes");
            //noinspection ResultOfMethodCallIgnored
            classesDir.mkdirs();
            File dexDir = new File(work, "dex");
            //noinspection ResultOfMethodCallIgnored
            dexDir.mkdirs();
            File apkDir = new File(context.getCacheDir(), "compile_apk");
            if (!apkDir.exists() && !apkDir.mkdirs()) {
                r.error = "无法创建 APK 输出目录";
                r.log = log.toString();
                return r;
            }

            File resDir = RClassGenerator.findResDir(projectDir);
            File manifest = findManifest(projectDir);
            String pkg = "com.aicodestudio.ide"; // 自举编译写死包名
            if (manifest == null) {
                // 无 AndroidManifest：生成最小 manifest（包名取 java 包，缺省用占位）
                String mp = (pkg != null && pkg.length() > 0) ? pkg : "com.generated.app";
                manifest = new File(work, "AndroidManifest.xml");
                writeFile(manifest, "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                        + "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\""
                        + " package=\"" + mp + "\">\n"
                        + "    <uses-sdk android:minSdkVersion=\"21\" android:targetSdkVersion=\""
                        + "33" + "\" />\n"
                        + "    <application android:label=\"App\" />\n"
                        + "</manifest>\n");
            } else if (!manifestHasPackage(manifest)) {
                // AGP 8+ 新项目模板：AndroidManifest.xml 不再写 package 属性
                // （包名在 build.gradle 的 namespace）。aapt2 link 必须要有 package，
                // 这里从项目 java 源码推断包名并注入临时 manifest。
                if (pkg == null || pkg.length() == 0 || !pkg.matches("[a-zA-Z_][\\w.]*")) {
                    r.error = "AndroidManifest.xml 缺少 package 属性，且无法从项目源码推断包名。\n"
                            + "请在 AndroidManifest.xml 的 <manifest> 标签添加 package=\"你的包名\"，"
                            + "或检查项目 .java 文件的 package 声明。";
                    r.log = log.toString();
                    return r;
                }
                String injected = injectManifestDefaults(manifest, pkg);
                if (injected == null) {
                    injected = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                            + "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"" + pkg + "\">\n"
                            + "    <uses-sdk android:minSdkVersion=\"21\" android:targetSdkVersion=\"" + "33" + "\" />\n"
                            + "    <application android:label=\"App\" />\n"
                            + "</manifest>\n";
                }
                File tmpManifest = new File(work, "AndroidManifest.xml");
                writeFile(tmpManifest, injected);
                manifest = tmpManifest;
                log.append("[manifest] 检测到无 package 属性，已注入 package/versionCode/versionName/uses-sdk\n");
            } else {
                // manifest 已有 package：仍需补齐缺失的 versionCode/versionName/uses-sdk。
                // 否则产物 APK 的 targetSdk 缺失 → 系统按 targetSdk=1 处理，
                // 任何设备安装时都会提示「兼容性问题」。
                String injected = injectManifestDefaults(manifest, pkg);
                if (injected != null && !injected.equals(readFile(manifest))) {
                    File tmpManifest = new File(work, "AndroidManifest.xml");
                    writeFile(tmpManifest, injected);
                    manifest = tmpManifest;
                    log.append("[manifest] 已补齐缺失属性（versionCode/versionName/uses-sdk）\n");
                }
            }

            // ---- 1. aapt2 compile + link：resources.arsc + 二进制 manifest + 真实 R.java ----
            if (resDir != null && resDir.isDirectory()) {
                String c1 = runProcess(aapt2File, new String[]{
                        "compile", "--dir", resDir.getAbsolutePath(), "-o", resZip.getAbsolutePath()});
                log.append("[aapt2 compile]\n").append(c1 == null ? "" : c1.trim()).append('\n');
                if (!resZip.exists() || resZip.length() == 0) {
                    r.error = "资源编译失败（aapt2 compile）:\n" + (c1 == null ? "" : c1);
                    r.log = log.toString();
                    return r;
                }
            } else {
                createEmptyZip(resZip);
                log.append("[aapt2] 项目无 res 目录，跳过资源编译\n");
            }

            List<String> linkArgs = new ArrayList<>();
            linkArgs.add("link");
            linkArgs.add("-o"); linkArgs.add(baseApk.getAbsolutePath());
            linkArgs.add("-I"); linkArgs.add(androidJarFile.getAbsolutePath());
            linkArgs.add("--manifest"); linkArgs.add(manifest.getAbsolutePath());
            // Material + 依赖库资源（预编译 flat 包）：提供 Theme.Material3 / colorPrimary 等。
            // 顺序：库资源在前，项目资源在后（--auto-add-overlay 下后覆盖前 → 项目资源优先）
            linkArgs.add("--auto-add-overlay");
            if (matResZipFile != null && matResZipFile.exists() && matResZipFile.length() > 0) {
                linkArgs.add(matResZipFile.getAbsolutePath());
            } else {
                log.append("[aapt2] 警告：material-res.flat.zip 缺失，Material 主题项目可能无法编译\n");
            }
            linkArgs.add(resZip.getAbsolutePath());
            linkArgs.add("--java"); linkArgs.add(genDir.getAbsolutePath());
            

            String c2 = runProcess(aapt2File, linkArgs.toArray(new String[0]));
            log.append("[aapt2 link]\n").append(c2 == null ? "" : c2.trim()).append('\n');
            if (!baseApk.exists() || baseApk.length() == 0) {
                r.error = "资源链接失败（aapt2 link）:\n" + (c2 == null ? "" : c2);
                r.log = log.toString();
                return r;
            }

            // ---- 2. 收集源码 + 真实 R.java（ID 与 resources.arsc 一致）----
            List<File> sources = new ArrayList<>();
            collectJavaFiles(projectDir, sources);
            // 注入崩溃采集（工具链自带）：产物 APK 闪退时写崩溃日志到公共目录，
            // 便于定位运行时崩溃（模板项目无自定义 Application，注入安全）。
            // 若项目源码已自带 CrashCapture 类（sources 里已有同名文件），跳过注入避免重复定义。
            if (crashCaptureFile != null && crashCaptureFile.exists()
                    && !sourcesContainsName(sources, "CrashCapture.java")) {
                sources.add(crashCaptureFile);
                log.append("[crash] 已注入崩溃日志采集\n");
            }
            File rFile = findRJava(genDir);
            // 调试日志：打印 genDir 内容，定位 R.java 生成问题
            log.append("[R-debug] genDir=").append(genDir.getAbsolutePath()).append('\n');
            log.append("[R-debug] genDir exists=").append(genDir.exists())
               .append(" files=").append(countFilesRecursive(genDir)).append('\n');
            dumpDirToLog(genDir, "[R-debug] ", log, 0);
            if (rFile != null && rFile.exists()) {
                log.append("[R-debug] findRJava 找到 R.java: ").append(rFile.getAbsolutePath()).append('\n');
                sources.add(rFile);
            } else {
                log.append("[R-debug] findRJava 未找到 R.java，fallback 到 RClassGenerator\n");
                // fallback：RClassGenerator（无资源或 aapt2 未生成 R 时）
                String rJava = RClassGenerator.generate(resDir, pkg);
                log.append("[R-debug] RClassGenerator.generate 返回 ")
                   .append(rJava == null ? "null" : rJava.length() + " 字符").append('\n');
                if (rJava != null && rJava.length() > 0) {
                    File rf = new File(work, "R.java");
                    writeFile(rf, rJava);
                    log.append("[R-debug] fallback R.java 写入: ").append(rf.getAbsolutePath()).append('\n');
                    sources.add(rf);
                }
            }
            if (sources.isEmpty()) {
                r.error = "未找到任何 .java 源文件";
                r.log = log.toString();
                return r;
            }

            // ---- 2.5 动态生成 Java 8 lambda 存根类（解决 LambdaMetafactory 找不到）----
            // android.jar 不含 java.lang.invoke.LambdaMetafactory，ECJ 编译 lambda 表达式时需要。
            // 这里动态生成存根类源码加入编译列表，d8 后续会做脱糖处理，运行时不需要这些类。
            File lambdaStubsDir = new File(work, "lambda-stubs/java/lang/invoke");
            lambdaStubsDir.mkdirs();
            File lambdaMetafactoryJava = new File(lambdaStubsDir, "LambdaMetafactory.java");
            writeFile(lambdaMetafactoryJava,
                    "package java.lang.invoke;\n" +
                    "public class LambdaMetafactory {\n" +
                    "    public static CallSite metafactory(MethodHandles.Lookup caller,\n" +
                    "            String invokedName, MethodType invokedType,\n" +
                    "            MethodType samMethodType, MethodHandle implMethod,\n" +
                    "            MethodType instantiatedMethodType) {\n" +
                    "        throw new UnsupportedOperationException(\"lambda stub\");\n" +
                    "    }\n" +
                    "}\n");
            File serializedLambdaJava = new File(lambdaStubsDir, "SerializedLambda.java");
            writeFile(serializedLambdaJava,
                    "package java.lang.invoke;\n" +
                    "public class SerializedLambda implements java.io.Serializable {\n" +
                    "    private static final long serialVersionUID = 8025925345765579379L;\n" +
                    "    public SerializedLambda(Class<?> capturingClass, String functionalInterfaceClass,\n" +
                    "            String functionalInterfaceMethodName, String functionalInterfaceMethodSignature,\n" +
                    "            int implMethodKind, String implClass, String implMethodName,\n" +
                    "            String implMethodSignature, int instantiatedMethodType,\n" +
                    "            String instantiatedMethodTypeSig, Object[] capturedArgs) {}\n" +
                    "}\n");
            sources.add(lambdaMetafactoryJava);
            sources.add(serializedLambdaJava);
            log.append("[lambda] 已注入 Java 8 lambda 存根类（LambdaMetafactory + SerializedLambda）\n");

            // ---- 3. ECJ 编译（classpath：android.jar + androidx + 项目 libs + 外部）----
            String projectLibs = collectProjectLibs(projectDir);
            StringBuilder cp = new StringBuilder(androidJarFile.getAbsolutePath());
            cp.append(File.pathSeparator).append(androidxJarFile.getAbsolutePath());
            if (projectLibs.length() > 0) cp.append(File.pathSeparator).append(projectLibs);
            if (extraClasspath != null && extraClasspath.trim().length() > 0) {
                cp.append(File.pathSeparator).append(extraClasspath.trim());
            }
            List<String> cargs = new ArrayList<>();
            cargs.add("-source"); cargs.add("1.8");
            cargs.add("-target"); cargs.add("1.8");
            cargs.add("-encoding"); cargs.add("UTF-8");
            cargs.add("-proc:none");
            cargs.add("-nowarn");
            // 显式 bootclasspath=android.jar + core-lambda-stubs.jar（如果存在）：
            // ECJ 未指定时读 java.class.path（Android 上含 /apex native .so 会被当 jar 打开报错），
            // 且防止误用桌面 JDK 独有 API。
            // core-lambda-stubs.jar：Android SDK 提供的 Java 8 lambda 存根类，
            // android.jar 不含 java.lang.invoke.LambdaMetafactory，缺少它编译 lambda 表达式会报
            // "The type java.lang.invoke.LambdaMetafactory cannot be resolved"。
            // 此文件需放入 assets/compile/，prepare() 会自动解压到 toolDir。
            String bootCp = androidJarFile.getAbsolutePath();
            if (lambdaStubJarFile != null && lambdaStubJarFile.exists() && lambdaStubJarFile.length() > 0) {
                bootCp += File.pathSeparator + lambdaStubJarFile.getAbsolutePath();
                log.append("[ECJ] 已加载 core-lambda-stubs.jar（Java 8 lambda 支持）\n");
            } else {
                log.append("[ECJ] 警告：core-lambda-stubs.jar 缺失，含 lambda 表达式的源码可能编译失败\n");
            }
            cargs.add("-bootclasspath"); cargs.add(bootCp);
            cargs.add("-cp"); cargs.add(cp.toString());
            cargs.add("-d"); cargs.add(classesDir.getAbsolutePath());
            for (File f : sources) cargs.add(f.getAbsolutePath());

            StringWriter csw = new StringWriter();
            PrintWriter cpw = new PrintWriter(csw);
            boolean ok;
            try {
                ok = runEcj(cargs.toArray(new String[0]), cpw);
            } catch (Throwable t) {
                r.error = "ECJ 编译调用失败: " + describe(t);
                r.log = log.toString();
                return r;
            }
            cpw.flush();
            String ecjLog = csw.toString();
            List<CompileError> errs = new ArrayList<>();
            parseEcjOutput(ecjLog, errs);
            int ecount = 0;
            for (CompileError e : errs) if (e.isError()) ecount++;
            if (!ok || ecount > 0) {
                r.error = "编译错误 " + ecount + " 个:\n" + buildErrorText(errs);
                r.log = log.toString() + ecjLog;
                return r;
            }
            log.append("[ECJ] 编译通过 " + countClasses(classesDir) + " 个 class\n");

            // 剔除 java.lang.invoke 存根类（编译期占位用，运行期 d8 自己脱糖 lambda；
            // d8 拒绝 java.* 包的程序类，残留会导致 CompilationFailedException）
            File invokeDir = new File(classesDir, "java/lang/invoke");
            if (invokeDir.isDirectory()) {
                deleteRecursive(invokeDir);
                log.append("[d8] 已剔除 java/lang/invoke 编译期存根类\n");
            }

            // ---- 4. class → jar → d8 转 dex（输入 = 用户 class + 依赖库，产物可独立运行）----
            // 4.0 剔除与依赖 jar 重复的类：项目源码可能自带 sherpa-onnx 等类，
            // 而 androidx-all.jar 已包含同一份 → 两个 program 输入重复类，d8 报
            // "Type X is defined multiple times"。以依赖 jar 为准，剔除用户侧的重复类。
            if (androidxJarFile != null && androidxJarFile.exists()) {
                int removed = removeDuplicateClasses(classesDir, androidxJarFile);
                if (removed > 0) {
                    log.append("[d8] 剔除与依赖 jar 重复的类 " + removed + " 个\n");
                }
            }
            File allJar = new File(work, "all.jar");
            jarClasses(classesDir, allJar);
            String d8out = runD8(allJar, androidxJarFile, dexDir);
            if (d8out != null && d8out.length() > 0) log.append("[d8]\n").append(d8out.trim()).append('\n');

            File[] dexFiles = dexDir.listFiles((dir, name) -> name.matches("classes\\d*\\.dex"));
            if (dexFiles == null || dexFiles.length == 0) {
                r.error = "d8 未生成任何 dex";
                r.log = log.toString();
                return r;
            }
            java.util.Arrays.sort(dexFiles, (a, b) -> {
                if (a.getName().equals("classes.dex")) return -1;
                if (b.getName().equals("classes.dex")) return 1;
                return a.getName().compareTo(b.getName());
            });
            for (File df : dexFiles) {
                log.append("[d8] " + df.getName() + " " + df.length() + " bytes\n");
            }

            // ---- 5. 合并 base.apk + 所有 classes*.dex ----
            File unsigned = new File(work, "unsigned.apk");
            mergeZipMultiDex(baseApk, dexFiles, unsigned);
            log.append("[zip] 合并了 " + dexFiles.length + " 个 dex 文件\n");

            // ---- 5.5 打包项目自带 jniLibs/*.so 和 assets/（自举编译支持）----
            File unsignedWithNative = new File(work, "unsigned_native.apk");
            int nativeCount = packageNativeLibsAndAssets(unsigned, unsignedWithNative, projectDir, log);
            if (nativeCount > 0) {
                unsigned = unsignedWithNative;
                log.append("[zip] 已打包项目 native libs + assets，共 " + nativeCount + " 个文件\n");
            }
            // ---- 5.6 最终整体重对齐 ----
            File alignedFinal = new File(work, "aligned_final.apk");
            ZipAligner.realign(unsigned, alignedFinal);
            unsigned = alignedFinal;
            log.append("[zip] 最终重对齐完成\n");

            // ---- 6. apksigner 签名 ----
            String apkName = (pkg != null && pkg.length() > 0
                    ? pkg.substring(pkg.lastIndexOf('.') + 1) : "app") + "-debug.apk";
            File signed = new File(apkDir, apkName);
            
            // 【关键修复】优先使用项目根目录 signing/aicode.keystore（与 GitHub Actions 保持一致）
            // 只有找不到时，才退回到 assets 里的 debug.keystore
            File customKeystore = new File(projectDir, "signing/aicode.keystore");
            if (!customKeystore.exists()) {
                customKeystore = keystoreFile; // fallback to debug
                log.append("[apksigner] 未找到标准 key，使用内置 debug key\n");
            } else {
                log.append("[apksigner] 发现标准 key，使用: " + customKeystore.getAbsolutePath() + "\n");
            }
            
            String signOut = runApksigner(unsigned, signed, customKeystore);
            if (signOut != null && signOut.length() > 0) log.append("[apksigner]\n").append(signOut.trim()).append('\n');
            if (!signed.exists() || signed.length() == 0) {
                r.error = "APK 签名失败:\n" + (signOut == null ? "" : signOut);
                r.log = log.toString();
                return r;
            }
            log.append("[apksigner] 签名完成 " + signed.length() + " bytes\n");

            log.append("[done] =======================================\n");
            log.append("[done] APK 构建完成: ").append(signed.getName()).append("\n");
            log.append("[done] 文件大小: ").append(signed.length() / 1024 / 1024).append(" MB\n");
            log.append("[done] 签名状态: 强制 V1 + V2 + V3\n");
            log.append("[done] 目标 SDK : min 21 / target ").append(targetSdkForDevice()).append("\n");
            log.append("[done] =======================================\n");
            
            r.success = true;
            r.apkFile = signed;
            r.packageName = pkg;
            r.log = log.toString();
            if (listener != null) listener.onBuildLog(r.log, CompileExecutor.LOG_DEBUG);
            return r;
        } catch (Throwable t) {
            Log.e(TAG, "buildApk failed", t);
            r.error = "APK 构建异常: " + describe(t);
            r.log = log.toString();
            return r;
        }
    }

    /**
     * 打包项目自带的 jniLibs/*.so 和 assets/ 到 APK。
     * 自举编译支持：AI Code Studio 编译自身时需要把 llama.so 等 native 库和 assets/ 打进产物 APK。
     *
     * @param srcApk    源 APK（已含 resources.arsc + manifest + classes.dex）
     * @param outApk    输出 APK（含 native libs + assets）
     * @param projectDir 项目根目录
     * @param log       日志追加
     * @return 打包的文件数量（0 表示项目无 jniLibs/assets）
     */

    private static void mergeZipMultiDex(File baseZip, File[] dexFiles, File out) throws Exception {
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(baseZip);
             java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(out))) {
            byte[] buf = new byte[65536];
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                if (e.getName().startsWith("META-INF/")) continue;
                
                java.util.zip.ZipEntry ne = new java.util.zip.ZipEntry(e.getName());
                // 云端 pack_std.py 逻辑：resources.arsc 强转 STORED，其它默认 DEFLATED
                if (e.getName().equals("resources.arsc")) {
                    ne.setMethod(java.util.zip.ZipEntry.STORED);
                    ne.setSize(e.getSize());
                    ne.setCompressedSize(e.getSize());
                    ne.setCrc(e.getCrc());
                } else {
                    ne.setMethod(java.util.zip.ZipEntry.DEFLATED);
                }
                ne.setTime(e.getTime());
                
                zos.putNextEntry(ne);
                try (java.io.InputStream is = zf.getInputStream(e)) {
                    int len;
                    while ((len = is.read(buf)) > 0) zos.write(buf, 0, len);
                }
                zos.closeEntry();
            }
            for (File dex : dexFiles) {
                java.util.zip.ZipEntry ze = new java.util.zip.ZipEntry(dex.getName());
                // 照搬云端逻辑：dex 默认 DEFLATED 压缩
                ze.setMethod(java.util.zip.ZipEntry.DEFLATED);
                zos.putNextEntry(ze);
                try (java.io.FileInputStream fis = new java.io.FileInputStream(dex)) {
                    int len;
                    while ((len = fis.read(buf)) > 0) zos.write(buf, 0, len);
                }
                zos.closeEntry();
            }
        }
    }

    private int packageNativeLibsAndAssets(File srcApk, File outApk, File projectDir, StringBuilder log) {
        int count = 0;
        try {
            java.util.Map<String, File> toAdd = new java.util.LinkedHashMap<>();

            File jniLibsDir = findDir(projectDir, "jniLibs");
            if (jniLibsDir != null && jniLibsDir.isDirectory()) {
                java.util.List<File> soFiles = new java.util.ArrayList<>();
                collectFilesRecursive(jniLibsDir, soFiles);
                String libsBase = jniLibsDir.getAbsolutePath();
                for (File so : soFiles) {
                    if (!so.getName().endsWith(".so")) continue;
                    String relPath = so.getAbsolutePath().substring(libsBase.length() + 1);
                    toAdd.put("lib/" + relPath, so);
                }
                log.append("[native-assets] 收集 jniLibs 完毕，找到 .so 数量: ").append(soFiles.size()).append("\n");
            }

            File assetsDir = findDir(projectDir, "assets");
            if (assetsDir != null && assetsDir.isDirectory()) {
                java.util.List<File> assetFiles = new java.util.ArrayList<>();
                collectFilesRecursive(assetsDir, assetFiles);
                String assetsBase = assetsDir.getAbsolutePath();
                for (File af : assetFiles) {
                    String relPath = af.getAbsolutePath().substring(assetsBase.length() + 1);
                    toAdd.put("assets/" + relPath, af);
                }
                log.append("[native-assets] 收集 assets 完毕，找到文件数量: ").append(assetFiles.size()).append("\n");
            }

            count = toAdd.size();
            if (count == 0) return 0;

            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(srcApk);
                 java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
                        new java.io.FileOutputStream(outApk))) {

                byte[] buf = new byte[65536];
                java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    java.util.zip.ZipEntry e = en.nextElement();
                    if (e.getName().startsWith("assets/") || e.getName().startsWith("lib/")) continue;
                    java.util.zip.ZipEntry ne = new java.util.zip.ZipEntry(e.getName());
                    // 【关键】resources.arsc 被 Android 强制要求不能压缩，否则直接报兼容性错误
                    if (e.getName().equals("resources.arsc") || e.getName().endsWith(".dex")) {
                        ne.setMethod(java.util.zip.ZipEntry.STORED);
                        ne.setSize(e.getSize());
                        ne.setCompressedSize(e.getSize());
                        ne.setCrc(e.getCrc());
                    } else {
                        ne.setMethod(e.getMethod());
                    }
                    ne.setTime(e.getTime());
                    if (e.getMethod() == java.util.zip.ZipEntry.STORED) {
                        ne.setSize(e.getSize());
                        ne.setCompressedSize(e.getSize());
                        ne.setCrc(e.getCrc());
                    }
                    zos.putNextEntry(ne);
                    try (java.io.InputStream is = zf.getInputStream(e)) {
                        int len;
                        while ((len = is.read(buf)) > 0) zos.write(buf, 0, len);
                    }
                    zos.closeEntry();
                }

                int soCount = 0, assetCount = 0;
                for (java.util.Map.Entry<String, File> entry : toAdd.entrySet()) {
                    String name = entry.getKey();
                    File file = entry.getValue();

                    java.util.zip.ZipEntry ze = new java.util.zip.ZipEntry(name);
                    // 【核心修复】提前计算好 CRC 和 Size，防止生成 Data Descriptor，保证对齐工具能完美处理
                    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                    ze.setMethod(java.util.zip.ZipEntry.DEFLATED);
                    ze.setSize(file.length());
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                        int len;
                        while ((len = fis.read(buf)) > 0) crc.update(buf, 0, len);
                    }
                    ze.setCrc(crc.getValue());
                    zos.putNextEntry(ze);
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                        int len;
                        while ((len = fis.read(buf)) > 0) zos.write(buf, 0, len);
                    }
                    zos.closeEntry();
                    if (name.endsWith(".so")) soCount++; else assetCount++;
                }
                log.append("[native-assets] 实际写入: .so=").append(soCount).append(" 个, assets=").append(assetCount).append(" 个\n");
            }
            return count;
        } catch (Throwable t) {
            log.append("[native-assets] 打包异常: ").append(describe(t)).append("\n");
            return 0;
        }
    }

    /** 计算文件 CRC32 */
    private static long calcCrc(File f) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        byte[] buf = new byte[8192];
        try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
            int n;
            while ((n = fis.read(buf)) > 0) crc.update(buf, 0, n);
        } catch (Throwable ignored) {}
        return crc.getValue();
    }

    /** 在项目目录树中查找指定名称的目录（支持 src/main/xxx 或项目根目录 xxx） */
    private File findDir(File projectDir, String name) {
        // 优先 src/main/{name}
        File f = new File(projectDir, "src/main/" + name);
        if (f.exists() && f.isDirectory()) return f;
        // 其次项目根目录/{name}
        f = new File(projectDir, name);
        if (f.exists() && f.isDirectory()) return f;
        // 递归查找（最多3层）
        return findDirRecursive(projectDir, name, 0);
    }

    private File findDirRecursive(File dir, String name, int depth) {
        if (depth > 3) return null;
        File[] children = dir.listFiles();
        if (children == null) return null;
        for (File c : children) {
            if (c.isDirectory() && c.getName().equals(name)) return c;
            if (c.isDirectory()) {
                File r = findDirRecursive(c, name, depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** 递归收集目录下所有文件 */
    private void collectFilesRecursive(File dir, java.util.List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) collectFilesRecursive(f, out);
            else if (f.isFile()) out.add(f);
        }
    }

    private int countClasses(File dir) {
        int n = 0;
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        for (File f : fs) {
            if (f.isDirectory()) n += countClasses(f);
            else if (f.getName().endsWith(".class")) n++;
        }
        return n;
    }

    /** 执行原生二进制（aapt2），合并 stdout/stderr，返回输出文本 */
    private String runProcess(File exe, String[] args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(exe.getAbsolutePath());
        for (String a : args) cmd.add(a);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append('\n');
        int exit = p.waitFor();
        if (exit != 0) sb.append("[exit ").append(exit).append("]");
        return sb.toString();
    }

    /**
     * 反射调用 d8：用户 class jar + 依赖 jar → classes.dex。
     * 依赖（androidx + Material）打进产物 dex，使编译产物 APK 可独立运行。
     * 注意：必须用 D8Command.Builder 构造 + D8.run，绝不能用 D8.main——
     * main() 异常路径会 System.exit()，在 Android 上直接杀掉整个 App 进程；
     * 也不用 D8Command.parse（签名随 r8 版本变化，Builder API 稳定）。
     */
    private String runD8(File inputJar, File depJar, File outDir) throws Exception {
        File optDir = new File(toolDir, "opt");
        if (!optDir.exists() && !optDir.mkdirs()) optDir = null;
        ClassLoader cl = new dalvik.system.DexClassLoader(
                d8DexFile.getAbsolutePath(),
                optDir == null ? toolDir.getAbsolutePath() : optDir.getAbsolutePath(),
                null, BuiltinCompiler.class.getClassLoader());
        try {
            Class<?> d8 = Class.forName("com.android.tools.r8.D8", true, cl);
            Class<?> cmd = Class.forName("com.android.tools.r8.D8Command", true, cl);
            Object builder = cmd.getMethod("builder").invoke(null);
            builder.getClass().getMethod("setMinApiLevel", int.class).invoke(builder, 26);

            Class<?> pathsCls = Class.forName("java.nio.file.Paths");
            java.nio.file.Path libPath = (java.nio.file.Path) pathsCls.getMethod(
                    "get", String.class, String[].class)
                    .invoke(null, androidJarFile.getAbsolutePath(), new String[0]);
            java.nio.file.Path inPath = (java.nio.file.Path) pathsCls.getMethod(
                    "get", String.class, String[].class)
                    .invoke(null, inputJar.getAbsolutePath(), new String[0]);
            builder.getClass().getMethod("addLibraryFiles", java.nio.file.Path[].class)
                    .invoke(builder, (Object) new java.nio.file.Path[]{libPath});
            if (depJar != null && depJar.exists() && depJar.length() > 0) {
                java.nio.file.Path depPath = (java.nio.file.Path) pathsCls.getMethod(
                        "get", String.class, String[].class)
                        .invoke(null, depJar.getAbsolutePath(), new String[0]);
                builder.getClass().getMethod("addProgramFiles", java.nio.file.Path[].class)
                        .invoke(builder, (Object) new java.nio.file.Path[]{inPath, depPath});
            } else {
                builder.getClass().getMethod("addProgramFiles", java.nio.file.Path[].class)
                        .invoke(builder, (Object) new java.nio.file.Path[]{inPath});
            }
            Class<?> outputMode = Class.forName("com.android.tools.r8.OutputMode", true, cl);
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object dexIndexed = Enum.valueOf((Class) outputMode, "DexIndexed");
            java.nio.file.Path outPath = (java.nio.file.Path) pathsCls.getMethod(
                    "get", String.class, String[].class)
                    .invoke(null, outDir.getAbsolutePath(), new String[0]);
            builder.getClass().getMethod("setOutput", java.nio.file.Path.class, outputMode)
                    .invoke(builder, outPath, dexIndexed);
            Object command = builder.getClass().getMethod("build").invoke(builder);
            d8.getMethod("run", cmd).invoke(null, command);
        } catch (Throwable t) {
            Throwable c = t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null
                    ? t.getCause() : t;
            StringBuilder sb = new StringBuilder();
            sb.append("[d8 异常] ").append(describe(c));
            // d8 CompilationFailedException 通常带 diagnostics 列表，打印它
            try {
                java.lang.reflect.Method dm = c.getClass().getMethod("getDiagnostics");
                Object diags = dm.invoke(c);
                if (diags instanceof Iterable) {
                    for (Object d : (Iterable<?>) diags) {
                        sb.append("\n  - ").append(String.valueOf(d));
                    }
                }
            } catch (Throwable ignored) {}
            // 打印完整堆栈最后 10 行
            java.io.StringWriter sw = new java.io.StringWriter();
            c.printStackTrace(new java.io.PrintWriter(sw));
            String[] lines = sw.toString().split("\n");
            int start = Math.max(0, lines.length - 15);
            sb.append("\n  堆栈:\n");
            for (int i = start; i < lines.length; i++) sb.append("  ").append(lines[i]).append("\n");
            return sb.toString();
        }
        return "";
    }

    /** 反射调用 apksig 库签名：unsigned.apk → signed.apk（内置 debug keystore）。
     *  用库 API（ApkSigner.Builder）而非 ApkSignerTool.main，避免 System.exit 杀进程。 */
    private String runApksigner(File input, File output, File ksFile) throws Exception {
        File optDir = new File(toolDir, "opt");
        if (!optDir.exists() && !optDir.mkdirs()) optDir = null;
        ClassLoader cl = new dalvik.system.DexClassLoader(
                apksignerDexFile.getAbsolutePath(),
                optDir == null ? toolDir.getAbsolutePath() : optDir.getAbsolutePath(),
                null, BuiltinCompiler.class.getClassLoader());
        try {
            java.security.KeyStore ks = null;
            try {
                ks = java.security.KeyStore.getInstance("JKS");
                FileInputStream ksin = new FileInputStream(ksFile);
                ks.load(ksin, KS_PASS.toCharArray());
                ksin.close();
            } catch (Throwable jksFail) {
                ks = java.security.KeyStore.getInstance("PKCS12");
                FileInputStream ksin = new FileInputStream(ksFile);
                ks.load(ksin, KS_PASS.toCharArray());
                ksin.close();
            }
            java.security.PrivateKey key = (java.security.PrivateKey) ks.getKey(KS_ALIAS, KS_PASS.toCharArray());
            java.security.cert.X509Certificate cert =
                    (java.security.cert.X509Certificate) ks.getCertificate(KS_ALIAS);

            Class<?> cfgBuilderCls = Class.forName(
                    "com.android.apksig.ApkSigner$SignerConfig$Builder", true, cl);
            Object cfgBuilder = cfgBuilderCls.getConstructor(
                    String.class, java.security.PrivateKey.class, java.util.List.class)
                    .newInstance(KS_ALIAS, key, java.util.Collections.singletonList(cert));
            Object signerConfig = cfgBuilder.getClass().getMethod("build").invoke(cfgBuilder);

            Class<?> apkSignerBuilderCls = Class.forName(
                    "com.android.apksig.ApkSigner$Builder", true, cl);
            Object signerBuilder = apkSignerBuilderCls.getConstructor(java.util.List.class)
                    .newInstance(java.util.Collections.singletonList(signerConfig));
            signerBuilder.getClass().getMethod("setInputApk", File.class).invoke(signerBuilder, input);
            signerBuilder.getClass().getMethod("setOutputApk", File.class).invoke(signerBuilder, output);
            signerBuilder.getClass().getMethod("setMinSdkVersion", int.class).invoke(signerBuilder, 21);
            signerBuilder.getClass().getMethod("setV1SigningEnabled", boolean.class).invoke(signerBuilder, true);
            signerBuilder.getClass().getMethod("setV2SigningEnabled", boolean.class).invoke(signerBuilder, true);
            signerBuilder.getClass().getMethod("setV3SigningEnabled", boolean.class).invoke(signerBuilder, true);
            Object signer = signerBuilder.getClass().getMethod("build").invoke(signerBuilder);
            signer.getClass().getMethod("sign").invoke(signer);
        } catch (Throwable t) {
            Throwable c = t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null
                    ? t.getCause() : t;
            return "[apksigner 异常] " + describe(c);
        }
        return "";
    }

    /** 把 classes 目录递归打成 jar（d8 只吃 jar/zip，不吃目录） */
    private void jarClasses(File dir, File jar) throws IOException {
        java.util.zip.ZipOutputStream zos = null;
        try {
            zos = new java.util.zip.ZipOutputStream(new FileOutputStream(jar));
            addToJar(dir, dir, zos);
        } finally {
            if (zos != null) try { zos.close(); } catch (IOException ignored) {}
        }
    }

    /** 剔除 classes 目录中与依赖 jar 重复的 .class（d8 双 program 输入不允许重复类）。 */
    private int removeDuplicateClasses(File classesDir, File depJar) {
        java.util.HashSet<String> depEntries = new java.util.HashSet<>();
        java.util.zip.ZipInputStream zin = null;
        try {
            zin = new java.util.zip.ZipInputStream(new java.io.FileInputStream(depJar));
            java.util.zip.ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (!e.isDirectory() && e.getName().endsWith(".class")) {
                    depEntries.add(e.getName());
                }
                zin.closeEntry();
            }
        } catch (Throwable ignored) {
            return 0;
        } finally {
            if (zin != null) try { zin.close(); } catch (java.io.IOException ignored) {}
        }
        int[] removed = new int[1];
        removeDuplicateRecursive(classesDir, classesDir, depEntries, removed);
        return removed[0];
    }

    private void removeDuplicateRecursive(File base, File dir, java.util.HashSet<String> depEntries, int[] removed) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) {
                removeDuplicateRecursive(base, f, depEntries, removed);
                // 空目录清理
                File[] left = f.listFiles();
                if (left != null && left.length == 0) f.delete();
            } else if (f.getName().endsWith(".class")) {
                String rel = f.getAbsolutePath().substring(base.getAbsolutePath().length() + 1)
                        .replace(File.separatorChar, '/');
                if (depEntries.contains(rel)) {
                    if (f.delete()) removed[0]++;
                }
            }
        }
    }

    private void addToJar(File base, File dir, java.util.zip.ZipOutputStream zos) throws IOException {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) {
                addToJar(base, f, zos);
            } else {
                String rel = f.getAbsolutePath().substring(base.getAbsolutePath().length() + 1)
                        .replace(File.separatorChar, '/');
                zos.putNextEntry(new java.util.zip.ZipEntry(rel));
                FileInputStream in = new FileInputStream(f);
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
                in.close();
                zos.closeEntry();
            }
        }
    }

    private void createEmptyZip(File f) throws IOException {
        java.util.zip.ZipOutputStream zos = null;
        try {
            zos = new java.util.zip.ZipOutputStream(new FileOutputStream(f));
            zos.finish();
        } finally {
            if (zos != null) try { zos.close(); } catch (IOException ignored) {}
        }
    }


    private File findManifest(File dir) {
        File direct = new File(dir, "src/main/AndroidManifest.xml");
        if (direct.exists()) return direct;
        direct = new File(dir, "AndroidManifest.xml");
        if (direct.exists()) return direct;
        
        File[] fs = dir.listFiles();
        if (fs == null) return null;
        for (File f : fs) {
            if (f.isDirectory()) {
                if ("build".equals(f.getName()) || "build-merged".equals(f.getName())
                        || ".gradle".equals(f.getName()) || ".git".equals(f.getName())) continue;
                File hit = findManifest(f);
                if (hit != null) return hit;
            } else if ("AndroidManifest.xml".equals(f.getName())) {
                return f;
            }
        }
        return null;
    }


    // 递归找真正的项目根：含 AndroidManifest.xml 且有 src/main/java 的目录
    private File findProjectRootRecursive(File dir, int depth) {
        if (depth > 3 || dir == null) return null;
        File manifest = new File(dir, "AndroidManifest.xml");
        File srcMain = new File(dir, "src/main/java");
        if (manifest.isFile() && srcMain.isDirectory()) return dir;
        File[] kids = dir.listFiles();
        if (kids == null) return null;
        for (File f : kids) {
            if (!f.isDirectory()) continue;
            String n = f.getName();
            if ("build".equals(n) || ".git".equals(n) || ".gradle".equals(n)
                    || ".idea".equals(n) || "assets".equals(n)) continue;
            File hit = findProjectRootRecursive(f, depth + 1);
            if (hit != null) return hit;
        }
        return null;
    }

    /** 判断 manifest 的 <manifest> 标签是否已有 package 属性（AGP 8+ 新模板没有） */
    private boolean manifestHasPackage(File manifest) {
        try {
            String text = readFile(manifest);
            return text != null && MANIFEST_PACKAGE.matcher(text).find();
        } catch (Throwable t) {
            Log.w(TAG, "read manifest failed", t);
            return true; // 读不到就当作已有 package，让 aapt2 报真实错误
        }
    }

    /**
     * 向 <manifest ...> 注入 AGP 8+ 模板缺失的属性（设备端没有 Gradle，这些值
     * 平时由 build.gradle 注入）：package、versionCode、versionName、uses-sdk。
     * 缺失 minSdk/targetSdk/versionCode 的 APK 在安装时会被系统判为
     * 「解析软件包时出现问题」，必须补齐。返回新 manifest 全文。
     */
    private String injectManifestDefaults(File manifest, String pkg) {
        String text = readFile(manifest);
        if (text == null) return text;

        // 1. 直接使用 replace 清理敏感属性，绝不使用坐标替换
        text = text.replaceAll("\\s*android:testOnly\\s*=\\s*\"[^\"]*\"", "");
        text = text.replaceAll("\\s*android:debuggable\\s*=\\s*\"[^\"]*\"", "");
        text = text.replaceAll("\\s*android:extractNativeLibs\\s*=\\s*\"[^\"]*\"", ""); 
        text = text.replaceAll("\\s*package\\s*=\\s*[\"'][^\"']*[\"']", ""); 

        // 2. 使用正则表达式直接匹配整个 <manifest ...> 标签进行替换，绝不用坐标
        Matcher m = Pattern.compile("<manifest\\b([^>]*?)>", Pattern.DOTALL).matcher(text);
        if (m.find()) {
            String head = m.group(1);
            head += " package=\"" + (pkg != null ? pkg : "com.aicodestudio.ide") + "\"";
            if (!head.contains("android:versionCode")) head += " android:versionCode=\"79\"";
            if (!head.contains("android:versionName")) head += " android:versionName=\"1.76-fix4\"";
            // 安全替换
            text = m.replaceFirst("<manifest" + head + ">");
        }

        // 3. 安全地替换 uses-sdk 和注入 extractNativeLibs
        text = text.replaceAll("<uses-sdk[^>]*>", "");
        // 【关键修复】先清理掉所有的重复属性，再统一注入
        text = text.replaceAll("\\s*android:extractNativeLibs\\s*=\\s*\"[^\"]*\"", "");
        text = text.replaceAll("\\s*android:resizeableActivity\\s*=\\s*\"[^\"]*\"", "");
        text = text.replaceAll("\\s*android:maxAspectRatio\\s*=\\s*\"[^\"]*\"", "");
        // 统一注入唯一属性
        text = text.replaceAll("<application\\b([^>]*?)>", "<application$1 android:extractNativeLibs=\"true\" android:resizeableActivity=\"true\" android:maxAspectRatio=\"2.4\">");
        int idx = text.lastIndexOf("</manifest>");
        if (idx > 0) {
            text = text.substring(0, idx) + "    <uses-sdk android:minSdkVersion=\"21\" android:targetSdkVersion=\"34\" />\n" + text.substring(idx);
        }
        return text;
    }

    private String readFile(File f) {
        try {
            StringBuilder sb = new StringBuilder((int) Math.min(f.length(), 1 << 20));
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            r.close();
            return sb.toString();
        } catch (Throwable t) {
            Log.w(TAG, "read file failed: " + f.getName(), t);
            return null;
        }
    }

    private File findRJava(File dir) {
        File[] fs = dir.listFiles();
        if (fs == null) return null;
        for (File f : fs) {
            if (f.isDirectory()) {
                File hit = findRJava(f);
                if (hit != null) return hit;
            } else if ("R.java".equals(f.getName())) {
                return f;
            }
        }
        return null;
    }

    /** 递归统计目录下文件数 */
    private int countFilesRecursive(File dir) {
        if (dir == null || !dir.isDirectory()) return 0;
        int count = 0;
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        for (File f : fs) {
            if (f.isDirectory()) count += countFilesRecursive(f);
            else count++;
        }
        return count;
    }

    /** 递归打印目录内容到日志（限制深度和数量） */
    private void dumpDirToLog(File dir, String prefix, StringBuilder log, int depth) {
        if (depth > 4 || dir == null || !dir.isDirectory()) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        int limit = 20;
        for (int i = 0; i < fs.length && i < limit; i++) {
            log.append(prefix).append(fs[i].getName());
            if (fs[i].isDirectory()) {
                log.append('/').append('\n');
                dumpDirToLog(fs[i], prefix + "  ", log, depth + 1);
            } else {
                log.append(" (").append(fs[i].length()).append(" bytes)\n");
            }
        }
        if (fs.length > limit) {
            log.append(prefix).append("... 还有 ").append(fs.length - limit).append(" 个文件\n");
        }
    }

    /**
     * 编译项目目录下全部 .java 文件（递归）
     *
     * @param projectDir 项目根目录（源码可位于其下任意子目录，如 src/main/java）
     * @param extraClasspath 额外 classpath（分号/冒号分隔），可为空
     * @return 编译结果
     */
    public CompileResult compileProject(File projectDir, String extraClasspath) {
        CompileResult result = new CompileResult();
        if (!prepare()) {
            result.errorText = prepareError == null ? "内置工具链不可用" : prepareError;
            result.rawLog = result.errorText;
            result.success = false;
            return result;
        }

        List<File> sources = new ArrayList<>();
        collectJavaFiles(projectDir, sources);

        // 项目自带 libs（libs/*.jar + 任意 *.aar 解压 classes.jar）→ 编译 classpath
        String projectLibs = collectProjectLibs(projectDir);

        // R 类生成（阶段一过渡，替代 aapt2）：扫描 res/ + 包名 → 生成 R.java 加入编译
        try {
            File resDir = RClassGenerator.findResDir(projectDir);
            String pkg = "com.aicodestudio.ide"; // 自举编译写死包名
            String rJava = RClassGenerator.generate(resDir, pkg);
            if (rJava != null && rJava.length() > 0) {
                File genDir = new File(context.getCacheDir(), "compile_gen");
                if (!genDir.exists() && !genDir.mkdirs()) {
                    Log.w(TAG, "无法创建 R 生成目录");
                } else {
                    File rFile = new File(genDir, "R.java");
                    writeFile(rFile, rJava);
                    sources.add(rFile);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "R 类生成失败（跳过，纯 Java 项目无影响）", t);
        }

        if (sources.isEmpty()) {
            result.errorText = "未找到任何 .java 源文件";
            result.rawLog = result.errorText;
            result.success = false;
            return result;
        }

        // 合并 classpath：项目 libs + 外部追加
        String cp = projectLibs;
        if (extraClasspath != null && extraClasspath.trim().length() > 0) {
            cp = cp.length() > 0
                    ? cp + File.pathSeparator + extraClasspath.trim()
                    : extraClasspath.trim();
        }

        String[] args = buildArgs(projectDir, sources, cp);
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        boolean ok = false;
        try {
            ok = runEcj(args, pw);
        } catch (Throwable t) {
            Log.e(TAG, "ECJ invoke failed", t);
            result.errorText = "内置编译器调用失败: " + describe(t);
            result.rawLog = result.errorText;
            result.success = false;
            return result;
        }
        pw.flush();
        result.rawLog = sw.toString();

        // 解析标准错误
        parseEcjOutput(result.rawLog, result.errors);
        for (CompileError e : result.errors) {
            if (e.isError()) result.errorCount++;
            else result.warningCount++;
        }
        result.success = ok && result.errorCount == 0;
        result.errorText = buildErrorText(result.errors);
        return result;
    }

    private String[] buildArgs(File projectDir, List<File> sources, String extraClasspath) {
        List<String> args = new ArrayList<>();
        args.add("-source");
        args.add("1.8");
        args.add("-target");
        args.add("1.8");
        args.add("-encoding");
        args.add("UTF-8");
        args.add("-proc:none");
        args.add("-nowarn");
        // 显式 bootclasspath=android.jar（ECJ 未指定时读 java.class.path 会踩 /apex .so）
        args.add("-bootclasspath");
        args.add(androidJarFile.getAbsolutePath());
        // classpath：android.jar + androidx-all.jar(含 Material) + 项目 libs + 额外路径
        StringBuilder cp = new StringBuilder(androidJarFile.getAbsolutePath());
        cp.append(File.pathSeparator).append(androidxJarFile.getAbsolutePath());
        if (extraClasspath != null && extraClasspath.trim().length() > 0) {
            cp.append(File.pathSeparator).append(extraClasspath.trim());
        }
        args.add("-cp");
        args.add(cp.toString());
        // 输出目录：私有目录下 cache/classes（每次编译前清理）
        File outDir = new File(context.getCacheDir(), "compile_classes");
        if (outDir.exists()) {
            deleteRecursive(outDir);
        }
        //noinspection ResultOfMethodCallIgnored
        outDir.mkdirs();
        args.add("-d");
        args.add(outDir.getAbsolutePath());
        for (File f : sources) {
            args.add(f.getAbsolutePath());
        }
        return args.toArray(new String[0]);
    }

    /**
     * 反射调用 ECJ batch Main.compile
     * Main(PrintWriter out, PrintWriter err, boolean systemExit, Map options, CompilationProgress progress)
     */
    private boolean runEcj(String[] args, PrintWriter pw) throws Exception {
        // 用 DexClassLoader 加载 ecj.dex（ART 无法直接运行 jar 中的 .class，必须先转 dex）
        File optDir = new File(toolDir, "opt");
        if (!optDir.exists() && !optDir.mkdirs()) {
            optDir = null;
        }
        final String dexPath = ecjFile.getAbsolutePath()
                + File.pathSeparator + ecjJarFile.getAbsolutePath();
        final String optPath = optDir == null ? toolDir.getAbsolutePath() : optDir.getAbsolutePath();

        // 按阶段拆分 try，异常信息完整输出，便于实机定位
        // 清空 java.class.path：Android 上该属性含 /apex/com.android.art/lib/（native .so），
        // ECJ 未指定 bootclasspath 时会尝试把 .so 当 jar 打开报 "zip END header not found"
        String savedJavaCp = null;
        try {
            savedJavaCp = System.getProperty("java.class.path");
            System.setProperty("java.class.path", "");
        } catch (Throwable ignored) {
        }
        try {
            ClassLoader cl;
            try {
                cl = new dalvik.system.DexClassLoader(dexPath, optPath, null,
                        BuiltinCompiler.class.getClassLoader());
            } catch (Throwable t) {
                throw new IllegalStateException("[加载ecj.dex失败] " + describe(t), t);
            }

            Class<?> mainClass;
            try {
                mainClass = Class.forName("org.eclipse.jdt.internal.compiler.batch.Main", true, cl);
            } catch (Throwable t) {
                throw new IllegalStateException("[查找Main类失败] " + describe(t), t);
            }

            // 找到 (PrintWriter, PrintWriter, boolean, Map, CompilationProgress) 构造
            Constructor<?> ctor = null;
            try {
                for (Constructor<?> c : mainClass.getConstructors()) {
                    Class<?>[] pts = c.getParameterTypes();
                    if (pts.length == 5 && pts[0] == PrintWriter.class && pts[1] == PrintWriter.class) {
                        ctor = c;
                        break;
                    }
                }
                if (ctor == null) throw new IllegalStateException("ECJ Main 构造器不匹配");
                ctor.setAccessible(true);
            } catch (Throwable t) {
                throw new IllegalStateException("[查找Main构造器失败] " + describe(t), t);
            }

            Object main;
            try {
                // options 传空 HashMap 而非 null，避免 ECJ 构造器内直接使用 options 触发 NPE
                main = ctor.newInstance(pw, pw, Boolean.FALSE, new java.util.HashMap<String, String>(), null);
            } catch (Throwable t) {
                Throwable c = t.getCause() != null ? t.getCause() : t;
                throw new IllegalStateException("[构造Main失败] " + describe(c), t);
            }

            Method compile;
            try {
                compile = mainClass.getMethod("compile", String[].class);
            } catch (Throwable t) {
                throw new IllegalStateException("[查找compile方法失败] " + describe(t), t);
            }

            try {
                Object result = compile.invoke(main, (Object) args);
                return result instanceof Boolean && (Boolean) result;
            } catch (Throwable t) {
                Throwable c = t.getCause() != null ? t.getCause() : t;
                throw new IllegalStateException("[调用compile失败] " + describe(c), t);
            }
        } finally {
            if (savedJavaCp != null) {
                try {
                    System.setProperty("java.class.path", savedJavaCp);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 完整描述异常：类名 + message + 堆栈首行 */
    private static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        sb.append(t.getClass().getName());
        if (t.getMessage() != null) sb.append(": ").append(t.getMessage());
        StackTraceElement[] st = t.getStackTrace();
        if (st != null && st.length > 0) {
            sb.append(" @ ").append(st[0].getClassName())
              .append('.').append(st[0].getMethodName())
              .append('(').append(st[0].getLineNumber()).append(')');
        }
        return sb.toString();
    }

    /**
     * 解析 ECJ 输出：
     *   ----------
     *   1. ERROR in /path/File.java (at line 3)
     *       System.out.println(x);
     *                        ^
     *   x cannot be resolved to a variable
     *   ----------
     */
    private void parseEcjOutput(String raw, List<CompileError> out) {
        if (raw == null || raw.length() == 0) return;
        String[] lines = raw.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("----------")) continue;
            Matcher m = BLOCK_HEADER.matcher(line);
            if (!m.matches()) continue;
            // 已定位到块头
            String file = m.group(3).trim();
            int lineno = parseInt(m.group(4));
            int severity = "ERROR".equals(m.group(2))
                    ? CompileError.SEVERITY_ERROR : CompileError.SEVERITY_WARNING;
            // 下一行：代码行（\t 前缀）；再下一行：\t + ^^^（列定位）
            int column = -1;
            if (i + 2 < lines.length) {
                String caretLine = lines[i + 2];
                int caret = caretLine.indexOf('^');
                if (caret >= 0) {
                    column = caret; // \t 算 1 列
                }
            }
            // 消息行：caret 行之后的非 ----- 行
            String msg = "";
            for (int j = i + 3; j < lines.length; j++) {
                String s = lines[j];
                if (s.startsWith("----------")) break;
                if (s.trim().length() > 0) {
                    msg = s.trim();
                    break;
                }
            }
            if (msg.length() == 0 && i + 1 < lines.length) {
                msg = lines[i + 1].trim();
            }
            out.add(new CompileError(file, lineno, column, msg, severity));
        }
    }

    private String buildErrorText(List<CompileError> errors) {
        if (errors.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (CompileError e : errors) {
            if (!e.isError()) continue;
            sb.append(e.toShortString()).append('\n');
        }
        return sb.toString();
    }

    private void collectJavaFiles(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                // 跳过 assets/build 等非源码目录：assets 内可能含工具链 .java
                // （如 assets/compile/CrashCapture.java），会被误当项目源码收集，
                // 与编译器注入的同名崩溃采集类冲突（The type X is already defined）
                String n = f.getName();
                if ("assets".equals(n) || "build".equals(n) || "cache".equals(n)
                        || ".git".equals(n) || ".gradle".equals(n)) continue;
                collectJavaFiles(f, out);
            } else if (f.getName().endsWith(".java")) {
                out.add(f);
            }
        }
    }

    /** 判断 sources 列表里是否已有同名 .java（按文件名），用于避免重复注入。 */
    private boolean sourcesContainsName(List<File> sources, String fileName) {
        for (File f : sources) {
            if (f != null && fileName.equals(f.getName())) return true;
        }
        return false;
    }

    /**
     * 收集项目自带依赖到 classpath：
     *   - libs 目录下 *.jar 直接使用
     *   - 任意 *.aar 解压出 classes.jar（缓存到 cacheDir/compile_aar/）
     */
    private String collectProjectLibs(File projectDir) {
        List<File> jars = new ArrayList<>();
        List<File> aars = new ArrayList<>();
        scanLibs(projectDir, jars, aars);
        StringBuilder cp = new StringBuilder();
        for (File jar : jars) {
            if (cp.length() > 0) cp.append(File.pathSeparator);
            cp.append(jar.getAbsolutePath());
        }
        File aarOutDir = new File(context.getCacheDir(), "compile_aar");
        if (!aarOutDir.exists() && !aarOutDir.mkdirs()) {
            Log.w(TAG, "无法创建 aar 缓存目录");
            return cp.toString();
        }
        for (File aar : aars) {
            try {
                File classesJar = extractAarClasses(aar, aarOutDir);
                if (classesJar != null && classesJar.exists()) {
                    if (cp.length() > 0) cp.append(File.pathSeparator);
                    cp.append(classesJar.getAbsolutePath());
                }
            } catch (Throwable t) {
                Log.w(TAG, "aar 解压失败: " + aar.getName(), t);
            }
        }
        return cp.toString();
    }

    /** 递归扫描：libs 目录的 *.jar 收进 jars，*.aar 收进 aars（跳过 build 等产物目录） */
    private void scanLibs(File dir, List<File> jars, List<File> aars) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String n = f.getName();
            if (f.isDirectory()) {
                if ("build".equals(n) || "build-merged".equals(n)
                        || ".gradle".equals(n) || ".git".equals(n)) continue;
                scanLibs(f, jars, aars);
            } else if (n.endsWith(".jar") && "libs".equals(dir.getName())) {
                jars.add(f);
            } else if (n.endsWith(".aar")) {
                aars.add(f);
            }
        }
    }

    /** 从 aar 中解出 classes.jar（幂等：缓存文件已存在且非空则直接复用） */
    private File extractAarClasses(File aar, File outDir) throws IOException {
        String base = aar.getName();
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        File target = new File(outDir, base + "_classes.jar");
        if (target.exists() && target.length() > 0) return target;
        java.util.zip.ZipFile zf = null;
        FileOutputStream fos = null;
        InputStream in = null;
        try {
            zf = new java.util.zip.ZipFile(aar);
            java.util.zip.ZipEntry entry = zf.getEntry("classes.jar");
            if (entry == null) return null;
            in = zf.getInputStream(entry);
            fos = new FileOutputStream(target);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            fos.flush();
            return target;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
            if (fos != null) try { fos.close(); } catch (IOException ignored) {}
            if (zf != null) try { zf.close(); } catch (IOException ignored) {}
        }
    }

    private void writeFile(File f, String content) throws IOException {
        if (content == null) content = "";
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(f);
            fos.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            fos.flush();
        } finally {
            if (fos != null) try { fos.close(); } catch (IOException ignored) {}
        }
    }

    private void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }
}
