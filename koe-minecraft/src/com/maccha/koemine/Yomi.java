package com.maccha.koemine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 漢字の読み辞書：「老いて」も「おいて」と読めるようにして、誤変換でも言葉が見つかるようにする
 * （辞書は JMdict・KANJIDIC2 から作った resources/koemine/yomi.txt.gz）
 */
public class Yomi {
    // 2文字以上の言葉 → 読み（例: 作業台 → さぎょうだい）
    final Map<String, String[]> words = new HashMap<>();
    // 漢字1文字 → 訓読み（「お.く」の形）
    final Map<Character, List<String>> kunReadings = new HashMap<>();
    // 漢字1文字 → 音読み
    final Map<Character, List<String>> onReadings = new HashMap<>();
    int longestWord = 1;
    boolean loaded = false;

    static Yomi instance;

    /** 辞書を1回だけ読んで返す（読めなければ からっぽ） */
    public static synchronized Yomi get() {
        if (instance == null) {
            instance = new Yomi();
            instance.load();
        }
        return instance;
    }

    /** jar の中の yomi.txt.gz を読む */
    void load() {
        try (InputStream raw = Yomi.class.getResourceAsStream("/koemine/yomi.txt.gz")) {
            if (raw == null) return;
            BufferedReader reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#")) continue;
                int tab = line.indexOf('\t');
                if (tab < 0) continue;
                String key = line.substring(0, tab);
                String[] readings = line.substring(tab + 1).split(",");
                if (key.startsWith("@")) {
                    // 漢字1文字：k は訓読み、o は音読み
                    char kanji = key.charAt(1);
                    for (String reading : readings) {
                        if (reading.length() < 2) continue;
                        Map<Character, List<String>> target = reading.charAt(0) == 'k' ? kunReadings : onReadings;
                        target.computeIfAbsent(kanji, k -> new ArrayList<>()).add(reading.substring(1));
                    }
                } else {
                    words.put(key, readings);
                    longestWord = Math.max(longestWord, key.length());
                }
            }
            loaded = true;
        } catch (IOException error) {
            KoeMineMod.LOG.warn("こえマイクラ: 読み辞書が読めませんでした", error);
        }
    }

    /** 漢字かどうか（々 もふくむ） */
    static boolean isKanji(char letter) {
        return (letter >= '一' && letter <= '鿿') || letter == '々' || letter == '〆';
    }

    /** ひらがなかどうか */
    static boolean isHiragana(char letter) {
        return (letter >= 'ぁ' && letter <= 'ゖ') || letter == 'ー';
    }

    /** ことば表の言葉の「いちばんありそうな読み」をひとつ作る（例: 置いて → おいて、作業台 → さぎょうだい） */
    public String reading(String word) {
        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < word.length()) {
            char letter = word.charAt(i);
            if (!isKanji(letter)) {
                result.append(letter);
                i++;
                continue;
            }
            // 2文字以上の言葉として辞書にあれば、それを使う（長いほうから）
            int found = 0;
            for (int length = Math.min(longestWord, word.length() - i); length >= 2; length--) {
                String[] readings = words.get(word.substring(i, i + length));
                if (readings != null) {
                    result.append(readings[0]);
                    found = length;
                    break;
                }
            }
            if (found > 0) {
                i += found;
                continue;
            }
            // 漢字1文字：うしろがひらがな（送りがな）か 1文字だけなら訓読み、漢字がつづくなら音読み
            boolean nextIsKanji = i + 1 < word.length() && isKanji(word.charAt(i + 1));
            boolean prevIsKanji = i > 0 && isKanji(word.charAt(i - 1));
            List<String> kun = kunReadings.getOrDefault(letter, List.of());
            List<String> on = onReadings.getOrDefault(letter, List.of());
            String pick = null;
            if (!nextIsKanji && !prevIsKanji && !kun.isEmpty()) pick = stem(kun.getFirst());
            else if (!on.isEmpty()) pick = on.getFirst();
            else if (!kun.isEmpty()) pick = stem(kun.getFirst());
            result.append(pick == null ? String.valueOf(letter) : pick);
            i++;
        }
        return result.toString();
    }

    /** 「お.く」の送りがなをとった部分（お） */
    static String stem(String kun) {
        int dot = kun.indexOf('.');
        return dot < 0 ? kun : kun.substring(0, dot);
    }

    /**
     * 文 text の start から、読みが target と同じになる読み方があるかしらべて、
     * あれば何文字目までで読めたか（いちばん長いところ）を返す。なければ -1
     */
    public int matchAt(String text, int start, String target) {
        return match(text, start, target, 0, 0);
    }

    /** matchAt の中身：text の i 文字目から、target の j 文字目からを合わせていく */
    int match(String text, int i, String target, int j, int depth) {
        if (j == target.length()) return i;
        if (i >= text.length() || depth > 40) return -1;
        char letter = text.charAt(i);
        int best = -1;
        if (!isKanji(letter)) {
            // かなはそのまま同じか
            if (letter == target.charAt(j)) best = Math.max(best, match(text, i + 1, target, j + 1, depth + 1));
            return best;
        }
        // 2文字以上の言葉の読み
        for (int length = Math.min(longestWord, text.length() - i); length >= 2; length--) {
            String[] readings = words.get(text.substring(i, i + length));
            if (readings == null) continue;
            for (String reading : readings) {
                if (target.startsWith(reading, j)) best = Math.max(best, match(text, i + length, target, j + reading.length(), depth + 1));
            }
        }
        // 漢字1文字の読み（音読み・訓読み・訓読みの送りがななし・送りがなつき）
        char kanji = letter == '々' && i > 0 ? text.charAt(i - 1) : letter;
        for (String reading : singleReadings(kanji)) {
            if (target.startsWith(reading, j)) best = Math.max(best, match(text, i + 1, target, j + reading.length(), depth + 1));
        }
        return best;
    }

    /** 漢字1文字のありえる読みを全部 */
    List<String> singleReadings(char kanji) {
        List<String> result = new ArrayList<>(onReadings.getOrDefault(kanji, List.of()));
        for (String kun : kunReadings.getOrDefault(kanji, List.of())) {
            result.add(stem(kun));
            if (kun.contains(".")) result.add(kun.replace(".", ""));
        }
        return result;
    }

    /** 2つの読みが何文字ちがうか（編集距離） */
    public static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int change = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(change, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
