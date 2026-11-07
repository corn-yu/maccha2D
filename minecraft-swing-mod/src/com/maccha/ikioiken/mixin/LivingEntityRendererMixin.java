package com.maccha.ikioiken.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.maccha.ikioiken.client.MotionClient;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;

/**
 * 生き物のモデルの動きが決まった直後に、いきおい剣のモーション（腕の動き）を足すための差しこみ。
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {

    /** モデルの動き（setupAnim）が終わったら、腕をモーションに合わせる。 */
    @Inject(
            method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/world/entity/Entity;FFFFF)V",
                    shift = At.Shift.AFTER))
    private void ikioiken$afterSetupAnim(LivingEntity entity, float entityYaw, float partialTick,
                                         PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                                         CallbackInfo callbackInfo) {
        MotionClient.adjustModel(((LivingEntityRenderer<?, ?>) (Object) this).getModel(), entity, partialTick);
    }
}
