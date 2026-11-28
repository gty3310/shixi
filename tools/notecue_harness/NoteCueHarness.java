import com.ti.songdu.NoteCuePlan;
import com.ti.songdu.NoteCueTracker;
import com.ti.songdu.ZipExtract;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * v1.31.0 念注框架桌面 harness：直驱 NoteCuePlan / NoteCueTracker /
 * ZipExtract 本体（引擎已拍板为云端预生成包，原合成缓存层用例随
 * NoteAudioCache 删除而撤下，换成注块键对齐与包解压用例）。
 * 运行：javac -d <out> -sourcepath app/src/main/java tools/notecue_harness/NoteCueHarness.java
 */
public class NoteCueHarness {
    static int passed = 0, failed = 0;

    static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  PASS " + name); }
        else { failed++; System.out.println("  FAIL " + name); }
    }

    static NoteCuePlan.SentIn s(String id, long start, long end, boolean noAudio) {
        NoteCuePlan.SentIn in = new NoteCuePlan.SentIn();
        in.id = id;
        in.startMs = start;
        in.endMs = end;
        in.noAudio = noAudio;
        return in;
    }

    static NoteCuePlan.RefIn ref(String text, int a, int b) {
        return new NoteCuePlan.RefIn(text, a, b);
    }

    public static void main(String[] args) {
        // ---- 1. 基本块注：只在块末句触发，fireMs＝末句 endMs ----
        {
            List<NoteCuePlan.SentIn> in = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                NoteCuePlan.SentIn x = s("s" + i, i * 1000, i * 1000 + 900, false);
                if (i <= 2) x.zj = ref("直解注一", 0, 2);
                in.add(x);
            }
            List<NoteCuePlan.Cue> cues = NoteCuePlan.build(in, "zhijie", 1.0);
            check("plan basic: one cue for block 0-2", cues.size() == 1);
            check("plan basic: cue at flat 2, fire=2900",
                    cues.get(0).flat == 2 && cues.get(0).fireMs == 2900);
            check("plan basic: block range kept",
                    cues.get(0).blockStart == 0 && cues.get(0).blockEnd == 2);
        }
        // ---- 2. 选定版本优先 ----
        {
            List<NoteCuePlan.SentIn> in = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                NoteCuePlan.SentIn x = s("s" + i, i * 1000, i * 1000 + 900, false);
                x.zj = ref("直解文", 0, 1);
                x.jz = ref("集注文", 0, 1);
                in.add(x);
            }
            List<NoteCuePlan.Cue> a = NoteCuePlan.build(in, "jizhu", 1.0);
            check("plan priority: selected jizhu wins",
                    a.size() == 1 && "jizhu".equals(a.get(0).ver) && "集注文".equals(a.get(0).text));
            List<NoteCuePlan.Cue> b = NoteCuePlan.build(in, "zhijie", 1.0);
            check("plan priority: selected zhijie wins",
                    b.size() == 1 && "zhijie".equals(b.get(0).ver) && "直解文".equals(b.get(0).text));
        }
        // ---- 3. 选定版本无注 → 回退固定次序 ----
        {
            List<NoteCuePlan.SentIn> in = new ArrayList<>();
            NoteCuePlan.SentIn x = s("s0", 0, 900, false);
            x.zj = ref("只有直解", 0, 0);
            in.add(x);
            List<NoteCuePlan.Cue> cues = NoteCuePlan.build(in, "gengfu", 1.0);
            check("plan fallback: gengfu selected, zj used",
                    cues.size() == 1 && "zhijie".equals(cues.get(0).ver));
            List<NoteCuePlan.Cue> cues2 = NoteCuePlan.build(in, "daodu", 1.0);
            check("plan fallback: daodu selected, zj used",
                    cues2.size() == 1 && "zhijie".equals(cues2.get(0).ver));
        }
        // ---- 4. 嵌套块（集注）：内块先触发、外块后触发 ----
        {
            List<NoteCuePlan.SentIn> in = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                NoteCuePlan.SentIn x = s("s" + i, i * 1000, i * 1000 + 900, false);
                if (i >= 2 && i <= 3) x.jz = ref("内块注", 2, 3);
                else x.jz = ref("外块注", 0, 5);
                in.add(x);
            }
            List<NoteCuePlan.Cue> cues = NoteCuePlan.build(in, "jizhu", 1.0);
            check("plan nested: two cues", cues.size() == 2);
            check("plan nested: inner first (flat 3)",
                    cues.get(0).flat == 3 && "内块注".equals(cues.get(0).text));
            check("plan nested: outer last (flat 5)",
                    cues.get(1).flat == 5 && "外块注".equals(cues.get(1).text));
        }
        // ---- 5. noAudio 块末句：fire 取下一有声句起点 / 前一有声句末尾 ----
        {
            List<NoteCuePlan.SentIn> in = new ArrayList<>();
            NoteCuePlan.SentIn a = s("s0", 0, 900, false);
            a.zj = ref("注A", 0, 1);
            NoteCuePlan.SentIn b = s("s1", -1, -1, true);
            b.zj = ref("注A", 0, 1);
            NoteCuePlan.SentIn c = s("s2", 2000, 2900, false);
            c.zj = ref("注B", 2, 3);
            NoteCuePlan.SentIn d = s("s3", -1, -1, true);
            d.zj = ref("注B", 2, 3);
            in.add(a); in.add(b); in.add(c); in.add(d);
            List<NoteCuePlan.Cue> cues = NoteCuePlan.build(in, "zhijie", 1.0);
            check("plan noAudio: two cues", cues.size() == 2);
            check("plan noAudio: mid cue fires at next audio start (2000)",
                    cues.get(0).flat == 1 && cues.get(0).fireMs == 2000);
            check("plan noAudio: tail cue fires at prev audio end (2900)",
                    cues.get(1).flat == 3 && cues.get(1).fireMs == 2900);
        }
        // ---- 6. scale 缩放与相邻两块 ----
        {
            List<NoteCuePlan.SentIn> in = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                NoteCuePlan.SentIn x = s("s" + i, i * 1000, i * 1000 + 800, false);
                if (i <= 1) x.zj = ref("块一", 0, 1);
                else x.zj = ref("块二", 2, 4);
                in.add(x);
            }
            List<NoteCuePlan.Cue> cues = NoteCuePlan.build(in, "zhijie", 0.5);
            check("plan scale: two cues", cues.size() == 2);
            check("plan scale: fire halved (900, 2400)",
                    cues.get(0).fireMs == 900 && cues.get(1).fireMs == 2400);
        }
        // ---- 7. 空注文 / 无注 → 无 cue ----
        {
            List<NoteCuePlan.SentIn> in = new ArrayList<>();
            NoteCuePlan.SentIn x = s("s0", 0, 900, false);
            x.zj = ref("", 0, 0);
            in.add(x);
            in.add(s("s1", 1000, 1900, false));
            check("plan empty: no cues", NoteCuePlan.build(in, "zhijie", 1.0).isEmpty());
            check("plan empty: null input", NoteCuePlan.build(null, "zhijie", 1.0).isEmpty());
        }
        // ---- 8. Tracker：到点/消费/错位消费防护 ----
        {
            NoteCueTracker t = new NoteCueTracker();
            t.setCues(new long[]{1000, 3000});
            check("tracker: not due at 899 (early 100)", t.dueIndex(899, 100) == -1);
            check("tracker: due at 900 (early 100)", t.dueIndex(900, 100) == 0);
            check("tracker: dueIndex does not consume", t.dueIndex(950, 100) == 0);
            t.markFired(1); // 错位消费应被忽略
            check("tracker: wrong markFired ignored", t.nextIndex() == 0);
            t.markFired(0);
            check("tracker: advanced after markFired", t.nextIndex() == 1 && t.nextFireMs() == 3000);
        }
        // ---- 9. Tracker：resync 的 seek 语义 ----
        {
            NoteCueTracker t = new NoteCueTracker();
            t.setCues(new long[]{1000, 3000, 5000});
            t.resync(3000);
            check("tracker resync: cues <= pos consumed", t.nextIndex() == 2);
            t.resync(500);
            check("tracker resync: seek back re-arms", t.nextIndex() == 0);
            t.resync(9999);
            check("tracker resync: past end -> none", t.nextIndex() == -1 && t.nextFireMs() == -1);
            t.resync(4999);
            check("tracker resync: just before last", t.nextIndex() == 2 && t.dueIndex(4900, 100) == 2);
        }
        // ---- 9b. Tracker：consumeUpTo（轨尾补念末注时跳过旧 cue） ----
        {
            NoteCueTracker t = new NoteCueTracker();
            t.setCues(new long[]{1000, 3000, 5000});
            t.consumeUpTo(1);
            check("tracker consumeUpTo: pointer past idx", t.nextIndex() == 2);
            t.consumeUpTo(0); // 已在身后，不动
            check("tracker consumeUpTo: behind is no-op", t.nextIndex() == 2);
            t.consumeUpTo(2);
            check("tracker consumeUpTo: last consumed", t.nextIndex() == -1);
        }
        // ---- 10. 注块键：cue 的 keySentId = 块首句 id（与注数据条目键/包 manifest 对齐） ----
        {
            List<NoteCuePlan.SentIn> in = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                NoteCuePlan.SentIn x = s("sent-" + i, i * 1000, i * 1000 + 900, false);
                if (i >= 1) x.jz = ref("集注块", 1, 3);
                in.add(x);
            }
            List<NoteCuePlan.Cue> cues = NoteCuePlan.build(in, "jizhu", 1.0);
            check("cue key: one cue at block end (flat 3)", cues.size() == 1 && cues.get(0).flat == 3);
            check("cue key: keySentId is block-start sentence id",
                    "sent-1".equals(cues.get(0).keySentId));
            check("cue key: sentId is trigger sentence id",
                    "sent-3".equals(cues.get(0).sentId));
        }
        // ---- 12. 同一条注不念第二遍（v1.36：提前触发 + 播放器只建一次 + 落点重定） ----
        {
            NoteCueTracker t = new NoteCueTracker();
            t.setCues(new long[]{10000, 20000});
            // 1.5 倍速提前量约 63ms：还没到句末就到点
            check("replay: due just before fire", t.dueIndex(9940, 63) == 0);
            check("replay: begin once", t.begin(0));
            check("replay: begin again rejected", !t.begin(0));
            check("replay: no second due while leading in", t.dueIndex(9940, 63) == -1);
            check("replay: player claimed once", t.claimPlayer());
            check("replay: second claim rejected", !t.claimPlayer());
            t.markSpeaking();
            t.finish();
            // 念完接回正文，位置仍在触发点之前：不能再待发
            check("replay: resume before fire does not re-due", t.dueIndex(9940, 63) == -1);
            t.resync(9940); // 表重建 / 进度抖动落在同一点
            check("replay: resync at pause pos keeps it spoken",
                    t.nextIndex() == 1 && t.dueIndex(9940, 63) == -1);
            t.resync(10000); // 已经过了触发点
            check("replay: resync past fire still spoken", t.nextIndex() == 1);
            // 用户拖回这一句开头（远早于触发点）才可以再念
            t.resync(2000);
            check("replay: seek back into the sentence re-arms",
                    t.nextIndex() == 0 && t.dueIndex(9940, 63) == 0);
        }
        {
            NoteCueTracker t = new NoteCueTracker();
            t.setCues(new long[]{1000, 3000, 8000});
            check("replay: begin last at track end skips the middle", t.begin(2));
            t.finish();
            check("replay: after track-end note, nothing pending", t.nextIndex() == -1);
            t.resync(0); // 单章循环回到章首：三注都重新待发
            check("replay: chapter restart re-arms all", t.nextIndex() == 0 && t.dueIndex(1000, 50) == 0);
        }
        {
            // 改速 seek 只偏了一帧（约 30ms），已经念过的注不重开；还没念的下一注不受影响
            NoteCueTracker t = new NoteCueTracker();
            t.setCues(new long[]{5000, 9000});
            t.begin(0);
            t.claimPlayer();
            t.markSpeaking();
            t.finish();
            t.resync(4970);
            check("replay: speed-seek jitter does not repeat the note", t.nextIndex() == 1);
            check("replay: next note still due at its own fire", t.dueIndex(8960, 50) == 1);
        }
        // ---- 11. ZipExtract：正常解压 + 越界条目拒绝 ----
        {
            try {
                File dir = Files.createTempDirectory("notecue-zip").toFile();
                File zip = new File(dir, "pack.zip");
                ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip));
                zos.putNextEntry(new ZipEntry("manifest.json"));
                zos.write("{\"bookId\":\"daxue\"}".getBytes("UTF-8"));
                zos.closeEntry();
                zos.putNextEntry(new ZipEntry("audio/a.mp3"));
                zos.write(new byte[]{1, 2, 3});
                zos.closeEntry();
                zos.close();
                File out = new File(dir, "x");
                int n = ZipExtract.extract(zip, out);
                check("zip: extracts files incl nested", n == 2
                        && new File(out, "manifest.json").exists()
                        && new File(out, "audio/a.mp3").length() == 3);
                File evil = new File(dir, "evil.zip");
                ZipOutputStream ez = new ZipOutputStream(new FileOutputStream(evil));
                ez.putNextEntry(new ZipEntry("../evil.txt"));
                ez.write(1);
                ez.closeEntry();
                ez.close();
                boolean rejected = false;
                try {
                    ZipExtract.extract(evil, new File(dir, "y"));
                } catch (Exception e) {
                    rejected = true;
                }
                check("zip: ../ entry rejected", rejected && !new File(dir, "evil.txt").exists());
            } catch (Exception e) {
                check("zip: unexpected exception " + e, false);
            }
        }
        System.out.println("NoteCueHarness: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
