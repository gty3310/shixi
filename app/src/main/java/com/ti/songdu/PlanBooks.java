package com.ti.songdu;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * v1.21.0 计划可选书目与本书口径（单位、章数、每章遍数、是否已下载）。
 * availability 实时判定、不写死：大学 = APK 内置；中庸/论语与其余书看本地
 * 内容包是否真的存在（ContentRepo.load 能否读到）；判定不通过即「还没下载」。
 */
public final class PlanBooks {

    public static final class BookInfo {
        public String bookId, title, unit, unitName;
        public int chapterCount, defaultPassesPerChapter;

        BookInfo(String id, String t, String u, String un, int cc, int n) {
            bookId = id; title = t; unit = u; unitName = un; chapterCount = cc;
            defaultPassesPerChapter = n;
        }
    }

    /** 已知经典（9621 六本在前，顺序即预置阶段序） */
    public static final List<BookInfo> KNOWN = new ArrayList<>();
    static {
        KNOWN.add(new BookInfo("daxue", "大学", PlanData.UNIT_WHOLE, "节", 12, 0));
        KNOWN.add(new BookInfo("zhongyong", "中庸", PlanData.UNIT_WHOLE, "节", 24, 0));
        KNOWN.add(new BookInfo("lunyu", "论语", PlanData.UNIT_ZHANG, "篇", 20, 5));
        KNOWN.add(new BookInfo("mengzi", "孟子", PlanData.UNIT_ZHANG, "卷", 14, 3));
        KNOWN.add(new BookInfo("tanjing", "六祖坛经", PlanData.UNIT_ZHANG, "品", 10, 5));
        KNOWN.add(new BookInfo("daodejing", "道德经", PlanData.UNIT_ZHANG, "章", 81, 2));
    }

    public static BookInfo infoOf(String bookId) {
        if (bookId == null) return null;
        for (BookInfo b : KNOWN) if (b.bookId.equals(bookId)) return b;
        return null;
    }

    /** 实时判定：这本书在本地是否已有（可自动统计） */
    public static boolean isAvailable(Context ctx, String bookId) {
        if (bookId == null) return false;
        if ("daxue".equals(bookId)) return true; // APK 内置
        try {
            return ContentRepo.load(ctx, bookId) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** 曲目 -> 计划书 id（补 Bookstore/ContentRepo 未覆盖的大学预置轨） */
    public static String bookIdOfTrackFull(Track t) {
        if (t == null) return null;
        String b = Bookstore.bookIdOfTrack(t);
        if (b != null) return b;
        if ("preset-daxue-full".equals(t.id)) return "daxue";
        return ContentRepo.bookIdFor(t);
    }

    /** 某书在曲目列表里的全部曲目（整本时长/遍数聚合用） */
    public static List<Track> tracksOfBook(Store store, String bookId) {
        List<Track> out = new ArrayList<>();
        for (Track t : store.tracks) if (bookId.equals(bookIdOfTrackFull(t))) out.add(t);
        return out;
    }

    /** 某书整本一遍的时长（ms，曲目时长合计）；无曲目返回 0 */
    public static long bookPassDurationMs(Store store, String bookId) {
        long sum = 0;
        for (Track t : tracksOfBook(store, bookId)) sum += t.durationMs;
        return sum;
    }

    /** 按书自动带出的阶段骨架（unit/unitName/chapterCount/N 按本书口径） */
    public static PlanData.Stage stageSkeleton(String bookId, String title, int totalPasses) {
        PlanData.Stage s = new PlanData.Stage();
        s.bookId = bookId;
        BookInfo info = infoOf(bookId);
        s.bookTitle = title != null ? title : (info != null ? info.title : bookId);
        if (info != null) {
            s.unit = info.unit;
            s.unitName = info.unitName;
            s.chapterCount = info.chapterCount;
            s.passesPerChapter = info.defaultPassesPerChapter;
        } else {
            s.unit = PlanData.UNIT_WHOLE;
            s.unitName = "";
            s.chapterCount = 0;
            s.passesPerChapter = 0;
        }
        s.totalPasses = totalPasses;
        s.totalUnits = s.isZhang() ? s.chapterCount * totalPasses : totalPasses;
        return s;
    }

    private PlanBooks() {}
}
