package com.maccha.zombiepanic.mixin;

import com.maccha.zombiepanic.SpawnFilter;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.level.ServerLevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 呪いのあいだ、ゾンビ以外の自然なわきを止める（場所チェックと、わく直前の2か所）。
 */
@Mixin(Mob.class)
public abstract class MobMixin {

    /** 場所チェックで 失敗にする。 */
    @Inject(method = "checkSpawnRules", at = @At("HEAD"), cancellable = true)
    private void zombiepanic$blockSpawnRules(net.minecraft.world.level.LevelAccessor level, EntitySpawnReason spawnReason,
                                             CallbackInfoReturnable<Boolean> cir) {
        if (SpawnFilter.shouldBlock((Mob) (Object) this, spawnReason)) {
            cir.setReturnValue(false);
        }
    }

    /** わく直前に、その生き物を消して なかったことにする。 */
    @Inject(method = "finalizeSpawn", at = @At("HEAD"))
    private void zombiepanic$blockFinalize(ServerLevelAccessor level, DifficultyInstance difficulty, EntitySpawnReason spawnReason,
                                           SpawnGroupData groupData, CallbackInfoReturnable<SpawnGroupData> cir) {
        Mob self = (Mob) (Object) this;
        if (SpawnFilter.shouldBlock(self, spawnReason)) {
            self.discard();
        }
    }
}
