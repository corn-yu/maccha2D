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

        Frozen(Entity entity, long untilTick, boolean hadNoAi, boolean hadNoGravity) {
            this.entity = entity;
            this.untilTick = untilTick;
            this.hadNoAi = hadNoAi;
            this.hadNoGravity = hadNoGravity;
        }
    }

    private static final Map<UUID, Frozen> FROZEN = new HashMap<>();
    private static final Map<UUID, Long> LAST_APPLY = new HashMap<>();

    // 時間の道が働いている最後の tick と段(斬撃の速さを合わせるため)
    private static final Map<UUID, long[]> TIME_ACTIVE = new HashMap<>();

    /** 斬撃などの進む速さの倍率。時間の道が働いている間は、1段目＝0.6倍・2段目＝0.25倍・3段目＝止まる(0倍)。 */
    public static double timeScaleFor(UUID playerId) {
        long[] active = TIME_ACTIVE.get(playerId);
        if (active == null || tickCounter - active[0] > 3) {
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
                freeze(entity, 8);
            } else if (entity instanceof LivingEntity living) {
                // 1 段目はすこし遅く、2 段目はほとんど動けない
                living.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 20, tier == 1 ? 1 : 5, true, false, false));
                if (tier == 2) {
                    living.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 20, 2, true, false, false));
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
    static void freeze(Entity entity, int ticks) {
        Frozen frozen = FROZEN.get(entity.getUUID());
        long until = tickCounter + ticks;
        if (frozen != null) {
            frozen.untilTick = until;
        } else {
            boolean hadNoAi = entity instanceof Mob mob && mob.isNoAi();
            frozen = new Frozen(entity, until, hadNoAi, entity.isNoGravity());
            FROZEN.put(entity.getUUID(), frozen);
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
    }
}
