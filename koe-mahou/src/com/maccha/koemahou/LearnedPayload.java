package com.maccha.koemahou;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * サーバーからクライアントへ送る「このプレイヤーが覚えている魔法」のお知らせ
 * text は じゅもん.txt と同じ書きかた（[名前]・詠唱の行・＞効果の行）
 */
public record LearnedPayload(String text) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<LearnedPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("koemahou", "learned"));

    public static final StreamCodec<RegistryFriendlyByteBuf, LearnedPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(262144), LearnedPayload::text,
            LearnedPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
