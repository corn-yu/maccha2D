package com.maccha.zombiepanic;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * 世界ごとに保存する記録（たおした数・次のボス・呪いがとけたか）。
 */
public class WorldData extends SavedData {

    // 保存のきまり（ファイルの名前と、読み書きのしかた）
    public static final Codec<WorldData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("killCount", 0).forGetter(data -> data.killCount),
            Codec.INT.optionalFieldOf("nextBossKills", PanicConfig.FIRST_BOSS_KILLS).forGetter(data -> data.nextBossKills),
            Codec.INT.optionalFieldOf("bossKillStep", PanicConfig.BOSS_KILL_STEP).forGetter(data -> data.bossKillStep),
            Codec.INT.optionalFieldOf("bossesDefeated", 0).forGetter(data -> data.bossesDefeated),
            Codec.INT.optionalFieldOf("bossTurn", 0).forGetter(data -> data.bossTurn),
            Codec.BOOL.optionalFieldOf("cleared", false).forGetter(data -> data.cleared)
    ).apply(instance, WorldData::fromSaved));

    public static final SavedDataType<WorldData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(ZombiePanic.MOD_ID, "zombiepanic"), WorldData::new, CODEC, null);

    // みんなでたおしたゾンビの数
    public int killCount = 0;
    // この数になったらボスが出る
    public int nextBossKills = PanicConfig.FIRST_BOSS_KILLS;
    // 次のボスまでの数（ボスが出るたびにふえる）
    public int bossKillStep = PanicConfig.BOSS_KILL_STEP;
    // たおしたボスの数
    public int bossesDefeated = 0;
    // 次に出すボスの番号（順番に出す）
    public int bossTurn = 0;
    // 呪いがとけた（クリアした）か
    public boolean cleared = false;

    /** この世界の記録をとり出す（なければ作る）。 */
    public static WorldData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /** 保存してあった記録から作る。 */
    private static WorldData fromSaved(int killCount, int nextBossKills, int bossKillStep, int bossesDefeated, int bossTurn, boolean cleared) {
        WorldData data = new WorldData();
        data.killCount = killCount;
        data.nextBossKills = nextBossKills;
        data.bossKillStep = bossKillStep;
        data.bossesDefeated = bossesDefeated;
        data.bossTurn = bossTurn;
        data.cleared = cleared;
        return data;
    }
}
