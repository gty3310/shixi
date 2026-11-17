package com.ti.songdu;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.time.LocalDate;

/**
 * v1.21.0 章即小书：一本书展开为章卡片列表（设计方案三之补）。
 * 每章：自动编号 + 章题、累计/今日遍数、上次日期、约时长；
 * 动作：▶ 章级播放（章首到章尾）、读（进阅读页落到该章首句）、手动补记 +1 遍、
 * 循环范围切换（整书/单章，reader_prefs/loopScope 持久化，与阅读页同源）。
 */
public class ChapterListActivity extends Activity {

    public static final String EXTRA_BOOK_ID = "chapter_book_id";

    private String bookId;
    private String unitName = "章";
    private Store store;
    private ContentRepo.Book book;
    private LinearLayout list;
    private TextView loopScopeBtn, hint, wholeCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chapter_list);
        bookId = getIntent() != null ? getIntent().getStringExtra(EXTRA_BOOK_ID) : null;
        store = Store.get(this);
        list = findViewById(R.id.chList);
        loopScopeBtn = findViewById(R.id.chLoopScope);
        hint = findViewById(R.id.chHint);
        wholeCount = findViewById(R.id.chWholeCount);
        findViewById(R.id.chWholeMinus).setOnClickListener(v -> adjustWhole(-1));
        findViewById(R.id.chWholePlus).setOnClickListener(v -> adjustWhole(1));
        findViewById(R.id.chBack).setOnClickListener(v -> finish());
        PlanBooks.BookInfo info = PlanBooks.infoOf(bookId);
        if (info != null) unitName = info.unitName;
        TextView title = findViewById(R.id.chTitle);
        book = bookId != null ? ContentRepo.load(this, bookId) : null;
        // 双声音书已选 AI 声音时，章界与起播位置走该声音时间轴（与 PlayerService 同口径）
        // v1.25.0：泛化到正音版 aizz
        if (book != null && VoicePref.isAssetVoice(VoicePref.getVoice(this, bookId))) {
            ContentRepo.Book vb = ContentRepo.loadVoice(this, bookId,
                    VoicePref.getVoice(this, bookId));
            if (vb != null) book = vb;
        }
        String t = info != null ? info.title : (book != null ? book.title : bookId);
        title.setText("《" + t + "》 · " + unitName);
        hint.setText("播到章尾自动记一遍 · 补记标手动");
        loopScopeBtn.setOnClickListener(v -> {
            boolean nowChapter = !isScopeChapter();
            SharedPreferences rp = getSharedPreferences("reader_prefs", MODE_PRIVATE);
            rp.edit().putString("loopScope", nowChapter ? "chapter" : "book").apply();
            if (PlayerService.instance != null) PlayerService.instance.setLoopScope(nowChapter);
            refreshScopeBtn();
            Toast.makeText(this, nowChapter ? "单章循环" : "整书循环", Toast.LENGTH_SHORT).show();
        });
        refreshScopeBtn();
        buildList();
    }

    @Override
    protected void onResume() {
        super.onResume();
        buildList();
    }

    private boolean isScopeChapter() {
        if (PlayerService.instance != null) return PlayerService.instance.loopScopeChapter;
        return "chapter".equals(getSharedPreferences("reader_prefs", MODE_PRIVATE)
                .getString("loopScope", "book"));
    }

    private void refreshScopeBtn() {
        loopScopeBtn.setText(isScopeChapter() ? "循环范围 单章" : "循环范围 整书");
    }

    private Track trackForChapter(int chapterIndex) {
        if ("lunyu".equals(bookId)) {
            String id = String.format(java.util.Locale.ROOT, "preset-lunyu-%02d", chapterIndex + 1);
            Track t = store.findTrack(id);
            if (t != null) return t;
        }
        java.util.List<Track> ts = PlanBooks.tracksOfBook(store, bookId);
        return ts.isEmpty() ? null : ts.get(0);
    }

    /** v1.26.0 整本增减：对每一章各增减 1 遍今日（逐章下限 0），随后整表刷新 */
    private void adjustWhole(int delta) {
        if (book == null) return;
        ChapterStatsStore stats = ChapterStatsStore.get(this);
        LocalDate today = LocalDate.now();
        for (int i = 0; i < book.chapters.size(); i++) {
            stats.adjustChapterToday(bookId, i, today, delta);
        }
        buildList();
    }

    private void buildList() {
        list.removeAllViews();
        if (book == null) {
            if (wholeCount != null) wholeCount.setText("0.0");
            TextView tv = new TextView(this);
            tv.setText("这本书还没下载");
            tv.setTextSize(15);
            tv.setTextColor(getColor(R.color.muted));
            list.addView(tv);
            return;
        }
        ChapterStatsStore stats = ChapterStatsStore.get(this);
        LocalDate today = LocalDate.now();
        // v1.26.0 页顶整本今日遍数：Σ各章今日 ÷ 章数（BookCountFormat 折算）
        int sumToday = 0;
        for (int i = 0; i < book.chapters.size(); i++) {
            sumToday += stats.chapterToday(bookId, i, today);
        }
        if (wholeCount != null) {
            wholeCount.setText(BookCountFormat.wholeBookToday(sumToday, book.chapters.size()));
        }
        Track wholeTrack = trackForChapter(0);
        double scale = wholeTrack != null ? ContentRepo.scaleFor(wholeTrack, book) : 1.0;
        for (int i = 0; i < book.chapters.size(); i++) {
            ContentRepo.Chapter ch = book.chapters.get(i);
            long startMs = -1, endMs = -1;
            String firstSentId = null;
            for (ContentRepo.Sentence s : ch.sentences) {
                if (firstSentId == null) firstSentId = s.id;
                if (s.noAudio || s.startMs < 0) continue;
                if (startMs < 0) startMs = s.startMs;
                if (s.endMs > endMs) endMs = s.endMs;
            }
            final int idx = i;
            final long rawStart = startMs, rawEnd = endMs;
            final String sentId = firstSentId;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundResource(R.drawable.card_bg);
            card.setPadding(dp(14), dp(12), dp(14), dp(12));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.bottomMargin = dp(10);
            card.setLayoutParams(clp);

            LinearLayout line1 = new LinearLayout(this);
            line1.setOrientation(LinearLayout.HORIZONTAL);
            line1.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = new TextView(this);
            String chTitle = ch.title != null && !ch.title.trim().isEmpty() ? " · " + ch.title.trim() : "";
            name.setText("第 " + (i + 1) + " " + unitName + chTitle);
            name.setTextSize(16);
            name.setTypeface(null, Typeface.BOLD);
            name.setTextColor(getColor(R.color.ink));
            name.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            line1.addView(name);
            int total = stats.chapterTotal(bookId, i);
            TextView cnt = new TextView(this);
            cnt.setText("已读 " + total + " 遍");
            cnt.setTextSize(14);
            cnt.setTextColor(getColor(R.color.accent));
            line1.addView(cnt);
            card.addView(line1);

            TextView sub = new TextView(this);
            int todayCnt = stats.chapterToday(bookId, i, today);
            LocalDate last = stats.chapterLastDate(bookId, i);
            StringBuilder sb = new StringBuilder();
            sb.append("今日 ").append(todayCnt).append(" 遍");
            if (last != null) sb.append(" · 上次 ").append(last.getMonthValue()).append(" 月 ")
                    .append(last.getDayOfMonth()).append(" 日");
            if (rawStart >= 0 && rawEnd > rawStart) {
                long mins = Math.round((rawEnd - rawStart) / 60000.0);
                sb.append(" · 约 ").append(Math.max(1, mins)).append(" 分钟");
            }
            sub.setText(sb.toString());
            sub.setTextSize(12.5f);
            sub.setTextColor(getColor(R.color.muted));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dp(4);
            sub.setLayoutParams(slp);
            card.addView(sub);

            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            alp.topMargin = dp(8);
            actions.setLayoutParams(alp);
            TextView btnPlay = actionBtn("▶ 播本" + unitName);
            btnPlay.setOnClickListener(v -> playChapter(idx, rawStart, rawEnd, scale));
            actions.addView(btnPlay);
            TextView btnRead = actionBtn("读");
            btnRead.setOnClickListener(v -> openReader(idx, sentId));
            actions.addView(btnRead);
            TextView btnAdd = actionBtn("补记 +1 遍");
            btnAdd.setOnClickListener(v -> {
                ChapterStatsStore.get(this).addManual(bookId, idx, LocalDate.now(), 1);
                Toast.makeText(this, "已补记 1 遍（手动）", Toast.LENGTH_SHORT).show();
                buildList();
            });
            actions.addView(btnAdd);
            TextView btnMinus = actionBtn("−");
            btnMinus.setOnClickListener(v -> {
                ChapterStatsStore.get(this).adjustChapterToday(bookId, idx, LocalDate.now(), -1);
                buildList();
            });
            actions.addView(btnMinus);
            TextView btnPlus = actionBtn("＋");
            btnPlus.setOnClickListener(v -> {
                ChapterStatsStore.get(this).adjustChapterToday(bookId, idx, LocalDate.now(), 1);
                buildList();
            });
            actions.addView(btnPlus);
            card.addView(actions);
            list.addView(card);
        }
    }

    private TextView actionBtn(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13.5f);
        tv.setTextColor(getColor(R.color.ink));
        tv.setBackgroundResource(R.drawable.pill_outline);
        tv.setPadding(dp(12), dp(6), dp(12), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void playChapter(int idx, long rawStart, long rawEnd, double scale) {
        Track t = trackForChapter(idx);
        if (t == null || rawStart < 0 || rawEnd <= rawStart) {
            Toast.makeText(this, "这一" + unitName + "还没有音频", Toast.LENGTH_SHORT).show();
            return;
        }
        long start = Math.round(rawStart * scale);
        long end = Math.round(rawEnd * scale);
        Intent i = new Intent(this, PlayerService.class)
                .setAction(PlayerService.ACTION_PLAY_CHAPTER)
                .putExtra(PlayerService.EXTRA_TRACK_ID, t.id)
                .putExtra(PlayerService.EXTRA_CH_BOOK, bookId)
                .putExtra(PlayerService.EXTRA_CH_INDEX, idx)
                .putExtra(PlayerService.EXTRA_CH_START, start)
                .putExtra(PlayerService.EXTRA_CH_END, end);
        startService(i);
        Toast.makeText(this, "播第 " + (idx + 1) + " " + unitName, Toast.LENGTH_SHORT).show();
    }

    private void openReader(int idx, String sentId) {
        Track t = trackForChapter(idx);
        if (t == null) {
            Toast.makeText(this, "这本书不在列表里", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(this, ReaderActivity.class)
                .putExtra(ReaderActivity.EXTRA_TRACK_ID, t.id);
        if (sentId != null) i.putExtra(ReaderActivity.EXTRA_SCROLL_SENT_ID, sentId);
        startActivity(i);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
