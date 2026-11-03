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
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
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
        // 読み辞書は少し重いので、先にうしろで読んでおく
        Thread loader = new Thread(Yomi::get, "koemine-yomi");
        loader.setDaemon(true);
        loader.start();
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

    /** 見つかった言葉1つ（どこからどこまでか、どうやって見つけたか） */
    record Hit(CommandTable.Entry entry, int start, int end, boolean exact) {}

    // ことば表の言葉の読み（毎回作らないように、おぼえておく）
    final Map<String, String> readingCache = new HashMap<>();

    /**
     * 文の中の言葉を前から順にさがして、アクションの一覧にする
     * そのまま同じ言葉 → 読みが同じ言葉（誤変換：老いて＝置いて）→ 読みが1〜2文字ちがう言葉（打ちまちがい）の順にさがす
     */
    List<String> findActions(String message) {
        String text = CommandTable.normalize(message);
        List<Hit> hits = findHits(text);
        List<String> actions = new ArrayList<>();
        int nameFrom = 0;
        for (Hit hit : hits) {
            String action = hit.entry().action();
            if (action.contains("name:")) {
                // 「〇〇を持って」なら、〇〇の部分をアクションにつける
                String name = text.substring(Math.min(nameFrom, hit.start()), hit.start()).trim();
                if (name.endsWith("を")) name = name.substring(0, name.length() - 1);
                action = action + "|" + name;
                nameFrom = hit.end();
            } else if (!action.startsWith("item:")) {
                nameFrom = hit.end();
            }
            actions.add(action);
        }
        return actions;
    }

    /** 言葉を全部さがして、前から順にならべる */
    List<Hit> findHits(String text) {
        Yomi yomi = Yomi.get();
        List<Hit> hits = new ArrayList<>();
        // 1. そのままの言葉と、読みが同じ言葉
        int position = 0;
        while (position < text.length()) {
            Hit best = null;
            for (CommandTable.Entry entry : mod.table.entries) {
                Hit hit = null;
                if (text.startsWith(entry.word(), position)) {
                    hit = new Hit(entry, position, position + entry.word().length(), true);
                } else if (yomi.loaded) {
                    String reading = readingOf(entry);
                    if (reading.length() >= 2) {
                        int end = yomi.matchAt(text, position, reading);
                        if (end > position) hit = new Hit(entry, position, end, false);
                    }
                }
                if (hit == null) continue;
                // 長いほう、同じ長さならそのままの言葉を選ぶ
                if (best == null || hit.end() > best.end() || (hit.end() == best.end() && hit.exact() && !best.exact())) best = hit;
            }
            if (best != null) {
                hits.add(best);
                position = best.end();
            } else {
                position++;
            }
        }

        // 2. 言葉が見つからなかった所を、読みが近い言葉でうめる（名前のところはのぞく）
        List<Hit> near = new ArrayList<>();
        int gapStart = 0;
        for (int i = 0; i <= hits.size(); i++) {
            int gapEnd = i < hits.size() ? hits.get(i).start() : text.length();
            boolean beforeName = i < hits.size() && hits.get(i).entry().action().contains("name:");
            if (!beforeName) fillGap(text, gapStart, gapEnd, near);
            if (i < hits.size()) gapStart = hits.get(i).end();
        }
        hits.addAll(near);
        hits.sort(Comparator.comparingInt(Hit::start));

        // 3. 言葉でない所が多い文（ふつうのおしゃべり）なら、ぴったりの言葉以外はつかわない
        boolean allExact = hits.stream().allMatch(Hit::exact);
        if (!allExact && coverage(text, hits) < 0.6) hits.removeIf(hit -> !hit.exact());
        // 4わり以上が言葉でない文は、おしゃべりとみなして ふつうに送る（「前回のボス…」で歩かないように）
        if (coverage(text, hits) < 0.6) hits.clear();
        return hits;
    }

    /** text の from〜to の中から、読みが近い言葉をさがして near に足す */
    void fillGap(String text, int from, int to, List<Hit> near) {
        if (to - from < 2) return;
        Yomi yomi = Yomi.get();
        Hit best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int start = from; start < to; start++) {
            for (int end = start + 2; end <= to; end++) {
                String piece = text.substring(start, end);
                // 「してね」のような、つなぎの文字だけの所は 言葉にしない
                if (piece.chars().allMatch(letter -> NEUTRAL.indexOf(letter) >= 0)) continue;
                String reading = yomi.reading(piece);
                for (CommandTable.Entry entry : mod.table.entries) {
                    if (entry.action().contains("name:")) continue;
                    String target = readingOf(entry);
                    if (target.length() < 3 || Math.abs(target.length() - reading.length()) > 3) continue;
                    // 「こわす」と「こわして」、「とまる」と「とまれ」のように、動詞の形だけちがうなら同じとみなす
                    String stem = verbStem(reading);
                    boolean sameVerb = stem != null && stem.length() >= 2 && stem.equals(verbStem(target));
                    int distance = sameVerb ? 0 : Yomi.distance(reading, target);
                    int allowed = target.length() >= 6 ? 2 : 1;
                    // 「こわい」「すごい」のような「〜い」の言葉は、動きの言葉にしない
                    if (!sameVerb && reading.endsWith("い") && !target.endsWith("い")) continue;
                    // 3文字以下の短い言葉は、同じ長さで1文字ちがいだけ（「しろ」が「うしろ」にならないように）
                    if (!sameVerb && target.length() <= 3 && reading.length() != target.length()) continue;
                    if (distance > allowed) continue;
                    // ちがいが少ないほう、同じなら長いほう
                    boolean better = best == null || distance < bestDistance
                            || (distance == bestDistance && end - start > best.end() - best.start());
                    if (better) {
                        best = new Hit(entry, start, end, false);
                        bestDistance = distance;
                    }
                }
            }
        }
        if (best == null) return;
        near.add(best);
        // のこりの左と右も さがす
        fillGap(text, from, best.start(), near);
        fillGap(text, best.end(), to, near);
    }

    // 動詞のおわりの形（長いほうから）
    static final String[] VERB_ENDINGS = {"してください", "してくれ", "しろ", "して", "って", "いて", "んで", "て", "ろ", "れ", "る", "す", "せ", "け", "め", "む"};

    /** 動詞のおわりをとった部分（こわして → こわ）。おわりがなければ null */
    static String verbStem(String reading) {
        for (String ending : VERB_ENDINGS) {
            if (reading.length() > ending.length() && reading.endsWith(ending)) return reading.substring(0, reading.length() - ending.length());
        }
        return null;
    }

    // 言葉のあいだにあっても気にしない文字（「破壊しろ」の しろ、「まえ！」の ！ など）
    static final String NEUTRAL = "しろてでよねをにへのとーー〜!！?？。、,.";

    /** 文のうち、言葉（と名前）になった文字のわりあい */
    static double coverage(String text, List<Hit> hits) {
        if (text.isEmpty()) return 0;
        boolean[] covered = new boolean[text.length()];
        int previousEnd = 0;
        for (Hit hit : hits) {
            // 「〇〇つくって」の〇〇も言葉の一部とみなす
            int start = hit.entry().action().contains("name:") ? previousEnd : hit.start();
            for (int i = Math.max(0, start); i < hit.end(); i++) covered[i] = true;
            previousEnd = Math.max(previousEnd, hit.end());
        }
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (covered[i] || NEUTRAL.indexOf(text.charAt(i)) >= 0) count++;
        }
        return (double) count / text.length();
    }

    /** ことば表の言葉の読み（ひらがな） */
    String readingOf(CommandTable.Entry entry) {
        return readingCache.computeIfAbsent(entry.word(), word -> Yomi.get().reading(word));
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
