package com.ti.songdu;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.time.LocalDate;

/**
 * 前台播放服务（关屏续播的实现核心）：
 *  - foregroundServiceType = mediaPlayback，startForeground 常驻通知（带播放/暂停、停止按钮）
 *  - MediaPlayer.setWakeMode(PARTIAL_WAKE_LOCK)：息屏后 CPU 保持唤醒，播放与计数不中断
 *  - 不用 setLooping(true)：在 onCompletion 里先给当天计数 +1，再重播同一首
 *
 * v1.1 修复「第二遍无声」bug 的病因判断与对策：
 *  v1.0 在 onCompletion（PlaybackCompleted 状态）里直接 seekTo(0)+start()。seekTo 是异步的，
 *  在该状态下 start 时机与 seek 完成竞速，在 Pixel 的解码器上表现为进度归零但无声播放；
 *  且 completion 后 PlaybackParams（倍速）与音频焦点都可能已失效，位置落盘定时器也可能
 *  把末尾位置写回。对策：重播改走全量重建路径——reset → 重新设置唤醒模式/音频属性 →
 *  setDataSource → prepareAsync → 在 onPrepared 里重新取音频焦点、重新应用倍速、start；
 *  重播前先把已保存位置清零并置 restarting 标志，期间 ticker 不写位置，杜绝旧位置把播放拉走。
 *
 * v1.4.3 跟读模式（老师读一句、学生跟一句）：
 *  状态机在本服务内（followEnabled 开关 / followPhase 阶段 / followIndex 当前句），
 *  由主线程 Handler 每 50ms 驱动（followTick），不开新线程。开启且当前曲目有内容
 *  时间轴（ContentRepo 匹配、已按时长比缩放，noAudio 句不进轴）时生效：
 *  播当前句到句末 → 自动暂停 gap 毫秒（gap＝本句本段实际播过的媒体时长 / 当前倍速 ×1.5，
 *  即用户刚才听到的真实时长的 1.5 倍——跟读者不熟练、复诵比录音慢，要留更长时间，下限 300ms）→ 自动 seek 下一句开播，如此逐句。
 *  最后一句播完走 finishFollowPass（计数 +1，逻辑与 onCompletion 的旧路径同口径；
 *  循环开则全量重建从头再跟读，关则停在结尾、阶段回 IDLE、开关保持开）。
 *  逐句段的结束由状态机判定；MediaPlayer 自然播完只可能发生在最后一句，
 *  onCompletion 里先认 followFinishGuard / 跟读介入中，防与 watcher 抢处理而重复计数。
 *  用户干预：gap 中暂停＝取消倒计时（followGapPaused），gap 中播放＝跳过等待进下一句；
 *  seek / ±15 秒 / 点句播放统一在 seekToPosition 后按落点重新同步当前句；
 *  切歌复位状态机，新曲无原文则跟读自动关。开关持久化（follow_prefs），
 *  冷启动只恢复开关态，不自动开播——播起来后状态机按当前位置自动接上。
 */
public class PlayerService extends Service {
    public static final String ACTION_PLAY_TRACK = "com.ti.songdu.PLAY_TRACK";
    public static final String ACTION_TOGGLE = "com.ti.songdu.TOGGLE";
    public static final String ACTION_PAUSE = "com.ti.songdu.PAUSE";
    public static final String ACTION_RESUME = "com.ti.songdu.RESUME";
    public static final String ACTION_STOP = "com.ti.songdu.STOP";
    public static final String ACTION_PREV = "com.ti.songdu.PREV";
    public static final String ACTION_NEXT = "com.ti.songdu.NEXT";
    public static final String ACTION_PLAY_TRACK_AT = "com.ti.songdu.PLAY_TRACK_AT";
    public static final String ACTION_SEEK_TO = "com.ti.songdu.SEEK_TO";
    public static final String ACTION_SEEK_REL = "com.ti.songdu.SEEK_REL";
    public static final String EXTRA_TRACK_ID = "track_id";
    public static final String EXTRA_SEEK_POS = "seek_pos";
    public static final String EXTRA_SEEK_DELTA = "seek_delta";

    private static final String CHANNEL_ID = "songdu_player";
    private static final int NOTIF_ID = 1001;

    public interface Listener {
        void onStateChanged();
        void onProgress(String trackId, long posMs, long durMs);
        void onCountChanged();
    }

    public static PlayerService instance;
    public static Listener listener;

    private Store store;
    private MediaPlayer mp;
    private MediaSession session;
    private boolean prepared = false;
    private boolean preparing = false;
    /** 正在走「播完一遍→全量重建重播」流程，期间 ticker 不得把旧位置写回 */
    private boolean restarting = false;
    public Track current;
    public boolean playing = false;
    /** v1.4 阅读页的单曲循环开关：默认 true，与 v1.0–v1.3 的行为完全一致（播完计遍后自动重播）；
     *  关掉时播完只计数、不重播，停在结尾（位置已清零，再按播放从头播） */
    public boolean loopEnabled = true;
    /** v1.4 点句播放的待执行 seek：playTrackAt 在 prepare 完成前先记下，onPrepared 时消费；
     *  带 trackId 校验，防止中途切歌后把旧位置 seek 到新曲目上 */
    private long pendingSeekMs = -1;
    private String pendingSeekTrackId = null;

    // ---------- v1.4.3 跟读模式 ----------
    public static final int FOLLOW_IDLE = 0;
    public static final int FOLLOW_PLAYING = 1;
    public static final int FOLLOW_GAP = 2;
    private static final String FOLLOW_PREFS = "follow_prefs";
    /** 跟读开关（持久化在 follow_prefs/enabled；冷启动只恢复开关态，不自动开播） */
    public boolean followEnabled = false;
    /** v1.7 每段字数上限（持久化在 follow_prefs/chunkChars，默认 15；0＝整句不分段）。
     *  分段由 FollowChunker 按内容包 breaks（标点级时间戳）生成，状态机按「段」推进 */
    public int followChunkChars = 15;
    /** 跟读阶段：IDLE＝未介入 / PLAYING＝正在播当前句 / GAP＝已暂停留时间给用户复诵 */
    public int followPhase = FOLLOW_IDLE;
    /** 当前跟读句在时间轴数组（foStart/foEnd）中的下标 */
    public int followIndex = -1;
    /** gap 阶段被用户暂停：倒计时取消，停在 gap 等用户点播放直接进下一句 */
    private boolean followGapPaused = false;
    /** gap 倒计时截止时刻（SystemClock.uptimeMillis 口径） */
    private long followGapEndsAt = 0;
    /** v1.6.1：gap 时长倍数——停顿留本句真实时长的 1.5 倍（跟读者复诵比录音慢） */
    private static final double FOLLOW_GAP_FACTOR = 1.5;
    /** 当前句这一段是从哪个媒体位置开始播的；gap＝(句末−段始)/倍速，即实际听到的时长 */
    private long followSegFrom = 0;
    /** watcher 已接管最后一句的完成处理时置真，防 onCompletion 再走旧路径重复计数 */
    private boolean followFinishGuard = false;
    // 当前曲目的跟读时间轴（v1.7 起为「段」轴：FollowChunker 按 followChunkChars 生成；
    // 缩放后毫秒；与曲目 id 绑定，切歌或改字数失效重建）
    private String foTrackId = null;
    private long[] foStart = null;
    private long[] foEnd = null;
    /** 每段的起止句在拉平句表中的下标（高亮联动：播放中报段首句、gap 报段末句） */
    private int[] foFirst = null;
    private int[] foLast = null;
    private boolean followLoopPosted = false;
    private final Runnable followTick = new Runnable() {
        @Override
        public void run() {
            followStep();
            if (followEnabled) {
                handler.postDelayed(this, 50);
            } else {
                followLoopPosted = false;
            }
        }
    };

    // 音频焦点：v1.0 全程未申请，现补上——开始/恢复/重播前先取焦点，被抢时暂停
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private boolean pausedByFocusLoss = false;
    private final AudioManager.OnAudioFocusChangeListener focusListener = focusChange -> {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_LOSS:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                if (playing) {
                    pausedByFocusLoss = true;
                    pause();
                }
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                if (pausedByFocusLoss) {
                    pausedByFocusLoss = false;
                    resume();
                }
                break;
            default:
                break;
        }
    };

    private boolean requestFocus() {
        if (audioManager == null) audioManager = getSystemService(AudioManager.class);
        if (audioManager == null) return true;
        if (focusRequest == null) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setOnAudioFocusChangeListener(focusListener)
                    .build();
        }
        return audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonFocus() {
        if (audioManager != null && focusRequest != null) {
            try {
                audioManager.abandonAudioFocusRequest(focusRequest);
            } catch (Exception ignored) {
            }
        }
        pausedByFocusLoss = false;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            tick();
            handler.postDelayed(this, 2000);
        }
    };

    private void tick() {
        // 重播重建中（restarting）或准备中不写位置：防止把上一遍末尾位置写回，
        // 与重播竞速（这也是 v1.0 的隐患之一）
        if (restarting || preparing) return;
        if (mp != null && prepared && current != null) {
            try {
                long pos = mp.getCurrentPosition();
                current.positionMs = pos;
                store.saveTracks();
                if (listener != null)
                    listener.onProgress(current.id, pos, current.durationMs);
            } catch (Exception ignored) {
            }
        }
    }

    // ---------- v1.4.3 跟读状态机 ----------
    private void startFollowLoop() {
        if (followLoopPosted) return;
        followLoopPosted = true;
        handler.post(followTick);
    }

    private void stopFollowLoop() {
        handler.removeCallbacks(followTick);
        followLoopPosted = false;
    }

    /** 跟读开关：写持久化；开时只置 IDLE（播起来后状态机按当前位置接上），关时立即复位阶段 */
    public void setFollowEnabled(boolean on) {
        followEnabled = on;
        getSharedPreferences(FOLLOW_PREFS, MODE_PRIVATE)
                .edit().putBoolean("enabled", on).apply();
        followPhase = FOLLOW_IDLE;
        followIndex = -1;
        followGapPaused = false;
        followFinishGuard = false;
        if (on) startFollowLoop();
        else stopFollowLoop();
        updateNotification();
        notifyState();
    }

    /** 阅读页高亮联动用：当前跟读段的代表句在全书拉平顺序中的下标，无则 -1。
     *  gap 阶段报段末句（与旧版 gap 高亮语义一致：高亮停在刚播完的那句） */
    public int followFlatIndex() {
        if (followIndex < 0 || foFirst == null || followIndex >= foFirst.length) return -1;
        return followPhase == FOLLOW_GAP ? foLast[followIndex] : foFirst[followIndex];
    }

    /**
     * v1.7 改每段字数：持久化＋作废段轴；正在跟读时立即按新字数重算——
     * 播放中不打断声音，阶段回 IDLE 由状态机按当前位置以新段接上；
     * gap 中按落点所在段重同步（未暂停则直接播该段，已暂停只重同步等用户点播）。
     */
    public void setFollowChunkChars(int chars) {
        followChunkChars = chars;
        getSharedPreferences(FOLLOW_PREFS, MODE_PRIVATE)
                .edit().putInt("chunkChars", chars).apply();
        foTrackId = null;
        foStart = null;
        foEnd = null;
        foFirst = null;
        foLast = null;
        if (!followEnabled) {
            notifyState();
            return;
        }
        if (followPhase == FOLLOW_GAP) {
            if (!ensureFollowTimeline()) {
                setFollowEnabled(false);
                return;
            }
            long pos = (mp != null && prepared) ? currentPosition() : 0;
            int idx = followIndexForPosition(pos);
            if (idx < 0) {
                followPhase = FOLLOW_IDLE;
                followIndex = -1;
                followGapPaused = false;
            } else {
                // advanceFollowSentence 从 followIndex+1 播起：置为落点段的前一段，
                // 于是接下来播的正是包含当前停顿位置的那一段（按新字数）
                followIndex = idx - 1;
                if (!followGapPaused) advanceFollowSentence();
            }
            updateNotification();
            notifyState();
            return;
        }
        followPhase = FOLLOW_IDLE;
        followIndex = -1;
        notifyState();
    }

    /** 当前曲目的跟读段轴（缩放后）：按曲目 id 缓存，无内容包/无可播句返回 false。
     *  v1.7：句子先缩放，再由 FollowChunker 按 followChunkChars 与 breaks 分段 */
    private boolean ensureFollowTimeline() {
        if (current == null) return false;
        if (foStart != null && current.id.equals(foTrackId)) return foStart.length > 0;
        foTrackId = current.id;
        foStart = null;
        foEnd = null;
        foFirst = null;
        foLast = null;
        // v1.5：统一走 bookForTrack（论语返回单篇 Book，跟读轴即该篇篇内时间轴）
        ContentRepo.Book b = ContentRepo.bookForTrack(getApplicationContext(), current);
        if (b == null) return false;
        double scale = ContentRepo.scaleFor(current, b);
        java.util.List<FollowChunker.SIn> sins = new java.util.ArrayList<>();
        for (int i = 0; i < b.flat.size(); i++) {
            ContentRepo.Sentence s = b.flat.get(i);
            if (s.noAudio || s.startMs < 0) continue; // 录音未诵的句子不播也不停
            long[] bms = null;
            if (s.breakMs != null) {
                bms = new long[s.breakMs.length];
                for (int k = 0; k < bms.length; k++) bms[k] = Math.round(s.breakMs[k] * scale);
            }
            sins.add(new FollowChunker.SIn(Math.round(s.startMs * scale),
                    Math.round(s.endMs * scale), i, s.textT, s.breakPos, bms));
        }
        if (sins.isEmpty()) return false;
        java.util.List<FollowChunker.Chunk> chunks = FollowChunker.chunk(sins, followChunkChars);
        if (chunks.isEmpty()) return false;
        foStart = new long[chunks.size()];
        foEnd = new long[chunks.size()];
        foFirst = new int[chunks.size()];
        foLast = new int[chunks.size()];
        for (int i = 0; i < chunks.size(); i++) {
            foStart[i] = chunks.get(i).startMs;
            foEnd[i] = chunks.get(i).endMs;
            foFirst[i] = chunks.get(i).firstFlat;
            foLast[i] = chunks.get(i).lastFlat;
        }
        return true;
    }

    /** 位置 → 当前句：最后一个 start<=pos 的句子；已过其句末（句间停顿/章题秒数）归下一句，
     *  没有下一句返回 -1；还没到第一句返回第一句 */
    private int followIndexForPosition(long pos) {
        if (foStart == null || foStart.length == 0) return -1;
        int lo = 0, hi = foStart.length - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) / 2;
            if (foStart[mid] <= pos) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (ans < 0) return 0;
        if (pos >= foEnd[ans]) {
            return ans + 1 < foStart.length ? ans + 1 : -1;
        }
        return ans;
    }

    /** 状态机每 50ms 一步：gap 倒计时、播放中介入/监控当前句 */
    private void followStep() {
        if (!followEnabled) return;
        if (followPhase == FOLLOW_GAP) {
            if (!followGapPaused && SystemClock.uptimeMillis() >= followGapEndsAt) {
                advanceFollowSentence();
            }
            return;
        }
        if (current == null || mp == null || !playing || !prepared || preparing || restarting) return;
        if (!ensureFollowTimeline()) {
            // 正在播的曲目没有原文时间轴：跟读不可用，自动关（切到无内容曲目同理）
            setFollowEnabled(false);
            return;
        }
        long pos = currentPosition();
        if (followPhase == FOLLOW_IDLE) {
            int idx = followIndexForPosition(pos);
            if (idx >= 0) {
                // 新一段开播：上一遍 watcher 接管完成时设的 guard 到此失效清除，
                // 防它误吞以后自然播完的 completion
                followFinishGuard = false;
                followIndex = idx;
                followSegFrom = pos;
                followPhase = FOLLOW_PLAYING;
                notifyState();
            }
            return;
        }
        // FOLLOW_PLAYING
        if (followIndex < 0 || followIndex >= foStart.length) {
            followPhase = FOLLOW_IDLE;
            followIndex = -1;
            return;
        }
        if (pos < foStart[followIndex] - 300 || pos > foEnd[followIndex] + 300) {
            // 位置被外部 seek 拉出当前句范围（seek 入口已同步过，这里是兜底）：重新同步
            int idx = followIndexForPosition(pos);
            if (idx < 0) {
                followPhase = FOLLOW_IDLE;
                followIndex = -1;
                notifyState();
                return;
            }
            followIndex = idx;
            followSegFrom = pos;
        }
        if (pos >= foEnd[followIndex]) {
            onFollowSentenceEnd();
        }
    }

    /** 当前句播完：最后一句走整曲完成；否则暂停并进入 gap（时长＝本段实际播过/倍速 ×1.5） */
    private void onFollowSentenceEnd() {
        int idx = followIndex;
        if (idx < 0 || foEnd == null || idx >= foEnd.length) {
            followPhase = FOLLOW_IDLE;
            followIndex = -1;
            return;
        }
        if (idx >= foStart.length - 1) {
            // 最后一句：先暂停封住 MediaPlayer 自己的 completion，再接管完成处理
            followFinishGuard = true;
            pausePlayer();
            finishFollowPass();
            return;
        }
        long gapMs = (long) ((foEnd[idx] - followSegFrom) / store.speed * FOLLOW_GAP_FACTOR);
        if (gapMs < 300) gapMs = 300;
        pausePlayer();
        followPhase = FOLLOW_GAP;
        followGapPaused = false;
        followGapEndsAt = SystemClock.uptimeMillis() + gapMs;
        updateNotification();
        notifyState();
    }

    /** gap 到点 / gap 中点播放：跳过等待，从下一句开头继续 */
    private void advanceFollowSentence() {
        int next = followIndex + 1;
        if (foStart == null || next >= foStart.length) {
            followPhase = FOLLOW_IDLE;
            followIndex = -1;
            notifyState();
            return;
        }
        followPhase = FOLLOW_PLAYING;
        followGapPaused = false;
        followIndex = next;
        followSegFrom = foStart[next];
        if (mp != null && prepared) {
            seekToPosition(foStart[next]); // 内部会按落点再同步一次（结果与此处一致）
            if (!playing) {
                requestFocus();
                applySpeed();
                try {
                    mp.start();
                } catch (Exception ignored) {
                }
                playing = true;
            }
        }
        updateNotification();
        notifyState();
    }

    /**
     * 跟读下的一遍播完：计数 +1（与 onCompletion 旧路径同口径），位置清零；
     * 循环开 → 全量重建从头播，播起来后状态机自动从第一句重新跟读；
     * 循环关 → 停在结尾，阶段回 IDLE，跟读开关保持开。
     */
    private void finishFollowPass() {
        if (current != null) {
            store.increment(current, LocalDate.now());
            current.positionMs = 0;
            store.saveTracks();
            if (listener != null) listener.onCountChanged();
        }
        followPhase = FOLLOW_IDLE;
        followIndex = -1;
        followGapPaused = false;
        if (loopEnabled) {
            replayFromStart();
        } else {
            playing = false;
        }
        updateNotification();
        notifyState();
    }

    /** 切歌后复位跟读状态机；新曲无原文则跟读自动关 */
    private void resetFollowForNewTrack() {
        followPhase = FOLLOW_IDLE;
        followIndex = -1;
        followGapPaused = false;
        followFinishGuard = false;
        foTrackId = null;
        foStart = null;
        foEnd = null;
        foFirst = null;
        foLast = null;
        if (followEnabled && current != null && ContentRepo.bookIdFor(current) == null) {
            setFollowEnabled(false);
        }
    }

    /** seek（进度条/±15 秒/点句）后：按落点重新同步当前句，阶段回 PLAYING（gap 被取消） */
    private void resyncFollowAfterSeek(long pos) {
        if (!followEnabled || current == null) return;
        if (!ensureFollowTimeline()) return;
        int idx = followIndexForPosition(pos);
        followGapPaused = false;
        if (idx < 0) {
            followPhase = FOLLOW_IDLE;
            followIndex = -1;
        } else {
            followIndex = idx;
            followSegFrom = pos;
            followPhase = FOLLOW_PLAYING;
        }
        notifyState();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        store = Store.get(this);
        store.installPresetsIfNeeded(); // 与 MainActivity 双入口，靠 presetsDone 标记保证只做一次
        // v1.4.3：恢复跟读开关态（只恢复开关，冷启动不自动开播、不进 gap）
        followEnabled = getSharedPreferences(FOLLOW_PREFS, MODE_PRIVATE)
                .getBoolean("enabled", false);
        // v1.7：恢复每段字数（默认 15；0＝整句）
        followChunkChars = getSharedPreferences(FOLLOW_PREFS, MODE_PRIVATE)
                .getInt("chunkChars", 15);
        if (followEnabled) startFollowLoop();
        createChannel();
        initSession();
        // 先进入前台，避免 startForegroundService 超时被杀
        startForegroundWith(buildNotification());
        handler.post(ticker);
    }

    /** MediaSession：让下拉通知栏 / 锁屏 / 快捷设置里的系统媒体控制能直接播放、暂停、切歌 */
    private void initSession() {
        session = new MediaSession(getApplicationContext(), "SongduSession");
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                if (current != null) resume();
                else if (!store.tracks.isEmpty()) playTrack(store.tracks.get(0));
            }

            @Override
            public void onPause() {
                pause();
            }

            @Override
            public void onStop() {
                stopAll();
            }

            @Override
            public void onSkipToNext() {
                skip(1);
            }

            @Override
            public void onSkipToPrevious() {
                skip(-1);
            }

            @Override
            public void onSeekTo(long pos) {
                seekToPosition(pos);
            }
        });
        session.setActive(true);
    }

    /** 上一首/下一首：按列表顺序切歌（单曲循环仍是默认，切歌只来自用户手动操作） */
    public void skip(int delta) {
        if (store.tracks.isEmpty()) return;
        int idx = 0;
        if (current != null) {
            for (int i = 0; i < store.tracks.size(); i++) {
                if (store.tracks.get(i).id.equals(current.id)) {
                    idx = i;
                    break;
                }
            }
        }
        int next = (idx + delta + store.tracks.size()) % store.tracks.size();
        playTrack(store.tracks.get(next));
    }

    private void syncSession() {
        if (session == null) return;
        if (current != null) {
            // 统计串写进 ARTIST / DISPLAY_SUBTITLE：快捷设置媒体面板与锁屏展示
            // 的正是标题+这两位文本，数字因此与首页卡片同步可见
            String stats = store.statsLine(current);
            session.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, current.title)
                    .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, current.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, stats)
                    .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, stats)
                    .putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, stats)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, current.durationMs)
                    .build());
        }
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | PlaybackState.ACTION_SEEK_TO;
        int state = playing ? PlaybackState.STATE_PLAYING
                : (current != null ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_STOPPED);
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(state, currentPosition(), store.speed)
                .build());
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "播放", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("诵读播放状态");
            nm.createNotificationChannel(ch);
        }
    }

    /** reset 之后必须重设：唤醒模式与音频属性都会随 reset 丢失，漏设会导致息屏断播/无声 */
    private void configurePlayer(MediaPlayer p) {
        p.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
        p.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build());
    }

    private void ensurePlayer() {
        if (mp != null) return;
        mp = new MediaPlayer();
        configurePlayer(mp);
        mp.setOnPreparedListener(p -> {
            prepared = true;
            preparing = false;
            boolean wasRestart = restarting;
            restarting = false;
            if (current != null && pendingSeekMs >= 0 && current.id.equals(pendingSeekTrackId)) {
                // v1.4 点句播放：优先跳到指定句的位置（clamp 到时长内，留 500ms 余量防直接播完）
                long start = pendingSeekMs;
                if (current.durationMs > 0)
                    start = Math.min(start, Math.max(0, current.durationMs - 500));
                if (start > 0) p.seekTo((int) start);
                current.positionMs = start;
            } else if (current != null && !wasRestart) {
                // 普通点播/续播才跳到上次位置；重播（restarting）位置已清零，从头播
                long start = current.positionMs;
                if (current.durationMs > 0 && start >= current.durationMs - 1000) start = 0;
                if (start > 0) p.seekTo((int) start);
            }
            pendingSeekMs = -1;
            pendingSeekTrackId = null;
            requestFocus();
            applySpeed(); // 每次 start 前重新应用倍速（completion/reset 后参数会失效）
            p.start();
            playing = true;
            updateNotification();
            notifyState();
        });
        mp.setOnCompletionListener(p -> {
            // v1.4.3 跟读介入时，整曲完成归状态机：watcher 已接管（guard）则直接忽略；
            // 自然播完先到（只可能在最后一句）则由 finishFollowPass 统一计数，
            // 两条路只会走一条，不重复计数、也不漏计。
            if (followFinishGuard) {
                followFinishGuard = false;
                return;
            }
            if (followEnabled && followPhase != FOLLOW_IDLE) {
                finishFollowPass();
                return;
            }
            // 一遍完整播完：先计数落盘、位置清零，再走全量重建路径重播（单曲循环）。
            // 不再用 v1.0 的 seekTo(0)+start()：PlaybackCompleted 状态下异步 seek 与
            // start 竞速，在真机上会出现第二遍无声（本次 bug）。
            if (current != null) {
                store.increment(current, LocalDate.now());
                current.positionMs = 0;
                store.saveTracks();
                if (listener != null) listener.onCountChanged();
            }
            if (!loopEnabled) {
                // v1.4：循环关——计完这一遍就停，不重播（默认开，旧行为不变）
                playing = false;
                updateNotification();
                notifyState();
                return;
            }
            replayFromStart();
            updateNotification();
            notifyState();
        });
        mp.setOnErrorListener((p, what, extra) -> {
            prepared = false;
            preparing = false;
            restarting = false;
            playing = false;
            updateNotification();
            notifyState();
            return true;
        });
    }

    /** 按曲目来源类型设置数据源：file（预置音频）走内部存储绝对路径，content 走 content:// URI */
    private void setDataSourceFor(MediaPlayer p, Track t) throws Exception {
        if (t.isFile()) {
            p.setDataSource(t.uri);
        } else {
            p.setDataSource(getApplicationContext(), Uri.parse(t.uri));
        }
    }

    /** 播完一遍后的重播：reset → 重设唤醒/属性 → setDataSource → prepareAsync，
     *  后续 start 统一在 onPrepared 里完成（取焦点＋重应用倍速），路径明确、无竞速。 */
    private void replayFromStart() {
        if (current == null) return;
        ensurePlayer();
        restarting = true;
        prepared = false;
        preparing = true;
        playing = false;
        try {
            mp.reset();
            configurePlayer(mp);
            setDataSourceFor(mp, current);
            mp.prepareAsync();
        } catch (Exception e) {
            restarting = false;
            preparing = false;
        }
    }

    public void playTrack(Track t) {
        if (t == null) return;
        if (current != null && current.id.equals(t.id) && prepared) {
            if (!playing) {
                requestFocus();
                applySpeed();
                mp.start();
                playing = true;
            }
            updateNotification();
            notifyState();
            return;
        }
        // 切换音频前，先保存旧音频的位置
        saveCurrentPosition();
        ensurePlayer();
        restarting = false;
        try {
            mp.reset();
            // reset 后重新设置唤醒模式与音频属性，保证息屏续播不失效
            configurePlayer(mp);
        } catch (Exception ignored) {
        }
        prepared = false;
        preparing = true;
        current = t;
        playing = false;
        // v1.4.3：切歌复位跟读（新曲有原文则播起来后自动接上，无原文自动关）
        resetFollowForNewTrack();
        try {
            setDataSourceFor(mp, t);
            mp.prepareAsync();
        } catch (Exception e) {
            preparing = false;
        }
        updateNotification();
        notifyState();
    }

    /**
     * v1.4 阅读页点句播放：从指定位置播某曲目。
     * 已在播同一首且已 prepare → 直接 seek 并确保播放中；否则记下 pendingSeek，走 playTrack
     * 的全量准备路径，onPrepared 里跳到该位置（不被「上次听到」位置覆盖）。
     */
    public void playTrackAt(Track t, long posMs) {
        if (t == null) return;
        if (current != null && current.id.equals(t.id) && prepared) {
            if (mp != null) {
                seekToPosition(posMs);
                if (!playing) {
                    requestFocus();
                    applySpeed();
                    try {
                        mp.start();
                    } catch (Exception ignored) {
                    }
                    playing = true;
                }
                updateNotification();
                notifyState();
            }
            return;
        }
        pendingSeekMs = Math.max(0, posMs);
        pendingSeekTrackId = t.id;
        playTrack(t);
    }

    public void pause() {
        if (followPhase == FOLLOW_GAP) {
            // v1.4.3：gap 中点暂停＝真暂停，取消倒计时（播放器本就停着），
            // 停在 gap 等用户点播放直接进下一句
            followGapPaused = true;
            updateNotification();
            notifyState();
            return;
        }
        pausePlayer();
    }

    /** 真正暂停播放器（用户暂停与跟读状态机内部暂停共用，本身不带跟读语义） */
    private void pausePlayer() {
        if (mp != null && prepared && playing) {
            try {
                mp.pause();
            } catch (Exception ignored) {
            }
            playing = false;
        }
        saveCurrentPosition();
        updateNotification();
        notifyState();
    }

    public void resume() {
        if (current == null) return;
        if (followPhase == FOLLOW_GAP) {
            // v1.4.3：gap 中点播放＝跳过等待，立即从下一句开头继续
            advanceFollowSentence();
            return;
        }
        if (mp != null && prepared && !playing) {
            requestFocus();
            applySpeed();
            mp.start();
            playing = true;
            updateNotification();
            notifyState();
        } else if (!prepared && !preparing) {
            playTrack(current);
        }
    }

    public void toggle() {
        if (playing) pause();
        else resume();
    }

    public void stopAll() {
        saveCurrentPosition();
        abandonFocus();
        playing = false;
        prepared = false;
        preparing = false;
        restarting = false;
        // v1.4.3：停止播放只复位跟读阶段，开关态保留（下次开播仍可跟读）
        followPhase = FOLLOW_IDLE;
        followIndex = -1;
        followGapPaused = false;
        if (mp != null) {
            try {
                mp.stop();
            } catch (Exception ignored) {
            }
            mp.release();
            mp = null;
        }
        current = null;
        stopForeground(true);
        stopSelf();
        notifyState();
    }

    /** 拖动进度条 seek 到绝对位置：clamp 到 [0, 时长-1]，拖到末尾不触发计遍（只有自然播完才计） */
    public void seekToPosition(long posMs) {
        if (mp == null || !prepared || current == null) return;
        long dur = current.durationMs > 0 ? current.durationMs : Long.MAX_VALUE;
        long target = Math.max(0, Math.min(posMs, dur == Long.MAX_VALUE ? posMs : dur - 1));
        try {
            mp.seekTo((int) target);
            current.positionMs = target;
            store.saveTracks();
            if (listener != null) listener.onProgress(current.id, target, current.durationMs);
            syncSession();
            // v1.4.3：跟读中 seek 后按落点重新同步当前句（gap 中的 seek 亦取消等待）
            resyncFollowAfterSeek(target);
        } catch (Exception ignored) {
        }
    }

    /** 快进/后退（±15 秒）：相对当前进度 seek，同样 clamp 且末尾留 1ms，不计遍 */
    public void seekRelative(long deltaMs) {
        if (mp == null || !prepared || current == null) return;
        try {
            seekToPosition(mp.getCurrentPosition() + deltaMs);
        } catch (Exception ignored) {
        }
    }

    /** 外部（首页手动 +/−、设置页改名/改数）改了记录或标题后，刷新通知与 MediaSession 统计 */
    public void refreshStats() {
        updateNotification();
        notifyState();
    }

    public void setSpeed(float s) {
        store.speed = s;
        store.saveSettings();
        applySpeed();
    }

    public float getSpeed() {
        return store.speed;
    }

    private void applySpeed() {
        if (mp != null && prepared) {
            try {
                mp.setPlaybackParams(mp.getPlaybackParams().setSpeed(store.speed));
            } catch (Exception ignored) {
            }
        }
    }

    private void saveCurrentPosition() {
        if (mp != null && prepared && current != null) {
            try {
                current.positionMs = mp.getCurrentPosition();
                store.saveTracks();
            } catch (Exception ignored) {
            }
        }
    }

    public long currentPosition() {
        if (mp != null && prepared) {
            try {
                return mp.getCurrentPosition();
            } catch (Exception ignored) {
            }
        }
        return current != null ? current.positionMs : 0;
    }

    // ---------- 通知 ----------
    private Notification buildNotification() {
        String title = current != null ? current.title : "诵读";
        // 副文本位放当前音频的统计串（今日/昨日/本周/上周/本月），与首页卡片同口径；
        // 每次计数变化、切歌、播放/暂停都会重建通知刷新这串数字
        String text = current == null ? "打开应用选择音频开始播放"
                : store.statsLine(current);
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(text)
                .setSubText(current != null ? text : null)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                // MediaStyle：下拉面板与锁屏显示标准媒体控件，并与 MediaSession 双向同步
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session != null ? session.getSessionToken() : null)
                        .setShowActionsInCompactView(0, 1, 2));

        Intent prev = new Intent(this, PlayerService.class).setAction(ACTION_PREV);
        b.addAction(android.R.drawable.ic_media_previous, "上一首",
                PendingIntent.getService(this, 3, prev,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        Intent toggle = new Intent(this, PlayerService.class).setAction(ACTION_TOGGLE);
        PendingIntent piToggle = PendingIntent.getService(
                this, 1, toggle, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        b.addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                playing ? "暂停" : "播放", piToggle);

        Intent next = new Intent(this, PlayerService.class).setAction(ACTION_NEXT);
        b.addAction(android.R.drawable.ic_media_next, "下一首",
                PendingIntent.getService(this, 4, next,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        Intent stop = new Intent(this, PlayerService.class).setAction(ACTION_STOP);
        PendingIntent piStop = PendingIntent.getService(
                this, 2, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", piStop);
        return b.build();
    }

    private void startForegroundWith(Notification n) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void updateNotification() {
        syncSession();
        startForegroundWith(buildNotification());
    }

    private void notifyState() {
        if (listener != null) listener.onStateChanged();
    }

    // ---------- 生命周期 ----------
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_PLAY_TRACK: {
                    String id = intent.getStringExtra(EXTRA_TRACK_ID);
                    Track t = store.findTrack(id);
                    if (t != null) playTrack(t);
                    break;
                }
                case ACTION_PLAY_TRACK_AT: {
                    String id = intent.getStringExtra(EXTRA_TRACK_ID);
                    Track t = store.findTrack(id);
                    if (t != null)
                        playTrackAt(t, intent.getLongExtra(EXTRA_SEEK_POS, 0));
                    break;
                }
                case ACTION_TOGGLE:
                    toggle();
                    break;
                case ACTION_PAUSE:
                    pause();
                    break;
                case ACTION_RESUME:
                    resume();
                    break;
                case ACTION_STOP:
                    stopAll();
                    break;
                case ACTION_PREV:
                    skip(-1);
                    break;
                case ACTION_NEXT:
                    skip(1);
                    break;
                case ACTION_SEEK_TO:
                    seekToPosition(intent.getLongExtra(EXTRA_SEEK_POS, 0));
                    break;
                case ACTION_SEEK_REL:
                    seekRelative(intent.getLongExtra(EXTRA_SEEK_DELTA, 0));
                    break;
                default:
                    break;
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(ticker);
        stopFollowLoop();
        abandonFocus();
        saveCurrentPosition();
        if (mp != null) {
            mp.release();
            mp = null;
        }
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
