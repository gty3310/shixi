#!/usr/bin/env python3
"""lunyu.ai 白话导读 → 《论语》注释数据生成器（弦歌 v1.29.0）。

数据源：lunyu.ai「审校白话导读」全文（用户 2026-11-18 拍板入 App，版本名
「lunyu.ai 白话导读」即署名；站方无公开联系方式、授权信无处投递，此为用户
知悉版权状态后的决定）。输入 md 每章含论语原文（简体，Legge/Wikisource 底本）
＋白话导读；本次只取导读，英译不用。

对齐口径（不按序号硬套，按文本逐句定位）：
- App《论语》内容包（在线书库 lunyu.json）1585 句、全局整数 id，底本为阮元
  注疏本系统；站方底本为 Legge 本——两本章划分与个别字句有出入（全书站方
  499 章 vs 注疏本 501 节，差在公冶长、子罕两篇的合/分）。
- 且站方对长章只给开头部分的导读（如季氏 16.1、先进 11.25 侍坐章），导读
  原文只覆盖本章前段——故对齐必须允许「章后留白」（导读未覆盖的句不挂 dd）。
- 方法：逐篇做序列 DP——每章导读原文（归一化）与一串连续 App 句（textS
  归一化拼接）按 difflib 相似度配对，句子可被跳过（小罚分），章序与句序
  都不得倒置；每章至少落 1 句。低相似落位逐章列入报告，不静默处理。

输出：app/src/main/assets/notes/lunyu_notes.json
  {"<块首句id>": {"daodu": 导读全文, "daoduSpan": 覆盖句数}}
  与既有注文件同格式（attachNotes 的第四个版本 daodu/dd）。
报告：tools/lunyu_daodu_report.md（对齐覆盖率、低相似清单、未覆盖句清单）。

用法：
  .venv-gen/bin/python tools/gen_lunyu_daodu.py \
      [--lunyu ~/workspace/bookstore/repos/lunyu/content/lunyu.json] \
      [--md ~/workspace/lunyu-baihua-1948/text-candidates/lunyu-ai-导读全文-个人参考/lunyu-ai-导读全文.md] \
      [--out app/src/main/assets/notes/lunyu_notes.json] \
      [--report tools/lunyu_daodu_report.md]
"""
from __future__ import annotations

import argparse
import difflib
import json
import re
import sys

PUNCT = re.compile(r'[\s，。；：！？、「」『』（）·…—\-"“”‘’〈〉《》]')
MAX_RUN = 24          # 一章导读最多覆盖的 App 句数（微子 18.6 全章 17 句，长章上限留足）
SKIP_PENALTY = 0.012  # 跳过一句的罚分（允许章后留白，但不鼓励乱跳）
LOW_SIM = 0.80        # 低于此相似度的落位列入报告人工复核


def norm(s: str) -> str:
    return PUNCT.sub('', s)


def parse_md(path: str):
    md = open(path, encoding='utf-8').read()
    pians = []
    for part in re.split(r'^## ', md, flags=re.M)[1:]:
        chs = []
        for m in re.finditer(
                r'^### (.+?)\n\n\*\*原文\*\*\n\n(.+?)\n\n\*\*白话导读\*\*\n\n(.+?)'
                r'(?=\n\n\*\*英译|\n\n### |\n\n## |\Z)',
                part, flags=re.M | re.S):
            chs.append({'title': m.group(1).strip(),
                        'yuan': m.group(2).strip(),
                        'daodu': m.group(3).strip()})
        pians.append(chs)
    return pians


def align_pian(chapters, sents):
    """DP：返回 (blocks, skipped)；blocks[i] = (start_idx, end_idx, sim)。"""
    C, S = len(chapters), len(sents)
    sent_norm = [norm(s['textS']) for s in sents]
    # 句拼接归一化文本与偏移（用 textS 原文拼接后归一，标点已去，等价于逐句归一拼接）
    concat = ''.join(sent_norm)
    offs = []
    o = 0
    for t in sent_norm:
        offs.append((o, o + len(t)))
        o += len(t)
    yuan_norm = [norm(c['yuan']) for c in chapters]

    def sim(ci, a, b):
        run = concat[offs[a][0]:offs[b][1]]
        if not run or not yuan_norm[ci]:
            return 0.0
        return difflib.SequenceMatcher(None, run, yuan_norm[ci], autojunk=False).ratio()

    NEG = float('-inf')
    # dp[ci][si] = 前 ci 章配到前 si 句为止的最高分；choice 记录回溯
    dp = [[NEG] * (S + 1) for _ in range(C + 1)]
    choice = [[None] * (S + 1) for _ in range(C + 1)]
    dp[0][0] = 0.0
    for si in range(S):
        if dp[0][si] > NEG:
            dp[0][si + 1] = dp[0][si] - SKIP_PENALTY
            choice[0][si + 1] = ('skip',)
    for ci in range(C):
        for si in range(S + 1):
            cur = dp[ci][si]
            if cur == NEG:
                continue
            # 跳过一句
            if si < S and cur - SKIP_PENALTY > dp[ci][si + 1]:
                dp[ci][si + 1] = cur - SKIP_PENALTY
                choice[ci][si + 1] = ('skip',)
            # 本章吃 k 句
            for k in range(1, MAX_RUN + 1):
                if si + k > S:
                    break
                v = cur + sim(ci, si, si + k - 1)
                if v > dp[ci + 1][si + k]:
                    dp[ci + 1][si + k] = v
                    choice[ci + 1][si + k] = ('take', k)
    # 结尾允许留白
    best_si = max(range(S + 1), key=lambda x: dp[C][x])
    blocks = [None] * C
    skipped = []
    ci, si = C, best_si
    tail_skip = list(range(best_si, S))
    while ci > 0 or si > 0:
        ch = choice[ci][si]
        if ch is None:
            break
        if ch[0] == 'skip':
            skipped.append(si - 1)
            si -= 1
        else:
            k = ch[1]
            blocks[ci - 1] = (si - k, si - 1, sim(ci - 1, si - k, si - 1))
            ci -= 1
            si -= k
    skipped.extend(tail_skip)
    skipped.sort()
    return blocks, skipped, dp[C][best_si]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--lunyu', default='/home/hatch/workspace/bookstore/repos/lunyu/content/lunyu.json')
    ap.add_argument('--md', default='/home/hatch/workspace/lunyu-baihua-1948/text-candidates/lunyu-ai-导读全文-个人参考/lunyu-ai-导读全文.md')
    ap.add_argument('--out', default='app/src/main/assets/notes/lunyu_notes.json')
    ap.add_argument('--report', default='tools/lunyu_daodu_report.md')
    a = ap.parse_args()

    book = json.load(open(a.lunyu, encoding='utf-8'))
    pians = parse_md(a.md)
    if len(pians) != 20 or len(book['chapters']) != 20:
        print('篇数异常', len(pians), len(book['chapters'])); sys.exit(1)
    total_md = sum(len(p) for p in pians)
    print('md 章数', total_md)

    notes = {}
    rep = []
    total_covered = 0
    total_sents = 0
    low_sim = []
    gap_runs = []
    placed = 0
    for pi in range(20):
        ch = book['chapters'][pi]
        sents = ch['sentences']
        total_sents += len(sents)
        blocks, skipped, score = align_pian(pians[pi], sents)
        covered = set()
        for ci, b in enumerate(blocks):
            if b is None:
                rep.append(f'- ⚠ 篇{pi+1} 第{ci+1}章（{pians[pi][ci]["title"]}）未落位！')
                continue
            placed += 1
            s0, s1, sm = b
            first = sents[s0]
            notes[str(first['id'])] = {
                'daodu': pians[pi][ci]['daodu'],
                'daoduSpan': s1 - s0 + 1,
            }
            for x in range(s0, s1 + 1):
                covered.add(x)
            total_covered += s1 - s0 + 1
            if sm < LOW_SIM:
                low_sim.append((pians[pi][ci]['title'], round(sm, 3),
                                sents[s0]['id'], s1 - s0 + 1))
        # 未覆盖句按连续段汇总
        runs = []
        for idx in skipped:
            if runs and idx == runs[-1][1] + 1:
                runs[-1][1] = idx
            else:
                runs.append([idx, idx])
        for (g0, g1) in runs:
            gap_runs.append((ch['title'], sents[g0]['id'], sents[g1]['id'],
                             g1 - g0 + 1, sents[g0]['textS'][:18]))
        rep.append(f'- {ch["title"]}：md {len(pians[pi])} 章 → 落位 '
                   f'{sum(1 for b in blocks if b)} 章，覆盖句 '
                   f'{len(covered)}/{len(sents)}，未覆盖 {len(skipped)} 句')

    with open(a.out, 'w', encoding='utf-8') as f:
        json.dump(notes, f, ensure_ascii=False, indent=1, sort_keys=True)
    print('blocks', len(notes), 'placed', placed, '/', total_md,
          'covered sents', total_covered, '/', total_sents)
    print('low-sim:', low_sim)

    lines = ['# lunyu.ai 白话导读对齐报告（v1.29.0 生成器）', '',
             f'- md 章数：{total_md}；落位：{placed}；生成注块：{len(notes)}',
             f'- App 句数：{total_sents}；导读覆盖句：{total_covered}'
             f'（{total_covered/total_sents:.1%}）；未覆盖句：{total_sents-total_covered}'
             '（长章导读只及前段的章后留白＋两本章划分出入处，逐段列于下）',
             '', '## 逐篇', *rep, '', '## 低相似落位（<0.80，人工复核用）']
    if low_sim:
        for t, sm, fid, span in low_sim:
            lines.append(f'- {t}：相似度 {sm}，块首句 id {fid}，覆盖 {span} 句')
    else:
        lines.append('- 无')
    lines += ['', '## 未覆盖句段（篇 / 起句id / 止句id / 句数 / 首句摘录）']
    for title, i0, i1, n, snip in gap_runs:
        lines.append(f'- {title}：id {i0}–{i1}，{n} 句，首句「{snip}…」')
    open(a.report, 'w', encoding='utf-8').write('\n'.join(lines) + '\n')
    print('report →', a.report)


if __name__ == '__main__':
    main()
