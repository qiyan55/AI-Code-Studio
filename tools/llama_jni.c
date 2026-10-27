/*
 * llama_jni.c - llama.cpp <-> Java 的 JNI 桥接层
 *
 * 兼容 llama.cpp 0.4.0+（新 KV cache 管理 API）
 *
 * 设计取舍：
 *   1. 只依赖 libllama 的核心 C API，不用 common/ 模块。
 *   2. 采样用 temperature + top-k + top-p，够用且可控。
 *   3. 中断用 volatile 标志位，生成循环里轮询。
 *   4. KV cache 由 llama_decode 自动管理，prefix 预热通过预 decode 实现。
 *
 * 编译：见 build-termux.sh 或 NDK 交叉编译
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <stdio.h>
#include <stdarg.h>
#include <stdbool.h>
#include <pthread.h>
#include <time.h>
#include <android/log.h>

#include "llama.h"

#define TAG "llama_jni"

/* ==================== 文件日志 ==================== */

static char * g_log_file = NULL;
static FILE * g_log_fp = NULL;             /* 保持打开的日志文件句柄，避免每次 fopen/fclose 高频 I/O 耗电发热 */
static pthread_mutex_t g_log_lock = PTHREAD_MUTEX_INITIALIZER;

/* 打开日志文件（惰性，句柄保持到进程退出） */
static void log_open(void) {
    if (g_log_fp != NULL || g_log_file == NULL) return;
    /* v1.124: 日志超过5MB则截断重写，防止无限增长 */
    FILE * f = fopen(g_log_file, "a+");
    if (f) {
        fseek(f, 0, SEEK_END);
        long sz = ftell(f);
        fclose(f);
        if (sz > 5 * 1024 * 1024) {
            g_log_fp = fopen(g_log_file, "w"); /* 截断 */
        } else {
            g_log_fp = fopen(g_log_file, "a");
        }
    }
}

static void log_to_file(int level, const char * fmt, ...) {
    va_list args;
    char buf[2048];
    int n;

    va_start(args, fmt);
    n = vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    if (n < 0) return;

    /* 同时输出到 logcat */
    int prio = (level == 0) ? ANDROID_LOG_ERROR :
               (level == 1) ? ANDROID_LOG_WARN  : ANDROID_LOG_INFO;
    __android_log_print(prio, TAG, "%s", buf);

    /* 写入文件（句柄复用，减少 I/O） */
    if (g_log_file == NULL) return;

    pthread_mutex_lock(&g_log_lock);
    log_open();
    if (g_log_fp != NULL) {
        time_t now = time(NULL);
        struct tm tm;
        localtime_r(&now, &tm);
        const char * lvl = (level == 0) ? "E" : (level == 1) ? "W" : "I";
        fprintf(g_log_fp, "%02d-%02d %02d:%02d:%02d %s %s\n",
                tm.tm_mon + 1, tm.tm_mday,
                tm.tm_hour, tm.tm_min, tm.tm_sec,
                lvl, buf);
        /* 仅错误级别立即刷盘，普通日志由系统缓冲，减少存储唤醒 */
        if (level == 0) fflush(g_log_fp);
    }
    pthread_mutex_unlock(&g_log_lock);
}

#define LOGI(...) log_to_file(2, __VA_ARGS__)
#define LOGE(...) log_to_file(0, __VA_ARGS__)
#define LOGW(...) log_to_file(1, __VA_ARGS__)

/* ==================== 会话结构 ==================== */

typedef struct {
    struct llama_model  * model;
    struct llama_context* ctx;
    int            n_ctx;
    volatile int   abort;      /* 中断标志 */
    pthread_mutex_t lock;      /* 保护 ctx 的并发访问 */
    char         * path;       /* 模型路径，用于 prefetch */
    int            n_prefix_keep; /* v1.107: 前缀 PIN 锁定长度（L1+L2+L3 token 数，不被淘汰） */
    volatile int   was_trimmed;   /* v4.5: 最近一次 generate 是否发生了 KV trim */
} session_t;

/* v1.107: 前缀 PIN + 滑动窗口淘汰。
 * 当 KV cache 接近满时，保留前 n_prefix_keep 个 token（L1+L2+L3），
 * 并保留最近 keepRecent 个 token，淘汰中间对话部分。
 * 返回淘汰后新的 n_past；如果不需要淘汰，返回 -1。
 */
static int trimContext(session_t * s, int keepRecent) {
    if (s->n_prefix_keep <= 0) return -1; /* 未设置前缀保护，不淘汰 */
    llama_memory_t mem = llama_get_memory(s->ctx);
    if (mem == NULL) return -1;
    llama_pos pos_max = llama_memory_seq_pos_max(mem, 0);
    if (pos_max < 0) return -1;

    /* 空间还够，不淘汰 */
    if (pos_max + keepRecent < s->n_ctx) return -1;

    /* 要删除的范围：[n_prefix_keep, pos_max - keepRecent) */
    llama_pos del_end = pos_max - keepRecent;
    if (del_end <= s->n_prefix_keep) return -1;

    LOGI("trimContext: evicting [%d, %lld), keeping prefix %d + recent %d",
         s->n_prefix_keep, (long long)del_end, s->n_prefix_keep, keepRecent);

    s->was_trimmed = 1;
    llama_memory_seq_rm(mem, 0, s->n_prefix_keep, del_end);
    /* 平移保留的最近 token：从 [del_end, pos_max] 移到 [n_prefix_keep, ...] */
    llama_pos shift = del_end - s->n_prefix_keep;
    llama_memory_seq_add(mem, 0, del_end, pos_max + 1, -shift);

    /* 新的 n_past = 前缀长度 + 保留的最近 token 数 */
    int new_past = s->n_prefix_keep + (int)(pos_max - del_end + 1);
    LOGI("trimContext: done, new n_past=%d", new_past);
    return new_past;
}

/* ==================== 后端信息（编译期宏，最可靠） ==================== */

static const char * backend_info(void) {
    static char buf[512];
    int n = 0;

    n += snprintf(buf + n, sizeof(buf) - n, "CPU[");
#if defined(__aarch64__)
    n += snprintf(buf + n, sizeof(buf) - n, "aarch64");
#elif defined(__ARM_ARCH_7A__)
    n += snprintf(buf + n, sizeof(buf) - n, "armv7");
#elif defined(__x86_64__)
    n += snprintf(buf + n, sizeof(buf) - n, "x86_64");
#else
    n += snprintf(buf + n, sizeof(buf) - n, "unknown");
#endif
    n += snprintf(buf + n, sizeof(buf) - n, "]");

#if defined(GGML_USE_VULKAN)
    n += snprintf(buf + n, sizeof(buf) - n, " Vulkan=on");
#endif
#if defined(GGML_USE_BLAS)
    n += snprintf(buf + n, sizeof(buf) - n, " BLAS=on");
#endif
#if defined(GGML_USE_KLEIDIAI)
    n += snprintf(buf + n, sizeof(buf) - n, " KleidiAI=on");
#endif

    n += snprintf(buf + n, sizeof(buf) - n, " NEON=");
#if defined(__ARM_NEON)
    n += snprintf(buf + n, sizeof(buf) - n, "on");
#else
    n += snprintf(buf + n, sizeof(buf) - n, "off");
#endif

    n += snprintf(buf + n, sizeof(buf) - n, " dotprod=");
#if defined(__ARM_FEATURE_DOTPROD)
    n += snprintf(buf + n, sizeof(buf) - n, "on");
#else
    n += snprintf(buf + n, sizeof(buf) - n, "off");
#endif

    n += snprintf(buf + n, sizeof(buf) - n, " i8mm=");
#if defined(__ARM_FEATURE_MATMUL_INT8)
    n += snprintf(buf + n, sizeof(buf) - n, "on");
#else
    n += snprintf(buf + n, sizeof(buf) - n, "off");
#endif

    n += snprintf(buf + n, sizeof(buf) - n, " fp16=");
#if defined(__ARM_FEATURE_FP16_VECTOR_ARITHMETIC)
    n += snprintf(buf + n, sizeof(buf) - n, "on");
#else
    n += snprintf(buf + n, sizeof(buf) - n, "off");
#endif

    return buf;
}

/* ==================== 采样 ==================== */

typedef struct {
    int32_t id;
    float   logit;
} token_candidate;

/* v1.51v：采样 buffer 预分配（P0-3 修复：不再每 token malloc 1.2MB）
   单线程生成，静态 buffer 安全。vocab ~150k，约 2.4MB。 */
#define MAX_VOCAB 200000
static float          s_logits_buf[MAX_VOCAB];
static token_candidate s_cand[MAX_VOCAB];
static char            s_seen[MAX_VOCAB]; /* repeat_penalty 去重位图（P1-1） */

static int cmp_candidate(const void * a, const void * b) {
    const token_candidate * ta = (const token_candidate *) a;
    const token_candidate * tb = (const token_candidate *) b;
    if (ta->logit > tb->logit) return -1;
    if (ta->logit < tb->logit) return  1;
    return 0;
}

static void softmax(float * arr, int n) {
    float max = arr[0];
    for (int i = 1; i < n; i++) if (arr[i] > max) max = arr[i];
    float sum = 0.0f;
    for (int i = 0; i < n; i++) {
        arr[i] = expf(arr[i] - max);
        sum += arr[i];
    }
    for (int i = 0; i < n; i++) arr[i] /= sum;
}

static llama_token sample_token(struct llama_context * ctx, int n_vocab,
                                float temperature, int top_k, float top_p,
                                float repeat_penalty,
                                const llama_token * prev_tokens, int n_prev) {
    /* P0-2 修复：拷贝 llama 内部 logits 到本地 buffer，不污染内部状态 */
    const float * src_logits = llama_get_logits(ctx);
    if (src_logits == NULL) return -1;
    if (n_vocab > MAX_VOCAB) n_vocab = MAX_VOCAB;
    memcpy(s_logits_buf, src_logits, sizeof(float) * (size_t) n_vocab);
    float * logits = s_logits_buf;

    /* P1-1 修复：repeat_penalty 去重——同一 token 只惩罚一次（不是出现几次惩罚几次） */
    if (repeat_penalty > 1.0f && prev_tokens != NULL && n_prev > 0) {
        memset(s_seen, 0, (size_t) n_vocab);
        for (int i = 0; i < n_prev; i++) {
            llama_token t = prev_tokens[i];
            if (t >= 0 && t < n_vocab && !s_seen[t]) {
                s_seen[t] = 1;
                if (logits[t] < 0.0f) logits[t] *= repeat_penalty;
                else                   logits[t] /= repeat_penalty;
            }
        }
    }

    if (temperature <= 0.0f) {
        int best = 0;
        float best_logit = logits[0];
        for (int i = 1; i < n_vocab; i++) {
            if (logits[i] > best_logit) { best_logit = logits[i]; best = i; }
        }
        return best;
    }

    /* P0-4 修复：正确采样顺序——先 top-k（原始 logits）→ 再 temperature 缩放 → softmax → top-p */
    /* 第1步：收集所有 token 到 cand */
    int ncand = 0;
    for (int i = 0; i < n_vocab; i++) {
        s_cand[ncand].id = i;
        s_cand[ncand].logit = logits[i];
        ncand++;
    }

    /* 第2步：按原始 logit 降序排序 */
    qsort(s_cand, (size_t) ncand, sizeof(token_candidate), cmp_candidate);

    /* 第3步：top-k 截断（softmax 前） */
    int k = (top_k <= 0 || top_k > ncand) ? ncand : top_k;
    ncand = k;

    /* 第4步：对保留的候选做 temperature 缩放 + softmax */
    float max_logit = s_cand[0].logit;
    for (int i = 0; i < ncand; i++) {
        s_cand[i].logit /= temperature;
        if (s_cand[i].logit > max_logit) max_logit = s_cand[i].logit;
    }
    float sum = 0.0f;
    for (int i = 0; i < ncand; i++) {
        s_cand[i].logit = expf(s_cand[i].logit - max_logit);
        sum += s_cand[i].logit;
    }
    if (sum <= 0.0f) return s_cand[0].id;
    for (int i = 0; i < ncand; i++) s_cand[i].logit /= sum;

    /* 第5步：top-p 截断 */
    if (top_p > 0.0f && top_p < 1.0f) {
        float cum = 0.0f;
        int keep = 0;
        for (int i = 0; i < ncand; i++) {
            cum += s_cand[i].logit;
            keep = i + 1;
            if (cum >= top_p) break;
        }
        ncand = keep;
    }

    /* 第6步：归一化后采样 */
    sum = 0.0f;
    for (int i = 0; i < ncand; i++) sum += s_cand[i].logit;
    if (sum <= 0.0f) return s_cand[0].id;
    /* v1.124: 线程安全随机数（rand_r替代rand） */
    static __thread unsigned int rng_seed = 0;
    if (rng_seed == 0) rng_seed = (unsigned int)time(NULL) ^ (unsigned int)pthread_self();
    float r = (float)rand_r(&rng_seed) / (float)RAND_MAX * sum;
    float c = 0.0f;
    llama_token chosen = s_cand[ncand - 1].id;
    for (int i = 0; i < ncand; i++) {
        c += s_cand[i].logit;
        if (c >= r) { chosen = s_cand[i].id; break; }
    }
    return chosen;
}

/* ==================== JNI 实现 ==================== */

JNIEXPORT void JNICALL
Java_com_aicodestudio_ide_ai_engine_LlamaCppEngine_nSetLogFile(JNIEnv * env, jclass cls, jstring jpath) {
    (void) cls;
    if (jpath == NULL) return;
    const char * path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (path == NULL) return;

    pthread_mutex_lock(&g_log_lock);
    /* 关闭旧句柄，避免句柄泄漏 */
    if (g_log_fp != NULL) {
        fclose(g_log_fp);
        g_log_fp = NULL;
    }
    if (g_log_file != NULL) {
        free(g_log_file);
        g_log_file = NULL;
    }
    g_log_file = strdup(path);
    pthread_mutex_unlock(&g_log_lock);

    /* 写入日志头（复用句柄） */
    pthread_mutex_lock(&g_log_lock);
    log_open();
    if (g_log_fp != NULL) {
        fprintf(g_log_fp, "\n===== llama_jni 日志开始 =====\n");
        fflush(g_log_fp);
    }
    pthread_mutex_unlock(&g_log_lock);

    __android_log_print(ANDROID_LOG_INFO, TAG, "log file set to: %s", path);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
}

JNIEXPORT jstring JNICALL
Java_com_aicodestudio_ide_ai_engine_LlamaCppEngine_nVersion(JNIEnv * env, jclass cls) {
    (void) cls;
    char buf[128];
    const char * ver = llama_version();
    snprintf(buf, sizeof(buf), "llama.cpp %s", ver ? ver : "unknown");
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jstring JNICALL
Java_com_aicodestudio_ide_ai_engine_LlamaCppEngine_nBackendInfo(JNIEnv * env, jclass cls) {
    (void) cls;
    return (*env)->NewStringUTF(env, backend_info());
}

JNIEXPORT jlong JNICALL
Java_com_aicodestudio_ide_ai_engine_LlamaCppEngine_nLoad(
        JNIEnv * env, jclass cls, jstring jpath,
        jint ctx_size, jint threads, jfloat kv_bytes,
        jint ubatch, jboolean mmap, jboolean fast_kernels) {
    (void) cls;
    (void) fast_kernels;

    if (jpath == NULL) {
        LOGE("nLoad: path is NULL");
        return 0;
    }

    const char * path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (path == NULL) {
        LOGE("nLoad: GetStringUTFChars failed");
        return 0;
    }

    LOGI("nLoad: path=%s, ctx=%d, threads=%d, kv_bytes=%.2f, ubatch=%d, mmap=%d",
         path, ctx_size, threads, kv_bytes, ubatch, mmap);

    // 检查文件是否存在且可读
    FILE * ftest = fopen(path, "rb");
    if (ftest == NULL) {
        LOGE("nLoad: cannot open file '%s' (permission denied or not exists)", path);
        (*env)->ReleaseStringUTFChars(env, jpath, path);
        return 0;
    }
    fclose(ftest);
    LOGI("nLoad: file exists and readable");

    session_t * s = (session_t *) calloc(1, sizeof(session_t));
    if (s == NULL) {
        LOGE("nLoad: calloc failed (OOM)");
        (*env)->ReleaseStringUTFChars(env, jpath, path);
        return 0;
    }
    s->path = strdup(path);
    pthread_mutex_init(&s->lock, NULL);
    s->abort = 0;

    struct llama_model_params mparams = llama_model_default_params();
    mparams.load_mode = mmap ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;
    LOGI("nLoad: load_mode=%d", mparams.load_mode);

    LOGI("nLoad: calling llama_model_load_from_file...");
    s->model = llama_model_load_from_file(path, mparams);
    (*env)->ReleaseStringUTFChars(env, jpath, path);

    if (s->model == NULL) {
        LOGE("nLoad: llama_model_load_from_file returned NULL (model load failed, likely OOM or corrupted file)");
        pthread_mutex_destroy(&s->lock);
        free(s->path);
        free(s);
        return 0;
    }
    LOGI("nLoad: model loaded successfully");

    struct llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx    = (uint32_t) ctx_size;
    cparams.n_batch  = (uint32_t) (ubatch > 512 ? 512 : ubatch);
    cparams.n_ubatch = (uint32_t) ubatch;
    cparams.n_threads = (int32_t) threads;
    cparams.n_threads_batch = (int32_t) threads;

    if (kv_bytes >= 2.0f) {
        cparams.type_k = GGML_TYPE_F16;
        cparams.type_v = GGML_TYPE_F16;
        LOGI("nLoad: KV cache type = FP16");
    } else if (kv_bytes >= 1.0f) {
        cparams.type_k = GGML_TYPE_Q8_0;
        cparams.type_v = GGML_TYPE_Q8_0;
        LOGI("nLoad: KV cache type = Q8_0");
    } else {
        cparams.type_k = GGML_TYPE_Q4_0;
        cparams.type_v = GGML_TYPE_Q4_0;
        LOGI("nLoad: KV cache type = Q4_0");
    }

    LOGI("nLoad: calling llama_init_from_model (n_ctx=%d)...", cparams.n_ctx);
    s->ctx = llama_init_from_model(s->model, cparams);
    if (s->ctx == NULL) {
        LOGE("nLoad: llama_init_from_model returned NULL (context init failed, likely OOM)");
        llama_model_free(s->model);
        pthread_mutex_destroy(&s->lock);
        free(s->path);
        free(s);
        return 0;
    }
    s->n_ctx = llama_n_ctx(s->ctx);  // P1-2: 用实际值，不用 Java 传入值
    LOGI("nLoad: context initialized successfully, session=%p", (void*)s);

    return (jlong) (intptr_t) s;
}

JNIEXPORT void JNICALL
Java_com_aicodestudio_ide_ai_engine_LlamaCppEngine_nUnload(JNIEnv * env, jclass cls, jlong handle) {
    (void) env; (void) cls;
    if (handle == 0) return;
    session_t * s = (session_t *) (intptr_t) handle;
    s->abort = 1;
    if (s->ctx)   llama_free(s->ctx);
    if (s->model) llama_model_free(s->model);
    pthread_mutex_destroy(&s->lock);
    if (s->path) free(s->path);
    free(s);
}

JNIEXPORT void JNICALL
Java_com_aicodestudio_ide_ai_engine_LlamaCppEngine_nStop(JNIEnv * env, jclass cls, jlong handle) {
    (void) env; (void) cls;
    if (handle == 0) return;
    session_t * s = (session_t *) (intptr_t) handle;
    s->abort = 1;
}

/*
 * 生成。核心循环：
 *   1. tokenize prompt
 *   2. 分批 prefill（llama_decode 自动管理 KV cache）
 *   3. 逐 token decode + sample，每步回调 Java
 */
JNIEXPORT jint JNICALL
Java_com_aicodestudio_ide_ai_engine_LlamaCppEngine_nGenerate(
        JNIEnv * env, jclass cls, jlong handle, jstring jprompt,
        jint max_tokens, jfloat temperature, jint top_k, jfloat top_p,
        jfloat repeat_penalty, jobjectArray jstop, jobject jout,
        jint n_past) {
    (void) cls; (void) jstop;

    if (handle == 0) return -3;
    session_t * s = (session_t *) (intptr_t) handle;
    if (s->ctx == NULL || s->model == NULL) return -3;

    if (jprompt == NULL) return -2;

    pthread_mutex_lock(&s->lock);
    s->abort = 0;

    const struct llama_vocab * vocab = llama_model_get_vocab(s->model);
    int n_vocab = llama_vocab_n_tokens(vocab);

    const char * prompt_c = (*env)->GetStringUTFChars(env, jprompt, NULL);
    if (prompt_c == NULL) {
        pthread_mutex_unlock(&s->lock);
        return -2;
    }
    int prompt_len = (int) strlen(prompt_c);

    int max_tokens_buf = prompt_len + 64;
    llama_token * tokens = (llama_token *) malloc(sizeof(llama_token) * (size_t) max_tokens_buf);
    if (tokens == NULL) {
        (*env)->ReleaseStringUTFChars(env, jprompt, prompt_c);
        pthread_mutex_unlock(&s->lock);
        return -1;
    }

    /* n_past>0 时是增量 prefill（只 prefill 新增段），不重复加 BOS */
    int add_bos = (n_past == 0) ? 1 : 0;
    int n_tokens = llama_tokenize(vocab, prompt_c, prompt_len, tokens,
                                  max_tokens_buf, add_bos, true);
    (*env)->ReleaseStringUTFChars(env, jprompt, prompt_c);

    if (n_tokens < 0) {
        int need = -n_tokens;
        free(tokens);
        tokens = (llama_token *) malloc(sizeof(llama_token) * (size_t) need);
        if (tokens == NULL) {
            pthread_mutex_unlock(&s->lock);
            return -1;
        }
        const char * p2 = (*env)->GetStringUTFChars(env, jprompt, NULL);
        n_tokens = llama_tokenize(vocab, p2, prompt_len, tokens, need, add_bos, true);
        (*env)->ReleaseStringUTFChars(env, jprompt, p2);
        if (n_tokens <= 0) {
            free(tokens);
            pthread_mutex_unlock(&s->lock);
            return -2;
        }
    }

    LOGI("nGenerate: prompt_len=%d, n_tokens=%d, max_tokens=%d, temp=%.2f, top_k=%d, top_p=%.2f, n_past=%d",
         prompt_len, n_tokens, max_tokens, temperature, top_k, top_p, n_past);

    /* 增量 KV cache：不清空已有内容，从 n_past 位置继续 prefill */
    struct llama_batch batch = llama_batch_init((int32_t) s->n_ctx, 0, 1);

    /* ---- prefill：从 n_past 位置开始，增量写入 KV cache ---- */
    int32_t n_batch = llama_n_batch(s->ctx);
    int processed = 0;

    /* v1.107: 如果 prefill 会溢出，先淘汰中间对话（保留前缀+最近） */
    if (n_past + n_tokens > s->n_ctx) {
        int trimmed = trimContext(s, 2048);
        if (trimmed > 0) {
            n_past = trimmed;
            LOGI("nGenerate: after trim, n_past=%d, retry prefill", n_past);
        }
        if (n_past + n_tokens > s->n_ctx) {
            LOGE("nGenerate: context overflow even after trim: n_past=%d + n_tokens=%d > ctx %d", n_past, n_tokens, s->n_ctx);
            llama_batch_free(batch);
            free(tokens);
            pthread_mutex_unlock(&s->lock);
            return -5;
        }
    }
    LOGI("nGenerate: incremental prefill start, n_tokens=%d, n_past=%d, n_batch=%d, n_ctx=%d",
         n_tokens, n_past, n_batch, s->n_ctx);

    while (processed < n_tokens) {
        if (s->abort) {
            llama_batch_free(batch);
            free(tokens);
            pthread_mutex_unlock(&s->lock);
            return -4;
        }
        int32_t chunk = n_tokens - processed;
        if (chunk > n_batch) chunk = n_batch;

        for (int32_t i = 0; i < chunk; i++) {
            batch.token[i]  = tokens[processed + i];
            batch.pos[i]    = (llama_pos) (n_past + i);
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i] = 0;
        }
        batch.logits[chunk - 1] = 1;
        batch.n_tokens = chunk;

        int decode_rc = llama_decode(s->ctx, batch);
        if (decode_rc != 0) {
            LOGE("nGenerate: prefill llama_decode failed at token %d/%d, rc=%d (1=KV slot conflict, -1=invalid batch, <-1=fatal)",
                 processed + chunk, n_tokens, decode_rc);
            llama_batch_free(batch);
            free(tokens);
            pthread_mutex_unlock(&s->lock);
            return -99;
        }
        processed += chunk;
        n_past += chunk;
    }
    LOGI("nGenerate: prefill done, n_past=%d", n_past);

    /* ---- 生成循环 ---- */
    int generated = 0;
    int rc = 0;

    /* 已生成 token 列表，用于重复惩罚 */
    llama_token * gen_tokens = (llama_token *) malloc(sizeof(llama_token) * (size_t) max_tokens);
    if (gen_tokens == NULL) {
        llama_batch_free(batch);
        free(tokens);
        pthread_mutex_unlock(&s->lock);
        return -6;
    }

    /* 输出文本缓冲区，用于停止序列检测 */
    char * out_buf = (char *) malloc(sizeof(char) * (size_t) (max_tokens * 8 + 64));
    int out_len = 0;
    if (out_buf == NULL) {
        free(gen_tokens);
        llama_batch_free(batch);
        free(tokens);
        pthread_mutex_unlock(&s->lock);
        return -6;
    }
    out_buf[0] = '\0';

    /* v1.131：改用官方 llama_sampler_chain 替换手写 sample_token。
     * 分工：采样（top-k/top-p/temperature/重复惩罚/EOS）交给官方；
     *       KV 分层（n_prefix_keep PIN + trimContext 滑动淘汰）保留自研。
     * 官方 chain 内部维护采样历史与 repeat penalty，不再需要手写 gen_tokens 喂惩罚。 */
    static __thread unsigned int smpl_rng = 0;
    if (smpl_rng == 0) smpl_rng = (unsigned int)time(NULL) ^ (unsigned int)pthread_self();
    struct llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (smpl == NULL) {
        free(out_buf);
        free(gen_tokens);
        llama_batch_free(batch);
        free(tokens);
        pthread_mutex_unlock(&s->lock);
        return -99;
    }
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        if (top_k > 0) llama_sampler_chain_add(smpl, llama_sampler_init_top_k(top_k));
        if (top_p > 0.0f && top_p < 1.0f) llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        /* repeat penalty：last 64 token 窗口，freq/present 置 0 保持原行为 */
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(n_vocab, 64, repeat_penalty, 0.0f, 0.0f));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(smpl_rng));
    }
    LOGI("nGenerate: sampler chain ready (temp=%.2f top_k=%d top_p=%.2f rep=%.2f)",
         temperature, top_k, top_p, repeat_penalty);

    /* v1.51u：去掉写死的 <|im_end|> 字符串匹配，只依赖 llama_vocab_is_eog 官方 EOS 检测 */
    const char * stop_seq = NULL;
    int stop_seq_len = 0;

    jmethodID mid_append = NULL;
    jclass cls_sb = NULL;
    if (jout != NULL) {
        cls_sb = (*env)->GetObjectClass(env, jout);
        mid_append = (*env)->GetMethodID(env, cls_sb, "append",
                                         "(Ljava/lang/String;)Ljava/lang/StringBuilder;");
    }

    jclass engine_cls = (*env)->FindClass(env, "com/aicodestudio/ide/ai/engine/LlamaCppEngine");
    jmethodID mid_token = NULL;
    if (engine_cls != NULL) {
        mid_token = (*env)->GetStaticMethodID(env, engine_cls, "onToken",
                                              "(Ljava/lang/String;)V");
    }

    /* UTF-8 字符缓冲区：llama_token_to_piece 可能把多字节字符（如中文）拆分成多个 token，
       每个 token 只包含部分字节，直接传给 NewStringUTF 会产生乱码。
       把 token 字节追加到缓冲区，提取完整的 UTF-8 字符后再回调 Java 层。 */
    char utf8_buf[8192];
    int utf8_len = 0;

    while (generated < max_tokens) {
        if (s->abort) {
            rc = -4;
            break;
        }
        /* v1.107: KV 满了先淘汰中间对话（保留前缀+最近），而不是直接停 */
        if (n_past >= s->n_ctx) {
            int trimmed = trimContext(s, 2048);
            if (trimmed > 0) {
                n_past = trimmed;
                LOGI("nGenerate: gen loop trim, new n_past=%d", n_past);
            } else {
                break; /* 没设置前缀保护，真的满了 */
            }
        }

        /* 采样：官方 sampler chain 内部处理 top-k/top-p/temperature/repeat penalty */
        llama_token next = llama_sampler_sample(smpl, s->ctx, -1);
        if (next < 0) {
            rc = -99;
            break;
        }

        /* EOS 检测 */
        if (llama_vocab_is_eog(vocab, next)) {
            LOGI("nGenerate: EOS token detected, stopping");
            break;
        }

        /* 喂给 sampler 作为后续采样历史（官方 repeat penalty 依据） */
        llama_sampler_accept(smpl, next);
        /* 记录已生成 token（保留数组供后续扩展，采样已由官方 chain 处理） */
        gen_tokens[generated] = next;

        char piece[128];
        int n = llama_token_to_piece(vocab, next, piece, sizeof(piece), 0, false);  // P1-4: special=false，不吐特殊 token 字符串
        const char * piece_ptr = NULL;
        char * big = NULL;
        if (n < 0) {
            int need = -n;
            big = (char *) malloc((size_t) need + 1);
            if (big != NULL) {
                int n2 = llama_token_to_piece(vocab, next, big, need, 0, false);  // P1-4
                if (n2 > 0) {
                    big[n2] = '\0';
                    piece_ptr = big;
                }
            }
        } else if (n > 0) {
            piece[n] = '\0';
            piece_ptr = piece;
        }

        if (piece_ptr != NULL) {
            /* 停止序列检测：把片段追加到输出缓冲区，检测是否包含 <|im_end|> */
            int piece_len = (int) strlen(piece_ptr);
            if (out_len + piece_len < max_tokens * 8) {
                memcpy(out_buf + out_len, piece_ptr, (size_t) piece_len);
                out_len += piece_len;
                out_buf[out_len] = '\0';
            }
            /* v1.51u：写死的 <|im_end|> 字符串检测已移除，只靠 llama_vocab_is_eog */
            (void)stop_seq; (void)stop_seq_len;

            /* 回调 Java 层：使用 UTF-8 缓冲区，避免不完整的多字节字符导致乱码 */
            if (mid_token != NULL || mid_append != NULL) {
                /* 把 piece 追加到 UTF-8 缓冲区 */
                if (utf8_len + piece_len < (int)sizeof(utf8_buf) - 1) {
                    memcpy(utf8_buf + utf8_len, piece_ptr, (size_t)piece_len);
                    utf8_len += piece_len;
                    utf8_buf[utf8_len] = '\0';
                }

                /* 提取完整的 UTF-8 字符 */
                int processed = 0;
                while (processed < utf8_len) {
                    unsigned char c = (unsigned char)utf8_buf[processed];
                    int char_len;
                    if (c < 0x80) char_len = 1;
                    else if ((c & 0xE0) == 0xC0) char_len = 2;
                    else if ((c & 0xF0) == 0xE0) char_len = 3;
                    else if ((c & 0xF8) == 0xF0) char_len = 4;
                    else char_len = 1;  /* 无效字符，当成单字节处理 */

                    if (processed + char_len > utf8_len) {
                        /* 不完整的字符，等下一个 token */
                        break;
                    }
                    processed += char_len;
                }

                if (processed > 0) {
                    /* 有完整的字符，回调 Java 层 */
                    char complete_str[processed + 1];
                    memcpy(complete_str, utf8_buf, (size_t)processed);
                    complete_str[processed] = '\0';

                    if (mid_token != NULL) {
                        jstring js = (*env)->NewStringUTF(env, complete_str);
                        (*env)->CallStaticVoidMethod(env, engine_cls, mid_token, js);
                        (*env)->DeleteLocalRef(env, js);
                    }
                    if (mid_append != NULL) {
                        jstring js = (*env)->NewStringUTF(env, complete_str);
                        (*env)->CallObjectMethod(env, jout, mid_append, js);
                        (*env)->DeleteLocalRef(env, js);
                    }

                    /* 把剩余的不完整字节移到缓冲区开头 */
                    int remaining = utf8_len - processed;
                    if (remaining > 0) {
                        memmove(utf8_buf, utf8_buf + processed, (size_t)remaining);
                    }
                    utf8_len = remaining;
                }
            }
        }
        if (big != NULL) free(big);

        batch.token[0]    = next;
        batch.pos[0]      = (llama_pos) n_past;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0]   = 1;
        batch.n_tokens    = 1;

        int gen_rc = llama_decode(s->ctx, batch);
        if (gen_rc != 0) {
            LOGE("nGenerate: decode llama_decode failed at generated=%d, n_past=%d, rc=%d",
                 generated, n_past, gen_rc);
            rc = -99;
            break;
        }
        n_past++;
        generated++;
    }

    /* 生成结束后，把 UTF-8 缓冲区中剩余的字节也输出（可能是不完整的多字节字符，但也要输出） */
    if (utf8_len > 0 && (mid_token != NULL || mid_append != NULL)) {
        utf8_buf[utf8_len] = '\0';
        if (mid_token != NULL) {
            jstring js = (*env)->NewStringUTF(env, utf8_buf);
            (*env)->CallStaticVoidMethod(env, engine_cls, mid_token, js);
            (*env)->DeleteLocalRef(env, js);
        }
        if (mid_append != NULL) {
            jstring js = (*env)->NewStringUTF(env, utf8_buf);
            (*env)->CallObjectMethod(env, jout, mid_append, js);
            (*env)->DeleteLocalRef(env, js);
        }
        utf8_len = 0;
    }

    LOGI("nGenerate: generation done, generated=%d tokens", generated);

    llama_sampler_free(smpl);

    /* 释放临时缓冲区 */
    free(gen_tokens);
    free(out_buf);

    llama_batch_free(batch);
    free(tokens);
    pthread_mutex_unlock(&s->lock);
    /* 成功时返回最终的 n_past（包括 prefill 和生成），用于 Java 层增量 KV cache 跟踪 */
    return rc == 0 ? n_past : rc;
}

/*
 * v2 重写：直接接收 int[] tokens，不再在 native 层做 tokenize。
 * Java 层负责：完整 prompt tokenize → 对比前缀 → 把新增 token 数组传进来。
 * 这样彻底避免字符串 substring 切分位置错位导致的上下文错乱。
 *
 * 参数：
 *   tokens   —— 要 prefill 的 token 数组（已经是好的，不要再 tokenize）
 *   n_past   —— 从 ctx 的哪个位置开始 prefill（0 = 全量）
 *   max_tokens —— 最多生成多少 token
 *   out      —— StringBuilder 接收生成文本
 *   out_gen_tokens —— int[] 输出，返回生成的 token IDs（可为 NULL）
 */
JNIEXPORT jint JNICALL
Java_com_aicodestudio_ide_ai_engine_LlamaCppEngine_nGenerateTokens(
        JNIEnv * env, jclass cls, jlong handle,
        jintArray jtokens, jint n_past,
        jint max_tokens, jfloat temperature, jint top_k, jfloat top_p,
        jfloat repeat_penalty, jobject jout,
        jintArray jout_gen_tokens) {
    (void) cls;

    if (handle == 0) return -3;
    session_t * s = (session_t *) (intptr_t) handle;
    if (s->ctx == NULL || s->model == NULL) return -3;
    if (jtokens == NULL) return -2;

    jsize n_tokens = (*env)->GetArrayLength(env, jtokens);
    if (n_tokens <= 0) return -2;
    jboolean isCopy = JNI_FALSE;
    jint * tokens = (*env)->GetIntArrayElements(env, jtokens, &isCopy);

    pthread_mutex_lock(&s->lock);
    s->abort = 0;

    const struct llama_vocab * vocab = llama_model_get_vocab(s->model);
    int n_vocab = llama_vocab_n_tokens(vocab);

    LOGI("nGenerateTokens: n_tokens=%d n_past=%d max_tokens=%d", n_tokens, n_past, max_tokens);

    struct llama_batch batch = llama_batch_init((int32_t) s->n_ctx, 0, 1);
    int32_t n_batch = llama_n_batch(s->ctx);

    /* ---- prefill：从 n_past 位置开始 ---- */
    if (n_past + n_tokens > s->n_ctx) {
        int trimmed = trimContext(s, 2048);
        if (trimmed > 0) n_past = trimmed;
        if (n_past + n_tokens > s->n_ctx) {
            LOGE("nGenerateTokens: overflow n_past=%d + %d > ctx %d", n_past, n_tokens, s->n_ctx);
            llama_batch_free(batch);
            (*env)->ReleaseIntArrayElements(env, jtokens, tokens, JNI_ABORT);
            pthread_mutex_unlock(&s->lock);
            return -5;
        }
    }

    int processed = 0;
    while (processed < n_tokens) {
        if (s->abort) {
            llama_batch_free(batch);
            (*env)->ReleaseIntArrayElements(env, jtokens, tokens, JNI_ABORT);
            pthread_mutex_unlock(&s->lock);
            return -4;
        }
        int32_t chunk = n_tokens - processed;
        if (chunk > n_batch) chunk = n_batch;
        for (int32_t i = 0; i < chunk; i++) {
            batch.token[i]  = tokens[processed + i];
            batch.pos[i]    = (llama_pos)(n_past + i);
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i] = 0;
        }
        batch.logits[chunk - 1] = 1;
        batch.n_tokens = chunk;
        int decode_rc = llama_decode(s->ctx, batch);
        if (decode_rc != 0) {
            LOGE("nGenerateTokens: prefill decode failed at %d/%d rc=%d", processed+chunk, n_tokens, decode_rc);
            llama_batch_free(batch);
            (*env)->ReleaseIntArrayElements(env, jtokens, tokens, JNI_ABORT);
            pthread_mutex_unlock(&s->lock);
            return -99;
        }
        processed += chunk;
        n_past += chunk;
    }
    LOGI("nGenerateTokens: prefill done, n_past=%d", n_past);

    /* ---- 生成循环 ---- */
    int generated = 0;
    int rc = 0;
    llama_token * gen_tokens = (llama_token *) malloc(sizeof(llama_token) * (size_t) max_tokens);

    char out_buf[4096];
    int out_len = 0;
    out_buf[0] = '\0';

    static __thread unsigned int smpl_rng = 0;
    if (smpl_rng == 0) smpl_rng = (unsigned int)time(NULL) ^ (unsigned int)pthread_self();
    struct llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (smpl == NULL) { free(gen_tokens); llama_batch_free(batch); (*env)->ReleaseIntArrayElements(env, jtokens, tokens, JNI_ABORT); pthread_mutex_unlock(&s->lock); return -99; }
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        if (top_k > 0) llama_sampler_chain_add(smpl, llama_sampler_init_top_k(top_k));
        if (top_p > 0.0f && top_p < 1.0f) llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(n_vocab, 64, repeat_penalty, 0.0f, 0.0f));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(smpl_rng));
    }

    jmethodID mid_append = NULL;
    jclass cls_sb = NULL;
    if (jout != NULL) {
        cls_sb = (*env)->GetObjectClass(env, jout);
        mid_append = (*env)->GetMethodID(env, cls_sb, "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;");
    }
    jclass engine_cls = (*env)->FindClass(env, "com/aicodestudio/ide/ai/engine/LlamaCppEngine");
    jmethodID mid_token = NULL;
    if (engine_cls != NULL) mid_token = (*env)->GetStaticMethodID(env, engine_cls, "onToken", "(Ljava/lang/String;)V");

    char utf8_buf[8192];
    int utf8_len = 0;

    while (generated < max_tokens) {
        if (s->abort) { rc = -4; break; }
        if (n_past >= s->n_ctx) {
            int trimmed = trimContext(s, 2048);
            if (trimmed > 0) n_past = trimmed;
            else break;
        }
        llama_token next = llama_sampler_sample(smpl, s->ctx, -1);
        if (next < 0) { rc = -99; break; }
        if (llama_vocab_is_eog(vocab, next)) {
            LOGI("nGenerateTokens: EOS at generated=%d", generated);
            break;
        }
        llama_sampler_accept(smpl, next);
        gen_tokens[generated] = next;

        char piece[128];
        int n = llama_token_to_piece(vocab, next, piece, sizeof(piece), 0, false);
        const char * piece_ptr = NULL;
        char * big = NULL;
        if (n < 0) {
            int need = -n;
            big = (char *) malloc((size_t)need + 1);
            if (big) {
                int n2 = llama_token_to_piece(vocab, next, big, need, 0, false);
                if (n2 > 0) { big[n2] = '\0'; piece_ptr = big; }
            }
        } else if (n > 0) { piece[n] = '\0'; piece_ptr = piece; }

        if (piece_ptr) {
            int pl = (int)strlen(piece_ptr);
            if (out_len + pl < (int)sizeof(out_buf) - 1) {
                memcpy(out_buf + out_len, piece_ptr, (size_t)pl);
                out_len += pl; out_buf[out_len] = '\0';
            }
            if (utf8_len + pl < (int)sizeof(utf8_buf) - 1) {
                memcpy(utf8_buf + utf8_len, piece_ptr, (size_t)pl);
                utf8_len += pl; utf8_buf[utf8_len] = '\0';
            }
            int processed_chars = 0;
            while (processed_chars < utf8_len) {
                unsigned char c = (unsigned char)utf8_buf[processed_chars];
                int cl;
                if (c < 0x80) cl = 1;
                else if ((c & 0xE0) == 0xC0) cl = 2;
                else if ((c & 0xF0) == 0xE0) cl = 3;
                else if ((c & 0xF8) == 0xF0) cl = 4;
                else cl = 1;
                if (processed_chars + cl > utf8_len) break;
                processed_chars += cl;
            }
            if (processed_chars > 0) {
                char cs[processed_chars + 1];
                memcpy(cs, utf8_buf, (size_t)processed_chars);
                cs[processed_chars] = '\0';
                if (mid_token) { jstring js = (*env)->NewStringUTF(env, cs); (*env)->CallStaticVoidMethod(env, engine_cls, mid_token, js); (*env)->DeleteLocalRef(env, js); }
                if (mid_append) { jstring js = (*env)->NewStringUTF(env, cs); (*env)->CallObjectMethod(env, jout, mid_append, js); (*env)->DeleteLocalRef(env, js); }
                int rem = utf8_len - processed_chars;
                if (rem > 0) memmove(utf8_buf, utf8_buf + processed_chars, (size_t)rem);
                utf8_len = rem;
            }
        }
        if (big) free(big);

        batch.token[0] = next;
        batch.pos[0] = (llama_pos)n_past;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = 1;
        batch.n_tokens = 1;
        if (llama_decode(s->ctx, batch) != 0) { rc = -99; break; }
        n_past++;
        generated++;
    }

    /* 输出剩余 UTF-8 字节 */
    if (utf8_len > 0 && (mid_token || mid_append)) {
        utf8_buf[utf8_len] = '\0';
        if (mid_token) { jstring js = (*env)->NewStringUTF(env, utf8_buf); (*env)->CallStaticVoidMethod(env, engine_cls, mid_token, js); (*env)->DeleteLocalRef(env, js); }
        if (mid_append) { jstring js = (*env)->NewStringUTF(env, utf8_buf); (*env)->CallObjectMethod(env, jout, mid_append, js); (*env)->DeleteLocalRef(env, js); }
    }

    /* 返回生成的 token IDs 给 Java 层 */
    if (jout_gen_tokens != NULL && generated > 0) {
        (*env)->SetIntArrayRegion(env, jout_gen_tokens, 0, (jsize)generated, gen_tokens);
    }

    llama_sampler_free(smpl);
    free(gen_tokens);
    llama_batch_free(batch);
    (*env)->ReleaseIntArrayElements(env, jtokens, tokens, JNI_ABORT);
    pthread_mutex_unlock(&s->lock);
    LOGI("nGenerateTokens: done, generated=%d n_past=%d", generated, n_past);
    return rc == 0 ? n_past : rc;
}

/*
 * 前缀缓存预热：把 prefix tokenize 后送入 KV cache。
 * llama_decode 自动管理 KV cache，后续生成可复用。
 */
JNIEXPORT void JNICALL
Java_com_aicodestudio_ide_ai_engine_LlamaCppEngine_nWarmPrefix(
        JNIEnv * env, jclass cls, jlong handle, jstring jprefix) {
    (void) cls;
    if (handle == 0 || jprefix == NULL) return;
    session_t * s = (session_t *) (intptr_t) handle;
    if (s->ctx == NULL || s->model == NULL) return;

    pthread_mutex_lock(&s->lock);

    const struct llama_vocab * vocab = llama_model_get_vocab(s->model);
    const char * p = (*env)->GetStringUTFChars(env, jprefix, NULL);
    if (p == NULL) {
        pthread_mutex_unlock(&s->lock);
        return;
    }
    int len = (int) strlen(p);

    llama_token * tokens = (llama_token *) malloc(sizeof(llama_token) * (size_t) (len + 64));
    if (tokens == NULL) {
        (*env)->ReleaseStringUTFChars(env, jprefix, p);
        pthread_mutex_unlock(&s->lock);
        return;
    }

    int n = llama_tokenize(vocab, p, len, tokens, len + 64, true, true);
    if (n < 0) {
        free(tokens);
        tokens = (llama_token *) malloc(sizeof(llama_token) * (size_t) (-n));
        if (tokens == NULL) {
            (*env)->ReleaseStringUTFChars(env, jprefix, p);
            pthread_mutex_unlock(&s->lock);
            return;
        }
        n = llama_tokenize(vocab, p, len, tokens, -n, true, true);
    }
    (*env)->ReleaseStringUTFChars(env, jprefix, p);

    if (n > 0) {
        int32_t n_batch = llama_n_batch(s->ctx);
        struct llama_batch batch = llama_batch_init((int32_t) s->n_ctx, 0, 1);
        int processed = 0;
        int n_past = 0;
        while (processed < n) {
            /* v1.153ao：warm 循环必须检查 abort——用户点停止/切换时，若正在 prefill
               长系统提示词（可长达几十秒），abort 后必须立刻退出，否则生成线程会
               继续跑完整个 warm，导致切换任务排队数十秒、遮罩超时 */
            if (s->abort) break;
            int32_t chunk = n - processed;
            if (chunk > n_batch) chunk = n_batch;
            for (int32_t i = 0; i < chunk; i++) {
                batch.token[i]    = tokens[processed + i];
                batch.pos[i]      = (llama_pos) (n_past + i);
                batch.n_seq_id[i] = 1;
                batch.seq_id[i][0] = 0;
                batch.logits[i]   = 0;
            }
            batch.n_tokens = chunk;
            if (llama_decode(s->ctx, batch) != 0) break;
            processed += chunk;
            n_past += chunk;
        }
        llama_batch_free(batch);
    }

    free(tokens);
    pthread_mutex_unlock(&s->lock);
}

JNIEXPORT void JNICALL
Java_com_aicodestudio_ide_ai_engine_LlamaCppEngine_nClearCache(
        JNIEnv * env, jclass cls, jlong handle) {
    (void) env; (void) cls;
    if (handle == 0) return;
    session_t * s = (session_t *) (intptr_t) handle;
    if (s->ctx != NULL) {
        pthread_mutex_lock(&s->lock);
        llama_memory_t mem = llama_get_memory(s->ctx);
        if (mem != NULL) {
            llama_memory_clear(mem, true);
        }
        pthread_mutex_unlock(&s->lock);
    }
}

/* v1.107: 设置前缀 PIN 锁定长度（L1+L2+L3 的 token 数）。
 * 设置后，KV 淘汰时永远保留这部分前缀，只滑动淘汰后面的对话。
 */
JNIEXPORT void JNICALL
Java_com_aicodestudio_ide_ai_engine_LlamaCppEngine_nSetPrefixKeep(
        JNIEnv * env, jclass cls, jlong handle, jint n_keep) {
    (void) env; (void) cls;
    if (handle == 0) return;
    session_t * s = (session_t *) (intptr_t) handle;
    s->n_prefix_keep = (int) n_keep;
    LOGI("nSetPrefixKeep: n_prefix_keep=%d", s->n_prefix_keep);
}

JNIEXPORT jstring JNICALL
Java_com_aicodestudio_ide_ai_engine_LlamaCppEngine_nCacheStats(
        JNIEnv * env, jclass cls, jlong handle) {
    (void) cls;
    if (handle == 0) return (*env)->NewStringUTF(env, "");
    session_t * s = (session_t *) (intptr_t) handle;
    if (s->ctx == NULL) return (*env)->NewStringUTF(env, "");

    uint32_t n_ctx = llama_n_ctx(s->ctx);
    char buf[128];
    snprintf(buf, sizeof(buf), "KV ctx %d cells (auto-managed)", (int) n_ctx);
    return (*env)->NewStringUTF(env, buf);
}

/* v1.133：把 KV 重置回系统前缀位置——只保留前 n_prefix_keep 个 token，
 * 删除其后所有对话/生成 KV。配合增量 prefill：系统段 KV 常驻不重算，
 * 每轮只 prefill 新增的对话段（从 n_prefix_keep 位置继续）。 */
JNIEXPORT void JNICALL
Java_com_aicodestudio_ide_ai_engine_LlamaCppEngine_nResetToPrefix(
        JNIEnv * env, jclass cls, jlong handle) {
    (void) env; (void) cls;
    if (handle == 0) return;
    session_t * s = (session_t *) (intptr_t) handle;
    if (s->ctx == NULL) return;
    if (s->n_prefix_keep <= 0) return;
    pthread_mutex_lock(&s->lock);
    llama_memory_t mem = llama_get_memory(s->ctx);
    if (mem != NULL) {
        llama_pos pos_max = llama_memory_seq_pos_max(mem, 0);
        if (pos_max > s->n_prefix_keep) {
            llama_memory_seq_rm(mem, 0, s->n_prefix_keep, pos_max + 1);
            LOGI("nResetToPrefix: evicted [%d, %lld), kept prefix %d",
                 s->n_prefix_keep, (long long)pos_max, s->n_prefix_keep);
        }
    }
    pthread_mutex_unlock(&s->lock);
}

/* ==================== v1.150：KV 状态导出/导入（会话级 KV 持久化） ====================
 * 切换会话时把当前 KV（前缀+历史对话的完整 prefill 状态）导出为 byte[] 落盘；
 * 切回会话时导入恢复，下一轮只 prefill 新增 user 消息，省掉重新 prefill 整段历史。
 * 依赖 llama_state_get_data / llama_state_set_data（llama.cpp 0.4.0+）。 */

/* v1.153bj：tokenize 一段文本，返回 token IDs 数组（供 token 级前缀匹配，替代字符串 startsWith） */
JNIEXPORT jintArray JNICALL
Java_com_aicodestudio_ide_ai_engine_LlamaCppEngine_nTokenize(
        JNIEnv * env, jclass cls, jlong handle, jstring jprompt, jboolean addSpecial) {
    (void) cls;
    if (handle == 0 || jprompt == NULL) return NULL;
    session_t * s = (session_t *) (intptr_t) handle;
    if (s->ctx == NULL || s->model == NULL) return NULL;

    const struct llama_vocab * vocab = llama_model_get_vocab(s->model);
    const char * prompt_c = (*env)->GetStringUTFChars(env, jprompt, NULL);
    if (prompt_c == NULL) return NULL;
    int prompt_len = (int) strlen(prompt_c);

    int buf_size = prompt_len + 64;
    llama_token * tokens = (llama_token *) malloc(sizeof(llama_token) * (size_t) buf_size);
    if (!tokens) { (*env)->ReleaseStringUTFChars(env, jprompt, prompt_c); return NULL; }

    int n = llama_tokenize(vocab, prompt_c, prompt_len, tokens, buf_size, (bool) addSpecial, true);
    if (n < 0) {
        free(tokens);
        tokens = (llama_token *) malloc(sizeof(llama_token) * (size_t)(-n));
        if (!tokens) { (*env)->ReleaseStringUTFChars(env, jprompt, prompt_c); return NULL; }
        n = llama_tokenize(vocab, prompt_c, prompt_len, tokens, -n, (bool) addSpecial, true);
    }
    (*env)->ReleaseStringUTFChars(env, jprompt, prompt_c);

    jintArray out = NULL;
    if (n > 0) {
        out = (*env)->NewIntArray(env, (jsize) n);
        if (out) (*env)->SetIntArrayRegion(env, out, 0, (jsize) n, (const jint *) tokens);
    }
    free(tokens);
    return out;
}

/* 导出：返回完整 KV 状态 byte[]；失败返回 NULL */
JNIEXPORT jbyteArray JNICALL
Java_com_aicodestudio_ide_ai_engine_LlamaCppEngine_nKvExport(
        JNIEnv * env, jclass cls, jlong handle) {
    (void) cls;
    if (handle == 0) return NULL;
    session_t * s = (session_t *) (intptr_t) handle;
    if (s->ctx == NULL) return NULL;

    jbyteArray out = NULL;
    pthread_mutex_lock(&s->lock);
    size_t size = llama_state_get_size(s->ctx);
    if (size > 0 && size < (size_t) 512 * 1024 * 1024) { /* 防御：单会话 KV 上限 512MB */
        uint8_t * buf = (uint8_t *) malloc(size);
        if (buf != NULL) {
            size_t n = llama_state_get_data(s->ctx, buf, size);
            if (n > 0 && n <= size) {
                out = (*env)->NewByteArray(env, (jsize) n);
                if (out != NULL) {
                    (*env)->SetByteArrayRegion(env, out, 0, (jsize) n, (const jbyte *) buf);
                    LOGI("nKvExport: exported %zu bytes KV state", n);
                }
            }
            free(buf);
        }
    }
    pthread_mutex_unlock(&s->lock);
    return out;
}

/* 导入：把之前导出的 KV 状态恢复进 ctx；成功返回 true */
JNIEXPORT jboolean JNICALL
Java_com_aicodestudio_ide_ai_engine_LlamaCppEngine_nKvImport(
        JNIEnv * env, jclass cls, jlong handle, jbyteArray data) {
    (void) cls;
    if (handle == 0) return JNI_FALSE;
    session_t * s = (session_t *) (intptr_t) handle;
    if (s->ctx == NULL || data == NULL) return JNI_FALSE;

    jsize len = (*env)->GetArrayLength(env, data);
    if (len <= 0) return JNI_FALSE;
    jbyte * bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (bytes == NULL) return JNI_FALSE;

    size_t read = 0;
    pthread_mutex_lock(&s->lock);
    read = llama_state_set_data(s->ctx, (const uint8_t *) bytes, (size_t) len);
    pthread_mutex_unlock(&s->lock);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);

    LOGI("nKvImport: restored %zu/%d bytes KV state", read, (int) len);
    return (read > 0) ? JNI_TRUE : JNI_FALSE;
}

/* KV 当前已用 token 数（seq 0 的最大位置 +1）——导入后供增量 prefill 定位 */
JNIEXPORT jint JNICALL
Java_com_aicodestudio_ide_ai_engine_LlamaCppEngine_nKvUsedTokens(
        JNIEnv * env, jclass cls, jlong handle) {
    (void) env; (void) cls;
    if (handle == 0) return 0;
    session_t * s = (session_t *) (intptr_t) handle;
    if (s->ctx == NULL) return 0;
    pthread_mutex_lock(&s->lock);
    int used = 0;
    llama_memory_t mem = llama_get_memory(s->ctx);
    if (mem != NULL) {
        llama_pos pos_max = llama_memory_seq_pos_max(mem, 0);
        if (pos_max >= 0) used = (int) pos_max + 1;
    }
    pthread_mutex_unlock(&s->lock);
    return used;
}

/* v4.5：查询最近一次 generate 是否发生了 KV trim（发生后调用方应重置 kvTokenIds） */
JNIEXPORT jint JNICALL
Java_com_aicodestudio_ide_ai_engine_LlamaCppEngine_nWasTrimmed(
        JNIEnv * env, jclass cls, jlong handle) {
    (void) env; (void) cls;
    if (handle == 0) return 0;
    session_t * s = (session_t *) (intptr_t) handle;
    return s->was_trimmed;
}

JNIEXPORT void JNICALL
Java_com_aicodestudio_ide_ai_engine_LlamaCppEngine_nClearTrimmedFlag(
        JNIEnv * env, jclass cls, jlong handle) {
    (void) env; (void) cls;
    if (handle == 0) return;
    session_t * s = (session_t *) (intptr_t) handle;
    s->was_trimmed = 0;
}

/* v4.3：用官方 llama_state_save_file / load_file，同时保存 KV + token IDs。
 * 这是 llama.cpp 官方推荐的会话持久化方式，恢复后 token 数和 KV 严格一致。 */
JNIEXPORT jboolean JNICALL
Java_com_aicodestudio_ide_ai_engine_LlamaCppEngine_nSaveSessionFile(
        JNIEnv * env, jclass cls, jlong handle, jstring jpath, jintArray jtokens) {
    (void) cls;
    if (handle == 0) return JNI_FALSE;
    session_t * s = (session_t *) (intptr_t) handle;
    if (s->ctx == NULL) return JNI_FALSE;
    const char * path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (path == NULL) return JNI_FALSE;
    jsize n = (*env)->GetArrayLength(env, jtokens);
    jint * tokens = (*env)->GetIntArrayElements(env, jtokens, NULL);
    pthread_mutex_lock(&s->lock);
    bool ok = llama_state_save_file(s->ctx, path, (llama_token *)tokens, (size_t)n);
    pthread_mutex_unlock(&s->lock);
    (*env)->ReleaseIntArrayElements(env, jtokens, tokens, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    LOGI("nSaveSessionFile: %s n_tokens=%d ok=%d", path, (int)n, (int)ok);
    return ok ? JNI_TRUE : JNI_FALSE;
}

/* 加载官方 session 文件，直接返回恢复的 token IDs。
 * 对齐官方 completion.cpp：分配 n_ctx 大小 buffer，一次调用完成。 */
JNIEXPORT jintArray JNICALL
Java_com_aicodestudio_ide_ai_engine_LlamaCppEngine_nLoadSessionFile(
        JNIEnv * env, jclass cls, jlong handle, jstring jpath) {
    (void) cls;
    if (handle == 0) return NULL;
    session_t * s = (session_t *) (intptr_t) handle;
    if (s->ctx == NULL) return NULL;
    const char * path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (path == NULL) return NULL;
    pthread_mutex_lock(&s->lock);
    size_t capacity = (size_t) s->n_ctx;
    llama_token * tokens = (llama_token *) malloc(sizeof(llama_token) * capacity);
    size_t n_loaded = 0;
    bool ok = false;
    if (tokens) {
        ok = llama_state_load_file(s->ctx, path, tokens, capacity, &n_loaded);
        if (!ok) {
            // 诊断：手动读文件头看 magic/version/token_count
            FILE *f = fopen(path, "rb");
            if (f) {
                uint32_t magic = 0, ver = 0, ntok = 0;
                fread(&magic, 4, 1, f);
                fread(&ver, 4, 1, f);
                fread(&ntok, 4, 1, f);
                fseek(f, 0, SEEK_END);
                long fsz = ftell(f);
                fclose(f);
                LOGI("nLoadSessionFile: DIAG magic=0x%08x ver=%u ntok=%u cap=%zu filesz=%ld",
                     magic, ver, ntok, capacity, fsz);
            }
        }
    }
    pthread_mutex_unlock(&s->lock);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (!ok || n_loaded == 0 || !tokens) {
        free(tokens);
        LOGI("nLoadSessionFile: %s failed ok=%d n=%zu", path, (int)ok, n_loaded);
        return NULL;
    }
    jintArray result = (*env)->NewIntArray(env, (jsize)n_loaded);
    if (result) {
        (*env)->SetIntArrayRegion(env, result, 0, (jsize)n_loaded, (jint *)tokens);
    }
    free(tokens);
    LOGI("nLoadSessionFile: loaded %zu tokens", n_loaded);
    return result;
}

/* v1.151：保存前主动裁剪 KV 到滑动窗口（系统前缀 + 最近 keepRecent token）。
 * 快照恒为窗口大小，不随历史对话累积膨胀；窗口外历史由 JSON 消息记录保留。
 * 复用 trimContext 逻辑；未设置前缀 PIN 时（未 warm）裁剪为空操作。
 */
JNIEXPORT void JNICALL
Java_com_aicodestudio_ide_ai_engine_LlamaCppEngine_nTrimToWindow(
        JNIEnv * env, jclass cls, jlong handle, jint keepRecent) {
    (void) env; (void) cls;
    if (handle == 0) return;
    session_t * s = (session_t *) (intptr_t) handle;
    if (s->ctx == NULL || keepRecent <= 0) return;
    if (s->n_prefix_keep <= 0) return; /* 未设置前缀 PIN，不裁剪 */
    pthread_mutex_lock(&s->lock);
    int new_past = trimContext(s, (int) keepRecent);
    pthread_mutex_unlock(&s->lock);
    LOGI("nTrimToWindow: keepRecent=%d new_n_past=%d", (int) keepRecent, new_past);
}

JNIEXPORT void JNICALL
Java_com_aicodestudio_ide_ai_engine_LlamaCppEngine_nPrefetch(
        JNIEnv * env, jclass cls, jlong handle) {
    (void) env; (void) cls;
    if (handle == 0) return;
    session_t * s = (session_t *) (intptr_t) handle;
    if (s->path == NULL) return;

    FILE * f = fopen(s->path, "rb");
    if (f == NULL) return;

    const size_t BLK = 1024 * 1024;
    void * buf = malloc(BLK);
    if (buf == NULL) {
        fclose(f);
        return;
    }
    size_t got;
    while ((got = fread(buf, 1, BLK, f)) > 0) {
        if (fseek(f, (long) (BLK - 4096), SEEK_CUR) != 0) break;
    }
    free(buf);
    fclose(f);
}

/* v1.124: 用模型自带chat template格式化messages，替代手动拼ChatML */
JNIEXPORT jstring JNICALL
Java_com_aicodestudio_ide_ai_engine_LlamaCppEngine_nApplyChatTemplate(
        JNIEnv * env, jclass cls, jlong handle,
        jobjectArray roles, jobjectArray contents, jboolean add_assistant) {
    if (handle == 0) return (*env)->NewStringUTF(env, "");
    session_t * s = (session_t *) (intptr_t) handle;
    if (s->model == NULL) return (*env)->NewStringUTF(env, "");

    const char * tmpl = llama_model_chat_template(s->model, NULL);
    if (tmpl == NULL || strlen(tmpl) == 0) {
        LOGE("applyChatTemplate: no chat template in model");
        return (*env)->NewStringUTF(env, "");
    }

    jsize n = (*env)->GetArrayLength(env, roles);
    struct llama_chat_message * msgs = calloc(n, sizeof(struct llama_chat_message));
    for (jsize i = 0; i < n; i++) {
        jstring jr = (jstring)(*env)->GetObjectArrayElement(env, roles, i);
        jstring jc = (jstring)(*env)->GetObjectArrayElement(env, contents, i);
        msgs[i].role = (*env)->GetStringUTFChars(env, jr, NULL);
        msgs[i].content = (*env)->GetStringUTFChars(env, jc, NULL);
    }

    int32_t need = llama_chat_apply_template(tmpl, msgs, n, add_assistant, NULL, 0);
    char * buf = malloc(need + 1);
    llama_chat_apply_template(tmpl, msgs, n, add_assistant, buf, need);
    buf[need] = 0;

    for (jsize i = 0; i < n; i++) {
        jstring jr = (jstring)(*env)->GetObjectArrayElement(env, roles, i);
        jstring jc = (jstring)(*env)->GetObjectArrayElement(env, contents, i);
        (*env)->ReleaseStringUTFChars(env, jr, msgs[i].role);
        (*env)->ReleaseStringUTFChars(env, jc, msgs[i].content);
    }
    free(msgs);

    jstring result = (*env)->NewStringUTF(env, buf);
    free(buf);
    return result;
}
