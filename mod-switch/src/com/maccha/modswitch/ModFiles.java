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

    /** ゲームが終わるのを待ってから名前を変える PowerShell を、見えないように動かす（Windows 用） */
    static void renameAfterExit() {
        if (pendingOff.isEmpty()) return;
        long pid = ProcessHandle.current().pid();
        StringBuilder script = new StringBuilder();
        script.append("Wait-Process -Id ").append(pid).append(" -ErrorAction SilentlyContinue; Start-Sleep -Seconds 1; ");
        for (Path file : pendingOff) {
            String path = file.toAbsolutePath().toString().replace("'", "''");
            String newName = (file.getFileName().toString() + OFF).replace("'", "''");
            // 念のため 何回か ためす（ファイルがまだ使われていることがあるので）
            script.append("for($i=0;$i -lt 10;$i++){ try { Rename-Item -LiteralPath '").append(path)
                    .append("' -NewName '").append(newName).append("' -ErrorAction Stop; break } catch { Start-Sleep -Seconds 1 } }; ");
        }
        // 日本語のパスでも こわれないように、UTF-16 の Base64 でわたす
        String encoded = Base64.getEncoder().encodeToString(script.toString().getBytes(StandardCharsets.UTF_16LE));
        try {
            new ProcessBuilder("powershell", "-NoProfile", "-WindowStyle", "Hidden", "-EncodedCommand", encoded)
                    .redirectErrorStream(true)
                    .start();
        } catch (Exception problem) {
            ModSwitchClient.LOG.warn("MODスイッチ: 閉じたあとの切りかえを始められませんでした", problem);
        }
    }
}
