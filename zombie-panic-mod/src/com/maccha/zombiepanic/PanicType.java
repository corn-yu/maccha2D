package com.maccha.zombiepanic;

/**
 * パニックの種類（どれが起きるかはランダム）。
 */
public enum PanicType {
    NORMAL("normal", "ゾンビパニック", "世界中に ゾンビが あふれだした！ 生きのびろ！"),
    HORDE("horde", "ゾンビ大行進", "いつもの2倍のゾンビが おしよせてくる！"),
    RUNNERS("runners", "走るゾンビ", "ゾンビが 走ってくる！ にげ足に気をつけて！"),
    BOSS("boss", "ゾンビ大将の襲来", "ゾンビ大将を たおせば パニックが終わる！");

    public final String commandName;
    public final String title;
    public final String description;

    /** 種類の名前と、遊ぶ人に見せる文を決める。 */
    PanicType(String commandName, String title, String description) {
        this.commandName = commandName;
        this.title = title;
        this.description = description;
    }

    /** 数の倍率を返す（大行進だけ多い）。 */
    public double amountMultiplier() {
        return this == HORDE ? PanicConfig.HORDE_MULTIPLIER : 1.0;
    }
}
