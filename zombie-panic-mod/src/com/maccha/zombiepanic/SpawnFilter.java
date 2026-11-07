package com.maccha.zombiepanic;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Zombie;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;

/**
 * 呪いのあいだは、どのディメンションでも ゾンビしか わかないようにする。
 * スポーンエッグ・コマンド・繁殖・バケツ・変身（村人ゾンビを治すなど）は止めない。
 */
public final class SpawnFilter {

    private SpawnFilter() {
    }

    /** 自然スポーン・スポナーの場所チェックで、ゾンビ以外を失敗にする。 */
    public static void onPositionCheck(MobSpawnEvent.PositionCheck event) {
        if (!PanicEvent.isCurseActive() || !isBlockedSpawnType(event.getSpawnType())) {
            return;
        }
        if (!isZombieKind(event.getEntity())) {
            event.setResult(MobSpawnEvent.PositionCheck.Result.FAIL);
        }
    }

    /** わく直前に、ゾンビ以外が出るのを取り消す。 */
    public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        if (!PanicEvent.isCurseActive() || !isBlockedSpawnType(event.getSpawnType())) {
            return;
        }
        if (!isZombieKind(event.getEntity())) {
            event.setSpawnCancelled(true);
        }
    }

    /** 止める対象のわき方かどうか（自然・地形生成・パトロール・建物・スポナー・イベントなど）。 */
    private static boolean isBlockedSpawnType(MobSpawnType spawnType) {
        return switch (spawnType) {
            case NATURAL, CHUNK_GENERATION, PATROL, STRUCTURE, SPAWNER, TRIAL_SPAWNER,
                 EVENT, REINFORCEMENT, JOCKEY, MOB_SUMMONED -> true;
            default -> false;
        };
    }

    /** ゾンビのなかま（ゾンビ・ハスク・ドラウンド・村人ゾンビ・ゾンビピグリン）かどうか。 */
    private static boolean isZombieKind(Entity entity) {
        return entity instanceof Zombie;
    }
}
