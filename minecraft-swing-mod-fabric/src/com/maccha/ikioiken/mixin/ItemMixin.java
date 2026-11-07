package com.maccha.ikioiken.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.maccha.ikioiken.WeaponAbilities;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * シフトクリックで作った物をまとめて受け取るときにも、武器に能力をつけるための差しこみ。
 */
@Mixin(Item.class)
public abstract class ItemMixin {

    /** 作った物を受け取る前に、武器なら能力をつける。 */
    @Inject(method = "onCraftedBy(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/player/Player;)V", at = @At("HEAD"))
    private void ikioiken$assignOnCraft(ItemStack stack, Player player, CallbackInfo callbackInfo) {
        if (player instanceof ServerPlayer serverPlayer) {
            WeaponAbilities.assignOnCraft(stack, serverPlayer.getRandom());
        }
    }
}
