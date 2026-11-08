package com.maccha.ikioiken;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * スキルツリー「剣の達人の道」。剣を止めて構えると力がたまり、次の一撃が強くなる。
 * 2段目＝当てた敵が少し動けなくなり、継続ダメージ。3段目＝10ダメージをこえる一撃で、当てた場所が真っ二つ。
 */
public final class MasterSkill {

    private MasterSkill() {
    }

    // 力がたまりきる長さ（tick）・止めているとみなす剣の速さ・次の一撃の最大倍率
    private static final int FULL_CHARGE_TICKS = 60;
    private static final double STILL_SPEED = 2.0;
    private static final double MAX_BONUS = 1.5;
    private static final float SPLIT_DAMAGE_LIMIT = 10.0f;

    private static final Map<UUID, Integer> CHARGE = new HashMap<>();

    /** 剣を振っているあいだ毎 tick 呼ぶ。止めて構えているほど力がたまる。 */
    public static void onBladeTick(ServerPlayer player, double tipSpeed) {
        if (SkillTree.tierOf(player, SkillTree.Branch.MASTER) < 1) {
            return;
        }
        if (tipSpeed >= STILL_SPEED) {
            return;
        }
        int charge = Math.min(FULL_CHARGE_TICKS, CHARGE.getOrDefault(player.getUUID(), 0) + 1);
        CHARGE.put(player.getUUID(), charge);

        // たまり具合を画面の下に出す(1 秒に 2 回)。いっぱいになったら光る
        if (charge % 10 == 0 || charge == FULL_CHARGE_TICKS) {
            int percent = charge * 100 / FULL_CHARGE_TICKS;
            player.sendOverlayMessage(Component.literal("溜め " + percent + "%" + (charge >= FULL_CHARGE_TICKS ? "  MAX!" : "")));
        }
        if (charge == FULL_CHARGE_TICKS) {
            ServerLevel level = (ServerLevel) player.level();
            level.sendParticles(ParticleTypes.ENCHANTED_HIT, player.getX(), player.getY() + 1.2, player.getZ(), 20, 0.5, 0.5, 0.5, 0.2);
            level.playSound(null, player.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 1.0f, 1.5f);
        }
    }

    /** たまった力を使って、次の一撃の倍率を返す(たまっていなければ 1 倍)。使うと力は空になる。 */
    public static double takeChargeMultiplier(ServerPlayer player) {
        Integer charge = CHARGE.remove(player.getUUID());
        if (charge == null || SkillTree.tierOf(player, SkillTree.Branch.MASTER) < 1) {
            return 1.0;
        }
        return 1.0 + MAX_BONUS * charge / FULL_CHARGE_TICKS;
    }

    /** 剣をはなしたとき・ログアウトしたとき、たまった力を消す。 */
    public static void reset(UUID playerId) {
        CHARGE.remove(playerId);
    }

    /** 敵に当てたあとの効果。動けなくする・継続ダメージ・真っ二つ。 */
    public static void onHit(ServerPlayer player, LivingEntity target, float dealt, Vec3 hitPoint) {
        int tier = SkillTree.tierOf(player, SkillTree.Branch.MASTER);
        if (tier < 2 || target instanceof Player || !target.isAlive()) {
            return;
        }

        // 少し動けなくして、毒のような継続ダメージ(ウィザー)を与える
        SkillEffects.freeze(target, 20);
        target.addEffect(new MobEffectInstance(MobEffects.WITHER, 80, 0, false, true, false));

        // 10 ダメージをこえた一撃は、当てた場所が真っ二つ(同じだけの追加ダメージ)
        if (tier >= 3 && dealt > SPLIT_DAMAGE_LIMIT) {
            ServerLevel level = (ServerLevel) player.level();
            for (double dy = -1.2; dy <= 1.2; dy += 0.2) {
                level.sendParticles(ParticleTypes.CRIT, hitPoint.x, hitPoint.y + dy, hitPoint.z, 3, 0.05, 0.0, 0.05, 0.3);
                level.sendParticles(ParticleTypes.SWEEP_ATTACK, hitPoint.x, hitPoint.y + dy, hitPoint.z, 1, 0.0, 0.0, 0.0, 0.0);
            }
            level.playSound(null, hitPoint.x, hitPoint.y, hitPoint.z, SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.2f, 0.6f);
            target.setInvulnerableTime(0);
            target.hurtServer(level, level.damageSources().indirectMagic(player, player), dealt);
        }
    }
}
