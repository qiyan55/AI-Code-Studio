package com.aicodestudio.ide.ai.engine;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * NativeLibraryLoader —— 让引擎 .so 不必打包进 APK 也能加载。
 *
 * 背景：
 *   System.loadLibrary() 只能加载 APK 内 jniLibs 的库，
 *   一旦 .so 需要单独编译（llama.cpp 就是如此），
 *   每次换库都要重新打包 APK，非常麻烦。
 *
 * 方案：
 *   从多个候选目录搜索 .so → 拷贝到 App 私有内部目录 → System.load()。
 *   内部目录是唯一安全的选择：
 *     - Android 10+ 禁止从外部存储 mmap 可执行代码
 *     - 内部目录文件由 App 自己拥有，权限可控
 *
 * 依赖库处理：
 *   llama.cpp 新版拆成 libllama.so / libggml*.so 多个库，
 *   且相互之间有 NEEDED 依赖。所以不能只加载 libllama_jni.so，
 *   必须把同一目录下所有 .so 一起拷贝，并用 System.load() 逐个显式加载，
 *   链接器才能在同一目录里找到依赖。
 */
public final class NativeLibraryLoader {

    private static final String TAG = "NativeLibraryLoader";

    /**
     * llama.cpp 依赖库，按依赖顺序加载（被依赖者在前）。
     * 外部目录加载时必须显式逐个加载，链接器才能找到依赖。
     * APK 内置时由 System.loadLibrary 自动处理依赖。
     */
    private static final String[] COMPANION_LIBS = {
            "libc++_shared.so",     // C++ 运行时（NDK c++_shared）
            "libggml-base.so",      // ggml 基础
            "libggml-cpu.so",       // ggml CPU 后端
            "libggml.so",            // ggml 核心
            "libllama.so",          // llama.cpp 核心
            "libomp.so",            // OpenMP（兼容旧版，新版不依赖）
    };

    /** 主库：JNI 桥接层 */
    public static final String MAIN_LIB = "libllama_jni.so";

    /** 加载结果 */
    public static final class Result {
        public boolean ok;
        public String sourceDir;   // 从哪个目录找到的
        public String loadedFrom;  // 实际加载的绝对路径
        public String error;       // 失败原因
        public List<String> loadedLibs = new ArrayList<>();

        @Override
        public String toString() {
            if (ok) return "已加载 " + loadedLibs.size() + " 个库\n来源: " + loadedFrom;
            return "加载失败: " + error;
        }
    }

    private NativeLibraryLoader() {}

    /**
     * 尝试加载引擎。按以下顺序：
     *   1. APK 内置 jniLibs（最优先，若已打包则直接用）
     *   2. App 私有内部目录（之前导入过的）
     *   3. 常见的 Termux 输出目录
     *   4. 外部存储常见位置
     */
    public static Result load(Context ctx, String libBaseName) {
        Result r = new Result();

        // 1) APK 内置：必须显式按依赖顺序加载所有库
        //    虽然 System.loadLibrary 理论上会自动解析依赖，
        //    但 llama.cpp 拆成多个 .so 且依赖 libc++_shared.so，
        //    自动解析在部分设备上会失败，所以显式逐个加载更可靠。
        try {
            List<String> loaded = new ArrayList<>();
            for (String name : COMPANION_LIBS) {
                String base = name.replaceFirst("^lib", "").replaceFirst("\\.so$", "");
                try {
                    System.loadLibrary(base);
                    loaded.add(name);
                } catch (Throwable t) {
                    // 部分库可能不存在（如 libomp.so），忽略
                    Log.d(TAG, "APK 跳过 " + name + ": " + t.getMessage());
                }
            }
            // 最后加载主库
            System.loadLibrary(libBaseName);
            loaded.add("lib" + libBaseName + ".so");
            r.ok = true;
            r.sourceDir = "APK jniLibs";
            r.loadedFrom = "内置";
            r.loadedLibs = loaded;
            return r;
        } catch (Throwable t) {
            Log.e(TAG, "APK 内置加载失败: " + t.getMessage(), t);
            r.error = "APK 内置加载失败: " + t.getMessage();
        }

        Context app = (ctx != null) ? ctx.getApplicationContext() : null;
        if (app == null) {
            r.error = "Context 为空，无法搜索外部 .so";
            return r;
        }

        // 2~4) 搜索候选目录
        for (String dir : candidateDirs(app)) {
            File main = new File(dir, MAIN_LIB);
            if (!main.isFile() || main.length() <= 0) continue;

            // 拷贝到内部目录再加载
            File internal = new File(app.getFilesDir(), "engine");
            File dst = new File(internal, MAIN_LIB);

            // 同一目录且是内部目录时无需拷贝
            boolean needCopy = !dir.equals(internal.getAbsolutePath());
            if (!needCopy || copyTree(new File(dir), internal)) {
                try {
                    List<String> loaded = loadAll(internal, dst);
                    r.ok = true;
                    r.sourceDir = dir;
                    r.loadedFrom = dst.getAbsolutePath();
                    r.loadedLibs = loaded;
                    return r;
                } catch (Throwable t) {
                    r.error = "System.load 失败: " + t.getMessage();
                    Log.e(TAG, "load failed", t);
                    return r;
                }
            } else {
                r.error = "无法拷贝 .so 到内部目录";
            }
        }

        if (r.error == null) r.error = "未在任何候选目录找到 " + MAIN_LIB;
        return r;
    }

    /**
     * 候选目录，去重且保持顺序。
     *
     * ⚠ 重要：Android 应用沙箱隔离——App 无法读取另一个 App 的私有目录。
     *   /data/data/com.termux/files/home/ 对 Termux 是私有目录，
     *   本 App 访问会被拒绝。所以 Termux 编译完必须把产物拷到共享位置。
     *
     * 优先级说明：
     *   1. 内部 engine 目录：上次导入过，最可靠
     *   2. App 专属外部目录：/sdcard/Android/data/<pkg>/files
     *      —— 无需任何存储权限即可读写，是 Termux 产物的推荐落点
     *   3. 共享存储常见位置：需要存储权限，且受分区存储限制
     */
    private static Set<String> candidateDirs(Context app) {
        Set<String> dirs = new LinkedHashSet<>();

        // 1) App 私有内部目录（上次导入过的话）
        dirs.add(new File(app.getFilesDir(), "engine").getAbsolutePath());

        // 2) App 专属外部目录（免权限，Termux 拷贝产物的推荐位置）
        File ext = app.getExternalFilesDir(null);
        if (ext != null) {
            dirs.add(new File(ext, "engine").getAbsolutePath());
            dirs.add(new File(ext, "llama-out/arm64-v8a").getAbsolutePath());
            dirs.add(ext.getAbsolutePath());
        }

        // 3) 共享存储常见位置（需存储权限）
        for (String p : new String[]{
                "/sdcard/Download/llama-out/arm64-v8a",
                "/sdcard/llama-out/arm64-v8a",
                "/sdcard/llama-engine",
                "/sdcard/Download",
                "/storage/emulated/0/Download/llama-out/arm64-v8a",
                "/storage/emulated/0/llama-engine",
        }) {
            dirs.add(p);
        }

        // 4) Termux 私有目录：正常情况读不到，保留仅为兼容
        //    部分设备（root / 特殊 ROM）可能可访问，失败会自动跳过
        String home = "/data/data/com.termux/files/home";
        dirs.add(home + "/llama-out/arm64-v8a");
        dirs.add(home + "/llama-out");

        return dirs;
    }

    /**
     * 加载主库及其依赖库。
     * 必须先加载被依赖的库，否则链接器报 "not found"。
     * 这里按 COMPANION_LIBS 的顺序先加载依赖，最后加载主库。
     */
    private static List<String> loadAll(File dir, File main) {
        List<String> loaded = new ArrayList<>();
        for (String name : COMPANION_LIBS) {
            File f = new File(dir, name);
            if (!f.isFile()) continue;
            try {
                System.load(f.getAbsolutePath());
                loaded.add(name);
            } catch (Throwable t) {
                // 依赖库可能已被主库隐式加载，重复加载会报 already loaded，忽略
                Log.d(TAG, "skip " + name + ": " + t.getMessage());
            }
        }
        System.load(main.getAbsolutePath());
        loaded.add(MAIN_LIB);
        return loaded;
    }

    /** 拷贝目录下所有 .so 到目标目录 */
    private static boolean copyTree(File from, File to) {
        try {
            if (!to.exists() && !to.mkdirs()) return false;
            File[] files = from.listFiles();
            if (files == null) return false;
            for (File f : files) {
                if (!f.isFile()) continue;
                if (!f.getName().endsWith(".so")) continue;
                File dst = new File(to, f.getName());
                // 目标已存在且大小一致则跳过，避免每次启动都拷贝上百 MB
                if (dst.exists() && dst.length() == f.length()) continue;
                if (!copyFile(f, dst)) return false;
            }
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "copyTree failed", t);
            return false;
        }
    }

    private static boolean copyFile(File src, File dst) {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "copy failed: " + src, t);
            return false;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try { c.close(); } catch (Throwable ignored) {}
        }
    }

    /** 供 UI 展示：当前有哪些候选目录里放了 .so */
    public static List<String> scanAvailable(Context app) {
        List<String> found = new ArrayList<>();
        if (app == null) return found;
        for (String dir : candidateDirs(app)) {
            if (new File(dir, MAIN_LIB).isFile()) found.add(dir);
        }
        return found;
    }

    /** 手动导入：把用户选中的 .so 所在目录整体搬进内部目录 */
    public static boolean importFrom(Context app, String dirPath) {
        if (app == null || dirPath == null) return false;
        File from = new File(dirPath);
        if (!from.isDirectory()) return false;
        File to = new File(app.getFilesDir(), "engine");
        return copyTree(from, to);
    }

    /** 已导入引擎的内部目录 */
    public static File engineDir(Context app) {
        return new File(app.getFilesDir(), "engine");
    }

    /** 已导入的库清单，用于 UI 展示版本和体积 */
    public static String describe(Context app) {
        File dir = engineDir(app);
        File[] fs = dir.listFiles();
        if (fs == null || fs.length == 0) return "尚未导入引擎";
        List<String> names = new ArrayList<>();
        long total = 0;
        for (File f : fs) {
            if (!f.isFile()) continue;
            total += f.length();
            names.add(f.getName() + " (" + (f.length() / 1024 / 1024) + "MB)");
        }
        java.util.Collections.sort(names);
        StringBuilder sb = new StringBuilder();
        sb.append("已导入 ").append(names.size())
          .append(" 个库，共 ").append(total / 1024 / 1024).append("MB\n");
        for (String n : names) sb.append("  ").append(n).append("\n");
        return sb.toString().trim();
    }
}
