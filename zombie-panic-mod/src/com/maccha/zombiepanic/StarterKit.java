package com.maccha.zombiepanic;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * はじめて入った人に わたす「生きのびセット」。1人につき1回だけ。
 */
public final class StarterKit {

    // もらったあとの目じるし
    private static final String RECEIVED_TAG = "zp_starter_kit";

    private StarterKit() {
    }

    /** まだもらっていない人に 生きのびセットをわたす。 */
    public static void giveIfFirstTime(ServerPlayer player) {
        if (player.getTags().contains(RECEIVED_TAG)) {
            return;
        }
        player.addTag(RECEIVED_TAG);

        give(player, new ItemStack(Items.STONE_SWORD));
        give(player, new ItemStack(Items.STONE_PICKAXE));
        give(player, new ItemStack(Items.BOW));
        give(player, new ItemStack(Items.ARROW, 32));
        give(player, new ItemStack(Items.BREAD, 16));
        give(player, new ItemStack(Items.TORCH, 32));
        player.sendSystemMessage(Component.literal("生きのびセットを もらったよ！ 石の剣・ツルハシ・弓と矢・パン・たいまつ。").withStyle(ChatFormatting.GREEN));
    }

    /** アイテムを渡す（持ち物がいっぱいなら足もとに落とす）。 */
    private static void give(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }
}
