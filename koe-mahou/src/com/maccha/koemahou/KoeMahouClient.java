package com.maccha.koemahou;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * こえまほう（クライアント側）：マイクで詠唱を聞いて、1行ずつ進め、言いきったら魔法をたのむ
 * 聞くのは「覚えている魔法」の詠唱だけ（サーバーが送ってくる）
 */
public class KoeMahouClient implements ClientModInitializer {

    // この時間（ミリ秒）だまっていると詠唱失敗
    static final long CHANT_TIMEOUT_MS = 6000;
    // チャットで唱えるときは 打つのに時間がかかるので、長めに待つ
    static final long CHAT_TIMEOUT_MS = 20000;

    final ConcurrentLinkedQueue<VoiceListener.Heard> queue = new ConcurrentLinkedQueue<>();
    // 覚えている魔法（サーバーから来る。来るまでは からっぽ）
    SpellBook book = new SpellBook();
    // サーバーから 新しい「覚えている魔法」が来たら ここに入る（ゲームのスレッドで入れかえる）
    volatile String learnedText = null;
    VoiceListener listener;
    // チャットで魔法を唱えるモード（/まほうチャット で切りかえ。config/koemahou/チャット.txt にとっておく）
    boolean chatMode = false;
    Path chatModeFile;

    // 今となえている魔法と、次に言う行（何も となえていないときは null）
    SpellBook.Spell chanting = null;
    int nextLine = 0;
    long lastLineTime = 0;
    boolean warnedNoServer = false;

    /** MOD が読みこまれたら、マイクを聞きはじめて、覚えている魔法のお知らせを待つ */
    @Override
    public void onInitializeClient() {
        Path folder = FabricLoader.getInstance().getConfigDir().resolve("koemahou");
        // 誤変換を読みがなでくらべるための辞書を 先に読みこんでおく
        Reading.prepare();
        chatModeFile = folder.resolve("チャット.txt");
        chatMode = loadChatMode();

        // 音声認識を別スレッドで始める（ゲームが止まらないように）
        listener = new VoiceListener(folder, book, queue);
        Thread thread = new Thread(listener, "koemahou-voice");
        thread.setDaemon(true);
        thread.start();

        // サーバーから「覚えている魔法」が来たら とっておく
        ClientPlayNetworking.registerGlobalReceiver(LearnedPayload.TYPE, (payload, context) -> learnedText = payload.text());

        // 毎tick、聞こえた行を進める
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);

        // チャットモードのときは、チャットに打った詠唱で魔法を進める
        ClientSendMessageEvents.ALLOW_CHAT.register(this::onChat);
        // /まほうチャット [オン|オフ] でモードを切りかえる
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(
                ClientCommands.literal("まほうチャット")
                        .executes(context -> setChatMode(context, !chatMode))
                        .then(ClientCommands.literal("オン").executes(context -> setChatMode(context, true)))
                        .then(ClientCommands.literal("オフ").executes(context -> setChatMode(context, false)))));
    }

    /** チャットを送る前：チャットモードで詠唱の行が入っていたら、魔法に使ってチャットには出さない */
    boolean onChat(String message) {
        if (!chatMode) return true;
        // 空白や「、」で区切ってあったら、1つずつ 詠唱の行としてしらべる
        boolean found = false;
        for (String piece : message.split("[\\s　、。,，]+")) {
            String text = SpellBook.normalize(piece);
            if (text.isEmpty()) continue;
            // まずは ぴったりの行をさがす（1つに何行か続けて書いてあっても、前から順に進める）
            SpellBook.Match exact = book.findFirst(text, 0);
            if (exact != null) {
                found = true;
                for (; exact != null; exact = book.findFirst(text, exact.end())) {
                    queue.add(new VoiceListener.Heard(message, exact, "line"));
                }
                continue;
            }
            // ぴったりがなければ、誤変換や 少しのまちがいを ゆるして いちばん近い行
            SpellBook.Match closest = book.findClosest(text);
            if (closest != null) {
                found = true;
                queue.add(new VoiceListener.Heard(message, closest, "line"));
            }
        }
        if (found) return false;
        // 区切ると見つからないとき（詠唱の行に空白が入っているなど）は、チャットぜんたいでしらべる
        String whole = SpellBook.normalize(message);
        SpellBook.Match match = book.findFirst(whole, 0);
        if (match == null) match = book.findClosest(whole);
        if (match == null) return true;
        for (; match != null; match = match.end() >= whole.length() ? null : book.findFirst(whole, match.end())) {
            queue.add(new VoiceListener.Heard(message, match, "line"));
        }
        return false;
    }

    /** チャットモードを オン・オフして、とっておく */
    int setChatMode(CommandContext<FabricClientCommandSource> context, boolean on) {
        chatMode = on;
        try {
            Files.createDirectories(chatModeFile.getParent());
            Files.writeString(chatModeFile, on ? "オン" : "オフ");
        } catch (Exception problem) {
            KoeMahouMod.LOG.warn("こえまほう: チャットモードを保存できませんでした", problem);
        }
        context.getSource().sendFeedback(Component.literal(on
                ? "§dこえまほう：チャットモード オン！ チャットに詠唱を打つと魔法が出ます（1行ずつでも、まとめて1回でもOK）"
                : "§dこえまほう：チャットモード オフ（声だけで唱えます）"));
        return 1;
    }

    /** とっておいたチャットモードを読む（なければ オフ） */
    boolean loadChatMode() {
        try {
            return Files.exists(chatModeFile) && Files.readString(chatModeFile).trim().equals("オン");
        } catch (Exception problem) {
            return false;
        }
    }

    /** 毎tick：覚えている魔法が変わっていたら入れかえ、聞こえた行で詠唱を進めて、だまりすぎていたら失敗にする */
    void onTick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null) return;

        // 覚えている魔法が新しく来たら、マイクの聞く言葉を入れかえる
        String text = learnedText;
        if (text != null) {
            learnedText = null;
            SpellBook newBook = new SpellBook();
            newBook.parse(List.of(text.split("\n")));
            boolean more = newBook.spells.size() > book.spells.size();
            book = newBook;
            chanting = null;
            listener.setBook(newBook);
            if (more) player.sendSystemMessage(Component.literal("§dこえまほう：唱えられる魔法 " + newBook.spells.size() + " こ"));
        }

        // 聞こえたものを全部しらべる
        VoiceListener.Heard heard;
        while ((heard = queue.poll()) != null) {
            onHeard(player, heard);
        }

        // だまりすぎたら詠唱失敗
        long timeout = chatMode ? CHAT_TIMEOUT_MS : CHANT_TIMEOUT_MS;
        if (chanting != null && System.currentTimeMillis() - lastLineTime > timeout) {
            fail(player, "§7詠唱がとぎれた…");
        }
    }

    /** 聞こえたものを1つしらべる */
    void onHeard(LocalPlayer player, VoiceListener.Heard heard) {
        // 状態のお知らせ
        switch (heard.event()) {
            case "ready" -> {
                player.sendSystemMessage(Component.literal("§dこえまほう：聞いています！魔導書を読んで覚えた魔法を唱えられます（覚えた魔法は魔法本にのります）"));
                return;
            }
            case "status", "error" -> {
                player.sendSystemMessage(Component.literal("§eこえまほう：" + listener.status));
                return;
            }
            case "problems" -> {
                for (String problem : heard.text().split("\n")) {
                    player.sendSystemMessage(Component.literal("§cこえまほう：" + problem));
                }
                return;
            }
            default -> { }
        }
        SpellBook.Match line = heard.line();
        if (line == null) return;
        KoeMahouMod.LOG.info("こえまほう: 「{}」→ {} の {} 行目", heard.text(), line.spell().name(), line.lineIndex() + 1);

        // 1行目なら、その魔法の詠唱を始める（となえている途中でも、始めなおし）
        if (line.lineIndex() == 0 && !(chanting == line.spell() && nextLine == 1)) {
            chanting = line.spell();
            nextLine = 0;
        }
        if (chanting == null) return;

        // 同じ行をもう一度言っただけなら、何もしない
        if (line.spell() == chanting && line.lineIndex() < nextLine) return;

        // 順番どおりの行なら進める。ちがう行なら失敗
        if (line.spell() == chanting && line.lineIndex() == nextLine) {
            nextLine++;
            lastLineTime = System.currentTimeMillis();
            int total = chanting.lines().size();
            send(player, new CastPayload(chanting.name(), chanting.program(), nextLine, total));
            if (nextLine >= total) {
                player.sendOverlayMessage(Component.literal("§d✦ " + chanting.name() + "！ ✦"));
                chanting = null;
            } else {
                showProgress(player);
            }
        } else {
            fail(player, "§7呪文をまちがえた…");
        }
    }

    /** 画面の下に、どこまで言えたかと、次の行を出す */
    void showProgress(LocalPlayer player) {
        int total = chanting.lines().size();
        StringBuilder bar = new StringBuilder();
        for (int index = 0; index < total; index++) bar.append(index < nextLine ? "§d◆" : "§8◇");
        String next = chanting.lines().get(nextLine);
        player.sendOverlayMessage(Component.literal("§d" + chanting.name() + " " + bar + " §7つぎ：「" + next + "」"));
    }

    /** 詠唱失敗にする */
    void fail(LocalPlayer player, String message) {
        if (chanting != null) send(player, new CastPayload(chanting.name(), "", -1, chanting.lines().size()));
        player.sendOverlayMessage(Component.literal(message));
        chanting = null;
        nextLine = 0;
    }

    /** サーバーにお知らせを送る（サーバーにこえまほうがないときは教える） */
    void send(LocalPlayer player, CastPayload payload) {
        if (ClientPlayNetworking.canSend(CastPayload.TYPE)) {
            ClientPlayNetworking.send(payload);
        } else if (!warnedNoServer) {
            warnedNoServer = true;
            player.sendSystemMessage(Component.literal("§cこえまほう：このサーバー（ワールドの持ち主）に こえまほう が入っていないので、魔法が出ません"));
        }
    }
}
