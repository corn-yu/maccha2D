/* =====================================================================
   game.js ── maccha2D（ver 5）
   ・左右に動く（PC：← → / A D キー）
   ・ジャンプ（PC：スペース / ↑ / W キー）
   ・スマホ：画面の下の左右をタッチで移動、画面の上をタッチでジャンプ
   ・ティーカップに入るとゴール → 次のステージへ（全5ステージ）
   ・カメラが主人公を追いかける（横も縦も）
   ・コイン集め / 歩く敵（踏むと倒せる）/ 落とし穴 / ライフ3つ
   ===================================================================== */

// 設定
const CONFIG = {
  title:      "maccha2D",
  tagline:    "2Dアクションゲーム（ver 5）",   // ← ページが新しくなったか確認する目印。不要なら消してOK
  howTo:      "ティーカップに入ればゴール！ コインを集めて、敵は上から踏んでたおそう。落とし穴と横からの接触に注意（ライフ3つ）。← → キーで移動、スペースキーでジャンプ。スマホは画面の下の左右で移動、上をタッチでジャンプ。",
  timeLimit:  null,               // 時間制限なし
  storageKey: "maccha2d-best",    // ベストスコアの保存名
};

const GRAVITY    = 1800;   // 重力（大きいほど早く落ちる）
const JUMP_SPEED = 640;    // ジャンプの強さ（大きいほど高く跳ぶ）
const ENTER_TIME = 0.5;    // カップに入る動きにかかる秒数
const MAX_LIVES  = 3;      // ライフの数
const INVULN_TIME = 1.5;   // ミスしたあとの無敵の秒数
const COIN_POINT  = 100;   // コイン1枚の点数
const STOMP_POINT = 200;   // 敵を踏んだときの点数
const STAGE_POINT = 1000;  // ステージクリアの点数
const LIFE_POINT  = 500;   // 全クリア時、残りライフ1つあたりの点数

/* ---------------------------------------------------------------------
   ステージのデータ（ここを書き換えると、ステージを作り変えられます）
     width      ステージの横幅
     platforms  足場。x=左端 / w=幅 / top=地面から足場の上面までの高さ
   pits       落とし穴（地面の切れ目）。x=左端 / w=幅（100〜120pxまで。跳べる距離は約170px）
   coins      コイン。x=中心の位置 / z=地面からの高さ
   enemies    歩く敵。x=最初の位置 / z=立っている足場の高さ / min・max=歩く範囲（左右の端）
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
    pits: [],
    coins: [
      { x: 400, z: 40 }, { x: 460, z: 40 }, { x: 520, z: 40 },
      { x: 790, z: 110 }, { x: 1060, z: 110 }, { x: 1320, z: 110 },
      { x: 1700, z: 40 }, { x: 1760, z: 40 }, { x: 1820, z: 40 },
    ],
    enemies: [
      { x: 1550, z: 0, min: 1480, max: 1950 },
    ],
    cup: { x: 2100, base: 0 },
  },
  { // ステージ2：落とし穴と足場。登った先にカップがある
    name: "ステージ 2", width: 2400,
    sky: "#f7dfc4", ground: "#7a5a3a", pillar: "#9c774f", plat: "#b98a57", platTop: "#d99a4e", text: "#3a2412",
    platforms: [
      { x: 800,  w: 160, top: 70 },
      { x: 1060, w: 160, top: 140 },
      { x: 1320, w: 140, top: 70 },
      { x: 1560, w: 160, top: 140 },
      { x: 1820, w: 260, top: 210 },
    ],
    pits: [ { x: 560, w: 100 } ],
    coins: [
      { x: 300, z: 40 }, { x: 380, z: 40 },
      { x: 610, z: 100 },
      { x: 880, z: 110 }, { x: 1140, z: 180 }, { x: 1390, z: 110 }, { x: 1640, z: 180 }, { x: 1950, z: 250 },
    ],
    enemies: [
      { x: 350,  z: 0, min: 250,  max: 520 },
      { x: 1500, z: 0, min: 1480, max: 1780 },
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
    pits: [ { x: 400, w: 100 }, { x: 1450, w: 110 } ],
    coins: [
      { x: 250, z: 40 }, { x: 450, z: 100 },
      { x: 775, z: 110 }, { x: 1005, z: 180 }, { x: 1235, z: 250 }, { x: 1465, z: 180 },
      { x: 1695, z: 250 }, { x: 1925, z: 320 }, { x: 2150, z: 320 },
    ],
    enemies: [
      { x: 600, z: 0, min: 520, max: 690 },
    ],
    cup: { x: 2210, base: 280 },
  },
  { // ステージ4：落とし穴が続く夕暮れの道
    name: "ステージ 4", width: 3000,
    sky: "#f4c6cf", ground: "#6b4a4f", pillar: "#8c646b", plat: "#a8767d", platTop: "#e58ea0", text: "#3d1f27",
    platforms: [
      { x: 1600, w: 150, top: 70 },
      { x: 1830, w: 150, top: 140 },
      { x: 2060, w: 150, top: 70 },
      { x: 2300, w: 300, top: 140 },
    ],
    pits: [ { x: 500, w: 100 }, { x: 900, w: 100 }, { x: 1300, w: 110 } ],
    coins: [
      { x: 300, z: 40 }, { x: 550, z: 100 }, { x: 750, z: 40 },
      { x: 950, z: 100 }, { x: 1150, z: 40 }, { x: 1355, z: 100 },
      { x: 1675, z: 110 }, { x: 1905, z: 180 }, { x: 2135, z: 110 }, { x: 2400, z: 180 },
    ],
    enemies: [
      { x: 700,  z: 0,   min: 620,  max: 880 },
      { x: 1100, z: 0,   min: 1020, max: 1280 },
      { x: 1450, z: 0,   min: 1430, max: 1560 },
      { x: 2330, z: 140, min: 2310, max: 2380 },
    ],
    cup: { x: 2500, base: 140 },
  },
  { // ステージ5：ラスト。高い階段と落とし穴の夜
    name: "ステージ 5", width: 3200,
    sky: "#0f1830", ground: "#2a2440", pillar: "#3a3358", plat: "#4a4275", platTop: "#7d6fd0", text: "#eceaff",
    platforms: [
      { x: 600,  w: 140, top: 80 },
      { x: 820,  w: 140, top: 160 },
      { x: 1040, w: 140, top: 240 },
      { x: 1260, w: 200, top: 160 },
      { x: 1560, w: 140, top: 80 },
      { x: 1800, w: 140, top: 160 },
      { x: 2040, w: 140, top: 240 },
      { x: 2280, w: 140, top: 320 },
      { x: 2520, w: 300, top: 320 },
    ],
    pits: [ { x: 300, w: 100 }, { x: 1500, w: 100 }, { x: 1900, w: 120 } ],
    coins: [
      { x: 200, z: 40 }, { x: 350, z: 100 },
      { x: 670, z: 120 }, { x: 890, z: 200 }, { x: 1110, z: 280 }, { x: 1360, z: 200 },
      { x: 1630, z: 120 }, { x: 1870, z: 200 }, { x: 2110, z: 280 }, { x: 2350, z: 360 }, { x: 2600, z: 360 },
    ],
    enemies: [
      { x: 480,  z: 0,   min: 420,  max: 590 },
      { x: 1350, z: 160, min: 1270, max: 1440 },
      { x: 1250, z: 0,   min: 1200, max: 1480 },
      { x: 2600, z: 320, min: 2530, max: 2620 },
    ],
    cup: { x: 2720, base: 320 },
  },
];

// ステージのデータから、遊んでいる間に変わるコインと敵の状態を作る
function stage_coins(stage) {
  return stage.coins.map((c) => ({ x: c.x, z: c.z, got: false }));
}
function stage_enemies(stage) {
  // x は中心の位置。dead は倒されてからの秒数（null なら生きている）
  return stage.enemies.map((e) => ({ x: e.x, z: e.z, w: 36, h: 30, min: e.min, max: e.max, dir: 1, speed: 60, dead: null }));
}

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
    this.lives = MAX_LIVES;
    this.coinCount = 0;
    this.points = 0;
    this.shell.setScore(0);
    this.loadStage(0);
  },

  // 点数を足して、結果画面のスコアにも反映する
  addPoints(n) {
    this.points += n;
    this.shell.setScore(this.points);
  },

  // 指定した x が落とし穴の上か
  inPit(x) {
    for (const pit of this.stage.pits) {
      if (x > pit.x && x < pit.x + pit.w) return true;
    }
    return false;
  },

  // ステージを読み込む
  loadStage(index) {
    this.stageIndex = index;
    this.stage = STAGES[index];
    // 主人公：x は左端の位置、z は地面からの高さ、vz は上向きの速さ
    this.player = { x: 120, z: 0, vz: 0, w: 40, h: 40, speed: 240, facing: 1, grounded: true };
    this.jumpBuffer = 0;
    this.invuln = 0;                           // 無敵の残り秒数
    this.coinList = stage_coins(this.stage);
    this.enemies = stage_enemies(this.stage);
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
      const fallSpeed = p.vz;                                  // 着地で0にされる前の速さ（敵を踏む判定に使う）
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
        else if (p.z <= 0 && !this.inPit(p.x + p.w / 2)) { p.z = 0; p.vz = 0; grounded = true; }
      }
      p.grounded = grounded;

      // 落とし穴に落ちたらミス
      if (p.z < -300) { this.loseLife(); return; }

      // コイン
      const pcx = p.x + p.w / 2, pcz = p.z + p.h / 2;
      for (const c of this.coinList) {
        if (!c.got && Math.abs(c.x - pcx) < 28 && Math.abs(c.z - pcz) < 30) {
          c.got = true;
          this.coinCount++;
          this.addPoints(COIN_POINT);
        }
      }

      // 敵：左右に歩く。上から踏めば倒せる。横などから触れるとミス
      if (this.invuln > 0) this.invuln -= dt;
      for (const en of this.enemies) {
        if (en.dead !== null) { en.dead += dt; continue; }
        en.x += en.dir * en.speed * dt;
        if (en.x < en.min) { en.x = en.min; en.dir = 1; }
        if (en.x > en.max) { en.x = en.max; en.dir = -1; }
        const hitX = Math.abs(en.x - pcx) < (p.w + en.w) / 2 - 8;
        const hitZ = p.z < en.z + en.h && p.z + p.h > en.z + 4;
        if (!hitX || !hitZ) continue;
        if (fallSpeed < 0 && prevZ >= en.z + en.h * 0.5) {
          en.dead = 0;                                        // 踏んだ！
          p.vz = JUMP_SPEED * 0.65;
          p.grounded = false;
          this.addPoints(STOMP_POINT);
        } else if (this.invuln <= 0) {
          this.loseLife();
          return;
        }
      }

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

  // ミス：ライフが1つ減る。0になったらゲームオーバー
  loseLife() {
    this.lives--;
    if (this.lives <= 0) {
      this.shell.end("ゲームオーバー…（ステージ " + (this.stageIndex + 1) + " まで到達）");
      return;
    }
    const p = this.player;
    p.x = 120; p.z = 0; p.vz = 0; p.grounded = true;
    this.jumpBuffer = 0;
    this.invuln = INVULN_TIME;
    this.cameraX = this.cameraTargetX();
    this.cameraY = 0;
  },

  // ゴールしたあと：次のステージへ、最後ならクリア
  goNext() {
    this.addPoints(STAGE_POINT);
    if (this.stageIndex < STAGES.length - 1) {
      this.loadStage(this.stageIndex + 1);
    } else {
      this.entering = null;
      this.addPoints(this.lives * LIFE_POINT);   // 残りライフのボーナス
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
    // （落とし穴のところは地面を描かない）
    ctx.fillStyle = stage.ground;
    let gx = 0;
    const drawGround = (from, to) => {
      const a = Math.max(from, cam), b = Math.min(to, cam + w);
      if (b > a) ctx.fillRect(a, groundY, b - a, 2000);
    };
    for (const pit of stage.pits) { drawGround(gx, pit.x); gx = pit.x + pit.w; }
    drawGround(gx, stage.width);

    // 目印の柱（200pxごと）。カメラが動いているのが分かるようにする
    ctx.fillStyle = stage.pillar;
    const first = Math.floor(cam / 200) * 200;
    for (let x = first; x <= cam + w; x += 200) {
      if (this.inPit(x + 5)) continue;
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

    // コイン
    for (const c of this.coinList) {
      if (c.got || c.x + 20 < cam || c.x - 20 > cam + w) continue;
      const bob = Math.sin(this.time * 4 + c.x * 0.05) * 3;
      const sx = Math.abs(Math.cos(this.time * 3 + c.x * 0.05)) * 0.7 + 0.3;   // くるくる回る見た目
      ctx.fillStyle = "#f5c518";
      ctx.strokeStyle = "#b8860b";
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.ellipse(c.x, groundY - c.z + bob, 10 * sx, 10, 0, 0, Math.PI * 2);
      ctx.fill();
      ctx.stroke();
    }

    // 敵（踏まれたらぺしゃんこになって消える）
    for (const en of this.enemies) {
      if (en.dead !== null && en.dead > 0.3) continue;
      if (en.x + en.w < cam || en.x - en.w > cam + w) continue;
      const squash = en.dead === null ? 1 : 0.25;
      const eh = en.h * squash;
      const ex = en.x - en.w / 2, ey = groundY - en.z - eh;
      ctx.fillStyle = "#c0504d";
      ctx.beginPath();
      ctx.roundRect(ex, ey, en.w, eh, 8);
      ctx.fill();
      if (en.dead === null) {
        ctx.fillStyle = "#fff";
        ctx.fillRect(ex + (en.dir > 0 ? 18 : 6), ey + 8, 6, 8);
        ctx.fillRect(ex + (en.dir > 0 ? 26 : 14), ey + 8, 6, 8);
        ctx.fillStyle = "#1c2433";
        ctx.fillRect(ex + (en.dir > 0 ? 21 : 6), ey + 11, 3, 4);
        ctx.fillRect(ex + (en.dir > 0 ? 29 : 14), ey + 11, 3, 4);
      }
    }

    // 影（高く跳ぶほど小さく薄くなる）
    const floor = this.floorUnder();
    const shrink = Math.max(0.3, 1 - (p.z - floor) / 300);
    if (floor > 0 || !this.inPit(p.x + p.w / 2)) {
      ctx.fillStyle = "rgba(0, 0, 0, 0.25)";
      ctx.beginPath();
      ctx.ellipse(p.x + p.w / 2, groundY - floor + 3, (p.w / 2) * shrink, 5 * shrink, 0, 0, Math.PI * 2);
      ctx.fill();
    }

    // 主人公（緑の四角）。z の分だけ上に持ち上げる。カップに入るときは小さくなる
    // 無敵の間はちかちか点滅する
    const s = this.entering ? Math.max(0.05, 1 - this.entering.t / ENTER_TIME) : 1;
    const cxp = p.x + p.w / 2, cyp = groundY - p.z - p.h / 2;
    if (this.invuln > 0 && Math.floor(this.invuln * 10) % 2 === 0) ctx.globalAlpha = 0.35;
    ctx.save();
    ctx.translate(cxp, cyp);
    ctx.scale(s, s);
    ctx.fillStyle = "#6aa84f";
    ctx.fillRect(-p.w / 2, -p.h / 2, p.w, p.h);
    ctx.fillStyle = "#1c2433";                                  // 目（向いている方向に寄せる）
    ctx.fillRect(p.facing > 0 ? p.w / 2 - 14 : -p.w / 2 + 6, -p.h / 2 + 10, 8, 8);
    ctx.restore();
    ctx.globalAlpha = 1;

    ctx.restore();

    // 画面に固定して出す文字
    ctx.fillStyle = stage.text;
    ctx.font = "700 18px 'Hiragino Sans', 'Yu Gothic', sans-serif";
    ctx.textAlign = "left";
    ctx.fillText(stage.name + " / " + STAGES.length, 16, 34);
    ctx.textAlign = "right";
    ctx.fillText("♥ ×" + this.lives + "   ● ×" + this.coinCount, w - 16, 34);
    ctx.font = "700 14px 'Hiragino Sans', 'Yu Gothic', sans-serif";
    ctx.fillText(this.points + " 点", w - 16, 56);
    ctx.textAlign = "left";

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
