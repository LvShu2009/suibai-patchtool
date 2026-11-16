#!/bin/bash
# 酸败补丁助手 —— 命令行构建
#
# 依赖（全部用环境变量提供，不写死任何本机绝对路径）：
#   ANDROID_TOOLS  工具根目录，需包含：
#                  android-34/android.jar
#                  sysroot/usr/lib/android-sdk/build-tools/debian/{aapt,zipalign}
#                  r8.jar、apksigner.jar、apksig.jar
#                  api/classes.jar、prov/classes.jar、aidl/classes.jar、shared/classes.jar（Shizuku）
#   JAVA_HOME      可选，JDK 17+；不设则用 PATH 里的 java/javac
#   KS_FILE        可选，签名密钥库，默认 ./keystore.jks
#   KS_PASS        密钥库口令（必填，不落盘）
#   KEY_PASS       密钥口令（必填，不落盘）
#   ANDROID_TOOLS_LD 可选，aapt 运行需要的 LD_LIBRARY_PATH
set -e

PROJ="$(cd "$(dirname "$0")" && pwd)"
OUT="$PROJ/build"
TOOLS="${ANDROID_TOOLS:?请先设置 ANDROID_TOOLS，指向包含 android.jar、build-tools、r8 与 Shizuku jars 的目录}"
AJAR="$TOOLS/android-34/android.jar"
SYS="$TOOLS/sysroot"
AAPT="${AAPT_BIN:-$SYS/usr/lib/android-sdk/build-tools/debian/aapt}"
ZIPALIGN="${ZIPALIGN_BIN:-$SYS/usr/lib/android-sdk/build-tools/debian/zipalign}"
[ -n "${JAVA_HOME:-}" ] && export PATH="$JAVA_HOME/bin:$PATH"
[ -n "${ANDROID_TOOLS_LD:-}" ] && export LD_LIBRARY_PATH="$ANDROID_TOOLS_LD"

LIBJARS="$TOOLS/api/classes.jar:$TOOLS/prov/classes.jar:$TOOLS/aidl/classes.jar:$TOOLS/shared/classes.jar"
PROGRAM_JARS="$TOOLS/api/classes.jar $TOOLS/prov/classes.jar $TOOLS/aidl/classes.jar $TOOLS/shared/classes.jar"

for f in "$AJAR" "$AAPT" "$ZIPALIGN" "$TOOLS/r8.jar" "$TOOLS/apksigner.jar" \
         "$TOOLS/api/classes.jar" "$TOOLS/prov/classes.jar" "$TOOLS/aidl/classes.jar" "$TOOLS/shared/classes.jar"; do
    if [ ! -f "$f" ]; then
        echo "✗ 缺少构建依赖: $f" >&2
        echo "  按脚本头部注释准备 ANDROID_TOOLS；Shizuku 的四个 classes.jar 必须齐全。" >&2
        exit 1
    fi
done

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

echo "== 1/6 javac =="
javac -source 8 -target 8 -nowarn -Xlint:-options \
    -bootclasspath "$AJAR" \
    -cp "$LIBJARS" \
    -d "$OUT/classes" $(find "$PROJ/src" -name '*.java')

echo "== 2/6 d8 =="
java -cp "$TOOLS/r8.jar" com.android.tools.r8.D8 \
    --release --min-api 24 --lib "$AJAR" \
    --output "$OUT/dex" \
    $PROGRAM_JARS \
    $(find "$OUT/classes" -name '*.class')

echo "== 3/6 aapt package =="
"$AAPT" package -f \
    -M "$PROJ/AndroidManifest.xml" \
    -S "$PROJ/res" \
    -I "$AJAR" \
    --min-sdk-version 24 --target-sdk-version 33 \
    -F "$OUT/base.apk"

echo "== 4/6 塞入 classes.dex =="
( cd "$OUT/dex" && "$AAPT" add "$OUT/base.apk" classes.dex )

echo "== 5/6 zipalign =="
"$ZIPALIGN" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "== 6/6 签名 =="
KS="${KS_FILE:-$PROJ/keystore.jks}"
if [ ! -f "$KS" ]; then
    echo "✗ 找不到签名密钥库: $KS" >&2
    echo "  请自己生成并离线保存，不要提交到仓库：" >&2
    echo "  keytool -genkeypair -keystore keystore.jks -alias lvshu -keyalg RSA -keysize 2048 -validity 10950" >&2
    exit 1
fi
: "${KS_PASS:?请通过环境变量提供密钥库口令 KS_PASS}"
: "${KEY_PASS:?请通过环境变量提供密钥口令 KEY_PASS}"
java -cp "$TOOLS/apksigner.jar:$TOOLS/apksig.jar" \
    com.android.apksigner.ApkSignerTool sign \
    --ks "$KS" --ks-pass env:KS_PASS --key-pass env:KEY_PASS \
    --min-sdk-version 24 \
    --out "$OUT/酸败补丁助手.apk" "$OUT/aligned.apk"

echo "== 完成 =="
ls -l "$OUT/酸败补丁助手.apk"
java -cp "$TOOLS/apksigner.jar:$TOOLS/apksig.jar" \
    com.android.apksigner.ApkSignerTool verify --print-certs "$OUT/酸败补丁助手.apk" | head -8
