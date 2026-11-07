package com.maccha.zombiepanic;

import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;

import com.maccha.zombiepanic.ZombieAbilities.Ability;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;


import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * プレイヤーの能力（たおした能力ゾンビから ときどき もらえる）。
 * 持っているだけで効く「いつもの能力」と、能力の石で使う「大技」がある。死んでも なくならない。
 */
public final class PlayerPowers {

    // 能力で呼んだアイアンゴーレム（しばらくで帰る）
    public static final String HELPER_GOLEM_TAG = "zp_helper_golem";
    private static final int HELPER_GOLEM_LIFE_TICKS = 20 * 60;

    private static final RandomSource random = RandomSource.create();

    // つぎに使えるようになる時間（プレイヤー＋能力 → ゲーム時間）
    private static final Map<String, Long> cooldowns = new HashMap<>();
    // 落ちてもへいきな時間（プレイヤー → ゲーム時間）
    private static final Map<UUID, Long> noFallUntil = new HashMap<>();
    // ロケットでとんだ時間（着地で しょうげき波）
    private static final Map<UUID, Long> rocketLaunch = new HashMap<>();

    private PlayerPowers() {
    }

    // ---------------- 能力をもらう ----------------

    /** 能力ゾンビをたおした人に、確率で そのゾンビの能力を1つ あげる。 */
    public static void tryStealAbility(ServerPlayer player, Zombie zombie) {
        int tier = ZombieAbilities.bodyTier(zombie);
        int chance = switch (tier) {
            case 3 -> PanicConfig.STEAL_CHANCE_TIER3;
            case 2 -> PanicConfig.STEAL_CHANCE_TIER2;
            case 1 -> PanicConfig.STEAL_CHANCE_TIER1;
            default -> 0;
        };
        if (chance <= 0 || random.nextInt(chance) != 0) {
            return;
        }

        // そのゾンビの能力のうち、まだ持っていないもの（災厄があれば それが先）
        List<Ability> candidates = new ArrayList<>();
        for (Ability ability : Ability.values()) {
            if (ability.tier >= 1 && ZombieAbilities.has(zombie, ability) && !hasPower(player, ability)) {
                candidates.add(ability);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        Ability stolen = candidates.contains(Ability.CALAMITY) ? Ability.CALAMITY : candidates.get(random.nextInt(candidates.size()));
        givePower(player, stolen);
    }

    /** 能力を1つ あげて、お知らせする（はじめての大技なら 能力の石もわたす）。 */
    public static void givePower(ServerPlayer player, Ability ability) {
        if (hasPower(player, ability)) {
            player.sendSystemMessage(Component.literal("「" + ability.shortName + "」は もう持っているよ。").withStyle(ChatFormatting.GRAY));
            return;
        }
        PowerData powerData = PowerData.get(player.level().getServer());
        PowerData.Entry entry = powerData.entryOf(player.getUUID());
        entry.owned.add(ability.id);
        powerData.setDirty();

        // 大技なら、それを えらんだ状態にする
        if (!isPassive(ability)) {
            entry.selected = activePowers(player).indexOf(ability);
        }

        // お知らせ
        player.sendSystemMessage(Component.literal("★ 能力ゲット！「" + ability.shortName + "」を うばった！")
                .withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
        player.sendSystemMessage(Component.literal("  " + describe(ability)).withStyle(ChatFormatting.LIGHT_PURPLE));
        MobUtil.playFor(player, SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0f, 0.6f);
        MobUtil.playFor(player, SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.PLAYERS, 1.0f, 1.0f);
        player.level().sendParticles(ParticleTypes.WITCH, player.getX(), player.getY() + 1.0, player.getZ(), 40, 0.5, 0.8, 0.5, 0.05);

        if (!isPassive(ability)) {
            giveStoneIfMissing(player);
        }
    }

    /** 能力の石を持っていなければ わたす。 */
    public static void giveStoneIfMissing(ServerPlayer player) {
        if (player.getInventory().hasAnyMatching(stack -> stack.is(ModContent.ABILITY_STONE.get()))) {
            return;
        }
        ItemStack stone = new ItemStack(ModContent.ABILITY_STONE.get());
        if (!player.getInventory().add(stone)) {
            player.spawnAtLocation(player.level(), stone);
        }
        player.sendSystemMessage(Component.literal("  「能力の石」をもらった！ 右クリックで大技、しゃがんで右クリックで 大技をえらびなおせるよ。")
                .withStyle(ChatFormatting.YELLOW));
    }

    // ---------------- 持っている能力 ----------------

    /** その能力を持っているか。 */
    public static boolean hasPower(Entity player, Ability ability) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return false;
        }
        return PowerData.get(serverPlayer.level().getServer()).entryOf(serverPlayer.getUUID()).owned.contains(ability.id);
    }

    /** 持っている大技の一覧（能力の順）。 */
    public static List<Ability> activePowers(Entity player) {
        List<Ability> actives = new ArrayList<>();
        for (Ability ability : Ability.values()) {
            if (!isPassive(ability) && hasPower(player, ability)) {
                actives.add(ability);
            }
        }
        actives.sort(Comparator.comparingInt(Enum::ordinal));
        return actives;
    }

    /** いま えらんでいる大技（なければ null）。 */
    public static Ability selectedPower(Entity player) {
        List<Ability> actives = activePowers(player);
        if (actives.isEmpty()) {
            return null;
        }
        PowerData.Entry entry = PowerData.get(((ServerPlayer) player).level().getServer()).entryOf(player.getUUID());
        int index = Math.floorMod(entry.selected, actives.size());
        return actives.get(index);
    }

    /** 持っているだけで効く能力か（いつもの能力）。 */
    public static boolean isPassive(Ability ability) {
        return switch (ability) {
            case SWIFT, JUMPER, INVISIBLE, POISON, FIRE, VAMPIRE, FREEZER -> true;
            default -> false;
        };
    }

    /** もらったときに見せる 説明。 */
    public static String describe(Ability ability) {
        return switch (ability) {
            case GRAPPLE -> "大技: 見ている場所へ フックで 一気にとぶ";
            case ROCKET -> "大技: 空高く とび上がり、着地で しょうげき波";
            case CHARGER -> "大技: 前へ 突進して ゾンビを ふきとばす";
            case WIND -> "大技: 風弾を うつ";
            case CLONE -> "大技: 分身で ゾンビを まどわせ、すがたを消して 走る";
            case EXPLODER -> "大技: 自分のまわりを ばくはつさせる（自分は へいき）";
            case SPLITTER -> "大技: 風弾を 3つ いっぺんに うつ";
            case GIANT -> "大技: 大きな 地ならしで まわりを ふきとばす";
            case METEOR -> "大技: まわりのゾンビに 隕石を ふらせる";
            case TORNADO -> "大技: 竜巻で まわりのゾンビを 空へまきあげる";
            case SHADOW -> "大技: 影になって すがたを消し、力が強くなる";
            case SUMMONER -> "大技: アイアンゴーレム2体を よぶ（1分）";
            case TELEPORTER -> "大技: 見ている場所へ 瞬間移動";
            case THUNDER -> "大技: 近くのゾンビ3体に 雷を落とす";
            case PULLER -> "大技: まわりのゾンビを 引きよせる";
            case CALAMITY -> "大技: 災厄の力（まわりのゾンビすべてに 雷と 大ダメージ）";
            case SWIFT -> "いつも: 足が速くなる";
            case JUMPER -> "いつも: 高くジャンプできる";
            case INVISIBLE -> "いつも: しゃがむと すがたが消える";
            case POISON -> "いつも: なぐった相手を 毒にする";
            case FIRE -> "いつも: なぐった相手を 燃やす";
            case VAMPIRE -> "いつも: なぐると 体力が少しもどる";
            case FREEZER -> "いつも: なぐった相手を こおらせて おそくする";
            default -> "";
        };
    }

    /** 大技の 使ったあと待つ時間（秒）。 */
    private static int cooldownSeconds(Ability ability) {
        return switch (ability) {
            case WIND -> 1;
            case GRAPPLE -> 2;
            case CHARGER, TELEPORTER -> 4;
            case SPLITTER, PULLER -> 5;
            case ROCKET -> 7;
            case EXPLODER, THUNDER -> 10;
            case METEOR, TORNADO, GIANT -> 12;
            case CLONE, SHADOW -> 20;
            case SUMMONER -> 45;
            case CALAMITY -> 60;
            default -> 5;
        };
    }

    // ---------------- 能力の石 ----------------

    /** 能力の石で 大技をつかう。 */
    public static void useSelectedPower(ServerPlayer player) {
        Ability ability = selectedPower(player);
        if (ability == null) {
            showActionBar(player, "まだ 大技を持っていないよ。能力ゾンビを たおして うばおう！", ChatFormatting.GRAY);
            return;
        }

        // まだ待ち時間
        long now = player.level().getGameTime();
        String key = player.getUUID() + ":" + ability.id;
        long readyAt = cooldowns.getOrDefault(key, 0L);
        if (now < readyAt) {
            long secondsLeft = (readyAt - now + 19) / 20;
            showActionBar(player, ability.shortName + " は あと " + secondsLeft + " 秒で 使えるよ", ChatFormatting.RED);
            return;
        }

        if (runPower(player, ability)) {
            cooldowns.put(key, now + cooldownSeconds(ability) * 20L);
            showActionBar(player, "【" + ability.shortName + "】！", ChatFormatting.LIGHT_PURPLE);
        }
    }

    /** つぎの大技に えらびなおす。 */
    public static void selectNextPower(ServerPlayer player) {
        List<Ability> actives = activePowers(player);
        if (actives.isEmpty()) {
            showActionBar(player, "まだ 大技を持っていないよ。", ChatFormatting.GRAY);
            return;
        }
        PowerData powerData = PowerData.get(player.level().getServer());
        PowerData.Entry entry = powerData.entryOf(player.getUUID());
        int next = Math.floorMod(entry.selected + 1, actives.size());
        entry.selected = next;
        powerData.setDirty();

        // 「隕石 ▶ 竜巻 ▶ 雷」のように 今のを目立たせて見せる
        StringBuilder text = new StringBuilder("大技: ");
        for (int i = 0; i < actives.size(); i++) {
            if (i > 0) {
                text.append("  ");
            }
            text.append(i == next ? "▶" + actives.get(i).shortName + "◀" : actives.get(i).shortName);
        }
        showActionBar(player, text.toString(), ChatFormatting.AQUA);
        MobUtil.playFor(player, SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.PLAYERS, 0.6f, 1.4f);
    }

    /** 大技をじっさいに出す。出せなかったら false。 */
    private static boolean runPower(ServerPlayer player, Ability ability) {
        ServerLevel level = player.level();
        switch (ability) {
            case GRAPPLE -> {
                return grapple(level, player);
            }
            case ROCKET -> rocket(level, player);
            case CHARGER -> charge(level, player);
            case WIND -> shootWind(level, player, player.getLookAngle());
            case SPLITTER -> {
                Vec3 look = player.getLookAngle();
                shootWind(level, player, look.yRot(0.25f));
                shootWind(level, player, look);
                shootWind(level, player, look.yRot(-0.25f));
            }
            case CLONE -> vanish(level, player, 6, false);
            case SHADOW -> vanish(level, player, 10, true);
            case EXPLODER -> {
                level.explode(player, player.getX(), player.getY() + 0.5, player.getZ(), 3.0f, Level.ExplosionInteraction.NONE);
                protectFromFall(player, 40);
            }
            case GIANT -> shockwave(level, player, 7.0, 12.0f, 0.9);
            case METEOR -> {
                return meteors(level, player);
            }
            case TORNADO -> tornado(level, player);
            case SUMMONER -> summonGolems(level, player);
            case TELEPORTER -> {
                return teleport(level, player);
            }
            case THUNDER -> {
                return thunder(level, player, 3, 20.0);
            }
            case PULLER -> pullZombies(level, player);
            case CALAMITY -> calamity(level, player);
            default -> {
                return false;
            }
        }
        return true;
    }

    // ---------------- 大技の中身 ----------------

    /** 見ている場所へ フックをうちこんで とんでいく。 */
    private static boolean grapple(ServerLevel level, ServerPlayer player) {
        HitResult hit = player.pick(40.0, 0.0f, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            showActionBar(player, "フックが とどかない…（40ブロックまで）", ChatFormatting.GRAY);
            return false;
        }
        Vec3 from = player.getEyePosition();
        Vec3 to = hit.getLocation();
        AbilityMoves.drawLine(level, from, to, ParticleTypes.CRIT);
        playSound(level, player.blockPosition(), SoundEvents.FISHING_BOBBER_THROW, 1.5f, 0.8f);
        playSound(level, BlockPos.containing(to), SoundEvents.CHAIN_PLACE, 1.2f, 0.8f);

        // 遠いほど速く（上限あり）
        Vec3 direction = to.subtract(from).normalize();
        double speed = Math.min(3.0, 1.0 + from.distanceTo(to) * 0.08);
        pushPlayer(player, new Vec3(direction.x * speed, direction.y * speed + 0.4, direction.z * speed));
        protectFromFall(player, 80);
        return true;
    }

    /** ロケットのように とび上がる（着地で しょうげき波）。 */
    private static void rocket(ServerLevel level, ServerPlayer player) {
        Vec3 look = player.getLookAngle();
        pushPlayer(player, new Vec3(look.x * 0.8, 1.6, look.z * 0.8));
        protectFromFall(player, 200);
        rocketLaunch.put(player.getUUID(), level.getGameTime());
        level.sendParticles(ParticleTypes.FIREWORK, player.getX(), player.getY(), player.getZ(), 30, 0.3, 0.1, 0.3, 0.15);
        playSound(level, player.blockPosition(), SoundEvents.FIREWORK_ROCKET_LAUNCH, 1.5f, 0.7f);
    }

    /** 前へ 突進して、通り道のゾンビを ふきとばす。 */
    private static void charge(ServerLevel level, ServerPlayer player) {
        Vec3 look = player.getLookAngle();
        Vec3 direction = new Vec3(look.x, 0, look.z).normalize();
        pushPlayer(player, new Vec3(direction.x * 2.0, 0.3, direction.z * 2.0));
        protectFromFall(player, 40);
        level.sendParticles(ParticleTypes.CLOUD, player.getX(), player.getY() + 0.2, player.getZ(), 15, 0.4, 0.1, 0.4, 0.05);
        playSound(level, player.blockPosition(), SoundEvents.RAVAGER_ROAR, 1.0f, 1.6f);

        // 通り道（前に7ブロック）のゾンビ
        Vec3 start = player.position();
        Vec3 end = start.add(direction.scale(7.0));
        AABB path = new AABB(start, end).inflate(1.5, 1.0, 1.5);
        for (Zombie zombie : nearbyZombies(level, path)) {
            zombie.hurt(level.damageSources().playerAttack(player), 8.0f);
            zombie.setDeltaMovement(direction.x * 1.5, 0.6, direction.z * 1.5);
            MobUtil.syncMotion(zombie);
        }
    }

    /** 風弾を うつ。 */
    private static void shootWind(ServerLevel level, ServerPlayer player, Vec3 direction) {
        Vec3 from = player.getEyePosition();
        WindCharge windCharge = new WindCharge(level, from.x + direction.x, from.y + direction.y, from.z + direction.z, direction.scale(1.0));
        windCharge.setOwner(player);
        level.addFreshEntity(windCharge);
        playSound(level, player.blockPosition(), SoundEvents.BREEZE_SHOOT, 1.0f, 1.0f);
    }

    /** すがたを消して ゾンビの目をくらます（影なら 力も強くなる）。 */
    private static void vanish(ServerLevel level, ServerPlayer player, int seconds, boolean strong) {
        MobUtil.addTimedEffect(player, MobEffects.INVISIBILITY, seconds * 20, 0);
        MobUtil.addTimedEffect(player, MobEffects.SPEED, seconds * 20, 1);
        if (strong) {
            MobUtil.addTimedEffect(player, MobEffects.STRENGTH, seconds * 20, 1);
        }
        // ねらっていたゾンビは 見うしなう
        for (Zombie zombie : nearbyZombies(level, player.getBoundingBox().inflate(24.0))) {
            if (zombie.getTarget() == player) {
                zombie.setTarget(null);
            }
        }
        level.sendParticles(strong ? ParticleTypes.SQUID_INK : ParticleTypes.POOF, player.getX(), player.getY() + 1.0, player.getZ(),
                40, 0.5, 0.8, 0.5, 0.05);
        playSound(level, player.blockPosition(), SoundEvents.ILLUSIONER_MIRROR_MOVE, 1.0f, 1.0f);
    }

    /** まわりに しょうげき波（ゾンビに ダメージと ふきとばし）。 */
    private static void shockwave(ServerLevel level, ServerPlayer player, double radius, float damage, double knockUp) {
        AbilityMoves.drawRing(level, player.position().add(0, 0.2, 0), radius * 0.5, ParticleTypes.EXPLOSION, 8);
        AbilityMoves.drawRing(level, player.position().add(0, 0.2, 0), radius, ParticleTypes.CAMPFIRE_COSY_SMOKE, 32);
        level.sendParticles(ParticleTypes.DUST_PLUME, player.getX(), player.getY() + 0.1, player.getZ(), 40, radius * 0.4, 0.1, radius * 0.4, 0.05);
        playSound(level, player.blockPosition(), SoundEvents.GENERIC_EXPLODE.value(), 1.2f, 0.6f);

        for (Zombie zombie : nearbyZombies(level, player.getBoundingBox().inflate(radius))) {
            zombie.hurt(level.damageSources().playerAttack(player), damage);
            Vec3 away = zombie.position().subtract(player.position());
            Vec3 push = new Vec3(away.x, 0, away.z).normalize().scale(1.2);
            zombie.setDeltaMovement(push.x, knockUp, push.z);
            MobUtil.syncMotion(zombie);
        }
    }

    /** まわりのゾンビ（5体まで）に 隕石をふらせる。 */
    private static boolean meteors(ServerLevel level, ServerPlayer player) {
        List<Zombie> targets = closestZombies(level, player, 24.0, 5);
        if (targets.isEmpty()) {
            showActionBar(player, "近くに ゾンビがいないよ", ChatFormatting.GRAY);
            return false;
        }
        for (Zombie zombie : targets) {
            AbilityMoves.callMeteorAt(level, zombie.position(), player);
        }
        playSound(level, player.blockPosition(), SoundEvents.BLAZE_SHOOT, 1.5f, 0.5f);
        return true;
    }

    /** 竜巻で まわりのゾンビを 空へまきあげる。 */
    private static void tornado(ServerLevel level, ServerPlayer player) {
        for (int i = 0; i < 40; i++) {
            double angle = i * 0.6;
            double radius = 0.8 + i * 0.15;
            level.sendParticles(ParticleTypes.CLOUD, player.getX() + Math.cos(angle) * radius, player.getY() + i * 0.2,
                    player.getZ() + Math.sin(angle) * radius, 1, 0, 0, 0, 0);
        }
        playSound(level, player.blockPosition(), SoundEvents.BREEZE_WIND_CHARGE_BURST.value(), 1.5f, 0.6f);

        for (Zombie zombie : nearbyZombies(level, player.getBoundingBox().inflate(10.0))) {
            Vec3 away = zombie.position().subtract(player.position());
            Vec3 outward = new Vec3(away.x, 0, away.z).normalize();
            Vec3 around = new Vec3(-outward.z, 0, outward.x);
            zombie.setDeltaMovement(outward.scale(0.4).add(around.scale(0.8)).add(0, 1.3, 0));
            MobUtil.syncMotion(zombie);
            zombie.hurt(level.damageSources().playerAttack(player), 4.0f);
        }
    }

    /** アイアンゴーレム2体を よぶ（1分で帰る）。 */
    private static void summonGolems(ServerLevel level, ServerPlayer player) {
        for (int i = 0; i < 2; i++) {
            IronGolem golem = EntityTypes.IRON_GOLEM.create(level, EntitySpawnReason.EVENT);
            if (golem == null) {
                continue;
            }
            BlockPos spot = ZombieSpawner.findStandPosAround(level, player.blockPosition(), 2, 4);
            BlockPos place = spot != null ? spot : player.blockPosition();
            golem.snapTo(place.getX() + 0.5, place.getY(), place.getZ() + 0.5, player.getYRot(), 0.0f);
            golem.setPlayerCreated(true);
            golem.addTag(HELPER_GOLEM_TAG);
            level.addFreshEntity(golem);
            level.sendParticles(ParticleTypes.SOUL, golem.getX(), golem.getY() + 1.0, golem.getZ(), 20, 0.6, 1.0, 0.6, 0.02);
        }
        playSound(level, player.blockPosition(), SoundEvents.IRON_GOLEM_REPAIR, 1.0f, 0.8f);
    }

    /** 見ている場所へ 瞬間移動する。 */
    private static boolean teleport(ServerLevel level, ServerPlayer player) {
        HitResult hit = player.pick(48.0, 0.0f, false);
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
            showActionBar(player, "そこへは とべない…（48ブロックまで）", ChatFormatting.GRAY);
            return false;
        }
        BlockPos landing = blockHit.getBlockPos().relative(blockHit.getDirection());
        // 足もとと頭が 空いているところだけ
        if (!level.getBlockState(landing).getCollisionShape(level, landing).isEmpty()
                || !level.getBlockState(landing.above()).getCollisionShape(level, landing.above()).isEmpty()) {
            showActionBar(player, "そこは せまくて とべない…", ChatFormatting.GRAY);
            return false;
        }
        level.sendParticles(ParticleTypes.PORTAL, player.getX(), player.getY() + 1.0, player.getZ(), 30, 0.5, 1.0, 0.5, 0.1);
        player.teleportTo(landing.getX() + 0.5, landing.getY(), landing.getZ() + 0.5);
        player.resetFallDistance();
        protectFromFall(player, 40);
        level.sendParticles(ParticleTypes.PORTAL, player.getX(), player.getY() + 1.0, player.getZ(), 30, 0.5, 1.0, 0.5, 0.1);
        playSound(level, landing, SoundEvents.ENDERMAN_TELEPORT, 1.0f, 1.0f);
        return true;
    }

    /** 近くのゾンビに 雷を落とす。 */
    private static boolean thunder(ServerLevel level, ServerPlayer player, int count, double range) {
        List<Zombie> targets = closestZombies(level, player, range, count);
        if (targets.isEmpty()) {
            showActionBar(player, "近くに ゾンビがいないよ", ChatFormatting.GRAY);
            return false;
        }
        for (Zombie zombie : targets) {
            LightningBolt bolt = EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.EVENT);
            if (bolt == null) {
                continue;
            }
            bolt.snapTo(zombie.getX(), zombie.getY(), zombie.getZ());
            bolt.setCause(player);
            level.addFreshEntity(bolt);
        }
        return true;
    }

    /** まわりのゾンビを 自分のほうへ 引きよせる。 */
    private static void pullZombies(ServerLevel level, ServerPlayer player) {
        for (Zombie zombie : nearbyZombies(level, player.getBoundingBox().inflate(14.0))) {
            AbilityMoves.drawLine(level, player.getEyePosition(), zombie.getEyePosition(), ParticleTypes.ENCHANTED_HIT);
            Vec3 toPlayer = player.position().subtract(zombie.position()).normalize().scale(1.0);
            zombie.setDeltaMovement(toPlayer.x, 0.4, toPlayer.z);
            MobUtil.syncMotion(zombie);
        }
        playSound(level, player.blockPosition(), SoundEvents.FISHING_BOBBER_RETRIEVE, 1.2f, 0.6f);
    }

    /** 災厄の力: まわりのゾンビすべてに 大ダメージ・枯れ・雷。 */
    private static void calamity(ServerLevel level, ServerPlayer player) {
        level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, player.getX(), player.getY() + 1.0, player.getZ(), 120, 3.0, 1.5, 3.0, 0.1);
        AbilityMoves.drawRing(level, player.position().add(0, 0.2, 0), 16.0, ParticleTypes.SOUL_FIRE_FLAME, 64);
        playSound(level, player.blockPosition(), SoundEvents.WITHER_SPAWN, 1.0f, 1.2f);
        for (Zombie zombie : nearbyZombies(level, player.getBoundingBox().inflate(16.0))) {
            zombie.hurt(level.damageSources().playerAttack(player), 20.0f);
            MobUtil.addTimedEffect(zombie, MobEffects.WITHER, 200, 1);
        }
        thunder(level, player, 5, 16.0);
        MobUtil.addTimedEffect(player, MobEffects.RESISTANCE, 200, 1);
        MobUtil.addTimedEffect(player, MobEffects.STRENGTH, 200, 1);
    }

    // ---------------- いつもの能力 ----------------

    /** 1秒ごと: 持っているだけで効く能力をかける。 */
    public static void tickEverySecond(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (hasPower(player, Ability.SWIFT)) {
                MobUtil.addTimedEffect(player, MobEffects.SPEED, 50, 0);
            }
            if (hasPower(player, Ability.JUMPER)) {
                MobUtil.addTimedEffect(player, MobEffects.JUMP_BOOST, 50, 1);
            }
            if (hasPower(player, Ability.INVISIBLE) && player.isShiftKeyDown()) {
                MobUtil.addTimedEffect(player, MobEffects.INVISIBILITY, 30, 0);
            }
        }
    }

    /** 毎tick: ロケットの着地を見る。 */
    public static void tick(MinecraftServer server) {
        if (rocketLaunch.isEmpty()) {
            return;
        }
        rocketLaunch.entrySet().removeIf(entry -> {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                return true;
            }
            ServerLevel level = player.level();
            long flying = level.getGameTime() - entry.getValue();
            if (!player.onGround()) {
                level.sendParticles(ParticleTypes.FLAME, player.getX(), player.getY(), player.getZ(), 2, 0.1, 0.1, 0.1, 0.01);
            }
            if ((player.onGround() && flying > 6) || flying > 200) {
                shockwave(level, player, 5.0, 8.0f, 0.8);
                return true;
            }
            return false;
        });
    }

    /** プレイヤーがなぐったときの おまけの効果（毒・炎・吸血・凍結）。 */
    public static void onPlayerHits(ServerPlayer attacker, LivingEntity target) {
        if (target == attacker) {
            return;
        }
        if (hasPower(attacker, Ability.POISON)) {
            MobUtil.addTimedEffect(target, MobEffects.POISON, 100, 0);
        }
        if (hasPower(attacker, Ability.FIRE)) {
            target.igniteForSeconds(4);
        }
        if (hasPower(attacker, Ability.VAMPIRE)) {
            attacker.heal(1.5f);
        }
        if (hasPower(attacker, Ability.FREEZER)) {
            MobUtil.addTimedEffect(target, MobEffects.SLOWNESS, 80, 2);
            target.setTicksFrozen(Math.max(target.getTicksFrozen(), 160));
        }
    }

    /** 大技でとんだあとの 落下ダメージを消すか。 */
    public static boolean shouldIgnoreFall(ServerPlayer player, DamageSource source) {
        if (!source.is(DamageTypeTags.IS_FALL)) {
            return false;
        }
        Long until = noFallUntil.get(player.getUUID());
        return until != null && player.level().getGameTime() < until;
    }

    /** 能力で呼んだゴーレムが 時間になったら帰る。 */
    public static void tickHelperGolem(Entity entity) {
        if (entity.tickCount > HELPER_GOLEM_LIFE_TICKS && entity.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.POOF, entity.getX(), entity.getY() + 1.0, entity.getZ(), 20, 0.5, 1.0, 0.5, 0.02);
            entity.discard();
        }
    }

    // ---------------- 死んでも なくさない ----------------

    /** 生きかえったとき、大技を持っていて 石がなければ もう一度わたす。 */
    public static void onPlayerRespawn(ServerPlayer player) {
        if (!activePowers(player).isEmpty()) {
            giveStoneIfMissing(player);
        }
    }

    // ---------------- 道具 ----------------

    /** 持っている能力を チャットに書き出す（/zombiepanic powers 用）。 */
    public static void listPowers(ServerPlayer player) {
        boolean any = false;
        for (Ability ability : Ability.values()) {
            if (hasPower(player, ability)) {
                player.sendSystemMessage(Component.literal("・" + ability.shortName + " … " + describe(ability)).withStyle(ChatFormatting.LIGHT_PURPLE));
                any = true;
            }
        }
        if (!any) {
            player.sendSystemMessage(Component.literal("まだ 能力を持っていないよ。能力ゾンビを たおすと ときどき もらえる！").withStyle(ChatFormatting.GRAY));
        }
    }

    /** 能力を ぜんぶ なくす（ためし用）。 */
    public static void clearPowers(ServerPlayer player) {
        PowerData.get(player.level().getServer()).clear(player.getUUID());
    }

    /** 近くのゾンビ（分身でも まとめて）。 */
    private static List<Zombie> nearbyZombies(ServerLevel level, AABB area) {
        return level.getEntitiesOfClass(Zombie.class, area, Zombie::isAlive);
    }

    /** 近い順に ゾンビを えらぶ。 */
    private static List<Zombie> closestZombies(ServerLevel level, ServerPlayer player, double range, int count) {
        List<Zombie> zombies = new ArrayList<>(nearbyZombies(level, player.getBoundingBox().inflate(range)));
        zombies.sort(Comparator.comparingDouble(zombie -> zombie.distanceToSqr(player)));
        return zombies.subList(0, Math.min(count, zombies.size()));
    }

    /** プレイヤーを とばす（プレイヤーの動きは 知らせないと 反映されない）。 */
    private static void pushPlayer(ServerPlayer player, Vec3 motion) {
        player.setDeltaMovement(motion);
        MobUtil.syncMotion(player);
        player.resetFallDistance();
    }

    /** しばらく 落ちてもへいきにする。 */
    private static void protectFromFall(ServerPlayer player, int ticks) {
        noFallUntil.put(player.getUUID(), player.level().getGameTime() + ticks);
    }

    /** 画面の下（ホットバーの上）に 文字を出す。 */
    private static void showActionBar(ServerPlayer player, String text, ChatFormatting color) {
        player.sendOverlayMessage(Component.literal(text).withStyle(color));
    }

    /** まわりに聞こえる音を鳴らす。 */
    private static void playSound(ServerLevel level, BlockPos pos, SoundEvent sound, float volume, float pitch) {
        level.playSound(null, pos, sound, SoundSource.PLAYERS, volume, pitch);
    }
}
