package com.maccha.ikioiken;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 当てた場所と与えたダメージを、サーバーから本人の画面へ伝えるパケット（相手から数字が出る）。
 * style: 0 = ふつう、1 = 強い当たり、2 = 超クリティカル
 */
public record DamagePayload(double x, double y, double z, float damage, int style) implements CustomPacketPayload {

    // 数字の見た目の種類
    public static final int NORMAL = 0;
    public static final int STRONG = 1;
    public static final int CRITICAL = 2;

    // パケットの名前
    public static final Type<DamagePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IkioiKen.MOD_ID, "damage"));

    // 送るときの並び順（場所・ダメージ・見た目）
    public static final StreamCodec<ByteBuf, DamagePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, DamagePayload::x,
            ByteBufCodecs.DOUBLE, DamagePayload::y,
            ByteBufCodecs.DOUBLE, DamagePayload::z,
            ByteBufCodecs.FLOAT, DamagePayload::damage,
            ByteBufCodecs.VAR_INT, DamagePayload::style,
            DamagePayload::new);

    /** このパケットの種類を返す。 */
    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
