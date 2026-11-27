import com.ti.songdu.NoteCuePlan;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * v1.34.0 念注切片回归 harness：用真实数据（内容包 JSON＋注 JSON）逐篇断言
 * 「按篇切片建表」与「整本建表后按篇过滤」产出同一张触发表（条数与注块键
 * 序列逐篇一致）。
 *
 * 背景（缺陷 1）：NoteRef.startFlat/endFlat 是整本书拉平坐标（全书空间），
 * 《论语》取书按篇切片、篇内下标 0 起；v1.33 的适配（PlayerService.refIn）
 * 把全书坐标的 RefIn 原样喂给按篇内下标建表的 NoteCuePlan.build，于是
 * 只有偏移为 0 的第一篇能建出 cue、其余各篇恒 0 条。
 *
 * 数据制备镜像 ContentRepo.load（句 id/startMs/endMs/noAudio）与
 * ContentRepo.attachNotes（逐版本收集注块、每句取起点最晚的覆盖块），
 * 被测本体是 NoteCuePlan.build（与固定模式下的 NoteCuePlan.rebase——
 * PlayerService 的适配只委托它）。期望值＝整本建表后按篇归属的条数与键序，
 * 另对论语导读前四篇钉死已知值 16/24/26/26。
 *
 * 模式：
 *   legacy —— 切片时 RefIn 原样（v1.33 行为，预期 FAIL，作修复前基线）
 *   fixed  —— 切片时经 NoteCuePlan.rebase 按篇基址重定基（预期全 PASS）
 *
 * 运行（仓库根目录）：
 *   javac -cp tools/model_harness/lib/json-20231013.jar -d /tmp/ncs_out \
 *     -sourcepath app/src/main/java tools/notecue_harness/NoteCueSliceHarness.java
 *   java -cp /tmp/ncs_out:tools/model_harness/lib/json-20231013.jar \
 *     NoteCueSliceHarness legacy|fixed
 */
public class NoteCueSliceHarness {
    static int passed = 0, failed = 0;

    static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  PASS " + name); }
        else { failed++; System.out.println("  FAIL " + name); }
    }

    static final String[] VER_ORDER = {"zhijie", "jizhu", "gengfu", "daodu"};

    /** 一本书的制备结果：整本句列（全书空间 Ref）＋逐篇 [起,止] 全书下标 */
    static final class BookData {
        List<NoteCuePlan.SentIn> whole;
        List<int[]> chapRanges;
    }

    static NoteCuePlan.RefIn getRef(NoteCuePlan.SentIn s, String ver) {
        if ("zhijie".equals(ver)) return s.zj;
        if ("jizhu".equals(ver)) return s.jz;
        if ("gengfu".equals(ver)) return s.gf;
        return s.dd;
    }

    static void setRef(NoteCuePlan.SentIn s, String ver, NoteCuePlan.RefIn r) {
        if ("zhijie".equals(ver)) s.zj = r;
        else if ("jizhu".equals(ver)) s.jz = r;
        else if ("gengfu".equals(ver)) s.gf = r;
        else s.dd = r;
    }

    /** 镜像 ContentRepo.load＋attachNotes 制备整本句列（Ref 为全书空间） */
    static BookData load(String contentPath, String notesPath) throws Exception {
        JSONObject doc = new JSONObject(new String(
                Files.readAllBytes(Paths.get(contentPath)), StandardCharsets.UTF_8));
        JSONObject notes = new JSONObject(new String(
                Files.readAllBytes(Paths.get(notesPath)), StandardCharsets.UTF_8));
        BookData bd = new BookData();
        bd.whole = new ArrayList<>();
        bd.chapRanges = new ArrayList<>();
        JSONArray chs = doc.getJSONArray("chapters");
        for (int c = 0; c < chs.length(); c++) {
            JSONArray ss = chs.getJSONObject(c).getJSONArray("sentences");
            int start = bd.whole.size();
            for (int k = 0; k < ss.length(); k++) {
                JSONObject so = ss.getJSONObject(k);
                NoteCuePlan.SentIn in = new NoteCuePlan.SentIn();
                in.id = so.optString("id", "");
                in.noAudio = so.optBoolean("noAudio", false);
                if (!so.isNull("startMs")) in.startMs = so.getLong("startMs");
                if (!so.isNull("endMs")) in.endMs = so.getLong("endMs");
                if (in.noAudio) { in.startMs = -1; in.endMs = -1; }
                bd.whole.add(in);
            }
            bd.chapRanges.add(new int[]{start, bd.whole.size() - 1});
        }
        int total = bd.whole.size();
        for (String ver : VER_ORDER) {
            List<Integer> starts = new ArrayList<>();
            List<Integer> ends = new ArrayList<>();
            List<String> texts = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                JSONObject e = notes.optJSONObject(bd.whole.get(i).id);
                if (e == null || e.isNull(ver)) continue;
                String t = e.optString(ver, null);
                if (t == null || t.length() == 0) continue;
                int span = Math.max(1, e.optInt(ver + "Span", 1));
                starts.add(i);
                ends.add(Math.min(total - 1, i + span - 1));
                texts.add(t);
            }
            if (starts.isEmpty()) continue;
            for (int i = 0; i < total; i++) {
                int best = -1;
                for (int k = 0; k < starts.size(); k++) {
                    if (starts.get(k) <= i && i <= ends.get(k)
                            && (best < 0 || starts.get(k) > starts.get(best))) best = k;
                }
                if (best < 0) continue;
                setRef(bd.whole.get(i), ver, new NoteCuePlan.RefIn(
                        texts.get(best), starts.get(best), ends.get(best)));
            }
        }
        return bd;
    }

    /** 切片适配：把整本句列中 [lo,hi] 一段复制成篇内句列并按模式适配 Ref */
    static List<NoteCuePlan.SentIn> slice(BookData bd, int lo, int hi, String mode) {
        int size = hi - lo + 1;
        List<NoteCuePlan.SentIn> out = new ArrayList<>();
        for (int i = lo; i <= hi; i++) {
            NoteCuePlan.SentIn src = bd.whole.get(i);
            NoteCuePlan.SentIn in = new NoteCuePlan.SentIn();
            in.id = src.id;
            in.startMs = src.startMs;
            in.endMs = src.endMs;
            in.noAudio = src.noAudio;
            for (String ver : VER_ORDER) {
                NoteCuePlan.RefIn r = getRef(src, ver);
                if (r == null) continue;
                if ("fixed".equals(mode)) {
                    setRef(in, ver, NoteCuePlan.rebase(r, lo, size));
                } else { // legacy：v1.33 的适配——全书坐标原样
                    setRef(in, ver, new NoteCuePlan.RefIn(r.text, r.startFlat, r.endFlat));
                }
            }
            out.add(in);
        }
        return out;
    }

    static List<String> keysOf(List<NoteCuePlan.Cue> cues) {
        List<String> ks = new ArrayList<>();
        for (NoteCuePlan.Cue c : cues) ks.add(c.keySentId);
        return ks;
    }

    static void runBook(String bookId, String contentPath, String notesPath,
                        String[] vers, String mode) throws Exception {
        BookData bd = load(contentPath, notesPath);
        for (String ver : vers) {
            List<NoteCuePlan.Cue> whole = NoteCuePlan.build(bd.whole, ver, 1.0);
            System.out.println(bookId + " " + ver + " whole-book cues=" + whole.size());
            for (int c = 0; c < bd.chapRanges.size(); c++) {
                int lo = bd.chapRanges.get(c)[0], hi = bd.chapRanges.get(c)[1];
                List<NoteCuePlan.Cue> exp = new ArrayList<>();
                for (NoteCuePlan.Cue cue : whole) {
                    if (cue.flat >= lo && cue.flat <= hi) exp.add(cue);
                }
                List<NoteCuePlan.Cue> act = NoteCuePlan.build(slice(bd, lo, hi, mode), ver, 1.0);
                boolean ok = exp.size() == act.size()
                        && keysOf(exp).equals(keysOf(act));
                System.out.println("  " + bookId + " " + ver + " ch" + (c + 1)
                        + " expected=" + exp.size() + " actual=" + act.size()
                        + " keys=" + (keysOf(exp).equals(keysOf(act)) ? "OK" : "MISMATCH"));
                check(bookId + "/" + ver + " ch" + (c + 1) + " slice==whole ("
                        + exp.size() + " cues)", ok);
                if ("lunyu".equals(bookId) && "daodu".equals(ver) && c < 4) {
                    int[] known = {16, 24, 26, 26};
                    check("lunyu/daodu ch" + (c + 1) + " expected==known " + known[c],
                            exp.size() == known[c]);
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "fixed";
        String assets = "app/src/main/assets";
        // 书库根目录（可由 args[1] 覆盖；注意 JVM 的 user.home 未必是仓库所有者家目录）
        String bs = args.length > 1 ? args[1] : "/home/hatch/workspace/bookstore/repos";
        runBook("daxue", assets + "/content/daxue.json",
                assets + "/notes/daxue_notes.json",
                new String[]{"zhijie", "jizhu", "gengfu"}, mode);
        runBook("zhongyong", bs + "/zhongyong/content/zhongyong.json",
                bs + "/zhongyong/notes/zhongyong_notes.json",
                new String[]{"zhijie", "jizhu"}, mode);
        runBook("lunyu", bs + "/lunyu/content/lunyu.json",
                assets + "/notes/lunyu_notes.json",
                new String[]{"daodu"}, mode);
        System.out.println("NoteCueSliceHarness[" + mode + "]: "
                + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
