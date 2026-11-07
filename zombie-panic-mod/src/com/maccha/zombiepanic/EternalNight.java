package com.maccha.zombiepanic;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameRules;

/**
 * 呪いのあいだは「永遠の明るい夜」にする（ずっと夜で ゾンビは燃えない・プレイヤーには明るく見える）。
 */
public final class EternalNight {

    // 真夜中の時刻（1日 = 24000）
    private static final long MIDNIGHT = 18000L;
    // 夜明けの時刻（クリアしたとき）
    private static final long SUNRISE = 23500L;
    // 明るく見える効果の長さ（10秒より長くして、点めつしないようにする）
    private static final int NIGHT_VISION_TICKS = 20 * 20;

    private EternalNight() {
    }

    /** 1秒ごと: 時間を真夜中にとめて、みんなを明るく見えるようにする。 */
    public static void keepNight(MinecraftServer server) {
        // 昼と夜のくりかえしを止める
        GameRules.BooleanValue daylightRule = server.getGameRules().getRule(GameRules.RULE_DAYLIGHT);
        if (daylightRule.get()) {
            daylightRule.set(false, server);
        }

        // 夜でなければ 真夜中にする（ベッドで寝て朝になっても すぐ夜にもどる）
        ServerLevel overworld = server.overworld();
        long timeOfDay = overworld.getDayTime() % 24000L;
        if (timeOfDay != MIDNIGHT) {
            long dayStart = overworld.getDayTime() - timeOfDay;
            overworld.setDayTime(dayStart + MIDNIGHT);
        }

        // 明るく見える効果（アイコンもつぶつぶも出さない）
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            MobEffectInstance current = player.getEffect(MobEffects.NIGHT_VISION);
            if (current == null || current.getDuration() < NIGHT_VISION_TICKS - 100) {
                player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, NIGHT_VISION_TICKS, 0, false, false, false));
            }
        }
    }

    /** 呪いがとけたら、夜明けにして 昼と夜をもどす。 */
    public static void bringBackSunrise(MinecraftServer server) {
        server.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(true, server);
        ServerLevel overworld = server.overworld();
        long timeOfDay = overworld.getDayTime() % 24000L;
        overworld.setDayTime(overworld.getDayTime() - timeOfDay + SUNRISE);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.removeEffect(MobEffects.NIGHT_VISION);
        }
    }
}
