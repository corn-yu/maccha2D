/* =====================================================================
   game.js ── maccha2D（ver 50）
   ・左右に動く（PC：← → / A D キー）
   ・ジャンプ（PC：スペース / ↑ / W キー）
   ・スマホ：画面の下の左右をタッチで移動、画面の上をタッチでジャンプ
   ・ティーカップに入るとゴール → 次のステージへ（全5ステージ）→ 最後は巨大アールグレイとのボス戦
   ・カメラが主人公を追いかける（横も縦も）
   ・キャラは液体（跳ぶとのびて、着地でぷるぷる、しぶきが飛ぶ）。敵に当たると、大きい敵なら吸収されてスタート地点からやりなおし／同じか小さい敵なら分裂（かけらを拾うと回復）。敵同士も戦う。敵は抹茶のライバルのお茶（紅茶・ほうじ茶・ウーロン茶）。四角い体で追いかけてジャンプする。踏むと倒せる（ライフ制はなし。ミスしても何度でもやりなおせる）
   ===================================================================== */

// 設定
const CONFIG = {
  title:      "maccha2D",
  tagline:    "2Dアクションゲーム（ver 50）",   // ← ページが新しくなったか確認する目印。不要なら消してOK
  howTo:      "",                    // タイトル画面の説明文（空なら出さない）
  timeLimit:  null,               // 時間制限なし
  noScore:    true,                // スコアなし（枠のHUDと、結果画面の点数・ベストを出さない）
  storageKey: "maccha2d-best",    // ベストスコアの保存名（noScoreがtrueの間は使わない）
};

const GRAVITY    = 1800;   // 重力（大きいほど早く落ちる）
const JUMP_SPEED = 600;    // ジャンプの強さ（大きいほど高く跳ぶ）
const ENTER_TIME = 0.5;    // カップに入る動きにかかる秒数
const INVULN_TIME = 1.5;   // ミスしたあとの無敵の秒数
const PLAYER_SIZE = 40;    // 主人公のふつうの大きさ
const GROW_RATIO  = 0.015; // 敵を踏んで吸収したとき、その敵の大きさの何割だけ大きくなるか（端数は貯まっていくので、無駄にはならない）
const KAKERA_GROW_RATIO = 0.02; // かけらを拾ったときは、敵を直接吸収するときより大きくなりにくい（かけらの大きさの何割が実際の成長になるか）
const MAX_SIZE    = Infinity; // 大きくなれる上限はなし（ミスするとふつうの大きさに戻る）
const ABSORB_TIME = 0.3;   // 敵が吸い込まれる秒数
const MIN_SIZE    = 24;    // 分裂で小さくなれる限界
const SPLIT_KEEP  = 0.6;   // 敵に当たったとき、本体に残る大きさの割合（残りは2つのかけらになって飛び出す）
const FRAG_LIFE   = 8;     // かけらが消えるまでの秒数
const FRAG_DELAY  = 0.5;   // 飛び出したかけらを拾えるようになるまでの秒数
const ENEMY_MAX   = 140;   // 敵が大きくなれる限界
const ENEMY_SPLIT_MIN = 28;// これより小さい敵は分裂できない
const FIGHT_COOLDOWN = 0.8;// 敵同士が戦ったあと、また戦えるまでの秒数
const BRAWL_SIGHT = 480;   // 敵が、ほかの敵に気づく距離
const FOE_PRIORITY = 150;  // ほかの敵のほうが、プレイヤーよりこれだけ遠くても、敵のほうを優先して襲いにいく
const BOSS_HP      = 30;    // ボスの体力（上から踏むと1減る）
const BOSS_SHRINK  = 0.5;   // 体力が0になるまでに、最初の大きさの何割まで縮むか（HPが多くても縮みすぎない）
const BOSS_SIZE    = 130;   // ボスの最初の大きさ（体力が減るごとに10ずつ小さくなる）
const BOSS_STUN    = 1.15;  // 踏まれたあと、ボスが動けなくなる秒数
const BOSS_SIZE_2  = 148;   // 第二形態になったときの大きさ
const BOSS_COLOR_2 = "#8f2a6b";   // 第二形態の体の色
const SHAKE_HEAD   = 1.3;   // 頭の上にこの秒数いすわると、ボスがふりはらう
const SHAKE_TELE   = 0.55;  // ふりはらいの予告（赤い柱が出る）の秒数
const BLAST_TIME   = 0.4;   // ふりはらいの衝撃が出ている秒数
const BOSS_STOMP   = 0.4;   // ボスは、体の高さのこれより上から踏めば踏んだことになる
const SLAM_UP_SPEED = 1000; // 滞空技：真上に跳ぶ強さ（ふつうのジャンプより高い）
const SLAM_HOVER    = 1.0;  // 滞空技：頂上で静止している秒数（第二形態はもう少し短い）
const SLAM_FALL_MULT = 2.4; // 滞空技：落ちるときの重力の倍率（ふつうより速く落ちる）
const BOSS_DATA_SCALE = 0.6;// ボスの「サイズの数値」(en.size)は、見た目・当たり判定(en.w/en.h)より小さくする（最初は150*0.6=90）
const PLAYER_COLOR = "#6aa84f";   // 主人公（抹茶）の色。しぶきの色にも使う
const MAX_DROPS = 240;     // 同時に飛ぶしずくの数の上限

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
  { // ステージ1：やさしい草原。足場と穴はなくして、坂と丘にした
    name: "ステージ 1", width: 2400, deco: "meadow",
    sky: "#8fd0f2", ground: "#6b4a2f", grass: "#7ed957", pillar: "#8a6a42", plat: "#9c7248", platTop: "#8bcf5e", text: "#1c2433",
    platforms: [],
    pits: [],
    // 丘の地形。x=位置 / h=地面の高さ。となりの点との間は、坂になってなめらかにつながる
    hills: [
      { x: 0,    h: 0 },
      { x: 260,  h: 0 },
      { x: 520,  h: 110 },
      { x: 760,  h: 0 },
      { x: 1000, h: 0 },
      { x: 1260, h: 170 },
      { x: 1520, h: 40 },
      { x: 1760, h: 0 },
      { x: 2400, h: 0 },
    ],
    enemies: [
      { x: 300,  z: 0, min: 200,  max: 600 },
      { x: 700,  z: 0, min: 620,  max: 980 },
      { x: 1100, z: 0, min: 1000, max: 1350 },
      { x: 1550, z: 0, min: 1480, max: 1950 },
      { x: 1800, z: 0, min: 1720, max: 1960 },
    ],
    cup: { x: 2100, base: 0 },
  },
  { // ステージ2：午後の草原。足場と穴はなくして、坂と丘にした
    name: "ステージ 2", width: 2400, deco: "meadow",
    sky: "#f7dfc4", ground: "#7a5a3a", grass: "#c8d95a", pillar: "#9c774f", plat: "#b98a57", platTop: "#d99a4e", text: "#3a2412",
    platforms: [],
    pits: [],
    hills: [
      { x: 0,    h: 0 },
      { x: 240,  h: 0 },
      { x: 480,  h: 150 },
      { x: 700,  h: 30 },
      { x: 940,  h: 0 },
      { x: 1180, h: 200 },
      { x: 1420, h: 70 },
      { x: 1660, h: 0 },
      { x: 1900, h: 0 },
      { x: 2400, h: 0 },
    ],
    enemies: [
      { x: 350,  z: 0, min: 250,  max: 520 },
      { x: 650,  z: 0, min: 560,  max: 900 },
      { x: 950,  z: 0, min: 850,  max: 1150 },
      { x: 1500, z: 0, min: 1480, max: 1780 },
      { x: 1700, z: 0, min: 1620, max: 1780 },
      { x: 2000, z: 0, min: 1900, max: 2100 },
    ],
    cup: { x: 2150, base: 0 },
  },
  { // ステージ3：夕暮れの草原。足場と穴はなくして、坂と丘にした
    name: "ステージ 3", width: 2700, deco: "meadow",
    sky: "#26335f", ground: "#3f3550", grass: "#7fae6f", pillar: "#54497a", plat: "#6b5a94", platTop: "#9d8ad0", text: "#f2f0ff",
    platforms: [],
    pits: [],
    hills: [
      { x: 0,    h: 0 },
      { x: 220,  h: 0 },
      { x: 460,  h: 170 },
      { x: 660,  h: 60 },
      { x: 880,  h: 220 },
      { x: 1100, h: 80 },
      { x: 1340, h: 0 },
      { x: 1580, h: 230 },
      { x: 1820, h: 90 },
      { x: 2060, h: 0 },
      { x: 2300, h: 0 },
      { x: 2700, h: 0 },
    ],
    enemies: [
      { x: 250,  z: 0, min: 180,  max: 450 },
      { x: 600,  z: 0, min: 520,  max: 690 },
      { x: 1000, z: 0, min: 900,  max: 1250 },
      { x: 1700, z: 0, min: 1600, max: 1780 },
      { x: 1900, z: 0, min: 1800, max: 2000 },
      { x: 2200, z: 0, min: 2100, max: 2380 },
    ],
    cup: { x: 2450, base: 0 },
  },
  { // ステージ4：夕焼けの草原。足場と穴はなくして、坂と丘にした
    name: "ステージ 4", width: 3000, deco: "meadow",
    sky: "#f4c6cf", ground: "#6b4a4f", grass: "#8fbf6a", pillar: "#8c646b", plat: "#a8767d", platTop: "#e58ea0", text: "#3d1f27",
    platforms: [],
    pits: [],
    hills: [
      { x: 0,    h: 0 },
      { x: 200,  h: 0 },
      { x: 420,  h: 130 },
      { x: 600,  h: 40 },
      { x: 820,  h: 190 },
      { x: 1020, h: 60 },
      { x: 1240, h: 0 },
      { x: 1460, h: 240 },
      { x: 1680, h: 90 },
      { x: 1900, h: 260 },
      { x: 2120, h: 100 },
      { x: 2340, h: 0 },
      { x: 2560, h: 0 },
      { x: 3000, h: 0 },
    ],
    enemies: [
      { x: 300,  z: 0, min: 200,  max: 550 },
      { x: 700,  z: 0, min: 620,  max: 880 },
      { x: 800,  z: 0, min: 640,  max: 880 },
      { x: 1100, z: 0, min: 1020, max: 1280 },
      { x: 1450, z: 0, min: 1430, max: 1560 },
      { x: 1750, z: 0, min: 1650, max: 1950 },
      { x: 2330, z: 0, min: 2310, max: 2380 },
      { x: 2600, z: 0, min: 2500, max: 2700 },
    ],
    cup: { x: 2750, base: 0 },
  },
  { // ステージ5：ラスト。夜の草原。足場と穴はなくして、坂と丘にした
    name: "ステージ 5", width: 3200, deco: "meadow",
    sky: "#0f1830", ground: "#2a2440", grass: "#5f8f6a", pillar: "#3a3358", plat: "#4a4275", platTop: "#7d6fd0", text: "#eceaff",
    platforms: [],
    pits: [],
    hills: [
      { x: 0,    h: 0 },
      { x: 180,  h: 0 },
      { x: 380,  h: 160 },
      { x: 560,  h: 50 },
      { x: 760,  h: 210 },
      { x: 960,  h: 70 },
      { x: 1160, h: 260 },
      { x: 1380, h: 100 },
      { x: 1600, h: 0 },
      { x: 1820, h: 280 },
      { x: 2060, h: 110 },
      { x: 2280, h: 300 },
      { x: 2520, h: 120 },
      { x: 2740, h: 0 },
      { x: 2960, h: 0 },
      { x: 3200, h: 0 },
    ],
    enemies: [
      { x: 200,  z: 0, min: 120,  max: 380 },
      { x: 480,  z: 0, min: 420,  max: 590 },
      { x: 530,  z: 0, min: 440,  max: 590 },
      { x: 900,  z: 0, min: 800,  max: 1100 },
      { x: 1250, z: 0, min: 1200, max: 1480 },
      { x: 1350, z: 0, min: 1270, max: 1440 },
      { x: 1850, z: 0, min: 1750, max: 2000 },
      { x: 2600, z: 0, min: 2530, max: 2620 },
    ],
    cup: { x: 2960, base: 0 },
  },
  { // ステージ6：ボス戦。巨大アールグレイ（カップはない。ボスを倒せばクリア）。ここも、なだらかな草原のアリーナに
    name: "ボス戦", width: 1900, boss: true, deco: "meadow",
    sky: "#2a1f3d", ground: "#3b2a4d", grass: "#7a6fae", pillar: "#54406e", plat: "#6a5390", platTop: "#b39ae6", text: "#f3ecff",
    platforms: [],
    pits: [],
    hills: [                                                   // 真ん中に、ボスより高い丘（衝撃波は地面基準でよけるので、高くてもズルはできない）。左右にも小さい丘
      { x: 0,    h: 0 },
      { x: 260,  h: 0 },
      { x: 480,  h: 80 },
      { x: 680,  h: 0 },
      { x: 950,  h: 200 },
      { x: 1220, h: 0 },
      { x: 1420, h: 80 },
      { x: 1640, h: 0 },
      { x: 1900, h: 0 },
    ],
    enemies: [],
    cup: null,
  },
];

// 敵の種類：抹茶のライバルの、抹茶以外のお茶たち（四角い体）
//   size=大きさ（主人公と同じ40） / speed=追いかける速さ / jump=ジャンプの強さ / body=体の色 / dark=ふちの色
const DRINKS = {
  kocha:   { name: "紅茶",     size: 40, speed: 90,  jump: 560, body: "#c4501f", dark: "#8f2f0e" },
  hojicha: { name: "ほうじ茶", size: 40, speed: 125, jump: 620, body: "#8b5a2b", dark: "#4d2c12" },
  earlgrey:{ name: "巨大アールグレイ", size: BOSS_SIZE, speed: 95, jump: 0, body: "#5b4b9a", dark: "#3a2f66" },
  oolong:  { name: "ウーロン茶", size: 40, speed: 65,  jump: 520, body: "#d19a2a", dark: "#8a5f14" },
};
const DRINK_ORDER = ["kocha", "hojicha", "oolong"];
const ENEMY_SIZE_MIN = 14;  // 敵の大きさのばらつき：小さいほう
const ENEMY_SIZE_MAX = 80;  // 敵の大きさのばらつき：大きいほう
const SIGHT_X = 340;        // 敵がプレイヤーに気づく横の距離
const SIGHT_Z = 220;        // 敵がプレイヤーに気づく高さの差
const PATROL_RATIO = 0.55;  // 気づいていないときの歩く速さ（追いかける速さに対する割合）
const ENEMY_JUMP_WAIT = 1.1;// 敵が続けてジャンプできるまでの秒数

// ステージのデータから、遊んでいる間に変わる敵の状態を作る
function stage_enemies(stage) {
  // x は中心の位置。vz は上向きの速さ。size は目指す大きさ（w・h は今の大きさ）。
  // dead は倒されてからの秒数（null なら生きている）。absorber は吸収した相手（主人公なら null）
  // 大きさは、ステージ側で指定（e.size）が無ければ、毎回ランダムにばらつかせる
  return stage.enemies.map((e, i) => {
    const type = e.type || DRINK_ORDER[i % DRINK_ORDER.length];
    const d = DRINKS[type];
    const size = e.size || Math.round(ENEMY_SIZE_MIN + Math.random() * (ENEMY_SIZE_MAX - ENEMY_SIZE_MIN));
    return { type, x: e.x, z: e.z, vz: 0, grounded: true, size, w: size, h: size, min: e.min, max: e.max,
             dir: 1, speed: d.speed, jump: d.jump, jumpWait: 0, chasing: false, fightCd: 0, dead: null, absorber: null, spr: { x: 0, v: 0 } };
  });
}

// ステージセレクトのワールド一覧（今はワールド1だけ遊べる。あとで増やせるように枠を残してある）
const WORLDS = [
  { title: "ステージ1", desc: "抹茶の大冒険", locked: false },
  { title: "ステージ2", desc: "近日公開", locked: true },
  { title: "ステージ3", desc: "近日公開", locked: true },
];

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
    const target = dx + 0.45 * tvx;                            // 先読みを強化：プレイヤーの、もう少し先の位置を狙う
    const move = Math.abs(target) < 8 ? 0 : (target > 0 ? 1 : -1);
    const pitAhead = move > 0 ? f[7] : move < 0 ? f[8] : 0;
    const above = p.z > en.z + 20 && Math.abs(dx) < 150;
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

  // スタートを押すたびに呼ばれる（初期化）。まずはステージセレクト画面を出す
  onStart() {
    this.playing = true;
    this.startedAt = performance.now();
    this.keys.left = this.keys.right = this.keys.jump = false;
    this.pointers.clear();
    this.touch.left = this.touch.right = this.touch.jump = false;
    this.mode = "select";
    this.worldRects = [];
  },

  // ステージセレクトで、ワールドが選ばれたら呼ばれる（今あるのは、1〜5＋ボス戦のワールド1だけ）
  beginPlay() {
    this.mode = "play";
    this.time = 0;
    this.pvx = 0;
    this.size = PLAYER_SIZE;                                   // 主人公が目指す大きさ（敵を吸収すると大きくなる）
    this.growPool = 0;                                         // 大きくなる量の端数（少しずつ貯まって、1を超えたら実際に大きくなる）
    this.loadStage(0);
  },

  // ステージセレクト画面のカードをタップ／クリックしたとき
  handleSelectTap(x, y) {
    for (const r of this.worldRects) {
      if (x >= r.x && x <= r.x + r.w && y >= r.y && y <= r.y + r.h) {
        if (!r.locked) this.beginPlay();
        return;
      }
    }
  },

  // ステージセレクト画面（キャンバスに描く）
  drawSelect(ctx, w, h) {
    ctx.fillStyle = "#cfe8f7";
    ctx.fillRect(0, 0, w, h);
    ctx.fillStyle = "#1c2433";
    ctx.textAlign = "center";
    ctx.font = "700 " + Math.round(Math.min(30, w * 0.06)) + "px 'Hiragino Sans', 'Yu Gothic', sans-serif";
    ctx.fillText("ステージをえらぼう", w / 2, h * 0.15);

    const n = WORLDS.length;
    const cardW = Math.min(200, (w - 48) / n - 16);
    const cardH = Math.min(240, h * 0.56);
    const gap = 16;
    const startX = w / 2 - (n * cardW + (n - 1) * gap) / 2;
    const cardY = h / 2 - cardH / 2;
    this.worldRects = [];

    WORLDS.forEach((wd, i) => {
      const x = startX + i * (cardW + gap);
      this.worldRects.push({ x, y: cardY, w: cardW, h: cardH, locked: wd.locked });
      ctx.save();
      const bob = wd.locked ? 0 : Math.sin(this.time * 2.4) * 4;
      ctx.globalAlpha = wd.locked ? 0.55 : 1;
      ctx.fillStyle = wd.locked ? "#d7dee8" : "#ffffff";
      ctx.beginPath();
      ctx.roundRect(x, cardY, cardW, cardH, 18);
      ctx.fill();
      ctx.strokeStyle = wd.locked ? "#b7c1cf" : "#6aa84f";
      ctx.lineWidth = 3;
      ctx.stroke();

      if (!wd.locked) {                                       // 小さな抹茶のアイコン（呼吸するように上下する）
        const cx = x + cardW / 2, cy = cardY + cardH * 0.42 + bob;
        ctx.fillStyle = "#6aa84f";
        ctx.beginPath();
        ctx.roundRect(cx - 26, cy - 24, 52, 48, 14);
        ctx.fill();
        ctx.fillStyle = "#fff";
        ctx.beginPath();
        ctx.ellipse(cx - 10, cy - 10, 7, 4, -0.5, 0, Math.PI * 2);
        ctx.fill();
        ctx.fillStyle = "#1c2433";
        ctx.fillRect(cx + 6, cy - 4, 9, 9);
      } else {
        ctx.fillStyle = "#8b97a8";
        ctx.font = "700 " + Math.round(cardH * 0.24) + "px 'Hiragino Sans', 'Yu Gothic', sans-serif";
        ctx.fillText("🔒", x + cardW / 2, cardY + cardH * 0.5);
      }

      ctx.fillStyle = "#1c2433";
      ctx.font = "700 " + Math.round(Math.min(19, cardW * 0.11)) + "px 'Hiragino Sans', 'Yu Gothic', sans-serif";
      ctx.fillText(wd.title, x + cardW / 2, cardY + cardH * 0.74);
      ctx.fillStyle = "#5d6b82";
      ctx.font = Math.round(Math.min(13, cardW * 0.08)) + "px 'Hiragino Sans', 'Yu Gothic', sans-serif";
      ctx.fillText(wd.desc, x + cardW / 2, cardY + cardH * 0.87);
      ctx.restore();
    });
  },

  // いまの大きさに応じたジャンプの強さ（ジャンプの高さが、ふつうの大きさのときの何倍かが「大きさ ×」で決まる）
  jumpPower() {
    return JUMP_SPEED * Math.pow(this.size / PLAYER_SIZE, 0.6);
  },

  // 敵を踏んだときの跳ね返りの強さ（大きくなるほど少しだけ弾みは強くなるが、画面外まで飛んでいかないよう上限あり）
  stompBouncePower() {
    return Math.min(this.jumpPower() * 0.65, JUMP_SPEED * 0.9);
  },

  // 主人公を大きくする（端数を貯めておいて、1を超えたら実際に大きさへ反映＝小さい敵を倒しても無駄にならず、でも一気に大きくはならない）
  growPlayer(amount) {
    this.growPool += amount;
    const inc = Math.floor(this.growPool);
    if (inc > 0) {
      this.growPool -= inc;
      this.size = Math.min(MAX_SIZE, this.size + inc);
    }
  },

  // 指定した x が落とし穴の上か
  inPit(x) {
    for (const pit of this.stage.pits) {
      if (x > pit.x && x < pit.x + pit.w) return true;
    }
    return false;
  },

  // 指定した x の地面の高さ（stage.hills が無いステージは、これまで通り平らな0）
  groundAt(x) {
    const hills = this.stage.hills;
    if (!hills || hills.length === 0) return 0;
    if (x <= hills[0].x) return hills[0].h;
    for (let i = 1; i < hills.length; i++) {
      const a = hills[i - 1], b = hills[i];
      if (x <= b.x) return a.h + (b.h - a.h) * ((x - a.x) / (b.x - a.x));
    }
    return hills[hills.length - 1].h;
  },

  // ステージを読み込む
  loadStage(index) {
    this.stageIndex = index;
    this.stage = STAGES[index];
    // 主人公：x は左端の位置、z は地面からの高さ、vz は上向きの速さ
    this.player = { x: 120, z: this.groundAt(120), vz: 0, w: this.size, h: this.size, speed: 240, facing: 1, grounded: true };
    this.jumpBuffer = 0;
    this.invuln = 0;                           // 無敵の残り秒数
    this.frags = [];                           // 分裂で飛び出した、拾えるかけら
    this.drops = [];                           // 飛び散るしずく（見た目だけ）
    this.spr = { x: 0, v: 0 };                 // 主人公のぷるぷる（バネ）。正=つぶれる／負=のびる
    this.dropTimer = 0;
    this.enemies = stage_enemies(this.stage);
    this.waves = [];                           // ボスの衝撃波
    this.bossWin = 0;                          // ボスを倒したあと、クリアまでの残り秒数
    this.shake = 0;                            // 画面のゆれ
    if (this.stage.boss) this.enemies.push(this.makeBoss());
    this.entering = null;                      // カップに入っている最中の情報
    this.banner = { t: 0 };                    // 「ステージ ○」の表示（text があればそれを出す）
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
    let floor = this.groundAt(p.x + p.w / 2);
    for (const pl of this.stage.platforms) {
      if (p.x + p.w > pl.x && p.x < pl.x + pl.w && pl.top <= p.z + 0.5 && pl.top > floor) floor = pl.top;
    }
    return floor;
  },

  // 毎フレーム呼ばれる（動きの計算）
  onUpdate(dt) {
    if (this.mode !== "play") { this.time += dt; return; }   // ステージセレクト画面の間は、ゲームの中身を動かさない
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
      p.z = e.fromZ + (this.groundAt(stage.cup.x) + stage.cup.base + 30 - e.fromZ) * k;
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
        p.vz = this.jumpPower();
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
        else {
          const floorZ = this.groundAt(p.x + p.w / 2);           // 坂・丘の地面（無いステージは平らな0）
          if (p.z <= floorZ && !this.inPit(p.x + p.w / 2)) { p.z = floorZ; p.vz = 0; grounded = true; }
        }
      }
      p.grounded = grounded;
      if (!wasGrounded && grounded && fallSpeed < -150) {      // 着地：ぺちゃっとつぶれてしぶきが飛ぶ
        const k = Math.min(1, -fallSpeed / 700);
        this.kick(this.spr, 3 + k * 9);
        this.spawnDrops(p.x + p.w / 2, p.z + 2, PLAYER_COLOR, Math.round(3 + k * 6), 90 + k * 140);
      }

      // 落とし穴に落ちたらミス
      // （キャラが画面の下に完全に見えなくなってから、やられる）
      if (p.z < -(this.shell.height * 0.2) - p.h - 12 + this.cameraY) { this.loseLife(); return; }

      const pcx = p.x + p.w / 2;

      // 敵：近づくと追いかけてきてジャンプもする。上から踏めば倒せる。横などから触れるとミス
      if (this.invuln > 0) this.invuln -= dt;
      this.enemies = this.enemies.filter((e) => e.dead === null || e.dead <= ABSORB_TIME);   // 消えた敵を取り除く
      this.enemyFights();
      for (const en of this.enemies) {
        if (en.dead !== null) { en.dead += dt; continue; }
        // 大きさをなめらかに変える。ボスは、サイズの数値(en.size)より、見た目・当たり判定(en.w/en.h)のほうが大きい
        const growTarget = en.boss ? en.size / BOSS_DATA_SCALE : en.size;
        if (Math.abs(en.w - growTarget) > 0.05) {
          en.w = en.h = en.w + (growTarget - en.w) * Math.min(1, dt * 10);
        }
        const prevEnZ = en.z;                                  // 敵の、この処理直前の高さ（上から降ってきたか判定に使う）
        if (en.boss) this.updateBoss(en, dt, p, pcx);
        else this.moveEnemy(en, dt, pcx, p);
        if (en.dead !== null) continue;                       // 穴に落ちた
        const hitX = Math.abs(en.x - pcx) < (p.w + en.w) / 2 - 8;
        const hitZ = p.z < en.z + en.h && p.z + p.h > en.z + 4;
        if (!hitX || !hitZ) continue;
        if (fallSpeed < 0 && prevZ >= en.z + en.h * (en.boss ? BOSS_STOMP : 0.5)) {
          // 踏んだ！吸収が起きるのは、大きいほうが小さいほうを攻撃したときだけ
          if (en.boss) {
            if (en.stun <= 0) this.damageBoss(en);   // ボスは吸収できない。踏むとダメージ（目を回している間は入らない）
          } else if (this.size > en.size + 0.5) {
            en.dead = 0;                                      // 自分のほうが大きい：敵を吸収して大きくなる
            en.absorber = null;
            this.spawnDrops(en.x, en.z + en.h / 2, DRINKS[en.type].body, 12, 260);   // 敵がはじけてしぶきになる
            this.spawnDrops(pcx, p.z, PLAYER_COLOR, 4, 160);
            this.kick(this.spr, 8);
            this.growPlayer(en.size * GROW_RATIO);
          } else if (en.fightCd <= 0) {
            this.splitEnemy(en);                              // 同じ大きさか大きい敵：吸収できず、敵が分裂する
            en.fightCd = FIGHT_COOLDOWN;                       // 直後にもう一度踏んでも、連続で分裂はしない
          }
          p.vz = this.stompBouncePower();
          p.grounded = false;
          p.z = Math.max(p.z, en.z + en.h + 1);               // 敵の上に乗せて、すぐ横から当たらないようにする
          if (!en.boss) this.invuln = Math.max(this.invuln, 0.4);   // （ボスの上で跳ね続けても無敵にならないように、ボスのときはつけない）
        } else if (!en.boss && en.vz < 0 && prevEnZ >= p.z + p.h * 0.5 && this.invuln <= 0) {
          // 敵の方が上から降ってきて当たった：自分が大きくても必ず何かしら負けるが、
          //   相手が明らかに大きいときは（横から当たったときと同じく）完全に吸収されてミス
          const enWasBigger = en.size > this.size + 0.5;
          en.size = Math.min(ENEMY_MAX, en.size + this.size * 0.15);
          this.spawnDrops(pcx, p.z + p.h / 2, PLAYER_COLOR, 16, 280);
          this.spawnDrops(en.x, en.z + en.h / 2, DRINKS[en.type].body, 8, 200);
          this.kick(en.spr, 8);
          if (!enWasBigger && this.size > MIN_SIZE) { this.splitPlayer(); continue; }
          this.loseLife();
          return;
        } else if (this.invuln <= 0 && !(en.boss && en.stun > 0)) {   // ボスが目を回している間は、横から当たってもだいじょうぶ
          if (!en.boss && this.size > en.size + 0.5) {
            // 横から当たっても、自分の方が大きい敵になら勝つ（踏んだときと同じく吸収して大きくなる）
            en.dead = 0;
            en.absorber = null;
            this.spawnDrops(en.x, en.z + en.h / 2, DRINKS[en.type].body, 12, 260);
            this.spawnDrops(pcx, p.z, PLAYER_COLOR, 4, 160);
            this.kick(this.spr, 8);
            this.growPlayer(en.size * GROW_RATIO);
            this.invuln = Math.max(this.invuln, 0.4);
            continue;
          }
          if (en.boss && this.size > en.size + 0.5) {
            // ボス戦でも、横から当たって自分の方が大きければ、踏んだときと同じくダメージが入る
            this.damageBoss(en);
            this.spawnDrops(pcx, p.z, PLAYER_COLOR, 4, 160);
            this.kick(this.spr, 8);
            this.invuln = Math.max(this.invuln, 0.4);
            continue;
          }
          if (en.size > this.size + 0.5) {
            // 自分より大きい敵に当たった：吸収されてミス。敵は大きくなる
            if (!en.boss) en.size = Math.min(ENEMY_MAX, en.size + this.size * 0.15);
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
      if (this.updateBossFx(dt, p, pcx)) return;

      // ゴール判定：カップの真上あたりで、カップと同じ高さにいたら「入った」（base は、カップの足元の地面からの高さ）
      const cup = stage.cup;
      const cx = p.x + p.w / 2;
      if (cup) {
        const cupZ = this.groundAt(cup.x) + cup.base;
        if (Math.abs(cx - cup.x) <= 26 && p.z >= cupZ - 2 && p.z <= cupZ + 30) {
          this.entering = { t: 0, fromX: p.x, fromZ: p.z };
        }
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
      if (o === en || o.dead !== null || o.boss) continue;
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
        if (a.boss || b.boss || a.dead !== null || b.dead !== null || a.fightCd > 0 || b.fightCd > 0) continue;
        if (Math.abs(a.x - b.x) >= (a.w + b.w) / 2 - 4) continue;
        if (!(a.z < b.z + b.h - 4 && b.z < a.z + a.h - 4)) continue;
        a.fightCd = b.fightCd = FIGHT_COOLDOWN;
        if (Math.abs(a.size - b.size) < 0.5) {
          this.splitEnemy(a);
          this.splitEnemy(b);
        } else {
          const big = a.size > b.size ? a : b, small = big === a ? b : a;
          big.size = Math.min(ENEMY_MAX, big.size + small.size * 0.3);
          small.dead = 0;
          small.absorber = big;
          this.spawnDrops(small.x, small.z + small.h / 2, DRINKS[small.type].body, 10, 200);
          this.kick(big.spr, 6);
        }
      }
    }
  },

  // 敵の分裂：本体は小さくなるだけ。失った分は、その敵の色をした「かけら」になって2つ、近くに飛び出す
  //   （プレイヤーの分裂と同じ仕組み＝かけらは追ってこない。拾えば、プレイヤーが少し大きくなれる。小さすぎる敵は分裂しない）
  splitEnemy(e) {
    e.vz = 250; e.grounded = false;
    this.kick(e.spr, 8);
    const color = DRINKS[e.type].body;
    this.spawnDrops(e.x, e.z + e.h / 2, color, 8, 220);
    if (e.size < ENEMY_SPLIT_MIN) return;
    const keep = Math.round(e.size * SPLIT_KEEP);
    const mass = (e.size - keep) / 2;                          // かけら1つぶんの大きさ
    const cx = e.x, cz = e.z + e.h / 2;
    e.size = keep;
    for (const dir of [-1, 1]) {
      this.frags.push({ x: cx, z: cz, vx: dir * (220 + Math.random() * 120), vz: 380 + Math.random() * 80, mass, s: mass * 1.6 + 4, t: 0, color });
    }
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
      const hasEdgeFoe = en.z > foe.z + 30;                   // 相手の敵より高い場所にいる＝上から狙えるチャンス
      if (hasEdgeFoe) speed *= 1.5;
      lo = 0; hi = stage.width;                               // 追いかけているあいだは、なわばりの外まで（ステージの端まで）ずっと追える
      // 相手の敵に向かって、AI（なければルール）が動きとジャンプを決める（避けジャンプはしない）
      const d = this.decideEnemy(en, { z: foe.z, vz: foe.vz, grounded: true }, foe.x, 0);
      if (foe.size > en.size + 0.5 && !hasEdgeFoe) {
        move = foe.x > en.x ? -1 : 1;                         // 相手のほうが大きいときは、ふだんは逃げる
      } else {
        move = d.move;                                        // チャンス（高い場所）があるときは、逃げずに攻めにいく
      }
      wantJump = d.jump;
      if (move !== 0) en.dir = move;
    } else if (seesPlayer) {
      speed = en.speed;
      const hasEdge = en.z > p.z + 30;                        // 高い場所にいる＝上から狙えるチャンス（上から当たれば大きさに関係なく勝てる）
      if (hasEdge) speed *= 1.5;                              // 高い場所にいるときは、勢いよく降りて上から仕掛ける
      lo = 0; hi = stage.width;                               // 追いかけているあいだは、なわばりの外まで（ステージの端まで）ずっと追える
      const d = this.decideEnemy(en, p, pcx, this.pvx);      // AI（なければルール）が動きとジャンプを決める
      if (this.size > en.size + 0.5 && !hasEdge) {
        move = dx > 0 ? -1 : 1;                               // 自分より大きいプレイヤーからは、ふだんは追いかけずに逃げる
      } else {
        move = d.move;                                        // チャンス（高い場所）があるときは、逃げずに攻めにいく
      }
      wantJump = d.jump;
      if (move !== 0) en.dir = move;
    } else {
      if (en.x <= en.min) en.dir = 1;
      if (en.x >= en.max) en.dir = -1;
      move = en.dir;
    }

    // 横に動く（地面にいるときは、落とし穴には入らずに手前で止まる）
    // ※ lo/hi は行動（追う／戦う／歩き回る）によって毎フレーム変わりうるので、
    //   すでに範囲の外にいるときにここで一気に戻すと「ワープ」になってしまう。
    //   なので、まだ範囲の内側にいるときだけ「これ以上外に出ない」ように止める（外に出ていたら、ふつうに歩いて戻ってくる）
    let nx = en.x + move * speed * dt;
    if (en.x <= hi) nx = Math.min(nx, hi);
    if (en.x >= lo) nx = Math.max(nx, lo);
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
      else {
        const floorZ = this.groundAt(en.x);                      // 坂・丘の地面（無いステージは平らな0）
        if (en.z <= floorZ && !this.inPit(en.x)) { en.z = floorZ; en.vz = 0; en.grounded = true; }
      }
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
        if (d.z <= this.groundAt(d.x) && !this.inPit(d.x)) return false;
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
      this.frags.push({ x: cx, z: cz, vx: dir * (220 + Math.random() * 120), vz: 380 + Math.random() * 80, mass, s: mass * 1.6 + 4, t: 0 });
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
      f.vx *= Math.pow(0.62, dt);                               // 横の勢いはだんだん弱まる
      f.vz -= GRAVITY * dt;
      f.z += f.vz * dt;
      if (f.vz <= 0) {
        let landTop = -1;
        for (const pl of stage.platforms) {
          if (f.x > pl.x && f.x < pl.x + pl.w && prevZ >= pl.top - 0.5 && f.z <= pl.top && pl.top > landTop) landTop = pl.top;
        }
        if (landTop < 0 && !this.inPit(f.x)) { const fz = this.groundAt(f.x); if (f.z <= fz) landTop = fz; }
        if (landTop >= 0) {
          f.z = landTop;
          if (f.vz < -200) this.spawnDrops(f.x, f.z, PLAYER_COLOR, 3, 110);
          f.vz = f.vz < -200 ? -f.vz * 0.35 : 0;              // 弾む
          f.vx *= 0.75;
        }
      }
      if (f.z < -20 && this.inPit(f.x)) return false;          // 穴に落ちたら、すぐ消える
      if (f.t <= FRAG_DELAY) return true;
      if (Math.abs(f.x - pcx) < (p.w + f.s) / 2 && p.z < f.z + f.s && p.z + p.h > f.z) {
        this.growPlayer(f.mass * KAKERA_GROW_RATIO);            // 拾うと大きくなるが、敵をまるごと吸収するときより控えめ
        this.kick(this.spr, 4);
        this.spawnDrops(f.x, f.z + f.s / 2, PLAYER_COLOR, 4, 110);
        return false;
      }
      for (const en of this.enemies) {                         // かけらは、ボスや敵も拾って大きくなれる
        if (en.dead !== null) continue;
        if (Math.abs(f.x - en.x) < (en.w + f.s) / 2 && en.z < f.z + f.s && en.z + en.h > f.z) {
          en.size = en.boss ? en.size + f.mass : Math.min(ENEMY_MAX, en.size + f.mass);
          this.kick(en.spr, 4);
          this.spawnDrops(f.x, f.z + f.s / 2, DRINKS[en.type].body, 4, 110);
          return false;
        }
      }
      return true;
    });
  },

  // ---- ボス：巨大アールグレイ -------------------------------------------------
  // 敵を1体つくる（ボスがはなつ子分にも使う）
  makeEnemy(type, x, z, size, min, max) {
    const d = DRINKS[type];
    return { type, x, z, vz: 0, grounded: true, size, w: size, h: size, min, max, dir: 1, speed: d.speed, jump: d.jump,
             jumpWait: 0, chasing: false, fightCd: 0, dead: null, absorber: null, spr: { x: 0, v: 0 } };
  },
  makeBoss() {
    const b = this.makeEnemy("earlgrey", this.stage.width - 320, 0, BOSS_SIZE, 0, this.stage.width);
    b.boss = true; b.hp = BOSS_HP; b.phase = "walk"; b.t = 0; b.stun = 0; b.flash = 0; b.vx = 0; b.dir = -1; b.chasing = true;
    b.form = 1; b.headT = 0; b.blast = 0; b.blastHit = false; b.combo = 0; b.next = "jump";
    b.size = BOSS_SIZE * BOSS_DATA_SCALE;                     // 見た目・当たり判定(w/h)はBOSS_SIZEのまま、サイズの数値だけ小さく
    return b;
  },

  // ボスの色（第二形態は赤紫）
  bossColor(en) { return en.form === 2 ? BOSS_COLOR_2 : DRINKS.earlgrey.body; },

  // ボスの動き：追いかける → かがむ → 大ジャンプ → 着地で衝撃波（ダメージを受けるほど速くなる）
  // 第二形態は、さらに速く、連続ジャンプと速い衝撃波を使う。頭の上にいすわると、ふりはらわれる
  updateBoss(en, dt, p, pcx) {
    this.stepSpring(en.spr, dt);
    en.flash = Math.max(0, en.flash - dt);
    en.chasing = true;
    if (en.blast > 0) en.blast -= dt;
    const f2 = en.form === 2;
    const col = this.bossColor(en);
    if (f2 && Math.random() < 0.25) this.spawnDrops(en.x + (Math.random() - 0.5) * en.w, en.z + en.h, "#ff7a3d", 1, 50);   // 炎
    const airPhases = en.phase === "air" || en.phase === "slamUp" || en.phase === "slamHover" || en.phase === "slamFall";
    const floorZ = this.groundAt(en.x);                        // 坂・丘の地面（無いステージは平らな0）
    if (!airPhases && (en.z > floorZ || en.vz !== 0)) {         // 空中で踏まれたときは、落ちてくる
      en.vz -= GRAVITY * dt;
      en.z += en.vz * dt;
      if (en.z <= floorZ) { en.z = floorZ; en.vz = 0; en.grounded = true; }
    }

    // 頭の上にいすわられたら、ふりはらう（予告 → 真上への衝撃）
    const above = Math.abs(pcx - en.x) < en.w / 2 + 20 && p.z >= en.z + en.h * 0.6 && p.z <= en.z + en.h + 260;
    en.headT = above ? en.headT + dt : Math.max(0, en.headT - dt * 2);
    if (en.phase !== "shake" && !airPhases && en.headT >= SHAKE_HEAD) { en.phase = "shake"; en.t = 0; en.headT = 0; }
    if (en.phase === "shake") {
      en.t += dt;
      en.spr.x = 0.5 * Math.min(1, en.t / SHAKE_TELE);
      en.spr.v = 0;
      if (en.t >= SHAKE_TELE) {
        en.blast = BLAST_TIME; en.blastHit = false;
        en.phase = "walk"; en.t = 0;
        this.kick(en.spr, -14);
        this.shake = 0.4;
        this.spawnDrops(en.x, en.z + en.h, col, 30, 360);
      }
      return;
    }

    if (en.stun > 0) {                                        // 踏まれて目を回している
      en.stun -= dt;
      en.phase = "walk"; en.t = 0;
      return;
    }
    const rage = (f2 ? 1.15 : 0.9) + ((BOSS_HP - en.hp) / BOSS_HP) * 0.3;   // 体力が減るほど速くなる（HPの数が変わっても、増え方は同じにする）
    const half = en.w / 2;
    en.t += dt;
    if (en.phase === "walk") {
      en.dir = pcx >= en.x ? 1 : -1;
      en.x = Math.max(half, Math.min(this.stage.width - half, en.x + en.dir * en.speed * rage * dt));
      en.z = this.groundAt(en.x);                             // 坂を歩いても、地面の高さにそのまま合わせる
      if (en.t > 2.0 / rage) {                                // 次の攻撃：ジャンプか、突進か、滞空
        en.phase = "wind"; en.t = 0;
        en.dir = pcx >= en.x ? 1 : -1;
        const r = Math.random();
        en.next = r < (f2 ? 0.3 : 0.18) ? "charge" : r < (f2 ? 0.55 : 0.35) ? "slam" : "jump";
      }
    } else if (en.phase === "wind") {                         // かがんで力をためる
      en.spr.x = 0.5 * Math.min(1, en.t / 0.45);
      en.spr.v = 0;
      if (en.t > 0.45) {
        if (en.next === "charge") { en.phase = "charge"; en.t = 0; this.shake = 0.2; return; }   // 突進（向きは予告のときに決まっている）
        if (en.next === "slam") {                             // 滞空技：真上に高く跳んで、しばらく浮いてから落ちてくる
          en.phase = "slamUp"; en.t = 0;
          en.vz = SLAM_UP_SPEED; en.grounded = false;
          en.vx = Math.max(-300, Math.min(300, (pcx - en.x) / 1.2));
          this.kick(en.spr, -18);
          this.shake = 0.25;
          return;
        }
        en.phase = "air"; en.t = 0;
        en.vz = 820; en.grounded = false;
        en.vx = Math.max(-420, Math.min(420, (pcx - en.x) / 0.9));   // プレイヤーの位置を狙って跳ぶ
        this.kick(en.spr, -14);
      }
    } else if (en.phase === "charge") {                       // 突進：一直線に走る。壁にぶつかるまで止まらない
      const spd = f2 ? 560 : 450;
      en.x += en.dir * spd * dt;
      en.z = this.groundAt(en.x);                             // 坂を突進しても、地面の高さにそのまま合わせる
      en.spr.x = -0.15;
      if (Math.random() < 0.7) this.spawnDrops(en.x - en.dir * half, 6, col, 1, 120);
      if (en.x <= half || en.x >= this.stage.width - half || en.t > 1.4) {
        en.x = Math.max(half, Math.min(this.stage.width - half, en.x));
        en.phase = "walk"; en.t = -0.3;
        this.kick(en.spr, 14);
        this.shake = 0.4;
        this.spawnDrops(en.x + en.dir * half, 40, col, 20, 320);
      }
    } else if (en.phase === "air") {                          // 空中（ふつうのジャンプ）
      en.x = Math.max(half, Math.min(this.stage.width - half, en.x + en.vx * dt));
      en.vz -= GRAVITY * dt;
      en.z += en.vz * dt;
      const land = this.groundAt(en.x);
      if (en.z <= land) {                                     // 着地：衝撃波が左右に走る
        en.z = land; en.vz = 0; en.grounded = true;
        en.phase = "walk"; en.t = 0;
        this.kick(en.spr, 16);
        this.shake = 0.35;
        this.spawnDrops(en.x, land + 4, col, 24, 380);
        const speeds = f2 ? [280, 400] : [280];                // 速さのちがう波が続けて走る（第二形態は2本）
        for (const dir of [-1, 1]) {
          for (const sp of speeds) this.waves.push({ x: en.x + dir * half * 0.8, dir, life: 2.4, speed: sp, color: col });
        }
        if (en.combo < (f2 ? 1 : 0) && Math.random() < 0.5) {  // すぐにもう一度跳ぶことがある（第二形態のみ、1回まで）
          en.combo++; en.phase = "wind"; en.t = 0.2; en.next = "jump";
        } else {
          en.combo = 0;
        }
      }
    } else if (en.phase === "slamUp") {                       // 滞空技：真上に高く跳んでいく
      en.x = Math.max(half, Math.min(this.stage.width - half, en.x + en.vx * dt));
      en.vz -= GRAVITY * dt;
      en.z += en.vz * dt;
      en.vx *= Math.pow(0.3, dt);                             // 横の勢いはすぐに弱まる（頂上ではほぼ真下に落ちる）
      if (en.vz <= 0) { en.phase = "slamHover"; en.t = 0; en.vz = 0; }   // 頂上に着いたら、静止して浮く
    } else if (en.phase === "slamHover") {                    // 滞空技：頂上でしばらく静止する（真下に着地の予告が出る）
      en.vz = 0;
      en.spr.x = Math.sin(en.t * 6) * 0.06;                   // ゆらゆら浮いているような揺れ
      if (Math.random() < 0.3) this.spawnDrops(en.x + (Math.random() - 0.5) * en.w * 0.6, en.z, col, 1, 40);
      if (en.t > (f2 ? SLAM_HOVER * 0.7 : SLAM_HOVER)) { en.phase = "slamFall"; en.t = 0; }
    } else if (en.phase === "slamFall") {                     // 滞空技：真下へ、ふつうより速く落ちる
      en.vz -= GRAVITY * SLAM_FALL_MULT * dt;
      en.z += en.vz * dt;
      const land = this.groundAt(en.x);
      if (en.z <= land) {                                     // 着地：大きな衝撃と、いつもより多い衝撃波
        en.z = land; en.vz = 0; en.grounded = true;
        en.phase = "walk"; en.t = 0; en.combo = 0;
        this.kick(en.spr, 24);
        this.shake = 0.6;
        this.spawnDrops(en.x, land + 4, col, 40, 480);
        const speeds = f2 ? [280, 420, 560] : [280, 420];
        for (const dir of [-1, 1]) {
          for (const sp of speeds) this.waves.push({ x: en.x + dir * half * 0.6, dir, life: 2.6, speed: sp, color: col });
        }
      }
    }
  },

  // 踏まれた：体力が減って本体が小さくなり、失った分だけ、ボスの色をした「かけら」が2つ近くに飛び出す。体力が0なら撃破
  //   （敵の分裂・プレイヤーの分裂と同じ仕組み＝かけらは追ってこない。拾えば、プレイヤーが少し大きくなれる）
  // 踏まれた：体力が減って本体が小さくなり、失った分の半分は「かけら」に、半分は「子分」になって、左右に飛び出す
  //   （かけらはプレイヤーだけでなく、ほかの敵やボス自身も拾って大きくなれる。子分はこれまで通り歩いて襲ってくる）
  damageBoss(en) {
    en.hp--;
    if (en.form === 1 && en.hp <= BOSS_HP / 2) { this.transformBoss(en); return; }   // 体力が半分になったら第二形態
    en.stun = BOSS_STUN;
    en.flash = 0.35;
    const col = this.bossColor(en);
    const oldSize = en.size;
    const baseSize = en.form === 2 ? BOSS_SIZE_2 : BOSS_SIZE;
    en.size = (baseSize * (1 - (1 - BOSS_SHRINK) * (BOSS_HP - Math.max(0, en.hp)) / BOSS_HP)) * BOSS_DATA_SCALE;
    this.kick(en.spr, 12);
    this.shake = 0.3;
    this.spawnDrops(en.x, en.z + en.h * 0.6, col, 22, 340);
    if (en.hp <= 0) {                                         // 撃破！
      this.bossWin = 2.0;
      this.invuln = 99;
      en.dead = 0; en.absorber = null;
      this.waves = [];
      for (const o of this.enemies) {                         // 子分もかけらもはじける
        if (o !== en && o.dead === null) { o.dead = 0; o.absorber = null; this.spawnDrops(o.x, o.z + o.h / 2, DRINKS[o.type].body, 8, 240); }
      }
      this.spawnDrops(en.x, en.z + en.h / 2, col, 60, 520);
      return;
    }
    const cz = en.z + en.h / 2;
    const mass = (oldSize - en.size) / 2;                      // かけらの大きさ＝失った分の半分
    this.frags.push({ x: en.x, z: cz, vx: -(220 + Math.random() * 120), vz: 380 + Math.random() * 80, mass, s: mass * 1.6 + 4, t: 0, color: col });
    const types = ["kocha", "hojicha", "oolong"];              // もう半分は、子分になって反対側へ
    const type = types[Math.floor(Math.random() * types.length)];
    const max = this.stage.width - 30;
    const m = this.makeEnemy(type, Math.max(30, Math.min(max, en.x + en.w * 0.6)), en.z + 20, 30, 30, max);
    m.grounded = false; m.vz = 520; m.dir = 1; m.fightCd = FIGHT_COOLDOWN;
    this.enemies.push(m);
  },

  // 第二形態：体力が全回復して、大きく、赤紫になる。子分は消える
  transformBoss(en) {
    en.form = 2;
    en.hp = BOSS_HP;
    en.size = BOSS_SIZE_2 * BOSS_DATA_SCALE;
    en.stun = 2.2;                                            // 変身中は、こちらもボスも動けない
    en.flash = 1.2;
    en.phase = "walk"; en.t = 0; en.combo = 0; en.headT = 0;
    this.kick(en.spr, 16);
    this.shake = 0.8;
    this.waves = [];
    this.banner = { t: 0, text: "第二形態！" };
    this.spawnDrops(en.x, en.z + en.h / 2, BOSS_COLOR_2, 50, 480);
    this.spawnDrops(en.x, en.z + en.h / 2, "#ff7a3d", 30, 380);
    for (const o of this.enemies) {                           // 子分ははじけて消える
      if (o !== en && o.dead === null) { o.dead = 0; o.absorber = null; this.spawnDrops(o.x, o.z + o.h / 2, DRINKS[o.type].body, 8, 240); }
    }
  },

  // 衝撃波の動きと当たり／ボスを倒したあとの演出。ゲームを終えたら true
  updateBossFx(dt, p, pcx) {
    this.shake = Math.max(0, this.shake - dt);
    if (this.bossWin > 0) {
      this.bossWin -= dt;
      if (Math.random() < 0.6) this.spawnDrops(p.x + Math.random() * 300 - 100, 40 + Math.random() * 60, DRINKS.earlgrey.body, 3, 200);
      if (this.bossWin <= 0) {
        this.shell.end("巨大アールグレイをたおした！ぜんぶクリア！おめでとう！");
        return true;
      }
      return false;
    }
    this.waves = this.waves.filter((w) => {
      w.x += w.dir * (w.speed || 300) * dt;
      w.life -= dt;
      return w.life > 0 && w.x > -20 && w.x < this.stage.width + 20;
    });
    if (this.invuln <= 0) {
      for (const w of this.waves) {
        if (p.z < this.groundAt(pcx) + 26 && Math.abs(w.x - pcx) < p.w / 2 + 12) {  // 地面すれすれを走る。ジャンプでよける
          this.spawnDrops(pcx, p.z + p.h / 2, PLAYER_COLOR, 10, 220);
          if (this.size > MIN_SIZE) { this.splitPlayer(); return false; }   // ボスの特殊攻撃は、分裂するだけ（これ以上小さくなれないときだけミス）
          this.loseLife();
          return true;
        }
      }
    }
    const bs = this.enemies.find((e) => e.boss && e.blast > 0 && !e.blastHit && e.dead === null);
    if (bs && this.invuln <= 0 && Math.abs(pcx - bs.x) < bs.w / 2 + 50 && p.z < bs.z + bs.h + 260) {
      bs.blastHit = true;                                     // ふりはらいの衝撃：頭の上にいるとダメージ＋はじき飛ばされる
      this.spawnDrops(pcx, p.z + p.h / 2, PLAYER_COLOR, 14, 300);
      if (this.size > MIN_SIZE) { this.splitPlayer(); return false; }   // ボスの特殊攻撃は、分裂するだけ（これ以上小さくなれないときだけミス）
      this.loseLife();
      return true;
    }
    return false;
  },

  // ミス：やられたら、そこで終わり。結果画面の「もう一度」で、ステージセレクトからやりなおす
  loseLife() {
    const label = this.stage.boss ? "ボス戦" : "ステージ " + (this.stageIndex + 1);
    this.shell.end("やられた…（" + label + " で力つきた）");
  },

  // ゴールしたあと：次のステージへ、最後ならクリア
  goNext() {
    if (this.stageIndex < STAGES.length - 1) {
      this.loadStage(this.stageIndex + 1);
    } else {
      this.entering = null;
      this.shell.end("全ステージクリア！おめでとう！");
    }
  },

  // 毎フレーム呼ばれる（描画）
  onDraw(ctx, w, h) {
    if (this.mode !== "play") { this.drawSelect(ctx, w, h); return; }   // ステージセレクト画面
    const stage = this.stage;
    const groundY = h * 0.8;   // 地面の高さ（画面の上から8割のところ）
    const p = this.player;
    const cam = this.cameraX;

    // 空
    ctx.fillStyle = stage.sky;
    ctx.fillRect(0, 0, w, h);
    if (stage.deco === "meadow") this.drawMeadowSky(ctx, w, h, groundY, cam);

    ctx.save();
    const sh = this.shake > 0 ? this.shake * 24 : 0;     // ボスの着地・被弾で画面がゆれる
    ctx.translate(-cam + (Math.random() - 0.5) * sh, this.cameraY + (Math.random() - 0.5) * sh);    // ここから先は「ステージの座標」で描く

    // 地面（見えている範囲だけ）
    if (stage.hills) {
      // 坂・丘のステージ：地形の高さに沿って地面を描く（落とし穴は、いまのところ使っていない）
      const step = 16;
      const from = Math.floor(cam / step) * step, to = cam + w + step;
      ctx.fillStyle = stage.ground;
      ctx.beginPath();
      ctx.moveTo(from, groundY + 2000);
      for (let x = from; x <= to; x += step) {
        ctx.lineTo(x, groundY - this.groundAt(Math.max(0, Math.min(stage.width, x))));
      }
      ctx.lineTo(to, groundY + 2000);
      ctx.closePath();
      ctx.fill();
    } else {
      // （落とし穴のところは地面を描かない）
      ctx.fillStyle = stage.ground;
      let gx = 0;
      const drawGround = (from, to) => {
        const a = Math.max(from, cam), b = Math.min(to, cam + w);
        if (b > a) ctx.fillRect(a, groundY, b - a, 2000);
      };
      for (const pit of stage.pits) { drawGround(gx, pit.x); gx = pit.x + pit.w; }
      drawGround(gx, stage.width);
    }
    if (stage.deco === "meadow") this.drawMeadowGround(ctx, stage, cam, w, groundY);

    // 目印の柱（200pxごと）。カメラが動いているのが分かるようにする
    const first = Math.floor(cam / 200) * 200;
    for (let x = first; x <= cam + w; x += 200) {
      if (this.inPit(x + 5)) continue;
      const gy = groundY - this.groundAt(x);
      if (stage.deco === "meadow") this.drawFencePost(ctx, x, gy, stage);
      else { ctx.fillStyle = stage.pillar; ctx.fillRect(x, gy - 70, 10, 70); }
    }

    // ステージの左端と右端の壁
    ctx.fillStyle = stage.pillar;
    ctx.fillRect(-20, groundY - 600, 20, 600);
    ctx.fillRect(stage.width, groundY - 600, 20, 600);

    // 足場
    for (const pl of stage.platforms) {
      if (pl.x + pl.w < cam || pl.x > cam + w) continue;
      const y = groundY - pl.top;
      if (stage.deco === "meadow") { this.drawMound(ctx, pl, y, stage); continue; }
      ctx.fillStyle = stage.plat;
      ctx.fillRect(pl.x, y, pl.w, 20);
      ctx.fillStyle = stage.platTop;
      ctx.fillRect(pl.x, y, pl.w, 6);
    }

    // 草原の飾り（木・ちょうちょ）。ここで描くのは、地面の高さより上にある物の後ろに隠れないように
    if (stage.deco === "meadow") this.drawMeadowFx(ctx, stage, cam, w, groundY);

    // ゴールのティーカップ
    if (stage.cup) this.drawCup(ctx, stage.cup.x, groundY - this.groundAt(stage.cup.x) - stage.cup.base, stage);

    // 敵（踏まれたら主人公に吸収される）
    for (const en of this.enemies) {
      if (en.dead !== null && en.dead > ABSORB_TIME) continue;
      if (en.x + en.w < cam || en.x - en.w > cam + w) continue;
      this.drawDrink(ctx, en, groundY, p);
      if (en.boss) this.drawBossFx(ctx, en, groundY);
    }

    // かけら（しずくの形。消える直前はちかちかする）
    for (const f of this.frags) {
      if (f.x + f.s < cam || f.x - f.s > cam + w) continue;
      if (f.t > FRAG_LIFE - 2 && Math.floor(f.t * 8) % 2 === 0) continue;
      const fq = Math.max(-0.4, Math.min(0.5, -Math.min(0.3, Math.abs(f.vz) / 1800) + Math.sin(f.t * 14) * 0.12 * Math.max(0, 1 - f.t * 1.5)));
      ctx.save();
      ctx.translate(f.x, groundY - f.z);
      ctx.scale(1 + fq * 0.6, 1 - fq * 0.7);
      ctx.fillStyle = f.color || PLAYER_COLOR;                  // プレイヤーのかけらは抹茶色、敵のかけらは、その敵の色
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

    // ボスの衝撃波（地面をはう波。ジャンプでよける）
    for (const wv of this.waves) {
      const wgy = groundY - this.groundAt(wv.x);                // 坂・丘の高さに沿わせる
      ctx.fillStyle = wv.color || DRINKS.earlgrey.body;
      ctx.globalAlpha = Math.min(1, wv.life / 0.4);
      ctx.beginPath();
      ctx.ellipse(wv.x, wgy, 22, 30, 0, Math.PI, Math.PI * 2);
      ctx.fill();
      ctx.fillStyle = "rgba(255, 255, 255, 0.3)";
      ctx.beginPath();
      ctx.ellipse(wv.x - wv.dir * 4, wgy - 12, 6, 10, 0, 0, Math.PI * 2);
      ctx.fill();
    }
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
    ctx.fillText(stage.boss ? stage.name : stage.name + " / " + (STAGES.length - 1), 16, 34);
    const boss = this.enemies.find((e) => e.boss);
    if (boss) {                                               // ボスの体力ゲージ
      const bw = Math.min(260, w - 200), bx = w / 2 - bw / 2;
      ctx.fillStyle = "rgba(0, 0, 0, 0.35)";
      ctx.fillRect(bx, 46, bw, 12);
      ctx.fillStyle = boss.form === 2 ? "#ff5a7a" : "#b478ff";
      ctx.fillRect(bx, 46, bw * Math.max(0, boss.hp) / BOSS_HP, 12);
      ctx.font = "700 12px 'Hiragino Sans', 'Yu Gothic', sans-serif";
      ctx.textAlign = "center";
      ctx.fillText(boss.form === 2 ? "巨大アールグレイ【第二形態】" : "巨大アールグレイ", w / 2, 40);
      ctx.font = "700 18px 'Hiragino Sans', 'Yu Gothic', sans-serif";
    }
    ctx.textAlign = "right";
    ctx.font = "700 14px 'Hiragino Sans', 'Yu Gothic', sans-serif";
    ctx.globalAlpha = 0.6;
    ctx.fillText(this.ai ? "敵AI：ニューラルネット" : "敵AI：ルール", w - 16, 34);
    ctx.globalAlpha = 1;
    ctx.textAlign = "left";

    // ステージが始まったときの大きな表示
    if (this.banner.t < 1.6) {
      const a = this.banner.t < 1.2 ? 1 : Math.max(0, 1 - (this.banner.t - 1.2) / 0.4);
      ctx.globalAlpha = a;
      ctx.textAlign = "center";
      ctx.font = "700 44px 'Hiragino Sans', 'Yu Gothic', sans-serif";
      ctx.fillText(this.banner.text || stage.name, w / 2, h * 0.3);
      ctx.globalAlpha = 1;
    }
  },

  // 敵のお茶を描く（四角い体に怒った顔。種類ごとに色と大きさが違う）
  // ボスのふりはらい：予告の赤い柱と、衝撃の柱
  drawBossFx(ctx, en, groundY) {
    const x0 = en.x - en.w / 2 - 50, wd = en.w + 100;
    const top = groundY - (en.z + en.h + 260), bottom = groundY - en.z - en.h * 0.3;
    if (en.phase === "shake") {
      ctx.fillStyle = "rgba(255, 60, 90, " + (0.14 + 0.1 * Math.sin(this.time * 30)) + ")";
      ctx.fillRect(x0, top, wd, bottom - top);
      ctx.strokeStyle = "rgba(255, 60, 90, 0.7)";
      ctx.lineWidth = 3;
      ctx.strokeRect(x0, top, wd, bottom - top);
    }
    if (en.phase === "wind" && en.next === "charge") {        // 突進の予告：走る向きに、赤い帯
      const len = 700, x1 = en.dir > 0 ? en.x : en.x - len;
      const by = groundY - en.z;                               // ボスの足元の高さを基準にする
      ctx.fillStyle = "rgba(255, 60, 90, " + (0.16 + 0.12 * Math.sin(this.time * 30)) + ")";
      ctx.fillRect(x1, by - 60, len, 60);
      ctx.strokeStyle = "rgba(255, 60, 90, 0.7)";
      ctx.lineWidth = 3;
      ctx.strokeRect(x1, by - 60, len, 60);
    }
    if (en.blast > 0) {
      const a = en.blast / BLAST_TIME;
      ctx.fillStyle = this.bossColor(en);
      ctx.globalAlpha = 0.55 * a;
      ctx.fillRect(x0, top, wd, bottom - top);
      ctx.fillStyle = "#fff";
      ctx.globalAlpha = 0.6 * a;
      ctx.fillRect(x0 + wd * 0.3, top, wd * 0.4, bottom - top);
      ctx.globalAlpha = 1;
    }
    if (en.phase === "slamUp" || en.phase === "slamHover") {   // 滞空技：真下の地面に、落ちてくる場所の予告
      const gy = groundY - this.groundAt(en.x);
      const pulse = 0.5 + 0.35 * Math.sin(this.time * 14);
      ctx.save();
      ctx.globalAlpha = pulse;
      ctx.fillStyle = "rgba(255, 60, 90, 0.4)";
      ctx.beginPath();
      ctx.ellipse(en.x, gy, en.w * 0.6, 16, 0, 0, Math.PI * 2);
      ctx.fill();
      ctx.strokeStyle = "rgba(255, 60, 90, 0.85)";
      ctx.lineWidth = 3;
      ctx.stroke();
      ctx.restore();
      // ボスから地面まで、うっすら光の筋
      ctx.strokeStyle = "rgba(255, 200, 120, 0.35)";
      ctx.lineWidth = 4;
      ctx.beginPath();
      ctx.moveTo(en.x, gy - 6);
      ctx.lineTo(en.x, groundY - en.z - en.h * 0.2);
      ctx.stroke();
    }
  },

  drawDrink(ctx, en, groundY, p) {
    const d = DRINKS[en.type];
    const bodyColor = en.boss && en.form === 2 ? BOSS_COLOR_2 : d.body;
    if (en.dead !== null) {                                   // 吸収：しずくの形で、吸収した相手のほうへ吸い込まれる
      const t = Math.min(1, en.dead / ABSORB_TIME);
      const ecx = en.x, ecy = groundY - en.z - en.h / 2;
      const a = en.absorber;                                  // 吸収した相手（敵か主人公）
      const pcx = a ? a.x : p.x + p.w / 2, pcy = a ? groundY - a.z - a.h / 2 : groundY - p.z - p.h / 2;
      const cx = ecx + (pcx - ecx) * t, cy = ecy + (pcy - ecy) * t, sc = 1 - t;
      const W = en.w * sc, H = en.h * sc;
      ctx.save();
      ctx.translate(cx, cy + H / 2);
      ctx.fillStyle = bodyColor;
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
    ctx.fillStyle = bodyColor;
    this.blobPath(ctx, W, H, q, en.x);
    ctx.fill();
    this.shine(ctx, W, H);
    if (en.boss) {
      if (en.flash > 0) {                                     // ダメージを受けたら白くひかる
        ctx.fillStyle = "rgba(255, 255, 255, " + Math.min(0.7, en.flash * 2.5) + ")";
        this.blobPath(ctx, W, H, q, en.x);
        ctx.fill();
      }
      ctx.fillStyle = "#f2c230";                              // 王冠
      ctx.beginPath();
      ctx.moveTo(-W * 0.22, -H + 3); ctx.lineTo(-W * 0.22, -H - H * 0.16); ctx.lineTo(-W * 0.11, -H - H * 0.06);
      ctx.lineTo(0, -H - H * 0.2); ctx.lineTo(W * 0.11, -H - H * 0.06); ctx.lineTo(W * 0.22, -H - H * 0.16);
      ctx.lineTo(W * 0.22, -H + 3);
      ctx.closePath();
      ctx.fill();
      if (en.form === 2) {                                    // 第二形態：角
        ctx.fillStyle = "#ffd0e0";
        for (const sd of [-1, 1]) {
          ctx.beginPath();
          ctx.moveTo(sd * W * 0.14, -H + 4); ctx.lineTo(sd * W * 0.4, -H - H * 0.22); ctx.lineTo(sd * W * 0.36, -H + 6);
          ctx.closePath();
          ctx.fill();
        }
      }
      ctx.fillStyle = "#f28c28";                              // ベルガモット（アールグレイの香りづけ）
      ctx.beginPath();
      ctx.arc(-W * 0.3, -H * 0.2, W * 0.07, 0, Math.PI * 2);
      ctx.arc(W * 0.3, -H * 0.2, W * 0.07, 0, Math.PI * 2);
      ctx.fill();
    }

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
    ctx.lineWidth = Math.max(2, u * 1.1);
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
    ctx.font = (en.boss ? "700 16px" : "700 11px") + " 'Hiragino Sans', 'Yu Gothic', sans-serif";
    ctx.textAlign = "center";
    ctx.fillText(d.name, en.x, groundY - en.z - H * (1 - q * 0.7) - 6 - (en.boss ? H * 0.22 : 0));
    ctx.textAlign = "left";
    ctx.globalAlpha = 1;
  },

  // ---- 草原ステージの飾り ------------------------------------------------------
  // 空：遠くの山の稜線・太陽・雲（画面に固定したまま、カメラよりゆっくり動く＝遠近感）
  drawMeadowSky(ctx, w, h, groundY, cam) {
    const hill = (offset, amp, freq, baseY, color) => {
      ctx.fillStyle = color;
      ctx.beginPath();
      ctx.moveTo(0, groundY);
      for (let x = 0; x <= w; x += 20) {
        ctx.lineTo(x, baseY - Math.sin((x + offset) * freq) * amp);
      }
      ctx.lineTo(w, groundY);
      ctx.closePath();
      ctx.fill();
    };
    ctx.save();
    ctx.beginPath(); ctx.rect(0, 0, w, groundY); ctx.clip();     // 地面より下にはみ出させない
    ctx.fillStyle = "rgba(255, 250, 210, 0.85)";                  // 太陽（HUDの文字とかぶらない位置に）
    ctx.beginPath();
    ctx.arc(w * 0.5, groundY * 0.3, 30, 0, Math.PI * 2);
    ctx.fill();
    hill(-cam * 0.12, 22, 0.010, groundY - 90, "#bfe6c9");        // いちばん遠い山
    hill(-cam * 0.22, 16, 0.016, groundY - 46, "#9fd7ae");        // 手前の丘
    ctx.fillStyle = "rgba(255, 255, 255, 0.9)";                   // 雲（ゆっくり流れる）
    const cx1 = ((w * 0.2 + this.time * 6 - cam * 0.05) % (w + 200)) - 100;
    const cx2 = ((w * 0.7 + this.time * 4 - cam * 0.05) % (w + 260)) - 130;
    for (const cx of [cx1, cx2]) {
      ctx.beginPath();
      ctx.ellipse(cx, h * 0.18, 34, 12, 0, 0, Math.PI * 2);
      ctx.ellipse(cx + 26, h * 0.16, 22, 10, 0, 0, Math.PI * 2);
      ctx.ellipse(cx - 22, h * 0.19, 18, 9, 0, 0, Math.PI * 2);
      ctx.fill();
    }
    ctx.restore();
  },

  // 地面：草の帯と、ふちの草むら（落とし穴の上は描かない）
  drawMeadowGround(ctx, stage, cam, w, groundY) {
    const a = Math.max(0, cam), b = Math.min(stage.width, cam + w);
    const gy = (x) => groundY - this.groundAt(x);                // その x の、画面上での地面の高さ（坂・丘に追従）
    ctx.fillStyle = stage.grass;
    if (stage.hills) {                                            // 草の帯も、地形の輪郭に沿わせる
      const step = 16;
      ctx.beginPath();
      ctx.moveTo(a, gy(a) + 16);
      for (let x = a; x <= b; x += step) ctx.lineTo(x, gy(x));
      ctx.lineTo(b, gy(b) + 16);
      ctx.closePath();
      ctx.fill();
    } else {
      ctx.fillRect(a, groundY, b - a, 14);
    }
    for (let x = Math.floor(a / 22) * 22; x < b; x += 22) {
      if (this.inPit(x)) continue;
      const hgt = 6 + Math.abs(Math.sin(x * 0.9)) * 6;
      const y = gy(x);
      ctx.fillStyle = stage.grass;
      ctx.beginPath();
      ctx.moveTo(x, y);
      ctx.lineTo(x + 5, y - hgt);
      ctx.lineTo(x + 10, y);
      ctx.fill();
    }
    for (let x = Math.floor(a / 96) * 96; x < b; x += 96) {
      if (this.inPit(x + 4)) continue;
      const c = ["#ffffff", "#ffd85e", "#ff9fc0"][Math.floor(x / 96) % 3];
      ctx.fillStyle = c;
      ctx.beginPath();
      ctx.arc(x + 4, gy(x + 4) + 6, 3, 0, Math.PI * 2);
      ctx.fill();
    }
  },

  // 目印の柱の代わりに立てる、小さな木の柵
  drawFencePost(ctx, x, groundY, stage) {
    ctx.fillStyle = "#8a6a42";
    ctx.fillRect(x, groundY - 46, 7, 46);
    ctx.fillRect(x - 8, groundY - 32, 23, 6);
    ctx.fillStyle = "#ff9fc0";
    ctx.beginPath();
    ctx.arc(x + 3, groundY - 50, 4, 0, Math.PI * 2);
    ctx.fill();
  },

  // 足場：草の生えた丸いマウンド（当たり判定は、ふつうの四角のまま＝上面は pl.top で平ら）
  drawMound(ctx, pl, y, stage) {
    ctx.fillStyle = stage.plat;
    ctx.fillRect(pl.x, y + 6, pl.w, 20);
    ctx.fillStyle = stage.platTop;
    ctx.beginPath();
    ctx.moveTo(pl.x, y + 8);
    for (let x = 0; x <= pl.w; x += 10) {
      ctx.lineTo(pl.x + x, y + 4 - Math.sin(x * 0.5 + pl.x) * 3);
    }
    ctx.lineTo(pl.x + pl.w, y + 8);
    ctx.closePath();
    ctx.fill();
    ctx.fillStyle = "#fff";                                       // 小さなお花
    ctx.beginPath(); ctx.arc(pl.x + pl.w * 0.25, y + 3, 2.4, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = "#ffd85e";
    ctx.beginPath(); ctx.arc(pl.x + pl.w * 0.7, y + 2, 2.4, 0, Math.PI * 2); ctx.fill();
  },

  // 木・茂み・ちょうちょ（当たり判定のない、見た目だけの飾り）
  drawMeadowFx(ctx, stage, cam, w, groundY) {
    const a = Math.max(0, cam - 60), b = Math.min(stage.width, cam + w + 60);
    for (let x = Math.floor(a / 260) * 260 + 130; x < b; x += 260) {
      if (this.inPit(x) || this.onAnyPlatform(x)) continue;
      const gy = groundY - this.groundAt(x);                      // 坂・丘に立たせる
      const bush = (x / 260) % 2 < 1;
      if (bush) {
        ctx.fillStyle = "#5da150";
        for (const off of [-10, 0, 10]) { ctx.beginPath(); ctx.arc(x + off, gy - 12, 13, 0, Math.PI * 2); ctx.fill(); }
      } else {
        ctx.fillStyle = "#7a5233";
        ctx.fillRect(x - 4, gy - 34, 8, 34);
        ctx.fillStyle = "#4f9a4a";
        ctx.beginPath(); ctx.arc(x, gy - 44, 22, 0, Math.PI * 2); ctx.fill();
        ctx.fillStyle = "#66b35e";
        ctx.beginPath(); ctx.arc(x - 8, gy - 52, 13, 0, Math.PI * 2); ctx.fill();
      }
    }
    ctx.fillStyle = "#ffcf5e";                                    // ちょうちょ（ふわふわ飛ぶ）
    for (let i = 0; i < 5; i++) {
      const bx = (i * 430 + 200) % stage.width;
      if (bx < a - 40 || bx > b + 40) continue;
      const fy = groundY - this.groundAt(bx) - 90 - Math.sin(this.time * 2 + i) * 26;
      const fx = bx + Math.cos(this.time * 1.3 + i) * 30;
      const flap = Math.abs(Math.sin(this.time * 9 + i));
      ctx.save();
      ctx.translate(fx, fy);
      ctx.beginPath(); ctx.ellipse(-4, 0, 5 * flap + 1, 4, 0, 0, Math.PI * 2); ctx.fill();
      ctx.beginPath(); ctx.ellipse(4, 0, 5 * flap + 1, 4, 0, 0, Math.PI * 2); ctx.fill();
      ctx.restore();
    }
  },

  // x のあたりに足場があるか（飾りが足場と重ならないように）
  onAnyPlatform(x) {
    for (const pl of this.stage.platforms) {
      if (x > pl.x - 20 && x < pl.x + pl.w + 20) return true;
    }
    return false;
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

  // shell からの入力。ステージセレクト画面のタップ／クリックだけ、ここで受け取る
  onPointer(type, x, y) {
    if (this.mode === "select" && type === "down") this.handleSelectTap(x, y);
  },

  // shell からのキー入力（今回は使わない。キーは onReady で自分で受け取っている）
  onKey(e) {},

  // 終了時に呼ばれる
  onEnd() {
    this.playing = false;
  },
};
