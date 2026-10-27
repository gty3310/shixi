package com.ti.songdu.recite;

import java.util.Set;

/**
 * v1.18.0 识读加权判定 v2（纯 Java，可桌面单测；App 与 harness 共用同一份逻辑）。
 *
 * 公式（加权报告定死）：S ＝ W（先验）＋ E（音素距离）＋ C（双引擎一致）＋ K（雪崩折扣）
 *  - E：音素距离 d（声母/韵母/声调各差 1；双方声调可知才计声调）：
 *       d=0 → −2；d=1 → +1；d≥2 → +3。
 *  - C：双引擎同字同错音 +2、同字不同错音 +1、单引擎 0。
 *       用户 2026-10-27 新规矩：双报同一字错是全公式最强证据项，按此取值，不得弱化。
 *  - K：同一小句同引擎 SUB 事件 ≥3 条 → 该句 SUB 全体 −2。
 * 阈值 T=2 定「确定错」。硬规则：低先验 SUB 永不计确定错——双报同音升
 * 「存疑·双报」，其余为「存疑」。非低先验 0≤S<2 为「存疑·高」。
 * DEL/INS 不计分、单列。提交前去重由对齐器/会话层负责。
 */
public final class ReciteJudge {

    private ReciteJudge() {}

    public static final int TIER_CONFIRMED = 0;
    public static final int TIER_DOUBT_DUAL = 1;
    public static final int TIER_DOUBT_HIGH = 2;
    public static final int TIER_DOUBT = 3;
    public static final int TIER_DEL = 4;
    public static final int TIER_INS = 5;

    public static final int THRESHOLD = 2;

    public static final class Result {
        public int w, e, c, k, s, d;
        public int tier;
        public String category = "";
    }

    private static final String[] INITIALS = {
            "zh", "ch", "sh", "b", "p", "m", "f", "d", "t", "n", "l",
            "g", "k", "h", "j", "q", "x", "z", "c", "s", "r", "y", "w"};

    /** 音节 → {声母, 韵母}（去声调小写；v 表示 ü） */
    public static String[] split(String syll) {
        String s = syll == null ? "" : syll;
        for (String ini : INITIALS) {
            if (s.startsWith(ini)) return new String[]{ini, s.substring(ini.length())};
        }
        return new String[]{"", s};
    }

    /** 带调拼音的声调（1-4，无调/轻声返回 0=未知） */
    public static int toneOf(String py) {
        if (py == null) return 0;
        for (int i = 0; i < py.length(); i++) {
            char c = py.charAt(i);
            if ("āēīōūǖ".indexOf(c) >= 0) return 1;
            if ("áéíóúǘń".indexOf(c) >= 0) return 2;
            if ("ǎěǐǒǔǚň".indexOf(c) >= 0) return 3;
            if ("àèìòùǜǹ".indexOf(c) >= 0) return 4;
            if (c >= '1' && c <= '4' && i == py.length() - 1) return c - '0';
        }
        return 0;
    }

    /** heard 原始值（音节串或单个汉字）→ 去声调音节；汉字经 PinyinTable 取读，
     *  多音时取集合中与期望声母相同者（无则任取其一，距离结论对多音听写稳健）。 */
    public static String heardSyllable(String heard, String expected) {
        if (heard == null) return "";
        String h = heard.trim();
        if (h.isEmpty()) return "";
        if (h.length() == 1 && Syllables.isCjk(h.codePointAt(0))) {
            Set<String> set = PinyinTable.syllablesOf(h);
            if (set == null || set.isEmpty()) return "";
            if (set.size() == 1) return set.iterator().next();
            if (expected != null) {
                String expIni = split(expected)[0];
                for (String s : set) if (split(s)[0].equals(expIni)) return s;
            }
            // 确定性选择：字典序最小，避免 HashSet 迭代序不稳定
            String best = null;
            for (String s : set) if (best == null || s.compareTo(best) < 0) best = s;
            return best;
        }
        return Syllables.toneless(h);
    }

    /** 音素距离：声母差 +1、韵母差 +1、声调差 +1（双方可知时） */
    public static int phonemeDistance(String expToneless, int expTone,
                                      String heardToneless, int heardTone) {
        String[] a = split(expToneless), b = split(heardToneless);
        int d = 0;
        if (!a[0].equals(b[0])) d++;
        if (!a[1].equals(b[1])) d++;
        if (expTone > 0 && heardTone > 0 && expTone != heardTone) d++;
        return d;
    }

    public static int evidenceE(int d) {
        if (d <= 0) return -2;
        if (d == 1) return 1;
        return 3;
    }

    /**
     * 对一条 SUB 事件打分定档。
     * @param hanzi       错字（本书字形）
     * @param bookToned   本书带调拼音
     * @param heardRaw    主引擎听到的（音节或汉字，可空）
     * @param otherHeard  副引擎对同一字的听写（null=副引擎未报此字）
     * @param clauseSubCountSameEngine 同一小句同引擎 SUB 事件总数（雪崩判定）
     */
    public static Result judgeSub(String hanzi, String bookToned, String heardRaw,
                                  String otherHeard, int clauseSubCountSameEngine) {
        Result r = new Result();
        String bookTl = Syllables.toneless(bookToned);
        PriorTable.Prior prior = PriorTable.of(hanzi, bookTl);
        r.w = prior.w;
        r.category = prior.category;
        String heardTl = heardSyllable(heardRaw, bookTl);
        int heardTone = heardRaw != null && heardRaw.length() > 1 || (heardRaw != null && toneOf(heardRaw) > 0)
                ? toneOf(heardRaw) : 0;
        r.d = heardTl.isEmpty() ? 2 : phonemeDistance(bookTl, toneOf(bookToned), heardTl, heardTone);
        r.e = evidenceE(r.d);
        if (otherHeard == null) {
            r.c = 0;
        } else {
            String otherTl = heardSyllable(otherHeard, bookTl);
            r.c = (!otherTl.isEmpty() && otherTl.equals(heardTl)) ? 2 : 1;
        }
        r.k = clauseSubCountSameEngine >= 3 ? -2 : 0;
        r.s = r.w + r.e + r.c + r.k;
        if (prior.w == PriorTable.W_LOW) {
            // 硬规则：低先验永不计确定错；双报同音（C=2）升存疑·双报
            r.tier = (r.c == 2) ? TIER_DOUBT_DUAL : TIER_DOUBT;
        } else if (r.s >= THRESHOLD) {
            r.tier = TIER_CONFIRMED;
        } else if (r.c == 2) {
            r.tier = TIER_DOUBT_DUAL;
        } else if (r.s >= 0) {
            r.tier = TIER_DOUBT_HIGH;
        } else {
            r.tier = TIER_DOUBT;
        }
        return r;
    }

    public static String tierName(int tier) {
        switch (tier) {
            case TIER_CONFIRMED: return "confirmed";
            case TIER_DOUBT_DUAL: return "dual";
            case TIER_DOUBT_HIGH: return "doubtHigh";
            case TIER_DEL: return "del";
            case TIER_INS: return "ins";
            default: return "doubt";
        }
    }

    /** 严重度序（同一记录重复判定时取更重者落盘）：confirmed > dual > doubtHigh > doubt */
    public static int severity(int tier) {
        switch (tier) {
            case TIER_CONFIRMED: return 4;
            case TIER_DOUBT_DUAL: return 3;
            case TIER_DOUBT_HIGH: return 2;
            case TIER_DOUBT: return 1;
            default: return 0;
        }
    }
}
