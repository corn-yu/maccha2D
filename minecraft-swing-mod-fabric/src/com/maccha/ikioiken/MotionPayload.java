package com.maccha.ikioiken;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 体の動き（モーション）をサーバーからまわりの人の画面へ伝えるパケット。
 * kind: 0 = 敵がためはじめた、1 = 敵が振った、2 = プレイヤーの剣の向き、3 = プレイヤーが剣をおろした
 */
public record MotionPayload(int entityId, int kind, float first, float second) implements CustomPacketPayload {

    // モーションの種類
    public static final int ENEMY_WINDUP = 0;
    public static final int ENEMY_STRIKE = 1;
    public static final int PLAYER_BLADE = 2;
    public static final int PLAYER_BLADE_END = 3;
    // 左腕で剣をにぎっているときの、プレイヤーの剣の向き
    public static final int PLAYER_BLADE_LEFT = 4;

    // パケットの名前
    public static final Type<MotionPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(IkioiKen.MOD_ID, "motion"));

    // 送るときの並び順（だれの・何の動きか・数字 2 つ）
    public static final StreamCodec<ByteBuf, MotionPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, MotionPayload::entityId,
            ByteBufCodecs.VAR_INT, MotionPayload::kind,
            ByteBufCodecs.FLOAT, MotionPayload::first,
            ByteBufCodecs.FLOAT, MotionPayload::second,
            MotionPayload::new);

    /** このパケットの種類を返す。 */
    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
