package com.maccha.koemine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 言葉とアクションの対応表（config/koemine/ことば.txt から読む）
 */
public class CommandTable {

    /** 言葉ひとつと、そのアクションの組 */
    public record Entry(String word, String action) {}

    /** 文の中で見つかった言葉と、その場所 */
    public record Match(Entry entry, int start, int end) {}

    // はじめて動かしたときに作る ことば.txt の中身
    static final String DEFAULT_TEXT = """
            # こえマイクラ ことば表
            # 「言葉 = アクション」の形で1行にひとつ書きます。ひらがな・漢字・カタカナどれでもOK。
            # 同じアクションに、言葉をいくつ書いてもいいです。# から後ろはメモです。
            # 変えたら、ゲームの中で「ことばよみこみ」と言うか、マイクラを起動しなおしてください。

            # 歩く
            まえ = forward
            前 = forward
            すすめ = forward
            進め = forward
            進んで = forward
            すすんで = forward
            あるいて = walk
            歩いて = walk
            うしろにさがって = back
            さがって = back
            下がって = back
            バック = back
            ひだりによけて = strafe_left
            左によけて = strafe_left
            みぎによけて = strafe_right
            右によけて = strafe_right
            はしれ = sprint
            走れ = sprint
            はしって = sprint
            走って = sprint
            ダッシュ = sprint
            とまれ = stop
            止まれ = stop
            とまって = stop
            止まって = stop
            ストップ = stop
            まって = stop
            待って = stop
            ぜんぶやめて = stop_all
            全部やめて = stop_all
            おしまい = stop_all

            # ジャンプ・しゃがむ
            ジャンプ = jump
            とんで = jump
            跳んで = jump
            飛んで = jump
            れんぞくジャンプ = jump_repeat
            連続ジャンプ = jump_repeat
            しゃがんで = sneak
            しゃがめ = sneak
            たって = stand
            立って = stand

            # むき
            みぎ = turn_right
            右 = turn_right
            ひだり = turn_left
            左 = turn_left
            ちょっとみぎ = turn_right_small
            ちょっと右 = turn_right_small
            ちょっとひだり = turn_left_small
            ちょっと左 = turn_left_small
            うしろむいて = turn_around
            後ろ向いて = turn_around
            まわれみぎ = turn_around
            回れ右 = turn_around
            うえむいて = look_up
            上向いて = look_up
            うえみて = look_up
            上見て = look_up
            したむいて = look_down
            下向いて = look_down
            したみて = look_down
            下見て = look_down
            まっすぐ = look_front
            正面 = look_front

            # こわす・こうげき
            ほって = dig
            掘って = dig
            ほれ = dig
            掘れ = dig
            こわして = dig
            壊して = dig
            こうげき = attack
            攻撃 = attack
            なぐって = attack
            殴って = attack
            たたいて = attack
            パンチ = attack
            れんだ = attack_repeat
            連打 = attack_repeat

            # つかう・おく
            おいて = use
            置いて = use
            つかって = use
            使って = use
            あけて = use
            開けて = use
            たべて = eat
            食べて = eat
            のんで = eat
            飲んで = eat
            ひいて = hold_use
            引いて = hold_use
            うって = release_use
            撃って = release_use
            はなして = release_use
            放して = release_use

            # もちもの
            インベントリ = inventory
            もちもの = inventory
            持ち物 = inventory
            とじて = close
            閉じて = close
            すてて = drop
            捨てて = drop
            いちばん = slot1
            一番 = slot1
            1番 = slot1
            にばん = slot2
            二番 = slot2
            2番 = slot2
            さんばん = slot3
            三番 = slot3
            3番 = slot3
            よんばん = slot4
            四番 = slot4
            4番 = slot4
            ごばん = slot5
            五番 = slot5
            5番 = slot5
            ろくばん = slot6
            六番 = slot6
            6番 = slot6
            ななばん = slot7
            七番 = slot7
            7番 = slot7
            はちばん = slot8
            八番 = slot8
            8番 = slot8
            きゅうばん = slot9
            九番 = slot9
            9番 = slot9
            つぎ = slot_next
            次 = slot_next
            まえのどうぐ = slot_prev

            # マイク（友だちとしゃべるときは「きかないで」）
            きかないで = mute
            聞かないで = mute
            きいて = unmute
            聞いて = unmute
            ことばよみこみ = reload
            言葉読み込み = reload
            """;

    final List<Entry> entries = new ArrayList<>();

    /** ことば.txt を読む（なければ最初の中身で作る） */
    public void load(Path file) {
        // ファイルがなければ作る
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, DEFAULT_TEXT, StandardCharsets.UTF_8);
            }
            parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException error) {
            KoeMineMod.LOG.warn("ことば.txt が読めなかったので、最初の言葉を使います: {}", error.toString());
            parse(DEFAULT_TEXT);
        }
    }

    /** 文字列から「言葉 = アクション」を全部とりだす */
    public void parse(String text) {
        entries.clear();
        for (String line : text.split("\n")) {
            // # から後ろはメモなので消す
            int sharp = line.indexOf('#');
            if (sharp >= 0) line = line.substring(0, sharp);
            int equal = line.indexOf('=');
            if (equal < 0) continue;
            String word = normalize(line.substring(0, equal));
            String action = line.substring(equal + 1).trim();
            if (!word.isEmpty() && !action.isEmpty()) entries.add(new Entry(word, action));
        }
    }

    /** くらべやすいように、空白を消して カタカナをひらがなにする */
    public static String normalize(String text) {
        StringBuilder result = new StringBuilder();
        for (char letter : text.toCharArray()) {
            // 空白はとばす
            if (Character.isWhitespace(letter) || letter == '　') continue;
            // カタカナ（ァ〜ヶ）はひらがなにする（長音ーはそのまま）
            if (letter >= 'ァ' && letter <= 'ヶ') letter = (char) (letter - 0x60);
            // 全角数字は半角にする
            if (letter >= '０' && letter <= '９') letter = (char) (letter - '０' + '0');
            result.append(letter);
        }
        return result.toString();
    }

    /** from より後ろで、いちばん前にある言葉をさがす（同じ場所なら長い言葉を選ぶ） */
    public Match findFirst(String text, int from) {
        Match best = null;
        for (Entry entry : entries) {
            int start = text.indexOf(entry.word(), from);
            if (start < 0) continue;
            // もっと前にあるか、同じ場所でもっと長いなら こちらにする
            boolean earlier = best == null || start < best.start();
            boolean longer = best != null && start == best.start() && entry.word().length() > best.entry().word().length();
            if (earlier || longer) best = new Match(entry, start, start + entry.word().length());
        }
        return best;
    }

    /** この文字のつづきを言えば、もっと長い言葉になるか（「みぎに」→「みぎによけて」など） */
    public boolean couldGrow(String tail) {
        for (Entry entry : entries) {
            if (entry.word().length() > tail.length() && entry.word().startsWith(tail)) return true;
        }
        return false;
    }

    /** 文の中の言葉を順番に全部とりだす（話し終わったとき用） */
    public List<Match> findAll(String text, int from) {
        List<Match> matches = new ArrayList<>();
        Match match = findFirst(text, from);
        while (match != null) {
            matches.add(match);
            match = findFirst(text, match.end());
        }
        return matches;
    }
}
