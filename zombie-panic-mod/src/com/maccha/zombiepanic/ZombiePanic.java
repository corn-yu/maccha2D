package com.maccha.zombiepanic;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

/**
 * ゾンビパニック Mod の入り口（イベントとコマンドを登録する）。
 */
@Mod(ZombiePanic.MOD_ID)
public class ZombiePanic {

    public static final String MOD_ID = "zombiepanic";

    /** Mod が読みこまれたときに、サーバーで使うイベントを登録する。 */
    public ZombiePanic(IEventBus modEventBus) {
        // パニックの時計（毎tick）とサーバーの開始・終了
        NeoForge.EVENT_BUS.addListener(PanicEvent::onServerTick);
        NeoForge.EVENT_BUS.addListener(PanicEvent::onServerStarted);
        NeoForge.EVENT_BUS.addListener(PanicEvent::onServerStopping);

        // 途中で入ってきた人・ぬけた人のバーの出し入れ
        NeoForge.EVENT_BUS.addListener(PanicEvent::onPlayerLogin);
        NeoForge.EVENT_BUS.addListener(PanicEvent::onPlayerLogout);

        // ゾンビをたおした数とゾンビ大将
        NeoForge.EVENT_BUS.addListener(PanicEvent::onLivingDeath);

        // パニック中はゾンビ以外わかないようにする
        NeoForge.EVENT_BUS.addListener(SpawnFilter::onPositionCheck);
        NeoForge.EVENT_BUS.addListener(SpawnFilter::onFinalizeSpawn);

        // /zombiepanic コマンド
        NeoForge.EVENT_BUS.addListener(PanicCommand::onRegisterCommands);
    }
}
