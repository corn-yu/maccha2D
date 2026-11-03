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
    static final String VERSION_MARK = "# じゅもん表 v5";

    /** 1つの魔法（名前と、効果の部品の文と、詠唱の行） */
    public record Spell(String name, String program, List<String> lines) {}

    /** 文の中で見つかった1行（どの魔法の何行目か） */
    public record Match(Spell spell, int lineIndex, int start, int end) {}

    // はじめに作る じゅもん表（見本。ぜんぶ書きかえてOK）
    static final String DEFAULT_TEXT = VERSION_MARK + """

            # ここは まっちゃの魔法の本です。呪文も効果も自由に作ってね
            # くわしい書きかたは「魔法の作りかた.txt」を見てね
            #
            #   [魔法の名前]
            #   詠唱の1行目        ← ふつうの行は、声で言う詠唱
            #   詠唱の2行目
            #   ＞部品 数字 …      ← ＞ で始まる行は、魔法の効果（上から順番に動く）
            #
            # ・# で始まる行はメモ（読まれない）
            # ・保存すると、ゲームの中ですぐ読みなおします
            # ・書きまちがいや、マイクが知らない言葉は、チャットで教えます
            #
            # ↓ 下は見本です。消して、自分の魔法を書いてね

            [ファイアボール]
            炎よ
            ファイアボール
            ＞撃つ なに=火の玉 数=3 速さ=2
            ＞音 種類=ブレイズ
            ＞待ち時間 秒=2

            [ヒール]
            光よ
            ヒール
            ＞回復 量=8
            ＞粒 種類=ハート 数=10
            ＞待ち時間 秒=15

            [ブラックホール]
            闇よ
            ブラックホール
            ＞場所 どこ=見ている所 距離=20
            ＞場所 どこ=上 距離=3
            ＞音 種類=ウォーデン
            ＞くりかえす 回数=50 間隔=0.1
            ＞  球 種類=闇 大きさ=1.5
            ＞  吸いこむ 半径=18 秒=0.1 強さ=1
            ＞ここまで
            ＞爆発 大きさ=7
            ＞ダメージ 量=40 半径=12
            ＞吹きとばす 半径=12 強さ=3
            ＞待ち時間 秒=45

            [ビーム]
            光よ
            つらぬけ
            ＞ビーム 種類=光 長さ=30 ダメージ=15
            ＞向き どっち=横にまわす 角度=15
            ＞ビーム 種類=光 長さ=30 ダメージ=15
            ＞向き どっち=横にまわす 角度=-30
            ＞ビーム 種類=光 長さ=30 ダメージ=15
            ＞待ち時間 秒=8
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
        List<String> lines = new ArrayList<>();
        List<String> program = new ArrayList<>();
        List<Integer> programLineNumbers = new ArrayList<>();
        for (int index = 0; index < textLines.size(); index++) {
            String line = textLines.get(index).strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            // [魔法の名前] のような見出し（うしろに効果を1つ書いてもよい: [闇] ブラックホール）
            if (line.startsWith("[") && line.contains("]")) {
                addSpell(name, lines, program, programLineNumbers);
                name = line.substring(1, line.indexOf(']')).strip();
                lines = new ArrayList<>();
                program = new ArrayList<>();
                programLineNumbers = new ArrayList<>();
                String shortEffect = line.substring(line.indexOf(']') + 1).strip();
                if (!shortEffect.isEmpty()) {
                    program.add(shortEffect);
                    programLineNumbers.add(index + 1);
                }
                continue;
            }
            if (name == null) continue;
            // ＞ で始まる行は効果、ほかは詠唱
            if (line.startsWith("＞") || line.startsWith(">")) {
                program.add(line);
                programLineNumbers.add(index + 1);
            } else {
                lines.add(line);
            }
        }
        addSpell(name, lines, program, programLineNumbers);
    }

    /** 1つの魔法を一覧に足す（行がない・効果がない ときは足さない） */
    void addSpell(String name, List<String> lines, List<String> program, List<Integer> programLineNumbers) {
        if (name == null) return;
        if (lines.isEmpty()) {
            problems.add("[" + name + "] に詠唱の行がありません");
            return;
        }
        List<Magic.Node> nodes = Magic.parse(program, programLineNumbers, name, problems);
        if (nodes.isEmpty()) {
            problems.add("[" + name + "] に効果（＞ で始まる行）がありません");
            return;
        }
        spells.put(name, new Spell(name, String.join("\n", program), List.copyOf(lines)));
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

    /** 文ぜんたいが どの詠唱の行に いちばん近いかを「読みがな」でさがす（誤変換や 少しの言いまちがいを ゆるす。なければ null） */
    public Match findClosest(String text) {
        if (text.isEmpty()) return null;
        String sound = Reading.of(text);
        Match best = null;
        int bestDistance = Integer.MAX_VALUE;
        int bestLength = 0;
        for (Spell spell : spells.values()) {
            for (int index = 0; index < spell.lines().size(); index++) {
                String line = Reading.of(spell.lines().get(index));
                if (line.isEmpty()) continue;
                int distance = distance(sound, line);
                if (distance > allowedMistakes(line.length())) continue;
                // ちがいが少ないほうを選ぶ（同じなら長い行）
                if (distance < bestDistance || (distance == bestDistance && line.length() > bestLength)) {
                    best = new Match(spell, index, 0, text.length());
                    bestDistance = distance;
                    bestLength = line.length();
                }
            }
        }
        return best;
    }

    /** 行の長さごとに、何文字までちがっていてもいいか（3文字以下は ぴったりだけ、あとは 6文字に1つくらい） */
    static int allowedMistakes(int length) {
        if (length <= 3) return 0;
        return Math.max(1, length / 6);
    }

    /** 2つの文が 何文字ちがうか（1文字の 入れかえ・たりない・よけい を1つと数える） */
    static int distance(String first, String second) {
        int[] previous = new int[second.length() + 1];
        int[] current = new int[second.length() + 1];
        for (int column = 0; column <= second.length(); column++) previous[column] = column;
        for (int row = 1; row <= first.length(); row++) {
            current[0] = row;
            for (int column = 1; column <= second.length(); column++) {
                int change = first.charAt(row - 1) == second.charAt(column - 1) ? 0 : 1;
                current[column] = Math.min(Math.min(current[column - 1] + 1, previous[column] + 1), previous[column - 1] + change);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[second.length()];
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
