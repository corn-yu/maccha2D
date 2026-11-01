package com.maccha.koemine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 言葉とアクションの対応表と設定（config/koemine/ことば.txt から読む）
 */
public class CommandTable {

    /** 言葉ひとつ（くらべる形・書いたままの形）と、そのアクションの組 */
    public record Entry(String word, String raw, String action) {}

    /** 文の中で見つかった言葉と、その場所 */
    public record Match(Entry entry, int start, int end) {}

    // ことば表の版。これが入っていない古い ことば.txt は、ことば-old.txt にして新しくする
    static final String VERSION_MARK = "ことば表 v4";

    // はじめて動かしたときに作る ことば.txt の中身
    static final String DEFAULT_TEXT = """
            # こえマイクラ ことば表 v4
            # 「言葉 = アクション」の形で1行にひとつ書きます。ひらがな・漢字・カタカナどれでもOK。
            # 同じアクションに、言葉をいくつ書いてもいいです。# から後ろはメモです。
            # アクションは + でつなぐと、いっしょにします（例: 下を壊して = look_bottom+dig）
            # 「〇〇つくって」「〇〇を持って」「〇〇を見て」「〇〇を壊して」の〇〇は、アイテム・ブロック・モブの名前ならなんでもOK。
            # * ではじまる行は あだ名です（*木 = log なら、「木を見て」で名前に log がつくブロックをさがす）。
            # 変えたら、ゲームの中で「ことばよみこみ」と言うか、マイクラを起動しなおしてください。

            # 設定（! ではじまる行）
            # ことばだけ聞く = はい … この表の言葉だけを聞く（速くて、まちがいが少ない）。いいえ にすると何でも聞きとる
            !ことばだけ聞く = はい
            # まつ時間 … 「右」のあとに「によけて」とつづくかを待つ時間（ミリ秒）。短いほど速い
            !まつ時間 = 250

            # 歩く
            まえ = forward
            前 = forward
            すすめ = forward
            進め = forward
            進んで = forward
            あるいて = walk
            歩いて = walk
            いっぽまえ = step_forward
            一歩前 = step_forward
            ちょっとまえ = short_forward
            ちょっと前 = short_forward
            さがって = back
            下がって = back
            うしろにさがって = back
            後ろに下がって = back
            バック = back
            うしろ = back
            後ろ = back
            うしろへ = back
            後ろへ = back
            うしろにすすんで = back
            後ろに進んで = back
            後ろ歩き = back
            いっぽうしろ = step_back
            一歩後ろ = step_back
            ひだりによけて = strafe_left
            左によけて = strafe_left
            左へよけて = strafe_left
            ひだりへよけて = strafe_left
            みぎによけて = strafe_right
            右によけて = strafe_right
            右へよけて = strafe_right
            みぎへよけて = strafe_right
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
            待って = stop
            ぜんぶやめて = stop_all
            全部やめて = stop_all
            おしまい = stop_all

            # ジャンプ・しゃがむ・泳ぐ
            ジャンプ = jump
            とんで = jump
            跳んで = jump
            飛んで = jump
            れんぞくジャンプ = jump_repeat
            連続ジャンプ = jump_repeat
            およいで = jump_repeat
            泳いで = jump_repeat
            のぼって = jump_repeat
            登って = jump_repeat
            オートジャンプ = auto_jump
            しゃがんで = sneak
            しゃがめ = sneak
            たって = stand
            立って = stand
            おりて = dismount
            降りて = dismount
            のって = use
            乗って = use

            # むき
            みぎ = turn_right
            右 = turn_right
            みぎむいて = turn_right
            右向いて = turn_right
            ひだり = turn_left
            左 = turn_left
            ひだりむいて = turn_left
            左向いて = turn_left
            ちょっとみぎ = turn_right_small
            ちょっと右 = turn_right_small
            ちょっとひだり = turn_left_small
            ちょっと左 = turn_left_small
            うしろむいて = turn_around
            後ろ向いて = turn_around
            回れ右 = turn_around
            うえ = look_up
            上 = look_up
            うえむいて = look_up
            上向いて = look_up
            上見て = look_up
            うえをみて = look_up
            上を見て = look_up
            もっと上 = look_up
            ちょっと上 = look_up_small
            した = look_down
            下 = look_down
            したむいて = look_down
            下向いて = look_down
            下見て = look_down
            したをみて = look_down
            下を見て = look_down
            もっと下 = look_down
            ちょっと下 = look_down_small
            まっすぐ = look_front
            正面 = look_front
            真下 = look_bottom
            真上 = look_top
            てきをみて = aim_enemy
            敵を見て = aim_enemy
            ねらって = aim_enemy
            狙って = aim_enemy
            どうぶつをみて = aim_animal
            動物を見て = aim_animal
            視点 = perspective
            カメラ = perspective

            # こわす・こうげき
            こわして = dig
            壊して = dig
            下を壊して = look_bottom+dig
            したをこわして = look_bottom+dig
            トンネル = look_front+dig+forward
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
            たべて = item:food+eat
            食べて = item:food+eat
            のんで = eat
            飲んで = eat
            ひいて = hold_use
            引いて = hold_use
            うって = release_use
            撃って = release_use
            はなして = release_use
            放して = release_use
            まもって = hold_use
            守って = hold_use
            持ちかえ = swap_hand
            持ち替え = swap_hand

            # もちもの（名前でえらぶ）
            けん = item:sword
            剣 = item:sword
            つるはし = item:pickaxe
            ツルハシ = item:pickaxe
            おの = item:axe
            斧 = item:axe
            シャベル = item:shovel
            スコップ = item:shovel
            クワ = item:hoe
            ゆみ = item:bow
            弓 = item:bow
            クロスボウ = item:crossbow
            たいまつ = item:torch
            松明 = item:torch
            ブロック = item:block
            バケツ = item:bucket
            たべもの = item:food
            食べ物 = item:food
            インベントリ = inventory
            もちもの = inventory
            持ち物 = inventory
            とじて = close
            閉じて = close
            とじる = close
            閉じる = close
            クローズ = close
            すてて = drop
            捨てて = drop
            いちばん = slot1
            一番 = slot1
            にばん = slot2
            二番 = slot2
            さんばん = slot3
            三番 = slot3
            よんばん = slot4
            四番 = slot4
            ごばん = slot5
            五番 = slot5
            ろくばん = slot6
            六番 = slot6
            ななばん = slot7
            七番 = slot7
            はちばん = slot8
            八番 = slot8
            きゅうばん = slot9
            九番 = slot9
            つぎ = slot_next
            次 = slot_next

            # 名前で言う（〇〇はアイテム・ブロック・モブの名前）
            つくって = name:craft
            作って = name:craft
            クラフト = name:craft
            やいて = name:smelt
            焼いて = name:smelt
            をもって = name:hold
            を持って = name:hold
            もって = name:hold
            持って = name:hold
            をだして = name:hold
            を出して = name:hold
            をみて = name:look
            を見て = name:look
            をさがして = name:look
            を探して = name:look
            さがして = name:look
            探して = name:look
            をこわして = name:mine
            を壊して = name:mine

            # あだ名（* ではじまる行。右はブロック・アイテム・モブの英語の名前の一部）
            *木 = _log
            *丸太 = _log
            *原木 = _log
            *いし = stone
            *石 = stone
            *土 = dirt
            *砂 = sand
            *葉っぱ = leaves
            *石炭 = coal_ore
            *鉄 = iron_ore
            *金 = gold_ore
            *ダイヤ = diamond_ore
            *ダイヤモンド = diamond_ore
            *レッドストーン = redstone_ore
            *水 = water
            *マグマ = lava
            *溶岩 = lava
            *敵 = @enemy
            *モンスター = @enemy
            *動物 = @animal
            *ひつじ = sheep
            *うし = cow
            *ぶた = pig
            *にわとり = chicken

            # 作業台・チェスト・かまど
            ぜんぶいれて = put_all
            全部入れて = put_all
            ぜんぶしまって = put_all
            全部しまって = put_all
            ぜんぶとって = take_all
            全部取って = take_all
            ぜんぶだして = take_all
            全部出して = take_all
            できたのとって = take_result
            出来たの取って = take_result

            # そのほか
            あしもとにおいて = pillar
            足元に置いて = pillar
            うえにのぼって = pillar
            上に登って = pillar
            つみあげて = pillar
            積み上げて = pillar
            リスポーン = respawn
            いきかえって = respawn
            生き返って = respawn
            ポーズ = pause
            メニュー = pause
            ねて = use
            寝て = use

            # マイク（友だちとしゃべるときは「マイクオフ」）
            マイクオフ = mute
            きかないで = mute
            聞かないで = mute
            マイクオン = unmute
            ことばよみこみ = reload
            言葉読み込み = reload
            """;

    final List<Entry> entries = new ArrayList<>();
    final Map<String, String> settings = new HashMap<>();
    // あだ名（「木」→ ブロックの名前に log がつくもの など）。キーは書いたままの形
    final Map<String, String> nicknames = new LinkedHashMap<>();

    /** ことば.txt を読む（なければ、または古ければ 最初の中身で作る） */
    public void load(Path file) {
        try {
            // 古い版なら ことば-old.txt にとっておいて、新しく作る
            if (Files.exists(file) && !Files.readString(file, StandardCharsets.UTF_8).contains(VERSION_MARK)) {
                Files.move(file, file.resolveSibling("ことば-old.txt"), StandardCopyOption.REPLACE_EXISTING);
            }
            // ファイルがなければ作る
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

    /** 文字列から「言葉 = アクション」と設定を全部とりだす */
    public void parse(String text) {
        entries.clear();
        settings.clear();
        nicknames.clear();
        for (String line : text.split("\n")) {
            // # から後ろはメモなので消す
            int sharp = line.indexOf('#');
            if (sharp >= 0) line = line.substring(0, sharp);
            int equal = line.indexOf('=');
            if (equal < 0) continue;
            String left = line.substring(0, equal).trim();
            String right = line.substring(equal + 1).trim();
            if (left.isEmpty() || right.isEmpty()) continue;
            // ! ではじまる行は設定
            if (left.startsWith("!")) {
                settings.put(left.substring(1).trim(), right);
            } else if (left.startsWith("*")) {
                // * ではじまる行は あだ名
                nicknames.put(left.substring(1).trim(), right);
            } else {
                entries.add(new Entry(normalize(left), left.replace(" ", "").replace("　", ""), right));
            }
        }
    }

    /** 設定が「はい」かどうか */
    public boolean isOn(String name) {
        String value = settings.getOrDefault(name, "はい");
        return value.equals("はい") || value.equalsIgnoreCase("on") || value.equalsIgnoreCase("true");
    }

    /** 数の設定を読む（書いていなければ fallback） */
    public int number(String name, int fallback) {
        try {
            return Integer.parseInt(settings.getOrDefault(name, "" + fallback).trim());
        } catch (NumberFormatException error) {
            return fallback;
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

    /** Vosk に「この言葉だけ聞いて」と渡す JSON を作る（モデルの単語に区切れない言葉はのぞく） */
    public String grammarJson(Set<String> vocabulary, List<String> extraWords, List<String> skipped) {
        // ことば表の言葉と、あだ名と、ゲームの名前を全部あつめる
        List<String> words = new ArrayList<>();
        for (Entry entry : entries) words.add(entry.raw());
        words.addAll(nicknames.keySet());
        words.addAll(extraWords);
        Set<String> phrases = new LinkedHashSet<>();
        for (String word : words) {
            String phrase = splitWords(word, vocabulary);
            if (phrase == null) {
                skipped.add(word);
            } else {
                phrases.add(phrase);
            }
        }
        // 表にないことばは [unk]（わからない）になるようにする
        StringBuilder json = new StringBuilder("[");
        for (String phrase : phrases) {
            json.append('"').append(phrase.replace("\"", "")).append("\",");
        }
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
