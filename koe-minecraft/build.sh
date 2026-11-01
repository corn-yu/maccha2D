#!/bin/bash
# こえマイクラ MOD をビルドする（Gradle なし。Java 25 の javac を使う）
# 使い方: ./build.sh <ライブラリのフォルダ>
#   ライブラリのフォルダには、次のファイルを入れておく（どれもリポジトリには入れない）
#   mc.jar          … .minecraft/versions/26.3/26.3.jar
#   loader.jar      … .minecraft/libraries/net/fabricmc/fabric-loader/0.20.1/fabric-loader-0.20.1.jar
#   fapi.jar        … mods に入っている fabric-api-0.165.0+26.3.jar
#   jna.jar         … jna-5.17.0.jar（Maven Central）
#   vosk.jar        … vosk-0.3.45.jar（Maven Central、MOD の中に入れる）
set -e
LIB="$(cd "$1" && pwd)"
HERE="$(cd "$(dirname "$0")" && pwd)"
JAVAC="${JAVAC:-javac}"
WORK="$(mktemp -d)"
VERSION=$(grep -o '"version": *"[^"]*"' "$HERE/resources/fabric.mod.json" | cut -d'"' -f4)

# Fabric API の中から、つかう部品の jar をとりだす
mkdir -p "$WORK/fapi"
(cd "$WORK/fapi" && unzip -q -o "$LIB/fapi.jar" 'META-INF/jars/fabric-lifecycle-events-v1*' 'META-INF/jars/fabric-api-base*')
CP="$LIB/mc.jar:$LIB/loader.jar:$LIB/jna.jar:$LIB/vosk.jar"
for jar in "$WORK"/fapi/META-INF/jars/*.jar; do CP="$CP:$jar"; done
# slf4j と gson（マイクラのライブラリ）
for jar in "$LIB"/extra/*.jar; do [ -f "$jar" ] && CP="$CP:$jar"; done

# コンパイルする
mkdir -p "$WORK/classes"
"$JAVAC" --release 25 -encoding UTF-8 -proc:none -cp "$CP" -d "$WORK/classes" $(find "$HERE/src" -name '*.java')

# vosk（日本語を文字にする部分。Windows・Mac・Linux 用が入っている）を MOD の中に入れる
(cd "$WORK/classes" && unzip -q -o "$LIB/vosk.jar" -x 'META-INF/*')
cp -r "$HERE/resources/." "$WORK/classes/"

# jar にまとめる
mkdir -p "$HERE/dist"
OUT="$HERE/dist/koemine-$VERSION.jar"
rm -f "$OUT"
(cd "$WORK/classes" && jar --create --file "$OUT" .)
rm -rf "$WORK"
echo "できた: $OUT"
