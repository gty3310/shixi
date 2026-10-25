package com.ti.songdu;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.4 阅读模式的内容包仓库。
 *
 * 内容包是构建期由 gen_content.py 生成的 JSON（assets/content/daxue.json、zhongyong.json），
 * 结构：bookId / title / audioDurationMs / chapters[]（id、title 章题繁体、note 章下注）
 *      / sentences[]（id、startMs、endMs、textS、textT、pinyin[] 逐字数组）。
 * 时间轴来自 SRT 逐字对齐（以完整版原速录音为准）；朱子补传等录音未诵的句子
 * startMs/endMs 为 null 且带 noAudio=true（照常显示、不可点播、不参与高亮）。
 *
 * 匹配规则：预置完整版按稳定 id 绑定；用户自加音频若显示名或来源文件名含
 * 「大学」/「中庸」（如 3 倍速版），绑定对应内容包，句时间按
 * scale = 该音频实际时长 / audioDurationMs 等比缩放。
 *
 * v1.5《论语》按篇拆分：内容包 lunyu.json 的 chapters 即二十篇，每篇句时间以该篇
 * 音频起点为 0 基准（篇内时间轴），并带该篇基准时长 durationMs。运行时不取整本，
 * 而是经 bookForTrack() 按曲目取「单篇 Book」（chapters 只含该篇、audioDurationMs
 * 换成该篇时长），阅读与跟读都按篇工作。论语曲目定位篇目：预置按 id 后缀
 * preset-lunyu-NN；自加音频要求名称含篇名（如「论语·子路第十三」），否则不绑定。
 */
public class ContentRepo {

    /** v1.9.0 注释块引用：古人按段注，一块注覆盖连续若干句；text 挂在块首句的数据上，
     *  这里存的是「覆盖本句的那一块」在全书拉平序中的起止下标（0 基、含两端）与注文。
     *  块有嵌套时以起点最晚（最贴身）的那块为准。 */
    public static class NoteRef {
        public String text;
        public int startFlat;
        public int endFlat;
    }

    public static class Sentence {
        public String id;
        /** 内容包原始时间（原速完整版口径）；noAudio 时为 -1 */
        public long startMs = -1;
        public long endMs = -1;
        public boolean noAudio;
        public String textS;
        public String textT;
        public List<String> pinyin = new ArrayList<>();
        /** v1.9.0 覆盖本句的注（张居正直解 / 朱熹集注）；该版本下无块覆盖时为 null */
        public NoteRef noteZj;
        public NoteRef noteJz;
        /** v1.7 句内可停标点点位（，。；！？，逐字对齐＋静音吸附定时间，个别估算点位
         *  在生成数据时已标记）：breakPos[i] 为标点在 textT 中的 UTF-16 下标（升序），
         *  breakMs[i] 为内容包原始时间轴毫秒（与 startMs 同口径）；无内部点位时为 null */
        public int[] breakPos;
        public long[] breakMs;
    }

    public static class Chapter {
        public String id;
        public String title;
        public String note; // 可为 null，如补传章的「朱子補傳 · 錄音未誦」
        /** v1.5 论语按篇时长基准（该篇音频时长）；大学/中庸内容包无此字段，为 0 */
        public long durationMs;
        public final List<Sentence> sentences = new ArrayList<>();
    }

    public static class Book {
        public String bookId;
        public String title;
        public long audioDurationMs;
        /** v1.17.0 本 Book 的时间轴所属声音："orig" 原声（默认）/ "ai" AI 朗读。
         *  AI 书与原声书同 bookId、同句 id、文本一致，只是时间轴各自实测 */
        public String voice = VoicePref.VOICE_ORIG;
        public final List<Chapter> chapters = new ArrayList<>();
        /** 全部句子按阅读顺序拉平（与 chapters 内顺序一致），下标即阅读页的句序号 */
        public final List<Sentence> flat = new ArrayList<>();
        /** 原始 JSON 文本，阅读页直接注入 WebView 渲染，避免 Java 侧再序列化 */
        public String rawJson;
    }

    private static final Map<String, Book> CACHE = new HashMap<>();

    /** 论语二十篇篇名（繁/简成对，下标/2 即篇序） */
    private static final String[] LUNYU_PIAN = {
            "學而", "学而", "為政", "为政", "八佾", "八佾", "里仁", "里仁",
            "公冶長", "公冶长", "雍也", "雍也", "述而", "述而", "泰伯", "泰伯",
            "子罕", "子罕", "鄉黨", "乡党", "先進", "先进", "顏淵", "颜渊",
            "子路", "子路", "憲問", "宪问", "衛靈公", "卫灵公", "季氏", "季氏",
            "陽貨", "阳货", "微子", "微子", "子張", "子张", "堯曰", "尧曰"};

    /** 曲目对应论语第几篇（0 基）；不是论语曲目或定位不到篇目时返回 -1 */
    public static int lunyuChapterIndex(Track t) {
        if (t == null) return -1;
        if (t.id != null && t.id.startsWith("preset-lunyu-")) {
            try {
                int n = Integer.parseInt(t.id.substring("preset-lunyu-".length()));
                if (n >= 1 && n <= 20) return n - 1;
            } catch (Exception ignored) {
            }
            return -1;
        }
        String hay = (t.title != null ? t.title : "") + "\n" + (t.sourceName != null ? t.sourceName : "");
        if (!hay.contains("论语") && !hay.contains("論語")) return -1;
        for (int i = 0; i < LUNYU_PIAN.length; i += 2) {
            if (hay.contains(LUNYU_PIAN[i]) || hay.contains(LUNYU_PIAN[i + 1])) return i / 2;
        }
        return -1;
    }

    /** 曲目 → 内容包 id；无配套原文时返回 null（卡上不显示阅读钮） */
    public static String bookIdFor(Track t) {
        if (t == null) return null;
        // v1.8.2：用户导入字幕生成的自有内容包优先于一切名称匹配
        if (t.contentId != null) return t.contentId;
        if ("preset-daxue-full".equals(t.id)) return "daxue";
        if ("preset-zhongyong-full".equals(t.id)) return "zhongyong";
        if (t.id != null && t.id.startsWith("preset-lunyu-")) return "lunyu";
        String hay = (t.title != null ? t.title : "") + "\n" + (t.sourceName != null ? t.sourceName : "");
        if (hay.contains("大学")) return "daxue";
        if (hay.contains("中庸")) return "zhongyong";
        // 论语按篇取用：自加音频须能从名称定位篇目（含篇名），整本文件无法按篇绑定
        if (hay.contains("论语") || hay.contains("論語")) {
            return lunyuChapterIndex(t) >= 0 ? "lunyu" : null;
        }
        return null;
    }

    /**
     * 曲目 → 运行时实际使用的 Book：大学/中庸为整本；论语为「单篇 Book」
     * （只含该篇、audioDurationMs 换成该篇基准时长、rawJson 为单篇 JSON）。
     * 无内容时返回 null。ReaderActivity 与 PlayerService 都走这里，口径一致。
     */
    public static Book bookForTrack(Context ctx, Track t) {
        String bid = bookIdFor(t);
        if (bid == null) return null;
        if (!"lunyu".equals(bid)) {
            // v1.17.0：双声音书的预置曲目按已选声音取时间轴（AI 书加载失败回退原声）
            if (VoicePref.VOICE_AI.equals(VoicePref.getVoice(ctx, bid))
                    && VoicePref.appliesTo(ctx, t)) {
                Book ai = loadAi(ctx, bid);
                if (ai != null) return ai;
            }
            return load(ctx, bid);
        }
        int idx = lunyuChapterIndex(t);
        if (idx < 0) return null;
        Book full = load(ctx, "lunyu");
        if (full == null || idx >= full.chapters.size()) return null;
        Chapter ch = full.chapters.get(idx);
        Book b = new Book();
        b.bookId = "lunyu";
        b.title = ch.title;
        b.audioDurationMs = ch.durationMs > 0 ? ch.durationMs : full.audioDurationMs;
        b.chapters.add(ch);
        b.flat.addAll(ch.sentences);
        b.rawJson = chapterRawJson(idx, ch, b.audioDurationMs);
        return b;
    }

    /** 论语整本 JSON 原文（load 时留存），供拼单篇 rawJson 用 */
    private static JSONObject lunyuDoc;

    private static String chapterRawJson(int idx, Chapter ch, long durMs) {
        try {
            if (lunyuDoc != null) {
                JSONObject one = new JSONObject();
                one.put("bookId", "lunyu");
                one.put("title", ch.title);
                one.put("audioDurationMs", durMs);
                JSONArray arr = new JSONArray();
                arr.put(lunyuDoc.getJSONArray("chapters").getJSONObject(idx));
                one.put("chapters", arr);
                return one.toString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static boolean hasContent(Track t) {
        return bookIdFor(t) != null;
    }

    /** v1.8.2 用户导入内容包存放目录（内部存储 files/usercontent/<bookId>.json） */
    public static java.io.File userContentFile(Context ctx, String bookId) {
        java.io.File dir = new java.io.File(ctx.getFilesDir(), "usercontent");
        return new java.io.File(dir, bookId + ".json");
    }

    /** v1.8.2 保存用户导入内容包（结构与 assets/content 相同）；成功返回 true */
    public static boolean saveUserBook(Context ctx, String bookId, String json) {
        try {
            java.io.File f = userContentFile(ctx, bookId);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            java.io.FileOutputStream out = new java.io.FileOutputStream(f);
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.close();
            synchronized (ContentRepo.class) {
                CACHE.remove(bookId);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** v1.14.0：让某本书的缓存失效（书库整本下载完成 / 删书后调用，下次 load 重新读盘） */
    public static synchronized void invalidate(String bookId) {
        CACHE.remove(bookId);
        CACHE.remove(bookId + "#ai"); // v1.17.0：AI 时间轴缓存一并失效
        if ("lunyu".equals(bookId)) lunyuDoc = null;
    }

    /** v1.8.2 删除用户导入内容包（删曲目时一并清理，不留孤儿文件） */
    public static void deleteUserBook(Context ctx, String bookId) {
        try {
            java.io.File f = userContentFile(ctx, bookId);
            if (f.exists()) f.delete();
        } catch (Exception ignored) {
        }
        synchronized (ContentRepo.class) {
            CACHE.remove(bookId);
        }
    }

    public static synchronized Book load(Context ctx, String bookId) {
        if (bookId == null) return null;
        Book cached = CACHE.get(bookId);
        if (cached != null) return cached;
        try {
            String raw;
            try {
                InputStream in = ctx.getAssets().open("content/" + bookId + ".json");
                byte[] buf = new byte[in.available()];
                int n = in.read(buf);
                in.close();
                if (n <= 0) return null;
                raw = new String(buf, 0, n, StandardCharsets.UTF_8);
            } catch (Exception assetMiss) {
                // v1.14.0：assets 没有时先回退到在线书库下载包（files/books/<id>/content/），
                // 再回退到 v1.8.2 的用户导入内容包（内部存储 usercontent）
                java.io.File f = Bookstore.contentFile(ctx, bookId);
                if (!f.exists()) f = userContentFile(ctx, bookId);
                if (!f.exists()) return null;
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                byte[] buf = new byte[in.available()];
                int n = in.read(buf);
                in.close();
                if (n <= 0) return null;
                raw = new String(buf, 0, n, StandardCharsets.UTF_8);
            }
            if ("lunyu".equals(bookId)) {
                try {
                    lunyuDoc = new JSONObject(raw);
                } catch (Exception ignored) {
                }
            }
            Book b = parseBook(ctx, raw);
            if (b == null) return null;
            CACHE.put(bookId, b);
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * v1.17.0 AI 朗读时间轴：assets/audio_ai/<bookId>_ai.json，与原声包同结构、
     * 同句 id、文本一致，时间轴为 AI 音轨的实测值。缓存键 bookId+"#ai" 与原声
     * 分开；解析与挂注走 parseBook 同一套（句 id 相同，注释照常挂得上）。
     */
    public static synchronized Book loadAi(Context ctx, String bookId) {
        if (bookId == null) return null;
        String key = bookId + "#ai";
        Book cached = CACHE.get(key);
        if (cached != null) return cached;
        try {
            InputStream in = ctx.getAssets().open("audio_ai/" + bookId + "_ai.json");
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int n;
            while ((n = in.read(tmp)) > 0) bos.write(tmp, 0, n);
            in.close();
            Book b = parseBook(ctx, new String(bos.toByteArray(), StandardCharsets.UTF_8));
            if (b == null) return null;
            b.voice = VoicePref.VOICE_AI;
            CACHE.put(key, b);
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    /** 内容包 JSON → Book：解析章节/句子、挂注释、定 rawJson（load 与 loadAi 共用） */
    private static Book parseBook(Context ctx, String raw) {
        try {
            JSONObject o = new JSONObject(raw);
            Book b = new Book();
            b.bookId = o.getString("bookId");
            b.title = o.optString("title", "");
            b.audioDurationMs = o.getLong("audioDurationMs");
            b.rawJson = raw;
            List<JSONObject> sentObjs = new ArrayList<>();
            JSONArray chs = o.getJSONArray("chapters");
            for (int i = 0; i < chs.length(); i++) {
                JSONObject co = chs.getJSONObject(i);
                Chapter ch = new Chapter();
                ch.id = co.optString("id", "c" + (i + 1));
                ch.title = co.optString("title", "");
                ch.note = co.isNull("note") ? null : co.optString("note", null);
                ch.durationMs = co.optLong("durationMs", 0);
                JSONArray ss = co.getJSONArray("sentences");
                for (int k = 0; k < ss.length(); k++) {
                    JSONObject so = ss.getJSONObject(k);
                    Sentence s = new Sentence();
                    s.id = so.optString("id", "");
                    s.noAudio = so.optBoolean("noAudio", false);
                    if (!so.isNull("startMs")) s.startMs = so.getLong("startMs");
                    if (!so.isNull("endMs")) s.endMs = so.getLong("endMs");
                    if (s.noAudio) {
                        s.startMs = -1;
                        s.endMs = -1;
                    }
                    s.textS = so.optString("textS", "");
                    s.textT = so.optString("textT", "");
                    JSONArray py = so.optJSONArray("pinyin");
                    if (py != null) {
                        for (int m = 0; m < py.length(); m++) s.pinyin.add(py.getString(m));
                    }
                    JSONArray br = so.optJSONArray("breaks");
                    if (br != null && br.length() > 0 && !s.noAudio) {
                        s.breakPos = new int[br.length()];
                        s.breakMs = new long[br.length()];
                        for (int m = 0; m < br.length(); m++) {
                            JSONObject bo = br.getJSONObject(m);
                            s.breakPos[m] = bo.getInt("p");
                            s.breakMs[m] = bo.getLong("t");
                        }
                    }
                    ch.sentences.add(s);
                    b.flat.add(s);
                    sentObjs.add(so);
                }
                b.chapters.add(ch);
            }
            // v1.9.0：《大学》《中庸》挂注释（assets/notes/<bookId>_notes.json）；
            // 挂上后把带注的 JSON 重新序列化为 rawJson，阅读页注入即带注。
            // 《论语》与用户导入书无注文件，attachNotes 直接返回 false、零回退。
            if (attachNotes(ctx, b, sentObjs)) {
                b.rawJson = o.toString();
            }
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * v1.17.0 按指定声音取曲目的 Book（不看当前偏好，供切声时同时拿新旧两条
     * 时间轴做位置映射）：AI 只对双声音预置曲目成立，其余一律原声口径。
     * 论语走 bookForTrack 的按篇逻辑（无双声音）。
     */
    public static Book bookForVoice(Context ctx, Track t, String voice) {
        String bid = bookIdFor(t);
        if (bid == null) return null;
        if ("lunyu".equals(bid)) return bookForTrack(ctx, t);
        if (VoicePref.VOICE_AI.equals(voice) && VoicePref.appliesTo(ctx, t)) {
            Book ai = loadAi(ctx, bid);
            if (ai != null) return ai;
        }
        return load(ctx, bid);
    }

    /**
     * v1.17.0 位置跨声音映射：from 时间轴里 posMs 落在哪句（最后一个 start<=pos
     * 的有声句），返回 to 时间轴里同一句（按句 id 对齐）的 startMs；
     * 定位不到返回 0（从头）。切声保持当前句、冷启动续播换轴都走这里。
     */
    public static long mapPosition(Book from, Book to, long posMs) {
        if (from == null || to == null) return 0;
        // 注意不能对 posMs<=0 早退：AI 轴首句 startMs 就是 0，0 点属于首句，
        // 要映到对方轴的首句句首（原声首句前有片头静音，startMs>0）
        Sentence hit = null;
        for (Sentence s : from.flat) {
            if (s.noAudio || s.startMs < 0) continue;
            if (s.startMs <= posMs) hit = s;
            else break; // 内容包时间单调，越过即停
        }
        if (hit == null) return 0;
        for (Sentence s : to.flat) {
            if (s.id.equals(hit.id)) return Math.max(0, s.startMs);
        }
        return 0;
    }

    /**
     * v1.9.0 注释挂载：注数据文件结构 {sentenceId: {zhijie, jizhu, zhijieSpan, jizhuSpan}}，
     * 注文只挂在块首句、span 为该块覆盖的句数（块注）。本方法对每个版本先收集全部注块，
     * 再为每一句判定覆盖它的注块（多块覆盖时取起点最晚的那块，处理集注的嵌套小块），
     * 把判定结果同时写进 Sentence 字段（供竖排原生注栏用）与内容包 JSON 的句子对象
     * （新增 "nt" 字段：{"zj"/"jz": {"t": 注文, "a": 块首拉平下标, "b": 块末拉平下标}}，
     * 供阅读页注入）。无注文件/解析失败返回 false，调用方保持原 rawJson 不动。
     */
    private static boolean attachNotes(Context ctx, Book b, List<JSONObject> sentObjs) {
        String raw;
        try {
            InputStream in = ctx.getAssets().open("notes/" + b.bookId + "_notes.json");
            byte[] buf = new byte[in.available()];
            int n = in.read(buf);
            in.close();
            if (n <= 0) return false;
            raw = new String(buf, 0, n, StandardCharsets.UTF_8);
        } catch (Exception noAsset) {
            // v1.14.0：注文件也可来自在线书库下载包（如《中庸》两家注随书下载）
            try {
                java.io.File f = Bookstore.notesFile(ctx, b.bookId);
                if (!f.exists()) return false;
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                byte[] buf = new byte[in.available()];
                int n = in.read(buf);
                in.close();
                if (n <= 0) return false;
                raw = new String(buf, 0, n, StandardCharsets.UTF_8);
            } catch (Exception noNotes) {
                return false;
            }
        }
        try {
            JSONObject notes = new JSONObject(raw);
            int total = b.flat.size();
            boolean any = false;
            String[][] vers = {{"zhijie", "zj"}, {"jizhu", "jz"}};
            for (String[] v : vers) {
                String key = v[0], shortKey = v[1];
                List<Integer> starts = new ArrayList<>();
                List<Integer> ends = new ArrayList<>();
                List<String> texts = new ArrayList<>();
                for (int i = 0; i < total; i++) {
                    JSONObject e = notes.optJSONObject(b.flat.get(i).id);
                    if (e == null || e.isNull(key)) continue;
                    String t = e.optString(key, null);
                    if (t == null || t.length() == 0) continue;
                    int span = Math.max(1, e.optInt(key + "Span", 1));
                    starts.add(i);
                    ends.add(Math.min(total - 1, i + span - 1));
                    texts.add(t);
                }
                for (int i = 0; i < total; i++) {
                    int best = -1;
                    for (int k = 0; k < starts.size(); k++) {
                        if (starts.get(k) <= i && i <= ends.get(k)
                                && (best < 0 || starts.get(k) > starts.get(best))) {
                            best = k;
                        }
                    }
                    if (best < 0) continue;
                    NoteRef ref = new NoteRef();
                    ref.text = texts.get(best);
                    ref.startFlat = starts.get(best);
                    ref.endFlat = ends.get(best);
                    if ("zhijie".equals(key)) b.flat.get(i).noteZj = ref;
                    else b.flat.get(i).noteJz = ref;
                    JSONObject so = sentObjs.get(i);
                    JSONObject nt = so.optJSONObject("nt");
                    if (nt == null) {
                        nt = new JSONObject();
                        so.put("nt", nt);
                    }
                    JSONObject one = new JSONObject();
                    one.put("t", ref.text);
                    one.put("a", ref.startFlat);
                    one.put("b", ref.endFlat);
                    nt.put(shortKey, one);
                    any = true;
                }
            }
            return any;
        } catch (Exception e) {
            return false;
        }
    }

    /** 句时间缩放系数：该音频实际时长 / 内容包基准时长（3 倍速版 ≈ 0.34，原速版 = 1）。
     *  v1.17.0：AI 书的时间轴就是 AI 音轨自身的实测值（播的正是那条音轨），恒 1.0，
     *  不拿曲目的原声时长去除 */
    public static double scaleFor(Track t, Book b) {
        if (t == null || b == null || b.audioDurationMs <= 0 || t.durationMs <= 0) return 1.0;
        if (VoicePref.VOICE_AI.equals(b.voice)) return 1.0;
        return (double) t.durationMs / (double) b.audioDurationMs;
    }
}
