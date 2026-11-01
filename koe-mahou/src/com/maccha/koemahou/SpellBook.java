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
    static final String VERSION_MARK = "# じゅもん表 v4";

    // 書ける効果（日本語の名前 → サーバーに送る id）
    static final Map<String, String> EFFECTS = new LinkedHashMap<>();
    static {
        EFFECTS.put("ファイアボール", "fireball");
        EFFECTS.put("ヒール", "heal");
        EFFECTS.put("ワープ", "warp");
        EFFECTS.put("ブラックホール", "blackhole");
        EFFECTS.put("メテオ", "meteor");
        EFFECTS.put("ジャッジメント", "judgment");
        EFFECTS.put("サンクチュアリ", "sanctuary");
        EFFECTS.put("クロノスタシス", "chrono");
    }

    /** 1つの魔法（名前と、効果の id と、詠唱の行） */
    public record Spell(String name, String effect, List<String> lines) {}

    /** 文の中で見つかった1行（どの魔法の何行目か） */
    public record Match(Spell spell, int lineIndex, int start, int end) {}

    // はじめに作る じゅもん表（見本。ぜんぶ書きかえてOK）
    static final String DEFAULT_TEXT = VERSION_MARK + """

            # ここは まっちゃの魔法の本です。呪文は自由に作ってね
            #
            # 書きかた:
            #   [魔法の名前] 効果
            #   1行目の詠唱
            #   2行目の詠唱
            #   （何行でもOK。全部の行を順番に言いきると魔法が出る）
            #
            # 効果は次の中から1つ選ぶ:
            #   ファイアボール … 火の玉を3発
            #   ヒール … ハート4つ回復
            #   ワープ … 前へ10ブロック瞬間移動
            #   ブラックホール … モブを5秒吸いこんで大爆散
            #   メテオ … 隕石10こ＋巨大隕石
            #   ジャッジメント … 雷の檻でうかせて雷、さいごに光の柱
            #   サンクチュアリ … 10秒の回復の結界、モブははじく
            #   クロノスタシス … モブの時間を6秒止めて、ダメージ2倍で返す
            #
            # ・同じ効果の魔法を、ちがう呪文でいくつ作ってもOK
            # ・保存すると、ゲームの中ですぐ読みなおします
            # ・マイクの日本語モデルが知らない言葉は使えません。そのときはチャットで「どこが知らない言葉か」を教えます
            # ・# で始まる行はメモ（読まれない）
            #
            # ↓ 下は見本です。消して、自分の呪文を書いてね

            [ファイアボール] ファイアボール
            炎よ
            ファイアボール

            [ヒール] ヒール
            光よ
            ヒール

            [ワープ] ワープ
            風よ
            ワープ

            [ブラックホール] ブラックホール
            闇よ
            ブラックホール

            [メテオ] メテオ
            星よ
            メテオ

            [ジャッジメント] ジャッジメント
            雷よ
            ジャッジメント

            [サンクチュアリ] サンクチュアリ
            聖なる光よ
            サンクチュアリ

            [クロノスタシス] クロノスタシス
            時よ止まれ
            クロノスタシス
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

    /** じゅもん表の文を、魔法の一覧にする（書きまちがいは problems に入れる） */
    void parse(List<String> textLines) {
        spells.clear();
        problems.clear();
        String name = null;
        String effect = null;
        List<String> lines = new ArrayList<>();
        for (String raw : textLines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            // [魔法の名前] 効果 のような見出し
            if (line.startsWith("[") && line.contains("]")) {
                addSpell(name, effect, lines);
                name = line.substring(1, line.indexOf(']')).strip();
                String effectName = line.substring(line.indexOf(']') + 1).strip();
                effect = EFFECTS.get(effectName);
                if (effect == null) {
                    // id（fireball など）で書いてあってもOK
                    effect = EFFECTS.containsValue(effectName) ? effectName : null;
                }
                if (effect == null) problems.add("[" + name + "] の効果「" + effectName + "」がわかりません（ファイアボール・ヒール などから選んでね）");
                lines = new ArrayList<>();
                continue;
            }
            if (name != null) lines.add(line);
        }
        addSpell(name, effect, lines);
    }

    /** 1つの魔法を一覧に足す（効果がわからない・行がないときは足さない） */
    void addSpell(String name, String effect, List<String> lines) {
        if (name == null || effect == null) return;
        if (lines.isEmpty()) {
            problems.add("[" + name + "] に詠唱の行がありません");
            return;
        }
        spells.put(name, new Spell(name, effect, List.copyOf(lines)));
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
        Set<String> phrases = new LinkedHashSet<>();
        for (Spell spell : spells.values()) {
            for (String line : spell.lines()) {
                String phrase = splitWords(line.replace(" ", "").replace("　", ""), vocabulary);
                if (phrase == null) {
                    problems.add("[" + spell.name() + "]「" + line + "」の「" + unknownPart(line.replace(" ", "").replace("　", ""), vocabulary)
                            + "」のあたりをマイクが知りません（ひらがなや、ふつうの言葉に変えてみてね）");
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

    /** 区切れない言葉の、知らない部分（先頭から区切れるところの すぐ後ろ、4文字ぶん）を返す */
    static String unknownPart(String word, Set<String> vocabulary) {
        int length = word.length();
        boolean[] reach = new boolean[length + 1];
        reach[0] = true;
        int farthest = 0;
        for (int end = 1; end <= length; end++) {
            for (int start = Math.max(0, end - 12); start < end && !reach[end]; start++) {
                if (reach[start] && vocabulary.contains(word.substring(start, end))) reach[end] = true;
            }
            if (reach[end]) farthest = end;
        }
        return word.substring(farthest, Math.min(length, farthest + 4));
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
