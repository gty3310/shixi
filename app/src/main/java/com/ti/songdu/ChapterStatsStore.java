package com.ti.songdu;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Iterator;

/**
 * v1.21.0 章遍数账（files/chapter_stats.json，仿 ReciteStats/NotesStore 范式）：
 * 按 书 × 章序号 × 日期 记 {auto, manual} 两路；计划只读汇总；删书不删账。
 * 结构：{ "bookId|chapterIndex|yyyy-MM-dd": {"a": 自动, "m": 手动} }
 */
public final class ChapterStatsStore {

    private final File file;
    private JSONObject root = new JSONObject();

    private static ChapterStatsStore instance;

    public static synchronized ChapterStatsStore get(Context ctx) {
        if (instance == null) instance = new ChapterStatsStore(ctx);
        return instance;
    }

    private ChapterStatsStore(Context ctx) {
        file = new File(ctx.getApplicationContext().getFilesDir(), "chapter_stats.json");
        load();
    }

    private static String key(String bookId, int chapterIndex, LocalDate date) {
        return bookId + "|" + chapterIndex + "|" + date.toString();
    }

    public synchronized void addAuto(String bookId, int chapterIndex, LocalDate date, int n) {
        add(bookId, chapterIndex, date, n, true);
    }

    public synchronized void addManual(String bookId, int chapterIndex, LocalDate date, int n) {
        add(bookId, chapterIndex, date, n, false);
    }

    private void add(String bookId, int idx, LocalDate date, int n, boolean auto) {
        if (bookId == null || idx < 0 || n == 0) return;
        try {
            String k = key(bookId, idx, date);
            JSONObject e = root.optJSONObject(k);
            if (e == null) e = new JSONObject();
            e.put(auto ? "a" : "m", e.optInt(auto ? "a" : "m", 0) + n);
            if (e.optInt("a", 0) <= 0 && e.optInt("m", 0) <= 0) root.remove(k);
            else root.put(k, e);
            save();
        } catch (Exception ignored) {
        }
    }

    private int entryVal(String k, String field) {
        JSONObject e = root.optJSONObject(k);
        return e == null ? 0 : e.optInt(field, 0);
    }

    /** 某章累计（自动+手动，全部日期） */
    public synchronized int chapterTotal(String bookId, int chapterIndex) {
        int sum = 0;
        String prefix = bookId + "|" + chapterIndex + "|";
        Iterator<String> it = root.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (k.startsWith(prefix)) sum += entryVal(k, "a") + entryVal(k, "m");
        }
        return sum;
    }

    /** 某章今日（自动+手动） */
    public synchronized int chapterToday(String bookId, int chapterIndex, LocalDate today) {
        String k = key(bookId, chapterIndex, today);
        return entryVal(k, "a") + entryVal(k, "m");
    }

    /** 某书某日全部章合计（计划章账型 done 的日口径） */
    public synchronized int bookTotalOn(String bookId, LocalDate date) {
        int sum = 0;
        String suffix = "|" + date.toString();
        String prefix = bookId + "|";
        Iterator<String> it = root.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (k.startsWith(prefix) && k.endsWith(suffix)) sum += entryVal(k, "a") + entryVal(k, "m");
        }
        return sum;
    }

    /** 某书全部日期合计 */
    public synchronized int bookTotal(String bookId) {
        int sum = 0;
        String prefix = bookId + "|";
        Iterator<String> it = root.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (k.startsWith(prefix)) sum += entryVal(k, "a") + entryVal(k, "m");
        }
        return sum;
    }

    /** 某天全部书章账合计（计划页近况热力格用） */
    public synchronized int allBooksTotalOn(LocalDate date) {
        int sum = 0;
        String suffix = "|" + date.toString();
        Iterator<String> it = root.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (k.endsWith(suffix)) sum += entryVal(k, "a") + entryVal(k, "m");
        }
        return sum;
    }

    /** 某章最近一次有记录的日期；无记录返回 null */
    public synchronized LocalDate chapterLastDate(String bookId, int chapterIndex) {
        String prefix = bookId + "|" + chapterIndex + "|";
        LocalDate best = null;
        Iterator<String> it = root.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (!k.startsWith(prefix)) continue;
            if (entryVal(k, "a") + entryVal(k, "m") <= 0) continue;
            try {
                LocalDate d = LocalDate.parse(k.substring(prefix.length()));
                if (best == null || d.isAfter(best)) best = d;
            } catch (Exception ignored) {
            }
        }
        return best;
    }

    // ---------- 落盘（tmp + rename，原子写） ----------

    private void load() {
        if (!file.exists()) return;
        try {
            FileInputStream in = new FileInputStream(file);
            byte[] buf = new byte[(int) file.length()];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            in.close();
            root = new JSONObject(new String(buf, 0, off, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            root = new JSONObject();
        }
    }

    private void save() {
        try {
            byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
            File tmp = new File(file.getParentFile(), "chapter_stats.json.tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(bytes);
            out.close();
            if (!tmp.renameTo(file)) {
                FileOutputStream out2 = new FileOutputStream(file);
                out2.write(bytes);
                out2.close();
                tmp.delete();
            }
        } catch (Exception ignored) {
        }
    }
}
