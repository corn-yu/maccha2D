package com.maccha.koemine;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * マイクの音を聞いて文字にし、言葉が見つかったらアクションを順番待ちに入れる（別スレッドで動く）
 */
public class VoiceListener implements Runnable {

    // 日本語の小さいモデル（約48MB）。パソコンの中だけで文字にする
    static final String MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip";
    static final float SAMPLE_RATE = 16000f;

    /** 順番待ちに入れる1件（聞こえた文と、するアクション） */
    public record Heard(String text, String action, boolean isFinal) {}

    final Path folder;
    final ConcurrentLinkedQueue<Heard> queue;
    volatile CommandTable table;
    volatile boolean running = true;
    volatile String status = "じゅんびちゅう…";

    // 今の話のなかで、どこまでアクションにしたか
    int firedUntil = 0;
    // 今の話のなかで、アクションをしたか
    boolean firedAny = false;

    /** 作る（folder は config/koemine） */
    public VoiceListener(Path folder, CommandTable table, ConcurrentLinkedQueue<Heard> queue) {
        this.folder = folder;
        this.table = table;
        this.queue = queue;
    }

    /** スレッドの本体：モデルを用意して、マイクを聞きつづける */
    @Override
    public void run() {
        try {
            // モデルを用意する（なければダウンロード）
            Path modelPath = prepareModel();
            status = "モデルを読みこみ中…";
            LibVosk.setLogLevel(LogLevel.WARNINGS);
            // 日本語を UTF-8 で読むじゅんび（だめなら ふつうの読み方にもどる）
            try {
                VoskText.prepare();
            } catch (Throwable error) {
                KoeMineMod.LOG.warn("こえマイクラ: UTF-8 で読めないので、ふつうに読みます: {}", error.toString());
            }
            try (Model model = new Model(modelPath.toString());
                 Recognizer recognizer = new Recognizer(model, SAMPLE_RATE)) {
                listen(recognizer);
            }
        } catch (Throwable error) {
            status = "うごかせませんでした: " + error.getMessage();
            KoeMineMod.LOG.error("こえマイクラ: 音声認識が止まりました", error);
            queue.add(new Heard("", "error:" + status, true));
        }
    }

    /** マイクを開いて、音を Vosk にわたしつづける */
    void listen(Recognizer recognizer) throws Exception {
        // 16kHz・16bit・モノラルでマイクを開く
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        TargetDataLine microphone = AudioSystem.getTargetDataLine(format);
        microphone.open(format, 8192);
        microphone.start();
        status = "聞いています";
        queue.add(new Heard("", "ready", true));
        KoeMineMod.LOG.info("こえマイクラ: マイクを開きました ({})", microphone.getLineInfo());

        // 0.1秒ぶんずつ読んで、文字にする
        byte[] buffer = new byte[3200];
        while (running) {
            int count = microphone.read(buffer, 0, buffer.length);
            if (count <= 0) continue;
            if (recognizer.acceptWaveForm(buffer, count)) {
                onText(readField(VoskText.result(recognizer), "text"), true);
            } else {
                onText(readField(VoskText.partial(recognizer), "partial"), false);
            }
        }
        microphone.stop();
        microphone.close();
    }

    /** Vosk の JSON から1つの文字列をとりだす */
    static String readField(String json, String key) {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        return object.has(key) ? object.get(key).getAsString() : "";
    }

    /** 文字になった文から言葉をさがして、アクションを順番待ちに入れる */
    void onText(String rawText, boolean isFinal) {
        String text = CommandTable.normalize(rawText);
        // 途中の文が前より短くなったら（言いなおし中）、待つ
        if (text.length() < firedUntil && !isFinal) return;
        int from = Math.min(firedUntil, text.length());

        // 見つかった言葉を順番にアクションにする
        CommandTable.Match match = table.findFirst(text, from);
        while (match != null) {
            // 話している途中で、ここから後ろが もっと長い言葉の始まりかもしれないなら待つ（「みぎ」と「みぎによけて」など）
            if (!isFinal && table.couldGrow(text.substring(match.start()))) break;
            queue.add(new Heard(rawText, match.entry().action(), isFinal));
            firedUntil = match.end();
            firedAny = true;
            match = table.findFirst(text, firedUntil);
        }

        // 話し終わったら、次の話のために最初にもどす
        if (isFinal) {
            if (!text.isEmpty() && !firedAny) queue.add(new Heard(rawText, "heard", true));
            firedUntil = 0;
            firedAny = false;
        }
    }

    /** モデルのフォルダを返す（なければダウンロードして展開する） */
    Path prepareModel() throws IOException, InterruptedException {
        Path modelPath = folder.resolve("model");
        if (Files.isDirectory(modelPath)) return modelPath;
        Files.createDirectories(folder);

        // ダウンロードする
        status = "日本語モデルをダウンロード中…（はじめの1回だけ・約48MB）";
        queue.add(new Heard("", "status", true));
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
