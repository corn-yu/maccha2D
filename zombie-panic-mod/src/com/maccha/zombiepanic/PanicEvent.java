package com.maccha.zombiepanic;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * パニックの流れ（しずか → 前ぶれ → パニック → しずか）を進める係。
 */
public final class PanicEvent {

    /** いまの段階。 */
    public enum Phase { CALM, WARNING, PANIC }

    private static final int TICKS_PER_SECOND = 20;
    private static final RandomSource random = RandomSource.create();

    // いまの段階と、その段階の残りtick
    private static Phase currentPhase = Phase.CALM;
    private static int phaseTicksLeft = 0;

    // パニックの種類と長さ
    private static PanicType currentType = PanicType.NORMAL;
    private static int panicTotalTicks = 1;

    // 画面の上のバー（残り時間とゾンビ大将の体力）
    private static ServerBossEvent timeBar = null;
    private static ServerBossEvent bossBar = null;

    // ゾンビ大将
    private static Zombie bossZombie = null;
    private static boolean bossSpawned = false;

    // だれが何体たおしたか
    private static final Map<UUID, Integer> killCounts = new HashMap<>();
    private static final Map<UUID, String> killerNames = new HashMap<>();

    private PanicEvent() {
    }

    /** いまパニック中かどうか。 */
    public static boolean isPanicActive() {
        return currentPhase == Phase.PANIC;
    }

    /** いまの段階を返す。 */
    public static Phase getPhase() {
        return currentPhase;
    }

    /** いまの段階の残り秒数を返す。 */
    public static int getSecondsLeft() {
        return Math.max(0, phaseTicksLeft / TICKS_PER_SECOND);
    }

    /** いまのパニックの種類を返す。 */
    public static PanicType getCurrentType() {
        return currentType;
    }

    // ---------------- サーバーのイベント ----------------

    /** サーバー（ワールド）が始まったら、しずかな時間から始める。 */
    public static void onServerStarted(ServerStartedEvent event) {
        clearEverything();
        startCalm();
    }

    /** サーバーが止まるときに、バーなどを片づける。 */
    public static void onServerStopping(ServerStoppingEvent event) {
        clearEverything();
    }

    /** 毎tick、段階ごとの時計を進める。 */
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        boolean anyoneOnline = !server.getPlayerList().getPlayers().isEmpty();

        switch (currentPhase) {
            case CALM -> tickCalm(server, anyoneOnline);
            case WARNING -> tickWarning(server);
            case PANIC -> tickPanic(server);
        }
    }

    /** 途中で入ってきた人にもバーを見せる。 */
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (timeBar != null) {
            timeBar.addPlayer(player);
        }
        if (bossBar != null) {
            bossBar.addPlayer(player);
        }
        if (currentPhase == Phase.PANIC) {
            player.sendSystemMessage(Component.literal("いま「" + currentType.title + "」の真っ最中！ 気をつけて！")
                    .withStyle(ChatFormatting.RED));
        }
    }

    /** ぬけた人をバーから外す。 */
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (timeBar != null) {
            timeBar.removePlayer(player);
        }
        if (bossBar != null) {
            bossBar.removePlayer(player);
        }
    }

    /** ゾンビがたおされたら数え、ゾンビ大将ならパニックを終わらせる。 */
    public static void onLivingDeath(LivingDeathEvent event) {
        if (currentPhase != Phase.PANIC || event.getEntity().level().isClientSide()) {
            return;
        }
        LivingEntity deadEntity = event.getEntity();
        if (!(deadEntity instanceof Zombie)) {
            return;
        }

        // たおした人を数える（矢などでも、うった人を数える）
        Entity killer = event.getSource().getEntity();
        ServerPlayer killerPlayer = killer instanceof ServerPlayer player ? player : null;
        if (killerPlayer != null) {
            UUID playerId = killerPlayer.getUUID();
            killCounts.merge(playerId, 1, Integer::sum);
            killerNames.put(playerId, killerPlayer.getName().getString());
        }

        // ゾンビ大将がたおされた
        if (deadEntity.getTags().contains(ZombieSpawner.BOSS_TAG)) {
            MinecraftServer server = deadEntity.getServer();
            if (server != null) {
                onBossDefeated(server, deadEntity, killerPlayer);
            }
        }
    }

    // ---------------- 段階ごとの動き ----------------

    /** しずかな時間を進め、時間がきたら前ぶれを始める（だれもいないときは待つ）。 */
    private static void tickCalm(MinecraftServer server, boolean anyoneOnline) {
        if (phaseTicksLeft > 0) {
            phaseTicksLeft--;
            return;
        }
        if (anyoneOnline) {
            startWarning(server, pickRandomType());
        }
    }

    /** 前ぶれを進める（ときどき うめき声）。 */
    private static void tickWarning(MinecraftServer server) {
        phaseTicksLeft--;
        if (phaseTicksLeft % (5 * TICKS_PER_SECOND) == 0) {
            playSoundToAll(server, SoundEvents.ZOMBIE_AMBIENT, 1.0f, 0.6f);
        }
        if (phaseTicksLeft <= 0) {
            startPanic(server, currentType);
        }
    }

    /** パニックを進める（ゾンビを出す・バーを更新・ゾンビ大将を出す）。 */
    private static void tickPanic(MinecraftServer server) {
        phaseTicksLeft--;
        int elapsedTicks = panicTotalTicks - phaseTicksLeft;
        float progress = (float) elapsedTicks / panicTotalTicks;

        // ゾンビの群れを出す
        if (elapsedTicks % PanicConfig.SPAWN_INTERVAL_TICKS == 0) {
            ZombieSpawner.spawnWave(server, currentType, progress, phaseTicksLeft + 10 * TICKS_PER_SECOND);
        }

        // ゾンビ大将（出せなかったら1秒ごとにやりなおす）
        if (currentType == PanicType.BOSS && !bossSpawned
                && elapsedTicks >= PanicConfig.BOSS_APPEAR_SECONDS * TICKS_PER_SECOND
                && elapsedTicks % TICKS_PER_SECOND == 0) {
            trySpawnBoss(server);
        }

        // 1秒ごとにバーを更新
        if (elapsedTicks % TICKS_PER_SECOND == 0) {
            updateBars();
        }

        if (phaseTicksLeft <= 0) {
            endPanic(server, false);
        }
    }

    // ---------------- 段階の切りかえ ----------------

    /** しずかな時間を始める（長さはランダム）。 */
    private static void startCalm() {
        currentPhase = Phase.CALM;
        int calmMinutes = PanicConfig.CALM_MIN_MINUTES
                + random.nextInt(PanicConfig.CALM_MAX_MINUTES - PanicConfig.CALM_MIN_MINUTES + 1);
        phaseTicksLeft = calmMinutes * 60 * TICKS_PER_SECOND;
    }

    /** 前ぶれを始める（うめき声とメッセージ）。 */
    public static void startWarning(MinecraftServer server, PanicType panicType) {
        currentPhase = Phase.WARNING;
        currentType = panicType;
        phaseTicksLeft = PanicConfig.WARNING_SECONDS * TICKS_PER_SECOND;

        broadcast(server, Component.literal("……どこからか ゾンビのうめき声が聞こえる……")
                .withStyle(ChatFormatting.DARK_RED, ChatFormatting.ITALIC));
        broadcast(server, Component.literal("あと " + PanicConfig.WARNING_SECONDS + " 秒で 何かが起きそうだ。かくれる場所をさがそう！")
                .withStyle(ChatFormatting.GRAY));
        playSoundToAll(server, SoundEvents.ZOMBIE_AMBIENT, 1.0f, 0.5f);
    }

    /** パニックを始める（タイトル・ラッパの音・残り時間のバー）。 */
    public static void startPanic(MinecraftServer server, PanicType panicType) {
        // 前のパニックが残っていたら片づける
        clearBars();
        killCounts.clear();
        killerNames.clear();
        bossZombie = null;
        bossSpawned = false;

        currentPhase = Phase.PANIC;
        currentType = panicType;
        panicTotalTicks = PanicConfig.PANIC_MINUTES * 60 * TICKS_PER_SECOND;
        phaseTicksLeft = panicTotalTicks;

        // 残り時間のバー
        timeBar = new ServerBossEvent(makeTimeBarName(), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_10);
        timeBar.setDarkenScreen(true);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            timeBar.addPlayer(player);
        }

        // 大きな文字とラッパの音
        showTitleToAll(server,
                Component.literal(panicType.title + "！").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD),
                Component.literal(panicType.description).withStyle(ChatFormatting.RED));
        playSoundToAll(server, SoundEvents.RAID_HORN.value(), 1.0f, 0.8f);
        broadcast(server, Component.literal("【" + panicType.title + "】" + panicType.description
                + "（" + PanicConfig.PANIC_MINUTES + "分間）").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
    }

    /** パニックを終わらせ、ランキングを見せて、しずかな時間にもどす。 */
    public static void endPanic(MinecraftServer server, boolean bossDefeated) {
        if (currentPhase != Phase.PANIC) {
            startCalm();
            return;
        }

        // ゾンビ大将がまだいたら、帰っていく
        if (bossZombie != null && bossZombie.isAlive() && !bossDefeated) {
            broadcast(server, Component.literal("ゾンビ大将は どこかへ 去っていった……").withStyle(ChatFormatting.GRAY));
        }

        // まず段階をもどしてから、ゾンビを落ちつかせる
        currentPhase = Phase.CALM;
        ZombieSpawner.calmDownPanicZombies(server);
        clearBars();
        bossZombie = null;

        // おわりの文字・音・ランキング
        showTitleToAll(server,
                Component.literal("パニック しゅうりょう！").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
                Component.literal("よく 生きのびたね！").withStyle(ChatFormatting.YELLOW));
        playSoundToAll(server, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        broadcast(server, Component.literal("ゾンビは 太陽の光で また燃えるようになったよ。").withStyle(ChatFormatting.GRAY));
        broadcastRanking(server);

        killCounts.clear();
        killerNames.clear();
        startCalm();
    }

    // ---------------- ゾンビ大将 ----------------

    /** ゾンビ大将を出してみる。出せたら体力バーを作る。 */
    private static void trySpawnBoss(MinecraftServer server) {
        Zombie boss = ZombieSpawner.spawnBoss(server, phaseTicksLeft + 10 * TICKS_PER_SECOND);
        if (boss == null) {
            return;
        }
        bossZombie = boss;
        bossSpawned = true;

        // 体力のバー
        bossBar = new ServerBossEvent(Component.literal("ゾンビ大将").withStyle(ChatFormatting.GOLD),
                BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.PROGRESS);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            bossBar.addPlayer(player);
        }
        playSoundToAll(server, SoundEvents.WITHER_SPAWN, 0.6f, 1.2f);
    }

    /** ゾンビ大将がたおされたら、ごほうびを落として パニックを終わらせる。 */
    private static void onBossDefeated(MinecraftServer server, LivingEntity boss, ServerPlayer killerPlayer) {
        // だれがたおしたか知らせる
        String heroName = killerPlayer != null ? killerPlayer.getName().getString() : "だれか";
        broadcast(server, Component.literal(heroName + " が ゾンビ大将を たおした！ すごい！")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));

        // ごほうびを その場に落とす
        dropReward(boss, new ItemStack(Items.GOLDEN_APPLE, PanicConfig.REWARD_GOLDEN_APPLES));
        dropReward(boss, new ItemStack(Items.DIAMOND, PanicConfig.REWARD_DIAMONDS));

        endPanic(server, true);
    }

    /** ゾンビ大将の場所に ごほうびのアイテムを落とす。 */
    private static void dropReward(LivingEntity boss, ItemStack stack) {
        ItemEntity itemEntity = new ItemEntity(boss.level(), boss.getX(), boss.getY() + 0.5, boss.getZ(), stack);
        itemEntity.setDefaultPickUpDelay();
        boss.level().addFreshEntity(itemEntity);
    }

    // ---------------- 表示 ----------------

    /** 残り時間とゾンビ大将の体力のバーを新しくする。 */
    private static void updateBars() {
        if (timeBar != null) {
            timeBar.setName(makeTimeBarName());
            timeBar.setProgress(Math.max(0.0f, (float) phaseTicksLeft / panicTotalTicks));
        }
        if (bossBar != null && bossZombie != null) {
            if (bossZombie.isAlive()) {
                bossBar.setProgress(Math.max(0.0f, bossZombie.getHealth() / bossZombie.getMaxHealth()));
            } else if (bossZombie.isRemoved()) {
                // 読みこみの外に行った・消えたときは、バーだけ消す
                bossBar.removeAllPlayers();
                bossBar = null;
            }
        }
    }

    /** 残り時間のバーの文字を作る。 */
    private static Component makeTimeBarName() {
        int secondsLeft = getSecondsLeft();
        String timeText = (secondsLeft / 60) + ":" + String.format("%02d", secondsLeft % 60);
        return Component.literal(currentType.title + "  のこり " + timeText).withStyle(ChatFormatting.RED);
    }

    /** たおした数のランキング（上位3人）をみんなに見せる。 */
    private static void broadcastRanking(MinecraftServer server) {
        if (killCounts.isEmpty()) {
            broadcast(server, Component.literal("今回は だれもゾンビを たおさなかったみたい。").withStyle(ChatFormatting.GRAY));
            return;
        }

        // 多い順にならべる
        List<Map.Entry<UUID, Integer>> ranking = new ArrayList<>(killCounts.entrySet());
        ranking.sort((a, b) -> b.getValue() - a.getValue());

        broadcast(server, Component.literal("=== ゾンビをたおした数ランキング ===").withStyle(ChatFormatting.YELLOW));
        String[] medals = {"1位", "2位", "3位"};
        for (int i = 0; i < Math.min(3, ranking.size()); i++) {
            Map.Entry<UUID, Integer> entry = ranking.get(i);
            String name = killerNames.getOrDefault(entry.getKey(), "?");
            broadcast(server, Component.literal(medals[i] + "  " + name + "  " + entry.getValue() + "体")
                    .withStyle(i == 0 ? ChatFormatting.GOLD : ChatFormatting.WHITE));
        }
    }

    /** みんなのチャットに知らせる。 */
    private static void broadcast(MinecraftServer server, Component message) {
        server.getPlayerList().broadcastSystemMessage(message, false);
    }

    /** みんなの画面に大きな文字を出す。 */
    private static void showTitleToAll(MinecraftServer server, Component title, Component subtitle) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 60, 20));
            player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
            player.connection.send(new ClientboundSetTitleTextPacket(title));
        }
    }

    /** みんなに音を聞かせる（その人にだけ聞こえる音）。 */
    private static void playSoundToAll(MinecraftServer server, SoundEvent sound, float volume, float pitch) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.playNotifySound(sound, SoundSource.HOSTILE, volume, pitch);
        }
    }

    // ---------------- 片づけ ----------------

    /** パニックの種類をランダムに選ぶ。 */
    private static PanicType pickRandomType() {
        PanicType[] types = PanicType.values();
        return types[random.nextInt(types.length)];
    }

    /** バーを全部消す。 */
    private static void clearBars() {
        if (timeBar != null) {
            timeBar.removeAllPlayers();
            timeBar = null;
        }
        if (bossBar != null) {
            bossBar.removeAllPlayers();
            bossBar = null;
        }
    }

    /** 記録を全部まっさらにする（ワールドを開きなおしたとき用）。 */
    private static void clearEverything() {
        clearBars();
        currentPhase = Phase.CALM;
        phaseTicksLeft = 0;
        bossZombie = null;
        bossSpawned = false;
        killCounts.clear();
        killerNames.clear();
    }
}
