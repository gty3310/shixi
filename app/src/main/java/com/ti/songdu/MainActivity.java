package com.ti.songdu;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ClipDescription;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.InputType;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;

import com.ti.songdu.recite.ReciteStatsV2;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity implements PlayerService.Listener {

    private static final String[] WEEKDAYS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    private static final float[] SPEEDS = {0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f, 4.0f};
    private static final String[] SPEED_LABELS = {"0.75×", "1.0×", "1.25×", "1.5×", "2.0×", "3.0×", "4.0×"};

    private Store store;

    /** v1.14.0：书库页「我愿意自己上传」回跳标记——带此 extra 回到主页时打开本地导入弹窗 */
    public static final String EXTRA_OPEN_ADD = "open_add_dialog";

    private TextView tvDate, tvEmpty;
    private TextView nowTitle, nowPos, nowDur, btnBarToggle, nowLoop;
    /** v1.11.0 播放器栏「悬浮字幕」钮（状态靠样式，见 refreshOverlayBtn） */
    private TextView btnOverlaySub;
    private SeekBar playerSeek;
    /** 用户正在拖播放条时置真，期间 ticker 的进度刷新不许覆盖拖动位置 */
    private boolean userSeeking = false;
    /** 哪些卡片展开了今日遍数 −/＋ 步进器（按音频 id，列表重建后保持展开） */
    private final Set<String> expandedSteppers = new HashSet<>();
    private LinearLayout speedRow, dayList, listContainer;
    private GridLayout calGrid;
    private TextView calMonthTitle, calMonthTotal;
    private View pagePlay;
    /** v1.21.0 底部三栏改定案：0 听读 / 1 复习 / 2 阅读计划（原历史记录整块迁入计划页作诵读日历） */
    private ScrollView pagePlan;
    private LinearLayout planContainer, todayPlanBand;
    private Button navPlay, navPlan;
    private android.webkit.WebView pageReview;
    private Button navReview;
    private int curPage = 0;
    private final List<TextView> speedChips = new ArrayList<>();
    private final Map<String, View> cardViews = new HashMap<>();
    /** v1.5 分组：已展开的组名集合（持久化在 SharedPreferences；不在集合里＝折叠，默认折叠） */
    private final Set<String> expandedGroups = new HashSet<>();
    /** 组名 → 组头行视图（拖动重排时按 store.tracks 顺序重排子视图用） */
    private final Map<String, View> groupHeaderViews = new HashMap<>();
    /** 组名 → 当前可见的组内行（篇行＋可能的提示行，按显示顺序；重排子视图用） */
    private final Map<String, java.util.List<View>> groupRowViews = new HashMap<>();
    /** v1.14.1：拖拽中已投递待执行的视图重排（合并多次 ENTERED 只排一次，见 postRebuildChildOrder） */
    private boolean dragRebuildPosted = false;
    private boolean showingCalendar = false;

    // 文件选择用经典 startActivityForResult（零第三方依赖）
    private static final int REQ_PICK = 1007;
    private static final int REQ_PICK_SRT_S = 1008;
    private static final int REQ_PICK_SRT_T = 1009;

    // v1.10.0 悬浮控件权限指引：进程内至多弹一次（冷启动口径）；pendingOverlayGrant
    // 标记「正从本页跳去系统设置授权」，onResume 回来时据此立即拉起悬浮件
    private static boolean overlayHintShownThisProcess = false;
    private boolean pendingOverlayGrant = false;

    // v1.8.2「添加书籍」弹窗的待选文件（选完点「添加」才真正导入）
    private Uri pendingAudioUri, pendingSrtSUri, pendingSrtTUri;
    private String pendingAudioName, pendingSrtSName, pendingSrtTName;
    private TextView lblAudioFile, lblSrtSFile, lblSrtTFile;
    private Button btnConfirmAdd;
    private Dialog addBookDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        store = Store.get(this);
        store.installPresetsIfNeeded(); // v1.3：首次启动释放两个预置完整版音频（只做一次）
        store.migrateBookstoreIfNeeded(); // v1.14.0：老用户的中庸/论语预置副本原地转为「已下载的书」（只做一次）
        // v1.14.0：从书库页「我愿意自己上传」回跳时直接打开本地导入弹窗
        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_OPEN_ADD, false)) {
            getIntent().removeExtra(EXTRA_OPEN_ADD);
            showAddBookDialog();
        }

        tvDate = findViewById(R.id.tvDate);
        tvEmpty = findViewById(R.id.tvEmpty);
        nowTitle = findViewById(R.id.nowTitle);
        nowPos = findViewById(R.id.nowPos);
        nowDur = findViewById(R.id.nowDur);
        btnBarToggle = findViewById(R.id.btnBarToggle);
        nowLoop = findViewById(R.id.nowLoop);
        playerSeek = findViewById(R.id.playerSeek);
        speedRow = findViewById(R.id.speedRow);
        dayList = findViewById(R.id.dayList);
        listContainer = findViewById(R.id.listContainer);
        calGrid = findViewById(R.id.calGrid);
        calMonthTitle = findViewById(R.id.calMonthTitle);
        calMonthTotal = findViewById(R.id.calMonthTotal);
        pagePlay = findViewById(R.id.pagePlay);
        pagePlan = findViewById(R.id.pagePlan);
        planContainer = findViewById(R.id.planContainer);
        todayPlanBand = findViewById(R.id.todayPlanBand);
        navPlay = findViewById(R.id.navPlay);
        navPlan = findViewById(R.id.navPlan);
        pageReview = findViewById(R.id.pageReview);
        navReview = findViewById(R.id.navReview);
        pageReview.getSettings().setJavaScriptEnabled(true);
        pageReview.addJavascriptInterface(new ReviewBridge(), "Bridge");

        expandedGroups.addAll(getSharedPreferences("ui_prefs", MODE_PRIVATE)
                .getStringSet("expandedGroups", new HashSet<>()));

        LocalDate today = LocalDate.now();
        tvDate.setText(String.format("%d年%d月%d日 · %s",
                today.getYear(), today.getMonthValue(), today.getDayOfMonth(),
                WEEKDAYS[today.getDayOfWeek().getValue() - 1]));

        // v1.14.0：「添加书籍」改开书库页（线上目录下载＋自传入口）；本地导入弹窗由书库页回调用
        WhatsNew.maybeShowOnLaunch(this); // v1.18.0 新版首次打开弹更新日志

        findViewById(R.id.btnAdd).setOnClickListener(
                v -> startActivity(new Intent(this, BookstoreActivity.class)));
        findViewById(R.id.btnHelp).setOnClickListener(v -> startActivity(
                new Intent(this, HelpActivity.class)));
        findViewById(R.id.btnSettings).setOnClickListener(v -> startActivity(
                new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btnCopy).setOnClickListener(v -> showCopyDialog());
        btnOverlaySub = findViewById(R.id.btnOverlaySub);
        btnOverlaySub.setOnClickListener(v -> toggleOverlayWanted());
        refreshOverlayBtn();

        buildSpeedChips();

        // 播放条进度可拖动：拖动时只更新时间显示，松手才 seek（并同步当前卡片进度）
        playerSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                userSeeking = true;
            }

            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                PlayerService svc = PlayerService.instance;
                if (svc != null && svc.current != null && svc.effDurationMs() > 0) {
                    nowPos.setText(Store.fmtTimeAt(svc.effDurationMs() * progress / 1000, store.speed));
                }
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                userSeeking = false;
                PlayerService svc = PlayerService.instance;
                if (svc != null && svc.current != null && svc.effDurationMs() > 0) {
                    long target = svc.effDurationMs() * sb.getProgress() / 1000;
                    Intent i = new Intent(MainActivity.this, PlayerService.class)
                            .setAction(PlayerService.ACTION_SEEK_TO)
                            .putExtra(PlayerService.EXTRA_SEEK_POS, target);
                    startService(i);
                }
            }
        });

        // 播放条「进入阅读界面」把手：点按与上滑都打开当前播放音频的阅读页，
        // 判定走纯逻辑类 HandleGesture；与卡片/篇行上的「阅读」钮最终都汇到
        // openReader（同一函数、同一参数），落点与跟随口径完全一致。
        // 手势在检测器里消费掉，不叠加 click——避免打开两次阅读页，也避免
        // 冒泡到播放条本体的播放/暂停点击。onDown 回 true 是手势检测器的
        // 既定要求：不回 true，后续的 fling 可能根本不送达。
        // （沿革：把手原为「上拉展开字幕」，字幕展开层早已取消；v1.14.1 曾摘除
        //   上滑手势只留点按；v1.20.6 按用户批准方案文案改「进入阅读界面」、
        //   点按与上滑并回同一入口，此处再无旧展开层残留。）
        View expandHandle = findViewById(R.id.btnExpandReader);
        final android.view.GestureDetector gd = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(android.view.MotionEvent e) {
                        return true;
                    }

                    @Override
                    public boolean onSingleTapUp(android.view.MotionEvent e) {
                        if (HandleGesture.onTap() == HandleGesture.Action.OPEN_READER) {
                            openReaderForCurrent();
                        }
                        return true;
                    }

                    @Override
                    public boolean onFling(android.view.MotionEvent e1, android.view.MotionEvent e2,
                                           float velocityX, float velocityY) {
                        if (e1 == null || e2 == null) return false;
                        HandleGesture.Action a = HandleGesture.onFling(
                                e2.getX() - e1.getX(), e2.getY() - e1.getY(),
                                velocityX, velocityY);
                        if (a == HandleGesture.Action.OPEN_READER) {
                            openReaderForCurrent();
                            return true;
                        }
                        return false;
                    }
                });
        expandHandle.setOnTouchListener((v, ev) -> gd.onTouchEvent(ev));

        View.OnClickListener toggleClick = v -> {
            if (PlayerService.instance != null && PlayerService.instance.current != null) {
                sendService(PlayerService.ACTION_TOGGLE, null);
            }
        };
        btnBarToggle.setOnClickListener(toggleClick);
        // v1.20.7：播放条上的「单曲循环」就是开关本人（此前是写死文字、与真开关
        // 脱节）：点它经 PlayerService.setLoopEnabled 改真源并持久化；服务不在时
        // 直接写同一份 reader_prefs/loop，服务下次起来读到的就是它
        nowLoop.setOnClickListener(v -> {
            PlayerService svc = PlayerService.instance;
            boolean now;
            if (svc != null) {
                now = !svc.loopEnabled;
                svc.setLoopEnabled(now);
            } else {
                SharedPreferences rp = getSharedPreferences("reader_prefs", MODE_PRIVATE);
                now = !rp.getBoolean("loop", true);
                rp.edit().putBoolean("loop", now).apply();
            }
            refreshPlayerBar();
            Toast.makeText(this, now ? "单曲循环已开" : "单曲循环已关",
                    Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btnBack15).setOnClickListener(v -> sendSeekRel(-15000));
        findViewById(R.id.btnFwd15).setOnClickListener(v -> sendSeekRel(15000));

        findViewById(R.id.playerBar).setOnClickListener(v -> {
            if (PlayerService.instance != null && PlayerService.instance.current != null) {
                sendService(PlayerService.ACTION_TOGGLE, null);
            }
        });
        navPlay.setOnClickListener(v -> showPage(0));
        navReview.setOnClickListener(v -> showPage(1));
        navPlan.setOnClickListener(v -> showPage(2));

        // Android 13+ 通知权限（前台服务媒体通知需要）
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 42);
        }

        showPage(0);
        refreshAll();

        // v1.11.0 悬浮字幕冷启动口径（状态在 overlay_prefs，与设置页/播放器钮同一份）：
        // 「打开软件时自动显示」＝自动 → 把显示意愿置真并拉起服务（手动藏过的这次
        // 也回来，沿用旧版每次冷启动重新出现的行为）；＝不自动 → 不拉起，用户手动
        // 点播放器「悬浮字幕」钮或去设置里打开。未授权且意愿为显示时给一次性指引。
        if (savedInstanceState == null) {
            if (OverlayController.autoShow(this)) {
                OverlayController.setShowWanted(this, true);
            }
            if (OverlayController.showWanted(this)) {
                if (OverlayController.canDraw(this)) {
                    ensureOverlayService();
                } else if (!overlayHintShownThisProcess) {
                    overlayHintShownThisProcess = true;
                    showOverlayPermissionHint();
                }
            }
        }
    }

    /** 「悬浮字幕」钮呈现：名字固定四字、不用开/关字样——朱砂填充白字＝显示中，
     *  灰描边灰字＝已隐藏（与设置页状态钮同一口径，只是底色在深色播放条上） */
    private void refreshOverlayBtn() {
        if (btnOverlaySub == null) return;
        boolean on = OverlayController.showWanted(this);
        float density = getResources().getDisplayMetrics().density;
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(999 * density);
        if (on) {
            g.setColor(getColor(R.color.accent));
            btnOverlaySub.setTextColor(0xFFFFFFFF);
            btnOverlaySub.setTypeface(null, android.graphics.Typeface.BOLD);
        } else {
            g.setColor(0x00000000);
            g.setStroke(Math.max(1, (int) density), 0xFF8A8174);
            btnOverlaySub.setTextColor(0xFFC8C1B4);
            btnOverlaySub.setTypeface(null, android.graphics.Typeface.NORMAL);
        }
        btnOverlaySub.setBackground(g);
    }

    /** 点「悬浮字幕」钮：翻转显示意愿。显示→拉起服务（无权限走既有指引弹窗，
     *  其「去授权」回程在 onResume 处理）；隐藏→摘窗，本会话内 REFRESH 路径
     *  再拉服务时 tryShow 也按意愿为关不挂，不会自己回来 */
    private void toggleOverlayWanted() {
        boolean next = !OverlayController.showWanted(this);
        OverlayController.setShowWanted(this, next);
        refreshOverlayBtn();
        if (next) {
            if (OverlayController.canDraw(this)) {
                ensureOverlayService();
            } else {
                pendingOverlayGrant = false; // 由弹窗的「去授权」置真
                showOverlayPermissionHint();
            }
        } else if (PlayerService.instance != null) {
            startService(new Intent(this, PlayerService.class)
                    .setAction(PlayerService.ACTION_HIDE_OVERLAY));
        }
    }

    /** 拉起 PlayerService 并让它把悬浮件显示出来（服务已在跑时只是一次幂等 intent） */
    private void ensureOverlayService() {
        Intent i = new Intent(this, PlayerService.class)
                .setAction(PlayerService.ACTION_REFRESH_OVERLAY);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    /** 悬浮窗权限一次性指引（每次冷启动至多一次，可取消；去授权跳系统设置页） */
    private void showOverlayPermissionHint() {
        new AlertDialog.Builder(this)
                .setTitle("悬浮播放按钮")
                .setMessage("弦歌可以在屏幕侧边留一个半透明的半圆按钮：轻点播放/暂停，"
                        + "长按打开正在读的阅读页，在别的 App 上面也能用。\n\n"
                        + "这需要系统里的「显示在其他应用上层」权限，授权一次即可；"
                        + "不授权也不影响在 App 里正常使用。")
                .setNegativeButton("下次再说", null)
                .setPositiveButton("去授权", (d, w) -> {
                    pendingOverlayGrant = true;
                    try {
                        startActivity(new Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        pendingOverlayGrant = false;
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
                        } catch (Exception ignored) {
                        }
                    }
                })
                .show();
    }

    // ---------- 文件选择与添加 ----------
    private void openPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/*", "audio/mpeg"});
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_PICK);
    }

    private void openSrtPicker(int requestCode) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"application/x-subrip", "text/plain", "text/x-subrip"});
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_PICK) {
            takeReadPermission(uri);
            pendingAudioUri = uri;
            pendingAudioName = queryDisplayName(uri);
            updateAddDialog();
        } else if (requestCode == REQ_PICK_SRT_S || requestCode == REQ_PICK_SRT_T) {
            String name = queryDisplayName(uri);
            if (name == null || !name.toLowerCase(java.util.Locale.ROOT).endsWith(".srt")) {
                Toast.makeText(this, "这个文件不是字幕文件，请选以 .srt 结尾的字幕文件",
                        Toast.LENGTH_LONG).show();
                return;
            }
            takeReadPermission(uri);
            if (requestCode == REQ_PICK_SRT_S) {
                pendingSrtSUri = uri;
                pendingSrtSName = name;
            } else {
                pendingSrtTUri = uri;
                pendingSrtTName = name;
            }
            updateAddDialog();
        }
    }

    private void takeReadPermission(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
    }

    private Track addTrackFromUri(Uri uri) {
        takeReadPermission(uri);
        String fileName = queryDisplayName(uri);
        String title = deriveTitle(fileName, uri);
        long dur = queryDuration(uri);
        Track t = new Track(String.valueOf(System.currentTimeMillis()),
                uri.toString(), title,
                fileName != null && !fileName.isEmpty() ? fileName : title,
                dur, 0, 0);
        store.tracks.add(t);
        store.saveTracks();
        refreshAll();
        return t;
    }

    // ---------- v1.8.2 添加书籍弹窗 ----------
    /**
     * 顶部「添加书籍」弹窗：三个选文件按钮（音频／简体字幕／繁体字幕）纵向排列，
     * 每行右侧显示已选文件名（未选显示「未选择」）；只有音频也能加（与旧行为一致），
     * 带字幕则把每个字幕 cue 建成一句、生成用户内容包，这本书就有文字可读。
     */
    private void showAddBookDialog() {
        pendingAudioUri = pendingSrtSUri = pendingSrtTUri = null;
        pendingAudioName = pendingSrtSName = pendingSrtTName = null;

        final Dialog dialog = new Dialog(this);
        addBookDialog = dialog;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, dp(14));

        TextView title = new TextView(this);
        title.setText("添加书籍");
        title.setTextSize(19);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(getColor(R.color.ink));
        root.addView(title);

        lblAudioFile = addPickRow(root, "上传音频", v -> openPicker());
        lblSrtSFile = addPickRow(root, "上传简体字幕", v -> openSrtPicker(REQ_PICK_SRT_S));
        lblSrtTFile = addPickRow(root, "上传繁体字幕", v -> openSrtPicker(REQ_PICK_SRT_T));

        TextView note = new TextView(this);
        note.setText("只有音频也能加，可以先听；字幕齐了，文字才能跟着声音走。");
        note.setTextSize(12);
        note.setTextColor(getColor(R.color.muted));
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noteLp.topMargin = dp(12);
        note.setLayoutParams(noteLp);
        root.addView(note);

        Button btnHelp = pillButton("查看帮助", false);
        LinearLayout.LayoutParams helpLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
        helpLp.topMargin = dp(12);
        btnHelp.setLayoutParams(helpLp);
        btnHelp.setOnClickListener(v -> startActivity(
                new Intent(this, HelpActivity.class)
                        .putExtra(HelpActivity.EXTRA_ANCHOR, "add")));
        root.addView(btnHelp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(16);
        row.setLayoutParams(rowLp);
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1));
        row.addView(spacer);
        Button btnCancel = pillButton("取消", false);
        btnCancel.setOnClickListener(v -> dialog.dismiss());
        row.addView(btnCancel);
        btnConfirmAdd = pillButton("添加", true);
        LinearLayout.LayoutParams addLp = (LinearLayout.LayoutParams) btnConfirmAdd.getLayoutParams();
        addLp.leftMargin = dp(10);
        btnConfirmAdd.setLayoutParams(addLp);
        btnConfirmAdd.setOnClickListener(v -> confirmAddBook());
        row.addView(btnConfirmAdd);
        root.addView(row);

        dialog.setContentView(root);
        dialog.setOnDismissListener(d -> {
            if (addBookDialog == d) addBookDialog = null;
        });
        dialog.show();
        Window win = dialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawableResource(R.drawable.dialog_bg);
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            win.setLayout((int) (dm.widthPixels * 0.94), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        updateAddDialog();
    }

    /** 弹窗里的一行：左侧选文件按钮＋右侧文件名（未选显示「未选择」），返回文件名标签 */
    private TextView addPickRow(LinearLayout root, String btnText, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(12);
        row.setLayoutParams(rowLp);
        Button b = pillButton(btnText, false);
        b.setOnClickListener(click);
        row.addView(b);
        TextView name = new TextView(this);
        name.setText("未选择");
        name.setTextSize(13);
        name.setTextColor(getColor(R.color.muted));
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        nameLp.leftMargin = dp(10);
        name.setLayoutParams(nameLp);
        row.addView(name);
        root.addView(row);
        return name;
    }

    private void updateAddDialog() {
        if (lblAudioFile != null) {
            lblAudioFile.setText(pendingAudioName != null ? pendingAudioName : "未选择");
            lblSrtSFile.setText(pendingSrtSName != null ? pendingSrtSName : "未选择");
            lblSrtTFile.setText(pendingSrtTName != null ? pendingSrtTName : "未选择");
        }
        if (btnConfirmAdd != null) {
            boolean ok = pendingAudioUri != null;
            btnConfirmAdd.setEnabled(ok);
            btnConfirmAdd.setAlpha(ok ? 1.0f : 0.4f);
        }
    }

    /** 点「添加」：无字幕＝旧行为；有字幕＝先解析，读不出来就提示且不留半成品 */
    private void confirmAddBook() {
        if (pendingAudioUri == null) return;
        java.util.List<SrtParser.Cue> cuesS = null, cuesT = null;
        if (pendingSrtSUri != null) {
            cuesS = SrtParser.parse(readTextUri(pendingSrtSUri));
            if (cuesS.isEmpty()) {
                Toast.makeText(this, "这个字幕文件没读出来", Toast.LENGTH_LONG).show();
                return;
            }
        }
        if (pendingSrtTUri != null) {
            cuesT = SrtParser.parse(readTextUri(pendingSrtTUri));
            if (cuesT.isEmpty()) {
                Toast.makeText(this, "这个字幕文件没读出来", Toast.LENGTH_LONG).show();
                return;
            }
        }
        Track t = addTrackFromUri(pendingAudioUri);
        if (cuesS != null || cuesT != null) {
            String bookId = "user-" + t.id;
            try {
                String json = buildUserBookJson(bookId, t, cuesS, cuesT);
                if (ContentRepo.saveUserBook(this, bookId, json)) {
                    t.contentId = bookId;
                    store.saveTracks();
                    refreshAll();
                }
            } catch (Exception ignored) {
            }
        }
        if (addBookDialog != null) addBookDialog.dismiss();
    }

    private String readTextUri(Uri uri) {
        try {
            java.io.InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) return null;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            byte[] bytes = bos.toByteArray();
            String utf8 = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            int bad = utf8.length() - utf8.replace("�", "").length();
            if (bad > 0) {
                try {
                    String gbk = new String(bytes, "GBK");
                    int bad2 = gbk.length() - gbk.replace("�", "").length();
                    if (bad2 < bad) return gbk;
                } catch (Exception ignored) {
                }
            }
            return utf8;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 把字幕 cue 建成内容包：每个 cue 即一句（无 breaks、无拼音）；简繁按开始时间
     * 就近配对（差 2 秒内），只给一份字幕时简繁两面都显示这份的文本（App 无繁简
     * 转换引擎，不做自动转换）。
     */
    private String buildUserBookJson(String bookId, Track t,
                                     java.util.List<SrtParser.Cue> cuesS,
                                     java.util.List<SrtParser.Cue> cuesT) throws Exception {
        java.util.List<SrtParser.Cue> primary = cuesS != null ? cuesS : cuesT;
        org.json.JSONArray ss = new org.json.JSONArray();
        for (int i = 0; i < primary.size(); i++) {
            SrtParser.Cue cue = primary.get(i);
            String textS, textT;
            if (cuesS != null && cuesT != null) {
                if (primary == cuesS) {
                    textS = cue.text;
                    SrtParser.Cue m = nearestCue(cuesT, cue.startMs);
                    textT = m != null ? m.text : cue.text;
                } else {
                    textT = cue.text;
                    SrtParser.Cue m = nearestCue(cuesS, cue.startMs);
                    textS = m != null ? m.text : cue.text;
                }
            } else {
                textS = cue.text;
                textT = cue.text;
            }
            JSONObject so = new JSONObject();
            so.put("id", "u" + (i + 1));
            so.put("startMs", cue.startMs);
            so.put("endMs", cue.endMs);
            so.put("textS", textS);
            so.put("textT", textT);
            so.put("pinyin", new org.json.JSONArray());
            ss.put(so);
        }
        JSONObject ch = new JSONObject();
        ch.put("id", "c1");
        ch.put("title", t.title);
        ch.put("sentences", ss);
        org.json.JSONArray chs = new org.json.JSONArray();
        chs.put(ch);
        JSONObject doc = new JSONObject();
        doc.put("bookId", bookId);
        doc.put("title", t.title);
        doc.put("audioDurationMs", t.durationMs);
        doc.put("chapters", chs);
        return doc.toString();
    }

    private SrtParser.Cue nearestCue(java.util.List<SrtParser.Cue> cues, long startMs) {
        SrtParser.Cue best = null;
        long bestDiff = Long.MAX_VALUE;
        for (SrtParser.Cue c : cues) {
            long d = Math.abs(c.startMs - startMs);
            if (d < bestDiff) {
                bestDiff = d;
                best = c;
            }
        }
        return bestDiff <= 2000 ? best : null;
    }

    private String queryDisplayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    static String deriveTitle(String fileName, Uri uri) {
        String name = fileName;
        if (name == null || name.isEmpty()) {
            String seg = uri.getLastPathSegment();
            name = seg != null ? Uri.decode(seg) : "未命名音频";
            int slash = name.lastIndexOf('/');
            if (slash >= 0) name = name.substring(slash + 1);
        }
        int a = name.indexOf('《');
        int b = name.indexOf('》');
        if (a >= 0 && b > a) return name.substring(a, b + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return name;
    }

    private long queryDuration(Uri uri) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(this, uri);
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) return Long.parseLong(d);
        } catch (Exception ignored) {
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    // ---------- 播放命令 ----------
    private void sendService(String action, String trackId) {
        Intent i = new Intent(this, PlayerService.class).setAction(action);
        if (trackId != null) i.putExtra(PlayerService.EXTRA_TRACK_ID, trackId);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    private void sendSeekRel(long deltaMs) {
        if (PlayerService.instance != null && PlayerService.instance.current != null) {
            Intent i = new Intent(this, PlayerService.class)
                    .setAction(PlayerService.ACTION_SEEK_REL)
                    .putExtra(PlayerService.EXTRA_SEEK_DELTA, deltaMs);
            startService(i);
        }
    }

    // ---------- 复制群消息 ----------
    /**
     * v1.4.2 弹窗重做（原 AlertDialog 小窗有横线压字 bug，已弃用）：
     * 接近全屏的 Dialog（宽 94% × 高 92%）：自绘标题＋说明，下面是 ScrollView 包裹的
     * 多行编辑区（EditText 用 edit_bg 白底圆角背景、不用默认下划线样式，行距/内边距充足，
     * 文本再长也能上下滑着看和改），底部一行固定按钮：设置／取消／复制。
     * 文本仍由 store.buildProgressText() 生成，可编辑，点「复制」才写剪贴板。
     */
    private void showCopyDialog() {
        final Dialog dialog = new Dialog(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, dp(14));

        TextView title = new TextView(this);
        title.setText("复制群消息");
        title.setTextSize(19);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(getColor(R.color.ink));
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("按所选时段自动生成的进度，可上下滑动查看、直接修改；点「复制」后粘贴到群里或发给 AI。");
        hint.setTextSize(12);
        hint.setTextColor(getColor(R.color.muted));
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(6);
        hint.setLayoutParams(hintLp);
        root.addView(hint);

        // 编辑区：ScrollView 负责滚动，EditText 只管显示与编辑（高度随内容、背景无横线）
        EditText et = new EditText(this);
        et.setText(store.buildProgressText());
        et.setTextSize(15);
        et.setTextColor(getColor(R.color.ink));
        et.setGravity(Gravity.TOP | Gravity.START);
        et.setBackgroundResource(R.drawable.edit_bg);
        et.setPadding(dp(14), dp(12), dp(14), dp(12));
        et.setLineSpacing(dp(4), 1.0f);
        et.setHorizontallyScrolling(false);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        scrollLp.topMargin = dp(12);
        scroll.setLayoutParams(scrollLp);
        scroll.addView(et, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll);

        // 底部固定按钮行：设置（左）／取消／复制（右）
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(12);
        row.setLayoutParams(rowLp);

        Button btnSettings = pillButton("设置", false);
        btnSettings.setOnClickListener(v -> {
            dialog.dismiss();
            startActivity(new Intent(this, CopySettingsActivity.class));
        });
        row.addView(btnSettings);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1));
        row.addView(spacer);

        Button btnCancel = pillButton("取消", false);
        btnCancel.setOnClickListener(v -> dialog.dismiss());
        row.addView(btnCancel);

        Button btnCopy = pillButton("复制", true);
        LinearLayout.LayoutParams copyLp = (LinearLayout.LayoutParams) btnCopy.getLayoutParams();
        copyLp.leftMargin = dp(10);
        btnCopy.setLayoutParams(copyLp);
        btnCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("弦歌进度", et.getText().toString()));
                Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
            }
            dialog.dismiss();
        });
        row.addView(btnCopy);
        root.addView(row);

        dialog.setContentView(root);
        dialog.show();
        Window win = dialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawableResource(R.drawable.dialog_bg);
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            win.setLayout((int) (dm.widthPixels * 0.94), (int) (dm.heightPixels * 0.92));
        }
    }

    /** 弹窗底部按钮：与顶栏同款胶囊（描边＝次要、墨色实心＝主要） */
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

    /** 手动改今日遍数后：卡片、通知统计串全部立即刷新到一致 */
    private void afterCountsChanged() {
        refreshAll();
        if (PlayerService.instance != null) PlayerService.instance.refreshStats();
    }

    private void adjustToday(Track t, int delta) {
        int cur = store.trackToday(t);
        int next = Math.max(0, cur + delta);
        if (next == cur) return;
        store.setTrackCountOn(t, LocalDate.now(), next);
        afterCountsChanged();
    }

    /** v1.4 打开某音频的阅读页（内容包匹配在 ContentRepo；无原文的音频不会走到这里） */
    private void openReader(Track t) {
        startActivity(new Intent(this, ReaderActivity.class)
                .putExtra(ReaderActivity.EXTRA_TRACK_ID, t.id));
    }

    /** 播放条手柄：打开「当前播放」音频的阅读页；未在播放/无原文时给提示 */
    private void openReaderForCurrent() {
        PlayerService svc = PlayerService.instance;
        if (svc == null || svc.current == null) {
            Toast.makeText(this, "先选一个音频开始播放，再打开阅读页", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!ContentRepo.hasContent(svc.current)) {
            Toast.makeText(this, "该音频暂无原文", Toast.LENGTH_SHORT).show();
            return;
        }
        openReader(svc.current);
    }

    public void onPlayToggle(Track t) {
        PlayerService svc = PlayerService.instance;
        if (svc != null && svc.current != null && svc.current.id.equals(t.id)) {
            sendService(PlayerService.ACTION_TOGGLE, null);
        } else {
            sendService(PlayerService.ACTION_PLAY_TRACK, t.id);
        }
    }

    public void onDelete(Track t) {
        // v1.14.0：书库下载的书按整本删（确认框为定案文案：只删文件、记录保留）
        String storeBookId = Bookstore.bookIdOfTrack(t);
        if (storeBookId != null) {
            Bookstore.confirmDeleteBook(this, storeBookId, this::refreshAll);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("移除音频")
                .setMessage("把" + t.title + "从列表移除？\n它过去的播放记录会保留在日历统计里。")
                .setNegativeButton("取消", null)
                .setPositiveButton("移除", (d, w) -> {
                    PlayerService svc = PlayerService.instance;
                    if (svc != null && svc.current != null && svc.current.id.equals(t.id)) {
                        sendService(PlayerService.ACTION_STOP, null);
                    }
                    // v1.8.2：导入字幕生成的自有内容包一并删除，不留孤儿文件
                    if (t.contentId != null) ContentRepo.deleteUserBook(this, t.contentId);
                    store.tracks.remove(t);
                    store.saveTracks();
                    refreshAll();
                })
                .show();
    }

    // ---------- 音频列表（纯框架：ScrollView + 卡片，长按拖动排序） ----------
    /**
     * v1.5 分组渲染：store.tracks 里连续同 group 的曲目收拢为一组——
     * 先放一行组头（书名＋篇数＋今日合计＋展开/收起箭头），展开时才逐篇放缩进小卡。
     * 无组曲目与旧版完全一致。拖动排序以「组」为整体（见拖拽辅助方法）。
     */
    private void buildTrackList() {
        listContainer.removeAllViews();
        cardViews.clear();
        groupHeaderViews.clear();
        groupRowViews.clear();
        LayoutInflater inflater = LayoutInflater.from(this);
        int n = store.tracks.size();
        int i = 0;
        while (i < n) {
            Track t = store.tracks.get(i);
            if (t.group == null) {
                View card = inflater.inflate(R.layout.item_track, listContainer, false);
                bindCard(card, t);
                cardViews.put(t.id, card);
                listContainer.addView(card);
                i++;
            } else {
                int j = i + 1;
                while (j < n && t.group.equals(store.tracks.get(j).group)) j++;
                View header = buildGroupHeader(t.group, i, j);
                groupHeaderViews.put(t.group, header);
                listContainer.addView(header);
                java.util.List<View> rows = new ArrayList<>();
                if (expandedGroups.contains(t.group)) {
                    // 展开态：全部篇目按篇序，今天读过的在原位带「今日」标
                    for (int k = i; k < j; k++) rows.add(buildPianRow(store.tracks.get(k)));
                } else {
                    // 收起态（默认）：只露今天有遍数的篇（按篇序），其余收起
                    int shown = 0;
                    for (int k = i; k < j; k++) {
                        Track ct = store.tracks.get(k);
                        if (store.trackToday(ct) > 0) {
                            rows.add(buildPianRow(ct));
                            shown++;
                        }
                    }
                    int hidden = (j - i) - shown;
                    if (hidden > 0) rows.add(buildGroupHintRow(t.group, hidden));
                }
                groupRowViews.put(t.group, rows);
                for (View r : rows) listContainer.addView(r);
                i = j;
            }
        }
        tvEmpty.setVisibility(store.tracks.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /**
     * 组头行（mockup v2 定稿）：左朱砂竖条＋米色底；第一行《书名》＋「全N篇」，
     * 第二行汇总「今日读了 X 篇 · 共 Y 遍」与「本月进度 N / 总篇数 篇」；右上角三角
     * （收起 ▽／展开 △）。整行可点切换展开/收起（持久化），长按整组拖动。
     * 汇总数字在每次 buildTrackList 时现算（计数变化会触发重建，见 onCountChanged）。
     */
    private View buildGroupHeader(String group, int from, int to) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFF3ECDC);
        bg.setCornerRadius(dp(14));
        row.setBackground(bg);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(12);
        row.setLayoutParams(rlp);
        row.setTag("group:" + group);

        View bar = new View(this);
        bar.setBackgroundColor(getColor(R.color.accent));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(5),
                ViewGroup.LayoutParams.MATCH_PARENT);
        barLp.topMargin = dp(10);
        barLp.bottomMargin = dp(10);
        barLp.leftMargin = dp(8);
        bar.setLayoutParams(barLp);
        row.addView(bar);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), dp(11), dp(6), dp(11));
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(col);

        LinearLayout line1 = new LinearLayout(this);
        line1.setOrientation(LinearLayout.HORIZONTAL);
        line1.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(this);
        name.setText("《" + group + "》");
        name.setTextSize(18);
        name.setTypeface(null, Typeface.BOLD);
        name.setTextColor(getColor(R.color.ink));
        line1.addView(name);
        TextView all = new TextView(this);
        all.setText("  全" + cnNum(to - from) + "篇");
        all.setTextSize(13);
        all.setTextColor(getColor(R.color.muted));
        line1.addView(all);
        if ("论语".equals(group)) {
            // v1.21.0 章即小书：组头进入论语章（篇）卡片列表
            TextView chEntry = new TextView(this);
            chEntry.setText("  章列表 ›");
            chEntry.setTextSize(13);
            chEntry.setTextColor(getColor(R.color.accent));
            chEntry.setOnClickListener(v -> startActivity(new Intent(this, ChapterListActivity.class)
                    .putExtra(ChapterListActivity.EXTRA_BOOK_ID, "lunyu")));
            line1.addView(chEntry);
        }
        col.addView(line1);

        int todayPian = 0, todaySum = 0, monthPian = 0;
        for (int k = from; k < to; k++) {
            Track tk = store.tracks.get(k);
            int td = store.trackToday(tk);
            if (td > 0) { todayPian++; todaySum += td; }
            if (store.trackThisMonth(tk) > 0) monthPian++;
        }
        LinearLayout line2 = new LinearLayout(this);
        line2.setOrientation(LinearLayout.HORIZONTAL);
        TextView sum = new TextView(this);
        sum.setText("今日读了 " + todayPian + " 篇 · 共 " + todaySum + " 遍");
        sum.setTextSize(13);
        sum.setTextColor(getColor(R.color.ink));
        sum.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        line2.addView(sum);
        TextView prog = new TextView(this);
        prog.setText("本月进度 " + monthPian + " / " + (to - from) + " 篇");
        prog.setTextSize(13);
        prog.setTextColor(getColor(R.color.muted));
        line2.addView(prog);
        LinearLayout.LayoutParams l2lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l2lp.topMargin = dp(5);
        line2.setLayoutParams(l2lp);
        col.addView(line2);

        TextView tri = new TextView(this);
        tri.setText(expandedGroups.contains(group) ? "▲" : "▼");
        tri.setTextSize(15);
        tri.setTextColor(getColor(R.color.accent));
        tri.setPadding(dp(6), dp(10), dp(12), 0);
        row.addView(tri);

        row.setOnClickListener(v -> toggleGroup(group));
        // 长按组头：整组作为一个条目拖动（组内篇序固定，不单独拖）
        row.setOnLongClickListener(v -> {
            ClipData.Item item = new ClipData.Item("group:" + group);
            ClipData data = new ClipData("track", new String[]{ClipDescription.MIMETYPE_TEXT_PLAIN}, item);
            v.startDragAndDrop(data, new View.DragShadowBuilder(v), v, 0);
            v.setVisibility(View.INVISIBLE);
            return true;
        });
        row.setOnDragListener(trackDragListener);
        return row;
    }

    private void toggleGroup(String group) {
        if (expandedGroups.contains(group)) expandedGroups.remove(group);
        else expandedGroups.add(group);
        getSharedPreferences("ui_prefs", MODE_PRIVATE).edit()
                .putStringSet("expandedGroups", new HashSet<>(expandedGroups)).apply();
        buildTrackList();
    }

    /** 收起态底部提示行（有未露出的篇时才加）：浅色小字，点它也展开 */
    private View buildGroupHintRow(String group, int hidden) {
        TextView tv = new TextView(this);
        tv.setText("其余 " + hidden + " 篇先收着 · 点右上角 ▽ 看全部");
        tv.setTextSize(12.5f);
        tv.setTextColor(getColor(R.color.muted));
        tv.setPadding(dp(14), dp(9), dp(14), dp(9));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFF7F2E7);
        bg.setCornerRadius(dp(10));
        tv.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(16);
        lp.bottomMargin = dp(10);
        tv.setLayoutParams(lp);
        tv.setTag("group:" + group);
        tv.setOnClickListener(v -> toggleGroup(group));
        tv.setOnDragListener(trackDragListener);
        return tv;
    }

    /**
     * 组内篇行（mockup v2 定稿紧凑版式）：篇名（＋今日朱砂小标）、单行五数统计、
     * 圆形播放钮、金色「阅读」钮。不带齿轮/删除（篇目设置不在此行），不单独拖动
     * （只作拖放目标）。正在播放的篇沿用普通卡的高亮口径。
     */
    private View buildPianRow(Track ct) {
        boolean isCurrent = PlayerService.instance != null
                && PlayerService.instance.current != null
                && PlayerService.instance.current.id.equals(ct.id);
        boolean isPlaying = isCurrent && PlayerService.instance.playing;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setTag(ct.id);
        root.setBackgroundResource(isCurrent ? R.drawable.card_bg_playing : R.drawable.card_bg);
        root.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.leftMargin = dp(16);
        rlp.bottomMargin = dp(10);
        root.setLayoutParams(rlp);

        LinearLayout line1 = new LinearLayout(this);
        line1.setOrientation(LinearLayout.HORIZONTAL);
        line1.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        String t = ct.title;
        int dot = t.indexOf('·');
        if (t.startsWith("《") && dot > 0) t = t.substring(dot + 1); // 只显示篇名
        title.setText(t);
        title.setTextSize(16);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(getColor(R.color.ink));
        line1.addView(title);
        if (store.trackToday(ct) > 0) {
            TextView tag = new TextView(this);
            tag.setText("今日");
            tag.setTextSize(10);
            tag.setTypeface(null, Typeface.BOLD);
            tag.setTextColor(0xFFFFFFFF);
            tag.setPadding(dp(8), dp(2), dp(8), dp(2));
            GradientDrawable gb = new GradientDrawable();
            gb.setColor(getColor(R.color.accent));
            gb.setCornerRadius(dp(999));
            tag.setBackground(gb);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.leftMargin = dp(8);
            tag.setLayoutParams(tlp);
            line1.addView(tag);
        }
        root.addView(line1);

        LinearLayout line2 = new LinearLayout(this);
        line2.setOrientation(LinearLayout.HORIZONTAL);
        line2.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l2.topMargin = dp(5);
        line2.setLayoutParams(l2);
        TextView stats = new TextView(this);
        stats.setText(String.format("%s · %s",
                Store.fmtDurationAt(ct.durationMs, store.speed), cardStatText(ct)));
        stats.setTextSize(12.5f);
        stats.setTextColor(getColor(R.color.muted));
        stats.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        line2.addView(stats);

        if (ContentRepo.hasContent(ct)) {
            Button btnRead = new Button(this);
            btnRead.setText("阅读");
            btnRead.setTextSize(13);
            btnRead.setTextColor(getColor(R.color.gold));
            btnRead.setPadding(dp(13), 0, dp(13), 0);
            GradientDrawable rb = new GradientDrawable();
            rb.setColor(0x00000000);
            rb.setStroke(dp(1), getColor(R.color.gold));
            rb.setCornerRadius(dp(999));
            btnRead.setBackground(rb);
            btnRead.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)));
            btnRead.setOnClickListener(v -> openReader(ct));
            line2.addView(btnRead);
        }
        Button btnPlay = new Button(this);
        btnPlay.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        ((LinearLayout.LayoutParams) btnPlay.getLayoutParams()).leftMargin = dp(10);
        if (isCurrent) {
            btnPlay.setBackgroundResource(R.drawable.circle_red);
            btnPlay.setTextColor(0xFFFFFFFF);
            btnPlay.setText(isPlaying ? "❚❚" : "▶");
        } else {
            btnPlay.setBackgroundResource(R.drawable.circle_outline);
            btnPlay.setTextColor(getColor(R.color.accent));
            btnPlay.setText("▶");
        }
        btnPlay.setTextSize(16);
        btnPlay.setOnClickListener(v -> onPlayToggle(ct));
        line2.addView(btnPlay);
        root.addView(line2);

        root.setOnDragListener(trackDragListener);
        return root;
    }

    private static String cnNum(int n) {
        String[] d = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
        if (n < 10) return d[n];
        if (n < 20) return "十" + (n % 10 == 0 ? "" : d[n % 10]);
        return d[n / 10] + "十" + (n % 10 == 0 ? "" : d[n % 10]);
    }

    // ---------- 拖拽排序（v1.5 改为按曲目区间移动，组整体成块） ----------
    /** 视图 → 它代表的曲目区间 [start, end)：组头＝整组区间，卡片＝[i, i+1)；找不到返回 null */
    private int[] unitOfView(View v) {
        Object tag = v.getTag();
        if (tag instanceof String) {
            String s = (String) tag;
            if (s.startsWith("group:")) {
                String g = s.substring("group:".length());
                int start = -1, end = -1;
                for (int i = 0; i < store.tracks.size(); i++) {
                    if (g.equals(store.tracks.get(i).group)) {
                        if (start < 0) start = i;
                        end = i + 1;
                    } else if (start >= 0) break;
                }
                return start >= 0 ? new int[]{start, end} : null;
            }
            for (int i = 0; i < store.tracks.size(); i++) {
                if (s.equals(store.tracks.get(i).id)) return new int[]{i, i + 1};
            }
        }
        return null;
    }

    /** 把区间 [insertPos) 吸附到组边界：不允许把别的曲目插进一组中间（会把组拆成两截） */
    private int snapInsertPos(List<Track> list, int pos, int[] moving) {
        int i = 0;
        while (i < list.size()) {
            Track t = list.get(i);
            if (t.group == null) { i++; continue; }
            int j = i + 1;
            while (j < list.size() && t.group.equals(list.get(j).group)) j++;
            if (pos > i && pos < j) {
                // 落点在组内部：吸到较近的一端；若移动的正是这一组，回到原位
                if (moving != null && moving[0] == i && moving[1] == j) return i;
                return (pos - i <= j - pos) ? i : j;
            }
            i = j;
        }
        return pos;
    }

    /** 按当前 store.tracks 顺序把已建好的视图重新排进容器（不重建视图，拖拽中不断链） */
    private void rebuildChildOrder() {
        List<View> ordered = new ArrayList<>();
        int n = store.tracks.size();
        int i = 0;
        while (i < n) {
            Track t = store.tracks.get(i);
            if (t.group == null) {
                View c = cardViews.get(t.id);
                if (c != null) ordered.add(c);
                i++;
            } else {
                int j = i + 1;
                while (j < n && t.group.equals(store.tracks.get(j).group)) j++;
                View h = groupHeaderViews.get(t.group);
                if (h != null) ordered.add(h);
                java.util.List<View> rows = groupRowViews.get(t.group);
                if (rows != null) ordered.addAll(rows);
                i = j;
            }
        }
        listContainer.removeAllViews();
        java.util.Set<View> seen = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        for (View v : ordered) {
            // v1.14.1 防护：同一个视图对象只许加一次。万一数据异常（如一组被
            // 拆成两截、两截共用同一组头/行视图），宁可少显示一行，也不要让
            // addView 抛「child already has a parent」直接闪退
            if (seen.add(v)) listContainer.addView(v);
        }
    }

    /**
     * v1.14.1 闪退修复（用户报「每次改变不同经典的顺序时直接闪退」）：
     * 旧版在 ACTION_DRAG_ENTERED 回调里同步 rebuildChildOrder——removeAllViews
     * 再逐个 addView。可是这个回调正跑在框架的拖拽分发调用栈里
     * （ViewGroup.dispatchDragEvent 正在遍历子视图分发同一次拖拽事件），
     * 分发途中增删子视图会让框架的分发状态失效，新版 Android 上必崩；
     * 而不改变顺序的拖动走的是提前返回分支、根本不重排，所以现象恰好是
     * 「一改变顺序就闪退」。数据层逻辑本身没有问题（下标计算与组边界吸附
     * 已经桌面模糊测试 2 万余次事件验证：不越界、不拆组、不重复）。
     * 修法：回调里只改数据顺序（后续 ENTERED 的目标判定只看 tag 与数据、
     * 不看视图位置，立即改是安全的），视图重排 post 到消息队列——当前
     * 分发一结束就执行，用户无感知。
     */
    private void postRebuildChildOrder() {
        if (dragRebuildPosted) return;
        dragRebuildPosted = true;
        listContainer.post(() -> {
            dragRebuildPosted = false;
            rebuildChildOrder();
        });
    }

    /** 卡片与组头共用的拖拽监听：进入某个条目时，把被拖单元整块挪到它之前/之后 */
    private final View.OnDragListener trackDragListener = (v, event) -> {
        View dragged = (View) event.getLocalState();
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return event.getClipDescription() != null
                        && event.getClipDescription().hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN);
            case DragEvent.ACTION_DRAG_ENTERED: {
                if (dragged == null || dragged == v) return true;
                int[] block = unitOfView(dragged);
                int[] target = unitOfView(v);
                if (block == null || target == null) return true;
                if (target[0] >= block[0] && target[1] <= block[1]) return true; // 在自己块内
                int blockSize = block[1] - block[0];
                // 先在副本上算落点：向下拖放到目标之后、向上拖放到目标之前
                List<Track> copy = new ArrayList<>(store.tracks);
                List<Track> moving = new ArrayList<>(copy.subList(block[0], block[1]));
                copy.subList(block[0], block[1]).clear();
                int insertPos = (block[1] <= target[0])
                        ? target[1] - blockSize : target[0] - (target[0] > block[0] ? blockSize : 0);
                if (block[1] > target[0] && target[0] > block[0]) insertPos = target[0] - blockSize;
                else if (target[0] < block[0]) insertPos = target[0];
                if (block[1] <= target[0]) insertPos = target[1] - blockSize;
                insertPos = Math.max(0, Math.min(insertPos, copy.size()));
                int snapped = snapInsertPos(copy, insertPos, null);
                if (snapped == block[0]) return true; // 位置没变
                copy.addAll(snapped, moving);
                store.tracks.clear();
                store.tracks.addAll(copy);
                // v1.14.1：不在拖拽分发回调里同步重排视图（闪退根因，见
                // postRebuildChildOrder 的说明），延迟到分发结束执行
                postRebuildChildOrder();
                return true;
            }
            case DragEvent.ACTION_DRAG_ENDED:
                if (dragged != null) {
                    dragged.setVisibility(View.VISIBLE);
                    store.saveTracks(); // 顺序落盘
                }
                return true;
            default:
                return true;
        }
    };

    // ---------- 卡片阅读数（v1.12.0 单选 → v1.14.1 多选，总设置页勾选） ----------
    /** 时段键的规范顺序（设置页勾选与卡面显示共用此序） */
    static final String[] CARD_STAT_KEYS = {
            "today", "yesterday", "daybefore", "week", "lastweek", "month", "lastmonth", "total"};

    /** v1.14.1 默认勾选：今日、昨日、上周、本月、上月 */
    private static boolean isDefaultCardStat(String k) {
        return "today".equals(k) || "yesterday".equals(k) || "lastweek".equals(k)
                || "month".equals(k) || "lastmonth".equals(k);
    }

    /**
     * 当前生效的勾选集合（设置页与卡面共用同一口径）：
     * 新键 reader_prefs/cardStats（字符串集合）优先；老用户只有 v1.12.0 的
     * 单选键 cardStat 时，迁移为只含该项的集合（不丢已有设置）；都没有时
     * 用默认五项。返回的是可安全读取的副本。
     */
    static Set<String> cardStatSelection(SharedPreferences p) {
        Set<String> out = new HashSet<>();
        if (p.contains("cardStats")) {
            Set<String> s = p.getStringSet("cardStats", null);
            if (s != null) out.addAll(s);
            return out;
        }
        if (p.contains("cardStat")) {
            out.add(p.getString("cardStat", "today"));
            return out;
        }
        for (String k : CARD_STAT_KEYS) if (isDefaultCardStat(k)) out.add(k);
        return out;
    }

    /** 勾选的时段键按规范顺序排成列表；全不勾时兜底只显示今日（卡面不留空白） */
    private List<String> cardStatKeys() {
        Set<String> sel = cardStatSelection(getSharedPreferences("reader_prefs", MODE_PRIVATE));
        List<String> out = new ArrayList<>();
        for (String k : CARD_STAT_KEYS) if (sel.contains(k)) out.add(k);
        if (out.isEmpty()) out.add("today");
        return out;
    }

    static String cardStatLabel(String key) {
        switch (key) {
            case "yesterday": return "昨日";
            case "daybefore": return "前日";
            case "week": return "本周";
            case "lastweek": return "上周";
            case "month": return "本月";
            case "lastmonth": return "上月";
            case "total": return "总计";
            default: return "今日";
        }
    }

    /** 取数全部走 Store 既有分书分时段口径（前日＝trackCountOn 的前天，与昨日同一原语；
     *  上周/上月用 Store 既有的 trackLastWeek/trackLastMonth，与复制文本同口径） */
    private int cardStatCount(Track t, String key) {
        switch (key) {
            case "yesterday": return store.trackYesterday(t);
            case "daybefore": return store.trackCountOn(t.id, Store.today().minusDays(2));
            case "week": return store.trackThisWeek(t);
            case "lastweek": return store.trackLastWeek(t);
            case "month": return store.trackThisMonth(t);
            case "lastmonth": return store.trackLastMonth(t);
            case "total": return store.trackTotal(t);
            default: return store.trackToday(t);
        }
    }

    /** 小字行：所有勾选时段按规范顺序以「 · 」连接，如「今日 2 遍 · 昨日 1 遍 · 上周 0 遍」。
     *  卡片小字行与篇行统计的 TextView 都不设单行限制，长串自动换行显示、不截断 */
    private String cardStatText(Track t) {
        StringBuilder sb = new StringBuilder();
        for (String k : cardStatKeys()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(cardStatLabel(k)).append(' ').append(cardStatCount(t, k)).append(" 遍");
        }
        return sb.toString();
    }

    private void bindCard(View card, Track t) {
        card.setTag(t.id); // 拖拽排序按 tag 定位曲目区间（见 unitOfView）
        TextView title = card.findViewById(R.id.tvTitle);
        TextView duration = card.findViewById(R.id.tvDuration);
        TextView todayCount = card.findViewById(R.id.tvTodayCount);
        TextView subCounts = card.findViewById(R.id.tvSubCounts);
        TextView lastPos = card.findViewById(R.id.tvLastPos);
        ProgressBar progress = card.findViewById(R.id.progress);
        Button btnPlay = card.findViewById(R.id.btnPlay);
        TextView btnDelete = card.findViewById(R.id.btnDelete);
        TextView btnSettings = card.findViewById(R.id.btnSettings);
        View todayRow = card.findViewById(R.id.todayRow);
        TextView btnMinus = card.findViewById(R.id.btnMinus);
        TextView btnPlus = card.findViewById(R.id.btnPlus);

        title.setText(t.title);
        duration.setText(Store.fmtDurationAt(t.durationMs, store.speed));
        todayCount.setText(String.valueOf(store.trackToday(t)));
        // v1.14.1：小字行显示总设置页勾选的全部时段（带口径标签，如「今日 3 遍 · 上周 1 遍」）
        subCounts.setText(cardStatText(t));
        updateCardProgress(card, t);

        boolean isCurrent = PlayerService.instance != null
                && PlayerService.instance.current != null
                && PlayerService.instance.current.id.equals(t.id);
        boolean isPlaying = isCurrent && PlayerService.instance.playing;
        View root = card.findViewById(R.id.cardRoot);
        if (isCurrent) {
            root.setBackgroundResource(R.drawable.card_bg_playing);
            btnPlay.setBackgroundResource(R.drawable.circle_red);
            btnPlay.setTextColor(0xFFFFFFFF);
            btnPlay.setText(isPlaying ? "❚❚" : "▶");
        } else {
            root.setBackgroundResource(R.drawable.card_bg);
            btnPlay.setBackgroundResource(R.drawable.circle_outline);
            btnPlay.setTextColor(getColor(R.color.accent));
            btnPlay.setText("▶");
        }

        // v1.4 阅读钮：只有配了原文内容包的音频才显示（金色书本），点开阅读页
        Button btnRead = card.findViewById(R.id.btnRead);
        if (ContentRepo.hasContent(t)) {
            btnRead.setVisibility(View.VISIBLE);
            btnRead.setOnClickListener(v -> openReader(t));
        } else {
            btnRead.setVisibility(View.GONE);
        }

        // v1.21.0 章列表入口：有内容包且章数 > 1 的书，卡上进章卡片列表（章即小书）
        Button btnChapters = card.findViewById(R.id.btnChapters);
        String cardBookId = PlanBooks.bookIdOfTrackFull(t);
        ContentRepo.Book cardBook = cardBookId != null ? ContentRepo.load(this, cardBookId) : null;
        if (cardBook != null && cardBook.chapters.size() > 1) {
            btnChapters.setVisibility(View.VISIBLE);
            final String bid = cardBookId;
            btnChapters.setOnClickListener(v -> startActivity(new Intent(this, ChapterListActivity.class)
                    .putExtra(ChapterListActivity.EXTRA_BOOK_ID, bid)));
        } else {
            btnChapters.setVisibility(View.GONE);
        }

        btnPlay.setOnClickListener(v -> onPlayToggle(t));
        btnDelete.setOnClickListener(v -> onDelete(t));
        btnSettings.setOnClickListener(v -> startActivity(
                new Intent(this, TrackSettingsActivity.class)
                        .putExtra(TrackSettingsActivity.EXTRA_TRACK_ID, t.id)));

        // 点「今日 X 遍」区域展开/收起 − ＋ 步进器，手动增减今日遍数（最低 0）
        boolean expanded = expandedSteppers.contains(t.id);
        btnMinus.setVisibility(expanded ? View.VISIBLE : View.GONE);
        btnPlus.setVisibility(expanded ? View.VISIBLE : View.GONE);
        todayRow.setOnClickListener(v -> {
            if (expandedSteppers.contains(t.id)) expandedSteppers.remove(t.id);
            else expandedSteppers.add(t.id);
            buildTrackList();
        });
        btnMinus.setOnClickListener(v -> adjustToday(t, -1));
        btnPlus.setOnClickListener(v -> adjustToday(t, 1));

        // 长按拖动排序：无组曲目可单独拖；组内篇目不单独拖（整组由组头拖动，篇序固定）
        if (t.group == null) {
            card.setOnLongClickListener(v -> {
                ClipData.Item item = new ClipData.Item(t.id);
                ClipData data = new ClipData("track", new String[]{ClipDescription.MIMETYPE_TEXT_PLAIN}, item);
                v.startDragAndDrop(data, new View.DragShadowBuilder(v), v, 0);
                v.setVisibility(View.INVISIBLE);
                return true;
            });
        }
        card.setOnDragListener(trackDragListener);
    }

    private void updateCardProgress(View card, Track t) {
        ProgressBar progress = card.findViewById(R.id.progress);
        TextView lastPos = card.findViewById(R.id.tvLastPos);
        if (t.durationMs > 0) {
            progress.setProgress((int) Math.min(1000, t.positionMs * 1000 / t.durationMs));
        } else {
            progress.setProgress(0);
        }
        lastPos.setText("上次听到 " + Store.fmtTimeAt(t.positionMs, store.speed));
    }

    // ---------- 速度 ----------
    private void buildSpeedChips() {
        speedRow.removeAllViews();
        speedChips.clear();
        for (int i = 0; i < SPEEDS.length; i++) {
            final float val = SPEEDS[i];
            TextView chip = new TextView(this);
            chip.setText(SPEED_LABELS[i]);
            chip.setTextSize(12);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(6), dp(6), dp(6), dp(6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (i > 0) lp.leftMargin = dp(6);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                if (PlayerService.instance != null) {
                    PlayerService.instance.setSpeed(val);
                } else {
                    store.speed = val;
                    store.saveSettings();
                }
                // v1.12.0：倍速一改，卡片时长与播放条时长当下就按新倍速重渲
                refreshAll();
            });
            speedChips.add(chip);
            speedRow.addView(chip);
        }
        refreshSpeedChips();
    }

    private void refreshSpeedChips() {
        for (int i = 0; i < SPEEDS.length; i++) {
            boolean sel = Math.abs(SPEEDS[i] - store.speed) < 0.01f;
            TextView chip = speedChips.get(i);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(999));
            if (sel) {
                g.setColor(0xFFFFFFFF);
                chip.setTextColor(0xFF26221C);
                chip.setTypeface(null, Typeface.BOLD);
            } else {
                g.setColor(0x00000000);
                g.setStroke(dp(1), 0xFF6F675C);
                chip.setTextColor(0xFFDDD6CB);
                chip.setTypeface(null, Typeface.NORMAL);
            }
            chip.setBackground(g);
        }
    }

    // ---------- 页面切换（v1.21.0 底部三栏：0 听读 / 1 复习 / 2 阅读计划） ----------
    private void showPage(int page) {
        curPage = page;
        showingCalendar = page == 2;
        pagePlay.setVisibility(page == 0 ? View.VISIBLE : View.GONE);
        pageReview.setVisibility(page == 1 ? View.VISIBLE : View.GONE);
        pagePlan.setVisibility(page == 2 ? View.VISIBLE : View.GONE);
        Button[] navs = {navPlay, navReview, navPlan};
        for (int i = 0; i < navs.length; i++) {
            boolean on = i == page;
            navs[i].setTextColor(getColor(on ? R.color.accent : R.color.muted));
            navs[i].setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
        }
        if (page == 1) loadReviewPage();
        if (page == 2) {
            buildPlanPage();
            buildCalendar();
        }
    }

    // ---------- v1.21.0 阅读计划页 ----------

    private TextView planText(String text, float sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sp);
        tv.setTextColor(getColor(color));
        if (bold) tv.setTypeface(null, Typeface.BOLD);
        return tv;
    }

    private TextView planTextLp(String text, float sp, int color, boolean bold, int topMargin) {
        TextView tv = planText(text, sp, color, bold);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topMargin);
        tv.setLayoutParams(lp);
        return tv;
    }

    private View planCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.card_bg);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        card.setLayoutParams(lp);
        return card;
    }

    /** 阅读计划页上半（下半诵读日历在布局里、由 buildCalendar 填充，口径不改） */
    private void buildPlanPage() {
        if (planContainer == null) return;
        planContainer.removeAllViews();
        PlanStore ps = PlanStore.get(this);
        PlanData plan = ps.plan();
        LocalDate today = LocalDate.now();
        if (plan == null || PlanData.STATUS_FINISHED.equals(plan.status)) {
            if (plan != null) {
                planContainer.addView(planTextLp("上一个计划已结束", 13, R.color.muted, false, 2));
            }
            LinearLayout card = (LinearLayout) planCard();
            card.addView(planText("9621 读经大挑战", 19, R.color.ink, true));
            card.addView(planTextLp("九个月内读完 · 六本书 · 每本 21 遍", 13.5f, R.color.muted, false, 6));
            card.addView(planTextLp("大学 · 中庸 · 论语 · 孟子 · 坛经 · 道德经", 13.5f, R.color.muted, false, 2));
            TextView use = planTextLp("选用这个计划", 15, R.color.accent, true, 12);
            use.setGravity(Gravity.CENTER);
            use.setOnClickListener(v -> startActivity(new Intent(this, PlanEditActivity.class)
                    .putExtra(PlanEditActivity.EXTRA_MODE, PlanEditActivity.MODE_PRESET)));
            card.addView(use);
            planContainer.addView(card);

            LinearLayout custom = (LinearLayout) planCard();
            custom.addView(planText("自定计划", 17, R.color.ink, true));
            custom.addView(planTextLp("按自己的书目、遍数与期限排", 13.5f, R.color.muted, false, 4));
            custom.setOnClickListener(v -> startActivity(new Intent(this, PlanEditActivity.class)
                    .putExtra(PlanEditActivity.EXTRA_MODE, PlanEditActivity.MODE_CUSTOM)));
            planContainer.addView(custom);
            return;
        }

        PlanEngine.Result res = ps.compute(today);
        ps.persistDerived(res);
        boolean paused = PlanData.STATUS_PAUSED.equals(plan.status);

        // 头部
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = planText(plan.name, 20, R.color.ink, true);
        name.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        head.addView(name);
        TextView settings = planText("设置", 13.5f, R.color.muted, false);
        settings.setBackgroundResource(R.drawable.pill_outline);
        settings.setPadding(dp(12), dp(6), dp(12), dp(6));
        settings.setOnClickListener(v -> startActivity(new Intent(this, PlanEditActivity.class)
                .putExtra(PlanEditActivity.EXTRA_MODE, PlanEditActivity.MODE_SETTINGS)));
        head.addView(settings);
        planContainer.addView(head);

        LocalDate dl = LocalDate.parse(plan.deadline);
        planContainer.addView(planTextLp("截止 " + dl.getYear() + " 年 " + dl.getMonthValue()
                + " 月 " + dl.getDayOfMonth() + " 日 · 还剩 " + Math.max(0, res.remainingDays) + " 天",
                13.5f, R.color.muted, false, 6));
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(res.totalProgressPct);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.accent)));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        blp.topMargin = dp(10);
        bar.setLayoutParams(blp);
        planContainer.addView(bar);
        planContainer.addView(planTextLp("总进度 " + res.totalProgressPct + "%", 13, R.color.muted, false, 4));
        String statusLine;
        if (paused) {
            statusLine = "已暂停";
        } else if (res.finished) {
            statusLine = "已读完";
        } else if (res.pace == PlanEngine.Pace.SLOW) {
            statusLine = "慢于节奏 · 预计晚 " + res.lateDays + " 天";
        } else {
            String verb = res.pace == PlanEngine.Pace.FAST ? "比节奏快" : "正好在节奏上";
            LocalDate pf = res.predictedFinish;
            statusLine = verb + (pf != null ? " · 预计 " + pf.getYear() + " 年 " + pf.getMonthValue()
                    + " 月 " + pf.getDayOfMonth() + " 日读毕" : "");
        }
        planContainer.addView(planTextLp(statusLine, 14.5f, R.color.ink, false, 6));

        // 今日块
        planContainer.addView(planTextLp("今 日", 13, R.color.accent, true, 20));
        if (paused) {
            planContainer.addView(planTextLp("计划暂停中 · 到设置里恢复", 15, R.color.muted, false, 6));
        } else if (res.currentStage < 0) {
            planContainer.addView(planTextLp("六本书都读完了", 15, R.color.ink, false, 6));
        } else {
            buildPlanTodayBlock(ps, plan, res, today);
        }

        // 阶段表
        planContainer.addView(planTextLp("阶 段", 13, R.color.accent, true, 20));
        ChapterStatsStore stats = ChapterStatsStore.get(this);
        for (int i = 0; i < plan.stages.size(); i++) {
            PlanData.Stage st = plan.stages.get(i);
            int done = res.donePerStage.get(i);
            boolean completed = res.completedPerStage.get(i);
            boolean avail = PlanBooks.isAvailable(this, st.bookId);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(8);
            row.setLayoutParams(rlp);
            TextView nm = planText("《" + st.bookTitle + "》", 15.5f, R.color.ink, false);
            nm.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(nm);
            String right;
            if (completed) right = "已完成 ✓";
            else if (i == res.currentStage) {
                int curPass = st.totalUnits > 0
                        ? Math.min(st.totalPasses, done * st.totalPasses / st.totalUnits + 1) : 1;
                right = st.isZhang() ? "进行中 · 第 " + curPass + " 遍" : "第 " + curPass + " 遍 · 进行中";
            } else if (!avail) right = "还没下载 · 手动记录";
            else right = "0 / " + st.totalPasses + " 遍";
            row.addView(planText(right, 13.5f, completed ? R.color.accent : R.color.muted, false));
            planContainer.addView(row);
            if (st.isZhang()) {
                planContainer.addView(planTextLp("细账 " + done + " / " + st.totalUnits + " "
                        + st.unitName + "·遍（见章卡片）", 12, R.color.muted, false, 1));
            }
        }
        if ("preset9621".equals(plan.source)) {
            planContainer.addView(planTextLp("原表建议节奏  大学 5 遍/天 · 中庸 3 遍/天 · 论语 5 遍/章/天"
                    + " · 孟子 3 遍/章/天 · 坛经 5 遍/章/天 · 道德经 10 遍/5 章/天", 11.5f, R.color.muted, false, 12));
        }
        planContainer.addView(planTextLp("整轨自然播完才计一遍 · 跟读遍也算 · 章级播到章尾记一遍",
                11.5f, R.color.muted, false, 4));

        // 近况热力格
        planContainer.addView(planTextLp("近 况", 13, R.color.accent, true, 20));
        GridLayout heat = new GridLayout(this);
        heat.setColumnCount(14);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(8);
        heat.setLayoutParams(hlp);
        int cellW = (getResources().getDisplayMetrics().widthPixels - dp(32) - dp(26)) / 14;
        for (int back = 97; back >= 0; back--) {
            LocalDate d = today.minusDays(back);
            int total = store.totalCountOn(d) + manualUnitsOn(ps, d);
            TextView cell = new TextView(this);
            GridLayout.LayoutParams clp = new GridLayout.LayoutParams();
            clp.width = cellW - dp(2);
            clp.height = cellW - dp(2);
            clp.setMargins(0, 0, dp(2), dp(2));
            cell.setLayoutParams(clp);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(2));
            int color = total <= 0 ? 0xFFECE7DB : total <= 2 ? 0xFFDCE3D0
                    : total <= 4 ? 0xFFB9C9A4 : total <= 8 ? 0xFF8FAE7E : 0xFF6E9463;
            g.setColor(color);
            if (d.equals(today)) g.setStroke(dp(2), getColor(R.color.accent));
            cell.setBackground(g);
            heat.addView(cell);
        }
        planContainer.addView(heat);
    }

    private int manualUnitsOn(PlanStore ps, LocalDate date) {
        PlanData plan = ps.plan();
        if (plan == null) return 0;
        Map<Integer, Integer> m = plan.manualEntries.get(date.toString());
        if (m == null) return 0;
        int sum = 0;
        for (int v : m.values()) sum += v;
        return sum;
    }

    private void buildPlanTodayBlock(PlanStore ps, PlanData plan, PlanEngine.Result res, LocalDate today) {
        final int ci = res.currentStage;
        PlanData.Stage st = plan.stages.get(ci);
        boolean avail = PlanBooks.isAvailable(this, st.bookId);
        int target = res.todaySuggestionUnits;
        int done = res.todayDone;
        if (target > 0 && done >= target) {
            planContainer.addView(planTextLp("今日建议已完成", 16, R.color.ink, true, 6));
        } else if (st.isZhang()) {
            planContainer.addView(planTextLp("《" + st.bookTitle + "》 建议 读 " + res.todayK + " "
                    + st.unitName + " · 每" + st.unitName + " " + res.todayN + " 遍", 16, R.color.ink, false, 6));
            String zhangLine = "已记 " + done + " " + st.unitName + "·遍"
                    + (target > done ? " · 还差 " + (target - done) + " " + st.unitName + "·遍" : "");
            long passMsZ = PlanBooks.bookPassDurationMs(store, st.bookId);
            if (target > done && passMsZ > 0 && st.totalUnits > 0 && store.speed > 0) {
                long mins = Math.round((double) passMsZ / st.totalUnits * (target - done)
                        / 60000.0 / store.speed);
                zhangLine += " · 约 " + Math.max(1, mins) + " 分钟";
            }
            planContainer.addView(planTextLp(zhangLine, 13.5f, R.color.muted, false, 2));
        } else {
            planContainer.addView(planTextLp("《" + st.bookTitle + "》 建议 " + target + " 遍 · 已读 "
                    + done + " 遍", 16, R.color.ink, false, 6));
            if (target > done) {
                String extra = "还差 " + (target - done) + " 遍";
                long passMs = PlanBooks.bookPassDurationMs(store, st.bookId);
                if (passMs > 0 && store.speed > 0) {
                    long mins = Math.round(passMs * (target - done) / 60000.0 / store.speed);
                    extra += " · 约 " + Math.max(1, mins) + " 分钟";
                }
                planContainer.addView(planTextLp(extra, 13.5f, R.color.muted, false, 2));
            }
            // 每单位一圆，满者朱砂（建议量过大时只画到达标线所需，封顶 30 枚防版面失控）
            if (target > 0 && target <= 30) {
                LinearLayout dots = new LinearLayout(this);
                dots.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                dlp.topMargin = dp(8);
                dots.setLayoutParams(dlp);
                for (int i = 0; i < target; i++) {
                    TextView dot = new TextView(this);
                    GradientDrawable g = new GradientDrawable();
                    g.setShape(GradientDrawable.OVAL);
                    if (i < done) g.setColor(getColor(R.color.accent));
                    else { g.setColor(0x00000000); g.setStroke(dp(1), 0xFFB9B1A2); }
                    dot.setBackground(g);
                    LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(dp(13), dp(13));
                    lp2.rightMargin = dp(6);
                    dot.setLayoutParams(lp2);
                    dots.addView(dot);
                }
                planContainer.addView(dots);
            }
        }
        // 覆盖提示：阶段将满且有未读章（只提示不拦截；仅章账可查的书）
        if (st.isZhang() && avail && st.totalUnits > 0
                && res.donePerStage.get(ci) * 5 >= st.totalUnits * 4) {
            ChapterStatsStore stats = ChapterStatsStore.get(this);
            int unread = 0;
            for (int c = 0; c < st.chapterCount; c++) {
                if (stats.chapterTotal(st.bookId, c) <= 0) unread++;
            }
            if (unread > 0) {
                planContainer.addView(planTextLp("还有 " + unread + " " + st.unitName + "未读过",
                        12.5f, R.color.muted, false, 6));
            }
        }
        // 手动入账入口
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(10);
        btns.setLayoutParams(blp);
        if (!avail) {
            TextView doneBtn = planText("今日已按建议读完", 13.5f, R.color.ink, false);
            doneBtn.setBackgroundResource(R.drawable.pill_outline);
            doneBtn.setPadding(dp(12), dp(7), dp(12), dp(7));
            doneBtn.setOnClickListener(v -> {
                ps.addManualToday(ci, target, today);
                buildPlanPage();
                buildPlanBand();
            });
            btns.addView(doneBtn);
        }
        TextView noteBtn = planText("记一笔", 13.5f, R.color.ink, false);
        noteBtn.setBackgroundResource(R.drawable.pill_outline);
        noteBtn.setPadding(dp(12), dp(7), dp(12), dp(7));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.leftMargin = dp(8);
        noteBtn.setLayoutParams(nlp);
        noteBtn.setOnClickListener(v -> showPlanNoteDialog(ps, st, ci, today));
        btns.addView(noteBtn);
        planContainer.addView(btns);
    }

    private void showPlanNoteDialog(PlanStore ps, PlanData.Stage st, int stageIdx, LocalDate today) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        final EditText first = new EditText(this);
        first.setInputType(InputType.TYPE_CLASS_NUMBER);
        first.setTextSize(16);
        final EditText second = new EditText(this);
        second.setInputType(InputType.TYPE_CLASS_NUMBER);
        second.setTextSize(16);
        if (st.isZhang()) {
            first.setHint("读了几" + st.unitName + "（如 1）");
            second.setHint("每" + st.unitName + "几遍（如 " + Math.max(1, st.passesPerChapter) + "）");
            form.addView(first);
            form.addView(second);
        } else {
            first.setHint("读了几遍");
            form.addView(first);
        }
        new AlertDialog.Builder(this)
                .setTitle("记一笔 · 《" + st.bookTitle + "》")
                .setView(form)
                .setNegativeButton("取消", null)
                .setPositiveButton("记下", (d, w) -> {
                    int a = parsePlanInt(first.getText().toString(), 0);
                    int units;
                    if (st.isZhang()) {
                        int b = parsePlanInt(second.getText().toString(), 0);
                        units = a * b;
                    } else units = a;
                    if (units <= 0) {
                        Toast.makeText(this, "没有填数量", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    ps.addManualToday(stageIdx, units, today);
                    Toast.makeText(this, "已记 " + units + (st.isZhang() ? " " + st.unitName + "·遍" : " 遍"),
                            Toast.LENGTH_SHORT).show();
                    buildPlanPage();
                    buildPlanBand();
                })
                .show();
    }

    private int parsePlanInt(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return fallback; }
    }

    /** 首页今日进度带（候选 A）：仅 active 计划时渲染，点按跳阅读计划页 */
    private void buildPlanBand() {
        if (todayPlanBand == null) return;
        todayPlanBand.removeAllViews();
        PlanStore ps = PlanStore.get(this);
        PlanData plan = ps.plan();
        if (plan == null || !PlanData.STATUS_ACTIVE.equals(plan.status)) {
            todayPlanBand.setVisibility(View.GONE);
            return;
        }
        PlanEngine.Result res = ps.compute(LocalDate.now());
        if (res == null || res.currentStage < 0) {
            todayPlanBand.setVisibility(View.GONE);
            return;
        }
        PlanData.Stage st = plan.stages.get(res.currentStage);
        int target = res.todaySuggestionUnits;
        int done = res.todayDone;
        boolean finishedToday = target > 0 && done >= target;
        TextView line = new TextView(this);
        line.setTextSize(14);
        line.setTextColor(getColor(R.color.ink));
        String txt;
        if (st.isZhang()) {
            txt = "今日 《" + st.bookTitle + "》 建议 读 " + res.todayK + " " + st.unitName
                    + " · 每" + st.unitName + " " + res.todayN + " 遍 · 已记 " + done + " "
                    + st.unitName + "·遍" + (finishedToday ? "" : " · 还差 " + Math.max(0, target - done)
                    + " " + st.unitName + "·遍");
        } else {
            txt = "今日 《" + st.bookTitle + "》 建议 " + target + " 遍 · 已读 " + done + " 遍"
                    + (finishedToday ? "" : " · 还差 " + Math.max(0, target - done) + " 遍");
        }
        line.setText(txt);
        todayPlanBand.addView(line);
        if (finishedToday) {
            TextView check = new TextView(this);
            check.setText("✓");
            check.setTextSize(13);
            check.setTextColor(0xFF8FAE7E);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.leftMargin = dp(6);
            // 放在同一行：重组为横行
            todayPlanBand.removeAllViews();
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            line.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(line);
            row.addView(check);
            todayPlanBand.addView(row);
        }
        // 极细进度线：朱砂只走已完成段
        LinearLayout lineBar = new LinearLayout(this);
        lineBar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
        llp.topMargin = dp(6);
        lineBar.setLayoutParams(llp);
        int totalW = Math.max(1, target);
        View fill = new View(this);
        fill.setBackgroundColor(getColor(R.color.accent));
        fill.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT,
                Math.min(done, totalW)));
        lineBar.addView(fill);
        View rest = new View(this);
        rest.setBackgroundColor(0xFFE9E2D6);
        rest.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(0, totalW - Math.min(done, totalW))));
        lineBar.addView(rest);
        todayPlanBand.addView(lineBar);
        todayPlanBand.setVisibility(View.VISIBLE);
        todayPlanBand.setOnClickListener(v -> showPage(2));
    }

    // ---------- v1.16.0 复习页 ----------

    /** 书名展示：内容包 title 若没带书名号则补上 */
    private static String bookTitleOf(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        return raw.startsWith("《") ? raw : "《" + raw + "》";
    }

    /** 组装复习页数据：识读统计（书→字→小句行）＋笔记（倒序） */
    private JSONObject buildReviewJson() {
        JSONObject root = new JSONObject();
        try {
            // —— 不熟练的字 ——
            // v1.18.0 复习页分档：主列表＝确定错＋四类高权重字中被报过的；
            // dual 单列人工确认区；doubt（低先验单引擎）折叠；DEL 单列折叠。
            Map<String, Map<String, List<ReciteStatsV2.Record>>> byBook = new HashMap<>();
            JSONArray dualArr = new JSONArray();
            JSONArray doubtArr = new JSONArray();
            for (ReciteStatsV2.Record r : ReciteStatsV2.get(this).allRecords()) {
                boolean highCat = com.ti.songdu.recite.PriorTable.isHighCategory(r.category);
                if ("confirmed".equals(r.tier) || highCat && !"dual".equals(r.tier)
                        && !"doubt".equals(r.tier)) {
                    byBook.computeIfAbsent(r.bookId, k -> new HashMap<>())
                            .computeIfAbsent(r.hanzi, k -> new ArrayList<>())
                            .add(r);
                } else if ("dual".equals(r.tier)) {
                    JSONObject dobj = new JSONObject();
                    dobj.put("key", r.key());
                    dobj.put("bookId", r.bookId);
                    dobj.put("hanzi", r.hanzi);
                    dobj.put("pyToned", r.pyToned);
                    dobj.put("heardMain", r.heardMain);
                    dobj.put("clauseText", r.clauseText);
                    dobj.put("posInClause", Math.max(0, r.ci - r.clauseHanziStart));
                    dobj.put("sentId", r.sentId);
                    dobj.put("count", r.count);
                    dobj.put("score", r.score);
                    dualArr.put(dobj);
                } else if ("doubtHigh".equals(r.tier)) {
                    // 存疑·高（非高权重字）：不进主列表，与存疑一同折叠
                    JSONObject dobj = new JSONObject();
                    dobj.put("bookId", r.bookId);
                    dobj.put("hanzi", r.hanzi);
                    dobj.put("pyToned", r.pyToned);
                    dobj.put("heardMain", r.heardMain);
                    dobj.put("clauseText", r.clauseText);
                    dobj.put("posInClause", Math.max(0, r.ci - r.clauseHanziStart));
                    dobj.put("sentId", r.sentId);
                    dobj.put("count", r.count);
                    doubtArr.put(dobj);
                } else {
                    JSONObject dobj = new JSONObject();
                    dobj.put("bookId", r.bookId);
                    dobj.put("hanzi", r.hanzi);
                    dobj.put("pyToned", r.pyToned);
                    dobj.put("heardMain", r.heardMain);
                    dobj.put("clauseText", r.clauseText);
                    dobj.put("posInClause", Math.max(0, r.ci - r.clauseHanziStart));
                    dobj.put("sentId", r.sentId);
                    dobj.put("count", r.count);
                    doubtArr.put(dobj);
                }
            }
            root.put("dual", dualArr);
            root.put("doubt", doubtArr);
            JSONArray delArr = new JSONArray();
            for (ReciteStatsV2.Del d : ReciteStatsV2.get(this).allDels()) {
                JSONObject dobj = new JSONObject();
                dobj.put("bookId", d.bookId);
                dobj.put("hanzi", d.hanzi);
                dobj.put("pyToned", d.pyToned);
                dobj.put("clauseText", d.clauseText);
                dobj.put("sentId", d.sentId);
                dobj.put("count", d.count);
                delArr.put(dobj);
            }
            root.put("dels", delArr);
            List<String> bookIds = new ArrayList<>(byBook.keySet());
            // 书序：大学、中庸、论语在前，其余按最近出错时间倒序
            Map<String, Long> bookLast = new HashMap<>();
            for (String bid : bookIds) {
                long mx = 0;
                for (List<ReciteStatsV2.Record> rs : byBook.get(bid).values()) {
                    for (ReciteStatsV2.Record r : rs) mx = Math.max(mx, r.lastTs);
                }
                bookLast.put(bid, mx);
            }
            List<String> canon = new ArrayList<>();
            for (String c : new String[]{"daxue", "zhongyong", "lunyu"}) {
                if (bookIds.contains(c)) canon.add(c);
            }
            bookIds.removeAll(canon);
            bookIds.sort((a, b) -> Long.compare(bookLast.get(b), bookLast.get(a)));
            canon.addAll(bookIds);

            JSONArray booksArr = new JSONArray();
            for (String bid : canon) {
                ContentRepo.Book bk = ContentRepo.load(this, bid);
                JSONObject bo = new JSONObject();
                bo.put("bookId", bid);
                bo.put("title", bookTitleOf(bk != null ? bk.title : bid));
                Map<String, List<ReciteStatsV2.Record>> byChar = byBook.get(bid);
                List<String> chars = new ArrayList<>(byChar.keySet());
                Map<String, Integer> charTotal = new HashMap<>();
                int bookTotal = 0;
                for (String hz : chars) {
                    int t = 0;
                    for (ReciteStatsV2.Record r : byChar.get(hz)) t += r.count;
                    charTotal.put(hz, t);
                    bookTotal += t;
                }
                chars.sort((a, b) -> Integer.compare(charTotal.get(b), charTotal.get(a)));
                bo.put("total", bookTotal);
                JSONArray charsArr = new JSONArray();
                for (String hz : chars) {
                    JSONObject co = new JSONObject();
                    co.put("hanzi", hz);
                    co.put("total", charTotal.get(hz));
                    List<ReciteStatsV2.Record> rs = new ArrayList<>(byChar.get(hz));
                    rs.sort((a, b) -> Long.compare(b.lastTs, a.lastTs));
                    JSONArray rowsArr = new JSONArray();
                    for (ReciteStatsV2.Record r : rs) {
                        JSONObject ro = new JSONObject();
                        ro.put("clauseText", r.clauseText);
                        ro.put("posInClause", Math.max(0, r.ci - r.clauseHanziStart));
                        ro.put("pyToned", r.pyToned);
                        ro.put("sentId", r.sentId);
                        ro.put("count", r.count);
                        rowsArr.put(ro);
                    }
                    co.put("rows", rowsArr);
                    charsArr.put(co);
                }
                bo.put("chars", charsArr);
                booksArr.put(bo);
            }
            root.put("books", booksArr);

            // —— 我的笔记 ——
            JSONArray notesArr = new JSONArray();
            java.text.SimpleDateFormat df = new java.text.SimpleDateFormat(
                    "M月d日", java.util.Locale.CHINA);
            for (NotesStore.Item it : NotesStore.get(this).allDesc()) {
                JSONObject no = new JSONObject();
                no.put("bookId", it.bookId);
                no.put("sentId", it.sentId);
                no.put("sentText", it.sentText == null ? "" : it.sentText);
                no.put("note", it.note == null ? "" : it.note);
                no.put("mark", it.mark);
                no.put("markName", it.mark >= 0 && it.mark < NotesStore.MARK_NAMES.length
                        ? NotesStore.MARK_NAMES[it.mark] : "");
                ContentRepo.Book bk = ContentRepo.load(this, it.bookId);
                String src = bookTitleOf(bk != null ? bk.title : it.bookId);
                if (bk != null && it.chapterId != null && !it.chapterId.isEmpty()) {
                    for (ContentRepo.Chapter ch : bk.chapters) {
                        if (it.chapterId.equals(ch.id) && ch.title != null
                                && !ch.title.isEmpty()) {
                            src = src + " " + ch.title;
                            break;
                        }
                    }
                }
                no.put("src", src);
                no.put("dateStr", df.format(new java.util.Date(it.updatedAt)));
                notesArr.put(no);
            }
            root.put("notes", notesArr);
        } catch (Exception ignored) {
        }
        return root;
    }

    private String reviewTemplate;

    private void loadReviewPage() {
        try {
            if (reviewTemplate == null) {
                InputStream in = getAssets().open("review.html");
                byte[] buf = new byte[in.available()];
                int off = 0;
                while (off < buf.length) {
                    int n = in.read(buf, off, buf.length - off);
                    if (n < 0) break;
                    off += n;
                }
                in.close();
                reviewTemplate = new String(buf, 0, off, java.nio.charset.StandardCharsets.UTF_8);
            }
            String html = reviewTemplate.replace("__REVIEW_JSON__",
                    buildReviewJson().toString());
            pageReview.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
        } catch (Exception ignored) {
        }
    }

    /** 复习页点条目跳回原句：找到该书的曲目打开阅读页并定位到句 */
    private void jumpToSentence(String bookId, String sentId) {
        Track target = null;
        for (Track t : store.tracks) {
            if (!bookId.equals(ContentRepo.bookIdFor(t))) continue;
            if (!"lunyu".equals(bookId)) {
                target = t;
                break;
            }
            // 论语按篇：找含该句的那一篇
            ContentRepo.Book b = ContentRepo.bookForTrack(this, t);
            if (b == null) continue;
            for (ContentRepo.Sentence s : b.flat) {
                if (sentId.equals(s.id)) {
                    target = t;
                    break;
                }
            }
            if (target != null) break;
        }
        if (target == null) {
            Toast.makeText(this, "这本书的音频不在列表里，无法跳回", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(this, ReaderActivity.class);
        i.putExtra(ReaderActivity.EXTRA_TRACK_ID, target.id);
        i.putExtra(ReaderActivity.EXTRA_SCROLL_SENT_ID, sentId);
        startActivity(i);
    }

    public class ReviewBridge {
        @android.webkit.JavascriptInterface
        public void jump(final String bookId, final String sentId) {
            runOnUiThread(() -> jumpToSentence(bookId, sentId));
        }

        @android.webkit.JavascriptInterface
        public void delChar(final String bookId, final String hanzi) {
            runOnUiThread(() -> {
                ReciteStatsV2.get(MainActivity.this).deleteBookChar(bookId, hanzi);
                loadReviewPage();
            });
        }

        /** v1.18.0 人工确认区：存疑·双报转正为确定错 */
        @android.webkit.JavascriptInterface
        public void confirmDual(final String recordKey) {
            runOnUiThread(() -> {
                ReciteStatsV2.get(MainActivity.this).confirmDual(recordKey);
                loadReviewPage();
            });
        }

        /** v1.18.0 人工确认区：勾销该（字,音）组合，此后不再报 */
        @android.webkit.JavascriptInterface
        public void dismissCombo(final String bookId, final String hanzi, final String heard) {
            runOnUiThread(() -> {
                ReciteStatsV2.get(MainActivity.this).dismissCombo(bookId, hanzi, heard);
                loadReviewPage();
            });
        }
    }

    // ---------- 刷新 ----------
    private void refreshAll() {
        // v1.4.2：顶部红色三数条已删除，这里不再刷新它（统计仍供卡片/日历/复制文本使用）
        buildTrackList();
        refreshPlayerBar();
        refreshSpeedChips();
        buildPlanBand();
        if (showingCalendar) {
            buildPlanPage();
            buildCalendar();
        }
    }

    private void refreshPlayerBar() {
        PlayerService svc = PlayerService.instance;
        // v1.20.7：循环显示只认真源（服务字段；服务不在时读同一份持久化），
        // 不再写死「开」——显示什么就是什么，标题的「正在循环播放」同理
        boolean loopOn = svc != null ? svc.loopEnabled
                : getSharedPreferences("reader_prefs", MODE_PRIVATE)
                        .getBoolean("loop", true);
        nowLoop.setText(loopOn ? "单曲循环 开" : "单曲循环 关");
        if (svc == null || svc.current == null) {
            nowTitle.setText("未在播放");
            nowPos.setText("0:00");
            nowDur.setText("0:00");
            btnBarToggle.setText("▶");
            if (!userSeeking) playerSeek.setProgress(0);
            return;
        }
        Track cur = svc.current;
        // v1.4.3：跟读开启时状态文字要能一眼看出模式与阶段（gap 中显示「请复诵」）
        if (svc.followEnabled && svc.followPhase == PlayerService.FOLLOW_GAP) {
            nowTitle.setText("请复诵 · " + cur.title);
        } else if (svc.followEnabled && svc.playing) {
            nowTitle.setText("正在跟读 · " + cur.title);
        } else if (svc.followEnabled && svc.followPhase == PlayerService.FOLLOW_PLAYING) {
            nowTitle.setText("跟读已暂停 · " + cur.title);
        } else {
            nowTitle.setText((svc.playing
                    ? (loopOn ? "正在循环播放 · " : "正在播放 · ")
                    : "已暂停 · ") + cur.title);
        }
        btnBarToggle.setText(svc.playing ? "❚❚" : "▶");
        // v1.17.0：总时长/进度按当前声音口径（AI 音轨时长与原声不同）
        long effDur = svc.effDurationMs();
        nowDur.setText(Store.fmtTimeAt(effDur, store.speed));
        long pos = svc.currentPosition();
        if (!userSeeking) {
            nowPos.setText(Store.fmtTimeAt(pos, store.speed));
            if (effDur > 0)
                playerSeek.setProgress((int) Math.min(1000, pos * 1000 / effDur));
        }
    }

    // ---------- 日历页 ----------
    private void buildCalendar() {
        expandedGroups.addAll(getSharedPreferences("ui_prefs", MODE_PRIVATE)
                .getStringSet("expandedGroups", new HashSet<>()));

        LocalDate today = LocalDate.now();
        YearMonth ym = YearMonth.from(today);
        calMonthTitle.setText(ym.getYear() + " 年 " + ym.getMonthValue() + " 月");
        // 本月实际总遍数（无目标数）
        calMonthTotal.setText("本月共听 " + store.totalCountBetween(Store.monthFirst(), today) + " 遍");

        calGrid.removeAllViews();
        int cellW = (getResources().getDisplayMetrics().widthPixels - dp(32) - dp(28)) / 7;
        for (String w : WEEKDAYS) {
            TextView tv = new TextView(this);
            tv.setText(w.substring(1));
            tv.setGravity(Gravity.CENTER);
            tv.setTextSize(11);
            tv.setTextColor(getColor(R.color.muted));
            setCellSize(tv, cellW, dp(24));
            calGrid.addView(tv);
        }
        int offset = ym.atDay(1).getDayOfWeek().getValue() - 1; // 周一起始
        for (int i = 0; i < offset; i++) {
            TextView tv = new TextView(this);
            setCellSize(tv, cellW, dp(44));
            calGrid.addView(tv);
        }
        for (int day = 1; day <= ym.lengthOfMonth(); day++) {
            LocalDate date = ym.atDay(day);
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            setCellSize(cell, cellW, dp(44));

            TextView dTv = new TextView(this);
            dTv.setText(String.valueOf(day));
            dTv.setGravity(Gravity.CENTER);
            dTv.setTextSize(14);
            boolean isToday = date.equals(today);
            boolean has = store.totalCountOn(date) > 0;
            if (isToday) {
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setColor(getColor(R.color.accent));
                dTv.setBackground(g);
                dTv.setTextColor(0xFFFFFFFF);
                dTv.setTypeface(null, Typeface.BOLD);
                dTv.setLayoutParams(new LinearLayout.LayoutParams(dp(34), dp(34)));
            } else if (date.isAfter(today)) {
                dTv.setTextColor(0xFFB9B1A2);
            } else {
                dTv.setTextColor(getColor(R.color.ink));
                if (has) dTv.setTypeface(null, Typeface.BOLD);
            }
            cell.addView(dTv);
            TextView dot = new TextView(this);
            dot.setText("•");
            dot.setGravity(Gravity.CENTER);
            dot.setTextSize(10);
            dot.setTextColor(getColor(R.color.gold));
            dot.setVisibility(has && !isToday ? View.VISIBLE : View.INVISIBLE);
            cell.addView(dot);
            calGrid.addView(cell);
        }

        buildDayList();
    }

    private void setCellSize(View v, int w, int h) {
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = w;
        lp.height = h;
        v.setLayoutParams(lp);
    }

    private void buildDayList() {
        dayList.removeAllViews();
        List<LocalDate> dates = new ArrayList<>();
        Iterator<String> it = store.records.keys();
        while (it.hasNext()) {
            try {
                dates.add(LocalDate.parse(it.next()));
            } catch (Exception ignored) {
            }
        }
        Collections.sort(dates, Collections.reverseOrder());
        expandedGroups.addAll(getSharedPreferences("ui_prefs", MODE_PRIVATE)
                .getStringSet("expandedGroups", new HashSet<>()));

        LocalDate today = LocalDate.now();
        boolean anyShown = false;
        for (LocalDate date : dates) {
            int total = store.totalCountOn(date);
            if (total == 0) continue;
            JSONObject dayObj = store.records.optJSONObject(Store.dateKey(date));
            if (dayObj == null) continue;
            anyShown = true;

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundResource(R.drawable.card_bg);
            card.setPadding(dp(14), dp(12), dp(14), dp(12));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.bottomMargin = dp(10);
            card.setLayoutParams(clp);

            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            TextView dTv = new TextView(this);
            String label = date.getMonthValue() + "月" + date.getDayOfMonth() + "日";
            String wk = WEEKDAYS[date.getDayOfWeek().getValue() - 1];
            dTv.setText(label + (date.equals(today) ? " · 今天" : " · " + wk));
            dTv.setTextSize(16);
            dTv.setTypeface(null, Typeface.BOLD);
            dTv.setTextColor(getColor(R.color.ink));
            dTv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            head.addView(dTv);
            TextView tTv = new TextView(this);
            tTv.setText("共 " + total + " 遍");
            tTv.setTextSize(16);
            tTv.setTypeface(null, Typeface.BOLD);
            tTv.setTextColor(getColor(R.color.accent));
            head.addView(tTv);
            card.addView(head);

            LinearLayout detail = new LinearLayout(this);
            detail.setOrientation(LinearLayout.VERTICAL);
            detail.setVisibility(date.equals(today) ? View.VISIBLE : View.GONE);
            Iterator<String> kit = dayObj.keys();
            while (kit.hasNext()) {
                JSONObject e = dayObj.optJSONObject(kit.next());
                if (e == null) continue;
                int cnt = e.optInt("count", 0);
                long dur = e.optLong("dur", 0);
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, dp(6), 0, 0);
                TextView name = new TextView(this);
                name.setText(e.optString("title", "未知音频"));
                name.setTextSize(14);
                name.setTextColor(getColor(R.color.ink));
                name.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                row.addView(name);
                TextView val = new TextView(this);
                String s = cnt + " 遍";
                if (dur > 0) {
                    long mins = Math.round(cnt * dur / 60000.0);
                    s += " · 约 " + mins + " 分钟";
                }
                val.setText(s);
                val.setTextSize(14);
                val.setTextColor(getColor(R.color.muted));
                row.addView(val);
                detail.addView(row);
            }
            card.addView(detail);
            card.setOnClickListener(v ->
                    detail.setVisibility(detail.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
            dayList.addView(card);
        }
        if (!anyShown) {
            TextView tv = new TextView(this);
            tv.setText("还没有播放记录");
            tv.setTextColor(getColor(R.color.muted));
            tv.setTextSize(14);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(0, dp(20), 0, 0);
            dayList.addView(tv);
        }
    }

    // ---------- Service 回调 ----------
    @Override
    public void onStateChanged() {
        runOnUiThread(() -> {
            buildTrackList();
            refreshPlayerBar();
        });
    }

    @Override
    public void onProgress(String trackId, long posMs, long durMs) {
        runOnUiThread(() -> {
            refreshPlayerBar();
            View card = cardViews.get(trackId);
            Track t = store.findTrack(trackId);
            if (card != null && t != null) updateCardProgress(card, t);
        });
    }

    @Override
    public void onCountChanged() {
        runOnUiThread(this::refreshAll);
    }

    /** v1.14.0：主页是 singleTop——从书库页带 EXTRA_OPEN_ADD 回跳时走这里 */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra(EXTRA_OPEN_ADD, false)) {
            intent.removeExtra(EXTRA_OPEN_ADD);
            showAddBookDialog();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        PlayerService.listener = this;
        refreshAll();
        refreshOverlayBtn(); // 设置页可能刚改过显示意愿，回来同步钮的样式
        // v1.10.0：从系统设置授权页回来——权限已给就立即把悬浮件拉起来，不等下次冷启动
        if (pendingOverlayGrant) {
            pendingOverlayGrant = false;
            if (OverlayController.canDraw(this)) ensureOverlayService();
        }
    }

    @Override
    protected void onPause() {
        if (PlayerService.listener == this) PlayerService.listener = null;
        super.onPause();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
