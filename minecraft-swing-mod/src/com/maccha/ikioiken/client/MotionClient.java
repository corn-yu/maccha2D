package com.maccha.ikioiken.client;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.maccha.ikioiken.IkioiKen;
import com.maccha.ikioiken.MotionPayload;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLivingEvent;

/**
 * 敵の「ため → 振り下ろし → もどり」と、まわりの人の剣を振る腕を、体の動き（モーション）として見せるクラス。
 */
@EventBusSubscriber(modid = IkioiKen.MOD_ID, value = Dist.CLIENT)
public final class MotionClient {

    private MotionClient() {
    }

    /** 敵 1 体ぶんの攻撃の動き（いつためはじめて、いつ振ったか）。 */
    private static final class EnemyMotion {
        float windupStart;
        float windupLength;
        float strikeStart = -1.0f;
    }

    /** ほかのプレイヤー 1 人ぶんの剣の向き。 */
    private record PlayerBlade(float yaw, float pitch, float receivedTime) {
    }

    /** 今の動きの様子（体のかたむき・前への踏みこみ・腕）。 */
    private record Pose(float leanDegrees, float lungeBlocks, float armRaise, float armSwing, float twist, float blend) {
    }

    // 振り下ろしにかかる時間と、もどるのにかかる時間（tick）
    private static final float STRIKE_TICKS = 3.0f;
    private static final float RECOVER_TICKS = 7.0f;

    // ためでのけぞる角度・振り下ろしで前にかたむく角度（度）・踏みこむ距離（ブロック）
    private static final float WINDUP_LEAN = 12.0f;
    private static final float STRIKE_LEAN = 16.0f;
    private static final float STRIKE_LUNGE = 0.35f;

    // 敵ごとの攻撃の動き、プレイヤーごとの剣の向き
    private static final Map<Integer, EnemyMotion> ENEMY_MOTIONS = new HashMap<>();
    private static final Map<Integer, PlayerBlade> PLAYER_BLADES = new HashMap<>();

    // 体をかたむけて描いている途中の生き物（描き終わりでもとにもどす）
    private static final Set<Integer> LEANING_NOW = new HashSet<>();

    /** 今の時間（tick、小数つき）を返す。 */
    private static float now(float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return 0.0f;
        }
        return (float) (minecraft.level.getGameTime() % 1000000L) + partialTick;
    }

    /** サーバーから動きの知らせが届いたら覚える。 */
    public static void onMotion(MotionPayload payload) {
        float currentTime = now(0.0f);
        switch (payload.kind()) {
            case MotionPayload.ENEMY_WINDUP -> {
                EnemyMotion motion = new EnemyMotion();
                motion.windupStart = currentTime;
                motion.windupLength = Math.max(1.0f, payload.first());
                ENEMY_MOTIONS.put(payload.entityId(), motion);
            }
            case MotionPayload.ENEMY_STRIKE -> {
                EnemyMotion motion = ENEMY_MOTIONS.computeIfAbsent(payload.entityId(), id -> new EnemyMotion());
                motion.strikeStart = currentTime;
            }
            case MotionPayload.PLAYER_BLADE -> PLAYER_BLADES.put(payload.entityId(),
                    new PlayerBlade(payload.first(), payload.second(), currentTime));
            case MotionPayload.PLAYER_BLADE_END -> PLAYER_BLADES.remove(payload.entityId());
            default -> {
            }
        }
    }

    /** ゆっくり始まってゆっくり終わる 0〜1 の変化。 */
    private static float smooth(float progress) {
        float clamped = Mth.clamp(progress, 0.0f, 1.0f);
        return clamped * clamped * (3.0f - 2.0f * clamped);
    }

    /** 速く始まってゆっくり止まる 0〜1 の変化（振り下ろし用）。 */
    private static float fastStart(float progress) {
        float clamped = Mth.clamp(progress, 0.0f, 1.0f);
        return 1.0f - (1.0f - clamped) * (1.0f - clamped) * (1.0f - clamped);
    }

    /** 敵の今の動きを計算する（動きがなければ null）。 */
    private static Pose enemyPose(int entityId, float partialTick) {
        EnemyMotion motion = ENEMY_MOTIONS.get(entityId);
        if (motion == null) {
            return null;
        }
        float currentTime = now(partialTick);

        // ため中：のけぞって腕をふりかぶる
        if (motion.strikeStart < 0.0f) {
            float elapsed = currentTime - motion.windupStart;
            if (elapsed > motion.windupLength + 20.0f || elapsed < -5.0f) {
                ENEMY_MOTIONS.remove(entityId);
                return null;
            }
            float windup = smooth(elapsed / motion.windupLength);
            return new Pose(-WINDUP_LEAN * windup, 0.0f, windup, 0.0f, windup, windup);
        }

        // 振り下ろし：前にかたむいて踏みこみ、腕を一気に振る
        float sinceStrike = currentTime - motion.strikeStart;
        if (sinceStrike < STRIKE_TICKS) {
            float swing = fastStart(sinceStrike / STRIKE_TICKS);
            float lean = Mth.lerp(swing, -WINDUP_LEAN, STRIKE_LEAN);
            return new Pose(lean, STRIKE_LUNGE * swing, 1.0f - swing, swing, 1.0f - 2.0f * swing, 1.0f);
        }

        // もどり：ゆっくり元の姿勢へ
        float recover = 1.0f - smooth((sinceStrike - STRIKE_TICKS) / RECOVER_TICKS);
        if (recover <= 0.0f) {
            ENEMY_MOTIONS.remove(entityId);
            return null;
        }
        return new Pose(STRIKE_LEAN * recover, STRIKE_LUNGE * recover, 0.0f, 1.0f, -1.0f, recover);
    }

    /** 描く前に、攻撃中の敵の体全体をかたむけて踏みこませる（どんな形の敵でも動く）。 */
    @SubscribeEvent
    public static void onRenderLivingStart(RenderLivingEvent.Pre<?, ?> event) {
        LivingEntity entity = event.getEntity();
        Pose pose = enemyPose(entity.getId(), event.getPartialTick());
        if (pose == null) {
            return;
        }

        // 敵の正面と右の向き
        float bodyYaw = Mth.rotLerp(event.getPartialTick(), entity.yBodyRotO, entity.yBodyRot);
        double yawRadians = Math.toRadians(bodyYaw);
        Vector3f front = new Vector3f((float) -Math.sin(yawRadians), 0.0f, (float) Math.cos(yawRadians));
        Vector3f rightSide = new Vector3f((float) -Math.cos(yawRadians), 0.0f, (float) -Math.sin(yawRadians));

        // 腰を中心に、前へ踏みこみながらかたむける
        float hipHeight = entity.getBbHeight() * 0.45f;
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        LEANING_NOW.add(entity.getId());
        poseStack.translate(front.x * pose.lungeBlocks(), hipHeight, front.z * pose.lungeBlocks());
        poseStack.mulPose(new Quaternionf().rotationAxis((float) Math.toRadians(-pose.leanDegrees()), rightSide));
        poseStack.translate(0.0f, -hipHeight, 0.0f);
    }

    /** 描き終わったら、かたむけたのをもとにもどす。 */
    @SubscribeEvent
    public static void onRenderLivingEnd(RenderLivingEvent.Post<?, ?> event) {
        if (LEANING_NOW.remove(event.getEntity().getId())) {
            event.getPoseStack().popPose();
        }
    }

    /** 人の形のモデルの腕を、攻撃の動きや剣の向きに合わせる（モデルの動きが決まった直後によばれる）。 */
    public static void adjustModel(EntityModel<?> model, LivingEntity entity, float partialTick) {
        if (!(model instanceof HumanoidModel<?> humanoid)) {
            return;
        }

        // プレイヤーなら剣の向きに腕を向ける
        if (entity instanceof Player player) {
            pointArmAlongBlade(humanoid, player, partialTick);
            return;
        }

        // 敵なら、ふりかぶり → 振り下ろし
        Pose pose = enemyPose(entity.getId(), partialTick);
        if (pose == null) {
            return;
        }
        float raisedArm = -2.9f;
        float swungArm = 0.35f;
        float armAngle = pose.armSwing() > 0.0f
                ? Mth.lerp(pose.armSwing(), raisedArm, swungArm)
                : raisedArm;
        humanoid.rightArm.xRot = Mth.lerp(pose.blend(), humanoid.rightArm.xRot, armAngle);
        humanoid.rightArm.zRot = Mth.lerp(pose.blend(), humanoid.rightArm.zRot, 0.15f);
        humanoid.leftArm.xRot = Mth.lerp(pose.blend(), humanoid.leftArm.xRot, -0.7f * pose.armRaise() - 0.3f);
        humanoid.body.yRot = Mth.lerp(pose.blend(), humanoid.body.yRot, 0.35f * pose.twist());
        humanoid.rightArm.yRot = Mth.lerp(pose.blend(), humanoid.rightArm.yRot, 0.35f * pose.twist());
    }

    /** プレイヤーの右腕を、剣の向きに向ける（自分は三人称のとき、ほかの人はいつも）。 */
    private static void pointArmAlongBlade(HumanoidModel<?> humanoid, Player player, float partialTick) {
        float bladeYaw;
        float bladePitch;
        Minecraft minecraft = Minecraft.getInstance();
        if (player == minecraft.player) {
            // 自分：画面側の剣の向きをそのまま使う
            if (!SwingClient.isSwordOut()) {
                return;
            }
            LocalPlayer localPlayer = (LocalPlayer) player;
            bladeYaw = SwingClient.bladeWorldYaw(localPlayer);
            bladePitch = SwingClient.bladeWorldPitch(localPlayer);
        } else {
            // ほかの人：サーバーから届いた剣の向き（しばらく届かなければやめる）
            PlayerBlade blade = PLAYER_BLADES.get(player.getId());
            if (blade == null) {
                return;
            }
            if (now(partialTick) - blade.receivedTime() > 10.0f) {
                PLAYER_BLADES.remove(player.getId());
                return;
            }
            bladeYaw = blade.yaw();
            bladePitch = blade.pitch();
        }

        // 体の向きから見た剣の向きに、右腕を向ける
        float bodyYaw = Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot);
        float relativeYaw = Mth.clamp(Mth.wrapDegrees(bladeYaw - bodyYaw), -100.0f, 100.0f);
        humanoid.rightArm.xRot = (float) Math.toRadians(-90.0f + bladePitch);
        humanoid.rightArm.yRot = (float) Math.toRadians(relativeYaw);
        humanoid.rightArm.zRot = 0.0f;

        // 体も少しひねる
        humanoid.body.yRot = (float) Math.toRadians(relativeYaw * 0.25f);
    }
}
