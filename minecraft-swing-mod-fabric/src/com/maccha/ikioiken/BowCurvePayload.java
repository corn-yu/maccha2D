package com.maccha.ikioiken;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 弓を引いている間の、マウスの動き（1 tick に何度動いたか）をクライアントからサーバーへ送るパケット。
 */
public record BowCurvePayload(float yawSpeed, float pitchSpeed) implements CustomPacketPayload {

    // パケットの名前
    public static final Type<BowCurvePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(IkioiKen.MOD_ID, "bow_curve"));

    // 送るときの並び順（左右の動き・上下の動き）
    public static final StreamCodec<ByteBuf, BowCurvePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.FLOAT, BowCurvePayload::yawSpeed,
            ByteBufCodecs.FLOAT, BowCurvePayload::pitchSpeed,
            BowCurvePayload::new);

    /** このパケットの種類を返す。 */
    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
