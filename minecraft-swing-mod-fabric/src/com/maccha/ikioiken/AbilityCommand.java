package com.maccha.ikioiken;

import java.util.ArrayList;
import java.util.List;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 武器の能力を試すためのコマンド（/ikioiken ...）。持っている武器に能力やレベルをつける。管理者だけが使える。
 */
public final class AbilityCommand {

    private AbilityCommand() {
    }

    /** コマンドを登録する。 */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ikioiken")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                // 能力を足す（同じ能力があれば強さを入れかえる）
                .then(Commands.literal("ability")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        WeaponAbilities.ALL.keySet(), builder))
                                .executes(context -> addAbility(context.getSource(), StringArgumentType.getString(context, "id"), 1))
                                .then(Commands.argument("level", IntegerArgumentType.integer(1, WeaponAbilities.MAX_ABILITY_LEVEL))
                                        .executes(context -> addAbility(context.getSource(),
                                                StringArgumentType.getString(context, "id"),
                                                IntegerArgumentType.getInteger(context, "level"))))))
                // 武器のレベルを決める
                .then(Commands.literal("level")
                        .then(Commands.argument("level", IntegerArgumentType.integer(1, WeaponAbilities.MAX_WEAPON_LEVEL))
                                .executes(context -> setLevel(context.getSource(), IntegerArgumentType.getInteger(context, "level")))))
                // 全部の能力をつけたネザライト武器一式をもらう（強さ・武器レベルは省略できる）
                .then(Commands.literal("kit")
                        .executes(context -> giveKit(context.getSource(), 10, 100))
                        .then(Commands.argument("abilityLevel", IntegerArgumentType.integer(1, WeaponAbilities.MAX_ABILITY_LEVEL))
                                .executes(context -> giveKit(context.getSource(),
                                        IntegerArgumentType.getInteger(context, "abilityLevel"), 100))
                                .then(Commands.argument("weaponLevel", IntegerArgumentType.integer(1, WeaponAbilities.MAX_WEAPON_LEVEL))
                                        .executes(context -> giveKit(context.getSource(),
                                                IntegerArgumentType.getInteger(context, "abilityLevel"),
                                                IntegerArgumentType.getInteger(context, "weaponLevel"))))))
                // 能力をぜんぶ消す
                .then(Commands.literal("clear")
                        .executes(context -> clear(context.getSource())))
                // 能力の数を見る
                .then(Commands.literal("count")
                        .executes(context -> {
                            context.getSource().sendSuccess(() -> Component.literal("能力は全部で " + WeaponAbilities.all().size() + " 種類です"), false);
                            return WeaponAbilities.all().size();
                        })));
    }

    /** 持っている武器に能力を足す。 */
    private static int addAbility(CommandSourceStack source, String id, int level) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        WeaponAbilities.Ability ability = WeaponAbilities.ALL.get(id);
        ItemStack stack = player.getMainHandItem();
        if (ability == null || !WeaponAbilities.canHaveAbilities(stack)) {
            source.sendFailure(Component.literal("その能力はありません、または手に武器を持っていません"));
            return 0;
        }
        List<WeaponAbilities.Entry> entries = new ArrayList<>(WeaponAbilities.abilitiesOf(stack));
        entries.removeIf(entry -> entry.ability().id().equals(id));
        if (entries.size() >= WeaponAbilities.MAX_ABILITIES) {
            entries.remove(0);
        }
        entries.add(new WeaponAbilities.Entry(ability, level));
        WeaponAbilities.write(stack, entries, WeaponAbilities.weaponLevel(stack));
        source.sendSuccess(() -> Component.literal("「" + ability.name() + "」をつけました"), false);
        return 1;
    }

    /** 持っている武器のレベルを決める。 */
    private static int setLevel(CommandSourceStack source, int level) {
        ServerPlayer player = source.getPlayer();
        if (player == null || !WeaponAbilities.canHaveAbilities(player.getMainHandItem())) {
            source.sendFailure(Component.literal("手に武器を持っていません"));
            return 0;
        }
        ItemStack stack = player.getMainHandItem();
        WeaponAbilities.write(stack, WeaponAbilities.abilitiesOf(stack), level);
        source.sendSuccess(() -> Component.literal("武器レベルを " + level + " にしました"), false);
        return 1;
    }

    /** 持っている武器の能力をぜんぶ消す。 */
    private static int clear(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null || !WeaponAbilities.canHaveAbilities(player.getMainHandItem())) {
            source.sendFailure(Component.literal("手に武器を持っていません"));
            return 0;
        }
        ItemStack stack = player.getMainHandItem();
        WeaponAbilities.write(stack, new ArrayList<>(), 1);
        source.sendSuccess(() -> Component.literal("能力を消しました"), false);
        return 1;
    }

    /** 全部の能力をつけた、こわれないネザライト武器一式を持ち物に入れる。 */
    private static int giveKit(CommandSourceStack source, int abilityLevel, int weaponLevel) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }

        // 一式：剣・斧・つるはし・シャベル・クワ・槍・メイス・トライデント
        net.minecraft.world.item.Item[] items = {
                net.minecraft.world.item.Items.NETHERITE_SWORD, net.minecraft.world.item.Items.NETHERITE_AXE,
                net.minecraft.world.item.Items.NETHERITE_PICKAXE, net.minecraft.world.item.Items.NETHERITE_SHOVEL,
                net.minecraft.world.item.Items.NETHERITE_HOE, net.minecraft.world.item.Items.NETHERITE_SPEAR,
                net.minecraft.world.item.Items.MACE, net.minecraft.world.item.Items.TRIDENT };

        // 能力は全部（強さはそろえる）
        List<WeaponAbilities.Entry> entries = new ArrayList<>();
        for (WeaponAbilities.Ability ability : WeaponAbilities.all()) {
            entries.add(new WeaponAbilities.Entry(ability, abilityLevel));
        }

        int given = 0;
        for (net.minecraft.world.item.Item item : items) {
            ItemStack stack = new ItemStack(item);
            WeaponAbilities.write(stack, entries, weaponLevel);
            stack.set(net.minecraft.core.component.DataComponents.UNBREAKABLE, net.minecraft.util.Unit.INSTANCE);
            if (!player.getInventory().add(stack)) {
                player.getInventory().placeItemBackInInventory(stack, net.minecraft.util.Prediction.SERVER_ONLY);
            }
            given++;
        }
        int count = given;
        source.sendSuccess(() -> Component.literal("全部の能力（強さ " + abilityLevel + "）・武器レベル " + weaponLevel
                + " の、こわれないネザライト武器 " + count + " 本をわたしました"), false);
        return count;
    }
}
