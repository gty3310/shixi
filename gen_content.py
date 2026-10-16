# -*- coding: utf-8 -*-
"""v1.4 内容包生成：原文 txt（分章分句）× SRT（时间轴）× 拼音版（逐字拼音）→ assets/content/*.json
校验口径：去掉标点后逐句与 SRT 正文比对；大学多出的 5 句应恰为传五章朱子补传。
不一致即报错退出，不硬凑。"""
import json, re, sys, os

BASE = os.path.expanduser("~/workspace")
TXT = os.path.join(BASE, "xueyong-texts")
SRTD = os.path.join(BASE, "srt")
OUT = os.path.join(BASE, "songdu-player/app/src/main/assets/content")
os.makedirs(OUT, exist_ok=True)

CJK = re.compile(r'[\u4e00-\u9fff\u3400-\u4dbf]')
SENT_RE = re.compile(r'[^。；！？]*[。；！？][」』”’]*')
SYL_RE = re.compile(r"[A-Za-zāáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜüńňǹḿê]+")

from opencc import OpenCC
s2t = OpenCC('s2t')

def strip_punct(s):
    return ''.join(CJK.findall(s))

def parse_srt(path):
    cues = []
    for block in open(path, encoding="utf-8").read().strip().split("\n\n"):
        lines = block.strip().split("\n")
        if len(lines) < 3:
            continue
        m = re.match(r'(\d+):(\d+):(\d+),(\d+) --> (\d+):(\d+):(\d+),(\d+)', lines[1])
        g = [int(x) for x in m.groups()]
        start = ((g[0]*60+g[1])*60+g[2])*1000+g[3]
        end = ((g[4]*60+g[5])*60+g[6])*1000+g[7]
        cues.append((start, end, lines[2].strip()))
    return cues

def parse_text(path, title_line):
    """返回 [(heading, [para, ...]), ...]"""
    chapters = []
    cur = None
    for line in open(path, encoding="utf-8"):
        line = line.strip()
        if not line or re.fullmatch(r'《[^》]+》', line) or line.startswith("底本"):
            continue
        if re.fullmatch(r'(經|经|傳|传)[一二三四五六七八九十]+章.*', line) or \
           re.fullmatch(r'第[一二三四五六七八九十百]+章', line):
            cur = (line, [])
            chapters.append(cur)
        else:
            assert cur is not None, (path, line)
            cur[1].append(line)
    return chapters

def split_sentences(paras):
    out = []
    for p in paras:
        parts = SENT_RE.findall(p)
        rest = SENT_RE.sub('', p)
        assert strip_punct(rest) == '', (p, rest)
        out.extend(x.strip() for x in parts if x.strip())
    return out

def parse_pinyin(path, title_line):
    """返回 [(hanzi_para, [syllable,...]), ...] 按段落顺序"""
    lines = [l.rstrip('\n') for l in open(path, encoding="utf-8")]
    pairs = []
    i = 0
    while i < len(lines):
        line = lines[i].strip()
        i += 1
        if not line or line == title_line or line.startswith("底本") or line.startswith("（每段"):
            continue
        if re.fullmatch(r'(经|传)[一二三四五六七八九十]+章.*', line) or \
           re.fullmatch(r'第[一二三四五六七八九十百]+章', line):
            continue
        # 汉字行：下一非空行应为拼音行
        j = i
        while j < len(lines) and not lines[j].strip():
            j += 1
        assert j < len(lines), "拼音行缺失: " + line[:20]
        py_line = lines[j].strip()
        i = j + 1
        syl = SYL_RE.findall(py_line)
        pairs.append((line, syl))
    return pairs

def chapter_title_t(heading):
    """章题繁体呈现：传X章 释YYY → 傳X章 · 釋YYY章；经一章→經一章；中庸第X章原样转繁"""
    note = None
    h = heading
    if "（朱子补传）" in h:
        h = h.replace("（朱子补传）", "").strip()
        note = "朱子補傳 · 錄音未誦"
    if " " in h:
        a, b = h.split(" ", 1)
        return s2t.convert(a) + " · " + s2t.convert(b) + "章", note
    return s2t.convert(h), note

def build(book_id, title_t, title_line, txt_s, txt_t, py_file, srt_s, dur_ms, butan_heading=None):
    chs_s = parse_text(os.path.join(TXT, txt_s), title_line)
    chs_t = parse_text(os.path.join(TXT, txt_t), title_line)
    assert len(chs_s) == len(chs_t), (len(chs_s), len(chs_t))
    py_pairs = parse_pinyin(os.path.join(TXT, py_file), title_line + "拼音版")

    # 段落级拼音对齐：拼音版汉字段应与简体原文段逐段相同（仅汉字序列）
    flat_paras_s = [p for _, paras in chs_s for p in paras]
    assert len(py_pairs) == len(flat_paras_s), (len(py_pairs), len(flat_paras_s))
    py_fallback_paras = []
    para_syls = []
    for k, ((hp, syl), tp) in enumerate(zip(py_pairs, flat_paras_s)):
        chars = CJK.findall(tp)
        if ''.join(CJK.findall(hp)) != ''.join(chars) or len(syl) != len(chars):
            # 兜底：pypinyin 现生成该段拼音
            from pypinyin import pinyin, Style
            syl = [x[0] for x in pinyin(''.join(chars), style=Style.TONE, heteronym=False)]
            py_fallback_paras.append(k)
        assert len(syl) == len(chars), (k, tp[:20], len(syl), len(chars))
        para_syls.append(syl)

    # 分句（简/繁并行）+ 逐句拼音切片
    chapters_out = []
    all_text_sents = []   # (chapter_idx, sent_idx_in_ch, textS, textT, pinyin, is_butan)
    para_idx = 0
    for ci, ((hs, paras_s), (ht, paras_t)) in enumerate(zip(chs_s, chs_t)):
        assert len(paras_s) == len(paras_t)
        sents_s = split_sentences(paras_s)
        sents_t = split_sentences(paras_t)
        assert len(sents_s) == len(sents_t), (hs, len(sents_s), len(sents_t))
        # 逐句拼音：按段落游标切片
        sent_py = []
        for p_s in paras_s:
            p_sents = split_sentences([p_s])
            syl = para_syls[para_idx]
            para_idx += 1
            cur = 0
            for s in p_sents:
                n = len(CJK.findall(s))
                sent_py.append(syl[cur:cur+n])
                cur += n
            assert cur == len(syl), (p_s[:20], cur, len(syl))
        assert len(sent_py) == len(sents_s)
        is_butan = (butan_heading is not None and hs.startswith(butan_heading))
        title, note = chapter_title_t(hs)
        chapters_out.append({"id": "c%d" % (ci+1), "title": title, "note": note, "sentences": []})
        for si, (a, b, py) in enumerate(zip(sents_s, sents_t, sent_py)):
            assert len(CJK.findall(a)) == len(CJK.findall(b)) == len(py), (a, b)
            all_text_sents.append((ci, si, a, b, py, is_butan))

    # 与 SRT 对齐（SRT 第 1 条为篇名，跳过）
    cues = parse_srt(os.path.join(SRTD, srt_s))
    title_txt = strip_punct(cues[0][2])
    body = cues[1:]
    j = 0
    mismatches = []
    timed = 0
    for (ci, si, a, b, py, is_butan) in all_text_sents:
        entry = {"textS": a, "textT": b, "pinyin": py}
        if is_butan:
            entry.update({"startMs": None, "endMs": None, "noAudio": True})
        else:
            if j >= len(body):
                mismatches.append(("SRT 句子不足", a))
                entry.update({"startMs": None, "endMs": None, "noAudio": True})
            elif strip_punct(body[j][2]) == strip_punct(a):
                entry.update({"startMs": body[j][0], "endMs": body[j][1], "noAudio": False})
                timed += 1
                j += 1
            else:
                mismatches.append(("第%d句 文本=%s | SRT=%s" % (j+1, strip_punct(a)[:30], strip_punct(body[j][2])[:30]), a))
                entry.update({"startMs": None, "endMs": None, "noAudio": True})
        chapters_out[ci]["sentences"].append(entry)
    leftover = len(body) - j
    print("== %s ==" % book_id)
    print("篇名条:", cues[0][2], "| 章数:", len(chapters_out),
          "| 文本句数:", len(all_text_sents), "| SRT 正文句数:", len(body),
          "| 对齐成功:", timed, "| SRT 剩余未用:", leftover)
    print("拼音兜底段落:", py_fallback_paras)
    if mismatches:
        print("!! 不一致 %d 处:" % len(mismatches))
        for m in mismatches:
            print("   ", m)
        sys.exit(2)
    assert leftover == 0

    # 全局 id + 单调校验
    n = 0
    prev = -1
    for ch in chapters_out:
        for s in ch["sentences"]:
            n += 1
            s2 = {"id": "s%d" % n, "startMs": s["startMs"], "endMs": s["endMs"],
                  "textS": s["textS"], "textT": s["textT"], "pinyin": s["pinyin"]}
            if s.get("noAudio"):
                s2["noAudio"] = True
            ch["sentences"][ch["sentences"].index(s)] = s2
            if s["startMs"] is not None:
                assert s["startMs"] > prev, ("时间不单调", s["textS"])
                prev = s["startMs"]
    last_end = [s["endMs"] for ch in chapters_out for s in ch["sentences"] if s["endMs"] is not None][-1]
    doc = {"bookId": book_id, "title": title_t, "audioDurationMs": dur_ms, "chapters": chapters_out}
    out_path = os.path.join(OUT, book_id + ".json")
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=1)
    na = sum(1 for ch in chapters_out for s in ch["sentences"] if s.get("noAudio"))
    total_py = sum(len(s["pinyin"]) for ch in chapters_out for s in ch["sentences"])
    total_ch = sum(len(CJK.findall(s["textS"])) for ch in chapters_out for s in ch["sentences"])
    print("写出 %s | 总句数 %d（有时间 %d / noAudio %d）| 末句 endMs=%d（音频 %d）| 拼音字数 %d/%d" %
          (out_path, n, n - na, na, last_end, dur_ms, total_py, total_ch))

build("daxue", "大學", "《大学》", "大学-原文-简体.txt", "大学-原文-繁体.txt",
      "大学-拼音版.txt", "大学-读诵版-完整-简体.srt", 747300, butan_heading="传五章")
build("zhongyong", "中庸", "《中庸》", "中庸-原文-简体.txt", "中庸-原文-繁体.txt",
      "中庸-拼音版.txt", "中庸-读诵版-完整-简体.srt", 1461900)
print("OK")
