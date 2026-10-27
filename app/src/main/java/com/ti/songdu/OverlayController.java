package com.ti.songdu;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 悬浮播放控件（宿主＝PlayerService：服务已是 mediaPlayback 前台服务，
 * 悬浮件挂在它身上不再起第二个服务、不会出现两个通知）。
 *
 * 形态一（DOCK）：贴在屏幕侧边的半圆形小按钮（v1.15.0 起造型改贴边圆角片：
 * 朝屏内一侧圆角、贴边一侧平直，整片可见），上部是播放/暂停图标，其下方
 * 新增字幕钮（琥珀小圆片＋绘制的字幕图标：圆角长方形＋一排圆点，无文字），
 * 点字幕钮打开悬浮播放卡；默认停右侧。
 * DOCK 手势（v1.10.2 改版，用户定的口径，四者互斥）：
 *  - 轻点（按下抬起、没拖没长按）：切换播放/暂停；没有装载曲目时改为打开主界面。
 *  - 长按（按住不动约 400ms）：打开当前曲目的阅读页；无装载曲目时打开主界面。
 *  - 快速上滑（按下后 ≤300ms 内上移 ≥48dp 且竖直位移占优）：同长按，打开阅读页；
 *    触发后本手势作废，不挪位、不再补发轻点。
 *  - 慢拖（按住移动）：挪位置；松手吸附到最近的左/右边缘，侧边与竖直位置
 *    写 overlay_prefs 持久化。慢拖与快滑靠时长区分：超过 300ms 的移动一律是拖。
 * v1.10.0 的「长按展开字幕卡」已整体移除。
 *
 * 形态二（CARD，v1.13.0 新增）：悬浮播放卡。把半圆钮往屏幕中间慢拖、拖离
 * 边缘超过约屏宽 25% 时松手 → 变形为播放卡停在松手处；卡只显示当前句
 * （跟读进行中显示当前跟读段）＋逐字注音（拼音在字上方），带播放/暂停钮
 * （无曲目时显示「弦歌」占位、按钮置灰）。卡宽高只由用户设定决定——内容
 * 绝不改变卡尺寸：文本自动调字号（30sp 起逐级下调、注音按 0.4 比例跟随）
 * 塞进固定框，下限 9sp，到下限仍塞不下时末尾省略号截断；长句换行落点
 * 优先标点处。右下角琥珀色圆底把手拖动改宽高（宽 [160dp, 屏宽 94%]、
 * 高 [76dp, 屏高 40%]）；拖卡身移动。形态/位置/尺寸存 overlay_prefs 的
 * mode/cardW/cardH/cardX/cardY，下次拉起恢复。
 * v1.15.0 卡改版（按钮一律只用图标、无文字标注，均小而淡）：把手更小、
 * 平时约 28% 不透明（全卡最淡）、按住拖动时才变亮；左上角新增关闭钮 ✕，
 * 点它弹二选一提示（缩小到侧边／关掉，关掉走 showWanted=false 隐藏）；
 * 右上角新增缩小钮（减号图标），点它收回该侧半圆钮；点卡文字区（角钮、
 * 把手、左侧播放钮除外）打开阅读页跳到当前句并开始播放。拖拽吸附改严：
 * 松手不再按位置贴边判定，改速度判定——只有水平速度达到
 * FLING_DOCK_DP_PER_SEC（1000dp/s）量级把卡甩向一侧才收回半圆钮，
 * 慢拖到贴边松手停在原地。
 * 卡内容口径：跟读段经 PlayerService.followSegment() 只读接口取段文本；
 * 普通播放按 currentPosition 二分查有时间轴的当前句（句间停顿归前一句，
 * 与 ReaderActivity.findTimedIndex 一致）。拼音对齐镜像 reader.html 的
 * sentHTML：显示文本逐码点遍历、CJK 字按序取句的 pinyin 数组、标点不
 * 注音；仅当句内 CJK 字数与数组长度恰好相等才认为对齐可靠并注音，否则
 * 整句只显文字——宁可无拼音，不许错位。繁/简跟随 reader_prefs 的 trad。
 * 卡背景与半圆钮共用 overlay_prefs 的 alpha 键（v1.13.0 工厂默认由
 * 0.6 改为 0.55：卡的默认呈现更透；已存过 alpha 的用户不受影响）；文字
 * 与图标固定约 0.95 不透明保可读，不随 alpha 变。
 *
 * v1.11.0 显示状态模型（overlay_prefs，设置页／主页播放器钮／本类同一份）：
 *  - showWanted：显示意愿——true 才挂窗，是「悬浮字幕」状态的唯一真源；
 *    历史版本的 closed 标记在首次读到新模型时一次性迁移（showWanted＝!closed）
 *    后删除、不再使用。
 *  - autoShow：打开软件时自动显示（默认 true）。冷启动由 MainActivity 读它决定
 *    是否把 showWanted 置真并拉起服务；本类不读。
 *  - alpha：悬浮件背景不透明度（v1.13.0 起默认 0.55，范围 0.25–1.0）；
 *    attach 时读取、轮询里持续同步，设置页拖动滑杆即时生效。
 *
 * 窗口：TYPE_APPLICATION_OVERLAY + FLAG_NOT_FOCUSABLE（不抢焦点）
 * + FLAG_NOT_TOUCH_MODAL（窗外触摸穿透到下层应用）。
 * 所有窗口操作 try/catch 隔离——悬浮件任何异常不得拖垮播放服务。
 * 轮询 500ms 同步播放/暂停图标与卡内容（内容键不变不重排），不做高频重绘。
 */
public class OverlayController {

    public static final String PREFS = "overlay_prefs";
    private static final String KEY_CLOSED = "closed"; // 历史键：仅供一次性迁移，迁后删除
    public static final String KEY_SHOW_WANTED = "showWanted";
    public static final String KEY_AUTO_SHOW = "autoShow";
    public static final String KEY_ALPHA = "alpha";
    // v1.13.0：工厂默认 0.6 → 0.55（卡片默认呈现更透；半圆钮与卡共用此键，
    // 新装用户半圆钮默认也随之为 0.55，已存值不覆盖）
    public static final float DEFAULT_ALPHA = 0.55f;
    public static final float MIN_ALPHA = 0.25f;
    private static final String KEY_SIDE = "side";   // 0=左 1=右（默认右）
    private static final String KEY_Y = "y";         // 窗口顶边 y（px）；-1=未存
    // v1.13.0：播放卡形态持久化
    private static final String KEY_MODE = "mode";   // 0=DOCK 半圆钮 1=CARD 播放卡
    private static final String KEY_CARD_W = "cardW";
    private static final String KEY_CARD_H = "cardH";
    private static final String KEY_CARD_X = "cardX";
    private static final String KEY_CARD_Y = "cardY";
    private static final int MODE_DOCK = 0;
    private static final int MODE_CARD = 1;
    private static final int CARD_BG_RGB = 0x23252D; // 卡底色（mockup 同系深灰蓝）
    // v1.15.0：吸附改严的速度阈值（dp/s）。慢拖松手时手的速度通常只有几百 px/s
    // （约一两百 dp/s），用力甩动轻松超过 1500dp/s；1000dp/s 卡在两者之间，
    // 保证「轻拖慢拖绝不吸附、狠甩必吸附」。
    private static final float FLING_DOCK_DP_PER_SEC = 1000f;

    private static final long LONG_PRESS_MS = 400;
    private static final long SWIPE_MAX_MS = 300;
    private static final long POLL_MS = 500;

    private final Context ctx;
    private final WindowManager wm;
    private final SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final float density;
    private final int touchSlop;
    private final int swipeDistPx;

    private boolean attached = false;
    private HalfButtonView dockedView;
    private WindowManager.LayoutParams dockedLp;

    private boolean cardAttached = false;
    private CardView cardView;
    private WindowManager.LayoutParams cardLp;

    // 当前显示状态（轮询比对，只在变化时动 UI）
    private boolean shownPlaying = false;
    private boolean shownHasTrack = false;
    private float shownAlpha = -1f;

    // 手势状态（同一时刻只跟踪一只手指，足够）
    private int gestureSeq = 0;
    private boolean gDragging = false;
    private boolean gLongFired = false;
    private boolean gSwipeFired = false;
    private boolean gDownOnCc = false;  // v1.15.0：DOCK 按下点是否落在字幕钮上
    private float gDownRawX, gDownRawY;
    private int gStartX, gStartY;

    // 卡手势状态
    private int cZone;              // 0=卡身 1=播放钮 2=调节把手 3=关闭钮 4=缩小钮
    private boolean cMoved;
    private float cDownRawX, cDownRawY;
    private int cStartX, cStartY, cStartW, cStartH;
    private VelocityTracker cVel;   // v1.15.0：松手甩动速度判定

    // v1.15.0：关闭二选一提示窗（自绘 overlay 窗，不用 Dialog——Service 上下文
    // 无窗口令牌，自绘窗与卡/半圆钮同一套 WindowManager 路径最稳）
    private ClosePromptView promptView;
    private boolean promptAttached = false;
    private int promptSide = 1;         // 弹提示时卡所在侧（「缩小到侧边」用）
    private int promptCenterY = 0;      // 弹提示时卡的竖直中心

    // v1.13.0 卡内容缓存：句轴按曲目缓存，内容键不变不重排版
    private ContentRepo.Book cardBook;
    private String cardTrackId;
    /** v1.17.0 句轴缓存键（曲目 id＋已选声音）：切声后键变、句轴重建 */
    private String cardVoiceKey;
    private long[] cardStarts;      // 与 cardFlatIdx 平行：有时间轴句的缩放后 startMs
    private int[] cardFlatIdx;
    private String cardKey = "";
    private SharedPreferences readerPrefsCache;

    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (gDragging || gLongFired || gSwipeFired) return;
            gLongFired = true;
            openReaderPage();
        }
    };

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!attached && !cardAttached) return;
            try {
                refreshIcon();
            } catch (Exception ignored) {
            }
            ui.postDelayed(this, POLL_MS);
        }
    };

    public OverlayController(Context serviceContext) {
        ctx = serviceContext;
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        migrateLegacy(prefs);
        density = ctx.getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        swipeDistPx = dp(48);
    }

    // ---------- v1.11.0 显示状态模型（静态口径：设置页/主页与本类共用同一份 prefs） ----------

    /** 一次性迁移：旧 closed 标记 → 新 showWanted（迁完删旧键）；已迁移则什么都不做 */
    public static void migrateLegacy(SharedPreferences p) {
        if (!p.contains(KEY_SHOW_WANTED)) {
            boolean wanted = !p.getBoolean(KEY_CLOSED, false);
            p.edit().putBoolean(KEY_SHOW_WANTED, wanted).remove(KEY_CLOSED).apply();
        }
    }

    public static SharedPreferences prefsOf(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        migrateLegacy(p);
        return p;
    }

    /** 显示意愿（唯一真源）：默认 true（沿用旧行为——没关过就是要显示的） */
    public static boolean showWanted(Context c) {
        return prefsOf(c).getBoolean(KEY_SHOW_WANTED, true);
    }

    public static void setShowWanted(Context c, boolean wanted) {
        prefsOf(c).edit().putBoolean(KEY_SHOW_WANTED, wanted).apply();
    }

    /** 打开软件时自动显示（默认 true＝现行冷启动行为） */
    public static boolean autoShow(Context c) {
        return prefsOf(c).getBoolean(KEY_AUTO_SHOW, true);
    }

    /** 悬浮件背景不透明度：v1.13.0 起默认 0.55，钳在 [0.25, 1.0] 保证可见 */
    public static float alphaOf(Context c) {
        float a = prefsOf(c).getFloat(KEY_ALPHA, DEFAULT_ALPHA);
        return Math.max(MIN_ALPHA, Math.min(1f, a));
    }

    private float alphaPref() {
        float a = prefs.getFloat(KEY_ALPHA, DEFAULT_ALPHA);
        return Math.max(MIN_ALPHA, Math.min(1f, a));
    }

    private int dp(float v) {
        return (int) (v * density + 0.5f);
    }

    public static boolean canDraw(Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    // ---------- 显示 / 隐藏 ----------

    /** 拉起悬浮件：显示意愿为关或无悬浮窗权限时不显示。形态按持久化的
     *  mode 恢复（DOCK 半圆钮 / CARD 播放卡）。服务 onCreate 与
     *  ACTION_REFRESH_OVERLAY 都走这里，幂等。 */
    public void tryShow() {
        try {
            if (!prefs.getBoolean(KEY_SHOW_WANTED, true)) {
                hide();
                return;
            }
            if (!canDraw(ctx)) {
                hide();
                return;
            }
            if (!attached && !cardAttached) {
                if (prefs.getInt(KEY_MODE, MODE_DOCK) == MODE_CARD) attachCard();
                else attachDocked();
            }
            startPoll();
        } catch (Exception ignored) {
        }
    }

    /** 摘除窗口并停轮询（服务销毁、无权限时调用；检查页让位也走这里） */
    public void hide() {
        try {
            ui.removeCallbacks(poll);
            dismissPrompt();
            detachDocked();
            detachCard();
        } catch (Exception ignored) {
        }
    }

    private void startPoll() {
        ui.removeCallbacks(poll);
        ui.post(poll);
    }

    private Point screenSize() {
        Point p = new Point();
        try {
            wm.getDefaultDisplay().getRealSize(p);
        } catch (Exception ignored) {
        }
        if (p.x <= 0 || p.y <= 0) {
            p.x = ctx.getResources().getDisplayMetrics().widthPixels;
            p.y = ctx.getResources().getDisplayMetrics().heightPixels;
        }
        return p;
    }

    private int side() {
        return prefs.getInt(KEY_SIDE, 1);
    }

    private int savedY(int winH) {
        Point s = screenSize();
        int y = prefs.getInt(KEY_Y, -1);
        if (y < 0) y = (s.y - winH) / 2;
        return clampY(y, winH);
    }

    private int clampY(int y, int winH) {
        Point s = screenSize();
        int max = Math.max(0, s.y - winH);
        return Math.max(0, Math.min(y, max));
    }

    private WindowManager.LayoutParams makeLp(int w, int h, int x, int y) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = x;
        lp.y = y;
        return lp;
    }

    // ---------- 半圆钮 ----------

    private void attachDocked() {
        if (dockedView == null) {
            dockedView = new HalfButtonView(ctx);
            dockedView.setOnTouchListener(new DragTouch());
        }
        // v1.15.0：贴边圆角片整片可见——宽 36dp、高 92dp（上 play 图标、
        // 下字幕钮，两段纵排）；高度变了，savedY/clampY 按新高自动跟随
        int w = dp(36);
        int h = dp(92);
        Point s = screenSize();
        int x = side() == 1 ? s.x - w : 0;
        dockedLp = makeLp(w, h, x, savedY(h));
        dockedView.setSide(side() == 1);
        dockedView.setPlaying(shownPlaying);
        shownAlpha = alphaPref();
        dockedView.setBgAlpha(shownAlpha);
        try {
            wm.addView(dockedView, dockedLp);
            attached = true;
        } catch (Exception e) {
            attached = false;
        }
    }

    private void detachDocked() {
        if (dockedView != null && attached) {
            try {
                wm.removeView(dockedView);
            } catch (Exception ignored) {
            }
        }
        attached = false;
    }

    // ---------- 播放卡：尺寸 / 位置（只由用户设定，内容绝不改） ----------

    private int cardW() {
        int w = prefs.getInt(KEY_CARD_W, -1);
        if (w < 0) w = Math.max(dp(220), Math.round(screenSize().x * 0.60f));
        return clampCardW(w);
    }

    private int cardH() {
        int h = prefs.getInt(KEY_CARD_H, -1);
        if (h < 0) h = dp(104);
        return clampCardH(h);
    }

    private int clampCardW(int w) {
        Point s = screenSize();
        int min = dp(160);
        int max = Math.max(min, Math.round(s.x * 0.94f));
        return Math.max(min, Math.min(w, max));
    }

    private int clampCardH(int h) {
        Point s = screenSize();
        int min = dp(76);
        int max = Math.max(min, Math.round(s.y * 0.40f));
        return Math.max(min, Math.min(h, max));
    }

    private int cardX(int w) {
        Point s = screenSize();
        int x = prefs.getInt(KEY_CARD_X, -1);
        if (x < 0) x = (s.x - w) / 2;
        return Math.max(0, Math.min(x, s.x - w));
    }

    private int cardY(int h) {
        Point s = screenSize();
        int y = prefs.getInt(KEY_CARD_Y, -1);
        if (y < 0) y = Math.round(s.y * 0.30f);
        return Math.max(0, Math.min(y, s.y - h));
    }

    private void persistCard() {
        if (cardLp == null) return;
        prefs.edit()
                .putInt(KEY_MODE, MODE_CARD)
                .putInt(KEY_CARD_W, cardLp.width)
                .putInt(KEY_CARD_H, cardLp.height)
                .putInt(KEY_CARD_X, cardLp.x)
                .putInt(KEY_CARD_Y, cardLp.y)
                .apply();
    }

    private void attachCard() {
        if (cardView == null) {
            cardView = new CardView(ctx);
            cardView.setOnTouchListener(new CardTouch());
        }
        int w = cardW(), h = cardH();
        cardLp = makeLp(w, h, cardX(w), cardY(h));
        cardView.setPlaying(shownPlaying);
        cardView.setHasTrack(shownHasTrack);
        shownAlpha = alphaPref();
        cardView.setBgAlpha(shownAlpha);
        cardKey = ""; // 强制首刷内容
        try {
            wm.addView(cardView, cardLp);
            cardAttached = true;
        } catch (Exception e) {
            cardAttached = false;
        }
    }

    private void detachCard() {
        if (cardView != null && cardAttached) {
            try {
                wm.removeView(cardView);
            } catch (Exception ignored) {
            }
        }
        cardAttached = false;
    }

    // ---------- 形态转换 ----------

    /** DOCK 拖离边缘超过约屏宽 25% 松手 → 播放卡停在松手处（卡心对齐钮心） */
    private void convertToCard(int cx, int cy) {
        int w = cardW(), h = cardH();
        Point s = screenSize();
        int x = Math.max(0, Math.min(cx - w / 2, s.x - w));
        int y = Math.max(0, Math.min(cy - h / 2, s.y - h));
        SharedPreferences.Editor ed = prefs.edit()
                .putInt(KEY_MODE, MODE_CARD)
                .putInt(KEY_CARD_X, x)
                .putInt(KEY_CARD_Y, y)
                .putInt(KEY_Y, clampY(cy - dp(46), dp(92)));
        if (!prefs.contains(KEY_CARD_W)) ed.putInt(KEY_CARD_W, w);
        if (!prefs.contains(KEY_CARD_H)) ed.putInt(KEY_CARD_H, h);
        ed.apply();
        detachDocked();
        attachCard();
        startPoll();
    }

    /** CARD 收回为该侧半圆钮（缩小钮／狠甩吸附／关闭提示的「缩小到侧边」共用；
     *  高度跟随卡的竖直中心，DOCK 高 v1.15.0 起为 92dp） */
    private void collapseToDock(int newSide, int centerY) {
        int h = dp(92);
        prefs.edit()
                .putInt(KEY_MODE, MODE_DOCK)
                .putInt(KEY_SIDE, newSide)
                .putInt(KEY_Y, clampY(centerY - h / 2, h))
                .apply();
        detachCard();
        attachDocked();
        startPoll();
    }

    private float dpF(float v) {
        return v * density;
    }

    // ---------- DOCK 手势：轻点 / 长按 / 快上滑 / 慢拖（靠「动不动＋时长」互斥） ----------

    private class DragTouch implements View.OnTouchListener {
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            try {
                WindowManager.LayoutParams lp = dockedLp;
                if (lp == null) return false;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: {
                        gestureSeq++;
                        gDragging = false;
                        gLongFired = false;
                        gSwipeFired = false;
                        gDownOnCc = dockedView != null && dockedView.isCcZone(e.getX(), e.getY());
                        gDownRawX = e.getRawX();
                        gDownRawY = e.getRawY();
                        gStartX = lp.x;
                        gStartY = lp.y;
                        final int seq = gestureSeq;
                        ui.postDelayed(() -> {
                            if (seq == gestureSeq) longPressRunnable.run();
                        }, LONG_PRESS_MS);
                        return true;
                    }
                    case MotionEvent.ACTION_MOVE: {
                        // 长按已开阅读页、或快滑已触发：本手势作废，后续移动一律吞掉
                        if (gLongFired || gSwipeFired) return true;
                        float dx = e.getRawX() - gDownRawX;
                        float dy = e.getRawY() - gDownRawY;
                        long elapsed = e.getEventTime() - e.getDownTime();
                        // 快上滑：≤300ms 内上移 ≥48dp 且竖直占优——先于慢拖判定；
                        // 窗口若已被同手势的先行小位移拖动，先还原到按下处再打开
                        if (elapsed <= SWIPE_MAX_MS && dy <= -swipeDistPx
                                && Math.abs(dy) > 1.4f * Math.abs(dx)) {
                            gSwipeFired = true;
                            gestureSeq++;  // 取消待触发的长按
                            if (gDragging) {
                                gDragging = false;
                                lp.x = gStartX;
                                lp.y = gStartY;
                                wm.updateViewLayout(v, lp);
                            }
                            openReaderPage();
                            return true;
                        }
                        if (!gDragging && Math.hypot(dx, dy) > touchSlop) {
                            // 一动即慢拖：取消本次长按判定（seq 自增使待触发的长按失效）
                            gDragging = true;
                            gestureSeq++;
                        }
                        if (gDragging) {
                            Point s = screenSize();
                            int w = v.getWidth();
                            int h = v.getHeight();
                            lp.x = Math.max(-w / 3, Math.min(gStartX + (int) dx, s.x - w * 2 / 3));
                            lp.y = Math.max(0, Math.min(gStartY + (int) dy, s.y - h));
                            wm.updateViewLayout(v, lp);
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        // 失效尚未触发的长按投递（按 seq 比对，见 ACTION_DOWN 的 postDelayed）
                        gestureSeq++;
                        boolean wasDrag = gDragging;
                        boolean wasSpent = gLongFired || gSwipeFired;
                        gDragging = false;
                        gLongFired = false;
                        gSwipeFired = false;
                        if (!wasSpent) {
                            if (wasDrag) {
                                if (!finishDockDrag(v, lp)) snapToEdge(v, lp);
                            } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                                // v1.15.0：按下点在字幕钮上且没拖动 → 字幕钮轻点；
                                // 其余轻点照旧切换播放/暂停
                                if (gDownOnCc) onSubtitleButtonTap();
                                else onToggleTap();
                            }
                        }
                        gDownOnCc = false;
                        return true;
                    }
                    default:
                        return true;
                }
            } catch (Exception ignored) {
                return true;
            }
        }
    }

    /** 慢拖松手分流：拖离起始边缘超过约屏宽 25% → 变播放卡停在松手处
     *  （返回 true＝已转换）；否则返回 false，走原吸附逻辑 */
    private boolean finishDockDrag(View v, WindowManager.LayoutParams lp) {
        try {
            Point s = screenSize();
            int startSide = gStartX > 0 ? 1 : 0; // 按下时必为贴边位：x=0 左、x=屏宽-窗宽 右
            int inward = startSide == 1 ? (gStartX - lp.x) : (lp.x - gStartX);
            if (inward <= s.x / 4) return false;
            int w = v.getWidth();
            int h = v.getHeight();
            int cx = lp.x + w / 2; // v1.15.0：圆角片整片可见，中心即片心
            int cy = lp.y + h / 2;
            convertToCard(cx, cy);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 松手吸附：按窗口中心判左/右，贴边并持久化侧边与顶边 y */
    private void snapToEdge(View v, WindowManager.LayoutParams lp) {
        try {
            Point s = screenSize();
            int w = v.getWidth();
            int h = v.getHeight();
            int newSide = (lp.x + w / 2) >= s.x / 2 ? 1 : 0;
            lp.x = newSide == 1 ? s.x - w : 0;
            lp.y = clampY(lp.y, h);
            wm.updateViewLayout(v, lp);
            prefs.edit().putInt(KEY_SIDE, newSide).putInt(KEY_Y, lp.y).apply();
            if (dockedView != null) dockedView.setSide(newSide == 1);
        } catch (Exception ignored) {
        }
    }

    // ---------- CARD 手势：拖卡身移动 / 按播放钮 / 拖右下把手改尺寸 ----------
    // v1.15.0：分区加左上关闭钮（3）与右上缩小钮（4）；未拖动的单击按区分流
    // （播放钮切换播放、关闭钮弹提示、缩小钮收回、文字区跳阅读页续播）；
    // 拖动松手改速度判定：VelocityTracker 的水平速度达到阈值且方向朝一侧
    // → 收回该侧半圆钮；慢拖（含拖到贴边）松手只存位置、停在原地。

    private class CardTouch implements View.OnTouchListener {
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            try {
                WindowManager.LayoutParams lp = cardLp;
                if (lp == null) return false;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: {
                        cMoved = false;
                        cDownRawX = e.getRawX();
                        cDownRawY = e.getRawY();
                        cStartX = lp.x;
                        cStartY = lp.y;
                        cStartW = lp.width;
                        cStartH = lp.height;
                        if (cVel != null) cVel.recycle();
                        cVel = VelocityTracker.obtain();
                        cVel.addMovement(e);
                        float lx = e.getX(), ly = e.getY();
                        float bcx = dpF(34), bcy = v.getHeight() / 2f;
                        float chx = dpF(15.5f), chy = dpF(15.5f);
                        if (Math.hypot(lx - chx, ly - chy) <= dpF(17)) {
                            cZone = 3; // 左上关闭钮
                        } else if (Math.hypot(lx - (v.getWidth() - chx), ly - chy) <= dpF(17)) {
                            cZone = 4; // 右上缩小钮
                        } else if (lx > v.getWidth() - dpF(46) && ly > v.getHeight() - dpF(46)) {
                            cZone = 2; // 右下把手区
                            if (cardView != null) cardView.setHandleActive(true); // 按住即变亮
                        } else if (Math.hypot(lx - bcx, ly - bcy) <= dpF(28)) {
                            cZone = 1; // 播放/暂停钮
                        } else {
                            cZone = 0; // 卡身（文字区）
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_MOVE: {
                        if (cVel != null) cVel.addMovement(e);
                        float dx = e.getRawX() - cDownRawX;
                        float dy = e.getRawY() - cDownRawY;
                        if (!cMoved && Math.hypot(dx, dy) > touchSlop) cMoved = true;
                        if (!cMoved) return true;
                        Point s = screenSize();
                        if (cZone == 2) {
                            int nw = clampCardW(cStartW + (int) dx);
                            int nh = clampCardH(cStartH + (int) dy);
                            lp.width = nw;
                            lp.height = nh;
                            lp.x = Math.max(0, Math.min(lp.x, s.x - nw));
                            lp.y = Math.max(0, Math.min(lp.y, s.y - nh));
                        } else {
                            lp.x = Math.max(0, Math.min(cStartX + (int) dx, s.x - lp.width));
                            lp.y = Math.max(0, Math.min(cStartY + (int) dy, s.y - lp.height));
                        }
                        wm.updateViewLayout(v, lp);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        boolean up = e.getActionMasked() == MotionEvent.ACTION_UP;
                        if (cardView != null) cardView.setHandleActive(false); // 把手恢复暗淡
                        if (cZone == 2) {
                            persistCard(); // 改完大小松手即存
                        } else if (up && !cMoved) {
                            if (cZone == 1) onCardButtonTap();
                            else if (cZone == 3) onCardCloseTap();
                            else if (cZone == 4) collapseToDock(cardSide(), lp.y + lp.height / 2);
                            else onCardTextTap();
                        } else if (cMoved) {
                            // 吸附改严：只认松手速度，不认位置
                            float vx = 0f;
                            if (cVel != null) {
                                cVel.computeCurrentVelocity(1000); // px/s
                                vx = cVel.getXVelocity();
                            }
                            float threshold = dpF(FLING_DOCK_DP_PER_SEC);
                            if (vx <= -threshold) {
                                collapseToDock(0, lp.y + lp.height / 2);
                            } else if (vx >= threshold) {
                                collapseToDock(1, lp.y + lp.height / 2);
                            } else {
                                persistCard(); // 慢拖：停在原地
                            }
                        }
                        if (cVel != null) {
                            cVel.recycle();
                            cVel = null;
                        }
                        return true;
                    }
                    default:
                        return true;
                }
            } catch (Exception ignored) {
                return true;
            }
        }
    }

    /** 卡当前所在侧（按卡心判左右），缩小钮与关闭提示共用 */
    private int cardSide() {
        if (cardLp == null) return side();
        Point s = screenSize();
        return (cardLp.x + cardLp.width / 2) >= s.x / 2 ? 1 : 0;
    }

    // ---------- 播放控制与跳转 ----------

    /** DOCK 轻点：有曲目走 PlayerService 既有 toggle；无曲目打开主界面 */
    private void onToggleTap() {
        try {
            PlayerService svc = PlayerService.instance;
            if (svc != null && svc.current != null) {
                svc.toggle();
                return;
            }
        } catch (Exception ignored) {
        }
        openMain();
    }

    /** CARD 播放钮：有曲目才切换（无曲目时钮置灰、点了不做事） */
    private void onCardButtonTap() {
        try {
            PlayerService svc = PlayerService.instance;
            if (svc != null && svc.current != null) svc.toggle();
        } catch (Exception ignored) {
        }
    }

    /** v1.15.0 DOCK 字幕钮轻点：打开悬浮播放卡（位置/尺寸用已存的 cardX/Y/W/H，
     *  attachCard 内部有默认值兜底）。卡与半圆钮互斥显示，卡开着时本钮不可见；
     *  万一状态并存，点它收回半圆钮（切换语义兜底）。 */
    private void onSubtitleButtonTap() {
        try {
            if (cardAttached) {
                collapseToDock(cardSide(),
                        cardLp != null ? cardLp.y + cardLp.height / 2 : screenSize().y / 2);
                return;
            }
            prefs.edit().putInt(KEY_MODE, MODE_CARD).apply();
            detachDocked();
            attachCard();
            startPoll();
        } catch (Exception ignored) {
        }
    }

    /** v1.15.0 CARD 文字区单击：打开阅读页（其 150ms 轮询会按服务当前进度
     *  自动滚到当前句并高亮），并保证处于播放态——暂停时先 resume() 再跳。
     *  无曲目时 openReaderPage 自然回退打开主界面。 */
    private void onCardTextTap() {
        try {
            PlayerService svc = PlayerService.instance;
            if (svc != null && svc.current != null && !svc.playing) svc.resume();
        } catch (Exception ignored) {
        }
        openReaderPage();
    }

    /** v1.15.0 CARD 关闭钮：弹二选一提示 */
    private void onCardCloseTap() {
        showClosePrompt();
    }

    /** 挂上关闭提示窗：整屏模态窗（不带 FLAG_NOT_TOUCH_MODAL，窗外触摸也归它，
     *  点面板外即取消）。弹出时的卡侧/卡心先记下，选项动作按弹出时状态执行。 */
    private void showClosePrompt() {
        if (promptAttached || cardLp == null) return;
        try {
            promptSide = cardSide();
            promptCenterY = cardLp.y + cardLp.height / 2;
            if (promptView == null) promptView = new ClosePromptView(ctx);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            lp.x = 0;
            lp.y = 0;
            wm.addView(promptView, lp);
            promptAttached = true;
        } catch (Exception ignored) {
            promptAttached = false;
        }
    }

    private void dismissPrompt() {
        if (promptView != null && promptAttached) {
            try {
                wm.removeView(promptView);
            } catch (Exception ignored) {
            }
        }
        promptAttached = false;
    }

    /** 「关掉」：走既有隐藏路径——showWanted 置假后 hide()；下次打开 App 时
     *  MainActivity 冷启动按 autoShow 决定是否重新置真显示（原有口径不变） */
    private void closeOverlayCompletely() {
        dismissPrompt();
        setShowWanted(ctx, false);
        hide();
    }

    /** 长按 / 快上滑：打开当前曲目的阅读页（intent 口径同 MainActivity 点条目进
     *  阅读页：EXTRA_TRACK_ID 传曲目 id；服务上下文起 Activity 须加 NEW_TASK）；
     *  无装载曲目时打开主界面。 */
    private void openReaderPage() {
        try {
            PlayerService svc = PlayerService.instance;
            if (svc != null && svc.current != null) {
                Intent r = new Intent(ctx, ReaderActivity.class);
                r.putExtra(ReaderActivity.EXTRA_TRACK_ID, svc.current.id);
                r.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(r);
                return;
            }
        } catch (Exception ignored) {
        }
        openMain();
    }

    private void openMain() {
        try {
            Intent open = new Intent(ctx, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            ctx.startActivity(open);
        } catch (Exception ignored) {
        }
    }

    /** 500ms 轮询体：同步播放/暂停图标、置灰态与背景透明度（设置页拖滑杆改
     *  prefs 后 ≤500ms 生效），状态没变不动 UI；CARD 态再刷新卡内容 */
    private void refreshIcon() {
        PlayerService svc = PlayerService.instance;
        boolean playingNow = svc != null && svc.playing;
        if (playingNow != shownPlaying) {
            shownPlaying = playingNow;
            if (dockedView != null) dockedView.setPlaying(playingNow);
            if (cardView != null) cardView.setPlaying(playingNow);
        }
        boolean hasTrack = svc != null && svc.current != null;
        if (hasTrack != shownHasTrack) {
            shownHasTrack = hasTrack;
            if (cardView != null) cardView.setHasTrack(hasTrack);
        }
        float a = alphaPref();
        if (Math.abs(a - shownAlpha) > 0.001f) {
            shownAlpha = a;
            if (dockedView != null) dockedView.setBgAlpha(a);
            if (cardView != null) cardView.setBgAlpha(a);
        }
        if (cardAttached) refreshCardContent();
    }

    // ---------- 卡内容：当前句 / 当前跟读段 + 逐字注音 ----------

    private boolean readerTrad() {
        if (readerPrefsCache == null) {
            readerPrefsCache = ctx.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE);
        }
        return readerPrefsCache.getBoolean("trad", true);
    }

    /** 句轴按曲目缓存：内容包＋缩放后的有时间句 startMs（与 ReaderActivity 同口径）。
     *  v1.17.0：缓存键带上已选声音——切声后同一曲目要换成另一条时间轴；
     *  缩放统一走 ContentRepo.scaleFor（AI 书恒 1.0，不拿原声时长去除） */
    private void ensureCardBook(Track track) {
        String bid = ContentRepo.bookIdFor(track);
        String wantKey = String.valueOf(track.id) + "|"
                + (bid != null && VoicePref.appliesTo(track)
                        ? String.valueOf(VoicePref.getVoice(ctx, bid)) : "");
        if (wantKey.equals(cardVoiceKey)) return;
        cardVoiceKey = wantKey;
        cardTrackId = track.id;
        cardBook = ContentRepo.bookForTrack(ctx, track);
        cardStarts = null;
        cardFlatIdx = null;
        if (cardBook == null) return;
        double scale = ContentRepo.scaleFor(track, cardBook);
        ArrayList<Long> st = new ArrayList<>();
        ArrayList<Integer> fl = new ArrayList<>();
        for (int i = 0; i < cardBook.flat.size(); i++) {
            ContentRepo.Sentence s = cardBook.flat.get(i);
            if (s.startMs >= 0) {
                st.add(Math.round(s.startMs * scale));
                fl.add(i);
            }
        }
        if (st.isEmpty()) return;
        cardStarts = new long[st.size()];
        cardFlatIdx = new int[fl.size()];
        for (int i = 0; i < st.size(); i++) {
            cardStarts[i] = st.get(i);
            cardFlatIdx[i] = fl.get(i);
        }
    }

    /** 最后一个 startMs ≤ pos 的有时间句在 cardStarts 中的下标；都晚于 pos 返回 -1 */
    private int findTimedIndex(long pos) {
        if (cardStarts == null) return -1;
        int lo = 0, hi = cardStarts.length - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (cardStarts[mid] <= pos) { ans = mid; lo = mid + 1; }
            else hi = mid - 1;
        }
        return ans;
    }

    /** 内容键（曲目|段或句|繁简）变了才把新文本交给卡重排，轮询稳态零重绘 */
    private void refreshCardContent() {
        if (cardView == null) return;
        PlayerService svc = PlayerService.instance;
        Track track = svc != null ? svc.current : null;
        String key;
        List<CardUnit> units;
        if (track == null) {
            key = "#none";
            units = plainUnits("弦歌");
        } else {
            ensureCardBook(track);
            boolean trad = readerTrad();
            int[] seg = svc.followSegment();
            if (seg != null && cardBook != null && seg[0] >= 0
                    && seg[1] < cardBook.flat.size()) {
                key = String.valueOf(cardTrackId) + "|seg" + seg[0] + ":" + seg[1]
                        + ":" + seg[2] + ":" + seg[3] + "|" + (trad ? 1 : 0);
                units = new ArrayList<>();
                for (int f = seg[0]; f <= seg[1]; f++) {
                    ContentRepo.Sentence s = cardBook.flat.get(f);
                    int from = f == seg[0] ? cpCount(s.textT, seg[2]) : 0;
                    int to = f == seg[1] ? cpCount(s.textT, seg[3]) : -1;
                    units.addAll(sentenceUnits(s, trad, from, to));
                }
                if (units.isEmpty()) units = plainUnits(titleOr(track));
            } else if (cardStarts != null) {
                int ti = findTimedIndex(svc.currentPosition());
                int flat = ti >= 0 ? cardFlatIdx[ti] : cardFlatIdx[0];
                key = String.valueOf(cardTrackId) + "|s" + flat + "|" + (trad ? 1 : 0);
                units = sentenceUnits(cardBook.flat.get(flat), trad, 0, -1);
                if (units.isEmpty()) units = plainUnits(titleOr(track));
            } else {
                key = String.valueOf(cardTrackId) + "|title|" + (trad ? 1 : 0);
                units = plainUnits(titleOr(track));
            }
        }
        if (!key.equals(cardKey)) {
            cardKey = key;
            cardView.setContent(units);
        }
    }

    private static String titleOr(Track track) {
        return track.title != null && !track.title.isEmpty() ? track.title : "弦歌";
    }

    private static List<CardUnit> plainUnits(String text) {
        ArrayList<CardUnit> out = new ArrayList<>();
        if (text == null) return out;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            out.add(new CardUnit(new String(Character.toChars(cp)), null));
            i += Character.charCount(cp);
        }
        return out;
    }

    /** 与 reader.html isCJK 同口径 */
    private static boolean isCjk(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0x20000 && cp <= 0x2A6DF) || (cp >= 0x2A700 && cp <= 0x2B73F)
                || (cp >= 0x2B740 && cp <= 0x2B81F) || (cp >= 0x2B820 && cp <= 0x2CEAF)
                || (cp >= 0xF900 && cp <= 0xFAFF) || (cp >= 0x2F800 && cp <= 0x2FA1F);
    }

    /** textT 中 UTF-16 下标 u16 之前的码点数（段的字范围 → 显示文本码点范围） */
    private static int cpCount(String textT, int u16) {
        if (textT == null || u16 <= 0) return 0;
        return textT.codePointCount(0, Math.min(u16, textT.length()));
    }

    /**
     * 一句的显示单元列表，镜像 reader.html 的 sentHTML 对齐逻辑：显示文本
     * （trad 取 textT、否则 textS）逐码点遍历，CJK 字按出现顺序逐个取句的
     * pinyin 数组，标点不注音。可靠门：句内 CJK 字数必须与 pinyin 数组长
     * 度恰好相等才给注音；不等（数据缺失／错位风险）时整句只显文字。
     * fromCp/toCp 为显示文本的码点下标范围（toCp&lt;0＝到句末），供跟读段截取。
     */
    private List<CardUnit> sentenceUnits(ContentRepo.Sentence s, boolean trad,
                                         int fromCp, int toCp) {
        String disp = trad ? s.textT : s.textS;
        if (disp == null || disp.isEmpty()) disp = trad ? s.textS : s.textT;
        ArrayList<CardUnit> out = new ArrayList<>();
        if (disp == null || disp.isEmpty()) return out;
        int cjk = 0;
        for (int i = 0; i < disp.length(); ) {
            int cp = disp.codePointAt(i);
            if (isCjk(cp)) cjk++;
            i += Character.charCount(cp);
        }
        boolean reliable = s.pinyin != null && s.pinyin.size() == cjk;
        int pi = 0, idx = 0;
        for (int i = 0; i < disp.length(); ) {
            int cp = disp.codePointAt(i);
            String py = null;
            if (isCjk(cp)) {
                if (reliable) {
                    String p = s.pinyin.get(pi);
                    if (p != null && !p.isEmpty()) py = p;
                }
                pi++;
            }
            if (idx >= fromCp && (toCp < 0 || idx < toCp)) {
                out.add(new CardUnit(new String(Character.toChars(cp)), py));
            }
            idx++;
            i += Character.charCount(cp);
        }
        return out;
    }

    /** 卡上的一个显示单元：一个码点＋其注音（无可靠注音时 py 为 null） */
    private static final class CardUnit {
        final String ch;
        final String py;

        CardUnit(String ch, String py) {
            this.ch = ch;
            this.py = py;
        }
    }

    // ---------- 半圆钮视图：v1.15.0 起为贴边圆角片（整片可见） ----------
    // 布局（窗口 36dp×92dp）：上部 play/暂停图标（中心 y=24dp），下方字幕钮
    // （中心 y=66dp）：琥珀小圆片＋绘制的字幕图标（圆角长方形＋顶部短线＋
    // 下方一排三个圆点，仿各家播放器的字幕条意象），无文字、无 emoji。

    private class HalfButtonView extends View {
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ccFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ccRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ccInkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ccDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean rightSide = true;
        private boolean playing = false;

        HalfButtonView(Context c) {
            super(c);
            bgPaint.setColor(0x99262626);  // 半透明深灰（约 60% 不透明）
            fgPaint.setColor(0xF2FFFFFF);
            ccFillPaint.setStyle(Paint.Style.FILL);
            ccFillPaint.setColor(0x29F0B35C);   // 琥珀填充约 16%
            ccRingPaint.setStyle(Paint.Style.STROKE);
            ccRingPaint.setColor(0x73F0B35C);   // 琥珀描边约 45%
            ccInkPaint.setStyle(Paint.Style.STROKE);
            ccInkPaint.setColor(0xF2F0B35C);    // 图标线条：琥珀近不透明
            ccInkPaint.setStrokeCap(Paint.Cap.ROUND);
            ccDotPaint.setStyle(Paint.Style.FILL);
            ccDotPaint.setColor(0xF2F0B35C);
        }

        private float dpF(float v) {
            return v * getResources().getDisplayMetrics().density;
        }

        void setSide(boolean right) {
            if (rightSide != right) {
                rightSide = right;
                invalidate();
            }
        }

        void setPlaying(boolean p) {
            if (playing != p) {
                playing = p;
                invalidate();
            }
        }

        /** 底色不透明度（RGB 固定 0x26 灰，只改 alpha 通道）；图标保持近不透明、始终清楚 */
        void setBgAlpha(float a) {
            int ab = Math.round(Math.max(MIN_ALPHA, Math.min(1f, a)) * 255f);
            bgPaint.setColor((ab << 24) | 0x262626);
            invalidate();
        }

        /** 字幕钮命中判定（DOCK 手势在 DOWN 时问）：圆心（宽中心, 66dp）、半径 17dp */
        boolean isCcZone(float x, float y) {
            return Math.hypot(x - getWidth() / 2f, y - dpF(66)) <= dpF(17);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            // 背景：朝屏内一侧圆角、贴边一侧平直——圆角矩形往贴边方向多画
            // 一个半径的量，超出窗口的部分由窗口边界自然裁平（mockup 口径）
            float rr = Math.min(w, dpF(24));
            RectF bg = rightSide
                    ? new RectF(0, 0, w + rr, h)
                    : new RectF(-rr, 0, w, h);
            canvas.drawRoundRect(bg, rr, rr, bgPaint);
            // play/暂停图标：横向居中，中心 y=24dp
            float ix = w / 2f;
            float iy = dpF(24);
            float s = dpF(11);
            if (playing) {
                float bw = s * 0.34f;
                float gap = s * 0.30f;
                canvas.drawRect(ix - gap / 2 - bw, iy - s * 0.62f, ix - gap / 2, iy + s * 0.62f, fgPaint);
                canvas.drawRect(ix + gap / 2, iy - s * 0.62f, ix + gap / 2 + bw, iy + s * 0.62f, fgPaint);
            } else {
                Path tri = new Path();
                tri.moveTo(ix - s * 0.45f, iy - s * 0.68f);
                tri.lineTo(ix - s * 0.45f, iy + s * 0.68f);
                tri.lineTo(ix + s * 0.72f, iy);
                tri.close();
                canvas.drawPath(tri, fgPaint);
            }
            // 字幕钮：琥珀小圆片（中心 y=66dp，半径 12dp）
            float cx = w / 2f;
            float cy = dpF(66);
            float cr = dpF(12);
            ccRingPaint.setStrokeWidth(dpF(1));
            canvas.drawCircle(cx, cy, cr, ccFillPaint);
            canvas.drawCircle(cx, cy, cr - dpF(0.5f), ccRingPaint);
            // 字幕图标：圆角长方形（13×9.6dp）＋顶部短线（半透明）＋下方三个圆点
            float gw = dpF(13), gh = dpF(9.6f);
            RectF cap = new RectF(cx - gw / 2, cy - gh / 2, cx + gw / 2, cy + gh / 2);
            ccInkPaint.setStrokeWidth(dpF(1.1f));
            canvas.drawRoundRect(cap, dpF(2.2f), dpF(2.2f), ccInkPaint);
            ccInkPaint.setAlpha(128);
            canvas.drawLine(cx - dpF(4), cy - dpF(1.7f), cx + dpF(4), cy - dpF(1.7f), ccInkPaint);
            ccInkPaint.setAlpha(242);
            float dr = dpF(0.95f), dy = cy + dpF(2.2f);
            canvas.drawCircle(cx - dpF(3.6f), dy, dr, ccDotPaint);
            canvas.drawCircle(cx, dy, dr, ccDotPaint);
            canvas.drawCircle(cx + dpF(3.6f), dy, dr, ccDotPaint);
        }
    }

    // ---------- 播放卡视图：固定尺寸，文本自动字号塞框 ----------
    // 尺寸铁律：本 View 的宽高只来自窗口 LayoutParams（用户设定），
    // onDraw 只读 getWidth/getHeight 做排版，任何内容都不回写尺寸。

    private class CardView extends View {
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint btnPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint handleInk = new Paint(Paint.ANTI_ALIAS_FLAG);
        // v1.15.0：角钮（左上关闭 ✕ / 右上缩小 −）小圆片底与字形线
        private final Paint chipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint chipGlyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean handleActive = false;   // 把手被按住拖动中（变亮）

        private List<CardUnit> units = new ArrayList<>();
        private List<List<CardUnit>> lines = new ArrayList<>();
        private boolean layoutDirty = true;
        private boolean playing = false;
        private boolean hasTrack = false;
        private float fsMain, fsPy, lineH;

        CardView(Context c) {
            super(c);
            bgPaint.setStyle(Paint.Style.FILL);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(dpF(1));
            ringPaint.setColor(0x2EFFFFFF);
            btnPaint.setStyle(Paint.Style.FILL);
            glyphPaint.setStyle(Paint.Style.FILL);
            mainPaint.setColor(0xF2FFFFFF);   // 文字约 0.95 不透明（固定，不随背景 alpha）
            mainPaint.setTextAlign(Paint.Align.CENTER);
            pyPaint.setColor(0xD9FFFFFF);     // 注音略淡但仍清楚
            pyPaint.setTextAlign(Paint.Align.CENTER);
            handlePaint.setStyle(Paint.Style.FILL);
            handlePaint.setColor(0xFFE8A33D); // 琥珀色圆底（与 mockup 一致）；透明度在 onDraw 按 handleActive 切（平时约 28%）
            handleInk.setStyle(Paint.Style.STROKE);
            handleInk.setColor(0xFF2E2A26);
            handleInk.setStrokeCap(Paint.Cap.ROUND);
            chipPaint.setStyle(Paint.Style.FILL);
            chipPaint.setColor(0x12FFFFFF);   // 角钮底：白约 7%
            chipGlyphPaint.setStyle(Paint.Style.STROKE);
            chipGlyphPaint.setColor(0x6BFFFFFF); // 角钮字形：白约 42%
            chipGlyphPaint.setStrokeCap(Paint.Cap.ROUND);
        }

        /** v1.15.0：把手亮度切换——平时约 28% 不透明（全卡最淡），按住拖动时全亮 */
        void setHandleActive(boolean active) {
            if (handleActive != active) {
                handleActive = active;
                invalidate();
            }
        }

        private float dpF(float v) {
            return v * getResources().getDisplayMetrics().density;
        }

        /** 背景不透明度（与半圆钮共用 alpha 偏好）；只改底色 alpha，文字/图标不动 */
        void setBgAlpha(float a) {
            int ab = Math.round(Math.max(MIN_ALPHA, Math.min(1f, a)) * 255f);
            bgPaint.setColor((ab << 24) | CARD_BG_RGB);
            invalidate();
        }

        void setContent(List<CardUnit> u) {
            units = u;
            layoutDirty = true;
            invalidate();
        }

        void setPlaying(boolean p) {
            if (playing != p) {
                playing = p;
                invalidate();
            }
        }

        /** 无曲目时播放钮置灰（仅视觉；触摸分区逻辑在 CardTouch） */
        void setHasTrack(boolean has) {
            if (hasTrack != has) {
                hasTrack = has;
                invalidate();
            }
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            layoutDirty = true;
        }

        private float unitW(CardUnit u) {
            float w = mainPaint.measureText(u.ch);
            if (u.py != null) w = Math.max(w, pyPaint.measureText(u.py));
            return w + fsMain * 0.10f;
        }

        private float lineWidth(List<CardUnit> line) {
            float w = 0;
            for (CardUnit u : line) w += unitW(u);
            return w;
        }

        private boolean isClauseEnd(String ch) {
            return "，。；！？、：".contains(ch);
        }

        private boolean isCloserCh(String ch) {
            return "」』）》”’】〕〉".contains(ch);
        }

        /** 换行：先按标点切 clause（标点及其后收尾符归前一 clause），整
         *  clause 优先同行；单个 clause 比整行还宽时才逐字折行 */
        private List<List<CardUnit>> wrap(float areaW) {
            ArrayList<ArrayList<CardUnit>> clauses = new ArrayList<>();
            ArrayList<CardUnit> cur = new ArrayList<>();
            for (int i = 0; i < units.size(); i++) {
                cur.add(units.get(i));
                if (isClauseEnd(units.get(i).ch)) {
                    while (i + 1 < units.size() && isCloserCh(units.get(i + 1).ch)) {
                        i++;
                        cur.add(units.get(i));
                    }
                    clauses.add(cur);
                    cur = new ArrayList<>();
                }
            }
            if (!cur.isEmpty()) clauses.add(cur);
            ArrayList<List<CardUnit>> out = new ArrayList<>();
            ArrayList<CardUnit> line = new ArrayList<>();
            float lw = 0;
            for (ArrayList<CardUnit> cl : clauses) {
                float cw = lineWidth(cl);
                if (lw + cw <= areaW) {
                    line.addAll(cl);
                    lw += cw;
                    continue;
                }
                if (cw <= areaW) {
                    out.add(line);
                    line = new ArrayList<>(cl);
                    lw = cw;
                    continue;
                }
                for (CardUnit u : cl) {
                    float uw = unitW(u);
                    if (lw + uw > areaW && !line.isEmpty()) {
                        out.add(line);
                        line = new ArrayList<>();
                        lw = 0;
                    }
                    line.add(u);
                    lw += uw;
                }
            }
            if (!line.isEmpty()) out.add(line);
            return out;
        }

        /** 自动字号：30sp 起逐级下调（注音＝正文 0.4 倍）直到整段塞进固定
         *  文本区；到下限 9sp 仍塞不下 → 只保留可视行数，末行尾部逐字删到
         *  放得下省略号为止（不溢出、不改框） */
        private void computeLayout(int w, int h) {
            float areaW = Math.max(dpF(20), w - dpF(64) - dpF(14));
            float areaH = Math.max(dpF(10), h - dpF(16));
            float scaled = getResources().getDisplayMetrics().scaledDensity;
            lines = new ArrayList<>();
            for (int sp = 30; sp >= 9; sp--) {
                fsMain = sp * scaled;
                fsPy = fsMain * 0.40f;
                mainPaint.setTextSize(fsMain);
                pyPaint.setTextSize(fsPy);
                lineH = fsPy * 1.30f + fsMain * 1.40f;
                List<List<CardUnit>> ls = wrap(areaW);
                if (ls.size() * lineH <= areaH) {
                    lines = ls;
                    layoutDirty = false;
                    return;
                }
                if (sp == 9) {
                    int maxLines = Math.max(1, (int) (areaH / lineH));
                    int total = 0;
                    for (List<CardUnit> l : ls) total += l.size();
                    while (ls.size() > maxLines) ls.remove(ls.size() - 1);
                    int kept = 0;
                    for (List<CardUnit> l : ls) kept += l.size();
                    if (kept < total && !ls.isEmpty()) {
                        List<CardUnit> last = ls.get(ls.size() - 1);
                        CardUnit ell = new CardUnit("…", null);
                        while (!last.isEmpty() && lineWidth(last) + unitW(ell) > areaW) {
                            last.remove(last.size() - 1);
                        }
                        last.add(ell);
                    }
                    lines = ls;
                    layoutDirty = false;
                    return;
                }
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float r = dpF(14);
            RectF rf = new RectF(0.5f, 0.5f, w - 0.5f, h - 0.5f);
            canvas.drawRoundRect(rf, r, r, bgPaint);
            canvas.drawRoundRect(rf, r, r, ringPaint);
            // 左侧播放/暂停圆钮（竖直居中）
            float bcx = dpF(34), bcy = h / 2f, br = dpF(19);
            btnPaint.setColor(hasTrack ? 0x2EFFFFFF : 0x17FFFFFF);
            canvas.drawCircle(bcx, bcy, br, btnPaint);
            glyphPaint.setColor(hasTrack ? 0xF2FFFFFF : 0x59FFFFFF);
            if (playing) {
                float bw = dpF(4.2f), bh = dpF(15), gap = dpF(3.4f);
                canvas.drawRect(bcx - gap - bw, bcy - bh / 2, bcx - gap, bcy + bh / 2, glyphPaint);
                canvas.drawRect(bcx + gap, bcy - bh / 2, bcx + gap + bw, bcy + bh / 2, glyphPaint);
            } else {
                Path p = new Path();
                float tw = dpF(13), th = dpF(16);
                p.moveTo(bcx - tw / 2 + dpF(1.5f), bcy - th / 2);
                p.lineTo(bcx - tw / 2 + dpF(1.5f), bcy + th / 2);
                p.lineTo(bcx + tw / 2 + dpF(1.5f), bcy);
                p.close();
                canvas.drawPath(p, glyphPaint);
            }
            // 当前句文本（注音在字上方，整段在文本区内竖直居中）
            if (layoutDirty) computeLayout(w, h);
            if (!lines.isEmpty()) {
                float y = (h - lines.size() * lineH) / 2f;
                float x0 = dpF(64);
                for (List<CardUnit> line : lines) {
                    float lx = x0;
                    for (CardUnit u : line) {
                        float uw = unitW(u);
                        float cxu = lx + uw / 2f;
                        if (u.py != null) {
                            canvas.drawText(u.py, cxu, y + fsPy * 1.02f, pyPaint);
                        }
                        canvas.drawText(u.ch, cxu, y + fsPy * 1.30f + fsMain * 1.06f, mainPaint);
                        lx += uw;
                    }
                    y += lineH;
                }
            }
            // v1.15.0 角钮：左上关闭 ✕、右上缩小 −（绘制字形、无文字；小而淡）
            float chx = dpF(15.5f), chy = dpF(15.5f), chr = dpF(9.5f);
            canvas.drawCircle(chx, chy, chr, chipPaint);
            canvas.drawCircle(w - chx, chy, chr, chipPaint);
            chipGlyphPaint.setStrokeWidth(dpF(1.3f));
            float cg = dpF(3.4f);
            canvas.drawLine(chx - cg, chy - cg, chx + cg, chy + cg, chipGlyphPaint);
            canvas.drawLine(chx - cg, chy + cg, chx + cg, chy - cg, chipGlyphPaint);
            float mg = dpF(4f);
            canvas.drawLine(w - chx - mg, chy, w - chx + mg, chy, chipGlyphPaint);
            // 右下调节把手：琥珀圆底＋深色双向箭头（⤡ 意象，手工线条不依赖字体字形）
            // v1.15.0：半径 12.5→10dp 更小；透明度平时约 28%、按住拖动时 100%
            int handleAlpha = handleActive ? 255 : 71;
            handlePaint.setAlpha(handleAlpha);
            handleInk.setAlpha(handleAlpha);
            float hx = w - dpF(15), hy = h - dpF(15), hr = dpF(10);
            canvas.drawCircle(hx, hy, hr, handlePaint);
            handleInk.setStrokeWidth(dpF(1.7f));
            float a = dpF(4.6f), wing = dpF(4.4f);
            canvas.drawLine(hx - a, hy - a, hx + a, hy + a, handleInk);
            canvas.drawLine(hx + a - wing, hy + a, hx + a, hy + a, handleInk);
            canvas.drawLine(hx + a, hy + a - wing, hx + a, hy + a, handleInk);
            canvas.drawLine(hx - a + wing, hy - a, hx - a, hy - a, handleInk);
            canvas.drawLine(hx - a, hy - a + wing, hx - a, hy - a, handleInk);
        }
    }

    // ---------- v1.15.0 关闭二选一提示窗：整屏自绘（遮罩＋居中面板） ----------
    // 文案口径照 mockup 定稿：标题「关闭悬浮字幕？」＋一句区别说明＋两个按钮
    // 「缩小到侧边」（琥珀）／「关掉」（灰白）。点面板外取消。

    private class ClosePromptView extends View {
        private final Paint scrimPaint = new Paint();
        private final Paint panelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint panelRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint descPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shrinkFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shrinkRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shrinkTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint closeFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint closeRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint closeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF panelRect = new RectF();
        private final RectF shrinkRect = new RectF();
        private final RectF closeRect = new RectF();

        ClosePromptView(Context c) {
            super(c);
            scrimPaint.setColor(0x73000000);            // 遮罩黑约 45%
            panelPaint.setStyle(Paint.Style.FILL);
            panelPaint.setColor(0xF722232A);            // 面板底（约 97% 不透明）
            panelRingPaint.setStyle(Paint.Style.STROKE);
            panelRingPaint.setColor(0x1FFFFFFF);
            titlePaint.setColor(0xF2FFFFFF);
            titlePaint.setTextAlign(Paint.Align.CENTER);
            titlePaint.setFakeBoldText(true);
            descPaint.setColor(0xFFA5A2AD);
            descPaint.setTextAlign(Paint.Align.CENTER);
            shrinkFillPaint.setStyle(Paint.Style.FILL);
            shrinkFillPaint.setColor(0x29F0B35C);       // 琥珀填充约 16%
            shrinkRingPaint.setStyle(Paint.Style.STROKE);
            shrinkRingPaint.setColor(0x8CF0B35C);       // 琥珀描边约 55%
            shrinkTextPaint.setColor(0xFFF0B35C);
            shrinkTextPaint.setTextAlign(Paint.Align.CENTER);
            closeFillPaint.setStyle(Paint.Style.FILL);
            closeFillPaint.setColor(0x0FFFFFFF);        // 灰白填充约 6%
            closeRingPaint.setStyle(Paint.Style.STROKE);
            closeRingPaint.setColor(0x29FFFFFF);        // 灰白描边约 16%
            closeTextPaint.setColor(0xFFD8D5DE);
            closeTextPaint.setTextAlign(Paint.Align.CENTER);
        }

        private float dpF(float v) {
            return v * getResources().getDisplayMetrics().density;
        }

        private float spF(float v) {
            return v * getResources().getDisplayMetrics().scaledDensity;
        }

        /** 面板与两按钮的几何（onDraw 与触摸共用）：面板宽 min(300dp, 屏宽-52dp)、
         *  高 172dp 居中；按钮在面板底部内缩 16dp、高 38dp、间距 10dp */
        private void layoutRects(int w, int h) {
            float pw = Math.min(dpF(300), w - dpF(52));
            float ph = dpF(172);
            float left = (w - pw) / 2f;
            float top = (h - ph) / 2f;
            panelRect.set(left, top, left + pw, top + ph);
            float btnH = dpF(38);
            float btnY = top + ph - dpF(16) - btnH;
            float innerL = left + dpF(16);
            float innerR = left + pw - dpF(16);
            float gap = dpF(10);
            float bw = (innerR - innerL - gap) / 2f;
            shrinkRect.set(innerL, btnY, innerL + bw, btnY + btnH);
            closeRect.set(innerL + bw + gap, btnY, innerR, btnY + btnH);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            layoutRects(w, h);
            canvas.drawRect(0, 0, w, h, scrimPaint);
            float r = dpF(16);
            canvas.drawRoundRect(panelRect, r, r, panelPaint);
            panelRingPaint.setStrokeWidth(dpF(1));
            canvas.drawRoundRect(panelRect, r, r, panelRingPaint);
            float cx = panelRect.centerX();
            titlePaint.setTextSize(spF(15));
            canvas.drawText("关闭悬浮字幕？", cx,
                    panelRect.top + dpF(18) + spF(15), titlePaint);
            descPaint.setTextSize(spF(12));
            float dBase = panelRect.top + dpF(52);
            canvas.drawText("可以先缩到侧边（半圆钮还在，", cx, dBase, descPaint);
            canvas.drawText("随时再打开），也可以彻底关掉悬浮窗。", cx, dBase + dpF(20), descPaint);
            float br = dpF(10);
            canvas.drawRoundRect(shrinkRect, br, br, shrinkFillPaint);
            shrinkRingPaint.setStrokeWidth(dpF(1));
            canvas.drawRoundRect(shrinkRect, br, br, shrinkRingPaint);
            canvas.drawRoundRect(closeRect, br, br, closeFillPaint);
            closeRingPaint.setStrokeWidth(dpF(1));
            canvas.drawRoundRect(closeRect, br, br, closeRingPaint);
            shrinkTextPaint.setTextSize(spF(13));
            closeTextPaint.setTextSize(spF(13));
            float tBase = shrinkRect.centerY() + spF(13) * 0.36f;
            canvas.drawText("缩小到侧边", shrinkRect.centerX(), tBase, shrinkTextPaint);
            canvas.drawText("关掉", closeRect.centerX(), tBase, closeTextPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                float x = e.getX(), y = e.getY();
                try {
                    if (shrinkRect.contains(x, y)) {
                        int s = promptSide, cy = promptCenterY;
                        dismissPrompt();
                        collapseToDock(s, cy);
                    } else if (closeRect.contains(x, y)) {
                        closeOverlayCompletely();
                    } else {
                        dismissPrompt(); // 点面板外（含遮罩）＝取消，什么都不做
                    }
                } catch (Exception ignored) {
                }
            }
            return true; // 模态：所有触摸都吞掉，不漏给下层应用
        }
    }
}
