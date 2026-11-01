package com.maccha.koemahou;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * こえまほう（クライアント側）：マイクで詠唱を聞いて、1行ずつ進め、言いきったら魔法をたのむ
 */
public class KoeMahouClient implements ClientModInitializer {

    // この時間（ミリ秒）だまっていると詠唱失敗
    static final long CHANT_TIMEOUT_MS = 6000;

    final ConcurrentLinkedQueue<VoiceListener.Heard> queue = new ConcurrentLinkedQueue<>();
    SpellBook book = new SpellBook();
    Path bookFile;
    long bookFileTime = 0;
    int tickCount = 0;
    VoiceListener listener;

    // 今となえている魔法と、次に言う行（何も となえていないときは null）
    SpellBook.Spell chanting = null;
    int nextLine = 0;
    long lastLineTime = 0;
    boolean warnedNoServer = false;

    /** MOD が読みこまれたら、じゅもん表を読んで、マイクを聞きはじめる */
    @Override
    public void onInitializeClient() {
        // じゅもん表を読む
        Path folder = FabricLoader.getInstance().getConfigDir().resolve("koemahou");
        bookFile = folder.resolve("じゅもん.txt");
        book.load(bookFile);
        bookFileTime = fileTime();
        KoeMahouMod.LOG.info("こえまほう: 魔法を {} こ読みこみました", book.spells.size());

        // 音声認識を別スレッドで始める（ゲームが止まらないように）
        listener = new VoiceListener(folder, book, queue);
        Thread thread = new Thread(listener, "koemahou-voice");
        thread.setDaemon(true);
        thread.start();

        // 毎tick、聞こえた行を進める
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    /** 毎tick：聞こえた行で詠唱を進めて、だまりすぎていたら失敗にする */
    void onTick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null) return;

        // じゅもん.txt が書きかえられたら、読みなおす（1秒ごとに見る）
        tickCount++;
        if (tickCount % 20 == 0) reloadIfChanged(player);

        // 聞こえたものを全部しらべる
        VoiceListener.Heard heard;
        while ((heard = queue.poll()) != null) {
            onHeard(player, heard);
        }

        // だまりすぎたら詠唱失敗
        if (chanting != null && System.currentTimeMillis() - lastLineTime > CHANT_TIMEOUT_MS) {
            fail(player, "§7詠唱がとぎれた…");
        }
    }

    /** 聞こえたものを1つしらべる */
    void onHeard(LocalPlayer player, VoiceListener.Heard heard) {
        // 状態のお知らせ
        switch (heard.event()) {
            case "ready" -> {
                player.sendSystemMessage(Component.literal("§dこえまほう：聞いています！呪文は config/koemahou/じゅもん.txt にあります"));
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

    /** じゅもん.txt が書きかえられていたら読みなおす */
    void reloadIfChanged(LocalPlayer player) {
        long time = fileTime();
        if (time == bookFileTime) return;
        bookFileTime = time;
        SpellBook newBook = new SpellBook();
        newBook.load(bookFile);
        book = newBook;
        chanting = null;
        listener.setBook(newBook);
        player.sendSystemMessage(Component.literal("§dこえまほう：じゅもん表を読みなおしました（魔法 " + newBook.spells.size() + " こ）"));
    }

    /** じゅもん.txt の書きかえ時刻（なければ 0） */
    long fileTime() {
        try {
            return Files.getLastModifiedTime(bookFile).toMillis();
        } catch (Exception error) {
            return 0;
        }
    }
}
