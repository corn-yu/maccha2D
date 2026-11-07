package com.maccha.ikioiken;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 剣の向きをクライアントからサーバーへ送るパケット（長押し中は毎 tick 送る）。
 */
public record BladePayload(boolean holding, float yaw, float pitch) implements CustomPacketPayload {

    // パケットの名前
    public static final Type<BladePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IkioiKen.MOD_ID, "blade"));

    // 送るときの並び順（長押し中か・剣の左右の向き・剣の上下の向き）
    public static final StreamCodec<ByteBuf, BladePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, BladePayload::holding,
            ByteBufCodecs.FLOAT, BladePayload::yaw,
            ByteBufCodecs.FLOAT, BladePayload::pitch,
            BladePayload::new);

    /** このパケットの種類を返す。 */
    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
