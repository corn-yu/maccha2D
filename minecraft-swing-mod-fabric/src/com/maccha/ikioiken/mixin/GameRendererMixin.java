package com.maccha.ikioiken.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.maccha.ikioiken.client.SwingClient;
import net.minecraft.client.renderer.GameRenderer;

/**
 * 毎フレームのはじめに、剣の動き（マウスの動き → 手 → 剣）を進めるための差しこみ。
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    /** 1 フレームを描くはじめに、剣を動かす（マウスの動きは、この直前に取りこまれている）。 */
    @Inject(method = "render()V", at = @At("HEAD"))
    private void ikioiken$beforeRender(CallbackInfo callbackInfo) {
        SwingClient.onRenderFrame();
    }
}
