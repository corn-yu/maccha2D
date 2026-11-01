package com.maccha.koemahou;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * じゅもん表：どの魔法に、どんな詠唱の行があるか（config/koemahou/じゅもん.txt）
 */
public class SpellBook {

    // この版の じゅもん表の目じるし（変わったら作りなおす）
    static final String VERSION_MARK = "# じゅもん表 v1";

    /** 1つの魔法（id と、見せる名前と、詠唱の行） */
    public record Spell(String id, String name, List<String> lines) {}

    /** 文の中で見つかった1行（どの魔法の何行目か） */
    public record Match(Spell spell, int lineIndex, int start, int end) {}

    // はじめに作る じゅもん表
    static final String DEFAULT_TEXT = VERSION_MARK + """

            # 呪文は [魔法のid] の下に、1行ずつ順番に書きます
            # 全部の行を順番に言いきると魔法が出ます。行の文は自由に変えてOK
            # （マイクの日本語モデルが知らない言葉の行は使えません。ゲームのチャットで教えます）
            # 魔法の id: fireball（ファイアボール） heal（ヒール） warp（ワープ）

            [fireball] ファイアボール
            燃えさかる炎よ
            わが手に集まれ
            敵を焼きつくせ
            ファイアボール

            [heal] ヒール
            やさしき光よ
            この身の傷を
            いやしたまえ
            ヒール

            [warp] ワープ
            風の精霊よ
            わが身をはこび
            まえへとばせ
            ワープ
            """;

    final Map<String, Spell> spells = new LinkedHashMap<>();
    // 読めなかった行（モデルが知らない言葉など）
    final List<String> problems = new ArrayList<>();

    /** じゅもん表を読む（なければ作る。古い版なら じゅもん-old.txt に移して作りなおす） */
    public void load(Path file) {
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file)) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                if (!text.contains(VERSION_MARK)) {
                    Files.move(file, file.resolveSibling("じゅもん-old.txt"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            if (!Files.exists(file)) Files.writeString(file, DEFAULT_TEXT, StandardCharsets.UTF_8);
            parse(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IOException error) {
            KoeMahouMod.LOG.warn("こえまほう: じゅもん表が読めないので、はじめの表を使います: {}", error.toString());
            parse(List.of(DEFAULT_TEXT.split("\n")));
        }
    }

    /** じゅもん表の文を、魔法の一覧にする */
    void parse(List<String> textLines) {
        spells.clear();
        String id = null;
        String name = null;
        List<String> lines = new ArrayList<>();
        for (String raw : textLines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            // [fireball] ファイアボール のような見出し
            if (line.startsWith("[") && line.contains("]")) {
                if (id != null && !lines.isEmpty()) spells.put(id, new Spell(id, name, List.copyOf(lines)));
                id = line.substring(1, line.indexOf(']')).strip();
                name = line.substring(line.indexOf(']') + 1).strip();
                if (name.isEmpty()) name = id;
                lines = new ArrayList<>();
                continue;
            }
            if (id != null) lines.add(line);
        }
        if (id != null && !lines.isEmpty()) spells.put(id, new Spell(id, name, List.copyOf(lines)));
    }

    /** 文の from より後ろで、いちばん前にある詠唱の行をさがす（同じ場所なら長い行） */
    public Match findFirst(String text, int from) {
        Match best = null;
        for (Spell spell : spells.values()) {
            for (int index = 0; index < spell.lines().size(); index++) {
                String line = normalize(spell.lines().get(index));
                if (line.isEmpty()) continue;
                int start = text.indexOf(line, from);
                if (start < 0) continue;
                boolean earlier = best == null || start < best.start();
                boolean longer = best != null && start == best.start() && line.length() > best.end() - best.start();
                if (earlier || longer) best = new Match(spell, index, start, start + line.length());
            }
        }
        return best;
    }

    /** 文字をそろえる（空白をとる・カタカナをひらがなに・全角数字を半角に） */
    public static String normalize(String text) {
        StringBuilder result = new StringBuilder();
        for (char letter : text.toCharArray()) {
            // 空白はとばす
            if (Character.isWhitespace(letter) || letter == '　') continue;
            // 、。！などの記号もとばす
            if ("、。！？!?・「」".indexOf(letter) >= 0) continue;
            // カタカナ（ァ〜ヶ）はひらがなにする
            if (letter >= 'ァ' && letter <= 'ヶ') letter = (char) (letter - 0x60);
            // 全角数字は半角にする
            if (letter >= '０' && letter <= '９') letter = (char) (letter - '０' + '0');
            result.append(letter);
        }
        return result.toString();
    }

    /** 詠唱の行を全部、モデルが知っている単語に区切って、Vosk の文法 JSON にする */
    public String grammarJson(Set<String> vocabulary) {
        problems.clear();
        Set<String> phrases = new LinkedHashSet<>();
        for (Spell spell : spells.values()) {
            for (String line : spell.lines()) {
                String phrase = splitWords(line.replace(" ", "").replace("　", ""), vocabulary);
                if (phrase == null) {
                    problems.add(line);
                } else {
                    phrases.add(phrase);
                }
            }
        }
        // 呪文でない話は [unk]（わからない）になるようにする
        StringBuilder json = new StringBuilder("[");
        for (String phrase : phrases) json.append('"').append(phrase.replace("\"", "")).append("\",");
        json.append("\"[unk]\"]");
        return json.toString();
    }

    /** 言葉を、モデルが知っている単語に区切る（いちばん少ない数で。区切れなければ null） */
    static String splitWords(String word, Set<String> vocabulary) {
        int length = word.length();
        // best[i] = 先頭から i 文字までを区切ったときの単語の数、from[i] = 最後の単語の始まり
        int[] best = new int[length + 1];
        int[] from = new int[length + 1];
        java.util.Arrays.fill(best, Integer.MAX_VALUE);
        best[0] = 0;
        for (int end = 1; end <= length; end++) {
            for (int start = Math.max(0, end - 12); start < end; start++) {
                if (best[start] == Integer.MAX_VALUE) continue;
                if (!vocabulary.contains(word.substring(start, end))) continue;
                if (best[start] + 1 < best[end]) {
                    best[end] = best[start] + 1;
                    from[end] = start;
                }
            }
        }
        if (best[length] == Integer.MAX_VALUE) return null;
        // うしろから たどって、空白でつなぐ
        List<String> parts = new ArrayList<>();
        for (int end = length; end > 0; end = from[end]) parts.add(0, word.substring(from[end], end));
        return String.join(" ", parts);
    }
}
