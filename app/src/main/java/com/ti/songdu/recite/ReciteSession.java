package com.ti.songdu.recite;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import com.k2fsa.sherpa.onnx.EndpointConfig;
import com.k2fsa.sherpa.onnx.EndpointRule;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * v1.16.0 识读会话：麦克风采集 → sherpa-onnx 流式识别 → ReciteAligner 音节对齐
 * → 错误落盘（ReciteStats，立即）＋显示事件延迟一拍下发（FOLLOW_DELAY_MS）。
 *
 * 延迟一拍（用户定的「他领、App 跟」）：识别进度/错误事件产生后不立即上屏，
 * 入队 FOLLOW_DELAY_MS 后才下发给阅读页（当前句高亮跟随、错字拼音标红）。
 * 1200ms 的取值：朗读一句约 2–4 秒，延迟约半句——跟得上、不抢拍；常量可调。
 *
 * 只有一个识读模式：亮屏给视觉提示；本类不感知屏幕状态。熄屏时事件照常入队、
 * 统计照常落盘（下发到 WebView 的调用在页面不可见时无副作用）。
 * 不确定判读（aligner 的 onUncertain）只记日志、永不上屏、永不落盘为错误。
 */
public final class ReciteSession {

    /** 跟随延迟（毫秒）：事件产生后多久才上屏。可调常量。 */
    public static final long FOLLOW_DELAY_MS = 1200;

    public interface Listener {
        /** 延迟后下发：阅读页应跟随到的句（flat 下标） */
        void onDisplaySentence(int flat);

        /** 延迟后下发：某句某字读错，其拼音标红（flat＋句内字序号） */
        void onErrorChar(int flat, int ci);

        /** 会话状态：开始/停止/失败（失败带原因，如 "model" 模型未就绪） */
        void onState(boolean running, String error);
    }

    private final Context ctx;
    private final ReciteData data;
    private final ReciteStats stats;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean running = false;
    private Thread worker;
    private AudioRecord audio;
    private ReciteAligner aligner;

    // 延迟下发队列（主线程访问）：每项 {applyAtMs, seq, kind, flat, ci}
    // kind: 0 跟随句，1 错字标红
    private final List<long[]> pending = new ArrayList<>();
    private long seq = 0;
    private int lastProgressFlat = -1;

    private final Runnable applyPump = new Runnable() {
        @Override
        public void run() {
            if (!running && pending.isEmpty()) return;
            long now = System.currentTimeMillis();
            // 按 seq 顺序下发已到期的事件
            while (true) {
                long[] due = null;
                for (long[] e : pending) {
                    if (e[0] <= now && (due == null || e[1] < due[1])) due = e;
                }
                if (due == null) break;
                pending.remove(due);
                if (due[2] == 0) listener.onDisplaySentence((int) due[3]);
                else listener.onErrorChar((int) due[3], (int) due[4]);
            }
            if (running || !pending.isEmpty()) main.postDelayed(this, 60);
        }
    };

    public ReciteSession(Context ctx, ReciteData data, ReciteStats stats, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.data = data;
        this.stats = stats;
        this.listener = listener;
    }

    public boolean isRunning() {
        return running;
    }

    /** 从某句开始识读（阅读页当前句/点句跳转时用） */
    public void startAtSentence(int flat) {
        if (aligner != null) aligner.startAtSentence(flat);
        synchronized (pending) {
            pending.clear();
        }
        lastProgressFlat = flat;
    }

    public void start(int fromFlat) {
        if (running) return;
        if (!AsrModelStore.isReady(ctx)) {
            listener.onState(false, "model");
            return;
        }
        running = true;
        aligner = new ReciteAligner(data, new ReciteAligner.Listener() {
            @Override
            public void onProgress(int unitIdx) {
                int flat = data.flatOfUnit(unitIdx);
                if (flat < 0 || flat == lastProgressFlat) return;
                lastProgressFlat = flat;
                enqueue(0, flat, -1);
            }

            @Override
            public void onError(ReciteAligner.ErrorEvent e) {
                handleError(e);
            }

            @Override
            public void onUncertain(String what) {
                android.util.Log.i("ShixiRecite", "uncertain: " + what);
            }
        });
        aligner.startAtSentence(fromFlat);
        lastProgressFlat = fromFlat;
        main.post(applyPump);
        worker = new Thread(this::runLoop, "recite-asr");
        worker.start();
        listener.onState(true, null);
    }

    public void stop() {
        if (!running) return;
        running = false;
        AudioRecord a = audio;
        if (a != null) {
            try {
                a.stop();
            } catch (Exception ignored) {
            }
        }
        Thread w = worker;
        if (w != null) {
            try {
                w.join(1500);
            } catch (InterruptedException ignored) {
            }
        }
        worker = null;
        synchronized (pending) {
            pending.clear();
        }
        listener.onState(false, null);
    }

    private void enqueue(int kind, int flat, int ci) {
        long[] e = {System.currentTimeMillis() + FOLLOW_DELAY_MS, seq++, kind, flat, ci};
        synchronized (pending) {
            pending.add(e);
        }
    }

    /**
     * 错误事件处理：统计立即落盘；显示延迟下发。
     * INS（多读）先过「回声过滤」：多出的音节若与所在位置期望音节相同，是对齐
     * 伪影（如段首 token 错位回声），不是真多读，不记不上屏。
     */
    private void handleError(ReciteAligner.ErrorEvent e) {
        if (e.type == ReciteAligner.ErrorEvent.INS) {
            if (e.unit >= 0 && e.unit < data.units.size()) {
                String heard = e.heard;
                String expHere = data.units.get(e.unit).py;
                String expNext = e.unit + 1 < data.units.size()
                        ? data.units.get(e.unit + 1).py : null;
                if (heard != null && (heard.equals(expHere) || heard.equals(expNext))) {
                    android.util.Log.i("ShixiRecite", "ins-echo skipped: " + heard
                            + " @unit " + e.unit);
                    return;
                }
            }
            if (e.unit < 0 && !data.units.isEmpty()
                    && data.units.get(0).py.equals(e.heard)) {
                return; // 段首回声（首字被对齐成 INS 的伪影）
            }
            stats.recordExtra(data.bookId, data, Math.max(0, e.unit), e.heard);
            return;
        }
        String type = e.type == ReciteAligner.ErrorEvent.SUB ? "sub" : "del";
        stats.recordError(data.bookId, data, e.unit, type, e.heard);
        if (e.unit >= 0 && e.unit < data.units.size()) {
            ReciteData.Unit u = data.units.get(e.unit);
            enqueue(1, u.flat, u.ci);
        }
    }

    // ---------- 采集＋解码（worker 线程） ----------

    private void runLoop() {
        OnlineRecognizer recognizer = null;
        OnlineStream stream = null;
        try {
            File dir = AsrModelStore.dir(ctx);
            OnlineTransducerModelConfig transducer = OnlineTransducerModelConfig.builder()
                    .setEncoder(new File(dir, AsrModelStore.FILES[0].name).getAbsolutePath())
                    .setDecoder(new File(dir, AsrModelStore.FILES[1].name).getAbsolutePath())
                    .setJoiner(new File(dir, AsrModelStore.FILES[2].name).getAbsolutePath())
                    .build();
            OnlineModelConfig modelConfig = OnlineModelConfig.builder()
                    .setTransducer(transducer)
                    .setTokens(new File(dir, AsrModelStore.FILES[3].name).getAbsolutePath())
                    .setNumThreads(2)
                    .setDebug(false)
                    .build();
            EndpointConfig endpoint = EndpointConfig.builder()
                    .setRule1(EndpointRule.builder().setMustContainNonSilence(false)
                            .setMinTrailingSilence(2.4f).setMinUtteranceLength(0).build())
                    .setRule2(EndpointRule.builder().setMustContainNonSilence(true)
                            .setMinTrailingSilence(1.2f).setMinUtteranceLength(0).build())
                    .setRule3(EndpointRule.builder().setMustContainNonSilence(false)
                            .setMinTrailingSilence(0).setMinUtteranceLength(300).build())
                    .build();
            OnlineRecognizerConfig config = OnlineRecognizerConfig.builder()
                    .setFeatureConfig(FeatureConfig.builder()
                            .setSampleRate(16000).setFeatureDim(80).build())
                    .setOnlineModelConfig(modelConfig)
                    .setEndpointConfig(endpoint)
                    .setEnableEndpoint(true)
                    .setDecodingMethod("greedy_search")
                    .build();
            recognizer = new OnlineRecognizer(config);
            stream = recognizer.createStream();

            int minBuf = AudioRecord.getMinBufferSize(16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bufSize = Math.max(minBuf * 2, 1600 * 2 * 4);
            AudioRecord rec = null;
            try {
                rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        16000, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, bufSize);
                if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                    rec.release();
                    rec = new AudioRecord(MediaRecorder.AudioSource.MIC,
                            16000, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, bufSize);
                }
            } catch (Exception e) {
                rec = new AudioRecord(MediaRecorder.AudioSource.MIC,
                        16000, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, bufSize);
            }
            audio = rec;
            rec.startRecording();

            short[] shorts = new short[1600]; // 100ms @16k
            float[] floats = new float[1600];
            String lastText = "";
            while (running) {
                int n = rec.read(shorts, 0, shorts.length);
                if (n <= 0) {
                    if (!running) break;
                    continue;
                }
                for (int i = 0; i < n; i++) floats[i] = shorts[i] / 32768f;
                float[] seg = n == floats.length ? floats
                        : java.util.Arrays.copyOf(floats, n);
                stream.acceptWaveform(seg, 16000);
                while (recognizer.isReady(stream)) recognizer.decode(stream);
                OnlineRecognizerResult res = recognizer.getResult(stream);
                String text = res.getText();
                if (!text.equals(lastText)) {
                    lastText = text;
                    aligner.updateSegmentTimed(tokTexts(res), tokTimes(res), false);
                }
                if (recognizer.isEndpoint(stream)) {
                    if (!text.isEmpty()) {
                        aligner.updateSegmentTimed(tokTexts(res), tokTimes(res), true);
                    }
                    recognizer.reset(stream);
                    lastText = "";
                }
            }
            // 收尾：把最后一段未端点的结果按 final 提交一次
            try {
                stream.inputFinished();
                while (recognizer.isReady(stream)) recognizer.decode(stream);
                OnlineRecognizerResult tail = recognizer.getResult(stream);
                if (tail.getText() != null && !tail.getText().isEmpty()
                        && !tail.getText().equals(lastText)) {
                    aligner.updateSegmentTimed(tokTexts(tail), tokTimes(tail), true);
                }
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            android.util.Log.e("ShixiRecite", "recite loop failed", e);
            if (running) {
                running = false;
                main.post(() -> listener.onState(false, "asr:" + e.getMessage()));
            }
        } finally {
            if (audio != null) {
                try {
                    audio.release();
                } catch (Exception ignored) {
                }
                audio = null;
            }
            if (stream != null) {
                try {
                    stream.release();
                } catch (Exception ignored) {
                }
            }
            if (recognizer != null) {
                try {
                    recognizer.release();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static List<String> tokTexts(OnlineRecognizerResult r) {
        return new ArrayList<>(java.util.Arrays.asList(r.getTokens()));
    }

    private static List<Float> tokTimes(OnlineRecognizerResult r) {
        float[] ts = r.getTimestamps();
        List<Float> out = new ArrayList<>(ts.length);
        for (float f : ts) out.add(f);
        return out;
    }
}
