package com.ti.songdu;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;

/**
 * 悬浮播放控件（宿主＝PlayerService：服务已是 mediaPlayback 前台服务，
 * 悬浮件挂在它身上不再起第二个服务、不会出现两个通知）。
 *
 * 形态：贴在屏幕侧边的半圆形小按钮（半透明，圆心压在屏幕边缘外，只露半圆），
 * 上面是播放/暂停图标；默认停右侧。
 *
 * 手势（v1.10.2 改版，用户定的口径，四者互斥）：
 *  - 轻点（按下抬起、没拖没长按）：切换播放/暂停；没有装载曲目时改为打开主界面。
 *  - 长按（按住不动约 400ms）：打开当前曲目的阅读页；无装载曲目时打开主界面。
 *  - 快速上滑（按下后 ≤300ms 内上移 ≥48dp 且竖直位移占优）：同长按，打开阅读页；
 *    触发后本手势作废，不挪位、不再补发轻点。
 *  - 慢拖（按住移动）：挪位置；松手吸附到最近的左/右边缘，侧边与竖直位置
 *    写 overlay_prefs 持久化。慢拖与快滑靠时长区分：超过 300ms 的移动一律是拖。
 * v1.10.0 的「长按展开字幕卡」已整体移除。
 * v1.11.0 显示状态模型（overlay_prefs，设置页／主页播放器钮／本类同一份）：
 *  - showWanted：显示意愿——true 才挂窗，是「悬浮字幕」状态的唯一真源；
 *    历史版本的 closed 标记在首次读到新模型时一次性迁移（showWanted＝!closed）
 *    后删除、不再使用。
 *  - autoShow：打开软件时自动显示（默认 true）。冷启动由 MainActivity 读它决定
 *    是否把 showWanted 置真并拉起服务；本类不读。
 *  - alpha：半圆钮不透明度（默认 0.6＝旧写死值 0x99/0xFF，范围 0.25–1.0）；
 *    attach 时读取、轮询里持续同步，设置页拖动滑杆即时生效。
 *
 * 窗口：TYPE_APPLICATION_OVERLAY + FLAG_NOT_FOCUSABLE（不抢焦点）
 * + FLAG_NOT_TOUCH_MODAL（窗外触摸穿透到下层应用）。
 * 所有窗口操作 try/catch 隔离——悬浮件任何异常不得拖垮播放服务。
 * 轮询 500ms 只同步播放/暂停图标，不做高频重绘。
 */
public class OverlayController {

    public static final String PREFS = "overlay_prefs";
    private static final String KEY_CLOSED = "closed"; // 历史键：仅供一次性迁移，迁后删除
    public static final String KEY_SHOW_WANTED = "showWanted";
    public static final String KEY_AUTO_SHOW = "autoShow";
    public static final String KEY_ALPHA = "alpha";
    public static final float DEFAULT_ALPHA = 0.6f;  // ＝旧写死底色 0x99262626 的不透明度
    public static final float MIN_ALPHA = 0.25f;
    private static final String KEY_SIDE = "side";   // 0=左 1=右（默认右）
    private static final String KEY_Y = "y";         // 窗口顶边 y（px）；-1=未存

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

    // 当前显示状态（轮询比对，只在变化时动 UI）
    private boolean shownPlaying = false;
    private float shownAlpha = -1f;

    // 手势状态（同一时刻只跟踪一只手指，足够）
    private int gestureSeq = 0;
    private boolean gDragging = false;
    private boolean gLongFired = false;
    private boolean gSwipeFired = false;
    private float gDownRawX, gDownRawY;
    private int gStartX, gStartY;

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
            if (!attached) return;
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

    /** 半圆钮不透明度：默认 0.6（旧写死值），钳在 [0.25, 1.0] 保证可见 */
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

    /** 拉起悬浮件：显示意愿为关或无悬浮窗权限时不显示。
     *  服务 onCreate 与 ACTION_REFRESH_OVERLAY 都走这里，幂等。 */
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
            if (!attached) attachDocked();
            startPoll();
        } catch (Exception ignored) {
        }
    }

    /** 摘除窗口并停轮询（服务销毁、无权限时调用） */
    public void hide() {
        try {
            ui.removeCallbacks(poll);
            detachDocked();
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
        int r = dp(28);                    // 半径 28dp（整圆 56dp）
        int w = r + dp(4);                 // 窗宽略大于半径：圆心压出屏幕，只露半圆多一点
        int h = r * 2;
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

    // ---------- 手势：轻点 / 长按 / 快上滑 / 慢拖（靠「动不动＋时长」互斥） ----------

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
                                snapToEdge(v, lp);
                            } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                                onToggleTap();
                            }
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

    // ---------- 播放控制与跳转 ----------

    /** 轻点：有曲目走 PlayerService 既有 toggle；无曲目打开主界面 */
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

    /** 500ms 轮询体：同步播放/暂停图标与透明度（设置页拖滑杆改 prefs 后 ≤500ms 生效），
     *  状态没变不动 UI */
    private void refreshIcon() {
        PlayerService svc = PlayerService.instance;
        boolean playingNow = svc != null && svc.playing;
        if (playingNow != shownPlaying) {
            shownPlaying = playingNow;
            if (dockedView != null) dockedView.setPlaying(playingNow);
        }
        float a = alphaPref();
        if (Math.abs(a - shownAlpha) > 0.001f) {
            shownAlpha = a;
            if (dockedView != null) dockedView.setBgAlpha(a);
        }
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

        /** 底色不透明度（RGB 固定 0x26 灰，只改 alpha 通道）；图标保持近不透明、始终清楚 */
        void setBgAlpha(float a) {
            int ab = Math.round(Math.max(MIN_ALPHA, Math.min(1f, a)) * 255f);
            bgPaint.setColor((ab << 24) | 0x262626);
            invalidate();
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
