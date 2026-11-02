package com.maccha.koemahou;

import java.util.ArrayList;
import java.util.Arrays;
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
            if (index >= args.size() || args.get(index) == null) return fallback;
            try {
                return Double.parseDouble(cleanNumber(args.get(index)));
            } catch (NumberFormatException error) {
                return fallback;
            }
        }

        /** index 番目の言葉（書いていなければ fallback） */
        public String word(int index, String fallback) {
            return index < args.size() && args.get(index) != null ? args.get(index) : fallback;
        }
    }

    /** 部品の並び。くりかえし は children に中身が入る */
    public record Node(Part part, List<Node> children) {}

    // 部品の名前と、うしろに書くものの名前（順番）。「名前=数字」でも、順番どおりに数字だけでも書ける
    // さいごの数字は「書かないといけない数」
    static final Map<String, Object[]> PARTS = Map.ofEntries(
            spec("場所", 1, "どこ", "距離"),
            spec("向き", 1, "どっち", "角度"),
            spec("待つ", 1, "秒"),
            spec("くりかえす", 1, "回数", "間隔"),
            spec("ここまで", 0),
            spec("爆発", 0, "大きさ"),
            spec("ダメージ", 1, "量", "半径"),
            spec("燃やす", 1, "秒", "半径"),
            spec("雷", 0, "本数", "半径"),
            spec("吸いこむ", 2, "半径", "秒", "強さ"),
            spec("吹きとばす", 1, "半径", "強さ"),
            spec("押す", 1, "半径", "強さ"),
            spec("うかせる", 2, "半径", "秒", "高さ"),
            spec("止める", 2, "半径", "秒"),
            spec("回復", 1, "量", "半径"),
            spec("効果", 2, "名前", "秒", "レベル", "だれ", "半径"),
            spec("ワープ", 0, "距離"),
            spec("撃つ", 1, "なに", "数", "速さ", "ひろがり"),
            spec("ビーム", 1, "種類", "長さ", "ダメージ"),
            spec("飛ばす", 1, "種類", "速さ", "距離"),
            spec("よぶ", 1, "なに", "数"),
            spec("粒", 1, "種類", "数", "広がり"),
            spec("輪", 1, "種類", "半径"),
            spec("柱", 1, "種類", "高さ"),
            spec("球", 1, "種類", "大きさ"),
            spec("魔法陣", 1, "種類", "半径"),
            spec("線", 1, "種類"),
            spec("音", 1, "種類", "大きさ", "高さ"),
            spec("待ち時間", 1, "秒"),
            spec("確率", 1, "パーセント"),
            spec("凍らせる", 1, "半径", "秒"),
            spec("引きよせる", 1, "半径", "強さ"),
            spec("うず", 2, "半径", "秒", "速さ"),
            spec("竜巻", 2, "半径", "秒", "高さ"),
            spec("衝撃波", 1, "半径", "ダメージ"),
            spec("たたきつける", 1, "半径", "ダメージ"),
            spec("連鎖雷", 1, "本数", "ダメージ"),
            spec("隕石", 0, "大きさ", "ダメージ"),
            spec("牙", 0, "数"),
            spec("雨", 1, "なに", "数", "半径", "高さ"),
            spec("ダッシュ", 0, "強さ"),
            spec("飛ぶ", 0, "高さ"),
            spec("壁", 1, "ブロック", "幅", "高さ", "秒"),
            spec("ドーム", 1, "ブロック", "半径", "秒"),
            spec("足場", 1, "ブロック", "半径", "秒"),
            spec("らせん", 1, "種類", "半径", "高さ"),
            spec("文字", 1, "文"),
            spec("ファイアボール", 0),
            spec("ヒール", 0),
            spec("ブラックホール", 0),
            spec("メテオ", 0),
            spec("ジャッジメント", 0),
            spec("サンクチュアリ", 0),
            spec("クロノスタシス", 0));

    // 数字でなく言葉を書くところ
    static final Set<String> WORD_NAMES = Set.of("どこ", "どっち", "名前", "だれ", "なに", "種類", "ブロック", "文");

    // 場所と向きの書きかた
    static final Set<String> PLACES = Set.of("自分", "見ている所", "上", "前", "まわり", "もどる", "近くのモブ", "ランダムなモブ");
    static final Set<String> DIRECTIONS = Set.of("見ている方", "上", "下", "前", "うしろ", "右", "左", "ランダム",
            "場所の方", "自分の方", "近くのモブの方", "横にまわす", "縦にまわす");

    /** PARTS の1行を作る（部品の名前, 書かないといけない数, うしろに書くものの名前…） */
    static Map.Entry<String, Object[]> spec(String name, int required, String... argNames) {
        return Map.entry(name, new Object[]{required, argNames});
    }

    /** 部品の、うしろに書くものの名前（知らない部品なら null） */
    public static String[] argNames(String partName) {
        Object[] spec = PARTS.get(partName);
        return spec == null ? null : (String[]) spec[1];
    }

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
            Object[] spec = PARTS.get(part.name());
            if (spec == null) {
                problems.add(where + "「" + part.name() + "」という部品はありません");
                continue;
            }
            String[] names = (String[]) spec[1];
            int required = (Integer) spec[0];
            if (part.args().size() > names.length || part.args().contains(UNKNOWN_NAME)) {
                problems.add(where + "書けるのは " + (names.length == 0 ? "なし" : String.join("・", names)) + " です");
                continue;
            }
            int given = 0;
            for (int argIndex = 0; argIndex < required && argIndex < part.args().size(); argIndex++) {
                if (part.args().get(argIndex) != null) given++;
            }
            if (given < required) {
                problems.add(where + String.join("・", Arrays.copyOf(names, required)) + " を書いてね");
                continue;
            }
            // 数字のところに数字が書いてあるか
            boolean bad = false;
            for (int argIndex = 0; argIndex < part.args().size(); argIndex++) {
                String value = part.args().get(argIndex);
                if (value == null || WORD_NAMES.contains(names[argIndex])) continue;
                try {
                    Double.parseDouble(cleanNumber(value));
                } catch (NumberFormatException error) {
                    problems.add(where + names[argIndex] + "「" + value + "」は数字にしてね");
                    bad = true;
                }
            }
            if (bad) continue;
            if (part.name().equals("場所") && !PLACES.contains(part.word(0, ""))) {
                problems.add(where + "場所は " + String.join("・", PLACES) + " のどれかにしてね");
                continue;
            }
            if (part.name().equals("向き") && !DIRECTIONS.contains(part.word(0, ""))) {
                problems.add(where + "向きは " + String.join("・", DIRECTIONS) + " のどれかにしてね");
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

    // 「名前=数字」の名前がまちがっているときの しるし
    static final String UNKNOWN_NAME = "\u0000";

    /** 1行を部品にする（「＞」のあとを空白で区切る。「名前=数字」は その名前の場所へ。空なら null） */
    static Part readPart(String line, int lineNumber) {
        String text = line.strip();
        if (text.startsWith("＞") || text.startsWith(">")) text = text.substring(1);
        text = text.replace('　', ' ').replace('＝', '=').strip();
        if (text.isEmpty()) return null;
        String[] pieces = text.split("\\s+");
        String[] names = argNames(pieces[0]);
        List<String> args = new ArrayList<>();
        int next = 0;
        for (int index = 1; index < pieces.length; index++) {
            String piece = pieces[index];
            int equal = piece.indexOf('=');
            if (equal > 0 && names != null) {
                // 名前=数字
                int slot = Arrays.asList(names).indexOf(piece.substring(0, equal));
                if (slot < 0) {
                    args.add(UNKNOWN_NAME);
                    continue;
                }
                while (args.size() <= slot) args.add(null);
                args.set(slot, piece.substring(equal + 1));
            } else {
                // 順番どおり：まだ うまっていない いちばん前へ
                while (next < args.size() && args.get(next) != null) next++;
                while (args.size() <= next) args.add(null);
                args.set(next, piece);
                next++;
            }
        }
        // うしろの空きは けずる
        while (!args.isEmpty() && args.get(args.size() - 1) == null) args.remove(args.size() - 1);
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
