package com.maccha.zombiepanic;

/**
 * パニックの種類（どれが起きるかはランダム）。
 */
public enum PanicType {
    NORMAL("normal", "ゾンビパニック", "ゾンビが あふれだした！ 生きのびろ！"),
    HORDE("horde", "ゾンビ大行進", "いつもの2倍のゾンビが おしよせてくる！"),
    RUNNERS("runners", "走るゾンビ", "ゾンビが 走ってくる！ にげ足に気をつけて！"),
    BOSS("boss", "ゾンビ大将の襲来", "ボスゾンビを たおせば パニックが終わる！"),
    ARMORED("armored", "鉄の軍団", "鉄のよろいを着たゾンビが せめてくる！"),
    GIANTS("giants", "巨人の行進", "大きなゾンビが ずしずし歩いてくる！"),
    BABIES("babies", "ちびゾンビの群れ", "すばしっこい ちびゾンビが いっぱい！"),
    JUMPERS("jumpers", "とびはねるゾンビ", "ゾンビが かべを とびこえてくる！"),
    DIGGERS("diggers", "地の底から", "足もとから ゾンビが わいてくる！"),
    INVISIBLE("invisible", "見えないゾンビ", "ゾンビの すがたが 見えない…！ 音をたよりに！"),
    BLOOD_MOON("blood_moon", "血の月", "夜になり、ゾンビが 強くなった！"),
    DROWNED_RAID("drowned", "水底からの襲撃", "三又のほこを持ったドラウンドが やってくる！"),
    VILLAGERS("villagers", "村人ゾンビの行進", "村人ゾンビの むれだ！ 治せるかも？"),
    EXPLODERS("exploders", "爆発ゾンビ", "たおすと ばくはつする！ 近くでたおすな！"),
    SPLITTERS("splitters", "分裂ゾンビ", "たおすと ちびゾンビに 分かれる！"),
    THUNDER("thunder", "雷の夜", "雷とともに ゾンビが 落ちてくる！"),
    GOLD_RUSH("gold_rush", "ゴールドラッシュ", "金のかぶとのゾンビは たからを落とす！ 今がチャンス！"),
    ELITE("elite", "特殊ゾンビの日", "すべてのゾンビが 特殊能力を持っている！");

    public final String commandName;
    public final String title;
    public final String description;

    /** 種類の名前と、遊ぶ人に見せる文を決める。 */
    PanicType(String commandName, String title, String description) {
        this.commandName = commandName;
        this.title = title;
        this.description = description;
    }

    /** 数の倍率を返す（大行進・ちびは多く、巨人は少ない）。 */
    public double amountMultiplier() {
        return switch (this) {
            case HORDE -> PanicConfig.HORDE_MULTIPLIER;
            case BABIES -> 1.5;
            case GIANTS -> 0.5;
            default -> 1.0;
        };
    }
}
