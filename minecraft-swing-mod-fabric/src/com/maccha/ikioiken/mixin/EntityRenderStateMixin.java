package com.maccha.ikioiken.mixin;

import org.spongepowered.asm.mixin.Mixin;

import com.maccha.ikioiken.client.EntityIdHolder;
import net.minecraft.client.renderer.entity.state.EntityRenderState;

/**
 * 描くための情報に「どの生き物か」と「半端な時間」を持たせるための差しこみ。
 */
@Mixin(EntityRenderState.class)
public abstract class EntityRenderStateMixin implements EntityIdHolder {

    // どの生き物か・描くときの半端な時間
    private int ikioiken$id = -1;
    private float ikioiken$partialTick = 0.0f;

    @Override
    public int ikioiken$getId() {
        return ikioiken$id;
    }

    @Override
    public void ikioiken$setId(int id) {
        this.ikioiken$id = id;
    }

    @Override
    public float ikioiken$getPartialTick() {
        return ikioiken$partialTick;
    }

    @Override
    public void ikioiken$setPartialTick(float partialTick) {
        this.ikioiken$partialTick = partialTick;
    }
}
