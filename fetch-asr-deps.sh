#!/bin/bash
# v1.16.0 识读原生库获取：sherpa-onnx v2.0.0 官方 Android 包里取两个 .so
# （libsherpa-onnx-jni.so + libonnxruntime.so，仅 arm64-v8a），放到 asr-libs/。
# build-manual.sh 构建时会把它们打进 APK 的 lib/arm64-v8a/。
# 这两个 .so 约 27MB，不入 git（.gitignore 已忽略 asr-libs/）；换机/换环境重跑本脚本即可。
set -e
cd "$(dirname "$0")"

VER=v2.0.0
PKG="sherpa-onnx-${VER}-android"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/${VER}/${PKG}.tar.bz2"
DEST=asr-libs/arm64-v8a
mkdir -p "$DEST" /tmp/shixi-asr-deps

JNI=libsherpa-onnx-jni.so
ORT=libonnxruntime.so
# 官方 v2.0.0 包实测大小＋md5（2026-10-24 从 release 包解出后核对），用于完整性校验
JNI_SIZE=4555952
ORT_SIZE=22249560
JNI_MD5=be2e6e2d1e3a36d9e8e68f17121ace08
ORT_MD5=87880a7261441057c4f9059ece093feb

if [ -f "$DEST/$JNI" ] && [ -f "$DEST/$ORT" ] \
   && [ "$(stat -c%s "$DEST/$JNI")" = "$JNI_SIZE" ] \
   && [ "$(stat -c%s "$DEST/$ORT")" = "$ORT_SIZE" ] \
   && [ "$(md5sum "$DEST/$JNI" | cut -d' ' -f1)" = "$JNI_MD5" ] \
   && [ "$(md5sum "$DEST/$ORT" | cut -d' ' -f1)" = "$ORT_MD5" ]; then
  echo "asr-libs 已就绪（大小＋md5 校验通过），跳过下载"
  exit 0
fi

echo "下载 $URL"
curl -sL --fail -o /tmp/shixi-asr-deps/pkg.tar.bz2 "$URL"
# 包内结构为 ./jniLibs/<abi>/*.so（无包名顶层目录）
tar xjf /tmp/shixi-asr-deps/pkg.tar.bz2 -C /tmp/shixi-asr-deps \
  "./jniLibs/arm64-v8a/${JNI}" "./jniLibs/arm64-v8a/${ORT}"
cp "/tmp/shixi-asr-deps/jniLibs/arm64-v8a/${JNI}" "$DEST/$JNI"
cp "/tmp/shixi-asr-deps/jniLibs/arm64-v8a/${ORT}" "$DEST/$ORT"

for f in "$JNI:$JNI_SIZE:$JNI_MD5" "$ORT:$ORT_SIZE:$ORT_MD5"; do
  name="$(echo "$f" | cut -d: -f1)"
  want="$(echo "$f" | cut -d: -f2)"
  wantmd5="$(echo "$f" | cut -d: -f3)"
  got="$(stat -c%s "$DEST/$name")"
  gotmd5="$(md5sum "$DEST/$name" | cut -d' ' -f1)"
  if [ "$got" != "$want" ] || [ "$gotmd5" != "$wantmd5" ]; then
    echo "校验不对：$name size=$got/$want md5=$gotmd5/$wantmd5" >&2
    exit 1
  fi
done
echo "DONE: $DEST/$JNI ($(stat -c%s "$DEST/$JNI")) + $DEST/$ORT ($(stat -c%s "$DEST/$ORT"))"
