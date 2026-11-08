package com.maccha.ikioiken;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.arrow.SpectralArrow;
import net.minecraft.world.phys.Vec3;

/**
 * 弓を引いている間にマウスを動かすと、離した矢がその向きに曲がる（動かした軌跡と曲がる矢にエフェクトが出る）。
 */
public final class BowCurve {

    private BowCurve() {
    }

    /** 最後に届いたマウスの動き（左右・上下の度/tick と、届いたサーバーの tick）。 */
    private record Input(float yawSpeed, float pitchSpeed, int tick) {
    }

    /** 曲がっている最中の矢 1 本（曲がる速さ・曲がりはじめた tick）。 */
    private record CurvingArrow(AbstractArrow arrow, double yawRate, double pitchRate) {
    }

    private static final Map<UUID, Input> INPUTS = new HashMap<>();
    private static final List<CurvingArrow> ARROWS = new ArrayList<>();

    /** マウスの動きが届いたら覚える。速く動かしているあいだは、動かした軌跡を光らせる。 */
    public static void onPayload(BowCurvePayload payload, ServerPlayNetworking.Context context) {
        ServerPlayer player = context.player();
        INPUTS.put(player.getUUID(), new Input(payload.yawSpeed(), payload.pitchSpeed(), player.tickCount));

        double speed = Math.hypot(payload.yawSpeed(), payload.pitchSpeed());
        if (speed < SwingConfig.BOW_CURVE_MIN_SPEED || !(player.level() instanceof ServerLevel level)) {
            return;
        }

        // 目の前の狙っている場所に、動かした向きの光を残す
        Vec3 point = player.getEyePosition().add(player.getLookAngle().scale(2.4));
        float size = (float) Mth.clamp(0.6 + speed * 0.12, 0.6, 1.4);
        level.sendParticles(new DustColorTransitionOptions(0x9FE8FF, 0xB070FF, size),
                point.x, point.y, point.z, 2, 0.04, 0.04, 0.04, 0.0);
        if (player.tickCount % 3 == 0) {
            level.sendParticles(ParticleTypes.END_ROD, point.x, point.y, point.z, 1, 0.03, 0.03, 0.03, 0.0);
        }
    }

    /** 矢が出たとき、本人のマウスの動きが新しければ、曲がる矢にする。 */
    public static void onEntityLoad(Entity entity, ServerLevel level) {
        if (!(entity instanceof Arrow || entity instanceof SpectralArrow)) {
            return;
        }
        AbstractArrow arrow = (AbstractArrow) entity;
        if (!(arrow.getOwner() instanceof ServerPlayer player)) {
            return;
        }
        Input input = INPUTS.get(player.getUUID());
        if (input == null || player.tickCount - input.tick() > SwingConfig.BOW_INPUT_FRESH_TICKS) {
            return;
        }
        if (Math.hypot(input.yawSpeed(), input.pitchSpeed()) < SwingConfig.BOW_CURVE_MIN_SPEED) {
            return;
        }

        // 曲がる速さ（度/tick）。マウスの動きに比例、上限つき
        double yawRate = Mth.clamp(input.yawSpeed() * SwingConfig.BOW_CURVE_GAIN, -SwingConfig.BOW_CURVE_MAX_RATE, SwingConfig.BOW_CURVE_MAX_RATE);
        double pitchRate = Mth.clamp(input.pitchSpeed() * SwingConfig.BOW_CURVE_GAIN, -SwingConfig.BOW_CURVE_MAX_RATE, SwingConfig.BOW_CURVE_MAX_RATE);
        ARROWS.add(new CurvingArrow(arrow, yawRate, pitchRate));

        // 出たときの光（矢のまわりに輪のように）
        Vec3 position = arrow.position();
        level.sendParticles(ParticleTypes.ENCHANT, position.x, position.y, position.z, 14, 0.25, 0.25, 0.25, 0.4);
        level.sendParticles(ParticleTypes.CRIT, position.x, position.y, position.z, 6, 0.15, 0.15, 0.15, 0.3);
    }

    /** 毎 tick、曲がっている矢の向きを少しずつかえて、軌跡を残す。 */
    public static void onServerTick(MinecraftServer server) {
        Iterator<CurvingArrow> iterator = ARROWS.iterator();
        while (iterator.hasNext()) {
            CurvingArrow curving = iterator.next();
            AbstractArrow arrow = curving.arrow();
            Vec3 velocity = arrow.getDeltaMovement();

            // 消えた・止まった（刺さった）・時間切れの矢は、曲げるのをやめる
            if (arrow.isRemoved() || velocity.lengthSqr() < 0.04 || arrow.tickCount > SwingConfig.BOW_CURVE_TICKS
                    || !(arrow.level() instanceof ServerLevel level)) {
                iterator.remove();
                continue;
            }

            // だんだん弱くなる
            double fade = 1.0 - (double) arrow.tickCount / SwingConfig.BOW_CURVE_TICKS;
            arrow.setDeltaMovement(turn(velocity, curving.yawRate() * fade, curving.pitchRate() * fade));

            // 曲がっている矢の軌跡（曲がる向きで色がかわる）
            Vec3 position = arrow.position();
            int from = curving.yawRate() >= 0 ? 0x7FD8FF : 0xFF9A3C;
            int to = Math.abs(curving.pitchRate()) > Math.abs(curving.yawRate())
                    ? (curving.pitchRate() > 0 ? 0xA070FF : 0x7CFF9A) : (curving.yawRate() >= 0 ? 0x3050FF : 0xFF3020);
            level.sendParticles(new DustColorTransitionOptions(from, to, 1.1f), position.x, position.y, position.z, 2, 0.03, 0.03, 0.03, 0.0);
            if (arrow.tickCount % 2 == 0) {
                level.sendParticles(ParticleTypes.END_ROD, position.x, position.y, position.z, 1, 0.02, 0.02, 0.02, 0.0);
            }
        }
    }

    /** 速さはそのまま、向きだけを左右・上下に少し回す（度）。 */
    private static Vec3 turn(Vec3 velocity, double yawDegrees, double pitchDegrees) {
        double speed = velocity.length();
        double horizontal = Math.hypot(velocity.x, velocity.z);

        // 今の向き（左右の角・上下の角、上が正）
        double yaw = Math.atan2(-velocity.x, velocity.z);
        double elevation = Math.atan2(velocity.y, horizontal);

        // マウスの上下は「下を向く」が正なので、上下の角は引く
        yaw += Math.toRadians(yawDegrees);
        elevation = Mth.clamp(elevation - Math.toRadians(pitchDegrees), -1.5, 1.5);

        return new Vec3(-Math.sin(yaw) * Math.cos(elevation) * speed, Math.sin(elevation) * speed,
                Math.cos(yaw) * Math.cos(elevation) * speed);
    }

    /** ワールドを閉じたら、記録を消す。 */
    public static void onServerStopped(MinecraftServer server) {
        INPUTS.clear();
        ARROWS.clear();
    }
}
