#!/bin/bash
# 弦歌 Mac 版构建脚本
#   ./build.sh             构建 build/弦歌.app（需 macOS + Xcode 命令行工具）
#   ./build.sh --web-only  只组装 build/web-preview/（Web 界面＋内容数据），
#                          可用任意静态服务器预览，如：
#                          python3 -m http.server -d build/web-preview 8077
#
# 内容引用方式（重要）：三本书的内容只有一份源，构建时才拷入产物，
# macos/ 目录内不存内容副本：
#   《大学》  本 repo：../app/src/main/assets/{content,notes,content/gd}/
#   《中庸》《论语》正文与《中庸》注释：书库工作副本（安卓在线书库的源），
#     默认位置 ../../bookstore/repos（即与本 repo 同级的 bookstore/repos），
#     可用环境变量 BOOKSTORE_DIR 覆盖；《论语》注释与三本古读清单在本 repo assets。
# 诵读音频永不进 repo、永不进 .app（版权口径同安卓）。
set -euo pipefail
cd "$(dirname "$0")"

ASSETS="../app/src/main/assets"
BOOKSTORE_DIR="${BOOKSTORE_DIR:-../../bookstore/repos}"

# 组装 Web 资源到目标目录（$1）
assemble_web() {
  local dest="$1"
  mkdir -p "$dest/data"
  cp Web/index.html Web/app.css Web/app.js Web/bookdata.js Web/render.js "$dest/"

  # 《大学》（本 repo assets）
  cp "$ASSETS/content/daxue.json"        "$dest/data/daxue.json"
  cp "$ASSETS/notes/daxue_notes.json"    "$dest/data/daxue_notes.json"
  cp "$ASSETS/content/gd/daxue.json"     "$dest/data/gd_daxue.json"

  # 《中庸》《论语》正文在书库工作副本
  if [ ! -f "$BOOKSTORE_DIR/zhongyong/content/zhongyong.json" ] || \
     [ ! -f "$BOOKSTORE_DIR/lunyu/content/lunyu.json" ]; then
    echo "错误：找不到书库工作副本（《中庸》《论语》正文不在本 repo 内）。" >&2
    echo "当前 BOOKSTORE_DIR=$BOOKSTORE_DIR" >&2
    echo "请把 bookstore 工作副本放在本 repo 同级的 bookstore/repos 下，" >&2
    echo "或用 BOOKSTORE_DIR=/路径/repos ./build.sh 指定。" >&2
    exit 1
  fi
  cp "$BOOKSTORE_DIR/zhongyong/content/zhongyong.json" "$dest/data/zhongyong.json"
  cp "$BOOKSTORE_DIR/zhongyong/notes/zhongyong_notes.json" "$dest/data/zhongyong_notes.json"
  cp "$BOOKSTORE_DIR/lunyu/content/lunyu.json"         "$dest/data/lunyu.json"
  cp "$ASSETS/notes/lunyu_notes.json"                  "$dest/data/lunyu_notes.json"
  cp "$ASSETS/content/gd/zhongyong.json"               "$dest/data/gd_zhongyong.json"
  cp "$ASSETS/content/gd/lunyu.json"                   "$dest/data/gd_lunyu.json"
}

if [ "${1:-}" = "--web-only" ]; then
  rm -rf build/web-preview
  assemble_web build/web-preview
  echo "已组装：build/web-preview/（静态服务器预览即可）"
  exit 0
fi

if [ "$(uname)" != "Darwin" ]; then
  echo "错误：.app 构建只能在 macOS 上进行（当前系统为 $(uname)）。" >&2
  echo "只想预览 Web 界面可用：./build.sh --web-only" >&2
  exit 1
fi

APP="build/弦歌.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
assemble_web "$APP/Contents/Resources/Web"
cp App/Info.plist "$APP/Contents/Info.plist"
swiftc -O -o "$APP/Contents/MacOS/Xiange" App/main.swift \
  -framework AppKit -framework WebKit
echo "构建完成：$APP（双击或 open \"$APP\" 运行）"
