package com.maccha.ikioiken.client;

import com.maccha.ikioiken.BowCurvePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Items;

/**
 * 弓を引いている間の、マウス（視点）の動きの速さを測って、サーバーへ送る。
 */
public final class BowClient {

    private BowClient() {
    }

    private static float lastYaw = 0.0f;
    private static float lastPitch = 0.0f;
    private static float smoothYaw = 0.0f;
    private static float smoothPitch = 0.0f;

    /** 毎 tick、弓を引いているなら視点の動きの速さ（度/tick）を送る。 */
    public static void onClientTick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }
        float yaw = player.getYRot();
        float pitch = player.getXRot();
        float deltaYaw = Mth.wrapDegrees(yaw - lastYaw);
        float deltaPitch = pitch - lastPitch;
        lastYaw = yaw;
        lastPitch = pitch;

        boolean drawingBow = player.isUsingItem() && player.getUseItem().is(Items.BOW);
        if (!drawingBow || minecraft.getConnection() == null) {
            smoothYaw = 0.0f;
            smoothPitch = 0.0f;
            return;
        }

        // 少しなめらかにして（速い動きが残るように）送る
        smoothYaw = smoothYaw * 0.4f + deltaYaw * 0.6f;
        smoothPitch = smoothPitch * 0.4f + deltaPitch * 0.6f;
        ClientPlayNetworking.send(new BowCurvePayload(smoothYaw, smoothPitch));
    }
}
