package com.maccha.zombiepanic;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Consumer;

/**
 * 人間がゾンビに対抗するために編み出した、最後の砦。
 * 呪鉄の剣に 特別な素材を合わせて作る「特殊武器」。なぐった効果と、右クリックの大技がある。
 */
public class SpecialWeaponItem extends Item {

    /** 特殊武器の種類（名前・説明・攻撃力・右クリックの待ち時間）。 */
    public enum Kind {
        FLAME_BLADE("flame_blade", "なぐると 相手が燃える。右クリックで まわりを焼きはらう", 3.5f, 200),
        FROST_BLADE("frost_blade", "なぐると 相手が こおって おそくなる。右クリックで 氷の嵐", 3.5f, 240),
        THUNDER_BLADE("thunder_blade", "ときどき 雷が落ちる。右クリックで 近くの敵に 雷", 3.5f, 240),
        VAMPIRE_BLADE("vampire_blade", "なぐると 体力がもどる。右クリックで 血をすう", 3.0f, 200),
        VENOM_BLADE("venom_blade", "なぐると 毒と ウィザーで じわじわ弱らせる", 3.0f, 0),
        GRAVITY_BLADE("gravity_blade", "なぐると 相手が ふわっと浮く。右クリックで まわりを引きよせる", 3.5f, 200),
        BLAST_BLADE("blast_blade", "なぐると 小さく ばくはつ。右クリックで 大ばくはつ（ブロックは こわれない）", 4.0f, 280),
        WIND_BLADE("wind_blade", "なぐると 大きくふっとぶ。右クリックで 前へ ダッシュ", 3.0f, 120),
        HOLY_BLADE("holy_blade", "ゾンビに 大ダメージ。右クリックで 体力かいふく＆ゾンビを光らせる", 3.0f, 320),
        SHADOW_BLADE("shadow_blade", "なぐると 相手の目が見えなくなる。右クリックで すがたを消して 走る", 3.5f, 280),
        TIME_BLADE("time_blade", "なぐると 相手の足が止まる。右クリックで まわりの時間を止める", 3.0f, 480),
        METEOR_BLADE("meteor_blade", "右クリックで 見ている場所に 隕石を ふらせる", 4.0f, 320),
        REAPER_BLADE("reaper_blade", "体力が少ない相手は 一撃でたおす", 4.5f, 0),
        CHAIN_BLADE("chain_blade", "なぐった相手の まわりの敵にも ダメージが 広がる", 3.5f, 0);

        public final String id;
        public final String description;
        public final float attackDamage;
        // 右クリックの待ち時間（tick。0 なら右クリックの技はない）
        public final int cooldownTicks;

        /** 種類ごとの決まりを持つ。 */
        Kind(String id, String description, float attackDamage, int cooldownTicks) {
            this.id = id;
            this.description = description;
            this.attackDamage = attackDamage;
            this.cooldownTicks = cooldownTicks;
        }
    }

    private final Kind kind;

    /** どの種類の武器かを決める。 */
    public SpecialWeaponItem(Properties properties, Kind kind) {
        super(properties);
        this.kind = kind;
    }

    // ---------------- なぐったとき ----------------

    /** なぐった相手に、武器ごとの効果をつける。 */
    @Override
    public void hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        super.hurtEnemy(stack, target, attacker);
        if (!(target.level() instanceof ServerLevel level)) {
            return;
        }
        Player player = attacker instanceof Player p ? p : null;
        switch (kind) {
            case FLAME_BLADE -> target.igniteForSeconds(6);
            case FROST_BLADE -> {
                MobUtil.addTimedEffect(target, MobEffects.SLOWNESS, 100, 2);
                target.setTicksFrozen(Math.max(target.getTicksFrozen(), 200));
            }
            case THUNDER_BLADE -> {
                if (level.getRandom().nextInt(100) < 15) {
                    strikeLightningAt(level, target.position());
                }
            }
            case VAMPIRE_BLADE -> attacker.heal(3.0f);
            case VENOM_BLADE -> {
                MobUtil.addTimedEffect(target, MobEffects.POISON, 160, 1);
                MobUtil.addTimedEffect(target, MobEffects.WITHER, 60, 0);
            }
            case GRAVITY_BLADE -> MobUtil.addTimedEffect(target, MobEffects.LEVITATION, 40, 1);
            case BLAST_BLADE -> {
                level.explode(attacker, target.getX(), target.getY() + 0.5, target.getZ(), 1.5f, Level.ExplosionInteraction.NONE);
            }
            case WIND_BLADE -> {
                Vec3 away = target.position().subtract(attacker.position()).multiply(1, 0, 1).normalize().scale(2.0);
                target.setDeltaMovement(target.getDeltaMovement().add(away.x, 0.6, away.z));
                MobUtil.syncMotion(target);
            }
            case HOLY_BLADE -> {
                if (target instanceof Zombie && player != null) {
                    target.hurt(level.damageSources().playerAttack(player), 8.0f);
                    level.sendParticles(ParticleTypes.END_ROD, target.getX(), target.getY() + 1.0, target.getZ(), 12, 0.3, 0.6, 0.3, 0.05);
                }
            }
            case SHADOW_BLADE -> MobUtil.addTimedEffect(target, MobEffects.BLINDNESS, 100, 0);
            case TIME_BLADE -> MobUtil.addTimedEffect(target, MobEffects.SLOWNESS, 50, 6);
            case REAPER_BLADE -> {
                if (target.getHealth() <= target.getMaxHealth() * 0.25f && player != null) {
                    float finisher = (target instanceof Zombie bossCheck && BossZombies.isBoss(bossCheck)) ? 30.0f : 10000.0f;
                    target.hurt(level.damageSources().playerAttack(player), finisher);
                    level.sendParticles(ParticleTypes.SOUL, target.getX(), target.getY() + 1.0, target.getZ(), 20, 0.3, 0.8, 0.3, 0.05);
                }
            }
            case CHAIN_BLADE -> {
                if (player != null) {
                    for (Monster other : level.getEntitiesOfClass(Monster.class, target.getBoundingBox().inflate(4.0))) {
                        if (other != target) {
                            other.hurt(level.damageSources().playerAttack(player), 5.0f);
                            AbilityMoves.drawLine(level, target.getEyePosition(), other.getEyePosition(), ParticleTypes.ELECTRIC_SPARK);
                        }
                    }
                }
            }
            default -> {
            }
        }
    }

    // ---------------- 右クリック ----------------

    /** 右クリックの大技（待ち時間がある武器だけ）。 */
    @Override
    public InteractionResult use(Level world, Player player, InteractionHand hand) {
        if (kind.cooldownTicks <= 0 || !(world instanceof ServerLevel level)) {
            return InteractionResult.PASS;
        }
        if (player.getCooldowns().isOnCooldown(player.getItemInHand(hand))) {
            return InteractionResult.PASS;
        }
        useBigMove(level, player);
        player.getCooldowns().addCooldown(player.getItemInHand(hand), kind.cooldownTicks);
        return InteractionResult.SUCCESS;
    }

    /** 武器ごとの大技。 */
    private void useBigMove(ServerLevel level, Player player) {
        Vec3 pos = player.position();
        switch (kind) {
            case FLAME_BLADE -> {
                AbilityMoves.drawRing(level, pos.add(0, 0.3, 0), 6.0, ParticleTypes.FLAME, 48);
                for (Monster mob : nearbyMonsters(level, player, 6.0)) {
                    mob.igniteForSeconds(8);
                    mob.hurt(level.damageSources().playerAttack(player), 6.0f);
                }
                sound(level, player, SoundEvents.BLAZE_SHOOT, 0.6f);
            }
            case FROST_BLADE -> {
                AbilityMoves.drawRing(level, pos.add(0, 0.3, 0), 7.0, ParticleTypes.SNOWFLAKE, 56);
                for (Monster mob : nearbyMonsters(level, player, 7.0)) {
                    MobUtil.addTimedEffect(mob, MobEffects.SLOWNESS, 160, 4);
                    mob.setTicksFrozen(mob.getTicksRequiredToFreeze() + 100);
                    mob.hurt(level.damageSources().playerAttack(player), 4.0f);
                }
                sound(level, player, SoundEvents.GLASS_BREAK, 0.6f);
            }
            case THUNDER_BLADE -> {
                int struck = 0;
                for (Monster mob : nearbyMonsters(level, player, 16.0)) {
                    strikeLightningAt(level, mob.position());
                    if (++struck >= 3) {
                        break;
                    }
                }
            }
            case VAMPIRE_BLADE -> {
                float total = 0;
                for (Monster mob : nearbyMonsters(level, player, 6.0)) {
                    mob.hurt(level.damageSources().playerAttack(player), 4.0f);
                    AbilityMoves.drawLine(level, mob.getEyePosition(), player.getEyePosition(), ParticleTypes.DAMAGE_INDICATOR);
                    total += 3.0f;
                }
                player.heal(Math.min(total, 12.0f));
            }
            case GRAVITY_BLADE -> {
                for (Monster mob : nearbyMonsters(level, player, 9.0)) {
                    Vec3 toPlayer = pos.subtract(mob.position()).normalize().scale(1.2);
                    mob.setDeltaMovement(mob.getDeltaMovement().add(toPlayer.x, 0.3, toPlayer.z));
                    MobUtil.syncMotion(mob);
                }
                AbilityMoves.drawRing(level, pos.add(0, 0.3, 0), 9.0, ParticleTypes.PORTAL, 56);
                sound(level, player, SoundEvents.SHULKER_BULLET_HIT, 0.5f);
            }
            case BLAST_BLADE -> {
                level.explode(player, pos.x, pos.y + 0.5, pos.z, 4.0f, Level.ExplosionInteraction.NONE);
                for (Monster mob : nearbyMonsters(level, player, 5.0)) {
                    mob.hurt(level.damageSources().playerAttack(player), 10.0f);
                }
            }
            case WIND_BLADE -> {
                Vec3 look = player.getLookAngle();
                player.setDeltaMovement(look.x * 1.8, 0.3, look.z * 1.8);
                MobUtil.syncMotion(player);
                player.resetFallDistance();
                level.sendParticles(ParticleTypes.CLOUD, pos.x, pos.y + 0.5, pos.z, 20, 0.3, 0.3, 0.3, 0.1);
            }
            case HOLY_BLADE -> {
                player.heal(6.0f);
                MobUtil.addTimedEffect(player, MobEffects.REGENERATION, 100, 1);
                for (Zombie zombie : level.getEntitiesOfClass(Zombie.class, player.getBoundingBox().inflate(16.0))) {
                    MobUtil.addTimedEffect(zombie, MobEffects.GLOWING, 200, 0);
                }
                level.sendParticles(ParticleTypes.HEART, pos.x, pos.y + 2.0, pos.z, 6, 0.4, 0.3, 0.4, 0.0);
            }
            case SHADOW_BLADE -> {
                MobUtil.addTimedEffect(player, MobEffects.INVISIBILITY, 160, 0);
                MobUtil.addTimedEffect(player, MobEffects.SPEED, 160, 2);
                level.sendParticles(ParticleTypes.LARGE_SMOKE, pos.x, pos.y + 1.0, pos.z, 25, 0.4, 0.8, 0.4, 0.02);
            }
            case TIME_BLADE -> {
                for (Monster mob : nearbyMonsters(level, player, 10.0)) {
                    MobUtil.addTimedEffect(mob, MobEffects.SLOWNESS, 70, 9);
                    MobUtil.addTimedEffect(mob, MobEffects.WEAKNESS, 70, 4);
                }
                AbilityMoves.drawRing(level, pos.add(0, 0.3, 0), 10.0, ParticleTypes.END_ROD, 64);
                sound(level, player, SoundEvents.BELL_BLOCK, 0.5f);
            }
            case METEOR_BLADE -> {
                HitResult hit = player.pick(40.0, 1.0f, false);
                Vec3 center = hit.getLocation();
                for (int i = 0; i < 5; i++) {
                    Vec3 spot = center.add((level.getRandom().nextDouble() - 0.5) * 8, 0, (level.getRandom().nextDouble() - 0.5) * 8);
                    AbilityMoves.callMeteorAt(level, spot, player);
                }
            }
            default -> {
            }
        }
    }

    // ---------------- 道具 ----------------

    /** まわりのモンスター（自分から近い順は気にしない）。 */
    private static List<Monster> nearbyMonsters(ServerLevel level, Player player, double radius) {
        return level.getEntitiesOfClass(Monster.class, player.getBoundingBox().inflate(radius));
    }

    /** その場所に雷を落とす。 */
    private static void strikeLightningAt(ServerLevel level, Vec3 pos) {
        LightningBolt bolt = EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.EVENT);
        if (bolt != null) {
            bolt.snapTo(pos.x, pos.y, pos.z);
            level.addFreshEntity(bolt);
        }
    }

    /** 音を鳴らす。 */
    private static void sound(ServerLevel level, Player player, net.minecraft.sounds.SoundEvent sound, float pitch) {
        level.playSound(null, player.blockPosition(), sound, SoundSource.PLAYERS, 1.2f, pitch);
    }

    /** キラキラ光らせる。 */
    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    /** 説明文（最後の砦のひとこと入り）。 */
    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display,
                                Consumer<Component> tooltip, TooltipFlag flag) {
        tooltip.accept(Component.literal(kind.description).withStyle(ChatFormatting.AQUA));
        if (kind.cooldownTicks > 0) {
            tooltip.accept(Component.literal("右クリックの待ち時間: " + kind.cooldownTicks / 20 + "秒").withStyle(ChatFormatting.GRAY));
        }
        tooltip.accept(Component.literal("人間がゾンビに対抗するために編み出した最後の砦").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
    }
}
