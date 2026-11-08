package com.maccha.ikioiken;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * スキルツリーの特殊能力を、武器を速く振っている間だけ発動するクラス（時間・命・風の道）。
 */
public final class SkillEffects {

    private SkillEffects() {
    }

    /** 今の振りの軌跡(前の向き・今の向き・肩の位置・速さ・刃の長さ・色と形)。斬撃がこれをそのまま飛ばす。 */
    public record SwingArc(Vec3 lastDir, Vec3 newDir, Vec3 lastShoulder, Vec3 shoulder,
                           double tipSpeed, double bladeLength, TrailStyle.Style style) {
    }

    /** 時間を止められている物の記録（終わったら元にもどすため）。 */
    private static final class Frozen {
        final Entity entity;
        long untilTick;
        final boolean hadNoAi;
        final boolean hadNoGravity;
        UUID ownerId;
        boolean deferDamage;
        float pendingDamage;
        final List<Runnable> pendingFeedback = new ArrayList<>();

        Frozen(Entity entity, long untilTick, boolean hadNoAi, boolean hadNoGravity) {
            this.entity = entity;
            this.untilTick = untilTick;
            this.hadNoAi = hadNoAi;
            this.hadNoGravity = hadNoGravity;
        }
    }

    private static final Map<UUID, Frozen> FROZEN = new HashMap<>();

    // 時間停止中に受けたダメージを後回しにする：この一撃で後回しにした量・解除後のダメージを出しているところか
    private static float deferredThisHit = 0.0f;
    private static boolean releasing = false;

    /** 時間停止中の敵に当てた手ごたえ・ダメージの数字を、解除後に出すためにためておく。 */
    public static void addDeferredFeedback(UUID entityId, Runnable feedback) {
        Frozen frozen = FROZEN.get(entityId);
        if (frozen == null) {
            feedback.run();
            return;
        }
        frozen.pendingFeedback.add(feedback);
    }

    /** 一撃の前に呼ぶ。この一撃で後回しにした量を 0 にもどす。 */
    public static void resetDeferred() {
        deferredThisHit = 0.0f;
    }

    /** この一撃で後回しにしたダメージの量(ダメージの数字や能力に使う)。 */
    public static float deferredAmount() {
        return deferredThisHit;
    }

    /** ダメージを受ける直前に呼ばれる。時間停止中の敵が、停止させた本人から受けるダメージは、いったんためて解除後に出す。 */
    public static boolean allowDamage(net.minecraft.world.entity.LivingEntity entity, net.minecraft.world.damagesource.DamageSource source, float amount) {
        if (releasing || amount <= 0.0f) {
            return true;
        }
        Frozen frozen = FROZEN.get(entity.getUUID());
        if (frozen == null || !frozen.deferDamage || !(source.getEntity() instanceof ServerPlayer attacker)
                || !attacker.getUUID().equals(frozen.ownerId)) {
            return true;
        }
        frozen.pendingDamage += amount;
        deferredThisHit += amount;
        return false;
    }

    // 時間停止中に壊すはずだったブロック(持ち主ごと)。解除後にまとめて壊す
    private record PendingBlock(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos) {
    }

    private static final Map<UUID, List<PendingBlock>> PENDING_BLOCKS = new HashMap<>();

    /** 時間停止中か(ザ・ワールドを使っている間)。 */
    public static boolean isTimeStopped(UUID playerId) {
        return timeScaleFor(playerId) == 0.0;
    }

    /** 時間停止中なら、ブロックを壊すのを後回しにして true を返す(止まっていなければ何もしない)。 */
    public static boolean deferBlockBreak(ServerPlayer player, net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos) {
        if (!isTimeStopped(player.getUUID())) {
            return false;
        }
        List<PendingBlock> list = PENDING_BLOCKS.computeIfAbsent(player.getUUID(), id -> new ArrayList<>());
        PendingBlock pending = new PendingBlock(level, pos.immutable());
        if (!list.contains(pending)) {
            list.add(pending);
        }
        return true;
    }

    /** 時間停止が解けたら、ためていたブロックをまとめて壊す。 */
    private static void releaseBlocks(MinecraftServer server) {
        if (PENDING_BLOCKS.isEmpty()) {
            return;
        }
        List<UUID> done = new ArrayList<>();
        for (Map.Entry<UUID, List<PendingBlock>> entry : PENDING_BLOCKS.entrySet()) {
            if (isTimeStopped(entry.getKey())) {
                continue;
            }
            ServerPlayer owner = server.getPlayerList().getPlayer(entry.getKey());
            if (owner != null) {
                for (PendingBlock pending : entry.getValue()) {
                    if (pending.level() == owner.level() && !pending.level().getBlockState(pending.pos()).isAir()) {
                        owner.gameMode.destroyBlock(pending.pos());
                    }
                }
            }
            done.add(entry.getKey());
        }
        for (UUID id : done) {
            PENDING_BLOCKS.remove(id);
        }
    }

    /** ダメージを受けたら、どんなダメージでも(能力・斬撃・毒・落下など)、その生き物から数字を出す。 */
    private static void showAnyDamage(LivingEntity entity, net.minecraft.world.damagesource.DamageSource source,
                                      float baseDamage, float damageTaken, boolean blocked) {
        // ふつうの振りの当たりは、専用の数字を別に出しているので重ねない。解除後のまとめ出しも、数字はすでに出ている
        if (damageTaken <= 0.0f || releasing || ServerSwing.isAttackingWithMomentum()) {
            return;
        }
        double height = entity.getBbHeight();
        DamagePayload payload = new DamagePayload(entity.getX() + (entity.getRandom().nextDouble() - 0.5) * 0.6,
                entity.getY() + height * 0.8, entity.getZ() + (entity.getRandom().nextDouble() - 0.5) * 0.6,
                damageTaken, damageTaken >= 10.0f ? DamagePayload.STRONG : DamagePayload.NORMAL);
        Net.sendToPlayersTrackingEntityAndSelf(entity, payload);
    }

    /** サーバーの起動時に、ダメージを後回しにする仕組みを登録する。 */
    public static void register() {
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DAMAGE.register(SkillEffects::allowDamage);
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DAMAGE.register(SkillEffects::showAnyDamage);
    }
    private static final Map<UUID, Long> LAST_APPLY = new HashMap<>();

    // 時間の道が働いている最後の tick と段(斬撃の速さを合わせるため)
    private static final Map<UUID, long[]> TIME_ACTIVE = new HashMap<>();

    /** 斬撃などの進む速さの倍率。時間の道が働いている間は、1段目＝0.6倍・2段目＝0.25倍・3段目＝止まる(0倍)。 */
    public static double timeScaleFor(UUID playerId) {
        long[] active = TIME_ACTIVE.get(playerId);
        if (active == null || tickCounter - active[0] > 20) {
            return 1.0;
        }
        return active[1] == 1 ? 0.6 : active[1] == 2 ? 0.25 : 0.0;
    }
    private static long tickCounter = 0;

    /** 武器を速く振っているとき毎 tick 呼ぶ。覚えているスキルに合わせて効果をかける。 */
    public static void onFastSwing(ServerPlayer player, SwingArc arc) {
        // 斬撃の道：斬撃を飛ばす
        int slashTier = SkillTree.tierOf(player, SkillTree.Branch.SLASH);
        if (slashTier > 0) {
            SlashSkill.fire(player, slashTier, arc);
        }

        // 2 tick に 1 回だけ（まわりを調べるのは少し重いので）
        Long last = LAST_APPLY.get(player.getUUID());
        if (last != null && tickCounter - last < 2) {
            return;
        }
        LAST_APPLY.put(player.getUUID(), tickCounter);

        // 時間の道：近くの敵を遅くする・止める
        int timeTier = SkillTree.tierOf(player, SkillTree.Branch.TIME);
        if (timeTier > 0) {
            TIME_ACTIVE.put(player.getUUID(), new long[] {tickCounter, timeTier});
            applyTime(player, timeTier);
        }
    }

    /** 自分に効果をかける(見た目の粒は出さない、短い時間なので振るのをやめるとすぐ切れる)。 */
    private static void giveEffect(ServerPlayer player, net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect, int amplifier) {
        MobEffectInstance current = player.getEffect(effect);
        if (current != null && current.getAmplifier() > amplifier) {
            return;
        }
        player.addEffect(new MobEffectInstance(effect, 25, amplifier, true, false, false));
    }

    /** 時間の道の効果：まわりの敵を遅くする、または止める。 */
    private static void applyTime(ServerPlayer player, int tier) {
        double radius = tier == 1 ? 8.0 : tier == 2 ? 16.0 : 20.0;
        AABB area = player.getBoundingBox().inflate(radius);
        List<Entity> nearby = player.level().getEntities(player, area, entity -> canAffect(player, entity));
        for (Entity entity : nearby) {
            if (entity.distanceTo(player) > radius) {
                continue;
            }
            if (tier == 3) {
                freeze(entity, 8, player.getUUID(), true);
            } else if (entity instanceof LivingEntity living) {
                // 1 段目はすこし遅く、2 段目はほとんど動けない
                living.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, tier == 1 ? 1 : 5, true, false, false));
                if (tier == 2) {
                    living.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 40, 2, true, false, false));
                }
            }
        }
    }

    /** 時間を遅くできる相手か（自分・ほかのプレイヤー・自分のペット・アイテムなどはのぞく）。 */
    private static boolean canAffect(ServerPlayer owner, Entity entity) {
        if (entity instanceof Player || !entity.isAlive()) {
            return false;
        }
        if (entity instanceof TamableAnimal pet && pet.isOwnedBy(owner)) {
            return false;
        }
        return entity instanceof Mob || entity instanceof Projectile;
    }

    /** 相手の時間を止める(動き・頭・重力を止める。終わったら元にもどす)。 */
    static void freeze(Entity entity, int ticks, UUID ownerId, boolean deferDamage) {
        Frozen frozen = FROZEN.get(entity.getUUID());
        long until = tickCounter + Math.max(ticks, 24);
        if (frozen != null) {
            frozen.untilTick = until;
        } else {
            boolean hadNoAi = entity instanceof Mob mob && mob.isNoAi();
            frozen = new Frozen(entity, until, hadNoAi, entity.isNoGravity());
            FROZEN.put(entity.getUUID(), frozen);
        }
        if (deferDamage) {
            frozen.deferDamage = true;
            frozen.ownerId = ownerId;
        }
        if (entity instanceof Mob mob) {
            mob.setNoAi(true);
        }
        entity.setNoGravity(true);
        entity.setDeltaMovement(Vec3.ZERO);
    }

    /** 毎 tick 呼ぶ。時間停止が終わった物を元にもどす。 */
    public static void onServerTick(MinecraftServer server) {
        tickCounter++;
        releaseBlocks(server);
        if (FROZEN.isEmpty()) {
            return;
        }
        List<UUID> finished = new ArrayList<>();
        for (Map.Entry<UUID, Frozen> entry : FROZEN.entrySet()) {
            Frozen frozen = entry.getValue();
            if (frozen.entity.isRemoved()) {
                finished.add(entry.getKey());
            } else if (frozen.untilTick <= tickCounter) {
                restore(frozen);
                releaseDamage(server, frozen);
                finished.add(entry.getKey());
            } else {
                // 止めている間は、動きもゼロにしつづける
                frozen.entity.setDeltaMovement(Vec3.ZERO);
            }
        }
        for (UUID id : finished) {
            FROZEN.remove(id);
        }
    }

    /** 時間停止が解けたとき、ためていたダメージを一気に出す。 */
    private static void releaseDamage(MinecraftServer server, Frozen frozen) {
        // ためていた手ごたえとダメージの数字を、まとめて出す
        for (Runnable feedback : frozen.pendingFeedback) {
            feedback.run();
        }
        frozen.pendingFeedback.clear();
        if (frozen.pendingDamage <= 0.0f || !(frozen.entity instanceof LivingEntity target) || !target.isAlive()
                || !(target.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }
        ServerPlayer owner = frozen.ownerId == null ? null : server.getPlayerList().getPlayer(frozen.ownerId);
        releasing = true;
        try {
            target.setInvulnerableTime(0);
            target.hurtServer(level, owner != null ? level.damageSources().indirectMagic(owner, owner) : level.damageSources().magic(), frozen.pendingDamage);
        } finally {
            releasing = false;
        }
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.CRIT, target.getX(), target.getY() + target.getBbHeight() / 2.0, target.getZ(), 40, 0.5, 0.5, 0.5, 0.4);
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.EXPLOSION, target.getX(), target.getY() + target.getBbHeight() / 2.0, target.getZ(), 1, 0.0, 0.0, 0.0, 0.0);
        level.playSound(null, target.blockPosition(), net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_CRIT, net.minecraft.sounds.SoundSource.PLAYERS, 1.0f, 0.7f);
    }

    /** 止めていた物を動けるようにもどす。 */
    private static void restore(Frozen frozen) {
        if (frozen.entity instanceof Mob mob) {
            mob.setNoAi(frozen.hadNoAi);
        }
        frozen.entity.setNoGravity(frozen.hadNoGravity);
    }

    /** サーバーが止まるとき、止めていた物を全部もどす(止まったまま保存されないように)。 */
    public static void onServerStopped(MinecraftServer server) {
        for (Frozen frozen : FROZEN.values()) {
            if (!frozen.entity.isRemoved()) {
                restore(frozen);
            }
        }
        FROZEN.clear();
        LAST_APPLY.clear();
        TIME_ACTIVE.clear();
        PENDING_BLOCKS.clear();
    }
}
