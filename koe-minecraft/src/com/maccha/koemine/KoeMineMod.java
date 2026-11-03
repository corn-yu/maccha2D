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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
    int forwardTicks = 0;
    int backTicks = 0;
    int sneakTicks = 0;
    final Survival survival = new Survival(this);
    // つなげ技で、これからするアクションと、つぎまでの待ち時間（tick）
    final ArrayDeque<String> steps = new ArrayDeque<>();
    int stepWait = 0;
    boolean namesSent = false;
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

        // チャットに打った言葉でも動けるようにする（/koemine chat on・off）
        new ChatInput(this).register();
    }

    /** 毎tick：順番待ちのアクションを実行して、キーをおしつづける */
    void onTick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null) return;

        // はじめにワールドに入ったら、アイテムの名前を聞く言葉に足す
        if (!namesSent) {
            namesSent = true;
            try {
                listener.setExtraPhrases(survival.buildNames());
            } catch (Throwable error) {
                LOG.warn("こえマイクラ: 名前の一覧が作れませんでした", error);
            }
        }

        // 聞こえたアクションを全部実行する
        VoiceListener.Heard heard;
        while ((heard = queue.poll()) != null) {
            runAction(minecraft, player, heard);
        }

        // つなげ技の待ち時間をへらして、終わったらつづきをする
        if (stepWait > 0) {
            stepWait--;
            if (stepWait == 0) runSteps(minecraft, player);
        }

        // クラフトなどのしごとを進める
        survival.tick(minecraft, player);

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

        // 状態のお知らせ（ダウンロード中・聞いています・エラー）
        if (action.equals("status") || action.equals("ready") || action.startsWith("error:")) {
            String message = action.equals("ready") ? "§aこえマイクラ：聞いています！「まえ」「壊して」「ジャンプ」などと言ってね"
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
        // （チャットで打った言葉は、マイクオフでも動く）
        if (muted && !heard.fromChat() && !action.equals("unmute")) return;
        player.sendOverlayMessage(Component.literal((heard.fromChat() ? "§bチャット：" : "§b♪ ") + heard.text() + " §f→ §e" + label(action)));
        LOG.info("こえマイクラ: 「{}」→ {}", heard.text(), action);

        // + でつながっていたら、順番にならべる（「とまれ」などは、ならんでいるものを消してすぐする）
        String[] parts = action.split("\\+");
        String first = parts[0].trim();
        if (first.equals("stop") || first.equals("stop_all") || first.equals("mute")) {
            steps.clear();
            stepWait = 0;
        }
        for (String part : parts) steps.add(part.trim());
        runSteps(minecraft, player);
    }

    /** ならんでいるアクションを、待ち時間（wait:tick数）をまもって順番にする */
    void runSteps(Minecraft minecraft, LocalPlayer player) {
        while (stepWait == 0 && !steps.isEmpty()) {
            String step = steps.poll();
            if (step.startsWith("wait:")) {
                stepWait = Math.max(0, parseNumber(step.substring(5)));
            } else {
                runOne(minecraft, player, step);
            }
        }
    }

    /** 数を読む（読めなければ 0） */
    static int parseNumber(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    /** アクションを1つ実行する */
    void runOne(Minecraft minecraft, LocalPlayer player, String action) {
        Options options = minecraft.options;
        // 名前つきのアクション（name:craft|作業台 など）
        if (action.startsWith("name:")) {
            int bar = action.indexOf('|');
            String kind = bar < 0 ? action.substring(5) : action.substring(5, bar);
            String name = bar < 0 ? "" : action.substring(bar + 1);
            runNamed(minecraft, player, kind, name);
            return;
        }
        if (action.startsWith("item:")) {
            survival.holdKind(minecraft, player, action.substring(5));
            return;
        }

        switch (action) {
            // 歩く
            case "forward", "walk" -> { holdForward = true; holdBack = false; if (action.equals("walk")) holdSprint = false; }
            case "back" -> { holdBack = true; holdForward = false; holdSprint = false; }
            case "strafe_left" -> { holdLeft = true; holdRight = false; }
            case "strafe_right" -> { holdRight = true; holdLeft = false; }
            case "sprint" -> { holdSprint = true; holdForward = true; holdBack = false; holdSneak = false; }
            case "step_forward" -> { forwardTicks = 5; holdBack = false; }
            case "short_forward" -> { forwardTicks = 12; holdBack = false; }
            case "step_back" -> { backTicks = 5; holdForward = false; }
            case "stop" -> { stopMoving(); survival.cancel(); }
            case "stop_all" -> { stopMoving(); survival.cancel(); holdAttack = false; holdUse = false; repeatAttack = false; useTicks = 0; holdSneak = false; }
            case "auto_jump" -> {
                boolean now = !options.autoJump().get();
                options.autoJump().set(now);
                Survival.say(player, now ? "オートジャンプ オン" : "オートジャンプ オフ");
            }
            case "dismount" -> sneakTicks = 5;
            case "perspective" -> click(options.keyTogglePerspective);
            case "swap_hand" -> click(options.keySwapOffhand);
            case "look_up_small" -> pendingPitch -= LOOK_STEP / 2;
            case "look_down_small" -> pendingPitch += LOOK_STEP / 2;
            case "look_bottom" -> { pendingPitch = 0f; player.setXRot(90f); }
            case "look_top" -> { pendingPitch = 0f; player.setXRot(-90f); }
            case "put_all" -> survival.moveAll(minecraft, player, true);
            case "take_all" -> survival.moveAll(minecraft, player, false);
            case "take_result" -> survival.takeResult(minecraft, player);
            case "pillar" -> survival.startPillar(player);
            case "jump_use", "jump_attack" -> survival.startJumpCombo(player, action);
            case "respawn" -> { if (player.isDeadOrDying()) { player.respawn(); minecraft.gui.setScreen(null); } }
            case "pause" -> minecraft.pauseGame(false);
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
            // 開いているときにもう一度言ったら閉じる
            case "inventory" -> {
                if (minecraft.gui.screen() == null) click(options.keyInventory);
                else closeScreen(minecraft, player);
            }
            case "close" -> closeScreen(minecraft, player);
            case "drop" -> click(options.keyDrop);
            case "slot_next" -> player.getInventory().setSelectedSlot((player.getInventory().getSelectedSlot() + 1) % 9);
            case "slot_prev" -> player.getInventory().setSelectedSlot((player.getInventory().getSelectedSlot() + 8) % 9);
            // マイク
            case "mute" -> { muted = true; stopMoving(); survival.cancel(); player.sendSystemMessage(Component.literal("§eこえマイクラ：お休み中。「マイクオン」でもどります")); }
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

    /** 名前つきのアクション（つくって・焼いて・持って）をする */
    void runNamed(Minecraft minecraft, LocalPlayer player, String kind, String name) {
        switch (kind) {
            case "craft" -> {
                if (name.isEmpty()) Survival.say(player, "「作業台つくって」のように、名前もいってね");
                else survival.startCraft(minecraft, player, name, false);
            }
            case "smelt" -> {
                if (name.isEmpty()) Survival.say(player, "「鉄インゴット焼いて」のように、名前もいってね");
                else survival.startCraft(minecraft, player, name, true);
            }
            case "hold" -> { if (!name.isEmpty()) survival.hold(minecraft, player, name); }
            default -> LOG.warn("こえマイクラ: 知らない名前アクション {}", kind);
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
        boolean forwardNow = holdForward || forwardTicks > 0;
        if (forwardTicks > 0) forwardTicks--;
        boolean backNow = holdBack || backTicks > 0;
        if (backTicks > 0) backTicks--;
        boolean sneakNow = holdSneak || sneakTicks > 0;
        if (sneakTicks > 0) sneakTicks--;

        // 1つずつキーの状態を決める
        setKey(options.keyUp, forwardNow);
        setKey(options.keyDown, backNow);
        setKey(options.keyLeft, holdLeft);
        setKey(options.keyRight, holdRight);
        setKey(options.keySprint, holdSprint);
        setKey(options.keyShift, sneakNow);
        setKey(options.keyJump, jumpNow);
        setKey(options.keyAttack, holdAttack);
        setKey(options.keyUse, useNow);
    }

    /** キーをおす（声でおしていたキーだけを はなす。手でおしているキーはじゃましない） */
    void setKey(KeyMapping key, boolean down) {
        if (down) {
            // 壊しはじめるときは、攻撃の待ち時間を消す
            if (key == Minecraft.getInstance().options.keyAttack && !key.isDown()) allowAttack();
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
        if (key == Minecraft.getInstance().options.keyAttack) allowAttack();
        KeyMapping.click(InputConstants.getKey(key.saveString()));
    }

    // マイクラの「攻撃できない待ち時間」（Minecraft.missTime）
    static java.lang.reflect.Field missTimeField;
    static boolean missTimeSearched = false;

    /**
     * 攻撃の待ち時間を 0 にする。
     * チャットやインベントリを閉じた直後は、マイクラが 10000tick（約8分）攻撃できなくする。
     * ふつうは次の tick で 0 にもどるが、閉じたすぐあとに声やチャットで攻撃・壊すをすると、ずっと動かなくなるため
     */
    static void allowAttack() {
        try {
            if (!missTimeSearched) {
                missTimeSearched = true;
                missTimeField = Minecraft.class.getDeclaredField("missTime");
                missTimeField.setAccessible(true);
            }
            if (missTimeField != null) missTimeField.setInt(Minecraft.getInstance(), 0);
        } catch (ReflectiveOperationException | RuntimeException error) {
            LOG.warn("こえマイクラ: 攻撃の待ち時間を消せませんでした", error);
        }
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
        listener.setTable(table);
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
        List<String> parts = new ArrayList<>();
        for (String part : action.split("\\+")) parts.add(labelOne(part.trim()));
        return String.join("＋", parts);
    }

    /** アクション1つの名前を日本語にする */
    static String labelOne(String action) {
        // 名前つき・道具えらび
        if (action.startsWith("name:")) {
            int bar = action.indexOf('|');
            String kind = bar < 0 ? action.substring(5) : action.substring(5, bar);
            String name = bar < 0 ? "" : action.substring(bar + 1);
            String verb = switch (kind) {
                case "craft" -> "つくる";
                case "smelt" -> "やく";
                case "hold" -> "もつ";
                default -> kind;
            };
            return name.isEmpty() ? verb : name + "を" + verb;
        }
        if (action.startsWith("item:")) return action.substring(5) + "をもつ";
        if (action.startsWith("wait:")) return "まつ";
        return switch (action) {
            case "step_forward" -> "いっぽまえ";
            case "short_forward" -> "ちょっとまえ";
            case "step_back" -> "いっぽうしろ";
            case "auto_jump" -> "オートジャンプ";
            case "dismount" -> "おりる";
            case "perspective" -> "視点";
            case "swap_hand" -> "持ちかえ";
            case "look_up_small" -> "ちょっとうえ";
            case "look_down_small" -> "ちょっとした";
            case "look_bottom" -> "真下";
            case "look_top" -> "真上";
            case "put_all" -> "ぜんぶいれる";
            case "take_all" -> "ぜんぶとる";
            case "take_result" -> "できたのをとる";
            case "pillar" -> "足元におく";
            case "jump_use" -> "ジャンプして置く";
            case "jump_attack" -> "ジャンプして攻撃";
            case "respawn" -> "リスポーン";
            case "pause" -> "ポーズ";
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
