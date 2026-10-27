#!/usr/bin/env python3
"""v1.20.4 harness 取数：把真实仓的 git trees 索引与 model-manifest.json
取到本地文件，供 ModelPlanHarness 喂给 App 的 ModelPlans 本体。

取数通道不是被测对象（被测的是计划构建逻辑），这里只按 App 相同的
API 端点（git trees 根树＋一层子树、git blobs 取清单）取原始数据。
用法：fetch_data.py <repo_root> <out_dir>
"""
import base64
import json
import os
import sys
import urllib.parse
import urllib.request

API_BASE = "https://gitee.com/api/v5"
OWNER = "gty3310"
REPO = "shixi-models"


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "Shixi-Harness"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read().decode("utf-8"))


def main():
    root, out_dir = sys.argv[1], sys.argv[2]
    token = open(os.path.join(root, "store_token.txt"), encoding="utf-8").read()
    token = "".join(token.split()).strip('"').replace("\\", "")
    if not token:
        raise SystemExit("store_token.txt 为空")
    q = "?access_token=" + urllib.parse.quote(token)
    base = f"{API_BASE}/repos/{OWNER}/{REPO}"

    sha_by_path = {}
    tree = get(f"{base}/git/trees/master{q}")
    for e in tree["tree"]:
        if e.get("type") == "tree":
            sub = get(f"{base}/git/trees/{e['sha']}{q}")
            for s in sub["tree"]:
                sha_by_path[e["path"] + "/" + s["path"]] = s["sha"]
        else:
            sha_by_path[e["path"]] = e["sha"]

    blob = get(f"{base}/git/blobs/{sha_by_path['model-manifest.json']}{q}")
    manifest = json.loads(base64.b64decode(blob["content"]).decode("utf-8"))

    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, "sha_by_path.json"), "w", encoding="utf-8") as f:
        json.dump(sha_by_path, f)
    with open(os.path.join(out_dir, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f)
    print(f"取数完成：trees {len(sha_by_path)} 条，清单已落盘 {out_dir}")


if __name__ == "__main__":
    main()
