package com.ti.songdu;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 遍数的唯一记账入口。自动听完（普通播完、跟读收尾、单章回绕、切章时已听进章尾）
 * 和阅读页「已读到末尾」都只走这里。
 *
 * 同一遍的 dedupKey 只落一次：先写入两本既有账（曲目账、分章账——屏幕现在还读它们），
 * 再把键写进 pass_events.json。键文件只用来防重复，不另算一套今日/本周数字。
 * pass_session.json 不是账，只记「这一遍听到哪、记过没有」，进程被杀掉后用来补记。
 */
public final class PassCounter {

    private static PassCounter instance;

    private final Context app;
    private final File eventFile;
    private final File sessionFile;
    private final Set<String> seen = new HashSet<>();
    private String sessionId = UUID.randomUUID().toString();
    private String sealTrack = "";
    private int sealChapter = -1;
    private boolean sealLoaded;

    public static synchronized PassCounter get(Context ctx) {
        if (instance == null) instance = new PassCounter(ctx.getApplicationContext());
        return instance;
    }

    private PassCounter(Context app) {
        this.app = app;
        File dir = app.getFilesDir();
        eventFile = new File(dir, "pass_events.json");
        sessionFile = new File(dir, "pass_session.json");
        loadSeen();
        PassMonitor.Snapshot s = readSession();
        if (s != null && s.sessionId != null && s.sessionId.length() > 0) {
            sessionId = s.sessionId;
        }
    }

    public String sessionId() { return sessionId; }

    /** 把播放监测报出的完成逐条落账。已经记过的键直接跳过。 */
    public int commitAll(List<PassMonitor.Event> events) {
        if (events == null || events.isEmpty()) return 0;
        int n = 0;
        for (PassMonitor.Event e : events) {
            if (commit(e, LocalDate.now())) n++;
        }
        return n;
    }

    public boolean commit(PassMonitor.Event e, LocalDate day) {
        if (e == null || e.dedupKey == null || e.dedupKey.length() == 0) return false;
        if (!seen.add(e.dedupKey)) return false;
        if (e.chapter) {
            if (e.bookId != null && e.chapterIndex >= 0) writeChapter(e.bookId, e.chapterIndex, day);
        } else {
            Track t = findTrack(e.trackId);
            if (t != null) writeTrack(t, day);
        }
        appendEvent(e, day);
        return true;
    }

    /**
     * 阅读页「已读到末尾」。每一次点按都是一次真的完成（键用新的随机号，不与听读合并）。
     * 论语一篇同时是一条音频和一章，两本账一起记；整本一条音频的书只记曲目账。
     */
    public void recordManualFinish(Track t, String bookId, int chapterIndex) {
        if (t == null) return;
        String id = UUID.randomUUID().toString();
        LocalDate day = LocalDate.now();
        PassMonitor.Event trackEv = new PassMonitor.Event(
                false, -1, "manual|" + id + "|track", bookId, t.id);
        // 手动完成不走 seen 去重（每次点按都算），但仍从这里写账，避免再开一条旁路
        writeTrack(t, day);
        appendEvent(trackEv, day);
        if (bookId != null && chapterIndex >= 0) {
            PassMonitor.Event chEv = new PassMonitor.Event(
                    true, chapterIndex, "manual|" + id + "|chapter", bookId, t.id);
            writeChapter(bookId, chapterIndex, day);
            appendEvent(chEv, day);
        }
    }

    /**
     * 上次听到章尾区却没来得及落账（进程被系统杀掉）时补记一次。
     * 重复调用无效果。补记之后记住是哪一章，续播还停在章尾就不要再记。
     */
    public boolean settleSavedSession() {
        PassMonitor.Snapshot s = readSession();
        List<PassMonitor.Event> due = PassMonitor.eventsDue(s);
        if (s == null || due.isEmpty()) {
            loadSealFrom(s);
            return false;
        }
        LocalDate day = LocalDate.now();
        if (s.date != null && s.date.length() > 0) {
            try { day = LocalDate.parse(s.date); } catch (Exception ignored) {}
        }
        boolean any = false;
        for (PassMonitor.Event e : due) {
            if (commit(e, day)) any = true;
        }
        s.chapterEmitted = true;
        s.armed = false;
        s.tail = false;
        s.chapterKey = "";
        if (s.trackDue() || s.single) s.trackEmitted = true;
        sealTrack = s.trackId == null ? "" : s.trackId;
        sealChapter = s.chapter;
        s.sealedTrack = sealTrack;
        s.sealedChapter = sealChapter;
        writeSession(s);
        sealLoaded = true;
        return any;
    }

    public String peekSealedTrack() {
        ensureSeal();
        return sealTrack == null ? "" : sealTrack;
    }

    public int peekSealedChapter() {
        ensureSeal();
        return sealChapter;
    }

    public void clearSeal() {
        ensureSeal();
        if ((sealTrack == null || sealTrack.length() == 0) && sealChapter < 0) return;
        sealTrack = "";
        sealChapter = -1;
        PassMonitor.Snapshot s = readSession();
        if (s != null) {
            s.sealedTrack = "";
            s.sealedChapter = -1;
            writeSession(s);
        }
    }

    public void saveSnapshot(PassMonitor.Snapshot s) {
        if (s == null) return;
        ensureSeal();
        if (s.sessionId == null || s.sessionId.length() == 0) s.sessionId = sessionId;
        if (sealTrack != null && sealTrack.length() > 0) {
            s.sealedTrack = sealTrack;
            s.sealedChapter = sealChapter;
        }
        writeSession(s);
    }

    private void loadSealFrom(PassMonitor.Snapshot s) {
        if (sealLoaded) return;
        sealLoaded = true;
        if (s == null) return;
        sealTrack = s.sealedTrack == null ? "" : s.sealedTrack;
        sealChapter = s.sealedChapter;
    }

    private void ensureSeal() {
        if (sealLoaded) return;
        loadSealFrom(readSession());
    }

    private void writeChapter(String bookId, int idx, LocalDate day) {
        ChapterStatsStore.get(app).addAuto(bookId, idx, day, 1);
    }

    private void writeTrack(Track t, LocalDate day) {
        Store.get(app).increment(t, day);
    }

    private Track findTrack(String id) {
        if (id == null || id.length() == 0) return null;
        Store store = Store.get(app);
        for (int i = 0; i < store.tracks.size(); i++) {
            Track t = store.tracks.get(i);
            if (t != null && id.equals(t.id)) return t;
        }
        return null;
    }

    private void loadSeen() {
        JSONObject root = readJson(eventFile);
        if (root == null) return;
        JSONArray arr = root.optJSONArray("events");
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e == null) continue;
            String k = e.optString("dedupKey", "");
            if (k.length() > 0) seen.add(k);
        }
    }

    private void appendEvent(PassMonitor.Event e, LocalDate day) {
        JSONObject root = readJson(eventFile);
        if (root == null) root = new JSONObject();
        JSONArray arr = root.optJSONArray("events");
        if (arr == null) {
            arr = new JSONArray();
            try { root.put("events", arr); } catch (Exception ignored) { return; }
        }
        try {
            JSONObject o = new JSONObject();
            o.put("dedupKey", e.dedupKey);
            o.put("date", day.toString());
            o.put("kind", e.chapter ? "chapter" : "track");
            o.put("bookId", e.bookId == null ? "" : e.bookId);
            o.put("chapter", e.chapterIndex);
            o.put("trackId", e.trackId == null ? "" : e.trackId);
            arr.put(o);
        } catch (Exception ignored) {
            return;
        }
        writeJson(eventFile, root);
    }

    private PassMonitor.Snapshot readSession() {
        JSONObject o = readJson(sessionFile);
        if (o == null) return null;
        PassMonitor.Snapshot s = new PassMonitor.Snapshot();
        s.sessionId = o.optString("sessionId", "");
        s.trackId = o.optString("trackId", "");
        s.bookId = o.optString("bookId", "");
        s.globalOffset = o.optInt("globalOffset", 0);
        s.chapter = o.optInt("chapter", -1);
        s.attempt = o.optInt("attempt", 1);
        s.playId = o.optInt("playId", 1);
        s.armed = o.optBoolean("armed", false);
        s.tail = o.optBoolean("tail", false);
        s.chapterEmitted = o.optBoolean("chapterEmitted", false);
        s.trackEmitted = o.optBoolean("trackEmitted", false);
        s.single = o.optBoolean("single", false);
        s.pos = o.optLong("pos", -1);
        s.date = o.optString("date", "");
        s.chapterKey = o.optString("chapterKey", "");
        s.trackKey = o.optString("trackKey", "");
        s.sealedTrack = o.optString("sealedTrack", "");
        s.sealedChapter = o.optInt("sealedChapter", -1);
        return s;
    }

    private void writeSession(PassMonitor.Snapshot s) {
        JSONObject o = new JSONObject();
        try {
            o.put("sessionId", s.sessionId == null ? "" : s.sessionId);
            o.put("trackId", s.trackId == null ? "" : s.trackId);
            o.put("bookId", s.bookId == null ? "" : s.bookId);
            o.put("globalOffset", s.globalOffset);
            o.put("chapter", s.chapter);
            o.put("attempt", s.attempt);
            o.put("playId", s.playId);
            o.put("armed", s.armed);
            o.put("tail", s.tail);
            o.put("chapterEmitted", s.chapterEmitted);
            o.put("trackEmitted", s.trackEmitted);
            o.put("single", s.single);
            o.put("pos", s.pos);
            o.put("date", s.date == null ? "" : s.date);
            o.put("chapterKey", s.chapterKey == null ? "" : s.chapterKey);
            o.put("trackKey", s.trackKey == null ? "" : s.trackKey);
            o.put("sealedTrack", s.sealedTrack == null ? "" : s.sealedTrack);
            o.put("sealedChapter", s.sealedChapter);
        } catch (Exception ignored) {
            return;
        }
        writeJson(sessionFile, o);
    }

    private JSONObject readJson(File file) {
        if (file == null || !file.exists()) return null;
        try {
            FileInputStream in = new FileInputStream(file);
            byte[] buf = new byte[(int) file.length()];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            in.close();
            if (off <= 0) return null;
            return new JSONObject(new String(buf, 0, off, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return null;
        }
    }

    private void writeJson(File file, JSONObject o) {
        try {
            byte[] bytes = o.toString().getBytes(StandardCharsets.UTF_8);
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(bytes);
            out.close();
            if (!tmp.renameTo(file)) {
                FileOutputStream out2 = new FileOutputStream(file);
                out2.write(bytes);
                out2.close();
                tmp.delete();
            }
        } catch (Exception ignored) {
        }
    }
}
