// まっちゃリズム：音ゲーの本体
// 音は Web Audio でその場で作って鳴らす（音声ファイルは使わない）

// ---- 設定 ----
const LANE_COUNT = 4;
const LANE_KEYS = ["d", "f", "j", "k"];
const LANE_COLORS = ["#ff6fa8", "#7fd6ff", "#8fe36b", "#ffd84d"];
const CANVAS_WIDTH = 480;
const CANVAS_HEIGHT = 720;
const JUDGE_LINE_Y = 610;
const NOTE_HEIGHT = 22;
const LEAD_IN_SECONDS = 2.5;
const PERFECT_WINDOW = 0.05;
const GREAT_WINDOW = 0.1;
const GOOD_WINDOW = 0.15;
const MAX_SCORE = 1000000;
const DIFFICULTY_LIST = [
  { id: "easy", label: "かんたん", fallSeconds: 1.7 },
  { id: "normal", label: "ふつう", fallSeconds: 1.35 },
  { id: "hard", label: "むずかしい", fallSeconds: 1.05 }
];
const NOTE_NAME_OFFSETS = { C: 0, D: 2, E: 4, F: 5, G: 7, A: 9, B: 11 };

// ---- 画面の部品 ----
const selectScreen = document.getElementById("selectScreen");
const playScreen = document.getElementById("playScreen");
const resultScreen = document.getElementById("resultScreen");
const canvas = document.getElementById("canvas");
const context2d = canvas.getContext("2d");

// ---- ゲームの状態 ----
let audioContext = null;
let masterGain = null;
let noiseBuffer = null;
let currentSong = null;
let currentDifficulty = null;
let chartNotes = [];
let songStartTime = 0;
let songLengthSeconds = 0;
let isPlaying = false;
let score = 0;
let combo = 0;
let maxCombo = 0;
let judgeCounts = { perfect: 0, great: 0, good: 0, miss: 0 };
let judgeText = "";
let judgeTextTime = -10;
let laneFlashTimes = [-10, -10, -10, -10];
let hitEffects = [];
let timingOffsetMs = loadTimingOffset();

/** 音の名前（例: "C4"）を MIDI の番号に直す */
function noteNameToMidi(noteName) {
  const letter = noteName[0];
  const octave = Number(noteName.slice(1));
  return 12 * (octave + 1) + NOTE_NAME_OFFSETS[letter];
}

/** MIDI の番号を周波数（Hz）に直す */
function midiToFrequency(midiNumber) {
  return 440 * Math.pow(2, (midiNumber - 69) / 12);
}

/** 曲のメロディの文字を、くり返しもふくめた音のリストに直す */
function parseMelody(song) {
  const tokens = song.melody.trim().split(/\s+/);
  const melodyNotes = [];
  let beat = 0;

  // くり返しの回ごとに、メロディを順にならべる
  for (const octaveShift of song.repeatOctaves) {
    for (const token of tokens) {
      const parts = token.split(":");
      const lengthBeats = Number(parts[1]);
      if (parts[0] !== "R") {
        const midiNumber = noteNameToMidi(parts[0]) + octaveShift * 12;
        melodyNotes.push({ beat: beat, lengthBeats: lengthBeats, midi: midiNumber });
      }
      beat += lengthBeats;
    }
  }
  return { melodyNotes: melodyNotes, totalBeats: beat };
}

/** メロディから、えらんだむずかしさの譜面（ノーツのリスト）を作る */
function buildChart(song, difficultyId) {
  const parsed = parseMelody(song);
  const secondsPerBeat = 60 / song.bpm;
  const notes = [];

  // 音の高さの順番からレーンを決める（となりの高さはとなりのレーン）
  const pitchList = [...new Set(parsed.melodyNotes.map((note) => note.midi % 12))].sort((a, b) => a - b);

  // メロディの音をノーツにする（かんたん・ふつうは近すぎる音をまびく）
  let minGapSeconds = 0;
  if (difficultyId === "easy") minGapSeconds = 0.6;
  if (difficultyId === "normal") minGapSeconds = 0.25;
  let lastTime = -10;
  const usedBeats = new Set();
  for (const melodyNote of parsed.melodyNotes) {
    const time = melodyNote.beat * secondsPerBeat;
    if (difficultyId === "easy" && melodyNote.beat % 1 !== 0) continue;
    if (time - lastTime < minGapSeconds - 0.001) continue;
    const lane = pitchList.indexOf(melodyNote.midi % 12) % LANE_COUNT;
    notes.push({ time: time, lane: lane, judged: false });
    usedBeats.add(melodyNote.beat);
    lastTime = time;
  }

  // むずかしいは、音のないところにリズムのノーツを足して、小節の頭は同時おしにする
  if (difficultyId === "hard") {
    const fillStep = secondsPerBeat >= 0.47 ? 0.5 : 1;
    for (let beat = 0; beat < parsed.totalBeats; beat += fillStep) {
      if (usedBeats.has(beat)) continue;
      const lane = (beat * 2 * 3 + 1) % LANE_COUNT;
      notes.push({ time: beat * secondsPerBeat, lane: lane, judged: false });
    }
    const melodyCount = notes.length;
    for (let index = 0; index < melodyCount; index++) {
      const beat = Math.round(notes[index].time / secondsPerBeat * 2) / 2;
      if (beat % 4 !== 0) continue;
      notes.push({ time: notes[index].time, lane: (notes[index].lane + 2) % LANE_COUNT, judged: false });
    }
  }

  // 時間の順にならべる
  notes.sort((a, b) => a.time - b.time);
  return notes;
}

/** 譜面のむずかしさを 1〜10 の数字にする（1秒あたりのノーツの数から） */
function getLevelNumber(song, difficultyId) {
  const notes = buildChart(song, difficultyId);
  const lengthSeconds = parseMelody(song).totalBeats * 60 / song.bpm;
  const notesPerSecond = notes.length / lengthSeconds;
  return Math.max(1, Math.min(10, Math.round(notesPerSecond * 2)));
}

/** 音を鳴らす準備をする（最初の1回だけ） */
function setupAudio() {
  if (audioContext) return;
  audioContext = new (window.AudioContext || window.webkitAudioContext)();

  // ドラム用のノイズを作っておく
  noiseBuffer = audioContext.createBuffer(1, audioContext.sampleRate, audioContext.sampleRate);
  const noiseData = noiseBuffer.getChannelData(0);
  for (let index = 0; index < noiseData.length; index++) {
    noiseData[index] = Math.random() * 2 - 1;
  }
}

/** 決まった高さの音を1つ予約する */
function scheduleTone(waveType, frequency, startTime, lengthSeconds, volume) {
  const oscillator = audioContext.createOscillator();
  const gain = audioContext.createGain();
  oscillator.type = waveType;
  oscillator.frequency.value = frequency;

  // 音の出だしと消え方をなめらかにする
  gain.gain.setValueAtTime(0, startTime);
  gain.gain.linearRampToValueAtTime(volume, startTime + 0.01);
  gain.gain.setValueAtTime(volume * 0.7, startTime + lengthSeconds * 0.6);
  gain.gain.linearRampToValueAtTime(0, startTime + lengthSeconds);

  oscillator.connect(gain);
  gain.connect(masterGain);
  oscillator.start(startTime);
  oscillator.stop(startTime + lengthSeconds + 0.02);
}

/** バスドラム（ドン）を1つ予約する */
function scheduleKick(startTime) {
  const oscillator = audioContext.createOscillator();
  const gain = audioContext.createGain();
  oscillator.frequency.setValueAtTime(150, startTime);
  oscillator.frequency.exponentialRampToValueAtTime(45, startTime + 0.12);
  gain.gain.setValueAtTime(0.5, startTime);
  gain.gain.exponentialRampToValueAtTime(0.001, startTime + 0.18);
  oscillator.connect(gain);
  gain.connect(masterGain);
  oscillator.start(startTime);
  oscillator.stop(startTime + 0.2);
}

/** ノイズのドラム（スネアやハイハット）を1つ予約する */
function scheduleNoise(startTime, lengthSeconds, volume, filterFrequency) {
  const source = audioContext.createBufferSource();
  const filter = audioContext.createBiquadFilter();
  const gain = audioContext.createGain();
  source.buffer = noiseBuffer;
  filter.type = "highpass";
  filter.frequency.value = filterFrequency;
  gain.gain.setValueAtTime(volume, startTime);
  gain.gain.exponentialRampToValueAtTime(0.001, startTime + lengthSeconds);
  source.connect(filter);
  filter.connect(gain);
  gain.connect(masterGain);
  source.start(startTime);
  source.stop(startTime + lengthSeconds + 0.02);
}

/** 曲ぜんぶ（メロディ・ベース・ドラム）を予約する */
function scheduleSong(song, startTime) {
  const parsed = parseMelody(song);
  const secondsPerBeat = 60 / song.bpm;

  // メロディ
  for (const melodyNote of parsed.melodyNotes) {
    const noteStart = startTime + melodyNote.beat * secondsPerBeat;
    const noteLength = melodyNote.lengthBeats * secondsPerBeat * 0.92;
    scheduleTone("square", midiToFrequency(melodyNote.midi), noteStart, noteLength, 0.09);
    scheduleTone("triangle", midiToFrequency(melodyNote.midi), noteStart, noteLength, 0.14);
  }

  // ベース（2拍ごとのコードの音を、1拍ずつ鳴らす）
  const chordNames = song.chords.trim().split(/\s+/);
  for (let beat = 0; beat < parsed.totalBeats; beat++) {
    const chordName = chordNames[Math.floor(beat / 2) % chordNames.length];
    const bassMidi = noteNameToMidi(chordName + "2") + (beat % 2 === 1 ? 7 : 0);
    scheduleTone("triangle", midiToFrequency(bassMidi), startTime + beat * secondsPerBeat, secondsPerBeat * 0.8, 0.28);
  }

  // ドラム（ドン・タン と、半拍ごとのチッ）
  for (let beat = 0; beat < parsed.totalBeats; beat++) {
    const beatTime = startTime + beat * secondsPerBeat;
    if (beat % 2 === 0) scheduleKick(beatTime);
    if (beat % 2 === 1) scheduleNoise(beatTime, 0.12, 0.22, 1500);
    scheduleNoise(beatTime, 0.04, 0.07, 7000);
    scheduleNoise(beatTime + secondsPerBeat / 2, 0.04, 0.05, 7000);
  }

  return parsed.totalBeats * secondsPerBeat;
}

/** いまの曲の時間（秒）を返す。曲が始まる前はマイナス */
function getSongTime() {
  const outputLatency = audioContext.outputLatency || 0;
  return audioContext.currentTime - songStartTime - outputLatency - timingOffsetMs / 1000;
}

/** 保存してあるタイミング調整の数字を読む */
function loadTimingOffset() {
  const saved = Number(localStorage.getItem("otoge_timing_offset"));
  return Number.isFinite(saved) ? saved : 0;
}

/** タイミング調整の数字を変えて保存する */
function changeTimingOffset(amountMs) {
  timingOffsetMs = Math.max(-300, Math.min(300, timingOffsetMs + amountMs));
  localStorage.setItem("otoge_timing_offset", String(timingOffsetMs));
  document.getElementById("timingValue").textContent = timingOffsetMs + " ms";
}

/** ハイスコアを保存するときの名前を作る */
function getRecordKey(songId, difficultyId) {
  return "otoge_best_" + songId + "_" + difficultyId;
}

/** 保存してあるハイスコアを読む */
function loadBestScore(songId, difficultyId) {
  return Number(localStorage.getItem(getRecordKey(songId, difficultyId))) || 0;
}

/** 画面を1つだけ表示する */
function showScreen(screen) {
  for (const element of [selectScreen, playScreen, resultScreen]) {
    element.classList.toggle("hidden", element !== screen);
  }
}

/** 曲をえらぶ画面のリストを作る */
function renderSongList() {
  const songListElement = document.getElementById("songList");
  songListElement.innerHTML = "";

  for (const song of SONG_LIST) {
    // 曲のカード
    const card = document.createElement("div");
    card.className = "song-card";
    card.style.borderLeftColor = song.color;
    card.innerHTML = '<span class="song-title"></span><span class="song-composer"></span>';
    card.querySelector(".song-title").textContent = song.title;
    card.querySelector(".song-composer").textContent = song.composer;

    // むずかしさのボタン
    const levelRow = document.createElement("div");
    levelRow.className = "level-row";
    for (const difficulty of DIFFICULTY_LIST) {
      const button = document.createElement("button");
      button.className = "level-button";
      const bestScore = loadBestScore(song.id, difficulty.id);
      const bestText = bestScore > 0 ? "ベスト " + bestScore : "まだあそんでないよ";
      button.innerHTML = difficulty.label + " ★" + getLevelNumber(song, difficulty.id) + "<small>" + bestText + "</small>";
      button.addEventListener("click", () => startGame(song, difficulty));
      levelRow.appendChild(button);
    }
    card.appendChild(levelRow);
    songListElement.appendChild(card);
  }
}

/** 曲をえらんで、ゲームを始める */
function startGame(song, difficulty) {
  setupAudio();
  audioContext.resume();
  currentSong = song;
  currentDifficulty = difficulty;

  // 点数などを最初にもどす
  chartNotes = buildChart(song, difficulty.id);
  score = 0;
  combo = 0;
  maxCombo = 0;
  judgeCounts = { perfect: 0, great: 0, good: 0, miss: 0 };
  judgeText = "";
  hitEffects = [];

  // 曲を予約して鳴らす
  masterGain = audioContext.createGain();
  masterGain.gain.value = 0.8;
  masterGain.connect(audioContext.destination);
  songStartTime = audioContext.currentTime + LEAD_IN_SECONDS;
  songLengthSeconds = scheduleSong(song, songStartTime);

  isPlaying = true;
  showScreen(playScreen);
  requestAnimationFrame(gameLoop);
}

/** 鳴っている曲を止める */
function stopMusic() {
  if (masterGain) {
    masterGain.disconnect();
    masterGain = null;
  }
}

/** とちゅうでやめて、曲をえらぶ画面にもどる */
function quitGame() {
  isPlaying = false;
  stopMusic();
  renderSongList();
  showScreen(selectScreen);
}

/** ずれの大きさから判定（PERFECT など）を決めて、点数に入れる */
function applyJudge(note, judgeName) {
  note.judged = true;
  judgeCounts[judgeName] += 1;

  // コンボを数える
  if (judgeName === "miss") {
    combo = 0;
  } else {
    combo += 1;
    maxCombo = Math.max(maxCombo, combo);
    hitEffects.push({ lane: note.lane, time: getSongTime(), color: LANE_COLORS[note.lane] });
  }

  // 点数を計算しなおす（ぜんぶ PERFECT で 100万点）
  const weightedHits = judgeCounts.perfect + judgeCounts.great * 0.7 + judgeCounts.good * 0.4;
  score = Math.round(MAX_SCORE * weightedHits / chartNotes.length);

  // 判定の文字を出す
  judgeText = judgeName.toUpperCase();
  judgeTextTime = getSongTime();
}

/** レーンをおした時の処理 */
function hitLane(lane) {
  if (!isPlaying) return;
  const songTime = getSongTime();
  laneFlashTimes[lane] = songTime;

  // そのレーンで、まだ判定していない一番早いノーツをさがす
  for (const note of chartNotes) {
    if (note.judged || note.lane !== lane) continue;
    const difference = Math.abs(note.time - songTime);
    if (difference > GOOD_WINDOW) {
      if (note.time > songTime) break;
      continue;
    }

    // ずれの大きさで判定する
    if (difference <= PERFECT_WINDOW) applyJudge(note, "perfect");
    else if (difference <= GREAT_WINDOW) applyJudge(note, "great");
    else applyJudge(note, "good");
    break;
  }
}

/** 通りすぎたノーツを MISS にする */
function checkMissedNotes(songTime) {
  for (const note of chartNotes) {
    if (note.time > songTime) break;
    if (!note.judged && songTime - note.time > GOOD_WINDOW) {
      applyJudge(note, "miss");
    }
  }
}

/** 点数からランク（S〜D）を決める */
function getRank(finalScore) {
  if (finalScore >= 950000) return "S";
  if (finalScore >= 850000) return "A";
  if (finalScore >= 700000) return "B";
  if (finalScore >= 500000) return "C";
  return "D";
}

/** 曲が終わったら、結果の画面を出す */
function finishGame() {
  isPlaying = false;
  stopMusic();

  // ハイスコアなら保存する
  const bestScore = loadBestScore(currentSong.id, currentDifficulty.id);
  const isNewRecord = score > bestScore;
  if (isNewRecord) {
    localStorage.setItem(getRecordKey(currentSong.id, currentDifficulty.id), String(score));
  }

  // 結果を表示する
  document.getElementById("resultTitle").textContent = currentSong.title + "（" + currentDifficulty.label + "）";
  document.getElementById("resultRank").textContent = getRank(score);
  document.getElementById("resultScore").textContent = score + " 点";
  document.getElementById("resultRecord").textContent = isNewRecord ? "新記録！おめでとう！" : "";
  const fullComboText = judgeCounts.miss === 0 ? "（フルコンボ！）" : "";
  document.getElementById("resultTable").innerHTML =
    "<tr><td>PERFECT</td><td>" + judgeCounts.perfect + "</td></tr>" +
    "<tr><td>GREAT</td><td>" + judgeCounts.great + "</td></tr>" +
    "<tr><td>GOOD</td><td>" + judgeCounts.good + "</td></tr>" +
    "<tr><td>MISS</td><td>" + judgeCounts.miss + "</td></tr>" +
    "<tr><td>最大コンボ</td><td>" + maxCombo + fullComboText + "</td></tr>";
  showScreen(resultScreen);
}

/** あそぶ画面を1回かく */
function drawGame(songTime) {
  const laneWidth = CANVAS_WIDTH / LANE_COUNT;
  const secondsPerBeat = 60 / currentSong.bpm;

  // 背景（拍に合わせて少し明るくなる）
  const beatPhase = songTime >= 0 ? (songTime / secondsPerBeat) % 1 : 1;
  const pulse = Math.max(0, 1 - beatPhase * 2) * 0.12;
  context2d.fillStyle = "rgb(" + Math.round(16 + pulse * 120) + "," + Math.round(19 + pulse * 120) + "," + Math.round(31 + pulse * 160) + ")";
  context2d.fillRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT);

  // レーンと、おした時の光
  for (let lane = 0; lane < LANE_COUNT; lane++) {
    const flashAge = songTime - laneFlashTimes[lane];
    if (flashAge >= 0 && flashAge < 0.15) {
      context2d.globalAlpha = 0.35 * (1 - flashAge / 0.15);
      context2d.fillStyle = LANE_COLORS[lane];
      context2d.fillRect(lane * laneWidth, 0, laneWidth, CANVAS_HEIGHT);
      context2d.globalAlpha = 1;
    }
    context2d.fillStyle = "rgba(255,255,255,0.12)";
    context2d.fillRect(lane * laneWidth, 0, 1, CANVAS_HEIGHT);
  }

  // 判定の線と、キーの名前
  context2d.fillStyle = "#ffffff";
  context2d.fillRect(0, JUDGE_LINE_Y - 2, CANVAS_WIDTH, 4);
  context2d.font = "bold 26px sans-serif";
  context2d.textAlign = "center";
  for (let lane = 0; lane < LANE_COUNT; lane++) {
    context2d.fillStyle = LANE_COLORS[lane];
    context2d.fillText(LANE_KEYS[lane].toUpperCase(), lane * laneWidth + laneWidth / 2, JUDGE_LINE_Y + 60);
  }

  // ノーツ
  for (const note of chartNotes) {
    if (note.judged) continue;
    const timeUntilHit = note.time - songTime;
    if (timeUntilHit > currentDifficulty.fallSeconds) break;
    const noteY = JUDGE_LINE_Y * (1 - timeUntilHit / currentDifficulty.fallSeconds);
    context2d.fillStyle = LANE_COLORS[note.lane];
    context2d.beginPath();
    context2d.roundRect(note.lane * laneWidth + 8, noteY - NOTE_HEIGHT / 2, laneWidth - 16, NOTE_HEIGHT, 8);
    context2d.fill();
  }

  // 当たった時の輪
  hitEffects = hitEffects.filter((effect) => songTime - effect.time < 0.25);
  for (const effect of hitEffects) {
    const age = (songTime - effect.time) / 0.25;
    context2d.globalAlpha = 1 - age;
    context2d.strokeStyle = effect.color;
    context2d.lineWidth = 5;
    context2d.beginPath();
    context2d.arc(effect.lane * laneWidth + laneWidth / 2, JUDGE_LINE_Y, 20 + age * 40, 0, Math.PI * 2);
    context2d.stroke();
    context2d.globalAlpha = 1;
  }

  // 点数とコンボ
  context2d.fillStyle = "#ffffff";
  context2d.textAlign = "left";
  context2d.font = "bold 22px sans-serif";
  context2d.fillText(String(score), 12, 34);
  context2d.textAlign = "center";
  if (combo >= 2) {
    context2d.font = "bold 54px sans-serif";
    context2d.fillStyle = "rgba(255,255,255,0.85)";
    context2d.fillText(String(combo), CANVAS_WIDTH / 2, 250);
    context2d.font = "bold 18px sans-serif";
    context2d.fillText("コンボ", CANVAS_WIDTH / 2, 278);
  }

  // 判定の文字
  const judgeAge = songTime - judgeTextTime;
  if (judgeText !== "" && judgeAge >= 0 && judgeAge < 0.5) {
    const judgeColors = { PERFECT: "#ffd84d", GREAT: "#8fe36b", GOOD: "#7fd6ff", MISS: "#ff5c5c" };
    context2d.globalAlpha = 1 - judgeAge / 0.5;
    context2d.fillStyle = judgeColors[judgeText];
    context2d.font = "bold 40px sans-serif";
    context2d.fillText(judgeText, CANVAS_WIDTH / 2, 440 - judgeAge * 30);
    context2d.globalAlpha = 1;
  }

  // 始まる前のカウントダウン
  if (songTime < 0) {
    context2d.fillStyle = "#ffffff";
    context2d.font = "bold 30px sans-serif";
    context2d.fillText(currentSong.title, CANVAS_WIDTH / 2, 330);
    context2d.font = "bold 22px sans-serif";
    context2d.fillText("よーい…", CANVAS_WIDTH / 2, 370);
  }

  // 曲の進みぐあいのバー
  const progress = Math.max(0, Math.min(1, songTime / songLengthSeconds));
  context2d.fillStyle = currentSong.color;
  context2d.fillRect(0, 0, CANVAS_WIDTH * progress, 5);
}

/** 毎フレームの処理（判定のチェックと、画面をかく） */
function gameLoop() {
  if (!isPlaying) return;
  const songTime = getSongTime();
  checkMissedNotes(songTime);
  drawGame(songTime);

  // 曲が終わったら結果へ
  if (songTime > songLengthSeconds + 1.2) {
    finishGame();
    return;
  }
  requestAnimationFrame(gameLoop);
}

/** キーボードをおした時の処理 */
function onKeyDown(event) {
  if (event.repeat) return;
  if (event.key === "Escape" && isPlaying) {
    quitGame();
    return;
  }
  const lane = LANE_KEYS.indexOf(event.key.toLowerCase());
  if (lane >= 0) hitLane(lane);
}

/** 画面をタッチ（クリック）した時の処理 */
function onPointerDown(event) {
  event.preventDefault();
  const rect = canvas.getBoundingClientRect();
  const lane = Math.floor((event.clientX - rect.left) / rect.width * LANE_COUNT);
  if (lane >= 0 && lane < LANE_COUNT) hitLane(lane);
}

// ---- ボタンやキーの登録 ----
document.addEventListener("keydown", onKeyDown);
canvas.addEventListener("pointerdown", onPointerDown);
document.getElementById("quitButton").addEventListener("click", quitGame);
document.getElementById("retryButton").addEventListener("click", () => startGame(currentSong, currentDifficulty));
document.getElementById("backButton").addEventListener("click", quitGame);
document.getElementById("timingMinusButton").addEventListener("click", () => changeTimingOffset(-10));
document.getElementById("timingPlusButton").addEventListener("click", () => changeTimingOffset(10));

// ---- 最初の表示 ----
changeTimingOffset(0);
renderSongList();
