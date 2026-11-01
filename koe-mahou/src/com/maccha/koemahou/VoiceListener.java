package com.maccha.koemahou;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.TargetDataLine;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * マイクの音を聞いて文字にし、詠唱の行が聞こえたら順番待ちに入れる（別スレッドで動く）
 */
public class VoiceListener implements Runnable {

    // 日本語の小さいモデル（約48MB）。パソコンの中だけで文字にする
    static final String MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip";
    static final float SAMPLE_RATE = 16000f;

    /** 順番待ちに入れる1件（聞こえた文と、見つかった行。行がないときは line が null） */
    public record Heard(String text, SpellBook.Match line, String event) {}

    final Path folder;
    final ConcurrentLinkedQueue<Heard> queue;
    volatile SpellBook book;
    volatile boolean running = true;
    volatile boolean grammarDirty = false;
    volatile String status = "じゅんびちゅう…";
    // モデルが知っている単語
    final Set<String> vocabulary = new HashSet<>();

    // 今の話のなかで、どこまで行を見つけたか
    int foundUntil = 0;

    /** 作る（folder は config/koemahou） */
    public VoiceListener(Path folder, SpellBook book, ConcurrentLinkedQueue<Heard> queue) {
        this.folder = folder;
        this.book = book;
        this.queue = queue;
    }

    /** じゅもん表が変わったので、文法を作りなおしてもらう */
    public void setBook(SpellBook newBook) {
        book = newBook;
        grammarDirty = true;
    }

    /** スレッドの本体：モデルを用意して、マイクを聞きつづける */
    @Override
    public void run() {
        try {
            // モデルを用意する（こえマイクラのモデルがあれば それを使う。なければダウンロード）
            Path modelPath = prepareModel();
            status = "モデルを読みこみ中…";
            LibVosk.setLogLevel(LogLevel.WARNINGS);
            // 日本語を UTF-8 で読むじゅんび（Windows の文字化け対策）
            VoskText.prepare();
            loadVocabulary(modelPath.resolve("graph").resolve("words.txt"));
            try (Model model = new Model(modelPath.toString());
                 Recognizer recognizer = new Recognizer(model, SAMPLE_RATE)) {
                setGrammar(recognizer);
                listen(recognizer);
            }
        } catch (Throwable error) {
            status = "うごかせませんでした: " + error.getMessage();
            KoeMahouMod.LOG.error("こえまほう: 音声認識が止まりました", error);
            queue.add(new Heard("", null, "error"));
        }
    }

    /** モデルが知っている単語の一覧を読む */
    void loadVocabulary(Path wordsFile) {
        try (Stream<String> lines = Files.lines(wordsFile, StandardCharsets.UTF_8)) {
            lines.forEach(line -> {
                int space = line.indexOf(' ');
                if (space > 0) vocabulary.add(line.substring(0, space));
            });
        } catch (IOException error) {
            KoeMahouMod.LOG.warn("こえまほう: 単語の一覧が読めません: {}", error.toString());
        }
    }

    /** 詠唱の行だけを聞くように、文法を入れる（ほかの話は [unk] になる） */
    void setGrammar(Recognizer recognizer) {
        grammarDirty = false;
        String json = book.grammarJson(vocabulary);
        VoskText.setGrammar(recognizer, json);
        KoeMahouMod.LOG.info("こえまほう: 文法 {}", json);
        if (!book.problems.isEmpty()) queue.add(new Heard(String.join("、", book.problems), null, "problems"));
    }

    /** マイクを開いて、音を Vosk にわたしつづける */
    void listen(Recognizer recognizer) throws Exception {
        // 16kHz・16bit・モノラルでマイクを開く
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        TargetDataLine microphone = AudioSystem.getTargetDataLine(format);
        microphone.open(format, 4096);
        microphone.start();
        status = "聞いています";
        queue.add(new Heard("", null, "ready"));

        // 0.05秒ぶんずつ読んで、文字にする
        byte[] buffer = new byte[1600];
        while (running) {
            if (grammarDirty) setGrammar(recognizer);
            int count = microphone.read(buffer, 0, buffer.length);
            if (count > 0) process(recognizer, buffer, count);
        }
        microphone.stop();
        microphone.close();
    }

    /** 音を少しわたして、文字になったところまで行をさがす */
    void process(Recognizer recognizer, byte[] buffer, int count) {
        if (recognizer.acceptWaveForm(buffer, count)) {
            onText(readField(VoskText.result(recognizer), "text"), true);
        } else {
            onText(readField(VoskText.partial(recognizer), "partial"), false);
        }
    }

    /** Vosk の JSON から1つの文字列をとりだす */
    static String readField(String json, String key) {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        return object.has(key) ? object.get(key).getAsString() : "";
    }

    /** 文字になった文から詠唱の行をさがして、順番待ちに入れる */
    void onText(String rawText, boolean isFinal) {
        String text = SpellBook.normalize(rawText.replace("[unk]", ""));
        // 途中の文が前より短くなったら（言いなおし中）、待つ
        if (text.length() < foundUntil && !isFinal) return;
        int from = Math.min(foundUntil, text.length());

        // 見つかった行を、順番に全部入れる
        SpellBook.Match match = book.findFirst(text, from);
        while (match != null) {
            queue.add(new Heard(rawText, match, "line"));
            foundUntil = match.end();
            match = book.findFirst(text, foundUntil);
        }

        // 話し終わったら、次の話のために最初にもどす
        if (isFinal) foundUntil = 0;
    }

    /** モデルのフォルダを返す（こえマイクラのがあれば使う。なければダウンロードして展開する） */
    Path prepareModel() throws IOException, InterruptedException {
        Path shared = folder.resolveSibling("koemine").resolve("model");
        if (Files.isDirectory(shared)) return shared;
        Path modelPath = folder.resolve("model");
        if (Files.isDirectory(modelPath)) return modelPath;
        Files.createDirectories(folder);

        // ダウンロードする
        status = "日本語モデルをダウンロード中…（はじめの1回だけ・約48MB）";
        queue.add(new Heard("", null, "status"));
        Path zipPath = folder.resolve("model-download.zip");
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(MODEL_URL)).GET().build();
        HttpResponse<Path> response = http.send(request, HttpResponse.BodyHandlers.ofFile(zipPath));
        if (response.statusCode() != 200) throw new IOException("ダウンロードできませんでした（" + response.statusCode() + "）");

        // 展開して、中のフォルダを model という名前にする
        status = "日本語モデルを展開中…";
        Path unpackPath = folder.resolve("model-unpack");
        unzip(zipPath, unpackPath);
        try (Stream<Path> children = Files.list(unpackPath)) {
            Path inner = children.filter(Files::isDirectory).findFirst().orElse(unpackPath);
            Files.move(inner, modelPath, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.deleteIfExists(zipPath);
        return modelPath;
    }

    /** zip を target フォルダに展開する */
    static void unzip(Path zipPath, Path target) throws IOException {
        try (InputStream file = Files.newInputStream(zipPath); ZipInputStream zip = new ZipInputStream(file)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path out = target.resolve(entry.getName()).normalize();
                // zip の外に書きださないようにする
                if (!out.startsWith(target)) continue;
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zip, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
