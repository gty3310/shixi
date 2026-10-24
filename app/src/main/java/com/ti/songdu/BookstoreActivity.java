package com.ti.songdu;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * v1.14.0 书库页（主页「添加书籍」钮打开）：
 *  上半是可滚动的线上目录（Gitee 私有仓 catalog.json）：每本书一行——书名、
 *  总大小、状态（未下载／下载中百分比／已下载）；点未下载整本下载，点已下载
 *  进入该书；已下载的书可在此删除（确认框写明只删文件、记录保留）。
 *  下半是「我愿意自己上传」：跳回主页打开原有的本地导入弹窗（音频＋字幕）。
 * 目录拉取失败显示错误与重试，不白屏、不崩溃。
 */
public class BookstoreActivity extends Activity {

    private Store store;
    private LinearLayout rowsBox;
    private TextView tvStatus;
    private Button btnRetry;
    private List<Bookstore.Book> books;
    /** 本次打开页面后拉到的目录缓存（进程内复用，切换页面不重复拉） */
    private static List<Bookstore.Book> cachedBooks;

    private static class RowViews {
        TextView status;
        Button action;
        TextView delete;
        ProgressBar progress;
    }

    private final Map<String, RowViews> rowViews = new HashMap<>();
    /** v1.14.1：每本书最近一次下载的最终失败原因——持久显示在该书行内
     *  （旧版只有一闪而过的 Toast，失败了也看不出为什么、在哪停的） */
    private final Map<String, String> lastErrors = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = Store.get(this);
        setContentView(buildUi());
        if (cachedBooks != null) {
            books = cachedBooks;
            renderBooks();
        } else {
            loadCatalog();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (books != null) renderBooks(); // 状态可能因下载完成/删除而变化
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.paper));

        // 顶栏：返回 + 标题
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(10), dp(16), dp(10));
        TextView back = new TextView(this);
        back.setText("‹ 返回");
        back.setTextSize(16);
        back.setTextColor(getColor(R.color.accent));
        back.setPadding(dp(8), dp(6), dp(12), dp(6));
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        TextView title = new TextView(this);
        title.setText("添加书籍");
        title.setTextSize(19);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(getColor(R.color.ink));
        LinearLayout.LayoutParams ttlLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ttlLp.leftMargin = dp(4);
        title.setLayoutParams(ttlLp);
        bar.addView(title);
        root.addView(bar);

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(4), dp(16), dp(24));
        scroll.addView(page);
        root.addView(scroll);

        // —— 线上目录区 ——
        page.addView(sectionTitle("在线书库"));
        TextView hint = smallText("点一本书，整本下载到手机里。下载完和内置书一样读、一样计数；"
                + "删掉也只是删文件，读过的遍数都还在。");
        page.addView(hint);

        tvStatus = new TextView(this);
        tvStatus.setTextSize(14);
        tvStatus.setTextColor(getColor(R.color.muted));
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.topMargin = dp(14);
        tvStatus.setLayoutParams(stLp);
        page.addView(tvStatus);

        btnRetry = pillButton("重试", false);
        btnRetry.setVisibility(View.GONE);
        btnRetry.setOnClickListener(v -> loadCatalog());
        LinearLayout.LayoutParams rtLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
        rtLp.topMargin = dp(10);
        btnRetry.setLayoutParams(rtLp);
        page.addView(btnRetry);

        rowsBox = new LinearLayout(this);
        rowsBox.setOrientation(LinearLayout.VERTICAL);
        page.addView(rowsBox);

        // —— 自传区（目录底下） ——
        TextView sec2 = sectionTitle("从本机添加");
        LinearLayout.LayoutParams sec2Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sec2Lp.topMargin = dp(28);
        sec2.setLayoutParams(sec2Lp);
        page.addView(sec2);
        page.addView(smallText("书库里没有的书，也可以自己准备音频（必需）和字幕（可选），从本机添加进来。"));
        Button btnUpload = pillButton("我愿意自己上传", true);
        LinearLayout.LayoutParams upLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(46));
        upLp.topMargin = dp(12);
        btnUpload.setLayoutParams(upLp);
        btnUpload.setOnClickListener(v -> {
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_OPEN_ADD, true));
            finish();
        });
        page.addView(btnUpload);
        return root;
    }

    private TextView sectionTitle(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(16);
        t.setTypeface(null, Typeface.BOLD);
        t.setTextColor(getColor(R.color.ink));
        return t;
    }

    private TextView smallText(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(12);
        t.setTextColor(getColor(R.color.muted));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    private Button pillButton(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(14);
        b.setBackgroundResource(primary ? R.drawable.pill_ink : R.drawable.pill_outline);
        b.setTextColor(primary ? 0xFFFFFFFF : getColor(R.color.ink));
        b.setPadding(dp(18), 0, dp(18), 0);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)));
        return b;
    }

    // ---------- 目录 ----------
    private void loadCatalog() {
        tvStatus.setText("正在加载目录…");
        btnRetry.setVisibility(View.GONE);
        rowsBox.removeAllViews();
        new Thread(() -> {
            try {
                final List<Bookstore.Book> got = Bookstore.fetchCatalog(this);
                cachedBooks = got;
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    books = got;
                    renderBooks();
                });
            } catch (final Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    tvStatus.setText("目录没拉下来：" + errText(e)
                            + "\n检查一下网络，再点重试。");
                    btnRetry.setVisibility(View.VISIBLE);
                });
            }
        }).start();
    }

    private String errText(Exception e) {
        String m = e.getMessage();
        return m != null && !m.isEmpty() ? m : "网络异常";
    }

    private void renderBooks() {
        rowsBox.removeAllViews();
        rowViews.clear();
        if (books == null) return;
        tvStatus.setText("");
        btnRetry.setVisibility(View.GONE);
        if (books.isEmpty()) {
            tvStatus.setText("目录是空的，暂时没有可下载的书。");
            return;
        }
        for (final Bookstore.Book b : books) {
            rowsBox.addView(buildRow(b));
        }
    }

    private View buildRow(final Bookstore.Book b) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.card_bg);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(10);
        card.setLayoutParams(cardLp);

        LinearLayout line1 = new LinearLayout(this);
        line1.setOrientation(LinearLayout.HORIZONTAL);
        line1.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(this);
        name.setText("《" + b.title + "》");
        name.setTextSize(17);
        name.setTextColor(getColor(R.color.ink));
        line1.addView(name);
        View sp = new View(this);
        sp.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1));
        line1.addView(sp);
        TextView size = new TextView(this);
        size.setText(fmtSize(b.totalSize));
        size.setTextSize(12);
        size.setTextColor(getColor(R.color.muted));
        line1.addView(size);
        card.addView(line1);

        LinearLayout line2 = new LinearLayout(this);
        line2.setOrientation(LinearLayout.HORIZONTAL);
        line2.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams l2Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l2Lp.topMargin = dp(8);
        line2.setLayoutParams(l2Lp);
        RowViews rv = new RowViews();
        rv.status = new TextView(this);
        rv.status.setTextSize(13);
        rv.status.setTextColor(getColor(R.color.muted));
        line2.addView(rv.status);
        View sp2 = new View(this);
        sp2.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1));
        line2.addView(sp2);
        rv.delete = new TextView(this);
        rv.delete.setText("删除");
        rv.delete.setTextSize(13);
        rv.delete.setTextColor(getColor(R.color.accent));
        rv.delete.setPadding(dp(10), dp(8), dp(10), dp(8));
        rv.delete.setOnClickListener(v -> Bookstore.confirmDeleteBook(this, b.id, this::renderBooks));
        line2.addView(rv.delete);
        rv.action = pillButton("", true);
        rv.action.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)));
        line2.addView(rv.action);
        card.addView(line2);

        rv.progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        rv.progress.setMax(100);
        rv.progress.setProgress(0);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        pbLp.topMargin = dp(8);
        rv.progress.setLayoutParams(pbLp);
        card.addView(rv.progress);

        rowViews.put(b.id, rv);
        refreshRow(b, rv);
        return card;
    }

    private void refreshRow(final Bookstore.Book b, final RowViews rv) {
        boolean downloading = Bookstore.isDownloading(b.id);
        Bookstore.BookStatus st = Bookstore.status(this, store, b);
        rv.progress.setVisibility(downloading ? View.VISIBLE : View.GONE);
        rv.action.setOnClickListener(null);
        if (downloading) {
            rv.status.setText("正在下载…");
            rv.action.setText("下载中");
            rv.action.setEnabled(false);
            rv.action.setAlpha(0.5f);
            rv.delete.setVisibility(View.GONE);
            return;
        }
        rv.action.setEnabled(true);
        rv.action.setAlpha(1f);
        if (st.isDownloaded()) {
            rv.status.setText("已下载");
            rv.action.setText("打开");
            rv.action.setOnClickListener(v -> enterBook(b));
            rv.delete.setVisibility(View.VISIBLE);
        } else if (st.isNone()) {
            rv.status.setText("未下载");
            rv.action.setText("下载");
            rv.action.setOnClickListener(v -> startDownload(b));
            rv.delete.setVisibility(View.GONE);
        } else {
            rv.status.setText(st.presentTracks >= st.totalTracks
                    ? "已下载音频，还缺文字包" : "下了一部分（" + st.presentTracks + "/" + st.totalTracks + "）");
            rv.action.setText("补全");
            rv.action.setOnClickListener(v -> startDownload(b));
            rv.delete.setVisibility(View.VISIBLE);
        }
        // v1.14.1：有未清除的失败原因时覆盖状态行（朱砂色），说明停在哪个文件、
        // 已下好的部分保留、再点按钮接着下；下载成功或重新开始下载时清除
        String err = lastErrors.get(b.id);
        if (err != null && !st.isDownloaded()) {
            rv.status.setText("下载失败：" + err + "。已下好的文件保留，再点「"
                    + rv.action.getText() + "」会接着下。");
            rv.status.setTextColor(getColor(R.color.accent));
        } else {
            rv.status.setTextColor(getColor(R.color.muted));
        }
    }

    private void startDownload(final Bookstore.Book b) {
        final RowViews rv = rowViews.get(b.id);
        lastErrors.remove(b.id); // 重新开始下载：旧失败原因清除
        if (rv != null) refreshRow(b, rv);
        Bookstore.downloadBookAsync(this, b, new Bookstore.ProgressListener() {
            @Override
            public void onProgress(final long done, final long total) {
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    RowViews r = rowViews.get(b.id);
                    if (r == null) return;
                    int pct = total > 0 ? (int) (done * 100 / total) : 0;
                    r.progress.setVisibility(View.VISIBLE);
                    r.progress.setProgress(pct);
                    r.status.setText("正在下载 " + pct + "%");
                });
            }

            @Override
            public void onFileRetry(final String path, final int attempt,
                                    final int maxAttempts, final String reason) {
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    RowViews r = rowViews.get(b.id);
                    if (r == null) return;
                    // 进度长时间不动时这里给出状态变化：正在重试哪个文件、第几次
                    r.progress.setVisibility(View.VISIBLE);
                    r.status.setText("网络中断，正在重试 " + fileBase(path)
                            + "（第 " + attempt + "/" + maxAttempts + " 次）…");
                });
            }

            @Override
            public void onDone(final boolean ok, final String error) {
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    if (ok) lastErrors.remove(b.id);
                    else lastErrors.put(b.id, error != null ? error : "网络异常");
                    Toast.makeText(BookstoreActivity.this,
                            ok ? "《" + b.title + "》下载完成" : "下载失败：" + error,
                            Toast.LENGTH_LONG).show();
                    renderBooks();
                });
            }
        });
        // downloadBookAsync 是异步登记，先等一拍再刷行状态（按钮变「下载中」）
        if (rv != null) {
            rv.action.post(() -> {
                if (!isFinishing()) refreshRow(b, rv);
            });
        }
    }

    /** 点已下载的书：有原文进阅读页；没有原文回主页列表（该书行就在那） */
    private void enterBook(Bookstore.Book b) {
        for (Bookstore.TrackSpec s : Bookstore.trackSpecs(b)) {
            Track t = store.findTrack(s.trackId);
            if (t == null) continue;
            if (ContentRepo.hasContent(t)) {
                startActivity(new Intent(this, ReaderActivity.class)
                        .putExtra(ReaderActivity.EXTRA_TRACK_ID, t.id));
            } else {
                startActivity(new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                finish();
            }
            return;
        }
        Toast.makeText(this, "这本书还没有下载完整", Toast.LENGTH_SHORT).show();
    }

    private static String fileBase(String path) {
        if (path == null) return "";
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }

    private String fmtSize(long bytes) {
        if (bytes <= 0) return "";
        double mb = bytes / 1048576.0;
        return mb >= 10
                ? String.format(Locale.ROOT, "约 %.0f MB", mb)
                : String.format(Locale.ROOT, "约 %.1f MB", mb);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
