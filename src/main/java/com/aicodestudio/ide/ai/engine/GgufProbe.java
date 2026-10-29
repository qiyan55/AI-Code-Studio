package com.aicodestudio.ide.ai.engine;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/**
 * GgufProbe - 解析 GGUF 文件头部的元数据
 *
 * 为什么需要它：
 *   KV cache 的精确大小完全取决于模型架构（层数、KV 头数、维度），
 *   凭文件名猜量化档位估不准，更算不出 KV 开销。而从 GGUF metadata
 *   能拿到全部真实参数——这是准入判断能做到"准"的前提。
 *
 *  顺带还能识别 MoE（expert_count > 0），这类模型即使激活参数很小，
 *  全部专家权重也要装进内存，必须区别对待。
 *
 * 只读元数据段（通常在文件头几 KB 内），不加载权重，开销可忽略。
 *
 * GGUF 格式参考：
 *   magic "GGUF" | u32 version | u64 tensor_count | u64 metadata_kv_count
 *   随后是 metadata_kv_count 个 KV 对：
 *     string key  (u64 len + utf8 bytes)
 *     u32   value_type
 *     value (按类型变长)
 */
public class GgufProbe {

    private static final String TAG = "GgufProbe";

    // GGUF value types
    private static final int TYPE_UINT8   = 0;
    private static final int TYPE_INT8    = 1;
    private static final int TYPE_UINT16  = 2;
    private static final int TYPE_INT16   = 3;
    private static final int TYPE_UINT32  = 4;
    private static final int TYPE_INT32   = 5;
    private static final int TYPE_FLOAT32 = 6;
    private static final int TYPE_BOOL    = 7;
    private static final int TYPE_STRING  = 8;
    private static final int TYPE_ARRAY   = 9;
    private static final int TYPE_UINT64  = 10;
    private static final int TYPE_INT64   = 11;
    private static final int TYPE_FLOAT64 = 12;

    // GGUF file_type 编号 -> 人类可读（llama.cpp ftype enum）
    private static final String[] FTYPE_NAMES = {
        "F32",    // 0  ALL_F32
        "F16",    // 1  MOSTLY_F16
        "Q4_0",   // 2
        "Q4_1",   // 3
        "Q4_1_F16", // 4
        "Q5_0",   // 8  -> 见下方映射表
        "Q5_1",
        "Q8_0",
        "Q2_K",
        "Q3_K",
        "Q4_K",
        "Q5_K",
        "Q6_K",
        "IQ2_XXS",
        "IQ2_XS",
        "Q2_K_S",
        "IQ3_XS",
        "IQ3_XXS",
        "IQ1_S",
        "IQ4_NL",
        "IQ3_S",
        "IQ2_S",
        "IQ4_XS",
        "IQ1_M",
        "BF16",
        "Q4_0_4_4",
        "Q4_0_4_8",
        "Q4_0_8_8",
        "TQ1_0",
        "TQ2_0"
    };
    // ftype 值不连续，用显式映射更可靠
    private static final int[] FTYPE_KEYS = {
        0, 1, 2, 3, 4, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
        20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 36, 37, 38, 39, 40
    };
    private static final String[] FTYPE_VALS = {
        "F32", "F16", "Q4_0", "Q4_1", "Q4_1_F16", "Q8_0", "Q5_0", "Q5_1",
        "Q2_K", "Q3_K", "Q4_K", "Q5_K", "Q6_K", "IQ2_XXS", "IQ2_XS", "Q2_K_S",
        "IQ3_XXS", "IQ1_S", "IQ4_NL", "IQ3_S", "IQ2_S", "IQ4_XS", "IQ1_M",
        "BF16", "Q4_0_4_4", "Q4_0_4_8", "Q4_0_8_8", "TQ1_0", "TQ2_0",
        "IQ2_M", "IQ2_XXS_", "TQ1_0_", "TQ2_0_", "IQ3_XXS_", "Q4_0_R8", "Q8_0_R8"
    };

    /** 每 token 的 KV 元素字节数（FP16 = 2），Q8 KV 量化后为 1 */
    public static final float KV_FP16 = 2f;
    public static final float KV_Q8   = 1f;
    public static final float KV_Q4   = 0.5f;

    public static class GgufInfo {
        public boolean valid;
        public String  error;

        public int     version;
        public long    tensorCount;
        public String  architecture;   // qwen2 / llama / mixtral / qwen3moe ...
        public String  modelName;
        public long    fileSize;

        // 架构参数
        public int  blockCount;        // 层数
        public int  embeddingLength;   // hidden dim
        public int  headCount;         // Q 头数
        public int  headCountKv;       // KV 头数（GQA，可能小于 headCount）
        public int  contextLength;     // 训练时上下文
        public int  feedForwardLength;
        public int  headDim;           // 由 embeddingLength / headCount 推导

        // MoE
        public int  expertCount;       // 0 表示 Dense
        public int  expertUsedCount;

        public int  fileType;          // 量化编号
        public String quantization = "UNKNOWN";

        public boolean isMoe() {
            return expertCount > 0;
        }

        /** 参数量估算（亿参数），用于 UI 展示 */
        public float paramBillions() {
            if (blockCount <= 0 || embeddingLength <= 0) return 0f;
            // Attention: 4 * emb^2 (QKV+O)，GQA 下略小，这里按 Q 头数算近似
            double attn = 4.0 * embeddingLength * embeddingLength;
            // FFN: 3 * emb * ffn（Dense）或 expertCount 倍（MoE）
            double ffnDim = feedForwardLength > 0 ? feedForwardLength : embeddingLength * 4;
            double ffn = 3.0 * embeddingLength * ffnDim;
            if (expertCount > 0) ffn *= expertCount;
            double perLayer = attn + ffn;
            double total = perLayer * blockCount + 2.0 * embeddingLength * 65536; // + embedding 表（按 64K 词表估）
            return (float) (total / 1e9);
        }

        /**
         * 每 token 的 KV cache 字节数
         * 公式: 2(K+V) × 层数 × KV头数 × head_dim × 每元素字节
         */
        public long kvBytesPerToken(float bytesPerElement) {
            if (blockCount <= 0 || headCountKv <= 0) return 0;
            int hd = headDim > 0 ? headDim
                    : (headCount > 0 ? embeddingLength / headCount : 0);
            if (hd <= 0) return 0;
            return (long) (2L * blockCount * headCountKv * hd * bytesPerElement);
        }

        public String archSummary() {
            if (!valid) return "解析失败";
            StringBuilder sb = new StringBuilder();
            sb.append(architecture).append(" · ").append(blockCount).append("层 · ")
              .append(embeddingLength).append("维");
            if (headCountKv > 0 && headCount > 0) {
                sb.append(" · Q").append(headCount).append("/KV").append(headCountKv);
            }
            if (isMoe()) {
                sb.append(" · MoE ").append(expertUsedCount).append("/").append(expertCount);
            }
            return sb.toString();
        }
    }

    /**
     * 解析 GGUF 文件头。
     * 只读前 MAX_META_BYTES 字节，大文件也不会有 IO 压力。
     */
    public static GgufInfo probe(File file) {
        GgufInfo info = new GgufInfo();
        info.fileSize = file.length();

        if (!file.exists()) {
            info.valid = false;
            info.error = "文件不存在";
            return info;
        }
        if (info.fileSize < 64) {
            info.valid = false;
            info.error = "文件过小，不是有效模型";
            return info;
        }

        InputStream in = null;
        try {
            in = new FileInputStream(file);
            byte[] head = new byte[4];
            if (readFully(in, head, 4) < 4) {
                info.error = "读取失败";
                return info;
            }
            // magic "GGUF"
            if (!(head[0] == 'G' && head[1] == 'G' && head[2] == 'U' && head[3] == 'F')) {
                // 老 GGML 格式（magic 0x67676d6c）无法解析架构
                info.valid = false;
                info.error = "非 GGUF 格式（可能是旧版 GGML）";
                return info;
            }

            // version u32
            byte[] b4 = new byte[4];
            readFully(in, b4, 4);
            info.version = (int) u32(b4);
            if (info.version != 3) {
                // 兼容尝试：v1/v2 的 string 结构不同，直接降级
                info.valid = false;
                info.error = "不支持的 GGUF 版本: " + info.version;
                return info;
            }

            // tensor_count u64
            byte[] b8 = new byte[8];
            readFully(in, b8, 8);
            info.tensorCount = u64(b8);
            // metadata_kv_count u64
            readFully(in, b8, 8);
            long kvCount = u64(b8);

            if (kvCount < 0 || kvCount > 100000) {
                info.valid = false;
                info.error = "元数据数量异常: " + kvCount;
                return info;
            }

            Map<String, String> meta = new HashMap<String, String>();
            for (long i = 0; i < kvCount; i++) {
                String key = readString(in);
                if (key == null) break;
                byte[] tb = new byte[4];
                if (readFully(in, tb, 4) < 4) break;
                int type = (int) u32(tb);
                String val = readValue(in, type);
                if (val != null) meta.put(key, val);
            }

            info.architecture    = meta.get("general.architecture");
            info.modelName       = meta.get("general.name");
            if (info.architecture == null) info.architecture = "unknown";
            if (info.modelName == null) info.modelName = file.getName();

            String arch = info.architecture;
            info.blockCount        = intMeta(meta, arch + ".block_count", 0);
            info.embeddingLength   = intMeta(meta, arch + ".embedding_length", 0);
            info.headCount         = intMeta(meta, arch + ".attention.head_count", 0);
            info.headCountKv       = intMeta(meta, arch + ".attention.head_count_kv", 0);
            info.contextLength     = intMeta(meta, arch + ".context_length", 0);
            info.feedForwardLength = intMeta(meta, arch + ".feed_forward_length", 0);
            info.expertCount       = intMeta(meta, arch + ".expert_count", 0);
            info.expertUsedCount   = intMeta(meta, arch + ".expert_used_count", 0);

            // head_dim 可能显式给出（Qwen2/3 常见）
            info.headDim = intMeta(meta, arch + ".attention.head_dim", 0);
            if (info.headDim <= 0 && info.headCount > 0 && info.embeddingLength > 0) {
                info.headDim = info.embeddingLength / info.headCount;
            }

            // 量化类型
            int ft = intMeta(meta, "general.file_type", -1);
            info.fileType = ft;
            info.quantization = ftypeName(ft);

            // 若 headCountKv 缺失（部分模型不写），退化为 headCount
            if (info.headCountKv <= 0) info.headCountKv = info.headCount;

            info.valid = info.blockCount > 0 && info.embeddingLength > 0;
            if (!info.valid) {
                info.error = "未能解析出架构参数";
            }
            return info;

        } catch (Exception e) {
            Log.w(TAG, "GGUF parse error", e);
            info.valid = false;
            info.error = "解析异常: " + e.getMessage();
            return info;
        } finally {
            close(in);
        }
    }

    // ==================== 读取原语 ====================

    private static int readFully(InputStream in, byte[] buf, int len) {
        int off = 0;
        while (off < len) {
            int n;
            try {
                n = in.read(buf, off, len - off);
            } catch (IOException e) {
                return off;
            }
            if (n < 0) break;
            off += n;
        }
        return off;
    }

    private static long u32(byte[] b) {
        // GGUF 是小端
        return ((long) (b[0] & 0xFF))
             | ((long) (b[1] & 0xFF) << 8)
             | ((long) (b[2] & 0xFF) << 16)
             | ((long) (b[3] & 0xFF) << 24);
    }

    private static long u64(byte[] b) {
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            v = (v << 8) | (b[i] & 0xFF);
        }
        return v;
    }

    private static String readString(InputStream in) throws IOException {
        byte[] b8 = new byte[8];
        if (readFully(in, b8, 8) < 8) return null;
        long len = u64(b8);
        if (len < 0 || len > 1L << 20) return null; // 1MB 上限，防异常
        byte[] s = new byte[(int) len];
        if (len > 0 && readFully(in, s, (int) len) < len) return null;
        return new String(s, "UTF-8");
    }

    /** 读一个值，统一以字符串返回（数组返回首个元素的字符串形式） */
    private static String readValue(InputStream in, int type) throws IOException {
        switch (type) {
            case TYPE_UINT8: {
                byte[] b = new byte[1];
                readFully(in, b, 1);
                return String.valueOf(b[0] & 0xFF);
            }
            case TYPE_INT8: {
                byte[] b = new byte[1];
                readFully(in, b, 1);
                return String.valueOf((int) b[0]);
            }
            case TYPE_UINT16: {
                byte[] b = new byte[2];
                readFully(in, b, 2);
                return String.valueOf(u32(new byte[]{b[0], b[1], 0, 0}));
            }
            case TYPE_INT16: {
                byte[] b = new byte[2];
                readFully(in, b, 2);
                int v = (short) ((b[0] & 0xFF) | ((b[1] & 0xFF) << 8));
                return String.valueOf(v);
            }
            case TYPE_UINT32:
            case TYPE_INT32: {
                byte[] b = new byte[4];
                readFully(in, b, 4);
                return String.valueOf((int) u32(b));
            }
            case TYPE_FLOAT32: {
                byte[] b = new byte[4];
                readFully(in, b, 4);
                ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
                return String.valueOf(bb.getFloat());
            }
            case TYPE_BOOL: {
                byte[] b = new byte[1];
                readFully(in, b, 1);
                return String.valueOf(b[0] != 0);
            }
            case TYPE_STRING:
                return readString(in);
            case TYPE_ARRAY: {
                byte[] tb = new byte[4];
                if (readFully(in, tb, 4) < 4) return null;
                int elemType = (int) u32(tb);
                byte[] cb = new byte[8];
                if (readFully(in, cb, 8) < 8) return null;
                long count = u64(cb);
                String first = null;
                for (long i = 0; i < count; i++) {
                    String v = readValue(in, elemType);
                    if (v == null) break;
                    if (first == null) first = v;
                }
                return first;
            }
            case TYPE_UINT64:
            case TYPE_INT64: {
                byte[] b = new byte[8];
                readFully(in, b, 8);
                return String.valueOf(u64(b));
            }
            case TYPE_FLOAT64: {
                byte[] b = new byte[8];
                readFully(in, b, 8);
                ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
                return String.valueOf(bb.getDouble());
            }
            default:
                return null; // 未知类型，放弃后续解析
        }
    }

    private static int intMeta(Map<String, String> meta, String key, int def) {
        String v = meta.get(key);
        if (v == null) return def;
        try {
            // 可能是 "28" 或 "28.0"
            return (int) Double.parseDouble(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    public static String ftypeName(int ft) {
        for (int i = 0; i < FTYPE_KEYS.length; i++) {
            if (FTYPE_KEYS[i] == ft && i < FTYPE_VALS.length) return FTYPE_VALS[i];
        }
        return "UNKNOWN";
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
