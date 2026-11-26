package com.ti.songdu;

import java.util.ArrayList;
import java.util.List;

/**
 * v1.31.0 念注触发计划（纯逻辑、无 Android 依赖，桌面 harness 直驱本类验收）。
 *
 * 职责：把一本书的逐句注释数据（四家 NoteRef 等价输入）＋逐句时间轴，按
 * 「选定版本优先、缺则按固定次序回退」口径（与 ReaderActivity.displayNote /
 * reader.html 的 noteFor 完全一致）解析成一张「念注触发表」：每条 cue 对应
 * 一条要在正文里念出来的注。
 *
 * 触发句口径：注在数据里是块注——一条注覆盖连续若干句（startFlat..endFlat），
 * 解析结果挂在被覆盖的每一句上；只有当本句恰是解析出那块的末句
 * （flat == ref.endFlat）时才在本句生成一条 cue，与阅读页「只显一次」
 * （注文只在块末句落点渲染）同口径。嵌套块（集注小块套在大块里）时本句的
 * 解析结果是最贴身（起点最晚）的那块，故内块先在自己的末句触发、外块再在
 * 外块末句触发，与竖排注栏任一时刻显示的那块一致。
 *
 * 触发时刻（fireMs，音频轴毫秒、已乘 scale）：触发句有录音 → 本句 endMs；
 * 触发句无录音（noAudio，如补传句）→ 其后第一句有录音句的 startMs（正文
 * 播到那里即等于刚越过本句）；再其后也没有 → 其前最后一句有录音句的
 * endMs；全书无录音句 → 不出 cue。
 *
 * cue 顺序：按 fireMs 升序，同点按 flat 升序（Tracker 按此序逐条消费）。
 */
public final class NoteCuePlan {

    /** 版本固定次序（回退口径，与 ReaderActivity / reader.html 一致） */
    public static final String[] VER_ORDER = {"zhijie", "jizhu", "gengfu", "daodu"};

    /** 一家注对一句的覆盖（等价于 ContentRepo.NoteRef；text 可为 null 表示该家无此数据行） */
    public static final class RefIn {
        public String text;
        public int startFlat;
        public int endFlat;

        public RefIn(String text, int startFlat, int endFlat) {
            this.text = text;
            this.startFlat = startFlat;
            this.endFlat = endFlat;
        }
    }

    /** 一句的输入：时间轴＋四家覆盖（无覆盖为 null） */
    public static final class SentIn {
        public long startMs = -1;
        public long endMs = -1;
        public boolean noAudio;
        public String id;
        public RefIn zj, jz, gf, dd;
    }

    /** 一条念注触发 */
    public static final class Cue {
        /** 触发句（块末句）的拉平下标 */
        public int flat;
        /** 注块覆盖范围（拉平下标，含两端），供阅读页视觉标出 */
        public int blockStart;
        public int blockEnd;
        /** 实际念的那家（\"zhijie\"/\"jizhu\"/\"gengfu\"/\"daodu\"） */
        public String ver;
        /** 注文全文 */
        public String text;
        /** 触发句 id */
        public String sentId;
        /** 注块键：块首句 id——与注数据条目键（注文件里挂该条注的那个句 id）、
         *  云端注音频包 manifest 的 key 同口径，引擎按它取包内音频 */
        public String keySentId;
        /** 触发时刻（音频轴毫秒，已乘 scale） */
        public long fireMs;
    }

    private NoteCuePlan() {
    }

    private static RefIn refOf(SentIn s, String ver) {
        if ("zhijie".equals(ver)) return s.zj;
        if ("jizhu".equals(ver)) return s.jz;
        if ("gengfu".equals(ver)) return s.gf;
        return s.dd;
    }

    /**
     * 本句要念的注：优先选定版本，无块覆盖才按 VER_ORDER 固定次序回退其他家；
     * usedVerOut[0] 回报实际用的哪家。无任何一家覆盖时返回 null。
     * 与 ReaderActivity.displayNote 同一判定，本类是其纯逻辑镜像（harness 对账）。
     */
    public static RefIn resolve(SentIn s, String selectedVer, String[] usedVerOut) {
        if (s == null) return null;
        RefIn r = refOf(s, selectedVer);
        if (r != null && r.text != null && r.text.length() > 0) {
            if (usedVerOut != null) usedVerOut[0] = selectedVer;
            return r;
        }
        for (String v : VER_ORDER) {
            if (v.equals(selectedVer)) continue;
            r = refOf(s, v);
            if (r != null && r.text != null && r.text.length() > 0) {
                if (usedVerOut != null) usedVerOut[0] = v;
                return r;
            }
        }
        return null;
    }

    /** 建触发表；sents 为全书拉平序（下标即 flat），scale 为句时间缩放系数 */
    public static List<Cue> build(List<SentIn> sents, String selectedVer, double scale) {
        List<Cue> out = new ArrayList<>();
        if (sents == null || sents.isEmpty()) return out;
        int n = sents.size();
        // 每句的有声起止（音频轴）：无录音句为 -1
        long[] aStart = new long[n];
        long[] aEnd = new long[n];
        for (int i = 0; i < n; i++) {
            SentIn s = sents.get(i);
            if (!s.noAudio && s.startMs >= 0 && s.endMs > s.startMs) {
                aStart[i] = Math.round(s.startMs * scale);
                aEnd[i] = Math.round(s.endMs * scale);
            } else {
                aStart[i] = -1;
                aEnd[i] = -1;
            }
        }
        String[] used = new String[1];
        for (int i = 0; i < n; i++) {
            SentIn s = sents.get(i);
            RefIn ref = resolve(s, selectedVer, used);
            if (ref == null) continue;
            if (ref.endFlat != i) continue; // 只在块末句触发（只显一次同口径）
            long fire;
            if (aEnd[i] >= 0) {
                fire = aEnd[i];
            } else {
                fire = -1;
                for (int j = i + 1; j < n; j++) {
                    if (aStart[j] >= 0) { fire = aStart[j]; break; }
                }
                if (fire < 0) {
                    for (int j = i - 1; j >= 0; j--) {
                        if (aEnd[j] >= 0) { fire = aEnd[j]; break; }
                    }
                }
                if (fire < 0) continue; // 全书无录音句，无从触发
            }
            Cue c = new Cue();
            c.flat = i;
            c.blockStart = ref.startFlat;
            c.blockEnd = ref.endFlat;
            c.ver = used[0];
            c.text = ref.text;
            c.sentId = s.id;
            c.keySentId = (ref.startFlat >= 0 && ref.startFlat < n
                    && sents.get(ref.startFlat).id != null)
                    ? sents.get(ref.startFlat).id : s.id;
            c.fireMs = fire;
            out.add(c);
        }
        out.sort((x, y) -> x.fireMs != y.fireMs
                ? Long.compare(x.fireMs, y.fireMs)
                : Integer.compare(x.flat, y.flat));
        return out;
    }
}
