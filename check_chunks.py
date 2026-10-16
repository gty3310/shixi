# -*- coding: utf-8 -*-
"""v1.7 分段自检：真实 Java FollowChunker（javac 编译运行）与本文件内的 Python
镜像实现独立计算三本内容包在 10/15/20/25/整句五档的分段，逐段比对必须全等；
并断言：最大段字数、切分点全部落在 breaks/句界（硬切句单独列出）、
时间覆盖完整单调（首段 start＝首句 start、末段 end＝末句 end、段间不重叠）。"""
import base64
import json
import os
import subprocess
import sys

H = os.path.expanduser("~")
PACK_DIR = os.path.join(H, "workspace/songdu-player/app/src/main/assets/content")
JDK = os.path.join(H, "workspace/tools/jdk17/bin")
TMP = "/tmp/chunkcheck"
os.makedirs(TMP, exist_ok=True)
CLOSERS = set("」』）》”’】〕〉")
LIMITS = [10, 15, 20, 25, 0]


def is_han(ch):
    cp = ord(ch)
    return (0x4E00 <= cp <= 0x9FFF or 0x3400 <= cp <= 0x4DBF
            or 0x20000 <= cp <= 0x2A6DF or 0xF900 <= cp <= 0xFAFF)


# ---------- Python 镜像（与 FollowChunker.java 逐行同义） ----------
class SIn:
    def __init__(s, start, end, flat, text, bpos, bms):
        s.start, s.end, s.flat, s.text = start, end, flat, text
        s.bpos, s.bms = bpos, bms
        # u16 下标 -> (cp 下标) 映射辅助
        s.u16_of_cp = []
        acc = 0
        for ch in text:
            s.u16_of_cp.append(acc)
            acc += 2 if ord(ch) > 0xFFFF else 1
        s.u16_len = acc


def han_count(text, from_u16, to_u16, u16_of_cp):
    n = 0
    for i, ch in enumerate(text):
        u = u16_of_cp[i]
        if from_u16 <= u < to_u16 and is_han(ch):
            n += 1
    return n


def clauses_of(s):
    t = s.text
    out = []
    prev_u, prev_t = 0, s.start
    if s.bpos:
        for i, p in enumerate(s.bpos):
            cut = p + 1
            if cut <= prev_u or cut > s.u16_len:
                continue
            # 收尾符顺延（按 cp 找 u16 >= cut 的第一个字符判断）
            while True:
                nxt = None
                for ci, ch in enumerate(t):
                    if s.u16_of_cp[ci] == cut:
                        nxt = ch
                        break
                if nxt is not None and nxt in CLOSERS:
                    cut += 2 if ord(nxt) > 0xFFFF else 1
                else:
                    break
            out.append([prev_u, cut, prev_t, s.bms[i],
                        han_count(t, prev_u, cut, s.u16_of_cp)])
            prev_u, prev_t = cut, s.bms[i]
    out.append([prev_u, s.u16_len, prev_t, s.end,
                han_count(t, prev_u, s.u16_len, s.u16_of_cp)])
    i = len(out) - 1
    while i > 0:
        if out[i][4] == 0:
            out[i - 1][1] = out[i][1]
            out[i - 1][3] = out[i][3]
            out.pop(i)
        i -= 1
    return out  # [startU16, endU16, startMs, endMs, han]


def hard_split(s, c, limit, out):
    t = s.text
    han_seen = 0
    piece_start = c[2]
    piece_han = 0
    for ci, ch in enumerate(t):
        u = s.u16_of_cp[ci]
        if u < c[0] or u >= c[1]:
            continue
        if is_han(ch):
            han_seen += 1
            piece_han += 1
            if piece_han == limit and han_seen < c[4]:
                cut_t = c[2] + int((c[3] - c[2]) * (han_seen / c[4]))
                out.append((piece_start, cut_t, s.flat, s.flat, piece_han))
                piece_start = cut_t
                piece_han = 0
    out.append((piece_start, c[3], s.flat, s.flat, piece_han))


def chunk_py(sins, limit):
    out = []
    if limit <= 0:
        for s in sins:
            out.append((s.start, s.end, s.flat, s.flat,
                        han_count(s.text, 0, s.u16_len, s.u16_of_cp)))
        return out
    cur = None  # [startMs, endMs, firstFlat, lastFlat, han]

    def flush(acc):
        if acc is not None:
            out.append(tuple(acc))

    for s in sins:
        cs = clauses_of(s)
        total = sum(c[4] for c in cs)
        if total == 0:
            continue
        if len(cs) == 1 and total <= limit:
            only = cs[0]
            if cur is None:
                cur = [only[2], only[3], s.flat, s.flat, only[4]]
            elif cur[4] + total <= limit:
                cur[1] = only[3]
                cur[3] = s.flat
                cur[4] += only[4]
            else:
                flush(cur)
                cur = [only[2], only[3], s.flat, s.flat, only[4]]
            continue
        flush(cur)
        cur = None
        sub = None
        for c in cs:
            if c[4] > limit:
                flush(sub)
                sub = None
                hard_split(s, c, limit, out)
            elif sub is None:
                sub = [c[2], c[3], s.flat, s.flat, c[4]]
            elif sub[4] + c[4] <= limit:
                sub[1] = c[3]
                sub[4] += c[4]
            else:
                flush(sub)
                sub = [c[2], c[3], s.flat, s.flat, c[4]]
        flush(sub)
    flush(cur)
    return out


# ---------- Java harness ----------
HARNESS = r"""
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class Harness {
    public static void main(String[] a) throws Exception {
        int limit = Integer.parseInt(a[1]);
        List<String> lines = Files.readAllLines(Paths.get(a[0]));
        List<com.ti.songdu.FollowChunker.SIn> sins = new ArrayList<>();
        for (String ln : lines) {
            if (ln.isEmpty()) continue;
            String[] f = ln.split("\t", -1);
            int[] bp = null; long[] bm = null;
            if (!f[4].isEmpty()) {
                String[] ps = f[4].split(","), ms = f[5].split(",");
                bp = new int[ps.length]; bm = new long[ms.length];
                for (int i = 0; i < ps.length; i++) { bp[i] = Integer.parseInt(ps[i]); bm[i] = Long.parseLong(ms[i]); }
            }
            String text = new String(Base64.getDecoder().decode(f[6]), "UTF-8");
            sins.add(new com.ti.songdu.FollowChunker.SIn(Long.parseLong(f[0]),
                Long.parseLong(f[1]), Integer.parseInt(f[2]), text, bp, bm));
        }
        for (com.ti.songdu.FollowChunker.Chunk c : com.ti.songdu.FollowChunker.chunk(sins, limit))
            System.out.println(c.startMs + "," + c.endMs + "," + c.firstFlat + "," + c.lastFlat + "," + c.han);
    }
}
"""


def java_chunks(sins, limit, tag):
    inp = os.path.join(TMP, "in_%s.txt" % tag)
    with open(inp, "w") as f:
        for s in sins:
            bp = ",".join(str(x) for x in (s.bpos or []))
            bm = ",".join(str(x) for x in (s.bms or []))
            b64 = base64.b64encode(s.text.encode("utf-8")).decode()
            f.write("%d\t%d\t%d\tx\t%s\t%s\t%s\n" % (s.start, s.end, s.flat, bp, bm, b64))
    out = subprocess.run([os.path.join(JDK, "java"), "-cp", TMP, "Harness", inp, str(limit)],
                         capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError(out.stderr)
    res = []
    for ln in out.stdout.strip().split("\n"):
        if ln:
            res.append(tuple(int(x) for x in ln.split(",")))
    return res


def main():
    with open(os.path.join(TMP, "Harness.java"), "w") as f:
        f.write(HARNESS)
    src = os.path.join(H, "workspace/songdu-player/app/src/main/java/com/ti/songdu/FollowChunker.java")
    r = subprocess.run([os.path.join(JDK, "javac"), "-d", TMP,
                        os.path.join(TMP, "Harness.java"), src],
                       capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stderr)
        sys.exit(1)
    hardcut_report = {}
    for name in ["daxue", "zhongyong", "lunyu"]:
        pack = json.load(open(os.path.join(PACK_DIR, name + ".json")))
        # 分组与 App 实际一致：大学/中庸整本一本；论语按篇（bookForTrack 返回单篇 Book，
        # 时间为篇内基准，段不跨篇）
        groups = []
        gflat = 0
        for ci, c in enumerate(pack["chapters"]):
            gs = []
            allowed = set()
            for s in c["sentences"]:
                if s.get("noAudio") or s.get("startMs") is None or s["startMs"] < 0:
                    continue
                bpos = [b["p"] for b in s.get("breaks", [])]
                bms = [b["t"] for b in s.get("breaks", [])]
                gs.append(SIn(s["startMs"], s["endMs"], gflat, s["textT"],
                              bpos or None, bms or None))
                gflat += 1
                allowed.add(s["startMs"])
                allowed.add(s["endMs"])
                allowed.update(bms)
            if name == "lunyu":
                groups.append((gs, allowed))
            elif groups:
                groups[0][0].extend(gs)
                groups[0][1].update(allowed)
            else:
                groups.append((gs, allowed))
        nsent = sum(len(g[0]) for g in groups)
        print("== %s: %d 句, %d 组" % (name, nsent, len(groups)))
        for limit in LIMITS:
            jc_all, maxhan, hard_sents = [], 0, set()
            for gi, (gs, allowed) in enumerate(groups):
                jc = java_chunks(gs, limit, "%s%d" % (name, gi))
                pc = chunk_py(gs, limit)
                assert jc == pc, (name, limit, gi, "JAVA/PY MISMATCH",
                                  [x for x in zip(jc, pc) if x[0] != x[1]][:3],
                                  len(jc), len(pc))
                maxhan = max(maxhan, max(c[4] for c in jc))
                for st, en, ff, lf, _h in jc:
                    for bnd in (st, en):
                        if bnd not in allowed:
                            hard_sents.add(ff)
                # 时间覆盖：本组首尾与单调
                assert jc[0][0] == gs[0].start and jc[-1][1] == gs[-1].end, (name, limit, gi)
                for i in range(1, len(jc)):
                    assert jc[i][0] >= jc[i - 1][1], (name, limit, gi, i)
                jc_all.extend(jc)
            if limit > 0:
                assert maxhan <= limit, (name, limit, maxhan)
            print("  上限 %-3s 段数 %-5d 最大段字数 %-3d 硬切句 %s"
                  % (limit if limit else "整句", len(jc_all), maxhan,
                     sorted(hard_sents) if hard_sents else "无"))
            if hard_sents:
                hardcut_report[(name, limit)] = sorted(hard_sents)
    print("HARD_CUT:", json.dumps({str(k): v for k, v in hardcut_report.items()},
                                  ensure_ascii=False))
    print("ALL CHECKS PASSED")


if __name__ == "__main__":
    main()
