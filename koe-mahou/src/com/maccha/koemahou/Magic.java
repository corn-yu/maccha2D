package com.maccha.koemahou;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 効果の部品の書きかた（じゅもん.txt の「＞」の行）を読む。クライアントとサーバーの両方で使う
 */
public class Magic {

    /** 1つの部品（名前と、うしろに書いた言葉や数字） */
    public record Part(String name, List<String> args, int lineNumber) {
        /** index 番目の数字（書いていなければ fallback） */
        public double number(int index, double fallback) {
            if (index >= args.size()) return fallback;
            try {
                return Double.parseDouble(cleanNumber(args.get(index)));
            } catch (NumberFormatException error) {
                return fallback;
            }
        }

        /** index 番目の言葉（書いていなければ fallback） */
        public String word(int index, String fallback) {
            return index < args.size() ? args.get(index) : fallback;
        }
    }

    /** 部品の並び。くりかえし は children に中身が入る */
    public record Node(Part part, List<Node> children) {}

    // 部品の名前と、何こ数字や言葉を書けるか（いちばん少ない数, いちばん多い数）
    static final Map<String, int[]> PARTS = Map.ofEntries(
            Map.entry("場所", new int[]{1, 2}),
            Map.entry("待つ", new int[]{1, 1}),
            Map.entry("くりかえす", new int[]{1, 2}),
            Map.entry("ここまで", new int[]{0, 0}),
            Map.entry("爆発", new int[]{0, 1}),
            Map.entry("ダメージ", new int[]{1, 2}),
            Map.entry("燃やす", new int[]{1, 2}),
            Map.entry("雷", new int[]{0, 2}),
            Map.entry("吸いこむ", new int[]{2, 3}),
            Map.entry("吹きとばす", new int[]{1, 2}),
            Map.entry("うかせる", new int[]{2, 3}),
            Map.entry("止める", new int[]{2, 2}),
            Map.entry("回復", new int[]{1, 2}),
            Map.entry("効果", new int[]{2, 5}),
            Map.entry("ワープ", new int[]{0, 1}),
            Map.entry("撃つ", new int[]{1, 4}),
            Map.entry("よぶ", new int[]{1, 2}),
            Map.entry("粒", new int[]{1, 3}),
            Map.entry("輪", new int[]{1, 2}),
            Map.entry("柱", new int[]{1, 2}),
            Map.entry("球", new int[]{1, 2}),
            Map.entry("魔法陣", new int[]{1, 2}),
            Map.entry("線", new int[]{1, 1}),
            Map.entry("音", new int[]{1, 3}),
            Map.entry("待ち時間", new int[]{1, 1}),
            Map.entry("ファイアボール", new int[]{0, 0}),
            Map.entry("ヒール", new int[]{0, 0}),
            Map.entry("ブラックホール", new int[]{0, 0}),
            Map.entry("メテオ", new int[]{0, 0}),
            Map.entry("ジャッジメント", new int[]{0, 0}),
            Map.entry("サンクチュアリ", new int[]{0, 0}),
            Map.entry("クロノスタシス", new int[]{0, 0}));

    // 言葉で書くところ（数字でなくてよい場所）: 部品の名前 → 何番目
    static final Map<String, Set<Integer>> WORD_ARGS = Map.ofEntries(
            Map.entry("場所", Set.of(0)),
            Map.entry("効果", Set.of(0, 3)),
            Map.entry("撃つ", Set.of(0)),
            Map.entry("よぶ", Set.of(0)),
            Map.entry("粒", Set.of(0)),
            Map.entry("輪", Set.of(0)),
            Map.entry("柱", Set.of(0)),
            Map.entry("球", Set.of(0)),
            Map.entry("魔法陣", Set.of(0)),
            Map.entry("線", Set.of(0)),
            Map.entry("音", Set.of(0)));

    // 場所の書きかた
    static final Set<String> PLACES = Set.of("自分", "見ている所", "上", "前", "まわり", "もどる");

    /** 「＞」の行をまとめて読む（まちがいは problems に足す。spellName は知らせる用） */
    public static List<Node> parse(List<String> lines, List<Integer> lineNumbers, String spellName, List<String> problems) {
        List<Node> root = new ArrayList<>();
        // くりかえし の中に入っているときの、親の並び
        List<List<Node>> stack = new ArrayList<>();
        List<Node> current = root;
        for (int index = 0; index < lines.size(); index++) {
            int lineNumber = lineNumbers.isEmpty() ? index + 1 : lineNumbers.get(index);
            Part part = readPart(lines.get(index), lineNumber);
            if (part == null) continue;
            String where = "[" + spellName + "] " + lineNumber + "行目「" + lines.get(index).strip() + "」: ";

            // 知らない部品
            int[] counts = PARTS.get(part.name());
            if (counts == null) {
                problems.add(where + "「" + part.name() + "」という部品はありません");
                continue;
            }
            if (part.args().size() < counts[0] || part.args().size() > counts[1]) {
                problems.add(where + "数字や言葉の数がちがいます（" + counts[0] + "〜" + counts[1] + "こ）");
                continue;
            }
            // 数字のところに数字が書いてあるか
            Set<Integer> words = WORD_ARGS.getOrDefault(part.name(), Set.of());
            boolean bad = false;
            for (int argIndex = 0; argIndex < part.args().size(); argIndex++) {
                if (words.contains(argIndex)) continue;
                try {
                    Double.parseDouble(cleanNumber(part.args().get(argIndex)));
                } catch (NumberFormatException error) {
                    problems.add(where + "「" + part.args().get(argIndex) + "」は数字にしてね");
                    bad = true;
                }
            }
            if (bad) continue;
            if (part.name().equals("場所") && !PLACES.contains(part.word(0, ""))) {
                problems.add(where + "場所は 自分・見ている所・上・前・まわり・もどる のどれかにしてね");
                continue;
            }

            // くりかえす 〜 ここまで
            if (part.name().equals("くりかえす")) {
                Node node = new Node(part, new ArrayList<>());
                current.add(node);
                stack.add(current);
                current = node.children();
            } else if (part.name().equals("ここまで")) {
                if (stack.isEmpty()) {
                    problems.add(where + "「くりかえす」がないのに「ここまで」があります");
                } else {
                    current = stack.remove(stack.size() - 1);
                }
            } else {
                current.add(new Node(part, List.of()));
            }
        }
        if (!stack.isEmpty()) problems.add("[" + spellName + "] 「くりかえす」の「ここまで」がありません（さいごまで くりかえします）");
        return root;
    }

    /** 1行を部品にする（「＞」のあとを空白で区切る。空なら null） */
    static Part readPart(String line, int lineNumber) {
        String text = line.strip();
        if (text.startsWith("＞") || text.startsWith(">")) text = text.substring(1);
        text = text.replace('　', ' ').strip();
        if (text.isEmpty()) return null;
        String[] pieces = text.split("\\s+");
        List<String> args = new ArrayList<>();
        for (int index = 1; index < pieces.length; index++) args.add(pieces[index]);
        return new Part(pieces[0], args, lineNumber);
    }

    /** 全角数字・「秒」「ブロック」などを とって、数字だけにする */
    static String cleanNumber(String text) {
        StringBuilder result = new StringBuilder();
        for (char letter : text.toCharArray()) {
            if (letter >= '０' && letter <= '９') letter = (char) (letter - '０' + '0');
            if (letter == '．') letter = '.';
            if (letter == '－' || letter == 'ー') letter = '-';
            if ((letter >= '0' && letter <= '9') || letter == '.' || letter == '-') result.append(letter);
        }
        return result.toString();
    }

    /** 部品の並びから「待ち時間」をさがす（なければ 3秒） */
    public static double cooldownSeconds(List<Node> nodes) {
        for (Node node : nodes) {
            if (node.part().name().equals("待ち時間")) return node.part().number(0, 3);
        }
        return 3;
    }
}
