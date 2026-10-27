package com.ti.songdu.recite;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v1.18.0 识读统计 v2（新文件 files/recite_stats_v2.json）。
 *
 * 旧 recite_stats.json 原样保留在设备上，但 App 自本版起不再读取/展示
 * （旧口径含重复计数与雪崩灌水，作废重建、不做重判——加权报告第六节）。
 * 本类绝不删除/改写旧文件。
 *
 * v2 记录在 V1 字段之上加：tier（confirmed/dual/doubtHigh/doubt）、合成分数
 * S 与 W/E/C/K 分项、先验类别 category、主听写音节 heardMain。DEL 单列
 * （dels），INS 单列（extras）。人工确认区动作持久化于此：
 * confirmDual 把存疑·双报转正为确定错；dismissCombo 勾销（字,音）组合，
 * 写入 dismissed 后该组合此后不再记录。
 */
public final class ReciteStatsV2 {

    public static final class Record {
        public String bookId, sentId, clauseText, hanzi, pyToned, type;
        public String tier, category, heardMain, engines;
        public int flat, clauseIdx;
        public int clauseTextStart, clauseTextEnd;
        public int clauseHanziStart, clauseHanziEnd;
        public int ci;
        public int count;
        public int score, w, e, c, k;
        public boolean userConfirmed;
        public final Map<String, Integer> heardCounts = new LinkedHashMap<>();
        public long firstTs, lastTs;

        public String key() {
            return bookId + "|" + sentId + "|" + clauseIdx + "|" + ci;
        }
    }

    public static final class Del {
        public String bookId, sentId, clauseText, hanzi, pyToned;
        public int flat, clauseIdx, ci, count;
        public long lastTs;

        public String key() {
            return bookId + "|" + sentId + "|" + clauseIdx + "|" + ci;
        }
    }

    public static final class Extra {
        public String bookId, sentId, clauseText, heard;
        public int flat, clauseIdx, count;
        public long lastTs;
    }

    private final File file;
    private final Map<String, Record> records = new LinkedHashMap<>();
    private final Map<String, Del> dels = new LinkedHashMap<>();
    private final List<Extra> extras = new ArrayList<>();
    /** 已勾销的（字,音）组合：bookId|hanzi|heardSyllable */
    private final Set<String> dismissed = new LinkedHashSet<>();
    private boolean dirty = false;

    private static ReciteStatsV2 instance;

    public static synchronized ReciteStatsV2 get(Context ctx) {
        if (instance == null) instance = new ReciteStatsV2(ctx);
        return instance;
    }

    /** 桌面/测试用：指定文件构造（不读旧文件） */
    public ReciteStatsV2(File file) {
        this.file = file;
        load();
    }

    private ReciteStatsV2(Context ctx) {
        file = new File(ctx.getFilesDir(), "recite_stats_v2.json");
        load();
    }

    public static String comboKey(String bookId, String hanzi, String heardSyllable) {
        return bookId + "|" + hanzi + "|" + (heardSyllable == null ? "" : heardSyllable);
    }

    public synchronized boolean isDismissed(String bookId, String hanzi, String heardSyllable) {
        return dismissed.contains(comboKey(bookId, hanzi, heardSyllable));
    }

    // ---------- 记录 ----------

    /** 记一条已判定 SUB。dismissed 组合直接吞掉（此后不再报）。 */
    public synchronized void recordJudged(String bookId, ReciteData data, int unitIdx,
                                          String heardRaw, ReciteJudge.Result jr,
                                          String engines) {
        if (data == null || unitIdx < 0 || unitIdx >= data.units.size() || jr == null) return;
        ReciteData.Unit u = data.units.get(unitIdx);
        String heardTl = ReciteJudge.heardSyllable(heardRaw, u.py);
        if (isDismissed(bookId, u.hanzi, heardTl)) return;
        ReciteData.Clause cl = data.clauseOf(u.flat, u.clause);
        String sentId = u.flat < data.sentIds.size() ? data.sentIds.get(u.flat) : null;
        if (cl == null || sentId == null) return;
        String key = bookId + "|" + sentId + "|" + u.clause + "|" + u.ci;
        Record r = records.get(key);
        long now = System.currentTimeMillis();
        int tier = jr.tier;
        if (r == null) {
            r = new Record();
            r.bookId = bookId; r.sentId = sentId; r.flat = u.flat; r.clauseIdx = u.clause;
            r.clauseTextStart = cl.textStart; r.clauseTextEnd = cl.textEnd;
            r.clauseHanziStart = cl.hanziStart; r.clauseHanziEnd = cl.hanziEnd;
            r.clauseText = cl.text; r.ci = u.ci; r.hanzi = u.hanzi; r.pyToned = u.pyToned;
            r.type = "sub"; r.count = 0; r.firstTs = now;
            r.tier = ReciteJudge.tierName(tier);
            r.category = jr.category; r.score = jr.s;
            r.w = jr.w; r.e = jr.e; r.c = jr.c; r.k = jr.k;
            r.heardMain = heardTl; r.engines = engines == null ? "" : engines;
            records.put(key, r);
        } else if (ReciteJudge.severity(tier) > ReciteJudge.severity(tierOf(r))) {
            r.tier = ReciteJudge.tierName(tier);
            r.score = jr.s; r.w = jr.w; r.e = jr.e; r.c = jr.c; r.k = jr.k;
            r.category = jr.category; r.heardMain = heardTl;
        }
        r.count++;
        r.lastTs = now;
        if (heardTl != null && !heardTl.isEmpty()) {
            Integer cnt = r.heardCounts.get(heardTl);
            r.heardCounts.put(heardTl, cnt == null ? 1 : cnt + 1);
            if (r.heardMain == null || r.heardMain.isEmpty()) r.heardMain = heardTl;
        }
        dirty = true;
        save();
    }

    private static int tierOf(Record r) {
        if ("confirmed".equals(r.tier)) return ReciteJudge.TIER_CONFIRMED;
        if ("dual".equals(r.tier)) return ReciteJudge.TIER_DOUBT_DUAL;
        if ("doubtHigh".equals(r.tier)) return ReciteJudge.TIER_DOUBT_HIGH;
        return ReciteJudge.TIER_DOUBT;
    }

    /** 漏读单列（不计分） */
    public synchronized void recordDel(String bookId, ReciteData data, int unitIdx) {
        if (data == null || unitIdx < 0 || unitIdx >= data.units.size()) return;
        ReciteData.Unit u = data.units.get(unitIdx);
        ReciteData.Clause cl = data.clauseOf(u.flat, u.clause);
        String sentId = u.flat < data.sentIds.size() ? data.sentIds.get(u.flat) : null;
        if (cl == null || sentId == null) return;
        String key = bookId + "|" + sentId + "|" + u.clause + "|" + u.ci;
        Del d = dels.get(key);
        if (d == null) {
            d = new Del();
            d.bookId = bookId; d.sentId = sentId; d.flat = u.flat; d.clauseIdx = u.clause;
            d.clauseText = cl.text; d.ci = u.ci; d.hanzi = u.hanzi; d.pyToned = u.pyToned;
            d.count = 0;
            dels.put(key, d);
        }
        d.count++;
        d.lastTs = System.currentTimeMillis();
        dirty = true;
        save();
    }

    /** 多读单列（不计分） */
    public synchronized void recordExtra(String bookId, ReciteData data, int unitIdx, String heard) {
        if (data == null || heard == null || heard.isEmpty() || data.units.isEmpty()) return;
        int ui = Math.max(0, Math.min(unitIdx, data.units.size() - 1));
        ReciteData.Unit u = data.units.get(ui);
        ReciteData.Clause cl = data.clauseOf(u.flat, u.clause);
        String sentId = u.flat < data.sentIds.size() ? data.sentIds.get(u.flat) : null;
        if (cl == null || sentId == null) return;
        for (Extra e : extras) {
            if (e.bookId.equals(bookId) && e.sentId.equals(sentId)
                    && e.clauseIdx == u.clause && e.heard.equals(heard)) {
                e.count++;
                e.lastTs = System.currentTimeMillis();
                dirty = true; save(); return;
            }
        }
        Extra e = new Extra();
        e.bookId = bookId; e.sentId = sentId; e.flat = u.flat; e.clauseIdx = u.clause;
        e.clauseText = cl.text; e.heard = heard; e.count = 1;
        e.lastTs = System.currentTimeMillis();
        extras.add(e);
        dirty = true;
        save();
    }

    // ---------- 人工确认区 ----------

    /** 存疑·双报一键转正：tier → confirmed 并标 userConfirmed */
    public synchronized boolean confirmDual(String recordKey) {
        Record r = records.get(recordKey);
        if (r == null) return false;
        r.tier = "confirmed";
        r.userConfirmed = true;
        dirty = true; save(); return true;
    }

    /** 勾销该（字,音）组合：删掉同组合的存疑记录并持久化 dismissed，此后不再报 */
    public synchronized int dismissCombo(String bookId, String hanzi, String heardSyllable) {
        dismissed.add(comboKey(bookId, hanzi, heardSyllable));
        List<String> gone = new ArrayList<>();
        for (Record r : records.values()) {
            if (r.bookId.equals(bookId) && r.hanzi.equals(hanzi)
                    && heardSyllable != null && heardSyllable.equals(r.heardMain)
                    && !"confirmed".equals(r.tier)) {
                gone.add(r.key());
            }
        }
        for (String k : gone) records.remove(k);
        dirty = true; save();
        return gone.size();
    }

    // ---------- 查询 ----------

    public synchronized List<Record> allRecords() {
        return new ArrayList<>(records.values());
    }

    public synchronized List<Record> byTier(String tier) {
        List<Record> out = new ArrayList<>();
        for (Record r : records.values()) if (tier.equals(r.tier)) out.add(r);
        return out;
    }

    public synchronized List<Del> allDels() {
        return new ArrayList<>(dels.values());
    }

    public synchronized List<Extra> allExtras() {
        return new ArrayList<>(extras);
    }

    public synchronized int deleteBookChar(String bookId, String hanzi) {
        List<String> gone = new ArrayList<>();
        for (Record r : records.values()) {
            if (r.bookId.equals(bookId) && r.hanzi.equals(hanzi)) gone.add(r.key());
        }
        for (String k : gone) records.remove(k);
        if (!gone.isEmpty()) { dirty = true; save(); }
        return gone.size();
    }

    public synchronized int totalConfirmedCount() {
        int n = 0;
        for (Record r : records.values()) if ("confirmed".equals(r.tier)) n += r.count;
        return n;
    }

    // ---------- 落盘 ----------

    private void load() {
        if (!file.exists()) return;
        try {
            byte[] buf = new byte[(int) file.length()];
            FileInputStream in = new FileInputStream(file);
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            in.close();
            JSONObject root = new JSONObject(new String(buf, 0, off, StandardCharsets.UTF_8));
            JSONArray arr = root.optJSONArray("records");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Record r = new Record();
                    r.bookId = o.getString("bookId"); r.sentId = o.getString("sentId");
                    r.flat = o.optInt("flat"); r.clauseIdx = o.optInt("clauseIdx");
                    r.clauseTextStart = o.optInt("clauseTextStart");
                    r.clauseTextEnd = o.optInt("clauseTextEnd");
                    r.clauseHanziStart = o.optInt("clauseHanziStart");
                    r.clauseHanziEnd = o.optInt("clauseHanziEnd");
                    r.clauseText = o.optString("clauseText", "");
                    r.ci = o.optInt("ci"); r.hanzi = o.optString("hanzi", "");
                    r.pyToned = o.optString("pyToned", "");
                    r.type = o.optString("type", "sub");
                    r.tier = o.optString("tier", "doubt");
                    r.category = o.optString("category", "");
                    r.heardMain = o.optString("heardMain", "");
                    r.engines = o.optString("engines", "");
                    r.score = o.optInt("score"); r.w = o.optInt("w");
                    r.e = o.optInt("e"); r.c = o.optInt("c"); r.k = o.optInt("k");
                    r.userConfirmed = o.optBoolean("userConfirmed");
                    r.count = o.optInt("count", 1);
                    r.firstTs = o.optLong("firstTs"); r.lastTs = o.optLong("lastTs");
                    JSONObject hc = o.optJSONObject("heard");
                    if (hc != null) {
                        JSONArray names = hc.names();
                        if (names != null) {
                            for (int k = 0; k < names.length(); k++) {
                                String name = names.getString(k);
                                r.heardCounts.put(name, hc.optInt(name, 1));
                            }
                        }
                    }
                    records.put(r.key(), r);
                }
            }
            JSONArray dl = root.optJSONArray("dels");
            if (dl != null) {
                for (int i = 0; i < dl.length(); i++) {
                    JSONObject o = dl.getJSONObject(i);
                    Del d = new Del();
                    d.bookId = o.getString("bookId"); d.sentId = o.getString("sentId");
                    d.flat = o.optInt("flat"); d.clauseIdx = o.optInt("clauseIdx");
                    d.clauseText = o.optString("clauseText", "");
                    d.ci = o.optInt("ci"); d.hanzi = o.optString("hanzi", "");
                    d.pyToned = o.optString("pyToned", "");
                    d.count = o.optInt("count", 1); d.lastTs = o.optLong("lastTs");
                    dels.put(d.key(), d);
                }
            }
            JSONArray ex = root.optJSONArray("extras");
            if (ex != null) {
                for (int i = 0; i < ex.length(); i++) {
                    JSONObject o = ex.getJSONObject(i);
                    Extra e = new Extra();
                    e.bookId = o.getString("bookId"); e.sentId = o.getString("sentId");
                    e.flat = o.optInt("flat"); e.clauseIdx = o.optInt("clauseIdx");
                    e.clauseText = o.optString("clauseText", "");
                    e.heard = o.optString("heard", "");
                    e.count = o.optInt("count", 1); e.lastTs = o.optLong("lastTs");
                    extras.add(e);
                }
            }
            JSONArray dis = root.optJSONArray("dismissed");
            if (dis != null) {
                for (int i = 0; i < dis.length(); i++) dismissed.add(dis.getString(i));
            }
        } catch (Exception ignored) {
            records.clear(); dels.clear(); extras.clear();
        }
    }

    private void save() {
        if (!dirty) return;
        try {
            JSONObject root = new JSONObject();
            root.put("version", 2);
            JSONArray arr = new JSONArray();
            for (Record r : records.values()) {
                JSONObject o = new JSONObject();
                o.put("bookId", r.bookId); o.put("sentId", r.sentId);
                o.put("flat", r.flat); o.put("clauseIdx", r.clauseIdx);
                o.put("clauseTextStart", r.clauseTextStart);
                o.put("clauseTextEnd", r.clauseTextEnd);
                o.put("clauseHanziStart", r.clauseHanziStart);
                o.put("clauseHanziEnd", r.clauseHanziEnd);
                o.put("clauseText", r.clauseText); o.put("ci", r.ci);
                o.put("hanzi", r.hanzi); o.put("pyToned", r.pyToned);
                o.put("type", r.type); o.put("tier", r.tier);
                o.put("category", r.category); o.put("heardMain", r.heardMain);
                o.put("engines", r.engines);
                o.put("score", r.score); o.put("w", r.w); o.put("e", r.e);
                o.put("c", r.c); o.put("k", r.k);
                o.put("userConfirmed", r.userConfirmed);
                o.put("count", r.count); o.put("firstTs", r.firstTs); o.put("lastTs", r.lastTs);
                JSONObject hc = new JSONObject();
                for (Map.Entry<String, Integer> en : r.heardCounts.entrySet()) {
                    hc.put(en.getKey(), en.getValue());
                }
                o.put("heard", hc);
                arr.put(o);
            }
            root.put("records", arr);
            JSONArray dl = new JSONArray();
            for (Del d : dels.values()) {
                JSONObject o = new JSONObject();
                o.put("bookId", d.bookId); o.put("sentId", d.sentId);
                o.put("flat", d.flat); o.put("clauseIdx", d.clauseIdx);
                o.put("clauseText", d.clauseText); o.put("ci", d.ci);
                o.put("hanzi", d.hanzi); o.put("pyToned", d.pyToned);
                o.put("count", d.count); o.put("lastTs", d.lastTs);
                dl.put(o);
            }
            root.put("dels", dl);
            JSONArray exArr = new JSONArray();
            for (Extra e : extras) {
                JSONObject o = new JSONObject();
                o.put("bookId", e.bookId); o.put("sentId", e.sentId);
                o.put("flat", e.flat); o.put("clauseIdx", e.clauseIdx);
                o.put("clauseText", e.clauseText); o.put("heard", e.heard);
                o.put("count", e.count); o.put("lastTs", e.lastTs);
                exArr.put(o);
            }
            root.put("extras", exArr);
            JSONArray dis = new JSONArray();
            for (String s : dismissed) dis.put(s);
            root.put("dismissed", dis);
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            out.close();
            if (!tmp.renameTo(file)) {
                FileOutputStream out2 = new FileOutputStream(file);
                out2.write(root.toString().getBytes(StandardCharsets.UTF_8));
                out2.close();
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            dirty = false;
        } catch (Exception ignored) {
        }
    }
}
