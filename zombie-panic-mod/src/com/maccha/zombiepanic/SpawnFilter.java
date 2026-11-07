package com.maccha.zombiepanic;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Zombie;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;

/**
 * パニック中は、自然にわくモブをゾンビだけにする。
 * スポナー・スポーンエッグ・繁殖・建物のモブ（ボスなど）は止めない。
 */
public final class SpawnFilter {

    private SpawnFilter() {
    }

    /** 自然スポーンの場所チェックで、ゾンビ以外を失敗にする。 */
    public static void onPositionCheck(MobSpawnEvent.PositionCheck event) {
        // パニック中の自然スポーンだけ見る
        if (!PanicEvent.isPanicActive() || event.getSpawnType() != MobSpawnType.NATURAL) {
            return;
        }
        if (!isZombieKind(event.getEntity())) {
            event.setResult(MobSpawnEvent.PositionCheck.Result.FAIL);
        }
    }

    /** 自然スポーン・地形生成・パトロールで、ゾンビ以外が出るのを取り消す。 */
    public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        if (!PanicEvent.isPanicActive() || !isBlockedSpawnType(event.getSpawnType())) {
            return;
        }
        if (!isZombieKind(event.getEntity())) {
            event.setSpawnCancelled(true);
        }
    }

    /** 止める対象のわき方かどうか。 */
    private static boolean isBlockedSpawnType(MobSpawnType spawnType) {
        return spawnType == MobSpawnType.NATURAL
                || spawnType == MobSpawnType.CHUNK_GENERATION
                || spawnType == MobSpawnType.PATROL;
    }

    /** ゾンビのなかま（ゾンビ・ハスク・ドラウンド・村人ゾンビ・ゾンビピグリン）かどうか。 */
    private static boolean isZombieKind(Entity entity) {
        return entity instanceof Zombie;
    }
}
