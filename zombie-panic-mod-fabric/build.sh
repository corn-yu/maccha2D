#!/bin/bash
# ゾンビパニック（26.3・Fabric）をビルドする（Gradle なし。Java 25 の javac を使う）
# 使い方: ./build.sh <ライブラリのフォルダ>  （mc.jar・loader.jar・fapi.jar・extra/*.jar が入っている）
set -e
LIB="$(cd "$1" && pwd)"
HERE="$(cd "$(dirname "$0")" && pwd)"
JAVAC="${JAVAC:-javac}"
WORK="$(mktemp -d)"
VERSION=$(grep -o '"version": *"[^"]*"' "$HERE/resources/fabric.mod.json" | cut -d'"' -f4)

# Fabric API の中から部品の jar をとりだす
mkdir -p "$WORK/fapi"
(cd "$WORK/fapi" && unzip -q -o "$LIB/fapi.jar" 'META-INF/jars/*.jar')
CP="$LIB/mc.jar:$LIB/loader.jar"
for jar in "$WORK"/fapi/META-INF/jars/*.jar; do CP="$CP:$jar"; done
for jar in "$LIB"/extra/*.jar; do [ -f "$jar" ] && CP="$CP:$jar"; done

# コンパイルして jar にまとめる
mkdir -p "$WORK/classes"
"$JAVAC" --release 25 -encoding UTF-8 -proc:none -cp "$CP" -d "$WORK/classes" $(find "$HERE/src" -name '*.java')
cp -r "$HERE/resources/." "$WORK/classes/"
mkdir -p "$HERE/dist"
OUT="$HERE/dist/zombiepanic-$VERSION-fabric-26.3.jar"
rm -f "$OUT"
(cd "$WORK/classes" && jar --create --file "$OUT" .)
rm -rf "$WORK"
echo "できた: $OUT"
