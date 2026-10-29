package com.aicodestudio.ide.ai.engine;

import android.content.Context;

import java.io.File;

/**
 * EngineManager - 引擎选择与策略适配
 *
 * 这一层是"引擎优先"策略的落点：把设备探测结果翻译成引擎的最优运行参数。
 * 内核优化是上游社区的事，我们能自研且可控的是策略层——
 * 同一份 llama.cpp，参数给对了差距能到 30%~50%。
 */
public class EngineManager {

    private static volatile InferenceEngine engine;

    /** 获取当前引擎（单例）。引擎实现可替换，这里统一出口。 */
    public static InferenceEngine get() {
        if (engine == null) {
            synchronized (EngineManager.class) {
                if (engine == null) {
                    engine = new LlamaCppEngine();
                }
            }
        }
        return engine;
    }

    /**
     * App 启动时调用：尝试从手机上的任意候选目录加载引擎 .so。
     * 引擎不必打包进 APK，用户编译好放哪儿都行。
     * 建议在 Application.onCreate 或 WelcomeActivity 里调一次。
     */
    public static void bootstrap(Context ctx) {
        if (ctx == null) return;
        LlamaCppEngine.init(ctx.getApplicationContext());
    }

    /**
     * 根据设备能力推导最优加载参数。
     *
     * 关键决策：
     *  1. 线程数：小模型 decode 是 memory-bound，线程开满反而因调度
     *     开销和缓存争用变慢。8 核机上 4 线程通常最优。
     *  2. KV 精度：内存吃紧时先用 Q8（几乎无损），再考虑 Q4。
     *     KV 换出的空间直接变成上下文长度，比省那点精度值钱。
     *  3. ubatch：决定 logits buffer 大小，直接吃内存。
     *     内存紧张时降到 64，能省约 40MB 且对速度影响很小。
     *  4. fastKernels：只有检测到 dotprod/i8mm 才开，否则内核
     *     分派到不支持的指令会直接崩（这是 ARM 上最常见的踩坑）。
     */
    public static InferenceEngine.LoadOptions autoLoadOptions(Context ctx,
                                                              int wantedContext) {
        DeviceProfile dev = DeviceProfile.get(ctx);
        InferenceEngine.LoadOptions o = new InferenceEngine.LoadOptions();

        o.threads = dev.suggestThreads();

        // 内存分级决定 ubatch 和 KV 精度
        long budget = dev.usableMB;
        if (budget < 900) {
            o.ubatch = 64;
            o.kvBytes = GgufProbe.KV_Q8;
        } else if (budget < 1800) {
            o.ubatch = 128;
            o.kvBytes = GgufProbe.KV_Q8;
        } else {
            o.ubatch = 256;
            o.kvBytes = GgufProbe.KV_FP16;
        }

        o.contextSize = wantedContext > 0 ? wantedContext : 16384;
        o.mmap = true;

        // 内核分派必须保守：没有 dotprod/i8mm 就别让 ggml 走对应路径
        o.fastKernels = dev.dotprod || dev.i8mm;

        return o;
    }

    /**
     * 带准入检查的加载。
     * 先用 ModelAdmission 算账，超预算直接拒绝，不走到 native 层。
     *
     * @param ctx      Android Context
     * @param file     模型文件
     * @param wantedCtx 期望上下文
     * @param allowDowngrade 是否允许自动降档（缩短上下文 / 降 KV 精度）
     * @return 准入结果，含实际生效的参数
     */
    public static LoadOutcome loadWithAdmission(Context ctx, File file,
                                                int wantedCtx, boolean allowDowngrade) {
        LoadOutcome out = new LoadOutcome();
        InferenceEngine e = get();

        if (!e.available()) {
            out.ok = false;
            out.message = e.unavailableReason();
            return out;
        }

        if (file == null || !file.exists()) {
            out.ok = false;
            out.message = "模型文件不存在";
            return out;
        }

        DeviceProfile dev = DeviceProfile.get(ctx);
        InferenceEngine.LoadOptions opts = autoLoadOptions(ctx, wantedCtx);

        // 第一轮：按期望配置评估
        ModelAdmission.AdmissionResult r =
                ModelAdmission.evaluate(ctx, file, wantedCtx, opts.kvBytes);
        out.admission = r;

        if (r.canLoad()) {
            out.options = opts;
        } else if (allowDowngrade && r.suggestCtx >= 512) {
            // 自动降档：缩短上下文
            opts.contextSize = r.suggestCtx;
            out.downgraded = true;
            out.options = opts;
            out.message = "已自动将上下文调整为 " + r.suggestCtx + " 以适应可用内存";

            r = ModelAdmission.evaluate(ctx, file, opts.contextSize, opts.kvBytes);
            out.admission = r;
            if (!r.canLoad() && r.suggestKvQ8) {
                // 再降一档：KV 量化
                opts.kvBytes = GgufProbe.KV_Q8;
                r = ModelAdmission.evaluate(ctx, file, opts.contextSize, opts.kvBytes);
                out.admission = r;
                out.message = "已启用 Q8 KV cache 并将上下文调整为 " + opts.contextSize;
                if (r.canLoad()) out.options = opts;
            }
        }

        if (out.options == null) {
            out.ok = false;
            if (out.message == null) out.message = r.message;
            out.advice = r.advice;
            return out;
        }

        // 真正加载
        int rc = e.load(file.getAbsolutePath(), out.options);
        if (rc == InferenceEngine.ERROR_NONE) {
            out.ok = true;
            // native 层汇报实际内核，回写到 DeviceProfile 供后续诊断
            dev.nativeBackend = e.backendInfo();
            if (out.message == null) out.message = "已加载";
        } else {
            out.ok = false;
            out.message = "加载失败 (rc=" + rc + ")";
            if (rc == InferenceEngine.ERROR_OOM) {
                out.message += "：内存不足";
                out.advice = r.advice != null ? r.advice
                        : "建议改用更小的量化档位，或缩短上下文长度。";
            } else {
                out.message += "：" + e.lastError();
            }
        }
        return out;
    }

    /** 加载结果 */
    public static class LoadOutcome {
        public boolean ok;
        public String message;
        public String advice;
        public boolean downgraded;
        public InferenceEngine.LoadOptions options;
        public ModelAdmission.AdmissionResult admission;
    }
}
