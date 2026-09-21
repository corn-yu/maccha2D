/* 敵AIの学習スクリプト（Node.js で実行： node tools/train-enemy-ai.js）
   お手本の動き（先回りして追いかける・上にいたらジャンプ・穴の前でジャンプ・避けジャンプ）を
   小さなニューラルネットに学習させて、重みを enemy-ai.json に書き出します。
   game.js は、この enemy-ai.json を読み込んで敵の動きを決めます。
   ※ 入力の並びは game.js の enemyFeatures() と同じにしてください。 */
const fs = require("fs");
const path = require("path");

const SIGHT_X = 340, SIGHT_Z = 220;
const SIZES = [9, 16, 16, 4];          // 入力9 → 16 → 16 → 出力4（左/止まる/右 + ジャンプ）

// ---- お手本：入力（特徴量）から、動きとジャンプを決める ----
function features(s) {
  const clamp = (v) => Math.max(-1, Math.min(1, v));
  return [
    clamp(s.dx / SIGHT_X), clamp(s.dz / SIGHT_Z), clamp(s.pvx / 240), clamp(s.pvz / 640),
    s.pGrounded, s.eGrounded, s.ready, s.pitR, s.pitL,
  ];
}
function teacher(s) {
  const target = s.dx + 0.25 * s.pvx;                          // プレイヤーの少し先を狙う
  const move = Math.abs(target) < 8 ? 0 : (target > 0 ? 1 : -1);
  const pitAhead = move > 0 ? s.pitR : move < 0 ? s.pitL : 0;
  const above = s.dz > 20 && Math.abs(s.dx) < 140;
  const dodge = !s.pGrounded && Math.abs(s.dx) < 110;
  const jump = s.eGrounded && s.ready && (above || pitAhead || dodge) ? 1 : 0;
  return { move, jump };
}
function randomState() {
  const r = (a, b) => a + Math.random() * (b - a);
  return {
    dx: r(-SIGHT_X, SIGHT_X), dz: r(-SIGHT_Z, SIGHT_Z), pvx: r(-240, 240), pvz: r(-700, 640),
    pGrounded: Math.random() < 0.6 ? 1 : 0, eGrounded: Math.random() < 0.85 ? 1 : 0,
    ready: Math.random() < 0.7 ? 1 : 0,
    pitR: Math.random() < 0.15 ? 1 : 0, pitL: Math.random() < 0.15 ? 1 : 0,
  };
}

// ---- ニューラルネット（tanh の全結合）----
function initNet() {
  return SIZES.slice(1).map((n, i) => {
    const m = SIZES[i], scale = Math.sqrt(1 / m);
    return { w: Array.from({ length: n }, () => Array.from({ length: m }, () => (Math.random() * 2 - 1) * scale)), b: new Array(n).fill(0) };
  });
}
function forward(net, x) {
  const acts = [x];
  net.forEach((L, li) => {
    const prev = acts[li];
    const out = L.w.map((row, j) => row.reduce((s, wv, k) => s + wv * prev[k], L.b[j]));
    acts.push(li < net.length - 1 ? out.map(Math.tanh) : out);
  });
  return acts;
}
const softmax3 = (z) => { const m = Math.max(z[0], z[1], z[2]); const e = [z[0], z[1], z[2]].map((v) => Math.exp(v - m)); const t = e[0] + e[1] + e[2]; return e.map((v) => v / t); };
const sigmoid = (v) => 1 / (1 + Math.exp(-v));

function predict(net, f) {
  const o = forward(net, f).pop();
  const pm = softmax3(o);
  return { move: pm.indexOf(Math.max(...pm)) - 1, jump: sigmoid(o[3]) > 0.5 ? 1 : 0 };
}

// ---- 学習（Adam）----
const net = initNet();
const zero = () => net.map((L) => ({ w: L.w.map((r) => r.map(() => 0)), b: L.b.map(() => 0) }));
let m1 = zero(), m2 = zero(), step = 0;
const data = Array.from({ length: 60000 }, () => { const s = randomState(); return { f: features(s), t: teacher(s) }; });
const EPOCHS = 40, BATCH = 64, LR = 0.004;

for (let ep = 0; ep < EPOCHS; ep++) {
  data.sort(() => Math.random() - 0.5);
  let loss = 0;
  for (let i = 0; i < data.length; i += BATCH) {
    const grad = zero();
    const batch = data.slice(i, i + BATCH);
    for (const d of batch) {
      const acts = forward(net, d.f);
      const o = acts[acts.length - 1];
      const pm = softmax3(o), pj = sigmoid(o[3]);
      const tm = d.t.move + 1;
      loss += -Math.log(pm[tm] + 1e-9) - (d.t.jump ? Math.log(pj + 1e-9) : Math.log(1 - pj + 1e-9));
      let delta = [pm[0] - (tm === 0), pm[1] - (tm === 1), pm[2] - (tm === 2), pj - d.t.jump];
      for (let li = net.length - 1; li >= 0; li--) {
        const prev = acts[li];
        const next = new Array(prev.length).fill(0);
        net[li].w.forEach((row, j) => {
          grad[li].b[j] += delta[j];
          row.forEach((wv, k) => { grad[li].w[j][k] += delta[j] * prev[k]; next[k] += wv * delta[j]; });
        });
        if (li > 0) delta = next.map((v, k) => v * (1 - prev[k] * prev[k]));
      }
    }
    step++;
    const b1 = 0.9, b2 = 0.999, n = batch.length;
    net.forEach((L, li) => {
      const upd = (get, set, g, a, b) => {
        const gg = g / n;
        a.v = b1 * a.v + (1 - b1) * gg;
        b.v = b2 * b.v + (1 - b2) * gg * gg;
        set(get() - LR * (a.v / (1 - Math.pow(b1, step))) / (Math.sqrt(b.v / (1 - Math.pow(b2, step))) + 1e-8));
      };
      L.w.forEach((row, j) => row.forEach((_, k) => {
        const a = { v: m1[li].w[j][k] }, b = { v: m2[li].w[j][k] };
        upd(() => L.w[j][k], (v) => { L.w[j][k] = v; }, grad[li].w[j][k], a, b);
        m1[li].w[j][k] = a.v; m2[li].w[j][k] = b.v;
      }));
      L.b.forEach((_, j) => {
        const a = { v: m1[li].b[j] }, b = { v: m2[li].b[j] };
        upd(() => L.b[j], (v) => { L.b[j] = v; }, grad[li].b[j], a, b);
        m1[li].b[j] = a.v; m2[li].b[j] = b.v;
      });
    });
  }
  if (ep % 5 === 4 || ep === EPOCHS - 1) console.log("epoch", ep + 1, "loss", (loss / data.length).toFixed(4));
}

// ---- 新しいデータで正解率を確認 ----
let okMove = 0, okJump = 0;
const N = 20000;
for (let i = 0; i < N; i++) {
  const s = randomState(), t = teacher(s), p = predict(net, features(s));
  if (p.move === t.move) okMove++;
  if (p.jump === t.jump) okJump++;
}
console.log("動きの正解率", (okMove / N * 100).toFixed(1) + "%", "/ ジャンプの正解率", (okJump / N * 100).toFixed(1) + "%");

const round = (v) => Math.round(v * 1e4) / 1e4;
const out = {
  name: "maccha2D enemy AI", inputs: ["dx", "dz", "pvx", "pvz", "pGrounded", "eGrounded", "ready", "pitR", "pitL"],
  outputs: ["moveLeft", "moveStay", "moveRight", "jump"],
  layers: net.map((L) => ({ w: L.w.map((r) => r.map(round)), b: L.b.map(round) })),
};
fs.writeFileSync(path.join(__dirname, "..", "enemy-ai.json"), JSON.stringify(out));
console.log("enemy-ai.json を書き出しました");
