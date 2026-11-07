package com.maccha.zombiepanic;

import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.item.ItemStack;

/**
 * モブの強さやそうびを変えるときの、小さな道具あつめ。
 */
public final class MobUtil {

    private MobUtil() {
    }

    /** 能力の元の値を変える（その能力がなければ何もしない）。 */
    public static void setBaseAttribute(LivingEntity entity, Holder<Attribute> attribute, double value) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    /** 能力の元の値に足す。 */
    public static void addBaseAttribute(LivingEntity entity, Holder<Attribute> attribute, double amount) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(instance.getBaseValue() + amount);
        }
    }

    /** 体力の最大値を変えて、満タンにする。 */
    public static void setMaxHealth(LivingEntity entity, double maxHealth) {
        setBaseAttribute(entity, net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH, maxHealth);
        entity.setHealth(entity.getMaxHealth());
    }

    /** ずっと続く効果をつける（つぶつぶは見せない）。 */
    public static void addForeverEffect(LivingEntity entity, Holder<MobEffect> effect, int amplifier) {
        entity.addEffect(new MobEffectInstance(effect, MobEffectInstance.INFINITE_DURATION, amplifier, false, false));
    }

    /** 決まった時間だけ続く効果をつける。 */
    public static void addTimedEffect(LivingEntity entity, Holder<MobEffect> effect, int ticks, int amplifier) {
        entity.addEffect(new MobEffectInstance(effect, ticks, amplifier, false, true));
    }

    /** そうびを持たせる（落とす確率も決める）。 */
    public static void equip(Mob mob, EquipmentSlot slot, ItemStack stack, float dropChance) {
        mob.setItemSlot(slot, stack);
        mob.setDropChance(slot, dropChance);
    }

    /** 動きを変えたことを、まわりの人に知らせる（とばしたあと）。 */
    public static void syncMotion(net.minecraft.world.entity.Entity entity) {
        entity.needsSync = true;
        entity.syncVelocity = true;
    }

    /** その場所にアイテムを落とす（サーバー側だけ）。 */
    public static void drop(net.minecraft.world.entity.Entity entity, ItemStack stack) {
        if (entity.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            entity.spawnAtLocation(serverLevel, stack);
        }
    }

    /** プレイヤーのまわりに音を鳴らす。 */
    public static void playFor(net.minecraft.server.level.ServerPlayer player, net.minecraft.sounds.SoundEvent sound,
                               net.minecraft.sounds.SoundSource source, float volume, float pitch) {
        player.level().playSound(null, player.blockPosition(), sound, source, volume, pitch);
    }
}
