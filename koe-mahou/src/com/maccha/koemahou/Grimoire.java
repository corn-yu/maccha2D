package com.maccha.koemahou;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootTable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 魔導書と魔法本（サーバー側）：魔導書を読むと魔法を覚え、覚えた魔法だけ唱えられる。覚えた魔法は魔法本にのる
 * 魔法の中身は、サーバー（ワールドの持ち主）の じゅもん.txt から来る
 */
public class Grimoire {

    // 宝箱に「謎の魔導書」が入る確率
    static final double CHEST_CHANCE = 0.3;
    // 謎の魔導書が入る宝箱
    static final Set<ResourceKey<LootTable>> CHESTS = Set.of(
            BuiltInLootTables.SIMPLE_DUNGEON, BuiltInLootTables.ABANDONED_MINESHAFT, BuiltInLootTables.DESERT_PYRAMID,
            BuiltInLootTables.JUNGLE_TEMPLE, BuiltInLootTables.STRONGHOLD_LIBRARY, BuiltInLootTables.STRONGHOLD_CORRIDOR,
            BuiltInLootTables.WOODLAND_MANSION, BuiltInLootTables.ANCIENT_CITY, BuiltInLootTables.VILLAGE_TEMPLE,
            BuiltInLootTables.IGLOO_CHEST, BuiltInLootTables.SHIPWRECK_TREASURE, BuiltInLootTables.BURIED_TREASURE,
            BuiltInLootTables.PILLAGER_OUTPOST, BuiltInLootTables.RUINED_PORTAL, BuiltInLootTables.NETHER_BRIDGE,
            BuiltInLootTables.BASTION_TREASURE, BuiltInLootTables.END_CITY_TREASURE, BuiltInLootTables.UNDERWATER_RUIN_BIG);

    // アイテムにつける しるし
    static final String SPELL_KEY = "koemahou_spell";
    static final String MYSTERY_KEY = "koemahou_mystery";
    static final String SPELLBOOK_KEY = "koemahou_spellbook";

    static MinecraftServer server;
    // サーバーの じゅもん表（書きかえられたら読みなおす）
    static SpellBook book = new SpellBook();
    static Path bookFile;
    static long bookFileTime = -1;
    // だれが どの魔法を覚えているか（魔法の名前）
    static final Map<UUID, Set<String>> learned = new HashMap<>();
    static Path learnedFile;
    static int tickCount = 0;

    // ================= はじまり・おわり =================

    /** サーバーが始まったら、じゅもん表と 覚えた魔法を読む */
    public static void onServerStarted(MinecraftServer started) {
        server = started;
        bookFile = FabricLoader.getInstance().getConfigDir().resolve("koemahou").resolve("じゅもん.txt");
        bookFileTime = -1;
        reloadBookIfChanged();
        learnedFile = server.getWorldPath(LevelResource.ROOT).resolve("koemahou_learned.txt");
        loadLearned();
    }

    /** サーバーが止まったら、わすれる（次のワールドと まざらないように） */
    public static void onServerStopped() {
        server = null;
        learned.clear();
    }

    /** プレイヤーが入ったら、覚えている魔法を送り、魔法本を新しくする */
    public static void onJoin(ServerPlayer player) {
        sync(player);
        refreshSpellbooks(player);
        if (!book.problems.isEmpty()) {
            for (String problem : book.problems) player.sendSystemMessage(Component.literal("§cこえまほう：" + problem));
        }
    }

    /** 毎tick：じゅもん.txt が書きかえられていたら（2秒ごとに見る）読みなおして、みんなに送りなおす */
    public static void tick() {
        if (server == null) return;
        tickCount++;
        if (tickCount % 40 != 0) return;
        if (!reloadBookIfChanged()) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sync(player);
            refreshSpellbooks(player);
            player.sendSystemMessage(Component.literal("§dこえまほう：じゅもん表を読みなおしました（魔法 " + book.spells.size() + " こ）"));
            for (String problem : book.problems) player.sendSystemMessage(Component.literal("§cこえまほう：" + problem));
        }
    }

    // ================= じゅもん表と 覚えた魔法 =================

    /** じゅもん.txt が変わっていたら読みなおす（読みなおしたら true） */
    static boolean reloadBookIfChanged() {
        long time;
        try {
            time = Files.exists(bookFile) ? Files.getLastModifiedTime(bookFile).toMillis() : 0;
        } catch (Exception error) {
            time = 0;
        }
        if (time == bookFileTime) return false;
        SpellBook newBook = new SpellBook();
        newBook.load(bookFile);
        book = newBook;
        try {
            bookFileTime = Files.getLastModifiedTime(bookFile).toMillis();
        } catch (Exception error) {
            bookFileTime = time;
        }
        KoeMahouMod.LOG.info("こえまほう: サーバーの じゅもん表 {} こ", book.spells.size());
        return true;
    }

    /** 覚えた魔法を ワールドのフォルダから読む（1行に「プレイヤーのid タブ 魔法 タブ 魔法…」） */
    static void loadLearned() {
        learned.clear();
        try {
            if (!Files.exists(learnedFile)) return;
            for (String line : Files.readAllLines(learnedFile, StandardCharsets.UTF_8)) {
                String[] parts = line.split("\t");
                if (parts.length < 1 || parts[0].isBlank()) continue;
                Set<String> names = new LinkedHashSet<>();
                for (int index = 1; index < parts.length; index++) if (!parts[index].isBlank()) names.add(parts[index]);
                learned.put(UUID.fromString(parts[0].strip()), names);
            }
        } catch (Exception error) {
            KoeMahouMod.LOG.warn("こえまほう: 覚えた魔法が読めません: {}", error.toString());
        }
    }

    /** 覚えた魔法を ワールドのフォルダに書く */
    static void saveLearned() {
        if (learnedFile == null) return;
        List<String> lines = new ArrayList<>();
        for (Map.Entry<UUID, Set<String>> entry : learned.entrySet()) {
            lines.add(entry.getKey() + "\t" + String.join("\t", entry.getValue()));
        }
        try {
            Files.write(learnedFile, lines, StandardCharsets.UTF_8);
        } catch (Exception error) {
            KoeMahouMod.LOG.warn("こえまほう: 覚えた魔法が書けません: {}", error.toString());
        }
    }

    /** プレイヤーが覚えている魔法の名前（じゅもん表にあるものだけ、覚えた順） */
    static List<String> learnedNames(ServerPlayer player) {
        List<String> names = new ArrayList<>();
        for (String name : learned.getOrDefault(player.getUUID(), Set.of())) {
            if (book.spells.containsKey(name)) names.add(name);
        }
        return names;
    }

    /** 唱えた魔法を 覚えていれば、その中身（覚えていなければ null） */
    public static SpellBook.Spell knownSpell(ServerPlayer player, String name) {
        if (!learned.getOrDefault(player.getUUID(), Set.of()).contains(name)) return null;
        return book.spells.get(name);
    }

    /** 覚えている魔法を クライアントへ送る（マイクが その詠唱だけを聞くように） */
    static void sync(ServerPlayer player) {
        if (!ServerPlayNetworking.canSend(player, LearnedPayload.TYPE)) return;
        StringBuilder text = new StringBuilder(SpellBook.VERSION_MARK).append("\n");
        for (String name : learnedNames(player)) {
            SpellBook.Spell spell = book.spells.get(name);
            text.append('[').append(name).append("]\n");
            for (String line : spell.lines()) text.append(line).append('\n');
            text.append(spell.program()).append('\n');
        }
        ServerPlayNetworking.send(player, new LearnedPayload(text.toString()));
    }

    /** 魔法を覚える（覚えたら true）。はじめてなら魔法本もわたす */
    static boolean learn(ServerPlayer player, String name) {
        Set<String> names = learned.computeIfAbsent(player.getUUID(), id -> new LinkedHashSet<>());
        if (!names.add(name)) return false;
        saveLearned();
        sync(player);
        if (!refreshSpellbooks(player)) give(player, makeSpellbook(player));
        // 光と音
        ServerLevel level = (ServerLevel) player.level();
        level.sendParticles(ParticleTypes.ENCHANT, player.getX(), player.getY() + 1.2, player.getZ(), 80, 0.6, 0.8, 0.6, 0.8);
        level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, player.getX(), player.getY() + 1, player.getZ(), 30, 0.4, 0.6, 0.4, 0.3);
        level.playSound(null, player.blockPosition(), SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.PLAYERS, 1.0f, 1.0f);
        level.playSound(null, player.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6f, 1.4f);
        SpellBook.Spell spell = book.spells.get(name);
        player.sendSystemMessage(Component.literal("§d✦ 魔法「" + name + "」を覚えた！ ✦"));
        player.sendSystemMessage(Component.literal("§7詠唱：「" + String.join("」→「", spell.lines()) + "」"));
        return true;
    }

    // ================= アイテム =================

    /** アイテムの しるし（なければ 空） */
    static CompoundTag tagOf(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? new CompoundTag() : data.copyTag();
    }

    /** 魔導書を作る（name が null なら 謎の魔導書） */
    static ItemStack makeGrimoire(String name) {
        ItemStack stack = new ItemStack(Items.ENCHANTED_BOOK);
        CompoundTag tag = new CompoundTag();
        if (name == null) tag.putBoolean(MYSTERY_KEY, true);
        else tag.putString(SPELL_KEY, name);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        stack.set(DataComponents.ITEM_NAME, Component.literal(name == null ? "§5謎の魔導書" : "§d魔導書「" + name + "」"));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal(name == null ? "§7何の魔法が書いてあるかは、読むまでわからない" : "§7この魔法の使いかたが書いてある"),
                Component.literal("§7右クリックで読むと、魔法を覚える"))));
        return stack;
    }

    /** 魔法本を作る（覚えた魔法がのっている） */
    static ItemStack makeSpellbook(ServerPlayer player) {
        ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(SPELLBOOK_KEY, true);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        stack.set(DataComponents.WRITTEN_BOOK_CONTENT, spellbookContent(player));
        return stack;
    }

    /** 魔法本の中身：1ページ目は目次、そのあと魔法1つに1ページ */
    static WrittenBookContent spellbookContent(ServerPlayer player) {
        List<String> names = learnedNames(player);
        List<Filterable<Component>> pages = new ArrayList<>();
        StringBuilder first = new StringBuilder("§5§l魔法本§r\n§8" + player.getName().getString() + " の魔法\n\n");
        if (names.isEmpty()) {
            first.append("§8まだ何も覚えていない。\n魔導書を見つけて読もう。");
        } else {
            first.append("§0覚えた魔法 ").append(names.size()).append(" こ\n");
            for (String name : names.subList(0, Math.min(10, names.size()))) first.append("§5・").append(name).append('\n');
            if (names.size() > 10) first.append("§8…ほか ").append(names.size() - 10).append(" こ");
        }
        pages.add(Filterable.passThrough(Component.literal(first.toString())));
        for (String name : names) {
            SpellBook.Spell spell = book.spells.get(name);
            StringBuilder page = new StringBuilder("§5§l" + name + "§r\n\n§8詠唱§0\n");
            for (String line : spell.lines()) page.append("「").append(line).append("」\n");
            int seconds = SpellEffects.cooldownTicks(spell.program()) / 20;
            page.append("\n§8待ち時間 ").append(seconds).append(" 秒");
            String text = page.toString();
            if (text.length() > 900) text = text.substring(0, 900);
            pages.add(Filterable.passThrough(Component.literal(text)));
        }
        return new WrittenBookContent(Filterable.passThrough("魔法本"), player.getName().getString(), 0, pages, true);
    }

    /** 持っている魔法本を ぜんぶ新しくする（1冊でも持っていれば true） */
    static boolean refreshSpellbooks(ServerPlayer player) {
        boolean found = false;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(Items.WRITTEN_BOOK) && tagOf(stack).getBooleanOr(SPELLBOOK_KEY, false)) {
                stack.set(DataComponents.WRITTEN_BOOK_CONTENT, spellbookContent(player));
                found = true;
            }
        }
        return found;
    }

    /** アイテムをわたす（いっぱいなら 足もとに落とす） */
    static void give(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().add(stack)) player.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY);
    }

    /** 右クリックしたとき：魔導書なら読んで覚える、魔法本なら中身を新しくしてから開く */
    public static InteractionResult onUse(Player user, Level level, InteractionHand hand) {
        if (level.isClientSide() || !(user instanceof ServerPlayer player)) return InteractionResult.PASS;
        ItemStack stack = player.getItemInHand(hand);
        CompoundTag tag = tagOf(stack);

        // 魔法本：中身を新しくして、ふつうに開く
        if (stack.is(Items.WRITTEN_BOOK) && tag.getBooleanOr(SPELLBOOK_KEY, false)) {
            stack.set(DataComponents.WRITTEN_BOOK_CONTENT, spellbookContent(player));
            return InteractionResult.PASS;
        }
        if (!stack.is(Items.ENCHANTED_BOOK)) return InteractionResult.PASS;

        String name;
        if (tag.getBooleanOr(MYSTERY_KEY, false)) {
            // 謎の魔導書：まだ覚えていない魔法から 1つ
            List<String> unknown = new ArrayList<>();
            Set<String> mine = learned.getOrDefault(player.getUUID(), Set.of());
            for (String spell : book.spells.keySet()) if (!mine.contains(spell)) unknown.add(spell);
            if (unknown.isEmpty()) {
                player.sendOverlayMessage(Component.literal("§7この魔導書から学べることは、もうない…"));
                return InteractionResult.FAIL;
            }
            name = unknown.get(player.getRandom().nextInt(unknown.size()));
        } else {
            name = tag.getStringOr(SPELL_KEY, "");
            if (name.isEmpty()) return InteractionResult.PASS;
            if (!book.spells.containsKey(name)) {
                player.sendOverlayMessage(Component.literal("§7「" + name + "」は、この世界の じゅもん表にない魔法だ…"));
                return InteractionResult.FAIL;
            }
        }
        if (!learn(player, name)) {
            player.sendOverlayMessage(Component.literal("§7「" + name + "」は もう覚えている"));
            return InteractionResult.FAIL;
        }
        // 読んだ魔導書は 消える（クリエイティブでは のこす）
        if (!player.getAbilities().instabuild) stack.shrink(1);
        return InteractionResult.SUCCESS;
    }

    /** 宝箱が開いたとき：決まった宝箱なら、ときどき 謎の魔導書を入れる */
    public static void onChestLoot(Holder<LootTable> table, LootContext context, List<ItemStack> drops) {
        boolean chest = false;
        for (ResourceKey<LootTable> key : CHESTS) if (table.is(key)) chest = true;
        if (chest && context.getRandom().nextDouble() < CHEST_CHANCE) drops.add(makeGrimoire(null));
    }

    // ================= コマンド =================

    /** /koemahou 魔導書 <名前>・/koemahou 謎の魔導書（どちらもチート）・/koemahou 魔法本（だれでも） */
    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("koemahou")
                .then(Commands.literal("魔導書")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("名前", StringArgumentType.greedyString())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(book.spells.keySet(), builder))
                                .executes(context -> {
                                    ServerPlayer player = context.getSource().getPlayerOrException();
                                    String name = StringArgumentType.getString(context, "名前").strip();
                                    if (!book.spells.containsKey(name)) {
                                        context.getSource().sendFailure(Component.literal("「" + name + "」は じゅもん表にありません"));
                                        return 0;
                                    }
                                    give(player, makeGrimoire(name));
                                    context.getSource().sendSuccess(() -> Component.literal("魔導書「" + name + "」をわたしました"), false);
                                    return 1;
                                })))
                .then(Commands.literal("謎の魔導書")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(context -> {
                            give(context.getSource().getPlayerOrException(), makeGrimoire(null));
                            context.getSource().sendSuccess(() -> Component.literal("謎の魔導書をわたしました"), false);
                            return 1;
                        }))
                .then(Commands.literal("魔法本")
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            give(player, makeSpellbook(player));
                            context.getSource().sendSuccess(() -> Component.literal("魔法本をわたしました"), false);
                            return 1;
                        })));
    }
}
