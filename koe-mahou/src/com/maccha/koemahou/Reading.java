package com.maccha.koemahou;

import com.atilika.kuromoji.ipadic.Token;
import com.atilika.kuromoji.ipadic.Tokenizer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 漢字の「読みがな」を出す係（Kuromoji の辞書を使う）
 * 誤変換（闇→病み、飲み込め→呑み込め）でも 読みがなが同じなら 同じ詠唱だとわかるようにする
 */
public class Reading {

    // 辞書（読みこみに1秒くらいかかるので、別スレッドで用意する）
    static volatile Tokenizer tokenizer = null;
    // 詠唱の行の読みがなは何回も使うので とっておく
    static final Map<String, String> cache = new ConcurrentHashMap<>();

    /** 辞書を別スレッドで読みこみはじめる */
    public static void prepare() {
        Thread thread = new Thread(() -> {
            try {
                tokenizer = new Tokenizer();
            } catch (Throwable problem) {
                KoeMahouMod.LOG.warn("こえまほう: 読みがなの辞書が読めませんでした", problem);
            }
        }, "koemahou-reading");
        thread.setDaemon(true);
        thread.start();
    }

    /** 文の読みがな（ひらがな）。辞書がまだなら 文字をそろえただけのもの */
    public static String of(String text) {
        Tokenizer current = tokenizer;
        if (current == null) return SpellBook.normalize(text);
        return cache.computeIfAbsent(text, key -> {
            StringBuilder result = new StringBuilder();
            for (Token token : current.tokenize(key)) {
                String reading = token.getReading();
                // 辞書にない言葉（カタカナ語など）は そのまま
                result.append(reading == null || reading.equals("*") ? token.getSurface() : reading);
            }
            return SpellBook.normalize(result.toString());
        });
    }
}
