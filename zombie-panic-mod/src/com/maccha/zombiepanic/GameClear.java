package com.maccha.zombiepanic;

import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;

import java.util.List;

/**
 * ゲームクリア（ゾンビの呪いがとけた）の演出。
 */
public final class GameClear {

    private static final RandomSource random = RandomSource.create();

    // 花火の色（緑・金・白・水色）
    private static final int[] FIREWORK_COLORS = {0x55FF55, 0xFFD700, 0xFFFFFF, 0x55FFFF};

    private GameClear() {
    }

    /** 大きな文字・音・花火・ふりかえりを みんなに見せる。 */
    public static void celebrate(MinecraftServer server, WorldData data) {
        // 大きな文字と音
        PanicEvent.showTitleToAll(server,
                Component.literal("ゲームクリア！").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                Component.literal("ゾンビの呪いが とけた！").withStyle(ChatFormatting.GREEN));
        PanicEvent.playSoundToAll(server, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        PanicEvent.playSoundToAll(server, SoundEvents.PLAYER_LEVELUP, 1.0f, 0.8f);

        // 花火
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            launchFireworksAround(player, 6);
        }

        // ふりかえり
        PanicEvent.broadcast(server, Component.literal("============================").withStyle(ChatFormatting.GOLD));
        PanicEvent.broadcast(server, Component.literal(" ゾンビドラゴンが たおれ、世界の呪いが とけた！").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        PanicEvent.broadcast(server, Component.literal(" たおしたゾンビ: " + data.killCount + "体").withStyle(ChatFormatting.WHITE));
        PanicEvent.broadcast(server, Component.literal(" たおしたボス: " + data.bossesDefeated + "体").withStyle(ChatFormatting.WHITE));
        PanicEvent.broadcast(server, Component.literal(" これからは ふつうの世界。動物や ほかのモブも もどってくるよ。").withStyle(ChatFormatting.GREEN));
        PanicEvent.broadcast(server, Component.literal(" のこったゾンビは また お日さまで 燃えるようになった。").withStyle(ChatFormatting.GRAY));
        PanicEvent.broadcast(server, Component.literal(" あそんでくれて ありがとう！").withStyle(ChatFormatting.YELLOW));
        PanicEvent.broadcast(server, Component.literal("============================").withStyle(ChatFormatting.GOLD));
    }

    /** プレイヤーのまわりに 花火をいくつか打ち上げる。 */
    private static void launchFireworksAround(ServerPlayer player, int count) {
        ServerLevel level = player.serverLevel();
        for (int i = 0; i < count; i++) {
            double x = player.getX() + (random.nextDouble() - 0.5) * 8.0;
            double z = player.getZ() + (random.nextDouble() - 0.5) * 8.0;
            FireworkRocketEntity rocket = new FireworkRocketEntity(level, x, player.getY() + 1.0, z, makeFireworkItem());
            level.addFreshEntity(rocket);
        }
    }

    /** 色つきの花火アイテムを作る。 */
    private static ItemStack makeFireworkItem() {
        int color = FIREWORK_COLORS[random.nextInt(FIREWORK_COLORS.length)];
        FireworkExplosion explosion = new FireworkExplosion(FireworkExplosion.Shape.LARGE_BALL,
                IntList.of(color), IntList.of(0xFFFFFF), true, true);
        ItemStack stack = new ItemStack(Items.FIREWORK_ROCKET);
        stack.set(DataComponents.FIREWORKS, new Fireworks(1 + random.nextInt(2), List.of(explosion)));
        return stack;
    }
}
