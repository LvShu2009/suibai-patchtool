#!/bin/bash
# 酸败补丁助手 —— 纯命令行构建（便携 JDK + Debian 版 aapt + r8/d8）
set -e

export PATH=/root/build/tools/jdk-17.0.2/bin:$PATH
TOOLS=/root/build/tools
SYS=$TOOLS/sysroot
AAPT=$SYS/usr/lib/android-sdk/build-tools/debian/aapt
ZIPALIGN=$SYS/usr/lib/android-sdk/build-tools/debian/zipalign
AJAR=$TOOLS/android-34/android.jar
PROJ=/root/build/p342
OUT=$PROJ/build

export LD_LIBRARY_PATH=$SYS/usr/lib/aarch64-linux-gnu/android:$SYS/usr/lib/7zip:$SYS/usr/lib/aarch64-linux-gnu:$SYS/usr/lib

# Shizuku: api / provider / aidl / shared 四个 aar 的 classes.jar
LIBJARS="$TOOLS/api/classes.jar:$TOOLS/prov/classes.jar:$TOOLS/aidl/classes.jar:$TOOLS/shared/classes.jar"
PROGRAM_JARS="$TOOLS/api/classes.jar $TOOLS/prov/classes.jar $TOOLS/aidl/classes.jar $TOOLS/shared/classes.jar"

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
KS=$PROJ/keystore.jks
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -storepass lvshu123 -keypass lvshu123 \
        -alias lvshu -keyalg RSA -keysize 2048 -validity 10950 \
        -dname "CN=LvShu PatchTool, OU=patch, O=LvShu, L=CN, ST=CN, C=CN" >/dev/null 2>&1
fi
java -cp "$SYS/usr/share/java/apksigner.jar:$SYS/usr/share/java/apksig.jar" \
    com.android.apksigner.ApkSignerTool sign \
    --ks "$KS" --ks-pass pass:lvshu123 --key-pass pass:lvshu123 \
    --min-sdk-version 24 \
    --out "$OUT/酸败补丁助手.apk" "$OUT/aligned.apk"

echo "== 完成 =="
ls -l "$OUT/酸败补丁助手.apk"
java -cp "$SYS/usr/share/java/apksigner.jar:$SYS/usr/share/java/apksig.jar" \
    com.android.apksigner.ApkSignerTool verify --print-certs "$OUT/酸败补丁助手.apk" | head -8