package com.maccha.zombiepanic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.List;

import static com.maccha.zombiepanic.ZombieAbilities.Ability;

/**
 * ゾンビだけが持つ、あとから足した能力（とげ・光線・氷の嵐・時間停止 など）。
 * 毎秒1回、いちばん近くのプレイヤーや まわりのゾンビに向けて使う。
 */
public final class NewAbilities {

    private NewAbilities() {
    }

    // ---------------- なぐったとき ----------------

    /** なぐった相手につく おまけの効果。 */
    public static void onZombieHits(Zombie attacker, LivingEntity target) {
        if (ZombieAbilities.has(attacker, Ability.WEAKENER)) {
            MobUtil.addTimedEffect(target, MobEffects.WEAKNESS, 160, 1);
        }
        if (ZombieAbilities.has(attacker, Ability.BLINDER)) {
            MobUtil.addTimedEffect(target, MobEffects.BLINDNESS, 60, 0);
        }
        if (ZombieAbilities.has(attacker, Ability.DIZZY)) {
            MobUtil.addTimedEffect(target, MobEffects.NAUSEA, 160, 0);
        }
        if (ZombieAbilities.has(attacker, Ability.KNOCKER)) {
            Vec3 away = target.position().subtract(attacker.position()).multiply(1, 0, 1).normalize().scale(1.6);
            target.setDeltaMovement(target.getDeltaMovement().add(away.x, 0.5, away.z));
            MobUtil.syncMotion(target);
        }
        if (ZombieAbilities.has(attacker, Ability.THIEF)) {
            // 食べものゲージを うばって、自分は体力がもどる
            MobUtil.addTimedEffect(target, MobEffects.HUNGER, 200, 2);
            attacker.heal(2.0f);
        }
        if (ZombieAbilities.has(attacker, Ability.DRAIN)) {
            attacker.heal(3.0f);
        }
    }

    // ---------------- なぐられたとき ----------------

    /** なぐられたとき、とげ・反射で なぐった人に ダメージが返る。 */
    public static void onZombieHurt(ServerLevel level, Zombie victim, Entity attacker, float amount) {
        if (!(attacker instanceof LivingEntity livingAttacker)) {
            return;
        }
        if (ZombieAbilities.has(victim, Ability.THORNS)) {
            livingAttacker.hurt(level.damageSources().thorns(victim), 2.0f);
        }
        if (ZombieAbilities.has(victim, Ability.MIRROR)) {
            livingAttacker.hurt(level.damageSources().mobAttack(victim), Math.max(1.0f, amount * 0.3f));
            level.sendParticles(ParticleTypes.ENCHANTED_HIT, victim.getX(), victim.getY() + 1.0, victim.getZ(), 8, 0.4, 0.6, 0.4, 0.1);
        }
    }

    // ---------------- 相手がいなくても使う ----------------

    /** 自分や なかまのための技（癒し・さけび・バリア・狂乱・ネクロ）。 */
    public static void useSelfMoves(ServerLevel level, Zombie zombie, int time) {
        if (ZombieAbilities.has(zombie, Ability.HEALER) && time % 60 == 0) {
            for (Zombie ally : level.getEntitiesOfClass(Zombie.class, zombie.getBoundingBox().inflate(8.0))) {
                ally.heal(4.0f);
            }
            level.sendParticles(ParticleTypes.HEART, zombie.getX(), zombie.getY() + 2.0, zombie.getZ(), 5, 0.5, 0.3, 0.5, 0.0);
        }
        boolean hasEnemy = zombie.getTarget() != null;
        if (ZombieAbilities.has(zombie, Ability.SCREAMER) && time % 200 == 0 && hasEnemy) {
            for (Zombie ally : level.getEntitiesOfClass(Zombie.class, zombie.getBoundingBox().inflate(16.0))) {
                MobUtil.addTimedEffect(ally, MobEffects.SPEED, 160, 1);
                MobUtil.addTimedEffect(ally, MobEffects.STRENGTH, 160, 0);
            }
            sound(level, zombie.blockPosition(), SoundEvents.ZOMBIE_VILLAGER_CONVERTED, 2.0f, 0.5f);
        }
        if (ZombieAbilities.has(zombie, Ability.BARRIER) && time % 240 == 0 && hasEnemy) {
            MobUtil.addTimedEffect(zombie, MobEffects.RESISTANCE, 80, 4);
            AbilityMoves.drawRing(level, zombie.position().add(0, 1.0, 0), 1.2, ParticleTypes.END_ROD, 24);
            sound(level, zombie.blockPosition(), SoundEvents.BEACON_ACTIVATE, 1.0f, 1.5f);
        }
        if (ZombieAbilities.has(zombie, Ability.BERSERK) && zombie.getHealth() < zombie.getMaxHealth() * 0.5f) {
            MobUtil.addTimedEffect(zombie, MobEffects.STRENGTH, 60, 2);
            MobUtil.addTimedEffect(zombie, MobEffects.SPEED, 60, 2);
            level.sendParticles(ParticleTypes.ANGRY_VILLAGER, zombie.getX(), zombie.getY() + 2.0, zombie.getZ(), 2, 0.3, 0.2, 0.3, 0.0);
        }
        if (ZombieAbilities.has(zombie, Ability.NECRO) && time % 300 == 0 && hasEnemy) {
            int near = level.getEntitiesOfClass(Zombie.class, zombie.getBoundingBox().inflate(16.0)).size();
            for (int i = 0; i < 3 && near < 30; i++) {
                Zombie helper = ZombieSpawner.spawnZombieCloseTo(level, zombie.blockPosition(), 2, 5, null);
                if (helper != null) {
                    helper.setTarget(zombie.getTarget());
                }
            }
            level.sendParticles(ParticleTypes.SOUL, zombie.getX(), zombie.getY() + 1.0, zombie.getZ(), 25, 1.0, 1.0, 1.0, 0.03);
            sound(level, zombie.blockPosition(), SoundEvents.EVOKER_PREPARE_SUMMON, 1.0f, 0.5f);
        }
    }

    // ---------------- 相手がいるとき ----------------

    /** 相手（いちばん近い人）に向けて使う技。 */
    public static void useTargetMoves(ServerLevel level, Zombie zombie, LivingEntity target, int time) {
        double distance = zombie.distanceTo(target);
        boolean canSee = zombie.hasLineOfSight(target);

        if (ZombieAbilities.has(zombie, Ability.LEAPER) && time % 80 == 0 && distance > 3 && distance < 10 && canSee) {
            Vec3 dir = target.position().subtract(zombie.position()).normalize();
            zombie.setDeltaMovement(dir.x * 1.2, 0.6, dir.z * 1.2);
            MobUtil.syncMotion(zombie);
            sound(level, zombie.blockPosition(), SoundEvents.SLIME_JUMP, 1.0f, 0.6f);
        }
        if (ZombieAbilities.has(zombie, Ability.SPITTER) && time % 80 == 0 && distance > 3 && distance < 18 && canSee) {
            AbilityMoves.drawLine(level, zombie.getEyePosition(), target.getEyePosition(), ParticleTypes.ITEM_SLIME);
            target.hurt(level.damageSources().mobAttack(zombie), 3.0f);
            MobUtil.addTimedEffect(target, MobEffects.POISON, 80, 0);
            sound(level, zombie.blockPosition(), SoundEvents.LLAMA_SPIT, 1.2f, 0.7f);
        }
        if (ZombieAbilities.has(zombie, Ability.FIREBREATH) && time % 100 == 0 && distance < 8 && canSee) {
            AbilityMoves.drawLine(level, zombie.getEyePosition(), target.getEyePosition(), ParticleTypes.FLAME);
            target.igniteForSeconds(5);
            target.hurt(level.damageSources().mobAttack(zombie), 2.0f);
            sound(level, zombie.blockPosition(), SoundEvents.BLAZE_SHOOT, 1.2f, 0.6f);
        }
        if (ZombieAbilities.has(zombie, Ability.LASER) && time % 100 == 0 && distance < 22 && canSee) {
            AbilityMoves.drawLine(level, zombie.getEyePosition(), target.getEyePosition(), ParticleTypes.END_ROD);
            target.hurt(level.damageSources().mobAttack(zombie), 8.0f);
            sound(level, zombie.blockPosition(), SoundEvents.GUARDIAN_ATTACK, 1.5f, 1.2f);
        }
        if (ZombieAbilities.has(zombie, Ability.GRAVITY) && time % 120 == 0 && distance < 16) {
            MobUtil.addTimedEffect(target, MobEffects.LEVITATION, 40, 2);
            level.sendParticles(ParticleTypes.PORTAL, target.getX(), target.getY() + 1.0, target.getZ(), 30, 0.4, 0.8, 0.4, 0.3);
            sound(level, target.blockPosition(), SoundEvents.SHULKER_BULLET_HIT, 1.2f, 0.5f);
        }
        if (ZombieAbilities.has(zombie, Ability.FROSTNOVA) && time % 160 == 0 && distance < 9) {
            AbilityMoves.drawRing(level, zombie.position().add(0, 0.2, 0), 8.0, ParticleTypes.SNOWFLAKE, 48);
            for (Player player : level.getEntitiesOfClass(Player.class, zombie.getBoundingBox().inflate(8.0))) {
                MobUtil.addTimedEffect(player, MobEffects.SLOWNESS, 100, 2);
                player.setTicksFrozen(player.getTicksRequiredToFreeze() + 60);
            }
            sound(level, zombie.blockPosition(), SoundEvents.GLASS_BREAK, 1.5f, 0.6f);
        }
        if (ZombieAbilities.has(zombie, Ability.FIRESTORM) && time % 160 == 0 && distance < 24) {
            for (int i = 0; i < 4; i++) {
                Vec3 spot = target.position().add((level.getRandom().nextDouble() - 0.5) * 8, 0, (level.getRandom().nextDouble() - 0.5) * 8);
                AbilityMoves.callMeteorAt(level, spot, zombie);
            }
        }
        if (ZombieAbilities.has(zombie, Ability.DRAIN) && time % 80 == 0 && distance < 10 && canSee) {
            AbilityMoves.drawLine(level, target.getEyePosition(), zombie.getEyePosition(), ParticleTypes.DAMAGE_INDICATOR);
            target.hurt(level.damageSources().magic(), 4.0f);
            zombie.heal(4.0f);
        }
        if (ZombieAbilities.has(zombie, Ability.LAUNCHER) && time % 120 == 0 && distance < 5) {
            target.setDeltaMovement(target.getDeltaMovement().add(0, 1.3, 0));
            MobUtil.syncMotion(target);
            sound(level, target.blockPosition(), SoundEvents.GENERIC_EXPLODE.value(), 0.8f, 1.4f);
        }
        if (ZombieAbilities.has(zombie, Ability.DARKNESS) && time % 160 == 0 && distance < 24) {
            for (Player player : level.getEntitiesOfClass(Player.class, zombie.getBoundingBox().inflate(20.0))) {
                MobUtil.addTimedEffect(player, MobEffects.DARKNESS, 200, 0);
            }
            sound(level, zombie.blockPosition(), SoundEvents.WARDEN_HEARTBEAT, 1.5f, 0.5f);
        }
        if (ZombieAbilities.has(zombie, Ability.DOOMSDAY) && time % 400 == 0 && distance < 40) {
            doomsday(level, zombie);
        }
        if (ZombieAbilities.has(zombie, Ability.TIMESTOP) && time % 500 == 0 && distance < 20) {
            stopTime(level, zombie);
        }
    }

    /** 終末: まわりのプレイヤーすべての頭の上に 隕石と雷。 */
    private static void doomsday(ServerLevel level, Zombie zombie) {
        List<Player> players = level.getEntitiesOfClass(Player.class, zombie.getBoundingBox().inflate(40.0));
        for (Player player : players) {
            AbilityMoves.callMeteorAt(level, player.position(), zombie);
            AbilityMoves.callMeteorAt(level, player.position().add(3, 0, 0), zombie);
            AbilityMoves.callMeteorAt(level, player.position().add(-3, 0, 0), zombie);
        }
        sound(level, zombie.blockPosition(), SoundEvents.WITHER_SPAWN, 2.0f, 0.5f);
    }

    /** 時間停止: 近くのプレイヤーが 3秒だけ 動けなくなる。 */
    private static void stopTime(ServerLevel level, Zombie zombie) {
        for (Player player : level.getEntitiesOfClass(Player.class, zombie.getBoundingBox().inflate(15.0))) {
            MobUtil.addTimedEffect(player, MobEffects.SLOWNESS, 60, 6);
            MobUtil.addTimedEffect(player, MobEffects.MINING_FATIGUE, 60, 4);
            MobUtil.addTimedEffect(player, MobEffects.JUMP_BOOST, 60, 128);
            level.sendParticles(ParticleTypes.END_ROD, player.getX(), player.getY() + 1.0, player.getZ(), 20, 0.4, 0.8, 0.4, 0.02);
        }
        sound(level, zombie.blockPosition(), SoundEvents.BELL_BLOCK, 2.0f, 0.5f);
    }

    /** まわりに聞こえる音を鳴らす。 */
    private static void sound(ServerLevel level, BlockPos pos, SoundEvent sound, float volume, float pitch) {
        level.playSound(null, pos, sound, SoundSource.HOSTILE, volume, pitch);
    }
}
