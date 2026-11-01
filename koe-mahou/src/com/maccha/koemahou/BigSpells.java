package com.maccha.koemahou;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.maccha.koemahou.SpellEffects.aimPoint;
import static com.maccha.koemahou.SpellEffects.explodeSafely;
import static com.maccha.koemahou.SpellEffects.hurt;
import static com.maccha.koemahou.SpellEffects.later;
import static com.maccha.koemahou.SpellEffects.mobsAround;
import static com.maccha.koemahou.SpellEffects.playSound;

/**
 * 大魔法（長い詠唱。何段階も動く）。モブにだけ当たり、プレイヤーには当たらない
 */
public class BigSpells {

    // 大魔法がとどく距離（ブロック）
    static final double RANGE = 64.0;
    // いまクロノスタシスで止まっているモブ（2回かさねてかけたときに、もとにもどせなくならないように）
    static final java.util.Set<LivingEntity> chronoFrozen = new java.util.HashSet<>();

    // ===================== ブラックホール =====================

    /** ブラックホール：黒い球が生まれて、まわりのモブを5秒吸いこみつづけ、ちぢんでから大爆散する */
    static void blackHole(ServerLevel level, ServerPlayer player) {
        Vec3 center = aimPoint(level, player, RANGE).add(0, 3, 0);
        // 吸いこまれたモブ（爆散のときに まとめてダメージ）
        List<LivingEntity> swallowed = new ArrayList<>();

        // 1. 生まれる（1秒）：まわりから闇が集まってくる
        playSound(level, center, SoundEvents.WARDEN_EMERGE, 3.0f, 0.6f);
        for (int tick = 0; tick < 20; tick++) {
            double size = tick / 20.0 * 1.5;
            later(tick, () -> {
                darkSphere(level, center, size);
                level.sendParticles(ParticleTypes.REVERSE_PORTAL, center.x, center.y, center.z, 20, 6, 6, 6, 0.0);
            });
        }

        // 2. 吸いこむ（5秒）：半径18のモブを まんなかへ引っぱる。近いほど強い
        for (int tick = 20; tick < 120; tick++) {
            int age = tick;
            later(tick, () -> {
                darkSphere(level, center, 1.5 + Math.sin(age * 0.4) * 0.2);
                spiral(level, center, age);
                for (LivingEntity mob : mobsAround(level, player, center, 18.0)) {
                    Vec3 toCenter = center.subtract(mob.position().add(0, mob.getBbHeight() / 2, 0));
                    double distance = toCenter.length();
                    double strength = Math.min(1.2, 0.25 + 3.0 / Math.max(distance, 1.0));
                    mob.setDeltaMovement(toCenter.normalize().scale(strength));
                    mob.hurtMarked = true;
                    mob.resetFallDistance();
                    // まんなかの近くでは ちぎれるようにダメージ
                    if (distance < 2.5 && age % 5 == 0) hurt(level, player, mob, 3.0f);
                    if (!swallowed.contains(mob)) swallowed.add(mob);
                }
                if (age % 20 == 0) playSound(level, center, SoundEvents.PORTAL_AMBIENT, 3.0f, 0.5f);
            });
        }

        // 3. ちぢむ（0.5秒）：しーんと静かになって、小さくなる
        for (int tick = 120; tick < 130; tick++) {
            double size = 1.5 * (130 - tick) / 10.0;
            later(tick, () -> {
                darkSphere(level, center, size);
                for (LivingEntity mob : swallowed) {
                    if (!mob.isAlive()) continue;
                    mob.setDeltaMovement(center.subtract(mob.position()).scale(0.5));
                    mob.hurtMarked = true;
                }
            });
        }
        later(120, () -> playSound(level, center, SoundEvents.BEACON_DEACTIVATE, 3.0f, 0.5f));

        // 4. 爆散：大爆発して、モブを外へ ふきとばす
        later(132, () -> {
            explodeSafely(level, player, center, 7.0f);
            level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, center.x, center.y, center.z, 3, 2, 2, 2, 0);
            level.sendParticles(ParticleTypes.END_ROD, center.x, center.y, center.z, 300, 0.5, 0.5, 0.5, 1.2);
            level.sendParticles(ParticleTypes.SQUID_INK, center.x, center.y, center.z, 200, 1, 1, 1, 0.8);
            level.sendParticles(ParticleTypes.SONIC_BOOM, center.x, center.y, center.z, 1, 0, 0, 0, 0);
            playSound(level, center, SoundEvents.WARDEN_SONIC_BOOM, 4.0f, 0.6f);
            playSound(level, center, SoundEvents.GENERIC_EXPLODE.value(), 4.0f, 0.5f);
            List<LivingEntity> targets = new ArrayList<>(mobsAround(level, player, center, 12.0));
            for (LivingEntity mob : swallowed) if (!targets.contains(mob) && mob.isAlive()) targets.add(mob);
            for (LivingEntity mob : targets) {
                hurt(level, player, mob, 40.0f);
                Vec3 push = mob.position().subtract(center).normalize().scale(3.0);
                mob.setDeltaMovement(push.x, 1.2, push.z);
                mob.hurtMarked = true;
            }
        });
    }

    /** 黒い球（size はブロック）をパーティクルで描く */
    static void darkSphere(ServerLevel level, Vec3 center, double size) {
        int count = (int) (20 + size * 25);
        level.sendParticles(ParticleTypes.SQUID_INK, center.x, center.y, center.z, count, size * 0.4, size * 0.4, size * 0.4, 0.0);
        level.sendParticles(new DustParticleOptions(0x2a0040, 2.0f), center.x, center.y, center.z, count / 2, size * 0.5, size * 0.5, size * 0.5, 0.0);
    }

    /** 黒い球のまわりを回る、紫のうず */
    static void spiral(ServerLevel level, Vec3 center, int age) {
        for (int arm = 0; arm < 3; arm++) {
            for (int step = 0; step < 6; step++) {
                double radius = 2.5 + step * 1.2;
                double angle = age * 0.35 + arm * Math.PI * 2 / 3 + step * 0.5;
                level.sendParticles(ParticleTypes.PORTAL, center.x + Math.cos(angle) * radius, center.y + (step - 3) * 0.1,
                        center.z + Math.sin(angle) * radius, 1, 0, 0, 0, 0);
            }
        }
    }

    // ===================== メテオストーム =====================

    /** メテオストーム：空に魔法陣が出て、隕石が10こ降り、さいごに巨大な隕石が落ちる */
    static void meteorStorm(ServerLevel level, ServerPlayer player) {
        Vec3 ground = aimPoint(level, player, RANGE);
        Vec3 sky = ground.add(0, 30, 0);

        // 1. 空の魔法陣（1.5秒）
        playSound(level, ground, SoundEvents.END_PORTAL_SPAWN, 3.0f, 0.8f);
        for (int tick = 0; tick < 30; tick += 2) {
            int age = tick;
            later(tick, () -> magicCircle(level, sky, 10.0, age, ParticleTypes.FLAME));
        }

        // 2. 隕石が10こ、0.3秒ごとに ばらばらの場所へ
        for (int index = 0; index < 10; index++) {
            double angle = level.getRandom().nextDouble() * Math.PI * 2;
            double radius = 2 + level.getRandom().nextDouble() * 9;
            Vec3 spot = ground.add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
            Vec3 start = sky.add(level.getRandom().nextDouble() * 6 - 3, 0, level.getRandom().nextDouble() * 6 - 3);
            fallingMeteor(level, player, start, spot, 30 + index * 6, 12, 1.0, 3.5f, 14.0f, 4.0);
        }

        // 3. さいごに巨大な隕石（ゆっくり落ちてきて、超大爆発）
        later(95, () -> playSound(level, ground, SoundEvents.WITHER_SPAWN, 3.0f, 0.6f));
        fallingMeteor(level, player, sky.add(0, 10, 0), ground, 95, 30, 3.0, 8.0f, 45.0f, 10.0);
    }

    /** 1この隕石：startTick から ticks かけて from から to へ落ちて、爆発する */
    static void fallingMeteor(ServerLevel level, ServerPlayer player, Vec3 from, Vec3 to, int startTick, int ticks,
                              double size, float power, float damage, double radius) {
        for (int tick = 0; tick <= ticks; tick++) {
            double progress = (double) tick / ticks;
            Vec3 point = from.add(to.subtract(from).scale(progress * progress));
            later(startTick + tick, () -> {
                level.sendParticles(ParticleTypes.FLAME, point.x, point.y, point.z, (int) (15 * size), size * 0.4, size * 0.4, size * 0.4, 0.02);
                level.sendParticles(ParticleTypes.LAVA, point.x, point.y, point.z, (int) (3 * size), size * 0.3, size * 0.3, size * 0.3, 0);
                level.sendParticles(ParticleTypes.LARGE_SMOKE, point.x, point.y + size, point.z, (int) (6 * size), size * 0.3, size * 0.6, size * 0.3, 0.01);
            });
        }
        later(startTick + ticks, () -> {
            explodeSafely(level, player, to, power);
            level.sendParticles(ParticleTypes.LAVA, to.x, to.y + 0.5, to.z, (int) (30 * size), radius * 0.3, 0.5, radius * 0.3, 0.2);
            for (LivingEntity mob : mobsAround(level, player, to, radius)) {
                hurt(level, player, mob, damage);
                mob.igniteForSeconds(8);
            }
        });
    }

    /** 地面と平行な、まわる魔法陣（輪2つと星形） */
    static void magicCircle(ServerLevel level, Vec3 center, double radius, int age, ParticleOptions particle) {
        double turn = age * 0.08;
        for (int index = 0; index < 60; index++) {
            double angle = Math.PI * 2 * index / 60 + turn;
            level.sendParticles(particle, center.x + Math.cos(angle) * radius, center.y, center.z + Math.sin(angle) * radius, 1, 0, 0, 0, 0);
            level.sendParticles(particle, center.x + Math.cos(-angle) * radius * 0.6, center.y, center.z + Math.sin(-angle) * radius * 0.6, 1, 0, 0, 0, 0);
        }
        // 星形（5つの とがり）
        for (int point = 0; point < 5; point++) {
            double angleA = turn + Math.PI * 2 * point / 5;
            double angleB = turn + Math.PI * 2 * (point + 2) / 5;
            Vec3 a = center.add(Math.cos(angleA) * radius, 0, Math.sin(angleA) * radius);
            Vec3 b = center.add(Math.cos(angleB) * radius, 0, Math.sin(angleB) * radius);
            for (int step = 0; step <= 12; step++) {
                Vec3 dot = a.add(b.subtract(a).scale(step / 12.0));
                level.sendParticles(particle, dot.x, dot.y, dot.z, 1, 0, 0, 0, 0);
            }
        }
    }

    // ===================== ジャッジメント =====================

    /** ジャッジメント：雷の檻にモブを閉じこめて宙にうかせ、雷を何度も落とし、さいごに天から光の柱 */
    static void judgment(ServerLevel level, ServerPlayer player) {
        Vec3 center = aimPoint(level, player, RANGE);
        double cage = 8.0;
        List<LivingEntity> prisoners = new ArrayList<>(mobsAround(level, player, center, cage));
        playSound(level, center, SoundEvents.TRIDENT_THUNDER.value(), 3.0f, 0.7f);

        // 1. 檻（5秒）：雷の柱の輪。中のモブは うかんで、外へ出られない
        for (int tick = 0; tick < 100; tick++) {
            int age = tick;
            later(tick, () -> {
                if (age % 2 == 0) {
                    for (int bar = 0; bar < 16; bar++) {
                        double angle = Math.PI * 2 * bar / 16 + age * 0.03;
                        double x = center.x + Math.cos(angle) * cage;
                        double z = center.z + Math.sin(angle) * cage;
                        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, x, center.y + 3, z, 6, 0.05, 3, 0.05, 0);
                    }
                    magicCircle(level, center.add(0, 0.2, 0), cage, age, ParticleTypes.ELECTRIC_SPARK);
                }
                for (LivingEntity mob : prisoners) {
                    if (!mob.isAlive()) continue;
                    // 地面から4ブロックの高さで ふわふわ。檻の外に出たら もどす
                    Vec3 hover = new Vec3(mob.getX(), center.y + 4, mob.getZ());
                    Vec3 flat = new Vec3(mob.getX() - center.x, 0, mob.getZ() - center.z);
                    if (flat.length() > cage - 1) hover = center.add(flat.normalize().scale(cage - 1.5)).add(0, 4, 0);
                    mob.setDeltaMovement(hover.subtract(mob.position()).scale(0.3));
                    mob.hurtMarked = true;
                    mob.resetFallDistance();
                }
                // 0.4秒ごとに、だれかに雷
                if (age % 8 == 4) {
                    List<LivingEntity> alive = prisoners.stream().filter(LivingEntity::isAlive).toList();
                    Vec3 spot = alive.isEmpty()
                            ? center.add(level.getRandom().nextDouble() * 10 - 5, 0, level.getRandom().nextDouble() * 10 - 5)
                            : alive.get(level.getRandom().nextInt(alive.size())).position();
                    bolt(level, spot);
                    for (LivingEntity mob : alive) {
                        if (mob.position().distanceTo(spot) < 3) hurt(level, player, mob, 8.0f);
                    }
                }
            });
        }

        // 2. 裁き：天から光の柱、まんなかに雷が5本、中のモブ全部に大ダメージ
        later(100, () -> {
            for (double height = 0; height < 40; height += 0.5) {
                level.sendParticles(ParticleTypes.END_ROD, center.x, center.y + height, center.z, 6, 1.2, 0.1, 1.2, 0.0);
            }
            level.sendParticles(ParticleTypes.FLASH, center.x, center.y + 2, center.z, 3, 0, 0, 0, 0);
            for (int index = 0; index < 5; index++) bolt(level, center.add(index - 2, 0, (index % 2) * 2 - 1));
            playSound(level, center, SoundEvents.LIGHTNING_BOLT_THUNDER, 5.0f, 0.5f);
            List<LivingEntity> targets = new ArrayList<>(mobsAround(level, player, center, cage));
            for (LivingEntity mob : prisoners) if (!targets.contains(mob) && mob.isAlive()) targets.add(mob);
            for (LivingEntity mob : targets) {
                hurt(level, player, mob, 35.0f);
                mob.setDeltaMovement(0, -2.5, 0);
                mob.hurtMarked = true;
                mob.igniteForSeconds(5);
            }
        });
    }

    /** 見た目だけの雷を1本落とす（ダメージは魔法のほうで出す） */
    static void bolt(ServerLevel level, Vec3 spot) {
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level, EntitySpawnReason.TRIGGERED);
        if (bolt == null) return;
        bolt.setPos(spot.x, spot.y, spot.z);
        bolt.setVisualOnly(true);
        level.addFreshEntity(bolt);
    }

    // ===================== サンクチュアリ =====================

    /** サンクチュアリ：10秒の光の結界。中の仲間は回復しつづけ、入ってきたモブは はじかれる */
    static void sanctuary(ServerLevel level, ServerPlayer player) {
        Vec3 center = player.position();
        double radius = 9.0;
        playSound(level, center, SoundEvents.BEACON_ACTIVATE, 3.0f, 1.2f);
        playSound(level, center, SoundEvents.TOTEM_USE, 1.5f, 1.0f);

        for (int tick = 0; tick < 200; tick++) {
            int age = tick;
            later(tick, () -> {
                // 光のドーム
                if (age % 3 == 0) {
                    magicCircle(level, center.add(0, 0.1, 0), radius, age, ParticleTypes.END_ROD);
                    for (int index = 0; index < 20; index++) {
                        double angle = level.getRandom().nextDouble() * Math.PI * 2;
                        double up = level.getRandom().nextDouble() * Math.PI / 2;
                        level.sendParticles(ParticleTypes.WAX_OFF, center.x + Math.cos(angle) * Math.cos(up) * radius,
                                center.y + Math.sin(up) * radius, center.z + Math.sin(angle) * Math.cos(up) * radius, 1, 0, 0, 0, 0);
                    }
                }
                // 中の仲間：1秒ごとに回復と守り
                if (age % 20 == 0) {
                    for (Player friend : level.getEntitiesOfClass(Player.class, new AABB(center, center).inflate(radius), Player::isAlive)) {
                        friend.heal(6.0f);
                        friend.clearFire();
                        friend.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 30, 2));
                        friend.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 30, 1));
                        level.sendParticles(ParticleTypes.HEART, friend.getX(), friend.getY() + 2, friend.getZ(), 2, 0.3, 0.2, 0.3, 0);
                    }
                    playSound(level, center, SoundEvents.AMETHYST_BLOCK_RESONATE, 2.0f, 1.5f);
                }
                // 中のモブ：はじきとばして、少しダメージ
                if (age % 5 == 0) {
                    for (LivingEntity mob : mobsAround(level, player, center, radius)) {
                        Vec3 push = mob.position().subtract(center).normalize().scale(1.6);
                        mob.setDeltaMovement(push.x, 0.5, push.z);
                        mob.hurtMarked = true;
                        hurt(level, player, mob, 4.0f);
                        level.sendParticles(ParticleTypes.END_ROD, mob.getX(), mob.getY() + 1, mob.getZ(), 10, 0.3, 0.5, 0.3, 0.1);
                    }
                }
            });
        }

        // さいご：光がはじけて、仲間に金のハート（衝撃吸収）
        later(200, () -> {
            for (Player friend : level.getEntitiesOfClass(Player.class, new AABB(center, center).inflate(radius), Player::isAlive)) {
                friend.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 1200, 3));
                friend.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 200, 1));
            }
            level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, center.x, center.y + 1, center.z, 200, 2, 2, 2, 0.6);
            playSound(level, center, SoundEvents.PLAYER_LEVELUP, 2.0f, 0.8f);
        });
    }

    // ===================== クロノスタシス =====================

    /** クロノスタシス：まわりのモブの時間を6秒止める。止まっていたあいだに受けたダメージが、時間がもどったとき2倍で返ってくる */
    static void chronostasis(ServerLevel level, ServerPlayer player) {
        Vec3 center = player.position();
        double radius = 16.0;
        List<LivingEntity> frozen = new ArrayList<>(mobsAround(level, player, center, radius));
        frozen.removeIf(chronoFrozen::contains);
        chronoFrozen.addAll(frozen);
        Map<LivingEntity, Vec3> frozenAt = new HashMap<>();
        Map<LivingEntity, Float> healthAtStart = new HashMap<>();
        Map<Mob, Boolean> hadNoAi = new HashMap<>();
        for (LivingEntity mob : frozen) {
            frozenAt.put(mob, mob.position());
            healthAtStart.put(mob, mob.getHealth());
            if (mob instanceof Mob thinking) {
                hadNoAi.put(thinking, thinking.isNoAi());
                thinking.setNoAi(true);
            }
        }
        playSound(level, center, SoundEvents.BELL_BLOCK, 3.0f, 0.5f);
        playSound(level, center, SoundEvents.BEACON_POWER_SELECT, 3.0f, 0.5f);

        // 1. 止まった時間（6秒）：モブは同じ場所から動けない。時計の針がまわる
        for (int tick = 0; tick < 120; tick++) {
            int age = tick;
            later(tick, () -> {
                for (LivingEntity mob : frozen) {
                    if (!mob.isAlive()) continue;
                    Vec3 at = frozenAt.get(mob);
                    mob.setDeltaMovement(Vec3.ZERO);
                    mob.teleportTo(at.x, at.y, at.z);
                    mob.hurtMarked = true;
                    if (age % 4 == 0) {
                        level.sendParticles(ParticleTypes.ENCHANT, mob.getX(), mob.getY() + 1, mob.getZ(), 6, 0.4, 0.6, 0.4, 0.5);
                    }
                }
                if (age % 3 == 0) clockFace(level, center.add(0, 0.2, 0), radius, age);
                if (age % 20 == 0) playSound(level, center, SoundEvents.NOTE_BLOCK_HAT.value(), 2.0f, 0.5f);
            });
        }

        // 2. 時間がもどる：受けたダメージの2倍＋10が いっきに返ってくる
        later(120, () -> {
            playSound(level, center, SoundEvents.BELL_RESONATE, 3.0f, 1.0f);
            for (LivingEntity mob : frozen) {
                chronoFrozen.remove(mob);
                if (mob instanceof Mob thinking) thinking.setNoAi(hadNoAi.getOrDefault(thinking, false));
                if (!mob.isAlive()) continue;
                float taken = Math.max(0f, healthAtStart.get(mob) - mob.getHealth());
                hurt(level, player, mob, taken * 2f + 10f);
                level.sendParticles(ParticleTypes.CRIT, mob.getX(), mob.getY() + 1, mob.getZ(), 40, 0.4, 0.6, 0.4, 0.6);
                level.sendParticles(ParticleTypes.FLASH, mob.getX(), mob.getY() + 1, mob.getZ(), 1, 0, 0, 0, 0);
            }
        });
    }

    /** 地面の時計（外の輪と、ちがう速さでまわる長い針・短い針） */
    static void clockFace(ServerLevel level, Vec3 center, double radius, int age) {
        for (int index = 0; index < 72; index++) {
            double angle = Math.PI * 2 * index / 72;
            ParticleOptions dot = index % 6 == 0 ? ParticleTypes.END_ROD : ParticleTypes.ENCHANT;
            level.sendParticles(dot, center.x + Math.cos(angle) * radius, center.y, center.z + Math.sin(angle) * radius, 1, 0, 0, 0, 0);
        }
        double longHand = age * 0.15;
        double shortHand = age * 0.02;
        for (double step = 0; step < radius; step += 0.5) {
            level.sendParticles(ParticleTypes.END_ROD, center.x + Math.cos(longHand) * step, center.y, center.z + Math.sin(longHand) * step, 1, 0, 0, 0, 0);
            if (step < radius * 0.6) {
                level.sendParticles(ParticleTypes.END_ROD, center.x + Math.cos(shortHand) * step, center.y, center.z + Math.sin(shortHand) * step, 1, 0, 0, 0, 0);
            }
        }
    }
}
