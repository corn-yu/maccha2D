package com.maccha.zombiepanic;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;









import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 呪いの世界の流れを進める係（いつものゾンビ・パニック・たおした数・ボス・クリア）。
 */
public final class PanicEvent {

    /** パニックの段階。 */
    public enum Phase { CALM, WARNING, PANIC }

    private static final int TICKS_PER_SECOND = 20;
    private static final RandomSource random = RandomSource.create();

    // 呪いがまだ続いているか（クリアすると false）
    private static boolean curseActive = true;

    // パニックの段階と、その段階の残りtick
    private static Phase currentPhase = Phase.CALM;
    private static int phaseTicksLeft = 0;
    private static PanicType currentType = PanicType.NORMAL;
    private static int panicTotalTicks = 1;

    // 残り時間のバー
    private static ServerBossEvent timeBar = null;

    // 「ゾンビ大将の襲来」のボス
    private static boolean panicBossSpawned = false;
    private static UUID panicBossId = null;

    // パニック中に だれが何体たおしたか
    private static final Map<UUID, Integer> killCounts = new HashMap<>();
    private static final Map<UUID, String> killerNames = new HashMap<>();

    // サーバーが動いてからのtick
    private static long serverTicks = 0;

    private PanicEvent() {
    }

    // ---------------- まわりから見る ----------------

    /** 呪いが続いているか（ゾンビしか出ない・ゾンビが燃えない）。 */
    public static boolean isCurseActive() {
        return curseActive;
    }

    /** いまパニック中かどうか。 */
    public static boolean isPanicActive() {
        return curseActive && currentPhase == Phase.PANIC;
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

    /** サーバー（ワールド）が始まったら、保存してある記録を読んで しずかな時間から始める。 */
    public static void onServerStarted(MinecraftServer server) {
        clearEverything();
        curseActive = !WorldData.get(server).cleared;
        startCalm();
    }

    /** サーバーが止まるときに、バーなどを片づける。 */
    public static void onServerStopping(MinecraftServer server) {
        clearEverything();
    }

    /** 毎tick、いつものゾンビ・パニック・ドラゴンを進める。 */
    public static void onServerTick(MinecraftServer server) {
        serverTicks++;
        tickAllZombies(server);
        AbilityMoves.tickPendingMeteors(server);
        PlayerPowers.tick(server);
        if (serverTicks % TICKS_PER_SECOND == 0) {
            PlayerPowers.tickEverySecond(server);
        }
        if (!curseActive) {
            return;
        }

        // いつものゾンビ（ずっと）
        if (serverTicks % PanicConfig.BASE_SPAWN_INTERVAL_TICKS == 0) {
            ZombieSpawner.spawnBaseWave(server);
        }

        // パニックの段階
        boolean anyoneOnline = !server.getPlayerList().getPlayers().isEmpty();
        switch (currentPhase) {
            case CALM -> tickCalm(server, anyoneOnline);
            case WARNING -> tickWarning(server);
            case PANIC -> tickPanic(server);
        }

        // 1秒ごと: 永遠の夜・ボスのバー・ゾンビドラゴン・クリアのチェック
        if (serverTicks % TICKS_PER_SECOND == 0) {
            EternalNight.keepNight(server);
            BossZombies.updateBars();
            ZombieDragon.tickEverySecond(server);
        }
    }

    /** 途中で入ってきた人にもバーを見せる。 */
    public static void onPlayerLogin(ServerPlayer player) {
        if (timeBar != null) {
            timeBar.addPlayer(player);
        }
        BossZombies.showBarsTo(player);
        StarterKit.giveIfFirstTime(player);
        PlayerLevel.reapply(player);

        // 入ったときは、パニック中のときだけ知らせる
        if (!curseActive) {
            return;
        }
        if (currentPhase == Phase.PANIC) {
            player.sendSystemMessage(Component.literal("いま「" + currentType.title + "」の真っ最中！ 気をつけて！").withStyle(ChatFormatting.RED));
        }
    }

    /** ぬけた人をバーから外す。 */
    public static void onPlayerLogout(ServerPlayer player) {
        if (timeBar != null) {
            timeBar.removePlayer(player);
        }
        BossZombies.hideBarsFrom(player);
    }

    /** ゾンビやドラゴンが世界に出てきたとき（呪いの力・能力のくじ・ドラゴンの強化）。 */
    public static void onEntityJoin(Entity entity, ServerLevel level) {
        if (!curseActive) {
            return;
        }
        if (entity instanceof Zombie zombie) {
            // 呪いのあいだ、ゾンビは日光で燃えない
            if (!zombie.hasEffect(MobEffects.FIRE_RESISTANCE)) {
                zombie.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, PanicConfig.CURSE_FIRE_RESIST_TICKS, 0, false, false));
            }
            ZombieAbilities.rollAbility(zombie);
        } else if (entity instanceof EnderDragon dragon) {
            ZombieDragon.makeZombieDragon(dragon);
        }
    }

    /** 毎tick、能力ゾンビ（と、よんだゴーレム）を動かす。 */
    private static void tickAllZombies(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            // 動かしている途中で ゾンビが増えたり消えたりするので、先に写してから回す
            List<Entity> snapshot = new ArrayList<>();
            for (Entity entity : level.getAllEntities()) {
                if (entity != null) {
                    snapshot.add(entity);
                }
            }
            for (Entity entity : snapshot) {
                if (entity.isRemoved()) {
                    continue;
                }
                if (entity instanceof Zombie zombie) {
                    ZombieAbilities.tickAbilities(zombie);
                } else if (entity.entityTags().contains(PlayerPowers.HELPER_GOLEM_TAG)) {
                    PlayerPowers.tickHelperGolem(entity);
                }
            }
        }
    }

    // いま ダメージを 入れなおしている最中か（くりかえし防止）
    private static boolean reapplyingDamage = false;

    /** なぐったとき・なぐられたときの、能力と呪鉄の武器の効果。ダメージを通すなら true。 */
    public static boolean onAllowDamage(LivingEntity victimEntity, DamageSource source, float amount) {
        if (reapplyingDamage || !(victimEntity.level() instanceof ServerLevel level)) {
            return true;
        }
        Entity attacker = source.getEntity();

        // 能力ゾンビがなぐった
        if (attacker instanceof Zombie zombieAttacker) {
            ZombieAbilities.onZombieHits(zombieAttacker, victimEntity);
        }

        // プレイヤーがなぐった（うばった能力の 毒・炎・吸血・凍結）
        if (attacker instanceof ServerPlayer playerAttacker && source.getDirectEntity() == playerAttacker) {
            PlayerPowers.onPlayerHits(playerAttacker, victimEntity);
        }

        // 大技でとんだあとは 落ちても平気
        if (victimEntity instanceof ServerPlayer playerVictim && PlayerPowers.shouldIgnoreFall(playerVictim, source)) {
            return false;
        }

        // ゾンビがなぐられた（飛びまわる能力のゾンビは 落ちても平気）
        if (victimEntity instanceof Zombie victim) {
            if (ZombieAbilities.shouldIgnoreDamage(victim, source)) {
                return false;
            }
            NewAbilities.onZombieHurt(level, victim, attacker, amount);
            float multiplier = ZombieAbilities.damageTakenMultiplier(victim);
            if (attacker instanceof Player player) {
                multiplier *= ZombieAbilities.cursedWeaponMultiplier(player);
            }
            // ダメージの大きさを変えるときは、元のダメージを止めて 変えた大きさで入れなおす
            if (Math.abs(multiplier - 1.0f) > 0.001f) {
                reapplyingDamage = true;
                try {
                    victim.hurtServer(level, source, amount * multiplier);
                } finally {
                    reapplyingDamage = false;
                }
                return false;
            }
        }
        return true;
    }

    /** ゾンビがたおされたら数え、ボスや能力のごほうびを出す。 */
    public static void onLivingDeath(LivingEntity dead, DamageSource source) {
        if (!(dead instanceof Zombie zombie) || !(zombie.level() instanceof ServerLevel level)) {
            return;
        }
        MinecraftServer server = level.getServer();

        // 能力ゾンビ（ばくはつ・分裂・ごほうび）とゴールドラッシュ
        ZombieAbilities.onZombieDeath(level, zombie);
        if (zombie.entityTags().contains(ZombieSpawner.GOLD_TAG)) {
            ZombieSpawner.dropGoldRushReward(zombie);
        }

        // たおした人
        ServerPlayer killerPlayer = source.getEntity() instanceof ServerPlayer player ? player : null;

        // ボスがたおされた
        if (BossZombies.isBoss(zombie)) {
            BossZombies.onBossDefeated(server, zombie, killerPlayer);
            WorldData data = WorldData.get(server);
            data.bossesDefeated++;
            data.setDirty();
            if (currentPhase == Phase.PANIC && zombie.getUUID().equals(panicBossId)) {
                endPanic(server);
            }
        }

        // 能力ゾンビをたおした人は、ときどき その能力をもらえる
        if (killerPlayer != null && !ZombieAbilities.isIllusion(zombie) && !BossZombies.isBoss(zombie)) {
            PlayerPowers.tryStealAbility(killerPlayer, zombie);
        }

        if (killerPlayer != null && !ZombieAbilities.isIllusion(zombie)) {
            PlayerLevel.addExpForKill(killerPlayer, ZombieLevel.levelOf(zombie));
        }

        if (killerPlayer != null && curseActive && !ZombieAbilities.isIllusion(zombie)) {
            countKill(server, killerPlayer);
        }
    }

    // ---------------- たおした数 ----------------

    /** たおした数を数えて、決まった数になったらボスを出す。 */
    private static void countKill(MinecraftServer server, ServerPlayer killerPlayer) {
        WorldData data = WorldData.get(server);
        data.killCount++;
        data.setDirty();

        // パニック中のランキング用
        if (currentPhase == Phase.PANIC) {
            killCounts.merge(killerPlayer.getUUID(), 1, Integer::sum);
            killerNames.put(killerPlayer.getUUID(), killerPlayer.getName().getString());
        }

        // ボスの出番
        if (data.killCount >= data.nextBossKills) {
            data.bossKillStep += PanicConfig.BOSS_KILL_STEP_GROW;
            data.nextBossKills = data.killCount + data.bossKillStep;
            BossZombies.BossKind kind = BossZombies.nextKindInTurn(data);
            killerPlayer.level().getServer().getPlayerList().broadcastSystemMessage(
                    Component.literal("ゾンビを " + data.killCount + "体 たおした！ 強い気配が 近づいてくる……")
                            .withStyle(ChatFormatting.DARK_RED), false);
            BossZombies.spawnBossNear(killerPlayer, kind);
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

    /** パニックを進める（ゾンビを出す・バーを更新・ボスを出す）。 */
    private static void tickPanic(MinecraftServer server) {
        phaseTicksLeft--;
        int elapsedTicks = panicTotalTicks - phaseTicksLeft;
        float progress = (float) elapsedTicks / panicTotalTicks;

        // ゾンビの群れを出す
        if (elapsedTicks % PanicConfig.PANIC_SPAWN_INTERVAL_TICKS == 0) {
            ZombieSpawner.spawnPanicWave(server, currentType, progress, phaseTicksLeft + 10 * TICKS_PER_SECOND);
        }

        // 「ゾンビ大将の襲来」のボス（出せなかったら1秒ごとにやりなおす）
        if (currentType == PanicType.BOSS && !panicBossSpawned
                && elapsedTicks >= PanicConfig.BOSS_APPEAR_SECONDS * TICKS_PER_SECOND
                && elapsedTicks % TICKS_PER_SECOND == 0) {
            trySpawnPanicBoss(server);
        }

        // 1秒ごとにバーを更新
        if (elapsedTicks % TICKS_PER_SECOND == 0) {
            updateTimeBar();
        }

        if (phaseTicksLeft <= 0) {
            endPanic(server);
        }
    }

    /** 「ゾンビ大将の襲来」のボスを、だれか1人の近くに出してみる。 */
    private static void trySpawnPanicBoss(MinecraftServer server) {
        List<ServerPlayer> players = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!player.isSpectator()) {
                players.add(player);
            }
        }
        if (players.isEmpty()) {
            return;
        }
        ServerPlayer target = players.get(random.nextInt(players.size()));
        Zombie boss = BossZombies.spawnBossNear(target, BossZombies.BossKind.GENERAL);
        if (boss != null) {
            panicBossSpawned = true;
            panicBossId = boss.getUUID();
            broadcast(server, Component.literal("ゾンビ大将を たおせば パニックが終わるよ！").withStyle(ChatFormatting.GOLD));
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

        broadcast(server, Component.literal("……どこからか たくさんの うめき声が聞こえる……")
                .withStyle(ChatFormatting.DARK_RED, ChatFormatting.ITALIC));
        broadcast(server, Component.literal("あと " + PanicConfig.WARNING_SECONDS + " 秒で 何かが起きそうだ。そなえよう！")
                .withStyle(ChatFormatting.GRAY));
        playSoundToAll(server, SoundEvents.ZOMBIE_AMBIENT, 1.0f, 0.5f);
    }

    /** パニックを始める（タイトル・ラッパの音・残り時間のバー）。 */
    public static void startPanic(MinecraftServer server, PanicType panicType) {
        if (!curseActive) {
            return;
        }
        // 前のパニックが残っていたら片づける
        clearTimeBar();
        killCounts.clear();
        killerNames.clear();
        panicBossSpawned = false;
        panicBossId = null;

        currentPhase = Phase.PANIC;
        currentType = panicType;
        panicTotalTicks = PanicConfig.PANIC_MINUTES * 60 * TICKS_PER_SECOND;
        phaseTicksLeft = panicTotalTicks;

        // 残り時間のバー
        timeBar = new ServerBossEvent(java.util.UUID.randomUUID(), makeTimeBarName(), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_10);
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
    public static void endPanic(MinecraftServer server) {
        if (currentPhase != Phase.PANIC) {
            startCalm();
            return;
        }

        // 先に段階をもどしてから、ゾンビを落ちつかせる
        currentPhase = Phase.CALM;
        ZombieSpawner.calmDownPanicZombies(server);
        clearTimeBar();

        // おわりの文字・音・ランキング
        showTitleToAll(server,
                Component.literal("パニック しゅうりょう！").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
                Component.literal("よく 生きのびたね！").withStyle(ChatFormatting.YELLOW));
        playSoundToAll(server, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        broadcastRanking(server);

        killCounts.clear();
        killerNames.clear();
        startCalm();
    }

    // ---------------- クリア ----------------

    /** 呪いがとけた（ゾンビドラゴンをたおした）ときの、クリアの演出。 */
    public static void onCurseLifted(MinecraftServer server) {
        if (!curseActive) {
            return;
        }
        WorldData data = WorldData.get(server);
        data.cleared = true;
        data.setDirty();
        curseActive = false;

        // パニックとバーを止めて、ゾンビをふつうにもどす
        currentPhase = Phase.CALM;
        clearTimeBar();
        ZombieSpawner.calmDownPanicZombies(server);
        ZombieSpawner.removeCurseFromAllZombies(server);
        EternalNight.bringBackSunrise(server);

        GameClear.celebrate(server, data);
    }

    // ---------------- 表示 ----------------

    /** 残り時間のバーを新しくする。 */
    private static void updateTimeBar() {
        if (timeBar != null) {
            timeBar.setName(makeTimeBarName());
            timeBar.setProgress(Math.max(0.0f, (float) phaseTicksLeft / panicTotalTicks));
        }
    }

    /** 残り時間のバーの文字を作る。 */
    private static Component makeTimeBarName() {
        int secondsLeft = getSecondsLeft();
        String timeText = (secondsLeft / 60) + ":" + String.format("%02d", secondsLeft % 60);
        return Component.literal(currentType.title + "  のこり " + timeText).withStyle(ChatFormatting.RED);
    }

    /** パニック中にたおした数のランキング（上位3人）をみんなに見せる。 */
    private static void broadcastRanking(MinecraftServer server) {
        if (killCounts.isEmpty()) {
            broadcast(server, Component.literal("今回は だれもゾンビを たおさなかったみたい。").withStyle(ChatFormatting.GRAY));
            return;
        }

        // 多い順にならべる
        List<Map.Entry<UUID, Integer>> ranking = new ArrayList<>(killCounts.entrySet());
        ranking.sort((a, b) -> b.getValue() - a.getValue());

        broadcast(server, Component.literal("=== パニック中に たおした数 ===").withStyle(ChatFormatting.YELLOW));
        for (int i = 0; i < Math.min(3, ranking.size()); i++) {
            Map.Entry<UUID, Integer> entry = ranking.get(i);
            String name = killerNames.getOrDefault(entry.getKey(), "?");
            broadcast(server, Component.literal((i + 1) + "位  " + name + "  " + entry.getValue() + "体")
                    .withStyle(i == 0 ? ChatFormatting.GOLD : ChatFormatting.WHITE));
        }
    }

    /** みんなのチャットに知らせる。 */
    public static void broadcast(MinecraftServer server, Component message) {
        server.getPlayerList().broadcastSystemMessage(message, false);
    }

    /** みんなの画面に大きな文字を出す。 */
    public static void showTitleToAll(MinecraftServer server, Component title, Component subtitle) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
            player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
            player.connection.send(new ClientboundSetTitleTextPacket(title));
        }
    }

    /** みんなに音を聞かせる（その人にだけ聞こえる音）。 */
    public static void playSoundToAll(MinecraftServer server, SoundEvent sound, float volume, float pitch) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            MobUtil.playFor(player, sound, SoundSource.HOSTILE, volume, pitch);
        }
    }

    // ---------------- 片づけ ----------------

    /** パニックの種類をランダムに選ぶ。 */
    private static PanicType pickRandomType() {
        PanicType[] types = PanicType.values();
        return types[random.nextInt(types.length)];
    }

    /** 残り時間のバーを消す。 */
    private static void clearTimeBar() {
        if (timeBar != null) {
            timeBar.removeAllPlayers();
            timeBar = null;
        }
    }

    /** 記録を全部まっさらにする（ワールドを開きなおしたとき用）。 */
    private static void clearEverything() {
        clearTimeBar();
        BossZombies.clearAll();
        ZombieDragon.reset();
        AbilityMoves.reset();
        currentPhase = Phase.CALM;
        phaseTicksLeft = 0;
        panicBossSpawned = false;
        panicBossId = null;
        killCounts.clear();
        killerNames.clear();
        curseActive = true;
    }
}
