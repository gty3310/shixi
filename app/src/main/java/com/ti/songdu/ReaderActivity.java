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

import com.ti.songdu.recite.AsrModelStore;
import com.ti.songdu.recite.ReciteData;
import com.ti.songdu.recite.ReciteSession;
import com.ti.songdu.recite.ReciteStats;

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
    /** v1.16.0 复习页跳回原句：带内容包句子 id，打开后定位到该句 */
    public static final String EXTRA_SCROLL_SENT_ID = "reader_scroll_sent_id";

    private static final float[] SPEEDS = {0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f, 4.0f};
    private static final String[] SPEED_LABELS = {"0.75×", "1.0×", "1.25×", "1.5×", "2.0×", "3.0×", "4.0×"};
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
    private TextView btnRFollow, btnRFont;
    /** v1.16.0 识读：开关钮、会话、数据（懒构建）与待启动标记（等权限/模型时置位） */
    private TextView btnRRecite;
    private boolean reciteOn = false;
    private boolean recitePending = false;
    private ReciteSession reciteSession;
    private ReciteData reciteData;
    /** v1.16.0 点句面板与跳转定位 */
    private View sentPanel, sentNoteEditRow;
    private TextView sentPanelText;
    private android.widget.EditText sentNoteEdit;
    private TextView btnSentNote, btnMark1, btnMark2, btnMark3;
    private int panelFlat = -1;
    private int jumpFlat = -1;
    private int jumpTries = 0;

    /** v1.9.0 注释开关钮与竖排固定注栏（注文/页脚/滚动容器） */
    private TextView btnRNotes;
    private View readerNoteBar;
    private TextView readerNoteText, readerNoteFooter;
    private android.widget.ScrollView readerNoteScroll;
    /** 竖排注栏上次内容键（句号＋版本＋块首），变化才重设文本，避免轮询里反复刷 */
    private String noteBarKey = null;
    /** v1.7 跟读字数小钮（跟读开时显示，如「15字」）与底部说明行 */
    private TextView btnRFollowChars;
    private TextView readerFollowHint;
    /** v1.8.5 跟读调试读数行（跟读开时显示）与上次推送文案（去重） */
    private TextView readerFollowDebug;
    private String lastFollowDebug = null;
    private TextView readerPageInfo;
    private Boolean lastFollowOn = null;
    private Integer lastChunkChars = null;
    private boolean lastGapOn = false;

    // 阅读设置（各自独立持久化）
    private SharedPreferences prefs;
    private boolean useTrad, pinyinOn, vertical, loopOn;
    private int fontIdx;
    /** v1.9.0 注释开关与选定版本（"zhijie" 张居正（默认） / "jizhu" 朱熹），独立持久化 */
    private boolean notesOn;
    private String notesVer = "zhijie";
    /** 本书是否有注数据（仅《大学》《中庸》内容包在 ContentRepo 挂得上注） */
    private boolean hasNotes;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int curSentence = -1;
    /** v1.8.2 小句高亮：当前句内小句序号（与 JS wantClause 同步）与跟读模式推送去重 */
    private int curClause = -1;
    private Boolean followModePushed = null;
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
        for (ContentRepo.Sentence s : book.flat) {
            if (s.noteZj != null || s.noteJz != null) { hasNotes = true; break; }
        }

        prefs = getSharedPreferences("reader_prefs", MODE_PRIVATE);
        useTrad = prefs.getBoolean("trad", true);
        pinyinOn = prefs.getBoolean("pinyin", true);
        vertical = prefs.getBoolean("vertical", false);
        fontIdx = prefs.getInt("font", 1);
        loopOn = prefs.getBoolean("loop", true);
        notesOn = prefs.getBoolean("notes", false);
        notesVer = prefs.getString("notesVer", "zhijie");

        topBar = findViewById(R.id.readerTopBar);
        bottomBar = findViewById(R.id.readerBottomBar);
        btnRToggle = findViewById(R.id.btnRToggle);
        btnRSpeed = findViewById(R.id.btnRSpeed);
        btnRLoop = findViewById(R.id.btnRLoop);
        btnRFollow = findViewById(R.id.btnRFollow);
        btnRFollowChars = findViewById(R.id.btnRFollowChars);
        btnRRecite = findViewById(R.id.btnRRecite);
        sentPanel = findViewById(R.id.sentPanel);
        sentNoteEditRow = findViewById(R.id.sentNoteEditRow);
        sentPanelText = findViewById(R.id.sentPanelText);
        sentNoteEdit = findViewById(R.id.sentNoteEdit);
        btnSentNote = findViewById(R.id.btnSentNote);
        btnMark1 = findViewById(R.id.btnMark1);
        btnMark2 = findViewById(R.id.btnMark2);
        btnMark3 = findViewById(R.id.btnMark3);
        btnRNotes = findViewById(R.id.btnRNotes);
        readerNoteBar = findViewById(R.id.readerNoteBar);
        readerNoteText = findViewById(R.id.readerNoteText);
        readerNoteFooter = findViewById(R.id.readerNoteFooter);
        readerNoteScroll = findViewById(R.id.readerNoteScroll);
        readerFollowHint = findViewById(R.id.readerFollowHint);
        readerFollowDebug = findViewById(R.id.readerFollowDebug);
        readerPageInfo = findViewById(R.id.readerPageInfo);
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
                // v1.16.0：注入本书的句子标记（render 分批完成时 JS 会再重放一次）
                pushMarksToWeb();
                // v1.16.0 复习页跳转：目标句可能在分批渲染的后段，等它出现再定位
                if (jumpFlat >= 0) {
                    jumpTries = 0;
                    handler.postDelayed(jumpAttempt, 350);
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
        btnRRecite.setOnClickListener(v -> toggleRecite());
        findViewById(R.id.btnSentClose).setOnClickListener(v -> hideSentPanel());
        btnSentNote.setOnClickListener(v -> {
            if (panelFlat < 0) return;
            NotesStore.Item it = panelItem();
            sentNoteEdit.setText(it != null && it.note != null ? it.note : "");
            sentNoteEditRow.setVisibility(View.VISIBLE);
            sentNoteEdit.requestFocus();
        });
        findViewById(R.id.btnSentNoteCancel).setOnClickListener(v ->
                sentNoteEditRow.setVisibility(View.GONE));
        findViewById(R.id.btnSentNoteSave).setOnClickListener(v -> savePanelNote());
        btnMark1.setOnClickListener(v -> togglePanelMark(NotesStore.MARK_YELLOW));
        btnMark2.setOnClickListener(v -> togglePanelMark(NotesStore.MARK_GREEN));
        btnMark3.setOnClickListener(v -> togglePanelMark(NotesStore.MARK_BLUE));
        // v1.16.0 复习页跳回原句：按句子 id 在本（篇）书里定位
        String jumpSentId = getIntent().getStringExtra(EXTRA_SCROLL_SENT_ID);
        if (jumpSentId != null) {
            for (int i = 0; i < book.flat.size(); i++) {
                if (jumpSentId.equals(book.flat.get(i).id)) {
                    jumpFlat = i;
                    curSentence = i;
                    break;
                }
            }
        }
        refreshReciteButton();
        btnRNotes.setOnClickListener(v -> {
            if (notesOn) {
                // 点关：直接关、不弹窗
                notesOn = false;
                prefs.edit().putBoolean("notes", false).apply();
                web.evaluateJavascript("setNotesOn(false)", null);
                refreshNotesButton();
                refreshNoteBar();
                return;
            }
            // 点开：无注书目不弹选版窗，提示后保持关闭
            if (!hasNotes) {
                Toast.makeText(this, "本书暂无注释数据", Toast.LENGTH_SHORT).show();
                return;
            }
            // 点开：先弹选版小窗，选定后才开启（取消则保持关闭）
            showNoteVerChooser();
        });
        btnRFont = findViewById(R.id.btnRFont);
        btnRFont.setOnClickListener(v -> showFontPanel());
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
            refreshNoteBar();
        });
        applyLoop();
        refreshBarLabels();
        boolean fOn = PlayerService.instance != null
                ? PlayerService.instance.followEnabled
                : getSharedPreferences("follow_prefs", MODE_PRIVATE).getBoolean("enabled", false);
        lastFollowOn = fOn;
        refreshFollowButton(fOn);
        refreshNotesButton();
        refreshNoteBar();
        applyButtonVisibility();
    }

    /**
     * v1.11.0 按总设置页的显隐键（reader_prefs show*Btn，默认全显示）设第二排
     * 按钮 visibility。藏钮只藏入口：功能状态本身照常生效（如默认竖排仍是竖排、
     * 注释开着仍开着），用户回设置把钮打开即可继续操作。唯一例外是跟读——
     * 正在跟读时把「跟读」钮藏掉会让用户无处退出，故先退出跟读模式再藏钮
     * （防困死）。onCreate 与 onResume 各调一次，设置改完回来即生效。
     */
    private void applyButtonVisibility() {
        if (prefs == null) return;
        btnRScript.setVisibility(prefs.getBoolean("showTradBtn", true)
                ? View.VISIBLE : View.GONE);
        btnROrient.setVisibility(prefs.getBoolean("showVertBtn", true)
                ? View.VISIBLE : View.GONE);
        btnRPinyin.setVisibility(prefs.getBoolean("showPinyinBtn", true)
                ? View.VISIBLE : View.GONE);
        btnRFont.setVisibility(prefs.getBoolean("showFontBtn", true)
                ? View.VISIBLE : View.GONE);
        btnRNotes.setVisibility(prefs.getBoolean("showNotesBtn", true)
                ? View.VISIBLE : View.GONE);
        boolean showFollow = prefs.getBoolean("showFollowBtn", true);
        if (!showFollow) {
            PlayerService svc = PlayerService.instance;
            boolean fOn = svc != null ? svc.followEnabled
                    : getSharedPreferences("follow_prefs", MODE_PRIVATE)
                            .getBoolean("enabled", false);
            if (fOn) {
                if (svc != null) svc.setFollowEnabled(false);
                else getSharedPreferences("follow_prefs", MODE_PRIVATE)
                        .edit().putBoolean("enabled", false).apply();
                lastFollowOn = false;
                refreshFollowButton(false); // 连带藏起字数小钮与说明行
            }
        }
        btnRFollow.setVisibility(showFollow ? View.VISIBLE : View.GONE);
    }

    /** v1.9.0 注释开关钮呈现：与跟读钮同口径（开＝朱砂实心 pill＋白字加粗，关＝无底浅字） */
    private void refreshNotesButton() {
        if (btnRNotes == null) return;
        btnRNotes.setText(notesOn ? "注释 开" : "注释 关");
        float density = getResources().getDisplayMetrics().density;
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(999 * density);
        if (notesOn) {
            g.setColor(0xFF9E3B2B);
            btnRNotes.setTextColor(0xFFFFFFFF);
            btnRNotes.setTypeface(null, Typeface.BOLD);
        } else {
            g.setColor(0x00000000);
            btnRNotes.setTextColor(0xFFDDD6CB);
            btnRNotes.setTypeface(null, Typeface.NORMAL);
        }
        btnRNotes.setBackground(g);
    }

    /**
     * v1.10.2 选版小窗：只在用户亲手点开「注释」时弹（页面重建恢复开态不走这里）。
     * 两个版本各一块：标题＋一小行说明；上次所选带 ✓ 并高亮。点某一块即
     * 选定＋关窗＋开启注释（一步完成）；「取消」或点外关闭则保持关闭、什么都不改。
     */
    private void showNoteVerChooser() {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padH = (int) (20 * density), padTop = (int) (14 * density), padBot = (int) (6 * density);
        root.setPadding(padH, padTop, padH, padBot);
        root.addView(noteVerOption(density, "zhijie",
                "张居正《四书直解》",
                "明朝首辅给十岁小皇帝讲课的讲稿，近乎白话，讲得细。适合第一次读、想轻松读懂的人。"));
        View gap = new View(this);
        root.addView(gap, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (10 * density)));
        root.addView(noteVerOption(density, "jizhu",
                "朱熹《四书章句集注》",
                "南宋朱熹的注本，八百年读书人的标准，文言短注、义理最全。适合文言有底子、想看原汁原味的人。"));
        final AlertDialog[] dlg = new AlertDialog[1];
        for (int i = 0; i < root.getChildCount(); i++) {
            final View child = root.getChildAt(i);
            Object tag = child.getTag();
            if (tag instanceof String) {
                final String ver = (String) tag;
                child.setOnClickListener(v -> {
                    applyNoteVerAndEnable(ver);
                    if (dlg[0] != null) dlg[0].dismiss();
                });
            }
        }
        dlg[0] = new AlertDialog.Builder(this)
                .setTitle("选择注释版本")
                .setView(root)
                .setNegativeButton("取消", null)
                .show();
    }

    /** 选版窗里的一块选项：整块可点；是上次所选时带 ✓ 且底色高亮 */
    private View noteVerOption(float density, String ver, String title, String desc) {
        boolean selected = ver.equals(notesVer);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setTag(ver);
        int pad = (int) (12 * density);
        box.setPadding(pad, (int) (10 * density), pad, (int) (10 * density));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(10 * density);
        bg.setColor(selected ? 0xFFF1E2C4 : 0xFFF7F2E9);
        bg.setStroke((int) density, selected ? 0xFFA97C2C : 0xFFD8CDB4);
        box.setBackground(bg);
        TextView t = new TextView(this);
        t.setText((selected ? "✓ " : "") + title);
        t.setTextSize(16);
        t.setTextColor(0xFF26221C);
        t.setTypeface(null, Typeface.BOLD);
        box.addView(t);
        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextSize(12);
        d.setTextColor(0xFF6E6455);
        d.setLineSpacing(0, 1.3f);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = (int) (4 * density);
        d.setLayoutParams(dlp);
        box.addView(d);
        return box;
    }

    /** 选版确定：持久化版本＋开态，回推 JS 按新版本重填注块并显示，再刷新钮与竖排注栏 */
    private void applyNoteVerAndEnable(String ver) {
        notesVer = ver;
        notesOn = true;
        prefs.edit().putString("notesVer", ver).putBoolean("notes", true).apply();
        web.evaluateJavascript("setNotesVer('" + ver + "')", null);
        web.evaluateJavascript("setNotesOn(true)", null);
        noteBarKey = null;
        refreshNotesButton();
        refreshNoteBar();
    }

    /** 本句要显示的注：优先选定版本，无块覆盖才回退另一家；zjFlag[0] 回报实际用的哪家 */
    private ContentRepo.NoteRef displayNote(ContentRepo.Sentence s, boolean[] zjFlag) {
        boolean zjFirst = "zhijie".equals(notesVer);
        ContentRepo.NoteRef first = zjFirst ? s.noteZj : s.noteJz;
        if (first != null) {
            zjFlag[0] = zjFirst;
            return first;
        }
        ContentRepo.NoteRef second = zjFirst ? s.noteJz : s.noteZj;
        if (second != null) {
            zjFlag[0] = !zjFirst;
            return second;
        }
        return null;
    }

    /**
     * v1.9.0 竖排固定注栏刷新：仅「竖排＋注释开＋本书有注」时可见（横排注在页内）。
     * 内容＝当前句的注（与页内同口径：选定版本优先、缺则回退、页脚标实际版本与块范围）；
     * 无当前句时给占位说明。内容键（句号/版本/块首）不变则不重设文本，轮询里可每拍调用。
     */
    private void refreshNoteBar() {
        if (readerNoteBar == null) return;
        boolean show = vertical && notesOn && hasNotes;
        readerNoteBar.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) {
            noteBarKey = null;
            return;
        }
        String defLab = "zhijie".equals(notesVer) ? "张居正的版本" : "朱熹的版本";
        if (curSentence < 0 || curSentence >= book.flat.size()) {
            String key = "ph|" + notesVer;
            if (!key.equals(noteBarKey)) {
                noteBarKey = key;
                readerNoteText.setText("播放或点按一句，这里显示这一句的注释。");
                readerNoteFooter.setText(defLab);
                readerNoteScroll.scrollTo(0, 0);
            }
            return;
        }
        boolean[] zj = new boolean[1];
        ContentRepo.NoteRef ref = displayNote(book.flat.get(curSentence), zj);
        if (ref == null) {
            String key = "none|" + curSentence;
            if (!key.equals(noteBarKey)) {
                noteBarKey = key;
                readerNoteText.setText("本句暂无注释。");
                readerNoteFooter.setText(defLab);
                readerNoteScroll.scrollTo(0, 0);
            }
            return;
        }
        String key = curSentence + "|" + notesVer + "|" + ref.startFlat;
        if (!key.equals(noteBarKey)) {
            noteBarKey = key;
            readerNoteText.setText(ref.text);
            String lab = zj[0] ? "张居正的版本" : "朱熹的版本";
            if (ref.endFlat > ref.startFlat) {
                lab += " · 本注管第 " + (ref.startFlat + 1) + "–" + (ref.endFlat + 1) + " 句";
            }
            readerNoteFooter.setText(lab);
            readerNoteScroll.scrollTo(0, 0);
        }
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
            if (notesOn) cls.append(" noteson");
            return tpl.replace("__CLASSES__", cls.toString())
                    .replace("__TRAD__", String.valueOf(useTrad))
                    .replace("__NOTES_VER__", notesVer)
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
        // v1.14.1：走 setLoopEnabled（字段＋reader_prefs/loop 持久化一并写，
        // 与总设置页「单曲循环」开关同一份口径）
        if (PlayerService.instance != null) PlayerService.instance.setLoopEnabled(loopOn);
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
        // v1.8.2：跟读开关态推给 JS——跟读时不拆小句（整句高亮＋gap 不变），
        // 普通播放时当前句才按 breaks 拆小句高亮
        if (followModePushed == null || followModePushed != fOn) {
            followModePushed = fOn;
            web.evaluateJavascript("setFollowMode(" + fOn + ")", null);
            if (fOn) curClause = -1;
        }
        // v1.8.5 跟读调试读数：跟读开且服务在场时附一行实测读数（过冲/落点），
        // 随本轮询（150ms）刷新、文案变化才 setText；关时整行隐藏
        if (readerFollowDebug != null) {
            if (fOn && svc != null) {
                String dbg = svc.followDebugText();
                readerFollowDebug.setVisibility(View.VISIBLE);
                if (!dbg.equals(lastFollowDebug)) {
                    lastFollowDebug = dbg;
                    readerFollowDebug.setText(dbg);
                }
            } else {
                readerFollowDebug.setVisibility(View.GONE);
                lastFollowDebug = null;
            }
        }
        // gap 阶段标记：当前句保持高亮，只换一层底色/线型提示「轮到你复诵」
        boolean gapOn = mine && svc.followEnabled
                && svc.followPhase == PlayerService.FOLLOW_GAP;
        if (gapOn != lastGapOn) {
            lastGapOn = gapOn;
            web.evaluateJavascript("setGap(" + gapOn + ")", null);
        }
        // v1.16.0 识读中：当前句由识读会话驱动，播放轮询不再抢高亮
        if (reciteOn) {
            refreshNoteBar();
            return;
        }
        if (!mine || tStart.length == 0) {
            refreshNoteBar();
            return;
        }

        long pos = svc.currentPosition();
        int ti = findTimedIndex(pos);
        int flatIdx = ti >= 0 ? tFlat[ti] : -1;
        boolean sentChanged = false;
        if (flatIdx != curSentence) {
            curSentence = flatIdx;
            sentChanged = true;
            boolean scroll = !followPaused;
            web.evaluateJavascript("setCur(" + flatIdx + "," + scroll + ")", null);
        } else if (followPaused && System.currentTimeMillis() >= followResumeAt) {
            resumeFollow(true);
        }
        // v1.8.2 小句高亮：仅普通播放——按 breaks（缩放后时间轴，与 tStart 同口径）
        // 算当前小句序号，只在（句, 小句）变化时推给 JS；JS 侧负责拆 span 与挪 class。
        // 当前句判定/跟随/seek 仍以整句为单位（上面 setCur 的逻辑不动）。
        if (!fOn && flatIdx >= 0) {
            int cl = clauseIndexAt(flatIdx, pos);
            if (sentChanged || cl != curClause) {
                curClause = cl;
                web.evaluateJavascript("setClause(" + cl + ")", null);
            }
        }
        // v1.9.0 竖排注栏跟随当前句（内部按内容键去重，未变不刷）
        refreshNoteBar();
    }

    /** 当前位置在该句内的小句序号：breakMs 缩放后 <= pos 的个数；无 breaks 时 0（整句一个小句） */
    private int clauseIndexAt(int flatIdx, long pos) {
        ContentRepo.Sentence s = book.flat.get(flatIdx);
        if (s.breakMs == null) return 0;
        int cl = 0;
        for (long t : s.breakMs) {
            if (Math.round(t * scale) <= pos) cl++;
            else break;
        }
        return cl;
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

    // ---------- v1.16.0 识读 ----------

    private final Runnable jumpAttempt = new Runnable() {
        @Override
        public void run() {
            if (jumpFlat < 0 || jumpTries++ > 12) return;
            web.evaluateJavascript("!!document.getElementById('si" + jumpFlat + "')",
                    v -> {
                        if ("true".equals(v)) {
                            web.evaluateJavascript("setCur(" + jumpFlat + ",true)", null);
                        } else {
                            handler.postDelayed(jumpAttempt, 400);
                        }
                    });
        }
    };

    private ReciteData buildReciteData() {
        if (reciteData != null) return reciteData;
        List<ReciteData.SentIn> ins = new ArrayList<>();
        for (ContentRepo.Sentence s : book.flat) {
            ReciteData.SentIn in = new ReciteData.SentIn();
            in.id = s.id;
            in.textT = s.textT;
            in.textS = s.textS;
            in.pinyin = s.pinyin;
            in.breakPos = s.breakPos;
            ins.add(in);
        }
        reciteData = ReciteData.build(book.bookId, ins);
        return reciteData;
    }

    private void toggleRecite() {
        if (reciteOn) {
            stopRecite();
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            recitePending = true;
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 9021);
            return;
        }
        if (!AsrModelStore.isReady(this)) {
            askDownloadModel();
            return;
        }
        startRecite();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 9021 && recitePending) {
            recitePending = false;
            if (grantResults.length > 0
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                if (AsrModelStore.isReady(this)) startRecite();
                else askDownloadModel();
            } else {
                Toast.makeText(this, "识读需要麦克风权限", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void askDownloadModel() {
        new AlertDialog.Builder(this)
                .setTitle("识读需要下载识别模型")
                .setMessage("离线语音识别模型约 25MB，只下一次，之后断网也能用。")
                .setPositiveButton("下载", (d, w) -> downloadModel())
                .setNegativeButton("取消", null)
                .show();
    }

    private void downloadModel() {
        final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setTitle("正在下载识别模型");
        pd.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(100);
        pd.setCancelable(false);
        pd.show();
        new Thread(() -> AsrModelStore.ensure(this, new AsrModelStore.Callback() {
            @Override
            public void onProgress(long done, long total) {
                runOnUiThread(() -> pd.setProgress(
                        total > 0 ? (int) (done * 100 / total) : 0));
            }

            @Override
            public void onDone() {
                runOnUiThread(() -> {
                    pd.dismiss();
                    startRecite();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    pd.dismiss();
                    Toast.makeText(ReaderActivity.this,
                            "模型下载失败：" + message, Toast.LENGTH_LONG).show();
                });
            }
        })).start();
    }

    private void startRecite() {
        PlayerService svc = PlayerService.instance;
        if (svc != null) {
            if (svc.followEnabled) svc.setFollowEnabled(false);
            if (svc.playing) svc.pause();
        }
        if (reciteSession == null) {
            reciteSession = new ReciteSession(this, buildReciteData(),
                    ReciteStats.get(this), new ReciteSession.Listener() {
                        @Override
                        public void onDisplaySentence(int flat) {
                            if (!reciteOn) return;
                            curSentence = flat;
                            web.evaluateJavascript("setCur(" + flat + ",true)", null);
                        }

                        @Override
                        public void onErrorChar(int flat, int ci) {
                            if (!reciteOn) return;
                            web.evaluateJavascript(
                                    "setReciteErr(" + flat + "," + ci + ")", null);
                        }

                        @Override
                        public void onState(boolean running, String error) {
                            if (!running && error != null) {
                                reciteOn = false;
                                refreshReciteButton();
                                Toast.makeText(ReaderActivity.this,
                                        "识读已停止：" + error, Toast.LENGTH_LONG).show();
                            }
                        }
                    });
        }
        reciteOn = true;
        reciteSession.start(curSentence >= 0 ? curSentence : 0);
        refreshReciteButton();
        Toast.makeText(this, "识读已开启：你领读，我跟着听", Toast.LENGTH_SHORT).show();
    }

    private void stopRecite() {
        reciteOn = false;
        if (reciteSession != null) reciteSession.stop();
        refreshReciteButton();
    }

    /** 识读开关钮呈现：与跟读钮同口径（开＝朱砂实心 pill＋白字加粗） */
    private void refreshReciteButton() {
        if (btnRRecite == null) return;
        btnRRecite.setText(reciteOn ? "识读 开" : "识读 关");
        float density = getResources().getDisplayMetrics().density;
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(999 * density);
        if (reciteOn) {
            g.setColor(0xFF9E3B2B);
            btnRRecite.setTextColor(0xFFFFFFFF);
            btnRRecite.setTypeface(null, Typeface.BOLD);
        } else {
            g.setColor(0x00000000);
            btnRRecite.setTextColor(0xFFDDD6CB);
            btnRRecite.setTypeface(null, Typeface.NORMAL);
        }
        btnRRecite.setBackground(g);
    }

    // ---------- v1.16.0 点句面板（笔记＋标记） ----------

    private String chapterIdOf(int flat) {
        int acc = 0;
        for (ContentRepo.Chapter ch : book.chapters) {
            if (flat < acc + ch.sentences.size()) return ch.id;
            acc += ch.sentences.size();
        }
        return "";
    }

    private NotesStore.Item panelItem() {
        if (panelFlat < 0 || book.bookId == null) return null;
        ContentRepo.Sentence s = book.flat.get(panelFlat);
        return NotesStore.get(this).getItem(book.bookId, s.id);
    }

    private void showSentPanel(int flat) {
        if (flat < 0 || flat >= book.flat.size()) return;
        panelFlat = flat;
        ContentRepo.Sentence s = book.flat.get(flat);
        sentPanelText.setText(s.textT != null ? s.textT : s.textS);
        sentNoteEditRow.setVisibility(View.GONE);
        refreshPanelMarkButtons();
        sentPanel.setVisibility(View.VISIBLE);
    }

    private void hideSentPanel() {
        panelFlat = -1;
        sentPanel.setVisibility(View.GONE);
        sentNoteEditRow.setVisibility(View.GONE);
    }

    private void refreshPanelMarkButtons() {
        NotesStore.Item it = panelItem();
        int mark = it != null ? it.mark : NotesStore.MARK_NONE;
        btnSentNote.setText(it != null && it.note != null && !it.note.trim().isEmpty()
                ? "✎ 改笔记" : "✎ 笔记");
        TextView[] btns = {btnMark1, btnMark2, btnMark3};
        for (int i = 0; i < btns.length; i++) {
            boolean on = mark == i + 1;
            btns[i].setText(on ? "✓" : "");
            btns[i].setTextColor(0xFF4A4234);
            btns[i].setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
            btns[i].setAlpha(on ? 1f : 0.55f);
        }
    }

    private void togglePanelMark(int mark) {
        if (panelFlat < 0 || book.bookId == null) return;
        ContentRepo.Sentence s = book.flat.get(panelFlat);
        NotesStore.Item it = panelItem();
        int cur = it != null ? it.mark : NotesStore.MARK_NONE;
        int next = cur == mark ? NotesStore.MARK_NONE : mark;
        NotesStore.get(this).upsert(book.bookId, chapterIdOf(panelFlat), s.id, panelFlat,
                s.textT != null ? s.textT : s.textS, next,
                it != null && it.note != null ? it.note : "");
        web.evaluateJavascript("setMark(" + panelFlat + "," + next + ")", null);
        refreshPanelMarkButtons();
    }

    private void savePanelNote() {
        if (panelFlat < 0 || book.bookId == null) return;
        ContentRepo.Sentence s = book.flat.get(panelFlat);
        NotesStore.Item it = panelItem();
        String text = sentNoteEdit.getText().toString();
        NotesStore.get(this).upsert(book.bookId, chapterIdOf(panelFlat), s.id, panelFlat,
                s.textT != null ? s.textT : s.textS,
                it != null ? it.mark : NotesStore.MARK_NONE, text);
        sentNoteEditRow.setVisibility(View.GONE);
        refreshPanelMarkButtons();
        Toast.makeText(this, "已保存到「复习 · 我的笔记」", Toast.LENGTH_SHORT).show();
    }

    /** 本书的标记注入阅读页（sentId → flat 换算后按 flat 下发） */
    private void pushMarksToWeb() {
        if (book.bookId == null) return;
        java.util.Map<String, Integer> bySent = NotesStore.get(this).marksForBook(book.bookId);
        if (bySent.isEmpty()) {
            web.evaluateJavascript("setMarks({})", null);
            return;
        }
        StringBuilder sb = new StringBuilder("setMarks({");
        boolean first = true;
        for (int i = 0; i < book.flat.size(); i++) {
            Integer m = bySent.get(book.flat.get(i).id);
            if (m == null) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append(i).append(':').append(m);
        }
        sb.append("})");
        web.evaluateJavascript(sb.toString(), null);
    }

    // ---------- JS 桥 ----------
    public class Bridge {
        @JavascriptInterface
        public void sentence(int flatIdx) {
            runOnUiThread(() -> {
                if (flatIdx < 0 || flatIdx >= book.flat.size()) return;
                // v1.16.0 点句：识读中点句＝把识读起点挪到该句；否则照旧从该句起读。
                // 两种情况都打开点句面板（写笔记/打标记）。
                if (reciteOn && reciteSession != null) {
                    reciteSession.startAtSentence(flatIdx);
                    curSentence = flatIdx;
                    web.evaluateJavascript("setCur(" + flatIdx + ",true)", null);
                } else if (!book.flat.get(flatIdx).noAudio) {
                    playAtSentence(flatIdx);
                }
                showSentPanel(flatIdx);
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

        /** 竖版分页页码（v1.8.0）：JS 分页引擎回报「第 X 页 / 共 Y 页」；
         *  横版时 JS 传 (0, 0)，此处隐藏。显示在底部状态区，不遮正文。 */
        @JavascriptInterface
        public void pageInfo(final int curPage, final int totalPages) {
            runOnUiThread(() -> {
                if (readerPageInfo == null) return;
                if (totalPages > 0 && curPage > 0) {
                    readerPageInfo.setText("第 " + curPage + " 页 / 共 " + totalPages + " 页");
                    readerPageInfo.setVisibility(View.VISIBLE);
                } else {
                    readerPageInfo.setVisibility(View.GONE);
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
        applyButtonVisibility(); // 设置页可能刚改过显隐，回来即生效
        handler.post(poll);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(poll);
        if (PlayerService.listener == this) PlayerService.listener = null;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        // 识读会话持有麦克风与识别器，页面销毁必须停干净
        if (reciteSession != null) {
            reciteOn = false;
            reciteSession.stop();
            reciteSession = null;
        }
        super.onDestroy();
    }
}
