/* =====================================================================
   game.js ── ゲームの中身はこのファイルだけに書きます
   （index.html / style.css / shell.js は触らなくてOK）
   ===================================================================== */

// 設定
const CONFIG = {
  title:      "ゲームのタイトル",
  tagline:    "ひとことで説明する文章",
  howTo:      "あそびかたをここに書く。",
  timeLimit:  30,              // 制限時間（秒）。時間制限なしなら null
  storageKey: "my-game-best",  // ベストスコアの保存名（ゲームごとに変える）
};

/* shell（枠）が用意している機能
     shell.addScore(n)    スコアを加算
     shell.setScore(n)    スコアを直接セット
     shell.end(msg)       ゲームを終了して結果画面を出す（msg は省略可）
     shell.width/height   描画エリアのサイズ
*/
const game = {
  // 最初に1回だけ呼ばれる（画像の読み込みなど）
  onReady(ctx, shell) {
    this.ctx = ctx;
    this.shell = shell;
  },

  // スタートを押すたびに呼ばれる（状態の初期化）
  onStart() {
  },

  // 毎フレーム呼ばれる。dt は前回からの経過秒数（動きや当たり判定）
  onUpdate(dt) {
  },

  // 毎フレーム呼ばれる（描画）
  onDraw(ctx, w, h) {
  },

  // 画面を押す・動かす・離すたびに呼ばれる。type は "down" | "move" | "up"
  onPointer(type, x, y) {
  },

  // キーが押されたときに呼ばれる（PC用）
  onKey(e) {
  },

  // 終了時に呼ばれる（任意）
  onEnd() {
  },
};
