package com.maccha.zombiepanic;

/**
 * ゾンビの呪いの世界の設定（数字を変えたいときは、ここだけ直せばよい）。
 */
public final class PanicConfig {

    // ---------------- いつものゾンビ（呪いがとけるまで、ずっと） ----------------

    // いつものゾンビを出す間隔（tick、20tick = 1秒）
    public static final int BASE_SPAWN_INTERVAL_TICKS = 40;
    // いつものゾンビ: 1回で1人のまわりに出す数
    public static final int BASE_WAVE_SIZE = 2;
    // いつものゾンビ: 1人のまわりにいてよい数
    public static final int BASE_MAX_NEAR_PLAYER = 18;

    // ---------------- パニック（たまに起きる大きなイベント） ----------------

    // 何もない時間（分）: この間のランダムな時間がたつと、パニックの前ぶれが始まる
    public static final int CALM_MIN_MINUTES = 8;
    public static final int CALM_MAX_MINUTES = 16;
    // 前ぶれの長さ（秒）
    public static final int WARNING_SECONDS = 20;
    // パニックの長さ（分）
    public static final int PANIC_MINUTES = 4;
    // パニックのゾンビを出す間隔（tick）
    public static final int PANIC_SPAWN_INTERVAL_TICKS = 20;
    // パニック: 1回で1人のまわりに出す数（はじめ → 終わりごろ）
    public static final int WAVE_SIZE_START = 2;
    public static final int WAVE_SIZE_END = 5;
    // パニック: 1人のまわりにいてよい数
    public static final int MAX_ZOMBIES_NEAR_PLAYER = 35;

    // ---------------- ゾンビを出す場所 ----------------

    // 数えるはんい（ブロック）
    public static final double COUNT_RADIUS = 48.0;
    // プレイヤーからの距離（ブロック）
    public static final int SPAWN_DISTANCE_MIN = 16;
    public static final int SPAWN_DISTANCE_MAX = 36;
    // だれかの目の前には出さない（この距離より近いところには出さない）
    public static final double SAFE_DISTANCE_FROM_PLAYERS = 12.0;
    // ゾンビがプレイヤーに気づく距離（ふつうは35）
    public static final double ZOMBIE_FOLLOW_RANGE = 64.0;

    // ---------------- 特殊能力ゾンビ ----------------

    // 特殊能力（10体に1体）・強い特殊能力（100体に1体）・最強（1000体に1体）
    public static final int ABILITY_CHANCE = 10;
    public static final int STRONG_ABILITY_CHANCE = 100;
    public static final int STRONGEST_ABILITY_CHANCE = 1000;

    // 能力ゾンビをたおすと 能力をもらえる確率（○回に1回）: 特殊・強い特殊・最強
    public static final int STEAL_CHANCE_TIER1 = 5;
    public static final int STEAL_CHANCE_TIER2 = 3;
    public static final int STEAL_CHANCE_TIER3 = 1;

    // ---------------- ゾンビのレベル（たくさん遊ぶほど 高いレベルが出る） ----------------

    // あそんだ時間 ○tick（20tick = 1秒）ごとに、出るレベルの上限が1ふえる（1200 = 1分）
    public static final int LEVEL_TICKS_PER_STEP = 20 * 60;
    // レベルのかたより（大きいほど 低いレベルが多く、高いレベルはまれ）
    public static final int LEVEL_LUCK_POWER = 5;
    // 上限をこえる「とつぜん変異」の確率（○体に1体）
    public static final int LEVEL_MUTATION_CHANCE = 300;
    // この数字以上のレベルで、能力の段が上がる（段1・段2・段3）
    public static final int LEVEL_FOR_TIER1 = 25;
    public static final int LEVEL_FOR_TIER2 = 60;
    public static final int LEVEL_FOR_TIER3 = 100;
    // 1レベルごとの強さ: 体力は +6%、攻撃は +0.08、かたさは +0.12（上限20）、足の速さは +0.3%（上限30%）
    public static final double LEVEL_HEALTH_PER_LEVEL = 0.06;
    public static final double LEVEL_ATTACK_PER_LEVEL = 0.08;
    public static final double LEVEL_ARMOR_PER_LEVEL = 0.12;
    public static final double LEVEL_SPEED_PER_LEVEL = 0.003;
    // 能力を1こ足すのに必要なレベル（Lv15で1こ、Lv30で2こ…）と、足せる数の上限
    public static final int LEVEL_PER_EXTRA_ABILITY = 15;
    public static final int LEVEL_MAX_EXTRA_ABILITIES = 8;
    // いちばん高いレベル
    public static final int LEVEL_MAX = 999;

    // ---------------- たおした数で出るボス ----------------

    // ボスが出る数（300体ごと。BOSS_KILL_STEP_GROW を足すと だんだん間があく）
    public static final int FIRST_BOSS_KILLS = 300;
    public static final int BOSS_KILL_STEP = 300;
    public static final int BOSS_KILL_STEP_GROW = 0;
    // ボスが出てくるまでの時間（「ゾンビ大将の襲来」のとき、パニック開始から秒）
    public static final int BOSS_APPEAR_SECONDS = 30;

    // ---------------- そのほか ----------------

    // 「ゾンビ大行進」の数の倍率
    public static final double HORDE_MULTIPLIER = 2.0;
    // 呪いのあいだ、ゾンビが燃えない時間（tick、出たときにつく）
    public static final int CURSE_FIRE_RESIST_TICKS = 20 * 60 * 20;
    // ゾンビドラゴンの体力（ふつうのエンダードラゴンは200）
    public static final double DRAGON_HEALTH = 400.0;
    // ゾンビドラゴンがゾンビを呼ぶ間隔（tick）
    public static final int DRAGON_SUMMON_INTERVAL_TICKS = 200;

    private PanicConfig() {
    }
}
