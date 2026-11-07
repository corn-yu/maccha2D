package com.maccha.ikioiken;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/**
 * 受けようとしているダメージ 1 回ぶん（止めたり、へらしたりできる）。NeoForge 版のイベントと同じ使い方。
 */
public final class IncomingDamage {

    private final LivingEntity entity;
    private final DamageSource source;
    private float amount;
    private boolean canceled = false;

    public IncomingDamage(LivingEntity entity, DamageSource source, float amount) {
        this.entity = entity;
        this.source = source;
        this.amount = amount;
    }

    public LivingEntity getEntity() {
        return entity;
    }

    public DamageSource getSource() {
        return source;
    }

    public float getAmount() {
        return amount;
    }

    public void setAmount(float amount) {
        this.amount = amount;
    }

    public boolean isCanceled() {
        return canceled;
    }

    public void setCanceled(boolean canceled) {
        this.canceled = canceled;
    }
}
