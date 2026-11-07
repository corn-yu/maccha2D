package com.maccha.zombiepanic;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 世界ごとに保存する記録（たおした数・次のボス・呪いがとけたか）。
 */
public class WorldData extends SavedData {

    private static final String SAVE_NAME = "zombiepanic";

    // みんなでたおしたゾンビの数
    public int killCount = 0;
    // この数になったらボスが出る
    public int nextBossKills = PanicConfig.FIRST_BOSS_KILLS;
    // 次のボスまでの数（ボスが出るたびにふえる）
    public int bossKillStep = PanicConfig.BOSS_KILL_STEP;
    // たおしたボスの数
    public int bossesDefeated = 0;
    // 呪いがとけた（クリアした）か
    public boolean cleared = false;

    /** この世界の記録をとり出す（なければ作る）。 */
    public static WorldData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(WorldData::new, WorldData::load, null), SAVE_NAME);
    }

    /** 保存してあった記録を読む。 */
    private static WorldData load(CompoundTag tag, HolderLookup.Provider registries) {
        WorldData data = new WorldData();
        data.killCount = tag.getInt("killCount");
        data.nextBossKills = tag.contains("nextBossKills") ? tag.getInt("nextBossKills") : PanicConfig.FIRST_BOSS_KILLS;
        data.bossKillStep = tag.contains("bossKillStep") ? tag.getInt("bossKillStep") : PanicConfig.BOSS_KILL_STEP;
        data.bossesDefeated = tag.getInt("bossesDefeated");
        data.cleared = tag.getBoolean("cleared");
        return data;
    }

    /** 記録を保存する。 */
    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("killCount", killCount);
        tag.putInt("nextBossKills", nextBossKills);
        tag.putInt("bossKillStep", bossKillStep);
        tag.putInt("bossesDefeated", bossesDefeated);
        tag.putBoolean("cleared", cleared);
        return tag;
    }
}
