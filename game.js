/* =====================================================================
   game.js ── maccha2D のお手本（ver 3）
   ・左右に動く（PC：← → / A D キー）
   ・ジャンプ（PC：スペース / ↑ / W キー）
   ・カメラが主人公を追いかける（ステージは画面より広い）
   ・スマホ：画面の下の左右をタッチで移動、画面の上をタッチでジャンプ
   ・PCのマウスでは動かない
   ===================================================================== */

// 設定
const CONFIG = {
  title:      "maccha2D",
  tagline:    "2Dアクションゲーム（ver 3）",   // ← ページが新しくなったか確認する目印。不要なら消してOK
  howTo:      "← → キーで移動、スペースキーでジャンプ。スマホは画面の下の左右で移動、上をタッチでジャンプ。",
  timeLimit:  null,               // 時間制限なし
  storageKey: "maccha2d-best",    // ベストスコアの保存名
};

const WORLD_WIDTH = 3000;   // ステージ全体の横幅（画面より広くする）
const GRAVITY     = 1800;   // 重力（大きいほど早く落ちる）
const JUMP_SPEED  = 640;    // ジャンプの強さ（大きいほど高く跳ぶ）

const game = {
  // 最初に1回だけ呼ばれる
  onReady(ctx, shell) {
    this.ctx = ctx;
    this.shell = shell;
    this.playing = false;
    this.startedAt = 0;
    this.cameraX = 0;
    this.jumpBuffer = 0;                                       // ジャンプの先行入力（着地の少し前に押しても跳べる）
    this.keys = { left: false, right: false, jump: false };    // キーボードの状態
    this.touch = { left: false, right: false, jump: false };   // タッチの状態
    this.pointers = new Map();                                 // 画面に触れている指

    // スコアとベストの表示を消す（今のゲームでは使わないため）
    document.getElementById("hud").style.display = "none";

    // キーボード
    const setKey = (e, isDown) => {
      const k = e.key;
      const isLeft  = k === "ArrowLeft" || k === "a" || k === "A";
      const isRight = k === "ArrowRight" || k === "d" || k === "D";
      const isJump  = k === " " || k === "ArrowUp" || k === "w" || k === "W";
      if (!isLeft && !isRight && !isJump) return;
      if (this.playing) e.preventDefault();                    // 画面がスクロールしないように
      if (isLeft)  this.keys.left = isDown;
      if (isRight) this.keys.right = isDown;
      if (isJump) {
        this.keys.jump = isDown;
        // スタートを押した瞬間のキーは、ジャンプに数えない
        if (isDown && !e.repeat && performance.now() - this.startedAt > 100) this.jumpBuffer = 0.12;
      }
    };
    window.addEventListener("keydown", (e) => setKey(e, true));
    window.addEventListener("keyup", (e) => setKey(e, false));
    window.addEventListener("blur", () => {                    // 別の画面に移ったらキーを離した扱いにする
      this.keys.left = this.keys.right = this.keys.jump = false;
    });

    // タッチ：マウス（PC）は無視して、指（スマホ）だけ反応させる
    const canvas = document.getElementById("canvas");
    const refreshTouch = () => {
      const r = canvas.getBoundingClientRect();
      let left = false, right = false, jump = false;
      for (const pt of this.pointers.values()) {
        if (pt.y < r.height * 0.5) jump = true;                // 画面の上半分：ジャンプ
        else if (pt.x < r.width / 2) left = true;              // 下半分の左：左へ
        else right = true;                                     // 下半分の右：右へ
      }
      this.touch.left = left; this.touch.right = right; this.touch.jump = jump;
    };
    const touchDown = (e) => {
      if (e.pointerType === "mouse" || !this.playing) return;
      const r = canvas.getBoundingClientRect();
      const pt = { x: e.clientX - r.left, y: e.clientY - r.top };
      if (e.type === "pointerdown" && pt.y < r.height * 0.5) this.jumpBuffer = 0.12;
      this.pointers.set(e.pointerId, pt);
      refreshTouch();
    };
    const touchUp = (e) => {
      if (e.pointerType === "mouse") return;
      this.pointers.delete(e.pointerId);
      refreshTouch();
    };
    canvas.addEventListener("pointerdown", touchDown);
    canvas.addEventListener("pointermove", touchDown);
    canvas.addEventListener("pointerup", touchUp);
    canvas.addEventListener("pointercancel", touchUp);
  },

  // スタートを押すたびに呼ばれる（初期化）
  onStart() {
    this.playing = true;
    this.startedAt = performance.now();
    this.jumpBuffer = 0;
    this.keys.left = this.keys.right = this.keys.jump = false;
    this.pointers.clear();
    this.touch.left = this.touch.right = this.touch.jump = false;

    // 主人公：x は左端の位置、z は地面からの高さ、vz は上向きの速さ
    this.player = { x: 200, z: 0, vz: 0, w: 40, h: 40, speed: 240, facing: 1 };
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

    // 左右の移動
    let dir = 0;
    if (this.keys.left || this.touch.left) dir -= 1;
    if (this.keys.right || this.touch.right) dir += 1;
    p.x += dir * p.speed * dt;
    p.x = Math.max(0, Math.min(WORLD_WIDTH - p.w, p.x));  // ステージの外に出ない
    if (dir !== 0) p.facing = dir;

    // ジャンプ：地面にいるときにボタンが押されたら跳ぶ
    if (this.jumpBuffer > 0) this.jumpBuffer -= dt;
    if (p.z === 0 && this.jumpBuffer > 0) {
      p.vz = JUMP_SPEED;
      this.jumpBuffer = 0;
    }

    // 重力：ボタンを早く離すと低いジャンプになる
    const jumpHeld = this.keys.jump || this.touch.jump;
    const g = (p.vz > 0 && !jumpHeld) ? GRAVITY * 2.5 : GRAVITY;
    p.vz -= g * dt;
    p.z += p.vz * dt;
    if (p.z <= 0) { p.z = 0; p.vz = 0; }                  // 地面に着いたら止まる

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

    // 影（高く跳ぶほど小さく薄くなる）
    const shrink = Math.max(0.3, 1 - p.z / 300);
    ctx.fillStyle = "rgba(0, 0, 0, 0.25)";
    ctx.beginPath();
    ctx.ellipse(p.x + p.w / 2, groundY + 3, (p.w / 2) * shrink, 5 * shrink, 0, 0, Math.PI * 2);
    ctx.fill();

    // 主人公（緑の四角）。z の分だけ上に持ち上げる
    const y = groundY - p.h - p.z;
    ctx.fillStyle = "#6aa84f";
    ctx.fillRect(p.x, y, p.w, p.h);

    // 目（向いている方向に寄せる）
    ctx.fillStyle = "#1c2433";
    const eyeX = p.facing > 0 ? p.x + p.w - 14 : p.x + 6;
    ctx.fillRect(eyeX, y + 10, 8, 8);

    ctx.restore();
  },

  // shell からの入力（今回は使わない。入力は onReady で自分で受け取っている）
  onPointer(type, x, y) {},

  // 終了時に呼ばれる
  onEnd() {
    this.playing = false;
  },
};
