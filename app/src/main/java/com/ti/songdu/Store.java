package com.ti.songdu;

import android.content.Context;
import android.media.MediaMetadataRetriever;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 全部数据用 JSON 文件存内部存储：
 *  tracks.json  —— 曲目列表（顺序即数组顺序）
 *                   v1.2 起每项多三个字段：src（来源文件名）、base（此前已读遍数偏移）
 *                   v1.3 起多 kind 字段：content（content:// URI，缺省值）/ file（内部存储文件，
 *                   uri 字段存绝对路径，供预置完整版音频用）。v1.2 及更早文件无这些字段，
 *                   读取时 optXxx 兜底自动迁移，不丢数据
 *  records.json —— { "yyyy-MM-dd": { trackId: {"title":..,"dur":..,"count":..} } }
 *                   记录按 trackId 关联，与显示名称无关，改名不影响历史
 *  settings.json—— {"speed":1.0, "copyDate":true, "copyPeriods":["today","week","month","total"],
 *                   "presetsDone":true（v1.3 预置音频已释放标记，只释放一次）}
 *                   v1.5 起多 group 字段（曲目分组，如论语二十篇同属一组）与
 *                   settings 的 presetsInstalled 数组（已安装过的预置 id 集合，增量补装用）
 */
public class Store {
    private final Context ctx;
    public final List<Track> tracks = new ArrayList<>();
    public JSONObject records = new JSONObject();
    public float speed = 1.0f;

    // ---------- 复制进度设置 ----------
    /** 时段键的固定顺序与中文标签（复制文本、设置页共用） */
    public static final String[] PERIOD_KEYS = {
            "today", "yesterday", "week", "lastweek", "month", "lastmonth", "year", "total"};
    public static final String[] PERIOD_LABELS = {
            "今日", "昨日", "本周", "上周", "本月", "上月", "本年", "总计"};
    public boolean copyIncludeDate = true;
    /** v1.5：复制群消息的自定义标题句（复制文本第一行；trim 后为空则不加）。三个全角下划线是姓名占位 */
    public String copyTitle = "＿＿＿读经的记录";
    /** v1.5：打卡天数——锚点值与锚点日期（yyyy-MM-dd）。手动设置语义＝「以今天为准，
     *  打卡天数就是这个数」；有效天数显示时现算（见 effectiveCheckinDays），不逐日写回。
     *  checkinAnchor 为空＝从未设置过，有效天数恒为 0 */
    public int checkinCount = 0;
    public String checkinAnchor = "";
    /** v1.5：是否在日期行后拼「-打卡第X天」后缀（设置页开关，默认开；仍需日期行开且有效天数 > 0） */
    public boolean checkinShow = true;
    public final java.util.Set<String> copyPeriods = new java.util.LinkedHashSet<>();
    /** v1.3：预置完整版音频是否已释放到内部存储并建过曲目（只允许做一次，见 installPresetsIfNeeded） */
    public boolean presetsDone = false;
    /** v1.5：已安装过的预置曲目 id 集合（持久化在 settings.json）。增量补装只看这个集合：
     *  某 id 一旦装过就永不再装，用户移除后不会被加回；老用户从 v1.3/v1.4 升级时
     *  集合为空但 presetsDone=true，则把大学/中庸两个 id 视为已装、只补论语二十篇 */
    public final java.util.List<String> presetsInstalled = new ArrayList<>();

    public Store(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        load();
    }

    private static Store instance;

    public static synchronized Store get(Context ctx) {
        if (instance == null) instance = new Store(ctx);
        return instance;
    }

    // ---------- 基础读写 ----------
    private String readFile(String name) {
        try {
            File f = new File(ctx.getFilesDir(), name);
            if (!f.exists()) return null;
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int n = in.read(buf);
            in.close();
            return n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void writeFile(String name, String content) {
        try {
            FileOutputStream out = new FileOutputStream(new File(ctx.getFilesDir(), name));
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.close();
        } catch (Exception ignored) {
        }
    }

    public void load() {
        tracks.clear();
        try {
            String s = readFile("tracks.json");
            if (s != null) {
                JSONArray arr = new JSONArray(s);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String title = o.getString("title");
                    Track t = new Track(
                            o.getString("id"),
                            o.getString("uri"),
                            title,
                            // v1.2 迁移：旧 tracks.json 无 src/base 字段 → 来源名默认=标题、偏移默认 0
                            o.optString("src", title),
                            o.optLong("dur", 0),
                            o.optLong("pos", 0),
                            o.optInt("base", 0),
                            // v1.3 迁移：旧文件无 kind 字段 → 默认 content（content:// URI）
                            o.optString("kind", Track.KIND_CONTENT));
                    // v1.5 迁移：旧文件无 group 字段 → null（不分组）
                    if (o.has("group") && !o.isNull("group")) t.group = o.getString("group");
                    tracks.add(t);
                }
            }
        } catch (Exception ignored) {
        }
        // v1.5 改名迁移：预置曲目若仍是旧默认名（《大学》（完整版）/《中庸》（完整版））
        // 自动换成新名《大学》/《中庸》；用户自己改过的任何名字一律不动
        boolean renamed = false;
        for (Track t : tracks) {
            if ("preset-daxue-full".equals(t.id) && "《大学》（完整版）".equals(t.title)) {
                t.title = "《大学》"; renamed = true;
            } else if ("preset-zhongyong-full".equals(t.id) && "《中庸》（完整版）".equals(t.title)) {
                t.title = "《中庸》"; renamed = true;
            }
        }
        if (renamed) saveTracks();
        try {
            String s = readFile("records.json");
            if (s != null) records = new JSONObject(s);
        } catch (Exception ignored) {
        }
        try {
            String s = readFile("settings.json");
            copyPeriods.clear();
            if (s != null) {
                JSONObject o = new JSONObject(s);
                speed = (float) o.optDouble("speed", 1.0);
                copyIncludeDate = o.optBoolean("copyDate", true);
                // v1.5 迁移：旧 settings 无 copyTitle → 用默认标题句
                copyTitle = o.optString("copyTitle", "＿＿＿读经的记录");
                checkinCount = o.optInt("checkinCount", 0);
                checkinAnchor = o.optString("checkinAnchor", "");
                checkinShow = o.optBoolean("checkinShow", true);
                presetsDone = o.optBoolean("presetsDone", false);
                presetsInstalled.clear();
                JSONArray pi = o.optJSONArray("presetsInstalled");
                if (pi != null) {
                    for (int i = 0; i < pi.length(); i++) presetsInstalled.add(pi.getString(i));
                } else if (presetsDone) {
                    // v1.3/v1.4 老用户：大学/中庸当年已装过（标记在）→ 视为已装，只补新预置
                    presetsInstalled.add("preset-daxue-full");
                    presetsInstalled.add("preset-zhongyong-full");
                }
                JSONArray p = o.optJSONArray("copyPeriods");
                if (p != null) {
                    for (int i = 0; i < p.length(); i++) copyPeriods.add(p.getString(i));
                }
            }
            if (copyPeriods.isEmpty()) {
                // 默认勾选：今天、本周、本月、自始至今
                copyPeriods.add("today");
                copyPeriods.add("week");
                copyPeriods.add("month");
                copyPeriods.add("total");
            }
        } catch (Exception ignored) {
        }
    }

    public synchronized void saveTracks() {
        try {
            JSONArray arr = new JSONArray();
            for (Track t : tracks) {
                JSONObject o = new JSONObject();
                o.put("id", t.id);
                o.put("uri", t.uri);
                o.put("title", t.title);
                o.put("src", t.sourceName);
                o.put("base", t.baseTotal);
                o.put("kind", t.kind);
                if (t.group != null) o.put("group", t.group);
                o.put("dur", t.durationMs);
                o.put("pos", t.positionMs);
                arr.put(o);
            }
            writeFile("tracks.json", arr.toString());
        } catch (Exception ignored) {
        }
    }

    public synchronized void saveRecords() {
        writeFile("records.json", records.toString());
    }

    public synchronized void saveSettings() {
        try {
            JSONObject o = new JSONObject();
            o.put("speed", speed);
            o.put("copyDate", copyIncludeDate);
            o.put("copyTitle", copyTitle);
            o.put("checkinCount", checkinCount);
            o.put("checkinAnchor", checkinAnchor);
            o.put("checkinShow", checkinShow);
            o.put("presetsDone", presetsDone);
            JSONArray piArr = new JSONArray();
            for (String id : presetsInstalled) piArr.put(id);
            o.put("presetsInstalled", piArr);
            JSONArray p = new JSONArray();
            for (String k : PERIOD_KEYS) if (copyPeriods.contains(k)) p.put(k);
            o.put("copyPeriods", p);
            writeFile("settings.json", o.toString());
        } catch (Exception ignored) {
        }
    }

    public Track findTrack(String id) {
        if (id == null) return null;
        for (Track t : tracks) if (t.id.equals(id)) return t;
        return null;
    }

    // ---------- 预置音频（v1.3 完整版 / v1.5 论语按篇） ----------
    /** 预置曲目规格：{稳定 id, 显示名称, res/raw 资源, 内部存储文件名, 分组(可为 null)} */
    private static final Object[][] PRESETS = {
            {"preset-daxue-full", "《大学》", R.raw.preset_daxue_full, "daxue-full.mp3", null},
            {"preset-zhongyong-full", "《中庸》", R.raw.preset_zhongyong_full, "zhongyong-full.mp3", null},
            {"preset-lunyu-01", "學而第一", R.raw.lunyu_01, "lunyu-01.mp3", "论语"},
            {"preset-lunyu-02", "為政第二", R.raw.lunyu_02, "lunyu-02.mp3", "论语"},
            {"preset-lunyu-03", "八佾第三", R.raw.lunyu_03, "lunyu-03.mp3", "论语"},
            {"preset-lunyu-04", "里仁第四", R.raw.lunyu_04, "lunyu-04.mp3", "论语"},
            {"preset-lunyu-05", "公冶長第五", R.raw.lunyu_05, "lunyu-05.mp3", "论语"},
            {"preset-lunyu-06", "雍也第六", R.raw.lunyu_06, "lunyu-06.mp3", "论语"},
            {"preset-lunyu-07", "述而第七", R.raw.lunyu_07, "lunyu-07.mp3", "论语"},
            {"preset-lunyu-08", "泰伯第八", R.raw.lunyu_08, "lunyu-08.mp3", "论语"},
            {"preset-lunyu-09", "子罕第九", R.raw.lunyu_09, "lunyu-09.mp3", "论语"},
            {"preset-lunyu-10", "鄉黨第十", R.raw.lunyu_10, "lunyu-10.mp3", "论语"},
            {"preset-lunyu-11", "先進第十一", R.raw.lunyu_11, "lunyu-11.mp3", "论语"},
            {"preset-lunyu-12", "顏淵第十二", R.raw.lunyu_12, "lunyu-12.mp3", "论语"},
            {"preset-lunyu-13", "子路第十三", R.raw.lunyu_13, "lunyu-13.mp3", "论语"},
            {"preset-lunyu-14", "憲問第十四", R.raw.lunyu_14, "lunyu-14.mp3", "论语"},
            {"preset-lunyu-15", "衛靈公第十五", R.raw.lunyu_15, "lunyu-15.mp3", "论语"},
            {"preset-lunyu-16", "季氏第十六", R.raw.lunyu_16, "lunyu-16.mp3", "论语"},
            {"preset-lunyu-17", "陽貨第十七", R.raw.lunyu_17, "lunyu-17.mp3", "论语"},
            {"preset-lunyu-18", "微子第十八", R.raw.lunyu_18, "lunyu-18.mp3", "论语"},
            {"preset-lunyu-19", "子張第十九", R.raw.lunyu_19, "lunyu-19.mp3", "论语"},
            {"preset-lunyu-20", "堯曰第二十", R.raw.lunyu_20, "lunyu-20.mp3", "论语"}
    };

    /**
     * 预置音频的释放与建目（v1.5 改为按 id 增量补装，替代 v1.3 的 presetsDone 一次性逻辑）：
     *  - 待装集合 = PRESETS 中 id 不在 presetsInstalled 里的条目；装过的 id 永不再装，
     *    所以用户移除过的预置曲目不会被加回（包括大学/中庸），老用户升级只补新出现的论语二十篇；
     *  - 待装条目整体成块插入：位置在「现有最后一个预置曲目」之后（老用户即中庸之后），
     *    列表里一个预置都没有时插到最前；块内顺序即 PRESETS 顺序（大学、中庸、论语一至二十篇）；
     *  - 音频文件释放到内部存储 files/presets/，已存在且非空则不重复释放；
     *  - 额外再按稳定 id 查一遍，列表中已存在同 id 时只登记已装、不重复插入（双保险）。
     * 预置曲目 kind=file、来源记「内置音频」，与普通曲目完全同权。
     */
    public synchronized void installPresetsIfNeeded() {
        java.util.List<Object[]> todo = new ArrayList<>();
        for (Object[] spec : PRESETS) {
            if (!presetsInstalled.contains((String) spec[0])) todo.add(spec);
        }
        if (todo.isEmpty()) return;
        File dir = new File(ctx.getFilesDir(), "presets");
        if (!dir.exists()) dir.mkdirs();
        // 插入点：现有最后一个预置曲目之后；没有预置曲目则在最前
        int insertAt = 0;
        for (Object[] spec : PRESETS) {
            Track t = findTrack((String) spec[0]);
            if (t != null) insertAt = Math.max(insertAt, tracks.indexOf(t) + 1);
        }
        boolean changed = false;
        for (Object[] spec : todo) {
            String id = (String) spec[0];
            String title = (String) spec[1];
            int rawRes = (Integer) spec[2];
            String fileName = (String) spec[3];
            String group = (String) spec[4];
            File dest = new File(dir, fileName);
            try {
                if (!dest.exists() || dest.length() == 0) {
                    InputStream in = ctx.getResources().openRawResource(rawRes);
                    FileOutputStream out = new FileOutputStream(dest);
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    out.close();
                    in.close();
                }
            } catch (Exception ignored) {
            }
            if (findTrack(id) == null && dest.exists() && dest.length() > 0) {
                long dur = fileDurationMs(dest.getAbsolutePath());
                Track t = new Track(
                        id, dest.getAbsolutePath(), title, "内置音频",
                        dur, 0, 0, Track.KIND_FILE);
                t.group = group;
                tracks.add(insertAt++, t);
                changed = true;
            }
            if (!presetsInstalled.contains(id)) presetsInstalled.add(id);
        }
        presetsDone = true; // 旧标记继续维护（已无实际作用，仅保持 settings 兼容）
        saveSettings();
        if (changed) saveTracks();
    }

    /** 本地文件的时长（预置音频释放后读取；content:// 曲目的时长读取在 MainActivity 里） */
    private long fileDurationMs(String path) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(path);
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) return Long.parseLong(d);
        } catch (Exception ignored) {
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    // ---------- 计数 ----------
    public static String dateKey(LocalDate d) {
        return d.toString(); // yyyy-MM-dd
    }

    /** 某音频在某天完整播完一遍：+1，并快照标题/时长（音频删掉后历史仍可读） */
    public synchronized void increment(Track t, LocalDate day) {
        try {
            String key = dateKey(day);
            JSONObject dayObj = records.optJSONObject(key);
            if (dayObj == null) {
                dayObj = new JSONObject();
                records.put(key, dayObj);
            }
            JSONObject e = dayObj.optJSONObject(t.id);
            if (e == null) e = new JSONObject();
            e.put("title", t.title);
            e.put("dur", t.durationMs);
            e.put("count", e.optInt("count", 0) + 1);
            dayObj.put(t.id, e);
            saveRecords();
        } catch (Exception ignored) {
        }
    }

    /** 把某音频某天的遍数直接设为指定值（手动 +/− 与设置页修正用）；设为 0 则删掉该日条目 */
    public synchronized void setTrackCountOn(Track t, LocalDate day, int count) {
        try {
            String key = dateKey(day);
            JSONObject dayObj = records.optJSONObject(key);
            if (count <= 0) {
                if (dayObj != null) {
                    dayObj.remove(t.id);
                    if (dayObj.length() == 0) records.remove(key);
                }
            } else {
                if (dayObj == null) {
                    dayObj = new JSONObject();
                    records.put(key, dayObj);
                }
                JSONObject e = dayObj.optJSONObject(t.id);
                if (e == null) e = new JSONObject();
                e.put("title", t.title);
                e.put("dur", t.durationMs);
                e.put("count", count);
                dayObj.put(t.id, e);
            }
            saveRecords();
        } catch (Exception ignored) {
        }
    }

    /** 清空某音频的全部按天记录（该音频在各日期的条目全删，空日期一并删） */
    public synchronized void resetTrackRecords(Track t) {
        try {
            Iterator<String> it = records.keys();
            List<String> emptyDays = new ArrayList<>();
            while (it.hasNext()) {
                String key = it.next();
                JSONObject dayObj = records.optJSONObject(key);
                if (dayObj != null && dayObj.has(t.id)) {
                    dayObj.remove(t.id);
                    if (dayObj.length() == 0) emptyDays.add(key);
                }
            }
            for (String k : emptyDays) records.remove(k);
            saveRecords();
        } catch (Exception ignored) {
        }
    }

    public int trackCountOn(String trackId, LocalDate day) {
        JSONObject dayObj = records.optJSONObject(dateKey(day));
        if (dayObj == null) return 0;
        JSONObject e = dayObj.optJSONObject(trackId);
        return e == null ? 0 : e.optInt("count", 0);
    }

    /** 某音频在日期区间 [from, to] 内的总遍数 */
    public int trackCountBetween(String trackId, LocalDate from, LocalDate to) {
        int sum = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            sum += trackCountOn(trackId, d);
        }
        return sum;
    }

    /** 全部音频在某天的总遍数 */
    public int totalCountOn(LocalDate day) {
        JSONObject dayObj = records.optJSONObject(dateKey(day));
        if (dayObj == null) return 0;
        int sum = 0;
        Iterator<String> it = dayObj.keys();
        while (it.hasNext()) {
            JSONObject e = dayObj.optJSONObject(it.next());
            if (e != null) sum += e.optInt("count", 0);
        }
        return sum;
    }

    /**
     * v1.5 打卡天数（有效值，现算不写回）＝锚点值
     *   ＋（锚点日期之后、到今天为止，任一曲目当天遍数 > 0 的天数）。
     * 锚点就是今天时即锚点值本身（哪怕今天已读也不 +1）；未设置（锚点为空）时为锚点值（默认 0，
     * 但此后读过的日子照常累计）。今天若已读且晚于锚点，也计入那 +N。
     */
    public int effectiveCheckinDays() {
        if (checkinAnchor == null || checkinAnchor.isEmpty()) {
            // 从未手动设置过：仍按「有记录的日子」累计（锚点视为远古，锚点值默认 0）
            int extra = 0;
            Iterator<String> keys = records.keys();
            while (keys.hasNext()) {
                try {
                    LocalDate d = LocalDate.parse(keys.next());
                    if (!d.isAfter(today()) && totalCountOn(d) > 0) extra++;
                } catch (Exception ignored) { }
            }
            return checkinCount + extra;
        }
        LocalDate anchor;
        try {
            anchor = LocalDate.parse(checkinAnchor);
        } catch (Exception e) {
            return checkinCount;
        }
        LocalDate t = today();
        if (!anchor.isBefore(t)) return checkinCount; // 锚点是今天（或异常地在未来）
        int extra = 0;
        for (LocalDate d = anchor.plusDays(1); !d.isAfter(t); d = d.plusDays(1)) {
            if (totalCountOn(d) > 0) extra++;
        }
        return checkinCount + extra;
    }

    public int totalCountBetween(LocalDate from, LocalDate to) {
        int sum = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) sum += totalCountOn(d);
        return sum;
    }

    // ---------- 日期口径 ----------
    public static LocalDate today() {
        return LocalDate.now();
    }

    /** 本周一 */
    public static LocalDate thisMonday() {
        return today().with(DayOfWeek.MONDAY);
    }

    /** 上周一 / 上周日（上一自然周） */
    public static LocalDate lastMonday() {
        return thisMonday().minusWeeks(1);
    }

    public static LocalDate lastSunday() {
        return thisMonday().minusDays(1);
    }

    public static LocalDate monthFirst() {
        return today().withDayOfMonth(1);
    }

    // ---------- 每张卡上的五个数字：今日 / 昨日 / 本周 / 上周 / 本月 ----------
    public int trackToday(Track t) {
        return trackCountOn(t.id, today());
    }

    public int trackYesterday(Track t) {
        return trackCountOn(t.id, today().minusDays(1));
    }

    /** 本周：本周一 00:00 至今天合计 */
    public int trackThisWeek(Track t) {
        return trackCountBetween(t.id, thisMonday(), today());
    }

    public int trackLastWeek(Track t) {
        return trackCountBetween(t.id, lastMonday(), lastSunday());
    }

    public int trackThisMonth(Track t) {
        return trackCountBetween(t.id, monthFirst(), today());
    }

    /** 上月：上月 1 日至上月最后一日 */
    public int trackLastMonth(Track t) {
        LocalDate first = monthFirst().minusMonths(1);
        return trackCountBetween(t.id, first, monthFirst().minusDays(1));
    }

    /** 本年：本年 1 月 1 日至今天 */
    public int trackThisYear(Track t) {
        return trackCountBetween(t.id, today().withDayOfYear(1), today());
    }

    /** 本软件内全部按天记录合计（不含 baseTotal 偏移） */
    public int trackRecordedTotal(Track t) {
        int sum = 0;
        Iterator<String> it = records.keys();
        while (it.hasNext()) {
            JSONObject dayObj = records.optJSONObject(it.next());
            if (dayObj == null) continue;
            JSONObject e = dayObj.optJSONObject(t.id);
            if (e != null) sum += e.optInt("count", 0);
        }
        return sum;
    }

    /** 自始至今总计 = 按天记录合计 + 此前已读偏移 baseTotal（口径：卡片、复制文本一致） */
    public int trackTotal(Track t) {
        return trackRecordedTotal(t) + t.baseTotal;
    }

    /** 复制进度：按时段键取某音频的遍数 */
    public int trackCountForPeriod(Track t, String periodKey) {
        switch (periodKey) {
            case "today": return trackToday(t);
            case "yesterday": return trackYesterday(t);
            case "week": return trackThisWeek(t);
            case "lastweek": return trackLastWeek(t);
            case "month": return trackThisMonth(t);
            case "lastmonth": return trackLastMonth(t);
            case "year": return trackThisYear(t);
            case "total": return trackTotal(t);
            default: return 0;
        }
    }

    /**
     * 生成「复制群消息」文本（v1.5 改为打卡模板句式，取代旧时段多选格式）：
     *  标题行（标题句非空才有）→ 日期行（开关开才有；打卡显示开且有效天数 > 0 时
     *  拼「-打卡第X天」后缀）→ 正文按首页列表顺序逐条：
     *   普通曲目一行：今日=0 →「读《名》 共 Y遍」；今日>0 →「读《名》 今天 X 遍，共 Y遍」
     *   （Y＝总计＝records 累计＋baseTotal；名已带《》时不重复包书名号）；
     *   分组（如论语）：全组总计皆 0 → 单行「读《组名》 共 0遍」；否则先一行
     *   「读《组名》」，其下总计 > 0 的篇按篇序编号「1.篇名 …」（行文同普通规则，
     *   今日=0 时为「N.篇名 Y遍」）。
     * 这是唯一的生成入口：复制弹窗预览与实际复制都调这里，弹窗里的临时编辑不回写设置。
     */
    public String buildProgressText() {
        StringBuilder sb = new StringBuilder();
        String ttl = copyTitle == null ? "" : copyTitle.trim();
        if (!ttl.isEmpty()) sb.append(ttl);
        if (copyIncludeDate) {
            if (sb.length() > 0) sb.append("\n");
            LocalDate d = today();
            sb.append(d.getYear()).append("年").append(d.getMonthValue())
                    .append("月").append(d.getDayOfMonth()).append("日");
            if (checkinShow) {
                int cd = effectiveCheckinDays();
                if (cd > 0) sb.append("-打卡第").append(cd).append("天");
            }
        }
        int i = 0;
        while (i < tracks.size()) {
            Track t = tracks.get(i);
            if (t.group == null) {
                appendCopyLine(sb, normalTrackLine(t));
                i++;
            } else {
                int j = i + 1;
                while (j < tracks.size() && t.group.equals(tracks.get(j).group)) j++;
                boolean allZero = true;
                for (int k = i; k < j; k++) {
                    if (trackTotal(tracks.get(k)) > 0) { allZero = false; break; }
                }
                if (allZero) {
                    appendCopyLine(sb, "读《" + t.group + "》 共 0遍");
                } else {
                    appendCopyLine(sb, "读《" + t.group + "》");
                    int seq = 0;
                    for (int k = i; k < j; k++) {
                        Track pt = tracks.get(k);
                        int tot = trackTotal(pt);
                        if (tot <= 0) continue;
                        seq++;
                        int td = trackToday(pt);
                        appendCopyLine(sb, seq + "." + pianDisplayName(pt)
                                + (td > 0 ? " 今天 " + td + " 遍，共 " + tot + "遍"
                                          : " " + tot + "遍"));
                    }
                }
                i = j;
            }
        }
        return sb.toString();
    }

    private static void appendCopyLine(StringBuilder sb, String line) {
        if (sb.length() > 0) sb.append("\n");
        sb.append(line);
    }

    /** 普通曲目行：读《名》 共 Y遍 ／ 读《名》 今天 X 遍，共 Y遍 */
    private String normalTrackLine(Track t) {
        int tot = trackTotal(t);
        int td = trackToday(t);
        String name = t.title != null && t.title.startsWith("《") ? t.title : "《" + t.title + "》";
        return "读" + name + (td > 0 ? " 今天 " + td + " 遍，共 " + tot + "遍" : " 共 " + tot + "遍");
    }

    /** 组内篇行用的篇名：篇目显示名本身（v1.5 起预置篇名即篇名）；兼容旧式「《论语》·篇名」前缀则剥掉 */
    private static String pianDisplayName(Track t) {
        String title = t.title == null ? "" : t.title;
        int dot = title.indexOf('·');
        if ((title.startsWith("《论语》") || title.startsWith("《論語》")) && dot > 0) {
            return title.substring(dot + 1);
        }
        return title;
    }

    /** 单行统计串：卡面小字行与下拉通知/锁屏媒体信息共用，保证两处数字一致 */
    public String statsLine(Track t) {
        return String.format("今日 %d · 昨日 %d · 本周 %d · 上周 %d · 本月 %d 遍",
                trackToday(t), trackYesterday(t), trackThisWeek(t),
                trackLastWeek(t), trackThisMonth(t));
    }

    public static String fmtTime(long ms) {
        long s = Math.max(0, ms / 1000);
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }

    public static String fmtDuration(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s >= 3600) return (s / 3600) + " 小时 " + ((s % 3600) / 60) + " 分";
        if (s >= 60) return (s / 60) + " 分 " + (s % 60) + " 秒";
        return s + " 秒";
    }
}
