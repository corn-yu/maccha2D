package com.maccha.ikioiken.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.maccha.ikioiken.EnemyStrike;
import com.maccha.ikioiken.IncomingDamage;
import com.maccha.ikioiken.ServerSwing;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/**
 * 生き物がダメージを受ける直前に、敵の「ため → 振り」への変更と、剣のガード・パリィを差しこむ。
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    // へらしたダメージで受けなおしている最中か（くりかえし止めないため）
    private static boolean ikioiken$reapplying = false;

    /** ダメージを受ける最初に、止めたり、へらしたりする。 */
    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void ikioiken$beforeHurt(ServerLevel level, DamageSource source, float amount,
                                     CallbackInfoReturnable<Boolean> callbackInfo) {
        if (ikioiken$reapplying) {
            return;
        }
        LivingEntity self = (LivingEntity) (Object) this;

        // 敵の殴りを横取りして、そのあと剣のガード・パリィを調べる
        IncomingDamage damage = new IncomingDamage(self, source, amount);
        EnemyStrike.onIncomingDamage(damage);
        if (!damage.isCanceled()) {
            ServerSwing.interceptDamage(damage);
        }

        // 全部止めた
        if (damage.isCanceled()) {
            callbackInfo.setReturnValue(false);
            return;
        }

        // 一部だけ止めた：へらしたダメージで受けなおす
        if (damage.getAmount() != amount) {
            ikioiken$reapplying = true;
            try {
                callbackInfo.setReturnValue(self.hurtServer(level, source, damage.getAmount()));
            } finally {
                ikioiken$reapplying = false;
            }
        }
    }
}
