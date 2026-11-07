package com.maccha.ikioiken;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.joml.Vector3f;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.entity.PartEntity;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.SweepAttackEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * サーバー側で、剣の通り道に敵やブロックがあるか調べて、勢いからダメージ・ガード・パリィ・ブロック壊しをするクラス。
 */
public final class ServerSwing {

    private ServerSwing() {
    }

    /** 一人ぶんの剣の状態（前の tick の剣の向きと肩の位置など）。 */
    private static final class SwingState {
        Vec3 lastBladeDir;
        Vec3 lastShoulder;
        int packetCount = 0;
        int lastPacketGameTick = 0;
        int steadyTicks = 0;
        boolean guarding = false;
        int lastFastSwingTick = -1000;
        int counterUntilTick = 0;
        double bladeLength = SwingConfig.BLADE_LENGTH;
        final Map<BlockPos, Integer> blockLastHitTick = new HashMap<>();
        boolean wasFast = false;
        final Map<Integer, Integer> lastHitPacket = new HashMap<>();
        final Map<BlockPos, Float> blockProgress = new HashMap<>();
    }

    /** 1 回の振りで当たった相手の情報。 */
    private record BladeHit(Entity target, Vec3 hitPoint, int step) {
    }

    // プレイヤーごとの剣の状態
    private static final Map<UUID, SwingState> STATES = new HashMap<>();

    /** トライデントの、あとから出る斬撃 1 本ぶん。 */
    private record PendingSlash(UUID playerId, int targetId, long dueTick, double momentumFactor, int slashIndex) {
    }

    // これから出るトライデントの斬撃と、サーバーの時間（tick）
    private static final List<PendingSlash> PENDING_SLASHES = new ArrayList<>();
    private static long serverTick = 0;

    // 勢いの倍率を一時的に攻撃力へかけるときの名前
    private static final ResourceLocation MOMENTUM_MODIFIER_ID =
            ResourceLocation.fromNamespaceAndPath(IkioiKen.MOD_ID, "momentum");

    // いきおい剣の攻撃をしている最中なら true（なぎ払いを止めるのに使う）
    private static boolean attackingWithMomentum = false;

    // 攻撃のたまり具合（ふつうはクリックの間かくで決まる）を書きかえるための入口
    private static Field attackStrengthField = null;

    /** 剣の向きパケットが届いたら、メインスレッドで処理する。 */
    public static void onBladePayload(BladePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                handleBlade(player, payload);
            }
        });
    }

    /** ワールドを閉じたら、全員の剣の記録を消す（次に開いたワールドに古い記録を持ちこまない）。 */
    public static void onServerStopped(ServerStoppedEvent event) {
        STATES.clear();
        PENDING_SLASHES.clear();
    }

    /** ログアウトした人の剣の記録を消す。 */
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        STATES.remove(event.getEntity().getUUID());
    }

    /** いきおい剣の攻撃中は、ふつうのなぎ払いを出さない。 */
    public static void onSweepAttack(SweepAttackEvent event) {
        if (attackingWithMomentum) {
            event.setSweeping(false);
        }
    }

    /** 届いた剣の向きで、前の向きからの振りを調べる。 */
    private static void handleBlade(ServerPlayer player, BladePayload payload) {
        UUID playerId = player.getUUID();

        // 長押しをやめた・振れる物を持っていない・観戦中なら記録を消して終わり
        ItemStack heldItem = player.getMainHandItem();
        boolean canSwing = payload.holding()
                && player.isAlive()
                && !player.isSpectator()
                && SwordCheck.isSwingItem(heldItem);
        if (!canSwing) {
            clearState(player);
            return;
        }

        // 今の剣の向きと肩の位置
        float bladePitch = Mth.clamp(payload.pitch(), -90.0f, 90.0f);
        Vec3 bladeDir = Vec3.directionFromRotation(bladePitch, payload.yaw());
        Vec3 shoulder = shoulderPosition(player);

        // テレポート・リスポーン・しばらく止まっていたあとは、はじめからやりなおす（遠くまで一気に切らないように）
        SwingState state = STATES.get(playerId);
        if (state != null && (state.lastShoulder.distanceTo(shoulder) > SwingConfig.MAX_SHOULDER_JUMP
                || player.tickCount - state.lastPacketGameTick > 5)) {
            clearState(player);
            state = null;
        }

        // はじめの 1 回目は記録するだけ
        if (state == null) {
            state = new SwingState();
            state.lastBladeDir = bladeDir;
            state.lastShoulder = shoulder;
            state.lastPacketGameTick = player.tickCount;
            STATES.put(playerId, state);
            return;
        }
        state.packetCount++;
        state.lastPacketGameTick = player.tickCount;

        // 刃の長さは、その武器で届く距離に合わせる（長い槍は遠くまで、短剣は近く）
        state.bladeLength = Mth.clamp(player.entityInteractionRange(), SwingConfig.MIN_BLADE_LENGTH, SwingConfig.MAX_BLADE_LENGTH);

        // 1 tick に回しすぎていたら上限までにする（ずる防止）
        bladeDir = limitTurn(state.lastBladeDir, bladeDir);
        double tipSpeed = angleBetween(state.lastBladeDir, bladeDir) * state.bladeLength * 20.0;

        // 止めて構えているか（ガード）・さっき速く振ったか（パリィ）
        updateGuard(player, state, bladeDir, tipSpeed);

        // 速く振ったら「ブンッ」と鳴らして、通り道に軌跡を出す
        playWhooshIfFast(player, state, tipSpeed);
        if (tipSpeed >= SwingConfig.TRAIL_SPEED) {
            spawnTrail(player.serverLevel(), state.lastBladeDir, bladeDir, state.lastShoulder, shoulder, tipSpeed, state.bladeLength);

            // トライデントは上と下にもう 1 本ずつ、三本の斬撃の軌跡
            if (WeaponSkill.skillOf(heldItem) == WeaponSkill.TRIDENT) {
                Vec3 gap = new Vec3(0.0, 0.35, 0.0);
                spawnTrail(player.serverLevel(), state.lastBladeDir, bladeDir, state.lastShoulder.add(gap), shoulder.add(gap), tipSpeed, state.bladeLength);
                spawnTrail(player.serverLevel(), state.lastBladeDir, bladeDir, state.lastShoulder.subtract(gap), shoulder.subtract(gap), tipSpeed, state.bladeLength);
            }
        }

        // 前の向きから今の向きまでの通り道で、当たった相手にダメージ
        List<BladeHit> hits = findHits(player, state, state.lastBladeDir, bladeDir, state.lastShoulder, shoulder);
        double remainingPower = 1.0;
        for (BladeHit hit : hits) {
            boolean didHit = applyMomentumHit(player, state, hit, bladeDir, shoulder, remainingPower);
            if (didHit) {
                remainingPower *= SwingConfig.MULTI_HIT_FALLOFF;
            }
        }

        // 道具なら、当たったブロックを勢いで掘る
        if (SwordCheck.isTool(heldItem)) {
            mineBlocksOnPath(player, state, state.lastBladeDir, bladeDir, state.lastShoulder, shoulder);
        }

        // まわりの人に剣の向きを知らせる（三人称で腕が動いて見える）
        PacketDistributor.sendToPlayersTrackingEntity(player,
                new MotionPayload(player.getId(), MotionPayload.PLAYER_BLADE, payload.yaw(), bladePitch));

        // 次の tick のために覚えておく
        state.lastBladeDir = bladeDir;
        state.lastShoulder = shoulder;
    }

    /** 剣の記録を消す（掘りかけのひびも消す）。 */
    private static void clearState(ServerPlayer player) {
        SwingState state = STATES.remove(player.getUUID());
        if (state == null) {
            return;
        }
        PacketDistributor.sendToPlayersTrackingEntity(player,
                new MotionPayload(player.getId(), MotionPayload.PLAYER_BLADE_END, 0.0f, 0.0f));
        for (BlockPos pos : state.blockProgress.keySet()) {
            player.serverLevel().destroyBlockProgress(player.getId(), pos, -1);
        }
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

    // ===== ガードとパリィ =====

    /** 剣をじっと止めているか（ガードの準備）と、さっき速く振ったか（パリィの準備）を記録する。 */
    private static void updateGuard(ServerPlayer player, SwingState state, Vec3 bladeDir, double tipSpeed) {
        double turnDegrees = Math.toDegrees(angleBetween(state.lastBladeDir, bladeDir));
        state.steadyTicks = turnDegrees <= SwingConfig.GUARD_MAX_TURN_DEGREES ? state.steadyTicks + 1 : 0;
        state.guarding = state.steadyTicks >= SwingConfig.GUARD_READY_TICKS;
        if (tipSpeed >= SwingConfig.PARRY_SPEED) {
            state.lastFastSwingTick = player.tickCount;
        }
    }

    /** 攻撃を受けたとき、剣が体と相手のあいだにあれば止める（タイミングが合えばパリィ）。 */
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        // 剣を振っている最中か（剣の向きが最近届いているか）
        SwingState state = STATES.get(player.getUUID());
        boolean isSwinging = state != null
                && player.tickCount - state.lastPacketGameTick <= 3
                && SwordCheck.isWeapon(player.getMainHandItem());
        if (!isSwinging) {
            return;
        }

        // 盾でも防げない攻撃（落下・炎・魔法など）や、どこから来たかわからない攻撃は止められない
        DamageSource source = event.getSource();
        if (source.is(DamageTypeTags.BYPASSES_SHIELD) || source.getSourcePosition() == null) {
            return;
        }

        // 攻撃が飛んでくる線（相手 → 自分の胸）
        Entity directAttacker = source.getDirectEntity();
        Vec3 attackFrom = directAttacker != null ? directAttacker.getBoundingBox().getCenter() : source.getSourcePosition();
        Vec3 attackTo = player.getEyePosition().add(0.0, -0.4, 0.0);

        // 剣の線（肩 → 剣先）
        Vec3 bladeStart = state.lastShoulder.add(state.lastBladeDir.scale(0.3));
        Vec3 bladeEnd = state.lastShoulder.add(state.lastBladeDir.scale(state.bladeLength));

        // 剣が攻撃の線のじゃまをしていなければ、ふつうに当たる
        double gap = segmentDistance(attackFrom, attackTo, bladeStart, bladeEnd);
        if (gap > SwingConfig.BLADE_BLOCK_RADIUS) {
            return;
        }

        // さっき速く振っていたらパリィ、止めて構えていたらガード、それ以外は半分だけ止まる
        boolean isParry = player.tickCount - state.lastFastSwingTick <= SwingConfig.PARRY_WINDOW_TICKS;
        if (isParry) {
            parryAttack(event, player, state, directAttacker, bladeEnd);
        } else {
            float blockRatio = state.guarding ? SwingConfig.GUARD_BLOCK_RATIO : SwingConfig.MOVING_BLOCK_RATIO;
            blockAttack(event, player, directAttacker, blockRatio);
        }
    }

    /** 2 本の線のいちばん近いところの距離を返す。 */
    private static double segmentDistance(Vec3 firstStart, Vec3 firstEnd, Vec3 secondStart, Vec3 secondEnd) {
        Vec3 firstDir = firstEnd.subtract(firstStart);
        Vec3 secondDir = secondEnd.subtract(secondStart);
        Vec3 startGap = firstStart.subtract(secondStart);
        double firstLengthSqr = firstDir.dot(firstDir);
        double secondLengthSqr = secondDir.dot(secondDir);
        double firstDotSecond = firstDir.dot(secondDir);
        double firstDotGap = firstDir.dot(startGap);
        double secondDotGap = secondDir.dot(startGap);
        double denominator = firstLengthSqr * secondLengthSqr - firstDotSecond * firstDotSecond;

        // 1 本目の線のどこがいちばん近いか（0〜1）
        double firstPart = denominator > 1.0e-9
                ? Mth.clamp((firstDotSecond * secondDotGap - firstDotGap * secondLengthSqr) / denominator, 0.0, 1.0)
                : 0.0;

        // 2 本目の線のどこがいちばん近いか（0〜1）、はみ出したら 1 本目を決めなおす
        double secondPart = secondLengthSqr > 1.0e-9 ? (firstDotSecond * firstPart + secondDotGap) / secondLengthSqr : 0.0;
        if (secondPart < 0.0) {
            secondPart = 0.0;
            firstPart = firstLengthSqr > 1.0e-9 ? Mth.clamp(-firstDotGap / firstLengthSqr, 0.0, 1.0) : 0.0;
        } else if (secondPart > 1.0) {
            secondPart = 1.0;
            firstPart = firstLengthSqr > 1.0e-9 ? Mth.clamp((firstDotSecond - firstDotGap) / firstLengthSqr, 0.0, 1.0) : 0.0;
        }

        Vec3 firstPoint = firstStart.add(firstDir.scale(firstPart));
        Vec3 secondPoint = secondStart.add(secondDir.scale(secondPart));
        return firstPoint.distanceTo(secondPoint);
    }

    /** 剣で攻撃を受け止める（止めたぶん武器がけずれる）。 */
    private static void blockAttack(LivingIncomingDamageEvent event, ServerPlayer player, Entity directAttacker, float blockRatio) {
        float blockedDamage = event.getAmount() * blockRatio;
        int durabilityCost = Math.max(1, (int) Math.ceil(blockedDamage / SwingConfig.GUARD_DAMAGE_PER_DURABILITY));
        player.getMainHandItem().hurtAndBreak(durabilityCost, player, EquipmentSlot.MAINHAND);

        // ダメージを減らす（全部止めたら攻撃自体をなかったことにする）
        if (blockRatio >= 1.0f) {
            event.setCanceled(true);
        } else {
            event.setAmount(event.getAmount() - blockedDamage);
        }

        // 近くで殴ってきた相手は少しはじき返す
        if (directAttacker instanceof LivingEntity attacker && attacker.distanceTo(player) < 4.0) {
            attacker.knockback(0.4, player.getX() - attacker.getX(), player.getZ() - attacker.getZ());
        }

        // 音と火花で「ガキンッ」
        ServerLevel level = player.serverLevel();
        Vec3 sparkPoint = player.getEyePosition().add(player.getLookAngle().scale(0.8));
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.SHIELD_BLOCK, SoundSource.PLAYERS, 1.0f, 1.2f);
        level.sendParticles(ParticleTypes.CRIT, sparkPoint.x, sparkPoint.y, sparkPoint.z, 8, 0.15, 0.15, 0.15, 0.3);
        PacketDistributor.sendToPlayer(player, new HitPayload(0.5f));
        // 文字を出す設定のときだけ、止めたダメージを見せる
        if (SwingConfig.SHOW_MESSAGES) {
            String word = blockRatio >= 1.0f ? "ガキンッ！ ガード" : "ガッ！ 受け流し";
            player.displayClientMessage(Component.literal(String.format("%s（%.1f ダメージを止めた）", word, blockedDamage))
                    .withStyle(ChatFormatting.AQUA), true);
        }
    }

    /** ピッタリのタイミングで振り返した：攻撃を全部はじき、相手をよろけさせ、次の一撃を強くする。 */
    private static void parryAttack(LivingIncomingDamageEvent event, ServerPlayer player, SwingState state,
                                    Entity directAttacker, Vec3 bladeEnd) {
        event.setCanceled(true);
        state.counterUntilTick = player.tickCount + SwingConfig.COUNTER_TICKS;

        // 飛んできた物（矢・火の玉など）ははね返す
        if (directAttacker instanceof Projectile projectile) {
            Vec3 backward = projectile.getDeltaMovement().scale(-SwingConfig.PARRY_REFLECT_SPEED);
            projectile.setDeltaMovement(backward);
            projectile.setOwner(player);
            projectile.hasImpulse = true;
        }

        // 殴ってきた相手は大きくはじいて、少しのあいだよろけさせる
        Entity attackerEntity = event.getSource().getEntity();
        if (attackerEntity instanceof LivingEntity attacker && attacker != player) {
            attacker.knockback(1.0, player.getX() - attacker.getX(), player.getZ() - attacker.getZ());
            attacker.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, SwingConfig.PARRY_STUN_TICKS, 3));
            attacker.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, SwingConfig.PARRY_STUN_TICKS, 1));
        }

        // 音と火花で「キィンッ」
        ServerLevel level = player.serverLevel();
        Vec3 sparkPoint = player.getEyePosition().add(player.getLookAngle().scale(1.0));
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.5f, 1.8f);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.0f, 1.4f);
        level.sendParticles(ParticleTypes.CRIT, sparkPoint.x, sparkPoint.y, sparkPoint.z, 25, 0.2, 0.2, 0.2, 0.6);
        level.sendParticles(ParticleTypes.FLASH, sparkPoint.x, sparkPoint.y, sparkPoint.z, 1, 0.0, 0.0, 0.0, 0.0);
        PacketDistributor.sendToPlayer(player, new HitPayload(1.3f));
        // 文字を出す設定のときだけ、パリィしたことを見せる
        if (SwingConfig.SHOW_MESSAGES) {
            player.displayClientMessage(Component.literal("⚔ キィンッ！ パリィ！ 次の一撃が強くなる！")
                    .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD), true);
        }
    }

    // ===== 音と軌跡 =====

    /** 剣先が速く動いたら、振りはじめに 1 回だけ風切り音と腕ふりを出す。 */
    private static void playWhooshIfFast(ServerPlayer player, SwingState state, double tipSpeed) {
        boolean isFast = tipSpeed >= SwingConfig.WHOOSH_SPEED;

        // 遅い → 速い に変わった瞬間だけ鳴らす
        if (isFast && !state.wasFast) {
            float loudness = (float) Mth.clamp(tipSpeed / SwingConfig.REFERENCE_SPEED * 0.5, 0.3, 1.0);
            float pitch = (float) Mth.clamp(0.9 + tipSpeed / SwingConfig.REFERENCE_SPEED * 0.3, 0.9, 1.6);
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, loudness, pitch);
            player.swing(InteractionHand.MAIN_HAND, true);
        }
        state.wasFast = tipSpeed >= SwingConfig.WHOOSH_SPEED * 0.6;
    }

    /** 剣の通り道（剣先の弧）に白い軌跡の粒を出す（みんなに見える）。 */
    private static void spawnTrail(ServerLevel level, Vec3 lastDir, Vec3 newDir,
                                   Vec3 lastShoulder, Vec3 shoulder, double tipSpeed, double bladeLength) {
        // 速いほど太く明るく
        float trailSize = (float) Mth.clamp(tipSpeed / SwingConfig.REFERENCE_SPEED, 0.6, 1.6);
        DustParticleOptions trailDust = new DustParticleOptions(new Vector3f(0.95f, 0.97f, 1.0f), trailSize);

        // 弧にそって、剣の先の方 3 か所に点を打つ
        int pointCount = 6;
        for (int point = 0; point <= pointCount; point++) {
            double progress = (double) point / pointCount;
            Vec3 dir = lastDir.lerp(newDir, progress).normalize();
            Vec3 center = lastShoulder.lerp(shoulder, progress);
            for (double along = 0.65; along <= 1.0; along += 0.175) {
                Vec3 spot = center.add(dir.scale(bladeLength * along));
                level.sendParticles(trailDust, spot.x, spot.y, spot.z, 1, 0.0, 0.0, 0.0, 0.0);
            }
        }
    }

    // ===== 生き物への攻撃 =====

    /** 剣の通り道（前の向き → 今の向き）にいる相手を、当たった順にさがす。 */
    private static List<BladeHit> findHits(ServerPlayer player, SwingState state,
                                           Vec3 lastDir, Vec3 newDir, Vec3 lastShoulder, Vec3 shoulder) {
        // 何回に区切って調べるか
        double turnDegrees = Math.toDegrees(angleBetween(lastDir, newDir));
        int stepCount = Mth.clamp((int) Math.ceil(turnDegrees / SwingConfig.SWEEP_STEP_DEGREES), 1, 30);

        // 近くにいる、切ってよい相手（大きいボスにも届くように広めにさがす）
        AABB searchArea = new AABB(shoulder, shoulder).inflate(state.bladeLength + 4.0);
        List<Entity> candidates = player.level().getEntities(player, searchArea, entity -> canBeHit(player, entity));

        // 相手ごとに、刃がはじめて当たった場所をさがす
        // 剣（高速切り）は、同じ相手にすぐまた当てられる
        int cooldownTicks = WeaponSkill.skillOf(player.getMainHandItem()) == WeaponSkill.SWORD
                ? SwingConfig.SWORD_HIT_COOLDOWN_TICKS : SwingConfig.HIT_COOLDOWN_TICKS;

        List<BladeHit> hits = new ArrayList<>();
        for (Entity candidate : candidates) {
            if (isOnCooldown(state, candidate, cooldownTicks)) {
                continue;
            }
            AABB targetBox = candidate.getBoundingBox().inflate(candidate.getPickRadius() + 0.1);
            for (int step = 0; step <= stepCount; step++) {
                double progress = (double) step / stepCount;
                Vec3 stepDir = lastDir.lerp(newDir, progress).normalize();
                Vec3 stepShoulder = lastShoulder.lerp(shoulder, progress);
                Vec3 bladeStart = stepShoulder.add(stepDir.scale(SwingConfig.BLADE_START));
                Vec3 bladeEnd = stepShoulder.add(stepDir.scale(state.bladeLength));
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

        // 同じボスの体のあちこちに当たっても 1 回だけにする
        List<BladeHit> uniqueHits = new ArrayList<>();
        Set<Integer> hitRoots = new HashSet<>();
        for (BladeHit hit : hits) {
            if (hitRoots.add(rootOf(hit.target()).getId())) {
                uniqueHits.add(hit);
            }
        }
        return uniqueHits;
    }

    /** 切ってよい相手なら true（ふつうの左クリックで殴れる相手。ただし乗り物・額縁・落ちている物は切らない）。 */
    private static boolean canBeHit(ServerPlayer player, Entity entity) {
        Entity rootEntity = rootOf(entity);
        if (rootEntity == player || !entity.isAlive() || entity.isSpectator()) {
            return false;
        }
        if (!entity.isAttackable() || !entity.isPickable() || entity.skipAttackInteraction(player)) {
            return false;
        }
        if (entity instanceof ItemEntity || entity instanceof ExperienceOrb
                || entity instanceof VehicleEntity || entity instanceof HangingEntity) {
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
    private static boolean isOnCooldown(SwingState state, Entity entity, int cooldownTicks) {
        Integer lastHit = state.lastHitPacket.get(rootOf(entity).getId());
        return lastHit != null && state.packetCount - lastHit < cooldownTicks;
    }

    /** 肩から当たった場所までのあいだに壁があれば true（壁ごしには切れない）。 */
    private static boolean isBlockedByWall(ServerPlayer player, Vec3 shoulder, Vec3 hitPoint) {
        ClipContext clip = new ClipContext(shoulder, hitPoint, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player);
        return player.level().clip(clip).getType() != HitResult.Type.MISS;
    }

    /** 当たった場所の勢い（速さ・角度）から倍率を決めて、ふつうの攻撃と同じしくみで攻撃する。当たったら true。 */
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
        double momentumFactor = speedFactor * angleFactor * remainingPower;

        // パリィのすぐあとなら反撃ボーナス
        boolean isCounter = player.tickCount <= state.counterUntilTick;
        if (isCounter) {
            momentumFactor *= SwingConfig.COUNTER_BONUS;
            state.counterUntilTick = 0;
        }

        // 同じ相手にすぐ当たらないように記録（前に当てた時間も見ておく）
        Entity rootTarget = rootOf(target);
        Integer previousHit = state.lastHitPacket.get(rootTarget.getId());
        state.lastHitPacket.put(rootTarget.getId(), state.packetCount);

        // 武器ごとの特殊能力
        int skill = WeaponSkill.skillOf(player.getMainHandItem());
        boolean superCritical = false;
        if (skill == WeaponSkill.SWORD) {
            // 剣＝高速切り：無敵時間を消して続けて切れる（続けて当てた 1 回は少し弱い）
            if (rootTarget instanceof LivingEntity living) {
                living.invulnerableTime = 0;
            }
            if (previousHit != null && state.packetCount - previousHit < SwingConfig.HIT_COOLDOWN_TICKS) {
                momentumFactor *= SwingConfig.SWORD_RAPID_FACTOR;
            }
        } else if (skill == WeaponSkill.AXE && hitSpeed >= SwingConfig.REFERENCE_SPEED * SwingConfig.AXE_CRIT_SPEED_RATIO) {
            // 斧＝超クリティカル：思いきり振りぬくとダメージが大きく上がる
            momentumFactor *= SwingConfig.AXE_CRIT_MULTIPLIER;
            superCritical = true;
        }

        // 攻撃前の体力（与えたダメージを出すため）
        float healthBefore = rootTarget instanceof LivingEntity living ? living.getHealth() + living.getAbsorptionAmount() : 0.0f;

        // ふつうの攻撃と同じしくみで攻撃する（どの Mod のモブにも効く・剣の特殊効果も出る）
        attackWithMomentum(player, target, momentumFactor);

        // 与えたダメージ
        float dealtDamage = rootTarget instanceof LivingEntity living
                ? healthBefore - (living.getHealth() + living.getAbsorptionAmount()) : 0.0f;
        if (rootTarget instanceof LivingEntity && dealtDamage <= 0.0f && rootTarget.isAlive()) {
            return false;
        }

        // 勢いの向きにもふっとばす
        pushTarget(rootTarget, relativeVelocity, speedFactor);

        // 粒で手ごたえを出して、本人の画面をゆらす
        showHitEffects(player.serverLevel(), hitPoint, speedFactor);
        if (superCritical) {
            showCriticalEffects(player.serverLevel(), hitPoint);
        }
        PacketDistributor.sendToPlayer(player, new HitPayload((float) Math.min(speedFactor, 1.5) + (superCritical ? 0.6f : 0.0f)));

        // 当てた相手からダメージの数字を出す
        int numberStyle = superCritical ? DamagePayload.CRITICAL
                : hitSpeed >= SwingConfig.REFERENCE_SPEED ? DamagePayload.STRONG : DamagePayload.NORMAL;
        sendDamageNumber(player, hitPoint, dealtDamage, numberStyle);

        // トライデント＝三本の斬撃：少しずつおくれて、あと 2 本の斬撃が当たる
        if (skill == WeaponSkill.TRIDENT) {
            for (int slash = 1; slash <= SwingConfig.TRIDENT_EXTRA_SLASHES; slash++) {
                PENDING_SLASHES.add(new PendingSlash(player.getUUID(), rootTarget.getId(),
                        serverTick + (long) slash * SwingConfig.TRIDENT_SLASH_DELAY_TICKS,
                        momentumFactor * SwingConfig.TRIDENT_EXTRA_FACTOR, slash));
            }
        }
        return true;
    }

    /** 攻撃力に勢いの倍率を一時的にかけて、ふつうの攻撃（player.attack）をする。 */
    private static void attackWithMomentum(ServerPlayer player, Entity target, double momentumFactor) {
        AttributeInstance damageAttribute = player.getAttribute(Attributes.ATTACK_DAMAGE);
        if (damageAttribute != null) {
            damageAttribute.addOrUpdateTransientModifier(new AttributeModifier(
                    MOMENTUM_MODIFIER_ID, momentumFactor - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
        fillAttackStrength(player);
        attackingWithMomentum = true;
        try {
            player.attack(target);
        } finally {
            attackingWithMomentum = false;
            if (damageAttribute != null) {
                damageAttribute.removeModifier(MOMENTUM_MODIFIER_ID);
            }
        }
    }

    /** 攻撃のたまり具合を満タンにする（勢いで強さを決めるので、クリックの間かくでは弱めない）。 */
    private static void fillAttackStrength(ServerPlayer player) {
        try {
            if (attackStrengthField == null) {
                attackStrengthField = LivingEntity.class.getDeclaredField("attackStrengthTicker");
                attackStrengthField.setAccessible(true);
            }
            attackStrengthField.setInt(player, 1000);
        } catch (ReflectiveOperationException | RuntimeException error) {
            // 書きかえられなくても攻撃はできる（少し弱くなるだけ）
        }
    }

    /** 相手を勢いの向きにふっとばす（生き物だけ）。 */
    private static void pushTarget(Entity target, Vec3 relativeVelocity, double speedFactor) {
        if (!(target instanceof LivingEntity livingTarget)) {
            return;
        }
        Vec3 pushDir = new Vec3(relativeVelocity.x, 0.0, relativeVelocity.z);
        if (pushDir.lengthSqr() < 1.0e-6) {
            return;
        }
        pushDir = pushDir.normalize();
        float strength = (float) (SwingConfig.KNOCKBACK_BASE * speedFactor);
        livingTarget.knockback(strength, -pushDir.x, -pushDir.z);
    }

    /** 当たったときの粒を出す（速いほど派手、音はふつうの攻撃の音が鳴る）。 */
    private static void showHitEffects(ServerLevel level, Vec3 hitPoint, double speedFactor) {
        if (speedFactor >= 1.25) {
            level.sendParticles(ParticleTypes.CRIT, hitPoint.x, hitPoint.y, hitPoint.z, 14, 0.2, 0.2, 0.2, 0.4);
        }
        level.sendParticles(ParticleTypes.SWEEP_ATTACK, hitPoint.x, hitPoint.y, hitPoint.z, 1, 0.0, 0.0, 0.0, 0.0);
    }

    /** 当てた場所から、本人の画面にダメージの数字を出す。 */
    private static void sendDamageNumber(ServerPlayer player, Vec3 hitPoint, float dealtDamage, int style) {
        if (!SwingConfig.SHOW_DAMAGE_NUMBER || dealtDamage <= 0.0f) {
            return;
        }
        PacketDistributor.sendToPlayer(player, new DamagePayload(hitPoint.x, hitPoint.y, hitPoint.z, dealtDamage, style));
    }

    /** 斧の超クリティカルの派手な火花と音。 */
    private static void showCriticalEffects(ServerLevel level, Vec3 hitPoint) {
        level.sendParticles(ParticleTypes.CRIT, hitPoint.x, hitPoint.y, hitPoint.z, 30, 0.3, 0.3, 0.3, 0.6);
        level.sendParticles(ParticleTypes.ENCHANTED_HIT, hitPoint.x, hitPoint.y, hitPoint.z, 20, 0.3, 0.3, 0.3, 0.5);
        level.sendParticles(ParticleTypes.FLASH, hitPoint.x, hitPoint.y, hitPoint.z, 1, 0.0, 0.0, 0.0, 0.0);
        level.playSound(null, hitPoint.x, hitPoint.y, hitPoint.z, SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.3f, 0.6f);
        level.playSound(null, hitPoint.x, hitPoint.y, hitPoint.z, SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.35f, 1.7f);
    }

    // ===== トライデントの三本の斬撃 =====

    /** 毎 tick、時間が来たトライデントの斬撃を当てる。 */
    public static void onServerTick(ServerTickEvent.Post event) {
        serverTick++;
        if (PENDING_SLASHES.isEmpty()) {
            return;
        }
        List<PendingSlash> dueSlashes = new ArrayList<>();
        PENDING_SLASHES.removeIf(slash -> {
            if (slash.dueTick() <= serverTick) {
                dueSlashes.add(slash);
                return true;
            }
            return false;
        });
        for (PendingSlash slash : dueSlashes) {
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(slash.playerId());
            if (player != null) {
                extraSlash(player, slash);
            }
        }
    }

    /** あとから出る斬撃を 1 本当てる（相手が近くにいて、まだトライデントを持っているときだけ）。 */
    private static void extraSlash(ServerPlayer player, PendingSlash slash) {
        if (!player.isAlive() || WeaponSkill.skillOf(player.getMainHandItem()) != WeaponSkill.TRIDENT) {
            return;
        }
        Entity target = player.serverLevel().getEntity(slash.targetId());
        if (target == null || !target.isAlive() || !canBeHit(player, target)) {
            return;
        }
        double reach = Mth.clamp(player.entityInteractionRange(), SwingConfig.MIN_BLADE_LENGTH, SwingConfig.MAX_BLADE_LENGTH) + 3.0;
        if (target.distanceTo(player) > reach) {
            return;
        }

        // 斬撃が当たる場所（1 本目より上・下にずらす）
        Vec3 center = target.getBoundingBox().getCenter();
        double offset = (slash.slashIndex() % 2 == 1 ? 1.0 : -1.0) * target.getBbHeight() * 0.25;
        Vec3 slashPoint = center.add(0.0, offset, 0.0);

        // 無敵時間を消して、ふつうの攻撃と同じしくみで当てる
        float healthBefore = target instanceof LivingEntity living ? living.getHealth() + living.getAbsorptionAmount() : 0.0f;
        if (target instanceof LivingEntity living) {
            living.invulnerableTime = 0;
        }
        attackWithMomentum(player, target, slash.momentumFactor());
        float dealtDamage = target instanceof LivingEntity living
                ? healthBefore - (living.getHealth() + living.getAbsorptionAmount()) : 0.0f;

        // 斬撃の見た目と音
        ServerLevel level = player.serverLevel();
        level.sendParticles(ParticleTypes.SWEEP_ATTACK, slashPoint.x, slashPoint.y, slashPoint.z, 1, 0.0, 0.0, 0.0, 0.0);
        level.sendParticles(ParticleTypes.BUBBLE_POP, slashPoint.x, slashPoint.y, slashPoint.z, 8, 0.3, 0.1, 0.3, 0.05);
        level.playSound(null, slashPoint.x, slashPoint.y, slashPoint.z, SoundEvents.PLAYER_ATTACK_SWEEP,
                SoundSource.PLAYERS, 0.9f, 1.2f + 0.2f * slash.slashIndex());
        PacketDistributor.sendToPlayer(player, new HitPayload(0.4f));
        sendDamageNumber(player, slashPoint, dealtDamage, DamagePayload.NORMAL);
    }

    // ===== 道具でブロックを壊す =====

    /** 道具の通り道で当たったブロックを、勢いに合わせて掘る（何回か当てると壊れる）。 */
    private static void mineBlocksOnPath(ServerPlayer player, SwingState state,
                                         Vec3 lastDir, Vec3 newDir, Vec3 lastShoulder, Vec3 shoulder) {
        ServerLevel level = player.serverLevel();
        double turnRadians = angleBetween(lastDir, newDir);
        int stepCount = Mth.clamp((int) Math.ceil(Math.toDegrees(turnRadians) / SwingConfig.SWEEP_STEP_DEGREES), 1, 30);
        Set<BlockPos> touchedBlocks = new HashSet<>();
        int brokenCount = 0;

        for (int step = 1; step <= stepCount && brokenCount < SwingConfig.MAX_BLOCKS_PER_TICK; step++) {
            // この区切りでの刃の線
            double progress = (double) step / stepCount;
            Vec3 stepDir = lastDir.lerp(newDir, progress).normalize();
            Vec3 stepShoulder = lastShoulder.lerp(shoulder, progress);
            Vec3 bladeStart = stepShoulder.add(stepDir.scale(SwingConfig.BLADE_START));
            Vec3 bladeEnd = stepShoulder.add(stepDir.scale(state.bladeLength));

            // 刃がはじめに当たるブロック
            BlockHitResult blockHit = level.clip(new ClipContext(bladeStart, bladeEnd,
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (blockHit.getType() != HitResult.Type.BLOCK) {
                continue;
            }
            BlockPos pos = blockHit.getBlockPos();
            if (!touchedBlocks.add(pos)) {
                continue;
            }

            // 当たった場所の速さ（回転 ＋ 自分の移動）
            double hitRadius = blockHit.getLocation().distanceTo(stepShoulder);
            double hitSpeed = (turnRadians * hitRadius + shoulder.subtract(lastShoulder).length()) * 20.0;
            if (hitSpeed < SwingConfig.MIN_HIT_SPEED) {
                continue;
            }
            double speedFactor = Math.min(hitSpeed / SwingConfig.REFERENCE_SPEED, SwingConfig.MAX_SPEED_FACTOR);

            // ふつうに掘ったときの 1 tick ぶん × 勢い
            if (hitBlock(player, state, level, blockHit, speedFactor)) {
                brokenCount++;
            }
        }
    }

    /** 1 つのブロックに道具を当てて、ひびを進める。壊れたら true。 */
    private static boolean hitBlock(ServerPlayer player, SwingState state, ServerLevel level, BlockHitResult blockHit, double speedFactor) {
        BlockPos pos = blockHit.getBlockPos();
        BlockState blockState = level.getBlockState(pos);
        if (blockState.isAir() || !level.mayInteract(player, pos)) {
            return false;
        }
        ItemStack tool = player.getMainHandItem();

        // クワを土や草ブロックに振りおろしたら、畑にする（右クリックで耕すのと同じ）
        if (tillWithHoe(player, state, tool, blockHit)) {
            return false;
        }

        // その道具の得意なブロックだけ（斧は木、つるはしは石や鉱石、シャベルは土や砂、クワは葉っぱなど）
        if (tool.getDestroySpeed(blockState) <= 1.0f) {
            return false;
        }
        float progressPerTick = blockState.getDestroyProgress(player, level, pos);
        if (progressPerTick <= 0.0f) {
            return false;
        }

        // 同じ振りで何回も当たらないように、同じブロックには少しあけないと当たらない（たくさん振るほど早く壊れる）
        Integer lastHitTick = state.blockLastHitTick.get(pos);
        if (lastHitTick != null && player.tickCount - lastHitTick < SwingConfig.MINING_HIT_COOLDOWN_TICKS) {
            return false;
        }

        // しばらく当てていなかったブロックのひびは消えている（ふつうに掘るのをやめたときと同じ）
        if (lastHitTick != null && player.tickCount - lastHitTick > SwingConfig.MINING_FORGET_TICKS) {
            state.blockProgress.remove(pos);
        }
        state.blockLastHitTick.put(pos, player.tickCount);

        // 掘り具合を足す
        float progress = state.blockProgress.getOrDefault(pos, 0.0f)
                + (float) (progressPerTick * SwingConfig.SWING_MINING_TICKS * speedFactor);

        // 当たった音
        level.playSound(null, pos, blockState.getSoundType(level, pos, player).getHitSound(),
                SoundSource.BLOCKS, 0.8f, 0.9f);

        // まだ壊れないなら、ひびを見せる
        if (progress < 1.0f) {
            state.blockProgress.put(pos, progress);
            level.destroyBlockProgress(player.getId(), pos, Mth.clamp((int) (progress * 10.0f), 0, 9));
            return false;
        }

        // 壊す（ドロップ・道具の耐久・保護エリアなどはふつうに掘ったときと同じ）
        state.blockProgress.remove(pos);
        level.destroyBlockProgress(player.getId(), pos, -1);
        boolean broken = player.gameMode.destroyBlock(pos);
        if (broken) {
            PacketDistributor.sendToPlayer(player, new HitPayload(0.4f));
        }
        return broken;
    }

    /** クワが当たった土を耕して畑にする。耕せたら true。 */
    private static boolean tillWithHoe(ServerPlayer player, SwingState state, ItemStack tool, BlockHitResult blockHit) {
        // クワでなければ、または下の面に当たったときは耕さない
        if (!tool.canPerformAction(ItemAbilities.HOE_TILL) || blockHit.getDirection() == Direction.DOWN) {
            return false;
        }

        // 同じ振りで何回も耕そうとしない
        BlockPos pos = blockHit.getBlockPos();
        Integer lastHitTick = state.blockLastHitTick.get(pos);
        if (lastHitTick != null && player.tickCount - lastHitTick < SwingConfig.MINING_HIT_COOLDOWN_TICKS) {
            return false;
        }

        // 右クリックで耕すのと同じ動き（上が空いているか・音・耐久・ほかの Mod の決まりもそのまま）
        InteractionResult result = tool.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, blockHit));
        if (!result.consumesAction()) {
            return false;
        }
        state.blockLastHitTick.put(pos, player.tickCount);
        state.blockProgress.remove(pos);
        return true;
    }
}
