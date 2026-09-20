/* =====================================================================
   game.js ── maccha2D のお手本（ステップ1・2）
   1. 主人公を表示する
   2. 左右に動かす（キーボード ← → / A D、スマホは画面の左右をタッチ）
   ===================================================================== */

// 設定
const CONFIG = {
  title:      "maccha2D",
  tagline:    "2Dアクションゲーム",
  howTo:      "← → キー（スマホは画面の左右をタッチ）で動かします。",
  timeLimit:  null,               // 時間制限なし
  storageKey: "maccha2d-best",    // ベストスコアの保存名
};

const game = {
  // 最初に1回だけ呼ばれる
  onReady(ctx, shell) {
    this.ctx = ctx;
    this.shell = shell;
    this.playing = false;
    this.keys = { left: false, right: false };   // キーボードの状態
    this.touch = { left: false, right: false };  // タッチの状態

    // キーを押している間、動き続けるようにする
    const setKey = (e, isDown) => {
      let handled = true;
      if (e.key === "ArrowLeft" || e.key === "a" || e.key === "A") this.keys.left = isDown;
      else if (e.key === "ArrowRight" || e.key === "d" || e.key === "D") this.keys.right = isDown;
      else handled = false;
      if (handled && this.playing) e.preventDefault();   // 画面がスクロールしないように
    };
    window.addEventListener("keydown", (e) => setKey(e, true));
    window.addEventListener("keyup", (e) => setKey(e, false));
  },

  // スタートを押すたびに呼ばれる（初期化）
  onStart() {
    this.playing = true;
    this.keys.left = this.keys.right = false;
    this.touch.left = this.touch.right = false;

    // 主人公：x は左端の位置、w/h は大きさ、speed は1秒に進む距離
    this.player = { x: this.shell.width / 2 - 20, w: 40, h: 40, speed: 240, facing: 1 };
  },

  // 毎フレーム呼ばれる（動きの計算）
  onUpdate(dt) {
    const p = this.player;
    let dir = 0;
    if (this.keys.left || this.touch.left) dir -= 1;
    if (this.keys.right || this.touch.right) dir += 1;

    p.x += dir * p.speed * dt;                                   // 位置を動かす
    p.x = Math.max(0, Math.min(this.shell.width - p.w, p.x));    // 画面の外に出ない
    if (dir !== 0) p.facing = dir;                               // 向いている方向
  },

  // 毎フレーム呼ばれる（描画）
  onDraw(ctx, w, h) {
    const groundY = h * 0.8;   // 地面の高さ（画面の上から8割のところ）
    const p = this.player;

    // 地面
    ctx.fillStyle = "#8b6b4a";
    ctx.fillRect(0, groundY, w, h - groundY);

    // 主人公（緑の四角）
    const y = groundY - p.h;
    ctx.fillStyle = "#6aa84f";
    ctx.fillRect(p.x, y, p.w, p.h);

    // 目（向いている方向に寄せる）
    ctx.fillStyle = "#1c2433";
    const eyeX = p.facing > 0 ? p.x + p.w - 14 : p.x + 6;
    ctx.fillRect(eyeX, y + 10, 8, 8);
  },

  // 画面を押す・動かす・離すたびに呼ばれる（スマホ用）
  onPointer(type, x, y) {
    if (type === "up") {
      this.touch.left = this.touch.right = false;
      return;
    }
    // 画面の左半分を押すと左へ、右半分を押すと右へ
    const isLeft = x < this.shell.width / 2;
    this.touch.left = isLeft;
    this.touch.right = !isLeft;
  },

  // 終了時に呼ばれる
  onEnd() {
    this.playing = false;
  },
};
