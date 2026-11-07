package com.maccha.zombiepanic;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;

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

    /** 世界の時計（地上の昼と夜）をとり出す。 */
    private static Holder<WorldClock> overworldClock(MinecraftServer server) {
        return server.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(WorldClocks.OVERWORLD);
    }

    /** 時計を、その日の ○時にする（1日 = 24000）。 */
    private static void setTimeOfDay(MinecraftServer server, long timeOfDay) {
        ServerClockManager clocks = server.overworld().clockManager();
        Holder<WorldClock> clock = overworldClock(server);
        long total = clocks.getInstance(clock).totalTicks();
        clocks.setTotalTicks(clock, total - Math.floorMod(total, 24000L) + timeOfDay);
    }

    /** 1秒ごと: 時間を真夜中にとめて、みんなを明るく見えるようにする。 */
    public static void keepNight(MinecraftServer server) {
        // 昼と夜のくりかえしを止める（時計をとめる）
        ServerClockManager clocks = server.overworld().clockManager();
        Holder<WorldClock> clock = overworldClock(server);
        if (!clocks.getInstance(clock).isPaused()) {
            clocks.setPaused(clock, true);
        }

        // 夜でなければ 真夜中にする（ベッドで寝て朝になっても すぐ夜にもどる）
        long timeOfDay = Math.floorMod(clocks.getInstance(clock).totalTicks(), 24000L);
        if (timeOfDay != MIDNIGHT) {
            setTimeOfDay(server, MIDNIGHT);
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
        server.overworld().clockManager().setPaused(overworldClock(server), false);
        setTimeOfDay(server, SUNRISE);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.removeEffect(MobEffects.NIGHT_VISION);
        }
    }
}
