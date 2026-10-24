package com.ti.songdu.recite;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v1.16.0-dev 识读：一本书的「期望音节流」＋小句切分（纯 Java，可桌面单测）。
 *
 * 输入是与 ContentRepo 解耦的句子记录（id / 繁体文本 / 简体文本 / 逐字带调拼音 /
 * breakPos 句内可停标点在 textT 中的 UTF-16 下标），Android 侧由 ReciteSession
 * 从 ContentRepo.Book 适配，桌面测试由 fixture 适配。
 *
 * 小句口径（用户 2026-10-24 定）：以标点为界、含该字的最短片段——切口取内容包
 * breaks（只落 ，。；！？），标点后紧跟的收尾符归前一小句，与 FollowChunker 分句
 * 口径一致。错误统计的记录单位是「字＋所在小句」，故每字都带 clause 下标。
 */
public final class ReciteData {

    /** 一个字（期望流的最小单位） */
    public static final class Unit {
        public int global;        // 在全书期望流中的下标
        public int flat;          // 句序号（与阅读页 flat 一致）
        public int ci;            // 句内汉字序号（与拼音数组下标一致）
        public String hanzi;      // 繁体字
        public String pyToned;    // 带调拼音（展示/落盘用）
        public String py;         // 去声调音节（判定用）
        public int clause;        // 所在小句在句内的序号
    }

    /** 一个小句（标点间的最短片段） */
    public static final class Clause {
        public int flat;
        public int idx;           // 句内小句序号
        public int hanziStart;    // 句内汉字序号起（含）
        public int hanziEnd;      // 句内汉字序号止（不含）
        public int textStart;     // textT 的 UTF-16 下标起（含）
        public int textEnd;       // textT 的 UTF-16 下标止（不含）
        public String text;       // 小句文本（繁体，含尾标点）
    }

    /** 构建输入：一句 */
    public static final class SentIn {
        public String id;
        public String textT;
        public String textS;
        public List<String> pinyin;
        public int[] breakPos;
    }

    public final String bookId;
    /** flat → 内容包句子 id（统计记录的权威定位键，跳回原句靠它，不靠 flat） */
    public final List<String> sentIds = new ArrayList<>();
    public final List<Unit> units = new ArrayList<>();
    /** flat → 该句的小句列表 */
    public final List<List<Clause>> clausesBySent = new ArrayList<>();
    /** flat → 该句在期望流中的起始 unit 下标；句无可读字时为 -1 */
    public final List<Integer> sentUnitStart = new ArrayList<>();
    /** 汉字（繁/简两体都登记）→ 它在本书中出现过的去声调音节集合（多音字多值） */
    public final Map<String, Set<String>> charSyllables = new HashMap<>();

    private ReciteData(String bookId) {
        this.bookId = bookId;
    }

    private static boolean isCloser(int cp) {
        return "」』）》”’〕〉】".indexOf(cp) >= 0;
    }

    public static ReciteData build(String bookId, List<SentIn> sents) {
        ReciteData d = new ReciteData(bookId);
        for (int flat = 0; flat < sents.size(); flat++) {
            SentIn s = sents.get(flat);
            d.sentIds.add(s.id);
            String text = s.textT != null ? s.textT : "";
            String textS = s.textS != null ? s.textS : text;

            // 逐码点遍历：记录每个 CJK 字的 UTF-16 起点（拼音数组按 CJK 计数对齐）
            List<Integer> hanziTextIdx = new ArrayList<>();
            List<String> hanziChars = new ArrayList<>();
            List<String> hanziCharsS = new ArrayList<>();
            List<Integer> sTextIdx = new ArrayList<>();
            for (int i = 0; i < text.length(); ) {
                int cp = text.codePointAt(i);
                if (Syllables.isCjk(cp)) {
                    hanziTextIdx.add(i);
                    hanziChars.add(new String(Character.toChars(cp)));
                }
                i += Character.charCount(cp);
            }
            for (int i = 0; i < textS.length(); ) {
                int cp = textS.codePointAt(i);
                if (Syllables.isCjk(cp)) {
                    sTextIdx.add(i);
                    hanziCharsS.add(new String(Character.toChars(cp)));
                }
                i += Character.charCount(cp);
            }

            int nPy = s.pinyin != null ? s.pinyin.size() : 0;
            int nHanzi = Math.min(hanziChars.size(), nPy); // 数据异常时只取有拼音的字，宁缺勿错位

            // 小句切口：breakPos 标点之后（收尾符归前句），最后到句末
            List<Integer> ends = new ArrayList<>();
            if (s.breakPos != null) {
                for (int bp : s.breakPos) {
                    int e = bp + 1;
                    while (e < text.length() && isCloser(text.codePointAt(e))) {
                        e += Character.charCount(text.codePointAt(e));
                    }
                    if (e > (ends.isEmpty() ? 0 : ends.get(ends.size() - 1)) && e <= text.length()) {
                        ends.add(e);
                    }
                }
            }
            if (ends.isEmpty() || ends.get(ends.size() - 1) < text.length()) ends.add(text.length());

            List<Clause> clauses = new ArrayList<>();
            int prevText = 0, prevHanzi = 0;
            for (int ci2 = 0; ci2 < ends.size(); ci2++) {
                int e = ends.get(ci2);
                int hEnd = prevHanzi;
                while (hEnd < hanziChars.size() && hanziTextIdx.get(hEnd) < e) hEnd++;
                Clause c = new Clause();
                c.flat = flat;
                c.idx = ci2;
                c.textStart = prevText;
                c.textEnd = e;
                c.text = text.substring(prevText, e);
                c.hanziStart = prevHanzi;
                c.hanziEnd = hEnd;
                clauses.add(c);
                prevText = e;
                prevHanzi = hEnd;
            }
            d.clausesBySent.add(clauses);

            int startUnit = -1;
            for (int ci = 0; ci < nHanzi; ci++) {
                Unit u = new Unit();
                u.global = d.units.size();
                u.flat = flat;
                u.ci = ci;
                u.hanzi = hanziChars.get(ci);
                u.pyToned = s.pinyin.get(ci);
                u.py = Syllables.toneless(u.pyToned);
                // 定位小句：hanziTextIdx[ci] 落入的 clause
                int tIdx = hanziTextIdx.get(ci);
                int cl = clauses.size() - 1;
                for (int k = 0; k < clauses.size(); k++) {
                    if (tIdx < clauses.get(k).textEnd) { cl = k; break; }
                }
                u.clause = cl;
                d.units.add(u);
                if (startUnit < 0) startUnit = u.global;

                // 繁/简两体都登记到字→音节映射（ASR 多输出简体）
                d.addCharSyllable(u.hanzi, u.py);
                if (ci < hanziCharsS.size()) d.addCharSyllable(hanziCharsS.get(ci), u.py);
            }
            d.sentUnitStart.add(startUnit);
        }
        return d;
    }

    private void addCharSyllable(String ch, String py) {
        Set<String> set = charSyllables.get(ch);
        if (set == null) {
            set = new HashSet<>();
            charSyllables.put(ch, set);
        }
        set.add(py);
    }

    /** unit 下标 → 所在句 flat；越界返回 -1 */
    public int flatOfUnit(int unitIdx) {
        if (unitIdx < 0 || unitIdx >= units.size()) return -1;
        return units.get(unitIdx).flat;
    }

    /** 某句的小句（clause 下标越界返回 null） */
    public Clause clauseOf(int flat, int clauseIdx) {
        if (flat < 0 || flat >= clausesBySent.size()) return null;
        List<Clause> cs = clausesBySent.get(flat);
        if (clauseIdx < 0 || clauseIdx >= cs.size()) return null;
        return cs.get(clauseIdx);
    }
}
