package com.maccha.koemahou;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 魔法の効果（サーバーの中で動く。友だちにも見える）
 */
public class SpellEffects {

    // 魔法ごとの待ち時間（tick。20tick = 1秒）
    static final Map<String, Integer> COOLDOWN_TICKS = Map.of(
            "fireball", 60,
            "heal", 400,
            "warp", 100);

    // ワープで進める いちばん遠い距離（ブロック）
    static final double WARP_DISTANCE = 10.0;

    // だれが、どの魔法を、いつ（ゲームの時刻）まで使えないか
    static final Map<UUID, Map<String, Long>> readyTime = new HashMap<>();

    /** 詠唱のお知らせを受けとったときの入口 */
    public static void onCast(ServerPlayer player, CastPayload payload) {
        if (!player.isAlive() || player.isSpectator()) return;
        if (!COOLDOWN_TICKS.containsKey(payload.spell())) return;
        ServerLevel level = (ServerLevel) player.level();

        // 詠唱失敗：けむりが出るだけ
        if (payload.step() < 0) {
            fizzle(level, player);
            return;
        }
        // 詠唱のとちゅう：まわりに光が集まる
        if (payload.step() < payload.total()) {
            chantGlow(level, player, payload);
            return;
        }

        // 待ち時間がのこっていたら出さない
        long now = level.getGameTime();
        Map<String, Long> times = readyTime.computeIfAbsent(player.getUUID(), id -> new HashMap<>());
        long ready = times.getOrDefault(payload.spell(), 0L);
        if (now < ready) {
            double seconds = Math.ceil((ready - now) / 20.0);
            player.sendOverlayMessage(Component.literal("§7まだ魔力がたまっていない…（あと " + (int) seconds + " 秒）"));
            fizzle(level, player);
            return;
        }
        times.put(payload.spell(), now + COOLDOWN_TICKS.get(payload.spell()));

        // 魔法を出す
        switch (payload.spell()) {
            case "fireball" -> castFireball(level, player);
            case "heal" -> castHeal(level, player);
            case "warp" -> castWarp(level, player);
            default -> { }
        }
    }

    /** 詠唱のとちゅう：魔法の色の光を、行が進むほど多く出す */
    static void chantGlow(ServerLevel level, ServerPlayer player, CastPayload payload) {
        ParticleOptions particle = switch (payload.spell()) {
            case "fireball" -> ParticleTypes.FLAME;
            case "heal" -> ParticleTypes.HAPPY_VILLAGER;
            default -> ParticleTypes.PORTAL;
        };
        int count = 6 + payload.step() * 8;
        Vec3 center = player.position();
        // 足もとに輪を作る
        for (int index = 0; index < count; index++) {
            double angle = Math.PI * 2 * index / count;
            double x = center.x + Math.cos(angle) * 1.2;
            double z = center.z + Math.sin(angle) * 1.2;
            level.sendParticles(particle, x, center.y + 0.1 + payload.step() * 0.3, z, 1, 0, 0, 0, 0);
        }
        // 行が進むほど高い音にする
        float pitch = 0.8f + 0.25f * payload.step();
        playSound(level, player.position(), SoundEvents.AMETHYST_BLOCK_CHIME, 1.0f, pitch);
    }

    /** 詠唱失敗：けむりと しゅっという音 */
    static void fizzle(ServerLevel level, ServerPlayer player) {
        Vec3 position = player.position();
        level.sendParticles(ParticleTypes.SMOKE, position.x, position.y + 1.0, position.z, 20, 0.4, 0.5, 0.4, 0.02);
        playSound(level, position, SoundEvents.FIRE_EXTINGUISH, 0.6f, 1.2f);
    }

    /** ファイアボール：見ている方へ、小さな火の玉を3発 */
    static void castFireball(ServerLevel level, ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        // まんなか・左・右に少しずらして3発
        float[] spread = {0f, -6f, 6f};
        for (float yawOffset : spread) {
            Vec3 direction = Vec3.directionFromRotation(player.getXRot(), player.getYRot() + yawOffset);
            SmallFireball fireball = new SmallFireball(level, player, direction);
            fireball.setPos(eye.x + direction.x, eye.y - 0.1 + direction.y, eye.z + direction.z);
            fireball.setDeltaMovement(direction.scale(1.2));
            level.addFreshEntity(fireball);
        }
        playSound(level, eye, SoundEvents.BLAZE_SHOOT, 1.0f, 0.9f);
        level.sendParticles(ParticleTypes.FLAME, eye.x, eye.y, eye.z, 30, 0.3, 0.3, 0.3, 0.08);
    }

    /** ヒール：ハート4つ回復して、少しのあいだ再生 */
    static void castHeal(ServerLevel level, ServerPlayer player) {
        player.heal(8.0f);
        player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 0));
        Vec3 position = player.position();
        level.sendParticles(ParticleTypes.HEART, position.x, position.y + 1.2, position.z, 8, 0.5, 0.5, 0.5, 0.1);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, position.x, position.y + 1.0, position.z, 30, 0.6, 0.8, 0.6, 0.1);
        playSound(level, position, SoundEvents.PLAYER_LEVELUP, 0.8f, 1.5f);
    }

    /** ワープ：見ている方へ、最大10ブロック瞬間移動（壁の手前で止まる） */
    static void castWarp(ServerLevel level, ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        Vec3 start = player.position();

        // 壁にぶつかるところまでの距離をはかる
        BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(look.scale(WARP_DISTANCE)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        double distance = hit.getType() == HitResult.Type.MISS ? WARP_DISTANCE : hit.getLocation().distanceTo(eye) - 0.6;

        // 遠いところから少しずつ近づけて、体がうまらない場所をさがす
        Vec3 target = null;
        for (double step = distance; step >= 1.0; step -= 0.5) {
            Vec3 move = look.scale(step);
            AABB box = player.getBoundingBox().move(move);
            if (level.noCollision(player, box)) {
                target = start.add(move);
                break;
            }
        }
        if (target == null) {
            player.sendOverlayMessage(Component.literal("§7せまくてワープできない…"));
            fizzle(level, player);
            return;
        }

        // 消える場所と出る場所に、けむりと音
        level.sendParticles(ParticleTypes.PORTAL, start.x, start.y + 1.0, start.z, 40, 0.4, 0.8, 0.4, 0.3);
        playSound(level, start, SoundEvents.ENDERMAN_TELEPORT, 1.0f, 1.0f);
        player.teleportTo(target.x, target.y, target.z);
        player.resetFallDistance();
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, target.x, target.y + 1.0, target.z, 40, 0.4, 0.8, 0.4, 0.1);
        playSound(level, target, SoundEvents.ENDERMAN_TELEPORT, 1.0f, 1.2f);
    }

    /** みんなに聞こえる音を鳴らす */
    static void playSound(ServerLevel level, Vec3 position, SoundEvent sound, float volume, float pitch) {
        level.playSound(null, position.x, position.y, position.z, sound, SoundSource.PLAYERS, volume, pitch);
    }
}
