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
import net.minecraft.world.item.ItemStack;
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

    // 二刀流の 2 本目も送ってあるか
    private static boolean sentSecond = false;

    /** 剣 1 本ぶんの動きの状態（手の向き・剣の向き・速さ・刃の傾き・手前へ引く量・どちらの腕か）。 */
    private static final class Blade {
        // マウスで動かしている「手」の向き（視線から右へ何度・下へ何度）
        float handYaw = SwingConfig.REST_YAW;
        float handPitch = SwingConfig.REST_PITCH;
        // 実際の剣の向きと、その動く速さ（重さがあるので手より少しおくれて、ふりぬける）
        float swordYaw = SwingConfig.REST_YAW;
        float swordPitch = SwingConfig.REST_PITCH;
        float swordYawSpeed = 0.0f;
        float swordPitchSpeed = 0.0f;
        // 刃の面の傾き（振る向きに刃を合わせる）
        float bladeRoll = 0.0f;
        // 剣がブロックにめりこまないように、手前へ引いている量（ブロック）
        float pullBack = 0.0f;
        // 剣をにぎっているのが左腕なら true（毎フレーム決めなおす）
        boolean leftArm = false;
    }

    // 剣 2 本：PRIMARY はふつうの 1 本（メインの手、左手だけ持っているときは左手）、SECOND は二刀流の左手側の 2 本目
    private static final Blade PRIMARY = new Blade();
    private static final Blade SECOND = new Blade();

    // 二刀流（両手に振れる武器を持っている）なら true（毎フレーム決めなおす）
    private static boolean dualWield = false;

    // 長押し中に固定しているカメラの向き
    private static float lockedYaw = 0.0f;
    private static float lockedPitch = 0.0f;

    // 当てた瞬間に剣が止まる残り時間（tick）と、画面ゆれの強さ（度）
    private static float hitStopLeft = 0.0f;
    private static float shakeStrength = 0.0f;
    private static float shakeClock = 0.0f;

    // 両手で構えているなら true（振っている最中に右クリックも押していて、左手が空いているとき）
    private static boolean twoHanded = false;

    /** 両手で構えているなら true。 */
    public static boolean isTwoHanded() {
        return twoHanded;
    }

    /** 左手（オフハンド）に持った武器で振るモードなら true（メインの手が振れる物でなく、左手が振れる物のとき）。 */
    private static boolean isOffhandMode(LocalPlayer player) {
        return !SwordCheck.isSwingItem(player.getMainHandItem()) && SwordCheck.isSwingItem(player.getOffhandItem());
    }

    /** 今、振っている（振れる）手の持ち物を返す（メインの手が先、なければ左手）。 */
    private static ItemStack activeStack(LocalPlayer player) {
        return isOffhandMode(player) ? player.getOffhandItem() : player.getMainHandItem();
    }

    /** 剣が左腕にあるなら true（三人称と一人称の腕の左右を決めるのに使う）。 */
    public static boolean isBladeInLeftArm() {
        return PRIMARY.leftArm;
    }

    /** 左右で向きが逆になる、構えの左右の向き（度）。 */
    private static float restYaw(Blade blade) {
        return blade.leftArm ? -SwingConfig.REST_YAW : SwingConfig.REST_YAW;
    }

    /** 二刀流（メインの手も左手も振れる物）なら true。 */
    private static boolean isDualWield(LocalPlayer player) {
        return SwordCheck.isSwingItem(player.getMainHandItem()) && SwordCheck.isSwingItem(player.getOffhandItem());
    }

    /** 二刀流で構えているか、振っているなら true（三人称の腕の動きに使う）。 */
    public static boolean isSecondBladeOut() {
        return dualWield && (holding || followThroughLeft > 0
                || Math.abs(SECOND.swordYaw - restYaw(SECOND)) > 0.5f
                || Math.abs(SECOND.swordPitch - SwingConfig.REST_PITCH) > 0.5f);
    }

    /** 二刀流の 2 本目の剣の向き（世界の左右の向き）を返す。 */
    public static float secondBladeWorldYaw(LocalPlayer player) {
        return player.getYRot() + SECOND.swordYaw;
    }

    /** 二刀流の 2 本目の剣の向き（世界の上下の向き）を返す。 */
    public static float secondBladeWorldPitch(LocalPlayer player) {
        return Mth.clamp(player.getXRot() + SECOND.swordPitch, -90.0f, 90.0f);
    }

    /** 二刀流の 2 本目の剣が左腕にあるなら true。 */
    public static boolean isSecondBladeInLeftArm() {
        return SECOND.leftArm;
    }

    /** 剣モードが使える状態なら true（振れる物を持っていて、しゃがんでいない、画面を開いていない）。 */
    private static boolean isSwordModeActive(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.gui.screen() != null || player.isSpectator()) {
            return false;
        }
        if (player.isShiftKeyDown()) {
            return false;
        }
        return SwordCheck.isSwingItem(player.getMainHandItem()) || SwordCheck.isSwingItem(player.getOffhandItem());
    }

    /** 振るボタンが今おされているかを調べる（メインの手なら攻撃ボタン、左手持ちなら右クリック）。 */
    private static boolean isAttackButtonPressed(Minecraft minecraft) {
        if (isOffhandMode(minecraft.player)) {
            return minecraft.options.keyUse.isDown();
        }
        return minecraft.options.keyAttack.isDown();
    }

    /** ふつうの攻撃・採掘を止めるべきなら true（剣モード中）。 */
    public static boolean shouldBlockAttack() {
        Minecraft minecraft = Minecraft.getInstance();
        return isSwordModeActive(minecraft) && !isOffhandMode(minecraft.player);
    }

    /** 左手持ちの剣モード中の、ふつうの右クリック（使う・置く）を止めるべきなら true。 */
    public static boolean shouldBlockUse() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!isSwordModeActive(minecraft)) {
            return false;
        }

        // 左手持ちのとき、またはメインの手で振っている最中（右クリックを両手持ちに使う）は止める
        return isOffhandMode(minecraft.player) || holding;
    }

    /** 自分の剣を振っている・構えからずれているなら true（三人称の腕の動きに使う）。 */
    public static boolean isSwordOut() {
        return holding || followThroughLeft > 0
                || Math.abs(PRIMARY.swordYaw - restYaw(PRIMARY)) > 0.5f
                || Math.abs(PRIMARY.swordPitch - SwingConfig.REST_PITCH) > 0.5f;
    }

    /** 自分の剣の向き（世界の左右の向き）を返す。 */
    public static float bladeWorldYaw(LocalPlayer player) {
        return player.getYRot() + PRIMARY.swordYaw;
    }

    /** 自分の剣の向き（世界の上下の向き）を返す。 */
    public static float bladeWorldPitch(LocalPlayer player) {
        return Mth.clamp(player.getXRot() + PRIMARY.swordPitch, -90.0f, 90.0f);
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

        // たまったクリックを捨てる（左手持ちなら右クリック、そうでなければ攻撃）
        KeyMapping attackKey = isOffhandMode(minecraft.player) ? minecraft.options.keyUse : minecraft.options.keyAttack;
        while (attackKey.consumeClick()) {
            // 何もしない（クリックを捨てるだけ）
        }
        if (holding && !isOffhandMode(minecraft.player)) {
            while (minecraft.options.keyUse.consumeClick()) {
                // 振っている最中の右クリックは両手持ちに使うので、ふつうの使うは出さない
            }
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

        // 剣が左腕か右腕かを決める（利き手と、どちらの手に持っているかで決まる）
        boolean isLeftMain = player.getMainArm() == net.minecraft.world.entity.HumanoidArm.LEFT;
        PRIMARY.leftArm = isOffhandMode(player) ? !isLeftMain : isLeftMain;
        SECOND.leftArm = !isLeftMain;
        dualWield = isSwordModeActive(minecraft) && isDualWield(player);

        // 長押しが始まった・終わったを調べる
        boolean wantHold = isSwordModeActive(minecraft) && isAttackButtonPressed(minecraft);
        if (SwingConfig.DEBUG_LOG && wantHold != holding) {
            System.out.println("ikioiken: 長押し " + holding + " -> " + wantHold);
        }
        if (wantHold && !holding) {
            startHolding(player);
        } else if (!wantHold && holding) {
            holding = false;
            followThroughLeft = SwingConfig.FOLLOW_THROUGH_TICKS;
        }

        // 両手持ち：メインの手で振っている最中に右クリックも押していて、左手が空いている
        twoHanded = holding && !isOffhandMode(player) && player.getOffhandItem().isEmpty()
                && minecraft.options.keyUse.isDown();

        // 長押し中はマウスで手を動かす、はなしたら手は構えにもどる
        float frameTicks = Math.min(minecraft.getDeltaTracker().getRealtimeDeltaTicks(), 3.0f);
        if (holding) {
            moveHandByMouse(player);
        } else {
            PRIMARY.handYaw = restYaw(PRIMARY);
            PRIMARY.handPitch = SwingConfig.REST_PITCH;
            SECOND.handYaw = restYaw(SECOND);
            SECOND.handPitch = SwingConfig.REST_PITCH;
        }

        // 剣を手に向かって動かす（当てた瞬間は少し止まる）
        if (hitStopLeft > 0.0f) {
            hitStopLeft -= frameTicks;
        } else {
            moveSwordTowardHand(PRIMARY, activeStack(player), twoHanded, frameTicks);
            if (dualWield) {
                moveSwordTowardHand(SECOND, player.getOffhandItem(), false, frameTicks);
            }
        }

        // 二刀流でないときは、2 本目は構えの位置にもどしておく
        if (!dualWield) {
            SECOND.swordYaw = restYaw(SECOND);
            SECOND.swordPitch = SwingConfig.REST_PITCH;
            SECOND.handYaw = restYaw(SECOND);
            SECOND.handPitch = SwingConfig.REST_PITCH;
            SECOND.swordYawSpeed = 0.0f;
            SECOND.swordPitchSpeed = 0.0f;
            SECOND.bladeRoll = 0.0f;
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
        PRIMARY.handYaw = PRIMARY.swordYaw;
        PRIMARY.handPitch = PRIMARY.swordPitch;
        SECOND.handYaw = SECOND.swordYaw;
        SECOND.handPitch = SECOND.swordPitch;
    }

    /** マウスで動いたぶんを手の向きに回し、カメラは少しだけ動かす。 */
    private static void moveHandByMouse(LocalPlayer player) {
        // このフレームでマウスが動かした角度
        float mouseYaw = player.getYRot() - lockedYaw;
        float mousePitch = player.getXRot() - lockedPitch;

        // 一部はカメラへ、残りは手へ
        float cameraYaw = mouseYaw * SwingConfig.CAMERA_FOLLOW;
        float cameraPitch = mousePitch * SwingConfig.CAMERA_FOLLOW;
        float wantYaw = PRIMARY.handYaw + mouseYaw - cameraYaw;
        float wantPitch = PRIMARY.handPitch + mousePitch - cameraPitch;

        // 二刀流の 2 本目の手も、同じだけマウスで動かす（カメラには関係させない）
        SECOND.handYaw = Mth.clamp(SECOND.handYaw + mouseYaw - cameraYaw, -SwingConfig.MAX_OFFSET_YAW, SwingConfig.MAX_OFFSET_YAW);
        SECOND.handPitch = Mth.clamp(SECOND.handPitch + mousePitch - cameraPitch, -SwingConfig.MAX_OFFSET_PITCH, SwingConfig.MAX_OFFSET_PITCH);

        // 手が動ける広さをこえたぶんはカメラを回す（うしろも向ける）
        PRIMARY.handYaw = Mth.clamp(wantYaw, -SwingConfig.MAX_OFFSET_YAW, SwingConfig.MAX_OFFSET_YAW);
        PRIMARY.handPitch = Mth.clamp(wantPitch, -SwingConfig.MAX_OFFSET_PITCH, SwingConfig.MAX_OFFSET_PITCH);
        cameraYaw += wantYaw - PRIMARY.handYaw;
        cameraPitch += wantPitch - PRIMARY.handPitch;

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
    private static float weaponLightness(ItemStack stack, boolean twoHandedGrip) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return 1.0f;
        }
        float attackSpeed = (float) player.getAttributeValue(Attributes.ATTACK_SPEED);
        float lightness = Mth.clamp(attackSpeed / SwingConfig.NORMAL_ATTACK_SPEED, SwingConfig.MIN_WEIGHT_FACTOR, SwingConfig.MAX_WEIGHT_FACTOR);

        // 剣の特殊能力「高速切り」：剣はもっとすばやく振れる
        if (WeaponSkill.skillOf(stack) == WeaponSkill.SWORD) {
            lightness *= SwingConfig.SWORD_SPEED_BONUS;
        }

        // 素材が重いほどゆっくり、軽いほどすばやく動く
        lightness /= SwordCheck.materialWeight(stack);

        // 両手で握ると、少し重くなる
        if (twoHandedGrip) {
            lightness *= SwingConfig.TWO_HAND_LIGHTNESS;
        }
        return lightness;
    }

    /** 持っている武器の刃が片側だけ（斧）なら true。 */
    private static boolean isOneEdged(ItemStack stack) {
        return WeaponSkill.skillOf(stack) == WeaponSkill.AXE;
    }

    /** 剣を、ばねのように手の向きへ追いかけさせる（重さでおくれて、少し行きすぎてもどる）。 */
    private static void moveSwordTowardHand(Blade blade, ItemStack stack, boolean twoHandedGrip, float frameTicks) {
        float stiffness = (holding ? SwingConfig.SWING_STIFFNESS : SwingConfig.RETURN_STIFFNESS) * weaponLightness(stack, twoHandedGrip);
        float keepSpeed = (float) Math.pow(SwingConfig.SWING_DAMPING, frameTicks);

        // 引っぱる力で速さを変えて、ゆれを止めて、動かす
        blade.swordYawSpeed = (blade.swordYawSpeed + (blade.handYaw - blade.swordYaw) * stiffness * frameTicks) * keepSpeed;
        blade.swordPitchSpeed = (blade.swordPitchSpeed + (blade.handPitch - blade.swordPitch) * stiffness * frameTicks) * keepSpeed;
        blade.swordYaw += blade.swordYawSpeed * frameTicks;
        blade.swordPitch += blade.swordPitchSpeed * frameTicks;

        // 刃を、振っている向きに向ける（速く動いているときだけ）
        float moveSpeed = Math.abs(blade.swordYawSpeed) + Math.abs(blade.swordPitchSpeed);
        if (moveSpeed > 1.0f) {
            // 剣から見た、剣先の動く向き（右・上）。刃（絵の剣の上側のふち）をその向きへ回す角度
            float moveRight = blade.swordYawSpeed * Mth.cos((float) Math.toRadians(blade.swordPitch));
            float moveUp = -blade.swordPitchSpeed;
            float targetRoll = (float) Math.toDegrees(Math.atan2(-moveRight, moveUp));

            // 剣は両側が刃なので、近いほうの刃を向ける（くるっと半回転しない）。斧は刃が片側だけなので、その刃を向ける
            float period = isOneEdged(stack) ? 360.0f : 180.0f;
            float turn = targetRoll - blade.bladeRoll;
            turn = turn - period * Math.round(turn / period);
            float follow = 1.0f - (float) Math.pow(0.4, frameTicks);
            blade.bladeRoll += turn * follow;
            blade.bladeRoll = Mth.wrapDegrees(blade.bladeRoll);
        } else if (!holding) {
            blade.bladeRoll *= (float) Math.pow(0.85, frameTicks);
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
            float bladeYaw = player.getYRot() + PRIMARY.swordYaw;
            float bladePitch = Mth.clamp(player.getXRot() + PRIMARY.swordPitch, -90.0f, 90.0f);
            ClientPlayNetworking.send(new BladePayload(true, bladeYaw, bladePitch, isOffhandMode(player), twoHanded));

            // 二刀流なら、左手の 2 本目の向きも送る
            if (dualWield) {
                float secondYaw = player.getYRot() + SECOND.swordYaw;
                float secondPitch = Mth.clamp(player.getXRot() + SECOND.swordPitch, -90.0f, 90.0f);
                ClientPlayNetworking.send(new BladePayload(true, secondYaw, secondPitch, true, false));
                sentSecond = true;
            }
            sentHolding = true;
            return;
        }

        // 振り終わった：1 回だけ「やめた」を送る
        if (sentHolding) {
            ClientPlayNetworking.send(new BladePayload(false, 0.0f, 0.0f, false, false));
            if (sentSecond) {
                ClientPlayNetworking.send(new BladePayload(false, 0.0f, 0.0f, true, false));
                sentSecond = false;
            }
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

    /** その剣が構えの位置から動いているなら true。 */
    private static boolean isBladeMoved(Blade blade) {
        return Math.abs(blade.swordYaw - restYaw(blade)) > 0.5f
                || Math.abs(blade.swordPitch - SwingConfig.REST_PITCH) > 0.5f
                || Math.abs(blade.bladeRoll) > 1.0f;
    }

    /** 一人称の剣を、今の剣の向きで描く。描いたら true（ふつうの描き方はしない）。構えのまま止まっているときは false。 */
    public static boolean renderFirstPerson(PoseStack poseStack, SubmitNodeCollector collector,
                                            PlayerRenderState playerState) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || !isSwordModeActive(minecraft)) {
            return false;
        }

        // 構えにもどりきっていて長押しもしていないなら、ふつうの描き方
        boolean isMoved = isBladeMoved(PRIMARY) || (dualWield && isBladeMoved(SECOND));
        if (!holding && !isMoved) {
            return false;
        }
        int light = playerState.avatarRenderState.lightCoords;

        // 1 本目を描く。二刀流なら 2 本目（左手）も描く
        renderOneBlade(minecraft, player, poseStack, collector, playerState, light, PRIMARY, activeStack(player), twoHanded);
        if (dualWield) {
            renderOneBlade(minecraft, player, poseStack, collector, playerState, light, SECOND, player.getOffhandItem(), false);
        }
        return true;
    }

    /** 剣 1 本ぶんの、腕と剣を一人称で描く。 */
    private static void renderOneBlade(Minecraft minecraft, LocalPlayer player, PoseStack poseStack,
                                       SubmitNodeCollector collector, PlayerRenderState playerState, int light,
                                       Blade blade, ItemStack stack, boolean twoHandedGrip) {
        // 手の位置から、剣の向きに回して描く（速く振るほど手も少し前に出る）
        float swingPush = Math.min((Math.abs(blade.swordYawSpeed) + Math.abs(blade.swordPitchSpeed)) * 0.006f, 0.12f);

        // 剣先がブロックにめりこみそうなら、そのぶん手ごと手前へ引く（なめらかに）
        float targetPull = measurePullBack(player, blade);
        blade.pullBack += (targetPull - blade.pullBack) * SwingConfig.PULL_BACK_FOLLOW;
        Vector3f handPoint = handPosition(blade, swingPush - blade.pullBack * 0.5f);

        // 剣をにぎっている腕を、肩から手までのばして描く
        renderArm(minecraft, player, poseStack, collector, playerState, light, handPoint, blade.leftArm, blade.bladeRoll);

        // 両手持ちなら、もう片方の腕も、にぎりのうしろをにぎる
        if (twoHandedGrip) {
            float yawRadians = (float) Math.toRadians(blade.swordYaw);
            float pitchRadians = (float) Math.toRadians(blade.swordPitch);
            Vector3f bladeForward = new Vector3f(
                    Mth.cos(pitchRadians) * Mth.sin(yawRadians),
                    -Mth.sin(pitchRadians),
                    -Mth.cos(pitchRadians) * Mth.cos(yawRadians));
            Vector3f backHand = new Vector3f(handPoint).sub(bladeForward.mul(SwingConfig.TWO_HAND_BACK_GAP));
            renderArm(minecraft, player, poseStack, collector, playerState, light, backHand, !blade.leftArm, blade.bladeRoll);
        }

        poseStack.pushPose();
        poseStack.translate(handPoint.x, handPoint.y, handPoint.z);
        poseStack.rotateDegrees(Axis.YP, -blade.swordYaw);
        poseStack.rotateDegrees(Axis.XP, -blade.swordPitch);
        poseStack.rotateDegrees(Axis.ZP, blade.bladeRoll);

        // 手を引いたぶんで足りないところは、剣を手もとへ引きよせる
        poseStack.translate(0.0f, 0.0f, blade.pullBack * 0.5f);

        if (WeaponSkill.skillOf(stack) == WeaponSkill.SPEAR) {
            // 槍の絵は、剣と左右が逆（先が左上）で、2 倍の長さ。にぎりの場所を手に合わせて、先を前に向ける
            float spearScale = SwingConfig.SWORD_SCALE * SwingConfig.SPEAR_SCALE_FACTOR;
            poseStack.translate(0.0f, 0.0f, -0.2f * spearScale);
            poseStack.scale(spearScale, spearScale, spearScale);
            poseStack.rotateDegrees(Axis.YP, -90.0f);
            poseStack.rotateDegrees(Axis.ZP, 45.0f);
            poseStack.translate(-SwingConfig.SPEAR_GRIP_X, -SwingConfig.SPEAR_GRIP_Y, 0.0f);
        } else {
            // にぎりが手に来るように前へずらし、絵の斜めの剣をまっすぐ前に向ける
            poseStack.translate(0.0f, 0.0f, -0.45f * SwingConfig.SWORD_SCALE);
            poseStack.scale(SwingConfig.SWORD_SCALE, SwingConfig.SWORD_SCALE, SwingConfig.SWORD_SCALE);
            poseStack.rotateDegrees(Axis.YP, 90.0f);
            poseStack.rotateDegrees(Axis.ZP, -45.0f);
        }

        minecraft.getItemModelResolver().updateForLiving(SWORD_RENDER_STATE, stack,
                ItemDisplayContext.NONE, player);
        SWORD_RENDER_STATE.submit(poseStack, collector, light, OverlayTexture.NO_OVERLAY, 0);
        poseStack.popPose();
    }

    /** 一人称で、肩から剣をにぎる手まで自分の右腕を描く（スキンもそのまま）。 */
    private static void renderArm(Minecraft minecraft, LocalPlayer player, PoseStack poseStack,
                                  SubmitNodeCollector collector, PlayerRenderState playerState, int light,
                                  Vector3f handPoint, boolean leftArm, float roll) {
        // 透明のときは描かない
        if (player.isInvisible()) {
            return;
        }
        AvatarRenderer<?> avatarRenderer = minecraft.getEntityRenderDispatcher().getRenderer(playerState.avatarRenderState);

        // 肩から手への向き
        Vector3f shoulderPoint = new Vector3f(leftArm ? -SwingConfig.ARM_SHOULDER_X : SwingConfig.ARM_SHOULDER_X,
                SwingConfig.ARM_SHOULDER_Y, SwingConfig.ARM_SHOULDER_Z);
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
        poseStack.rotateDegrees(Axis.YP, -roll);
        if (leftArm) {
            poseStack.translate(-(5.0f / 16.0f + 1.0f / 16.0f), -(2.0f + 10.0f) / 16.0f, 0.0f);
            avatarRenderer.renderLeftHand(poseStack, collector, light,
                    playerState.avatarRenderState.skin.body().texturePath(), playerState.avatarRenderState.showLeftSleeve);
        } else {
            poseStack.translate(5.0f / 16.0f + 1.0f / 16.0f, -(2.0f + 10.0f) / 16.0f, 0.0f);
            avatarRenderer.renderRightHand(poseStack, collector, light,
                    playerState.avatarRenderState.skin.body().texturePath(), playerState.avatarRenderState.showRightSleeve);
        }
        poseStack.popPose();
    }

    /** 肩の左右の位置（右腕なら右、左腕なら左）。 */
    private static float shoulderX(Blade blade) {
        return blade.leftArm ? -SwingConfig.ARM_SHOULDER_X : SwingConfig.ARM_SHOULDER_X;
    }

    /** 一人称の手の位置：肩を中心に、剣の向きへ腕をのばした先（速く振るほど少し前へのびる）。 */
    private static Vector3f handPosition(Blade blade, float swingPush) {
        // 腕の向き（剣と同じ左右、上下は剣より少し下）
        float armYaw = (float) Math.toRadians(blade.swordYaw);
        float armPitch = (float) Math.toRadians(Mth.clamp(blade.swordPitch + SwingConfig.ARM_PITCH_OFFSET, -85.0f, 85.0f));
        Vector3f armDir = new Vector3f(
                Mth.cos(armPitch) * Mth.sin(armYaw),
                -Mth.sin(armPitch),
                -Mth.cos(armPitch) * Mth.cos(armYaw));

        // 肩から腕の長さぶん先が手
        float reach = SwingConfig.ARM_LENGTH + swingPush;
        return new Vector3f(shoulderX(blade), SwingConfig.ARM_SHOULDER_Y, SwingConfig.ARM_SHOULDER_Z)
                .add(armDir.mul(reach));
    }

    /** 剣先から先にブロックがあるとき、めりこまないために引く長さ（ブロック）を返す。 */
    private static float measurePullBack(LocalPlayer player, Blade blade) {
        // 目から剣の向きへ、見た目の剣の長さぶんの線を引いて、ブロックに当たる場所をさがす
        Vec3 eye = player.getEyePosition();
        Vec3 bladeDir = Vec3.directionFromRotation(Mth.clamp(player.getXRot() + blade.swordPitch, -90.0f, 90.0f), player.getYRot() + blade.swordYaw);
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
