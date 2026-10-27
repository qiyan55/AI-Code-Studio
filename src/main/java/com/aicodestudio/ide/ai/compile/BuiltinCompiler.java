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
 * 修复：Android 14+ DexClassLoader 的安全限制（Writable dex file is not allowed）
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
    private static final String CRASH_CAPTURE = "CrashCapture.java";

    /**
     * 工具链版本标记：升级到 v12.3，强制清理旧的可写文件
     */
    private static final String TOOLCHAIN_VERSION = "v12.3";
    private static final String VERSION_FILE = ".toolchain_version";

    private static final Pattern MANIFEST_PACKAGE =
	Pattern.compile("<manifest\\b[^>]*\\bpackage\\s*=");
    private static final Pattern MANIFEST_VERSION_CODE =
	Pattern.compile("<manifest\\b[^>]*\\bandroid:versionCode\\s*=");
    private static final Pattern MANIFEST_VERSION_NAME =
	Pattern.compile("<manifest\\b[^>]*\\bandroid:versionName\\s*=");
    private static final Pattern MANIFEST_TAG =
	Pattern.compile("<manifest\\b([^>]*?)>", Pattern.DOTALL);
    private static final String KS_ALIAS = "aicode";
    private static final String KS_PASS = "aicode123";

    private static String targetSdkForDevice() {
        int device = Build.VERSION.SDK_INT;
        int target = Math.min(device, 36);
        if (target < 26) target = 26;
        return String.valueOf(target);
    }

    private static final Pattern BLOCK_HEADER =
	Pattern.compile("^(\\d+)\\.\\s+(ERROR|WARNING)\\s+in\\s+(.+?)\\(at\\s+line\\s+(\\d+)\\)\\s*$");

    private final Context context;
    private File toolDir;
    private File ecjFile;
    private File ecjJarFile;
    private File androidJarFile;
    private File androidxJarFile;
    private File matResZipFile;
    private File d8DexFile;
    private File apksignerDexFile;
    private File aapt2File;
    private File keystoreFile;
    private File crashCaptureFile;
    private File lambdaStubJarFile;
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
        File nativeAapt2 = new File(context.getApplicationInfo().nativeLibraryDir, "libaapt2.so");
        this.aapt2File = nativeAapt2.exists() ? nativeAapt2 : new File(toolDir, AAPT2_BIN);
        this.keystoreFile = new File(toolDir, KEYSTORE);
        this.lambdaStubJarFile = new File(toolDir, "core-lambda-stubs.jar");
    }

    public synchronized boolean prepare() {
        if (prepared) return true;
        try {
            String currentVer = readVersionFile();
            if (currentVer == null || !currentVer.equals(TOOLCHAIN_VERSION)) {
                Log.i(TAG, "toolchain version " + currentVer + " != " + TOOLCHAIN_VERSION + ", re-extracting toolchain");
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
            if (!aapt2File.exists() || aapt2File.length() < 1_000_000) {
                File fallback = new File(toolDir, AAPT2_BIN);
                extractAsset(AAPT2_BIN, fallback);
                try { fallback.setExecutable(true, false); } catch (Throwable ignored) {}
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
                prepareError = "工具链文件不完整";
                return false;
            }
            try { writeVersionFile(TOOLCHAIN_VERSION); } catch (Throwable ignored) {}
            prepared = true;
            return true;
        } catch (Throwable t) {
            prepareError = "工具链解压失败: " + t.getMessage();
            Log.e(TAG, "prepare failed", t);
            return false;
        }
    }

    public String getPrepareError() {
        return prepareError;
    }

    public boolean isPrepared() {
        if (prepared) return true;
        String ver = readVersionFile();
        if (ver == null || !ver.equals(TOOLCHAIN_VERSION)) return false;
        return ecjFile.exists() && ecjJarFile.exists()
			&& androidJarFile.exists() && androidxJarFile.exists() && matResZipFile.exists()
			&& d8DexFile.exists() && apksignerDexFile.exists() && aapt2File.exists();
    }

    private void extractAsset(String assetName, File target) throws IOException {
        // 强制删除可能已存在的可写文件
        if (target.exists()) {
            target.delete();
        }
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

            // 🔴 核心修复：立刻设为只读，并强制同步
            target.setReadOnly();
            target.setReadable(true, false);
            target.setExecutable(false, false);
        }
    }

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

    public static class CompileResult {
        public boolean success;
        public final List<CompileError> errors = new ArrayList<>();
        public String rawLog = "";
        public String errorText = "";
        public int errorCount;
        public int warningCount;
    }

    public static class BuildApkResult {
        public boolean success;
        public File apkFile;
        public String packageName;
        public String log = "";
        public String error = "";
    }

    public interface BuildListener {
        void onBuildLog(String msg, int level);
    }

    public BuildApkResult buildApk(File projectDir, String extraClasspath, BuildListener listener) {
        BuildApkResult r = new BuildApkResult();
        StringBuilder log = new StringBuilder();
        if (!prepare()) {
            r.error = prepareError == null ? "内置工具链不可用" : prepareError;
            r.log = log.toString();
            return r;
        }
        if (findManifest(projectDir) == null) {
            File deeper = findProjectRootRecursive(projectDir, 0);
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
            genDir.mkdirs();
            File classesDir = new File(work, "classes");
            classesDir.mkdirs();
            File dexDir = new File(work, "dex");
            dexDir.mkdirs();
            File apkDir = new File(context.getCacheDir(), "compile_apk");
            if (!apkDir.exists() && !apkDir.mkdirs()) {
                r.error = "无法创建 APK 输出目录";
                r.log = log.toString();
                return r;
            }

            File resDir = RClassGenerator.findResDir(projectDir);
            File manifest = findManifest(projectDir);
            String pkg = RClassGenerator.findPackageName(projectDir);
            if (manifest == null) {
                String mp = (pkg != null && pkg.length() > 0) ? pkg : "com.generated.app";
                manifest = new File(work, "AndroidManifest.xml");
                writeFile(manifest, "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
						  + "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\""
						  + " package=\"" + mp + "\">\n"
						  + "    <uses-sdk android:minSdkVersion=\"26\" android:targetSdkVersion=\""
						  + targetSdkForDevice() + "\" />\n"
						  + "    <application android:label=\"App\" />\n"
						  + "</manifest>\n");
            } else if (!manifestHasPackage(manifest)) {
                if (pkg == null || pkg.length() == 0 || !pkg.matches("[a-zA-Z_][\\w.]*")) {
                    r.error = "AndroidManifest.xml 缺少 package 属性，且无法推断包名。";
                    r.log = log.toString();
                    return r;
                }
                File tmpManifest = new File(work, "AndroidManifest.xml");
                writeFile(tmpManifest, injectManifestDefaults(manifest, pkg));
                manifest = tmpManifest;
                log.append("[manifest] 检测到无 package 属性，已注入\n");
            } else {
                String injected = injectManifestDefaults(manifest, null);
                if (injected != null && !injected.equals(readFile(manifest))) {
                    File tmpManifest = new File(work, "AndroidManifest.xml");
                    writeFile(tmpManifest, injected);
                    manifest = tmpManifest;
                    log.append("[manifest] 已补齐缺失属性\n");
                }
            }

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
            linkArgs.add("--auto-add-overlay");
            if (matResZipFile != null && matResZipFile.exists() && matResZipFile.length() > 0) {
                linkArgs.add(matResZipFile.getAbsolutePath());
            } else {
                log.append("[aapt2] 警告：material-res.flat.zip 缺失\n");
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

            List<File> sources = new ArrayList<>();
            collectJavaFiles(projectDir, sources);
            if (crashCaptureFile != null && crashCaptureFile.exists()
				&& !sourcesContainsName(sources, "CrashCapture.java")) {
                sources.add(crashCaptureFile);
                log.append("[crash] 已注入崩溃日志采集\n");
            }
            File rFile = findRJava(genDir);
            if (rFile != null && rFile.exists()) {
                log.append("[R-debug] findRJava 找到 R.java\n");
                sources.add(rFile);
            } else {
                log.append("[R-debug] findRJava 未找到，fallback 到生成器\n");
                String rJava = RClassGenerator.generate(resDir, pkg);
                if (rJava != null && rJava.length() > 0) {
                    File rf = new File(work, "R.java");
                    writeFile(rf, rJava);
                    sources.add(rf);
                }
            }
            if (sources.isEmpty()) {
                r.error = "未找到任何 .java 源文件";
                r.log = log.toString();
                return r;
            }

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
            log.append("[lambda] 已注入 Java 8 lambda 存根类\n");

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
            String bootCp = androidJarFile.getAbsolutePath();
            if (lambdaStubJarFile != null && lambdaStubJarFile.exists() && lambdaStubJarFile.length() > 0) {
                bootCp += File.pathSeparator + lambdaStubJarFile.getAbsolutePath();
                log.append("[ECJ] 已加载 core-lambda-stubs.jar\n");
            } else {
                log.append("[ECJ] 警告：core-lambda-stubs.jar 缺失\n");
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

            File invokeDir = new File(classesDir, "java/lang/invoke");
            if (invokeDir.isDirectory()) {
                deleteRecursive(invokeDir);
                log.append("[d8] 已剔除 java/lang/invoke 编译期存根类\n");
            }

            if (androidxJarFile != null && androidxJarFile.exists()) {
                int removed = removeDuplicateClasses(classesDir, androidxJarFile);
                if (removed > 0) {
                    log.append("[d8] 剔除与依赖 jar 重复的类 " + removed + " 个\n");
                }
            }
            File allJar = new File(work, "all.jar");
            jarClasses(classesDir, allJar);
            String d8out = runD8(allJar, androidxJarFile, dexDir);
            File dexFile = new File(dexDir, "classes.dex");
            if (d8out != null && d8out.length() > 0) log.append("[d8]\n").append(d8out.trim()).append('\n');
            if (!dexFile.exists() || dexFile.length() == 0) {
                r.error = "d8 转 dex 失败:\n" + (d8out == null ? "" : d8out);
                r.log = log.toString();
                return r;
            }
            log.append("[d8] classes.dex " + dexFile.length() + " bytes\n");

            File unsigned = new File(work, "unsigned.apk");
            ZipAligner.realignAndAppend(baseApk, dexFile, unsigned, "classes.dex");
            log.append("[zip] 对齐并合并 classes.dex\n");

            File unsignedWithNative = new File(work, "unsigned_native.apk");
            int nativeCount = packageNativeLibsAndAssets(unsigned, unsignedWithNative, projectDir, log);
            if (nativeCount > 0) {
                unsigned = unsignedWithNative;
                log.append("[zip] 已打包项目 native libs + assets\n");
            }
            File alignedFinal = new File(work, "aligned_final.apk");
            ZipAligner.realign(unsigned, alignedFinal);
            unsigned = alignedFinal;
            log.append("[zip] 最终重对齐完成\n");

            String apkName = (pkg != null && pkg.length() > 0
				? pkg.substring(pkg.lastIndexOf('.') + 1) : "app") + "-debug.apk";
            File signed = new File(apkDir, apkName);
            String signOut = runApksigner(unsigned, signed);
            if (signOut != null && signOut.length() > 0) log.append("[apksigner]\n").append(signOut.trim()).append('\n');
            if (!signed.exists() || signed.length() == 0) {
                r.error = "APK 签名失败:\n" + (signOut == null ? "" : signOut);
                r.log = log.toString();
                return r;
            }
            log.append("[apksigner] 签名完成 " + signed.length() + " bytes\n");

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

    private int packageNativeLibsAndAssets(File srcApk, File outApk, File projectDir, StringBuilder log) {
        int count = 0;
        try {
            java.util.Map<String, File> toAdd = new java.util.LinkedHashMap<>();
            File jniLibsDir = findDir(projectDir, "jniLibs");
            if (jniLibsDir != null) {
                File[] abiDirs = jniLibsDir.listFiles();
                if (abiDirs != null) {
                    for (File abiDir : abiDirs) {
                        if (!abiDir.isDirectory()) continue;
                        String abiName = abiDir.getName();
                        File[] soFiles = abiDir.listFiles();
                        if (soFiles == null) continue;
                        for (File so : soFiles) {
                            if (so.isFile() && so.getName().endsWith(".so")) {
                                toAdd.put("lib/" + abiName + "/" + so.getName(), so);
                                count++;
                            }
                        }
                    }
                }
            }
            File assetsDir = findDir(projectDir, "assets");
            if (assetsDir != null) {
                java.util.List<File> assetFiles = new java.util.ArrayList<>();
                collectFilesRecursive(assetsDir, assetFiles);
                String assetsBase = assetsDir.getAbsolutePath();
                for (File af : assetFiles) {
                    String relPath = af.getAbsolutePath().substring(assetsBase.length() + 1);
                    toAdd.put("assets/" + relPath, af);
                    count++;
                }
            }
            if (count == 0) return 0;
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(srcApk);
			java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
				new java.io.FileOutputStream(outApk))) {
                byte[] buf = new byte[8192];
                java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    java.util.zip.ZipEntry e = en.nextElement();
                    java.util.zip.ZipEntry ne = new java.util.zip.ZipEntry(e.getName());
                    ne.setMethod(e.getMethod());
                    ne.setTime(e.getTime());
                    if (e.getMethod() == java.util.zip.ZipEntry.STORED) {
                        ne.setSize(e.getSize());
                        ne.setCompressedSize(e.getSize());
                        ne.setCrc(e.getCrc());
                    }
                    zos.putNextEntry(ne);
                    try (java.io.InputStream is = zf.getInputStream(e)) {
                        int len;
                        while ((len = is.read(buf)) > 0) {
                            zos.write(buf, 0, len);
                        }
                    }
                    zos.closeEntry();
                }
                for (java.util.Map.Entry<String, File> e : toAdd.entrySet()) {
                    java.util.zip.ZipEntry ze = new java.util.zip.ZipEntry(e.getKey());
                    zos.putNextEntry(ze);
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(e.getValue())) {
                        int len;
                        while ((len = fis.read(buf)) > 0) {
                            zos.write(buf, 0, len);
                        }
                    }
                    zos.closeEntry();
                }
            }
            log.append("[native-assets] 打包 ").append(count).append(" 个文件到 APK\n");
            return count;
        } catch (Throwable t) {
            log.append("[native-assets] 打包失败: ").append(t.getMessage()).append("\n");
            return 0;
        }
    }

    private File findDir(File projectDir, String name) {
        File f = new File(projectDir, "src/main/" + name);
        if (f.exists() && f.isDirectory()) return f;
        f = new File(projectDir, name);
        if (f.exists() && f.isDirectory()) return f;
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

    private String runD8(File inputJar, File depJar, File outDir) throws Exception {
        File optDir = new File(toolDir, "opt");
        if (!optDir.exists() && !optDir.mkdirs()) optDir = null;

        if (d8DexFile.exists()) {
            d8DexFile.setReadOnly();
            d8DexFile.setReadable(true, false);
        }

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
            try {
                java.lang.reflect.Method dm = c.getClass().getMethod("getDiagnostics");
                Object diags = dm.invoke(c);
                if (diags instanceof Iterable) {
                    for (Object d : (Iterable<?>) diags) {
                        sb.append("\n  - ").append(String.valueOf(d));
                    }
                }
            } catch (Throwable ignored) {}
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

    private String runApksigner(File input, File output) throws Exception {
        File optDir = new File(toolDir, "opt");
        if (!optDir.exists() && !optDir.mkdirs()) optDir = null;

        if (apksignerDexFile.exists()) {
            apksignerDexFile.setReadOnly();
            apksignerDexFile.setReadable(true, false);
        }

        ClassLoader cl = new dalvik.system.DexClassLoader(
			apksignerDexFile.getAbsolutePath(),
			optDir == null ? toolDir.getAbsolutePath() : optDir.getAbsolutePath(),
			null, BuiltinCompiler.class.getClassLoader());
        try {
            java.security.KeyStore ks = null;
            try {
                ks = java.security.KeyStore.getInstance("JKS");
                FileInputStream ksin = new FileInputStream(keystoreFile);
                ks.load(ksin, KS_PASS.toCharArray());
                ksin.close();
            } catch (Throwable jksFail) {
                ks = java.security.KeyStore.getInstance("PKCS12");
                FileInputStream ksin = new FileInputStream(keystoreFile);
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

    private void jarClasses(File dir, File jar) throws IOException {
        java.util.zip.ZipOutputStream zos = null;
        try {
            zos = new java.util.zip.ZipOutputStream(new FileOutputStream(jar));
            addToJar(dir, dir, zos);
        } finally {
            if (zos != null) try { zos.close(); } catch (IOException ignored) {}
        }
    }

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

    private boolean manifestHasPackage(File manifest) {
        try {
            String text = readFile(manifest);
            return text != null && MANIFEST_PACKAGE.matcher(text).find();
        } catch (Throwable t) {
            Log.w(TAG, "read manifest failed", t);
            return true;
        }
    }

    private String injectManifestDefaults(File manifest, String pkg) {
        String text = readFile(manifest);
        if (text == null) return text;
        Matcher m = MANIFEST_TAG.matcher(text);
        if (!m.find()) return text;
        String head = m.group(1);
        if (pkg != null && pkg.length() > 0 && !MANIFEST_PACKAGE.matcher(text).find()) {
            head = head + " package=\"" + pkg + "\"";
            Log.i(TAG, "injected package=\"" + pkg + "\"");
        }
        if (!MANIFEST_VERSION_CODE.matcher(text).find()) {
            head = head + " android:versionCode=\"1\"";
            Log.i(TAG, "injected versionCode=1");
        }
        if (!MANIFEST_VERSION_NAME.matcher(text).find()) {
            head = head + " android:versionName=\"1.0\"";
            Log.i(TAG, "injected versionName=1.0");
        }
        StringBuilder sb = new StringBuilder(text);
        sb.replace(m.start(), m.end(), "<manifest" + head + ">");
        text = sb.toString();
        if (!text.contains("<uses-sdk")) {
            int idx = text.lastIndexOf("</manifest>");
            if (idx > 0) {
                text = text.substring(0, idx)
					+ "    <uses-sdk android:minSdkVersion=\"26\" android:targetSdkVersion=\""
					+ targetSdkForDevice() + "\" />\n"
					+ text.substring(idx);
                Log.i(TAG, "injected uses-sdk minSdk=26 targetSdk=" + targetSdkForDevice());
            }
        } else {
            Matcher tm = Pattern.compile(
				"android:targetSdkVersion\\s*=\\s*\"(\\d+)\"").matcher(text);
            if (tm.find()) {
                int existing = Integer.parseInt(tm.group(1));
                if (existing < 33) {
                    text = text.substring(0, tm.start())
						+ "android:targetSdkVersion=\"34\""
						+ text.substring(tm.end());
                    Log.i(TAG, "upgraded targetSdk from " + existing + " to 34 (avoid compatibility warning)");
                }
            }
        }
        Matcher am = Pattern.compile("<application\\b([^>]*?)>", Pattern.DOTALL).matcher(text);
        if (am.find() && !am.group(1).contains("android:name")) {
            String ah = am.group(1);
            text = text.substring(0, am.start())
				+ "<application" + ah + " android:name=\"com.aicodestudio.toolchain.CrashCapture\">"
				+ text.substring(am.end());
            Log.i(TAG, "injected application android:name=CrashCapture");
        }
        am = Pattern.compile("<application\\b([^>]*?)>", Pattern.DOTALL).matcher(text);
        if (am.find() && !am.group(1).contains("resizeableActivity")) {
            String ah = am.group(1);
            text = text.substring(0, am.start())
				+ "<application" + ah + " android:resizeableActivity=\"true\">"
				+ text.substring(am.end());
            Log.i(TAG, "injected android:resizeableActivity=true");
        }
        am = Pattern.compile("<application\\b([^>]*?)>", Pattern.DOTALL).matcher(text);
        if (am.find() && !am.group(1).contains("maxAspectRatio")) {
            String ah = am.group(1);
            text = text.substring(0, am.start())
				+ "<application" + ah + " android:maxAspectRatio=\"2.4\">"
				+ text.substring(am.end());
            Log.i(TAG, "injected android:maxAspectRatio=2.4");
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
        String projectLibs = collectProjectLibs(projectDir);

        try {
            File resDir = RClassGenerator.findResDir(projectDir);
            String pkg = RClassGenerator.findPackageName(projectDir);
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
        args.add("-bootclasspath");
        args.add(androidJarFile.getAbsolutePath());
        StringBuilder cp = new StringBuilder(androidJarFile.getAbsolutePath());
        cp.append(File.pathSeparator).append(androidxJarFile.getAbsolutePath());
        if (extraClasspath != null && extraClasspath.trim().length() > 0) {
            cp.append(File.pathSeparator).append(extraClasspath.trim());
        }
        args.add("-cp");
        args.add(cp.toString());
        File outDir = new File(context.getCacheDir(), "compile_classes");
        if (outDir.exists()) {
            deleteRecursive(outDir);
        }
        outDir.mkdirs();
        args.add("-d");
        args.add(outDir.getAbsolutePath());
        for (File f : sources) {
            args.add(f.getAbsolutePath());
        }
        return args.toArray(new String[0]);
    }

    private boolean runEcj(String[] args, PrintWriter pw) throws Exception {
        File optDir = new File(toolDir, "opt");
        if (!optDir.exists() && !optDir.mkdirs()) {
            optDir = null;
        }

        // 强制剥夺写权限
        if (ecjFile.exists()) {
            ecjFile.setReadOnly();
            ecjFile.setReadable(true, false);
        }
        if (ecjJarFile.exists()) {
            ecjJarFile.setReadOnly();
            ecjJarFile.setReadable(true, false);
        }

        final String dexPath = ecjFile.getAbsolutePath()
			+ File.pathSeparator + ecjJarFile.getAbsolutePath();
        final String optPath = optDir == null ? toolDir.getAbsolutePath() : optDir.getAbsolutePath();

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

    private void parseEcjOutput(String raw, List<CompileError> out) {
        if (raw == null || raw.length() == 0) return;
        String[] lines = raw.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("----------")) continue;
            Matcher m = BLOCK_HEADER.matcher(line);
            if (!m.matches()) continue;
            String file = m.group(3).trim();
            int lineno = parseInt(m.group(4));
            int severity = "ERROR".equals(m.group(2))
				? CompileError.SEVERITY_ERROR : CompileError.SEVERITY_WARNING;
            int column = -1;
            if (i + 2 < lines.length) {
                String caretLine = lines[i + 2];
                int caret = caretLine.indexOf('^');
                if (caret >= 0) {
                    column = caret;
                }
            }
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
                String n = f.getName();
                if ("assets".equals(n) || "build".equals(n) || "cache".equals(n)
					|| ".git".equals(n) || ".gradle".equals(n)) continue;
                collectJavaFiles(f, out);
            } else if (f.getName().endsWith(".java")) {
                out.add(f);
            }
        }
    }

    private boolean sourcesContainsName(List<File> sources, String fileName) {
        for (File f : sources) {
            if (f != null && fileName.equals(f.getName())) return true;
        }
        return false;
    }

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
