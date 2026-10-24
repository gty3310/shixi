import json, glob, sys
from pypinyin import pinyin, Style

chars = set()
for line in open('/tmp/spike/model/tokens.txt', encoding='utf-8'):
    tok = line.rsplit(' ', 1)[0].strip()
    if len(tok) == 1 and '一' <= tok <= '鿿':
        chars.add(tok)
files = ['/home/hatch/workspace/songdu-player/app/src/main/assets/content/daxue.json',
         '/home/hatch/workspace/bookstore/repos/zhongyong/content/zhongyong.json']
files += glob.glob('/home/hatch/workspace/bookstore/repos/lunyu/content/*.json')
for f in files:
    try:
        d = json.load(open(f, encoding='utf-8'))
    except Exception as e:
        print('skip', f, e); continue
    for ch in d['chapters']:
        for s in ch['sentences']:
            for t in (s.get('textT',''), s.get('textS','')):
                for c in t:
                    if '一' <= c <= '鿿':
                        chars.add(c)
print('chars:', len(chars), file=sys.stderr)

pairs = []
for c in sorted(chars):
    pys = pinyin(c, style=Style.NORMAL, heteronym=True)[0]
    pys = sorted({p.replace('ü', 'v').lower() for p in pys})
    pys = [p for p in pys if all('a' <= x <= 'z' for x in p)]
    if not pys:
        continue
    pairs.append(c + '/'.join(pys))
data_chunks = []
cur = ''
for p in pairs:
    if len(cur) + len(p) + 1 > 3000:
        data_chunks.append(cur); cur = p
    else:
        cur = (cur + ' ' + p) if cur else p
if cur: data_chunks.append(cur)
lit = ' +\n            '.join('"' + c.replace('\\','\\\\').replace('"','\\"') + '"' for c in data_chunks)
out = '''package com.ti.songdu.recite;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * v1.16.0-dev 识读：通用字→去声调拼音兜底表（自动生成，勿手改）。
 * 来源：ASR 模型 tokens 词表单字 + 本项目全部内容包（大学/中庸/论语）出现过的字，
 * 读音由 pypinyin 取全部常用读法（多音字多值，宁宽勿误伤）。仅当某字不在当前书的
 * 字→音节映射中时兜底使用，书内映射永远优先。生成方法见 tools/recite_test/gen_table 注释。
 */
public final class PinyinTable {

    private static Map<String, Set<String>> MAP;

    private PinyinTable() {}

    /** 字（单字字符串）→ 去声调音节集合；查不到返回 null */
    public static synchronized Set<String> syllablesOf(String ch) {
        if (MAP == null) {
            MAP = new HashMap<>();
            for (String e : DATA.split(" ")) {
                if (e.length() >= 2) {
                    Set<String> set = new HashSet<>();
                    for (String p : e.substring(1).split("/")) set.add(p);
                    MAP.put(e.substring(0, 1), set);
                }
            }
        }
        return MAP.get(ch);
    }

    private static final String DATA =
            LIT;
}
'''
out = out.replace('LIT', lit)
open('/home/hatch/workspace/songdu-player/app/src/main/java/com/ti/songdu/recite/PinyinTable.java','w',encoding='utf-8').write(out)
print('pairs:', len(pairs), 'chunks:', len(data_chunks))
