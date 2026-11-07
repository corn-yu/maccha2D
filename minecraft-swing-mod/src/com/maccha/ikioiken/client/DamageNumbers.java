package com.maccha.ikioiken.client;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.joml.Matrix4f;
import org.joml.Vector4f;

import com.maccha.ikioiken.DamagePayload;
import com.maccha.ikioiken.IkioiKen;
import com.maccha.ikioiken.SwingConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * 当てた相手の体から、ダメージの数字をポンッと出すクラス（モンハンのような出方）。
 */
@EventBusSubscriber(modid = IkioiKen.MOD_ID, value = Dist.CLIENT)
public final class DamageNumbers {

    private DamageNumbers() {
    }

    /** 画面に出ている数字 1 つぶん。 */
    private record Popup(double x, double y, double z, String text, int color, float size, float startTime, float driftX) {
    }

    // 出ている数字
    private static final List<Popup> POPUPS = new ArrayList<>();

    // 今のフレームのカメラの向き・映し方・位置（世界の場所を画面の場所に変えるのに使う）
    private static final Matrix4f VIEW_MATRIX = new Matrix4f();
    private static final Matrix4f PROJECTION_MATRIX = new Matrix4f();
    private static Vec3 cameraPosition = Vec3.ZERO;
    private static boolean hasCamera = false;

    /** 今の時間（tick、小数つき）を返す。 */
    private static float now(float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return 0.0f;
        }
        return (float) (minecraft.level.getGameTime() % 1000000L) + partialTick;
    }

    /** サーバーからダメージが届いたら、数字を 1 つ出す。 */
    public static void onDamage(DamagePayload payload) {
        if (payload.damage() <= 0.0f) {
            return;
        }

        // 色と大きさ（ふつう＝白、強い当たり＝黄色、超クリティカル＝赤で大きく）
        int color = 0xFFFFFF;
        float size = 1.0f;
        if (payload.style() == DamagePayload.STRONG) {
            color = 0xFFC83A;
            size = 1.25f;
        } else if (payload.style() == DamagePayload.CRITICAL) {
            color = 0xFF4A2A;
            size = 1.8f;
        }

        // 数字（小数がなければ整数だけ）
        float damage = payload.damage();
        String text = Math.abs(damage - Math.round(damage)) < 0.05f
                ? String.valueOf(Math.round(damage))
                : String.format("%.1f", damage);
        if (payload.style() == DamagePayload.CRITICAL) {
            text = text + "!";
        }

        // 少しずつ左右にちらばるように
        float driftX = (float) (Math.random() - 0.5) * 0.4f;
        POPUPS.add(new Popup(payload.x(), payload.y(), payload.z(), text, color, size, now(0.0f), driftX));

        // 出しすぎないように古いものから消す
        while (POPUPS.size() > 40) {
            POPUPS.remove(0);
        }
    }

    /** 世界を描き終わったときのカメラの向きと映し方を覚える。 */
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }
        VIEW_MATRIX.set(event.getModelViewMatrix());
        PROJECTION_MATRIX.set(event.getProjectionMatrix());
        cameraPosition = event.getCamera().getPosition();
        hasCamera = true;
    }

    /** 画面のいちばん上に、相手の場所から数字を描く（壁のむこうでも見える）。 */
    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (POPUPS.isEmpty() || !hasCamera) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        GuiGraphics graphics = event.getGuiGraphics();
        Font font = minecraft.font;
        float currentTime = now(event.getPartialTick().getGameTimeDeltaPartialTick(false));

        Iterator<Popup> iterator = POPUPS.iterator();
        while (iterator.hasNext()) {
            Popup popup = iterator.next();
            float age = currentTime - popup.startTime();

            // 時間が来たら消す（時間がもどったとき＝ワールドを変えたときも消す）
            if (age > SwingConfig.DAMAGE_NUMBER_TICKS || age < -1.0f) {
                iterator.remove();
                continue;
            }

            // ふわっと上にのぼる
            double rise = 0.35 * (1.0 - Math.pow(1.0 - Math.min(age / SwingConfig.DAMAGE_NUMBER_TICKS, 1.0), 2.0));
            float[] screen = toScreen(popup.x() + popup.driftX() * age / SwingConfig.DAMAGE_NUMBER_TICKS,
                    popup.y() + 0.2 + rise, popup.z(), graphics);
            if (screen == null) {
                continue;
            }

            // 出た瞬間はポンッと大きく、すぐ落ちつく
            float pop = age < 3.0f ? 1.0f + 0.7f * (1.0f - age / 3.0f) : 1.0f;
            float scale = popup.size() * pop * 1.3f;

            // 終わりぎわにうすくなる
            float fadeStart = SwingConfig.DAMAGE_NUMBER_TICKS * 0.6f;
            float alpha = age < fadeStart ? 1.0f : 1.0f - (age - fadeStart) / (SwingConfig.DAMAGE_NUMBER_TICKS - fadeStart);
            int alphaBits = (int) (Math.max(alpha, 0.0f) * 255.0f);
            if (alphaBits < 10) {
                continue;
            }

            // 描く
            graphics.pose().pushPose();
            graphics.pose().translate(screen[0], screen[1], 200.0f);
            graphics.pose().scale(scale, scale, 1.0f);
            int textWidth = font.width(popup.text());
            graphics.drawString(font, popup.text(), -textWidth / 2, -4, (alphaBits << 24) | popup.color(), true);
            graphics.pose().popPose();
        }
    }

    /** 世界の場所を、画面の場所（GUI の座標）に変える。カメラのうしろなら null。 */
    private static float[] toScreen(double x, double y, double z, GuiGraphics graphics) {
        Vector4f point = new Vector4f((float) (x - cameraPosition.x), (float) (y - cameraPosition.y),
                (float) (z - cameraPosition.z), 1.0f);
        VIEW_MATRIX.transform(point);
        PROJECTION_MATRIX.transform(point);
        if (point.w <= 0.05f) {
            return null;
        }
        float screenX = point.x / point.w;
        float screenY = point.y / point.w;
        if (Math.abs(screenX) > 1.2f || Math.abs(screenY) > 1.2f) {
            return null;
        }
        return new float[] {
                (screenX + 1.0f) * 0.5f * graphics.guiWidth(),
                (1.0f - screenY) * 0.5f * graphics.guiHeight()
        };
    }
}
