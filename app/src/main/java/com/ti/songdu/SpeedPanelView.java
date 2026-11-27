package com.ti.songdu;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * 倍速面板（v1.33.0）：主页播放条与阅读页播放条共用同一实现、同一口径——
 * 预设排（0.75/1.0/1.25/1.5/2.0/3.0）＋「自定义」钮＋就地展开的调速滑杆。
 * 状态表为用户 2026-11-26 冻结，逐条对应如下：
 *
 * 1) 滑杆 0.5–3.0×、步进 0.05、−/＋ 微调钮（每点 ±0.05）、整档微吸附
 *    （SpeedControl.snap）；拖动即时生效——拖动中走 live 通道直改播放参数、
 *    不落盘、不走改速内部重启（逐格重启会抖），松手定版才走 commit 通道
 *    （PlayerService.setSpeed，含内部重启的干净改速）；松手/微调/展开后
 *    3 秒无操作自动收起，拖动中不计时（从最后一次触碰起算）。
 * 2) 自定义钮三态：未设过→「自定义」；自定义生效中→数值本身（如 1.35×）
 *    ＋选中态；改用预设后→变回「自定义」，已设数值保留在 Store.customSpeed。
 * 3) 滑杆展开起点＝当前生效速度；「↺ 回到上次自定义值」小钮仅当已存
 *    自定义值且与当前速度不同时显示，点它跳回该值并即时定版。
 * 4) 拖值恰等于某预设档按自定义算：亮哪个钮只认来源标记
 *    Store.speedCustom、不认数值，绝不双亮。
 * 5) 切篇/切书/锁屏回来：宿主在 onPause/切页时调 collapse() 收起滑杆。
 * 6) 自定义值与来源标记随 settings.json 持久化（Store），关机重开还在。
 * 7) 不显示任何字/分换算数字（用户亲否）。
 */
public final class SpeedPanelView extends LinearLayout {

    /** 宿主落地口：commit＝定版提交；live＝拖动预览（不落盘） */
    public interface Applier {
        void commitSpeed(float v);

        void liveSpeed(float v);
    }

    private static final long AUTO_COLLAPSE_MS = 3000;
    private static final int ACCENT = 0xFFF0CDA0;

    private final Store store;
    private final Applier applier;
    private final TextView[] presetChips = new TextView[SpeedControl.PRESETS.length];
    private TextView customChip;
    private LinearLayout sliderRow;
    private SeekBar seek;
    private TextView valueLabel;
    private TextView restoreBtn;
    private boolean sliderOpen = false;
    private boolean dragging = false;
    private float dragValue;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable autoCollapse = this::collapse;

    public SpeedPanelView(Context ctx, Store store, Applier applier) {
        super(ctx);
        this.store = store;
        this.applier = applier;
        setOrientation(VERTICAL);
        buildChips(ctx);
        buildSlider(ctx);
        refresh();
    }

    // ---------- 构建 ----------

    private void buildChips(Context ctx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(HORIZONTAL);
        row.setLayoutParams(new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        for (int i = 0; i < SpeedControl.PRESETS.length; i++) {
            final float val = SpeedControl.PRESETS[i];
            TextView chip = makeChip(ctx, SpeedControl.PRESET_LABELS[i], i == 0);
            chip.setOnClickListener(v -> onPreset(val));
            presetChips[i] = chip;
            row.addView(chip);
        }
        customChip = makeChip(ctx, "自定义", false);
        customChip.setOnClickListener(v -> {
            if (sliderOpen) collapse();
            else expand();
        });
        row.addView(customChip);
        addView(row);
    }

    private TextView makeChip(Context ctx, String text, boolean first) {
        TextView chip = new TextView(ctx);
        chip.setText(text);
        chip.setTextSize(12);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(6), dp(6), dp(6), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        if (!first) lp.leftMargin = dp(6);
        chip.setLayoutParams(lp);
        return chip;
    }

    private void buildSlider(Context ctx) {
        sliderRow = new LinearLayout(ctx);
        sliderRow.setOrientation(HORIZONTAL);
        sliderRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(8);
        sliderRow.setLayoutParams(rowLp);
        sliderRow.setVisibility(GONE);

        TextView minus = stepButton(ctx, "−");
        minus.setOnClickListener(v -> stepBy(-SpeedControl.STEP));
        sliderRow.addView(minus);

        seek = new SeekBar(ctx);
        seek.setMax(SpeedControl.SLIDER_STEPS);
        LinearLayout.LayoutParams seekLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        seekLp.leftMargin = dp(4);
        seekLp.rightMargin = dp(4);
        seek.setLayoutParams(seekLp);
        seek.setProgressTintList(ColorStateList.valueOf(ACCENT));
        seek.setThumbTintList(ColorStateList.valueOf(ACCENT));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                dragValue = SpeedControl.fromProgress(progress);
                applier.liveSpeed(dragValue);
                valueLabel.setText(SpeedControl.format(dragValue));
                customChip.setText(SpeedControl.format(dragValue));
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                dragging = true;
                handler.removeCallbacks(autoCollapse);
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                dragging = false;
                commitCustom(dragValue);
                resetTimer();
            }
        });
        sliderRow.addView(seek);

        TextView plus = stepButton(ctx, "＋");
        plus.setOnClickListener(v -> stepBy(SpeedControl.STEP));
        sliderRow.addView(plus);

        valueLabel = new TextView(ctx);
        valueLabel.setTextSize(14);
        valueLabel.setTextColor(ACCENT);
        valueLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams valLp = new LinearLayout.LayoutParams(dp(52),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        valLp.leftMargin = dp(6);
        valueLabel.setLayoutParams(valLp);
        sliderRow.addView(valueLabel);
        addView(sliderRow);

        restoreBtn = new TextView(ctx);
        restoreBtn.setTextSize(12);
        restoreBtn.setTextColor(ACCENT);
        restoreBtn.setPadding(0, dp(6), 0, 0);
        restoreBtn.setVisibility(GONE);
        restoreBtn.setOnClickListener(v -> {
            float target = store.customSpeed;
            seek.setProgress(SpeedControl.toProgress(target));
            commitCustom(target);
            resetTimer();
        });
        addView(restoreBtn);
    }

    private TextView stepButton(Context ctx, String text) {
        TextView b = new TextView(ctx);
        b.setText(text);
        b.setTextSize(18);
        b.setTextColor(0xFFDDD6CB);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(10), dp(4), dp(10), dp(4));
        return b;
    }

    // ---------- 状态与动作 ----------

    /** 按 Store 现行值重渲全部钮面（宿主在外部改速后调用） */
    public void refresh() {
        for (int i = 0; i < presetChips.length; i++) {
            boolean sel = !store.speedCustom
                    && Math.abs(SpeedControl.PRESETS[i] - store.speed) < 0.01f;
            styleChip(presetChips[i], sel);
        }
        customChip.setText(store.speedCustom
                ? SpeedControl.format(store.speed) : "自定义");
        styleChip(customChip, store.speedCustom);
        if (sliderOpen && !dragging) {
            valueLabel.setText(SpeedControl.format(store.speed));
            refreshRestore();
        }
    }

    private void styleChip(TextView chip, boolean sel) {
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

    private void onPreset(float val) {
        // 来源标记先落位，commit 内部的 saveSettings 才会把标记一并存下
        store.speedCustom = false;
        applier.commitSpeed(val);
        collapse();
        refresh();
    }

    private void commitCustom(float v) {
        store.customSpeed = v;
        store.speedCustom = true;
        applier.commitSpeed(v);
        refresh();
    }

    private void stepBy(float delta) {
        float base = SpeedControl.fromProgress(seek.getProgress());
        float v = SpeedControl.fromSlider(base + delta);
        seek.setProgress(SpeedControl.toProgress(v));
        dragValue = v;
        commitCustom(v);
        resetTimer();
    }

    private void expand() {
        sliderOpen = true;
        sliderRow.setVisibility(VISIBLE);
        dragValue = store.speed;
        seek.setProgress(SpeedControl.toProgress(store.speed));
        valueLabel.setText(SpeedControl.format(store.speed));
        refreshRestore();
        resetTimer();
    }

    /** 收起滑杆（状态表 5：切篇/切书/锁屏由宿主调；内部自动收起亦走此处） */
    public void collapse() {
        sliderOpen = false;
        handler.removeCallbacks(autoCollapse);
        if (sliderRow != null) sliderRow.setVisibility(GONE);
        if (restoreBtn != null) restoreBtn.setVisibility(GONE);
        refresh();
    }

    public boolean isSliderOpen() {
        return sliderOpen;
    }

    private void refreshRestore() {
        boolean show = sliderOpen && store.customSpeed > 0
                && Math.abs(store.customSpeed - store.speed) > 0.01f;
        restoreBtn.setVisibility(show ? VISIBLE : GONE);
        if (show) {
            restoreBtn.setText("↺ 回到上次自定义值 " + SpeedControl.format(store.customSpeed));
        }
    }

    private void resetTimer() {
        handler.removeCallbacks(autoCollapse);
        if (sliderOpen && !dragging) {
            handler.postDelayed(autoCollapse, AUTO_COLLAPSE_MS);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(autoCollapse);
        super.onDetachedFromWindow();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
