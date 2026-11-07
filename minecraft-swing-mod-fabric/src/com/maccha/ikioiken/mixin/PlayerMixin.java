package com.maccha.ikioiken.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.maccha.ikioiken.ServerSwing;
import net.minecraft.world.entity.player.Player;

/**
 * いきおい剣の攻撃では、ふつうの「なぎ払い」を出さないための差しこみ（勢いで何体も切れるので）。
 */
@Mixin(Player.class)
public abstract class PlayerMixin {

    /** なぎ払いかどうかを決める所で、いきおい剣の攻撃中なら「なぎ払いではない」にする。 */
    @Inject(method = "isSweepAttack", at = @At("HEAD"), cancellable = true)
    private void ikioiken$noSweep(boolean strong, boolean crit, boolean sprintKnockback,
                                  CallbackInfoReturnable<Boolean> callbackInfo) {
        if (ServerSwing.isAttackingWithMomentum()) {
            callbackInfo.setReturnValue(false);
        }
    }
}
