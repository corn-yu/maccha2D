package com.maccha.ikioiken;

/**
 * いきおい剣の設定値をぜんぶここにまとめたクラス（強さを変えたいときはここだけ直す）。
 */
public final class SwingConfig {

    private SwingConfig() {
    }

    // ===== 剣の動き（見た目と操作） =====

    // 手をはなしているときの剣の向き（視線から右へ何度・上へ何度。上はマイナス）
    public static final float REST_YAW = 15.0f;
    public static final float REST_PITCH = -20.0f;

    // 長押し中に剣を動かせる広さ（視線から左右・上下に何度まで）
    public static final float MAX_OFFSET_YAW = 80.0f;
    public static final float MAX_OFFSET_PITCH = 75.0f;

    // 長押し中、マウスの動きのうち何割をカメラにも回すか（0 = カメラは止まる）
    public static final float CAMERA_FOLLOW = 0.15f;

    // 手をはなしたあと、剣が元の構えにもどる速さ（1 tick あたりの割合）
    public static final float RETURN_SPEED = 0.25f;

    // 一人称で剣を持つ手の位置と、剣の大きさ
    public static final float HAND_X = 0.45f;
    public static final float HAND_Y = -0.5f;
    public static final float HAND_Z = -0.6f;
    public static final float SWORD_SCALE = 1.1f;

    // ===== 当たり判定 =====

    // 肩から見た刃の始まりと先っぽの距離（ブロック）
    public static final double BLADE_START = 0.5;
    public static final double BLADE_LENGTH = 3.0;

    // 肩の位置（目からどれだけ下・右にずらすか）
    public static final double SHOULDER_DROP = 0.3;
    public static final double SHOULDER_SIDE = 0.3;

    // 1 tick の振りを何度ごとに区切って当たりを調べるか
    public static final double SWEEP_STEP_DEGREES = 4.0;

    // 1 tick に回せる角度の上限（ずる防止）
    public static final double MAX_TURN_DEGREES_PER_TICK = 90.0;

    // ===== ダメージ =====

    // これより遅い当たりはダメージなし（m/s）
    public static final double MIN_HIT_SPEED = 3.0;

    // この速さで当てると、ふつうの剣と同じダメージ（m/s）
    public static final double REFERENCE_SPEED = 14.0;

    // 速さによるダメージ倍率の上限（ふつうのクリティカルと同じ 1.5 倍まで）
    public static final double MAX_SPEED_FACTOR = 1.5;

    // かすっただけ（斜めの当たり）のときの最低倍率
    public static final double ANGLE_MIN_FACTOR = 0.4;

    // 1 回の振りで何体も切ったとき、2 体目からの勢いの残り方
    public static final double MULTI_HIT_FALLOFF = 0.7;

    // 同じ相手にもう一度当たるまでの待ち時間（tick、20 で 1 秒）
    public static final int HIT_COOLDOWN_TICKS = 10;

    // ノックバックの強さ（速さ倍率をかけて使う）
    public static final float KNOCKBACK_BASE = 0.4f;

    // これより速く振ると「ブンッ」と音が鳴る（剣先の速さ m/s）
    public static final double WHOOSH_SPEED = 12.0;

    // 当てたときに画面下へ勢いとダメージを出すか
    public static final boolean SHOW_DAMAGE_MESSAGE = true;
}
