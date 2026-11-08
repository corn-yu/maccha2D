package com.maccha.ikioiken;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * スキルツリー「斬撃の道」の飛ぶ斬撃。1段目＝5ブロック飛んで敵にダメージ、
 * 2段目＝10ブロック飛んでブロックもドロップなしで消す、3段目＝太くなってドロップありで壊す。
 */
public final class SlashSkill {

    private SlashSkill() {
    }

    /** 飛んでいる斬撃 1 つ分。 */
    private static final class Slash {
        final UUID ownerId;
        final ServerLevel level;
        Vec3 position;
        final Vec3 direction;
        final int tier;
        final double range;
        final float damage;
        final SkillEffects.SwingArc arc;
        final Vec3 startPosition;
        final Set<Integer> hitEntities = new HashSet<>();
        double travelled = 0.0;

        Slash(UUID ownerId, ServerLevel level, Vec3 position, Vec3 direction, int tier, double range, float damage, SkillEffects.SwingArc arc) {
            this.ownerId = ownerId;
            this.level = level;
            this.position = position;
            this.direction = direction;
            this.tier = tier;
            this.range = range;
            this.damage = damage;
            this.arc = arc;
            this.startPosition = position;
        }
    }

    private static final List<Slash> FLYING = new ArrayList<>();
    private static final java.util.Map<UUID, Long> LAST_FIRE = new java.util.HashMap<>();
    private static long tickCounter = 0;

    // 1 tick に進む長さ（ブロック）・1 回の細かい歩幅・次の斬撃までの間隔（tick）
    private static final double SPEED_PER_TICK = 2.0;
    private static final double STEP = 0.5;
    private static final int FIRE_INTERVAL_TICKS = 1;
    private static final int SOUND_INTERVAL_TICKS = 6;
    private static final int HIT_COOLDOWN_TICKS = 10;
    private static final int MAX_FLYING = 150;

    // 同じ敵に斬撃が何度も当たらないよう、最近当てた敵を覚えておく(持ち主ごと)
    private static final java.util.Map<UUID, java.util.Map<Integer, Long>> RECENT_HITS = new java.util.HashMap<>();

    /** 武器を速く振ったとき、斬撃を飛ばす（間隔があくまで次は出ない）。 */
    public static void fire(ServerPlayer player, int tier, SkillEffects.SwingArc arc) {
        Long last = LAST_FIRE.get(player.getUUID());
        if (last != null && tickCounter - last < FIRE_INTERVAL_TICKS) {
            return;
        }
        boolean playSound = last == null || tickCounter - last >= SOUND_INTERVAL_TICKS;
        LAST_FIRE.put(player.getUUID(), tickCounter);

        // 段が上がるほど遠くまで・強く(5 → 10 → 10 ブロック、ダメージ 1 → 1.5 → 2.5 倍)
        double range = tier == 1 ? 5.0 : 10.0;
        float baseDamage = (float) Math.max(4.0, player.getAttributeValue(Attributes.ATTACK_DAMAGE));
        float damage = baseDamage * (tier == 1 ? 1.0f : tier == 2 ? 1.5f : 2.5f);
        // 刃の先の位置から、剣の向きへ飛ばす(切ったときの軌跡がそのまま飛んでいく)
        Vec3 direction = arc.newDir().normalize();
        Vec3 tip = arc.shoulder().add(direction.scale(arc.bladeLength()));
        if (FLYING.size() >= MAX_FLYING) {
            return;
        }
        FLYING.add(new Slash(player.getUUID(), (ServerLevel) player.level(), tip, direction, tier, range, damage, arc));
        if (playSound) {
            player.level().playSound(null, player.blockPosition(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.0f, 1.4f);
        }
    }

    /** 毎 tick 呼ぶ。飛んでいる斬撃を進めて、当たった敵とブロックを処理する。 */
    public static void onServerTick(MinecraftServer server) {
        tickCounter++;
        if (FLYING.isEmpty()) {
            return;
        }
        List<Slash> finished = new ArrayList<>();
        for (Slash slash : FLYING) {
            ServerPlayer owner = server.getPlayerList().getPlayer(slash.ownerId);
            if (owner == null || !owner.isAlive() || owner.level() != slash.level) {
                finished.add(slash);
                continue;
            }
            if (advance(slash, owner)) {
                finished.add(slash);
            }
        }
        FLYING.removeAll(finished);
    }

    /** 斬撃を 1 tick 分進める。終わったら true を返す。 */
    private static boolean advance(Slash slash, ServerPlayer owner) {
        // 時間がゆっくり・止まっている間は、斬撃も遅くなる・空中で止まる(振るのをやめると動きだす)
        double moveLimit = SPEED_PER_TICK * SkillEffects.timeScaleFor(slash.ownerId);
        for (double moved = 0.0; moved < moveLimit; moved += STEP) {
            if (slash.travelled >= slash.range) {
                return true;
            }
            slash.position = slash.position.add(slash.direction.scale(STEP));
            slash.travelled += STEP;

            // 敵にダメージ（1 体につき 1 回）
            hitEntities(slash, owner);

            // ブロック：1 段目はかべで止まり、2 段目以上は壊しながら進む
            if (handleBlocks(slash, owner)) {
                return true;
            }
        }
        showArc(slash);
        return false;
    }

    /** 切ったときの軌跡(色と形)を、いまの位置に出す。3段目は太く見えるよう上下にもう 2 本。 */
    private static void showArc(Slash slash) {
        SkillEffects.SwingArc arc = slash.arc;
        Vec3 offset = slash.position.subtract(slash.startPosition);
        ServerSwing.spawnTrail(slash.level, arc.lastDir(), arc.newDir(), arc.lastShoulder().add(offset), arc.shoulder().add(offset),
                arc.tipSpeed(), arc.bladeLength(), arc.style());
        if (slash.tier == 3) {
            for (double gap : new double[] {0.5, -0.5}) {
                Vec3 shifted = offset.add(0.0, gap, 0.0);
                ServerSwing.spawnTrail(slash.level, arc.lastDir(), arc.newDir(), arc.lastShoulder().add(shifted), arc.shoulder().add(shifted),
                        arc.tipSpeed(), arc.bladeLength(), arc.style());
            }
        }
    }

    /** 斬撃の太さのなかにいる敵にダメージを与える。 */
    private static void hitEntities(Slash slash, ServerPlayer owner) {
        double thickness = slash.tier == 3 ? 2.5 : slash.tier == 2 ? 1.5 : 1.0;
        AABB box = new AABB(slash.position, slash.position).inflate(thickness);
        for (LivingEntity target : slash.level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (target == owner || target instanceof Player || !target.isAlive() || slash.hitEntities.contains(target.getId())) {
                continue;
            }
            if (target instanceof TamableAnimal pet && pet.isOwnedBy(owner)) {
                continue;
            }
            java.util.Map<Integer, Long> recent = RECENT_HITS.computeIfAbsent(owner.getUUID(), id -> new java.util.HashMap<>());
            Long lastHit = recent.get(target.getId());
            if (lastHit != null && tickCounter - lastHit < HIT_COOLDOWN_TICKS) {
                continue;
            }
            recent.put(target.getId(), tickCounter);
            slash.hitEntities.add(target.getId());
            target.setInvulnerableTime(0);
            target.hurtServer(slash.level, slash.level.damageSources().indirectMagic(owner, owner), slash.damage);
        }
    }

    /** ブロックの処理。斬撃が止まるなら true を返す。 */
    private static boolean handleBlocks(Slash slash, ServerPlayer owner) {
        BlockPos center = BlockPos.containing(slash.position);
        BlockState centerState = slash.level.getBlockState(center);

        // 1 段目：かたいブロックにぶつかったら消える
        if (slash.tier == 1) {
            return !centerState.getCollisionShape(slash.level, center).isEmpty();
        }

        // 2 段目以上：まんなかが壊せないブロックなら止まる
        if (!centerState.isAir() && !canBreak(slash, owner, center, centerState)) {
            return true;
        }

        // 3 段目は太いので、まわり 3×3×3 も壊す
        int reach = slash.tier == 3 ? 1 : 0;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dy = -reach; dy <= reach; dy++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    BlockState state = slash.level.getBlockState(pos);
                    if (!state.isAir() && canBreak(slash, owner, pos, state)) {
                        // 2 段目はドロップなしで消す、3 段目はドロップありで壊す
                        slash.level.destroyBlock(pos, slash.tier == 3, owner);
                    }
                }
            }
        }
        return false;
    }

    /** 壊していいブロックか（岩盤・チェストなどの入れ物・水はのぞく。ぼうけんモードなどでは壊さない）。 */
    private static boolean canBreak(Slash slash, ServerPlayer owner, BlockPos pos, BlockState state) {
        if (!owner.getAbilities().mayBuild || !state.getFluidState().isEmpty()) {
            return false;
        }
        float hardness = state.getDestroySpeed(slash.level, pos);
        return hardness >= 0.0f && hardness < 60.0f && slash.level.getBlockEntity(pos) == null;
    }

    /** サーバーが止まるとき、飛んでいる斬撃を消す。 */
    public static void onServerStopped(MinecraftServer server) {
        FLYING.clear();
        LAST_FIRE.clear();
        RECENT_HITS.clear();
    }
}
