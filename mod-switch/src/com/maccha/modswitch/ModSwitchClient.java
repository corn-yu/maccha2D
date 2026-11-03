package com.maccha.modswitch;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MODスイッチ：Mod Menu の画面に「MODスイッチ」ボタンを足す
 */
public class ModSwitchClient implements ClientModInitializer {

    public static final Logger LOG = LoggerFactory.getLogger("modswitch");

    // Mod Menu の MOD 一覧の画面（Mod Menu がなくても落ちないように、名前でくらべる）
    static final String MOD_MENU_SCREEN = "com.terraformersmc.modmenu.gui.ModsScreen";

    /** MOD が読みこまれたら、画面が開くたびに しらべるようにする */
    @Override
    public void onInitializeClient() {
        ScreenEvents.AFTER_INIT.register(this::afterInit);
    }

    /** 画面が開いたとき：Mod Menu の一覧なら、下の右がわに「MODスイッチ」ボタンを足す */
    void afterInit(Minecraft minecraft, Screen screen, int width, int height) {
        if (!screen.getClass().getName().equals(MOD_MENU_SCREEN)) return;
        // Mod Menu の下のボタン（フォルダを開く・完了）は まん中に 2つ並んでいるので、その右に置く
        int x = width / 2 + 158;
        int buttonWidth = Math.min(110, width - x - 4);
        if (buttonWidth < 60) {
            // 右に入らないくらい せまいときは、左に置く
            buttonWidth = Math.min(110, width / 2 - 162);
            x = width / 2 - 158 - buttonWidth;
        }
        if (buttonWidth < 40) return;
        Button button = Button.builder(Component.literal("MODスイッチ"),
                        press -> minecraft.gui.setScreen(new SwitchScreen(screen)))
                .pos(x, height - 28).size(buttonWidth, 20).build();
        Screens.getWidgets(screen).add(button);
    }
}
