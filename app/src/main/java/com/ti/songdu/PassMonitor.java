package com.ti.songdu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一遍是否完成的唯一判定（纯逻辑、无 Android 依赖，桌面 harness 直驱本类）。
 *
 * 旧实现把「这一遍听完了」拆在三处：每 250ms 看播放位置有没有越过章尾、
 * 整条音频的播完回调、跟读自己的收尾。三处各写各的账，而且播完回调还会被
 * 「先把注念完」整段推迟——用户在这段空档里暂停、切到下一章、或进程被系统
 * 杀掉，这一遍就丢了。论语每篇是单独一条音频、章尾几乎贴着音频结尾，采样
 * 经常来不及越界，只能靠那次会被推迟的回调，所以按章听特别容易漏。
 *
 * 本类只回答「要不要记」，不负责去哪一首、也不负责落盘：
 *  - 从章身里听进来（armed）之后，位置到达章尾、整条音频自然结束、
 *    或已经听进章尾区就切走，都是同一遍，只报一次；
 *  - 拖到章尾附近才开始播的，不算（没听过这一章）；
 *  - 同一遍的键稳定，重复把「播完」送进来不会再报第二次。
 * 界面在前台、在后台、阅读页还是主页播放条，都不参与判定。
 */
public final class PassMonitor {

    /** 一次应当落账的完成。chapter=false 表示整条音频一遍（曲目账）。 */
    public static final class Event {
        public final boolean chapter;
        /** 章事件为全局章序号；曲目事件为 -1 */
        public final int chapterIndex;
        public final String dedupKey;
        public final String bookId;
        public final String trackId;

        public Event(boolean chapter, int chapterIndex, String dedupKey,
                     String bookId, String trackId) {
            this.chapter = chapter;
            this.chapterIndex = chapterIndex;
            this.dedupKey = dedupKey;
            this.bookId = bookId;
            this.trackId = trackId;
        }
    }

    /**
     * 可落盘的听读现场。不是遍数账：只记住「这一遍听到哪、记过没有」，
     * 进程被杀掉之后靠它把已经听进章尾区、还没来得及落账的那一遍补上。
     */
    public static final class Snapshot {
        public String sessionId = "";
        public String trackId = "";
        public String bookId = "";
        public int globalOffset;
        public int chapter = -1;
        public int attempt = 1;
        public int playId = 1;
        public boolean armed;
        public boolean tail;
        public boolean chapterEmitted;
        public boolean trackEmitted;
        public boolean single;
        public long pos = -1;
        public String date = "";
        public String chapterKey = "";
        public String trackKey = "";
        /** 补记过的那一章：下次若还停在这一章的章尾，不要把同一遍再记一回 */
        public String sealedTrack = "";
        public int sealedChapter = -1;

        public boolean chapterDue() {
            return tail && armed && !chapterEmitted
                    && chapterKey != null && chapterKey.length() > 0
                    && bookId != null && bookId.length() > 0 && chapter >= 0;
        }

        public boolean trackDue() {
            return single && tail && armed && !trackEmitted
                    && trackKey != null && trackKey.length() > 0
                    && trackId != null && trackId.length() > 0;
        }
    }

    private static final List<Event> NONE = Collections.emptyList();

    private String sessionId = "s0";
    private String trackId = "";
    private String bookId = "";
    private int globalOffset;
    private long[] starts = new long[0];
    private long[] ends = new long[0];
    private int cur = -1;
    private boolean armed;
    private int attempt = 1;
    private int playId;
    private boolean chapterEmitted;
    private boolean trackEmitted;
    private boolean tail;
    private long lastPos = -1;
    private String tailDate = "";
    private boolean live;
    private int revision;

    public void setSession(String sessionId) {
        if (sessionId != null && sessionId.length() > 0) this.sessionId = sessionId;
    }

    public String sessionId() { return sessionId; }

    public boolean isLive() { return live; }

    public boolean sameTrack(String id) {
        return live && id != null && id.equals(trackId);
    }

    public int revision() { return revision; }

    /** 与旧章界监测同一条防误计线：没从这条线之前听进来的，不算听完。 */
    public static long thresholdFor(long startMs, long endMs) {
        long len = Math.max(0, endMs - startMs);
        return Math.min(2000L, Math.max(500L, len / 10));
    }

    /**
     * 装上一条音频的章界。空表表示这条音频没有章节（只可能产生曲目一遍）。
     * 不在这里记任何一遍——切走之前的那条要由调用方先问 onLeave。
     */
    public void loadTrack(String trackId, String bookId, int globalOffset,
                          long[] startMs, long[] endMs) {
        this.trackId = trackId == null ? "" : trackId;
        this.bookId = bookId == null ? "" : bookId;
        this.globalOffset = Math.max(0, globalOffset);
        int n = 0;
        if (startMs != null && endMs != null) n = Math.min(startMs.length, endMs.length);
        starts = new long[n];
        ends = new long[n];
        for (int i = 0; i < n; i++) {
            starts[i] = startMs[i];
            ends[i] = endMs[i];
        }
        cur = -1;
        armed = false;
        attempt = 1;
        playId++;
        chapterEmitted = false;
        trackEmitted = false;
        tail = false;
        lastPos = -1;
        tailDate = "";
        live = true;
        touch();
    }

    /**
     * 播放中的一个位置。playing=false 只记住位置（暂停、念注、识读都不记遍）。
     * 返回本次新完成的事件，通常为空。
     */
    public List<Event> onSample(long pos, boolean playing) {
        if (!playing || ends.length == 0) {
            lastPos = pos;
            return NONE;
        }
        // 改速内部重启的 seek 可能先报一个跳回开头的假位置。那不是用户倒回去重听。
        if (lastPos >= 0 && pos + 1500 < lastPos) {
            return NONE;
        }
        if (cur < 0) {
            cur = chapterFor(pos);
            armed = pos <= tailLine(cur);
            tail = false;
            lastPos = pos;
            touch();
            return NONE;
        }
        if (armed && !chapterEmitted && pos >= ends[cur]) {
            List<Event> out = new ArrayList<>();
            addChapter(out);
            if (ends.length == 1) addTrack(out);
            int land = chapterFor(pos);
            if (land != cur && pos < ends[land]) {
                cur = land;
                attempt++;
                chapterEmitted = false;
                armed = pos <= tailLine(cur);
                tail = false;
            } else {
                armed = false;
            }
            lastPos = pos;
            touch();
            return out;
        }
        // 已经记过的章：位置自然走进后面的章，开始听新的一章（整本连播）
        if (chapterEmitted && cur + 1 < ends.length && pos >= ends[cur]) {
            cur = cur + 1;
            attempt++;
            chapterEmitted = false;
            armed = pos <= tailLine(cur);
            tail = false;
            lastPos = pos;
            touch();
            return NONE;
        }
        if (pos <= tailLine(cur)) {
            if (!chapterEmitted) {
                armed = true;
                if (tail) {
                    tail = false;
                    touch();
                }
            }
        } else if (armed && !chapterEmitted && pos < ends[cur]) {
            if (!tail) {
                tail = true;
                touch();
            }
        }
        lastPos = pos;
        return NONE;
    }

    /**
     * 整条音频自然播完（播放器回调或跟读收尾）。章已经记过就不再记；
     * 曲目一遍每装载一次只记一回。没从章身听进来的，不补章（拖到结尾再播完）。
     */
    public List<Event> onTrackEnded() {
        List<Event> out = new ArrayList<>();
        if (cur >= 0 && armed && !chapterEmitted && ends.length > 0 && posStillInside()) {
            addChapter(out);
            armed = false;
        }
        addTrack(out);
        if (!out.isEmpty()) touch();
        return out;
    }

    /**
     * 用户切到别的音频或停下。只有已经听进章尾区的章才补记——
     * 听到一半就切走的不记。单章音频（论语一篇）补上的这一章同时就是这一遍曲目。
     */
    public List<Event> onLeave() {
        List<Event> out = new ArrayList<>();
        if (cur >= 0 && armed && tail && !chapterEmitted && ends.length > 0) {
            addChapter(out);
            if (ends.length == 1) addTrack(out);
            armed = false;
            touch();
        }
        return out;
    }

    /**
     * 用户拖动进度。落在章身里才武装；直接拖过章尾不记（只有自然播到那里才记）。
     * 这一遍已经记过、又明确拖回章身，才算重听，下一遍可以再记。
     */
    public void onSeek(long pos) {
        if (ends.length == 0) {
            lastPos = pos;
            touch();
            return;
        }
        int c = chapterFor(pos);
        if (c >= 0 && pos >= ends[c]) {
            // 拖到章尾或越过章尾：不记，也别留着「听进章尾区」让松手后的采样补记
            cur = c;
            armed = false;
            tail = false;
            lastPos = pos;
            touch();
            return;
        }
        if (cur < 0) {
            cur = c;
            armed = pos <= tailLine(c);
            tail = false;
        } else if (c != cur) {
            cur = c;
            attempt++;
            chapterEmitted = false;
            armed = pos <= tailLine(c);
            tail = false;
        } else if (pos <= tailLine(c)) {
            if (chapterEmitted || (ends.length <= 1 && trackEmitted)) {
                attempt++;
                chapterEmitted = false;
                armed = true;
                tail = false;
                if (ends.length <= 1) {
                    playId++;
                    trackEmitted = false;
                }
            } else {
                armed = true;
                tail = false;
            }
        } else if (armed) {
            // 已经从章身听进来，再快进到章尾区：这一遍还在，播到章尾照记
            tail = true;
        }
        lastPos = pos;
        touch();
    }

    /**
     * 同一遍换时间轴（切声）。只把位置对到新轴上，不新开一遍，也不记。
     */
    public void retarget(long pos) {
        if (ends.length == 0) {
            lastPos = pos;
            touch();
            return;
        }
        int c = chapterFor(Math.max(0, pos));
        cur = c;
        lastPos = pos;
        if (!chapterEmitted && c >= 0 && pos < ends[c]) {
            armed = pos <= tailLine(c);
            tail = armed && pos > tailLine(c);
        }
        touch();
    }

    /** 单章回绕或整轨从头再播：明确的下一遍，从落点重新武装。 */
    public void rewindTo(int chapter, long pos) {
        if (ends.length == 0) {
            lastPos = pos;
            playId++;
            trackEmitted = false;
            live = true;
            touch();
            return;
        }
        if (chapter < 0 || chapter >= ends.length) chapter = chapterFor(pos);
        cur = chapter;
        lastPos = pos;
        attempt++;
        chapterEmitted = false;
        armed = pos <= tailLine(chapter);
        tail = false;
        if (ends.length <= 1) {
            playId++;
            trackEmitted = false;
        }
        live = true;
        touch();
    }

    /**
     * 进程重启后，这一章已经在启动时补记过。若续播还停在同一章，
     * 把剩下的一两秒听完不要再记；已经播到后面的章则不动。
     * 用户再拖回章身重听，仍可以记新的一遍。
     */
    /** 这个位置是不是还停在已补记的那一章的章尾区（续播一两秒不该再记）。 */
    public boolean positionInOpenTail(long pos, int globalChapter) {
        if (ends.length == 0 || globalChapter < 0) return false;
        int local = globalChapter - globalOffset;
        if (local < 0 || local >= ends.length) return false;
        long line = tailLine(local);
        return pos > line && pos < ends[local] + 2000;
    }

    public void sealChapter(int globalChapter) {
        if (ends.length == 0 || globalChapter < 0) return;
        int local = globalChapter - globalOffset;
        if (local < 0 || local >= ends.length) return;
        if (cur != local) return;
        chapterEmitted = true;
        armed = false;
        tail = false;
        if (ends.length <= 1) trackEmitted = true;
        touch();
    }

    /** 启动时应当补记的事件（与现场快照同一口径，方便桌面测试不碰磁盘）。 */
    public static List<Event> eventsDue(Snapshot s) {
        List<Event> out = new ArrayList<>();
        if (s == null) return out;
        if (s.chapterDue()) {
            out.add(new Event(true, s.chapter, s.chapterKey, s.bookId, s.trackId));
        }
        if (s.trackDue()) {
            out.add(new Event(false, -1, s.trackKey, s.bookId, s.trackId));
        }
        return out;
    }

    public Snapshot snapshot() {
        Snapshot s = new Snapshot();
        s.sessionId = sessionId;
        s.trackId = trackId;
        s.bookId = bookId;
        s.globalOffset = globalOffset;
        s.chapter = cur < 0 ? -1 : globalOffset + cur;
        s.attempt = attempt;
        s.playId = playId;
        s.armed = armed;
        s.tail = tail;
        s.chapterEmitted = chapterEmitted;
        s.trackEmitted = trackEmitted;
        s.single = ends.length <= 1 && ends.length > 0;
        s.pos = lastPos;
        s.date = tailDate == null ? "" : tailDate;
        s.chapterKey = (armed && !chapterEmitted) ? chapterKey() : "";
        s.trackKey = trackKey() == null ? "" : trackKey();
        return s;
    }

    private boolean posStillInside() {
        // 自然结束时位置可能停在章尾之前（采样没越过去），仍算这一章
        return cur >= 0 && cur < ends.length;
    }

    private long tailLine(int i) {
        return ends[i] - thresholdFor(starts[i], ends[i]);
    }

    private int chapterFor(long pos) {
        for (int i = 0; i < ends.length; i++) if (pos < ends[i]) return i;
        return ends.length - 1;
    }

    private String chapterKey() {
        if (bookId.length() == 0 || cur < 0) return "";
        return "auto|" + sessionId + "|" + bookId + "|" + (globalOffset + cur) + "|a" + attempt;
    }

    private String trackKey() {
        if (trackId.length() == 0) return "";
        return "auto|" + sessionId + "|" + trackId + "|p" + playId;
    }

    private void addChapter(List<Event> out) {
        String key = chapterKey();
        if (key.length() == 0 || chapterEmitted) return;
        chapterEmitted = true;
        tail = true;
        out.add(new Event(true, globalOffset + cur, key, bookId, trackId));
    }

    private void addTrack(List<Event> out) {
        String key = trackKey();
        if (key.length() == 0 || trackEmitted) return;
        trackEmitted = true;
        out.add(new Event(false, -1, key, bookId, trackId));
    }

    private void touch() { revision++; }

    /** 进入章尾区时盖上「这一遍属于哪一天」（只盖一次，跨日补记仍算听的那天）。 */
    public void stampDate(String date) {
        if (tail && date != null && date.length() > 0
                && (tailDate == null || tailDate.length() == 0)) {
            tailDate = date;
            touch();
        }
    }

    public boolean isArmed() { return armed; }
    public boolean inTail() { return tail; }
    public boolean chapterAlreadyCounted() { return chapterEmitted; }
    public boolean trackAlreadyCounted() { return trackEmitted; }
}
