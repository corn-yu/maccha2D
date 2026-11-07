package com.maccha.zombiepanic;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/**
 * ゾンビパニック（ゾンビの呪いの世界）。26.3 の Fabric 版。
 */
public class ZombiePanic implements ModInitializer {

    public static final String MOD_ID = "zombiepanic";

    /** Mod が読みこまれたときに、登録とイベントのつなぎこみをする。 */
    @Override
    public void onInitialize() {
        // 呪鉄のアイテム・能力の石・構造物
        ModContent.register();

        // 呪いの世界の時計（毎tick）とサーバーの開始・終了
        ServerTickEvents.END_SERVER_TICK.register(PanicEvent::onServerTick);
        ServerLifecycleEvents.SERVER_STARTED.register(PanicEvent::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(PanicEvent::onServerStopping);

        // 途中で入ってきた人・ぬけた人・生きかえった人
        ServerPlayerEvents.JOIN.register(PanicEvent::onPlayerLogin);
        ServerPlayerEvents.LEAVE.register(PanicEvent::onPlayerLogout);
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> PlayerPowers.onPlayerRespawn(newPlayer));

        // ゾンビが出てきたとき・なぐり合い・たおされたとき
        ServerEntityEvents.ENTITY_LOAD.register(PanicEvent::onEntityJoin);
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(PanicEvent::onAllowDamage);
        ServerLivingEntityEvents.AFTER_DEATH.register(PanicEvent::onLivingDeath);

        // /zombiepanic コマンド
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> PanicCommand.register(dispatcher));
    }
}
