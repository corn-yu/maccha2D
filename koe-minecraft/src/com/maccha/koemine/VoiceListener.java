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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    public record Heard(String text, String action, boolean isFinal, boolean fromChat) {
        /** 声で聞こえた1件をつくる */
        public Heard(String text, String action, boolean isFinal) {
            this(text, action, isFinal, false);
        }
    }

    final Path folder;
    final ConcurrentLinkedQueue<Heard> queue;
    volatile CommandTable table;
    volatile boolean running = true;
    volatile String status = "じゅんびちゅう…";
    // アイテム・ブロック・モブの名前（ゲームから。文法に足す）
    volatile List<String> extraPhrases = List.of();
    // 文法を作りなおしてほしいとき true
    volatile boolean grammarDirty = false;
    // モデルが知っている単語
    Set<String> vocabulary = new HashSet<>();

    // 今の話のなかで、どこまでアクションにしたか
    int firedUntil = 0;
    // 「〇〇を持って」の〇〇は、ここから後ろ
    int nameFrom = 0;
    // 今の話のなかで、アクションをしたか
    boolean firedAny = false;
    // さっき「ジャンプ」をした場所（「ジャンプして攻撃」につづいたら、そちらもする）
    int jumpFiredAt = -1;
    // 最後に聞いた途中の文と、それが変わった時刻（待ちすぎないため）
    String lastPartial = "";
    long lastPartialTime = 0;

    /** 作る（folder は config/koemine） */
    public VoiceListener(Path folder, CommandTable table, ConcurrentLinkedQueue<Heard> queue) {
        this.folder = folder;
        this.table = table;
        this.queue = queue;
    }

    /** 言葉表が変わったので、文法を作りなおしてもらう */
    public void setTable(CommandTable newTable) {
        table = newTable;
        grammarDirty = true;
    }

    /** ゲームの名前（アイテムなど）を文法に足してもらう */
    public void setExtraPhrases(List<String> phrases) {
        extraPhrases = phrases;
        grammarDirty = true;
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
            loadVocabulary(modelPath.resolve("graph").resolve("words.txt"));
            try (Model model = new Model(modelPath.toString());
                 Recognizer recognizer = makeRecognizer(model)) {
                listen(recognizer);
            }
        } catch (Throwable error) {
            status = "うごかせませんでした: " + error.getMessage();
            KoeMineMod.LOG.error("こえマイクラ: 音声認識が止まりました", error);
            queue.add(new Heard("", "error:" + status, true));
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
            KoeMineMod.LOG.warn("こえマイクラ: 単語の一覧が読めません: {}", error.toString());
        }
    }

    /** 文法（聞く言葉の一覧）つきの Recognizer を作る */
    Recognizer makeRecognizer(Model model) throws IOException {
        grammarDirty = false;
        Recognizer recognizer = new Recognizer(model, SAMPLE_RATE);
        if (table.isOn("ことばだけ聞く") && !vocabulary.isEmpty() && VoskText.grammarFunction != null) {
            VoskText.setGrammar(recognizer, makeGrammar());
        }
        return recognizer;
    }

    /** ことば表とゲームの名前から、文法の JSON を作る */
    String makeGrammar() {
        List<String> skipped = new ArrayList<>();
        String json = table.grammarJson(vocabulary, extraPhrases, skipped);
        KoeMineMod.LOG.info("こえマイクラ: 聞く言葉 {} 文字ぶん、モデルにない言葉 {} こ {}", json.length(), skipped.size(),
                skipped.size() > 20 ? skipped.subList(0, 20) + "…" : skipped);
        return json;
    }

    /** マイクを開いて、音を Vosk にわたしつづける */
    void listen(Recognizer recognizer) throws Exception {
        // 16kHz・16bit・モノラルでマイクを開く
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        TargetDataLine microphone = AudioSystem.getTargetDataLine(format);
        microphone.open(format, 4096);
        microphone.start();
        status = "聞いています";
        queue.add(new Heard("", "ready", true));
        KoeMineMod.LOG.info("こえマイクラ: マイクを開きました ({})", microphone.getLineInfo());

        // 0.05秒ぶんずつ読んで、文字にする
        byte[] buffer = new byte[1600];
        while (running) {
            // 言葉表やゲームの名前が変わったら、文法を入れかえる
            if (grammarDirty && table.isOn("ことばだけ聞く") && !vocabulary.isEmpty() && VoskText.grammarFunction != null) {
                grammarDirty = false;
                VoskText.setGrammar(recognizer, makeGrammar());
            }
            int count = microphone.read(buffer, 0, buffer.length);
            if (count > 0) process(recognizer, buffer, count, System.currentTimeMillis());
        }
        microphone.stop();
        microphone.close();
    }

    /** 音を少しわたして、文字になったところまで言葉をさがす（now は今の時刻ミリ秒） */
    void process(Recognizer recognizer, byte[] buffer, int count, long now) {
        if (recognizer.acceptWaveForm(buffer, count)) {
            onText(readField(VoskText.result(recognizer), "text"), true, false);
            return;
        }
        String partial = readField(VoskText.partial(recognizer), "partial");
        if (!partial.equals(lastPartial)) {
            lastPartial = partial;
            lastPartialTime = now;
            onText(partial, false, false);
        } else if (!partial.isEmpty() && now - lastPartialTime >= table.number("まつ時間", 250)) {
            // 少し待っても文がのびないなら、待っていた言葉も実行する
            onText(partial, false, true);
        }
    }

    /** Vosk の JSON から1つの文字列をとりだす */
    static String readField(String json, String key) {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        return object.has(key) ? object.get(key).getAsString() : "";
    }

    /** 文字になった文から言葉をさがして、アクションを順番待ちに入れる（force = 待たずに実行） */
    void onText(String rawText, boolean isFinal, boolean force) {
        String text = CommandTable.normalize(rawText.replace("[unk]", ""));
        // 途中の文が前より短くなったら（言いなおし中）、待つ
        if (text.length() < firedUntil && !isFinal) return;
        int from = Math.min(firedUntil, text.length());
        nameFrom = Math.min(nameFrom, text.length());

        // 「ジャンプ」をしたあとに「ジャンプして攻撃」とつづいていたら、攻撃のほうもする
        if (jumpFiredAt >= 0 && jumpFiredAt < text.length()) {
            CommandTable.Match combo = table.findFirst(text, jumpFiredAt);
            boolean isCombo = combo != null && combo.start() == jumpFiredAt && combo.entry().action().startsWith("jump_")
                    && !combo.entry().action().equals("jump_repeat");
            if (isCombo && combo.end() > firedUntil && (isFinal || force || !table.couldGrow(text.substring(combo.start())))) {
                queue.add(new Heard(rawText, combo.entry().action(), isFinal));
                firedUntil = combo.end();
                nameFrom = combo.end();
                jumpFiredAt = -1;
                from = Math.min(firedUntil, text.length());
            }
        }

        // 見つかった言葉を順番にアクションにする
        CommandTable.Match match = table.findFirst(text, from);
        while (match != null) {
            // 話している途中で、ここから後ろが もっと長い言葉の始まりかもしれないなら待つ（「みぎ」と「みぎによけて」など）
            if (!isFinal && !force && table.couldGrow(text.substring(match.start()))) break;
            String action = match.entry().action();
            if (action.contains("name:")) {
                // 「〇〇を持って」なら、〇〇の部分をアクションにつける
                String name = text.substring(nameFrom, match.start());
                if (name.endsWith("を")) name = name.substring(0, name.length() - 1);
                action = action + "|" + name;
                nameFrom = match.end();
            } else if (!action.startsWith("item:")) {
                // 道具えらび（「剣」など）は名前の一部かもしれないので、名前の始まりを動かさない
                nameFrom = match.end();
            }
            queue.add(new Heard(rawText, action, isFinal));
            jumpFiredAt = action.equals("jump") ? match.start() : -1;
            firedUntil = match.end();
            firedAny = true;
            match = table.findFirst(text, firedUntil);
        }

        // 話し終わったら、次の話のために最初にもどす
        if (isFinal) {
            if (!text.isEmpty() && !firedAny) queue.add(new Heard(rawText, "heard", true));
            firedUntil = 0;
            nameFrom = 0;
            firedAny = false;
            jumpFiredAt = -1;
            lastPartial = "";
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
