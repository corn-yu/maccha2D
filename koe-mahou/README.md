# こえまほう（koe-mahou）

声の詠唱で魔法を出す Fabric MOD（Minecraft Java 26.3）。音声認識は こえマイクラ（../koe-minecraft）と同じ Vosk 日本語モデル。
クライアントで詠唱を1行ずつ聞きとり、行が進むたびにサーバーへ `koemahou:cast`（spell, step, total）を送る。サーバーが光・音・魔法の効果を出すので、マルチでは友だちにも見える。

- `src/com/maccha/koemahou/KoeMahouClient.java` … 詠唱の進み具合（順番・6秒でとぎれる・まちがい）
- `src/com/maccha/koemahou/VoiceListener.java` … マイク → Vosk（文法は詠唱の行だけ）→ 行さがし
- `src/com/maccha/koemahou/SpellBook.java` … じゅもん表（config/koemahou/じゅもん.txt）
- `src/com/maccha/koemahou/SpellEffects.java` … サーバー側の魔法（ファイアボール・ヒール・ワープ）と待ち時間
- `src/com/maccha/koemahou/KoeMahouMod.java` / `CastPayload.java` … お知らせの登録
- `build.sh` … Gradle なしでビルド（ライブラリは ../koe-minecraft/build.sh と同じものを使う）
