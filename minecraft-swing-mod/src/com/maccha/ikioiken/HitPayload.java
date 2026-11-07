package com.maccha.ikioiken;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 当てた・防いだことをサーバーから本人の画面へ伝えるパケット（ヒットストップと画面ゆれに使う）。
 */
public record HitPayload(float strength) implements CustomPacketPayload {

    // パケットの名前
    public static final Type<HitPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IkioiKen.MOD_ID, "hit"));

    // 送るときの並び順（手ごたえの強さ）
    public static final StreamCodec<ByteBuf, HitPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.FLOAT, HitPayload::strength,
            HitPayload::new);

    /** このパケットの種類を返す。 */
    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
