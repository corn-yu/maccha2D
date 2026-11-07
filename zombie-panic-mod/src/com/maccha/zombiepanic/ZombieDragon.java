package com.maccha.zombiepanic;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.end.EndDragonFight;

/**
 * エンドのゾンビドラゴン（エンダードラゴンのなれの果て）。たおすと呪いがとける。
 */
public final class ZombieDragon {

    private static final String DRAGON_TAG = "zp_zombie_dragon";

    // ゾンビを呼ぶまでの秒
    private static int summonCountdown = 0;

    private ZombieDragon() {
    }

    /** エンダードラゴンを ゾンビドラゴンにする（体力をふやす）。名前と見た目は資源パックで変えている。 */
    public static void makeZombieDragon(EnderDragon dragon) {
        if (dragon.getTags().contains(DRAGON_TAG)) {
            return;
        }
        dragon.addTag(DRAGON_TAG);
        MobUtil.setMaxHealth(dragon, PanicConfig.DRAGON_HEALTH);
    }

    /** 1秒ごと: ドラゴンがいればゾンビを呼び、たおされていたらクリアにする。 */
    public static void tickEverySecond(MinecraftServer server) {
        ServerLevel endLevel = server.getLevel(Level.END);
        if (endLevel == null) {
            return;
        }

        // ドラゴンがたおされた → 呪いがとける
        EndDragonFight dragonFight = endLevel.getDragonFight();
        if (dragonFight != null && dragonFight.hasPreviouslyKilledDragon()) {
            PanicEvent.onCurseLifted(server);
            return;
        }

        // ドラゴンが生きているあいだ、ときどきゾンビを呼ぶ
        if (endLevel.getDragons().isEmpty() || endLevel.players().isEmpty()) {
            return;
        }
        summonCountdown--;
        if (summonCountdown > 0) {
            return;
        }
        summonCountdown = PanicConfig.DRAGON_SUMMON_INTERVAL_TICKS / 20;
        summonZombiesForDragon(endLevel);
    }

    /** エンドにいる人のまわりに ゾンビを3体ずつ呼ぶ。 */
    private static void summonZombiesForDragon(ServerLevel endLevel) {
        for (ServerPlayer player : endLevel.players()) {
            if (player.isSpectator()) {
                continue;
            }
            for (int i = 0; i < 3; i++) {
                Zombie zombie = ZombieSpawner.spawnZombieCloseTo(endLevel, player.blockPosition(), 8, 18, null);
                if (zombie != null) {
                    zombie.setTarget(player);
                }
            }
        }
    }

    /** 記録をまっさらにする。 */
    public static void reset() {
        summonCountdown = 0;
    }
}
