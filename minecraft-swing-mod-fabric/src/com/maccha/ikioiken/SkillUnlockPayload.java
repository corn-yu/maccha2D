package com.maccha.ikioiken;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 「このスキルを覚えたい」とクライアントからサーバーへ伝えるパケット。
 */
public record SkillUnlockPayload(String skillId) implements CustomPacketPayload {

    // パケットの名前
    public static final Type<SkillUnlockPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(IkioiKen.MOD_ID, "skill_unlock"));

    // 送るときの並び順（スキルの名前）
    public static final StreamCodec<ByteBuf, SkillUnlockPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, SkillUnlockPayload::skillId,
            SkillUnlockPayload::new);

    /** このパケットの種類を返す。 */
    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
