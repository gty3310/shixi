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
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * v1.18.0 识读会话（双引擎版）：麦克风采集 → 按停顿分块（单块 ≤25s，
 * 规格上限 30s）→ 主引擎识别＋伪 partial 递进喂对齐器 → 副引擎（在位时）
 * 对同一块音频复核 → ReciteJudge 加权判定 → v2 统计落盘＋延迟下发显示。
 *
 * 喂法口径（实测报告定死）：离线大模型整段直喂会静默丢内容，必须分块；
 * 只喂 final 会触发段首 DEL 锚点卡死，故每块都用伪 partial 递进喂入
 * （在线小模型则用真实 partial）。跟随延迟沿用 FOLLOW_DELAY_MS=1200。
 *
 * 判定口径：只有「确定错」才标红上屏；存疑各档只落盘供复习页分档呈现；
 * DEL/INS 不计分单列。双引擎都在位时副引擎在每块提交时批量复核产出
 * C 证据（同字同错音 +2——全公式最强证据项）；单引擎时 C=0 照常工作。
 */
public final class ReciteSession {

    public static final long FOLLOW_DELAY_MS = 1200;

    public interface Listener {
        void onDisplaySentence(int flat);
        void onErrorChar(int flat, int ci);
        void onState(boolean running, String error);
    }

    private static final int SR = 16000;
    private static final int FRAME = 1600;            // 100ms
    private static final double SPEECH_RMS = 0.008;   // 有声帧阈值
    private static final int TRAIL_SIL_FRAMES = 8;    // 0.8s 停顿切块
    private static final int MAX_CHUNK_FRAMES = 250;  // 25s 上限（规格 ≤30s）

    private final Context ctx;
    private final ReciteData data;
    private final ReciteStatsV2 stats;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean running = false;
    private Thread captureThread, decodeThread;
    private AudioRecord audio;
    private ReciteAligner alignerP, alignerS;
    private boolean primaryAdvanced, secondaryPresent, secondaryAdvanced;
    private String primaryTag, secondaryTag;

    private final LinkedBlockingQueue<float[]> chunkQueue = new LinkedBlockingQueue<>();
    private static final float[] POISON = new float[0];

    // 待判定 SUB（主引擎）与副引擎 SUB 复核表
    private final List<ReciteAligner.ErrorEvent> pendingSubs = new ArrayList<>();
    private final List<ReciteAligner.ErrorEvent> secondarySubs = new ArrayList<>();
    private final Map<String, Integer> clauseCountP = new HashMap<>();
    private final Map<String, Integer> clauseCountS = new HashMap<>();

    // 延迟下发队列（主线程访问）
    private final List<long[]> pending = new ArrayList<>();
    private long seq = 0;
    private int lastProgressFlat = -1;

    private final Runnable applyPump = new Runnable() {
        @Override public void run() {
            if (!running && pending.isEmpty()) return;
            long now = System.currentTimeMillis();
            while (true) {
                long[] due = null;
                synchronized (pending) {
                    for (long[] e : pending) {
                        if (e[0] <= now && (due == null || e[1] < due[1])) due = e;
                    }
                    if (due == null) break;
                    pending.remove(due);
                }
                if (due[2] == 0) listener.onDisplaySentence((int) due[3]);
                else listener.onErrorChar((int) due[3], (int) due[4]);
            }
            if (running || !pending.isEmpty()) main.postDelayed(this, 60);
        }
    };

    public ReciteSession(Context ctx, ReciteData data, ReciteStatsV2 stats, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.data = data;
        this.stats = stats;
        this.listener = listener;
    }

    public boolean isRunning() { return running; }

    public void startAtSentence(int flat) {
        if (alignerP != null) alignerP.startAtSentence(flat);
        if (alignerS != null) alignerS.startAtSentence(flat);
        synchronized (pendingSubs) { pendingSubs.clear(); }
        synchronized (secondarySubs) { secondarySubs.clear(); }
        synchronized (pending) { pending.clear(); }
        lastProgressFlat = flat;
    }

    public void start(int fromFlat) {
        if (running) return;
        primaryAdvanced = RecitePrefs.isAdvanced(ctx);
        if (primaryAdvanced ? !AsrModelStore.isSenseVoiceReady(ctx)
                : !AsrModelStore.isReady(ctx)) {
            listener.onState(false, "model");
            return;
        }
        secondaryPresent = primaryAdvanced ? AsrModelStore.isReady(ctx)
                : AsrModelStore.isSenseVoiceReady(ctx);
        secondaryAdvanced = !primaryAdvanced;
        primaryTag = primaryAdvanced ? "advanced" : "basic";
        secondaryTag = secondaryAdvanced ? "advanced" : "basic";
        running = true;
        alignerP = newAligner(true);
        alignerS = secondaryPresent ? newAligner(false) : null;
        alignerP.startAtSentence(fromFlat);
        if (alignerS != null) alignerS.startAtSentence(fromFlat);
        lastProgressFlat = fromFlat;
        main.post(applyPump);
        decodeThread = new Thread(this::decodeLoop, "recite-decode");
        decodeThread.start();
        captureThread = new Thread(this::captureLoop, "recite-capture");
        captureThread.start();
        listener.onState(true, null);
    }

    public void stop() {
        if (!running) return;
        running = false;
        AudioRecord a = audio;
        if (a != null) {
            try { a.stop(); } catch (Exception ignored) {}
        }
        if (captureThread != null) {
            try { captureThread.join(1200); } catch (InterruptedException ignored) {}
        }
        chunkQueue.offer(POISON);
        if (decodeThread != null) {
            try { decodeThread.join(4000); } catch (InterruptedException ignored) {}
        }
        captureThread = null; decodeThread = null;
        synchronized (pending) { pending.clear(); }
        listener.onState(false, null);
    }

    private ReciteAligner newAligner(final boolean primary) {
        return new ReciteAligner(data, new ReciteAligner.Listener() {
            @Override public void onProgress(int unitIdx) {
                if (!primary) return;
                int flat = data.flatOfUnit(unitIdx);
                if (flat < 0 || flat == lastProgressFlat) return;
                lastProgressFlat = flat;
                enqueue(0, flat, -1);
            }
            @Override public void onError(ReciteAligner.ErrorEvent e) {
                if (primary) handlePrimaryError(e);
                else if (e.type == ReciteAligner.ErrorEvent.SUB) {
                    synchronized (secondarySubs) { secondarySubs.add(copy(e)); }
                    bump(clauseCountS, e.unit);
                }
            }
            @Override public void onUncertain(String what) {
                android.util.Log.i("ShixiRecite", (primary ? "P " : "S ") + "uncertain: " + what);
            }
        });
    }

    private static ReciteAligner.ErrorEvent copy(ReciteAligner.ErrorEvent e) {
        ReciteAligner.ErrorEvent c = new ReciteAligner.ErrorEvent();
        c.type = e.type; c.unit = e.unit; c.heard = e.heard;
        return c;
    }

    private void bump(Map<String, Integer> map, int unit) {
        if (unit < 0 || unit >= data.units.size()) return;
        ReciteData.Unit u = data.units.get(unit);
        String key = String.valueOf(u.flat); // 雪崩按句计（加权报告实算口径：s11/s13 整句计）
        synchronized (map) {
            Integer v = map.get(key);
            map.put(key, v == null ? 1 : v + 1);
        }
    }

    private int clauseCount(Map<String, Integer> map, int unit) {
        if (unit < 0 || unit >= data.units.size()) return 0;
        ReciteData.Unit u = data.units.get(unit);
        synchronized (map) {
            Integer v = map.get(String.valueOf(u.flat));
            return v == null ? 0 : v;
        }
    }

    private void handlePrimaryError(ReciteAligner.ErrorEvent e) {
        if (e.type == ReciteAligner.ErrorEvent.SUB) {
            synchronized (pendingSubs) { pendingSubs.add(copy(e)); }
            bump(clauseCountP, e.unit);
            return;
        }
        if (e.type == ReciteAligner.ErrorEvent.DEL) {
            stats.recordDel(data.bookId, data, e.unit);
            return;
        }
        // INS：回声过滤（沿 v1.16.0 口径）
        if (e.unit >= 0 && e.unit < data.units.size()) {
            String heard = e.heard;
            String expHere = data.units.get(e.unit).py;
            String expNext = e.unit + 1 < data.units.size()
                    ? data.units.get(e.unit + 1).py : null;
            if (heard != null && (heard.equals(expHere) || heard.equals(expNext))) return;
        }
        if (e.unit < 0 && !data.units.isEmpty() && data.units.get(0).py.equals(e.heard)) return;
        stats.recordExtra(data.bookId, data, Math.max(0, e.unit), e.heard);
    }

    /** 每块两引擎都喂完后批量判定：主引擎 SUB 逐条匹配副引擎复核产出 C */
    private void scorePending() {
        List<ReciteAligner.ErrorEvent> mains, secs;
        synchronized (pendingSubs) { mains = new ArrayList<>(pendingSubs); pendingSubs.clear(); }
        synchronized (secondarySubs) { secs = new ArrayList<>(secondarySubs); secondarySubs.clear(); }
        Map<Integer, String> secByUnit = new HashMap<>();
        for (ReciteAligner.ErrorEvent s : secs) {
            if (!secByUnit.containsKey(s.unit)) secByUnit.put(s.unit, s.heard);
        }
        java.util.Set<Integer> mainUnits = new java.util.HashSet<>();
        for (ReciteAligner.ErrorEvent e : mains) {
            mainUnits.add(e.unit);
            judgeAndRecord(e, secByUnit.get(e.unit), clauseCount(clauseCountP, e.unit),
                    primaryTag + (secondaryPresent ? "+dual" : ""));
        }
        // 副引擎独报的 SUB：C=0 按单引擎判定（与 harness 双组同口径）
        for (ReciteAligner.ErrorEvent s : secs) {
            if (mainUnits.contains(s.unit)) continue;
            judgeAndRecord(s, null, clauseCount(clauseCountS, s.unit), secondaryTag);
        }
    }

    private void judgeAndRecord(ReciteAligner.ErrorEvent e, String otherHeard,
                                int clauseCount, String engines) {
        if (e.unit < 0 || e.unit >= data.units.size()) return;
        ReciteData.Unit u = data.units.get(e.unit);
        ReciteJudge.Result jr = ReciteJudge.judgeSub(u.hanzi, u.pyToned, e.heard,
                otherHeard, clauseCount);
        stats.recordJudged(data.bookId, data, e.unit, e.heard, jr, engines);
        if (jr.tier == ReciteJudge.TIER_CONFIRMED) {
            enqueue(1, u.flat, u.ci);
        }
    }

    private void enqueue(int kind, int flat, int ci) {
        long[] e = {System.currentTimeMillis() + FOLLOW_DELAY_MS, seq++, kind, flat, ci};
        synchronized (pending) { pending.add(e); }
    }

    // ---------- 采集：按停顿分块 ----------

    private void captureLoop() {
        AudioRecord rec = null;
        try {
            int minBuf = AudioRecord.getMinBufferSize(SR,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bufSize = Math.max(minBuf * 4, FRAME * 2 * 8);
            try {
                rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        SR, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, bufSize);
                if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                    rec.release();
                    rec = new AudioRecord(MediaRecorder.AudioSource.MIC,
                            SR, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, bufSize);
                }
            } catch (Exception e) {
                rec = new AudioRecord(MediaRecorder.AudioSource.MIC,
                        SR, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, bufSize);
            }
            audio = rec;
            rec.startRecording();
            short[] shorts = new short[FRAME];
            List<float[]> frames = new ArrayList<>();
            boolean hasSpeech = false;
            int trailSil = 0;
            while (running) {
                int n = rec.read(shorts, 0, shorts.length);
                if (n <= 0) continue;
                float[] f = new float[n];
                double sum = 0;
                for (int i = 0; i < n; i++) { f[i] = shorts[i] / 32768f; sum += f[i] * f[i]; }
                double rms = Math.sqrt(sum / Math.max(1, n));
                frames.add(f);
                if (rms > SPEECH_RMS) { hasSpeech = true; trailSil = 0; }
                else if (hasSpeech) trailSil++;
                if ((hasSpeech && trailSil >= TRAIL_SIL_FRAMES)
                        || frames.size() >= MAX_CHUNK_FRAMES) {
                    emitChunk(frames);
                    frames = new ArrayList<>();
                    hasSpeech = false; trailSil = 0;
                }
            }
            if (hasSpeech && !frames.isEmpty()) emitChunk(frames);
        } catch (Exception e) {
            android.util.Log.e("ShixiRecite", "capture failed", e);
            if (running) {
                running = false;
                main.post(() -> listener.onState(false, "mic:" + e.getMessage()));
            }
        } finally {
            if (rec != null) {
                try { rec.release(); } catch (Exception ignored) {}
            }
            audio = null;
        }
    }

    private void emitChunk(List<float[]> frames) {
        int total = 0;
        for (float[] f : frames) total += f.length;
        if (total < SR / 5) return; // 短于 0.2s 的碎块不送识别
        float[] chunk = new float[total];
        int off = 0;
        for (float[] f : frames) {
            System.arraycopy(f, 0, chunk, off, f.length);
            off += f.length;
        }
        chunkQueue.offer(chunk);
    }

    // ---------- 解码：双引擎逐块 ----------

    private OnlineRecognizer onlineRec;
    private OfflineRecognizer offlineRec;

    private void decodeLoop() {
        try {
            boolean needOnline = !primaryAdvanced || secondaryPresent && !secondaryAdvanced;
            boolean needOffline = primaryAdvanced || secondaryPresent && secondaryAdvanced;
            if (needOnline) onlineRec = buildOnline();
            if (needOffline) offlineRec = buildOffline();
            while (true) {
                float[] chunk = chunkQueue.take();
                if (chunk == POISON) break;
                try {
                    decodeChunk(chunk, primaryAdvanced, alignerP, onlineRec, offlineRec);
                    if (secondaryPresent) {
                        decodeChunk(chunk, secondaryAdvanced, alignerS, onlineRec, offlineRec);
                    }
                    scorePending();
                } catch (Exception e) {
                    android.util.Log.e("ShixiRecite", "decode chunk failed", e);
                }
            }
            scorePending();
        } catch (Exception e) {
            android.util.Log.e("ShixiRecite", "recite loop failed", e);
            if (running) {
                running = false;
                main.post(() -> listener.onState(false, "asr:" + e.getMessage()));
            }
        } finally {
            if (onlineRec != null) { try { onlineRec.release(); } catch (Exception ignored) {} onlineRec = null; }
            if (offlineRec != null) { try { offlineRec.release(); } catch (Exception ignored) {} offlineRec = null; }
        }
    }

    private void decodeChunk(float[] chunk, boolean advanced, ReciteAligner aligner,
                             OnlineRecognizer online, OfflineRecognizer offline) throws Exception {
        if (advanced) {
            OfflineStream s = offline.createStream();
            try {
                s.acceptWaveform(chunk, SR);
                offline.decode(s);
                OfflineRecognizerResult r = offline.getResult(s);
                feedPseudoPartial(aligner, r);
            } finally {
                try { s.release(); } catch (Exception ignored) {}
            }
        } else {
            OnlineStream s = online.createStream();
            try {
                String last = "";
                for (int off = 0; off < chunk.length; off += FRAME) {
                    int n = Math.min(FRAME, chunk.length - off);
                    float[] seg = new float[n];
                    System.arraycopy(chunk, off, seg, 0, n);
                    s.acceptWaveform(seg, SR);
                    while (online.isReady(s)) online.decode(s);
                    OnlineRecognizerResult r = online.getResult(s);
                    String text = r.getText();
                    if (text != null && !text.equals(last)) {
                        last = text;
                        aligner.updateSegmentTimed(tokTexts(r), tokTimes(r), false);
                    }
                }
                s.inputFinished();
                while (online.isReady(s)) online.decode(s);
                OnlineRecognizerResult tail = online.getResult(s);
                if (tail.getText() != null && !tail.getText().isEmpty()) {
                    aligner.updateSegmentTimed(tokTexts(tail), tokTimes(tail), true);
                } else if (!last.isEmpty()) {
                    aligner.updateSegment(last, true);
                }
            } finally {
                try { s.release(); } catch (Exception ignored) {}
            }
        }
    }

    /** 离线块伪 partial：按 token 递进分三步喂，最后一步 final（实测口径） */
    private void feedPseudoPartial(ReciteAligner aligner, OfflineRecognizerResult r) {
        if (r == null || r.getText() == null || r.getText().isEmpty()) return;
        String[] tokens = r.getTokens();
        float[] ts = r.getTimestamps();
        if (tokens == null || tokens.length == 0) {
            aligner.updateSegment(r.getText(), true);
            return;
        }
        List<String> texts = new ArrayList<>();
        List<Float> times = new ArrayList<>();
        for (int i = 0; i < tokens.length; i++) {
            texts.add(tokens[i]);
            times.add(ts != null && i < ts.length ? ts[i] : Float.NaN);
        }
        int n = texts.size();
        int[] cuts = {Math.max(1, n / 3), Math.max(1, 2 * n / 3), n};
        int prev = 0;
        for (int cut : cuts) {
            if (cut <= prev) continue;
            prev = cut;
            boolean fin = cut == n;
            aligner.updateSegmentTimed(texts.subList(0, cut), times.subList(0, cut), fin);
        }
    }

    private OnlineRecognizer buildOnline() {
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
                        .setSampleRate(SR).setFeatureDim(80).build())
                .setOnlineModelConfig(modelConfig)
                .setEndpointConfig(endpoint)
                .setEnableEndpoint(true)
                .setDecodingMethod("greedy_search")
                .build();
        return new OnlineRecognizer(config);
    }

    private OfflineRecognizer buildOffline() {
        File dir = AsrModelStore.dir(ctx);
        OfflineSenseVoiceModelConfig sv = OfflineSenseVoiceModelConfig.builder()
                .setModel(new File(dir, AsrModelStore.SV_MODEL_NAME).getAbsolutePath())
                .setLanguage("zh")
                .setInverseTextNormalization(false)
                .build();
        OfflineModelConfig modelConfig = OfflineModelConfig.builder()
                .setSenseVoice(sv)
                .setTokens(new File(dir, AsrModelStore.SV_TOKENS_NAME).getAbsolutePath())
                .setNumThreads(2)
                .setDebug(false)
                .build();
        OfflineRecognizerConfig config = OfflineRecognizerConfig.builder()
                .setFeatureConfig(FeatureConfig.builder()
                        .setSampleRate(SR).setFeatureDim(80).build())
                .setOfflineModelConfig(modelConfig)
                .setDecodingMethod("greedy_search")
                .build();
        return new OfflineRecognizer(config);
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
