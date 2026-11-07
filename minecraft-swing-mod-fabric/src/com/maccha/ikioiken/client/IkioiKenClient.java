package com.maccha.ikioiken.client;

import com.maccha.ikioiken.DamagePayload;
import com.maccha.ikioiken.HitPayload;
import com.maccha.ikioiken.IkioiKen;
import com.maccha.ikioiken.MotionPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.resources.Identifier;

/**
 * いきおい剣（Fabric 26.3 版）の画面側の入り口。サーバーからの知らせと、毎 tick・毎フレームの処理を登録する。
 */
public class IkioiKenClient implements ClientModInitializer {

    /** 画面側のイベントを登録する。 */
    @Override
    public void onInitializeClient() {
        // サーバーからの知らせ（手ごたえ・モーション・ダメージの数字）
        ClientPlayNetworking.registerGlobalReceiver(HitPayload.TYPE,
                (payload, context) -> SwingClient.onHit(payload.strength()));
        ClientPlayNetworking.registerGlobalReceiver(MotionPayload.TYPE,
                (payload, context) -> MotionClient.onMotion(payload));
        ClientPlayNetworking.registerGlobalReceiver(DamagePayload.TYPE,
                (payload, context) -> DamageNumbers.onDamage(payload));

        // tick のはじめ（ふつうの攻撃を止める）と終わり（剣の向きを送る）
        ClientTickEvents.START_CLIENT_TICK.register(SwingClient::onClientTickStart);
        ClientTickEvents.END_CLIENT_TICK.register(SwingClient::onClientTickEnd);

        // ダメージの数字は、画面のいちばん上に描く
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(IkioiKen.MOD_ID, "damage_numbers"),
                DamageNumbers::onRenderGui);
    }
}
