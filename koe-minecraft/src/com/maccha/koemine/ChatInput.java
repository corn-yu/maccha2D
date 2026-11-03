package com.maccha.koemine;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * チャットで発動モード：チャットに打った言葉を、声と同じアクションにする（/koemine chat on・off）
 */
public class ChatInput {
    // 言葉と言葉のあいだの待ち時間（tick）
    static final int GAP_TICKS = 4;
    final KoeMineMod mod;
    // オンかオフかを おぼえておくファイル（config/koemine/chat-mode.txt）
    final Path saveFile;
    boolean on;

    /** MOD 本体とつないで、前のオン・オフを読む */
    public ChatInput(KoeMineMod mod) {
        this.mod = mod;
        this.saveFile = mod.folder.resolve("chat-mode.txt");
        this.on = readSaved();
    }

    /** チャットを送る前の横取りと、/koemine コマンドを登録する */
    public void register() {
        ClientSendMessageEvents.ALLOW_CHAT.register(this::onChat);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(
                ClientCommands.literal("koemine")
                        .then(ClientCommands.literal("chat")
                                .executes(this::toggle)
                                .then(ClientCommands.literal("on").executes(c -> set(c, true)))
                                .then(ClientCommands.literal("off").executes(c -> set(c, false))))));
    }

    /** チャットを送る直前：言葉が見つかったらアクションにして、チャットは送らない（false） */
    boolean onChat(String message) {
        if (!on) return true;
        List<String> actions = findActions(message);
        if (actions.isEmpty()) return true;
        // 声のときと同じくらいの間（0.2秒）をあけて、順番にする
        mod.queue.add(new VoiceListener.Heard(message, String.join("+wait:" + GAP_TICKS + "+", actions), true, true));
        return false;
    }

    /** 文の中の言葉を前から順にさがして、アクションの一覧にする（声の「言い終わった」ときと同じやりかた） */
    List<String> findActions(String message) {
        String text = CommandTable.normalize(message);
        List<String> actions = new ArrayList<>();
        int nameFrom = 0;
        CommandTable.Match match = mod.table.findFirst(text, 0);
        while (match != null) {
            String action = match.entry().action();
            if (action.contains("name:")) {
                // 「〇〇を持って」なら、〇〇の部分をアクションにつける
                String name = text.substring(Math.min(nameFrom, match.start()), match.start()).trim();
                if (name.endsWith("を")) name = name.substring(0, name.length() - 1);
                action = action + "|" + name;
                nameFrom = match.end();
            } else if (!action.startsWith("item:")) {
                nameFrom = match.end();
            }
            actions.add(action);
            match = mod.table.findFirst(text, match.end());
        }
        return actions;
    }

    /** /koemine chat：オンとオフを入れかえる */
    int toggle(CommandContext<FabricClientCommandSource> context) {
        return set(context, !on);
    }

    /** /koemine chat on・off：モードを決めて、ファイルにおぼえる */
    int set(CommandContext<FabricClientCommandSource> context, boolean value) {
        on = value;
        try {
            Files.createDirectories(saveFile.getParent());
            Files.writeString(saveFile, on ? "on" : "off", StandardCharsets.UTF_8);
        } catch (IOException error) {
            KoeMineMod.LOG.warn("こえマイクラ: chat-mode.txt が書けませんでした", error);
        }
        context.getSource().sendFeedback(Component.literal(on
                ? "§aこえマイクラ：チャットで発動モード オン。チャットに「まえ」「ジャンプして置いて」などと打つと動きます（言葉が入っていないチャットは ふつうに送られます）"
                : "§eこえマイクラ：チャットで発動モード オフ。チャットは ふつうに送られます"));
        return 1;
    }

    /** 前に決めたオン・オフを読む（はじめはオフ） */
    boolean readSaved() {
        try {
            return Files.exists(saveFile) && Files.readString(saveFile, StandardCharsets.UTF_8).trim().equals("on");
        } catch (IOException error) {
            return false;
        }
    }
}
