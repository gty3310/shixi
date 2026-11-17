#!/usr/bin/env python3
"""古读标红清单生成器（弦歌 v1.27.0）。

判定口径（与正音 CLI 的终检 audit 完全同源，不另立标准）：
逐字取书中目标拼音，用 zhengyin.audit.classify_one 对原字分类——
  A 类（现代非多音字且唯一读音＝目标音）           → 不标
  B 类（现代多音字且目标音＝默认音）               → 不标
  D 类（多音字目标音≠默认音／非多音字唯一读音≠目标音）→ 标红
即「这个位置的读音不是现代汉语里这个字当下最常用的那个音」。
一/不 变调按 audit.norm_sandhi 归一（yi2/yi4→yi1、bu2→bu4），不算古读。
两处与原始 classify 的刻意偏离（报告里如实说明）：
  - 字典未收的字：classify 判 D（无法核验），本清单不标——无证据不标；
  - 书中拼音无声调且音节干对不上任何现代读音干：判标（其余无声调者，
    音节干属于现代读音干集合即不标，与 audit_text 的定音逻辑一致）。

数据：zhengyin-dict v0.3.1 的 zhengyin.db（modern_readings/is_polyphonic/
unique_reading/default 均出自该库），经 zhengyin 的 Dict/audit 代码读取，
本脚本不自备字典、不凭感觉挑字。
查字典用繁体字形（textT 对位字）：与正音审计同口径，且简繁两行字典数据
个别不一致时繁体行才合经典字形实情（如 盡 jin4 非多音、简体行 尽 却并了
儘 jin3 而成多音——按简体行判会把「尽其性」的 jin4 误标）。textT 与
textS 汉字数不齐的句退回简体字形判定并计数上报。

输出：app/src/main/assets/content/gd/<bookId>.json
  {"bookId","rule","dictSource","totalHan","totalRed",
   "sentences": {"<句id>": {"n": 本句汉字数, "gd": [句内汉字序号...]}}}
  只收有标红字的句；n 是挂载时的对账指纹（App 侧句内汉字数不符即整句不挂，
  防书库文本日后修订导致错位标红）。

用法：
  python3 tools/gen_gd_marks.py \
      --daxue app/src/main/assets/content/daxue.json \
      --zhongyong /path/to/zhongyong.json \
      --lunyu /path/to/lunyu.json \
      [--audit-daxue <audit.json>] [--audit-zhongyong <audit.json>] \
      [--outdir app/src/main/assets/content/gd] [--report <报告.md>]
环境变量 ZHENGYIN_SRC / ZHENGYIN_DB 可覆盖正音代码与词典路径。
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

ZHENGYIN_SRC = os.environ.get("ZHENGYIN_SRC", str(Path.home() / "workspace/zhengyin/src"))
ZHENGYIN_DB = os.environ.get("ZHENGYIN_DB", str(Path.home() / "workspace/zhengyin-dict/dist/zhengyin.db"))
sys.path.insert(0, ZHENGYIN_SRC)

from zhengyin.datalayer import Dict  # noqa: E402
from zhengyin.audit import classify_one  # noqa: E402
from zhengyin.pinyinutil import normalize_syllable, resolve_target, split_reading  # noqa: E402

# 与 reader.html 的 isCJK 完全一致（渲染端真相）：标点不占位，拼音按此序对齐
def is_han(ch: str) -> bool:
    cp = ord(ch)
    return (0x4E00 <= cp <= 0x9FFF) or (0x3400 <= cp <= 0x4DBF) or (0x20000 <= cp <= 0x323AF)


def resolve_like_audit(d: Dict, ch: str, syllable: str):
    """照 audit_text 的定音顺序把书中拼音（带调号）定到编号读音。
    返回 (target|None, stem, tone)。"""
    info = d.char_info(ch)
    readings = list(info["readings"]) if info else []
    target, stem, tone = resolve_target(syllable, readings)
    if target is None and tone is None and info:
        m = [r for r in info.get("modern_readings") or [] if split_reading(r)[0] == stem]
        if len(m) == 1:
            target = m[0]
    if target is None and tone is not None:
        s2, t2 = normalize_syllable(syllable)
        target = f"{s2}{t2}"
    return target, stem, tone


def judge(d: Dict, ch: str, syllable: str):
    """返回 (is_red, why, target)。why ∈ A/B/C/D/no_dict/no_tone_ok/no_tone_red。"""
    info = d.char_info(ch)
    if info is None:
        return False, "no_dict", None
    target, stem, tone = resolve_like_audit(d, ch, syllable)
    if target is None:
        # 无声调且定不了音：音节干在现代读音干集合里就算现代音
        modern_stems = {split_reading(r)[0] for r in info.get("modern_readings") or []}
        red = stem not in modern_stems
        return red, ("no_tone_red" if red else "no_tone_ok"), None
    cat, _basis, _norm = classify_one(d, ch, target, False)
    return (cat == "D"), cat, target


def gen_book(d: Dict, book_path: str):
    book = json.loads(Path(book_path).read_text(encoding="utf-8"))
    book_id = book["bookId"]
    out_sents = {}
    total_han = 0
    total_red = 0
    flat_red = []          # 全书拉平的标红汉字序号（与审计 han_index 对位）
    flat_chars = []        # 全书拉平汉字（与审计 details 对位校验用）
    stats = {"no_dict": [], "no_tone_ok": 0, "no_tone_red": [], "trad_fallback": 0}
    examples = []          # (字, 拼音, 句id, 句内序号) 抽查材料
    for ch in book["chapters"]:
        for s in ch["sentences"]:
            text = s["textS"]
            hans = [c for c in text if is_han(c)]
            hans_t = [c for c in (s.get("textT") or "") if is_han(c)]
            use_trad = len(hans_t) == len(hans)
            if not use_trad:
                stats["trad_fallback"] += 1
            py = s.get("pinyin") or []
            if len(hans) != len(py):
                raise SystemExit(
                    f"对齐失败：{book_id} 句 {s['id']} 汉字 {len(hans)} ≠ 拼音 {len(py)}，停工")
            gds = []
            for pi, (c, syll) in enumerate(zip(hans, py)):
                red, why, target = judge(d, hans_t[pi] if use_trad else c, syll)
                if why == "no_dict":
                    stats["no_dict"].append(c)
                elif why == "no_tone_ok":
                    stats["no_tone_ok"] += 1
                elif why == "no_tone_red":
                    stats["no_tone_red"].append((c, syll, s["id"]))
                if red:
                    gds.append(pi)
                    flat_red.append(total_han + pi)
                    examples.append((c, target or syll, s["id"], pi))
                flat_chars.append(c)
            total_han += len(hans)
            total_red += len(gds)
            if gds:
                out_sents[s["id"]] = {"n": len(hans), "gd": gds}
    payload = {
        "bookId": book_id,
        "rule": "target reading is D-class under zhengyin audit (dict v0.3.1 modern fields): "
                "polyphonic char read away from its modern default, or non-polyphonic char "
                "read away from its unique modern reading; sandhi of 一/不 normalised",
        "dictSource": "zhengyin-dict v0.3.1 zhengyin.db via zhengyin Dict/audit.classify_one",
        "totalHan": total_han,
        "totalRed": total_red,
        "sentences": out_sents,
    }
    return payload, stats, examples, flat_red, flat_chars


def cross_check(name: str, audit_path: str, flat_red: list, flat_chars: list, lines: list, d: Dict = None):
    a = json.loads(Path(audit_path).read_text(encoding="utf-8"))
    det = a["details"]
    # 审计输入是繁体原文，书 textS 是简体：先经词典 canonical 归一再对位
    audit_chars = [d.canonical(x["char"]) if d else x["char"] for x in det]
    lines.append(f"### {name} 与正音审计集交叉核对")
    lines.append(f"- 审计检出（定版前）计数 detected_counts：{a.get('detected_counts')}")
    if len(audit_chars) != len(flat_chars):
        lines.append(f"- ⚠️ 审计原文与书汉字总数不等（{len(audit_chars)} vs {len(flat_chars)}），"
                     "han_index 无法对位，只报总量")
        lines.append(f"- 审计终态 C（钉音）总数 {a['counts']['C']}；本清单标红总数 {len(flat_red)}（口径见正文）")
        return
    diff = [i for i, (x, y) in enumerate(zip(audit_chars, flat_chars)) if x != y]
    if diff:
        pairs = [(det[i]["char"], flat_chars[i]) for i in diff[:8]]
        lines.append(f"- 字形位点差异 {len(diff)} 处（异体/简繁对位，总数相等、无移位，"
                     f"han_index 对位仍成立）：{pairs}")
    c_set = {x["han_index"] for x in det if x["category"] == "C"}
    red_set = set(flat_red)
    lines.append(f"- 审计终态：A {a['counts']['A']}／C（钉音）{a['counts']['C']}；本清单标红 {len(red_set)}")
    lines.append(f"- 交集（既钉音又标红）：{len(c_set & red_set)}")
    only_c = sorted(c_set - red_set)
    only_red = sorted(red_set - c_set)
    from collections import Counter
    cc = Counter((det[i]["char"], det[i]["target"], det[i]["action"]) for i in only_c)
    lines.append(f"- 只钉音不标红 {len(only_c)} 处（按 字/目标音/审计动作 归类）：")
    for (c, t, act), n in cc.most_common():
        lines.append(f"  - {c} {t}（{act}）×{n}")
    rc = Counter((det[i]["char"], det[i]["target"], det[i]["action"]) for i in only_red)
    lines.append(f"- 只标红不钉音 {len(only_red)} 处（按 字/目标音/审计动作 归类）：")
    for (c, t, act), n in rc.most_common():
        lines.append(f"  - {c} {t}（{act}）×{n}")


SPOT = [("好", "hao4"), ("恶", "wu4"), ("乐", "yao4"), ("说", "yue4"), ("知", "zhi4")]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--daxue")
    ap.add_argument("--zhongyong")
    ap.add_argument("--lunyu")
    ap.add_argument("--audit-daxue")
    ap.add_argument("--audit-zhongyong")
    ap.add_argument("--outdir", default="app/src/main/assets/content/gd")
    ap.add_argument("--report")
    args = ap.parse_args()

    d = Dict(ZHENGYIN_DB)
    outdir = Path(args.outdir)
    outdir.mkdir(parents=True, exist_ok=True)
    lines = ["# 古读标红清单生成报告", ""]
    all_examples = {}
    flats = {}
    for bid, path in (("daxue", args.daxue), ("zhongyong", args.zhongyong), ("lunyu", args.lunyu)):
        if not path:
            continue
        payload, stats, examples, flat_red, flat_chars = gen_book(d, path)
        (outdir / f"{bid}.json").write_text(
            json.dumps(payload, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
        flats[bid] = (flat_red, flat_chars)
        all_examples[bid] = examples
        lines.append(f"## {bid}：汉字 {payload['totalHan']}，标红 {payload['totalRed']}，"
                     f"涉 {len(payload['sentences'])} 句")
        lines.append(f"- 字典未收（未标）：{len(stats['no_dict'])} 字次"
                     f"（{'、'.join(sorted(set(stats['no_dict']))[:20])}）")
        lines.append(f"- 无声调拼音按现代音节干放行：{stats['no_tone_ok']} 字次；"
                     f"无声调且音节干非现代：{len(stats['no_tone_red'])} 字次"
                     f"{stats['no_tone_red'][:5]}")
        lines.append(f"- textT 汉字数与 textS 不齐、退回简体字形判定的句：{stats['trad_fallback']}")
        lines.append("")
    lines.append("## 知名例字抽查（标红清单内实际命中）")
    for bid, examples in all_examples.items():
        ex = {}
        for c, syll, sid, pi in examples:
            ex.setdefault((c, syll), []).append((sid, pi))
        hits = []
        for c, t in SPOT:
            hits.append(f"{c} {t}：{len(ex.get((c, t), []))} 处")
        lines.append(f"- {bid}：" + "；".join(hits))
        if bid == "zhongyong":
            pi_hits = [(t, v) for (c, t), v in ex.items() if c == "辟"]
            lines.append(f"- 中庸「辟」全部标红读音：{pi_hits}")
    lines.append("")
    if args.audit_daxue and "daxue" in flats:
        cross_check("大学", args.audit_daxue, *flats["daxue"], lines, d)
        lines.append("")
    if args.audit_zhongyong and "zhongyong" in flats:
        cross_check("中庸", args.audit_zhongyong, *flats["zhongyong"], lines, d)
        lines.append("")
    report = "\n".join(lines)
    print(report)
    if args.report:
        Path(args.report).write_text(report, encoding="utf-8")


if __name__ == "__main__":
    main()
