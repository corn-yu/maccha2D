#!/bin/bash
# いきおい剣（Fabric 26.3 版）をビルドする: bash build.sh <部品の jar を集めたフォルダ>
# フォルダに必要な物: mc.jar（26.3.jar）・fapi.jar（fabric-api-0.165.0+26.3.jar）・fabric-loader・sponge-mixin・
#   brigadier・datafixerupper・logging・joml・jspecify・fastutil・guava・gson・slf4j・lwjgl・log4j・netty・authlib・asm など
# Java 25 の javac を使う（マイクラ 26.x は難読化されていないので、本物の名前でそのまま書ける）
set -e
cd "$(dirname "$0")"
LIB_DIR="$(cd "$1" && pwd)"
if [ -z "$LIB_DIR" ]; then
  echo "使い方: bash build.sh <部品のフォルダ>"
  exit 1
fi
JAVAC="${JAVAC:-javac}"

# Fabric API の中から部品の jar をとりだす
rm -rf build
mkdir -p build/classes build/fapi dist
(cd build/fapi && unzip -q -o "$LIB_DIR/fapi.jar" 'META-INF/jars/*')

# クラスパス（マイクラ本体を先に）
CLASS_PATH="$LIB_DIR/mc.jar"
for jar in "$LIB_DIR"/*.jar; do
  case "$jar" in
    "$LIB_DIR/mc.jar"|"$LIB_DIR/fapi.jar") ;;
    *) CLASS_PATH="$CLASS_PATH:$jar" ;;
  esac
done
for jar in build/fapi/META-INF/jars/*.jar; do
  CLASS_PATH="$CLASS_PATH:$jar"
done

# コンパイル（Java 25 向け、mixin の注釈処理はしない）
"$JAVAC" --release 25 -proc:none -encoding UTF-8 -nowarn -cp "$CLASS_PATH" -d build/classes $(find src -name "*.java")

# jar にまとめる
cp -r resources/* build/classes/
VERSION=$(grep -o '"version": *"[^"]*"' resources/fabric.mod.json | cut -d'"' -f4)
(cd build/classes && jar --create --file "../../dist/ikioiken-$VERSION-fabric-26.3.jar" .)
echo "できた: dist/ikioiken-$VERSION-fabric-26.3.jar"
