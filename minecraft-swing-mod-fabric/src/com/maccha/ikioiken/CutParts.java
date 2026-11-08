package com.maccha.ikioiken;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;

/**
 * 切り落とされた体の部位の記録（頭・右腕・左腕・右足・左足）。まわりのプレイヤーの画面にも届けて、モデルから消す。
 */
public final class CutParts {

    private CutParts() {
    }

    // 部位の目印(ビット)
    public static final int HEAD = 1;
    public static final int RIGHT_ARM = 2;
    public static final int LEFT_ARM = 4;
    public static final int RIGHT_LEG = 8;
    public static final int LEFT_LEG = 16;

    // 切り落とした部位の記録(ワールドには保存しない・見える人みんなに同期する)
    public static final AttachmentType<Integer> MASK = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath(IkioiKen.MOD_ID, "cut_parts"),
            builder -> builder.syncWith(ByteBufCodecs.VAR_INT, AttachmentSyncPredicate.all()));

    /** 切り落とした部位の目印を返す(なければ 0)。 */
    public static int maskOf(Entity entity) {
        Integer mask = ((AttachmentTarget) entity).getAttached(MASK);
        return mask == null ? 0 : mask;
    }

    /** 部位を切り落としたことを記録する。 */
    public static void add(Entity entity, int part) {
        ((AttachmentTarget) entity).setAttached(MASK, maskOf(entity) | part);
    }
}
