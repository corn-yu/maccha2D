package com.maccha.ikioiken;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/**
 * いきおい剣（Fabric 26.3 版）の入り口。パケットとサーバー側のイベントを登録する。
 */
public class IkioiKen implements ModInitializer {

    public static final String MOD_ID = "ikioiken";

    /** Mod が読みこまれたときに、パケットとサーバー側のイベントを登録する。 */
    @Override
    public void onInitialize() {
        // 武器合体台のブロックとアイテム
        WeaponForge.register();

        // スキルの記録(プレイヤーに付く)を、ワールドを読みこむ前に登録しておく
        SkillTree.NODES.size();
        SkillEffects.register();
        CutParts.MASK.identifier();

        // パケットの登録（剣の向きは本人 → サーバー、それ以外はサーバー → 本人の画面）
        PayloadTypeRegistry.serverboundPlay().register(BladePayload.TYPE, BladePayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(HitPayload.TYPE, HitPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MotionPayload.TYPE, MotionPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(DamagePayload.TYPE, DamagePayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SkillUnlockPayload.TYPE, SkillUnlockPayload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(BladePayload.TYPE, ServerSwing::onBladePayload);
        ServerPlayNetworking.registerGlobalReceiver(SkillUnlockPayload.TYPE, IkioiKen::onSkillUnlock);

        // ログアウト・サーバー終了のとき、剣の記録を消す
        ServerPlayConnectionEvents.DISCONNECT.register(ServerSwing::onPlayerLogout);
        ServerLifecycleEvents.SERVER_STOPPED.register(ServerSwing::onServerStopped);
        ServerLifecycleEvents.SERVER_STOPPED.register(EnemyStrike::onServerStopped);
        ServerLifecycleEvents.SERVER_STOPPED.register(SkillEffects::onServerStopped);
        ServerLifecycleEvents.SERVER_STOPPED.register(SlashSkill::onServerStopped);

        // 敵の殴りの変更とガード・パリィは mixin（LivingEntityMixin）で差しこむ。

        // 試すためのコマンド（/ikioiken ...）
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> AbilityCommand.register(dispatcher));

        // 毎 tick の処理（敵のため → 振り、トライデントの斬撃）
        ServerTickEvents.END_SERVER_TICK.register(EnemyStrike::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(ServerSwing::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(SkillEffects::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(SlashSkill::onServerTick);
    }

    /** 「このスキルを覚えたい」と言われたとき、プレイヤーに覚えさせる。 */
    private static void onSkillUnlock(SkillUnlockPayload payload, ServerPlayNetworking.Context context) {
        net.minecraft.server.level.ServerPlayer player = context.player();
        // 「forget:」で始まるときは、解除のお願い(無料)
        boolean forget = payload.skillId().startsWith("forget:");
        SkillTree.Node node = SkillTree.find(forget ? payload.skillId().substring("forget:".length()) : payload.skillId());
        if (node == null) {
            return;
        }
        if (forget) {
            if (SkillTree.forget(player, node)) {
                player.sendOverlayMessage(net.minecraft.network.chat.Component.literal("スキル「" + node.name() + "」を解除した"));
            }
            return;
        }

        // 覚えられない理由があれば、やさしく伝える
        String reason = SkillTree.whyNot(player, node);
        if (reason != null) {
            player.sendOverlayMessage(net.minecraft.network.chat.Component.literal(reason));
            return;
        }
        SkillTree.unlock(player, node);
        player.sendOverlayMessage(net.minecraft.network.chat.Component.literal(
                "スキル「" + node.name() + "」を覚えた！"));
        player.level().playSound(null, player.blockPosition(),
                net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP, net.minecraft.sounds.SoundSource.PLAYERS, 0.8f, 1.2f);
    }
}
