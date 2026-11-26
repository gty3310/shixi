#!/usr/bin/env bash
# check_single_writer.sh — 遍数统计「单写入口」架构适应度检查（grep 级）
#
# 用途（依据：AGENTS.md 红线一、docs/adr/0001-single-source-of-truth-for-practice-stats.md）：
# 遍数出过「两本账、五条以上写入路径各写各的」的结构病。本脚本扫描 App 源码中
# 对两本账写入 API 的全部调用点（＝写入点），与基线文件逐条对比：
# · 基线之外的新写入点（新文件、新方法、或同一文件内调用次数增加）→ 报警，退出码 1，不许提交；
# · 写入点减少 → 只打印警告（多半是重构收敛，属好事），退出码 0。
# 基线条目格式：相对路径|被调用的写方法|该文件内调用次数。不记行号——行号随编辑漂移，
# 文件＋方法＋次数才是稳定的结构指纹。
#
# 当前扫描的写方法（两本账的对外写 API）：
# Store.increment / Store.setTrackCountOn /
# ChapterStatsStore.addAuto / ChapterStatsStore.addManual / ChapterStatsStore.adjustChapterToday /
# PlanData.addManual（计划手动账，第三份数据源，一并看守）
# 账本实现文件本身（Store.java、ChapterStatsStore.java、PlanData.java）不计入——它们是账，不是写入路径。
#
# PassCounter 单写入口落地后，按下面三步把本脚本收紧为硬规则：
# 1) 旧账写 API（increment/setTrackCountOn/addAuto/addManual/adjustChapterToday）的合法调用点
# 应只剩 PassCounter.java 一个文件：把 EXCLUDE_FILES 的豁免从账本实现文件换成 PassCounter.java，
# 其余任何命中即红灯；
# 2) 删除基线文件与 --write-baseline 模式——规则从「不许比基线多」变成「入口之外零写入点」；
# 3) 同步修订 AGENTS.md 红线一的表述（把「基线之外」改为「入口之外」）。
#
# 用法：
# tools/check_single_writer.sh 检查（默认）
# tools/check_single_writer.sh --write-baseline 重新生成基线（仅在人工逐条复核写入点清单后使用！）

set -u
cd "$(dirname "$0")/.." || exit 2
SRC="app/src/main/java"
BASELINE="tools/single_writer_baseline.txt"
PATTERN='\.(increment|setTrackCountOn|addAuto|addManual|adjustChapterToday)\('
EXCLUDE_RE='/(Store|ChapterStatsStore|PlanData)\.java:'

scan() {
grep -rHoP "$PATTERN" "$SRC" --include='*.java' 2>/dev/null \
| grep -vE "$EXCLUDE_RE" \
| sed -E 's/:\.([A-Za-z]+)\($/|\1/' \
| sort | uniq -c \
| awk '{n=$1; $1=""; sub(/^ /, ""); print $0 "|" n}' \
| sort
}

if [ "${1:-}" = "--write-baseline" ]; then
{
echo "# 遍数统计写入点基线（由 tools/check_single_writer.sh --write-baseline 生成）"
echo "# 格式：相对路径|被调用的写方法|该文件内调用次数。账本实现文件本身不计。"
echo "# 生成日期：$(date +%F)；生成前请人工逐条复核下面每一行都是已知、合法的写入点。"
scan
} > "$BASELINE"
echo "基线已写入 $BASELINE（共 $(grep -vc '^#' "$BASELINE") 条）"
exit 0
fi

[ -f "$BASELINE" ] || { echo "缺基线文件 $BASELINE，先人工复核后跑 --write-baseline" >&2; exit 2; }

CUR="$(mktemp)"; trap 'rm -f "$CUR"' EXIT
scan > "$CUR"

awk -F'|' '
FNR==NR { if ($0!~ /^#/ && NF==3) base[$1 SUBSEP $2]=$3; next}
{ key=$1 SUBSEP $2; cur[key]=$3;
if (!(key in base)) { print "新增写入点: " $1 " 调用 " $2 " ×" $3; bad=1}
else if ($3 > base[key]) { print "写入点增多: " $1 " 调用 " $2 " 由 " base[key] " 增至 " $3; bad=1}}
END {
for (k in base) if (!(k in cur) || cur[k] < base[k]) {
split(k, p, SUBSEP); print "提示（写入点减少，疑似重构收敛）: " p[1] " 调用 " p[2] > "/dev/stderr"}
exit bad
}' "$BASELINE" "$CUR" && echo "OK：无基线外写入点。" || { echo "FAIL：出现基线外写入点。按 AGENTS.md 红线一，新写入必须走唯一记账入口；确属合法新增时，人工复核后更新基线。" >&2; exit 1;}
