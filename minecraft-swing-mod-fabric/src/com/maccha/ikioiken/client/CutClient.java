package com.maccha.ikioiken.client;

import com.maccha.ikioiken.CutParts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.Entity;

/**
 * 切り落とされた部位を、人の形のモデルから消す。
 */
public final class CutClient {

    private CutClient() {
    }

    /** モデルの動きが決まったあと、切り落とされた部位を見えなくする(されていない部位は見えるまま)。 */
    public static void hideCutParts(HumanoidModel<?> model, HumanoidRenderState state) {
        int mask = 0;
        Minecraft minecraft = Minecraft.getInstance();
        int id = ((EntityIdHolder) state).ikioiken$getId();
        if (minecraft.level != null && id >= 0) {
            Entity entity = minecraft.level.getEntity(id);
            if (entity != null) {
                mask = CutParts.maskOf(entity);
            }
        }
        // 何も切られていない生き物は、ほかの Mod の表示設定をじゃましないよう何もしない
        if (mask == 0 && !wasHidden) {
            return;
        }
        model.head.visible = (mask & CutParts.HEAD) == 0;
        model.hat.visible = (mask & CutParts.HEAD) == 0;
        model.rightArm.visible = (mask & CutParts.RIGHT_ARM) == 0;
        model.leftArm.visible = (mask & CutParts.LEFT_ARM) == 0;
        model.rightLeg.visible = (mask & CutParts.RIGHT_LEG) == 0;
        model.leftLeg.visible = (mask & CutParts.LEFT_LEG) == 0;
        wasHidden = mask != 0;
    }

    // 部位の名前(モデルの直下にある部品)と、対応する部位の目印。四つ足の前足は腕、後ろ足は足として扱う
    private static final String[][] PART_NAMES = {
            {"head", "1"},
            {"right_arm", "2"}, {"right_front_leg", "2"},
            {"left_arm", "4"}, {"left_front_leg", "4"},
            {"right_leg", "8"}, {"right_hind_leg", "8"},
            {"left_leg", "16"}, {"left_hind_leg", "16"},
    };

    // 消したことがある部品(あとで見えるようにもどすため)
    private static final java.util.Set<net.minecraft.client.model.geom.ModelPart> TOUCHED =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /** どんな形の生き物でも、切り落とされた部位に当たる部品(頭・腕・足・前足・後ろ足)を見えなくする。 */
    public static void hideCutPartsGeneric(net.minecraft.client.model.Model<?> model, net.minecraft.client.renderer.entity.state.EntityRenderState state) {
        Minecraft minecraft = Minecraft.getInstance();
        int id = ((EntityIdHolder) state).ikioiken$getId();
        int mask = 0;
        if (minecraft.level != null && id >= 0) {
            Entity entity = minecraft.level.getEntity(id);
            if (entity != null) {
                mask = CutParts.maskOf(entity);
            }
        }
        net.minecraft.client.model.geom.ModelPart root = model.root();
        for (String[] names : PART_NAMES) {
            if (!root.hasChild(names[0])) {
                continue;
            }
            net.minecraft.client.model.geom.ModelPart part = root.getChild(names[0]);
            boolean cut = (mask & Integer.parseInt(names[1])) != 0;
            if (cut) {
                part.visible = false;
                TOUCHED.add(part);
            } else if (TOUCHED.remove(part)) {
                part.visible = true;
            }
        }
    }

    // 直前に部位を消した描画があったか(あとの描画で元にもどすため)
    private static boolean wasHidden = false;
}
