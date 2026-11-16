// まっちゃリズム：曲のデータ
// melody は「音の名前:長さ(拍)」をならべたもの。R はお休み。
// chords は 2拍ごとのベースの音。repeatOctaves は くり返す回数と、その回のメロディの高さ（1 で1オクターブ上）。
const SONG_LIST = [
  {
    id: "twinkle",
    title: "きらきら星",
    composer: "フランスの古い歌",
    color: "#ffd84d",
    bpm: 112,
    repeatOctaves: [0, 1],
    melody: `
      C4:1 C4:1 G4:1 G4:1 A4:1 A4:1 G4:2
      F4:1 F4:1 E4:1 E4:1 D4:1 D4:1 C4:2
      G4:1 G4:1 F4:1 F4:1 E4:1 E4:1 D4:2
      G4:1 G4:1 F4:1 F4:1 E4:1 E4:1 D4:2
      C4:1 C4:1 G4:1 G4:1 A4:1 A4:1 G4:2
      F4:1 F4:1 E4:1 E4:1 D4:1 D4:1 C4:2`,
    chords: `
      C C F C  F C G C
      C F C G  C F C G
      C C F C  F C G C`
  },
  {
    id: "joy",
    title: "よろこびの歌",
    composer: "ベートーベン",
    color: "#7fd6ff",
    bpm: 126,
    repeatOctaves: [0, 1],
    melody: `
      E4:1 E4:1 F4:1 G4:1  G4:1 F4:1 E4:1 D4:1
      C4:1 C4:1 D4:1 E4:1  E4:1.5 D4:.5 D4:2
      E4:1 E4:1 F4:1 G4:1  G4:1 F4:1 E4:1 D4:1
      C4:1 C4:1 D4:1 E4:1  D4:1.5 C4:.5 C4:2
      D4:1 D4:1 E4:1 C4:1  D4:1 E4:.5 F4:.5 E4:1 C4:1
      D4:1 E4:.5 F4:.5 E4:1 D4:1  C4:1 D4:1 G3:2
      E4:1 E4:1 F4:1 G4:1  G4:1 F4:1 E4:1 D4:1
      C4:1 C4:1 D4:1 E4:1  D4:1.5 C4:.5 C4:2`,
    chords: `
      C C C G  C C G G
      C C C G  C C G C
      G C G C  G G C G
      C C C G  C C G C`
  },
  {
    id: "lavachicken",
    title: "あつあつ溶岩チキン",
    composer: "オリジナル曲",
    color: "#ff8a3d",
    bpm: 132,
    repeatOctaves: [0, 0, 0, 0],
    melody: `
      C5:.5 C5:.5 E5:.5 G5:.5 A5:1 G5:1
      E5:.5 E5:.5 G5:.5 E5:.5 D5:1 C5:1
      F5:.5 F5:.5 A5:.5 F5:.5 G5:1 E5:1
      D5:.5 E5:.5 D5:.5 B4:.5 G4:2
      C5:.5 C5:.5 E5:.5 G5:.5 A5:1 G5:1
      C6:1 B5:.5 A5:.5 G5:1 E5:1
      F5:.5 A5:.5 G5:.5 E5:.5 D5:1 G5:1
      C5:1 C5:.5 C5:.5 C5:1 R:1`,
    chords: `
      C C C G  F C G G
      C C A C  F G C C`
  },
  {
    id: "matcha",
    title: "まっちゃビート",
    composer: "オリジナル曲",
    color: "#8fe36b",
    bpm: 140,
    repeatOctaves: [0, 0, 0, 0],
    melody: `
      A4:1 C5:1 E5:1 D5:.5 C5:.5
      D5:1 C5:.5 A4:.5 G4:1 A4:1
      A4:.5 C5:.5 E5:1 G5:1 E5:1
      D5:.5 E5:.5 D5:.5 C5:.5 A4:2
      F4:1 A4:1 C5:1 A4:.5 C5:.5
      G4:1 B4:1 D5:1 B4:.5 D5:.5
      E5:.5 E5:.5 D5:.5 C5:.5 B4:1 G4:1
      A4:1 A4:.5 A4:.5 A4:2`,
    chords: `
      A A A G  A A A A
      F F G G  E G A A`
  },
  {
    id: "cancan",
    title: "天国と地獄",
    composer: "オッフェンバック",
    color: "#ff6fa8",
    bpm: 152,
    repeatOctaves: [0, 1, 0, 1],
    melody: `
      C4:1 C4:1  D4:.5 F4:.5 E4:.5 D4:.5
      G4:1 G4:1  G4:.5 A4:.5 E4:.5 F4:.5
      D4:1 D4:1  D4:.5 F4:.5 E4:.5 D4:.5
      C4:.5 C5:.5 B4:.5 A4:.5  G4:.5 F4:.5 E4:.5 D4:.5
      C4:1 C4:1  D4:.5 F4:.5 E4:.5 D4:.5
      G4:1 G4:1  G4:.5 A4:.5 E4:.5 F4:.5
      D4:1 D4:1  D4:.5 F4:.5 E4:.5 D4:.5
      C4:.5 G4:.5 D4:.5 E4:.5  C4:1 C4:1`,
    chords: `
      C G C C  G G C G
      C G C C  G G G C`
  }
];
