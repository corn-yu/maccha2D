package com.maccha.ikioiken.client;

import com.maccha.ikioiken.BladePayload;
import com.maccha.ikioiken.IkioiKen;
import com.maccha.ikioiken.SwingConfig;
import com.maccha.ikioiken.SwordCheck;
import com.maccha.ikioiken.WeaponSkill;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemDisplayContext;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

/**
 * 遊ぶ人の画面側の処理（長押し中はマウスで剣を動かし、重さのある動きで剣を描き、向きをサーバーへ送る）。
 */
@EventBusSubscriber(modid = IkioiKen.MOD_ID, value = Dist.CLIENT)
public final class SwingClient {

    private SwingClient() {
    }

    // 長押し中かどうか
    private static boolean holding = false;

    // 手をはなしたあと、まだ勢いで切れる残り時間（tick）
    private static int followThroughLeft = 0;

    // サーバーに「振っている」と伝えてあるか
    private static boolean sentHolding = false;

    // マウスで動かしている「手」の向き（視線から右へ何度・下へ何度）
    private static float handYaw = SwingConfig.REST_YAW;
    private static float handPitch = SwingConfig.REST_PITCH;

    // 実際の剣の向きと、その動く速さ（重さがあるので手より少しおくれて、ふりぬける）
    private static float swordYaw = SwingConfig.REST_YAW;
    private static float swordPitch = SwingConfig.REST_PITCH;
    private static float swordYawSpeed = 0.0f;
    private static float swordPitchSpeed = 0.0f;

    // 刃の面の傾き（振る向きに刃を合わせる）
    private static float bladeRoll = 0.0f;

    // 長押し中に固定しているカメラの向き
    private static float lockedYaw = 0.0f;
    private static float lockedPitch = 0.0f;

    // 当てた瞬間に剣が止まる残り時間（tick）と、画面ゆれの強さ（度）
    private static float hitStopLeft = 0.0f;
    private static float shakeStrength = 0.0f;
    private static float shakeClock = 0.0f;

    /** 剣モードが使える状態なら true（振れる物を持っていて、しゃがんでいない、画面を開いていない）。 */
    private static boolean isSwordModeActive(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.screen != null || player.isSpectator()) {
            return false;
        }
        if (player.isShiftKeyDown()) {
            return false;
        }
        return SwordCheck.isSwingItem(player.getMainHandItem());
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

    /** 自分の剣を振っている・構えからずれているなら true（三人称の腕の動きに使う）。 */
    public static boolean isSwordOut() {
        return holding || followThroughLeft > 0
                || Math.abs(swordYaw - SwingConfig.REST_YAW) > 0.5f
                || Math.abs(swordPitch - SwingConfig.REST_PITCH) > 0.5f;
    }

    /** 自分の剣の向き（世界の左右の向き）を返す。 */
    public static float bladeWorldYaw(LocalPlayer player) {
        return player.getYRot() + swordYaw;
    }

    /** 自分の剣の向き（世界の上下の向き）を返す。 */
    public static float bladeWorldPitch(LocalPlayer player) {
        return Mth.clamp(player.getXRot() + swordPitch, -90.0f, 90.0f);
    }

    /** サーバーから「当たった・防いだ」と届いたら、剣を一瞬止めて画面をゆらす。 */
    public static void onHit(float strength) {
        hitStopLeft = Math.max(hitStopLeft, SwingConfig.HITSTOP_TICKS * Math.min(strength, 1.5f));
        shakeStrength = Math.max(shakeStrength, SwingConfig.SHAKE_DEGREES * strength);
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

    /** 毎フレーム、長押しの開始・終了を調べて、マウスの動きを手に回し、剣を重さのある動きで追いかけさせる。 */
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
            followThroughLeft = SwingConfig.FOLLOW_THROUGH_TICKS;
        }

        // 長押し中はマウスで手を動かす、はなしたら手は構えにもどる
        float frameTicks = Math.min(event.getPartialTick().getRealtimeDeltaTicks(), 3.0f);
        if (holding) {
            moveHandByMouse(player);
        } else {
            handYaw = SwingConfig.REST_YAW;
            handPitch = SwingConfig.REST_PITCH;
        }

        // 剣を手に向かって動かす（当てた瞬間は少し止まる）
        if (hitStopLeft > 0.0f) {
            hitStopLeft -= frameTicks;
        } else {
            moveSwordTowardHand(frameTicks);
        }

        // 画面ゆれを少しずつおさめる
        shakeClock += frameTicks;
        shakeStrength *= (float) Math.pow(0.7, frameTicks);
    }

    /** 長押しを始める（今のカメラの向きを固定する）。 */
    private static void startHolding(LocalPlayer player) {
        holding = true;
        followThroughLeft = 0;
        lockedYaw = player.getYRot();
        lockedPitch = player.getXRot();
        handYaw = swordYaw;
        handPitch = swordPitch;
    }

    /** マウスで動いたぶんを手の向きに回し、カメラは少しだけ動かす。 */
    private static void moveHandByMouse(LocalPlayer player) {
        // このフレームでマウスが動かした角度
        float mouseYaw = player.getYRot() - lockedYaw;
        float mousePitch = player.getXRot() - lockedPitch;

        // 一部はカメラへ、残りは手へ
        float cameraYaw = mouseYaw * SwingConfig.CAMERA_FOLLOW;
        float cameraPitch = mousePitch * SwingConfig.CAMERA_FOLLOW;
        float wantYaw = handYaw + mouseYaw - cameraYaw;
        float wantPitch = handPitch + mousePitch - cameraPitch;

        // 手が動ける広さをこえたぶんはカメラを回す（うしろも向ける）
        handYaw = Mth.clamp(wantYaw, -SwingConfig.MAX_OFFSET_YAW, SwingConfig.MAX_OFFSET_YAW);
        handPitch = Mth.clamp(wantPitch, -SwingConfig.MAX_OFFSET_PITCH, SwingConfig.MAX_OFFSET_PITCH);
        cameraYaw += wantYaw - handYaw;
        cameraPitch += wantPitch - handPitch;

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

    /** 持っている武器の軽さ（攻撃速度が速いほど大きい）。大剣や斧は重くゆっくり、短剣は軽くすばやく動く。 */
    private static float weaponLightness() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return 1.0f;
        }
        float attackSpeed = (float) player.getAttributeValue(Attributes.ATTACK_SPEED);
        float lightness = Mth.clamp(attackSpeed / SwingConfig.NORMAL_ATTACK_SPEED, SwingConfig.MIN_WEIGHT_FACTOR, SwingConfig.MAX_WEIGHT_FACTOR);

        // 剣の特殊能力「高速切り」：剣はもっとすばやく振れる
        if (WeaponSkill.skillOf(player.getMainHandItem()) == WeaponSkill.SWORD) {
            lightness *= SwingConfig.SWORD_SPEED_BONUS;
        }
        return lightness;
    }

    /** 持っている武器の刃が片側だけ（斧）なら true。 */
    private static boolean isOneEdged() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && WeaponSkill.skillOf(player.getMainHandItem()) == WeaponSkill.AXE;
    }

    /** 剣を、ばねのように手の向きへ追いかけさせる（重さでおくれて、少し行きすぎてもどる）。 */
    private static void moveSwordTowardHand(float frameTicks) {
        float stiffness = (holding ? SwingConfig.SWING_STIFFNESS : SwingConfig.RETURN_STIFFNESS) * weaponLightness();
        float keepSpeed = (float) Math.pow(SwingConfig.SWING_DAMPING, frameTicks);

        // 引っぱる力で速さを変えて、ゆれを止めて、動かす
        swordYawSpeed = (swordYawSpeed + (handYaw - swordYaw) * stiffness * frameTicks) * keepSpeed;
        swordPitchSpeed = (swordPitchSpeed + (handPitch - swordPitch) * stiffness * frameTicks) * keepSpeed;
        swordYaw += swordYawSpeed * frameTicks;
        swordPitch += swordPitchSpeed * frameTicks;

        // 刃を、振っている向きに向ける（速く動いているときだけ）
        float moveSpeed = Math.abs(swordYawSpeed) + Math.abs(swordPitchSpeed);
        if (moveSpeed > 1.0f) {
            // 剣から見た、剣先の動く向き（右・上）。刃（絵の剣の上側のふち）をその向きへ回す角度
            float moveRight = swordYawSpeed * Mth.cos((float) Math.toRadians(swordPitch));
            float moveUp = -swordPitchSpeed;
            float targetRoll = (float) Math.toDegrees(Math.atan2(-moveRight, moveUp));

            // 剣は両側が刃なので、近いほうの刃を向ける（くるっと半回転しない）。斧は刃が片側だけなので、その刃を向ける
            float period = isOneEdged() ? 360.0f : 180.0f;
            float turn = targetRoll - bladeRoll;
            turn = turn - period * Math.round(turn / period);
            float follow = 1.0f - (float) Math.pow(0.4, frameTicks);
            bladeRoll += turn * follow;
            bladeRoll = Mth.wrapDegrees(bladeRoll);
        } else if (!holding) {
            bladeRoll *= (float) Math.pow(0.85, frameTicks);
        }
    }

    /** tick の終わりに、振っている間（ふりぬき中も）は剣の向きをサーバーへ送る。 */
    @SubscribeEvent
    public static void onClientTickEnd(ClientTickEvent.Post event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || Minecraft.getInstance().getConnection() == null) {
            sentHolding = false;
            return;
        }

        // 長押し中か、はなした直後の勢いが残っているあいだは、剣の向き（世界の向き）を送る
        boolean isSwinging = holding || followThroughLeft > 0;
        if (followThroughLeft > 0) {
            followThroughLeft--;
        }
        if (isSwinging && isSwordModeActive(Minecraft.getInstance())) {
            float bladeYaw = player.getYRot() + swordYaw;
            float bladePitch = Mth.clamp(player.getXRot() + swordPitch, -90.0f, 90.0f);
            PacketDistributor.sendToServer(new BladePayload(true, bladeYaw, bladePitch));
            sentHolding = true;
            return;
        }

        // 振り終わった：1 回だけ「やめた」を送る
        if (sentHolding) {
            PacketDistributor.sendToServer(new BladePayload(false, 0.0f, 0.0f));
            sentHolding = false;
        }
    }

    /** 当てたときの画面ゆれ（カメラを少しかたむける）。 */
    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (shakeStrength < 0.05f) {
            return;
        }
        event.setRoll(event.getRoll() + shakeStrength * (float) Math.sin(shakeClock * 2.9));
        event.setPitch(event.getPitch() + shakeStrength * 0.5f * (float) Math.sin(shakeClock * 3.7 + 1.0));
    }

    /** 一人称の剣を、今の剣の向きで描く（構えのまま止まっているときはふつうの描き方にまかせる）。 */
    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        if (!SwordCheck.isSwingItem(event.getItemStack()) || player.getMainArm() != HumanoidArm.RIGHT) {
            return;
        }

        // 構えにもどりきっていて長押しもしていないなら、ふつうの描き方
        boolean isMoved = Math.abs(swordYaw - SwingConfig.REST_YAW) > 0.5f
                || Math.abs(swordPitch - SwingConfig.REST_PITCH) > 0.5f
                || Math.abs(bladeRoll) > 1.0f;
        if (!holding && !isMoved) {
            return;
        }

        // 手の位置から、剣の向きに回して描く（速く振るほど手も少し前に出る）
        float swingPush = Math.min((Math.abs(swordYawSpeed) + Math.abs(swordPitchSpeed)) * 0.006f, 0.12f);
        PoseStack poseStack = event.getPoseStack();
        Vector3f handPoint = handPosition(swingPush);

        // 剣をにぎっている腕を、肩から手までのばして描く
        renderArm(minecraft, player, poseStack, event, handPoint);

        poseStack.pushPose();
        poseStack.translate(handPoint.x, handPoint.y, handPoint.z);
        poseStack.mulPose(Axis.YP.rotationDegrees(-swordYaw));
        poseStack.mulPose(Axis.XP.rotationDegrees(-swordPitch));
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

    /** 一人称で、肩から剣をにぎる手まで自分の右腕を描く（スキンもそのまま）。 */
    private static void renderArm(Minecraft minecraft, LocalPlayer player, PoseStack poseStack,
                                  RenderHandEvent event, Vector3f handPoint) {
        // 透明のときは描かない
        if (player.isInvisible()) {
            return;
        }
        if (!(minecraft.getEntityRenderDispatcher().getRenderer(player) instanceof PlayerRenderer playerRenderer)) {
            return;
        }

        // 肩から手への向き
        Vector3f shoulderPoint = new Vector3f(SwingConfig.ARM_SHOULDER_X, SwingConfig.ARM_SHOULDER_Y, SwingConfig.ARM_SHOULDER_Z);
        Vector3f armDir = new Vector3f(handPoint).sub(shoulderPoint);
        if (armDir.lengthSquared() < 1.0e-6f) {
            return;
        }
        armDir.normalize();

        // 腕のモデルは「肩（つけ根）から下（+Y）へ 12 ドットのびる」形なので、
        // 手の先を手の位置に置き、+Y を肩→手の向きに回す
        poseStack.pushPose();
        poseStack.translate(handPoint.x, handPoint.y, handPoint.z);
        poseStack.mulPose(new Quaternionf().rotationTo(0.0f, 1.0f, 0.0f, armDir.x, armDir.y, armDir.z));

        // 刃を向けた向きに合わせて、腕も同じだけひねる
        poseStack.mulPose(Axis.YP.rotationDegrees(-bladeRoll));
        poseStack.translate(5.0f / 16.0f + 1.0f / 16.0f, -(2.0f + 10.0f) / 16.0f, 0.0f);
        playerRenderer.renderRightHand(poseStack, event.getMultiBufferSource(), event.getPackedLight(), player);
        poseStack.popPose();
    }

    /** 一人称の手の位置：肩を中心に、剣の向きへ腕をのばした先（速く振るほど少し前へのびる）。 */
    private static Vector3f handPosition(float swingPush) {
        // 腕の向き（剣と同じ左右、上下は剣より少し下）
        float armYaw = (float) Math.toRadians(swordYaw);
        float armPitch = (float) Math.toRadians(Mth.clamp(swordPitch + SwingConfig.ARM_PITCH_OFFSET, -85.0f, 85.0f));
        Vector3f armDir = new Vector3f(
                Mth.cos(armPitch) * Mth.sin(armYaw),
                -Mth.sin(armPitch),
                -Mth.cos(armPitch) * Mth.cos(armYaw));

        // 肩から腕の長さぶん先が手
        float reach = SwingConfig.ARM_LENGTH + swingPush;
        return new Vector3f(SwingConfig.ARM_SHOULDER_X, SwingConfig.ARM_SHOULDER_Y, SwingConfig.ARM_SHOULDER_Z)
                .add(armDir.mul(reach));
    }
}
