package com.aicodestudio.ide.ai.voice;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.k2fsa.sherpa.onnx.OfflinePunctuation;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineStream;

/**
 * 语音输入控制器：使用 VoiceEngine 常驻引擎（App 启动已预加载），
 * 按住说话 → AudioRecord 录音 → 流式识别（实时出字）→ 松手出最终结果。
 * 引擎未就绪时回调 onError 提示"加载中"。
 */
public class VoiceInputManager {

    public interface Listener {
        /** 实时中间结果（主线程回调），text 可能为空串 */
        void onPartial(String text);
        /** 识别结束最终结果（主线程回调） */
        void onFinal(String text);
        /** 错误提示（主线程回调） */
        void onError(String msg);
    }

    private static final String TAG = "VoiceInput";
    private static final int SAMPLE_RATE = 16000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private OnlineRecognizer recognizer;
    private AudioRecord recorder;
    private Thread recThread;
    private volatile boolean running;
    private volatile String lastPartial = ""; // 录音循环中实时保存的最新识别结果（松手直接用它）
    private Listener listener;

    /** 开始录音识别（需调用方已获得 RECORD_AUDIO 权限；引擎由 VoiceEngine 常驻预加载） */
    public synchronized void start(Context ctx, final Listener l) {
        HotwordCorrector.ensureLoaded(ctx); // 应用侧热词词典（懒加载，一次）
        this.listener = l;
        // 上次识别线程仍在收尾（stop 后最多 ~1.7s）：拒绝本次，避免同一 recognizer 双线程并发 → native 崩溃
        if (running || (recThread != null && recThread.isAlive())) {
            postError("正在处理上次语音，请稍候再试");
            return;
        }
        log("start begin");
        VoiceEngine engine = VoiceEngine.get();
        if (!engine.isReady()) {
            log("engine not ready status=" + engine.getStatus());
            Log.i(TAG, "引擎未就绪 status=" + engine.getStatus());
            postError(engine.getStatus() == VoiceEngine.STATUS_LOADING
                    ? "语音引擎加载中，请稍候..." : "语音引擎加载失败，请重启应用");
            return;
        }
        recognizer = engine.getRecognizer();
        if (recognizer == null) {
            log("recognizer null");
            postError("语音引擎未就绪");
            return;
        }

        // FIX v11.29a: 从 assets 读取有效热词（188词：142英文大写+46中文，piece 均在 tokens.txt，避免 OOV 静默丢弃）
        // 热词使 beam 解码偏置代码/常用词；modelingUnit=cjkchar+bpe 时中文按字编码、英文按 BPE。
        final String hwText = readHotwordsFromAssets(ctx);
        final OnlineStream stream = recognizer.createStream();
        final int minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            postError("麦克风初始化失败");
            stream.release();
            return;
        }
        recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minBuf, SAMPLE_RATE / 2)); // 缓冲 0.5 秒，防 decode 慢时溢出丢音频
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            postError("麦克风不可用，请检查录音权限");
            recorder.release();
            recorder = null;
            stream.release();
            return;
        }

        running = true;
        recThread = new Thread(new Runnable() {
            @Override public void run() {
                // 完全对齐官方 sherpa-onnx demo 录音循环：
                // 小 buffer（0.1 秒）→ acceptWaveform(samples, sampleRateInHz)
                // → while(isReady) decode（把已送入音频全部处理完）→ getResult 实时上屏
                short[] buf = new short[SAMPLE_RATE / 10]; // 0.1 秒，官方 buffer 大小
                float[] f = new float[buf.length];
                try {
                    recorder.startRecording();
                    log("recording start");
                    int frames = 0;
                    while (true) {
                        int n;
                        try {
                            n = recorder.read(buf, 0, buf.length);
                        } catch (Throwable re) {
                            log("read error: " + re);
                            break; // 读取异常停止，避免死循环
                        }
                        if (n < 0) { log("read err n=" + n); break; }
                        if (n == 0) {
                            // 无数据：松手则结束，否则继续等
                            if (!running) break;
                            continue;
                        }
                        frames++;
                        try {
                            for (int i = 0; i < n; i++) f[i] = buf[i] / 32768.0f;
                            stream.acceptWaveform(f, SAMPLE_RATE); // 官方：第二参数是采样率
                            // 官方：while(isReady) 反复 decode，直到流处理完当前数据
                            while (recognizer.isReady(stream)) {
                                recognizer.decode(stream);
                            }
                            String t = recognizer.getResult(stream).getText();
                            if (t != null && t.length() > 0) {
                                lastPartial = t;
                                postPartial(t);
                            }
                        } catch (Throwable fe) {
                            log("frame error: " + fe);
                            break; // 本帧解码异常停止
                        }
                        if (!running) {
                            // 松手：继续排空 AudioRecord 缓冲中的尾部音频（最多 0.6 秒），
                            // 保证长句/复杂句最后几个字不丢（这是"复杂句识别不全"的关键）
                            for (int i = 0; i < 6; i++) {
                                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                                int n2;
                                try {
                                    n2 = recorder.read(buf, 0, buf.length);
                                } catch (Throwable re2) { break; }
                                if (n2 <= 0) break; // 缓冲已排空
                                frames++;
                                try {
                                    for (int j = 0; j < n2; j++) f[j] = buf[j] / 32768.0f;
                                    stream.acceptWaveform(f, SAMPLE_RATE);
                                    while (recognizer.isReady(stream)) {
                                        recognizer.decode(stream);
                                    }
                                    String t2 = recognizer.getResult(stream).getText();
                                    if (t2 != null && t2.length() > 0) {
                                        lastPartial = t2;
                                        postPartial(t2);
                                    }
                                } catch (Throwable fe2) { break; }
                            }
                            break; // 排空完成，退出录音循环
                        }
                    }
                    log("recording stop frames=" + frames);
                } catch (Throwable t) {
                    Log.e(TAG, "录音/识别异常", t);
                    log("rec error: " + t);
                    postError("识别中断：" + t.getMessage());
                } finally {
                    try { recorder.stop(); } catch (Throwable ignored) {}
                    recorder.release();
                    recorder = null;
                    try {
                        // 对齐官方 demo：松手后不再 decode（inputFinished 后继续 decode 会触发 native 崩溃）。
                        // 结果已在录音循环中实时保存在 lastPartial（while(isReady) decode 保证每块处理完）。
                        stream.inputFinished(); // 通知流结束（安全，官方 release 前同样调用）
                        String result = lastPartial == null ? "" : lastPartial.trim();
                        // 应用侧热词纠正（native 热词在 Android 有官方 mutex 崩溃 bug #3065，
                        // 不可用；此处用代码词词典做编辑距离<=1 纠正，稳定不闪退）
                        try {
                            result = HotwordCorrector.correct(result);
                        } catch (Throwable ignored) {}
                        // 标点模型：识别完成后整句加标点（引擎常驻的 OfflinePunctuation）
                        try {
                            OfflinePunctuation punc = VoiceEngine.get().ensurePunctuation();
                            if (punc != null && result.length() > 0) {
                                result = punc.addPunctuation(result);
                            }
                        } catch (Throwable pt) {
                            log("punct error: " + pt); // 标点失败不影响识别结果
                        }
                        log("final: " + result);
                        Log.i(TAG, "final: " + result);
                        postFinal(result);
                    } catch (Throwable t) {
                        Log.e(TAG, "取最终结果异常", t);
                        log("final error: " + t);
                    }
                    try { stream.release(); } catch (Throwable ignored) {}
                    // 引擎常驻（VoiceEngine），此处不 release
                }
            }
        });
        recThread.start();
    }

    /** 松手结束识别（输出最终结果） */
    public void stop() {
        running = false;
    }

    /** 取消本次识别（不输出结果） */
    public void cancel() {
        running = false;
    }

    // ================= 内部 =================

    /** FIX v11.29a: 读取 assets/asr/hotwords.txt 作为 native 热词（每行一个词） */
    private String readHotwordsFromAssets(Context ctx) {
        try {
            java.io.InputStream is = ctx.getAssets().open("asr/hotwords.txt");
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) sb.append(line).append("\n");
            }
            br.close();
            log("热词加载 " + (sb.length() > 0 ? "OK" : "EMPTY"));
            return sb.toString();
        } catch (Throwable t) {
            log("热词读取失败: " + t);
            return "";
        }
    }

    private void log(String msg) {
        VoiceLog.input(msg); // 公共目录 Download/AI_Code_Studio/voice_input_*.log
    }

    private void postPartial(final String text) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (listener != null) listener.onPartial(text);
            }
        });
    }

    private void postFinal(final String text) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (listener != null) listener.onFinal(text);
            }
        });
    }

    private void postError(final String msg) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (listener != null) listener.onError(msg);
            }
        });
    }
}
