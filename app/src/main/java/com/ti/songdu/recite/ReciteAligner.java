package com.ti.songdu.recite;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * v1.16.0-dev 识读：流式对齐器（纯 Java，可桌面单测）。
 *
 * 输入：ASR 当前段（两次静音端点之间）的最新全文（partial 会修订，故每次整段重对齐）。
 * 方法：把识别文本逐字查「字→音节集合」得到识别音节序列，与期望流在指针附近开窗做
 * Levenshtein 对齐（match=音节相等，含多音字集合命中）；只提交「稳定前缀」——
 * 事件（替换/漏字/多字）必须有锚点（前后都有命中）才确认落盘，不确定的放过记日志，
 * 守「只标内容硬错、不误伤」的口径。进度（tentative）与提交（committed）分开：
 * 前者供跟随显示，后者在确认后才推进指针与发事件。
 */
public final class ReciteAligner {

    public static final class ErrorEvent {
        public static final int SUB = 1;  // 读错音节：unit 处的字被读成 heard
        public static final int DEL = 2;  // 漏读：unit 处的字没读出
        public static final int INS = 3;  // 多读：unit 之后多读了 heard（unit 可为 -1 表示句首之前）
        public int type;
        public int unit;
        public String heard;
    }

    public interface Listener {
        void onError(ErrorEvent e);
        /** tentative 进度：已对齐到的最后一个 unit 下标（显示跟随用，未提交） */
        void onProgress(int tentativeUnit);
        /** 放过的不确定片段（只记日志，不标红） */
        void onUncertain(String note);
    }

    // ---- 可调常量（真机调参入口） ----
    /** 窗口回溯：允许对齐略回到指针之前（partial 修订/段界错位） */
    static final int BACKTRACK = 4;
    /** 窗口前探余量：窗口终点 = ptr + 本段 token 数 + 此余量。
     *  读出的字数不可能超过 token 数＋少量出入；窗口开太大，DP 会把文本
     *  对齐到后文相似的排比句上「瞬移」（桌面实测教训：140 的固定前探
     *  在 17 token 的段上产生了整片假漏读）。 */
    static final int AHEAD_MARGIN = 24;
    /** 对齐代价（×100 标度）：等编辑数时优先替换、其次插/删，
     *  避免 DP 用「删首字＋整体错位」拼出等价低分对齐 */
    static final int COST_SUB = 100;
    static final int COST_INS = 101;
    static final int COST_DEL = 101;
    /** 提交稳定性：事件/进度要被提交，其后须再有这么多已对齐 token */
    static final int STABLE_AFTER = 5;
    /** 事件确认锚点：其后命中数下限 */
    static final int ANCHOR_AFTER = 2;
    /** 事件确认锚点：其前命中数下限（段首事件可用其后命中补偿） */
    static final int ANCHOR_BEFORE = 1;
    /** 触发全局重锚的最低 token 数与本窗命中率上限 */
    static final int REANCHOR_MIN_TOKENS = 12;
    static final double REANCHOR_BAD_RATIO = 0.45;
    static final double REANCHOR_GOOD_RATIO = 0.70;

    private final ReciteData data;
    private final Listener listener;
    private int ptr;                 // 已提交指针（下一个期望 unit 的 global 下标）
    private int segId = -1;
    private boolean reanchorTriedInSeg = false;
    /** 本段已发出的 INS 事件键（unit|heard），防 partial 重算时重复发同一多读 */
    private final java.util.Set<String> emittedIns = new java.util.HashSet<>();
    /**
     * partial 每次送来整段全文，而 ptr 会随提交推进到段中——若拿全文对当前窗口
     * 重对齐，段首的已读 token 无处安放会毒化对齐（实测：整段后半被判低命中而
     * 整段丢弃）。sherpa 的 partial 每个 token 自带时间戳，故按时间切后缀：
     * 只对齐 time 大于已消费时刻的 token。已消费时刻 consumedTime = 已提交 op
     * 所配 token 的最大时间戳；回留 OVERLAP_S 秒重叠，容忍 ASR 对最近内容的修订。
     * 曾试「按 unit 位移估算已覆盖字数」与「按 op 数记账」，皆因稳定性节流造成
     * 的 ptr 滞后（落后真实位置约 STABLE_AFTER 个 token）而失真；时间戳是识别器
     * 给的精确值，无需估算（实测：unit 估算曾把 drop 算少 8 个 token，漏出假 INS）。
     */
    private float consumedTime = Float.NEGATIVE_INFINITY;
    static final float OVERLAP_S = 1.2f;

    public ReciteAligner(ReciteData data, Listener listener) {
        this.data = data;
        this.listener = listener;
    }

    /** 从某句开始（识读入口/跳句时调用）：指针设到该句首 unit，不补发历史事件 */
    public void startAtSentence(int flat) {
        if (flat >= 0 && flat < data.sentUnitStart.size()) {
            Integer s = data.sentUnitStart.get(flat);
            if (s != null && s >= 0) ptr = s;
        }
        segId++;
        reanchorTriedInSeg = false;
        consumedTime = Float.NEGATIVE_INFINITY;
        emittedIns.clear();
    }

    public int pointer() {
        return ptr;
    }

    /** 识别文本的一个 token：一个汉字对应的可能音节集合 */
    private static final class Tok {
        Set<String> sylls;   // null 表示该字不在本书字表中（必不命中）
        String raw;          // 原字（日志用）
        String single;       // sylls 恰一个时即它，否则 null（事件 heard 取值用）
        float time = Float.NaN; // 该 token 在音频中的时间戳（秒），未知为 NaN
    }

    private List<Tok> tokenize(String text) {
        List<Tok> out = new ArrayList<>();
        if (text == null) return out;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (Syllables.isCjk(cp)) {
                String ch = new String(Character.toChars(cp));
                Tok t = new Tok();
                t.raw = ch;
                t.sylls = data.charSyllables.get(ch);
                if (t.sylls == null) {
                    // 兜底：字不在本书字表时用通用读音表（多音全集，同音字互换不误报）
                    t.sylls = PinyinTable.syllablesOf(ch);
                }
                if (t.sylls != null && t.sylls.size() == 1) t.single = t.sylls.iterator().next();
                out.add(t);
            }
            i += Character.charCount(cp);
        }
        return out;
    }

    // 对齐操作
    private static final int OP_MATCH = 0, OP_SUB = 1, OP_DEL = 2, OP_INS = 3;

    private static final class Op {
        int kind;
        int unit;      // MATCH/SUB/DEL 的 unit；INS 为其前一个 unit（可为 winStart-1）
        Tok tok;       // SUB/INS 的识别 token
    }

    /** 对 tokens 与 expected[winStart, winEnd) 做编辑距离对齐，返回操作序列 */
    private List<Op> align(List<Tok> toks, int winStart, int winEnd) {
        int n = toks.size(), m = winEnd - winStart;
        int[][] dp = new int[n + 1][m + 1];
        for (int i = 1; i <= n; i++) dp[i][0] = i * COST_INS;
        for (int j = 1; j <= m; j++) dp[0][j] = j * COST_DEL;
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                ReciteData.Unit u = data.units.get(winStart + j - 1);
                boolean match = toks.get(i - 1).sylls != null && toks.get(i - 1).sylls.contains(u.py);
                int diag = dp[i - 1][j - 1] + (match ? 0 : COST_SUB);
                int ins = dp[i - 1][j] + COST_INS;   // 识别多出一个 token
                int del = dp[i][j - 1] + COST_DEL;   // 期望多出一个 unit（漏读）
                dp[i][j] = Math.min(diag, Math.min(ins, del));
            }
        }
        // 自由尾端（fitting 对齐）：回溯起点取 dp[n][j] 最小处、同分取最小 j。
        // 窗口尾部未被 token 覆盖的 unit 不计删除代价——否则尾部删除代价恒定，
        // DP 对「最后一个 token 落在哪个同字 unit」无差异，会漂移到最远的同字处
        // （实测：在 被对齐到第三个 在、中间整句成假漏读）。窗口内部的跳字仍计 DEL。
        int bestJ = 0;
        for (int j = 1; j <= m; j++) {
            if (dp[n][j] < dp[n][bestJ]) bestJ = j;
        }
        // 回溯（优先序：对角 > 删 > 插，偏向把差异解释为替换/漏读而非多读）
        List<Op> rev = new ArrayList<>();
        int i = n, j = bestJ;
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0) {
                ReciteData.Unit u = data.units.get(winStart + j - 1);
                boolean match = toks.get(i - 1).sylls != null && toks.get(i - 1).sylls.contains(u.py);
                if (dp[i][j] == dp[i - 1][j - 1] + (match ? 0 : COST_SUB)) {
                    Op op = new Op();
                    op.kind = match ? OP_MATCH : OP_SUB;
                    op.unit = winStart + j - 1;
                    op.tok = toks.get(i - 1);
                    rev.add(op);
                    i--; j--;
                    continue;
                }
            }
            if (j > 0 && dp[i][j] == dp[i][j - 1] + COST_DEL) {
                Op op = new Op();
                op.kind = OP_DEL;
                op.unit = winStart + j - 1;
                rev.add(op);
                j--;
                continue;
            }
            // INS
            Op op = new Op();
            op.kind = OP_INS;
            op.unit = winStart + j - 1;   // 其前一个 unit（j 可能为 0 → winStart-1）
            op.tok = toks.get(i - 1);
            rev.add(op);
            i--;
        }
        List<Op> ops = new ArrayList<>(rev.size());
        for (int k = rev.size() - 1; k >= 0; k--) ops.add(rev.get(k));
        return ops;
    }

    /** op k 之后/之前最近一个 MATCH 的 op 距离；没有返回 Integer.MAX_VALUE */
    private static int nextMatchDist(List<Op> ops, int k) {
        for (int q = k + 1; q < ops.size(); q++) {
            if (ops.get(q).kind == OP_MATCH) return q - k;
        }
        return Integer.MAX_VALUE;
    }

    private static int prevMatchDist(List<Op> ops, int k) {
        for (int q = k - 1; q >= 0; q--) {
            if (ops.get(q).kind == OP_MATCH) return k - q;
        }
        return Integer.MAX_VALUE;
    }

    /**
     * 喂入当前段的最新识别全文（无时间戳版，桌面单句测试用）。
     * isFinal=true 表示该段已端点结束：放宽稳定性要求做最终提交。
     */
    public void updateSegment(String text, boolean isFinal) {
        updateTokens(tokenize(text), isFinal);
    }

    /** 带时间戳版：texts/times 为识别器给的 token 及其时间戳（秒）。
     *  多字 token 按字展开、共享该 token 的时间戳。 */
    public void updateSegmentTimed(java.util.List<String> texts,
                                   java.util.List<Float> times, boolean isFinal) {
        List<Tok> all = new ArrayList<>();
        for (int k = 0; k < texts.size(); k++) {
            float t = (times != null && k < times.size() && times.get(k) != null)
                    ? times.get(k) : Float.NaN;
            for (Tok tok : tokenize(texts.get(k))) {
                tok.time = t;
                all.add(tok);
            }
        }
        updateTokens(all, isFinal);
    }

    private void updateTokens(List<Tok> all, boolean isFinal) {
        if (all.isEmpty()) {
            if (isFinal) {
                segId++;
                consumedTime = Float.NEGATIVE_INFINITY;
            }
            return;
        }
        // 按时间切后缀：丢掉已消费（时间 ≤ consumedTime − OVERLAP_S）的 token
        int drop = 0;
        if (consumedTime != Float.NEGATIVE_INFINITY) {
            while (drop < all.size()) {
                float t = all.get(drop).time;
                if (!Float.isNaN(t) && t <= consumedTime - OVERLAP_S) drop++;
                else break;
            }
        }
        List<Tok> toks = drop > 0 ? all.subList(drop, all.size()) : all;
        int winStart = Math.max(0, ptr - BACKTRACK);
        int winEnd = Math.min(data.units.size(), ptr + toks.size() + AHEAD_MARGIN);
        List<Op> ops = align(toks, winStart, winEnd);

        // 命中率太低 → 可能用户跳读/重读别处：尝试一次全局重锚（不发事件）
        int matches = 0;
        for (Op op : ops) if (op.kind == OP_MATCH) matches++;
        double ratio = (double) matches / toks.size();

        // 段首软重同步：新段开头几个字与 ptr 处对不上（段界把上段尾字切走、
        // 或用户小跳几字）时，在 ptr 后 16 字内找命中更好的起点，静默挪指针、
        // 不给被越过的字记漏读（段界归属不清，不误伤）。仅段首（未消费）时尝试。
        if (drop == 0 && toks.size() >= 4 && ratio < 0.5) {
            int bestStart = -1, bestMatches = matches;
            List<Op> bestOps = null;
            for (int cand = ptr + 1; cand <= Math.min(data.units.size() - 1, ptr + 16); cand++) {
                int ws = Math.max(0, cand - BACKTRACK);
                int we = Math.min(data.units.size(), cand + toks.size() + AHEAD_MARGIN);
                List<Op> o = align(toks, ws, we);
                int mt = 0;
                for (Op op : o) if (op.kind == OP_MATCH) mt++;
                if (mt > bestMatches) {
                    bestMatches = mt;
                    bestStart = cand;
                    bestOps = o;
                }
            }
            if (bestStart > 0 && (double) bestMatches / toks.size() >= 0.55) {
                listener.onUncertain("seg-start resync ptr " + ptr + " -> " + bestStart);
                ptr = bestStart;
                consumedTime = Float.NEGATIVE_INFINITY; // 本轮 commit 按新位置重建消费时刻
                ops = bestOps;
                matches = bestMatches;
                ratio = (double) matches / toks.size();
            }
        }
        if (!reanchorTriedInSeg && toks.size() >= REANCHOR_MIN_TOKENS
                && ratio < REANCHOR_BAD_RATIO) {
            reanchorTriedInSeg = true;
            int best = searchAnchor(toks);
            if (best >= 0 && Math.abs(best - ptr) > 12) {
                listener.onUncertain("reanchor ptr " + ptr + " -> " + best);
                ptr = best;
                consumedTime = Float.NEGATIVE_INFINITY; // 位置跳变：下一轮在新位置重建
                return; // 本轮不发事件，下一轮 partial 在新位置对齐
            }
        }
        // 段质量门：命中率过低的对齐整体不可信——不提交、不发事件、不推进显示，
        // 只记日志（守「不误伤」：宁可这段白读，不能把指针带跑、把对的字标红）
        if (toks.size() >= 6 && ratio < 0.5) {
            listener.onUncertain("low-match seg ratio=" + String.format("%.2f", ratio)
                    + " toks=" + toks.size() + " ptr=" + ptr);
            if (isFinal) {
                segId++;
                reanchorTriedInSeg = false;
                emittedIns.clear();
                consumedTime = Float.NEGATIVE_INFINITY;
            }
            return;
        }

        // tentative 进度：最后一个 MATCH/SUB 覆盖的 unit
        int lastCovered = -1;
        for (Op op : ops) {
            if (op.kind == OP_MATCH || op.kind == OP_SUB) lastCovered = Math.max(lastCovered, op.unit);
        }
        if (lastCovered >= ptr) listener.onProgress(lastCovered);

        commit(ops, isFinal);
        if (isFinal) {
            segId++;
            reanchorTriedInSeg = false;
            emittedIns.clear();
            consumedTime = Float.NEGATIVE_INFINITY;
        }
    }

    /**
     * 提交稳定前缀：推进 ptr 并发出确认的事件。
     * 铁律：在第一个「未确认事件」处停下，绝不越过它继续提交后面的内容——
     * 否则一处错位会对齐出整片假事件、把指针带跑（桌面实测教训）。
     * 事件确认＝距离锚点：其前 8 个 op 内有命中（或它在段首 3 个 op 内），
     * 且其后 14 个 op 内有命中（整小句漏读时，漏读串里每字都满足此条）。
     */
    private void commit(List<Op> ops, boolean isFinal) {
        int newPtr = ptr;
        for (int k = 0; k < ops.size(); k++) {
            Op op = ops.get(k);
            // 指针之前的回溯区：只看不提交（已在先前 partial 提交过）
            if (op.unit < ptr && op.kind != OP_INS) continue;
            if (op.kind == OP_INS && op.unit < ptr - 1) continue;
            // 稳定性：该 op 之后要有足够多已对齐 token（final 时免除）
            if (!isFinal) {
                int alignedAfter = 0;
                for (int q = k + 1; q < ops.size(); q++) {
                    if (ops.get(q).kind != OP_DEL) alignedAfter++;
                }
                if (alignedAfter < STABLE_AFTER) break;
            }
            if (op.kind == OP_MATCH) {
                newPtr = Math.max(newPtr, op.unit + 1);
                markConsumed(op);
                continue;
            }
            // 事件 op：距离锚点判定
            int prevD = prevMatchDist(ops, k);
            int nextD = nextMatchDist(ops, k);
            boolean anchored = (prevD <= 8 || k < 3) && nextD <= 14;
            if (!anchored) {
                listener.onUncertain("evt-unconfirmed kind " + op.kind + " unit " + op.unit
                        + " prevD=" + prevD + " nextD=" + (nextD == Integer.MAX_VALUE ? -1 : nextD));
                break; // 停在未确认事件之前，等更多上下文（或下一段重对齐）
            }
            ErrorEvent e = new ErrorEvent();
            e.type = op.kind == OP_SUB ? ErrorEvent.SUB : op.kind == OP_DEL ? ErrorEvent.DEL : ErrorEvent.INS;
            e.unit = op.unit;
            e.heard = op.tok != null ? (op.tok.single != null ? op.tok.single : op.tok.raw) : null;
            if (e.type == ErrorEvent.INS && !emittedIns.add(e.unit + "|" + e.heard)) {
                markConsumed(op);
                continue; // 同一段内同一多读已发过（partial 重算），不重复计
            }
            listener.onError(e);
            if (op.kind != OP_INS) newPtr = Math.max(newPtr, op.unit + 1);
            markConsumed(op);
        }
        ptr = Math.max(ptr, newPtr);
    }

    /** 已提交 op 所配 token 的时间戳推进已消费时刻（DEL 无 token，跳过） */
    private void markConsumed(Op op) {
        if (op.tok != null && !Float.isNaN(op.tok.time)) {
            consumedTime = Math.max(consumedTime, op.tok.time);
        }
    }

    /** 调试用：返回当前指针下对 text 的对齐操作序列（不提交、不发事件） */
    public String debugAlign(String text) {
        List<Tok> toks = tokenize(text);
        int winStart = Math.max(0, ptr - BACKTRACK);
        int winEnd = Math.min(data.units.size(), ptr + toks.size() + AHEAD_MARGIN);
        List<Op> ops = align(toks, winStart, winEnd);
        StringBuilder sb = new StringBuilder();
        for (Op op : ops) {
            if (sb.length() > 0) sb.append(' ');
            String k = op.kind == OP_MATCH ? "M" : op.kind == OP_SUB ? "S" : op.kind == OP_DEL ? "D" : "I";
            sb.append(k).append(op.unit);
            if (op.tok != null) sb.append('(').append(op.tok.raw).append(')');
        }
        return "win[" + winStart + "," + winEnd + ") " + sb;
    }

    /** 全局重锚：在整本书上找与当前识别最匹配的位置（粗粒度滑窗＋DP 命中率） */
    private int searchAnchor(List<Tok> toks) {
        int probe = Math.min(toks.size(), 24);
        List<Tok> head = toks.subList(0, probe);
        int bestStart = -1;
        double bestRatio = 0;
        int n = data.units.size();
        for (int start = 0; start < n; start += 8) {
            int end = Math.min(n, start + probe + 30);
            List<Op> ops = align(head, start, end);
            int mt = 0;
            for (Op op : ops) if (op.kind == OP_MATCH) mt++;
            double ratio = (double) mt / probe;
            if (ratio > bestRatio) {
                bestRatio = ratio;
                // 命中序列里第一个 MATCH 的 unit 作为新指针候选
                for (Op op : ops) {
                    if (op.kind == OP_MATCH) { bestStart = op.unit; break; }
                }
            }
        }
        return bestRatio >= REANCHOR_GOOD_RATIO ? bestStart : -1;
    }
}
