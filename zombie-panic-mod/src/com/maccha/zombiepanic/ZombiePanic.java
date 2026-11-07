package com.maccha.zombiepanic;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

/**
 * ゾンビパニック Mod の入り口（アイテム・構造物・イベント・コマンドを登録する）。
 */
@Mod(ZombiePanic.MOD_ID)
public class ZombiePanic {

    public static final String MOD_ID = "zombiepanic";

    /** Mod が読みこまれたときに、登録とイベントのつなぎこみをする。 */
    public ZombiePanic(IEventBus modEventBus) {
        // 呪鉄のアイテム・構造物
        ModContent.register(modEventBus);

        // 呪いの世界の時計（毎tick）とサーバーの開始・終了
        NeoForge.EVENT_BUS.addListener(PanicEvent::onServerTick);
        NeoForge.EVENT_BUS.addListener(PanicEvent::onServerStarted);
        NeoForge.EVENT_BUS.addListener(PanicEvent::onServerStopping);

        // 途中で入ってきた人・ぬけた人
        NeoForge.EVENT_BUS.addListener(PanicEvent::onPlayerLogin);
        NeoForge.EVENT_BUS.addListener(PanicEvent::onPlayerLogout);

        // ゾンビが出てきたとき・動くとき・なぐり合い・たおされたとき
        NeoForge.EVENT_BUS.addListener(PanicEvent::onEntityJoin);
        NeoForge.EVENT_BUS.addListener(PanicEvent::onEntityTick);
        NeoForge.EVENT_BUS.addListener(PanicEvent::onIncomingDamage);
        NeoForge.EVENT_BUS.addListener(PanicEvent::onLivingDeath);

        // プレイヤーの能力（死んでも なくさない）
        NeoForge.EVENT_BUS.addListener(PlayerPowers::onPlayerClone);
        NeoForge.EVENT_BUS.addListener(PlayerPowers::onPlayerRespawn);

        // 呪いのあいだは ゾンビ以外わかないようにする
        NeoForge.EVENT_BUS.addListener(SpawnFilter::onPositionCheck);
        NeoForge.EVENT_BUS.addListener(SpawnFilter::onFinalizeSpawn);

        // /zombiepanic コマンド
        NeoForge.EVENT_BUS.addListener(PanicCommand::onRegisterCommands);
    }
}
