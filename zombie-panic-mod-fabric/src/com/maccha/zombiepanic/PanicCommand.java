package com.maccha.zombiepanic;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.zombie.Zombie;


/**
 * /zombiepanic コマンド（start・stop・status・boss・ability）。チートがオンの人（OP）だけ使える。
 */
public final class PanicCommand {

    private PanicCommand() {
    }

    /** コマンドを登録する。 */
    public static void register(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {
        // /zombiepanic start [種類]
        LiteralArgumentBuilder<CommandSourceStack> startCommand = Commands.literal("start")
                .executes(context -> startPanicNow(context.getSource(), null));
        for (PanicType panicType : PanicType.values()) {
            startCommand.then(Commands.literal(panicType.commandName)
                    .executes(context -> startPanicNow(context.getSource(), panicType)));
        }

        // /zombiepanic boss [種類]
        LiteralArgumentBuilder<CommandSourceStack> bossCommand = Commands.literal("boss")
                .executes(context -> spawnBossNow(context.getSource(), null));
        for (BossZombies.BossKind kind : BossZombies.BossKind.values()) {
            bossCommand.then(Commands.literal(kind.name().toLowerCase())
                    .executes(context -> spawnBossNow(context.getSource(), kind)));
        }

        // /zombiepanic ability [能力]（目の前にその能力のゾンビを出す・ためし用）
        LiteralArgumentBuilder<CommandSourceStack> abilityCommand = Commands.literal("ability");
        for (ZombieAbilities.Ability ability : ZombieAbilities.Ability.values()) {
            abilityCommand.then(Commands.literal(ability.id)
                    .executes(context -> spawnAbilityZombieNow(context.getSource(), ability)));
        }

        // /zombiepanic power [能力|list|clear]（自分に能力をあげる・ためし用）
        LiteralArgumentBuilder<CommandSourceStack> powerCommand = Commands.literal("power")
                .then(Commands.literal("list").executes(context -> {
                    PlayerPowers.listPowers(context.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(Commands.literal("clear").executes(context -> {
                    PlayerPowers.clearPowers(context.getSource().getPlayerOrException());
                    context.getSource().sendSuccess(() -> Component.literal("能力を ぜんぶ なくしたよ。"), false);
                    return 1;
                }));
        for (ZombieAbilities.Ability ability : ZombieAbilities.Ability.values()) {
            if (ability.tier >= 1 && !ability.zombieOnly) {
                powerCommand.then(Commands.literal(ability.id).executes(context -> {
                    PlayerPowers.givePower(context.getSource().getPlayerOrException(), ability);
                    return 1;
                }));
            }
        }

        dispatcher.register(Commands.literal("zombiepanic")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(startCommand)
                .then(bossCommand)
                .then(abilityCommand)
                .then(powerCommand)
                // /zombiepanic level <数>（目の前に そのレベルのゾンビを出す・ためし用）
                .then(Commands.literal("level").then(Commands.argument("level", IntegerArgumentType.integer(1, PanicConfig.LEVEL_MAX))
                        .executes(context -> spawnLevelZombieNow(context.getSource(), IntegerArgumentType.getInteger(context, "level")))))
                .then(Commands.literal("stop").executes(context -> stopPanicNow(context.getSource())))
                .then(Commands.literal("status").executes(context -> showStatus(context.getSource()))));
    }

    /** すぐにパニックを始める（種類を書かなければランダム）。 */
    private static int startPanicNow(CommandSourceStack source, PanicType panicType) {
        if (!PanicEvent.isCurseActive()) {
            source.sendFailure(Component.literal("この世界の呪いは もう とけているよ。"));
            return 0;
        }
        PanicType chosenType = panicType;
        if (chosenType == null) {
            PanicType[] types = PanicType.values();
            chosenType = types[source.getServer().overworld().getRandom().nextInt(types.length)];
        }
        PanicEvent.startPanic(source.getServer(), chosenType);
        return 1;
    }

    /** いまのパニックを終わらせる。 */
    private static int stopPanicNow(CommandSourceStack source) {
        if (!PanicEvent.isPanicActive()) {
            source.sendSuccess(() -> Component.literal("いまはパニック中じゃないよ。次のパニックまでの時間を数えなおすね。")
                    .withStyle(ChatFormatting.GRAY), false);
        }
        PanicEvent.endPanic(source.getServer());
        return 1;
    }

    /** 自分の近くにボスを出す。 */
    private static int spawnBossNow(CommandSourceStack source, BossZombies.BossKind kind) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (BossZombies.spawnBossNear(player, kind) == null) {
            source.sendFailure(Component.literal("ボスを出せる場所が 見つからなかったよ。ひらけた場所で もう一度ためしてね。"));
            return 0;
        }
        return 1;
    }

    /** 自分の近くに その能力のゾンビを出す。 */
    private static int spawnAbilityZombieNow(CommandSourceStack source, ZombieAbilities.Ability ability) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Zombie zombie = ZombieSpawner.spawnZombieCloseTo(player.level(), player.blockPosition(), 4, 8, null);
        if (zombie == null) {
            source.sendFailure(Component.literal("ゾンビを出せる場所が 見つからなかったよ。"));
            return 0;
        }
        ZombieAbilities.giveAbility(zombie, ability);
        return 1;
    }

    /** 自分の近くに そのレベルのゾンビを出す。 */
    private static int spawnLevelZombieNow(CommandSourceStack source, int level) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Zombie zombie = ZombieSpawner.spawnZombieCloseTo(player.level(), player.blockPosition(), 4, 8, null);
        if (zombie == null) {
            source.sendFailure(Component.literal("ゾンビを出せる場所が 見つからなかったよ。"));
            return 0;
        }
        // 出たときのくじで つけたレベルは外して、えらんだレベルをつけなおす
        zombie.entityTags().removeIf(tag -> tag.startsWith("zp_lv_"));
        ZombieLevel.apply(zombie, level);
        return 1;
    }

    /** いまの段階・たおした数・次のボスを見せる。 */
    private static int showStatus(CommandSourceStack source) {
        WorldData data = WorldData.get(source.getServer());
        if (!PanicEvent.isCurseActive()) {
            source.sendSuccess(() -> Component.literal("呪いは もう とけているよ（クリアずみ）。たおしたゾンビ: " + data.killCount + "体")
                    .withStyle(ChatFormatting.GREEN), false);
            return 1;
        }
        int secondsLeft = PanicEvent.getSecondsLeft();
        String timeText = (secondsLeft / 60) + "分" + (secondsLeft % 60) + "秒";
        String phaseText = switch (PanicEvent.getPhase()) {
            case CALM -> "いまは しずか。次のパニックまで あと約 " + timeText;
            case WARNING -> "前ぶれ中！ あと " + timeText + " で「" + PanicEvent.getCurrentType().title + "」が始まる";
            case PANIC -> "「" + PanicEvent.getCurrentType().title + "」の真っ最中！ のこり " + timeText;
        };
        String killText = "たおしたゾンビ: " + data.killCount + "体 ／ 次のボスまで あと " + (data.nextBossKills - data.killCount)
                + "体 ／ たおしたボス: " + data.bossesDefeated + "体";
        source.sendSuccess(() -> Component.literal(phaseText).withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Component.literal(killText).withStyle(ChatFormatting.GRAY), false);
        return 1;
    }
}
