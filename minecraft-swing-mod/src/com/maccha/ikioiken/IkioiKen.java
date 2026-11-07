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

    /** Mod が読みこまれたときに、パケットとサーバー側のイベントを登録する。 */
    public IkioiKen(IEventBus modEventBus) {
        // パケットの登録
        modEventBus.addListener(IkioiKen::registerPayloads);

        // ログアウトしたら、その人の剣の記録を消す
        NeoForge.EVENT_BUS.addListener(ServerSwing::onPlayerLogout);

        // ガード中は前からの攻撃を防ぐ
        NeoForge.EVENT_BUS.addListener(ServerSwing::onIncomingDamage);

        // いきおい剣の攻撃では、ふつうの「なぎ払い」は出さない（勢いで何体も切れるので）
        NeoForge.EVENT_BUS.addListener(ServerSwing::onSweepAttack);
    }

    /** 剣の向きパケット（クライアント → サーバー）と手ごたえパケット（サーバー → クライアント）を登録する。 */
    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("2");
        registrar.playToServer(BladePayload.TYPE, BladePayload.STREAM_CODEC, ServerSwing::onBladePayload);
        registrar.playToClient(HitPayload.TYPE, HitPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> com.maccha.ikioiken.client.SwingClient.onHit(payload.strength())));
    }
}
