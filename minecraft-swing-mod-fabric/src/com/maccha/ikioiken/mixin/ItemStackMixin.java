package com.maccha.ikioiken.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.maccha.ikioiken.WeaponAbilities;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 作業台などで武器を作ったとき（取り出したとき）に、能力をつけるための差しこみ。
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {

    /** 作った物を取り出したとき、武器なら能力をつける。 */
    @Inject(method = "onCraftedBy(Lnet/minecraft/world/entity/player/Player;I)V", at = @At("HEAD"))
    private void ikioiken$assignOnCraft(Player player, int count, CallbackInfo callbackInfo) {
        if (player instanceof ServerPlayer serverPlayer) {
            WeaponAbilities.assignOnCraft((ItemStack) (Object) this, serverPlayer.getRandom());
        }
    }
}
