# こえマイクラ（koe-minecraft）

声だけで操作するマインクラフトの Fabric MOD（Minecraft Java 26.3）。
マイクの音をパソコンの中の Vosk（日本語モデル vosk-model-small-ja-0.22）で文字にして、
言葉をキー操作（歩く・掘る・置く・ジャンプなど）に変える。クライアントだけで動くので、Essential でのマルチでも各自が入れれば使える。

- `src/com/maccha/koemine/KoeMineMod.java` … 毎tick アクションをキー操作にする
- `src/com/maccha/koemine/VoiceListener.java` … マイク → Vosk → 言葉さがし（別スレッド）
- `src/com/maccha/koemine/CommandTable.java` … 言葉とアクションの表（config/koemine/ことば.txt）
- `build.sh` … Gradle なしでビルドする（ライブラリの集め方はファイルの先頭に書いてある）
- `使い方.txt` … 遊ぶ人向けの説明
