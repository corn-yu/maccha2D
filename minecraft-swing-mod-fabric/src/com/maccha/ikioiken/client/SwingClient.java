package com.maccha.ikioiken.client;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.maccha.ikioiken.BladePayload;
import com.maccha.ikioiken.SwingConfig;
import com.maccha.ikioiken.SwordCheck;
import com.maccha.ikioiken.WeaponSkill;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 遊ぶ人の画面側の処理（長押し中はマウスで剣を動かし、重さのある動きで剣を描き、向きをサーバーへ送る）。
 */
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

    // 剣がブロックにめりこまないように、手前へ引いている量（ブロック）
    private static float pullBack = 0.0f;

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
        if (player == null || minecraft.gui.screen() != null || player.isSpectator()) {
            return false;
        }
        if (player.isShiftKeyDown()) {
            return false;
        }
        return SwordCheck.isSwingItem(player.getMainHandItem());
    }

    /** 攻撃ボタン（ふつうは左クリック）が今おされているかを、ゲームのキー設定の状態で調べる。 */
    private static boolean isAttackButtonPressed(Minecraft minecraft) {
        return minecraft.options.keyAttack.isDown();
    }

    /** ふつうの攻撃・採掘を止めるべきなら true（剣モード中）。 */
    public static boolean shouldBlockAttack() {
        return isSwordModeActive(Minecraft.getInstance());
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
    public static void onClientTickStart(Minecraft minecraft) {
        if (!isSwordModeActive(minecraft)) {
            return;
        }

        // たまったクリックを捨てて、押しっぱなし扱いもやめる
        KeyMapping attackKey = minecraft.options.keyAttack;
        while (attackKey.consumeClick()) {
            // 何もしない（クリックを捨てるだけ）
        }
        // 押しっぱなしの状態は消さない（長押しの判定に使うので）。ふつうの攻撃・採掘は差しこみ側で止めている
    }

    /** 毎フレーム、長押しの開始・終了を調べて、マウスの動きを手に回し、剣を重さのある動きで追いかけさせる。 */
    public static void onRenderFrame() {
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
        float frameTicks = Math.min(minecraft.getDeltaTracker().getRealtimeDeltaTicks(), 3.0f);
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
    public static void onClientTickEnd(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
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
            ClientPlayNetworking.send(new BladePayload(true, bladeYaw, bladePitch));
            sentHolding = true;
            return;
        }

        // 振り終わった：1 回だけ「やめた」を送る
        if (sentHolding) {
            ClientPlayNetworking.send(new BladePayload(false, 0.0f, 0.0f));
            sentHolding = false;
        }
    }

    /** 当てたときの画面ゆれの横かたむき（度）。 */
    public static float shakeRoll() {
        return shakeStrength < 0.05f ? 0.0f : shakeStrength * (float) Math.sin(shakeClock * 2.9);
    }

    /** 当てたときの画面ゆれの縦ゆれ（度）。 */
    public static float shakePitch() {
        return shakeStrength < 0.05f ? 0.0f : shakeStrength * 0.5f * (float) Math.sin(shakeClock * 3.7 + 1.0);
    }

    // 一人称の剣を描くための、とりおき
    private static final ItemStackRenderState SWORD_RENDER_STATE = new ItemStackRenderState();

    /** 一人称の剣を、今の剣の向きで描く。描いたら true（ふつうの描き方はしない）。構えのまま止まっているときは false。 */
    public static boolean renderFirstPerson(PoseStack poseStack, SubmitNodeCollector collector,
                                            PlayerRenderState playerState) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || !SwordCheck.isSwingItem(player.getMainHandItem())
                || player.getMainArm() != net.minecraft.world.entity.HumanoidArm.RIGHT) {
            return false;
        }

        // 構えにもどりきっていて長押しもしていないなら、ふつうの描き方
        boolean isMoved = Math.abs(swordYaw - SwingConfig.REST_YAW) > 0.5f
                || Math.abs(swordPitch - SwingConfig.REST_PITCH) > 0.5f
                || Math.abs(bladeRoll) > 1.0f;
        if (!holding && !isMoved) {
            return false;
        }
        int light = playerState.avatarRenderState.lightCoords;

        // 手の位置から、剣の向きに回して描く（速く振るほど手も少し前に出る）
        float swingPush = Math.min((Math.abs(swordYawSpeed) + Math.abs(swordPitchSpeed)) * 0.006f, 0.12f);

        // 剣先がブロックにめりこみそうなら、そのぶん手ごと手前へ引く（なめらかに）
        float targetPull = measurePullBack(player);
        pullBack += (targetPull - pullBack) * SwingConfig.PULL_BACK_FOLLOW;
        Vector3f handPoint = handPosition(swingPush - pullBack * 0.5f);

        // 剣をにぎっている腕を、肩から手までのばして描く
        renderArm(minecraft, player, poseStack, collector, playerState, light, handPoint);

        poseStack.pushPose();
        poseStack.translate(handPoint.x, handPoint.y, handPoint.z);
        poseStack.rotateDegrees(Axis.YP, -swordYaw);
        poseStack.rotateDegrees(Axis.XP, -swordPitch);
        poseStack.rotateDegrees(Axis.ZP, bladeRoll);

        // 手を引いたぶんで足りないところは、剣を手もとへ引きよせる
        poseStack.translate(0.0f, 0.0f, pullBack * 0.5f);

        // にぎりが手に来るように前へずらし、絵の斜めの剣をまっすぐ前に向ける
        poseStack.translate(0.0f, 0.0f, -0.45f * SwingConfig.SWORD_SCALE);
        poseStack.scale(SwingConfig.SWORD_SCALE, SwingConfig.SWORD_SCALE, SwingConfig.SWORD_SCALE);
        poseStack.rotateDegrees(Axis.YP, 90.0f);
        poseStack.rotateDegrees(Axis.ZP, -45.0f);

        minecraft.getItemModelResolver().updateForLiving(SWORD_RENDER_STATE, player.getMainHandItem(),
                ItemDisplayContext.NONE, player);
        SWORD_RENDER_STATE.submit(poseStack, collector, light, OverlayTexture.NO_OVERLAY, 0);
        poseStack.popPose();
        return true;
    }

    /** 一人称で、肩から剣をにぎる手まで自分の右腕を描く（スキンもそのまま）。 */
    private static void renderArm(Minecraft minecraft, LocalPlayer player, PoseStack poseStack,
                                  SubmitNodeCollector collector, PlayerRenderState playerState, int light,
                                  Vector3f handPoint) {
        // 透明のときは描かない
        if (player.isInvisible()) {
            return;
        }
        AvatarRenderer<?> avatarRenderer = minecraft.getEntityRenderDispatcher().getRenderer(playerState.avatarRenderState);

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
        poseStack.rotate(new Quaternionf().rotationTo(0.0f, 1.0f, 0.0f, armDir.x, armDir.y, armDir.z));

        // 刃を向けた向きに合わせて、腕も同じだけひねる
        poseStack.rotateDegrees(Axis.YP, -bladeRoll);
        poseStack.translate(5.0f / 16.0f + 1.0f / 16.0f, -(2.0f + 10.0f) / 16.0f, 0.0f);
        avatarRenderer.renderRightHand(poseStack, collector, light,
                playerState.avatarRenderState.skin.body().texturePath(), playerState.avatarRenderState.showRightSleeve);
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

    /** 剣先から先にブロックがあるとき、めりこまないために引く長さ（ブロック）を返す。 */
    private static float measurePullBack(LocalPlayer player) {
        // 目から剣の向きへ、見た目の剣の長さぶんの線を引いて、ブロックに当たる場所をさがす
        Vec3 eye = player.getEyePosition();
        Vec3 bladeDir = Vec3.directionFromRotation(bladeWorldPitch(player), bladeWorldYaw(player));
        Vec3 tip = eye.add(bladeDir.scale(SwingConfig.VISUAL_BLADE_LENGTH + 0.3));
        BlockHitResult hit = player.level().clip(new ClipContext(eye, tip,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return 0.0f;
        }

        // 剣先がブロックの手前に来るように、足りないぶんだけ引く
        float freeDistance = (float) hit.getLocation().distanceTo(eye) - 0.3f;
        return Mth.clamp(SwingConfig.VISUAL_BLADE_LENGTH - freeDistance, 0.0f, SwingConfig.MAX_PULL_BACK);
    }
}
