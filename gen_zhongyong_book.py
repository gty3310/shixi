# -*- coding: utf-8 -*-
"""v1.8.0《中庸》内容包按《学庸论语》原书重做（2026-10-16）。

依据（先通读）：
- ~/workspace/your_files/中庸照片对账报告.md（前段：分节、定字、标点、拼音前段清单）
- ~/workspace/your_files/中庸全篇分节与尾段对账.md（全篇 24 节分节表、尾段定字、
  拼音全量 47 处/49 音节清单）
- 体例定案（用户 2026-10-16 拍板）：正文与旁注分歧一律跟书的正文走——
  「半涂而废」从正文作涂（旁注途）；s159「既」字从正文、音从书 xì。

做法：以现行 zhongyong.json（v1.7.1，300 句）为基底原地修订，保留全部句界
时间戳与 breaks；仅补句所在的 s134–s136 一带因老 SRT 句界本身错位（逐字比对
实证，见接缝段注释），按既有「逐字＋静音吸附」口径一并校正该段四界与两句
的估算 break 点，其余各句时间戳分毫不动。

变换：
1. 按原书重组为 24 小节（分节表 SECTIONS，句号为旧包句序；标题「第○節」、
   note 置 null，与 daxue.json 小节模型一致）。原 33 章分组废弃。
2. 定字：s13 增字「反」（见下）、s38 涂、s81 耽、s91 句末问号改叹号、
   s219 壹、s225 曰＋惟、s282 简体「䌹」归一为「絅」。
   —— s13 特注：书与录音均为「小人之反中庸也」，是在「之」后增字「反」，
   不是把「中」替换成「反」（照片报告第五节的 SRT 指令写作同位替换，与其
   第一节所引书文矛盾；以书文＋录音为准）。故 s13 繁/简各增一字、拼音插入
   fǎn、原 zhōng 归后一位的「中」、break 下标 p 6→7（时间不变）。
3. 注音：报告清单 47 处/49 音节逐项落实（脚本内计数断言 =49），另定字联动
   改音 2 音节（耽 zhàn→dān、曰 yún→yuē；惟 wéi 不变）。s90「夫」只改句末
   一个（照片报告仅引句末夫；报告 47 处/49 音节的计数亦与此吻合：仅
   s190/s191 的「尽」为双音节行）。
4. 补漏句：书中「在下位，不获乎上，民不可得而治矣。」出现两次，现行包只有
   第二次（旧 s168）。逐字稿证实录音两遍都念了，第一次在旧 s136「礼所生也」
   之后、旧 s137「故君子不可以不修身」之前。补入该处：textS/textT 用任务定案
   文本（与书一致；旧 s168 包内文本无「位」后逗号且句末作「；」，属另一处
   既有小异，本版不动 s168 本身），pinyin 直接复制旧 s168（同 14 字、口径
   一致），时间与 breaks 按逐字＋静音吸附定界（见 JUNCTION）。
   句 id 规则：未改句保持原 id（s1…s300，id 是句子的稳定标识、随句走）；
   补入句取下一个未用编号 s301。阅读顺序由 chapters 数组顺序决定，与 id
   数值顺序无关（ContentRepo 拉平即按数组顺序）。

基底断言：脚本断言输入为未改版（300 句、旧值逐项核对），重复运行会报错，
防误覆盖。跑完照例跑 check_chunks.py 自检。
"""
import json, re, os

BASE = os.path.expanduser("~/workspace")
SRC = os.path.join(BASE, "songdu-player/app/src/main/assets/content/zhongyong.json")
CJK = re.compile(r'[一-鿿㐀-䶿]')


def sec_num(i):
    """1 起的小节序号 → 汉字数字（九、十、十一、二十、二十四…）"""
    CN = "一二三四五六七八九"
    if i < 10:
        return CN[i - 1]
    tens, ones = divmod(i, 10)
    return (CN[tens - 1] if tens > 1 else "") + "十" + (CN[ones - 1] if ones else "")


def _py_pairs(sent):
    pairs, k = [], 0
    for ch in sent["textT"]:
        if CJK.match(ch):
            pairs.append((ch, k))
            k += 1
    assert k == len(sent["pinyin"]), sent["id"]
    return pairs


def fix_py(sent, sno, char, old, new, count=None, which=None):
    """句内注音修正：把读 old 的 char 改读 new。count=断言命中总数；
    which=只改第几个命中（0 起）。返回改动音节数。"""
    hits = [k for ch, k in _py_pairs(sent)
            if ch == char and sent["pinyin"][k] == old]
    if which is not None:
        assert len(hits) > which, "s%d「%s」(%s) 第 %d 处不存在" % (sno, char, old, which + 1)
        hits = [hits[which]]
    elif count is not None:
        assert len(hits) == count, \
            "s%d「%s」(%s) 命中 %d 处，与预期 %d 不符" % (sno, char, old, len(hits), count)
    for k in hits:
        sent["pinyin"][k] = new
    return len(hits)


def fix_text(sent, sno, old_t, new_t, old_s, new_s):
    assert old_t in sent["textT"], "s%d 繁体未找到「%s」：%s" % (sno, old_t, sent["textT"])
    assert old_s in sent["textS"], "s%d 简体未找到「%s」：%s" % (sno, old_s, sent["textS"])
    sent["textT"] = sent["textT"].replace(old_t, new_t, 1)
    sent["textS"] = sent["textS"].replace(old_s, new_s, 1)


doc = json.load(open(SRC, encoding="utf-8"))
flat = [s for c in doc["chapters"] for s in c["sentences"]]
assert len(flat) == 300, "基底应为未改版 300 句，实为 %d 句（勿重复运行）" % len(flat)
assert [s["id"] for s in flat] == ["s%d" % i for i in range(1, 301)]
S = lambda n: flat[n - 1]

# ================= 定字 =================
# s13 增字「反」（详见头部特注）：繁/简增字、拼音插入 fǎn、break 下标 +1
s13 = S(13)
assert s13["textT"] == "小人之中庸也，小人而無忌憚也。」", s13["textT"]
assert s13["textS"] == "小人之中庸也，小人而无忌惮也。」", s13["textS"]
assert len(s13["pinyin"]) == 13 and s13["pinyin"][3] == "zhōng"
s13["textT"] = "小人之反中庸也，小人而無忌憚也。」"
s13["textS"] = "小人之反中庸也，小人而无忌惮也。」"
s13["pinyin"] = s13["pinyin"][:3] + ["fǎn"] + s13["pinyin"][3:]
assert len(s13["pinyin"]) == 14
assert len(s13["breaks"]) == 1 and s13["breaks"][0]["p"] == 6 and s13["breaks"][0]["t"] == 60940
s13["breaks"][0]["p"] = 7  # 逗号随增字右移一位，时间不动

# s38 半涂而废（体例定案：从正文「涂」，不从旁注「途」；音 tú 不变）
fix_text(S(38), 38, "半途而廢", "半塗而廢", "半途而废", "半涂而废")
# s81 和乐且耽
fix_text(S(81), 81, "和樂且湛", "和樂且耽", "和乐且湛", "和乐且耽")
# s91 句末问号改叹号
s91 = S(91)
assert s91["textT"].endswith("與？") and s91["textS"].endswith("与？")
s91["textT"] = s91["textT"][:-1] + "！"
s91["textS"] = s91["textS"][:-1] + "！"
# s219 壹言（一→壹，音 yī 不变）
fix_text(S(219), 219, "一言而盡", "壹言而盡", "一言而尽", "壹言而尽")
# s225 《诗》曰＋惟天之命
fix_text(S(225), 225, "《詩》云", "《詩》曰", "《诗》云", "《诗》曰")
fix_text(S(225), 225, "維天之命", "惟天之命", "维天之命", "惟天之命")
# s282 简体「䌹」归一为「絅」（繁体已是絅）
assert S(282)["textS"].count("䌹") == 1 and "絅" in S(282)["textT"]
S(282)["textS"] = S(282)["textS"].replace("䌹", "絅", 1)

# ================= 注音（报告清单 47 处 / 49 音节）=================
n = 0
n += fix_py(S(19), 19, "夫", "fū", "fú", count=1)
n += fix_py(S(20), 20, "知", "zhī", "zhì", count=1)
n += fix_py(S(20), 20, "與", "yǔ", "yú", count=1)
n += fix_py(S(23), 23, "知", "zhī", "zhì", which=0)   # 只改「予知」的知；「知辟」的知仍 zhī
n += fix_py(S(23), 23, "辟", "pì", "bì", count=1)
n += fix_py(S(24), 24, "知", "zhī", "zhì", count=1)
n += fix_py(S(30), 30, "而", "ér", "ěr", count=1)      # 抑而
n += fix_py(S(37), 37, "素", "suǒ", "sù", count=1)
n += fix_py(S(58), 58, "丘", "qiū", "mǒu", count=1)    # 书圈注避讳读法
n += fix_py(S(64), 64, "盡", "jǐn", "jìn", count=1)
n += fix_py(S(76), 76, "俟", "qí", "sì", count=1)
n += fix_py(S(85), 85, "遺", "wèi", "yí", count=1)
n += fix_py(S(90), 90, "夫", "fū", "fú", which=1)      # 只改句末夫（见头部注）
n += fix_py(S(91), 91, "與", "yǔ", "yú", count=1)
n += fix_py(S(103), 103, "大", "dà", "tài", count=1)
n += fix_py(S(106), 106, "大", "dà", "tài", count=1)
# s106「追王」的王：定位「追」之后第一个王（wáng→wàng）；大王、王季之王不动
pairs106 = _py_pairs(S(106))
ai = [i for i, (ch, k) in enumerate(pairs106) if ch == "追"]
assert len(ai) == 1
done = False
for ch, k in pairs106[ai[0] + 1:]:
    if ch == "王":
        assert S(106)["pinyin"][k] == "wáng"
        S(106)["pinyin"][k] = "wàng"
        done = True
        break
assert done
n += 1
n += fix_py(S(115), 115, "裳", "shang", "cháng", count=1)
n += fix_py(S(119), 119, "逮", "dǎi", "dài", count=1)
n += fix_py(S(132), 132, "夫", "fū", "fú", count=1)
n += fix_py(S(151), 151, "子", "zi", "zǐ", count=1)     # 子庶民的子（轻声→上声）
n += fix_py(S(158), 158, "薄", "báo", "bó", count=1)
n += fix_py(S(159), 159, "既", "jì", "xì", count=1)    # 体例：字从正文、音从书
n += fix_py(S(161), 161, "薄", "báo", "bó", count=1)
n += fix_py(S(175), 175, "中", "zhōng", "zhòng", count=1)  # 從容中道；「不勉而中」已 zhòng
n += fix_py(S(189), 189, "盡", "jǐn", "jìn", count=1)
n += fix_py(S(190), 190, "盡", "jǐn", "jìn", count=2)
n += fix_py(S(191), 191, "盡", "jǐn", "jìn", count=2)
n += fix_py(S(192), 192, "盡", "jǐn", "jìn", count=1)
n += fix_py(S(193), 193, "參", "cān", "sān", count=1)
n += fix_py(S(201), 201, "將", "jiàng", "jiāng", count=1)
n += fix_py(S(205), 205, "道", "dǎo", "dào", count=1)  # 自道的道；「而道」已 dào
n += fix_py(S(210), 210, "知", "zhī", "zhì", count=1)
n += fix_py(S(219), 219, "盡", "jǐn", "jìn", count=1)
n += fix_py(S(222), 222, "撮", "zuǒ", "cuō", count=1)
n += fix_py(S(234), 234, "道", "dǎo", "dào", count=1)  # 道問學
n += fix_py(S(234), 234, "盡", "jǐn", "jìn", count=1)  # 盡精微
n += fix_py(S(253), 253, "俟", "qí", "sì", count=1)
n += fix_py(S(255), 255, "俟", "qí", "sì", count=1)
n += fix_py(S(256), 256, "道", "dǎo", "dào", count=1)
n += fix_py(S(256), 256, "行", "héng", "xíng", count=1)
n += fix_py(S(258), 258, "惡", "è", "wù", count=1)
n += fix_py(S(283), 283, "惡", "è", "wù", count=1)
n += fix_py(S(288), 288, "惡", "è", "wù", count=1)
n += fix_py(S(290), 290, "相", "xiāng", "xiàng", count=1)
n += fix_py(S(296), 296, "予", "yǔ", "yú", count=1)
n += fix_py(S(296), 296, "色", "shǎi", "sè", count=1)
assert n == 49, "注音改动 %d 音节，与报告 49 不符" % n
# 定字联动改音 2 音节（不计入上述 49）
m = 0
m += fix_py(S(81), 81, "耽", "zhàn", "dān", count=1)
m += fix_py(S(225), 225, "曰", "yún", "yuē", count=1)
assert m == 2
print("中庸按书校正：定字 7 句（s13 为增字）＋注音 49 音节＋定字联动改音 2 音节")

# ================= 补漏句＋接缝定界 =================
# 逐字稿（中庸-读诵版-完整_words.json）实测（秒）：
#   s136 末字「也」631.72–631.92；在 633.08–633.56 … 矣 637.26–637.48；
#   s137 首字「故」638.12–638.54；s135 末字「大」626.44–626.64；s136 首字「親」628.06。
# 静音段（ffmpeg silencedetect -40dB/0.06s，对完整版原速 mp3）：
#   626.678–628.047（大/親间）、629.056–629.600（殺/尊间）、
#   630.517–630.927（等/禮间）、631.972–633.389（也/在间）、
#   634.221–634.533（位/不间）、635.331–635.757（上/民間）。
# 按「句界/停顿点 = 相邻字中点、吸附最近静音段中点（±0.3s 内）」口径：
#   s135|s136 界 = 627360；s136 内 break：p4（殺，）= 629330、p9（等，）= 630720
#     —— 旧包此二点为估算值 632710/635410（带 e:1），后者已落在补入句音频区内、
#        且新 s136 endMs 之前/之后关系矛盾，必须按逐字实测改定（去掉 e 标记）；
#   s136|补句 界 = 632680；补句 break：p3（位，）= 634380、p8（上，）= 635540；
#   补句|s137 界 = 638120（与现行 s137 startMs 一致，不动）。
# 连带说明：老 SRT 在 s134–s136 这一带句界整体错位（与逐字稿逐字比对实证：
# s134|s135 界 626440 落在 s135 末字「大」的起点上、s135|s136 界 630000 落在
# s136 句中「之」上；相邻的 s133|s134 界 621220、s137|s138 界 641280 都是精确
# 的下句首字起点）。补句正落在此段：若不校正，s136 的 break 会跑到句界之外、
# s135 的两个估算 break（627330/628220，均 e:1）也会落在句外，跟读分段单调性
# 必然失败。故接缝四界与两句的估算 break 在此一并按同口径校正：
#   s134|s135 界 = 624210（大 end 623.82 / 義 start 624.54，静音 623.895–624.532 中点）
#   s135 break：p2（者，）= 625010（静音 624.891–625.120 中点）、
#               p5（也，）= 625620（静音 625.490–625.745 中点），均去掉 e 标记；
#   s135|s136 界 = 627360（见上）；s136 break 改实测（见上）。
# 除此之外全书时间戳分毫不动。SRT 同步改此 3 条（s134/s135/s136）＋插入 1 条。
s134, s135, s136, s137, s168 = S(134), S(135), S(136), S(137), S(168)
assert (s134["endMs"], s135["startMs"], s135["endMs"],
        s136["startMs"], s136["endMs"], s137["startMs"]) == \
    (626440, 626440, 630000, 630000, 638120, 638120)
assert s135["breaks"] == [{"p": 2, "t": 627330, "e": 1}, {"p": 5, "t": 628220, "e": 1}]
assert s136["breaks"] == [{"p": 4, "t": 632710, "e": 1}, {"p": 9, "t": 635410, "e": 1}]
s134["endMs"] = 624210
s135["startMs"] = 624210
s135["endMs"] = 627360
s135["breaks"] = [{"p": 2, "t": 625010}, {"p": 5, "t": 625620}]
s136["startMs"] = 627360
s136["endMs"] = 632680
s136["breaks"] = [{"p": 4, "t": 629330}, {"p": 9, "t": 630720}]
inserted = {
    "id": "s301",
    "startMs": 632680,
    "endMs": 638120,
    "breaks": [{"p": 3, "t": 634380}, {"p": 8, "t": 635540}],
    "textS": "在下位，不获乎上，民不可得而治矣。",
    "textT": "在下位，不獲乎上，民不可得而治矣。",
    "pinyin": list(s168["pinyin"]),
}
assert len(inserted["pinyin"]) == 14  # 在下位(3)＋不獲乎上(4)＋民不可得而治矣(7)
assert inserted["startMs"] < inserted["breaks"][0]["t"] < inserted["breaks"][1]["t"] < inserted["endMs"]
print("补漏句定界：s301 632680–638120（在 633.08 起、矣 637.48 止，"
      "界取静音中点 632680 / 沿用 s137 起点 638120）；接缝 s134 end、s135 start/end、"
      "s136 start/end 与 s135/s136 估算 break 已按逐字＋静音吸附校正")

# ================= 按原书 24 小节重组（句号为旧包句序）=================
SECTIONS = [(1, 10), (11, 19), (20, 22), (23, 24), (25, 26), (27, 36),
            (37, 51), (52, 76), (77, 83), (84, 90), (91, 100), (101, 112),
            (113, 126), (127, 146), (147, 172), (173, 185), (186, 193),
            (194, 211), (212, 228), (229, 240), (241, 246), (247, 260),
            (261, 281), (282, 300)]
by_no = {i + 1: s for i, s in enumerate(flat)}
chapters = []
for gi, (a, b) in enumerate(SECTIONS, 1):
    sents = [by_no[k] for k in range(a, b + 1)]
    if gi == 14:
        i136 = sents.index(by_no[136])
        sents = sents[:i136 + 1] + [inserted] + sents[i136 + 1:]
    chapters.append({"id": "c%d" % gi, "title": "第" + sec_num(gi) + "節",
                     "note": None, "sentences": sents})
doc["chapters"] = chapters

# ================= 全量校验 =================
new_flat = [s for c in chapters for s in c["sentences"]]
assert len(new_flat) == 301
assert len({s["id"] for s in new_flat}) == 301
prev_start, prev_end = -1, -1
for s in new_flat:
    assert len(CJK.findall(s["textS"])) == len(CJK.findall(s["textT"])) == len(s["pinyin"]), s["id"]
    assert s["startMs"] > prev_start and s["endMs"] > s["startMs"], s["id"]
    assert s["endMs"] >= prev_end, s["id"]
    prev_start, prev_end = s["startMs"], s["endMs"]
    for br in s.get("breaks", []):
        ch = s["textT"][br["p"]]
        assert ch in "，。；！？", (s["id"], br)
with open(SRC, "w", encoding="utf-8") as f:
    json.dump(doc, f, ensure_ascii=False, indent=1)
print("写出 %s | 节数 %d | 总句数 %d" % (SRC, len(chapters), len(new_flat)))
for c in chapters:
    print("  %s %s %d 句" % (c["id"], c["title"], len(c["sentences"])))
print("OK")
