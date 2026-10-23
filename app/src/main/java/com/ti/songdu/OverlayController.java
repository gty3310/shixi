package com.ti.songdu;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * v1.10.0 悬浮播放控件（宿主＝PlayerService：服务已是 mediaPlayback 前台服务，
 * 悬浮件挂在它身上不再起第二个服务、不会出现两个通知）。
 *
 * 形态与手势（用户定的口径）：
 *  - 平时是贴在屏幕侧边的半圆形小按钮（半透明，圆心压在屏幕边缘外，只露半个圆），
 *    上面是播放/暂停图标；默认停右侧。
 *  - 轻点：直接切换播放/暂停；没有装载曲目时改为打开 App 主界面。
 *  - 长按（按住不动约 400ms）：展开成半透明卡片——当前正在读的句子（字幕式，
 *    随播放推进更换）＋播放/暂停钮＋右上角 ✕；再长按收回半圆。
 *  - 拖拽：按下后移动超过系统 touch slop 即判拖拽（长按判定随之取消），两态都可拖；
 *    松手吸附到最近的左/右边缘，侧边与竖直位置写 overlay_prefs 持久化。
 *  - ✕ 关闭：写 overlay_prefs/closed=true 并摘除窗口；MainActivity 下次全新启动时
 *    清该标记并重新拉起（见 MainActivity），悬浮件届时再出现。
 *
 * 窗口：TYPE_APPLICATION_OVERLAY + FLAG_NOT_FOCUSABLE（不抢焦点；卡片内按钮的点击
 * 在 NOT_FOCUSABLE 下照常分发）+ FLAG_NOT_TOUCH_MODAL（窗外触摸穿透到下层应用）。
 * 所有窗口操作 try/catch 隔离——悬浮件任何异常不得拖垮播放服务。
 *
 * 当前句来源（复用既有判定，不另造口径）：
 *  - 跟读开时用 PlayerService.followFlatIndex()（状态机维护的当前段代表句）；
 *  - 否则按位置查内容包拉平句表：句时间经 ContentRepo.scaleFor 缩放，二分找
 *    「最后一个 start<=pos 的句子、已过其句末归下一句」，与跟读状态机的
 *    followIndexForPosition 同一语义。简繁跟阅读页偏好 reader_prefs/trad（默认繁体）。
 * 刷新节律：500ms 轮询只读位置/状态，仅当句下标、曲目或播放态变化时才动 UI。
 */
public class OverlayController {

    public static final String PREFS = "overlay_prefs";
    private static final String KEY_CLOSED = "closed";
    private static final String KEY_SIDE = "side";   // 0=左 1=右（默认右）
    private static final String KEY_Y = "y";         // 窗口顶边 y（px）；-1=未存

    private static final int MODE_NONE = 0;
    private static final int MODE_DOCKED = 1;
    private static final int MODE_CARD = 2;

    private static final long LONG_PRESS_MS = 400;
    private static final long POLL_MS = 500;

    private final Context ctx;
    private final WindowManager wm;
    private final SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final float density;
    private final int touchSlop;

    private int mode = MODE_NONE;
    private HalfButtonView dockedView;
    private WindowManager.LayoutParams dockedLp;
    private FrameLayout cardView;
    private WindowManager.LayoutParams cardLp;
    private TextView cardTitle;
    private TextView cardSentence;
    private TextView cardPlayBtn;

    // 当前显示状态（轮询比对，只在变化时动 UI）
    private boolean shownPlaying = false;
    private String shownTrackId = null;
    private int shownSentenceIdx = Integer.MIN_VALUE;

    // 当前句解析缓存（按曲目 id＋简繁偏好构建，与 ContentRepo 的 Book 缓存同生命周期近似）
    private String cacheTrackId = null;
    private boolean cacheTrad = true;
    private String[] flatTexts;      // 全拉平句文本（跟读下标直查用），无内容包时 null
    private long[] audStarts;        // 可播句的缩放后 startMs（升序）
    private long[] audEnds;
    private int[] audFlatIdx;        // 可播句对应的拉平下标

    // 手势状态（同一时刻只跟踪一只手指，足够）
    private int gestureSeq = 0;
    private boolean gDragging = false;
    private boolean gLongFired = false;
    private float gDownRawX, gDownRawY;
    private int gStartX, gStartY;
    private View gView;
    private WindowManager.LayoutParams gLp;

    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (gDragging || gLongFired) return;
            gLongFired = true;
            try {
                if (mode == MODE_DOCKED) expand();
                else if (mode == MODE_CARD) collapse();
            } catch (Exception ignored) {
            }
        }
    };

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (mode == MODE_NONE) return;
            try {
                refreshContent();
            } catch (Exception ignored) {
            }
            ui.postDelayed(this, POLL_MS);
        }
    };

    public OverlayController(Context serviceContext) {
        ctx = serviceContext;
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        density = ctx.getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(ctx).getScaledTouchSlop();
    }

    private int dp(float v) {
        return (int) (v * density + 0.5f);
    }

    public static boolean canDraw(Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    // ---------- 显示 / 隐藏 ----------

    /** 拉起悬浮件：已被用户 ✕ 关掉（标记未清）或无悬浮窗权限时不显示。
     *  服务 onCreate 与 ACTION_REFRESH_OVERLAY 都走这里，幂等。 */
    public void tryShow() {
        try {
            if (prefs.getBoolean(KEY_CLOSED, false)) {
                hide();
                return;
            }
            if (!canDraw(ctx)) {
                hide();
                return;
            }
            if (mode == MODE_NONE) attachDocked();
            startPoll();
        } catch (Exception ignored) {
        }
    }

    /** 摘除全部窗口并停轮询（服务销毁、被关掉、无权限时调用） */
    public void hide() {
        try {
            ui.removeCallbacks(poll);
            detachDocked();
            detachCard();
            mode = MODE_NONE;
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
        detachCard();
        if (dockedView == null) {
            dockedView = new HalfButtonView(ctx);
            dockedView.setOnTouchListener(new DragTouch());
        }
        int r = dp(28);                    // 半径 28dp（整圆 56dp）
        int w = r + dp(4);                 // 窗宽略大于半径：圆心压出屏幕，只露半圆多一点
        int h = r * 2;
        Point s = screenSize();
        int x = side() == 1 ? s.x - w : 0;
        dockedLp = makeLp(w, h, x, savedY(h));
        dockedView.setSide(side() == 1);
        dockedView.setPlaying(shownPlaying);
        try {
            wm.addView(dockedView, dockedLp);
            mode = MODE_DOCKED;
        } catch (Exception e) {
            mode = MODE_NONE;
        }
    }

    private void detachDocked() {
        if (dockedView != null && mode == MODE_DOCKED) {
            try {
                wm.removeView(dockedView);
            } catch (Exception ignored) {
            }
        }
        if (mode == MODE_DOCKED) mode = MODE_NONE;
    }

    // ---------- 展开卡片 ----------

    private void buildCard() {
        FrameLayout root = new FrameLayout(ctx);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xB0262626);           // 半透明深灰（约 69% 不透明）
        bg.setCornerRadius(dp(16));
        root.setBackground(bg);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(10), dp(14), dp(12));
        root.addView(col, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        cardTitle = new TextView(ctx);
        cardTitle.setTextSize(12);
        cardTitle.setTextColor(0xFFBFB6A4);
        cardTitle.setSingleLine(true);
        cardTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(cardTitle);

        cardSentence = new TextView(ctx);
        cardSentence.setTextSize(17);
        cardSentence.setTextColor(0xFFFFFFFF);
        cardSentence.setLineSpacing(0, 1.18f);
        cardSentence.setMaxLines(8);
        cardSentence.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = dp(6);
        cardSentence.setLayoutParams(sLp);
        col.addView(cardSentence);

        cardPlayBtn = new TextView(ctx);
        cardPlayBtn.setText("▶");
        cardPlayBtn.setTextSize(19);
        cardPlayBtn.setTextColor(0xFFFFFFFF);
        cardPlayBtn.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0x00000000);
        circle.setStroke(dp(1), 0xFFD8CFBB);
        cardPlayBtn.setBackground(circle);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(dp(46), dp(46));
        pLp.topMargin = dp(10);
        cardPlayBtn.setLayoutParams(pLp);
        cardPlayBtn.setOnClickListener(v -> onToggleTap());
        col.addView(cardPlayBtn);

        TextView close = new TextView(ctx);
        close.setText("✕");
        close.setTextSize(14);
        close.setTextColor(0xFFD8CFBB);
        close.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams cLp = new FrameLayout.LayoutParams(dp(34), dp(34));
        cLp.gravity = Gravity.TOP | Gravity.RIGHT;
        close.setLayoutParams(cLp);
        close.setOnClickListener(v -> closeByUser());
        root.addView(close);

        root.setOnTouchListener(new DragTouch());
        cardView = root;
    }

    private void attachCard() {
        detachDocked();
        if (cardView == null) buildCard();
        Point s = screenSize();
        int w = Math.min(dp(300), (int) (s.x * 0.82f));
        int x = side() == 1 ? s.x - w : 0;
        cardLp = makeLp(w, WindowManager.LayoutParams.WRAP_CONTENT, x, savedY(dp(180)));
        try {
            wm.addView(cardView, cardLp);
            mode = MODE_CARD;
            // 高度出来后把顶边钳回屏内（WRAP_CONTENT 添加时还不知道真实高度）
            cardView.post(() -> {
                try {
                    if (mode == MODE_CARD && cardLp != null) {
                        int cy = clampY(cardLp.y, cardView.getHeight());
                        if (cy != cardLp.y) {
                            cardLp.y = cy;
                            wm.updateViewLayout(cardView, cardLp);
                        }
                    }
                } catch (Exception ignored) {
                }
            });
            forceRefreshContent();
        } catch (Exception e) {
            mode = MODE_NONE;
        }
    }

    private void detachCard() {
        if (cardView != null && mode == MODE_CARD) {
            try {
                wm.removeView(cardView);
            } catch (Exception ignored) {
            }
        }
        if (mode == MODE_CARD) mode = MODE_NONE;
    }

    private void expand() {
        if (mode != MODE_DOCKED) return;
        attachCard();
    }

    private void collapse() {
        if (mode != MODE_CARD) return;
        attachDocked();
        startPoll();
    }

    /** ✕：写关闭标记并摘窗；MainActivity 下次全新启动清标记后再拉起 */
    private void closeByUser() {
        try {
            prefs.edit().putBoolean(KEY_CLOSED, true).apply();
        } catch (Exception ignored) {
        }
        hide();
    }

    // ---------- 手势：轻点 / 长按 / 拖拽（三者靠「动不动＋时长」互斥） ----------

    private class DragTouch implements View.OnTouchListener {
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            try {
                WindowManager.LayoutParams lp =
                        (v == cardView) ? cardLp : dockedLp;
                if (lp == null) return false;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: {
                        gestureSeq++;
                        gDragging = false;
                        gLongFired = false;
                        gView = v;
                        gLp = lp;
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
                        if (v != gView) return true;
                        float dx = e.getRawX() - gDownRawX;
                        float dy = e.getRawY() - gDownRawY;
                        if (!gDragging && Math.hypot(dx, dy) > touchSlop) {
                            // 一动即拖拽：取消本次长按判定（seq 自增使待触发的长按失效）
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
                        boolean wasLong = gLongFired;
                        gDragging = false;
                        gLongFired = false;
                        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                            if (wasDrag) {
                                snapToEdge(v, lp);
                            } else if (!wasLong) {
                                // 轻点只对半圆钮有定义；卡片背景轻点无动作
                                // （卡上播放键、✕ 是子视图，自行消费点击，不经这里）
                                if (mode == MODE_DOCKED && v == dockedView) onToggleTap();
                            }
                        } else if (wasDrag) {
                            snapToEdge(v, lp);
                        }
                        gView = null;
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
            if (v == dockedView && dockedView != null) dockedView.setSide(newSide == 1);
        } catch (Exception ignored) {
        }
    }

    // ---------- 播放控制与内容刷新 ----------

    /** 轻点 / 卡上播放键：有曲目走 PlayerService 既有 toggle；无曲目打开主界面 */
    private void onToggleTap() {
        try {
            PlayerService svc = PlayerService.instance;
            if (svc != null && svc.current != null) {
                svc.toggle();
                return;
            }
        } catch (Exception ignored) {
        }
        try {
            Intent open = new Intent(ctx, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            ctx.startActivity(open);
        } catch (Exception ignored) {
        }
    }

    private void forceRefreshContent() {
        shownTrackId = null;
        shownSentenceIdx = Integer.MIN_VALUE;
        refreshContent();
    }

    /** 500ms 轮询体：只在播放态/曲目/句下标变化时动 UI（不做高频重绘） */
    private void refreshContent() {
        PlayerService svc = PlayerService.instance;
        boolean playingNow = svc != null && svc.playing;
        if (playingNow != shownPlaying) {
            shownPlaying = playingNow;
            if (dockedView != null) dockedView.setPlaying(playingNow);
            if (cardPlayBtn != null) cardPlayBtn.setText(playingNow ? "❚❚" : "▶");
        }
        if (mode != MODE_CARD) return;

        Track cur = svc != null ? svc.current : null;
        String tid = cur != null ? cur.id : null;
        if (tid == null ? shownTrackId != null : !tid.equals(shownTrackId)) {
            shownTrackId = tid;
            shownSentenceIdx = Integer.MIN_VALUE;
            if (cardTitle != null) cardTitle.setText(cur != null ? cur.title : "");
            rebuildSentenceCache(svc, cur);
        } else {
            // 简繁偏好在同曲目内被改动时也重建一次（低频比对，开销可忽略）
            boolean trad = ctx.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)
                    .getBoolean("trad", true);
            if (flatTexts != null && trad != cacheTrad) rebuildSentenceCache(svc, cur);
        }
        if (cur == null) {
            if (shownSentenceIdx != -2) {
                shownSentenceIdx = -2;
                if (cardSentence != null) cardSentence.setText("时习");
            }
            return;
        }
        int idx = currentSentenceFlatIdx(svc, cur);
        if (idx != shownSentenceIdx) {
            shownSentenceIdx = idx;
            if (cardSentence != null) {
                String text = null;
                if (flatTexts != null && idx >= 0 && idx < flatTexts.length) text = flatTexts[idx];
                cardSentence.setText(text != null && text.length() > 0 ? text : cur.title);
            }
        }
    }

    private void rebuildSentenceCache(PlayerService svc, Track cur) {
        flatTexts = null;
        audStarts = null;
        audEnds = null;
        audFlatIdx = null;
        cacheTrackId = null;
        if (svc == null || cur == null) return;
        try {
            cacheTrad = ctx.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)
                    .getBoolean("trad", true);
            ContentRepo.Book b = ContentRepo.bookForTrack(ctx, cur);
            if (b == null || b.flat.isEmpty()) return;
            double scale = ContentRepo.scaleFor(cur, b);
            flatTexts = new String[b.flat.size()];
            int aud = 0;
            for (int i = 0; i < b.flat.size(); i++) {
                ContentRepo.Sentence s = b.flat.get(i);
                flatTexts[i] = cacheTrad ? s.textT : s.textS;
                if (!s.noAudio && s.startMs >= 0) aud++;
            }
            audStarts = new long[aud];
            audEnds = new long[aud];
            audFlatIdx = new int[aud];
            int k = 0;
            for (int i = 0; i < b.flat.size(); i++) {
                ContentRepo.Sentence s = b.flat.get(i);
                if (s.noAudio || s.startMs < 0) continue;
                audStarts[k] = Math.round(s.startMs * scale);
                audEnds[k] = Math.round(s.endMs * scale);
                audFlatIdx[k] = i;
                k++;
            }
            cacheTrackId = cur.id;
        } catch (Exception ignored) {
            flatTexts = null;
            audStarts = null;
        }
    }

    /** 当前句的拉平下标：跟读开走状态机口径；否则位置二分（与 followIndexForPosition 同语义） */
    private int currentSentenceFlatIdx(PlayerService svc, Track cur) {
        if (svc.followEnabled) {
            int fi = svc.followFlatIndex();
            if (fi >= 0) return fi;
        }
        if (audStarts == null || audStarts.length == 0) return -1;
        long pos = svc.currentPosition();
        int lo = 0, hi = audStarts.length - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) / 2;
            if (audStarts[mid] <= pos) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (ans < 0) return audFlatIdx[0];
        if (pos >= audEnds[ans]) {
            return ans + 1 < audStarts.length ? audFlatIdx[ans + 1] : audFlatIdx[ans];
        }
        return audFlatIdx[ans];
    }

    // ---------- 半圆钮视图：整圆圆心压在窗外边缘，只露半圆 ----------

    private class HalfButtonView extends View {
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean rightSide = true;
        private boolean playing = false;

        HalfButtonView(Context c) {
            super(c);
            bgPaint.setColor(0x99262626);  // 半透明深灰（约 60% 不透明）
            fgPaint.setColor(0xF2FFFFFF);
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

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float r = h / 2f;
            // 右侧停靠：圆心在窗右缘（＝屏幕右缘），露左半圆；左侧停靠镜像
            float cx = rightSide ? w : 0f;
            canvas.drawCircle(cx, r, r, bgPaint);
            // 图标中心取半圆的形心附近（距圆心约 0.45r 向屏内）
            float ix = rightSide ? w - r * 0.55f : r * 0.55f;
            float iy = r;
            float s = r * 0.40f;
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
        }
    }
}
