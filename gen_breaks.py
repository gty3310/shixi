# -*- coding: utf-8 -*-
"""v1.7.0 跟读标点级时间戳（breaks）生成管线。

背景：跟读停顿点只许落在句读标点（，。；！？五种；顿号、冒号不停），且切分时间
不许再用字数比例估算——必须有逐字对齐依据。本脚本：

1) 取 ~/workspace/srt/ 已有的 faster-whisper 逐字转录（*_words.json：[字, 起秒, 止秒]），
   与内容包原文（繁体 textT 的汉字流）做全局序列对齐（difflib，与旧 SRT 管线同法；
   ASR 识别错字不影响，字以原文为准、只借时间；章题行等原文没有的字成为插入项自动跳过）。
2) 每句内部每个候选标点（，。；！？且其后句内还有汉字）取一点位：
   点位 =（前一汉字 word end ＋ 后一汉字 word start）/2，
   再用 ffmpeg silencedetect（-40dB / 0.06s，与 precise SRT 同参）在 ±0.3s 内
   吸附到最近静音段的中点。
3) 无逐字依据的点位（ASR 整段漏转，如《大学》淇澳 03:04–03:21；或邻字未对位；
   或整句对位率 <50%）按句内汉字比例兜底，标 e=1（估算）。
4) 点位以「句 startMs ＋（点位 − 本句首字对齐时间）」锚定回内容包时间轴
   （大学锚定到古本序新包时间；论语锚定到篇内时间），厘秒取整（10ms）。
   写回 sentences[].breaks = [{"p": 标点在 textT 中的 UTF-16 下标, "t": ms, "e": 1?}]，
   仅有内部候选标点的句子才带 breaks 字段。
5) 另出分句 SRT（每分句一条，厘秒）到 ~/workspace/your_files/，与覆盖率报告
   build/breaks_work/report.json。

分句切分口径（与 App 内 FollowChunker 完全一致）：分句在 breaks 处断开，
标点后紧跟的收尾符（」』）》”’】〕〉）归前一分句。
"""
import difflib
import json
import os
import re
import subprocess
import sys

H = os.path.expanduser("~")
PACK_DIR = os.path.join(H, "workspace/songdu-player/app/src/main/assets/content")
WORK = os.path.join(H, "workspace/songdu-player/build/breaks_work")
os.makedirs(WORK, exist_ok=True)

BREAK_PUNCT = set("，。；！？")
CLOSERS = set("」』）》”’】〕〉")


def is_han(ch):
    cp = ord(ch)
    return (0x4E00 <= cp <= 0x9FFF or 0x3400 <= cp <= 0x4DBF
            or 0x20000 <= cp <= 0x2A6DF or 0xF900 <= cp <= 0xFAFF)


def u16len(ch):
    return 2 if ord(ch) > 0xFFFF else 1


def round10(x):
    return int(round(x / 10.0)) * 10


# ---------------- 静音段 ----------------
def silences(book, audio):
    cache = os.path.join(WORK, "sil_%s.json" % book)
    if os.path.exists(cache):
        return [tuple(x) for x in json.load(open(cache))]
    out = subprocess.run(
        ["ffmpeg", "-hide_banner", "-i", audio, "-af",
         "silencedetect=noise=-40dB:d=0.06", "-f", "null", "-"],
        capture_output=True, text=True).stderr
    starts = [float(x) for x in re.findall(r"silence_start: ([\d.]+)", out)]
    ends = [float(x) for x in re.findall(r"silence_end: ([\d.]+)", out)]
    sil = list(zip(starts, ends))
    json.dump(sil, open(cache, "w"))
    return sil


def snap(t, sil):
    """在 ±0.3s 内把 t 吸附到最近静音段中点；无则原样返回。"""
    best, bestd = None, 0.3
    for s, e in sil:
        m = (s + e) / 2.0
        d = abs(m - t)
        if d <= bestd:
            best, bestd = m, d
    return best if best is not None else t


# ---------------- 对齐 ----------------
def load_words(paths):
    words = []
    for p in paths:
        for ch, s, e in json.load(open(p)):
            if len(ch) == 1 and is_han(ch):
                words.append((ch, float(s), float(e)))
    words.sort(key=lambda w: w[1])
    return words


def prep_sentence(text):
    """返回 (han_chars, cands)：
    han_chars = 句内汉字列表（阅读顺序）；
    cands = [{p_u16, prev_ord, next_ord, cp_idx}] 内部候选标点。"""
    cps = list(text)
    han_ord = {}   # cp idx -> 汉字序号
    hans = []
    for i, ch in enumerate(cps):
        if is_han(ch):
            han_ord[i] = len(hans)
            hans.append(ch)
    u16 = []
    acc = 0
    for ch in cps:
        u16.append(acc)
        acc += u16len(ch)
    cands = []
    for i, ch in enumerate(cps):
        if ch in BREAK_PUNCT:
            prev_o = next_o = None
            for j in range(i - 1, -1, -1):
                if j in han_ord:
                    prev_o = han_ord[j]
                    break
            for j in range(i + 1, len(cps)):
                if j in han_ord:
                    next_o = han_ord[j]
                    break
            if prev_o is not None and next_o is not None:
                cands.append({"p": u16[i], "prev": prev_o, "next": next_o,
                              "cp": i})
    return hans, cands


def compute_breaks(book, pack, stream, words, sil):
    """stream: [(title_chars or None, [(sent_key, sentence_dict), ...]), ...] 的展开形式：
    实际入参为 (ref_stream, sent_infos)：
      ref_stream = 全书汉字流（含篇名等对齐锚，不属于任何句子时 sent=None）
      sent_infos = [{key, sent(dict), hans, cands, ref_lo}]  # ref_lo = 本句首字在 ref 流下标
    返回 stats dict，并把 breaks 写进各 sentence dict（内存）。"""
    ref_chars = stream["ref_chars"]
    asr_chars = [w[0] for w in words]
    sm = difflib.SequenceMatcher(None, ref_chars, asr_chars, autojunk=False)
    mapping = {}
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag == "equal":
            for k in range(i2 - i1):
                mapping[i1 + k] = j1 + k
        elif tag == "replace":
            for k in range(min(i2 - i1, j2 - j1)):
                mapping[i1 + k] = j1 + k
    total_pts = evid_pts = 0
    est_sentences = []
    for info in stream["sents"]:
        sent = info["sent"]
        hans, cands = info["hans"], info["cands"]
        if sent.get("noAudio") or sent.get("startMs") is None or sent.get("startMs", -1) < 0:
            continue
        lo = info["ref_lo"]
        matched = {}
        for o in range(len(hans)):
            j = mapping.get(lo + o)
            if j is not None:
                matched[o] = words[j]
        ratio = (len(matched) / len(hans)) if hans else 0.0
        if ratio < 0.5:
            est_sentences.append(sent.get("id"))
        if not cands:
            continue
        # 点位直接用 ASR 绝对时间（内容包时间轴与原音频同轴；论语每句带
        # axis_off = −篇切点，把整本轴换算到篇内轴）。不用「句首字锚定」：
        # v150 包章首句 startMs 被章题行污染偏早，锚定公式会整体平移失真。
        axis_off = info.get("axis_off", 0)
        brs = []
        for c in cands:
            total_pts += 1
            t_pack = None
            if (ratio >= 0.5 and c["prev"] in matched and c["next"] in matched):
                t_al = (matched[c["prev"]][2] + matched[c["next"]][1]) / 2.0
                t_al = snap(t_al, sil)
                cand = round10(t_al * 1000) + axis_off
                if sent["startMs"] < cand < sent["endMs"]:
                    t_pack = cand
            if t_pack is None:
                f = (c["prev"] + 1) / float(len(hans))
                t_pack = round10(sent["startMs"]
                                 + (sent["endMs"] - sent["startMs"]) * f)
                t_pack = max(sent["startMs"] + 10, min(sent["endMs"] - 10, t_pack))
                brs.append({"p": c["p"], "t": t_pack, "e": 1})
            else:
                brs.append({"p": c["p"], "t": t_pack})
        if brs:
            # 单调性修复：点位倒序/过近（≤30ms）多为对位错配，降级为比例估算
            for _ in range(4):
                bad = -1
                prev_t = sent["startMs"]
                for idx, b in enumerate(brs):
                    if b["t"] <= prev_t + 30:
                        bad = idx
                        break
                    prev_t = b["t"]
                if bad < 0:
                    break
                c = cands[bad]
                f = (c["prev"] + 1) / float(len(hans))
                brs[bad]["t"] = max(sent["startMs"] + 60,
                                    min(sent["endMs"] - 60, round10(
                                        sent["startMs"] + (sent["endMs"]
                                        - sent["startMs"]) * f)))
                brs[bad]["e"] = 1
            prev_t = sent["startMs"] + 30
            for b in brs:  # 最终安全钳制（极端情况才触发）
                if b["t"] <= prev_t:
                    b["t"] = prev_t + 10
                prev_t = b["t"]
            if brs[-1]["t"] >= sent["endMs"]:
                brs[-1]["t"] = sent["endMs"] - 40
                for i in range(len(brs) - 2, -1, -1):
                    if brs[i]["t"] >= brs[i + 1]["t"] - 30:
                        brs[i]["t"] = brs[i + 1]["t"] - 40
            evid_pts += sum(1 for b in brs if not b.get("e"))
            sent["breaks"] = brs
            info["est_ratio"] = ratio
    return {"points": total_pts, "evidence": evid_pts,
            "est_sentences": est_sentences,
            "ref_chars": len(ref_chars), "asr_words": len(words),
            "mapped": len(mapping)}


def build_stream(anchor_text, sent_items):
    """sent_items: [(key, sentence_dict)] 阅读顺序。anchor_text: 篇名等锚字（可为 ''）。
    返回 stream dict（sents 里带 ref_lo / hans / cands）。"""
    ref_chars = list(anchor_text)
    sents = []
    for key, sent in sent_items:
        hans, cands = prep_sentence(sent["textT"])
        sents.append({"key": key, "sent": sent, "hans": hans, "cands": cands,
                      "ref_lo": len(ref_chars)})
        ref_chars.extend(hans)
    return {"ref_chars": ref_chars, "sents": sents}


# ---------------- 分句（与 FollowChunker 同口径） ----------------
def clauses_of(text, breaks):
    """返回 [(start_cp, end_cp_excl, start_ms, end_ms, is_est_boundary_end)]；
    breaks 为 pack 句的 breaks 数组（p 为 UTF-16 下标）——此处按 cp 下标重算切分点，
    时间直接取 breaks 的 t（顺序一一对应）。"""
    cps = list(text)
    # cp idx -> u16 起点
    u16_of_cp = []
    acc = 0
    for ch in cps:
        u16_of_cp.append(acc)
        acc += u16len(ch)
    by_u16 = {b["p"]: b for b in (breaks or [])}
    pts = []  # (end_cp_excl, time_ms, est)
    for i, ch in enumerate(cps):
        if ch in BREAK_PUNCT and u16_of_cp[i] in by_u16:
            b = by_u16[u16_of_cp[i]]
            end = i + 1
            while end < len(cps) and cps[end] in CLOSERS:
                end += 1
            pts.append((end, b["t"], bool(b.get("e"))))
    return pts  # 调用方拼首尾


def split_clauses(sent):
    """整句 → 分句 [(text, start_ms, end_ms, est)]；est=边界含估算点位或整句低对位。"""
    text = sent["textT"]
    pts = clauses_of(text, sent.get("breaks"))
    out = []
    prev_cp, prev_t = 0, sent["startMs"]
    prev_est = False
    cps = list(text)
    for end_cp, t, est in pts:
        out.append(("".join(cps[prev_cp:end_cp]), prev_t, t,
                    prev_est or est))
        prev_cp, prev_t, prev_est = end_cp, t, est
    out.append(("".join(cps[prev_cp:]), prev_t, sent["endMs"], prev_est))
    return out


def fmt_tsrt(t_ms):
    cs = int(round(t_ms / 10.0))
    h, cs = divmod(cs, 360000)
    m, cs = divmod(cs, 6000)
    s, c = divmod(cs, 100)
    return "%02d:%02d:%02d,%02d0" % (h, m, s, c)


def write_split_srt(path, entries):
    with open(path, "w", encoding="utf-8") as f:
        for i, (txt, s, e, _est) in enumerate(entries, 1):
            f.write("%d\n%s --> %s\n%s\n\n" % (i, fmt_tsrt(s), fmt_tsrt(e), txt))


# ---------------- 主流程 ----------------
def main():
    report = {}

    # 重跑安全：先剥掉上一轮写进包里的 breaks
    for nm in ("daxue", "zhongyong", "lunyu"):
        pp = os.path.join(PACK_DIR, nm + ".json")
        pk = json.load(open(pp))
        for c in pk["chapters"]:
            for s in c["sentences"]:
                s.pop("breaks", None)
        with open(pp, "w", encoding="utf-8") as f:
            f.write(json.dumps(pk, ensure_ascii=False, indent=1))

    # ===== 《大学》古本序包：对齐流为原章句序音频顺序，锚定到古本新包 =====
    sil_dx = silences("daxue", os.path.join(H, "workspace/recitation-full/大学-读诵版-完整.mp3"))
    words_dx = load_words([os.path.join(H, "workspace/srt/大学-读诵版-完整_words.json")])
    old = json.load(open(os.path.join(
        H, "workspace/songdu-player/build/daxue-content-v150.json")))
    timed = [s for c in old["chapters"] for s in c["sentences"]
             if not s.get("noAudio")]
    assert len(timed) == 141, len(timed)
    canpian = [{"textT": "此謂知本。", "startMs": 241760, "endMs": 243160},
               {"textT": "此謂知之至也。", "startMs": 243680, "endMs": 245300}]
    # 音频顺序：篇名 + t1..t46 + 残篇两句 + t47..t141（t 为 1 基序号）
    audio_order = timed[:46] + canpian + timed[46:]
    stream_dx = build_stream("大學", [(("old", i), s)
                                      for i, s in enumerate(audio_order)])
    # 对齐/点位在「原音频时间轴」上先算到挂靠的临时句子对象上：
    # audio_order 里 timed 句的 startMs/endMs 本就是原轴；canpian 已填原轴时间。
    stats_dx = compute_breaks("daxue", None, stream_dx, words_dx, sil_dx)
    br_by_old = {}
    for info in stream_dx["sents"]:
        if "breaks" in info["sent"]:
            br_by_old[info["key"]] = info["sent"]["breaks"]
    # 映射到古本包：flat 顺序 = 第一节 t1..13+残篇, 二节 t47..54, 三节 t31..42,
    # 四节 t14..17+t18..21+t22..30+t43..46, 五节 t55..60, 六节 t61..64,
    # 七节 t65..90, 八节 t91..141（与 gen_daxue_guben.py BLOCKS 一致；
    # canpian 在 audio_order 中的 key 为 ("old",46) 与 ("old",47)）
    def T(t):  # 1 基 -> audio_order 下标
        return t - 1 if t <= 46 else t + 1  # 残篇占 46,47 两位
    guben_seq = (list(range(1, 14)) + ["A", "B"] + list(range(47, 55))
                 + list(range(31, 43)) + list(range(14, 18)) + list(range(18, 22))
                 + list(range(22, 31)) + list(range(43, 47)) + list(range(55, 61))
                 + list(range(61, 65)) + list(range(65, 91)) + list(range(91, 142)))
    assert len(guben_seq) == 143, len(guben_seq)
    pack_dx = json.load(open(os.path.join(PACK_DIR, "daxue.json")))
    flat_dx = [s for c in pack_dx["chapters"] for s in c["sentences"]]
    assert len(flat_dx) == 143

    # 古本平移按「块恒定平移」（与 build3_srt.py 同源数据 cuts.json）：
    # 点位新轴 = 点位原轴 +（块 newStart − 块 cutStart）；块内等距、精确。
    # timed 序号 t → 块（build3 ENTRY_RANGE 以 precise 条目号 n＝t+1 记）：
    def parse_srt(path):
        es = []
        for blk in re.split(r"\n\s*\n", open(path, encoding="utf-8").read().strip()):
            ls = blk.strip().split("\n")
            if len(ls) >= 3:
                m = re.match(r"(\d+):(\d+):(\d+),(\d+) --> (\d+):(\d+):(\d+),(\d+)", ls[1])
                g = [int(x) for x in m.groups()]
                es.append((g[0] * 3600000 + g[1] * 60000 + g[2] * 1000 + g[3],
                           g[4] * 3600000 + g[5] * 60000 + g[6] * 1000 + g[7]))
        return es
    ent_prec = parse_srt(os.path.join(
        H, "workspace/your_files/大学-读诵版-完整-简体-precise.srt"))
    assert len(ent_prec) == 142  # 篇名 + 141 timed（残篇两句无内部点位）
    cuts_doc = json.load(open(os.path.join(H, "workspace/guben_work/cuts.json")))
    shift_by_name = {c[0].split()[0]: int(round((c[6] - c[2]) * 1000))
                     for c in cuts_doc["cuts"]}

    def block_of(t):
        for lo, hi, nm in [(1, 13, "R1a"), (47, 54, "R2"), (31, 42, "R3"),
                           (14, 17, "R4a"), (18, 21, "R4b"), (22, 30, "R4c"),
                           (43, 46, "R4d"), (55, 60, "R5"), (61, 64, "R6"),
                           (65, 90, "R7"), (91, 141, "R8")]:
            if lo <= t <= hi:
                return nm
        raise AssertionError(t)
    for gi, item in enumerate(guben_seq):
        if item in ("A", "B"):
            continue  # 残篇两句无内部候选标点
        key = ("old", T(item))
        brs = br_by_old.get(key)
        if not brs:
            continue
        new_sent = flat_dx[gi]
        o_start, o_end = ent_prec[item]      # 兜底比例参照（precise 条目下标＝t）
        shift = shift_by_name[block_of(item)]
        # p（标点下标）须按古本新句重映射：校勘改字（删衍字等）会使旧句下标移位。
        # brs 与旧句候选同序，与新句候选按序位对应（改字不增删标点）。
        _h, new_cands = prep_sentence(new_sent["textT"])
        moved = []
        for k, b in enumerate(brs):
            np_ = new_cands[k]["p"] if k < len(new_cands) else b["p"]
            nt = round10(b["t"] + shift)
            if new_sent["startMs"] < nt < new_sent["endMs"]:
                nb = {"p": np_, "t": nt}
                if b.get("e"):
                    nb["e"] = 1
                moved.append(nb)
            else:
                f = (b["t"] - o_start) / float(o_end - o_start)
                nt = round10(new_sent["startMs"]
                             + (new_sent["endMs"] - new_sent["startMs"]) * f)
                nt = max(new_sent["startMs"] + 10,
                         min(new_sent["endMs"] - 10, nt))
                moved.append({"p": np_, "t": nt, "e": 1})
        if moved:
            new_sent["breaks"] = moved
    report["daxue"] = stats_dx

    # ===== 《中庸》：包时间轴即原音频时间轴 =====
    sil_zy = silences("zhongyong", os.path.join(H, "workspace/recitation-full/中庸-读诵版-完整.mp3"))
    words_zy = load_words([os.path.join(H, "workspace/srt/中庸-读诵版-完整_words.json")])
    pack_zy = json.load(open(os.path.join(PACK_DIR, "zhongyong.json")))
    items_zy = [(("zy", ci, si), s)
                for ci, c in enumerate(pack_zy["chapters"])
                for si, s in enumerate(c["sentences"])]
    stream_zy = build_stream("中庸", items_zy)
    report["zhongyong"] = compute_breaks("zhongyong", pack_zy, stream_zy,
                                          words_zy, sil_zy)

    # ===== 《论语》：对齐在整本时间轴，锚定到篇内时间 =====
    sil_ly = silences("lunyu", os.path.join(H, "workspace/recitation-full/论语-读诵版-完整.mp3"))
    words_ly = load_words([os.path.join(
        H, "workspace/srt/lunyu_work/lunyu_words_part%d.json" % i)
        for i in range(5)])
    pack_ly = json.load(open(os.path.join(PACK_DIR, "lunyu.json")))
    cuts_ly = json.load(open(os.path.join(
        H, "workspace/srt/lunyu_work/lunyu_bounds.json")))["cuts"]
    items_ly = [(("ly", ci, si), s)
                for ci, c in enumerate(pack_ly["chapters"])
                for si, s in enumerate(c["sentences"])]
    stream_ly = build_stream("論語", items_ly)
    for info, (key, _s) in zip(stream_ly["sents"], items_ly):
        info["axis_off"] = -int(round(cuts_ly[key[1]] * 1000))
    report["lunyu"] = compute_breaks("lunyu", pack_ly, stream_ly,
                                      words_ly, sil_ly)

    # ===== 写回内容包（保持 indent=1 / ensure_ascii=False 的原格式） =====
    for name, pack in [("daxue", pack_dx), ("zhongyong", pack_zy),
                       ("lunyu", pack_ly)]:
        # breaks 字段排在 endMs 之后：重建 sentence dict 顺序
        for c in pack["chapters"]:
            for si, s in enumerate(c["sentences"]):
                if "breaks" in s:
                    ns = {}
                    for k, v in s.items():
                        ns[k] = v
                        if k == "endMs":
                            ns["breaks"] = s["breaks"]
                    if "breaks" not in ns:
                        ns["breaks"] = s["breaks"]
                    c["sentences"][si] = ns
        with open(os.path.join(PACK_DIR, name + ".json"), "w",
                  encoding="utf-8") as f:
            f.write(json.dumps(pack, ensure_ascii=False, indent=1))
        n_br = sum(len(s.get("breaks", []))
                   for c in pack["chapters"] for s in c["sentences"])
        n_est = sum(1 for c in pack["chapters"] for s in c["sentences"]
                    for b in s.get("breaks", []) if b.get("e"))
        report[name]["written_breaks"] = n_br
        report[name]["written_est"] = n_est

    # ===== 分句 SRT =====
    outdir = os.path.join(H, "workspace/your_files")
    # 大学（古本序）：简/繁——分句文本需与句 textS/textT 同步切分：
    # clause 切分点由 textT 的 breaks 定，textS 与 textT 逐字对位（生成管线已断言），
    # 用同一 cp 切分位置切 textS。
    def clauses_both(sent):
        pts = clauses_of(sent["textT"], sent.get("breaks"))
        cpsT, cpsS = list(sent["textT"]), list(sent["textS"])
        assert len(cpsT) == len(cpsS), sent["textT"]
        res = []
        pc, pt, pe = 0, sent["startMs"], False
        for end_cp, t, est in pts:
            res.append((("".join(cpsS[pc:end_cp]), "".join(cpsT[pc:end_cp])),
                        pt, t, pe or est))
            pc, pt, pe = end_cp, t, est
        res.append((("".join(cpsS[pc:]), "".join(cpsT[pc:])), pt,
                    sent["endMs"], pe))
        return res

    def emit(name_s, name_t, per_sent_entries):
        es = [e for lst in per_sent_entries for e in lst]
        write_split_srt(os.path.join(outdir, name_s),
                        [(t[0], s, e, est) for t, s, e, est in es])
        write_split_srt(os.path.join(outdir, name_t),
                        [(t[1], s, e, est) for t, s, e, est in es])
        return {"entries": len(es),
                "est_entries": sum(1 for *_x, est in es if est)}

    report["srt_daxue"] = emit(
        "大学-古本序-分句简体.srt", "大学-古本序-分句繁体.srt",
        [clauses_both(s) for c in pack_dx["chapters"] for s in c["sentences"]])

    report["srt_zhongyong"] = emit(
        "中庸-分句简体.srt", "中庸-分句繁体.srt",
        [clauses_both(s) for c in pack_zy["chapters"] for s in c["sentences"]])

    cuts = json.load(open(os.path.join(
        H, "workspace/srt/lunyu_work/lunyu_bounds.json")))["cuts"]
    ly_entries = []
    for ci, c in enumerate(pack_ly["chapters"]):
        off = int(round(cuts[ci] * 1000))
        for s in c["sentences"]:
            lst = []
            for (pair, st, en, est) in clauses_both(s):
                lst.append((pair, st + off, en + off, est))
            ly_entries.append(lst)
    report["srt_lunyu"] = emit("论语-分句简体.srt", "论语-分句繁体.srt",
                               ly_entries)

    with open(os.path.join(WORK, "report.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=1)
    print(json.dumps(report, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
