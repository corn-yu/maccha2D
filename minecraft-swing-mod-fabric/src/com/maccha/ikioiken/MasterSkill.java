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
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * スキルツリー「剣の達人の道」。剣を止めて構えると力がたまり、次の一撃が強くなる。
 * 2段目＝当てた敵が少し動けなくなり、継続ダメージ。3段目＝10ダメージをこえる一撃で、当てた部位から先が切り落とされる。
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
        SkillEffects.freeze(target, 20, player.getUUID(), false);
        target.addEffect(new MobEffectInstance(MobEffects.WITHER, 80, 0, false, true, false));

        // 10 ダメージをこえた一撃は、当てた部位から先が切り落とされる
        if (tier >= 3 && dealt > SPLIT_DAMAGE_LIMIT) {
            ServerLevel level = (ServerLevel) player.level();
            level.playSound(null, hitPoint.x, hitPoint.y, hitPoint.z, SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.2f, 0.6f);

            // どの部位に当たったか：高さで頭・胴・足、胴の高さで体の真ん中から横にはなれていれば腕
            double heightRatio = (hitPoint.y - target.getY()) / Math.max(0.1, target.getBbHeight());
            double sideways = Math.hypot(hitPoint.x - target.getX(), hitPoint.z - target.getZ());
            String partName;
            if (heightRatio >= 0.8) {
                partName = "頭";
                cutHead(player, level, target);
            } else if (heightRatio < 0.4) {
                partName = "足";
                weaken(target, Attributes.MOVEMENT_SPEED, "leg_cut");
            } else if (sideways > target.getBbWidth() * 0.3) {
                partName = "腕";
                weaken(target, Attributes.ATTACK_DAMAGE, "arm_cut");
            } else {
                partName = "胴体";
            }

            // 切り口に赤い粒と斬撃(胴体はそのままダメージが倍、ほかは半分の追加ダメージ)
            level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK,
                    net.minecraft.world.level.block.Blocks.REDSTONE_BLOCK.defaultBlockState()),
                    hitPoint.x, hitPoint.y, hitPoint.z, 30, 0.25, 0.25, 0.25, 0.1);
            level.sendParticles(ParticleTypes.SWEEP_ATTACK, hitPoint.x, hitPoint.y, hitPoint.z, 2, 0.2, 0.2, 0.2, 0.0);
            player.sendOverlayMessage(Component.literal(partName + "を切り落とした！"));
            target.setInvulnerableTime(0);
            float extra = partName.equals("胴体") ? dealt : dealt * 0.5f;
            target.hurtServer(level, level.damageSources().indirectMagic(player, player), extra);
        }
    }

    /** 切り落とした部位の分、能力を弱くする(足＝移動速度、腕＝攻撃力。くりかえすほど弱くなる)。 */
    private static void weaken(LivingEntity target, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute, String name) {
        net.minecraft.world.entity.ai.attributes.AttributeInstance instance = target.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.fromNamespaceAndPath(IkioiKen.MOD_ID, name);
        net.minecraft.world.entity.ai.attributes.AttributeModifier old = instance.getModifier(id);
        double cut = old == null ? -0.5 : Math.max(-0.9, old.amount() - 0.25);
        instance.addOrUpdateTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                id, cut, net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    /** 頭を切り落とされた敵は、前が見えなくなり、近くの別の敵や、てきとうな場所へ向かって暴れだす。 */
    private static void cutHead(ServerPlayer player, ServerLevel level, LivingEntity target) {
        target.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 200, 0, false, false, false));
        if (!(target instanceof net.minecraft.world.entity.Mob mob)) {
            return;
        }

        // 近くにほかの敵がいれば、それを攻撃しはじめる
        java.util.List<LivingEntity> others = level.getEntitiesOfClass(LivingEntity.class, target.getBoundingBox().inflate(12.0),
                other -> other != target && other != player && !(other instanceof Player) && other.isAlive());
        if (!others.isEmpty()) {
            mob.setTarget(others.get(target.getRandom().nextInt(others.size())));
            return;
        }

        // いなければ、てきとうな場所へ歩きだす(プレイヤーを見失う)
        mob.setTarget(null);
        double angle = target.getRandom().nextDouble() * Math.PI * 2.0;
        mob.getNavigation().moveTo(target.getX() + Math.cos(angle) * 8.0, target.getY(), target.getZ() + Math.sin(angle) * 8.0, 1.2);
    }
}
