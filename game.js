/* =====================================================================
   game.js ── maccha2D（ver 12）
   ・左右に動く（PC：← → / A D キー）
   ・ジャンプ（PC：スペース / ↑ / W キー）
   ・スマホ：画面の下の左右をタッチで移動、画面の上をタッチでジャンプ
   ・ティーカップに入るとゴール → 次のステージへ（全5ステージ）
   ・カメラが主人公を追いかける（横も縦も）
   ・キャラは液体（跳ぶとのびて、着地でぷるぷる、しぶきが飛ぶ）。敵に当たると、大きい敵なら吸収されてミス／同じか小さい敵なら分裂（かけらを拾うと回復）。敵同士も戦う。敵は抹茶のライバルのお茶（紅茶・ほうじ茶・ウーロン茶）。四角い体で追いかけてジャンプする。踏むと倒せる / 落とし穴 / ライフ3つ
   ===================================================================== */

// 設定
const CONFIG = {
  title:      "maccha2D",
  tagline:    "2Dアクションゲーム（ver 12）",   // ← ページが新しくなったか確認する目印。不要なら消してOK
  howTo:      "ティーカップに入ればゴール！ ライバルのお茶（紅茶・ほうじ茶・ウーロン茶）は追いかけてきてジャンプもする！ 上から踏むと吸収して大きくなるよ。敵に当たったとき、相手が自分より大きいと吸収されてミス、同じ大きさか小さいと分裂（かけらを拾えば元にもどる）。敵同士も戦って、吸収したり分裂したりするよ。落とし穴と横からの接触に注意（ライフ3つ）。← → キーで移動、スペースキーでジャンプ。スマホは画面の下の左右で移動、上をタッチでジャンプ。",
  timeLimit:  null,               // 時間制限なし
  storageKey: "maccha2d-best",    // ベストスコアの保存名
};

const GRAVITY    = 1800;   // 重力（大きいほど早く落ちる）
const JUMP_SPEED = 640;    // ジャンプの強さ（大きいほど高く跳ぶ）
const ENTER_TIME = 0.5;    // カップに入る動きにかかる秒数
const MAX_LIVES  = 3;      // ライフの数
const INVULN_TIME = 1.5;   // ミスしたあとの無敵の秒数
const STOMP_POINT = 200;   // 敵を踏んだときの点数
const PLAYER_SIZE = 40;    // 主人公のふつうの大きさ
const GROW_RATIO  = 0.1;   // 敵を踏んで吸収したとき、その敵の大きさの何割だけ大きくなるか（40の敵なら+4）
const MAX_SIZE    = 100;   // 大きくなれる限界（ライフを失うとふつうの大きさに戻る）
const ABSORB_TIME = 0.3;   // 敵が吸い込まれる秒数
const MIN_SIZE    = 24;    // 分裂で小さくなれる限界
const SPLIT_KEEP  = 0.6;   // 敵に当たったとき、本体に残る大きさの割合（残りは2つのかけらになって飛び出す）
const FRAG_LIFE   = 8;     // かけらが消えるまでの秒数
const FRAG_DELAY  = 0.5;   // 飛び出したかけらを拾えるようになるまでの秒数
const ENEMY_MAX   = 140;   // 敵が大きくなれる限界
const ENEMY_SPLIT_MIN = 28;// これより小さい敵は分裂できない
const MAX_ENEMIES = 12;    // 同時にいられる敵の数（分裂で増えすぎないように）
const FIGHT_COOLDOWN = 0.8;// 敵同士が戦ったあと、また戦えるまでの秒数
const BRAWL_SIGHT = 480;   // 敵が、ほかの敵に気づく距離
const BRAWL_LEASH = 420;   // 敵同士で戦うとき、ふだん歩く範囲の外まで追いかけていける距離
const FOE_PRIORITY = 150;  // ほかの敵のほうが、プレイヤーよりこれだけ遠くても、敵のほうを優先して襲いにいく
const PLAYER_COLOR = "#6aa84f";   // 主人公（抹茶）の色。しぶきの色にも使う
const MAX_DROPS = 240;     // 同時に飛ぶしずくの数の上限
const STAGE_POINT = 1000;  // ステージクリアの点数
const LIFE_POINT  = 500;   // 全クリア時、残りライフ1つあたりの点数

/* ---------------------------------------------------------------------
   ステージのデータ（ここを書き換えると、ステージを作り変えられます）
     width      ステージの横幅
     platforms  足場。x=左端 / w=幅 / top=地面から足場の上面までの高さ
   pits       落とし穴（地面の切れ目）。x=左端 / w=幅（100〜120pxまで。跳べる距離は約170px）
   enemies    四角い敵（抹茶のライバルの、抹茶以外のお茶）。近づくと追いかけてジャンプもする。
              x=最初の位置 / z=立っている足場の高さ / min・max=ふだん歩く範囲（左右の端）
              type=お茶の種類（"kocha" 紅茶 / "hojicha" ほうじ茶 / "oolong" ウーロン茶。省略すると順番に決まる）
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
    enemies: [
      { x: 1550, z: 0, min: 1480, max: 1950 },
      { x: 1800, z: 0, min: 1720, max: 1960 },
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
    enemies: [
      { x: 350,  z: 0, min: 250,  max: 520 },
      { x: 1500, z: 0, min: 1480, max: 1780 },
      { x: 1700, z: 0, min: 1620, max: 1780 },
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
    enemies: [
      { x: 600, z: 0, min: 520, max: 690 },
      { x: 1700, z: 0, min: 1600, max: 1780 },
      { x: 1900, z: 0, min: 1800, max: 2000 },
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
    enemies: [
      { x: 700,  z: 0,   min: 620,  max: 880 },
      { x: 1100, z: 0,   min: 1020, max: 1280 },
      { x: 1450, z: 0,   min: 1430, max: 1560 },
      { x: 2330, z: 140, min: 2310, max: 2380 },
      { x: 800,  z: 0, min: 640,  max: 880 },
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
    enemies: [
      { x: 480,  z: 0,   min: 420,  max: 590 },
      { x: 1350, z: 160, min: 1270, max: 1440 },
      { x: 1250, z: 0,   min: 1200, max: 1480 },
      { x: 2600, z: 320, min: 2530, max: 2620 },
      { x: 530,  z: 0, min: 440,  max: 590 },
    ],
    cup: { x: 2720, base: 320 },
  },
];

// 敵の種類：抹茶のライバルの、抹茶以外のお茶たち（四角い体）
//   size=大きさ（主人公と同じ40） / speed=追いかける速さ / jump=ジャンプの強さ / body=体の色 / dark=ふちの色
const DRINKS = {
  kocha:   { name: "紅茶",     size: 40, speed: 90,  jump: 560, body: "#c4501f", dark: "#8f2f0e" },
  hojicha: { name: "ほうじ茶", size: 40, speed: 125, jump: 620, body: "#8b5a2b", dark: "#4d2c12" },
  oolong:  { name: "ウーロン茶", size: 40, speed: 65,  jump: 520, body: "#d19a2a", dark: "#8a5f14" },
};
const DRINK_ORDER = ["kocha", "hojicha", "oolong"];
const SIGHT_X = 340;        // 敵がプレイヤーに気づく横の距離
const SIGHT_Z = 220;        // 敵がプレイヤーに気づく高さの差
const LEASH   = 220;        // 敵が「歩く範囲」の外まで追いかけていける距離
const PATROL_RATIO = 0.55;  // 気づいていないときの歩く速さ（追いかける速さに対する割合）
const ENEMY_JUMP_WAIT = 1.1;// 敵が続けてジャンプできるまでの秒数

// ステージのデータから、遊んでいる間に変わる敵の状態を作る
function stage_enemies(stage) {
  // x は中心の位置。vz は上向きの速さ。size は目指す大きさ（w・h は今の大きさ）。
  // dead は倒されてからの秒数（null なら生きている）。absorber は吸収した相手（主人公なら null）
  return stage.enemies.map((e, i) => {
    const type = e.type || DRINK_ORDER[i % DRINK_ORDER.length];
    const d = DRINKS[type];
    return { type, x: e.x, z: e.z, vz: 0, grounded: true, size: d.size, w: d.size, h: d.size, min: e.min, max: e.max,
             dir: 1, speed: d.speed, jump: d.jump, jumpWait: 0, chasing: false, fightCd: 0, dead: null, absorber: null, spr: { x: 0, v: 0 } };
  });
}

const game = {
  // 敵のAI（学習済みのニューラルネット）を enemy-ai.json から読み込む
  //   tools/train-enemy-ai.js で作り直せる。読み込めなかったら（ファイルを直接開いたときなど）ルールで動く
  loadEnemyAI() {
    fetch("enemy-ai.json")
      .then((r) => (r.ok ? r.json() : Promise.reject()))
      .then((net) => { this.ai = net; })
      .catch(() => { this.ai = null; });
  },

  // ニューラルネットの計算（tanh の全結合）。出力は [左, 止まる, 右, ジャンプ] のスコア
  runAI(f) {
    let x = f;
    const layers = this.ai.layers;
    for (let li = 0; li < layers.length; li++) {
      const L = layers[li];
      const out = L.w.map((row, j) => {
        let sum = L.b[j];
        for (let k = 0; k < row.length; k++) sum += row[k] * x[k];
        return sum;
      });
      x = li < layers.length - 1 ? out.map(Math.tanh) : out;
    }
    return x;
  },

  // 敵AIへの入力：プレイヤーとの位置関係、プレイヤーの動き、足元の穴など
  enemyFeatures(en, p, pcx, tvx) {
    const c = (v) => Math.max(-1, Math.min(1, v));
    const onGround = en.z === 0;
    return [
      c((pcx - en.x) / SIGHT_X), c((p.z - en.z) / SIGHT_Z), c(tvx / 240), c(p.vz / 640),
      p.grounded ? 1 : 0, en.grounded ? 1 : 0, en.jumpWait <= 0 ? 1 : 0,
      onGround && this.inPit(en.x + 50) ? 1 : 0, onGround && this.inPit(en.x - 50) ? 1 : 0,
    ];
  },

  // 追いかけ中の敵の判断：{ move: -1/0/1, jump: true/false }。AIがあればAI、なければルール
  decideEnemy(en, p, pcx, tvx) {
    const f = this.enemyFeatures(en, p, pcx, tvx);
    if (this.ai) {
      const o = this.runAI(f);
      const move = o[0] > o[1] && o[0] > o[2] ? -1 : (o[2] > o[1] ? 1 : 0);
      return { move, jump: o[3] > 0 && en.grounded && en.jumpWait <= 0 };
    }
    const dx = pcx - en.x;
    const target = dx + 0.25 * tvx;
    const move = Math.abs(target) < 8 ? 0 : (target > 0 ? 1 : -1);
    const pitAhead = move > 0 ? f[7] : move < 0 ? f[8] : 0;
    const above = p.z > en.z + 20 && Math.abs(dx) < 140;
    const dodge = !p.grounded && Math.abs(dx) < 110;
    return { move, jump: en.grounded && en.jumpWait <= 0 && (above || pitAhead === 1 || dodge) };
  },

  // 最初に1回だけ呼ばれる
  onReady(ctx, shell) {
    this.ctx = ctx;
    this.shell = shell;
    this.playing = false;
    this.ai = null;                                            // 敵のAI（読み込めるまでは、ふつうのルールで動く）
    this.loadEnemyAI();
    this.pvx = 0;                                              // プレイヤーの横の速さ（敵AIの入力に使う）
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
    this.pvx = 0;
    this.lives = MAX_LIVES;
    this.size = PLAYER_SIZE;                                   // 主人公が目指す大きさ（敵を吸収すると増える）
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
    this.player = { x: 120, z: 0, vz: 0, w: this.size, h: this.size, speed: 240, facing: 1, grounded: true };
    this.jumpBuffer = 0;
    this.invuln = 0;                           // 無敵の残り秒数
    this.frags = [];                           // 分裂で飛び出した、拾えるかけら
    this.drops = [];                           // 飛び散るしずく（見た目だけ）
    this.spr = { x: 0, v: 0 };                 // 主人公のぷるぷる（バネ）。正=つぶれる／負=のびる
    this.dropTimer = 0;
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
    this.stepSpring(this.spr, dt);
    this.updateDrops(dt);

    // 大きさをなめらかに変える（横の中心は動かさない）
    if (Math.abs(p.w - this.size) > 0.05) {
      const cx = p.x + p.w / 2;
      p.w = p.h = p.w + (this.size - p.w) * Math.min(1, dt * 10);
      p.x = Math.max(0, Math.min(stage.width - p.w, cx - p.w / 2));
    }
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
      this.pvx = dir * p.speed;
      if (dir !== 0 && p.grounded) {                           // 歩くと足元にしずくが散る
        this.dropTimer -= dt;
        if (this.dropTimer <= 0) {
          this.dropTimer = 0.09;
          this.spawnDrops(p.x + p.w / 2 - dir * p.w * 0.4, p.z + 2, PLAYER_COLOR, 1, 40);
        }
      }
      p.x = Math.max(0, Math.min(stage.width - p.w, p.x));   // ステージの外に出ない
      if (dir !== 0) p.facing = dir;

      // ジャンプ：どこかに立っているときにボタンが押されたら跳ぶ
      if (this.jumpBuffer > 0) this.jumpBuffer -= dt;
      if (p.grounded && this.jumpBuffer > 0) {
        p.vz = JUMP_SPEED;
        p.grounded = false;
        this.jumpBuffer = 0;
        this.kick(this.spr, -7);                               // 跳ぶ瞬間にびよんとのびる
        this.spawnDrops(p.x + p.w / 2, p.z + 2, PLAYER_COLOR, 3, 90);
      }

      // 重力：ボタンを早く離すと低いジャンプになる
      const jumpHeld = this.keys.jump || this.touch.jump;
      const g = (p.vz > 0 && !jumpHeld) ? GRAVITY * 2.5 : GRAVITY;
      const prevZ = p.z;
      p.vz -= g * dt;
      const fallSpeed = p.vz;                                  // 着地で0にされる前の速さ（敵を踏む判定に使う）
      p.z += p.vz * dt;

      // 着地の判定：落ちているとき、足場の上面をまたいだら、その上に乗る
      const wasGrounded = p.grounded;
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
      if (!wasGrounded && grounded && fallSpeed < -150) {      // 着地：ぺちゃっとつぶれてしぶきが飛ぶ
        const k = Math.min(1, -fallSpeed / 700);
        this.kick(this.spr, 3 + k * 9);
        this.spawnDrops(p.x + p.w / 2, p.z + 2, PLAYER_COLOR, Math.round(3 + k * 6), 90 + k * 140);
      }

      // 落とし穴に落ちたらミス
      if (p.z < -300) { this.loseLife(); return; }

      const pcx = p.x + p.w / 2;

      // 敵：近づくと追いかけてきてジャンプもする。上から踏めば倒せる。横などから触れるとミス
      if (this.invuln > 0) this.invuln -= dt;
      this.enemies = this.enemies.filter((e) => e.dead === null || e.dead <= ABSORB_TIME);   // 消えた敵を取り除く
      this.enemyFights();
      for (const en of this.enemies) {
        if (en.dead !== null) { en.dead += dt; continue; }
        if (Math.abs(en.w - en.size) > 0.05) {                // 大きさをなめらかに変える
          en.w = en.h = en.w + (en.size - en.w) * Math.min(1, dt * 10);
        }
        this.moveEnemy(en, dt, pcx, p);
        if (en.dead !== null) continue;                       // 穴に落ちた
        const hitX = Math.abs(en.x - pcx) < (p.w + en.w) / 2 - 8;
        const hitZ = p.z < en.z + en.h && p.z + p.h > en.z + 4;
        if (!hitX || !hitZ) continue;
        if (fallSpeed < 0 && prevZ >= en.z + en.h * 0.5) {
          // 踏んだ！吸収が起きるのは、大きいほうが小さいほうを攻撃したときだけ
          if (this.size > en.size + 0.5) {
            en.dead = 0;                                      // 自分のほうが大きい：敵を吸収して大きくなる
            en.absorber = null;
            this.spawnDrops(en.x, en.z + en.h / 2, DRINKS[en.type].body, 12, 260);   // 敵がはじけてしぶきになる
            this.spawnDrops(pcx, p.z, PLAYER_COLOR, 4, 160);
            this.kick(this.spr, 8);
            this.size = Math.min(MAX_SIZE, this.size + Math.max(2, Math.round(en.size * GROW_RATIO)));
            this.addPoints(STOMP_POINT);
          } else {
            this.splitEnemy(en);                              // 同じ大きさか大きい敵：吸収できず、敵が分裂する
            en.fightCd = FIGHT_COOLDOWN;
            this.addPoints(STOMP_POINT / 2);
          }
          p.vz = JUMP_SPEED * 0.65;
          p.grounded = false;
          p.z = Math.max(p.z, en.z + en.h + 1);               // 敵の上に乗せて、すぐ横から当たらないようにする
          this.invuln = Math.max(this.invuln, 0.4);
        } else if (this.invuln <= 0) {
          if (en.size > this.size + 0.5) {
            // 自分より大きい敵に当たった：吸収されてミス。敵は大きくなる
            en.size = Math.min(ENEMY_MAX, en.size + this.size * 0.25);
            this.spawnDrops(pcx, p.z + p.h / 2, PLAYER_COLOR, 16, 280);      // 吸収されて飛び散る
            this.spawnDrops(en.x, en.z + en.h / 2, DRINKS[en.type].body, 8, 200);
            this.kick(en.spr, 8);
            this.loseLife();
            return;
          }
          if (this.size <= MIN_SIZE) {
            // 同じか小さい敵だけど、もう小さくなれない：吸収ではなく、ふつうにやられてミス（敵は大きくならない）
            this.spawnDrops(pcx, p.z + p.h / 2, PLAYER_COLOR, 8, 200);
            this.loseLife();
            return;
          }
          this.splitPlayer();                                     // 同じ大きさか小さい敵に当たった：分裂
          continue;
        }
      }

      this.updateFrags(dt, p);

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

  // いちばん近い、ほかの敵（同じ高さにいて、戦ったばかりでないもの）
  nearestFoe(en) {
    if (en.fightCd > 0) return null;
    let best = null, bestD = BRAWL_SIGHT;
    for (const o of this.enemies) {
      if (o === en || o.dead !== null) continue;
      const d = Math.abs(o.x - en.x);
      if (d < bestD && Math.abs(o.z - en.z) < 120) { best = o; bestD = d; }
    }
    return best;
  },

  // 敵同士がぶつかったとき：大きさが違えば大きいほうが吸収して大きくなる。同じなら両方分裂する
  enemyFights() {
    const list = this.enemies;
    const n = list.length;
    for (let i = 0; i < n; i++) {
      for (let j = i + 1; j < n; j++) {
        const a = list[i], b = list[j];
        if (a.dead !== null || b.dead !== null || a.fightCd > 0 || b.fightCd > 0) continue;
        if (Math.abs(a.x - b.x) >= (a.w + b.w) / 2 - 4) continue;
        if (!(a.z < b.z + b.h - 4 && b.z < a.z + a.h - 4)) continue;
        a.fightCd = b.fightCd = FIGHT_COOLDOWN;
        if (Math.abs(a.size - b.size) < 0.5) {
          this.splitEnemy(a);
          this.splitEnemy(b);
        } else {
          const big = a.size > b.size ? a : b, small = big === a ? b : a;
          big.size = Math.min(ENEMY_MAX, big.size + small.size * 0.5);
          small.dead = 0;
          small.absorber = big;
          this.spawnDrops(small.x, small.z + small.h / 2, DRINKS[small.type].body, 10, 200);
          this.kick(big.spr, 6);
        }
      }
    }
  },

  // 敵の分裂：小さくなって、失った分が新しい敵になって飛び出す（小さすぎる敵や、数が多すぎるときは分裂しない）
  splitEnemy(e) {
    e.vz = 250; e.grounded = false;
    this.kick(e.spr, 8);
    this.spawnDrops(e.x, e.z + e.h / 2, DRINKS[e.type].body, 8, 220);
    if (e.size < ENEMY_SPLIT_MIN || this.enemies.filter((o) => o.dead === null).length >= MAX_ENEMIES) return;
    const keep = Math.round(e.size * SPLIT_KEEP);
    const childSize = e.size - keep;
    e.size = keep;
    this.enemies.push({ ...e, spr: { x: 0, v: 0 }, size: childSize, w: childSize, h: childSize, x: e.x, vz: 350, grounded: false,
                        dir: -e.dir, jumpWait: 0, chasing: false, fightCd: FIGHT_COOLDOWN, dead: null, absorber: null });
  },

  // 敵1体の動き：プレイヤーが近ければ追いかける（上にいたらジャンプ）。遠ければ範囲内を歩く
  moveEnemy(en, dt, pcx, p) {
    const stage = this.stage;
    const dx = pcx - en.x;
    const seesPlayer = Math.abs(dx) < SIGHT_X && Math.abs(p.z - en.z) < SIGHT_Z && !this.entering;
    // 積極的に戦う：ほかの敵が見えたら、プレイヤーがよほど近くにいない限り、そっちを襲いにいく
    const foe = this.nearestFoe(en);
    const hunt = !!foe && (!seesPlayer || Math.abs(foe.x - en.x) < Math.abs(dx) + FOE_PRIORITY);
    en.chasing = seesPlayer || hunt;
    let speed = en.speed * PATROL_RATIO;
    let lo = en.min, hi = en.max;
    let move = en.dir, wantJump = false;
    en.fightCd -= dt;
    if (hunt) {
      speed = en.speed;
      lo = Math.max(0, en.min - BRAWL_LEASH);
      hi = Math.min(stage.width, en.max + BRAWL_LEASH);
      // 相手の敵に向かって、AI（なければルール）が動きとジャンプを決める（避けジャンプはしない）
      const d = this.decideEnemy(en, { z: foe.z, vz: foe.vz, grounded: true }, foe.x, 0);
      move = d.move;
      wantJump = d.jump;
      if (move !== 0) en.dir = move;
    } else if (seesPlayer) {
      speed = en.speed;
      lo = Math.max(0, en.min - LEASH);
      hi = Math.min(stage.width, en.max + LEASH);
      const d = this.decideEnemy(en, p, pcx, this.pvx);      // AI（なければルール）が動きとジャンプを決める
      move = d.move;
      wantJump = d.jump;
      if (move !== 0) en.dir = move;
    } else {
      if (en.x <= en.min) en.dir = 1;
      if (en.x >= en.max) en.dir = -1;
      move = en.dir;
    }

    // 横に動く（地面にいるときは、落とし穴には入らずに手前で止まる）
    let nx = en.x + move * speed * dt;
    nx = Math.max(lo, Math.min(hi, nx));
    if (en.grounded && en.z === 0 && this.inPit(nx)) nx = en.x;
    en.x = nx;

    // ジャンプ
    en.jumpWait -= dt;
    if (wantJump && en.grounded) {
      en.vz = en.jump; en.grounded = false; en.jumpWait = ENEMY_JUMP_WAIT;
      this.kick(en.spr, -7);
    }

    // 重力と着地（足場の上か、地面の上。穴の上なら落ちる）
    this.stepSpring(en.spr, dt);
    const wasG = en.grounded;
    const prevZ = en.z;
    en.vz -= GRAVITY * dt;
    const vzBefore = en.vz;
    en.z += en.vz * dt;
    en.grounded = false;
    if (en.vz <= 0) {
      let landTop = -1;
      for (const pl of stage.platforms) {
        if (en.x > pl.x && en.x < pl.x + pl.w && prevZ >= pl.top - 0.5 && en.z <= pl.top && pl.top > landTop) landTop = pl.top;
      }
      if (landTop >= 0) { en.z = landTop; en.vz = 0; en.grounded = true; }
      else if (en.z <= 0 && !this.inPit(en.x)) { en.z = 0; en.vz = 0; en.grounded = true; }
    }
    if (!wasG && en.grounded && vzBefore < -200) {              // 着地でぺちゃっとつぶれる
      this.kick(en.spr, 3 + Math.min(1, -vzBefore / 700) * 8);
      this.spawnDrops(en.x, en.z + 2, DRINKS[en.type].body, 3, 100);
    }
    if (en.z < 0 && !en.grounded && this.inPit(en.x)) en.dead = 1;   // 穴に落ちたら、すぐ消える
  },

  // ---- 液体の見た目まわり ----------------------------------------------------
  // バネ：x(位置) が 0 に戻ろうとして、ぷるぷる揺れる
  stepSpring(sp, dt) {
    sp.v += (-240 * sp.x - 9 * sp.v) * dt;
    sp.x += sp.v * dt;
    if (sp.x > 0.8) sp.x = 0.8; else if (sp.x < -0.8) sp.x = -0.8;
  },
  kick(sp, v) { sp.v += v; },

  // つぶれ具合：バネ + 空中での縦のび + 立っているときの呼吸
  squashOf(sp, vz, grounded, seed) {
    let q = sp.x;
    if (!grounded) q -= Math.min(0.3, Math.abs(vz) / 2200);
    else q += Math.sin(this.time * 3 + seed) * 0.03;
    return Math.max(-0.5, Math.min(0.6, q));
  },

  // しずくを飛ばす（x=中心 / z=地面からの高さ / power=勢い）
  spawnDrops(x, z, color, n, power) {
    for (let i = 0; i < n; i++) {
      const life = 0.45 + Math.random() * 0.4;
      this.drops.push({ x, z, vx: (Math.random() * 2 - 1) * power, vz: 60 + Math.random() * power * 1.1,
                        life, max: life, r: 2 + Math.random() * 3, color });
    }
    if (this.drops.length > MAX_DROPS) this.drops.splice(0, this.drops.length - MAX_DROPS);
  },
  updateDrops(dt) {
    this.drops = this.drops.filter((d) => {
      d.life -= dt;
      d.vz -= GRAVITY * 0.8 * dt;
      d.x += d.vx * dt;
      const prevZ = d.z;
      d.z += d.vz * dt;
      if (d.life <= 0 || d.z < -300) return false;
      if (d.vz < 0) {                                              // 足場か地面に落ちて消える
        for (const pl of this.stage.platforms) {
          if (d.x > pl.x && d.x < pl.x + pl.w && prevZ >= pl.top - 0.5 && d.z <= pl.top) return false;
        }
        if (d.z <= 0 && !this.inPit(d.x)) return false;
      }
      return true;
    });
  },

  // 液体の形（足元の中心が原点。上のふちが波うち、横がふくらむ）。塗るのは呼び出し側
  blobPath(ctx, W, H, q, seed) {
    const r = Math.min(W, H) * 0.32;
    const bulge = q * W * 0.08;
    const amp = Math.min(3, H * 0.07) * (1 + Math.abs(q) * 2);
    const x0 = -W / 2, x1 = W / 2, yb = 0, yt = -H, ym = -H / 2;
    ctx.beginPath();
    ctx.moveTo(x0 + r, yb);
    ctx.lineTo(x1 - r, yb);
    ctx.quadraticCurveTo(x1, yb, x1, yb - r);
    ctx.quadraticCurveTo(x1 + bulge, ym, x1, yt + r);
    ctx.quadraticCurveTo(x1, yt, x1 - r, yt + Math.sin(this.time * 5 + seed) * amp);
    for (let px = x1 - r; px >= x0 + r; px -= 4) {
      ctx.lineTo(px, yt + Math.sin(this.time * 5 + px * 0.25 + seed) * amp);
    }
    ctx.quadraticCurveTo(x0, yt, x0, yt + r);
    ctx.quadraticCurveTo(x0 - bulge, ym, x0, yb - r);
    ctx.quadraticCurveTo(x0, yb, x0 + r, yb);
    ctx.closePath();
  },
  // つやと、下のほうの濃い影（blobPath を塗ったあとに呼ぶ）
  shine(ctx, W, H) {
    ctx.save();
    ctx.clip();
    ctx.fillStyle = "rgba(0, 0, 0, 0.10)";
    ctx.fillRect(-W, -H * 0.35, W * 2, H);
    ctx.fillStyle = "rgba(255, 255, 255, 0.32)";
    ctx.beginPath();
    ctx.ellipse(-W * 0.2, -H * 0.72, W * 0.14, H * 0.09, -0.5, 0, Math.PI * 2);
    ctx.fill();
    ctx.restore();
  },

  // 分裂：敵に当たると小さくなり、失った分が2つのかけらになって飛び出す（拾えば戻る）
  splitPlayer() {
    const p = this.player;
    const oldSize = this.size;
    this.size = Math.max(MIN_SIZE, Math.round(oldSize * SPLIT_KEEP));
    const mass = (oldSize - this.size) / 2;                    // かけら1つぶんの大きさ
    const cx = p.x + p.w / 2, cz = p.z + p.h / 2;
    for (const dir of [-1, 1]) {
      this.frags.push({ x: cx, z: cz, vx: dir * (130 + Math.random() * 70), vz: 380 + Math.random() * 80, mass, s: mass * 1.6 + 4, t: 0 });
    }
    p.vz = 320; p.grounded = false;                            // 少し跳ね上がる
    this.spawnDrops(cx, cz, PLAYER_COLOR, 16, 300);            // 分裂のしぶき
    this.kick(this.spr, 10);
    this.invuln = INVULN_TIME;
  },

  // かけらの動き：重力で落ちて弾む。触れると吸収して大きさが戻る。時間が経つと消える
  updateFrags(dt, p) {
    const stage = this.stage;
    const pcx = p.x + p.w / 2;
    this.frags = this.frags.filter((f) => {
      f.t += dt;
      if (f.t > FRAG_LIFE) return false;
      const prevZ = f.z;
      f.x = Math.max(0, Math.min(stage.width, f.x + f.vx * dt));
      f.vx *= Math.pow(0.5, dt);                               // 横の勢いはだんだん弱まる
      f.vz -= GRAVITY * dt;
      f.z += f.vz * dt;
      if (f.vz <= 0) {
        let landTop = -1;
        for (const pl of stage.platforms) {
          if (f.x > pl.x && f.x < pl.x + pl.w && prevZ >= pl.top - 0.5 && f.z <= pl.top && pl.top > landTop) landTop = pl.top;
        }
        if (landTop < 0 && f.z <= 0 && !this.inPit(f.x)) landTop = 0;
        if (landTop >= 0) {
          f.z = landTop;
          if (f.vz < -200) this.spawnDrops(f.x, f.z, PLAYER_COLOR, 3, 110);
          f.vz = f.vz < -200 ? -f.vz * 0.35 : 0;              // 弾む
          f.vx *= 0.6;
        }
      }
      if (f.z < -20 && this.inPit(f.x)) return false;          // 穴に落ちたら、すぐ消える
      if (f.t > FRAG_DELAY && Math.abs(f.x - pcx) < (p.w + f.s) / 2 && p.z < f.z + f.s && p.z + p.h > f.z) {
        this.size = Math.min(MAX_SIZE, this.size + f.mass);    // 拾って元にもどる
        this.kick(this.spr, 4);
        this.spawnDrops(f.x, f.z + f.s / 2, PLAYER_COLOR, 4, 110);
        return false;
      }
      return true;
    });
  },

  // ミス：ライフが1つ減る。0になったらゲームオーバー
  loseLife() {
    this.lives--;
    if (this.lives <= 0) {
      this.shell.end("ゲームオーバー…（ステージ " + (this.stageIndex + 1) + " まで到達）");
      return;
    }
    const p = this.player;
    this.size = PLAYER_SIZE;                                   // ライフを失うとふつうの大きさに戻る
    this.frags = [];
    p.w = p.h = PLAYER_SIZE;
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

    // 敵（踏まれたら主人公に吸収される）
    for (const en of this.enemies) {
      if (en.dead !== null && en.dead > ABSORB_TIME) continue;
      if (en.x + en.w < cam || en.x - en.w > cam + w) continue;
      this.drawDrink(ctx, en, groundY, p);
    }

    // かけら（しずくの形。消える直前はちかちかする）
    for (const f of this.frags) {
      if (f.x + f.s < cam || f.x - f.s > cam + w) continue;
      if (f.t > FRAG_LIFE - 2 && Math.floor(f.t * 8) % 2 === 0) continue;
      const fq = Math.max(-0.4, Math.min(0.5, -Math.min(0.3, Math.abs(f.vz) / 1800) + Math.sin(f.t * 14) * 0.12 * Math.max(0, 1 - f.t * 1.5)));
      ctx.save();
      ctx.translate(f.x, groundY - f.z);
      ctx.scale(1 + fq * 0.6, 1 - fq * 0.7);
      ctx.fillStyle = PLAYER_COLOR;
      this.blobPath(ctx, f.s, f.s, fq, f.x);
      ctx.fill();
      this.shine(ctx, f.s, f.s);
      ctx.restore();
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

    // 主人公（抹茶の液体）。z の分だけ上に持ち上げる。カップに入るときは小さくなる
    // 無敵の間はちかちか点滅する。跳ぶとのびて、着地するとつぶれてぷるぷる揺れる。動くと進む向きにかたむく
    const s = this.entering ? Math.max(0.05, 1 - this.entering.t / ENTER_TIME) : 1;
    const cxp = p.x + p.w / 2;
    if (this.invuln > 0 && Math.floor(this.invuln * 10) % 2 === 0) ctx.globalAlpha = 0.35;
    const q = this.squashOf(this.spr, p.vz, p.grounded, 0);
    const lean = Math.max(-1, Math.min(1, this.pvx / 240)) * 0.1;
    ctx.save();
    ctx.translate(cxp, groundY - p.z);
    ctx.scale(s, s);
    ctx.transform(1, 0, -lean, 1, 0, 0);
    ctx.scale(1 + q * 0.6, 1 - q * 0.7);
    ctx.fillStyle = PLAYER_COLOR;
    this.blobPath(ctx, p.w, p.h, q, 0);
    ctx.fill();
    this.shine(ctx, p.w, p.h);
    const u = p.w / 40;
    ctx.fillStyle = "#1c2433";                                  // 目（向いている方向に寄せる）
    ctx.fillRect(p.facing > 0 ? p.w / 2 - 14 * u : -p.w / 2 + 6 * u, -p.h + 10 * u, 8 * u, 8 * u);
    ctx.restore();
    ctx.globalAlpha = 1;

    // しずく（キャラの手前に飛び散る）
    for (const d of this.drops) {
      if (d.x + 10 < cam || d.x - 10 > cam + w) continue;
      ctx.globalAlpha = Math.min(1, d.life / 0.25);
      ctx.fillStyle = d.color;
      ctx.beginPath();
      ctx.ellipse(d.x, groundY - d.z, d.r, d.r * (1 + Math.min(0.6, Math.abs(d.vz) / 900)), 0, 0, Math.PI * 2);
      ctx.fill();
    }
    ctx.globalAlpha = 1;

    ctx.restore();

    // 画面に固定して出す文字
    ctx.fillStyle = stage.text;
    ctx.font = "700 18px 'Hiragino Sans', 'Yu Gothic', sans-serif";
    ctx.textAlign = "left";
    ctx.fillText(stage.name + " / " + STAGES.length, 16, 34);
    ctx.textAlign = "right";
    ctx.fillText("♥ ×" + this.lives, w - 16, 34);
    ctx.font = "700 14px 'Hiragino Sans', 'Yu Gothic', sans-serif";
    ctx.fillText(this.points + " 点", w - 16, 56);
    ctx.globalAlpha = 0.6;
    ctx.fillText(this.ai ? "敵AI：ニューラルネット" : "敵AI：ルール", w - 16, 76);
    ctx.globalAlpha = 1;
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

  // 敵のお茶を描く（四角い体に怒った顔。種類ごとに色と大きさが違う）
  drawDrink(ctx, en, groundY, p) {
    const d = DRINKS[en.type];
    if (en.dead !== null) {                                   // 吸収：しずくの形で、吸収した相手のほうへ吸い込まれる
      const t = Math.min(1, en.dead / ABSORB_TIME);
      const ecx = en.x, ecy = groundY - en.z - en.h / 2;
      const a = en.absorber;                                  // 吸収した相手（敵か主人公）
      const pcx = a ? a.x : p.x + p.w / 2, pcy = a ? groundY - a.z - a.h / 2 : groundY - p.z - p.h / 2;
      const cx = ecx + (pcx - ecx) * t, cy = ecy + (pcy - ecy) * t, sc = 1 - t;
      const W = en.w * sc, H = en.h * sc;
      ctx.save();
      ctx.translate(cx, cy + H / 2);
      ctx.fillStyle = d.body;
      this.blobPath(ctx, W, H, Math.sin(t * 20) * 0.2, en.x);
      ctx.fill();
      ctx.restore();
      return;
    }
    const face = en.dir;                                      // 進む向き（1=右）
    const W = en.w, H = en.h;
    const q = this.squashOf(en.spr, en.vz, en.grounded, en.x);

    ctx.save();
    ctx.translate(en.x, groundY - en.z);
    ctx.transform(1, 0, -face * 0.06, 1, 0, 0);               // 進む向きに少しかたむく
    ctx.scale(1 + q * 0.6, 1 - q * 0.7);
    ctx.fillStyle = d.body;
    this.blobPath(ctx, W, H, q, en.x);
    ctx.fill();
    this.shine(ctx, W, H);

    // 怒った顔（追いかけているときは眉がつり上がる）
    const u = W / 34;                                         // 大きさに合わせた倍率
    const fx = face * 3 * u;
    const eyeY = -H + H * 0.36;
    ctx.fillStyle = "#fff";
    ctx.fillRect(fx - 10 * u, eyeY, 8 * u, 9 * u);
    ctx.fillRect(fx + 2 * u,  eyeY, 8 * u, 9 * u);
    ctx.fillStyle = "#1c2433";
    ctx.fillRect(fx - 10 * u + (face > 0 ? 4 * u : 0), eyeY + 3 * u, 4 * u, 5 * u);
    ctx.fillRect(fx + 2 * u  + (face > 0 ? 4 * u : 0), eyeY + 3 * u, 4 * u, 5 * u);
    const brow = en.chasing ? 6 * u : 3 * u;
    ctx.strokeStyle = "#1c2433";
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.moveTo(fx - 12 * u, eyeY - brow); ctx.lineTo(fx - 2 * u, eyeY);
    ctx.moveTo(fx + 12 * u, eyeY - brow); ctx.lineTo(fx + 2 * u, eyeY);
    ctx.stroke();
    // 口
    ctx.beginPath();
    ctx.moveTo(-7 * u, -H + H * 0.8); ctx.lineTo(7 * u, -H + H * 0.8);
    ctx.stroke();
    ctx.restore();

    // 名前
    ctx.fillStyle = "#1c2433";
    ctx.globalAlpha = 0.7;
    ctx.font = "700 11px 'Hiragino Sans', 'Yu Gothic', sans-serif";
    ctx.textAlign = "center";
    ctx.fillText(d.name, en.x, groundY - en.z - H * (1 - q * 0.7) - 6);
    ctx.textAlign = "left";
    ctx.globalAlpha = 1;
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

  // shell からのキー入力（今回は使わない。キーは onReady で自分で受け取っている）
  onKey(e) {},

  // 終了時に呼ばれる
  onEnd() {
    this.playing = false;
  },
};
