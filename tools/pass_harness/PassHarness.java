import com.ti.songdu.PassMonitor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * v1.35.0 遍数判定桌面 harness：直驱 PassMonitor。
 * 覆盖以前几次修改没挡住、真机会漏记或重记的场景：
 * 章尾贴着音频结尾（采样越不过去）、播完回调被重复送进来、
 * 念注被取消（计数已经发生，取消不能撤账）、听进章尾就切下一章、
 * 拖到结尾、单章循环两圈、进程死在章尾区后续播、1.5 倍速的采样间隔。
 *
 * 运行：javac --release 8 -encoding UTF-8 -d /tmp/pass-harness \
 *   -sourcepath app/src/main/java tools/pass_harness/PassHarness.java \
 *   && java -cp /tmp/pass-harness PassHarness
 */
public class PassHarness {

    static int passed = 0, failed = 0;

    static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  PASS " + name); }
        else { failed++; System.out.println("  FAIL " + name); }
    }

    /** 模拟唯一记账入口的去重：同一键只算一次。 */
    static final class Ledger {
        final Set<String> keys = new HashSet<>();
        int chapters;
        int tracks;
        final List<Integer> chapterIdx = new ArrayList<>();

        int apply(List<PassMonitor.Event> ev) {
            if (ev == null) return 0;
            int n = 0;
            for (PassMonitor.Event e : ev) {
                if (e == null || e.dedupKey == null || e.dedupKey.length() == 0) continue;
                if (!keys.add(e.dedupKey)) continue;
                n++;
                if (e.chapter) { chapters++; chapterIdx.add(e.chapterIndex); }
                else tracks++;
            }
            return n;
        }
    }

    static PassMonitor lunyu(int global) {
        PassMonitor m = new PassMonitor();
        m.setSession("sess-1");
        // 一篇一条音频，章尾 = 音频时长。这是论语按章听的真实形状。
        m.loadTrack("preset-lunyu-" + (global + 1), "lunyu", global,
                new long[]{0}, new long[]{251000});
        return m;
    }

    static void play(PassMonitor m, Ledger led, long... pos) {
        for (long p : pos) led.apply(m.onSample(p, true));
    }

    public static void main(String[] args) {
        // ---------- 1. 章尾贴着音频结尾：采样永远停在章尾之前，只靠「播完」 ----------
        {
            PassMonitor m = lunyu(4); // 公冶长，全局第 4 章
            Ledger led = new Ledger();
            play(m, led, 0, 10000, 120000, 249600); // 已进章尾区（末 2 秒），仍未到章尾
            check("end==duration: samples alone do not count", led.chapters == 0 && led.tracks == 0);
            check("end==duration: in the tail before the end", m.inTail() && m.isArmed());
            int n = led.apply(m.onTrackEnded());
            check("end==duration: track end counts chapter once", n == 2 && led.chapters == 1 && led.tracks == 1);
            check("end==duration: chapter index is the global one",
                    led.chapterIdx.size() == 1 && led.chapterIdx.get(0) == 4);
            check("end==duration: second completion is a no-op",
                    led.apply(m.onTrackEnded()) == 0 && led.chapters == 1 && led.tracks == 1);
        }

        // ---------- 2. 念注取消：计数在回调被推迟之前已经落了，取消撤不掉 ----------
        {
            PassMonitor m = lunyu(2);
            Ledger led = new Ledger();
            play(m, led, 0, 200000);
            led.apply(m.onTrackEnded());
            // 取消念注不调用任何「撤回」。再送一次播完，仍然只有一遍。
            check("note cancelled: still exactly one chapter and one track",
                    led.chapters == 1 && led.tracks == 1 && led.apply(m.onTrackEnded()) == 0);
        }

        // ---------- 3. 听进章尾就切下一章（文件还没播完） ----------
        {
            PassMonitor m = lunyu(5); // 雍也
            Ledger led = new Ledger();
            long end = 251000;
            long tail = end - PassMonitor.thresholdFor(0, end);
            play(m, led, 0, 100000, tail + 500); // 章尾区内，还没到章尾
            check("leave in tail: not counted before leaving", led.chapters == 0);
            led.apply(m.onLeave());
            check("leave in tail: chapter and track both count",
                    led.chapters == 1 && led.tracks == 1);
            check("leave in tail: leaving again does not double",
                    led.apply(m.onLeave()) == 0 && led.chapters == 1);
        }
        {
            PassMonitor m = lunyu(6);
            Ledger led = new Ledger();
            play(m, led, 0, 40000);
            led.apply(m.onLeave());
            check("leave in the middle: no count", led.chapters == 0 && led.tracks == 0);
        }

        // ---------- 4. 拖到结尾再播完：章不记，整条音频的曲目一遍仍记（v1.20.7 口径） ----------
        {
            PassMonitor m = lunyu(0);
            Ledger led = new Ledger();
            m.onSeek(251000 - 1); // 一上来就在结尾
            play(m, led, 251000);
            led.apply(m.onTrackEnded());
            check("seek to end: chapter not counted, track counted once",
                    led.chapters == 0 && led.tracks == 1);
        }
        {
            PassMonitor m = lunyu(0);
            Ledger led = new Ledger();
            play(m, led, 0, 10000); // 已经从章身听进来
            m.onSeek(251000);       // 再拖过章尾（松手在章尾上，不是自然播过去）
            play(m, led, 251000);
            led.apply(m.onTrackEnded());
            check("seek past the end after listening: the drag itself does not count the chapter",
                    led.chapters == 0 && led.tracks == 1);
        }
        {
            PassMonitor m = lunyu(0);
            Ledger led = new Ledger();
            play(m, led, 0, 10000);
            m.onSeek(249600); // 快进到章尾区，但没越过章尾
            led.apply(m.onTrackEnded());
            check("seek into the tail after listening the body: finishing still counts",
                    led.chapters == 1 && led.tracks == 1);
        }

        // ---------- 5. 大学式整本：两章都在一条音频里，内部章尾采样越得过去 ----------
        {
            PassMonitor m = new PassMonitor();
            m.setSession("sess-1");
            m.loadTrack("preset-daxue-full", "daxue", 0,
                    new long[]{0, 60000}, new long[]{60000, 120000});
            Ledger led = new Ledger();
            play(m, led, 0, 30000, 61000, 90000, 121000);
            led.apply(m.onTrackEnded());
            check("whole book: two chapters and one track",
                    led.chapters == 2 && led.tracks == 1
                            && led.chapterIdx.get(0) == 0 && led.chapterIdx.get(1) == 1);
            check("whole book: track end does not add a third chapter", led.chapters == 2);
        }

        // ---------- 6. 整本里只把中间一章听到章尾就切走：只记那一章，不记整本 ----------
        {
            PassMonitor m = new PassMonitor();
            m.setSession("sess-1");
            m.loadTrack("preset-daxue-full", "daxue", 0,
                    new long[]{0, 60000}, new long[]{60000, 120000});
            Ledger led = new Ledger();
            play(m, led, 0, 30000, 59000);
            led.apply(m.onLeave());
            check("leave mid-book in chapter tail: that chapter only",
                    led.chapters == 1 && led.tracks == 0 && led.chapterIdx.get(0) == 0);
        }

        // ---------- 7. 单章循环两圈 ----------
        {
            PassMonitor m = lunyu(7);
            Ledger led = new Ledger();
            play(m, led, 0, 200000, 251000);
            m.rewindTo(0, 0);
            play(m, led, 100, 200000, 251000);
            check("single-chapter loop: two rounds, two chapters and two tracks",
                    led.chapters == 2 && led.tracks == 2);
        }

        // ---------- 8. 暂停不记；接着听完只记一次 ----------
        {
            PassMonitor m = lunyu(1);
            Ledger led = new Ledger();
            play(m, led, 0, 80000);
            led.apply(m.onSample(80000, false));
            led.apply(m.onSample(90000, false));
            check("pause samples do not count", led.chapters == 0);
            play(m, led, 100000, 251000);
            check("resume then finish: one chapter", led.chapters == 1 && led.tracks == 1);
        }

        // ---------- 9. 1.5 倍速：真实 250ms 一跳 ≈ 音频 375ms，仍能在章尾记上 ----------
        {
            PassMonitor m = lunyu(3);
            Ledger led = new Ledger();
            for (long p = 0; p < 251000; p += 375) led.apply(m.onSample(p, true));
            check("1.5x grid: still short of a count before the end", led.chapters == 0 && m.inTail());
            led.apply(m.onTrackEnded());
            check("1.5x grid: track end counts once", led.chapters == 1 && led.tracks == 1);
        }

        // ---------- 10. 改速重启的假跳跃（位置瞬间变成 0）不能多记一遍 ----------
        {
            PassMonitor m = lunyu(8);
            Ledger led = new Ledger();
            play(m, led, 0, 200000, 251000);
            led.apply(m.onSample(0, true));      // 假跳跃
            led.apply(m.onSample(251000, true)); // 位置又回到结尾
            check("speed-restart glitch: still one pass", led.chapters == 1 && led.tracks == 1);
        }

        // ---------- 11. 进程死在章尾区：快照能补记；续播同一章尾不再记 ----------
        {
            PassMonitor m = lunyu(4);
            Ledger led = new Ledger();
            long tail = 251000 - PassMonitor.thresholdFor(0, 251000);
            play(m, led, 0, 100000, tail + 800);
            m.stampDate("2026-11-28");
            PassMonitor.Snapshot snap = m.snapshot();
            check("killed in tail: snapshot says the chapter is due",
                    snap.chapterDue() && snap.trackDue() && "2026-11-28".equals(snap.date));
            led.apply(PassMonitor.eventsDue(snap));
            check("killed in tail: settling counts once", led.chapters == 1 && led.tracks == 1);
            check("killed in tail: settling again is a no-op",
                    led.apply(PassMonitor.eventsDue(snap)) == 0);

            PassMonitor m2 = lunyu(4);
            Ledger led2 = new Ledger();
            m2.onSeek(tail + 800);
            m2.sealChapter(snap.chapter);
            led2.apply(m2.onSample(251000, true));
            led2.apply(m2.onTrackEnded());
            check("resume in the same tail: no second pass", led2.chapters == 0 && led2.tracks == 0);
            m2.onSeek(0);
            play(m2, led2, 1000, 251000);
            check("rewind after the sealed pass: a new listen counts",
                    led2.chapters == 1 && led2.tracks == 1);
        }

        // ---------- 12. 死在章身里：不补记；下次从章身续播，听完仍记一次 ----------
        {
            PassMonitor m = lunyu(9);
            Ledger led = new Ledger();
            play(m, led, 0, 40000);
            PassMonitor.Snapshot snap = m.snapshot();
            check("killed in the body: nothing due", !snap.chapterDue() && !snap.trackDue());
            PassMonitor m2 = lunyu(9);
            play(m2, led, 0, 40000, 251000);
            check("resume from the body and finish: one pass", led.chapters == 1 && led.tracks == 1);
        }

        // ---------- 13. 跟读收尾与普通播完同一条路，不会两笔 ----------
        {
            PassMonitor m = lunyu(10);
            Ledger led = new Ledger();
            play(m, led, 0, 180000);
            led.apply(m.onTrackEnded()); // 跟读 finishFollowPass
            led.apply(m.onTrackEnded()); // 播放器 onCompletion 又到了
            check("follow finish and player completion: one pass",
                    led.chapters == 1 && led.tracks == 1);
        }

        // ---------- 14. 整书循环：两篇各记各的，键不撞 ----------
        {
            Ledger led = new Ledger();
            PassMonitor a = lunyu(0);
            play(a, led, 0, 251000);
            PassMonitor b = lunyu(1);
            play(b, led, 0, 251000);
            check("two chapters in a row: two passes, distinct keys",
                    led.chapters == 2 && led.tracks == 2 && led.keys.size() == 4);
        }

        // ---------- 15. 采样间隔大于「章尾到音频尾」的缝：越界的那一跳就记，播完不重记 ----------
        {
            PassMonitor m = new PassMonitor();
            m.setSession("sess-1");
            m.loadTrack("preset-lunyu-3", "lunyu", 2,
                    new long[]{0}, new long[]{250000}); // 音频其实有 250400，缝只有 400ms
            Ledger led = new Ledger();
            play(m, led, 0, 249700, 250200); // 一跳越过章尾，音频还没完
            check("gap smaller than one sample: counted on the crossing",
                    led.chapters == 1 && led.tracks == 1);
            led.apply(m.onTrackEnded());
            check("gap smaller than one sample: file end does not double",
                    led.chapters == 1 && led.tracks == 1);
        }

        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
