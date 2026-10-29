package com.aicodestudio.ide.ai.engine;

import com.aicodestudio.ide.ai.engine.GgufProbe.GgufInfo;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * ModelAdmission - 模型准入引擎
 *
 * 解决的问题：
 *   用户导入一个 4.7GB 的 7B 模型，点加载 → 直接 OOM 崩溃。
 *   做通用工具，这个坑必须提前拦住，而不是崩了再说。
 *
 * 内存账本：
 *   总需求 = 模型权重 + KV cache + 运行时开销 + 安全余量
 *
 *   1. 模型权重 = 文件大小 × 1.06（GGUF 头部、tensor 表、页对齐）
 *      用 mmap 时 RSS 只计热页，但推理一轮下来基本会全部触碰，
 *      且 Android LMK 看的是 PSS 而非 RSS，按全量算更稳妥。
 *
 *   2. KV cache = 每 token 字节数 × 上下文长度
 *      这是最容易被忽略的一项——长上下文下可能比模型本身还大。
 *      例如 Qwen2.5-1.5B 在 8K 上下文、FP16 KV 下 KV 就要约 229MB。
 *
 *   3. 运行时开销 = 基础 + logits buffer + 每层计算缓冲
 *      logits buffer = 词表 × ubatch × 4 字节，移动端 ubatch 128 时约 78MB。
 *
 *   4. 安全余量：覆盖碎片、临时分配、GC 抖动。
 *
 * 输出三级结论 + 可执行建议（能跑多长上下文 / 要不要降 KV 精度）。
 */
public class ModelAdmission {

    /** 结论级别 */
    public static final int VERDICT_OK    = 0;  // 可加载
    public static final int VERDICT_WARN  = 1;  // 降级后可加载
    public static final int VERDICT_BLOCK = 2;  // 不建议，加载几乎必崩

    // 运行时参数（native 层校准后可覆盖）
    private static int    ubatch      = 128;   // 物理批大小，影响 logits buffer
    private static int    vocabSize   = 151936; // 默认按 Qwen2 词表
    private static long   runtimeBase = 90;    // 基础开销 MB
    private static float  safetyRatio = 0.12f; // 安全余量比例

    public static void calibrate(int ub, int vocab, long base) {
        if (ub > 0) ubatch = ub;
        if (vocab > 0) vocabSize = vocab;
        if (base > 0) runtimeBase = base;
    }

    public static class AdmissionResult {
        public int    verdict = VERDICT_BLOCK;
        public long   budgetMB;      // 设备可用预算
        public long   modelMB;       // 模型权重占用
        public long   kvMB;          // 按请求上下文算的 KV
        public long   runtimeMB;     // 运行时开销
        public long   needMB;        // 总需求（含安全余量）
        public int    requestCtx;    // 请求的上下文长度
        public int    maxCtx;        // 该模型在本设备能跑的最大上下文
        public int    suggestCtx;    // 留有余量的建议值
        public boolean suggestKvQ8;  // 是否建议开 KV 量化
        public boolean suggestKvQ4;
        public GgufInfo info;
        public String message;       // 一句话结论
        public String advice;        // 可执行建议
        public String bill;          // 明细账单（调试/展示用）

        public boolean canLoad() {
            return verdict == VERDICT_OK;
        }
    }

    /**
     * 判断某个模型文件在指定上下文长度下能否加载。
     *
     * @param ctx      Android Context
     * @param file     模型文件
     * @param reqCtx   期望的上下文长度
     * @param kvBytes  KV 每元素字节数（GgufProbe.KV_FP16 / KV_Q8 / KV_Q4）
     */
    public static AdmissionResult evaluate(android.content.Context ctx,
                                           File file, int reqCtx, float kvBytes) {
        AdmissionResult r = new AdmissionResult();
        DeviceProfile dev = DeviceProfile.refresh(ctx);
        r.budgetMB = dev.usableMB;
        r.requestCtx = reqCtx;

        if (file == null || !file.exists()) {
            r.verdict = VERDICT_BLOCK;
            r.message = "模型文件不存在";
            return r;
        }

        GgufInfo info = GgufProbe.probe(file);
        r.info = info;

        if (!info.valid) {
            // 解析不出架构，退化为按文件大小的粗估
            return fallbackBySize(dev, file, reqCtx, r);
        }

        // ---- 1. 模型权重 ----
        r.modelMB = (long) (file.length() / 1048576.0 * 1.06);

        // ---- 2. 运行时开销 ----
        long logitsMB = (long) (vocabSize * (long) ubatch * 4 / 1048576.0);
        long layerBufMB = (long) (info.blockCount * 0.5);
        r.runtimeMB = runtimeBase + logitsMB + layerBufMB;

        // ---- 3. KV cache ----
        long kvPerToken = info.kvBytesPerToken(kvBytes);
        r.kvMB = (long) (kvPerToken * reqCtx / 1048576.0);

        // ---- 4. 总账 ----
        long raw = r.modelMB + r.kvMB + r.runtimeMB;
        r.needMB = (long) (raw * (1f + safetyRatio));

        // ---- 5. 反推最大上下文 ----
        long leaveForKv = r.budgetMB - r.modelMB - r.runtimeMB;
        leaveForKv = (long) (leaveForKv / (1f + safetyRatio));
        if (kvPerToken > 0 && leaveForKv > 0) {
            r.maxCtx = (int) (leaveForKv * 1048576L / kvPerToken);
            // 对齐到 512 的整数倍
            r.maxCtx = (r.maxCtx / 512) * 512;
            r.suggestCtx = (int) (r.maxCtx * 0.85);
            r.suggestCtx = (r.suggestCtx / 512) * 512;
        } else {
            r.maxCtx = 0;
            r.suggestCtx = 0;
        }

        // ---- 6. 结论 ----
        if (r.needMB <= r.budgetMB) {
            r.verdict = VERDICT_OK;
            r.message = "可以加载（" + info.quantization + " · " + reqCtx + " 上下文）";
        } else if (r.maxCtx >= 512) {
            r.verdict = VERDICT_WARN;
            r.message = "当前上下文过长，缩短到 " + r.suggestCtx + " 可加载";
            r.advice = "将上下文调整为 " + r.suggestCtx + "，或开启 KV cache 量化以换取更长上下文。";
        } else {
            r.verdict = VERDICT_BLOCK;
            r.message = "内存不足，加载后极可能崩溃";
            r.advice = buildDowngradeAdvice(info, dev);
        }

        // ---- 7. KV 量化建议（仅在吃紧时给）----
        if (r.verdict != VERDICT_OK && kvPerToken > 0) {
            long kvQ8 = info.kvBytesPerToken(GgufProbe.KV_Q8) * reqCtx / 1048576L;
            long needQ8 = (long) ((r.modelMB + kvQ8 + r.runtimeMB) * (1f + safetyRatio));
            if (needQ8 <= r.budgetMB) {
                r.suggestKvQ8 = true;
                if (r.advice == null) r.advice = "";
                r.advice += "开启 Q8 KV cache 后即可加载（KV 占用 " + r.kvMB + "MB → " + kvQ8 + "MB）。";
            } else {
                long kvQ4 = info.kvBytesPerToken(GgufProbe.KV_Q4) * reqCtx / 1048576L;
                long needQ4 = (long) ((r.modelMB + kvQ4 + r.runtimeMB) * (1f + safetyRatio));
                if (needQ4 <= r.budgetMB) {
                    r.suggestKvQ4 = true;
                    if (r.advice == null) r.advice = "";
                    r.advice += "需开启 Q4 KV cache 才能加载（KV 占用 " + r.kvMB + "MB → " + kvQ4 + "MB）。";
                }
            }
        }

        // MoE 额外提示
        if (info.isMoe()) {
            String moeTip = "这是 MoE 模型（" + info.expertUsedCount + "/" + info.expertCount
                    + " 激活），注意：MoE 只省计算不省内存，全部 "
                    + info.expertCount + " 个专家权重都会装入内存。";
            r.advice = (r.advice == null ? "" : r.advice + "\n") + moeTip;
        }

        r.bill = buildBill(r, dev);
        return r;
    }

    /** 解析失败时按文件大小粗估 */
    private static AdmissionResult fallbackBySize(DeviceProfile dev, File file,
                                                  int reqCtx, AdmissionResult r) {
        r.modelMB = (long) (file.length() / 1048576.0 * 1.06);
        r.runtimeMB = runtimeBase + 80;
        // 无法算 KV，按经验给一个保守值：模型越大层数越多，KV 也越大
        r.kvMB = (long) (r.modelMB * 0.25 * (reqCtx / 4096.0));
        r.needMB = (long) ((r.modelMB + r.kvMB + r.runtimeMB) * (1f + safetyRatio));
        if (r.needMB <= r.budgetMB) {
            r.verdict = VERDICT_OK;
            r.message = "可以加载（未能解析架构，按文件体积估算）";
        } else {
            r.verdict = VERDICT_BLOCK;
            r.message = "内存不足（未能解析架构，按文件体积估算）";
            r.advice = "建议改用更小的量化档位（如 Q4_K_M）。";
        }
        r.maxCtx = 4096;
        r.suggestCtx = 2048;
        r.bill = buildBill(r, dev);
        return r;
    }

    private static String buildDowngradeAdvice(GgufInfo info, DeviceProfile dev) {
        if (info == null || !info.valid) {
            return "建议改用 Q4_K_M 或更小量化档位。";
        }
        StringBuilder sb = new StringBuilder();
        long budget = dev.usableMB;
        // 反推：给定预算，模型文件最大能有多大
        long maxModelMB = (long) (budget / (1f + safetyRatio)) - rRuntimeEstimate(info);
        if (maxModelMB < 0) maxModelMB = 0;
        sb.append("本设备推理预算约 ").append(budget).append("MB，");
        sb.append("该模型需要 ").append((long) (info.fileSize / 1048576.0 * 1.06)).append("MB。");
        if (maxModelMB > 0) {
            sb.append("建议改用体积在 ").append(maxModelMB).append("MB 以内的量化档位");
            sb.append("（例如 Q4_K_M 或 Q3_K）。");
        } else {
            sb.append("设备内存过低，建议仅使用云端模型。");
        }
        if (info.isMoe()) {
            sb.append(" MoE 架构无法缩减内存占用，换量化档位是唯一出路。");
        }
        return sb.toString();
    }

    private static long rRuntimeEstimate(GgufInfo info) {
        long logitsMB = (long) (vocabSize * (long) ubatch * 4 / 1048576.0);
        return runtimeBase + logitsMB + (long) (info.blockCount * 0.5);
    }

    private static String buildBill(AdmissionResult r, DeviceProfile dev) {
        StringBuilder sb = new StringBuilder();
        sb.append("设备可用预算: ").append(r.budgetMB).append(" MB (").append(dev.tierName()).append(")\n");
        sb.append("  模型权重:   ").append(r.modelMB).append(" MB\n");
        sb.append("  KV cache:   ").append(r.kvMB).append(" MB  @").append(r.requestCtx).append(" ctx\n");
        sb.append("  运行时:     ").append(r.runtimeMB).append(" MB\n");
        sb.append("  --------------------------------\n");
        sb.append("  小计:       ").append(r.modelMB + r.kvMB + r.runtimeMB).append(" MB\n");
        sb.append("  含安全余量: ").append(r.needMB).append(" MB\n");
        if (r.maxCtx > 0) {
            sb.append("  最大上下文: ").append(r.maxCtx).append(" (建议 ").append(r.suggestCtx).append(")");
        } else {
            sb.append("  最大上下文: 0 —— 该模型无法在本设备加载");
        }
        return sb.toString();
    }

    /**
     * 扫描目录中所有模型文件，给出各自的准入结论。
     * 用于导入界面直接显示"哪些能跑"。
     */
    public static List<AdmissionResult> scan(android.content.Context ctx,
                                             File dir, int reqCtx, float kvBytes) {
        List<AdmissionResult> out = new ArrayList<AdmissionResult>();
        if (dir == null || !dir.isDirectory()) return out;
        File[] fs = dir.listFiles();
        if (fs == null) return out;
        for (int i = 0; i < fs.length; i++) {
            File f = fs[i];
            if (!f.isFile()) continue;
            String n = f.getName().toLowerCase();
            if (!(n.endsWith(".gguf") || n.endsWith(".ggml") || n.endsWith(".bin"))) continue;
            out.add(evaluate(ctx, f, reqCtx, kvBytes));
        }
        return out;
    }

    /** 快速判断：只回答"这个文件现在能不能加载"，不做完整账单 */
    public static boolean quickCheck(android.content.Context ctx, File file,
                                     int reqCtx, float kvBytes) {
        try {
            return evaluate(ctx, file, reqCtx, kvBytes).canLoad();
        } catch (Exception e) {
            return false;
        }
    }
}
