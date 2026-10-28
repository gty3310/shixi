#!/bin/bash
# 手动构建管线（本机 Gradle 守护进程 IPC 被沙箱拦截，故用 SDK build-tools 直连构建）
# 产物与 Gradle assembleDebug 等价：debug 签名的可安装 APK
set -e
cd "$(dirname "$0")"

# JDK 17 若装在 workspace 持久目录，自动加入 PATH（系统 PATH 无 java 时靠它）
if [ -d "$HOME/workspace/tools/jdk17/bin" ]; then
  export PATH="$HOME/workspace/tools/jdk17/bin:$PATH"
fi

SDK=${ANDROID_HOME:-$HOME/workspace/tools/android-sdk}
BT=$SDK/build-tools/34.0.0
AJAR=$SDK/platforms/android-34/android.jar
SRC=app/src/main
B=build/manual
rm -rf $B
mkdir -p $B/gen $B/classes $B/dex

echo "[1/6] aapt2 compile"
$BT/aapt2 compile --dir $SRC/res -o $B/compiled.zip

echo "[2/6] aapt2 link"
$BT/aapt2 link -o $B/base.apk -I $AJAR \
  --manifest $SRC/AndroidManifest.xml \
  --java $B/gen \
  -A $SRC/assets \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code 43 --version-name 1.20.6 \
  $B/compiled.zip

echo "[2.5/6] gen BuildInfo（构建提交短号：从 git HEAD 注入，不手写）"
GIT_SHORT=$(git rev-parse --short HEAD 2>/dev/null || echo unknown)
mkdir -p $B/gen/com/ti/songdu
cat > $B/gen/com/ti/songdu/BuildInfo.java <<EOF
package com.ti.songdu;
// 构建时由 build-manual.sh 生成；源文件在 build 目录内，永不提交。
public final class BuildInfo {
    public static final String VERSION_NAME = "1.20.6";
    public static final int VERSION_CODE = 43;
    public static final String GIT_SHORT = "$GIT_SHORT";
    private BuildInfo() {}
}
EOF

echo "[2.5/6] gen StoreSecrets（书库令牌：只进 build 目录，永不入库）"
if [ -f store_token.txt ]; then
  TOK=$(tr -d '[:space:]' < store_token.txt | tr -d '"\\')
else
  TOK=""
fi
mkdir -p $B/gen/com/ti/songdu
cat > $B/gen/com/ti/songdu/StoreSecrets.java <<EOF
package com.ti.songdu;
// 构建时由 build-manual.sh 从 store_token.txt 生成；源文件在 build 目录内，永不提交。
public final class StoreSecrets {
    public static final String GITEE_TOKEN = "$TOK";
    private StoreSecrets() {}
}
EOF

echo "[3/6] javac"
find $SRC/java $B/gen -name '*.java' > $B/sources.txt
javac --release 8 -encoding UTF-8 -cp $AJAR -d $B/classes @$B/sources.txt

echo "[4/6] d8"
(cd $B/classes && jar cf ../classes.jar .)
$BT/d8 --min-api 26 --lib $AJAR --output $B/dex $B/classes.jar

echo "[5/6] package + zipalign"
cp $B/base.apk $B/unsigned.apk
(cd $B/dex && zip -q ../unsigned.apk classes.dex)
# v1.16.0 识读原生库（sherpa-onnx JNI＋onnxruntime，仅 arm64-v8a）：
# 由 fetch-asr-deps.sh 拉到 asr-libs/（不入 git）；缺了也能构建出包，
# 但识读功能会在启动时报错——构建日志明确警告，不许静默出残缺包。
mkdir -p $B/nativelib/lib/arm64-v8a
if [ -f asr-libs/arm64-v8a/libsherpa-onnx-jni.so ] && [ -f asr-libs/arm64-v8a/libonnxruntime.so ]; then
  cp asr-libs/arm64-v8a/libsherpa-onnx-jni.so asr-libs/arm64-v8a/libonnxruntime.so $B/nativelib/lib/arm64-v8a/
  (cd $B/nativelib && zip -q ../unsigned.apk lib/arm64-v8a/libsherpa-onnx-jni.so lib/arm64-v8a/libonnxruntime.so)
  echo "    native libs 打入 APK（lib/arm64-v8a/）"
else
  echo "    ⚠️ 警告：asr-libs/arm64-v8a/*.so 缺失，本包识读功能不可用！先跑 ./fetch-asr-deps.sh"
fi
$BT/zipalign -f -p 4 $B/unsigned.apk $B/aligned.apk

echo "[6/6] sign (debug key)"
KS=$HOME/workspace/tools/debug.keystore
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10950 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
$BT/apksigner sign --ks "$KS" --ks-key-alias androiddebugkey \
  --ks-pass pass:android --key-pass pass:android \
  --out $B/songdu-player.apk $B/aligned.apk

cp $B/songdu-player.apk $HOME/workspace/your_files/songdu-player.apk
echo "DONE: $B/songdu-player.apk"
