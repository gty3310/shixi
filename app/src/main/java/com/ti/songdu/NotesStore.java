package com.ti.songdu;

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
 * v1.16.0 点句笔记＋颜色标记的本地库（files/notes.json）。
 * 与识读统计 recite_stats.json 分开存储、互不污染；删书/删音频不删笔记
 * （口径同「删书不动 records」）。
 *
 * 一句一条：唯一键（bookId＋sentId）。字段：所在章 id、句在打开书中的 flat
 * （仅呈现参考，定位以 sentId 为准）、整句文本（繁体，复习页展示用）、
 * 标记色（0 无 / 1 浅黄 / 2 浅绿 / 3 浅蓝）、笔记正文（可空）、时间。
 * 标记取消且笔记为空时整条自动删除（不留空条目）。
 */
public final class NotesStore {

    public static final int MARK_NONE = 0;
    public static final int MARK_YELLOW = 1;
    public static final int MARK_GREEN = 2;
    public static final int MARK_BLUE = 3;

    public static final String[] MARK_NAMES = {"", "浅黄标记", "浅绿标记", "浅蓝标记"};

    public static final class Item {
        public String bookId, chapterId, sentId, sentText;
        public int flat;
        public int mark = MARK_NONE;
        public String note = "";
        public long createdAt, updatedAt;

        public String key() {
            return bookId + "|" + sentId;
        }

        public boolean isEmpty() {
            return mark == MARK_NONE && (note == null || note.trim().isEmpty());
        }
    }

    private final File file;
    private final Map<String, Item> items = new LinkedHashMap<>();

    private static NotesStore instance;

    public static synchronized NotesStore get(Context ctx) {
        if (instance == null) instance = new NotesStore(ctx);
        return instance;
    }

    private NotesStore(Context ctx) {
        file = new File(ctx.getFilesDir(), "notes.json");
        load();
    }

    public synchronized Item getItem(String bookId, String sentId) {
        if (bookId == null || sentId == null) return null;
        return items.get(bookId + "|" + sentId);
    }

    /** 写入/更新一句的笔记与标记；变空（无标记＋无笔记）则整条删除 */
    public synchronized void upsert(String bookId, String chapterId, String sentId,
                                    int flat, String sentText, int mark, String note) {
        if (bookId == null || sentId == null) return;
        String key = bookId + "|" + sentId;
        Item it = items.get(key);
        long now = System.currentTimeMillis();
        if (it == null) {
            it = new Item();
            it.bookId = bookId;
            it.sentId = sentId;
            it.createdAt = now;
            items.put(key, it);
        }
        it.chapterId = chapterId;
        it.flat = flat;
        if (sentText != null) it.sentText = sentText;
        it.mark = mark;
        it.note = note == null ? "" : note;
        it.updatedAt = now;
        if (it.isEmpty()) items.remove(key);
        save();
    }

    public synchronized void remove(String bookId, String sentId) {
        if (items.remove(bookId + "|" + sentId) != null) save();
    }

    /** 全部条目，按更新时间倒序（复习页「我的笔记」用） */
    public synchronized List<Item> allDesc() {
        List<Item> out = new ArrayList<>(items.values());
        out.sort((a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        return out;
    }

    /** 某书的标记映射 sentId → mark（阅读页渲染时注入） */
    public synchronized Map<String, Integer> marksForBook(String bookId) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Item it : items.values()) {
            if (it.bookId.equals(bookId) && it.mark != MARK_NONE) {
                out.put(it.sentId, it.mark);
            }
        }
        return out;
    }

    // ---------- 落盘（tmp＋rename，同 ReciteStats 口径） ----------

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
            JSONArray arr = root.optJSONArray("items");
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Item it = new Item();
                it.bookId = o.getString("bookId");
                it.sentId = o.getString("sentId");
                it.chapterId = o.optString("chapterId", "");
                it.flat = o.optInt("flat");
                it.sentText = o.optString("sentText", "");
                it.mark = o.optInt("mark", MARK_NONE);
                it.note = o.optString("note", "");
                it.createdAt = o.optLong("createdAt");
                it.updatedAt = o.optLong("updatedAt");
                items.put(it.key(), it);
            }
        } catch (Exception ignored) {
            items.clear();
        }
    }

    private void save() {
        try {
            JSONObject root = new JSONObject();
            JSONArray arr = new JSONArray();
            for (Item it : items.values()) {
                JSONObject o = new JSONObject();
                o.put("bookId", it.bookId);
                o.put("sentId", it.sentId);
                o.put("chapterId", it.chapterId == null ? "" : it.chapterId);
                o.put("flat", it.flat);
                o.put("sentText", it.sentText == null ? "" : it.sentText);
                o.put("mark", it.mark);
                o.put("note", it.note == null ? "" : it.note);
                o.put("createdAt", it.createdAt);
                o.put("updatedAt", it.updatedAt);
                arr.put(o);
            }
            root.put("items", arr);
            byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
            File tmp = new File(file.getParentFile(), "notes.json.tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(bytes);
            out.close();
            if (!tmp.renameTo(file)) {
                FileOutputStream out2 = new FileOutputStream(file);
                out2.write(bytes);
                out2.close();
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Exception ignored) {
        }
    }
}
