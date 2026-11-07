package com.maccha.ikioiken;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 敵の殴り攻撃を「ためてから振る」攻撃に変えるクラス（ためている間によけたり、剣で受けたりできる）。
 */
public final class EnemyStrike {

    private EnemyStrike() {
    }

    /** ためている途中の敵の攻撃 1 つぶん。 */
    private record PendingStrike(Mob attacker, ServerPlayer target, int strikeTick) {
    }

    // 敵ごとの、ためている途中の攻撃
    private static final Map<Integer, PendingStrike> PENDING = new HashMap<>();

    // ため終わった攻撃を当てている最中なら true（このときは横取りしない）
    private static boolean strikingNow = false;

    // サーバーの時計（tick）
    private static int serverTick = 0;

    /** 敵がプレイヤーを殴ろうとしたら、その場では当てずに「ため」を始める。 */
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!SwingConfig.ENEMY_REAL_ATTACK || strikingNow) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        // ふつうの殴り攻撃（敵が自分の体で殴った）だけ変える
        DamageSource source = event.getSource();
        boolean isMeleeAttack = source.is(DamageTypes.MOB_ATTACK) || source.is(DamageTypes.MOB_ATTACK_NO_AGGRO);
        if (!isMeleeAttack || !(source.getDirectEntity() instanceof Mob attacker) || source.getEntity() != attacker) {
            return;
        }
        if (shouldKeepOriginalAttack(attacker)) {
            return;
        }

        // その場のダメージはなしにする
        event.setCanceled(true);

        // もうためている途中なら、新しくは始めない
        if (PENDING.containsKey(attacker.getId())) {
            return;
        }

        // ためを始める（音と火花で「来るぞ」と知らせる）
        PENDING.put(attacker.getId(), new PendingStrike(attacker, player, serverTick + SwingConfig.ENEMY_WINDUP_TICKS));
        showWindup(attacker);
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(attacker,
                new MotionPayload(attacker.getId(), MotionPayload.ENEMY_WINDUP, SwingConfig.ENEMY_WINDUP_TICKS, 0.0f));
    }

    /** ボスや、もともと攻撃の動きがある Mod の敵は、元の攻撃のままにする。 */
    private static boolean shouldKeepOriginalAttack(Mob attacker) {
        EntityType<?> type = attacker.getType();
        if (type.is(Tags.EntityTypes.BOSSES)) {
            return true;
        }
        String modId = EntityType.getKey(type).getNamespace();
        for (String skippedMod : SwingConfig.ENEMY_SKIP_MODS) {
            if (skippedMod.equals(modId)) {
                return true;
            }
        }
        return false;
    }

    /** ための合図を出す（低い風切り音と、敵の前に火花）。 */
    private static void showWindup(Mob attacker) {
        ServerLevel level = (ServerLevel) attacker.level();
        Vec3 front = attacker.getEyePosition().add(Vec3.directionFromRotation(0.0f, attacker.getYHeadRot()).scale(0.6));
        level.playSound(null, attacker.getX(), attacker.getY(), attacker.getZ(),
                SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.HOSTILE, 0.5f, 0.5f);
        level.sendParticles(ParticleTypes.CRIT, front.x, front.y, front.z, 6, 0.15, 0.15, 0.15, 0.05);
    }

    /** 毎 tick、ため終わった攻撃を振る。 */
    public static void onServerTick(ServerTickEvent.Post event) {
        serverTick++;
        if (PENDING.isEmpty()) {
            return;
        }

        // ため終わった攻撃をとり出す
        List<PendingStrike> readyStrikes = new ArrayList<>();
        PENDING.values().removeIf(strike -> {
            if (strike.strikeTick() <= serverTick) {
                readyStrikes.add(strike);
                return true;
            }
            return false;
        });

        // 1 つずつ振る
        for (PendingStrike strike : readyStrikes) {
            swingStrike(strike);
        }
    }

    /** ため終わった攻撃を振る。まだ届く場所にいれば当たる、はなれていれば空ぶり。 */
    private static void swingStrike(PendingStrike strike) {
        Mob attacker = strike.attacker();
        ServerPlayer target = strike.target();

        // どちらかがいなくなっていたら終わり
        if (!attacker.isAlive() || !target.isAlive() || attacker.level() != target.level()) {
            return;
        }

        // 振る動きと音
        attacker.swing(InteractionHand.MAIN_HAND, true);
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(attacker,
                new MotionPayload(attacker.getId(), MotionPayload.ENEMY_STRIKE, 0.0f, 0.0f));
        ServerLevel level = (ServerLevel) attacker.level();
        level.playSound(null, attacker.getX(), attacker.getY(), attacker.getZ(),
                SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.HOSTILE, 0.8f, 0.8f);

        // よけられたら空ぶり
        if (!isStillInReach(attacker, target)) {
            target.displayClientMessage(Component.literal("ひらり！ 攻撃をよけた").withStyle(ChatFormatting.GREEN), true);
            return;
        }

        // 敵の本来の攻撃処理で当てなおす（毒・ウィザー・火などのおまけ効果やノックバックもそのまま出る）
        // 剣で受けていればガード・パリィになる
        strikingNow = true;
        try {
            attacker.doHurtTarget(target);
        } finally {
            strikingNow = false;
        }
    }

    /** 敵の前で、腕が届くところにまだいれば true。 */
    private static boolean isStillInReach(Mob attacker, ServerPlayer target) {
        if (!attacker.isWithinMeleeAttackRange(target) || !attacker.hasLineOfSight(target)) {
            return false;
        }
        Vec3 attackerFront = Vec3.directionFromRotation(0.0f, attacker.getYHeadRot());
        Vec3 toTarget = new Vec3(target.getX() - attacker.getX(), 0.0, target.getZ() - attacker.getZ());
        if (toTarget.lengthSqr() < 0.25) {
            return true;
        }
        double angle = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, attackerFront.dot(toTarget.normalize())))));
        return angle <= SwingConfig.ENEMY_STRIKE_ARC_DEGREES;
    }
}
