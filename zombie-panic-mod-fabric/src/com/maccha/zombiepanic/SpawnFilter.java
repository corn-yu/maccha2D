package com.maccha.zombiepanic;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.monster.zombie.Zombie;



/**
 * 呪いのあいだは、どのディメンションでも ゾンビしか わかないようにする。
 * スポーンエッグ・コマンド・繁殖・バケツ・変身（村人ゾンビを治すなど）は止めない。
 */
public final class SpawnFilter {

    private SpawnFilter() {
    }

    /** このわき方で、この生き物を止めるか（呪いのあいだ、ゾンビ以外の自然なわきを止める）。 */
    public static boolean shouldBlock(Entity entity, EntitySpawnReason spawnType) {
        return PanicEvent.isCurseActive() && isBlockedSpawnType(spawnType) && !isZombieKind(entity);
    }

    /** 止める対象のわき方かどうか（自然・地形生成・パトロール・建物・スポナー・イベントなど）。 */
    private static boolean isBlockedSpawnType(EntitySpawnReason spawnType) {
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
