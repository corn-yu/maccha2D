package com.maccha.modswitch;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * mods フォルダの MOD を しらべて、オン・オフする
 * オフ＝ファイル名のうしろに .disabled をつける（Fabric は .jar 以外を読まない）
 * Windows では動いている MOD の jar は名前を変えられないので、ゲームを閉じたあとに変える
 */
public class ModFiles {

    static final String OFF = ".disabled";

    /** mods フォルダの MOD 1つぶん */
    public static class Entry {
        // いまの jar のファイル（オンなら xxx.jar、オフなら xxx.jar.disabled）
        Path file;
        // 見せる名前（fabric.mod.json の name。なければファイル名）
        String name;
        // 今のゲームで読みこまれているか
        boolean loaded;
        // この MOD 自身か（自分はオフにできない）
        boolean self;
        // ゲームを閉じたらオフにする予約
        boolean offWhenClosed;

        /** 次の起動で読みこまれるか */
        boolean onNextStart() {
            return file.getFileName().toString().endsWith(".jar") && !offWhenClosed;
        }

        /** 次の起動で 今とちがう状態になるか */
        boolean changed() {
            return onNextStart() != loaded;
        }
    }

    // ゲームを閉じたらオフにする jar（ゲームを閉じるときに まとめて名前を変える）
    static final Set<Path> pendingOff = new LinkedHashSet<>();
    static boolean hookAdded = false;
    // 名前を変える係を もう始めたか
    static boolean helperStarted = false;
    // ゲームを閉じたあと、もう一度 Minecraft を起動するか（「今すぐ反映」ボタン）
    static boolean restartAfterExit = false;

    /** mods フォルダの場所 */
    static Path modsFolder() {
        return FabricLoader.getInstance().getGameDir().resolve("mods");
    }

    /** mods フォルダの MOD を全部しらべる（名前の順） */
    public static List<Entry> scan() {
        // 読みこまれている MOD の jar → 名前・id
        Map<Path, ModContainer> loaded = new HashMap<>();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            ModOrigin origin = mod.getOrigin();
            if (origin.getKind() != ModOrigin.Kind.PATH) continue;
            for (Path path : origin.getPaths()) loaded.put(path.toAbsolutePath().normalize(), mod);
        }
        List<Entry> entries = new ArrayList<>();
        try (Stream<Path> files = Files.list(modsFolder())) {
            for (Path file : files.toList()) {
                String fileName = file.getFileName().toString();
                if (!fileName.endsWith(".jar") && !fileName.endsWith(".jar" + OFF)) continue;
                Entry entry = new Entry();
                entry.file = file;
                ModContainer mod = loaded.get(file.toAbsolutePath().normalize());
                entry.loaded = mod != null;
                entry.self = mod != null && mod.getMetadata().getId().equals("modswitch");
                entry.name = mod != null ? mod.getMetadata().getName() : readName(file);
                entry.offWhenClosed = pendingOff.contains(file);
                entries.add(entry);
            }
        } catch (Exception problem) {
            ModSwitchClient.LOG.warn("MODスイッチ: mods フォルダを読めませんでした", problem);
        }
        entries.sort(Comparator.comparing((Entry entry) -> entry.name.toLowerCase()));
        return entries;
    }

    /** jar の中の fabric.mod.json から名前を読む（読めなければファイル名） */
    static String readName(Path file) {
        String fileName = file.getFileName().toString().replace(OFF, "");
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry json = zip.getEntry("fabric.mod.json");
            if (json == null) return fileName;
            try (InputStream input = zip.getInputStream(json)) {
                String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                Matcher matcher = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]*)\"").matcher(text);
                if (matcher.find()) return matcher.group(1);
            }
        } catch (Exception problem) {
            // 読めない jar はファイル名のまま
        }
        return fileName;
    }

    /** MOD のオン・オフを切りかえる（次の起動から反映） */
    public static void toggle(Entry entry) {
        if (entry.self) return;
        String fileName = entry.file.getFileName().toString();
        try {
            if (entry.offWhenClosed) {
                // 「閉じたらオフ」の予約をやめる
                pendingOff.remove(entry.file);
            } else if (fileName.endsWith(OFF)) {
                // オフ → オン：.disabled をとる（読みこまれていないので すぐ変えられる）
                Path target = entry.file.resolveSibling(fileName.substring(0, fileName.length() - OFF.length()));
                Files.move(entry.file, target);
            } else {
                // オン → オフ：.disabled をつける。動いている jar で変えられないときは、閉じたあとに変える
                Path target = entry.file.resolveSibling(fileName + OFF);
                try {
                    Files.move(entry.file, target);
                } catch (Exception locked) {
                    pendingOff.add(entry.file);
                    addCloseHook();
                }
            }
        } catch (Exception problem) {
            ModSwitchClient.LOG.warn("MODスイッチ: {} を切りかえられませんでした", fileName, problem);
        }
    }

    /** ゲームを閉じるときに、予約した jar の名前を変える係をたのむ（1回だけ） */
    static void addCloseHook() {
        if (hookAdded) return;
        hookAdded = true;
        Runtime.getRuntime().addShutdownHook(new Thread(ModFiles::renameAfterExit, "modswitch-close"));
    }

    /** 今すぐ反映：Minecraft を閉じて、名前を変えてから、もう一度起動する */
    public static void restartNow(net.minecraft.client.Minecraft minecraft) {
        restartAfterExit = true;
        // 終わるのを待つ係を「いま」始めておく（閉じるときの処理に たよらない）
        if (isWindows()) startHelper(true);
        addCloseHook();
        // ワールドに入っていたら保存してから閉じる（stop が ふつうの「ゲームを終了」と同じ流れ）
        minecraft.stop();
    }

    /** 今のゲームを起動したときと同じコマンド（作れなければ null） */
    static List<String> relaunchCommand() {
        try {
            String javaPath = ProcessHandle.current().info().command()
                    .orElse(Path.of(System.getProperty("java.home"), "bin", isWindows() ? "javaw.exe" : "java").toString());
            List<String> command = new ArrayList<>();
            command.add(javaPath);
            // JVM の設定（メモリ・ネイティブの場所・Fabric の設定など）
            command.addAll(java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
            command.add("-cp");
            command.add(System.getProperty("java.class.path"));
            // メインクラスと、ゲームの引数（ユーザー名・ゲームフォルダなど）
            String main = System.getProperty("sun.java.command");
            if (main == null || main.isBlank()) return null;
            command.addAll(List.of(main.trim().split(" ")));
            return command;
        } catch (Throwable problem) {
            ModSwitchClient.LOG.warn("MODスイッチ: 起動コマンドを作れませんでした", problem);
            return null;
        }
    }

    /** Windows かどうか */
    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** Windows のコマンドライン用に、空白や " がある引数を " でかこむ */
    static String quoteArg(String arg) {
        if (!arg.isEmpty() && arg.chars().noneMatch(c -> c == ' ' || c == '\t' || c == '"')) return arg;
        // 最後が \ だと閉じる " とくっついてしまうので、\ を2つにする
        String inner = arg.replace("\"", "\\\"");
        if (inner.endsWith("\\")) inner += "\\";
        return "\"" + inner + "\"";
    }

    /** PowerShell の '...' の中に入れられるようにする */
    static String ps(String text) {
        return "'" + text.replace("'", "''") + "'";
    }

    /** ゲームが終わるときの係：Windows 以外は 起動しなおすだけ、Windows は まだなら 名前を変える係を始める */
    static void renameAfterExit() {
        writeLog("閉じるときの処理が動きました");
        if (!isWindows()) {
            List<String> relaunch = restartAfterExit ? relaunchCommand() : null;
            // Windows 以外は もう名前を変えてあるので、起動しなおすだけ
            if (relaunch != null) {
                try {
                    new ProcessBuilder(relaunch).directory(new java.io.File(System.getProperty("user.dir"))).inheritIO().start();
                } catch (Exception problem) {
                    ModSwitchClient.LOG.warn("MODスイッチ: 起動しなおせませんでした", problem);
                }
            }
            return;
        }
        startHelper(restartAfterExit);
    }

    /** ログ（ゲームの外に残す。なにが起きたか あとで見られるように） */
    static void writeLog(String message) {
        try {
            Path log = FabricLoader.getInstance().getGameDir().resolve("modswitch-apply.log");
            Files.writeString(log, java.time.LocalTime.now().withNano(0) + " [ゲーム] " + message + "\n",
                    StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // ログが書けなくても 動きは変えない
        }
    }

    /**
     * ゲームが終わるのを待って、名前を変えて（Windows）、たのまれていたら もう一度起動する係を始める
     * 長いコマンドは入れられないので、やることを .ps1 のファイルに書いて、それを動かす
     */
    static synchronized void startHelper(boolean relaunchToo) {
        if (helperStarted) return;
        if (pendingOff.isEmpty() && !relaunchToo) return;
        List<String> relaunch = relaunchToo ? relaunchCommand() : null;
        helperStarted = true;
        try {
            Path folder = FabricLoader.getInstance().getGameDir();
            Path script = folder.resolve("modswitch-apply.ps1");
            Path log = folder.resolve("modswitch-apply.log");
            StringBuilder text = new StringBuilder();
            text.append("$log = ").append(ps(log.toAbsolutePath().toString())).append("\r\n");
            text.append("function Log($m) { Add-Content -LiteralPath $log -Value ((Get-Date -Format 'HH:mm:ss') + ' [係] ' + $m) -Encoding UTF8 }\r\n");
            text.append("try {\r\n");
            text.append("Log '待っています'\r\n");
            text.append("Wait-Process -Id ").append(ProcessHandle.current().pid()).append(" -ErrorAction SilentlyContinue\r\n");
            text.append("Start-Sleep -Seconds 2\r\n");
            text.append("Log 'ゲームが終わりました'\r\n");
            for (Path file : pendingOff) {
                String path = file.toAbsolutePath().toString();
                String newName = file.getFileName().toString() + OFF;
                text.append("$done = $false\r\n");
                text.append("for ($i = 0; $i -lt 15 -and -not $done; $i++) { try { Rename-Item -LiteralPath ").append(ps(path))
                        .append(" -NewName ").append(ps(newName)).append(" -ErrorAction Stop; $done = $true; Log ")
                        .append(ps("オフにしました: " + newName)).append(" } catch { Start-Sleep -Seconds 1 } }\r\n");
                text.append("if (-not $done) { Log ").append(ps("名前を変えられませんでした: " + file.getFileName())).append(" }\r\n");
            }
            if (relaunch != null) {
                StringBuilder args = new StringBuilder();
                for (String arg : relaunch.subList(1, relaunch.size())) {
                    if (args.length() > 0) args.append(' ');
                    args.append(quoteArg(arg));
                }
                text.append("Log '起動しなおします'\r\n");
                text.append("Start-Process -FilePath ").append(ps(relaunch.get(0)))
                        .append(" -ArgumentList ").append(ps(args.toString()))
                        .append(" -WorkingDirectory ").append(ps(System.getProperty("user.dir"))).append("\r\n");
            }
            text.append("} catch { Log ('しっぱい: ' + $_) }\r\n");
            // ゲームに入るための情報（ユーザー名やトークン）が ファイルに残らないよう、おわったら自分を消す
            text.append("Remove-Item -LiteralPath $PSCommandPath -ErrorAction SilentlyContinue\r\n");
            // PowerShell 5 が日本語を読めるように BOM つきの UTF-8 で書く
            Files.writeString(script, "\uFEFF" + text, StandardCharsets.UTF_8);
            writeLog("係のスクリプトを書きました（" + pendingOff.size() + " こ" + (relaunch != null ? "、起動しなおしあり" : "") + "）");
            if (!launchDetached(script)) writeLog("係を始められませんでした");
        } catch (Throwable problem) {
            helperStarted = false;
            ModSwitchClient.LOG.warn("MODスイッチ: 名前を変える係を始められませんでした", problem);
            writeLog("しっぱい: " + problem);
        }
    }

    /** スクリプトを、ゲームとは はなれた場所で動かす（ランチャーがゲームと一緒に子どもを終わらせても 生きのこれるように） */
    static boolean launchDetached(Path script) {
        String path = script.toAbsolutePath().toString();
        // まず Windows の仕組み（WMI）で はなれたところから始める（パスに空白があるときは使えない）
        if (!path.contains(" ")) {
            try {
                String command = "$r = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{CommandLine='powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "
                        + path.replace("'", "''") + "'}; if ($r.ReturnValue -ne 0) { exit 1 }";
                Process process = new ProcessBuilder("powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command", command)
                        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                if (process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0) {
                    writeLog("係を はなれた場所で始めました");
                    return true;
                }
            } catch (Throwable problem) {
                writeLog("WMI で始められませんでした: " + problem);
            }
        }
        // だめなら ふつうに始める
        try {
            new ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", path)
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            writeLog("係を ふつうに始めました");
            return true;
        } catch (Throwable problem) {
            writeLog("係を始められませんでした: " + problem);
            return false;
        }
    }
}
