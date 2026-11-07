package com.maccha.ikioiken.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.maccha.ikioiken.client.EntityIdHolder;
import com.maccha.ikioiken.client.MotionClient;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.LivingEntity;

/**
 * 生き物を描くとき、「どの生き物か」を覚えさせ、攻撃中の敵の体をかたむけるための差しこみ。
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {

    /** 描く情報を作ったあと、生き物の番号と半端な時間を覚えさせる。 */
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
            at = @At("TAIL"))
    private void ikioiken$rememberEntity(LivingEntity entity, LivingEntityRenderState state, float partialTick,
                                         CallbackInfo callbackInfo) {
        EntityIdHolder holder = (EntityIdHolder) state;
        holder.ikioiken$setId(entity.getId());
        holder.ikioiken$setPartialTick(partialTick);
    }

    /** 描きはじめに、攻撃中の敵の体全体をかたむけて踏みこませる。 */
    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At("HEAD"))
    private void ikioiken$beginLean(LivingEntityRenderState state, PoseStack poseStack,
                                    SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo callbackInfo) {
        MotionClient.beginLean(state, poseStack);
    }

    /** 描き終わったら、かたむけたのをもとにもどす。 */
    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At("RETURN"))
    private void ikioiken$endLean(LivingEntityRenderState state, PoseStack poseStack,
                                  SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo callbackInfo) {
        MotionClient.endLean(state, poseStack);
    }
}
