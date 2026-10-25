package com.ti.songdu;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * v1.17.0 选声页：一本有双声音的书（现仅《大学》）选「法师原声 / AI 朗读」。
 *
 * 两个入口共用本页：
 *  - 首播门：PlayerService.playTrack/playTrackAt 发现未选过 → openForPlay 拉起，
 *    带曲目 id（与点句位置）；选定即记住并按原入口续播。返回不选＝不播。
 *  - 设置重选：SettingsActivity「朗读声音」行进入（只带 bookId）；选定写偏好，
 *    若该书正在装载则经 PlayerService.switchVoice 按句换轴续播/保持暂停。
 *
 * 试听：本页自带一个 MediaPlayer，播所选音轨开头约 15 秒（各自真实文件：
 * 原声＝预置曲目文件、AI＝assets 音轨），到时自动停；点另一个/再点自己即停。
 * 与正文播放互不干扰（正文在 PlayerService）。
 *
 * 视觉沿总设置页口径：纸底、墨字、朱砂点缀，极简少字。
 */
public class VoiceChoiceActivity extends Activity {

    public static final String EXTRA_BOOK_ID = "voice_book_id";
    public static final String EXTRA_TRACK_ID = "voice_track_id";
    public static final String EXTRA_SEEK_POS = "voice_seek_pos";

    /** 试听时长：开头约 15 秒 */
    private static final long PREVIEW_MS = 15000;

    private String bookId;
    private String trackId;   // 首播门模式才有：选定后要播的曲目
    private long seekPos = -1; // 首播门模式点句位置；-1＝普通播放

    private MediaPlayer previewMp;
    private String previewVoice;
    private TextView btnPreviewOrig;
    private TextView btnPreviewAi;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable previewStopper = this::stopPreview;

    /** 首播门入口：服务侧发现未选过时调（ctx 可能是 Service，带 NEW_TASK） */
    public static void openForPlay(Context ctx, Track t, long seekPos) {
        String bid = ContentRepo.bookIdFor(t);
        if (bid == null) return;
        Intent i = new Intent(ctx, VoiceChoiceActivity.class);
        i.putExtra(EXTRA_BOOK_ID, bid);
        i.putExtra(EXTRA_TRACK_ID, t.id);
        i.putExtra(EXTRA_SEEK_POS, seekPos);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            ctx.startActivity(i);
        } catch (Exception ignored) {
        }
    }

    /** 设置重选入口 */
    public static Intent forSettings(Context ctx, String bookId) {
        Intent i = new Intent(ctx, VoiceChoiceActivity.class);
        i.putExtra(EXTRA_BOOK_ID, bookId);
        return i;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        bookId = getIntent().getStringExtra(EXTRA_BOOK_ID);
        trackId = getIntent().getStringExtra(EXTRA_TRACK_ID);
        seekPos = getIntent().getLongExtra(EXTRA_SEEK_POS, -1);
        if (bookId == null || !VoicePref.hasDualVoice(this, bookId)) {
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
        title.setText(bookDisplayTitle() + " · 选择朗读声音");
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(getColor(R.color.ink));
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("这本书有两个声音。先试听，再选一个；选好会记住，"
                + "以后点播放直接播。想换时到「设置 → 朗读声音」里重选。");
        hint.setTextSize(13);
        hint.setTextColor(getColor(R.color.muted));
        hint.setPadding(0, dp(8), 0, dp(10));
        root.addView(hint);

        String cur = VoicePref.getVoice(this, bookId);
        root.addView(optionCard(VoicePref.VOICE_ORIG, "法师原声",
                "法师诵读的原版录音", VoicePref.VOICE_ORIG.equals(cur)));
        root.addView(optionCard(VoicePref.VOICE_AI, "AI 朗读",
                "AI 合成的朗读声音", VoicePref.VOICE_AI.equals(cur)));

        TextView back = new TextView(this);
        back.setText("← 返回");
        back.setTextSize(15);
        back.setTextColor(getColor(R.color.accent));
        back.setPadding(0, dp(20), 0, 0);
        back.setOnClickListener(v -> finish());
        root.addView(back);
    }

    private String bookDisplayTitle() {
        Store store = Store.get(this);
        String pt = VoicePref.presetTrackIdFor(bookId);
        Track t = pt != null ? store.findTrack(pt) : null;
        if (t != null && t.title != null && !t.title.isEmpty()) return t.title;
        ContentRepo.Book b = ContentRepo.load(this, bookId);
        String raw = b != null ? b.title : bookId;
        return raw.startsWith("《") ? raw : "《" + raw + "》";
    }

    /** 一个声音选项卡：名字＋一句说明＋右侧试听钮；点卡身任意处即选定 */
    private LinearLayout optionCard(final String voice, String name, String desc,
                                    boolean isCurrent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(14), dp(12), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(0x00000000);
        bg.setStroke(Math.max(1, dp(1)), getColor(R.color.border));
        card.setBackground(bg);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = dp(12);
        card.setLayoutParams(clp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView nm = new TextView(this);
        nm.setText(isCurrent ? name + "（当前）" : name);
        nm.setTextSize(17);
        nm.setTypeface(null, Typeface.BOLD);
        nm.setTextColor(getColor(R.color.ink));
        texts.addView(nm);
        TextView ds = new TextView(this);
        ds.setText(desc);
        ds.setTextSize(13);
        ds.setTextColor(getColor(R.color.muted));
        ds.setPadding(0, dp(3), 0, 0);
        texts.addView(ds);
        card.addView(texts);

        TextView pv = new TextView(this);
        pv.setText("试听");
        pv.setTextSize(13);
        pv.setGravity(Gravity.CENTER);
        pv.setPadding(dp(14), dp(7), dp(14), dp(7));
        stylePreviewPill(pv, false);
        pv.setOnClickListener(v -> togglePreview(voice));
        card.addView(pv);
        if (VoicePref.VOICE_ORIG.equals(voice)) btnPreviewOrig = pv;
        else btnPreviewAi = pv;

        card.setOnClickListener(v -> choose(voice));
        return card;
    }

    /** 试听钮样式：未播＝灰描边，播着＝朱砂实心（与全 App 切换钮口径一致） */
    private void stylePreviewPill(TextView t, boolean active) {
        float density = getResources().getDisplayMetrics().density;
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(999 * density);
        if (active) {
            g.setColor(getColor(R.color.accent));
            t.setTextColor(0xFFFFFFFF);
            t.setTypeface(null, Typeface.BOLD);
        } else {
            g.setColor(0x00000000);
            g.setStroke(Math.max(1, (int) density), getColor(R.color.border));
            t.setTextColor(getColor(R.color.ink));
            t.setTypeface(null, Typeface.NORMAL);
        }
        t.setBackground(g);
    }

    private void refreshPreviewButtons() {
        if (btnPreviewOrig != null) {
            boolean on = VoicePref.VOICE_ORIG.equals(previewVoice);
            btnPreviewOrig.setText(on ? "停止" : "试听");
            stylePreviewPill(btnPreviewOrig, on);
        }
        if (btnPreviewAi != null) {
            boolean on = VoicePref.VOICE_AI.equals(previewVoice);
            btnPreviewAi.setText(on ? "停止" : "试听");
            stylePreviewPill(btnPreviewAi, on);
        }
    }

    /** 试听开关：正在播这个声音 → 停；否则换到这个声音从头播约 15 秒 */
    private void togglePreview(String voice) {
        if (voice.equals(previewVoice)) {
            stopPreview();
            return;
        }
        stopPreview();
        try {
            MediaPlayer mp = new MediaPlayer();
            if (VoicePref.VOICE_AI.equals(voice)) {
                AssetFileDescriptor afd = getAssets().openFd(VoicePref.aiAudioAsset(bookId));
                try {
                    mp.setDataSource(afd.getFileDescriptor(),
                            afd.getStartOffset(), afd.getLength());
                } finally {
                    afd.close();
                }
            } else {
                Track t = Store.get(this).findTrack(VoicePref.presetTrackIdFor(bookId));
                if (t == null || !t.isFile()) {
                    Toast.makeText(this, "原声音频不在列表里，无法试听",
                            Toast.LENGTH_SHORT).show();
                    mp.release();
                    return;
                }
                mp.setDataSource(t.uri);
            }
            previewMp = mp;
            previewVoice = voice;
            mp.setOnPreparedListener(p -> {
                p.start();
                handler.postDelayed(previewStopper, PREVIEW_MS);
                refreshPreviewButtons();
            });
            mp.setOnCompletionListener(p -> stopPreview());
            mp.setOnErrorListener((p, what, extra) -> {
                stopPreview();
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            stopPreview();
            Toast.makeText(this, "试听失败", Toast.LENGTH_SHORT).show();
        }
        refreshPreviewButtons();
    }

    private void stopPreview() {
        handler.removeCallbacks(previewStopper);
        if (previewMp != null) {
            try {
                previewMp.stop();
            } catch (Exception ignored) {
            }
            previewMp.release();
            previewMp = null;
        }
        previewVoice = null;
        refreshPreviewButtons();
    }

    /** 选定：记住选择；该书正在装载则服务内按句换轴；首播门模式再发起原播放 */
    private void choose(String voice) {
        stopPreview();
        VoicePref.setVoice(this, bookId, voice);
        PlayerService svc = PlayerService.instance;
        if (svc != null) svc.switchVoice(bookId, voice); // 内部同写偏好，重复无妨
        if (trackId != null) {
            Intent i = new Intent(this, PlayerService.class);
            if (seekPos >= 0) {
                i.setAction(PlayerService.ACTION_PLAY_TRACK_AT);
                i.putExtra(PlayerService.EXTRA_SEEK_POS, seekPos);
            } else {
                i.setAction(PlayerService.ACTION_PLAY_TRACK);
            }
            i.putExtra(PlayerService.EXTRA_TRACK_ID, trackId);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
            else startService(i);
        }
        finish();
    }

    @Override
    protected void onPause() {
        stopPreview();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopPreview();
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
