package com.maccha.koemine;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * こえマイクラ：声で言った言葉を、マイクラのキー操作に変える MOD（自分のパソコンだけで動く）
 */
public class KoeMineMod implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("koemine");

    // 1tick（0.05秒）にまわす角度
    static final float TURN_SPEED = 15f;
    // 「みぎ」「ひだり」でまわる角度、「ちょっと」のときの角度
    static final float TURN_BIG = 90f;
    static final float TURN_SMALL = 30f;
    static final float LOOK_STEP = 30f;

    final ConcurrentLinkedQueue<VoiceListener.Heard> queue = new ConcurrentLinkedQueue<>();
    final CommandTable table = new CommandTable();
    Path folder;
    Path wordsFile;
    VoiceListener listener;

    // 声でおしっぱなしにしているもの
    boolean holdForward, holdBack, holdLeft, holdRight;
    boolean holdSprint, holdSneak, holdAttack, holdUse;
    boolean repeatAttack, repeatJump;
    boolean muted = false;
    int jumpTicks = 0;
    int useTicks = 0;
    int attackTimer = 0;
    // これからまわる角度（なめらかにまわすため）
    float pendingYaw = 0f;
    float pendingPitch = 0f;
    // 今 MOD がおしているキー
    final Set<KeyMapping> pressedByVoice = new HashSet<>();
    String lastStatus = "";

    /** MOD が読みこまれたら、言葉表を読んで、マイクを聞きはじめる */
    @Override
    public void onInitializeClient() {
        // 言葉表を読む
        folder = FabricLoader.getInstance().getConfigDir().resolve("koemine");
        wordsFile = folder.resolve("ことば.txt");
        table.load(wordsFile);
        LOG.info("こえマイクラ: 言葉を {} こ読みこみました", table.entries.size());

        // 音声認識を別スレッドで始める（ゲームが止まらないように）
        listener = new VoiceListener(folder, table, queue);
        Thread thread = new Thread(listener, "koemine-voice");
        thread.setDaemon(true);
        thread.start();

        // 毎tickのはじめに、声のアクションをキー操作にする
        ClientTickEvents.START_CLIENT_TICK.register(this::onTick);
    }

    /** 毎tick：順番待ちのアクションを実行して、キーをおしつづける */
    void onTick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null) return;

        // 聞こえたアクションを全部実行する
        VoiceListener.Heard heard;
        while ((heard = queue.poll()) != null) {
            runAction(minecraft, player, heard);
        }

        // 画面（インベントリなど）が開いているときは、歩いたりしない
        if (minecraft.gui.screen() != null) {
            releaseAll();
            return;
        }
        pressKeys(minecraft.options, player);
        turnSmoothly(player);
    }

    /** 1つのアクションを実行する */
    void runAction(Minecraft minecraft, LocalPlayer player, VoiceListener.Heard heard) {
        String action = heard.action();
        Options options = minecraft.options;

        // 状態のお知らせ（ダウンロード中・聞いています・エラー）
        if (action.equals("status") || action.equals("ready") || action.startsWith("error:")) {
            String message = action.equals("ready") ? "§aこえマイクラ：聞いています！「まえ」「ほって」「ジャンプ」などと言ってね"
                    : action.startsWith("error:") ? "§cこえマイクラ：" + action.substring(6)
                    : "§eこえマイクラ：" + listener.status;
            player.sendSystemMessage(Component.literal(message));
            return;
        }
        // 話し終わった文は、画面の下に出すだけ
        if (action.equals("heard")) {
            if (!muted) player.sendOverlayMessage(Component.literal("§7聞こえた：" + heard.text()));
            return;
        }
        // マイクを止めているときは「きいて」だけを聞く
        if (muted && !action.equals("unmute")) return;
        player.sendOverlayMessage(Component.literal("§b♪ " + heard.text() + " §f→ §e" + label(action)));
        LOG.info("こえマイクラ: 「{}」→ {}", heard.text(), action);

        switch (action) {
            // 歩く
            case "forward", "walk" -> { holdForward = true; holdBack = false; if (action.equals("walk")) holdSprint = false; }
            case "back" -> { holdBack = true; holdForward = false; holdSprint = false; }
            case "strafe_left" -> { holdLeft = true; holdRight = false; }
            case "strafe_right" -> { holdRight = true; holdLeft = false; }
            case "sprint" -> { holdSprint = true; holdForward = true; holdBack = false; holdSneak = false; }
            case "stop" -> stopMoving();
            case "stop_all" -> { stopMoving(); holdAttack = false; holdUse = false; repeatAttack = false; useTicks = 0; holdSneak = false; }
            // ジャンプ・しゃがむ
            case "jump" -> jumpTicks = 4;
            case "jump_repeat" -> repeatJump = true;
            case "sneak" -> { holdSneak = true; holdSprint = false; }
            case "stand" -> holdSneak = false;
            // むき
            case "turn_right" -> pendingYaw += TURN_BIG;
            case "turn_left" -> pendingYaw -= TURN_BIG;
            case "turn_right_small" -> pendingYaw += TURN_SMALL;
            case "turn_left_small" -> pendingYaw -= TURN_SMALL;
            case "turn_around" -> pendingYaw += 180f;
            case "look_up" -> pendingPitch -= LOOK_STEP;
            case "look_down" -> pendingPitch += LOOK_STEP;
            case "look_front" -> pendingPitch = -player.getXRot();
            // こわす・こうげき
            case "dig" -> { holdAttack = true; repeatAttack = false; }
            case "attack" -> { holdAttack = false; click(options.keyAttack); }
            case "attack_repeat" -> { repeatAttack = true; holdAttack = false; }
            // つかう・おく
            case "use" -> { holdAttack = false; click(options.keyUse); }
            case "eat" -> { holdAttack = false; useTicks = 40; }
            case "hold_use" -> holdUse = true;
            case "release_use" -> { holdUse = false; useTicks = 0; }
            // もちもの
            case "inventory" -> { if (minecraft.gui.screen() == null) click(options.keyInventory); }
            case "close" -> closeScreen(minecraft, player);
            case "drop" -> click(options.keyDrop);
            case "slot_next" -> player.getInventory().setSelectedSlot((player.getInventory().getSelectedSlot() + 1) % 9);
            case "slot_prev" -> player.getInventory().setSelectedSlot((player.getInventory().getSelectedSlot() + 8) % 9);
            // マイク
            case "mute" -> { muted = true; stopMoving(); player.sendSystemMessage(Component.literal("§eこえマイクラ：お休み中。「きいて」でもどります")); }
            case "unmute" -> { muted = false; player.sendSystemMessage(Component.literal("§aこえマイクラ：また聞いています")); }
            case "reload" -> reloadWords(player);
            default -> {
                // slot1〜slot9 はホットバーをえらぶ
                if (action.startsWith("slot")) {
                    int slot = parseSlot(action);
                    if (slot >= 0) player.getInventory().setSelectedSlot(slot);
                } else {
                    LOG.warn("こえマイクラ: 知らないアクション {}", action);
                }
            }
        }
    }

    /** 歩く・走る・連打・連続ジャンプを全部やめる */
    void stopMoving() {
        holdForward = false;
        holdBack = false;
        holdLeft = false;
        holdRight = false;
        holdSprint = false;
        repeatJump = false;
        repeatAttack = false;
        holdAttack = false;
    }

    /** 声の状態に合わせて、キーをおしたり はなしたりする */
    void pressKeys(Options options, LocalPlayer player) {
        // 連打は 0.5秒ごとにクリック
        if (repeatAttack) {
            attackTimer++;
            if (attackTimer >= 10) {
                attackTimer = 0;
                click(options.keyAttack);
            }
        }
        // 時間つきのおしっぱなしを へらす
        boolean jumpNow = jumpTicks > 0 || repeatJump;
        if (jumpTicks > 0) jumpTicks--;
        boolean useNow = holdUse || useTicks > 0;
        if (useTicks > 0) useTicks--;

        // 1つずつキーの状態を決める
        setKey(options.keyUp, holdForward);
        setKey(options.keyDown, holdBack);
        setKey(options.keyLeft, holdLeft);
        setKey(options.keyRight, holdRight);
        setKey(options.keySprint, holdSprint);
        setKey(options.keyShift, holdSneak);
        setKey(options.keyJump, jumpNow);
        setKey(options.keyAttack, holdAttack);
        setKey(options.keyUse, useNow);
    }

    /** キーをおす（声でおしていたキーだけを はなす。手でおしているキーはじゃましない） */
    void setKey(KeyMapping key, boolean down) {
        if (down) {
            key.setDown(true);
            pressedByVoice.add(key);
        } else if (pressedByVoice.remove(key)) {
            key.setDown(false);
        }
    }

    /** 声でおしていたキーを全部はなす */
    void releaseAll() {
        for (KeyMapping key : pressedByVoice) key.setDown(false);
        pressedByVoice.clear();
    }

    /** キーを1回クリックしたことにする */
    void click(KeyMapping key) {
        if (key.isUnbound()) return;
        KeyMapping.click(InputConstants.getKey(key.saveString()));
    }

    /** 体のむきを、少しずつ回す */
    void turnSmoothly(LocalPlayer player) {
        // 横むき
        if (pendingYaw != 0f) {
            float step = Math.max(-TURN_SPEED, Math.min(TURN_SPEED, pendingYaw));
            player.setYRot(player.getYRot() + step);
            pendingYaw -= step;
        }
        // たてむき（真上・真下より先には回らない）
        if (pendingPitch != 0f) {
            float step = Math.max(-TURN_SPEED, Math.min(TURN_SPEED, pendingPitch));
            float pitch = Math.max(-90f, Math.min(90f, player.getXRot() + step));
            player.setXRot(pitch);
            pendingPitch -= step;
            if (pitch == 90f || pitch == -90f) pendingPitch = 0f;
        }
    }

    /** 開いている画面を閉じる */
    void closeScreen(Minecraft minecraft, LocalPlayer player) {
        if (minecraft.gui.screen() == null) return;
        player.closeContainer();
        if (minecraft.gui.screen() != null) minecraft.gui.setScreen(null);
    }

    /** 言葉表を読みなおす */
    void reloadWords(LocalPlayer player) {
        table.load(wordsFile);
        player.sendSystemMessage(Component.literal("§aこえマイクラ：言葉を " + table.entries.size() + " こ読みこみました"));
    }

    /** "slot3" から 2（0から数えた番号）をとりだす */
    static int parseSlot(String action) {
        try {
            int number = Integer.parseInt(action.substring(4));
            return (number >= 1 && number <= 9) ? number - 1 : -1;
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    /** アクション名を、画面に出す日本語にする */
    static String label(String action) {
        return switch (action) {
            case "forward" -> "まえへ";
            case "walk" -> "あるく";
            case "back" -> "うしろへ";
            case "strafe_left" -> "ひだりへよける";
            case "strafe_right" -> "みぎへよける";
            case "sprint" -> "はしる";
            case "stop" -> "とまる";
            case "stop_all" -> "ぜんぶやめる";
            case "jump" -> "ジャンプ";
            case "jump_repeat" -> "ジャンプしつづける";
            case "sneak" -> "しゃがむ";
            case "stand" -> "たつ";
            case "turn_right" -> "みぎをむく";
            case "turn_left" -> "ひだりをむく";
            case "turn_right_small" -> "ちょっとみぎ";
            case "turn_left_small" -> "ちょっとひだり";
            case "turn_around" -> "うしろをむく";
            case "look_up" -> "うえをみる";
            case "look_down" -> "したをみる";
            case "look_front" -> "まっすぐみる";
            case "dig" -> "ほる";
            case "attack" -> "こうげき";
            case "attack_repeat" -> "れんだ";
            case "use" -> "つかう・おく";
            case "eat" -> "たべる";
            case "hold_use" -> "ひく";
            case "release_use" -> "はなす";
            case "inventory" -> "もちもの";
            case "close" -> "とじる";
            case "drop" -> "すてる";
            case "slot_next" -> "つぎのどうぐ";
            case "slot_prev" -> "まえのどうぐ";
            case "mute" -> "お休み";
            case "unmute" -> "聞く";
            case "reload" -> "ことばよみこみ";
            default -> action.startsWith("slot") ? action.substring(4) + "ばん" : action;
        };
    }
}
