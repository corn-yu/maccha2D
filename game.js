/* =====================================================================
   game.js ── maccha2D のお手本（ステップ1・2 ＋ カメラ追従）
   1. 主人公を表示する
   2. 左右に動かす（PC：← → / A D キー、スマホ：画面の左右をタッチ）
   3. カメラが主人公を追いかける（ステージは画面より広い）
   ※ PCのマウスでは動かない（スマホのタッチだけ反応する）
   ===================================================================== */

// 設定
const CONFIG = {
  title:      "maccha2D",
  tagline:    "2Dアクションゲーム",
  howTo:      "← → キー（スマホは画面の左右をタッチ）で動かします。",
  timeLimit:  null,               // 時間制限なし
  storageKey: "maccha2d-best",    // ベストスコアの保存名
};

const WORLD_WIDTH = 3000;   // ステージ全体の横幅（画面より広くする）

const game = {
  // 最初に1回だけ呼ばれる
  onReady(ctx, shell) {
    this.ctx = ctx;
    this.shell = shell;
    this.playing = false;
    this.cameraX = 0;
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

    // タッチ操作：マウス（PC）は無視して、指（スマホ）だけ反応させる
    const canvas = document.getElementById("canvas");
    const onTouch = (e) => {
      if (e.pointerType === "mouse" || !this.playing) return;
      const rect = canvas.getBoundingClientRect();
      const isLeft = e.clientX - rect.left < rect.width / 2;   // 画面の左半分なら左へ
      this.touch.left = isLeft;
      this.touch.right = !isLeft;
    };
    const offTouch = (e) => {
      if (e.pointerType === "mouse") return;
      this.touch.left = this.touch.right = false;
    };
    canvas.addEventListener("pointerdown", onTouch);
    canvas.addEventListener("pointermove", onTouch);
    canvas.addEventListener("pointerup", offTouch);
    canvas.addEventListener("pointercancel", offTouch);
    canvas.addEventListener("pointerleave", offTouch);
  },

  // スタートを押すたびに呼ばれる（初期化）
  onStart() {
    this.playing = true;
    this.keys.left = this.keys.right = false;
    this.touch.left = this.touch.right = false;

    // 主人公：x は左端の位置、w/h は大きさ、speed は1秒に進む距離
    this.player = { x: 200, w: 40, h: 40, speed: 240, facing: 1 };
    this.cameraX = this.cameraTarget();   // 最初からカメラを主人公に合わせる
  },

  // カメラが映したい位置（主人公が画面の真ん中に来る。ステージの外は映さない）
  cameraTarget() {
    const p = this.player;
    const target = p.x + p.w / 2 - this.shell.width / 2;
    const max = Math.max(0, WORLD_WIDTH - this.shell.width);
    return Math.max(0, Math.min(max, target));
  },

  // 毎フレーム呼ばれる（動きの計算）
  onUpdate(dt) {
    const p = this.player;
    let dir = 0;
    if (this.keys.left || this.touch.left) dir -= 1;
    if (this.keys.right || this.touch.right) dir += 1;

    p.x += dir * p.speed * dt;                            // 位置を動かす
    p.x = Math.max(0, Math.min(WORLD_WIDTH - p.w, p.x));  // ステージの外に出ない
    if (dir !== 0) p.facing = dir;                        // 向いている方向

    // カメラをなめらかに主人公へ近づける
    this.cameraX += (this.cameraTarget() - this.cameraX) * Math.min(1, dt * 8);
  },

  // 毎フレーム呼ばれる（描画）
  onDraw(ctx, w, h) {
    const groundY = h * 0.8;   // 地面の高さ（画面の上から8割のところ）
    const p = this.player;
    const cam = this.cameraX;

    ctx.save();
    ctx.translate(-cam, 0);    // ここから先は「ステージの座標」で描く

    // 地面（見えている範囲だけ）
    ctx.fillStyle = "#8b6b4a";
    ctx.fillRect(cam, groundY, w, h - groundY);

    // 目印の柱（200pxごと）。カメラが動いているのが分かるようにする
    ctx.fillStyle = "#a9835b";
    const first = Math.floor(cam / 200) * 200;
    for (let x = first; x <= cam + w; x += 200) {
      ctx.fillRect(x, groundY - 70, 10, 70);
    }

    // ステージの左端と右端の壁
    ctx.fillStyle = "#4a3a2a";
    ctx.fillRect(-20, groundY - 200, 20, 200);
    ctx.fillRect(WORLD_WIDTH, groundY - 200, 20, 200);

    // 主人公（緑の四角）
    const y = groundY - p.h;
    ctx.fillStyle = "#6aa84f";
    ctx.fillRect(p.x, y, p.w, p.h);

    // 目（向いている方向に寄せる）
    ctx.fillStyle = "#1c2433";
    const eyeX = p.facing > 0 ? p.x + p.w - 14 : p.x + 6;
    ctx.fillRect(eyeX, y + 10, 8, 8);

    ctx.restore();
  },

  // shell からの入力（今回は使わない。タッチは onReady で自分で受け取っている）
  onPointer(type, x, y) {},

  // 終了時に呼ばれる
  onEnd() {
    this.playing = false;
  },
};
