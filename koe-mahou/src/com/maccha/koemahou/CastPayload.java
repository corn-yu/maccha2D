package com.maccha.koemahou;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * クライアントからサーバーへ送る「詠唱がここまで進んだ」のお知らせ
 * step が total と同じなら魔法を出す。step が -1 なら詠唱失敗
 */
public record CastPayload(String spell, int step, int total) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<CastPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("koemahou", "cast"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CastPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, CastPayload::spell,
            ByteBufCodecs.VAR_INT, CastPayload::step,
            ByteBufCodecs.VAR_INT, CastPayload::total,
            CastPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
