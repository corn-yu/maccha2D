package com.maccha.zombiepanic;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * プレイヤーがうばった能力の記録（死んでも なくならない）。
 */
public class PowerData extends SavedData {

    /** 1人ぶんの記録（持っている能力の名前と、えらんでいる大技の番号）。 */
    public static class Entry {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.listOf().optionalFieldOf("owned", List.of()).forGetter(entry -> entry.owned),
                Codec.INT.optionalFieldOf("selected", 0).forGetter(entry -> entry.selected)
        ).apply(instance, (owned, selected) -> {
            Entry entry = new Entry();
            entry.owned.addAll(owned);
            entry.selected = selected;
            return entry;
        }));

        public final List<String> owned = new ArrayList<>();
        public int selected = 0;
    }

    public static final Codec<PowerData> CODEC = Codec.unboundedMap(Codec.STRING, Entry.CODEC).xmap(map -> {
        PowerData data = new PowerData();
        map.forEach((key, entry) -> data.entries.put(UUID.fromString(key), entry));
        return data;
    }, data -> {
        Map<String, Entry> map = new HashMap<>();
        data.entries.forEach((uuid, entry) -> map.put(uuid.toString(), entry));
        return map;
    });

    public static final SavedDataType<PowerData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(ZombiePanic.MOD_ID, "powers"), PowerData::new, CODEC, null);

    private final Map<UUID, Entry> entries = new HashMap<>();

    /** この世界の記録をとり出す（なければ作る）。 */
    public static PowerData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /** その人の記録（なければ作る）。書きかえたら setDirty を呼ぶこと。 */
    public Entry entryOf(UUID player) {
        return entries.computeIfAbsent(player, key -> new Entry());
    }

    /** その人の記録を ぜんぶ消す。 */
    public void clear(UUID player) {
        entries.remove(player);
        setDirty();
    }
}
