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

v1.8.0（2026-10-16）：据《大学》PDF 全书复核报告全面按原书校正——
分节由 8 节改按原书 12 小节重组（句数 15/8/9/11/13/6/4/26/8/15/17/11；
BLOCKS 的 8 节分法只作取文组织、不再作分节依据；章题式注记书上没有，
note 一律置 null）；改字 2 处（s107 儀監於殷、s120 一介臣）＋体例定案
1 处（s79 正文作「帥」、不从旁注「率」，用户定案：正文与旁注分歧跟正文走）；
注音 14 音节按书改。写出前与现行 daxue.json 硬校验：逐句 startMs/endMs
全等（只许文字/拼音/分节变）、逐句繁体字数不变，并把现行包 breaks 按
拉平位置合并回（改字均为等长一对一，break 下标仍有效）。
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

# ---- 按书校正的辅助函数（v1.8.0）----
def sec_num(i):
    """1 起的小节序号 → 汉字数字（九、十、十一、二十、二十四…）"""
    CN = "一二三四五六七八九"
    if i < 10:
        return CN[i - 1]
    tens, ones = divmod(i, 10)
    return (CN[tens - 1] if tens > 1 else "") + "十" + (CN[ones - 1] if ones else "")


def _py_pairs(sent):
    """textT 中每个 CJK 字与其拼音下标的配对（与 reader 的 CJK 口径一致）"""
    pairs, k = [], 0
    for ch in sent["textT"]:
        if CJK.match(ch):
            pairs.append((ch, k))
            k += 1
    assert k == len(sent["pinyin"]), sent["id"]
    return pairs


def fix_py(sent, sno, char, old, new, count=None, which=None):
    """句内注音修正：把读 old 的 char 改读 new。count=断言命中总数；
    which=只改第几个命中（0 起），其余保留。返回改动音节数。"""
    hits = [k for ch, k in _py_pairs(sent)
            if ch == char and sent["pinyin"][k] == old]
    if which is not None:
        assert len(hits) > which, "s%d「%s」(%s) 第 %d 处不存在" % (sno, char, old, which + 1)
        hits = [hits[which]]
    elif count is not None:
        assert len(hits) == count, \
            "s%d「%s」(%s) 命中 %d 处，与报告预期 %d 不符" % (sno, char, old, len(hits), count)
    for k in hits:
        sent["pinyin"][k] = new
    return len(hits)


def fix_text(sent, sno, old_t, new_t, old_s, new_s):
    assert old_t in sent["textT"], "s%d 繁体未找到「%s」：%s" % (sno, old_t, sent["textT"])
    assert old_s in sent["textS"], "s%d 简体未找到「%s」：%s" % (sno, old_s, sent["textS"])
    sent["textT"] = sent["textT"].replace(old_t, new_t, 1)
    sent["textS"] = sent["textS"].replace(old_s, new_s, 1)


def apply_book_fixes(out_sents):
    """《大学》按原书校正（PDF 全书复核报告第四节）：改字 2 处、体例定案 1 处、
    注音 14 音节。句号为内容包句序（古本序新序）。"""
    S = lambda n: out_sents[n - 1]
    # 定字 2 处
    fix_text(S(107), 107, "宜鑒於殷", "儀監於殷", "宜鉴于殷", "仪监于殷")
    fix_text(S(120), 120, "一個臣", "一介臣", "一个臣", "一介臣")
    # 体例定案（单独标注）：s79 书正文作「帥」、黑框旁注「率」——按用户定案
    # 「正文与旁注分歧一律跟正文走」改作帥，音 shuài（原 lǜ 随字改）。
    fix_text(S(79), 79, "率天下", "帥天下", "率天下", "帅天下")
    # 报告注音条「s84 子之惡 wù→è」：经全包定位，含「子之惡」的句子实为 s65
    # 「人莫知其子之惡」，现行包该处已读 è 与书合——断言确认，不改动；
    # s84 本身（故治國在齊其家）无惡字。
    s65_mo = [k for ch, k in _py_pairs(S(65)) if ch == "惡"]
    assert len(s65_mo) == 1 and S(65)["pinyin"][s65_mo[0]] == "è", S(65)
    # 注音 14 音节
    n = 0
    n += fix_py(S(13), 13, "薄", "báo", "bó", count=2)
    n += fix_py(S(22), 22, "曾", "céng", "zēng", count=1)
    n += fix_py(S(45), 45, "緡", "mín", "mián", count=1)
    n += fix_py(S(59), 59, "好", "hǎo", "hào", count=1)
    n += fix_py(S(59), 59, "樂", "lè", "yào", count=1)
    n += fix_py(S(64), 64, "惡", "wù", "è", which=0)   # 其惡（名詞）改 è；「惡而」動詞仍 wù
    n += fix_py(S(72), 72, "中", "zhōng", "zhòng", count=1)
    n += fix_py(S(79), 79, "帥", "lǜ", "shuài", count=1)
    n += fix_py(S(120), 120, "介", "gè", "jiè", count=1)
    n += fix_py(S(127), 127, "迸", "bèng", "bǐng", count=1)
    n += fix_py(S(131), 131, "逮", "dǎi", "dài", count=1)
    n += fix_py(S(137), 137, "畜", "chù", "xù", count=1)
    n += fix_py(S(138), 138, "畜", "chù", "xù", count=1)
    assert n == 14, "注音改动 %d 音节，与报告 14 不符" % n
    print("大学按书校正完成：定字 2 处＋体例定案 1 处（帥）＋注音 14 音节；"
          "报告 s84 条经定位为 s65、现值已合书（断言确认）")


# ---- 组装与校验 ----
out_sents = []
prev = -1
for n, s in enumerate(flat, 1):
    assert len(CJK.findall(s["textS"])) == len(CJK.findall(s["textT"])) == len(s["pinyin"]), s["textS"]
    assert s["startMs"] > prev, ("时间不单调", s["textS"])
    prev = s["startMs"]
    out_sents.append({
        "id": "s%d" % n, "startMs": s["startMs"], "endMs": s["endMs"],
        "textS": s["textS"], "textT": s["textT"], "pinyin": list(s["pinyin"])})
assert flat[-1]["endMs"] <= DUR_MS, (flat[-1]["endMs"], DUR_MS)

# ---- v1.8.0 按原书校正 ----
apply_book_fixes(out_sents)

# ---- breaks 合并＋与现行包时间硬校验 ----
# 现行 daxue.json 的 breaks 由 gen_breaks.py 生成（标点级逐字对齐），本脚本不
# 重算，按拉平位置合并回；合并前硬校验：新旧逐句 startMs/endMs 全等（本版只许
# 文字/拼音/分节变）、逐句繁体字数不变（改字均为等长一对一，break 下标仍有效）。
if os.path.exists(OUT):
    old_doc = json.load(open(OUT, encoding="utf-8"))
    oflat = [x for c in old_doc["chapters"] for x in c["sentences"]]
    assert len(oflat) == len(out_sents), "现行包句数不一致"
    merged = []
    for i, (o, nw) in enumerate(zip(oflat, out_sents), 1):
        assert o["startMs"] == nw["startMs"] and o["endMs"] == nw["endMs"], \
            "硬校验失败：第 %d 句时间戳与现行包不一致" % i
        assert len(CJK.findall(o["textT"])) == len(CJK.findall(nw["textT"])), \
            "第 %d 句字数变化，breaks 不能按位合并" % i
        d = {"id": nw["id"], "startMs": nw["startMs"], "endMs": nw["endMs"]}
        if o.get("breaks"):
            d["breaks"] = o["breaks"]
        d["textS"], d["textT"], d["pinyin"] = nw["textS"], nw["textT"], nw["pinyin"]
        merged.append(d)
    out_sents = merged
    print("breaks 合并完成，与现行包 143 句时间戳硬校验通过")

# ---- 按原书 12 小节重组（书上无章题，note 置 null）----
GROUPS = [15, 8, 9, 11, 13, 6, 4, 26, 8, 15, 17, 11]
assert sum(GROUPS) == 143
chapters_out, pos = [], 0
for gi, cnt in enumerate(GROUPS, 1):
    chapters_out.append({"id": "c%d" % gi, "title": "第" + sec_num(gi) + "節",
                         "note": None, "sentences": out_sents[pos:pos + cnt]})
    pos += cnt

doc = {"bookId": "daxue", "title": "大學", "audioDurationMs": DUR_MS, "chapters": chapters_out}
with open(OUT, "w", encoding="utf-8") as f:
    json.dump(doc, f, ensure_ascii=False, indent=1)
print("写出 %s | 节数 %d | 总句数 %d | 末句 endMs=%d（音频 %d）" %
      (OUT, len(chapters_out), len(out_sents), flat[-1]["endMs"], DUR_MS))
for ch in chapters_out:
    print("  %s %s %d 句 | 首句 %s | 末句 %s" %
          (ch["id"], ch["title"], len(ch["sentences"]),
           ch["sentences"][0]["textT"][:16], ch["sentences"][-1]["textT"][:16]))
print("OK")
