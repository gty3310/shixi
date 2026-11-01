package com.ti.songdu;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.23.0 复习页「笔记」页签的聚合纯逻辑（无 Android 依赖，桌面 harness 直驱）。
 * 口径：只汇 note 正文非空的条目（纯标记条目不汇）；按书分组（9621 六书序在前，
 * 其余按首次出现序）；组内按 flat 句序升序。输入由 MainActivity 从 NotesStore
 * 读出并补齐书名/位置标签，本类只管过滤、分组、排序。
 */
public final class NotesDigest {

    public static final String[] CANON_ORDER = {
            "daxue", "zhongyong", "lunyu", "mengzi", "tanjing", "daodejing"};

    public static final class Entry {
        public String bookId, bookTitle, chapterId, sentId, sentText, posLabel, note;
        public int flat, mark;
        public long updatedAt;
    }

    public static final class Group {
        public String bookId, bookTitle;
        public final List<Entry> items = new ArrayList<>();
    }

    public static List<Group> group(List<Entry> in) {
        Map<String, Group> byBook = new LinkedHashMap<>();
        if (in != null) {
            for (Entry e : in) {
                if (e == null || e.bookId == null) continue;
                if (e.note == null || e.note.trim().isEmpty()) continue; // 纯标记/空笔记不汇
                Group g = byBook.get(e.bookId);
                if (g == null) {
                    g = new Group();
                    g.bookId = e.bookId;
                    g.bookTitle = e.bookTitle;
                    byBook.put(e.bookId, g);
                }
                g.items.add(e);
            }
        }
        List<Group> out = new ArrayList<>(byBook.values());
        for (Group g : out) g.items.sort((a, b) -> Integer.compare(a.flat, b.flat));
        out.sort((a, b) -> Integer.compare(canonIdx(a.bookId), canonIdx(b.bookId)));
        return out;
    }

    private static int canonIdx(String bookId) {
        for (int i = 0; i < CANON_ORDER.length; i++) if (CANON_ORDER[i].equals(bookId)) return i;
        return CANON_ORDER.length; // 未知书排在已知书之后（稳定排序保首次出现序）
    }

    private NotesDigest() {}
}
