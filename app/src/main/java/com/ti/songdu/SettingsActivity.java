package com.ti.songdu;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * v1.11.0 总设置页：集中管理每项功能的「默认状态」与「阅读页按钮显示与否」，
 * 以及悬浮字幕的显示意愿、启动行为、透明度与权限。
 * v1.12.0 增「卡片阅读数」组（单选时段）。
 * v1.14.1：增「播放」组（单曲循环开关，沿用 reader_prefs/loop，与阅读页开关
 * 同一份；默认开；关时读完自动接下一本）；「卡片阅读数」改多选（键
 * reader_prefs/cardStats 字符串集合，选项今日/昨日/前日/本周/上周/本月/
 * 上月/总计，默认今日、昨日、上周、本月、上月；旧单选值自动迁移为单项集合）。
 *
 * 口径（用户定）：
 *  - 与阅读页/主页共用同一份偏好：阅读默认与显隐写 reader_prefs、跟读分段写
 *    follow_prefs、悬浮写 overlay_prefs（键见 OverlayController），不另存一份；
 *    阅读页在 onCreate 读默认、onCreate/onResume 按显隐键设按钮 visibility。
 *  - 切换类控件不写「开/关」：分段钮当前项朱砂填充；状态钮靠样式表状态
 *    （朱砂实心＝显示，灰描边＝隐藏），文字只用「显示/隐藏」。
 * 入口：主页顶栏「帮助」之后的「设置」。
 */
public class SettingsActivity extends Activity {

    private SharedPreferences readerPrefs;
    private SharedPreferences followPrefs;
    private SharedPreferences overlayPrefs;

    private TextView permStatus;
    private Button permButton;
    private TextView autoNote;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        readerPrefs = getSharedPreferences("reader_prefs", MODE_PRIVATE);
        followPrefs = getSharedPreferences("follow_prefs", MODE_PRIVATE);
        overlayPrefs = OverlayController.prefsOf(this); // 含旧 closed 一次性迁移

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(getColor(R.color.paper));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(28));
        scroll.addView(root);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("设置");
        title.setTextSize(22);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(getColor(R.color.ink));
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("在这里定每项功能的默认状态，和阅读页上哪些按钮显示；改完即存，"
                + "下次打开阅读页就按这个来。悬浮字幕的开关也在这里，和主页播放器里的"
                + "「悬浮字幕」钮是同一个。");
        hint.setTextSize(13);
        hint.setTextColor(getColor(R.color.muted));
        hint.setPadding(0, dp(8), 0, dp(6));
        root.addView(hint);

        // ---------- 播放组（v1.14.1） ----------
        addGroupTitle(root, "播放");
        addSegRow(root, "单曲循环", new String[]{"开", "关"},
                loopSingleNow() ? 0 : 1,
                idx -> setLoopSingle(idx == 0));
        TextView loopHint = new TextView(this);
        loopHint.setText("开：一本书读完自动从头再读（一直以来的行为）。关：读完自动接列表里的下一本，最后一本读完停。");
        loopHint.setTextSize(12);
        loopHint.setTextColor(getColor(R.color.muted));
        loopHint.setPadding(dp(2), dp(2), 0, dp(4));
        root.addView(loopHint);

        addDivider(root);

        // ---------- 阅读组 ----------
        addGroupTitle(root, "阅读");

        addSegRow(root, "繁简", new String[]{"简体", "繁体"},
                readerPrefs.getBoolean("trad", true) ? 1 : 0,
                idx -> readerPrefs.edit().putBoolean("trad", idx == 1).apply());
        addShowRow(root, "繁简", "showTradBtn");

        addSegRow(root, "排版", new String[]{"横排", "竖排"},
                readerPrefs.getBoolean("vertical", false) ? 1 : 0,
                idx -> readerPrefs.edit().putBoolean("vertical", idx == 1).apply());
        addShowRow(root, "横竖排", "showVertBtn");

        addSegRow(root, "注音", new String[]{"有注音", "无注音"},
                readerPrefs.getBoolean("pinyin", true) ? 0 : 1,
                idx -> readerPrefs.edit().putBoolean("pinyin", idx == 0).apply());
        addShowRow(root, "注音", "showPinyinBtn");

        addSegRow(root, "字号", new String[]{"小", "中", "大", "特大"},
                clamp(readerPrefs.getInt("font", 1), 0, 3),
                idx -> readerPrefs.edit().putInt("font", idx).apply());
        addShowRow(root, "字号", "showFontBtn");

        final int[] chunkVals = {10, 15, 20, 25, 0};
        int curChunk = chunkCharsNow();
        int chunkIdx = 1;
        for (int i = 0; i < chunkVals.length; i++) {
            if (chunkVals[i] == curChunk) chunkIdx = i;
        }
        addSegRow(root, "跟读分段", new String[]{"10字", "15字", "20字", "25字", "整句"},
                chunkIdx, idx -> setChunkChars(chunkVals[idx]));
        addShowRow(root, "跟读", "showFollowBtn");

        addSegRow(root, "注释版本", new String[]{"张居正", "朱熹"},
                "jizhu".equals(readerPrefs.getString("notesVer", "zhijie")) ? 1 : 0,
                idx -> readerPrefs.edit()
                        .putString("notesVer", idx == 1 ? "jizhu" : "zhijie").apply());
        addShowRow(root, "注释", "showNotesBtn");

        addDivider(root);

        // ---------- 卡片阅读数组（v1.12.0 单选 → v1.14.1 多选） ----------
        addGroupTitle(root, "卡片阅读数");
        TextView statHint = new TextView(this);
        statHint.setText("主页每本书卡片上显示哪些时段的阅读数，就在这里勾（可多选）。");
        statHint.setTextSize(13);
        statHint.setTextColor(getColor(R.color.muted));
        statHint.setPadding(0, dp(2), 0, dp(2));
        root.addView(statHint);
        final String[] statKeys = MainActivity.CARD_STAT_KEYS;
        final String[] statLabels = {"今日", "昨日", "前日", "本周", "上周", "本月", "上月", "总计"};
        // 生效勾选与 MainActivity 同口径（旧单选 cardStat 自动算作单项集合）
        final java.util.Set<String> statSel =
                new java.util.HashSet<>(MainActivity.cardStatSelection(readerPrefs));
        // 八个选项分两行放（一行八个太挤，标签会挤变形）
        for (int r = 0; r < 2; r++) {
            LinearLayout statRow = rowLine();
            for (int c = 0; c < 4; c++) {
                final int idx = r * 4 + c;
                final TextView chip = new TextView(this);
                chip.setText(statLabels[idx]);
                chip.setTextSize(13);
                chip.setGravity(Gravity.CENTER);
                chip.setPadding(dp(4), dp(6), dp(4), dp(6));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                if (c > 0) lp.leftMargin = dp(6);
                chip.setLayoutParams(lp);
                styleMultiChip(chip, statSel.contains(statKeys[idx]));
                chip.setOnClickListener(v -> {
                    boolean on = !statSel.contains(statKeys[idx]);
                    if (on) statSel.add(statKeys[idx]);
                    else statSel.remove(statKeys[idx]);
                    styleMultiChip(chip, on);
                    readerPrefs.edit()
                            .putStringSet("cardStats", new java.util.HashSet<>(statSel))
                            .apply();
                });
                statRow.addView(chip);
            }
            root.addView(statRow);
        }

        addDivider(root);

        // ---------- 悬浮字幕组 ----------
        addGroupTitle(root, "悬浮字幕");

        // 显示意愿状态钮（与主页播放器钮同一份 showWanted）
        LinearLayout rowShow = rowLine();
        TextView labShow = rowLabel("悬浮字幕");
        rowShow.addView(labShow);
        final TextView pillShow = statePill(OverlayController.showWanted(this));
        pillShow.setOnClickListener(v -> {
            boolean next = !OverlayController.showWanted(this);
            OverlayController.setShowWanted(this, next);
            styleStatePill(pillShow, next);
            if (next) {
                if (OverlayController.canDraw(this)) {
                    ensureOverlayService();
                } else {
                    Toast.makeText(this, "还差一步：请先在下方授予悬浮窗权限",
                            Toast.LENGTH_LONG).show();
                }
            } else {
                hideOverlayService();
            }
        });
        rowShow.addView(pillShow);
        root.addView(rowShow);

        // 打开软件时自动显示
        addSegRow(root, "打开软件时", new String[]{"自动", "不自动"},
                overlayPrefs.getBoolean(OverlayController.KEY_AUTO_SHOW, true) ? 0 : 1,
                idx -> {
                    boolean auto = idx == 0;
                    overlayPrefs.edit()
                            .putBoolean(OverlayController.KEY_AUTO_SHOW, auto).apply();
                    if (autoNote != null) {
                        autoNote.setVisibility(auto ? View.GONE : View.VISIBLE);
                    }
                });
        autoNote = new TextView(this);
        autoNote.setText("想再打开时：到「设置」里打开，或用主页下方播放器里的「悬浮字幕」按钮。");
        autoNote.setTextSize(12);
        autoNote.setTextColor(getColor(R.color.muted));
        autoNote.setPadding(dp(2), dp(2), 0, dp(6));
        autoNote.setVisibility(overlayPrefs.getBoolean(
                OverlayController.KEY_AUTO_SHOW, true) ? View.GONE : View.VISIBLE);
        root.addView(autoNote);

        // 透明度滑杆（25%–100%，拖动写 prefs；悬浮件在跑时其轮询 ≤500ms 内同步）
        LinearLayout rowAlpha = rowLine();
        rowAlpha.addView(rowLabel("透明度"));
        final TextView alphaVal = new TextView(this);
        alphaVal.setTextSize(14);
        alphaVal.setTextColor(getColor(R.color.muted));
        SeekBar sb = new SeekBar(this);
        sb.setMax(75); // 25..100
        int curPct = Math.round(OverlayController.alphaOf(this) * 100);
        sb.setProgress(curPct - 25);
        alphaVal.setText(curPct + "%");
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int pct = progress + 25;
                alphaVal.setText(pct + "%");
                overlayPrefs.edit()
                        .putFloat(OverlayController.KEY_ALPHA, pct / 100f).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        sbLp.leftMargin = dp(8);
        sbLp.rightMargin = dp(8);
        sb.setLayoutParams(sbLp);
        rowAlpha.addView(sb);
        rowAlpha.addView(alphaVal);
        root.addView(rowAlpha);

        // 权限行
        LinearLayout rowPerm = rowLine();
        rowPerm.addView(rowLabel("悬浮窗权限"));
        permStatus = new TextView(this);
        permStatus.setTextSize(14);
        permStatus.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        rowPerm.addView(permStatus);
        permButton = new Button(this);
        permButton.setText("去授权");
        permButton.setTextSize(13);
        permButton.setBackgroundResource(R.drawable.pill_outline);
        permButton.setTextColor(getColor(R.color.ink));
        permButton.setPadding(dp(14), 0, dp(14), 0);
        permButton.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
        permButton.setOnClickListener(v -> openOverlayPermission());
        rowPerm.addView(permButton);
        root.addView(rowPerm);
        refreshPermRow();

        addDivider(root);

        TextView back = new TextView(this);
        back.setText("← 返回");
        back.setTextSize(15);
        back.setTextColor(getColor(R.color.accent));
        back.setPadding(0, dp(20), 0, 0);
        back.setOnClickListener(v -> finish());
        root.addView(back);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermRow(); // 可能刚从系统授权页回来
    }

    private void refreshPermRow() {
        if (permStatus == null) return;
        boolean ok = OverlayController.canDraw(this);
        permStatus.setText(ok ? "已授权" : "未授权");
        permStatus.setTextColor(getColor(ok ? R.color.muted : R.color.accent));
        permButton.setVisibility(ok ? View.GONE : View.VISIBLE);
    }

    /** 去授权：与 MainActivity 既有写法一致（带包名直达，失败回退总页） */
    private void openOverlayPermission() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            } catch (Exception ignored) {
            }
        }
    }

    /** 拉起服务显示悬浮件（与 MainActivity.ensureOverlayService 同口径） */
    private void ensureOverlayService() {
        Intent i = new Intent(this, PlayerService.class)
                .setAction(PlayerService.ACTION_REFRESH_OVERLAY);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
        else startService(i);
    }

    /** 摘窗：服务没在跑就没什么可摘（下次它起来时 tryShow 也会按 showWanted 不挂） */
    private void hideOverlayService() {
        if (PlayerService.instance == null) return;
        Intent i = new Intent(this, PlayerService.class)
                .setAction(PlayerService.ACTION_HIDE_OVERLAY);
        startService(i);
    }

    // ---------- 单曲循环（真源在 PlayerService，持久化沿用 reader_prefs/loop） ----------

    private boolean loopSingleNow() {
        PlayerService svc = PlayerService.instance;
        if (svc != null) return svc.loopEnabled;
        return readerPrefs.getBoolean("loop", true);
    }

    private void setLoopSingle(boolean on) {
        PlayerService svc = PlayerService.instance;
        if (svc != null) svc.setLoopEnabled(on); // 服务内部同步写 reader_prefs/loop
        else readerPrefs.edit().putBoolean("loop", on).apply();
    }

    /** 多选钮样式（与 styleChips 同一套视觉：朱砂填充＝已勾选，描边＝未勾选） */
    private void styleMultiChip(TextView chip, boolean sel) {
        float density = getResources().getDisplayMetrics().density;
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(999 * density);
        if (sel) {
            g.setColor(getColor(R.color.accent));
            chip.setTextColor(0xFFFFFFFF);
            chip.setTypeface(null, Typeface.BOLD);
        } else {
            g.setColor(0x00000000);
            g.setStroke(Math.max(1, (int) density), getColor(R.color.border));
            chip.setTextColor(getColor(R.color.ink));
            chip.setTypeface(null, Typeface.NORMAL);
        }
        chip.setBackground(g);
    }

    // ---------- 跟读分段（真源在 PlayerService/follow_prefs，与阅读页同口径） ----------

    private int chunkCharsNow() {
        PlayerService svc = PlayerService.instance;
        if (svc != null) return svc.followChunkChars;
        return followPrefs.getInt("chunkChars", 15);
    }

    private void setChunkChars(int v) {
        PlayerService svc = PlayerService.instance;
        if (svc != null) svc.setFollowChunkChars(v); // 服务内部同步写 follow_prefs
        else followPrefs.edit().putInt("chunkChars", v).apply();
    }

    // ---------- 通用小控件 ----------

    private interface OnPick {
        void pick(int idx);
    }

    private void addGroupTitle(LinearLayout root, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(17);
        t.setTypeface(null, Typeface.BOLD);
        t.setTextColor(getColor(R.color.ink));
        t.setPadding(0, dp(14), 0, dp(2));
        root.addView(t);
    }

    private LinearLayout rowLine() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));
        return row;
    }

    private TextView rowLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(15);
        t.setTextColor(getColor(R.color.ink));
        t.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    /** 一行「标签＋分段钮」：标签占剩余宽，右侧是一排小钮，当前项朱砂填充 */
    private void addSegRow(LinearLayout root, String label, String[] options,
                           int selected, OnPick onPick) {
        LinearLayout row = rowLine();
        TextView lab = rowLabel(label);
        lab.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(lab);
        final TextView[] chips = new TextView[options.length];
        for (int i = 0; i < options.length; i++) {
            final int idx = i;
            TextView chip = new TextView(this);
            chip.setText(options[i]);
            chip.setTextSize(13);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(12), dp(6), dp(12), dp(6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = dp(6);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                styleChips(chips, idx);
                onPick.pick(idx);
            });
            chips[i] = chip;
            row.addView(chip);
        }
        styleChips(chips, selected);
        root.addView(row);
    }

    private void styleChips(TextView[] chips, int selected) {
        float density = getResources().getDisplayMetrics().density;
        for (int i = 0; i < chips.length; i++) {
            boolean sel = i == selected;
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(999 * density);
            if (sel) {
                g.setColor(getColor(R.color.accent));
                chips[i].setTextColor(0xFFFFFFFF);
                chips[i].setTypeface(null, Typeface.BOLD);
            } else {
                g.setColor(0x00000000);
                g.setStroke(Math.max(1, (int) density), getColor(R.color.border));
                chips[i].setTextColor(getColor(R.color.ink));
                chips[i].setTypeface(null, Typeface.NORMAL);
            }
            chips[i].setBackground(g);
        }
    }

    /** 一行「『X』按钮＋显示/隐藏状态钮」，状态写 reader_prefs 的显隐键（默认显示） */
    private void addShowRow(LinearLayout root, String btnName, String key) {
        LinearLayout row = rowLine();
        TextView lab = new TextView(this);
        lab.setText("「" + btnName + "」按钮");
        lab.setTextSize(13);
        lab.setTextColor(getColor(R.color.muted));
        lab.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(lab);
        final TextView pill = statePill(readerPrefs.getBoolean(key, true));
        pill.setOnClickListener(v -> {
            boolean next = !readerPrefs.getBoolean(key, true);
            readerPrefs.edit().putBoolean(key, next).apply();
            styleStatePill(pill, next);
        });
        row.addView(pill);
        root.addView(row);
    }

    /** 状态钮：不写开/关——朱砂实心白字＝显示，灰描边灰字＝隐藏 */
    private TextView statePill(boolean on) {
        TextView t = new TextView(this);
        t.setTextSize(13);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(6), dp(14), dp(6));
        styleStatePill(t, on);
        return t;
    }

    private void styleStatePill(TextView t, boolean on) {
        float density = getResources().getDisplayMetrics().density;
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(999 * density);
        if (on) {
            g.setColor(getColor(R.color.accent));
            t.setTextColor(0xFFFFFFFF);
            t.setTypeface(null, Typeface.BOLD);
            t.setText("显示");
        } else {
            g.setColor(0x00000000);
            g.setStroke(Math.max(1, (int) density), getColor(R.color.border));
            t.setTextColor(getColor(R.color.muted));
            t.setTypeface(null, Typeface.NORMAL);
            t.setText("隐藏");
        }
        t.setBackground(g);
    }

    private void addDivider(LinearLayout root) {
        View div = new View(this);
        div.setBackgroundColor(getColor(R.color.border));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(10);
        div.setLayoutParams(lp);
        root.addView(div);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
