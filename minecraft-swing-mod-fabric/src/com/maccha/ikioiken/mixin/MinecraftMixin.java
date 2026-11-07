package com.maccha.ikioiken.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.maccha.ikioiken.client.SwingClient;
import net.minecraft.client.Minecraft;

/**
 * 剣モード中は、ふつうの左クリックの攻撃・採掘を出さないための差しこみ。
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    /** クリックしたときの攻撃を止める。 */
    @Inject(method = "startAttack()Z", at = @At("HEAD"), cancellable = true)
    private void ikioiken$noStartAttack(CallbackInfoReturnable<Boolean> callbackInfo) {
        if (SwingClient.shouldBlockAttack()) {
            callbackInfo.setReturnValue(false);
        }
    }

    /** 押しっぱなしの採掘を止める。 */
    @Inject(method = "continueAttack(Z)V", at = @At("HEAD"), cancellable = true)
    private void ikioiken$noContinueAttack(boolean leftClick, CallbackInfo callbackInfo) {
        if (SwingClient.shouldBlockAttack()) {
            callbackInfo.cancel();
        }
    }

    /** 左手持ちの剣モード中は、ふつうの右クリック（使う・置く）を出さない。 */
    @Inject(method = "startUseItem()V", at = @At("HEAD"), cancellable = true)
    private void ikioiken$noStartUse(CallbackInfo callbackInfo) {
        if (SwingClient.shouldBlockUse()) {
            callbackInfo.cancel();
        }
    }
}
