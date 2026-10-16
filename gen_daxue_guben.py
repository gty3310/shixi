# -*- coding: utf-8 -*-
"""v1.6.0《大学》内容包重做：朱子章句序 → 《礼记》古本序（跟《学庸论语》原书走）。

数据源：
- 旧内容包 build/daxue-content-v150.json（v1.5.0 快照，146 句：141 有时间轴＋传五章补传 5 句 noAudio）
- 新句界 ~/workspace/your_files/大学-古本序-简体.srt（144 条：第 1 条篇名＋143 正文句，新音频轴、厘秒级）
- 切分依据：~/workspace/your_files/大学古本序音频重排说明.md

变换：
1. 删朱子补传 5 句（古本无补传、录音未念）。
2. 段序按古本 8 小节重排（节内/节间映射见 BLOCKS；旧有时间轴句按旧 SRT 条号对位：
   旧 SRT 条 k ↔ 有时间轴句 t=k-1，第 1 条为篇名）。
3. 第一节末尾补残篇两句「此谓知本。」「此谓知之至也。」（录音实念、旧包未作独立句，
   时间取新 SRT 第 15/16 条；听讼段自身末尾的「此谓知本」是另一处，仍留在第四节）。
4. 校勘定字（繁/简同步；拼音随字联动，读音不变或按注音）：
   - 治国节「宜兄宜弟，而後可以教國之長。」→ 教國人（拼音去 zhī zhǎng、加 rén）
   - 平天下节「道得善則得之，失善則失之矣。」→「道善則得之，不善則失之矣。」（拼音按字重排＋bù）
   - 「《大甲》曰」「菉竹猗猗」旧包已是定字（tài jiǎ / lù 不变），脚本断言确认。
5. 每句 startMs/endMs 全部换新 SRT 对应条目；audioDurationMs=714230（新音频实测 714.23 秒）。
校验：新句与新 SRT 条目逐条去标点比对（仅上述 2 处定字句允许不一致）、拼音逐字对齐、时间单调。
不一致即报错退出，不硬凑。
"""
import json, re, os, sys

BASE = os.path.expanduser("~/workspace")
OLD = os.path.join(BASE, "songdu-player/build/daxue-content-v150.json")
SRT = os.path.join(BASE, "your_files/大学-古本序-简体.srt")
OUT = os.path.join(BASE, "songdu-player/app/src/main/assets/content/daxue.json")
DUR_MS = 714230

CJK = re.compile(r'[\u4e00-\u9fff\u3400-\u4dbf]')
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

old = json.load(open(OLD, encoding="utf-8"))
timed = []          # 有时间轴句，按旧包顺序（= 旧 SRT 正文条顺序），t 从 1 起
butan = 0
for ch in old["chapters"]:
    for s in ch["sentences"]:
        if s.get("noAudio"):
            butan += 1
        else:
            timed.append(s)
assert butan == 5, butan
assert len(timed) == 141, len(timed)
def T(t):           # t: 1-based
    return timed[t-1]

# ---- 校勘定字 ----
def fix_sentence(s, old_t, new_t, new_s, new_py):
    assert s["textT"] == old_t, s["textT"]
    s["textT"] = new_t
    s["textS"] = new_s
    s["pinyin"] = new_py

# 治国节：教國之長 → 教國人（旧拼音尾 …jiào guó zhī zhǎng → …jiào guó rén）
target = [x for x in timed if "教國之長" in x["textT"]]
assert len(target) == 1
s = target[0]
assert s["pinyin"][-4:] == ['jiào', 'guó', 'zhī', 'zhǎng'], s["pinyin"]
fix_sentence(s, "宜兄宜弟，而後可以教國之長。", "宜兄宜弟，而後可以教國人。",
             "宜兄宜弟，而后可以教国人。", s["pinyin"][:-2] + ['rén'])

# 平天下节：道得善則得之，失善則失之矣 → 道善則得之，不善則失之矣
target = [x for x in timed if x["textT"] == "道得善則得之，失善則失之矣。"]
assert len(target) == 1
s = target[0]
py = s["pinyin"]
assert py == ['dào', 'dé', 'shàn', 'zé', 'dé', 'zhī', 'shī', 'shàn', 'zé', 'shī', 'zhī', 'yǐ'], py
new_py = [py[0], py[2], py[3], py[4], py[5], 'bù', py[7], py[8], py[9], py[10], py[11]]
fix_sentence(s, "道得善則得之，失善則失之矣。", "道善則得之，不善則失之矣。",
             "道善则得之，不善则失之矣。", new_py)

# 大甲 / 菉竹：旧包已是定字，断言确认（拼音 tài jiǎ / lù 不变）
t15 = [x for x in timed if "甲" in x["textT"]]
assert len(t15) == 1 and "《大甲》曰" in t15[0]["textT"] and "《大甲》曰" in t15[0]["textS"], t15
t_qi = [x for x in timed if "淇澳" in x["textT"]]
assert len(t_qi) == 1 and "菉竹" in t_qi[0]["textT"] and "菉竹" in t_qi[0]["textS"], t_qi

# ---- 古本 8 小节映射（t 为有时间轴句序号；残篇两句为新增对象）----
canpian = [
    {"textS": "此谓知本。", "textT": "此謂知本。", "pinyin": ['cǐ', 'wèi', 'zhī', 'běn']},
    {"textS": "此谓知之至也。", "textT": "此謂知之至也。", "pinyin": ['cǐ', 'wèi', 'zhī', 'zhī', 'zhì', 'yě']},
]
def rng(a, b):
    return [T(t) for t in range(a, b + 1)]

BLOCKS = [
    ("第一節", "經文", rng(1, 13) + canpian),                       # 经一章＋残篇两句
    ("第二節", "誠意", rng(47, 54)),                                 # 传六章
    ("第三節", "淇澳", rng(31, 42)),                                 # 传三章后半
    ("第四節", "釋明明德 · 新民 · 止於至善 · 本末",
     rng(14, 17) + rng(18, 21) + rng(22, 30) + rng(43, 46)),         # 传一＋传二＋传三前半＋传四听讼
    ("第五節", "正心", rng(55, 60)),                                 # 传七章
    ("第六節", "齊家", rng(61, 64)),                                 # 传八章
    ("第七節", "治國", rng(65, 90)),                                 # 传九章
    ("第八節", "平天下", rng(91, 141)),                              # 传十章
]
flat = [s for _, _, ss in BLOCKS for s in ss]
assert len(flat) == 143, len(flat)

# ---- 新 SRT 对位赋时 ----
cues = parse_srt(SRT)
assert strip_punct(cues[0][2]) == "大学", cues[0]
body = cues[1:]
assert len(body) == 143, len(body)

FIXED_TEXTS = {"宜兄宜弟，而後可以教國人。", "道善則得之，不善則失之矣。"}
mismatch = []
for i, (s, cue) in enumerate(zip(flat, body)):
    if strip_punct(s["textS"]) != strip_punct(cue[2]):
        mismatch.append((i + 1, s["textS"], cue[2]))
    s["startMs"], s["endMs"] = cue[0], cue[1]
bad = [m for m in mismatch
       if flat[m[0] - 1]["textT"] not in FIXED_TEXTS]
print("与新 SRT 文本不一致条目 %d（应恰为 2 处定字句）:" % len(mismatch))
for m in mismatch:
    print("   第%d条 内容包=%s | SRT=%s" % m)
assert len(mismatch) == 2 and not bad, mismatch

# ---- 组装与校验 ----
chapters_out = []
n = 0
prev = -1
for ci, (title, note, ss) in enumerate(BLOCKS):
    ch = {"id": "c%d" % (ci + 1), "title": title, "note": note, "sentences": []}
    for s in ss:
        n += 1
        assert len(CJK.findall(s["textS"])) == len(CJK.findall(s["textT"])) == len(s["pinyin"]), s["textS"]
        assert s["startMs"] > prev, ("时间不单调", s["textS"])
        prev = s["startMs"]
        ch["sentences"].append({
            "id": "s%d" % n, "startMs": s["startMs"], "endMs": s["endMs"],
            "textS": s["textS"], "textT": s["textT"], "pinyin": s["pinyin"]})
    chapters_out.append(ch)
assert flat[-1]["endMs"] <= DUR_MS, (flat[-1]["endMs"], DUR_MS)

doc = {"bookId": "daxue", "title": "大學", "audioDurationMs": DUR_MS, "chapters": chapters_out}
with open(OUT, "w", encoding="utf-8") as f:
    json.dump(doc, f, ensure_ascii=False, indent=1)
print("写出 %s | 节数 %d | 总句数 %d | 末句 endMs=%d（音频 %d）" %
      (OUT, len(chapters_out), n, flat[-1]["endMs"], DUR_MS))
for ch in chapters_out:
    print("  %s %s（%s）%d 句 | 首句 %s | 末句 %s" %
          (ch["id"], ch["title"], ch["note"], len(ch["sentences"]),
           ch["sentences"][0]["textT"][:16], ch["sentences"][-1]["textT"][:16]))
print("OK")
