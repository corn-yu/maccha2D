package com.maccha.ikioiken.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.maccha.ikioiken.client.SwingClient;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;

/**
 * 一人称の手と剣を、いきおい剣の動きで描くための差しこみ。
 */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class FirstPersonHandsAndItemsRendererMixin {

    /** 手と持ち物を描くはじめに、剣を振っているときは自分の描き方に切りかえる。 */
    @Inject(method = "submitHandsWithItems", at = @At("HEAD"), cancellable = true)
    private void ikioiken$submitSword(float partialTick, PoseStack poseStack, SubmitNodeCollector collector,
                                      PlayerRenderState playerState, FirstPersonHandsAndItemsRenderState handsState,
                                      CallbackInfo callbackInfo) {
        if (SwingClient.renderFirstPerson(poseStack, collector, playerState)) {
            callbackInfo.cancel();
        }
    }
}
