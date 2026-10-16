package com.ti.songdu;

import java.util.ArrayList;
import java.util.List;

/**
 * v1.7 跟读分段（纯函数，无 Android 依赖，可独立编译测试）。
 *
 * 输入：一首曲目的句子序列（已按 ContentRepo 匹配＋时长缩放后的时间轴），
 * 每句带 textT（繁体，字数口径与繁简显示无关）与句内可停标点点位 breaks
 * （数据由 gen_breaks.py 用逐字对齐＋静音吸附生成；点位只落在 ，。；！？ 五种标点）。
 * 输出：跟读段序列——状态机按段推进（播到段 endMs 停、gap 后播下一段）。
 *
 * 规则（用户口径）：
 * - 字数＝正文汉字数（不计标点、空白），用繁体 textT 计。
 * - 贪心组段：依次累加整句，加入后总字数 ≤ 上限则并入，否则收段。
 * - 单句超上限时在句内按 breaks 切分句，分句贪心组装成 ≤ 上限的子段；
 *   切分点只能取 breaks 或句界——单个分句仍超上限且其内部无 breaks 可用时，
 *   才按字数硬切（时间按汉字比例插值，该句会在自检报告中列明）。
 * - limit ≤ 0 表示「整句」：不合并、不拆分，一句一段（v1.6 以前的旧行为）。
 * - 段不跨曲目（输入即单曲句子序列）；可跨节/章。
 */
public final class FollowChunker {

    public static final class Chunk {
        public long startMs;
        public long endMs;
        /** 段起止句在拉平句表中的下标（阅读页高亮联动用） */
        public int firstFlat;
        public int lastFlat;
        /** 段内汉字数（自检用） */
        public int han;
    }

    /** 输入句（时间已缩放；breakMs 与 breakPos 一一对应、已按同一系数缩放） */
    public static final class SIn {
        public long startMs;
        public long endMs;
        public int flat;
        public String textT;
        public int[] breakPos; // 标点在 textT 的 UTF-16 下标，升序，可为 null
        public long[] breakMs;  // 与 breakPos 平行，可为 null

        public SIn(long startMs, long endMs, int flat, String textT,
                   int[] breakPos, long[] breakMs) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.flat = flat;
            this.textT = textT;
            this.breakPos = breakPos;
            this.breakMs = breakMs;
        }
    }

    private FollowChunker() {
    }

    static boolean isHan(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0x20000 && cp <= 0x2A6DF) || (cp >= 0xF900 && cp <= 0xFAFF);
    }

    /** [fromU16, toU16) 范围内的汉字数 */
    static int hanCount(String text, int fromU16, int toU16) {
        int n = 0;
        int i = fromU16;
        while (i < toU16 && i < text.length()) {
            int cp = Character.codePointAt(text, i);
            if (isHan(cp)) n++;
            i += Character.charCount(cp);
        }
        return n;
    }

    private static boolean isCloser(char c) {
        return "」』）》”’】〕〉".indexOf(c) >= 0;
    }

    /** 一个分句：句内被 breaks 切开的一段（或整句） */
    private static final class Clause {
        long startMs, endMs;
        int startU16, endU16;
        int han;
    }

    /** 句 → 分句列表：切口在 breaks 处，标点后紧跟的收尾符归前一分句 */
    private static List<Clause> clausesOf(SIn s) {
        List<Clause> out = new ArrayList<>();
        String t = s.textT == null ? "" : s.textT;
        int prevU = 0;
        long prevT = s.startMs;
        if (s.breakPos != null && s.breakMs != null) {
            for (int i = 0; i < s.breakPos.length; i++) {
                int cut = s.breakPos[i] + 1; // 标点均为 BMP 单字
                if (cut <= prevU || cut > t.length()) continue;
                while (cut < t.length() && isCloser(t.charAt(cut))) cut++;
                Clause c = new Clause();
                c.startU16 = prevU;
                c.endU16 = cut;
                c.startMs = prevT;
                c.endMs = s.breakMs[i];
                c.han = hanCount(t, prevU, cut);
                out.add(c);
                prevU = cut;
                prevT = s.breakMs[i];
            }
        }
        Clause last = new Clause();
        last.startU16 = prevU;
        last.endU16 = t.length();
        last.startMs = prevT;
        last.endMs = s.endMs;
        last.han = hanCount(t, prevU, t.length());
        out.add(last);
        // 0 汉字分句（纯标点尾巴）并入前一分句，避免产生空段
        for (int i = out.size() - 1; i > 0; i--) {
            if (out.get(i).han == 0) {
                Clause p = out.get(i - 1);
                p.endU16 = out.get(i).endU16;
                p.endMs = out.get(i).endMs;
                out.remove(i);
            }
        }
        return out;
    }

    /** 段累积器 */
    private static final class Acc {
        long startMs, endMs;
        int firstFlat, lastFlat, han;

        void reset(Clause c, int flat) {
            startMs = c.startMs;
            endMs = c.endMs;
            firstFlat = flat;
            lastFlat = flat;
            han = c.han;
        }

        void add(Clause c, int flat) {
            endMs = c.endMs;
            lastFlat = flat;
            han += c.han;
        }

        Chunk build() {
            Chunk ch = new Chunk();
            ch.startMs = startMs;
            ch.endMs = endMs;
            ch.firstFlat = firstFlat;
            ch.lastFlat = lastFlat;
            ch.han = han;
            return ch;
        }
    }

    public static List<Chunk> chunk(List<SIn> sentences, int limit) {
        List<Chunk> out = new ArrayList<>();
        if (sentences == null) return out;
        if (limit <= 0) { // 整句模式：一句一段
            for (SIn s : sentences) {
                Chunk ch = new Chunk();
                ch.startMs = s.startMs;
                ch.endMs = s.endMs;
                ch.firstFlat = s.flat;
                ch.lastFlat = s.flat;
                ch.han = hanCount(s.textT == null ? "" : s.textT, 0,
                        s.textT == null ? 0 : s.textT.length());
                out.add(ch);
            }
            return out;
        }
        Acc cur = null; // 正在累积的整句段
        for (SIn s : sentences) {
            List<Clause> cs = clausesOf(s);
            int totalHan = 0;
            for (Clause c : cs) totalHan += c.han;
            if (totalHan == 0) continue; // 无汉字句不进跟读轴（理论上不存在）
            if (cs.size() == 1 && totalHan <= limit) {
                // 整句作一个单位贪心并入
                Clause only = cs.get(0);
                if (cur == null) {
                    cur = new Acc();
                    cur.reset(only, s.flat);
                } else if (cur.han + totalHan <= limit) {
                    cur.add(only, s.flat);
                } else {
                    out.add(cur.build());
                    cur = new Acc();
                    cur.reset(only, s.flat);
                }
                continue;
            }
            // 需拆句：先收掉累积段，再在本句内组装子段
            if (cur != null) {
                out.add(cur.build());
                cur = null;
            }
            Acc sub = null;
            for (Clause c : cs) {
                if (c.han > limit) {
                    if (sub != null) {
                        out.add(sub.build());
                        sub = null;
                    }
                    hardSplit(s, c, limit, out);
                } else if (sub == null) {
                    sub = new Acc();
                    sub.reset(c, s.flat);
                } else if (sub.han + c.han <= limit) {
                    sub.add(c, s.flat);
                } else {
                    out.add(sub.build());
                    sub = new Acc();
                    sub.reset(c, s.flat);
                }
            }
            if (sub != null) out.add(sub.build());
            // 注意：拆过的长句收尾子段不与下一句合并（它不是整句单位）
        }
        if (cur != null) out.add(cur.build());
        return out;
    }

    /** 单个分句仍超上限且内部无 breaks 可切：按字数硬切，时间按汉字比例插值 */
    private static void hardSplit(SIn s, Clause c, int limit, List<Chunk> out) {
        String t = s.textT == null ? "" : s.textT;
        int hanSeen = 0;
        long pieceStart = c.startMs;
        int pieceHan = 0;
        int i = c.startU16;
        while (i < c.endU16) {
            int cp = Character.codePointAt(t, i);
            int next = i + Character.charCount(cp);
            if (isHan(cp)) {
                hanSeen++;
                pieceHan++;
                if (pieceHan == limit && hanSeen < c.han) {
                    int cut = next;
                    while (cut < c.endU16 && isCloser(t.charAt(cut))) cut++;
                    long cutT = c.startMs + (long) ((c.endMs - c.startMs)
                            * (hanSeen / (double) c.han));
                    Chunk ch = new Chunk();
                    ch.startMs = pieceStart;
                    ch.endMs = cutT;
                    ch.firstFlat = s.flat;
                    ch.lastFlat = s.flat;
                    ch.han = pieceHan;
                    out.add(ch);
                    pieceStart = cutT;
                    pieceHan = 0;
                }
            }
            i = next;
        }
        Chunk ch = new Chunk();
        ch.startMs = pieceStart;
        ch.endMs = c.endMs;
        ch.firstFlat = s.flat;
        ch.lastFlat = s.flat;
        ch.han = pieceHan;
        out.add(ch);
    }
}
