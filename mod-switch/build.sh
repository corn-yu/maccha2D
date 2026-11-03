#!/bin/bash
# MODスイッチ をビルドする（Gradle なし。Java 25 の javac を使う）
# 使い方: ./build.sh <ライブラリのフォルダ>
#   ライブラリのフォルダには mc.jar・loader.jar・fapi.jar・extra/*.jar を入れておく（こえまほう と同じ）
set -e
LIB="$(cd "$1" && pwd)"
HERE="$(cd "$(dirname "$0")" && pwd)"
JAVAC="${JAVAC:-javac}"
WORK="$(mktemp -d)"
VERSION=$(grep -o '"version": *"[^"]*"' "$HERE/resources/fabric.mod.json" | cut -d'"' -f4)

# Fabric API の中から、つかう部品の jar をとりだす
mkdir -p "$WORK/fapi"
(cd "$WORK/fapi" && unzip -q -o "$LIB/fapi.jar" 'META-INF/jars/fabric-screen-api-v1*' 'META-INF/jars/fabric-api-base*')
CP="$LIB/mc.jar:$LIB/loader.jar"
for jar in "$WORK"/fapi/META-INF/jars/*.jar; do CP="$CP:$jar"; done
for jar in "$LIB"/extra/*.jar; do [ -f "$jar" ] && CP="$CP:$jar"; done

# コンパイルして jar にまとめる
mkdir -p "$WORK/classes"
"$JAVAC" --release 25 -encoding UTF-8 -proc:none -cp "$CP" -d "$WORK/classes" $(find "$HERE/src" -name '*.java')
cp -r "$HERE/resources/." "$WORK/classes/"
mkdir -p "$HERE/dist"
OUT="$HERE/dist/modswitch-$VERSION.jar"
rm -f "$OUT"
(cd "$WORK/classes" && jar --create --file "$OUT" .)
rm -rf "$WORK"
echo "できた: $OUT"
