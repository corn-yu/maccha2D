package com.maccha.koemahou;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * こえまほう（サーバー側）：詠唱のお知らせを受けとって、魔法を出す
 */
public class KoeMahouMod implements ModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("koemahou");

    /** MOD が読みこまれたら、お知らせの形を登録して、受けとる準備をする */
    @Override
    public void onInitialize() {
        // お知らせの形を登録する
        PayloadTypeRegistry.serverboundPlay().register(CastPayload.TYPE, CastPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(LearnedPayload.TYPE, LearnedPayload.CODEC);

        // お知らせが来たら、サーバーの中で魔法を出す
        ServerPlayNetworking.registerGlobalReceiver(CastPayload.TYPE, (payload, context) ->
                SpellEffects.onCast(context.player(), payload));

        // 大魔法の「あとで落ちる雷」などを、毎tick 進める
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            SpellEffects.tick();
            Grimoire.tick();
        });

        // 魔導書と魔法本：じゅもん表と覚えた魔法を読む・入ってきた人に送る・読んだら覚える・宝箱に入れる・コマンド
        ServerLifecycleEvents.SERVER_STARTED.register(Grimoire::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> Grimoire.onServerStopped());
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> Grimoire.onJoin(handler.player));
        UseItemCallback.EVENT.register(Grimoire::onUse);
        LootTableEvents.MODIFY_DROPS.register(Grimoire::onChestLoot);
        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> Grimoire.registerCommands(dispatcher));
    }
}
