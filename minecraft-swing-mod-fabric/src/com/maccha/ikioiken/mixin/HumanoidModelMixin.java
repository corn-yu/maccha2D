package com.maccha.ikioiken.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.maccha.ikioiken.client.MotionClient;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;

/**
 * 人の形のモデルの動きが決まった直後に、いきおい剣のモーション（腕の動き）を足すための差しこみ。
 */
@Mixin(HumanoidModel.class)
public abstract class HumanoidModelMixin {

    /** モデルの動き（setupAnim）が終わったら、腕をモーションに合わせる。 */
    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V", at = @At("TAIL"))
    private void ikioiken$afterSetupAnim(HumanoidRenderState state, CallbackInfo callbackInfo) {
        MotionClient.adjustModel((HumanoidModel<?>) (Object) this, state);
    }
}
