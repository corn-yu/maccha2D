package com.maccha.ikioiken;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * いきおい剣 Mod の入り口（パケットとイベントを登録する）。
 */
@Mod(IkioiKen.MOD_ID)
public class IkioiKen {

    public static final String MOD_ID = "ikioiken";

    /** Mod が読みこまれたときに、パケットとログアウト時の後片づけを登録する。 */
    public IkioiKen(IEventBus modEventBus) {
        // パケットの登録
        modEventBus.addListener(IkioiKen::registerPayloads);

        // ログアウトしたら、その人の剣の記録を消す
        NeoForge.EVENT_BUS.addListener(ServerSwing::onPlayerLogout);
    }

    /** 剣の向きパケット（クライアント → サーバー）を登録する。 */
    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(BladePayload.TYPE, BladePayload.STREAM_CODEC, ServerSwing::onBladePayload);
    }
}
