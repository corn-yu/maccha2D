package com.maccha.zombiepanic;

/**
 * ゾンビパニックの設定（数字を変えたいときは、ここだけ直せばよい）。
 */
public final class PanicConfig {

    // 何もない時間（分）: この間のランダムな時間がたつと、パニックの前ぶれが始まる
    public static final int CALM_MIN_MINUTES = 15;
    public static final int CALM_MAX_MINUTES = 30;

    // 前ぶれの長さ（秒）: うめき声が聞こえてから、パニックが始まるまで
    public static final int WARNING_SECONDS = 20;

    // パニックの長さ（分）
    public static final int PANIC_MINUTES = 5;

    // ゾンビを出す間隔（tick、20tick = 1秒）
    public static final int SPAWN_INTERVAL_TICKS = 20;

    // 1回で1人のまわりに出すゾンビの数（はじめ → 終わりごろ）
    public static final int WAVE_SIZE_START = 2;
    public static final int WAVE_SIZE_END = 5;

    // 1人のまわり（COUNT_RADIUS ブロック）にいてよいパニックゾンビの数
    public static final int MAX_ZOMBIES_NEAR_PLAYER = 35;
    public static final double COUNT_RADIUS = 48.0;

    // ゾンビを出す場所: プレイヤーからの距離（ブロック）
    public static final int SPAWN_DISTANCE_MIN = 16;
    public static final int SPAWN_DISTANCE_MAX = 36;

    // ほかのプレイヤーの目の前には出さない（この距離より近いところには出さない）
    public static final double SAFE_DISTANCE_FROM_PLAYERS = 12.0;

    // ゾンビがプレイヤーに気づく距離（ふつうは35）
    public static final double ZOMBIE_FOLLOW_RANGE = 64.0;

    // 「ゾンビ大行進」の数の倍率
    public static final double HORDE_MULTIPLIER = 2.0;

    // 「走るゾンビ」の速さ（スピード効果のレベル、0 = スピードI）
    public static final int RUNNER_SPEED_LEVEL = 1;

    // ゾンビ大将の強さ
    public static final double BOSS_HEALTH = 300.0;
    public static final double BOSS_ATTACK_DAMAGE = 12.0;
    public static final double BOSS_MOVEMENT_SPEED = 0.27;
    public static final double BOSS_SCALE = 1.8;
    // ゾンビ大将が出てくるまでの時間（パニック開始から、秒）
    public static final int BOSS_APPEAR_SECONDS = 30;

    // ゾンビ大将をたおしたときのごほうび
    public static final int REWARD_GOLDEN_APPLES = 2;
    public static final int REWARD_DIAMONDS = 3;

    private PanicConfig() {
    }
}
