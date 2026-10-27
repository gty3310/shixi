
import com.ti.songdu.recite.*;
import java.nio.file.*;
import java.util.*;

public class JudgeHarness {
    static class Ev {
        String engine, type, hanzi, py, heard, sent; int unit;
    }
    public static void main(String[] args) throws Exception {
        List<Ev> evs = new ArrayList<>();
        for (String line : Files.readAllLines(Paths.get(args[0]))) {
            if (line.isEmpty()) continue;
            String[] f = line.split("\t", -1);
            Ev e = new Ev();
            e.engine = f[0]; e.unit = Integer.parseInt(f[1]); e.type = f[2];
            e.hanzi = f[3]; e.py = f[4]; e.heard = f.length > 5 ? f[5] : ""; e.sent = f[6];
            evs.add(e);
        }
        // 提交前去重：同引擎同 unit 同 type 同 heard 只计一次
        Set<String> seen = new HashSet<>();
        List<Ev> dedup = new ArrayList<>();
        for (Ev e : evs) {
            String k = e.engine + "|" + e.unit + "|" + e.type + "|" + e.heard;
            if (seen.add(k)) dedup.add(e);
        }
        System.out.println("raw=" + evs.size() + " dedup=" + dedup.size());
        // 按句（sent）计同引擎 SUB 数（雪崩 K 口径，报告实算）
        Map<String, Integer> clauseCnt = new HashMap<>();
        for (Ev e : dedup) if (e.type.equals("sub"))
            clauseCnt.merge(e.engine + "|" + e.sent, 1, Integer::sum);
        // 对照表：unit -> engine -> heard
        Map<Integer, Map<String, String>> byUnit = new HashMap<>();
        for (Ev e : dedup) if (e.type.equals("sub"))
            byUnit.computeIfAbsent(e.unit, k -> new HashMap<>()).putIfAbsent(e.engine, e.heard);
        for (String eng : new String[]{"A", "B"}) {
            int confirmed = 0, dual = 0, dHigh = 0, doubt = 0, del = 0;
            List<String> dualLines = new ArrayList<>();
            for (Ev e : dedup) {
                if (!e.engine.equals(eng)) continue;
                if (e.type.equals("del")) { del++; continue; }
                if (!e.type.equals("sub")) continue;
                String other = null;
                Map<String, String> m = byUnit.get(e.unit);
                if (m != null) for (Map.Entry<String, String> en : m.entrySet())
                    if (!en.getKey().equals(eng)) other = en.getValue();
                ReciteJudge.Result r = ReciteJudge.judgeSub(e.hanzi, e.py, e.heard,
                        other, clauseCnt.getOrDefault(eng + "|" + e.sent, 0));
                switch (r.tier) {
                    case ReciteJudge.TIER_CONFIRMED: confirmed++; break;
                    case ReciteJudge.TIER_DOUBT_DUAL:
                        dual++;
                        dualLines.add(String.format("  dual %s %s py=%s heard=%s other=%s W=%d E=%d C=%d K=%d S=%d",
                                e.sent, e.hanzi, e.py, e.heard, other, r.w, r.e, r.c, r.k, r.s));
                        break;
                    case ReciteJudge.TIER_DOUBT_HIGH: dHigh++; break;
                    default: doubt++;
                }
                System.out.printf("  %s %s %s py=%s heard=%s other=%s W=%d d=%d E=%d C=%d K=%d S=%d tier=%s%n",
                        eng, e.sent, e.hanzi, e.py, e.heard, other, r.w, r.d, r.e, r.c, r.k, r.s,
                        ReciteJudge.tierName(r.tier));
            }
            System.out.printf("ENGINE %s: confirmed=%d dual=%d doubtHigh=%d doubt=%d delSeparate=%d%n",
                    eng, confirmed, dual, dHigh, doubt, del);
            for (String s : dualLines) System.out.println(s);
        }
        // 双报 vs 单报分数对照（正 unit96 / 平 unit164）
        for (int unit : new int[]{96, 164}) {
            Map<String, String> m = byUnit.get(unit);
            System.out.println("unit " + unit + " reports=" + m);
        }
    }
}
