package com.ti.songdu;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * v1.4 阅读页：正文在 WebView 里用本地模板（assets/reader.html）＋内容包 JSON 渲染，
 * 无任何网络请求。渲染口径（已确认的 mockup）：
 *  - 按章分段：每章一个大段落、章题单独呈现（横排段首行、竖排另起一列），章内连排不断行；
 *  - 默认繁体＋字顶拼音（ruby），可切简体，拼音可关；字号四档；横排/竖排可切；
 *    四种设置（繁简、拼音、字号、横竖）独立组合，各自存 SharedPreferences 持久化。
 * 播放仍走同一个 PlayerService（与主页、通知栏同一状态源）：
 *  - 点有时间的句子 → playTrackAt 到该句 startMs−150ms（缩放后的时间）；
 *  - 每 150ms 轮询位置、对有时间的句子二分查找当前句 → JS 高亮＋翻页式跟随
 *    （v1.4.2：当前句在可视区内不滚动，超出才整页翻到页首，实现在 reader.html 的
 *    pageToSentence；不再用居中跟随）；
 *    用户手动滚动（touchmove/wheel）后暂停跟随，6 秒后自动恢复或点顶栏「⇓ 跟随」立即恢复
 *    （恢复时同样按翻页式定位）；
 *  - 轻点正文非句子区域 → 沉浸模式（隐藏顶栏与播放栏），再点任意处恢复；
 *  - 纯读计数：全文末尾有「已读到末尾 · 点此记一遍」按钮（在文档最末端，必须真正
 *    滚到结尾才点得到，防误触），点击才走 Store.increment 给今日 +1，与听读共用一本账，
 *    随后弹「已读完一遍」对话框（回到第一页 / 再读一遍）。
 * 朱子补传等 noAudio 句子：照常显示，不可点播、不参与高亮。
 * v1.4.3 跟读模式：底部栏第二行加「跟读」开关钮（状态机与持久化都在 PlayerService，
 * 本页只切换与呈现：开启态朱砂实心 pill＋白字）。跟读的播放/暂停节奏由服务驱动，
 * 本页高亮仍按位置走（gap 中位置停在句末，高亮天然不跳走），另经 setGap 给当前句
 * 加一层区别（底色转金黄＋下划线变虚线），提示用户「现在轮到你复诵」。
 */
public class ReaderActivity extends Activity implements PlayerService.Listener {

    public static final String EXTRA_TRACK_ID = "reader_track_id";

    private static final float[] SPEEDS = {0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f};
    private static final String[] SPEED_LABELS = {"0.75×", "1.0×", "1.25×", "1.5×", "2.0×", "3.0×"};
    private static final String[] FONT_LABELS = {"小", "中", "大", "特大"};

    private Store store;
    private Track track;
    private ContentRepo.Book book;
    private double scale = 1.0;

    // 有时间句子的缩放后时间轴（与 flat 下标的映射），供二分查找当前句
    private long[] tStart = new long[0];
    private long[] tEnd = new long[0];
    private int[] tFlat = new int[0];

    private WebView web;
    private View topBar, bottomBar;
    private TextView btnRToggle, btnRSpeed, btnRLoop, btnRScript, btnRPinyin, btnROrient, btnFollow;
    private TextView btnRFollow;
    /** v1.7 跟读字数小钮（跟读开时显示，如「15字」）与底部说明行 */
    private TextView btnRFollowChars;
    private TextView readerFollowHint;
    private Boolean lastFollowOn = null;
    private Integer lastChunkChars = null;
    private boolean lastGapOn = false;

    // 阅读设置（各自独立持久化）
    private SharedPreferences prefs;
    private boolean useTrad, pinyinOn, vertical, loopOn;
    private int fontIdx;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int curSentence = -1;
    private boolean followPaused = false;
    private long followResumeAt = 0;
    private boolean finishDialogShowing = false;
    private String lastToggleLabel = null;
    private String lastSpeedLabel = null;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            pollOnce();
            handler.postDelayed(this, 150);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reader);
        store = Store.get(this);

        String trackId = getIntent().getStringExtra(EXTRA_TRACK_ID);
        track = store.findTrack(trackId);
        if (track == null) {
            finish();
            return;
        }
        // v1.5：论语按篇打开——bookForTrack 对论语曲目返回只含该篇的 Book（篇内时间轴）
        book = ContentRepo.bookForTrack(this, track);
        if (book == null) {
            Toast.makeText(this, "该音频暂无原文", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        scale = ContentRepo.scaleFor(track, book);
        buildTimeline();

        prefs = getSharedPreferences("reader_prefs", MODE_PRIVATE);
        useTrad = prefs.getBoolean("trad", true);
        pinyinOn = prefs.getBoolean("pinyin", true);
        vertical = prefs.getBoolean("vertical", false);
        fontIdx = prefs.getInt("font", 1);
        loopOn = prefs.getBoolean("loop", true);

        topBar = findViewById(R.id.readerTopBar);
        bottomBar = findViewById(R.id.readerBottomBar);
        btnRToggle = findViewById(R.id.btnRToggle);
        btnRSpeed = findViewById(R.id.btnRSpeed);
        btnRLoop = findViewById(R.id.btnRLoop);
        btnRFollow = findViewById(R.id.btnRFollow);
        btnRFollowChars = findViewById(R.id.btnRFollowChars);
        readerFollowHint = findViewById(R.id.readerFollowHint);
        btnRScript = findViewById(R.id.btnRScript);
        btnRPinyin = findViewById(R.id.btnRPinyin);
        btnROrient = findViewById(R.id.btnROrient);
        btnFollow = findViewById(R.id.btnFollow);
        TextView title = findViewById(R.id.readerTitle);
        title.setText(track.title);

        web = findViewById(R.id.readerWeb);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setTextZoom(100);
        ws.setBuiltInZoomControls(false);
        ws.setDisplayZoomControls(false);
        web.addJavascriptInterface(new Bridge(), "Bridge");
        web.setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // 轮询可能早于页面渲染完成：加载完后把当前句高亮补发一次，避免错过
                if (curSentence >= 0) {
                    web.evaluateJavascript("setCur(" + curSentence + ",false)", null);
                }
            }
        });
        web.loadDataWithBaseURL(null, buildHtml(), "text/html", "UTF-8", null);

        findViewById(R.id.btnReaderBack).setOnClickListener(v -> finish());
        btnFollow.setOnClickListener(v -> resumeFollow(true));
        btnRToggle.setOnClickListener(v -> onToggleThisTrack());
        findViewById(R.id.btnRBack15).setOnClickListener(v -> sendSeekRel(-15000));
        findViewById(R.id.btnRFwd15).setOnClickListener(v -> sendSeekRel(15000));
        btnRSpeed.setOnClickListener(v -> cycleSpeed());
        btnRLoop.setOnClickListener(v -> {
            loopOn = !loopOn;
            prefs.edit().putBoolean("loop", loopOn).apply();
            applyLoop();
            refreshBarLabels();
        });
        btnRFollow.setOnClickListener(v -> {
            // 跟读开关的真源在 PlayerService（follow_prefs 持久化）；
            // 服务未启动时直接写同一份 prefs，服务起来后 onCreate 会读到
            PlayerService svc = PlayerService.instance;
            boolean now;
            if (svc != null) {
                now = !svc.followEnabled;
                svc.setFollowEnabled(now);
            } else {
                SharedPreferences fp = getSharedPreferences("follow_prefs", MODE_PRIVATE);
                now = !fp.getBoolean("enabled", false);
                fp.edit().putBoolean("enabled", now).apply();
            }
            lastFollowOn = now;
            refreshFollowButton(now);
            Toast.makeText(this, now ? "跟读已开启：听一段，停一段等你复诵"
                    : "跟读已关闭", Toast.LENGTH_SHORT).show();
        });
        btnRFollowChars.setOnClickListener(v -> showChunkCharsDialog());
        findViewById(R.id.btnRFont).setOnClickListener(v -> showFontPanel());
        btnRScript.setOnClickListener(v -> {
            useTrad = !useTrad;
            prefs.edit().putBoolean("trad", useTrad).apply();
            web.evaluateJavascript("setTrad(" + useTrad + ")", null);
            refreshBarLabels();
        });
        btnRPinyin.setOnClickListener(v -> {
            pinyinOn = !pinyinOn;
            prefs.edit().putBoolean("pinyin", pinyinOn).apply();
            web.evaluateJavascript("setPinyin(" + pinyinOn + ")", null);
            refreshBarLabels();
        });
        btnROrient.setOnClickListener(v -> {
            vertical = !vertical;
            prefs.edit().putBoolean("vertical", vertical).apply();
            web.evaluateJavascript("setVert(" + vertical + ")", null);
            refreshBarLabels();
        });
        applyLoop();
        refreshBarLabels();
        boolean fOn = PlayerService.instance != null
                ? PlayerService.instance.followEnabled
                : getSharedPreferences("follow_prefs", MODE_PRIVATE).getBoolean("enabled", false);
        lastFollowOn = fOn;
        refreshFollowButton(fOn);
    }

    /** 跟读开关钮呈现：开＝朱砂实心 pill＋白字加粗（与速度/循环的浅金文字明显区分），关＝无底浅金字 */
    private void refreshFollowButton(boolean on) {
        if (btnRFollow == null) return;
        btnRFollow.setText(on ? "跟读 开" : "跟读 关");
        float density = getResources().getDisplayMetrics().density;
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(999 * density);
        if (on) {
            g.setColor(0xFF9E3B2B);
            btnRFollow.setTextColor(0xFFFFFFFF);
            btnRFollow.setTypeface(null, Typeface.BOLD);
        } else {
            g.setColor(0x00000000);
            btnRFollow.setTextColor(0xFFF0CDA0);
            btnRFollow.setTypeface(null, Typeface.NORMAL);
        }
        btnRFollow.setBackground(g);
        refreshFollowExtras(on);
    }

    /** 当前每段字数：服务在场以服务为准，否则读 follow_prefs（默认 15，0＝整句） */
    private int chunkCharsNow() {
        PlayerService svc = PlayerService.instance;
        if (svc != null) return svc.followChunkChars;
        return getSharedPreferences("follow_prefs", MODE_PRIVATE).getInt("chunkChars", 15);
    }

    private static String chunkLabel(int chars) {
        return chars <= 0 ? "整句" : chars + "字";
    }

    /** v1.7：字数小钮与说明行只在跟读开启时出现；说明行里的档位字样随当前字数走 */
    private void refreshFollowExtras(boolean on) {
        int chars = chunkCharsNow();
        lastChunkChars = chars;
        if (btnRFollowChars != null) {
            btnRFollowChars.setVisibility(on ? View.VISIBLE : View.GONE);
            btnRFollowChars.setText(chunkLabel(chars));
        }
        if (readerFollowHint != null) {
            readerFollowHint.setVisibility(on ? View.VISIBLE : View.GONE);
            if (on) {
                readerFollowHint.setText("跟读：播一段、停一段（停顿是所播时长的 1.5 倍），"
                        + "到段末自动停下等你复诵；点『" + chunkLabel(chars)
                        + "』可改每段字数，短句会自动合并、长句会按标点拆开。");
            }
        }
    }

    /** 每段字数选择：10/15/20/25 字或整句（不分段）；选中即由服务重算分段继续 */
    private void showChunkCharsDialog() {
        final int[] values = {10, 15, 20, 25, 0};
        String[] labels = {"10字", "15字", "20字", "25字", "整句（不分段）"};
        int cur = chunkCharsNow();
        int checked = 1;
        for (int i = 0; i < values.length; i++) if (values[i] == cur) checked = i;
        new AlertDialog.Builder(this)
                .setTitle("每段字数")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    int v = values[which];
                    PlayerService svc = PlayerService.instance;
                    if (svc != null) svc.setFollowChunkChars(v);
                    else getSharedPreferences("follow_prefs", MODE_PRIVATE)
                            .edit().putInt("chunkChars", v).apply();
                    lastChunkChars = v;
                    boolean on = PlayerService.instance != null
                            ? PlayerService.instance.followEnabled
                            : getSharedPreferences("follow_prefs", MODE_PRIVATE)
                                    .getBoolean("enabled", false);
                    refreshFollowButton(on);
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 有时间句子拉成数组：缩放后时间天然随阅读顺序递增（内容包已校验单调） */
    private void buildTimeline() {
        List<Long> starts = new ArrayList<>();
        List<Long> ends = new ArrayList<>();
        List<Integer> flats = new ArrayList<>();
        for (int i = 0; i < book.flat.size(); i++) {
            ContentRepo.Sentence s = book.flat.get(i);
            if (s.noAudio || s.startMs < 0) continue;
            starts.add(Math.round(s.startMs * scale));
            ends.add(Math.round(s.endMs * scale));
            flats.add(i);
        }
        tStart = new long[starts.size()];
        tEnd = new long[starts.size()];
        tFlat = new int[starts.size()];
        for (int i = 0; i < starts.size(); i++) {
            tStart[i] = starts.get(i);
            tEnd[i] = ends.get(i);
            tFlat[i] = flats.get(i);
        }
    }

    private String buildHtml() {
        try {
            InputStream in = getAssets().open("reader.html");
            byte[] buf = new byte[in.available()];
            int n = in.read(buf);
            in.close();
            String tpl = new String(buf, 0, n, StandardCharsets.UTF_8);
            StringBuilder cls = new StringBuilder("fs").append(fontIdx);
            if (!pinyinOn) cls.append(" nopy");
            if (vertical) cls.append(" vert");
            return tpl.replace("__CLASSES__", cls.toString())
                    .replace("__TRAD__", String.valueOf(useTrad))
                    .replace("__BOOK_JSON__", book.rawJson);
        } catch (Exception e) {
            return "<html><body>原文加载失败</body></html>";
        }
    }

    // ---------- 播放控制 ----------
    private void sendService(String action, String trackId, long seekPos) {
        Intent i = new Intent(this, PlayerService.class).setAction(action);
        if (trackId != null) i.putExtra(PlayerService.EXTRA_TRACK_ID, trackId);
        if (action.equals(PlayerService.ACTION_PLAY_TRACK_AT))
            i.putExtra(PlayerService.EXTRA_SEEK_POS, seekPos);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
        else startService(i);
    }

    private void playAtSentence(int flatIdx) {
        ContentRepo.Sentence s = book.flat.get(flatIdx);
        long pos = Math.max(0, Math.round(s.startMs * scale) - 150);
        PlayerService svc = PlayerService.instance;
        if (svc != null) svc.playTrackAt(track, pos);
        else sendService(PlayerService.ACTION_PLAY_TRACK_AT, track.id, pos);
    }

    private void onToggleThisTrack() {
        PlayerService svc = PlayerService.instance;
        if (svc != null && svc.current != null && svc.current.id.equals(track.id)) {
            sendService(PlayerService.ACTION_TOGGLE, null, 0);
        } else {
            sendService(PlayerService.ACTION_PLAY_TRACK, track.id, 0);
        }
    }

    private void sendSeekRel(long deltaMs) {
        PlayerService svc = PlayerService.instance;
        if (svc != null && svc.current != null && svc.current.id.equals(track.id)) {
            Intent i = new Intent(this, PlayerService.class)
                    .setAction(PlayerService.ACTION_SEEK_REL)
                    .putExtra(PlayerService.EXTRA_SEEK_DELTA, deltaMs);
            startService(i);
        }
    }

    private void cycleSpeed() {
        int cur = 0;
        for (int i = 0; i < SPEEDS.length; i++) {
            if (Math.abs(SPEEDS[i] - store.speed) < 0.01f) cur = i;
        }
        float next = SPEEDS[(cur + 1) % SPEEDS.length];
        if (PlayerService.instance != null) PlayerService.instance.setSpeed(next);
        else {
            store.speed = next;
            store.saveSettings();
        }
        refreshBarLabels();
    }

    private void applyLoop() {
        if (PlayerService.instance != null) PlayerService.instance.loopEnabled = loopOn;
    }

    // ---------- 轮询：当前句高亮/跟随 + 播放栏状态 ----------
    private void pollOnce() {
        PlayerService svc = PlayerService.instance;
        boolean mine = svc != null && svc.current != null && svc.current.id.equals(track.id);
        String toggleLabel = (mine && svc.playing) ? "❚❚" : "▶";
        if (!toggleLabel.equals(lastToggleLabel)) {
            lastToggleLabel = toggleLabel;
            btnRToggle.setText(toggleLabel);
        }
        String speedLabel = speedLabelOf(store.speed);
        if (!speedLabel.equals(lastSpeedLabel)) {
            lastSpeedLabel = speedLabel;
            btnRSpeed.setText(speedLabel);
        }
        // 跟读开关态与服务同步（可能被切歌自动关、或服务重启后恢复）
        boolean fOn = svc != null ? svc.followEnabled
                : getSharedPreferences("follow_prefs", MODE_PRIVATE).getBoolean("enabled", false);
        if (lastFollowOn == null || lastFollowOn != fOn) {
            lastFollowOn = fOn;
            refreshFollowButton(fOn);
        }
        // 每段字数与服务同步（字数小钮文案与说明行随之刷新）
        int cc = chunkCharsNow();
        if (lastChunkChars == null || lastChunkChars != cc) {
            lastChunkChars = cc;
            refreshFollowButton(fOn);
        }
        // gap 阶段标记：当前句保持高亮，只换一层底色/线型提示「轮到你复诵」
        boolean gapOn = mine && svc.followEnabled
                && svc.followPhase == PlayerService.FOLLOW_GAP;
        if (gapOn != lastGapOn) {
            lastGapOn = gapOn;
            web.evaluateJavascript("setGap(" + gapOn + ")", null);
        }
        if (!mine || tStart.length == 0) return;

        long pos = svc.currentPosition();
        int ti = findTimedIndex(pos);
        int flatIdx = ti >= 0 ? tFlat[ti] : -1;
        if (flatIdx != curSentence) {
            curSentence = flatIdx;
            boolean scroll = !followPaused;
            web.evaluateJavascript("setCur(" + flatIdx + "," + scroll + ")", null);
        } else if (followPaused && System.currentTimeMillis() >= followResumeAt) {
            resumeFollow(true);
        }
    }

    /** 二分查找：最后一个 startMs <= pos 的有时间句子（句间停顿/章题时间也归前一句高亮） */
    private int findTimedIndex(long pos) {
        int lo = 0, hi = tStart.length - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) / 2;
            if (tStart[mid] <= pos) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }

    private void resumeFollow(boolean scrollToCur) {
        followPaused = false;
        btnFollow.setVisibility(View.GONE);
        if (scrollToCur && curSentence >= 0) {
            web.evaluateJavascript("setCur(" + curSentence + ",true)", null);
        }
    }

    private String speedLabelOf(float speed) {
        for (int i = 0; i < SPEEDS.length; i++) {
            if (Math.abs(SPEEDS[i] - speed) < 0.01f) return SPEED_LABELS[i];
        }
        return speed + "×";
    }

    private void refreshBarLabels() {
        btnRLoop.setText(loopOn ? "循环 开" : "循环 关");
        btnRScript.setText(useTrad ? "简体" : "繁體"); // 钮上显示「点一下切到」的字形
        btnRPinyin.setText(pinyinOn ? "注音 开" : "注音 关");
        btnROrient.setText(vertical ? "横排" : "竖排");
        lastSpeedLabel = null; // 强制下一轮轮询刷新速度显示
    }

    // ---------- 字号面板：四档滑杆，拖动即时生效 ----------
    private void showFontPanel() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        TextView label = new TextView(this);
        label.setTextSize(16);
        label.setText("字号：" + FONT_LABELS[fontIdx]);
        box.addView(label);
        SeekBar sb = new SeekBar(this);
        sb.setMax(3);
        sb.setProgress(fontIdx);
        box.addView(sb);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                fontIdx = progress;
                label.setText("字号：" + FONT_LABELS[fontIdx]);
                prefs.edit().putInt("font", fontIdx).apply();
                web.evaluateJavascript("setFont(" + fontIdx + ")", null);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        new AlertDialog.Builder(this)
                .setTitle("字号")
                .setView(box)
                .setPositiveButton("完成", null)
                .show();
    }

    // ---------- JS 桥 ----------
    public class Bridge {
        @JavascriptInterface
        public void sentence(int flatIdx) {
            runOnUiThread(() -> {
                if (flatIdx >= 0 && flatIdx < book.flat.size()
                        && !book.flat.get(flatIdx).noAudio) {
                    playAtSentence(flatIdx);
                }
            });
        }

        @JavascriptInterface
        public void naTap() {
            runOnUiThread(() ->
                    Toast.makeText(ReaderActivity.this, "这一句录音中未诵读", Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public void setImmersive(boolean on) {
            runOnUiThread(() -> {
                topBar.setVisibility(on ? View.GONE : View.VISIBLE);
                bottomBar.setVisibility(on ? View.GONE : View.VISIBLE);
            });
        }

        @JavascriptInterface
        public void userScrolled() {
            runOnUiThread(() -> {
                PlayerService svc = PlayerService.instance;
                if (svc != null && svc.current != null && svc.current.id.equals(track.id)
                        && !followPaused) {
                    followPaused = true;
                    followResumeAt = System.currentTimeMillis() + 6000;
                    btnFollow.setVisibility(View.VISIBLE);
                }
            });
        }

        /** 纯读计数：按钮在文档最末端，滚到结尾才点得到（防误触）；点击才落账 */
        @JavascriptInterface
        public void finishRead() {
            runOnUiThread(() -> {
                if (finishDialogShowing) return;
                store.increment(track, LocalDate.now());
                if (PlayerService.instance != null) PlayerService.instance.refreshStats();
                finishDialogShowing = true;
                int today = store.trackToday(track);
                new AlertDialog.Builder(ReaderActivity.this)
                        .setTitle("已读完一遍")
                        .setMessage(track.title + " 今日 +1 遍（今日共 " + today + " 遍）")
                        .setPositiveButton("再读一遍", (d, w) -> {
                            finishDialogShowing = false;
                            web.evaluateJavascript("goTop()", null);
                        })
                        .setNegativeButton("回到第一页", (d, w) -> {
                            finishDialogShowing = false;
                            web.evaluateJavascript("goTop()", null);
                        })
                        .setNeutralButton("留在本页", (d, w) -> finishDialogShowing = false)
                        .setOnCancelListener(d -> finishDialogShowing = false)
                        .show();
            });
        }
    }

    // ---------- PlayerService.Listener（与 MainActivity 同一套注册口径） ----------
    @Override
    public void onStateChanged() {
        runOnUiThread(() -> lastToggleLabel = null);
    }

    @Override
    public void onProgress(String trackId, long posMs, long durMs) {
        // 进度高亮走本页 150ms 轮询，这里不处理
    }

    @Override
    public void onCountChanged() {
        // 计数变化（播完一遍/纯读 +1）不需要阅读页做额外刷新
    }

    @Override
    protected void onResume() {
        super.onResume();
        PlayerService.listener = this;
        applyLoop();
        handler.post(poll);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(poll);
        if (PlayerService.listener == this) PlayerService.listener = null;
        super.onPause();
    }
}
