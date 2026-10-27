#!/bin/bash
# v1.20.4 发版门禁：识读模型下载计划桌面验收。
# 直接驱动 App 的 ModelPlans 本体（编译进 build/manual/classes 的那份）跑
# 计划构建断言。数据＝真实仓 git trees 索引＋真实清单（fetch_data.py 用
# 真实令牌按 App 相同端点取到本地快照；取数通道非被测对象）。
# 先跑 ./build-manual.sh 再跑本脚本。
set -e
cd "$(dirname "$0")/../.."

if [ -d "$HOME/workspace/tools/jdk17/bin" ]; then
  export PATH="$HOME/workspace/tools/jdk17/bin:$PATH"
fi
SDK=${ANDROID_HOME:-$HOME/workspace/tools/android-sdk}
AJAR=$SDK/platforms/android-34/android.jar

mkdir -p tools/model_harness/lib tools/model_harness/out
JSON_JAR=tools/model_harness/lib/json-20231013.jar
if [ ! -f "$JSON_JAR" ]; then
  echo "下载 org.json 桌面 jar（仅 harness 用，不入 APK/不入库）…"
  curl -fsSL -o "$JSON_JAR" \
    https://repo1.maven.org/maven2/org/json/json/20231013/json-20231013.jar
fi

if [ ! -d build/manual/classes ]; then
  echo "缺 build/manual/classes，先跑 ./build-manual.sh" >&2
  exit 1
fi

python3 tools/model_harness/fetch_data.py . tools/model_harness/out

javac -encoding UTF-8 -cp "build/manual/classes:$JSON_JAR:$AJAR" \
  -d tools/model_harness/out tools/model_harness/ModelPlanHarness.java

java -Dfile.encoding=UTF-8 -cp "tools/model_harness/out:build/manual/classes:$JSON_JAR:$AJAR" \
  ModelPlanHarness tools/model_harness/out \
  | tee tools/model_harness/harness_output_v1204.txt
