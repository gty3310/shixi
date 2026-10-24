import com.k2fsa.sherpa.onnx.EndpointConfig;
import com.k2fsa.sherpa.onnx.EndpointRule;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.LibraryLoader;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import com.ti.songdu.recite.ReciteAligner;
import com.ti.songdu.recite.ReciteData;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 桌面自测驱动（不进 APK）：
 *  unit —— 对齐器合成用例（完美读/替换/漏读/多读/同音字/半途停读）
 *  e2e  —— 真实 vendored Java API + Linux JNI 解码真实诵读音频 → 对齐 → 事件
 * 用法：
 *  unit
 *  e2e <sentences.tsv> <wav16k> <modelDir> <jniSoPath>
 */
public class ReciteTestMain {

    // ---------- 通用 ----------
    static ReciteData.SentIn sent(String id, String t, String py, String bps) {
        ReciteData.SentIn s = new ReciteData.SentIn();
        s.id = id;
        s.textT = t;
        s.textS = t;
        s.pinyin = Arrays.asList(py.split(" "));
        if (bps != null && !bps.isEmpty()) {
            String[] parts = bps.split(",");
            s.breakPos = new int[parts.length];
            for (int i = 0; i < parts.length; i++) s.breakPos[i] = Integer.parseInt(parts[i]);
        }
        return s;
    }

    static final class Collect implements ReciteAligner.Listener {
        final List<ReciteAligner.ErrorEvent> errors = new ArrayList<>();
        final List<String> uncertains = new ArrayList<>();
        int lastProgress = -1;
        boolean verbose = false;
        String phase = "";
        public void onError(ReciteAligner.ErrorEvent e) {
            errors.add(e);
            if (verbose) System.out.println("    >> ERR " + phase + " type=" + e.type
                    + " unit=" + e.unit + " heard=" + e.heard);
        }
        public void onProgress(int u) { lastProgress = u; }
        public void onUncertain(String n) {
            uncertains.add(n);
            if (verbose) System.out.println("    >> UNC " + phase + " " + n);
        }
    }

    static int failures = 0;
    static void check(String name, boolean cond, String detail) {
        System.out.println((cond ? "PASS " : "FAIL ") + name + "  " + detail);
        if (!cond) failures++;
    }

    // ---------- unit ----------
    static void unit() {
        // 《大学》首句：16 字，breaks 在 ，处（UTF-16 下标 4/9/13）
        ReciteData.SentIn s1 = sent("s1", "大学之道，在明明德，在亲民，在止于至善。",
                "dà xué zhī dào zài míng míng dé zài qīn mín zài zhǐ yú zhì shàn", "4,9,13");
        ReciteData data = ReciteData.build("daxue", Arrays.asList(s1));
        System.out.println("units=" + data.units.size()
                + " clauses=" + data.clausesBySent.get(0).size()
                + " clause0=\"" + data.clausesBySent.get(0).get(0).text + "\""
                + " clause1=\"" + data.clausesBySent.get(0).get(1).text + "\"");

        // 1) 完美读
        {
            Collect c = new Collect();
            ReciteAligner a = new ReciteAligner(data, c);
            a.startAtSentence(0);
            a.updateSegment("大学之道在明明德在亲民在止于至善", true);
            check("perfect", c.errors.isEmpty() && a.pointer() == 16,
                    "errors=" + c.errors.size() + " ptr=" + a.pointer());
        }
        // 2) 替换：明明(ming) 读成 零零(ling) —— ASR 实际输出用简体 零
        {
            Collect c = new Collect();
            ReciteAligner a = new ReciteAligner(data, c);
            a.startAtSentence(0);
            a.updateSegment("大学之道在零零德在亲民在止于至善", true);
            long subs = c.errors.stream().filter(e -> e.type == ReciteAligner.ErrorEvent.SUB).count();
            check("sub-ming->ling", subs == 2 && c.errors.size() == 2,
                    "errors=" + describe(c, data));
        }
        // 3) 漏读：漏一个 明
        {
            Collect c = new Collect();
            ReciteAligner a = new ReciteAligner(data, c);
            a.startAtSentence(0);
            a.updateSegment("大学之道在明德在亲民在止于至善", true);
            check("del-one-ming", c.errors.size() == 1
                            && c.errors.get(0).type == ReciteAligner.ErrorEvent.DEL,
                    "errors=" + describe(c, data));
        }
        // 4) 多读：句中插入一个 啊
        {
            Collect c = new Collect();
            ReciteAligner a = new ReciteAligner(data, c);
            a.startAtSentence(0);
            a.updateSegment("大学之道啊在明明德在亲民在止于至善", true);
            boolean hasIns = c.errors.stream().anyMatch(e -> e.type == ReciteAligner.ErrorEvent.INS);
            boolean noSubDel = c.errors.stream().allMatch(e -> e.type == ReciteAligner.ErrorEvent.INS);
            check("ins-extra", hasIns && noSubDel, "errors=" + describe(c, data));
        }
        // 5) 同音字互换：德(de) → 得(de)，不算错
        {
            Collect c = new Collect();
            ReciteAligner a = new ReciteAligner(data, c);
            a.startAtSentence(0);
            a.updateSegment("大学之道在明明得在亲民在止于至善", true);
            check("homophone-ok", c.errors.isEmpty(), "errors=" + describe(c, data));
        }
        // 6) 半途停读即终局：未读部分不许报漏读
        {
            Collect c = new Collect();
            ReciteAligner a = new ReciteAligner(data, c);
            a.startAtSentence(0);
            a.updateSegment("大学之道在明明德", true);
            check("frontier-no-del", c.errors.isEmpty(), "errors=" + describe(c, data)
                    + " uncertains=" + c.uncertains);
        }
        // 7) partial 逐步喂入（模拟流式修订），最后终局：完美读不应在中途误报
        {
            Collect c = new Collect();
            ReciteAligner a = new ReciteAligner(data, c);
            a.startAtSentence(0);
            String full = "大学之道在明明德在亲民在止于至善";
            for (int i = 4; i < full.length(); i += 3) {
                a.updateSegment(full.substring(0, Math.min(i, full.length())), false);
            }
            a.updateSegment(full, true);
            check("streaming-partials", c.errors.isEmpty() && a.pointer() == 16,
                    "errors=" + describe(c, data) + " ptr=" + a.pointer());
        }
        System.out.println(failures == 0 ? "ALL UNIT PASS" : failures + " UNIT FAILURES");
    }

    static String describe(Collect c, ReciteData d) {
        StringBuilder sb = new StringBuilder();
        for (ReciteAligner.ErrorEvent e : c.errors) {
            if (sb.length() > 0) sb.append("; ");
            String type = e.type == 1 ? "SUB" : e.type == 2 ? "DEL" : "INS";
            String ctx = "";
            if (e.unit >= 0 && e.unit < d.units.size()) {
                ReciteData.Unit u = d.units.get(e.unit);
                ReciteData.Clause cl = d.clauseOf(u.flat, u.clause);
                ctx = u.hanzi + "(" + u.py + ")@" + (cl != null ? cl.text : "?");
            }
            sb.append(type).append(" unit=").append(e.unit).append(" ").append(ctx)
                    .append(" heard=").append(e.heard);
        }
        return sb.toString();
    }

    // ---------- e2e ----------
    static ReciteData loadTsv(String path) throws Exception {
        List<ReciteData.SentIn> sents = new ArrayList<>();
        BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(path), StandardCharsets.UTF_8));
        String line;
        while ((line = br.readLine()) != null) {
            if (line.isEmpty()) continue;
            String[] f = line.split("\t", -1);
            ReciteData.SentIn s = new ReciteData.SentIn();
            s.id = f[0];
            s.textT = f[1];
            s.textS = f[2];
            s.pinyin = Arrays.asList(f[3].split(" "));
            if (f.length > 4 && !f[4].isEmpty()) {
                String[] parts = f[4].split(",");
                s.breakPos = new int[parts.length];
                for (int i = 0; i < parts.length; i++) s.breakPos[i] = Integer.parseInt(parts[i]);
            }
            sents.add(s);
        }
        br.close();
        return ReciteData.build("daxue", sents);
    }

    static float[] readWav16k(String path) throws Exception {
        byte[] all = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path));
        // 极简 WAV 解析：找 "data" chunk（ffmpeg 输出的 PCM16 wav 头为 44 字节或带 LIST）
        int off = -1;
        for (int i = 0; i + 4 < all.length; i++) {
            if (all[i] == 'd' && all[i + 1] == 'a' && all[i + 2] == 't' && all[i + 3] == 'a') { off = i + 8; break; }
        }
        if (off < 0) throw new RuntimeException("no data chunk");
        int n = (all.length - off) / 2;
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            int v = (all[off + 2 * i] & 0xff) | (all[off + 2 * i + 1] << 8);
            out[i] = v / 32768f;
        }
        return out;
    }

    static void e2e(String tsv, String wav, String modelDir, String jni) throws Exception {
        ReciteData data = loadTsv(tsv);
        System.out.println("book units=" + data.units.size() + " sentences=" + data.sentUnitStart.size());

        LibraryLoader.setAutoLoadEnabled(false);
        System.load(jni);

        OnlineTransducerModelConfig transducer = OnlineTransducerModelConfig.builder()
                .setEncoder(modelDir + "/encoder-epoch-99-avg-1.int8.onnx")
                .setDecoder(modelDir + "/decoder-epoch-99-avg-1.int8.onnx")
                .setJoiner(modelDir + "/joiner-epoch-99-avg-1.int8.onnx")
                .build();
        OnlineModelConfig modelConfig = OnlineModelConfig.builder()
                .setTransducer(transducer)
                .setTokens(modelDir + "/tokens.txt")
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
                .setFeatureConfig(FeatureConfig.builder().setSampleRate(16000).setFeatureDim(80).build())
                .setOnlineModelConfig(modelConfig)
                .setEndpointConfig(endpoint)
                .setEnableEndpoint(true)
                .setDecodingMethod("greedy_search")
                .build();
        OnlineRecognizer recognizer = new OnlineRecognizer(config);
        OnlineStream stream = recognizer.createStream();

        Collect c = new Collect();
        c.verbose = Boolean.getBoolean("recite.verbose");
        ReciteAligner aligner = new ReciteAligner(data, c);
        aligner.startAtSentence(0);

        float[] samples = readWav16k(wav);
        int chunk = 1600; // 100ms @16k
        String lastText = "";
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < samples.length; i += chunk) {
            float[] seg = Arrays.copyOfRange(samples, i, Math.min(samples.length, i + chunk));
            stream.acceptWaveform(seg, 16000);
            while (recognizer.isReady(stream)) recognizer.decode(stream);
            com.k2fsa.sherpa.onnx.OnlineRecognizerResult res = recognizer.getResult(stream);
            String text = res.getText();
            if (!text.equals(lastText)) {
                lastText = text;
                c.phase = "[partial \"" + text + "\"]";
                aligner.updateSegmentTimed(tokTexts(res), tokTimes(res), false);
                if (c.verbose) System.out.println("    ptr=" + aligner.pointer());
            }
            if (recognizer.isEndpoint(stream)) {
                if (!text.isEmpty()) {
                    System.out.println("SEGMENT(final): " + text);
                    c.phase = "[final]";
                    aligner.updateSegmentTimed(tokTexts(res), tokTimes(res), true);
                    if (c.verbose) System.out.println("    ptr=" + aligner.pointer());
                }
                recognizer.reset(stream);
                lastText = "";
            }
        }
        stream.inputFinished();
        while (recognizer.isReady(stream)) recognizer.decode(stream);
        com.k2fsa.sherpa.onnx.OnlineRecognizerResult tailRes = recognizer.getResult(stream);
        String tail = tailRes.getText();
        if (!tail.isEmpty()) {
            System.out.println("SEGMENT(tail): " + tail);
            aligner.updateSegmentTimed(tokTexts(tailRes), tokTimes(tailRes), true);
        }
        long ms = System.currentTimeMillis() - t0;
        System.out.println("audio " + (samples.length / 16000.0) + "s processed in " + ms + "ms");
        System.out.println("final ptr=" + aligner.pointer() + " / " + data.units.size());
        System.out.println("ERRORS(" + c.errors.size() + "):");
        for (ReciteAligner.ErrorEvent e : c.errors) {
            String type = e.type == 1 ? "SUB" : e.type == 2 ? "DEL" : "INS";
            String ctx = "";
            if (e.unit >= 0 && e.unit < data.units.size()) {
                ReciteData.Unit u = data.units.get(e.unit);
                ReciteData.Clause cl = data.clauseOf(u.flat, u.clause);
                ctx = " sent=" + u.flat + " char=" + u.hanzi + " py=" + u.pyToned
                        + " clause=\"" + (cl != null ? cl.text : "?") + "\"";
            }
            System.out.println("  " + type + " unit=" + e.unit + ctx + " heard=" + e.heard);
        }
        System.out.println("UNCERTAIN(" + c.uncertains.size() + "): " + c.uncertains);
        recognizer.release();
    }

    static java.util.List<String> tokTexts(com.k2fsa.sherpa.onnx.OnlineRecognizerResult r) {
        return new java.util.ArrayList<>(java.util.Arrays.asList(r.getTokens()));
    }

    static java.util.List<Float> tokTimes(com.k2fsa.sherpa.onnx.OnlineRecognizerResult r) {
        float[] ts = r.getTimestamps();
        java.util.List<Float> out = new java.util.ArrayList<>(ts.length);
        for (float f : ts) out.add(f);
        return out;
    }

    static void dbg(String tsv) throws Exception {
        ReciteData data = loadTsv(tsv);
        Collect c = new Collect();
        ReciteAligner a = new ReciteAligner(data, c);
        a.startAtSentence(0);
        String[] partials = {"学大学之道在", "学大学之道在零零", "学大学之道在零零地",
                "学大学之道在零零地在", "学大学之道在零零地在清"};
        for (String p : partials) {
            System.out.println("IN: " + p + "  ptr=" + a.pointer());
            System.out.println("  ops: " + a.debugAlign(p));
            a.updateSegment(p, false);
            System.out.println("  -> ptr=" + a.pointer() + " errors=" + c.errors.size());
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length >= 2 && args[0].equals("dbg")) {
            dbg(args[1]);
        } else if (args.length >= 1 && args[0].equals("unit")) {
            unit();
            if (failures > 0) System.exit(1);
        } else if (args.length >= 5 && args[0].equals("e2e")) {
            e2e(args[1], args[2], args[3], args[4]);
        } else {
            System.out.println("usage: unit | e2e <tsv> <wav> <modelDir> <jniSo>");
            System.exit(2);
        }
    }
}
