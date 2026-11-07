#!/bin/bash
# いきおい剣をビルドする: bash build.sh <NeoForge 1.21.1 の jar を集めたフォルダ>
# 必要な jar: neoforge-21.1.x-client.jar（先に置く）・client-1.21.1-*-srg.jar・neoforge-21.1.x-universal.jar・
#            loader・bus・brigadier・datafixerupper・netty・joml・lwjgl・guava・gson など
set -e
cd "$(dirname "$0")"
LIB_DIR="$1"
if [ -z "$LIB_DIR" ]; then
  echo "使い方: bash build.sh <libフォルダ>"
  exit 1
fi

# クラスパス（パッチ済みのクラスを先に読む）
CLASS_PATH=$(ls "$LIB_DIR"/neoforge-*-client.jar)
for jar in "$LIB_DIR"/*.jar; do
  CLASS_PATH="$CLASS_PATH:$jar"
done

# コンパイル（Java 21 向け）
rm -rf build
mkdir -p build/classes dist
javac --release 21 -encoding UTF-8 -nowarn -cp "$CLASS_PATH" -d build/classes $(find src -name "*.java")

# jar にまとめる
cp -r resources/* build/classes/
VERSION=$(grep -m1 '^version' resources/META-INF/neoforge.mods.toml | sed 's/.*"\(.*\)"/\1/')
(cd build/classes && jar cf "../../dist/ikioiken-$VERSION-neoforge-1.21.1.jar" .)
echo "できた: dist/ikioiken-$VERSION-neoforge-1.21.1.jar"
