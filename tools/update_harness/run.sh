#!/bin/bash
# v1.22.0 发版门禁：应用内更新纯逻辑桌面验收。
# 直接驱动 App 的 UpdateLogic 本体（-sourcepath 指向 App 源码，不另抄一份）。
# org.json 桌面 jar 与 model_harness 共用（仅 harness 用，不入 APK）。
set -e
cd "$(dirname "$0")/../.."

if [ -d "$HOME/workspace/tools/jdk17/bin" ]; then
  export PATH="$HOME/workspace/tools/jdk17/bin:$PATH"
fi

JSON_JAR=tools/model_harness/lib/json-20231013.jar
mkdir -p tools/model_harness/lib
if [ ! -f "$JSON_JAR" ]; then
  echo "下载 org.json 桌面 jar（仅 harness 用，不入 APK/不入库）…"
  curl -fsSL -o "$JSON_JAR" \
    https://repo1.maven.org/maven2/org/json/json/20231013/json-20231013.jar
fi

OUT=/tmp/update_harness_out
rm -rf "$OUT" && mkdir -p "$OUT"
javac -encoding UTF-8 -cp "$JSON_JAR" -sourcepath app/src/main/java \
  -d "$OUT" tools/update_harness/UpdateHarness.java
java -Dfile.encoding=UTF-8 -cp "$OUT:$JSON_JAR" UpdateHarness \
  | tee tools/update_harness/harness_output_v1220.txt
