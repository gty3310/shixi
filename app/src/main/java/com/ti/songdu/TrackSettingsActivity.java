package com.ti.songdu;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.time.LocalDate;

/**
 * 单个音频的设置页（卡片右上角齿轮打开）：
 *  1) 改显示名称——卡片标题、通知标题、复制进度文本都用它；记录按音频 id 关联，不受改名影响
 *  2) 只读展示储存位置（content 曲目：URI 原文＋来源文件名；预置曲目：内部存储路径＋「内置音频」标注）
 *  3) 遍数修正：今日遍数（直接改当天记录）、此前已读遍数 baseTotal（偏移，计入总计）
 *  4) 重置遍数（二次确认）：清掉该音频全部按天记录＋偏移归零
 *  5) 从列表移除（二次确认）：与首页删除同一口径，历史记录留在日历统计里
 */
public class TrackSettingsActivity extends Activity {

    public static final String EXTRA_TRACK_ID = "track_id";

    private Store store;
    private Track track;
    private EditText etName, etToday, etBase;
    private TextView tvTotalHint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = Store.get(this);
        String id = getIntent().getStringExtra(EXTRA_TRACK_ID);
        track = store.findTrack(id);
        if (track == null) {
            finish();
            return;
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(getColor(R.color.paper));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(28));
        scroll.addView(root);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("音频设置");
        title.setTextSize(22);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(getColor(R.color.ink));
        root.addView(title);

        // ---- 显示名称 ----
        root.addView(label("显示名称"));
        root.addView(hint("卡片、通知栏、复制群消息里都用这个名字，不影响已记录的数据。"));
        etName = new EditText(this);
        etName.setText(track.title);
        etName.setTextSize(16);
        root.addView(etName);

        // ---- 储存位置（只读） ----
        root.addView(label("储存在手机里的位置"));
        TextView tvUri = new TextView(this);
        tvUri.setText(track.uri);
        tvUri.setTextSize(12);
        tvUri.setTextColor(getColor(R.color.ink));
        tvUri.setTextIsSelectable(true);
        root.addView(tvUri);
        TextView tvSrc = new TextView(this);
        String src = track.sourceName != null ? track.sourceName : "（未知）";
        if (track.isFile()) {
            // v1.3 预置音频：uri 即内部存储绝对路径，如实展示并标注内置来源
            tvSrc.setText("来源：内置音频（随 App 预置，储存在 App 内部存储）"
                    + "\n可读位置：" + track.uri);
        } else {
            tvSrc.setText("来源文件名：" + src
                    + "\n可读位置：" + readableLocation(track.uri));
        }
        tvSrc.setTextSize(12);
        tvSrc.setTextColor(getColor(R.color.muted));
        tvSrc.setPadding(0, dp(4), 0, 0);
        root.addView(tvSrc);

        // ---- 遍数修正 ----
        root.addView(label("遍数修正"));
        root.addView(hint("今日遍数：直接改今天记下的遍数。"));
        etToday = numberInput(store.trackToday(track));
        root.addView(etToday);
        root.addView(hint("在此软件记录之外、此前已读的遍数：填进来后会算进「总计」，不会改动每天的历史记录。"));
        etBase = numberInput(track.baseTotal);
        root.addView(etBase);
        tvTotalHint = new TextView(this);
        tvTotalHint.setTextSize(13);
        tvTotalHint.setTextColor(getColor(R.color.muted));
        tvTotalHint.setPadding(0, dp(6), 0, 0);
        root.addView(tvTotalHint);
        refreshTotalHint();

        Button btnSave = new Button(this);
        btnSave.setText("保存");
        btnSave.setTextSize(16);
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        saveLp.topMargin = dp(18);
        btnSave.setLayoutParams(saveLp);
        btnSave.setOnClickListener(v -> saveAll());
        root.addView(btnSave);

        // ---- 危险区 ----
        root.addView(label("清除与移除"));
        Button btnReset = new Button(this);
        btnReset.setText("重置这个音频的遍数");
        btnReset.setTextSize(15);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rLp.topMargin = dp(6);
        btnReset.setLayoutParams(rLp);
        btnReset.setOnClickListener(v -> confirmReset());
        root.addView(btnReset);

        Button btnRemove = new Button(this);
        btnRemove.setText("从列表中移除这个音频");
        btnRemove.setTextSize(15);
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mLp.topMargin = dp(8);
        btnRemove.setLayoutParams(mLp);
        btnRemove.setOnClickListener(v -> confirmRemove());
        root.addView(btnRemove);
    }

    /** content:// URI 尽量解析成可读形式：取文档路径段解码展示 */
    private String readableLocation(String uriStr) {
        try {
            Uri u = Uri.parse(uriStr);
            String path = u.getPath();
            if (path != null) return Uri.decode(path);
        } catch (Exception ignored) {
        }
        return uriStr;
    }

    private void refreshTotalHint() {
        if (track == null || tvTotalHint == null) return;
        tvTotalHint.setText("当前总计 " + store.trackTotal(track) + " 遍（本软件记录 "
                + store.trackRecordedTotal(track) + " 遍 ＋ 此前已读 " + track.baseTotal + " 遍）");
    }

    private void saveAll() {
        String name = etName.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(this, "显示名称不能为空", Toast.LENGTH_SHORT).show();
            return;
        }
        int todayCount = parseNonNeg(etToday.getText().toString());
        int base = parseNonNeg(etBase.getText().toString());
        if (todayCount < 0 || base < 0) {
            Toast.makeText(this, "遍数请填 0 或正整数", Toast.LENGTH_SHORT).show();
            return;
        }
        track.title = name;
        track.baseTotal = base;
        store.saveTracks();
        // 今日遍数直接改当天记录（与首页 +/− 同一写入路径）
        store.setTrackCountOn(track, LocalDate.now(), todayCount);
        refreshTotalHint();
        // 通知标题与统计串立刻跟上
        if (PlayerService.instance != null) PlayerService.instance.refreshStats();
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
    }

    private int parseNonNeg(String s) {
        try {
            int v = Integer.parseInt(s.trim());
            return v >= 0 ? v : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle("重置遍数")
                .setMessage("把" + track.title + "的全部播放记录清零（包括每天的历史和「此前已读」遍数）？此操作不可恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("重置", (d, w) -> {
                    store.resetTrackRecords(track);
                    track.baseTotal = 0;
                    store.saveTracks();
                    etToday.setText("0");
                    etBase.setText("0");
                    refreshTotalHint();
                    if (PlayerService.instance != null) PlayerService.instance.refreshStats();
                    Toast.makeText(this, "已重置", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void confirmRemove() {
        new AlertDialog.Builder(this)
                .setTitle("移除音频")
                .setMessage("把" + track.title + "从列表移除？\n它过去的播放记录会保留在日历统计里。")
                .setNegativeButton("取消", null)
                .setPositiveButton("移除", (d, w) -> {
                    PlayerService svc = PlayerService.instance;
                    if (svc != null && svc.current != null && svc.current.id.equals(track.id)) {
                        Intent i = new Intent(this, PlayerService.class)
                                .setAction(PlayerService.ACTION_STOP);
                        startService(i);
                    }
                    store.tracks.remove(track);
                    store.saveTracks();
                    Toast.makeText(this, "已移除", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .show();
    }

    private TextView label(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(17);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextColor(getColor(R.color.ink));
        tv.setPadding(0, dp(20), 0, dp(4));
        return tv;
    }

    private TextView hint(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(12);
        tv.setTextColor(getColor(R.color.muted));
        tv.setPadding(0, 0, 0, dp(4));
        return tv;
    }

    private EditText numberInput(int value) {
        EditText et = new EditText(this);
        et.setText(String.valueOf(value));
        et.setTextSize(16);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setSelectAllOnFocus(true);
        return et;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
