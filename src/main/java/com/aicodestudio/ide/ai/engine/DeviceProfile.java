package com.aicodestudio.ide.ai.engine;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * DeviceProfile - 运行时设备能力探测
 *
 * 设计原则：不按机型名判断，全部延迟到首次运行时实测。
 * 同芯片不同 ROM、同 ROM 不同内存配置都可能有差异。
 *
 * 探测维度：
 *   1. 内存 - 物理总量 / 当前可用 / LMK 阈值 / Java 堆上限
 *   2. CPU  - 核数 / 大小核 / ARM SIMD 特性位
 *   3. 存储 - 可用空间（模型文件要能放得下）
 *   4. 系统 - ABI / SDK 版本 / SoC 型号
 *
 * 关于 ARM SIMD 特性探测的已知局限：
 *   /proc/cpuinfo 在 Android 上通常只上报在线核心，且各簇特性可能不同。
 *   这里取"所有在线核心的并集"，并保留 unionFromAllCores 标记——
 *   若为 false（只读到单核），结论可靠性下降，native 层应在加载后二次校准。
 */
public class DeviceProfile {

    private static final String TAG = "DeviceProfile";
    private static DeviceProfile cached;

    // ===== 内存（MB）=====
    public long totalRamMB;        // 物理内存总量
    public long availRamMB;        // 当前可用（系统视角）
    public long lmkThresholdMB;    // 低内存杀进程阈值，低于此值极易被杀
    public int  javaHeapMaxMB;     // 本进程 Java 堆上限（largeHeap 另算）
    public int  javaHeapLargeMB;   // 声明 largeHeap 后的上限

    // 可用于 native 推理的预算（MB）——核心结论，由 admissionUsableMB 计算
    public long usableMB;

    // ===== CPU =====
    public int  cpuCores;          // 总核数
    public int  cpuOnline;         // 在线核数
    public int  cpuMaxFreqMHz;     // 最大频率
    public int  cpuMinFreqMHz;
    public String cpuModel;        // 例如 "Cortex-X4"
    public String socModel;        // 例如 "mt6991"

    // ARM SIMD 特性
    public boolean neon;           // 基础 128-bit SIMD（aarch64 必为 true）
    public boolean dotprod;        // SDOT/UDOT，int8 点积，Q4_0 系列依赖
    public boolean i8mm;           // int8 矩阵乘，Q4_K 系列提速关键
    public boolean fp16;           // FP16 算术
    public boolean bf16;           // BFloat16
    public boolean sve;            // 可伸缩向量扩展（服务器/少数旗舰）
    public boolean unionFromAllCores; // 特性是否来自多核并集

    // 未能识别的特性原始串，便于排查
    public String rawFeatures;

    // ===== 存储 =====
    public long storageFreeMB;     // 内部存储可用
    public long storageTotalMB;

    // ===== 系统 =====
    public String abi;             // arm64-v8a / armeabi-v7a / x86_64
    public String abis;            // 完整支持列表
    public int    sdkInt;
    public String device;
    public String manufacturer;

    // native 层校准后的实际内核（由 .so 汇报，未加载时为 null）
    public String nativeBackend;

    // ===== 单例 =====
    public static synchronized DeviceProfile get(Context ctx) {
        if (cached == null) {
            cached = new DeviceProfile(ctx.getApplicationContext());
        }
        return cached;
    }

    /** 强制刷新——内存可用量是动态的，加载模型前应调这个 */
    public static synchronized DeviceProfile refresh(Context ctx) {
        cached = new DeviceProfile(ctx.getApplicationContext());
        return cached;
    }

    private DeviceProfile(Context ctx) {
        probeMemory(ctx);
        probeCpu();
        probeStorage();
        probeSystem();
        computeUsableBudget();
    }

    // ==================== 内存 ====================
    private void probeMemory(Context ctx) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            try {
                am.getMemoryInfo(mi);
                totalRamMB = mi.totalMem / (1024L * 1024L);
                availRamMB = mi.availMem / (1024L * 1024L);
                lmkThresholdMB = mi.threshold / (1024L * 1024L);
            } catch (Exception e) {
                Log.w(TAG, "getMemoryInfo failed", e);
            }
            javaHeapMaxMB = am.getMemoryClass();
            try {
                javaHeapLargeMB = am.getLargeMemoryClass();
            } catch (Exception e) {
                javaHeapLargeMB = javaHeapMaxMB;
            }
        }
        // 兜底：部分设备上 getMemoryInfo 不可用，改读 /proc/meminfo
        if (totalRamMB <= 0) {
            long[] mem = readMemInfo();
            totalRamMB = mem[0];
            availRamMB = mem[1];
        }
        if (javaHeapMaxMB <= 0) javaHeapMaxMB = 128;
        if (javaHeapLargeMB <= 0) javaHeapLargeMB = javaHeapMaxMB;
    }

    /** 返回 [totalMB, availMB] */
    private long[] readMemInfo() {
        long[] out = new long[]{0, 0};
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/meminfo"));
            String line;
            long total = 0, avail = 0;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("MemTotal:")) {
                    total = parseKb(line);
                } else if (line.startsWith("MemAvailable:")) {
                    avail = parseKb(line);
                }
                if (total > 0 && avail > 0) break;
            }
            out[0] = total / 1024L;
            out[1] = avail / 1024L;
        } catch (Exception e) {
            Log.w(TAG, "readMemInfo failed", e);
        } finally {
            close(r);
        }
        return out;
    }

    private static long parseKb(String line) {
        // "MemTotal:        7864320 kB"
        String[] p = line.split("\\s+");
        if (p.length >= 2) {
            try {
                return Long.parseLong(p[1]);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * 计算可用于 native 推理的内存预算。
     *
     * 这是整个准入判断的基石。逻辑：
     *   可预算 = 当前可用 - LMK安全线 - Java堆 - 系统其他进程余量
     *
     * 注意 availMem 已经扣掉了系统和其他进程占用，但不能用到 0：
     *   - 逼近 LMK threshold 会被系统杀掉
     *   - 屏幕刷新、SurfaceFlinger、zygote 都需要常驻
     */
    private void computeUsableBudget() {
        long base;
        if (totalRamMB > 0) {
            // 以"总内存的一个安全比例"为上限，避免低内存时误判为可跑
            long byTotal = (long) (totalRamMB * 0.42f);
            // 以"当前可用减去安全线"为另一路估计
            long byAvail = availRamMB - Math.max(lmkThresholdMB, 512);
            base = Math.min(byTotal, byAvail);
        } else {
            base = availRamMB - 512;
        }
        // 再扣掉 Java 堆（模型不吃 Java 堆，但 App 本身要吃）
        base -= javaHeapMaxMB;
        // 低端机保底留 256MB，避免把系统拖垮
        if (base < 256) base = Math.max(0, availRamMB - lmkThresholdMB - javaHeapMaxMB);
        usableMB = Math.max(0, base);
    }

    // ==================== CPU ====================
    private void probeCpu() {
        cpuCores = Runtime.getRuntime().availableProcessors();
        cpuOnline = countOnlineCores();
        cpuMaxFreqMHz = readCpuFreq("cpu0", true);
        cpuMinFreqMHz = readCpuFreq("cpu0", false);
        probeCpuFeatures();
    }

    private int countOnlineCores() {
        int n = 0;
        // /sys/devices/system/cpu/online 形如 "0-7" 或 "0-3,5-7"
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/sys/devices/system/cpu/online"));
            String s = r.readLine();
            if (s != null && s.trim().length() > 0) {
                for (String part : s.trim().split(",")) {
                    part = part.trim();
                    int dash = part.indexOf('-');
                    if (dash > 0) {
                        int a = Integer.parseInt(part.substring(0, dash));
                        int b = Integer.parseInt(part.substring(dash + 1));
                        n += (b - a + 1);
                    } else if (part.length() > 0) {
                        n += 1;
                    }
                }
            }
        } catch (Exception e) {
            n = cpuCores;
        } finally {
            close(r);
        }
        return n > 0 ? n : cpuCores;
    }

    private int readCpuFreq(String cpu, boolean max) {
        String p = "/sys/devices/system/cpu/" + cpu + "/cpufreq/cpuinfo_" + (max ? "max" : "min") + "_freq";
        BufferedReader r = null;
        try {
            File f = new File(p);
            if (!f.exists()) return 0;
            r = new BufferedReader(new FileReader(f));
            String s = r.readLine();
            if (s != null) return Integer.parseInt(s.trim()) / 1000; // kHz -> MHz
        } catch (Exception e) {
            // 无权限或无此节点，忽略
        } finally {
            close(r);
        }
        return 0;
    }

    /**
     * 解析 /proc/cpuinfo 的 Features 行。
     *
     * 关键映射（内核上报名 -> 指令集）：
     *   asimd / neon  -> NEON
     *   asimddp       -> dotprod (SDOT/UDOT)
     *   i8mm          -> Int8 Matrix Multiply
     *   fphp/asimdhp  -> FP16
     *   bf16          -> BFloat16
     *   sve           -> Scalable Vector Extension
     *
     * 取所有在线核心的并集：任一颗核心支持，ggml 就可能分派到该内核。
     */
    private void probeCpuFeatures() {
        neon = false; dotprod = false; i8mm = false;
        fp16 = false; bf16 = false; sve = false;
        unionFromAllCores = false;
        rawFeatures = "";

        Set<String> union = new HashSet<String>();
        int featureLineCount = 0;
        StringBuilder modelSb = new StringBuilder();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/cpuinfo"));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("Features")) {
                    featureLineCount++;
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        String feats = line.substring(colon + 1).trim();
                        rawFeatures = feats;
                        String[] arr = feats.split("\\s+");
                        for (int i = 0; i < arr.length; i++) {
                            if (arr[i].length() > 0) union.add(arr[i].toLowerCase());
                        }
                    }
                } else if (line.startsWith("Hardware") || line.startsWith("model name")) {
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        String v = line.substring(colon + 1).trim();
                        if (v.length() > 0 && modelSb.length() == 0) modelSb.append(v);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "read cpuinfo failed", e);
        } finally {
            close(r);
        }

        unionFromAllCores = featureLineCount > 1;

        neon     = union.contains("asimd") || union.contains("neon");
        dotprod  = union.contains("asimddp");
        i8mm     = union.contains("i8mm");
        fp16     = union.contains("fphp") || union.contains("asimdhp");
        bf16     = union.contains("bf16");
        sve      = union.contains("sve");

        cpuModel = modelSb.toString();
        if (cpuModel.length() == 0) cpuModel = "unknown";

        // aarch64 上 NEON 是强制实现的，若没读到说明解析失败，按支持处理
        if (!neon && is64Bit()) neon = true;

        socModel = readSocModel();
    }

    private String readSocModel() {
        // 常见路径：/sys/devices/soc0/soc_id 或 ro.board.platform
        String v = readFirstLine("/sys/devices/soc0/soc_id");
        if (v == null || v.length() == 0) v = readFirstLine("/sys/devices/soc0/machine");
        if (v == null || v.length() == 0) v = getProp("ro.board.platform");
        return v == null ? "unknown" : v;
    }

    private static String readFirstLine(String path) {
        BufferedReader r = null;
        try {
            File f = new File(path);
            if (!f.exists()) return null;
            r = new BufferedReader(new FileReader(f));
            return r.readLine();
        } catch (Exception e) {
            return null;
        } finally {
            close(r);
        }
    }

    /** 通过反射调用隐藏的 SystemProperties，失败时静默返回 null */
    private static String getProp(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method m = c.getDeclaredMethod("get", String.class);
            Object v = m.invoke(null, key);
            return v == null ? null : v.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 存储 ====================
    private void probeStorage() {
        try {
            File dataDir = Environment.getDataDirectory();
            StatFs sf = new StatFs(dataDir.getPath());
            long blockSize, availBlocks, totalBlocks;
            if (android.os.Build.VERSION.SDK_INT >= 18) {
                blockSize   = sf.getBlockSizeLong();
                availBlocks = sf.getAvailableBlocksLong();
                totalBlocks = sf.getBlockCountLong();
            } else {
                blockSize   = sf.getBlockSize();
                availBlocks = sf.getAvailableBlocks();
                totalBlocks = sf.getBlockCount();
            }
            storageFreeMB  = (availBlocks * blockSize) / (1024L * 1024L);
            storageTotalMB = (totalBlocks * blockSize) / (1024L * 1024L);
        } catch (Exception e) {
            storageFreeMB = 0;
            storageTotalMB = 0;
        }
    }

    // ==================== 系统 ====================
    private void probeSystem() {
        sdkInt = Build.VERSION.SDK_INT;
        device = Build.DEVICE;
        manufacturer = Build.MANUFACTURER;
        if (Build.VERSION.SDK_INT >= 21 && Build.SUPPORTED_ABIS != null
                && Build.SUPPORTED_ABIS.length > 0) {
            abi = Build.SUPPORTED_ABIS[0];
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Build.SUPPORTED_ABIS.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(Build.SUPPORTED_ABIS[i]);
            }
            abis = sb.toString();
        } else {
            abi = Build.CPU_ABI;
            abis = Build.CPU_ABI + "," + Build.CPU_ABI2;
        }
    }

    public boolean is64Bit() {
        return abi != null && (abi.contains("64") || "arm64-v8a".equals(abi));
    }

    public boolean isArm64() {
        return "arm64-v8a".equals(abi);
    }

    // ==================== 建议线程数 ====================
    /**
     * 推理建议线程数。
     *
     * 经验法则：小模型（<3B）在手机上的 decode 是 memory-bound，
     * 线程数超过物理大核数反而因调度开销和缓存争用而变慢。
     * 常见最优区间是 4~6，而不是核数全开。
     */
    public int suggestThreads() {
        int c = cpuCores > 0 ? cpuCores : 4;
        if (c <= 2) return 2;
        if (c <= 4) return Math.max(2, c - 1);   // 留一核给系统/UI
        if (c <= 8) return 4;                     // 8 核机：4 线程通常最优
        return 6;                                 // 更多核收益递减，热节流也更快
    }

    /** 该设备能承载的模型档位描述，用于 UI 展示 */
    public String tierName() {
        if (usableMB >= 3000) return "旗舰";
        if (usableMB >= 1800) return "高端";
        if (usableMB >= 1100) return "中端";
        if (usableMB >= 700)  return "入门";
        return "受限";
    }

    // ==================== 诊断输出 ====================
    public String toReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("设备: ").append(manufacturer).append(" ").append(device).append("\n");
        sb.append("SoC: ").append(socModel).append("  CPU: ").append(cpuModel)
          .append(" (").append(cpuCores).append("核)\n");
        sb.append("ABI: ").append(abi).append("   SDK: ").append(sdkInt).append("\n");
        sb.append("内存: 总量 ").append(totalRamMB).append("MB / 可用 ").append(availRamMB)
          .append("MB / LMK阈值 ").append(lmkThresholdMB).append("MB\n");
        sb.append("Java堆: ").append(javaHeapMaxMB).append("MB (largeHeap ")
          .append(javaHeapLargeMB).append("MB)\n");
        sb.append("推理可用预算: ").append(usableMB).append("MB [").append(tierName()).append("]\n");
        sb.append("存储可用: ").append(storageFreeMB).append("MB\n");
        sb.append("SIMD: NEON=").append(neon)
          .append(" dotprod=").append(dotprod)
          .append(" i8mm=").append(i8mm)
          .append(" fp16=").append(fp16)
          .append(" bf16=").append(bf16)
          .append(" sve=").append(sve).append("\n");
        sb.append("特性来源: ").append(unionFromAllCores ? "多核并集" : "单核(可靠性较低)");
        if (nativeBackend != null) sb.append("\nnative后端: ").append(nativeBackend);
        return sb.toString();
    }

    /** SIMD 一行摘要，给 UI 显示 */
    public String simdSummary() {
        List<String> l = new ArrayList<String>();
        if (neon) l.add("NEON");
        if (dotprod) l.add("dotprod");
        if (i8mm) l.add("i8mm");
        if (fp16) l.add("FP16");
        if (bf16) l.add("BF16");
        if (sve) l.add("SVE");
        if (l.size() == 0) return "未检测到";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append(" · ");
            sb.append(l.get(i));
        }
        return sb.toString();
    }

    private static void close(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception e) {
                // ignore
            }
        }
    }
}
