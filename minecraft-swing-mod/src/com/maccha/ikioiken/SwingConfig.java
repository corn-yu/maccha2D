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

    // 剣の重さ（マウスに追いつく強さと、ゆれの止まりやすさ）。小さいほど重くておくれる
    public static final float SWING_STIFFNESS = 0.6f;
    public static final float SWING_DAMPING = 0.45f;

    // 武器の重さ：攻撃速度がこれ（ふつうの剣 1.6）より遅い武器ほど重く、速い武器ほど軽く動く（倍率の範囲）
    public static final float NORMAL_ATTACK_SPEED = 1.6f;
    public static final float MIN_WEIGHT_FACTOR = 0.45f;
    public static final float MAX_WEIGHT_FACTOR = 1.4f;

    // 手をはなしたあと、剣が元の構えにもどる強さ（ふりぬいてからゆっくりもどる）
    public static final float RETURN_STIFFNESS = 0.12f;

    // 手をはなしても、勢いで切れる時間（tick）
    public static final int FOLLOW_THROUGH_TICKS = 6;

    // 当てた瞬間に剣が止まる時間（tick、ヒットストップ）と画面ゆれの強さ（度）
    public static final float HITSTOP_TICKS = 2.0f;
    public static final float SHAKE_DEGREES = 2.5f;

    // 一人称の剣の大きさ
    public static final float SWORD_SCALE = 1.1f;

    // 一人称の腕：肩の位置（画面の右・上・うしろ）と腕の長さ（ブロック）。
    // 手は肩を中心に剣の向きへ回るので、手首ではなく腕ごと振って見える
    public static final float ARM_SHOULDER_X = 0.36f;
    public static final float ARM_SHOULDER_Y = -0.55f;
    public static final float ARM_SHOULDER_Z = 0.1f;
    public static final float ARM_LENGTH = 0.7f;

    // 腕を剣よりどれだけ下に向けるか（度）。剣は手首で少し上を向く
    public static final float ARM_PITCH_OFFSET = 25.0f;

    // ===== 当たり判定 =====

    // 肩から見た刃の始まりと先っぽの距離（ブロック）。先っぽは武器で届く距離に合わせる（この範囲で）
    public static final double BLADE_START = 0.5;
    public static final double BLADE_LENGTH = 3.0;
    public static final double MIN_BLADE_LENGTH = 2.0;
    public static final double MAX_BLADE_LENGTH = 6.0;

    // 1 tick に肩がこれ以上動いたら（テレポートなど）、振りをはじめからやりなおす
    public static final double MAX_SHOULDER_JUMP = 2.5;

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

    // 勢いの向きに足すノックバックの強さ（ふつうのノックバックとは別、速さ倍率をかけて使う）
    public static final float KNOCKBACK_BASE = 0.3f;

    // これより速く振ると「ブンッ」と音が鳴る（剣先の速さ m/s）
    public static final double WHOOSH_SPEED = 12.0;

    // 持つと攻撃力がこれ以上あがる物は、タグがなくても武器として振れる（木の剣は +3）
    public static final double MIN_WEAPON_DAMAGE = 2.0;

    // これより速く振ると、剣の通り道に軌跡が出る（剣先の速さ m/s）
    public static final double TRAIL_SPEED = 10.0;

    // ===== ガード =====

    // 剣が「相手 → 自分」の攻撃の線からこの距離（ブロック）以内にあれば、攻撃が剣に当たる
    public static final double BLADE_BLOCK_RADIUS = 0.45;

    // 剣先が 1 tick にこれより動かない状態が、この tick 続くとガード（止めて構えている）
    public static final double GUARD_MAX_TURN_DEGREES = 4.0;
    public static final int GUARD_READY_TICKS = 3;

    // 止めるダメージの割合：構えているとき・動かしているとき
    public static final float GUARD_BLOCK_RATIO = 1.0f;
    public static final float MOVING_BLOCK_RATIO = 0.5f;

    // 止めたダメージ何点で武器の耐久が 1 減るか
    public static final float GUARD_DAMAGE_PER_DURABILITY = 2.0f;

    // ===== パリィ =====

    // 攻撃が来る直前この tick 以内に、この速さ以上で振っていたらパリィ（剣先の速さ m/s）
    public static final int PARRY_WINDOW_TICKS = 5;
    public static final double PARRY_SPEED = 10.0;

    // パリィした相手がよろける時間（tick）と、はね返した矢の速さの倍率
    public static final int PARRY_STUN_TICKS = 40;
    public static final double PARRY_REFLECT_SPEED = 1.2;

    // パリィのあと、この時間（tick）以内の一撃は強くなる（倍率）
    public static final int COUNTER_TICKS = 30;
    public static final double COUNTER_BONUS = 1.5;

    // ===== 敵の攻撃 =====

    // 敵の殴りを「ためてから振る」攻撃に変えるか
    public static final boolean ENEMY_REAL_ATTACK = true;

    // 敵がためる時間（tick、20 で 1 秒）。この間によけたり、剣を入れたりできる
    public static final int ENEMY_WINDUP_TICKS = 8;

    // 敵の正面からこの角度の外へ回りこめば当たらない
    public static final double ENEMY_STRIKE_ARC_DEGREES = 80.0;

    // 元の攻撃のままにする Mod（カタリズムの敵は、もともと攻撃の前に動きがあるのでそのまま）
    public static final String[] ENEMY_SKIP_MODS = {"cataclysm"};

    // ===== 道具でブロックを壊す =====

    // 基準の速さで 1 回当てると、ふつうに何 tick 掘ったのと同じになるか
    // （石を鉄のつるはしで約 3 回、丸太を鉄の斧で約 4 回、土を鉄のシャベルで約 1〜2 回）
    public static final float SWING_MINING_TICKS = 3.0f;

    // 同じブロックにもう一度当たるまでの間（tick）。1 回の振りで何度も当たらないようにする
    public static final int MINING_HIT_COOLDOWN_TICKS = 6;

    // この時間（tick）当てなかったブロックのひびは消える
    public static final int MINING_FORGET_TICKS = 100;

    // 1 tick に壊せるブロックの数の上限
    public static final int MAX_BLOCKS_PER_TICK = 3;

    // 画面下に文字（ダメージ・ガード・パリィ・よけた）を出すか（まっちゃの希望で出さない）
    public static final boolean SHOW_MESSAGES = false;
}
