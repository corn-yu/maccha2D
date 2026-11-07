package com.maccha.ikioiken.client;

import com.maccha.ikioiken.BladePayload;
import com.maccha.ikioiken.IkioiKen;
import com.maccha.ikioiken.SwingConfig;
import com.maccha.ikioiken.SwordCheck;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * 遊ぶ人の画面側の処理（長押し中はマウスで剣を動かし、剣を描き、向きをサーバーへ送る）。
 */
@EventBusSubscriber(modid = IkioiKen.MOD_ID, value = Dist.CLIENT)
public final class SwingClient {

    private SwingClient() {
    }

    // 長押し中かどうか
    private static boolean holding = false;

    // サーバーに「長押し中」と伝えてあるか
    private static boolean sentHolding = false;

    // 剣の向き（視線から右へ何度・下へ何度）
    private static float offsetYaw = SwingConfig.REST_YAW;
    private static float offsetPitch = SwingConfig.REST_PITCH;

    // 刃の面の傾き（振る向きに刃を合わせる）
    private static float bladeRoll = 0.0f;

    // 長押し中に固定しているカメラの向き
    private static float lockedYaw = 0.0f;
    private static float lockedPitch = 0.0f;

    /** 剣モードが使える状態なら true（剣を持っていて、しゃがんでいない、画面を開いていない）。 */
    private static boolean isSwordModeActive(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.screen != null || player.isSpectator()) {
            return false;
        }
        if (player.isShiftKeyDown()) {
            return false;
        }
        return SwordCheck.isSword(player.getMainHandItem());
    }

    /** 攻撃ボタン（ふつうは左クリック）が今おされているかを、本当のマウス・キーの状態で調べる。 */
    private static boolean isAttackButtonPressed(Minecraft minecraft) {
        KeyMapping attackKey = minecraft.options.keyAttack;
        InputConstants.Key key = attackKey.getKey();
        long window = minecraft.getWindow().getWindow();
        if (key.getType() == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS;
        }
        return InputConstants.isKeyDown(window, key.getValue());
    }

    /** tick のはじめに、剣モード中はふつうの攻撃・採掘を止める（他の戦闘 Mod より先に止める）。 */
    @SubscribeEvent
    public static void onClientTickStart(ClientTickEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!isSwordModeActive(minecraft)) {
            return;
        }

        // たまったクリックを捨てて、押しっぱなし扱いもやめる
        KeyMapping attackKey = minecraft.options.keyAttack;
        while (attackKey.consumeClick()) {
            // 何もしない（クリックを捨てるだけ）
        }
        attackKey.setDown(false);
    }

    /** それでもふつうの攻撃が出ようとしたら止める。 */
    @SubscribeEvent
    public static void onAttackKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (event.isAttack() && isSwordModeActive(Minecraft.getInstance())) {
            event.setSwingHand(false);
            event.setCanceled(true);
        }
    }

    /** 毎フレーム、長押しの開始・終了を調べて、マウスの動きを剣に回す。 */
    @SubscribeEvent
    public static void onRenderFrame(RenderFrameEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) {
            holding = false;
            return;
        }

        // 長押しが始まった・終わったを調べる
        boolean wantHold = isSwordModeActive(minecraft) && isAttackButtonPressed(minecraft);
        if (wantHold && !holding) {
            startHolding(player);
        } else if (!wantHold && holding) {
            holding = false;
        }

        // 長押し中はマウスで剣を動かす、はなしたら構えにもどす
        float frameTicks = event.getPartialTick().getRealtimeDeltaTicks();
        if (holding) {
            moveSwordByMouse(player, frameTicks);
        } else {
            returnToRest(frameTicks);
        }
    }

    /** 長押しを始める（今のカメラの向きを固定する）。 */
    private static void startHolding(LocalPlayer player) {
        holding = true;
        lockedYaw = player.getYRot();
        lockedPitch = player.getXRot();
    }

    /** マウスで動いたぶんを剣の向きに回し、カメラは少しだけ動かす。 */
    private static void moveSwordByMouse(LocalPlayer player, float frameTicks) {
        // このフレームでマウスが動かした角度
        float mouseYaw = player.getYRot() - lockedYaw;
        float mousePitch = player.getXRot() - lockedPitch;

        // 一部はカメラへ、残りは剣へ
        float cameraYaw = mouseYaw * SwingConfig.CAMERA_FOLLOW;
        float cameraPitch = mousePitch * SwingConfig.CAMERA_FOLLOW;
        float wantYaw = offsetYaw + mouseYaw - cameraYaw;
        float wantPitch = offsetPitch + mousePitch - cameraPitch;

        // 剣が動ける広さをこえたぶんはカメラを回す（うしろも向ける）
        float newOffsetYaw = Mth.clamp(wantYaw, -SwingConfig.MAX_OFFSET_YAW, SwingConfig.MAX_OFFSET_YAW);
        float newOffsetPitch = Mth.clamp(wantPitch, -SwingConfig.MAX_OFFSET_PITCH, SwingConfig.MAX_OFFSET_PITCH);
        cameraYaw += wantYaw - newOffsetYaw;
        cameraPitch += wantPitch - newOffsetPitch;

        // 刃の面を振る向きに合わせる
        updateBladeRoll(newOffsetYaw - offsetYaw, newOffsetPitch - offsetPitch, frameTicks);
        offsetYaw = newOffsetYaw;
        offsetPitch = newOffsetPitch;

        // カメラの向きを決めなおす
        lockedYaw += cameraYaw;
        lockedPitch = Mth.clamp(lockedPitch + cameraPitch, -90.0f, 90.0f);
        float fixYaw = lockedYaw - player.getYRot();
        float fixPitch = lockedPitch - player.getXRot();
        player.setYRot(lockedYaw);
        player.setXRot(lockedPitch);
        player.yRotO += fixYaw;
        player.xRotO += fixPitch;
    }

    /** 振っている向きに合わせて、刃の面の傾きを少しずつ変える。 */
    private static void updateBladeRoll(float moveYaw, float movePitch, float frameTicks) {
        if (Math.abs(moveYaw) + Math.abs(movePitch) < 0.5f) {
            return;
        }
        float targetRoll = (float) Math.toDegrees(Math.atan2(Math.abs(moveYaw), Math.abs(movePitch)));
        float follow = 1.0f - (float) Math.pow(0.5, frameTicks);
        bladeRoll += (targetRoll - bladeRoll) * follow;
    }

    /** 手をはなしているとき、剣を元の構えにゆっくりもどす。 */
    private static void returnToRest(float frameTicks) {
        float follow = 1.0f - (float) Math.pow(1.0f - SwingConfig.RETURN_SPEED, frameTicks);
        offsetYaw += (SwingConfig.REST_YAW - offsetYaw) * follow;
        offsetPitch += (SwingConfig.REST_PITCH - offsetPitch) * follow;
        bladeRoll += (0.0f - bladeRoll) * follow;
    }

    /** tick の終わりに、長押し中なら剣の向きをサーバーへ送る。 */
    @SubscribeEvent
    public static void onClientTickEnd(ClientTickEvent.Post event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || Minecraft.getInstance().getConnection() == null) {
            sentHolding = false;
            return;
        }

        // 長押し中：今の剣の向き（世界の向き）を送る
        if (holding) {
            float bladeYaw = player.getYRot() + offsetYaw;
            float bladePitch = Mth.clamp(player.getXRot() + offsetPitch, -90.0f, 90.0f);
            PacketDistributor.sendToServer(new BladePayload(true, bladeYaw, bladePitch));
            sentHolding = true;
            return;
        }

        // はなした：1 回だけ「やめた」を送る
        if (sentHolding) {
            PacketDistributor.sendToServer(new BladePayload(false, 0.0f, 0.0f));
            sentHolding = false;
        }
    }

    /** 一人称の剣を、今の剣の向きで描く（構えのままのときはふつうの描き方にまかせる）。 */
    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        if (!SwordCheck.isSword(event.getItemStack()) || player.getMainArm() != HumanoidArm.RIGHT) {
            return;
        }

        // 構えにもどりきっていて長押しもしていないなら、ふつうの描き方
        boolean isMoved = Math.abs(offsetYaw - SwingConfig.REST_YAW) > 0.5f
                || Math.abs(offsetPitch - SwingConfig.REST_PITCH) > 0.5f;
        if (!holding && !isMoved) {
            return;
        }

        // 手の位置から、剣の向きに回して描く
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(SwingConfig.HAND_X, SwingConfig.HAND_Y, SwingConfig.HAND_Z);
        poseStack.mulPose(Axis.YP.rotationDegrees(-offsetYaw));
        poseStack.mulPose(Axis.XP.rotationDegrees(-offsetPitch));
        poseStack.mulPose(Axis.ZP.rotationDegrees(bladeRoll));

        // にぎりが手に来るように前へずらし、絵の斜めの剣をまっすぐ前に向ける
        poseStack.translate(0.0f, 0.0f, -0.45f * SwingConfig.SWORD_SCALE);
        poseStack.scale(SwingConfig.SWORD_SCALE, SwingConfig.SWORD_SCALE, SwingConfig.SWORD_SCALE);
        poseStack.mulPose(Axis.YP.rotationDegrees(90.0f));
        poseStack.mulPose(Axis.ZP.rotationDegrees(-45.0f));

        minecraft.getEntityRenderDispatcher().getItemInHandRenderer().renderItem(
                player, event.getItemStack(), ItemDisplayContext.NONE, false,
                poseStack, event.getMultiBufferSource(), event.getPackedLight());
        poseStack.popPose();

        // ふつうの描き方はしない
        event.setCanceled(true);
    }
}
