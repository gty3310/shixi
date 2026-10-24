#!/usr/bin/env python3
# 从内容包 JSON 生成桌面测试 fixture（sentences.tsv）：
# 每行一句：id \t textT \t textS \t 拼音(空格分隔) \t breakPos(逗号分隔，可空)
import json, sys

src = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/content/daxue.json"
out = sys.argv[2] if len(sys.argv) > 2 else "/tmp/spike/sentences.tsv"
d = json.load(open(src))
lines = []
for ch in d["chapters"]:
    for s in ch["sentences"]:
        bps = ",".join(str(b["p"]) for b in s.get("breaks", []))
        lines.append("\t".join([s["id"], s["textT"], s["textS"], " ".join(s["pinyin"]), bps]))
open(out, "w", encoding="utf-8").write("\n".join(lines) + "\n")
print(f"wrote {len(lines)} sentences -> {out}")
