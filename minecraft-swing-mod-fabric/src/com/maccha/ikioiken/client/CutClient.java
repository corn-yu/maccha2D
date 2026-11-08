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

    // 直前に部位を消した描画があったか(あとの描画で元にもどすため)
    private static boolean wasHidden = false;
}
