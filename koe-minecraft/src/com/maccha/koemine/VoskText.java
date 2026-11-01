package com.maccha.koemine;

import com.sun.jna.Function;
import com.sun.jna.Library;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import org.vosk.LibVosk;
import org.vosk.Recognizer;

import java.util.Map;

/**
 * Vosk の結果を UTF-8 で読む（Windows では そのままだと日本語が文字化けするため）
 */
public class VoskText {
    static Function resultFunction;
    static Function partialFunction;
    static Function grammarFunction;

    /** libvosk の中の関数を見つけておく */
    static void prepare() {
        if (resultFunction != null) return;
        // LibVosk を先に読みこんで、libvosk を用意してもらう
        LibVosk.setLogLevel(org.vosk.LogLevel.WARNINGS);
        // Windows では libvosk.dll、Mac・Linux では libvosk.so などという名前
        String name = Platform.isWindows() ? "libvosk" : "vosk";
        NativeLibrary library = NativeLibrary.getInstance(name, Map.of(Library.OPTION_CLASSLOADER, LibVosk.class.getClassLoader()));
        resultFunction = library.getFunction("vosk_recognizer_result");
        partialFunction = library.getFunction("vosk_recognizer_partial_result");
        grammarFunction = library.getFunction("vosk_recognizer_set_grm");
    }

    /** 話し終わった文の JSON を UTF-8 で読む */
    static String result(Recognizer recognizer) {
        if (resultFunction == null) return recognizer.getResult();
        return call(resultFunction, recognizer);
    }

    /** 話している途中の文の JSON を UTF-8 で読む */
    static String partial(Recognizer recognizer) {
        if (partialFunction == null) return recognizer.getPartialResult();
        return call(partialFunction, recognizer);
    }

    /** 聞く言葉の一覧（JSON）を UTF-8 でわたす（そのままだと日本語がこわれるため） */
    static void setGrammar(Recognizer recognizer, String json) {
        byte[] text = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] withEnd = java.util.Arrays.copyOf(text, text.length + 1);
        grammarFunction.invoke(Void.class, new Object[]{recognizer.getPointer(), withEnd});
    }

    /** 関数を呼んで、返ってきた文字を UTF-8 として読む */
    static String call(Function function, Recognizer recognizer) {
        Pointer text = (Pointer) function.invoke(Pointer.class, new Object[]{recognizer.getPointer()});
        return text == null ? "{}" : text.getString(0, "UTF-8");
    }
}
