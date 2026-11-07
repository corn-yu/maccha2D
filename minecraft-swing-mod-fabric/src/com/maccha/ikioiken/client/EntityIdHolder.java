package com.maccha.ikioiken.client;

/**
 * 描くための情報（レンダー状態）に「どの生き物か」と「何 tick 目か」をくっつけるための入れ物。
 */
public interface EntityIdHolder {

    /** 生き物の番号を返す。 */
    int ikioiken$getId();

    /** 生き物の番号を覚える。 */
    void ikioiken$setId(int id);

    /** 描くときの半端な時間（0〜1）を返す。 */
    float ikioiken$getPartialTick();

    /** 描くときの半端な時間を覚える。 */
    void ikioiken$setPartialTick(float partialTick);
}
