/* =====================================================================
   game.js ── maccha2D（ver 4）
   ・左右に動く（PC：← → / A D キー）
   ・ジャンプ（PC：スペース / ↑ / W キー）
   ・スマホ：画面の下の左右をタッチで移動、画面の上をタッチでジャンプ
   ・ティーカップに入るとゴール → 次のステージへ（全3ステージ）
   ・カメラが主人公を追いかける（横も縦も）
   ===================================================================== */

// 設定
const CONFIG = {
  title:      "maccha2D",
  tagline:    "2Dアクションゲーム（ver 4）",   // ← ページが新しくなったか確認する目印。不要なら消してOK
  howTo:      "ティーカップに入ればゴール！ ← → キーで移動、スペースキーでジャンプ。スマホは画面の下の左右で移動、上をタッチでジャンプ。",
  timeLimit:  null,               // 時間制限なし
  storageKey: "maccha2d-best",    // ベストスコアの保存名
};

const GRAVITY    = 1800;   // 重力（大きいほど早く落ちる）
const JUMP_SPEED = 640;    // ジャンプの強さ（大きいほど高く跳ぶ）
const ENTER_TIME = 0.5;    // カップに入る動きにかかる秒数

/* ---------------------------------------------------------------------
   ステージのデータ（ここを書き換えると、ステージを作り変えられます）
     width      ステージの横幅
     platforms  足場。x=左端 / w=幅 / top=地面から足場の上面までの高さ
     cup        ゴールのティーカップ。x=中心の位置 / base=置いてある高さ（0なら地面）
     sky 空の色 / ground 地面の色 / pillar 柱の色 / plat 足場の色 / platTop 足場の上の色 / text 文字の色
   ※ 主人公は最大で約110pxまで跳べます。足場の段差は80pxくらいまでにすると登れます。
   --------------------------------------------------------------------- */
const STAGES = [
  { // ステージ1：やさしい。カップは地面にある
    name: "ステージ 1", width: 2400,
    sky: "#cfe8f7", ground: "#8b6b4a", pillar: "#a9835b", plat: "#c49a63", platTop: "#7fb069", text: "#1c2433",
    platforms: [
      { x: 700,  w: 180, top: 70 },
      { x: 960,  w: 200, top: 70 },
      { x: 1240, w: 160, top: 70 },
    ],
    cup: { x: 2100, base: 0 },
  },
  { // ステージ2：足場を登った先にカップがある
    name: "ステージ 2", width: 2400,
    sky: "#f7dfc4", ground: "#7a5a3a", pillar: "#9c774f", plat: "#b98a57", platTop: "#d99a4e", text: "#3a2412",
    platforms: [
      { x: 800,  w: 160, top: 70 },
      { x: 1060, w: 160, top: 140 },
      { x: 1320, w: 140, top: 70 },
      { x: 1560, w: 160, top: 140 },
      { x: 1820, w: 260, top: 210 },
    ],
    cup: { x: 1960, base: 210 },
  },
  { // ステージ3：高い足場をいくつも渡る
    name: "ステージ 3", width: 2700,
    sky: "#26335f", ground: "#3f3550", pillar: "#54497a", plat: "#6b5a94", platTop: "#9d8ad0", text: "#f2f0ff",
    platforms: [
      { x: 700,  w: 150, top: 70 },
      { x: 930,  w: 150, top: 140 },
      { x: 1160, w: 150, top: 210 },
      { x: 1390, w: 150, top: 140 },
      { x: 1620, w: 150, top: 210 },
      { x: 1850, w: 150, top: 280 },
      { x: 2080, w: 260, top: 280 },
    ],
    cup: { x: 2210, base: 280 },
  },
];

const game = {
  // 最初に1回だけ呼ばれる
  onReady(ctx, shell) {
    this.ctx = ctx;
    this.shell = shell;
    this.playing = false;
    this.startedAt = 0;
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
    this.keys.left = this.keys.right = this.keys.jump = false;
    this.pointers.clear();
    this.touch.left = this.touch.right = this.touch.jump = false;
    this.time = 0;
    this.loadStage(0);
  },

  // ステージを読み込む
  loadStage(index) {
    this.stageIndex = index;
    this.stage = STAGES[index];
    // 主人公：x は左端の位置、z は地面からの高さ、vz は上向きの速さ
    this.player = { x: 120, z: 0, vz: 0, w: 40, h: 40, speed: 240, facing: 1, grounded: true };
    this.jumpBuffer = 0;
    this.entering = null;                      // カップに入っている最中の情報
    this.banner = { t: 0 };                    // 「ステージ ○」の表示
    this.cameraX = this.cameraTargetX();       // 最初からカメラを主人公に合わせる
    this.cameraY = 0;
  },

  // カメラが映したい横位置（主人公が画面の真ん中に来る。ステージの外は映さない）
  cameraTargetX() {
    const p = this.player;
    const target = p.x + p.w / 2 - this.shell.width / 2;
    const max = Math.max(0, this.stage.width - this.shell.width);
    return Math.max(0, Math.min(max, target));
  },

  // カメラの縦のずれ（高い所に登ったとき、主人公が画面の上に隠れないように下へずらす）
  cameraTargetY() {
    const p = this.player;
    const groundY = this.shell.height * 0.8;
    return Math.max(0, 90 + p.z + p.h - groundY);
  },

  // 主人公の足元にある床の高さ（影を描くのに使う）
  floorUnder() {
    const p = this.player;
    let floor = 0;
    for (const pl of this.stage.platforms) {
      if (p.x + p.w > pl.x && p.x < pl.x + pl.w && pl.top <= p.z + 0.5 && pl.top > floor) floor = pl.top;
    }
    return floor;
  },

  // 毎フレーム呼ばれる（動きの計算）
  onUpdate(dt) {
    const p = this.player, stage = this.stage;
    this.time += dt;
    this.banner.t += dt;

    if (this.entering) {
      // カップに吸い込まれる動き
      const e = this.entering;
      e.t += dt;
      const k = Math.min(1, e.t / ENTER_TIME);
      p.x = e.fromX + (stage.cup.x - p.w / 2 - e.fromX) * k;
      p.z = e.fromZ + (stage.cup.base + 30 - e.fromZ) * k;
      if (e.t >= ENTER_TIME) this.goNext();
    } else {
      // 左右の移動
      let dir = 0;
      if (this.keys.left || this.touch.left) dir -= 1;
      if (this.keys.right || this.touch.right) dir += 1;
      p.x += dir * p.speed * dt;
      p.x = Math.max(0, Math.min(stage.width - p.w, p.x));   // ステージの外に出ない
      if (dir !== 0) p.facing = dir;

      // ジャンプ：どこかに立っているときにボタンが押されたら跳ぶ
      if (this.jumpBuffer > 0) this.jumpBuffer -= dt;
      if (p.grounded && this.jumpBuffer > 0) {
        p.vz = JUMP_SPEED;
        p.grounded = false;
        this.jumpBuffer = 0;
      }

      // 重力：ボタンを早く離すと低いジャンプになる
      const jumpHeld = this.keys.jump || this.touch.jump;
      const g = (p.vz > 0 && !jumpHeld) ? GRAVITY * 2.5 : GRAVITY;
      const prevZ = p.z;
      p.vz -= g * dt;
      p.z += p.vz * dt;

      // 着地の判定：落ちているとき、足場の上面をまたいだら、その上に乗る
      let grounded = false;
      if (p.vz <= 0) {
        let landTop = -1;
        for (const pl of stage.platforms) {
          const overlap = p.x + p.w > pl.x && p.x < pl.x + pl.w;
          if (overlap && prevZ >= pl.top - 0.5 && p.z <= pl.top && pl.top > landTop) landTop = pl.top;
        }
        if (landTop >= 0) { p.z = landTop; p.vz = 0; grounded = true; }
        else if (p.z <= 0) { p.z = 0; p.vz = 0; grounded = true; }
      }
      p.grounded = grounded;

      // ゴール判定：カップの真上あたりで、カップと同じ高さにいたら「入った」
      const cup = stage.cup;
      const cx = p.x + p.w / 2;
      if (Math.abs(cx - cup.x) <= 26 && p.z >= cup.base - 2 && p.z <= cup.base + 30) {
        this.entering = { t: 0, fromX: p.x, fromZ: p.z };
      }
    }

    // カメラをなめらかに主人公へ近づける
    const f = Math.min(1, dt * 8);
    this.cameraX += (this.cameraTargetX() - this.cameraX) * f;
    this.cameraY += (this.cameraTargetY() - this.cameraY) * f;
  },

  // ゴールしたあと：次のステージへ、最後ならクリア
  goNext() {
    if (this.stageIndex < STAGES.length - 1) {
      this.loadStage(this.stageIndex + 1);
    } else {
      this.entering = null;
      this.shell.setScore(STAGES.length);   // 結果画面のスコアには、クリアしたステージ数を表示
      this.shell.end("全ステージクリア！おめでとう！");
    }
  },

  // 毎フレーム呼ばれる（描画）
  onDraw(ctx, w, h) {
    const stage = this.stage;
    const groundY = h * 0.8;   // 地面の高さ（画面の上から8割のところ）
    const p = this.player;
    const cam = this.cameraX;

    // 空
    ctx.fillStyle = stage.sky;
    ctx.fillRect(0, 0, w, h);

    ctx.save();
    ctx.translate(-cam, this.cameraY);    // ここから先は「ステージの座標」で描く

    // 地面（見えている範囲だけ）
    ctx.fillStyle = stage.ground;
    ctx.fillRect(cam, groundY, w, 2000);

    // 目印の柱（200pxごと）。カメラが動いているのが分かるようにする
    ctx.fillStyle = stage.pillar;
    const first = Math.floor(cam / 200) * 200;
    for (let x = first; x <= cam + w; x += 200) {
      ctx.fillRect(x, groundY - 70, 10, 70);
    }

    // ステージの左端と右端の壁
    ctx.fillStyle = stage.pillar;
    ctx.fillRect(-20, groundY - 600, 20, 600);
    ctx.fillRect(stage.width, groundY - 600, 20, 600);

    // 足場
    for (const pl of stage.platforms) {
      if (pl.x + pl.w < cam || pl.x > cam + w) continue;
      const y = groundY - pl.top;
      ctx.fillStyle = stage.plat;
      ctx.fillRect(pl.x, y, pl.w, 20);
      ctx.fillStyle = stage.platTop;
      ctx.fillRect(pl.x, y, pl.w, 6);
    }

    // ゴールのティーカップ
    this.drawCup(ctx, stage.cup.x, groundY - stage.cup.base, stage);

    // 影（高く跳ぶほど小さく薄くなる）
    const floor = this.floorUnder();
    const shrink = Math.max(0.3, 1 - (p.z - floor) / 300);
    ctx.fillStyle = "rgba(0, 0, 0, 0.25)";
    ctx.beginPath();
    ctx.ellipse(p.x + p.w / 2, groundY - floor + 3, (p.w / 2) * shrink, 5 * shrink, 0, 0, Math.PI * 2);
    ctx.fill();

    // 主人公（緑の四角）。z の分だけ上に持ち上げる。カップに入るときは小さくなる
    const s = this.entering ? Math.max(0.05, 1 - this.entering.t / ENTER_TIME) : 1;
    const cxp = p.x + p.w / 2, cyp = groundY - p.z - p.h / 2;
    ctx.save();
    ctx.translate(cxp, cyp);
    ctx.scale(s, s);
    ctx.fillStyle = "#6aa84f";
    ctx.fillRect(-p.w / 2, -p.h / 2, p.w, p.h);
    ctx.fillStyle = "#1c2433";                                  // 目（向いている方向に寄せる）
    ctx.fillRect(p.facing > 0 ? p.w / 2 - 14 : -p.w / 2 + 6, -p.h / 2 + 10, 8, 8);
    ctx.restore();

    ctx.restore();

    // 画面に固定して出す文字
    ctx.fillStyle = stage.text;
    ctx.font = "700 18px 'Hiragino Sans', 'Yu Gothic', sans-serif";
    ctx.textAlign = "left";
    ctx.fillText(stage.name + " / " + STAGES.length, 16, 34);

    // ステージが始まったときの大きな表示
    if (this.banner.t < 1.6) {
      const a = this.banner.t < 1.2 ? 1 : Math.max(0, 1 - (this.banner.t - 1.2) / 0.4);
      ctx.globalAlpha = a;
      ctx.textAlign = "center";
      ctx.font = "700 44px 'Hiragino Sans', 'Yu Gothic', sans-serif";
      ctx.fillText(stage.name, w / 2, h * 0.3);
      ctx.globalAlpha = 1;
    }
  },

  // ティーカップを描く（cx=中心の x、baseY=置いてある面の y）
  drawCup(ctx, cx, baseY, stage) {
    // 受け皿
    ctx.fillStyle = "#e8e2d6";
    ctx.beginPath();
    ctx.ellipse(cx, baseY - 2, 40, 6, 0, 0, Math.PI * 2);
    ctx.fill();
    // 取っ手
    ctx.strokeStyle = "#b9b1a0";
    ctx.lineWidth = 6;
    ctx.beginPath();
    ctx.arc(cx + 30, baseY - 26, 11, -Math.PI / 2, Math.PI / 2);
    ctx.stroke();
    // 本体
    ctx.fillStyle = "#f4f1ea";
    ctx.strokeStyle = "#b9b1a0";
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.moveTo(cx - 30, baseY - 46);
    ctx.lineTo(cx + 30, baseY - 46);
    ctx.quadraticCurveTo(cx + 28, baseY - 4, cx, baseY - 4);
    ctx.quadraticCurveTo(cx - 28, baseY - 4, cx - 30, baseY - 46);
    ctx.closePath();
    ctx.fill();
    ctx.stroke();
    // お茶（抹茶色）
    ctx.fillStyle = "#7cb342";
    ctx.beginPath();
    ctx.ellipse(cx, baseY - 46, 30, 6, 0, 0, Math.PI * 2);
    ctx.fill();
    // 湯気
    ctx.strokeStyle = stage.text;
    ctx.globalAlpha = 0.45;
    ctx.lineWidth = 3;
    for (const off of [-10, 10]) {
      ctx.beginPath();
      for (let k = 0; k <= 12; k++) {
        const x = cx + off + Math.sin(this.time * 3 + k * 0.5 + off) * 3;
        const y = baseY - 54 - k * 2;
        if (k === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
      }
      ctx.stroke();
    }
    ctx.globalAlpha = 1;
  },

  // shell からの入力（今回は使わない。入力は onReady で自分で受け取っている）
  onPointer(type, x, y) {},

  // 終了時に呼ばれる
  onEnd() {
    this.playing = false;
  },
};
