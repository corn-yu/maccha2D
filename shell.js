/* shell.js ── ゲームの枠の仕組み。基本的に触らなくて大丈夫です */
(function () {
  const $ = (id) => document.getElementById(id);
  const stage = $("stage"), canvas = $("canvas"), ctx = canvas.getContext("2d");
  const startScreen = $("startScreen"), resultScreen = $("resultScreen");

  let state = "title";           // "title" | "playing" | "result"
  let score = 0, best = 0, timeLeft = 0, lastT = 0;

  const shell = {
    width: 0, height: 0,
    addScore(n) { shell.setScore(score + n); },
    setScore(n) { score = n; $("score").textContent = score; },
    end(msg) { finish(msg); },
  };

  document.title = CONFIG.title;
  $("title").textContent = CONFIG.title;
  $("tagline").textContent = CONFIG.tagline;
  $("howto").textContent = CONFIG.howTo;
  if (CONFIG.timeLimit == null) $("timeWrap").style.display = "none";

  try { best = parseInt(localStorage.getItem(CONFIG.storageKey), 10) || 0; } catch (e) {}
  $("best").textContent = best;

  function resize() {
    const dpr = window.devicePixelRatio || 1;
    const r = stage.getBoundingClientRect();
    shell.width = r.width; shell.height = r.height;
    canvas.width = Math.round(r.width * dpr);
    canvas.height = Math.round(r.height * dpr);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  }
  window.addEventListener("resize", resize);

  ["down", "move", "up"].forEach((t) => {
    canvas.addEventListener("pointer" + t, (e) => {
      if (state !== "playing") return;
      const r = canvas.getBoundingClientRect();
      game.onPointer(t, e.clientX - r.left, e.clientY - r.top);
    });
  });
  window.addEventListener("keydown", (e) => {
    if (state === "playing") { game.onKey(e); return; }
    if (e.key === "Enter" || e.key === " ") { e.preventDefault(); start(); }
  });

  function loop(t) {
    const dt = Math.min((t - lastT) / 1000, 0.1);
    lastT = t;
    if (state === "playing") {
      if (CONFIG.timeLimit != null) {
        timeLeft -= dt;
        $("time").textContent = Math.max(0, Math.ceil(timeLeft));
        if (timeLeft <= 0) { finish(); requestAnimationFrame(loop); return; }
      }
      game.onUpdate(dt);
    }
    ctx.clearRect(0, 0, shell.width, shell.height);
    if (state === "playing") game.onDraw(ctx, shell.width, shell.height);
    requestAnimationFrame(loop);
  }

  function start() {
    shell.setScore(0);
    timeLeft = CONFIG.timeLimit || 0;
    $("time").textContent = CONFIG.timeLimit ?? "";
    startScreen.hidden = true; resultScreen.hidden = true;
    state = "playing";
    resize();
    game.onStart();
  }

  function finish(msg) {
    if (state !== "playing") return;
    state = "result";
    game.onEnd();
    const isBest = score > best;
    if (isBest) {
      best = score; $("best").textContent = best;
      try { localStorage.setItem(CONFIG.storageKey, String(best)); } catch (e) {}
    }
    $("resultMsg").textContent = msg || "おつかれさま！";
    $("finalScore").textContent = score;
    $("bestMsg").textContent = isBest ? "ベスト更新！" : "ベスト " + best;
    resultScreen.hidden = false;
  }

  $("startBtn").addEventListener("click", start);
  $("retryBtn").addEventListener("click", start);

  resize();
  game.onReady(ctx, shell);
  requestAnimationFrame((t) => { lastT = t; loop(t); });
})();
