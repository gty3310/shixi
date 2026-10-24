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
import java.util.List;
import java.util.Map;

/**
 * v1.16.0 识读错误统计（本地文件 files/recite_stats.json，与笔记库 notes.json 分开，
 * 互不污染；删书不删统计，口径同「删书不动 records」）。
 *
 * 记录单位（用户 2026-10-24 定）＝「字＋所在小句」，不能只存字：多音字的读音随
 * 语境变，复习页要按小句展示定音。每条记录存：书 id、句 id（内容包句子 id，
 * 跳回原句的权威定位键）、小句在句内的起止（textT 的 UTF-16 下标＋汉字序号）、
 * 小句文本、错字、正确拼音（带调）、用户实际读出的音节（按音节累计次数）。
 * 聚合键＝（书＋句＋小句＋字位）；复习页按（书，字）分组、按（书，字）整组删除。
 *
 * 多读（INS）没有「错字」，单独存 extras（带小句上下文），不混进字组。
 */
public final class ReciteStats {

    public static final class Record {
        public String bookId, sentId, clauseText, hanzi, pyToned, type; // type: "sub" | "del"
        public int flat, clauseIdx;
        public int clauseTextStart, clauseTextEnd;     // 小句在句 textT 中的 UTF-16 起止
        public int clauseHanziStart, clauseHanziEnd;   // 小句的汉字序号起止
        public int ci;                                  // 错字在句内的汉字序号
        public int count;
        public final Map<String, Integer> heardCounts = new LinkedHashMap<>();
        public long firstTs, lastTs;

        public String key() {
            return bookId + "|" + sentId + "|" + clauseIdx + "|" + ci;
        }
    }

    /** 多读记录：某小句处多读了一个音节（无对应错字） */
    public static final class Extra {
        public String bookId, sentId, clauseText, heard;
        public int flat, clauseIdx, count;
        public long lastTs;
    }

    private final File file;
    private final Map<String, Record> records = new LinkedHashMap<>();
    private final List<Extra> extras = new ArrayList<>();
    private boolean dirty = false;

    private static ReciteStats instance;

    public static synchronized ReciteStats get(Context ctx) {
        if (instance == null) instance = new ReciteStats(ctx);
        return instance;
    }

    private ReciteStats(Context ctx) {
        file = new File(ctx.getFilesDir(), "recite_stats.json");
        load();
    }

    // ---------- 记录 ----------

    /** 记一次读错（SUB 读错字 / DEL 漏字）。unitIdx 为期望流下标。 */
    public synchronized void recordError(String bookId, ReciteData data, int unitIdx,
                                         String type, String heard) {
        if (data == null || unitIdx < 0 || unitIdx >= data.units.size()) return;
        ReciteData.Unit u = data.units.get(unitIdx);
        ReciteData.Clause cl = data.clauseOf(u.flat, u.clause);
        String sentId = u.flat < data.sentIds.size() ? data.sentIds.get(u.flat) : null;
        if (cl == null || sentId == null) return;
        String key = bookId + "|" + sentId + "|" + u.clause + "|" + u.ci;
        Record r = records.get(key);
        long now = System.currentTimeMillis();
        if (r == null) {
            r = new Record();
            r.bookId = bookId;
            r.sentId = sentId;
            r.flat = u.flat;
            r.clauseIdx = u.clause;
            r.clauseTextStart = cl.textStart;
            r.clauseTextEnd = cl.textEnd;
            r.clauseHanziStart = cl.hanziStart;
            r.clauseHanziEnd = cl.hanziEnd;
            r.clauseText = cl.text;
            r.ci = u.ci;
            r.hanzi = u.hanzi;
            r.pyToned = u.pyToned;
            r.type = type;
            r.count = 0;
            r.firstTs = now;
            records.put(key, r);
        }
        r.count++;
        r.lastTs = now;
        if (heard != null && !heard.isEmpty()) {
            Integer c = r.heardCounts.get(heard);
            r.heardCounts.put(heard, c == null ? 1 : c + 1);
        }
        dirty = true;
        save();
    }

    /** 记一次多读（INS）：挂在 unit 所在小句上，无错字 */
    public synchronized void recordExtra(String bookId, ReciteData data, int unitIdx,
                                         String heard) {
        if (data == null || heard == null || heard.isEmpty()) return;
        int ui = Math.max(0, Math.min(unitIdx, data.units.size() - 1));
        if (data.units.isEmpty()) return;
        ReciteData.Unit u = data.units.get(ui);
        ReciteData.Clause cl = data.clauseOf(u.flat, u.clause);
        String sentId = u.flat < data.sentIds.size() ? data.sentIds.get(u.flat) : null;
        if (cl == null || sentId == null) return;
        for (Extra e : extras) {
            if (e.bookId.equals(bookId) && e.sentId.equals(sentId)
                    && e.clauseIdx == u.clause && e.heard.equals(heard)) {
                e.count++;
                e.lastTs = System.currentTimeMillis();
                dirty = true;
                save();
                return;
            }
        }
        Extra e = new Extra();
        e.bookId = bookId;
        e.sentId = sentId;
        e.flat = u.flat;
        e.clauseIdx = u.clause;
        e.clauseText = cl.text;
        e.heard = heard;
        e.count = 1;
        e.lastTs = System.currentTimeMillis();
        extras.add(e);
        dirty = true;
        save();
    }

    // ---------- 查询 / 删除（复习页消费） ----------

    public synchronized List<Record> allRecords() {
        return new ArrayList<>(records.values());
    }

    public synchronized List<Extra> allExtras() {
        return new ArrayList<>(extras);
    }

    /** 删除某书下某个字的全部没读好记录（复习页长按「删除」）。返回删掉的条数。 */
    public synchronized int deleteBookChar(String bookId, String hanzi) {
        List<String> gone = new ArrayList<>();
        for (Record r : records.values()) {
            if (r.bookId.equals(bookId) && r.hanzi.equals(hanzi)) gone.add(r.key());
        }
        for (String k : gone) records.remove(k);
        if (!gone.isEmpty()) {
            dirty = true;
            save();
        }
        return gone.size();
    }

    public synchronized int totalErrorCount() {
        int n = 0;
        for (Record r : records.values()) n += r.count;
        return n;
    }

    // ---------- 落盘（tmp＋rename，避免写一半断电毁文件） ----------

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
                    r.bookId = o.getString("bookId");
                    r.sentId = o.getString("sentId");
                    r.flat = o.optInt("flat");
                    r.clauseIdx = o.optInt("clauseIdx");
                    r.clauseTextStart = o.optInt("clauseTextStart");
                    r.clauseTextEnd = o.optInt("clauseTextEnd");
                    r.clauseHanziStart = o.optInt("clauseHanziStart");
                    r.clauseHanziEnd = o.optInt("clauseHanziEnd");
                    r.clauseText = o.optString("clauseText", "");
                    r.ci = o.optInt("ci");
                    r.hanzi = o.optString("hanzi", "");
                    r.pyToned = o.optString("pyToned", "");
                    r.type = o.optString("type", "sub");
                    r.count = o.optInt("count", 1);
                    r.firstTs = o.optLong("firstTs");
                    r.lastTs = o.optLong("lastTs");
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
            JSONArray ex = root.optJSONArray("extras");
            if (ex != null) {
                for (int i = 0; i < ex.length(); i++) {
                    JSONObject o = ex.getJSONObject(i);
                    Extra e = new Extra();
                    e.bookId = o.getString("bookId");
                    e.sentId = o.getString("sentId");
                    e.flat = o.optInt("flat");
                    e.clauseIdx = o.optInt("clauseIdx");
                    e.clauseText = o.optString("clauseText", "");
                    e.heard = o.optString("heard", "");
                    e.count = o.optInt("count", 1);
                    e.lastTs = o.optLong("lastTs");
                    extras.add(e);
                }
            }
        } catch (Exception ignored) {
            // 文件损坏时从空开始（统计数据丢了可再攒，不值得让 App 起不来）
            records.clear();
            extras.clear();
        }
    }

    private void save() {
        if (!dirty) return;
        try {
            JSONObject root = new JSONObject();
            JSONArray arr = new JSONArray();
            for (Record r : records.values()) {
                JSONObject o = new JSONObject();
                o.put("bookId", r.bookId);
                o.put("sentId", r.sentId);
                o.put("flat", r.flat);
                o.put("clauseIdx", r.clauseIdx);
                o.put("clauseTextStart", r.clauseTextStart);
                o.put("clauseTextEnd", r.clauseTextEnd);
                o.put("clauseHanziStart", r.clauseHanziStart);
                o.put("clauseHanziEnd", r.clauseHanziEnd);
                o.put("clauseText", r.clauseText);
                o.put("ci", r.ci);
                o.put("hanzi", r.hanzi);
                o.put("pyToned", r.pyToned);
                o.put("type", r.type);
                o.put("count", r.count);
                o.put("firstTs", r.firstTs);
                o.put("lastTs", r.lastTs);
                JSONObject hc = new JSONObject();
                for (Map.Entry<String, Integer> en : r.heardCounts.entrySet()) {
                    hc.put(en.getKey(), en.getValue());
                }
                o.put("heard", hc);
                arr.put(o);
            }
            root.put("records", arr);
            JSONArray exArr = new JSONArray();
            for (Extra e : extras) {
                JSONObject o = new JSONObject();
                o.put("bookId", e.bookId);
                o.put("sentId", e.sentId);
                o.put("flat", e.flat);
                o.put("clauseIdx", e.clauseIdx);
                o.put("clauseText", e.clauseText);
                o.put("heard", e.heard);
                o.put("count", e.count);
                o.put("lastTs", e.lastTs);
                exArr.put(o);
            }
            root.put("extras", exArr);
            File tmp = new File(file.getParentFile(), "recite_stats.json.tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            out.close();
            if (!tmp.renameTo(file)) {
                // rename 失败时退回直写
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
