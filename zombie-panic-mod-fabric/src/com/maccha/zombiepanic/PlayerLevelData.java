package com.maccha.zombiepanic;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * プレイヤーのレベルと経験値の記録（死んでも なくならない）。
 */
public class PlayerLevelData extends SavedData {

    /** 1人ぶんの記録。 */
    public static class Entry {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.optionalFieldOf("level", 1).forGetter(entry -> entry.level),
                Codec.INT.optionalFieldOf("exp", 0).forGetter(entry -> entry.exp)
        ).apply(instance, (level, exp) -> {
            Entry entry = new Entry();
            entry.level = level;
            entry.exp = exp;
            return entry;
        }));

        public int level = 1;
        public int exp = 0;
    }

    public static final Codec<PlayerLevelData> CODEC = Codec.unboundedMap(Codec.STRING, Entry.CODEC).xmap(map -> {
        PlayerLevelData data = new PlayerLevelData();
        map.forEach((key, entry) -> data.entries.put(UUID.fromString(key), entry));
        return data;
    }, data -> {
        Map<String, Entry> map = new HashMap<>();
        data.entries.forEach((uuid, entry) -> map.put(uuid.toString(), entry));
        return map;
    });

    public static final SavedDataType<PlayerLevelData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(ZombiePanic.MOD_ID, "player_levels"), PlayerLevelData::new, CODEC, null);

    private final Map<UUID, Entry> entries = new HashMap<>();

    /** この世界の記録をとり出す（なければ作る）。 */
    public static PlayerLevelData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /** その人の記録（なければ作る）。書きかえたら setDirty を呼ぶこと。 */
    public Entry entryOf(UUID player) {
        return entries.computeIfAbsent(player, key -> new Entry());
    }
}
