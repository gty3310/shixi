package com.ti.songdu;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * v1.17.0 双声音偏好：个别书除原版录音外多一条 AI 朗读音轨（资产在
 * assets/audio_ai/<bookId>_ai.mp3 + <bookId>_ai.json，句 id 与原声内容包一致、
 * 时间轴各自实测）。v1.20.0 起《大学》《中庸》两本；《中庸》的预置曲目由
 * 在线书库下载产生，未下载时它的设置行与首播门都不出现。
 *
 * 口径（用户定）：
 *  - 偏好按书存：voice_prefs 里 voice_pref_<bookId> = "orig"/"ai"；
 *    未存＝从未选过，首播任何入口都先拉选声页（见 PlayerService 的门）。
 *  - 双声音只作用于该书的预置曲目（preset-<bookId>-full）：用户自加的同名
 *    音频是他们自己的录音，不套 AI 音轨与时间轴。
 *  - pos_voice_<bookId> 记「曲目 positionMs 当前所属时间轴的声音」：切声与
 *    冷启动续播时按句把位置映射到另一条时间轴（ContentRepo.mapPosition）。
 */
public final class VoicePref {

    public static final String VOICE_ORIG = "orig";
    public static final String VOICE_AI = "ai";

    private static final String PREFS = "voice_prefs";
    private static final String KEY_VOICE = "voice_pref_";
    private static final String KEY_POS_VOICE = "pos_voice_";

    /** assets/audio_ai 下有成套双声音资产的 bookId 集合（进程内算一次） */
    private static Set<String> dualBooks;

    private VoicePref() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 该书是否有双声音资产（audio_ai/<bookId>_ai.mp3 与 _ai.json 成套） */
    public static synchronized boolean hasDualVoice(Context ctx, String bookId) {
        if (bookId == null) return false;
        if (dualBooks == null) {
            dualBooks = new HashSet<>();
            try {
                String[] names = ctx.getAssets().list("audio_ai");
                if (names != null) {
                    Set<String> files = new HashSet<>();
                    for (String n : names) files.add(n);
                    for (String n : names) {
                        if (n.endsWith("_ai.json")) {
                            String bid = n.substring(0, n.length() - "_ai.json".length());
                            if (files.contains(bid + "_ai.mp3")) dualBooks.add(bid);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return dualBooks.contains(bookId);
    }

    /** 该书的预置曲目 id（双声音只挂在这条曲目上）；无预置曲目约定的书返回 null */
    public static String presetTrackIdFor(String bookId) {
        if ("daxue".equals(bookId)) return "preset-daxue-full";
        if ("zhongyong".equals(bookId)) return "preset-zhongyong-full";
        return null;
    }

    /** 双声音是否作用于这条曲目：是该书的预置曲目（资产成套与否由 hasDualVoice 另判） */
    public static boolean appliesTo(Track t) {
        if (t == null || t.id == null) return false;
        String bid = ContentRepo.bookIdFor(t);
        if (bid == null) return false;
        String pt = presetTrackIdFor(bid);
        return pt != null && pt.equals(t.id);
    }

    /** 作用于该曲目且资产成套 */
    public static boolean appliesTo(Context ctx, Track t) {
        return appliesTo(t) && hasDualVoice(ctx, ContentRepo.bookIdFor(t));
    }

    /** 已选声音："orig"/"ai"；从未选过返回 null */
    public static String getVoice(Context ctx, String bookId) {
        if (bookId == null) return null;
        String v = prefs(ctx).getString(KEY_VOICE + bookId, null);
        return (VOICE_ORIG.equals(v) || VOICE_AI.equals(v)) ? v : null;
    }

    public static void setVoice(Context ctx, String bookId, String voice) {
        if (bookId == null || voice == null) return;
        prefs(ctx).edit().putString(KEY_VOICE + bookId, voice).apply();
    }

    /** 首播门：双声音书的预置曲目、尚未选过 → 任何播放入口先去选声页 */
    public static boolean needsChoice(Context ctx, Track t) {
        return appliesTo(ctx, t) && getVoice(ctx, ContentRepo.bookIdFor(t)) == null;
    }

    /** positionMs 当前所属时间轴的声音（默认原声：双声音出现前的存量位置都是原声口径） */
    public static String getPosVoice(Context ctx, String bookId) {
        if (bookId == null) return VOICE_ORIG;
        String v = prefs(ctx).getString(KEY_POS_VOICE + bookId, VOICE_ORIG);
        return VOICE_AI.equals(v) ? VOICE_AI : VOICE_ORIG;
    }

    public static void setPosVoice(Context ctx, String bookId, String voice) {
        if (bookId == null || voice == null) return;
        prefs(ctx).edit().putString(KEY_POS_VOICE + bookId, voice).apply();
    }

    /** 声音的展示名（选声页/设置页共用） */
    public static String voiceName(String voice) {
        return VOICE_AI.equals(voice) ? "AI 朗读" : "法师原声";
    }

    /** AI 音轨在 assets 中的路径 */
    public static String aiAudioAsset(String bookId) {
        return "audio_ai/" + bookId + "_ai.mp3";
    }

    /** 校验 assets 里 AI 音轨可经 openFd 读取（未压缩存储）；仅供排查，失败返回 false */
    public static boolean aiAudioReadable(Context ctx, String bookId) {
        try {
            InputStream in = ctx.getAssets().open(aiAudioAsset(bookId));
            in.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
