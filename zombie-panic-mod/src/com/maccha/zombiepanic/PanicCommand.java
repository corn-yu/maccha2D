package com.maccha.zombiepanic;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * /zombiepanic コマンド（start・stop・status）。チートがオンの人（OP）だけ使える。
 */
public final class PanicCommand {

    private PanicCommand() {
    }

    /** コマンドを登録する。 */
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        // /zombiepanic start [種類]
        LiteralArgumentBuilder<CommandSourceStack> startCommand = Commands.literal("start")
                .executes(context -> startPanicNow(context.getSource(), null));
        for (PanicType panicType : PanicType.values()) {
            startCommand.then(Commands.literal(panicType.commandName)
                    .executes(context -> startPanicNow(context.getSource(), panicType)));
        }

        event.getDispatcher().register(Commands.literal("zombiepanic")
                .requires(source -> source.hasPermission(2))
                .then(startCommand)
                .then(Commands.literal("stop").executes(context -> stopPanicNow(context.getSource())))
                .then(Commands.literal("status").executes(context -> showStatus(context.getSource()))));
    }

    /** すぐにパニックを始める（種類を書かなければランダム）。 */
    private static int startPanicNow(CommandSourceStack source, PanicType panicType) {
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
        PanicEvent.endPanic(source.getServer(), false);
        return 1;
    }

    /** いまの段階と残り時間を見せる。 */
    private static int showStatus(CommandSourceStack source) {
        int secondsLeft = PanicEvent.getSecondsLeft();
        String timeText = (secondsLeft / 60) + "分" + (secondsLeft % 60) + "秒";
        String message = switch (PanicEvent.getPhase()) {
            case CALM -> "いまは しずか。次のパニックまで あと約 " + timeText;
            case WARNING -> "前ぶれ中！ あと " + timeText + " で「" + PanicEvent.getCurrentType().title + "」が始まる";
            case PANIC -> "「" + PanicEvent.getCurrentType().title + "」の真っ最中！ のこり " + timeText;
        };
        source.sendSuccess(() -> Component.literal(message).withStyle(ChatFormatting.YELLOW), false);
        return 1;
    }
}
