package com.maccha.zombiepanic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.windcharge.WindCharge;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 能力ゾンビの「大技」（グラップル・ロケット・突進・風弾・分身・隕石・竜巻・地ならし など）。
 */
public final class AbilityMoves {

    // ロケットで とんでいる目じるし
    private static final String ROCKETING_TAG = "zp_rocketing";
    // 分身・影が消えるまでの時間（tick）
    private static final int ILLUSION_LIFE_TICKS = 20 * 20;
    // 隕石が落ちるまでの時間（tick）
    private static final int METEOR_FALL_TICKS = 30;

    /** 落ちてくる途中の隕石。 */
    private record PendingMeteor(ServerLevel level, Vec3 impactPos, long impactTime, Zombie owner) {
    }

    private static final List<PendingMeteor> pendingMeteors = new ArrayList<>();

    private AbilityMoves() {
    }

    // ---------------- 毎tick ----------------

    /** ゾンビ1体ごとに呼ばれて、時間がきた大技を使う。 */
    public static void tick(Zombie zombie) {
        if (!(zombie.level() instanceof ServerLevel level) || !zombie.isAlive()) {
            return;
        }

        // 分身・影は しばらくで消える
        if (ZombieAbilities.isIllusion(zombie)) {
            if (zombie.tickCount > ILLUSION_LIFE_TICKS) {
                level.sendParticles(ParticleTypes.POOF, zombie.getX(), zombie.getY() + 1.0, zombie.getZ(), 10, 0.3, 0.5, 0.3, 0.02);
                zombie.discard();
            }
            return;
        }

        // ロケットの着地（毎tick しらべる）
        if (zombie.getTags().contains(ROCKETING_TAG)) {
            checkRocketLanding(level, zombie);
        }

        // ほかは1秒に1回だけ しらべる
        if (zombie.tickCount % 20 != 0 || !ZombieAbilities.hasAnyAbility(zombie)) {
            return;
        }
        useTimedMoves(level, zombie);
    }

    /** 時間がきた大技を使う（相手がいるときだけのものが多い）。 */
    private static void useTimedMoves(ServerLevel level, Zombie zombie) {
        int time = zombie.tickCount;

        // 相手がいなくても使うもの
        if (ZombieAbilities.canUse(zombie, ZombieAbilities.Ability.SUMMONER) && time % 200 == 0) {
            summonHelpers(level, zombie, 2);
        }
        if (ZombieAbilities.has(zombie, ZombieAbilities.Ability.SHADOW) && time % 240 == 0) {
            spawnShadowsIfFew(level, zombie);
        }

        LivingEntity target = zombie.getTarget();
        if (target == null || !target.isAlive()) {
            return;
        }
        double distance = zombie.distanceTo(target);
        boolean canSee = zombie.hasLineOfSight(target);

        if (ZombieAbilities.canUse(zombie, ZombieAbilities.Ability.GRAPPLE) && time % 60 == 0 && distance > 6 && distance < 30 && canSee) {
            grappleToTarget(level, zombie, target);
        } else if (ZombieAbilities.has(zombie, ZombieAbilities.Ability.ROCKET) && time % 100 == 0 && distance > 4 && distance < 16) {
            launchRocket(level, zombie, target);
        } else if (ZombieAbilities.has(zombie, ZombieAbilities.Ability.CHARGER) && time % 80 == 0 && distance > 4 && distance < 14 && canSee) {
            chargeAtTarget(level, zombie, target);
        }
        if (ZombieAbilities.has(zombie, ZombieAbilities.Ability.WIND) && time % 60 == 0 && distance > 4 && distance < 20 && canSee) {
            shootWindCharge(level, zombie, target);
        }
        if (ZombieAbilities.canUse(zombie, ZombieAbilities.Ability.METEOR) && time % 140 == 0 && distance < 32) {
            callMeteor(level, zombie, target);
        }
        if (ZombieAbilities.canUse(zombie, ZombieAbilities.Ability.TORNADO) && time % 60 == 0 && distance < 10) {
            spinTornado(level, zombie);
        }
        if (ZombieAbilities.canUse(zombie, ZombieAbilities.Ability.GIANT) && time % 60 == 0 && distance < 6.0 + zombie.getBbWidth()) {
            stompGround(level, zombie);
        }
        if (ZombieAbilities.canUse(zombie, ZombieAbilities.Ability.TELEPORTER) && time % 100 == 0) {
            teleportNearTarget(level, zombie, target);
        }
        if (ZombieAbilities.canUse(zombie, ZombieAbilities.Ability.THUNDER) && time % 160 == 0 && distance < 24) {
            strikeLightning(level, target);
        }
        if (ZombieAbilities.canUse(zombie, ZombieAbilities.Ability.PULLER) && time % 80 == 0 && distance > 3 && distance < 16) {
            pullTarget(level, zombie, target);
        }
    }

    // ---------------- グラップル・ロケット・突進・風弾 ----------------

    /** グラップルフックを うちこんで、相手のほうへ 一気に とんでいく。 */
    private static void grappleToTarget(ServerLevel level, Zombie zombie, LivingEntity target) {
        Vec3 from = zombie.getEyePosition();
        Vec3 to = target.getEyePosition();
        drawLine(level, from, to, ParticleTypes.CRIT);
        playSound(level, zombie.blockPosition(), SoundEvents.FISHING_BOBBER_THROW, 1.5f, 0.6f);
        playSound(level, target.blockPosition(), SoundEvents.CHAIN_PLACE, 1.2f, 0.8f);

        // 遠いほど速く（上限あり）
        Vec3 direction = to.subtract(from).normalize();
        double speed = Math.min(2.6, 0.9 + zombie.distanceTo(target) * 0.07);
        zombie.setDeltaMovement(direction.x * speed, direction.y * speed + 0.45, direction.z * speed);
        zombie.hasImpulse = true;
        zombie.resetFallDistance();
    }

    /** ロケットのように 高くとび上がる（着地で しょうげき波）。 */
    private static void launchRocket(ServerLevel level, Zombie zombie, LivingEntity target) {
        Vec3 toTarget = target.position().subtract(zombie.position());
        Vec3 horizontal = new Vec3(toTarget.x, 0, toTarget.z).normalize().scale(Math.min(1.1, toTarget.horizontalDistance() * 0.09));
        zombie.setDeltaMovement(horizontal.x, 1.35, horizontal.z);
        zombie.hasImpulse = true;
        zombie.addTag(ROCKETING_TAG);
        zombie.getPersistentData().putInt("zp_launch", zombie.tickCount);
        level.sendParticles(ParticleTypes.FIREWORK, zombie.getX(), zombie.getY(), zombie.getZ(), 30, 0.3, 0.1, 0.3, 0.15);
        playSound(level, zombie.blockPosition(), SoundEvents.FIREWORK_ROCKET_LAUNCH, 1.5f, 0.7f);
    }

    /** ロケットで とんだあと、着地したら しょうげき波。 */
    private static void checkRocketLanding(ServerLevel level, Zombie zombie) {
        int launchTick = zombie.getPersistentData().getInt("zp_launch");
        int flying = zombie.tickCount - launchTick;
        // とんでいるあいだは 火花のあと
        if (!zombie.onGround()) {
            level.sendParticles(ParticleTypes.FLAME, zombie.getX(), zombie.getY(), zombie.getZ(), 2, 0.1, 0.1, 0.1, 0.01);
        }
        if ((zombie.onGround() && flying > 6) || flying > 100) {
            zombie.removeTag(ROCKETING_TAG);
            zombie.resetFallDistance();
            shockwave(level, zombie, 5.0, 6.0f, 0.8);
        }
    }

    /** 相手に向かって 地面をけって 突進する。 */
    private static void chargeAtTarget(ServerLevel level, Zombie zombie, LivingEntity target) {
        Vec3 toTarget = target.position().subtract(zombie.position());
        Vec3 direction = new Vec3(toTarget.x, 0, toTarget.z).normalize();
        zombie.setDeltaMovement(direction.x * 1.6, 0.25, direction.z * 1.6);
        zombie.hasImpulse = true;
        level.sendParticles(ParticleTypes.CLOUD, zombie.getX(), zombie.getY() + 0.2, zombie.getZ(), 15, 0.4, 0.1, 0.4, 0.05);
        playSound(level, zombie.blockPosition(), SoundEvents.RAVAGER_ROAR, 1.0f, 1.6f);
    }

    /** 風弾を うつ（当たると ふきとばされる）。 */
    private static void shootWindCharge(ServerLevel level, Zombie zombie, LivingEntity target) {
        Vec3 from = zombie.getEyePosition();
        Vec3 direction = target.getEyePosition().subtract(from).normalize();
        WindCharge windCharge = new WindCharge(level, from.x + direction.x, from.y, from.z + direction.z, direction.scale(0.8));
        windCharge.setOwner(zombie);
        level.addFreshEntity(windCharge);
        playSound(level, zombie.blockPosition(), SoundEvents.BREEZE_SHOOT, 1.0f, 0.8f);
    }

    // ---------------- 分身・影 ----------------

    /** 分身（体力1・しばらくで消える）を出して、本物は少し横へずれる。 */
    public static void spawnIllusions(ServerLevel level, Zombie original, int count) {
        @SuppressWarnings("unchecked")
        EntityType<? extends Zombie> type = (EntityType<? extends Zombie>) original.getType();
        for (int i = 0; i < count; i++) {
            Zombie illusion = type.create(level);
            if (illusion == null) {
                continue;
            }
            illusion.addTag(ZombieAbilities.ROLLED_TAG);
            illusion.addTag(ZombieAbilities.ILLUSION_TAG);
            illusion.setBaby(original.isBaby());
            illusion.setCustomName(original.getCustomName());
            MobUtil.setBaseAttribute(illusion, Attributes.SCALE, attributeValue(original, Attributes.SCALE));
            MobUtil.setBaseAttribute(illusion, Attributes.MOVEMENT_SPEED, attributeValue(original, Attributes.MOVEMENT_SPEED));
            MobUtil.setMaxHealth(illusion, 1.0);
            double angle = level.random.nextDouble() * Math.PI * 2.0;
            illusion.moveTo(original.getX() + Math.cos(angle) * 1.5, original.getY(), original.getZ() + Math.sin(angle) * 1.5,
                    original.getYRot(), 0.0f);
            if (original.getTarget() != null) {
                illusion.setTarget(original.getTarget());
            }
            level.addFreshEntity(illusion);
        }
        level.sendParticles(ParticleTypes.POOF, original.getX(), original.getY() + 1.0, original.getZ(), 25, 0.8, 0.6, 0.8, 0.02);
        playSound(level, original.blockPosition(), SoundEvents.ILLUSIONER_MIRROR_MOVE, 1.0f, 1.0f);
    }

    /** 影分身: まわりの影が少なければ 3体ふやす。 */
    private static void spawnShadowsIfFew(ServerLevel level, Zombie zombie) {
        int shadows = level.getEntitiesOfClass(Zombie.class, zombie.getBoundingBox().inflate(16.0), ZombieAbilities::isIllusion).size();
        if (shadows < 6) {
            spawnIllusions(level, zombie, 3);
        }
    }

    /** 能力の いまの元の値。 */
    private static double attributeValue(Zombie zombie, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute) {
        var instance = zombie.getAttribute(attribute);
        return instance != null ? instance.getBaseValue() : 1.0;
    }

    // ---------------- 隕石・竜巻・地ならし ----------------

    /** 相手の足もとに 隕石をよぶ（赤い輪が出て、少しあとに落ちる）。 */
    private static void callMeteor(ServerLevel level, Zombie zombie, LivingEntity target) {
        Vec3 impactPos = target.position();
        drawRing(level, impactPos.add(0, 0.1, 0), 2.5, ParticleTypes.FLAME, 24);
        playSound(level, target.blockPosition(), SoundEvents.BLAZE_SHOOT, 1.5f, 0.5f);
        pendingMeteors.add(new PendingMeteor(level, impactPos, level.getGameTime() + METEOR_FALL_TICKS, zombie));
    }

    /** 毎tick: 落ちてくる隕石を動かして、時間になったら 着弾させる。 */
    public static void tickPendingMeteors(MinecraftServer server) {
        if (pendingMeteors.isEmpty()) {
            return;
        }
        Iterator<PendingMeteor> iterator = pendingMeteors.iterator();
        while (iterator.hasNext()) {
            PendingMeteor meteor = iterator.next();
            ServerLevel level = meteor.level();
            long ticksLeft = meteor.impactTime() - level.getGameTime();
            Vec3 pos = meteor.impactPos();
            if (ticksLeft > 0) {
                // 空から 火の玉が おりてくる
                double height = ticksLeft * 1.0;
                level.sendParticles(ParticleTypes.FLAME, pos.x, pos.y + height, pos.z, 12, 0.4, 0.4, 0.4, 0.02);
                level.sendParticles(ParticleTypes.LARGE_SMOKE, pos.x, pos.y + height + 1.0, pos.z, 4, 0.3, 0.3, 0.3, 0.01);
                continue;
            }
            // 着弾（ブロックはこわさない）
            level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, pos.x, pos.y, pos.z, 1, 0, 0, 0, 0);
            level.sendParticles(ParticleTypes.LAVA, pos.x, pos.y + 0.5, pos.z, 30, 1.5, 0.5, 1.5, 0.1);
            level.explode(meteor.owner(), pos.x, pos.y, pos.z, 3.0f, Level.ExplosionInteraction.NONE);
            iterator.remove();
        }
    }

    /** 竜巻: まわりの人を ぐるぐる まきこんで 引きよせる。 */
    private static void spinTornado(ServerLevel level, Zombie zombie) {
        // 雲のうず
        for (int i = 0; i < 30; i++) {
            double angle = i * 0.6 + zombie.tickCount * 0.2;
            double radius = 0.8 + i * 0.12;
            level.sendParticles(ParticleTypes.CLOUD, zombie.getX() + Math.cos(angle) * radius, zombie.getY() + i * 0.2,
                    zombie.getZ() + Math.sin(angle) * radius, 1, 0, 0, 0, 0);
        }
        playSound(level, zombie.blockPosition(), SoundEvents.BREEZE_WIND_CHARGE_BURST.value(), 1.2f, 0.6f);

        // まきこむ（ゾンビ以外）
        for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, zombie.getBoundingBox().inflate(9.0),
                entity -> !(entity instanceof Zombie) && entity.isAlive())) {
            Vec3 toCenter = zombie.position().subtract(victim.position());
            Vec3 inward = new Vec3(toCenter.x, 0, toCenter.z).normalize();
            Vec3 around = new Vec3(-inward.z, 0, inward.x);
            victim.setDeltaMovement(inward.scale(0.35).add(around.scale(0.7)).add(0, 0.45, 0));
            victim.hurtMarked = true;
        }
    }

    /** 超巨大の 地ならし（ずしん！ まわりを ふきとばす）。 */
    private static void stompGround(ServerLevel level, Zombie zombie) {
        shockwave(level, zombie, 4.0 + zombie.getBbWidth(), 7.0f, 0.7);
    }

    /** まわりに しょうげき波（ダメージと ふきとばし）。 */
    private static void shockwave(ServerLevel level, Zombie zombie, double radius, float damage, double knockUp) {
        drawRing(level, zombie.position().add(0, 0.2, 0), radius * 0.5, ParticleTypes.EXPLOSION, 8);
        drawRing(level, zombie.position().add(0, 0.2, 0), radius, ParticleTypes.CAMPFIRE_COSY_SMOKE, 32);
        level.sendParticles(ParticleTypes.DUST_PLUME, zombie.getX(), zombie.getY() + 0.1, zombie.getZ(), 40, radius * 0.4, 0.1, radius * 0.4, 0.05);
        playSound(level, zombie.blockPosition(), SoundEvents.GENERIC_EXPLODE.value(), 1.2f, 0.5f);

        for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, zombie.getBoundingBox().inflate(radius),
                entity -> !(entity instanceof Zombie) && entity.isAlive())) {
            victim.hurt(level.damageSources().mobAttack(zombie), damage);
            Vec3 away = victim.position().subtract(zombie.position());
            Vec3 push = new Vec3(away.x, 0, away.z).normalize().scale(1.1);
            victim.setDeltaMovement(push.x, knockUp, push.z);
            victim.hurtMarked = true;
        }
    }

    // ---------------- 召喚・瞬間移動・雷・引きよせ ----------------

    /** まわりに なかまのゾンビを呼ぶ。 */
    private static void summonHelpers(ServerLevel level, Zombie zombie, int count) {
        int nearCount = level.getEntitiesOfClass(Zombie.class, zombie.getBoundingBox().inflate(16.0)).size();
        if (nearCount > 30) {
            return;
        }
        for (int i = 0; i < count; i++) {
            Zombie helper = ZombieSpawner.spawnZombieCloseTo(level, zombie.blockPosition(), 2, 5, null);
            if (helper != null && zombie.getTarget() != null) {
                helper.setTarget(zombie.getTarget());
            }
        }
        level.sendParticles(ParticleTypes.SOUL, zombie.getX(), zombie.getY() + 1.0, zombie.getZ(), 20, 1.0, 1.0, 1.0, 0.02);
        playSound(level, zombie.blockPosition(), SoundEvents.EVOKER_PREPARE_SUMMON, 1.0f, 0.7f);
    }

    /** 遠くにいる相手のそばへ 瞬間移動する。 */
    private static void teleportNearTarget(ServerLevel level, Zombie zombie, LivingEntity target) {
        if (zombie.distanceTo(target) < 8.0f || zombie.distanceTo(target) > 40.0f) {
            return;
        }
        BlockPos landing = ZombieSpawner.findStandPosAround(level, target.blockPosition(), 2, 4);
        if (landing == null) {
            return;
        }
        level.sendParticles(ParticleTypes.PORTAL, zombie.getX(), zombie.getY() + 1.0, zombie.getZ(), 30, 0.5, 1.0, 0.5, 0.1);
        zombie.teleportTo(landing.getX() + 0.5, landing.getY(), landing.getZ() + 0.5);
        playSound(level, landing, SoundEvents.ENDERMAN_TELEPORT, 1.0f, 0.8f);
    }

    /** 相手に雷を落とす。 */
    private static void strikeLightning(ServerLevel level, LivingEntity target) {
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
        if (bolt == null) {
            return;
        }
        bolt.moveTo(target.getX(), target.getY(), target.getZ());
        level.addFreshEntity(bolt);
    }

    /** 相手を ぐいっと引きよせる（くさりのような線が見える）。 */
    private static void pullTarget(ServerLevel level, Zombie zombie, LivingEntity target) {
        drawLine(level, zombie.getEyePosition(), target.getEyePosition(), ParticleTypes.ENCHANTED_HIT);
        Vec3 toZombie = zombie.position().subtract(target.position()).normalize().scale(0.9);
        target.setDeltaMovement(target.getDeltaMovement().add(toZombie.x, 0.35, toZombie.z));
        target.hurtMarked = true;
        playSound(level, target.blockPosition(), SoundEvents.FISHING_BOBBER_RETRIEVE, 1.0f, 0.6f);
    }

    // ---------------- 見た目の道具 ----------------

    /** 2点のあいだに つぶつぶの線を引く。 */
    private static void drawLine(ServerLevel level, Vec3 from, Vec3 to, ParticleOptions particle) {
        Vec3 step = to.subtract(from);
        int points = (int) Math.max(4, step.length() * 3);
        for (int i = 0; i <= points; i++) {
            Vec3 point = from.add(step.scale((double) i / points));
            level.sendParticles(particle, point.x, point.y, point.z, 1, 0, 0, 0, 0);
        }
    }

    /** 地面に つぶつぶの輪を えがく。 */
    private static void drawRing(ServerLevel level, Vec3 center, double radius, ParticleOptions particle, int points) {
        for (int i = 0; i < points; i++) {
            double angle = Math.PI * 2.0 * i / points;
            level.sendParticles(particle, center.x + Math.cos(angle) * radius, center.y, center.z + Math.sin(angle) * radius, 1, 0, 0.05, 0, 0);
        }
    }

    /** まわりに聞こえる音を鳴らす。 */
    private static void playSound(ServerLevel level, BlockPos pos, SoundEvent sound, float volume, float pitch) {
        level.playSound(null, pos, sound, SoundSource.HOSTILE, volume, pitch);
    }

    /** 記録をまっさらにする（ワールドを開きなおしたとき）。 */
    public static void reset() {
        pendingMeteors.clear();
    }
}
