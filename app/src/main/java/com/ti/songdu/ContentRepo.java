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
        public NoteRef noteGf;
        /** v1.29.0 覆盖本句的 lunyu.ai 白话导读（仅《论语》有）；无块覆盖时为 null */
        public NoteRef noteDd;
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
            // v1.25.0：泛化到第三声 aizz（正音版），同一分发
            String selVoice = VoicePref.getVoice(ctx, bid);
            if (VoicePref.isAssetVoice(selVoice) && VoicePref.appliesTo(ctx, t)) {
                Book vb = loadVoice(ctx, bid, selVoice);
                if (vb != null) return vb;
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
        CACHE.remove(bookId + "#aizz"); // v1.25.0：正音版时间轴缓存一并失效
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
                    // v1.27.0：单篇 rawJson 拼自 lunyuDoc（与 parseBook 独立解析），古读标红也要挂到这份上
                    attachGdToDoc(ctx, bookId, lunyuDoc);
                    // v1.29.0：白话导读的 nt 同理要挂到这份上（篇内下标口径，见该法注释）
                    attachDaoduToDoc(ctx, lunyuDoc);
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
        return loadVoice(ctx, bookId, VoicePref.VOICE_AI);
    }

    /** v1.25.0 正音版时间轴：assets/audio_ai/<bookId>_aizz.json，同构同句 id */
    public static synchronized Book loadAizz(Context ctx, String bookId) {
        return loadVoice(ctx, bookId, VoicePref.VOICE_AIZZ);
    }

    /** v1.25.0 按声音加载 assets 时间轴（ai / aizz 共用本体，缓存键按声音分开） */
    public static synchronized Book loadVoice(Context ctx, String bookId, String voice) {
        if (bookId == null || !VoicePref.isAssetVoice(voice)) return null;
        String suffix = VoicePref.VOICE_AIZZ.equals(voice) ? "aizz" : "ai";
        String key = bookId + "#" + suffix;
        Book cached = CACHE.get(key);
        if (cached != null) return cached;
        try {
            InputStream in = ctx.getAssets().open("audio_ai/" + bookId + "_" + suffix + ".json");
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int n;
            while ((n = in.read(tmp)) > 0) bos.write(tmp, 0, n);
            in.close();
            Book b = parseBook(ctx, new String(bos.toByteArray(), StandardCharsets.UTF_8));
            if (b == null) return null;
            b.voice = voice;
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
            // v1.29.0：《论语》也有注文件了（仅 lunyu.ai 白话导读一家，数据由
            // tools/gen_lunyu_daodu.py 生成）；用户导入书无注文件，attachNotes
            // 直接返回 false、零回退。
            boolean noted = attachNotes(ctx, b, sentObjs);
            boolean gded = attachGdMarks(ctx, b.bookId, sentObjs);
            if (noted || gded) {
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
        if (VoicePref.isAssetVoice(voice) && VoicePref.appliesTo(ctx, t)) {
            Book vb = loadVoice(ctx, bid, voice);
            if (vb != null) return vb;
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

    // ---------- v1.27.0 古读标红挂载 ----------
    // 清单 assets/content/gd/<bookId>.json 由 tools/gen_gd_marks.py 离线生成：
    // 逐字以书中拼音对正音词典（zhengyin-dict v0.3.1）审计口径判定，读音不是
    // 现代常用音（多音字不读默认音／非多音字不读唯一现代读音，一/不 变调归一）
    // 的字位收进 sentences.<句id>.gd（句内汉字序号）；n 是生成时的句内汉字数，
    // 在此作对账指纹——书库文本日后修订导致句长变化时整句不挂，防错位标红。
    // 无清单的书（用户导入书等）零回退。开关只管呈现（reader.html 的 gdon 类），
    // 挂载与开关无关，常开挂载、关呈现时 gd 类不生效。

    private static final java.util.Map<String, JSONObject> GD_CACHE = new java.util.HashMap<>();

    private static JSONObject gdData(Context ctx, String bookId) {
        if (GD_CACHE.containsKey(bookId)) return GD_CACHE.get(bookId);
        JSONObject data = null;
        try {
            InputStream in = ctx.getAssets().open("content/gd/" + bookId + ".json");
            byte[] buf = new byte[in.available()];
            int n = in.read(buf);
            in.close();
            if (n > 0) data = new JSONObject(new String(buf, 0, n, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
        GD_CACHE.put(bookId, data);
        return data;
    }

    /** 句内汉字计数，口径与 reader.html 的 isCJK 一致（拼音按此序逐字对齐） */
    private static int hanCount(String text) {
        int n = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if ((cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF)
                    || (cp >= 0x20000 && cp <= 0x323AF)) n++;
            i += Character.charCount(cp);
        }
        return n;
    }

    private static boolean applyGdToSentence(JSONObject data, JSONObject so) {
        try {
            JSONObject sents = data.optJSONObject("sentences");
            if (sents == null) return false;
            JSONObject e = sents.optJSONObject(so.optString("id", ""));
            if (e == null) return false;
            if (hanCount(so.optString("textS", "")) != e.optInt("n", -1)) return false;
            JSONArray gd = e.optJSONArray("gd");
            if (gd == null || gd.length() == 0) return false;
            so.put("gd", gd);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean attachGdMarks(Context ctx, String bookId, List<JSONObject> sentObjs) {
        JSONObject data = gdData(ctx, bookId);
        if (data == null) return false;
        boolean any = false;
        for (JSONObject so : sentObjs) {
            if (applyGdToSentence(data, so)) any = true;
        }
        return any;
    }

    /** 论语整本文档（lunyuDoc）专用：单篇 rawJson 拼自它，与 parseBook 独立解析，故整本逐句挂一份 */
    private static void attachGdToDoc(Context ctx, String bookId, JSONObject doc) {
        JSONObject data = gdData(ctx, bookId);
        if (data == null || doc == null) return;
        try {
            JSONArray chs = doc.getJSONArray("chapters");
            for (int i = 0; i < chs.length(); i++) {
                JSONArray ss = chs.getJSONObject(i).getJSONArray("sentences");
                for (int k = 0; k < ss.length(); k++) {
                    applyGdToSentence(data, ss.getJSONObject(k));
                }
            }
        } catch (Exception ignored) {
        }
    }

    // ---------- v1.29.0 lunyu.ai 白话导读挂载（论语整本文档路） ----------
    // 阅读页对《论语》按篇取 Book、rawJson 拼自 lunyuDoc（chapterRawJson），
    // parseBook 里的 attachNotes 写的是另一棵 JSON 树、到不了这条路，故仿
    // attachGdToDoc 在此对 lunyuDoc 再挂一份。数据同源：assets/notes/
    // lunyu_notes.json（{句id: {daodu, daoduSpan}}，生成器 gen_lunyu_daodu.py）。
    // 与整本口径的唯一差别：nt.dd 的 a/b 是「篇内」拉平下标——单篇渲染时
    // reader.html 的句序号从本篇 0 起，块归属按篇内数才对。
    // 导读注块由生成器按章切分、块间不重叠不嵌套，逐块直写即可；无数据零回退。
    // v1.30.0 注块模型：全量 {t,a,b} 只写块末句、块内其余句只写 {a,b} 标记。

    private static JSONObject lunyuNotesCache;

    private static JSONObject lunyuNotesData(Context ctx) {
        if (lunyuNotesCache != null) return lunyuNotesCache;
        JSONObject data = null;
        try {
            InputStream in = ctx.getAssets().open("notes/lunyu_notes.json");
            byte[] buf = new byte[in.available()];
            int n = in.read(buf);
            in.close();
            if (n > 0) data = new JSONObject(new String(buf, 0, n, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
        lunyuNotesCache = data;
        return data;
    }

    private static void attachDaoduToDoc(Context ctx, JSONObject doc) {
        JSONObject notes = lunyuNotesData(ctx);
        if (notes == null || doc == null) return;
        try {
            JSONArray chs = doc.getJSONArray("chapters");
            for (int c = 0; c < chs.length(); c++) {
                JSONArray ss = chs.getJSONObject(c).getJSONArray("sentences");
                int total = ss.length();
                for (int i = 0; i < total; i++) {
                    JSONObject so = ss.getJSONObject(i);
                    JSONObject e = notes.optJSONObject(so.optString("id", ""));
                    if (e == null || e.isNull("daodu")) continue;
                    String t = e.optString("daodu", null);
                    if (t == null || t.length() == 0) continue;
                    int span = Math.max(1, e.optInt("daoduSpan", 1));
                    int end = Math.min(total - 1, i + span - 1);
                    // v1.30.0 注块模型：全量 {t,a,b} 只写块末句，块内其余句
                    // 只写 {a,b} 标记（与 attachNotes 同口径，a/b 为篇内下标）
                    JSONObject full = new JSONObject();
                    full.put("t", t);
                    full.put("a", i);
                    full.put("b", end);
                    JSONObject mark = new JSONObject();
                    mark.put("a", i);
                    mark.put("b", end);
                    for (int j = i; j <= end; j++) {
                        JSONObject tgt = ss.getJSONObject(j);
                        JSONObject nt = tgt.optJSONObject("nt");
                        if (nt == null) {
                            nt = new JSONObject();
                            tgt.put("nt", nt);
                        }
                        nt.put("dd", j == end ? full : mark);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * v1.9.0 注释挂载：注数据文件结构 {sentenceId: {zhijie, jizhu, gengfu, zhijieSpan,
     * jizhuSpan, gengfuSpan}}（gengfu＝《耕夫讲经典》，v1.20.5 加入的第三家，仅《大学》有；
     * v1.29.0 加入第四家 daodu＝lunyu.ai 白话导读，仅《论语》有），
     * 注文只挂在块首句、span 为该块覆盖的句数（块注）。本方法对每个版本先收集全部注块，
     * 再为每一句判定覆盖它的注块（多块覆盖时取起点最晚的那块，处理集注的嵌套小块），
     * 把判定结果同时写进 Sentence 字段（供竖排原生注栏用）与内容包 JSON 的句子对象
     * （新增 "nt" 字段：{"zj"/"jz"/"gf"/"dd": {"t": 注文, "a": 块首拉平下标, "b": 块末拉平下标}}，
     * 供阅读页注入）。v1.30.0 注块模型（只对 daodu）：nt 的 "t" 只落在覆盖段末尾句，
     * 段内其余句只有 {"a","b"} 标记；其余三家仍逐句带 "t"（详见方法内注）。
     * 无注文件/解析失败返回 false，调用方保持原 rawJson 不动。
     * 注意：《论语》的单篇 rawJson 拼自 lunyuDoc、本方法写不到那棵树上，dd 在那条
     * 路上由 attachDaoduToDoc 以篇内下标另挂（zj/jz/gf 对论语本无数据，不受影响）。
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
            String[][] vers = {{"zhijie", "zj"}, {"jizhu", "jz"}, {"gengfu", "gf"}, {"daodu", "dd"}};
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
                int[] bestFor = new int[total];
                for (int i = 0; i < total; i++) {
                    int best = -1;
                    for (int k = 0; k < starts.size(); k++) {
                        if (starts.get(k) <= i && i <= ends.get(k)
                                && (best < 0 || starts.get(k) > starts.get(best))) {
                            best = k;
                        }
                    }
                    bestFor[i] = best;
                }
                for (int i = 0; i < total; i++) {
                    int best = bestFor[i];
                    if (best < 0) continue;
                    NoteRef ref = new NoteRef();
                    ref.text = texts.get(best);
                    ref.startFlat = starts.get(best);
                    ref.endFlat = ends.get(best);
                    if ("zhijie".equals(key)) b.flat.get(i).noteZj = ref;
                    else if ("jizhu".equals(key)) b.flat.get(i).noteJz = ref;
                    else if ("daodu".equals(key)) b.flat.get(i).noteDd = ref;
                    else b.flat.get(i).noteGf = ref;
                    JSONObject so = sentObjs.get(i);
                    JSONObject nt = so.optJSONObject("nt");
                    if (nt == null) {
                        nt = new JSONObject();
                        so.put("nt", nt);
                    }
                    // v1.30.0 注块模型（只对导读 daodu 落地）：注文只在本句是
                    // 「该注块在导读解析下连续覆盖段的末尾」时写全量 {t,a,b}，
                    // 段内其余句只写 {a,b} 标记（块归属，供阅读页按当前句定位
                    // 块尾的注元素）；reader.html 据此把一条导读只在块尾渲染
                    // 一次、不再逐句重复。直解/集注/耕夫三家维持旧口径逐句写
                    // 全量 {t,a,b}——三家在现数据里同为块注（直解 61 块、集注
                    // 65 块、耕夫 111 块），用户定版要求其排版与现状分毫不差，
                    // 故不套只显一次。原生侧 Sentence 字段仍逐句存覆盖块的
                    // NoteRef（竖排注栏按当前句取所在块的注，语义不变）。
                    boolean runEnd = !"daodu".equals(key)
                            || i == total - 1 || bestFor[i + 1] != best;
                    JSONObject one = new JSONObject();
                    if (runEnd) one.put("t", ref.text);
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
        if (VoicePref.isAssetVoice(b.voice)) return 1.0;
        return (double) t.durationMs / (double) b.audioDurationMs;
    }
}
