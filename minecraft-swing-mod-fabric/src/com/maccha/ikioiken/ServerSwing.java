package com.maccha.ikioiken;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import net.minecraft.world.entity.player.Inventory;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.joml.Vector3f;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;

/**
 * サーバー側で、剣の通り道に敵やブロックがあるか調べて、勢いからダメージ・ガード・パリィ・ブロック壊しをするクラス。
 */
public final class ServerSwing {

    private ServerSwing() {
    }

    /** 一人ぶんの剣の状態（前の tick の剣の向きと肩の位置など）。 */
    private static final class SwingState {
        Vec3 lastBladeDir;
        Vec3 lastShoulder;
        int packetCount = 0;
        int lastPacketGameTick = 0;
        int steadyTicks = 0;
        boolean guarding = false;
        boolean twoHanded = false;
        int lastFastSwingTick = -1000;
        int counterUntilTick = 0;
        double bladeLength = SwingConfig.BLADE_LENGTH;
        final Map<BlockPos, Integer> blockLastHitTick = new HashMap<>();
        boolean wasFast = false;
        final Map<Integer, Integer> lastHitPacket = new HashMap<>();
    }

    /** 1 回の振りで当たった相手の情報。 */
    private record BladeHit(Entity target, Vec3 hitPoint, int step) {
    }

    /** 掘りかけのブロックのひび 1 つぶん（掘り具合・最後に当てたサーバーの tick・ひびのあるワールド）。 */
    private static final class CrackInfo {
        float progress;
        long lastServerTick;
        ServerLevel level;
    }

    // プレイヤーごとの掘りかけのひび（長押しをやめても、しばらく残る）
    private static final Map<UUID, Map<BlockPos, CrackInfo>> CRACKS = new HashMap<>();

    /** そのプレイヤーの、掘りかけのひびの一覧を返す（なければ作る）。 */
    private static Map<BlockPos, CrackInfo> cracksOf(ServerPlayer player) {
        return CRACKS.computeIfAbsent(player.getUUID(), id -> new HashMap<>());
    }

    // プレイヤーごとの剣の状態（二刀流の左手の剣は、別の鍵で持つ）
    private static final Map<UUID, SwingState> STATES = new HashMap<>();

    // いま調べている剣が、二刀流の「左手側の 2 本目」なら true
    private static boolean processingSecondBlade = false;

    /** 二刀流の 2 本目の剣の状態をしまう鍵（プレイヤーの番号を 1 ビットだけ変えたもの）。 */
    private static UUID secondKey(UUID playerId) {
        return new UUID(playerId.getMostSignificantBits() ^ 1L, playerId.getLeastSignificantBits());
    }

    /** いま調べている剣の状態をしまう鍵を返す。 */
    private static UUID stateKey(ServerPlayer player) {
        return processingSecondBlade ? secondKey(player.getUUID()) : player.getUUID();
    }

    /** トライデントの、あとから出る斬撃 1 本ぶん。 */
    private record PendingSlash(UUID playerId, int targetId, long dueTick, double momentumFactor, int slashIndex) {
    }

    /** クワの連撃（何回続けて当てたか・最後に当てた時間）。 */
    private record HoeCombo(int count, int lastHitTick) {
    }

    // プレイヤーごとのクワの連撃（剣をおろしても、少しのあいだは続く）
    private static final Map<UUID, HoeCombo> HOE_COMBOS = new HashMap<>();

    // これから出るトライデントの斬撃と、サーバーの時間（tick）
    private static final List<PendingSlash> PENDING_SLASHES = new ArrayList<>();
    private static long serverTick = 0;

    // 勢いの倍率を一時的に攻撃力へかけるときの名前
    private static final Identifier MOMENTUM_MODIFIER_ID =
            Identifier.fromNamespaceAndPath(IkioiKen.MOD_ID, "momentum");

    // いきおい剣の攻撃をしている最中なら true（なぎ払いを止めるのに使う）
    private static boolean attackingWithMomentum = false;

    // 攻撃のたまり具合（ふつうはクリックの間かくで決まる）を書きかえるための入口
    private static Field attackStrengthField = null;

    /** 剣の向きパケットが届いたら、メインスレッドで処理する。 */
    public static void onBladePayload(BladePayload payload, ServerPlayNetworking.Context context) {
        if (SwingConfig.DEBUG_LOG && context.player().tickCount % 20 == 0) {
            System.out.println("ikioiken: 剣の向きを受け取った holding=" + payload.holding()
                    + " 持ち物=" + context.player().getMainHandItem().getItem()
                    + " 振れる物=" + SwordCheck.isSwingItem(context.player().getMainHandItem())
                    + " 道具=" + SwordCheck.isTool(context.player().getMainHandItem()));
        }
        handleBlade(context.player(), payload);
    }

    /** ワールドを閉じたら、全員の剣の記録を消す（次に開いたワールドに古い記録を持ちこまない）。 */
    public static void onServerStopped(MinecraftServer server) {
        STATES.clear();
        CRACKS.clear();
        PENDING_SLASHES.clear();
        HOE_COMBOS.clear();
    }

    /** ログアウトした人の剣の記録を消す。 */
    public static void onPlayerLogout(ServerGamePacketListenerImpl handler, MinecraftServer server) {
        STATES.remove(handler.getPlayer().getUUID());
        STATES.remove(secondKey(handler.getPlayer().getUUID()));
        HOE_COMBOS.remove(handler.getPlayer().getUUID());
    }

    /** いきおい剣の攻撃の最中なら true（ふつうのなぎ払いを出さないために使う）。 */
    public static boolean isAttackingWithMomentum() {
        return attackingWithMomentum;
    }

    /** ダメージを受ける直前に呼ばれる：ガード・パリィで止めたり、へらしたりする。 */
    public static void interceptDamage(IncomingDamage damage) {
        onIncomingDamage(damage);
    }

    /** 届いた剣の向きで、前の向きからの振りを調べる（左手に持っているときは、そのあいだだけ左右の手を入れかえる）。 */
    private static void handleBlade(ServerPlayer player, BladePayload payload) {
        processingSecondBlade = false;
        if (!payload.offhand()) {
            handleBladeInMainHand(player, payload);
            return;
        }

        // 左手持ち：持ち物を入れかえて、メインの手に左手の武器があるようにして調べる（攻撃力などもそのまま使える）
        Inventory inventory = player.getInventory();
        int selectedSlot = inventory.getSelectedSlot();
        ItemStack realMain = inventory.getItem(selectedSlot);
        ItemStack realOff = inventory.getItem(Inventory.SLOT_OFFHAND);
        if (!SwordCheck.isSwingItem(realOff)) {
            handleBladeInMainHand(player, payload);
            return;
        }
        inventory.setItem(selectedSlot, realOff);
        inventory.setItem(Inventory.SLOT_OFFHAND, realMain);
        moveHandModifiers(player, realMain, realOff);

        // メインの手にも振れる物があれば二刀流：左手の剣は 2 本目として別に記録する
        processingSecondBlade = SwordCheck.isSwingItem(realMain);
        try {
            handleBladeInMainHand(player, payload);
        } finally {
            processingSecondBlade = false;
            // 入れかえをもとにもどす（耐久がへった・こわれた持ち物も、そのまま元の手にもどる）
            ItemStack nowMain = inventory.getItem(selectedSlot);
            ItemStack nowOff = inventory.getItem(Inventory.SLOT_OFFHAND);
            moveHandModifiers(player, nowMain, nowOff);
            inventory.setItem(selectedSlot, nowOff);
            inventory.setItem(Inventory.SLOT_OFFHAND, nowMain);
        }
    }

    /** メインの手の攻撃力などの補正（持ち物の効果）を、別の持ち物のものに入れかえる。 */
    private static void moveHandModifiers(ServerPlayer player, ItemStack from, ItemStack to) {
        from.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance != null) {
                instance.removeModifier(modifier.id());
            }
        });
        to.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance != null) {
                instance.addOrUpdateTransientModifier(modifier);
            }
        });
    }

    /** メインの手に持っている物（左手持ちのときは入れかえたあと）で、前の向きからの振りを調べる。 */
    private static void handleBladeInMainHand(ServerPlayer player, BladePayload payload) {
        UUID playerId = stateKey(player);

        // 長押しをやめた・振れる物を持っていない・観戦中なら記録を消して終わり
        ItemStack heldItem = player.getMainHandItem();
        boolean canSwing = payload.holding()
                && player.isAlive()
                && !player.isSpectator()
                && SwordCheck.isSwingItem(heldItem);
        if (!canSwing) {
            clearState(player);
            return;
        }

        // 両手持ち（左手が空いているときだけ有効）
        boolean twoHandedNow = payload.twoHanded() && !payload.offhand() && player.getOffhandItem().isEmpty();

        // 今の剣の向きと肩の位置
        float bladePitch = Mth.clamp(payload.pitch(), -90.0f, 90.0f);
        Vec3 bladeDir = Vec3.directionFromRotation(bladePitch, payload.yaw());
        Vec3 shoulder = shoulderPosition(player);

        // テレポート・リスポーン・しばらく止まっていたあとは、はじめからやりなおす（遠くまで一気に切らないように）
        SwingState state = STATES.get(playerId);
        if (state != null && (state.lastShoulder.distanceTo(shoulder) > SwingConfig.MAX_SHOULDER_JUMP
                || player.tickCount - state.lastPacketGameTick > 5)) {
            clearState(player);
            state = null;
        }

        // はじめの 1 回目は記録するだけ
        if (state == null) {
            state = new SwingState();
            state.lastBladeDir = bladeDir;
            state.lastShoulder = shoulder;
            state.lastPacketGameTick = player.tickCount;
            STATES.put(playerId, state);
            return;
        }
        state.packetCount++;
        state.lastPacketGameTick = player.tickCount;
        state.twoHanded = twoHandedNow;

        // 刃の長さは、その武器で届く距離に合わせる（長い槍は遠くまで、短剣は近く）
        state.bladeLength = Mth.clamp(player.entityInteractionRange(), SwingConfig.MIN_BLADE_LENGTH, SwingConfig.MAX_BLADE_LENGTH);

        // 槍は、さらに遠くまで届く
        if (WeaponSkill.skillOf(heldItem) == WeaponSkill.SPEAR) {
            state.bladeLength = Math.min(state.bladeLength + SwingConfig.SPEAR_REACH_BONUS, SwingConfig.MAX_BLADE_LENGTH);
        }

        // 1 tick に回しすぎていたら上限までにする（ずる防止）
        bladeDir = limitTurn(state.lastBladeDir, bladeDir);
        double tipSpeed = angleBetween(state.lastBladeDir, bladeDir) * state.bladeLength * 20.0;

        // 止めて構えているか（ガード）・さっき速く振ったか（パリィ）
        updateGuard(player, state, bladeDir, tipSpeed);

        // 剣の達人：止めて構えているほど力がたまる
        if (!processingSecondBlade) {
            MasterSkill.onBladeTick(player, tipSpeed);
        }

        // 速く振ったら「ブンッ」と鳴らして、通り道に軌跡を出す
        playWhooshIfFast(player, state, tipSpeed);
        if (tipSpeed >= SwingConfig.MIN_HIT_SPEED) {
            // スキルツリーの特殊能力（時間を遅くする・止める・斬撃など）。ゆっくりめに振っていても働く
            SkillEffects.onFastSwing(player, new SkillEffects.SwingArc(state.lastBladeDir, bladeDir, state.lastShoulder, shoulder,
                    tipSpeed, state.bladeLength, TrailStyle.of(heldItem)));
        }
        if (tipSpeed >= SwingConfig.TRAIL_SPEED) {
            TrailStyle.Style trailStyle = TrailStyle.of(heldItem);
            spawnTrail(player.level(), state.lastBladeDir, bladeDir, state.lastShoulder, shoulder, tipSpeed, state.bladeLength, trailStyle);

            // トライデントは上と下にもう 1 本ずつ、三本の斬撃の軌跡
            if (WeaponSkill.skillOf(heldItem) == WeaponSkill.TRIDENT) {
                Vec3 gap = new Vec3(0.0, 0.35, 0.0);
                spawnTrail(player.level(), state.lastBladeDir, bladeDir, state.lastShoulder.add(gap), shoulder.add(gap), tipSpeed, state.bladeLength, trailStyle);
                spawnTrail(player.level(), state.lastBladeDir, bladeDir, state.lastShoulder.subtract(gap), shoulder.subtract(gap), tipSpeed, state.bladeLength, trailStyle);
            }
        }

        // 前の向きから今の向きまでの通り道で、当たった相手にダメージ
        List<BladeHit> hits = findHits(player, state, state.lastBladeDir, bladeDir, state.lastShoulder, shoulder);
        double remainingPower = 1.0;
        for (BladeHit hit : hits) {
            boolean didHit = applyMomentumHit(player, state, hit, bladeDir, shoulder, remainingPower);
            if (didHit) {
                remainingPower *= WeaponSkill.skillOf(heldItem) == WeaponSkill.SPEAR
                        ? SwingConfig.SPEAR_PIERCE_FALLOFF : SwingConfig.MULTI_HIT_FALLOFF;
            }
        }

        // 道具なら、当たったブロックを勢いで掘る
        if (SwordCheck.isTool(heldItem)) {
            mineBlocksOnPath(player, state, state.lastBladeDir, bladeDir, state.lastShoulder, shoulder);
        }

        // まわりの人に剣の向きを知らせる（三人称で腕が動いて見える）
        boolean isLeftMain = player.getMainArm() == net.minecraft.world.entity.HumanoidArm.LEFT;
        boolean bladeInLeftArm = payload.offhand() ? !isLeftMain : isLeftMain;
        int motionKind = bladeInLeftArm
                ? (twoHandedNow ? MotionPayload.PLAYER_BLADE_LEFT_TWO : MotionPayload.PLAYER_BLADE_LEFT)
                : (twoHandedNow ? MotionPayload.PLAYER_BLADE_TWO : MotionPayload.PLAYER_BLADE);
        if (processingSecondBlade) {
            motionKind = bladeInLeftArm ? MotionPayload.PLAYER_BLADE_SECOND_LEFT : MotionPayload.PLAYER_BLADE_SECOND;
        }
        Net.sendToPlayersTrackingEntity(player, new MotionPayload(player.getId(), motionKind, payload.yaw(), bladePitch));

        // 次の tick のために覚えておく
        state.lastBladeDir = bladeDir;
        state.lastShoulder = shoulder;
    }

    /** 剣の記録を消す（掘りかけのひびは残す）。 */
    private static void clearState(ServerPlayer player) {
        SwingState state = STATES.remove(stateKey(player));
        if (!processingSecondBlade) {
            MasterSkill.reset(player.getUUID());
        }
        if (state == null) {
            return;
        }
        Net.sendToPlayersTrackingEntity(player, new MotionPayload(player.getId(),
                processingSecondBlade ? MotionPayload.PLAYER_BLADE_SECOND_END : MotionPayload.PLAYER_BLADE_END, 0.0f, 0.0f));
        // 掘りかけのひびは、すぐには消さない（また振れば続きから広がる。しばらく当てなければ消える）
    }

    // 自分用のひびの番号（まわりの人に見せるひびとは別の番号にする）
    private static final int SELF_CRACK_ID_OFFSET = 1_000_000;

    /** ブロックのひびを見せる。stage は 0〜9、-1 でひびを消す。ふつうの命令は本人には送られないので、本人には別の番号で直接送る。 */
    private static void showCracks(ServerPlayer player, ServerLevel level, BlockPos pos, int stage) {
        // まわりの人へ
        level.destroyBlockProgress(player.getId(), pos, stage);

        // 本人へ
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket(
                player.getId() + SELF_CRACK_ID_OFFSET, pos, stage));
    }

    /** プレイヤーの右肩の位置を返す（剣の回転の中心）。 */
    private static Vec3 shoulderPosition(Player player) {
        double yawRadians = Math.toRadians(player.getYRot());
        Vec3 rightSide = new Vec3(-Math.cos(yawRadians), 0.0, -Math.sin(yawRadians));
        return player.getEyePosition()
                .add(0.0, -SwingConfig.SHOULDER_DROP, 0.0)
                .add(rightSide.scale(SwingConfig.SHOULDER_SIDE));
    }

    /** 2 つの向きのあいだの角度（ラジアン）を返す。 */
    private static double angleBetween(Vec3 fromDir, Vec3 toDir) {
        double dot = Mth.clamp(fromDir.dot(toDir), -1.0, 1.0);
        return Math.acos(dot);
    }

    /** 1 tick に回せる角度の上限をこえていたら、上限の向きにちぢめる。 */
    private static Vec3 limitTurn(Vec3 lastDir, Vec3 newDir) {
        double turnAngle = angleBetween(lastDir, newDir);
        double maxAngle = Math.toRadians(SwingConfig.MAX_TURN_DEGREES_PER_TICK);
        if (turnAngle <= maxAngle) {
            return newDir;
        }
        double ratio = maxAngle / turnAngle;
        return lastDir.lerp(newDir, ratio).normalize();
    }

    // ===== ガードとパリィ =====

    /** 剣をじっと止めているか（ガードの準備）と、さっき速く振ったか（パリィの準備）を記録する。 */
    private static void updateGuard(ServerPlayer player, SwingState state, Vec3 bladeDir, double tipSpeed) {
        double turnDegrees = Math.toDegrees(angleBetween(state.lastBladeDir, bladeDir));
        state.steadyTicks = turnDegrees <= SwingConfig.GUARD_MAX_TURN_DEGREES ? state.steadyTicks + 1 : 0;
        state.guarding = state.steadyTicks >= SwingConfig.GUARD_READY_TICKS;
        if (tipSpeed >= SwingConfig.PARRY_SPEED) {
            state.lastFastSwingTick = player.tickCount;
        }
    }

    /** 攻撃を受けたとき、剣が体と相手のあいだにあれば止める（タイミングが合えばパリィ）。 */
    public static void onIncomingDamage(IncomingDamage event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        // 剣を振っている最中か（剣の向きが最近届いているか）
        SwingState state = STATES.get(player.getUUID());
        boolean isSwinging = state != null
                && player.tickCount - state.lastPacketGameTick <= 3
                && SwordCheck.isWeapon(player.getMainHandItem());
        if (!isSwinging) {
            return;
        }

        // 盾でも防げない攻撃（落下・炎・魔法など）や、どこから来たかわからない攻撃は止められない
        DamageSource source = event.getSource();
        if (source.is(DamageTypeTags.BYPASSES_SHIELD) || source.getSourcePosition() == null) {
            return;
        }

        // 攻撃が飛んでくる線（相手 → 自分の胸）
        Entity directAttacker = source.getDirectEntity();
        Vec3 attackFrom = directAttacker != null ? directAttacker.getBoundingBox().getCenter() : source.getSourcePosition();
        Vec3 attackTo = player.getEyePosition().add(0.0, -0.4, 0.0);

        // 剣の線（肩 → 剣先）
        Vec3 bladeStart = state.lastShoulder.add(state.lastBladeDir.scale(0.3));
        Vec3 bladeEnd = state.lastShoulder.add(state.lastBladeDir.scale(state.bladeLength));

        // 剣が攻撃の線のじゃまをしていなければ、ふつうに当たる
        double gap = segmentDistance(attackFrom, attackTo, bladeStart, bladeEnd);
        if (gap > SwingConfig.BLADE_BLOCK_RADIUS) {
            return;
        }

        // さっき速く振っていたらパリィ、止めて構えていたらガード、それ以外は半分だけ止まる
        boolean isParry = player.tickCount - state.lastFastSwingTick <= SwingConfig.PARRY_WINDOW_TICKS;
        if (isParry) {
            parryAttack(event, player, state, directAttacker, bladeEnd);
        } else {
            float blockRatio = state.guarding ? SwingConfig.GUARD_BLOCK_RATIO : SwingConfig.MOVING_BLOCK_RATIO;
            blockAttack(event, player, directAttacker, blockRatio);
        }
    }

    /** 2 本の線のいちばん近いところの距離を返す。 */
    private static double segmentDistance(Vec3 firstStart, Vec3 firstEnd, Vec3 secondStart, Vec3 secondEnd) {
        Vec3 firstDir = firstEnd.subtract(firstStart);
        Vec3 secondDir = secondEnd.subtract(secondStart);
        Vec3 startGap = firstStart.subtract(secondStart);
        double firstLengthSqr = firstDir.dot(firstDir);
        double secondLengthSqr = secondDir.dot(secondDir);
        double firstDotSecond = firstDir.dot(secondDir);
        double firstDotGap = firstDir.dot(startGap);
        double secondDotGap = secondDir.dot(startGap);
        double denominator = firstLengthSqr * secondLengthSqr - firstDotSecond * firstDotSecond;

        // 1 本目の線のどこがいちばん近いか（0〜1）
        double firstPart = denominator > 1.0e-9
                ? Mth.clamp((firstDotSecond * secondDotGap - firstDotGap * secondLengthSqr) / denominator, 0.0, 1.0)
                : 0.0;

        // 2 本目の線のどこがいちばん近いか（0〜1）、はみ出したら 1 本目を決めなおす
        double secondPart = secondLengthSqr > 1.0e-9 ? (firstDotSecond * firstPart + secondDotGap) / secondLengthSqr : 0.0;
        if (secondPart < 0.0) {
            secondPart = 0.0;
            firstPart = firstLengthSqr > 1.0e-9 ? Mth.clamp(-firstDotGap / firstLengthSqr, 0.0, 1.0) : 0.0;
        } else if (secondPart > 1.0) {
            secondPart = 1.0;
            firstPart = firstLengthSqr > 1.0e-9 ? Mth.clamp((firstDotSecond - firstDotGap) / firstLengthSqr, 0.0, 1.0) : 0.0;
        }

        Vec3 firstPoint = firstStart.add(firstDir.scale(firstPart));
        Vec3 secondPoint = secondStart.add(secondDir.scale(secondPart));
        return firstPoint.distanceTo(secondPoint);
    }

    /** 剣で攻撃を受け止める（止めたぶん武器がけずれる）。 */
    private static void blockAttack(IncomingDamage event, ServerPlayer player, Entity directAttacker, float blockRatio) {
        float blockedDamage = event.getAmount() * blockRatio;
        int durabilityCost = Math.max(1, (int) Math.ceil(blockedDamage / SwingConfig.GUARD_DAMAGE_PER_DURABILITY));
        player.getMainHandItem().hurtAndBreak(durabilityCost, player, EquipmentSlot.MAINHAND);

        // ダメージを減らす（全部止めたら攻撃自体をなかったことにする）
        if (blockRatio >= 1.0f) {
            event.setCanceled(true);
        } else {
            event.setAmount(event.getAmount() - blockedDamage);
        }

        // 近くで殴ってきた相手は少しはじき返す
        if (directAttacker instanceof LivingEntity attacker && attacker.distanceTo(player) < 4.0) {
            knock(attacker, 0.4, player.getX() - attacker.getX(), player.getZ() - attacker.getZ());
        }

        // 音と火花で「ガキンッ」
        ServerLevel level = player.level();
        Vec3 sparkPoint = player.getEyePosition().add(player.getLookAngle().scale(0.8));
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.SHIELD_BLOCK, SoundSource.PLAYERS, 1.0f, 1.2f);
        level.sendParticles(ParticleTypes.CRIT, sparkPoint.x, sparkPoint.y, sparkPoint.z, 8, 0.15, 0.15, 0.15, 0.3);
        Net.sendToPlayer(player, new HitPayload(0.5f));
        // 文字を出す設定のときだけ、止めたダメージを見せる
        if (SwingConfig.SHOW_MESSAGES) {
            String word = blockRatio >= 1.0f ? "ガキンッ！ ガード" : "ガッ！ 受け流し";
            player.sendOverlayMessage(Component.literal(String.format("%s（%.1f ダメージを止めた）", word, blockedDamage))
                    .withStyle(ChatFormatting.AQUA));
        }
    }

    /** ピッタリのタイミングで振り返した：攻撃を全部はじき、相手をよろけさせ、次の一撃を強くする。 */
    private static void parryAttack(IncomingDamage event, ServerPlayer player, SwingState state,
                                    Entity directAttacker, Vec3 bladeEnd) {
        event.setCanceled(true);
        state.counterUntilTick = player.tickCount + SwingConfig.COUNTER_TICKS;

        // 飛んできた物（矢・火の玉など）ははね返す
        if (directAttacker instanceof Projectile projectile) {
            Vec3 backward = projectile.getDeltaMovement().scale(-SwingConfig.PARRY_REFLECT_SPEED);
            projectile.setDeltaMovement(backward);
            projectile.setOwner(player);
            projectile.needsSync = true;
        }

        // 殴ってきた相手は大きくはじいて、少しのあいだよろけさせる
        Entity attackerEntity = event.getSource().getEntity();
        if (attackerEntity instanceof LivingEntity attacker && attacker != player) {
            knock(attacker, 1.0, player.getX() - attacker.getX(), player.getZ() - attacker.getZ());
            attacker.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, SwingConfig.PARRY_STUN_TICKS, 3));
            attacker.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, SwingConfig.PARRY_STUN_TICKS, 1));
        }

        // 音と火花で「キィンッ」
        ServerLevel level = player.level();
        Vec3 sparkPoint = player.getEyePosition().add(player.getLookAngle().scale(1.0));
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.5f, 1.8f);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.0f, 1.4f);
        level.sendParticles(ParticleTypes.CRIT, sparkPoint.x, sparkPoint.y, sparkPoint.z, 25, 0.2, 0.2, 0.2, 0.6);
        level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFFFFF), sparkPoint.x, sparkPoint.y, sparkPoint.z, 1, 0.0, 0.0, 0.0, 0.0);
        Net.sendToPlayer(player, new HitPayload(1.3f));
        // 文字を出す設定のときだけ、パリィしたことを見せる
        if (SwingConfig.SHOW_MESSAGES) {
            player.sendOverlayMessage(Component.literal("⚔ キィンッ！ パリィ！ 次の一撃が強くなる！")
                    .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
        }
    }

    // ===== 音と軌跡 =====

    /** 剣先が速く動いたら、振りはじめに 1 回だけ風切り音と腕ふりを出す。 */
    private static void playWhooshIfFast(ServerPlayer player, SwingState state, double tipSpeed) {
        boolean isFast = tipSpeed >= SwingConfig.WHOOSH_SPEED;

        // 遅い → 速い に変わった瞬間だけ鳴らす
        if (isFast && !state.wasFast) {
            float loudness = (float) Mth.clamp(tipSpeed / SwingConfig.REFERENCE_SPEED * 0.5, 0.3, 1.0);
            float pitch = (float) Mth.clamp(0.9 + tipSpeed / SwingConfig.REFERENCE_SPEED * 0.3, 0.9, 1.6);
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, loudness, pitch);
            player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
        }
        state.wasFast = tipSpeed >= SwingConfig.WHOOSH_SPEED * 0.6;
    }

    /** 剣の通り道に、武器のスタイル（色と形）の軌跡を出す。 */
    static void spawnTrail(ServerLevel level, Vec3 lastDir, Vec3 newDir,
                                   Vec3 lastShoulder, Vec3 shoulder, double tipSpeed, double bladeLength,
                                   TrailStyle.Style style) {
        // 速いほど太く明るく
        float trailSize = (float) Mth.clamp(tipSpeed / SwingConfig.REFERENCE_SPEED, 0.6, 1.6) * style.sizeBoost();
        if (style.shape() == TrailStyle.Shape.SHORT) {
            trailSize *= 0.8f;
        } else if (style.shape() == TrailStyle.Shape.WIDE) {
            trailSize *= 1.3f;
        }
        DustColorTransitionOptions trailDust = new DustColorTransitionOptions(style.colorFrom(), style.colorTo(), trailSize);

        // 剣先からどこまで（根もとからの割合）に点を打つか
        double alongStart = style.shape() == TrailStyle.Shape.SHORT ? 0.8 : (style.shape() == TrailStyle.Shape.WIDE ? 0.5 : 0.65);
        double alongStep = style.shape() == TrailStyle.Shape.WIDE ? 0.125 : 0.175;

        // 弧の向きに直角な、横ゆれの向き（ギザギザ・2 本ならびに使う）
        Vec3 side = lastDir.cross(newDir);
        if (side.lengthSqr() < 1.0e-6) {
            side = new Vec3(0.0, 1.0, 0.0);
        }
        side = side.normalize();

        // 弧にそって点を打つ
        int pointCount = style.shape() == TrailStyle.Shape.STAB ? 3 : 6;
        for (int point = 0; point <= pointCount; point++) {
            double progress = (double) point / pointCount;
            Vec3 dir = lastDir.lerp(newDir, progress).normalize();
            Vec3 center = lastShoulder.lerp(shoulder, progress);

            if (style.shape() == TrailStyle.Shape.STAB) {
                // 槍：肩から剣先まで、まっすぐ線を引く
                for (double along = 0.3; along <= 1.0; along += 0.1) {
                    Vec3 spot = center.add(dir.scale(bladeLength * along));
                    level.sendParticles(trailDust, spot.x, spot.y, spot.z, 1, 0.0, 0.0, 0.0, 0.0);
                }
                continue;
            }

            for (double along = alongStart; along <= 1.0; along += alongStep) {
                Vec3 spot = center.add(dir.scale(bladeLength * along));

                // 雷：点ごとに横へギザギザにずらす
                if (style.shape() == TrailStyle.Shape.ZIGZAG) {
                    double jitter = ((point + (int) (along * 10)) % 2 == 0 ? 1.0 : -1.0) * 0.12;
                    spot = spot.add(side.scale(jitter));
                }
                level.sendParticles(trailDust, spot.x, spot.y, spot.z, 1, 0.0, 0.0, 0.0, 0.0);

                // 2 本ならび：少し横にもう 1 本
                if (style.shape() == TrailStyle.Shape.DOUBLE) {
                    Vec3 second = spot.add(side.scale(0.22));
                    level.sendParticles(trailDust, second.x, second.y, second.z, 1, 0.0, 0.0, 0.0, 0.0);
                }
            }

            // まぜる粒（炎・雪・火花など）。多すぎないように、弧の 1 か所ずつ
            if (style.extra() != null) {
                Vec3 spot = center.add(dir.scale(bladeLength * 0.9));
                level.sendParticles(style.extra(), spot.x, spot.y, spot.z, 1, 0.05, 0.05, 0.05, 0.01);
            }
        }
    }

    // ===== 生き物への攻撃 =====

    /** 剣の通り道（前の向き → 今の向き）にいる相手を、当たった順にさがす。 */
    private static List<BladeHit> findHits(ServerPlayer player, SwingState state,
                                           Vec3 lastDir, Vec3 newDir, Vec3 lastShoulder, Vec3 shoulder) {
        // 何回に区切って調べるか
        double turnDegrees = Math.toDegrees(angleBetween(lastDir, newDir));
        int stepCount = Mth.clamp((int) Math.ceil(turnDegrees / SwingConfig.SWEEP_STEP_DEGREES), 1, 30);

        // 近くにいる、切ってよい相手（大きいボスにも届くように広めにさがす）
        AABB searchArea = new AABB(shoulder, shoulder).inflate(state.bladeLength + 4.0);
        List<Entity> candidates = player.level().getEntities(player, searchArea, entity -> canBeHit(player, entity));

        // 相手ごとに、刃がはじめて当たった場所をさがす
        // 剣（高速切り）は、同じ相手にすぐまた当てられる
        int cooldownTicks = WeaponSkill.skillOf(player.getMainHandItem()) == WeaponSkill.SWORD
                ? SwingConfig.SWORD_HIT_COOLDOWN_TICKS : SwingConfig.HIT_COOLDOWN_TICKS;

        List<BladeHit> hits = new ArrayList<>();
        for (Entity candidate : candidates) {
            if (isOnCooldown(state, candidate, cooldownTicks)) {
                continue;
            }
            AABB targetBox = candidate.getBoundingBox().inflate(candidate.getPickRadius() + 0.1);
            for (int step = 0; step <= stepCount; step++) {
                double progress = (double) step / stepCount;
                Vec3 stepDir = lastDir.lerp(newDir, progress).normalize();
                Vec3 stepShoulder = lastShoulder.lerp(shoulder, progress);
                Vec3 bladeStart = stepShoulder.add(stepDir.scale(SwingConfig.BLADE_START));
                Vec3 bladeEnd = stepShoulder.add(stepDir.scale(state.bladeLength));
                Vec3 hitPoint = targetBox.contains(bladeStart) ? bladeStart : targetBox.clip(bladeStart, bladeEnd).orElse(null);
                if (hitPoint != null) {
                    if (!isBlockedByWall(player, stepShoulder, hitPoint)) {
                        hits.add(new BladeHit(candidate, hitPoint, step));
                    }
                    break;
                }
            }
        }

        // 先に当たった相手から順に並べる
        hits.sort((first, second) -> Integer.compare(first.step(), second.step()));

        // 同じボスの体のあちこちに当たっても 1 回だけにする
        List<BladeHit> uniqueHits = new ArrayList<>();
        Set<Integer> hitRoots = new HashSet<>();
        for (BladeHit hit : hits) {
            if (hitRoots.add(rootOf(hit.target()).getId())) {
                uniqueHits.add(hit);
            }
        }
        return uniqueHits;
    }

    /** 切ってよい相手なら true（ふつうの左クリックで殴れる相手。ただし乗り物・額縁・落ちている物は切らない）。 */
    private static boolean canBeHit(ServerPlayer player, Entity entity) {
        Entity rootEntity = rootOf(entity);
        if (rootEntity == player || !entity.isAlive() || entity.isSpectator()) {
            return false;
        }
        if (!entity.isAttackable() || !entity.isPickable() || entity.skipAttackInteraction(player)) {
            return false;
        }
        if (entity instanceof ItemEntity || entity instanceof ExperienceOrb
                || entity instanceof VehicleEntity || entity instanceof HangingEntity) {
            return false;
        }
        if (rootEntity instanceof OwnableEntity pet && pet.getOwner() == player) {
            return false;
        }
        if (rootEntity instanceof Player otherPlayer && !player.canHarmPlayer(otherPlayer)) {
            return false;
        }
        return true;
    }

    /** ボスの体の一部（カタリズムの大きいボスなど）なら本体を、そうでなければそのまま返す。 */
    private static Entity rootOf(Entity entity) {
        if (entity instanceof EnderDragonPart part) {
            return part.parentMob;
        }
        return entity;
    }

    /** 同じ相手にさっき当てたばかりなら true。 */
    private static boolean isOnCooldown(SwingState state, Entity entity, int cooldownTicks) {
        Integer lastHit = state.lastHitPacket.get(rootOf(entity).getId());
        return lastHit != null && state.packetCount - lastHit < cooldownTicks;
    }

    /** 肩から当たった場所までのあいだに壁があれば true（壁ごしには切れない）。 */
    private static boolean isBlockedByWall(ServerPlayer player, Vec3 shoulder, Vec3 hitPoint) {
        ClipContext clip = new ClipContext(shoulder, hitPoint, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player);
        return player.level().clip(clip).getType() != HitResult.Type.MISS;
    }

    /** 当たった場所の勢い（速さ・角度）から倍率を決めて、ふつうの攻撃と同じしくみで攻撃する。当たったら true。 */
    private static boolean applyMomentumHit(ServerPlayer player, SwingState state, BladeHit hit,
                                            Vec3 bladeDir, Vec3 shoulder, double remainingPower) {
        Entity target = hit.target();
        Vec3 hitPoint = hit.hitPoint();

        // 当たった場所の刃の速さ（剣の回転 ＋ 自分の移動 − 相手の移動）。単位はブロック/tick
        double hitRadius = hitPoint.distanceTo(state.lastShoulder);
        Vec3 swingVelocity = bladeDir.subtract(state.lastBladeDir).scale(hitRadius);
        Vec3 playerVelocity = shoulder.subtract(state.lastShoulder);
        Vec3 targetVelocity = new Vec3(target.getX() - target.xo, target.getY() - target.yo, target.getZ() - target.zo);
        Vec3 relativeVelocity = swingVelocity.add(playerVelocity).subtract(targetVelocity);
        double hitSpeed = relativeVelocity.length() * 20.0;

        // 遅すぎる当たりはダメージなし（そっとふれただけ）
        if (hitSpeed < SwingConfig.MIN_HIT_SPEED) {
            return false;
        }

        // 速さの倍率（基準の速さで 1 倍、上限あり）
        double speedFactor = Math.min(hitSpeed / SwingConfig.REFERENCE_SPEED, SwingConfig.MAX_SPEED_FACTOR);

        // 角度の倍率（相手の中心に向かってまっすぐ当たるほど強い、かすると弱い）
        Vec3 toCenter = target.getBoundingBox().getCenter().subtract(hitPoint);
        double straightness = toCenter.lengthSqr() < 1.0e-6 ? 1.0 : relativeVelocity.normalize().dot(toCenter.normalize());
        double angleFactor = SwingConfig.ANGLE_MIN_FACTOR + (1.0 - SwingConfig.ANGLE_MIN_FACTOR) * Math.max(0.0, straightness);
        double momentumFactor = speedFactor * angleFactor * remainingPower;
        if (state.twoHanded) {
            momentumFactor *= SwingConfig.TWO_HAND_DAMAGE_FACTOR;
        }

        // 武器のレベルが高いほどダメージが上がる
        momentumFactor *= 1.0 + SwingConfig.WEAPON_LEVEL_DAMAGE_BONUS * (WeaponAbilities.weaponLevel(player.getMainHandItem()) - 1);

        // 剣の達人：たまった力で一撃が強くなる
        momentumFactor *= MasterSkill.takeChargeMultiplier(player);

        // パリィのすぐあとなら反撃ボーナス
        boolean isCounter = player.tickCount <= state.counterUntilTick;
        if (isCounter) {
            momentumFactor *= SwingConfig.COUNTER_BONUS;
            state.counterUntilTick = 0;
        }

        // 同じ相手にすぐ当たらないように記録（前に当てた時間も見ておく）
        Entity rootTarget = rootOf(target);
        Integer previousHit = state.lastHitPacket.get(rootTarget.getId());
        state.lastHitPacket.put(rootTarget.getId(), state.packetCount);

        // 武器ごとの特殊能力
        int skill = WeaponSkill.skillOf(player.getMainHandItem());
        boolean superCritical = false;
        if (skill == WeaponSkill.SWORD) {
            // 剣＝高速切り：無敵時間を消して続けて切れる（続けて当てた 1 回は少し弱い）
            if (rootTarget instanceof LivingEntity living) {
                living.setInvulnerableTime(0);
            }
            if (previousHit != null && state.packetCount - previousHit < SwingConfig.HIT_COOLDOWN_TICKS) {
                momentumFactor *= SwingConfig.SWORD_RAPID_FACTOR;
            }
        } else if (skill == WeaponSkill.AXE && hitSpeed >= SwingConfig.REFERENCE_SPEED * SwingConfig.AXE_CRIT_SPEED_RATIO) {
            // 斧＝超クリティカル：思いきり振りぬくとダメージが大きく上がる
            momentumFactor *= SwingConfig.AXE_CRIT_MULTIPLIER;
            superCritical = true;
        } else if (skill == WeaponSkill.HOE) {
            // クワ＝連撃：続けて当てるほど攻撃力がどんどん上がる
            double comboBonus = nextHoeCombo(player);
            momentumFactor *= comboBonus;
            if (comboBonus >= SwingConfig.HOE_COMBO_MAX) {
                superCritical = true;
            }
        } else if (skill == WeaponSkill.SPEAR) {
            // 槍＝突き：刃の向きにまっすぐ突くほどダメージが上がる（横にふってもあまり効かない）
            double thrustRatio = relativeVelocity.lengthSqr() < 1.0e-8 ? 0.0 : Math.max(0.0, relativeVelocity.normalize().dot(bladeDir));
            if (thrustRatio >= SwingConfig.SPEAR_THRUST_RATIO) {
                momentumFactor *= SwingConfig.SPEAR_THRUST_MULTIPLIER;
                superCritical = true;
            } else {
                momentumFactor *= 0.6;
            }
        } else if (skill == WeaponSkill.MACE) {
            // メイス＝地ひびき：上からたたきつけるとダメージが上がる
            double downRatio = relativeVelocity.lengthSqr() < 1.0e-8 ? 0.0 : Math.max(0.0, -relativeVelocity.normalize().y);
            if (downRatio >= SwingConfig.MACE_SMASH_DOWN_RATIO) {
                momentumFactor *= SwingConfig.MACE_SMASH_MULTIPLIER;
                superCritical = true;
            }
        } else if (skill == WeaponSkill.PICKAXE && rootTarget instanceof LivingEntity living) {
            // つるはし＝鎧くだき：かたい防具の相手ほどダメージが上がる
            momentumFactor *= Math.min(1.0 + living.getArmorValue() * SwingConfig.PICKAXE_ARMOR_BONUS, SwingConfig.PICKAXE_MAX_BONUS);
        } else if (skill == WeaponSkill.OTHER && !isHeavyWeapon(player) && player.getRandom().nextDouble() < SwingConfig.WEAKPOINT_CHANCE) {
            // 軽い武器＝急所突き：ときどき急所に入ってダメージが上がる
            momentumFactor *= SwingConfig.WEAKPOINT_MULTIPLIER;
            superCritical = true;
        }

        // 攻撃前の体力（与えたダメージを出すため）
        float healthBefore = rootTarget instanceof LivingEntity living ? living.getHealth() + living.getAbsorptionAmount() : 0.0f;

        // ふつうの攻撃と同じしくみで攻撃する（どの Mod のモブにも効く・剣の特殊効果も出る）
        attackWithMomentum(player, target, momentumFactor);

        // 与えたダメージ
        float dealtDamage = rootTarget instanceof LivingEntity living
                ? healthBefore - (living.getHealth() + living.getAbsorptionAmount()) : 0.0f;
        if (rootTarget instanceof LivingEntity && dealtDamage <= 0.0f && rootTarget.isAlive()) {
            return false;
        }

        // 勢いの向きにもふっとばす
        pushTarget(rootTarget, relativeVelocity, speedFactor);

        // 粒で手ごたえを出して、本人の画面をゆらす
        showHitEffects(player.level(), hitPoint, speedFactor);
        if (superCritical) {
            showCriticalEffects(player.level(), hitPoint);
        }
        Net.sendToPlayer(player, new HitPayload((float) Math.min(speedFactor, 1.5) + (superCritical ? 0.6f : 0.0f)));

        // 武器についている能力（当てたとき・倒したとき）
        if (rootTarget instanceof LivingEntity struck) {
            WeaponAbilities.onHit(player, player.getMainHandItem(), struck, dealtDamage, hitPoint);
            MasterSkill.onHit(player, struck, dealtDamage, hitPoint);
            if (!struck.isAlive()) {
                WeaponAbilities.onKill(player, player.getMainHandItem(), struck);
            }
        }

        // 当てた相手からダメージの数字を出す
        int numberStyle = superCritical ? DamagePayload.CRITICAL
                : hitSpeed >= SwingConfig.REFERENCE_SPEED ? DamagePayload.STRONG : DamagePayload.NORMAL;
        sendDamageNumber(player, hitPoint, dealtDamage, numberStyle);

        // 当てたあとに出る能力（シャベル＝打ち上げ、クワ＝命刈り、重い武器＝衝撃波、つるはし＝鎧くだきの音）
        boolean strongHit = hitSpeed >= SwingConfig.REFERENCE_SPEED;
        if (skill == WeaponSkill.SHOVEL && strongHit) {
            launchTarget(rootTarget);
        } else if (skill == WeaponSkill.OTHER && isHeavyWeapon(player) && strongHit) {
            shockwave(player, rootTarget, hitPoint, momentumFactor);
        } else if (skill == WeaponSkill.MACE && strongHit) {
            shockwave(player, rootTarget, hitPoint, momentumFactor);
        } else if (skill == WeaponSkill.PICKAXE && rootTarget instanceof LivingEntity living && living.getArmorValue() > 0) {
            player.level().playSound(null, hitPoint.x, hitPoint.y, hitPoint.z, SoundEvents.ZOMBIE_ATTACK_IRON_DOOR,
                    SoundSource.PLAYERS, 0.6f, 1.4f);
            player.level().sendParticles(ParticleTypes.CRIT, hitPoint.x, hitPoint.y, hitPoint.z, 12, 0.2, 0.2, 0.2, 0.3);
        }

        // トライデント＝三本の斬撃：少しずつおくれて、あと 2 本の斬撃が当たる
        if (skill == WeaponSkill.TRIDENT) {
            for (int slash = 1; slash <= SwingConfig.TRIDENT_EXTRA_SLASHES; slash++) {
                PENDING_SLASHES.add(new PendingSlash(player.getUUID(), rootTarget.getId(),
                        serverTick + (long) slash * SwingConfig.TRIDENT_SLASH_DELAY_TICKS,
                        momentumFactor * SwingConfig.TRIDENT_EXTRA_FACTOR, slash));
            }
        }
        return true;
    }

    /** 攻撃力に勢いの倍率を一時的にかけて、ふつうの攻撃（player.attack）をする。 */
    private static void attackWithMomentum(ServerPlayer player, Entity target, double momentumFactor) {
        AttributeInstance damageAttribute = player.getAttribute(Attributes.ATTACK_DAMAGE);
        if (damageAttribute != null) {
            damageAttribute.addOrUpdateTransientModifier(new AttributeModifier(
                    MOMENTUM_MODIFIER_ID, momentumFactor - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
        fillAttackStrength(player);
        attackingWithMomentum = true;
        try {
            player.attack(target);
        } finally {
            attackingWithMomentum = false;
            if (damageAttribute != null) {
                damageAttribute.removeModifier(MOMENTUM_MODIFIER_ID);
            }
        }
    }

    /** 攻撃のたまり具合を満タンにする（勢いで強さを決めるので、クリックの間かくでは弱めない）。 */
    private static void fillAttackStrength(ServerPlayer player) {
        try {
            if (attackStrengthField == null) {
                attackStrengthField = LivingEntity.class.getDeclaredField("attackStrengthTicker");
                attackStrengthField.setAccessible(true);
            }
            attackStrengthField.setInt(player, 1000);
        } catch (ReflectiveOperationException | RuntimeException error) {
            // 書きかえられなくても攻撃はできる（少し弱くなるだけ）
        }
    }

    /** 生き物をふっとばす（マイクラのふつうのノックバックと同じ計算。向きは (dx, dz) の反対へ）。 */
    public static void knock(LivingEntity entity, double strength, double dx, double dz) {
        // ふっとびにくさ（防具など）をかける
        double resistance = entity.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE);
        strength *= 1.0 - resistance;
        if (strength <= 0.0) {
            return;
        }
        Vec3 push = new Vec3(dx, 0.0, dz).normalize().scale(strength);
        Vec3 motion = entity.getDeltaMovement();
        entity.setDeltaMovement(motion.x / 2.0 - push.x, entity.onGround() ? Math.min(0.4, motion.y / 2.0 + strength) : motion.y, motion.z / 2.0 - push.z);
        entity.needsSync = true;
    }

    /** 相手を勢いの向きにふっとばす（生き物だけ）。 */
    private static void pushTarget(Entity target, Vec3 relativeVelocity, double speedFactor) {
        if (!(target instanceof LivingEntity livingTarget)) {
            return;
        }
        Vec3 pushDir = new Vec3(relativeVelocity.x, 0.0, relativeVelocity.z);
        if (pushDir.lengthSqr() < 1.0e-6) {
            return;
        }
        pushDir = pushDir.normalize();
        float strength = (float) (SwingConfig.KNOCKBACK_BASE * speedFactor);
        knock(livingTarget, strength, -pushDir.x, -pushDir.z);
    }

    /** 当たったときの粒を出す（速いほど派手、音はふつうの攻撃の音が鳴る）。 */
    private static void showHitEffects(ServerLevel level, Vec3 hitPoint, double speedFactor) {
        if (speedFactor >= 1.25) {
            level.sendParticles(ParticleTypes.CRIT, hitPoint.x, hitPoint.y, hitPoint.z, 14, 0.2, 0.2, 0.2, 0.4);
        }
        level.sendParticles(ParticleTypes.SWEEP_ATTACK, hitPoint.x, hitPoint.y, hitPoint.z, 1, 0.0, 0.0, 0.0, 0.0);
    }

    /** 当てた場所から、本人の画面にダメージの数字を出す。 */
    private static void sendDamageNumber(ServerPlayer player, Vec3 hitPoint, float dealtDamage, int style) {
        if (!SwingConfig.SHOW_DAMAGE_NUMBER || dealtDamage <= 0.0f) {
            return;
        }
        Net.sendToPlayer(player, new DamagePayload(hitPoint.x, hitPoint.y, hitPoint.z, dealtDamage, style));
    }

    /** 斧の超クリティカルの派手な火花と音。 */
    private static void showCriticalEffects(ServerLevel level, Vec3 hitPoint) {
        level.sendParticles(ParticleTypes.CRIT, hitPoint.x, hitPoint.y, hitPoint.z, 30, 0.3, 0.3, 0.3, 0.6);
        level.sendParticles(ParticleTypes.ENCHANTED_HIT, hitPoint.x, hitPoint.y, hitPoint.z, 20, 0.3, 0.3, 0.3, 0.5);
        level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFFFFF), hitPoint.x, hitPoint.y, hitPoint.z, 1, 0.0, 0.0, 0.0, 0.0);
        level.playSound(null, hitPoint.x, hitPoint.y, hitPoint.z, SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.3f, 0.6f);
        level.playSound(null, hitPoint.x, hitPoint.y, hitPoint.z, SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.35f, 1.7f);
    }

    // ===== そのほかの武器の能力 =====

    /** 持っている武器が重い（攻撃速度が遅い）なら true。 */
    private static boolean isHeavyWeapon(ServerPlayer player) {
        return player.getAttributeValue(Attributes.ATTACK_SPEED) < SwingConfig.HEAVY_ATTACK_SPEED;
    }

    /** シャベル＝打ち上げ：相手を空中へ浮かせて、しばらく動きをおそくする。 */
    private static void launchTarget(Entity target) {
        if (!(target instanceof LivingEntity living)) {
            return;
        }
        living.setDeltaMovement(living.getDeltaMovement().add(0.0, SwingConfig.SHOVEL_LAUNCH_SPEED, 0.0));
        living.needsSync = true;
        living.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, SwingConfig.SHOVEL_SLOW_TICKS, 2));

        // 土けむりと音
        ServerLevel level = (ServerLevel) living.level();
        level.sendParticles(ParticleTypes.POOF, living.getX(), living.getY() + 0.1, living.getZ(), 15, 0.4, 0.1, 0.4, 0.05);
        level.playSound(null, living.getX(), living.getY(), living.getZ(), SoundEvents.SHOVEL_FLATTEN, SoundSource.PLAYERS, 1.0f, 0.7f);
    }

    /** クワ＝連撃：連撃の数を 1 つ進めて、今の攻撃力の倍率を返す（間があいたら 1 からやりなおし）。 */
    private static double nextHoeCombo(ServerPlayer player) {
        HoeCombo combo = HOE_COMBOS.get(player.getUUID());
        int count = 0;
        if (combo != null && player.tickCount - combo.lastHitTick() <= SwingConfig.HOE_COMBO_WINDOW_TICKS
                && player.tickCount >= combo.lastHitTick()) {
            count = combo.count() + 1;
        }
        HOE_COMBOS.put(player.getUUID(), new HoeCombo(count, player.tickCount));

        // 連撃が続くほど、手もとに火花が増えて音が高くなる
        double bonus = Math.min(1.0 + count * SwingConfig.HOE_COMBO_STEP, SwingConfig.HOE_COMBO_MAX);
        if (count > 0) {
            ServerLevel level = player.level();
            level.sendParticles(ParticleTypes.ENCHANTED_HIT, player.getX(), player.getEyeY() - 0.4, player.getZ(),
                    Math.min(count * 2, 20), 0.3, 0.3, 0.3, 0.2);
            level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.NOTE_BLOCK_CHIME.value(),
                    SoundSource.PLAYERS, 0.5f, (float) Math.min(0.6 + count * 0.1, 2.0));
        }
        return bonus;
    }

    /** 重い武器＝衝撃波：当てた場所のまわりの敵にもダメージを与えて、外へふっとばす。 */
    private static void shockwave(ServerPlayer player, Entity mainTarget, Vec3 center, double momentumFactor) {
        ServerLevel level = player.level();
        level.sendParticles(ParticleTypes.EXPLOSION, center.x, center.y, center.z, 2, 0.3, 0.1, 0.3, 0.0);
        level.playSound(null, center.x, center.y, center.z, SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.6f, 0.5f);

        // まわりの、切ってよい相手
        AABB area = new AABB(center, center).inflate(SwingConfig.SHOCKWAVE_RADIUS);
        List<Entity> nearby = level.getEntities(player, area,
                entity -> entity != mainTarget && rootOf(entity) != mainTarget && entity instanceof LivingEntity && canBeHit(player, entity));
        for (Entity entity : nearby) {
            LivingEntity living = (LivingEntity) entity;
            float healthBefore = living.getHealth() + living.getAbsorptionAmount();
            attackWithMomentum(player, living, momentumFactor * SwingConfig.SHOCKWAVE_FACTOR);
            float dealtDamage = healthBefore - (living.getHealth() + living.getAbsorptionAmount());

            // 外向きにふっとばす
            Vec3 away = living.position().subtract(center);
            if (away.horizontalDistanceSqr() > 1.0e-4) {
                knock(living, 0.6, -away.x, -away.z);
            }
            sendDamageNumber(player, living.getBoundingBox().getCenter(), dealtDamage, DamagePayload.NORMAL);
        }
    }

    // ===== トライデントの三本の斬撃 =====

    /** 毎 tick、時間が来たトライデントの斬撃を当てる。 */
    public static void onServerTick(MinecraftServer server) {
        serverTick++;
        removeOldCracks(server);

        // 1 秒ごとに、持っているだけで効く能力をかける
        if (serverTick % 20 == 0) {
            for (ServerPlayer onlinePlayer : server.getPlayerList().getPlayers()) {
                WeaponAbilities.tickPassives(onlinePlayer);
            }
        }
        if (PENDING_SLASHES.isEmpty()) {
            return;
        }
        List<PendingSlash> dueSlashes = new ArrayList<>();
        PENDING_SLASHES.removeIf(slash -> {
            if (slash.dueTick() <= serverTick) {
                dueSlashes.add(slash);
                return true;
            }
            return false;
        });
        for (PendingSlash slash : dueSlashes) {
            ServerPlayer player = server.getPlayerList().getPlayer(slash.playerId());
            if (player != null) {
                extraSlash(player, slash);
            }
        }
    }

    /** しばらく当てていないひびを消す（ふつうに掘るのをやめたときと同じ）。 */
    private static void removeOldCracks(MinecraftServer server) {
        if (CRACKS.isEmpty() || serverTick % 20 != 0) {
            return;
        }
        for (Map.Entry<UUID, Map<BlockPos, CrackInfo>> playerEntry : CRACKS.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerEntry.getKey());
            playerEntry.getValue().entrySet().removeIf(entry -> {
                CrackInfo crack = entry.getValue();
                if (serverTick - crack.lastServerTick <= SwingConfig.MINING_FORGET_TICKS) {
                    return false;
                }
                if (player != null) {
                    showCracks(player, crack.level, entry.getKey(), -1);
                }
                return true;
            });
        }
    }

    /** あとから出る斬撃を 1 本当てる（相手が近くにいて、まだトライデントを持っているときだけ）。 */
    private static void extraSlash(ServerPlayer player, PendingSlash slash) {
        if (!player.isAlive() || WeaponSkill.skillOf(player.getMainHandItem()) != WeaponSkill.TRIDENT) {
            return;
        }
        Entity target = player.level().getEntity(slash.targetId());
        if (target == null || !target.isAlive() || !canBeHit(player, target)) {
            return;
        }
        double reach = Mth.clamp(player.entityInteractionRange(), SwingConfig.MIN_BLADE_LENGTH, SwingConfig.MAX_BLADE_LENGTH) + 3.0;
        if (target.distanceTo(player) > reach) {
            return;
        }

        // 斬撃が当たる場所（1 本目より上・下にずらす）
        Vec3 center = target.getBoundingBox().getCenter();
        double offset = (slash.slashIndex() % 2 == 1 ? 1.0 : -1.0) * target.getBbHeight() * 0.25;
        Vec3 slashPoint = center.add(0.0, offset, 0.0);

        // 無敵時間を消して、ふつうの攻撃と同じしくみで当てる
        float healthBefore = target instanceof LivingEntity living ? living.getHealth() + living.getAbsorptionAmount() : 0.0f;
        if (target instanceof LivingEntity living) {
            living.setInvulnerableTime(0);
        }
        attackWithMomentum(player, target, slash.momentumFactor());
        float dealtDamage = target instanceof LivingEntity living
                ? healthBefore - (living.getHealth() + living.getAbsorptionAmount()) : 0.0f;

        // 斬撃の見た目と音
        ServerLevel level = player.level();
        level.sendParticles(ParticleTypes.SWEEP_ATTACK, slashPoint.x, slashPoint.y, slashPoint.z, 1, 0.0, 0.0, 0.0, 0.0);
        level.sendParticles(ParticleTypes.BUBBLE_POP, slashPoint.x, slashPoint.y, slashPoint.z, 8, 0.3, 0.1, 0.3, 0.05);
        level.playSound(null, slashPoint.x, slashPoint.y, slashPoint.z, SoundEvents.PLAYER_ATTACK_SWEEP,
                SoundSource.PLAYERS, 0.9f, 1.2f + 0.2f * slash.slashIndex());
        Net.sendToPlayer(player, new HitPayload(0.4f));
        sendDamageNumber(player, slashPoint, dealtDamage, DamagePayload.NORMAL);
    }

    // ===== 道具でブロックを壊す =====

    /** 道具の通り道で当たったブロックを、勢いに合わせて掘る（何回か当てると壊れる）。 */
    private static void mineBlocksOnPath(ServerPlayer player, SwingState state,
                                         Vec3 lastDir, Vec3 newDir, Vec3 lastShoulder, Vec3 shoulder) {
        ServerLevel level = player.level();
        double turnRadians = angleBetween(lastDir, newDir);
        int stepCount = Mth.clamp((int) Math.ceil(Math.toDegrees(turnRadians) / SwingConfig.SWEEP_STEP_DEGREES), 1, 30);
        Set<BlockPos> touchedBlocks = new HashSet<>();
        int brokenCount = 0;

        for (int step = 1; step <= stepCount && brokenCount < SwingConfig.MAX_BLOCKS_PER_TICK; step++) {
            // この区切りでの刃の線
            double progress = (double) step / stepCount;
            Vec3 stepDir = lastDir.lerp(newDir, progress).normalize();
            Vec3 stepShoulder = lastShoulder.lerp(shoulder, progress);
            Vec3 bladeStart = stepShoulder.add(stepDir.scale(SwingConfig.BLADE_START));
            Vec3 bladeEnd = stepShoulder.add(stepDir.scale(state.bladeLength));

            // 刃がはじめに当たるブロック
            BlockHitResult blockHit = level.clip(new ClipContext(bladeStart, bladeEnd,
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (blockHit.getType() != HitResult.Type.BLOCK) {
                continue;
            }
            if (SwingConfig.DEBUG_LOG) {
                System.out.println("ikioiken: 刃がブロックに当たった " + blockHit.getBlockPos());
            }
            BlockPos pos = blockHit.getBlockPos();
            if (!touchedBlocks.add(pos)) {
                continue;
            }

            // 当たった場所の速さ（回転 ＋ 自分の移動）
            double hitRadius = blockHit.getLocation().distanceTo(stepShoulder);
            double hitSpeed = (turnRadians * hitRadius + shoulder.subtract(lastShoulder).length()) * 20.0;
            if (SwingConfig.DEBUG_LOG) {
                System.out.println("ikioiken: 当たった速さ=" + hitSpeed);
            }
            if (hitSpeed < SwingConfig.MIN_HIT_SPEED) {
                continue;
            }
            double speedFactor = Math.min(hitSpeed / SwingConfig.REFERENCE_SPEED, SwingConfig.MAX_SPEED_FACTOR);

            // ふつうに掘ったときの 1 tick ぶん × 勢い
            if (hitBlock(player, state, level, blockHit, speedFactor)) {
                brokenCount++;
            }
        }
    }

    /** 1 つのブロックに道具を当てて、ひびを進める。壊れたら true。 */
    private static boolean hitBlock(ServerPlayer player, SwingState state, ServerLevel level, BlockHitResult blockHit, double speedFactor) {
        BlockPos pos = blockHit.getBlockPos();
        BlockState blockState = level.getBlockState(pos);
        if (blockState.isAir() || !level.mayInteract(player, pos)) {
            return false;
        }
        ItemStack tool = player.getMainHandItem();

        // クワを土や草ブロックに振りおろしたら、畑にする（右クリックで耕すのと同じ）
        if (tillWithHoe(player, state, tool, blockHit)) {
            return false;
        }

        // その道具の得意なブロックだけ（斧は木、つるはしは石や鉱石、シャベルは土や砂、クワは葉っぱなど）
        if (tool.getDestroySpeed(blockState) <= 1.0f) {
            return false;
        }
        float progressPerTick = blockState.getDestroyProgress(player, level, pos);
        if (progressPerTick <= 0.0f) {
            return false;
        }

        // 同じ振りで何回も当たらないように、同じブロックには少しあけないと当たらない（たくさん振るほど早く壊れる）
        Integer lastHitTick = state.blockLastHitTick.get(pos);
        if (lastHitTick != null && player.tickCount - lastHitTick < SwingConfig.MINING_HIT_COOLDOWN_TICKS) {
            return false;
        }

        state.blockLastHitTick.put(pos, player.tickCount);

        // 掘り具合を足す（しばらく当てていないひびは、サーバーの tick ごとの見回りで消えている）
        Map<BlockPos, CrackInfo> playerCracks = cracksOf(player);
        CrackInfo crack = playerCracks.get(pos);
        float gain = (float) (progressPerTick * SwingConfig.SWING_MINING_TICKS * speedFactor * SwordCheck.materialDigSpeed(tool)
                * (state.twoHanded ? SwingConfig.TWO_HAND_DAMAGE_FACTOR : 1.0));
        gain = Math.min(gain, SwingConfig.MAX_PROGRESS_PER_HIT);
        float progress = (crack == null ? 0.0f : crack.progress) + gain;

        // 当たった音
        level.playSound(null, pos, blockState.getSoundType().getHitSound(),
                SoundSource.BLOCKS, 0.8f, 0.9f);

        // まだ壊れないなら、ひびを見せる
        if (progress < 1.0f) {
            if (crack == null) {
                crack = new CrackInfo();
                playerCracks.put(pos, crack);
            }
            crack.progress = progress;
            crack.lastServerTick = serverTick;
            crack.level = level;
            showCracks(player, level, pos, Mth.clamp((int) (progress * 10.0f), 1, 9));
            return false;
        }

        // 壊す（ドロップ・道具の耐久・保護エリアなどはふつうに掘ったときと同じ）
        playerCracks.remove(pos);
        showCracks(player, level, pos, -1);
        boolean broken = player.gameMode.destroyBlock(pos);
        if (broken) {
            Net.sendToPlayer(player, new HitPayload(0.4f));
        }
        return broken;
    }

    /** クワが当たった土を耕して畑にする。耕せたら true。 */
    private static boolean tillWithHoe(ServerPlayer player, SwingState state, ItemStack tool, BlockHitResult blockHit) {
        // クワでなければ、または下の面に当たったときは耕さない
        if (!tool.is(ItemTags.HOES) || blockHit.getDirection() == Direction.DOWN) {
            return false;
        }

        // 同じ振りで何回も耕そうとしない
        BlockPos pos = blockHit.getBlockPos();
        Integer lastHitTick = state.blockLastHitTick.get(pos);
        if (lastHitTick != null && player.tickCount - lastHitTick < SwingConfig.MINING_HIT_COOLDOWN_TICKS) {
            return false;
        }

        // 右クリックで耕すのと同じ動き（上が空いているか・音・耐久・ほかの Mod の決まりもそのまま）
        InteractionResult result = tool.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, blockHit));
        if (!result.consumesAction()) {
            return false;
        }
        state.blockLastHitTick.put(pos, player.tickCount);
        cracksOf(player).remove(pos);
        return true;
    }
}
