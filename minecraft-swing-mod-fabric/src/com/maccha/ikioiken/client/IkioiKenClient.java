package com.maccha.ikioiken.client;

import com.maccha.ikioiken.DamagePayload;
import com.maccha.ikioiken.HitPayload;
import com.maccha.ikioiken.IkioiKen;
import com.maccha.ikioiken.MotionPayload;
import com.maccha.ikioiken.WeaponForge;
import com.maccha.ikioiken.WeaponForgeMenu;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
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

        // 武器合体台の画面を登録する
        registerForgeScreen();

        // 武器の説明文に攻撃力などを足す
        ItemTooltipCallback.EVENT.register(WeaponTooltip::onTooltip);

        // ダメージの数字は、画面のいちばん上に描く
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(IkioiKen.MOD_ID, "damage_numbers"),
                DamageNumbers::onRenderGui);
    }

    /** 武器合体台の画面を登録する（登録の入り口が公開されていないので、reflection で呼ぶ）。 */
    private static void registerForgeScreen() {
        try {
            Class<?> constructorType = Class.forName("net.minecraft.client.gui.screens.MenuScreens$ScreenConstructor");
            Object constructor = java.lang.reflect.Proxy.newProxyInstance(constructorType.getClassLoader(),
                    new Class<?>[] { constructorType }, (proxy, method, args) -> {
                        if (method.getName().equals("create") && args != null && args.length == 3) {
                            return new WeaponForgeScreen((WeaponForgeMenu) args[0],
                                    (net.minecraft.world.entity.player.Inventory) args[1],
                                    (net.minecraft.network.chat.Component) args[2]);
                        }
                        if (method.isDefault()) {
                            return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
                        }
                        if (method.getName().equals("hashCode")) {
                            return System.identityHashCode(proxy);
                        }
                        if (method.getName().equals("equals")) {
                            return proxy == args[0];
                        }
                        return "ikioiken forge screen";
                    });
            java.lang.reflect.Method register = Class.forName("net.minecraft.client.gui.screens.MenuScreens")
                    .getDeclaredMethod("register", net.minecraft.world.inventory.MenuType.class, constructorType);
            register.setAccessible(true);
            register.invoke(null, WeaponForge.menuType, constructor);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            throw new IllegalStateException("武器合体台の画面を登録できませんでした", exception);
        }
    }
}
