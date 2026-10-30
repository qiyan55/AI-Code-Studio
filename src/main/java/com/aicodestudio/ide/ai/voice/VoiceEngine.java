package com.aicodestudio.ide.ai.voice;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflinePunctuation;
import com.k2fsa.sherpa.onnx.OfflinePunctuationConfig;
import com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 语音识别引擎单例：App 启动时后台预加载（模型释放 + 识别器创建），
 * 常驻内存，界面可就绪后使用 —— 用户要求"打开应用自动加载、给进度、就绪后可操作"。
 *
 * 状态：LOADING → READY / ERROR；UI 通过 addStatusListener 监听进度。
 *
 * 崩溃排查支持：每一步关键操作写入 filesDir/voice_engine.log（每次 init 前把旧日志
 * 转存为 voice_engine.prev.log），即使 native 崩溃（无 Java 日志）也能从日志文件
 * 定位崩溃发生在哪一步。loadLibrary 从静态块移到 init 显式执行，确保 so 加载
 * 失败也有日志。
 */
public class VoiceEngine {

    public interface StatusListener {
        /** @param status STATUS_LOADING / STATUS_READY / STATUS_ERROR */
        void onStatus(int status);
    }

    public static final int STATUS_LOADING = 0;
    public static final int STATUS_READY   = 1;
    public static final int STATUS_ERROR   = 2;

    private static final String TAG = "VoiceEngine";
    private static final String ASSET_PREFIX = "asr/";
    private static final String[] MODEL_FILES = {
            "encoder-epoch-99-avg-1.int8.onnx",
            "decoder-epoch-99-avg-1.onnx",
            "joiner-epoch-99-avg-1.int8.onnx",
            "tokens.txt"
    };
    private static final int SAMPLE_RATE = 16000;

    private static volatile VoiceEngine instance;

    public static VoiceEngine get() {
        if (instance == null) {
            synchronized (VoiceEngine.class) {
                if (instance == null) instance = new VoiceEngine();
            }
        }
        return instance;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<StatusListener> listeners = new ArrayList<StatusListener>();
    private Context appContext;
    private OnlineRecognizer recognizer;
    private OfflinePunctuation punctuation; // 标点模型（可选：加载失败不影响语音识别）
    private volatile int status = STATUS_LOADING;
    private boolean initStarted;
    private boolean libLoaded;

    /** App 启动时调用：异步预加载，不阻塞主线程 */
    public synchronized void init(Context ctx) {
        if (initStarted) return;
        initStarted = true;
        appContext = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    VoiceLog.init(appContext); // 公共目录日志 Download/AI_Code_Studio/
                    log("init begin");
                    loadNative();
                    ensureModel();
                    recognizer = createRecognizer();
                    // 标点模型延迟加载：启动不加载（省内存），首次语音识别时 ensurePunctuation 创建
                    status = STATUS_READY;
                    log("VoiceEngine READY");
                } catch (Throwable t) {
                    log("VoiceEngine ERROR: " + t);
                    Log.e(TAG, "语音引擎加载失败", t);
                    status = STATUS_ERROR;
                }
                notifyStatus();
            }
        }).start();
    }

    public OnlineRecognizer getRecognizer() { return recognizer; }

    /**
     * 等待语音引擎就绪（串行加载用）：本地大模型加载前先调用，
     * 避免两个大模型同时加载导致内存峰值崩溃。返回 true=READY。
     */
    public boolean awaitReady(long timeoutMs) {
        long t0 = System.currentTimeMillis();
        while (status == STATUS_LOADING && System.currentTimeMillis() - t0 < timeoutMs) {
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }
        return status == STATUS_READY;
    }

    /** 标点模型：懒加载并初始化 */
    public synchronized OfflinePunctuation ensurePunctuation() {
        if (punctuation != null) return punctuation;
        try {
            File dir = new File(appContext.getFilesDir(), "punct");
            if (!dir.exists()) dir.mkdirs();
            File modelFile = new File(dir, "model.int8.onnx");
            
            // 从 assets 提取模型到私有目录
            if (!modelFile.exists() || modelFile.length() == 0) {
                java.io.InputStream is = appContext.getAssets().open("punct/model.int8.onnx");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(modelFile);
                byte[] buf = new byte[16384];
                int n;
                while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                fos.close();
                is.close();
                log("punct model extracted");
            }
            
            // 初始化标点模型配置
            com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig modelConfig =
                    com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig.builder()
                            .setCtTransformer(modelFile.getAbsolutePath())
                            .setNumThreads(1)
                            .setDebug(false)
                            .setProvider("cpu")
                            .build();
            
            com.k2fsa.sherpa.onnx.OfflinePunctuationConfig config =
                    com.k2fsa.sherpa.onnx.OfflinePunctuationConfig.builder()
                            .setModel(modelConfig)
                            .build();
            
            punctuation = new OfflinePunctuation(config);
            log("punctuation engine ready");
            return punctuation;
        } catch (Throwable t) {
            log("load punctuation failed: " + t);
            return null;
        }
    }

    /** 旧 getter 保留（兼容） */
    public OfflinePunctuation getPunctuation() { return punctuation; }

    /** 供 VoiceInputManager 等写文件日志用 */
    public static Context getAppContext() { return instance == null ? null : instance.appContext; }

    public int getStatus() { return status; }

    public boolean isReady() { return status == STATUS_READY; }

    /** 注册状态监听（立即回调一次当前状态）；返回 false 表示未就绪（仍在加载或失败） */
    public boolean addStatusListener(final StatusListener l) {
        if (l != null) listeners.add(l);
        ui.post(new Runnable() {
            @Override public void run() {
                if (l != null) l.onStatus(status);
            }
        });
        return status == STATUS_READY;
    }

    // ================= 内部 =================

    /** 显式加载 so（避免静态块里崩溃无日志） */
    private void loadNative() {
        if (libLoaded) return;
        log("loadLibrary sherpa-onnx-jni ...");
        System.loadLibrary("sherpa-onnx-jni");
        libLoaded = true;
        log("loadLibrary OK");
    }

    /**
     * 模型加载：与官方 demo CPU 路径一致——模型放 assets，路径用 assets 相对路径
     * （OnlineRecognizer 持有 AssetManager，C++ 层直接读 assets，不复制到 filesDir）。
     */
    private void ensureModel() {
        AssetManager am = appContext.getAssets();
        for (String name : MODEL_FILES) {
            String p = ASSET_PREFIX + name;
            boolean ok = false;
            try {
                java.io.InputStream is = am.open(p);
                ok = (is != null);
                if (is != null) is.close();
            } catch (Throwable t) {
                log("asset check fail: " + p + " -> " + t);
            }
            log("asset " + p + (ok ? " OK" : " MISSING"));
            if (!ok) throw new IllegalStateException("模型文件缺失: " + p);
        }
    }

    /**
     * 热词相关文件：sherpa 的 hotwordsFile / bpeVocab 都只认文件系统路径（fopen），
     * 不认 assets 相对路径（v11.28q 闪退根因；v11.28u bpe.vocab assets 路径崩溃，
     * 本地 sherpa-onnx 验证配置正确，安卓端必须用绝对路径）。
     * 启动时从 assets 复制到 filesDir 再传绝对路径。返回 [hotwordsPath, bpeVocabPath]。
     */
    private String[] ensureHotwordFiles() {
        String[] names = {"hotwords.txt", "bpe.vocab"};
        String[] out = new String[2];
        for (int i = 0; i < names.length; i++) {
            try {
                File dir = new File(appContext.getFilesDir(), "asr");
                if (!dir.exists()) dir.mkdirs();
                File outF = new File(dir, names[i]);
                java.io.InputStream is = appContext.getAssets().open(ASSET_PREFIX + names[i]);
                FileOutputStream fos = new FileOutputStream(outF);
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                fos.close();
                is.close();
                out[i] = outF.getAbsolutePath();
                log("copied " + names[i] + " -> " + outF.getAbsolutePath());
            } catch (Throwable t) {
                log("copy " + names[i] + " fail: " + t);
                out[i] = null;
            }
        }
        return out;
    }

    /**
     * 把 assets/asr/ 下的全部模型文件复制到 filesDir/asr/。
     * sherpa-onnx C++ 层用 fopen 读模型，只认文件系统绝对路径，不认 assets。
     * 已存在且大小一致则跳过，避免每次启动都复制 60MB 模型。
     * 返回 filesDir/asr/ 的绝对路径。
     */
    private String ensureModelFilesInFilesDir() throws Exception {
        File dir = new File(appContext.getFilesDir(), "asr");
        if (!dir.exists()) dir.mkdirs();

        // 需要复制的全部 ASR 文件（与 MODEL_FILES 保持一致，加热词和 bpe）
        String[] allFiles = {
            "encoder-epoch-99-avg-1.int8.onnx",
            "decoder-epoch-99-avg-1.onnx",
            "joiner-epoch-99-avg-1.int8.onnx",
            "tokens.txt",
            "hotwords.txt",
            "bpe.vocab"
        };

        AssetManager am = appContext.getAssets();
        int copied = 0, skipped = 0;
        for (String name : allFiles) {
            File outF = new File(dir, name);
            // 先查 assets 里的大小
            long assetSize = -1;
            java.io.InputStream checkIs = null;
            try {
                checkIs = am.open(ASSET_PREFIX + name);
                // 用 available() 估算大小（assets 压缩存储时可能不准，用实际读取判断更稳）
                assetSize = checkIs.available();
            } finally {
                if (checkIs != null) try { checkIs.close(); } catch (Throwable ignored) {}
            }

            // 已存在且大小 > 0 就跳过（首次安装后复制一次即可，后续启动不重复复制）
            if (outF.exists() && outF.length() > 0) {
                skipped++;
                log("skip (exists) " + name + " size=" + outF.length());
                continue;
            }

            // 复制文件
            java.io.InputStream is = am.open(ASSET_PREFIX + name);
            FileOutputStream fos = new FileOutputStream(outF);
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
            fos.close();
            is.close();
            copied++;
            log("copied " + name + " -> " + outF.getAbsolutePath() + " (" + outF.length() + " bytes)");
        }
        log("模型文件准备完成: copied=" + copied + ", skipped=" + skipped + ", dir=" + dir.getAbsolutePath());
        return dir.getAbsolutePath();
    }

    private OnlineRecognizer createRecognizer() throws Exception {
        // jar里是v1.12.15 builder版本，模型路径用filesDir绝对路径
        // 必须先把 assets 里的模型文件复制到 filesDir（sherpa-onnx C++ 层只认文件系统路径，不认 assets）
        String modelDir = ensureModelFilesInFilesDir();

        FeatureConfig feat = FeatureConfig.builder()
                .setSampleRate(16000).setFeatureDim(80).setDither(0).build();

        OnlineTransducerModelConfig transducer = OnlineTransducerModelConfig.builder()
                .setEncoder(modelDir + "/encoder-epoch-99-avg-1.int8.onnx")
                .setDecoder(modelDir + "/decoder-epoch-99-avg-1.onnx")
                .setJoiner(modelDir + "/joiner-epoch-99-avg-1.int8.onnx").build();

        OnlineModelConfig modelConfig = OnlineModelConfig.builder()
                .setTransducer(transducer)
                .setTokens(modelDir + "/tokens.txt")
                .setNumThreads(2).setDebug(false).setProvider("cpu").build();

        OnlineRecognizerConfig recognizerConfig = OnlineRecognizerConfig.builder()
                .setFeatureConfig(feat).setOnlineModelConfig(modelConfig)
                .setDecodingMethod("greedy_search").build();

        log("v1.12.15 builder方式创建recognizer，模型路径:" + modelDir);
        OnlineRecognizer recognizer = new OnlineRecognizer(recognizerConfig);
        log("recognizer创建成功");
        return recognizer;
    }

    private void notifyStatus() {
        final int st = status;
        ui.post(new Runnable() {
            @Override public void run() {
                for (StatusListener l : listeners) {
                    try { l.onStatus(st); } catch (Throwable ignored) {}
                }
            }
        });
    }

    private void log(String msg) {
        String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date())
                + " [" + Thread.currentThread().getName() + "] " + msg;
        Log.i(TAG, msg);
        VoiceLog.engine(line); // 公共目录 Download/AI_Code_Studio/voice_engine_*.log
    }
}
