package com.maccha.ikioiken;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.entity.PartEntity;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * サーバー側で、剣の通り道に敵がいるか調べて、勢いからダメージを計算するクラス。
 */
public final class ServerSwing {

    private ServerSwing() {
    }

    /** 一人ぶんの剣の状態（前の tick の剣の向きと肩の位置など）。 */
    private static final class SwingState {
        Vec3 lastBladeDir;
        Vec3 lastShoulder;
        int packetCount = 0;
        boolean wasFast = false;
        final Map<Integer, Integer> lastHitPacket = new HashMap<>();
    }

    /** 1 回の振りで当たった相手の情報。 */
    private record BladeHit(Entity target, Vec3 hitPoint, int step) {
    }

    // プレイヤーごとの剣の状態
    private static final Map<UUID, SwingState> STATES = new HashMap<>();

    /** 剣の向きパケットが届いたら、メインスレッドで処理する。 */
    public static void onBladePayload(BladePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                handleBlade(player, payload);
            }
        });
    }

    /** ログアウトした人の剣の記録を消す。 */
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        STATES.remove(event.getEntity().getUUID());
    }

    /** 届いた剣の向きで、前の向きからの振りを調べる。 */
    private static void handleBlade(ServerPlayer player, BladePayload payload) {
        UUID playerId = player.getUUID();

        // 長押しをやめた・剣を持っていない・観戦中なら記録を消して終わり
        boolean canSwing = payload.holding()
                && player.isAlive()
                && !player.isSpectator()
                && SwordCheck.isSword(player.getMainHandItem());
        if (!canSwing) {
            STATES.remove(playerId);
            return;
        }

        // 今の剣の向きと肩の位置
        float bladePitch = Mth.clamp(payload.pitch(), -90.0f, 90.0f);
        Vec3 bladeDir = Vec3.directionFromRotation(bladePitch, payload.yaw());
        Vec3 shoulder = shoulderPosition(player);

        // はじめの 1 回目は記録するだけ
        SwingState state = STATES.get(playerId);
        if (state == null) {
            state = new SwingState();
            state.lastBladeDir = bladeDir;
            state.lastShoulder = shoulder;
            STATES.put(playerId, state);
            return;
        }
        state.packetCount++;

        // 1 tick に回しすぎていたら上限までにする（ずる防止）
        bladeDir = limitTurn(state.lastBladeDir, bladeDir);

        // 速く振ったら「ブンッ」と鳴らして腕をふる
        playWhooshIfFast(player, state, bladeDir);

        // 前の向きから今の向きまでの通り道で、当たった相手にダメージ
        List<BladeHit> hits = findHits(player, state, state.lastBladeDir, bladeDir, state.lastShoulder, shoulder);
        double remainingPower = 1.0;
        for (BladeHit hit : hits) {
            boolean didHit = applyMomentumHit(player, state, hit, bladeDir, shoulder, remainingPower);
            if (didHit) {
                remainingPower *= SwingConfig.MULTI_HIT_FALLOFF;
            }
        }

        // 次の tick のために覚えておく
        state.lastBladeDir = bladeDir;
        state.lastShoulder = shoulder;
    }

    /** プレイヤーの右肩の位置を返す（剣の回転の中心）。 */
    private static Vec3 shoulderPosition(Player player) {
        double yawRadians = Math.toRadians(player.getYRot());
        Vec3 rightSide = new Vec3(-Math.cos(yawRadians), 0.0, -Math.sin(yawRadians));
        return player.getEyePosition()
                .add(0.0, -SwingConfig.SHOULDER_DROP, 0.0)
                .add(rightSide.scale(SwingConfig.SHOULDER_SIDE));
    }

    /** 2 つの向きのあいだの角度（ラジアン）を返す。 */
    private static double angleBetween(Vec3 fromDir, Vec3 toDir) {
        double dot = Mth.clamp(fromDir.dot(toDir), -1.0, 1.0);
        return Math.acos(dot);
    }

    /** 1 tick に回せる角度の上限をこえていたら、上限の向きにちぢめる。 */
    private static Vec3 limitTurn(Vec3 lastDir, Vec3 newDir) {
        double turnAngle = angleBetween(lastDir, newDir);
        double maxAngle = Math.toRadians(SwingConfig.MAX_TURN_DEGREES_PER_TICK);
        if (turnAngle <= maxAngle) {
            return newDir;
        }
        double ratio = maxAngle / turnAngle;
        return lastDir.lerp(newDir, ratio).normalize();
    }

    /** 剣先が速く動いたら、振りはじめに 1 回だけ風切り音と腕ふりを出す。 */
    private static void playWhooshIfFast(ServerPlayer player, SwingState state, Vec3 bladeDir) {
        double tipSpeed = angleBetween(state.lastBladeDir, bladeDir) * SwingConfig.BLADE_LENGTH * 20.0;
        boolean isFast = tipSpeed >= SwingConfig.WHOOSH_SPEED;

        // 遅い → 速い に変わった瞬間だけ鳴らす
        if (isFast && !state.wasFast) {
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 0.6f, 1.3f);
            player.swing(InteractionHand.MAIN_HAND, true);
        }
        state.wasFast = tipSpeed >= SwingConfig.WHOOSH_SPEED * 0.6;
    }

    /** 剣の通り道（前の向き → 今の向き）にいる相手を、当たった順にさがす。 */
    private static List<BladeHit> findHits(ServerPlayer player, SwingState state,
                                           Vec3 lastDir, Vec3 newDir, Vec3 lastShoulder, Vec3 shoulder) {
        // 何回に区切って調べるか
        double turnDegrees = Math.toDegrees(angleBetween(lastDir, newDir));
        int stepCount = Mth.clamp((int) Math.ceil(turnDegrees / SwingConfig.SWEEP_STEP_DEGREES), 1, 30);

        // 近くにいる、切ってよい相手
        AABB searchArea = new AABB(shoulder, shoulder).inflate(SwingConfig.BLADE_LENGTH + 2.0);
        List<Entity> candidates = player.level().getEntities(player, searchArea, entity -> canBeHit(player, entity));

        // 相手ごとに、刃がはじめて当たった場所をさがす
        List<BladeHit> hits = new ArrayList<>();
        for (Entity candidate : candidates) {
            if (isOnCooldown(state, candidate)) {
                continue;
            }
            AABB targetBox = candidate.getBoundingBox().inflate(candidate.getPickRadius() + 0.1);
            for (int step = 0; step <= stepCount; step++) {
                double progress = (double) step / stepCount;
                Vec3 stepDir = lastDir.lerp(newDir, progress).normalize();
                Vec3 stepShoulder = lastShoulder.lerp(shoulder, progress);
                Vec3 bladeStart = stepShoulder.add(stepDir.scale(SwingConfig.BLADE_START));
                Vec3 bladeEnd = stepShoulder.add(stepDir.scale(SwingConfig.BLADE_LENGTH));
                Vec3 hitPoint = targetBox.contains(bladeStart) ? bladeStart : targetBox.clip(bladeStart, bladeEnd).orElse(null);
                if (hitPoint != null) {
                    if (!isBlockedByWall(player, stepShoulder, hitPoint)) {
                        hits.add(new BladeHit(candidate, hitPoint, step));
                    }
                    break;
                }
            }
        }

        // 先に当たった相手から順に並べる
        hits.sort((first, second) -> Integer.compare(first.step(), second.step()));
        return hits;
    }

    /** 切ってよい相手なら true（自分・死んでいる・自分のペット・PvP 禁止の相手はだめ）。 */
    private static boolean canBeHit(ServerPlayer player, Entity entity) {
        Entity rootEntity = rootOf(entity);
        if (rootEntity == player || !entity.isAlive() || !entity.isAttackable() || !entity.isPickable()) {
            return false;
        }
        if (!(entity instanceof LivingEntity) && !(entity instanceof PartEntity<?>)) {
            return false;
        }
        if (entity.skipAttackInteraction(player)) {
            return false;
        }
        if (rootEntity instanceof OwnableEntity pet && pet.getOwner() == player) {
            return false;
        }
        if (rootEntity instanceof Player otherPlayer && !player.canHarmPlayer(otherPlayer)) {
            return false;
        }
        return true;
    }

    /** ボスの体の一部（カタリズムの大きいボスなど）なら本体を、そうでなければそのまま返す。 */
    private static Entity rootOf(Entity entity) {
        if (entity instanceof PartEntity<?> part) {
            return part.getParent();
        }
        return entity;
    }

    /** 同じ相手にさっき当てたばかりなら true。 */
    private static boolean isOnCooldown(SwingState state, Entity entity) {
        Integer lastHit = state.lastHitPacket.get(rootOf(entity).getId());
        return lastHit != null && state.packetCount - lastHit < SwingConfig.HIT_COOLDOWN_TICKS;
    }

    /** 肩から当たった場所までのあいだに壁があれば true（壁ごしには切れない）。 */
    private static boolean isBlockedByWall(ServerPlayer player, Vec3 shoulder, Vec3 hitPoint) {
        ClipContext clip = new ClipContext(shoulder, hitPoint, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player);
        return player.level().clip(clip).getType() != HitResult.Type.MISS;
    }

    /** 当たった場所の勢い（速さ・角度）からダメージを計算して与える。当たったら true。 */
    private static boolean applyMomentumHit(ServerPlayer player, SwingState state, BladeHit hit,
                                            Vec3 bladeDir, Vec3 shoulder, double remainingPower) {
        Entity target = hit.target();
        Vec3 hitPoint = hit.hitPoint();

        // 当たった場所の刃の速さ（剣の回転 ＋ 自分の移動 − 相手の移動）。単位はブロック/tick
        double hitRadius = hitPoint.distanceTo(state.lastShoulder);
        Vec3 swingVelocity = bladeDir.subtract(state.lastBladeDir).scale(hitRadius);
        Vec3 playerVelocity = shoulder.subtract(state.lastShoulder);
        Vec3 targetVelocity = new Vec3(target.getX() - target.xo, target.getY() - target.yo, target.getZ() - target.zo);
        Vec3 relativeVelocity = swingVelocity.add(playerVelocity).subtract(targetVelocity);
        double hitSpeed = relativeVelocity.length() * 20.0;

        // 遅すぎる当たりはダメージなし（そっとふれただけ）
        if (hitSpeed < SwingConfig.MIN_HIT_SPEED) {
            return false;
        }

        // 速さの倍率（基準の速さで 1 倍、上限あり）
        double speedFactor = Math.min(hitSpeed / SwingConfig.REFERENCE_SPEED, SwingConfig.MAX_SPEED_FACTOR);

        // 角度の倍率（相手の中心に向かってまっすぐ当たるほど強い、かすると弱い）
        Vec3 toCenter = target.getBoundingBox().getCenter().subtract(hitPoint);
        double straightness = toCenter.lengthSqr() < 1.0e-6 ? 1.0 : relativeVelocity.normalize().dot(toCenter.normalize());
        double angleFactor = SwingConfig.ANGLE_MIN_FACTOR + (1.0 - SwingConfig.ANGLE_MIN_FACTOR) * Math.max(0.0, straightness);

        // 剣の攻撃力（エンチャントこみ）に倍率をかける
        ServerLevel level = player.serverLevel();
        ItemStack weapon = player.getMainHandItem();
        DamageSource damageSource = player.damageSources().playerAttack(player);
        float swordDamage = (float) player.getAttributeValue(Attributes.ATTACK_DAMAGE);
        swordDamage += weapon.getItem().getAttackDamageBonus(target, swordDamage, damageSource);
        swordDamage = EnchantmentHelper.modifyDamage(level, weapon, target, damageSource, swordDamage);
        float finalDamage = (float) (swordDamage * speedFactor * angleFactor * remainingPower);

        // 同じ相手にすぐ当たらないように記録
        state.lastHitPacket.put(rootOf(target).getId(), state.packetCount);

        // ダメージを与える（効かなかったら終わり）
        boolean damaged = target.hurt(damageSource, finalDamage);
        if (!damaged) {
            return false;
        }

        // 勢いの向きにふっとばす
        pushTarget(player, target, weapon, damageSource, relativeVelocity, speedFactor);

        // 剣の特殊効果・耐久・エンチャントの効果（カタリズムの剣の効果もここで出る）
        if (target instanceof LivingEntity livingTarget) {
            if (weapon.hurtEnemy(livingTarget, player)) {
                weapon.postHurtEnemy(livingTarget, player);
            }
        }
        EnchantmentHelper.doPostAttackEffects(level, target, damageSource);
        player.setLastHurtMob(target);
        player.causeFoodExhaustion(0.1f);

        // 音と粒で手ごたえを出す
        showHitEffects(level, hitPoint, speedFactor);

        // 画面下に勢いとダメージを出す
        if (SwingConfig.SHOW_DAMAGE_MESSAGE) {
            showDamageMessage(player, hitSpeed, angleFactor, finalDamage);
        }
        return true;
    }

    /** 相手を勢いの向きにふっとばす（生き物だけ）。 */
    private static void pushTarget(ServerPlayer player, Entity target, ItemStack weapon,
                                   DamageSource damageSource, Vec3 relativeVelocity, double speedFactor) {
        LivingEntity livingTarget = target instanceof LivingEntity living ? living
                : rootOf(target) instanceof LivingEntity rootLiving ? rootLiving : null;
        if (livingTarget == null) {
            return;
        }
        Vec3 pushDir = new Vec3(relativeVelocity.x, 0.0, relativeVelocity.z);
        if (pushDir.lengthSqr() < 1.0e-6) {
            return;
        }
        pushDir = pushDir.normalize();
        float strength = (float) (SwingConfig.KNOCKBACK_BASE * speedFactor);
        strength = EnchantmentHelper.modifyKnockback(player.serverLevel(), weapon, target, damageSource, strength);
        livingTarget.knockback(strength, -pushDir.x, -pushDir.z);
    }

    /** 当たったときの音と粒を出す（速いほど派手）。 */
    private static void showHitEffects(ServerLevel level, Vec3 hitPoint, double speedFactor) {
        boolean isStrong = speedFactor >= 1.0;
        level.playSound(null, hitPoint.x, hitPoint.y, hitPoint.z,
                isStrong ? SoundEvents.PLAYER_ATTACK_STRONG : SoundEvents.PLAYER_ATTACK_WEAK,
                SoundSource.PLAYERS, 1.0f, 1.0f);
        if (speedFactor >= 1.25) {
            level.playSound(null, hitPoint.x, hitPoint.y, hitPoint.z,
                    SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.0f, 1.0f);
            level.sendParticles(ParticleTypes.CRIT, hitPoint.x, hitPoint.y, hitPoint.z, 12, 0.2, 0.2, 0.2, 0.3);
        }
        level.sendParticles(ParticleTypes.SWEEP_ATTACK, hitPoint.x, hitPoint.y, hitPoint.z, 1, 0.0, 0.0, 0.0, 0.0);
    }

    /** 画面下（アクションバー）に「いきおい・当たり方・ダメージ」を出す。 */
    private static void showDamageMessage(ServerPlayer player, double hitSpeed, double angleFactor, float finalDamage) {
        String word = hitSpeed >= SwingConfig.REFERENCE_SPEED * 1.25 ? "ズバァッ！" : hitSpeed >= SwingConfig.REFERENCE_SPEED ? "ズバッ！" : "ペチッ";
        ChatFormatting color = hitSpeed >= SwingConfig.REFERENCE_SPEED ? ChatFormatting.GOLD : ChatFormatting.GRAY;
        String text = String.format("%s いきおい %.0f m/s ・ 当たり方 %.0f%% → %.1f ダメージ",
                word, hitSpeed, angleFactor * 100.0, finalDamage);
        player.displayClientMessage(Component.literal(text).withStyle(color), true);
    }
}
