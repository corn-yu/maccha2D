package com.maccha.zombiepanic;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Zombie;

/**
 * ゾンビのレベル。レベルが高いほど 体力・攻撃・かたさ・速さが上がる。
 * 遊ぶ人には「Lv.数字」だけが見える（くわしい強さや能力は見えない）。
 * 近くのプレイヤーのレベルが 出てくるゾンビの平均レベルになる。
 */
public final class ZombieLevel {

    // レベルをおぼえておく目じるし（例: zp_lv_37）
    private static final String LEVEL_TAG_PREFIX = "zp_lv_";

    private static final RandomSource random = RandomSource.create();

    private ZombieLevel() {
    }

    /** このゾンビのレベル（きまっていなければ 0）。 */
    public static int levelOf(Entity entity) {
        for (String tag : entity.entityTags()) {
            if (tag.startsWith(LEVEL_TAG_PREFIX)) {
                try {
                    return Integer.parseInt(tag.substring(LEVEL_TAG_PREFIX.length()));
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        return 0;
    }

    /** 近くのプレイヤーのレベルを、基準にする（だれも近くにいなければ 全員の平均）。 */
    public static int baseLevelNear(Zombie zombie) {
        if (!(zombie.level() instanceof ServerLevel level)) {
            return 1;
        }
        net.minecraft.world.entity.player.Player nearest = level.getNearestPlayer(zombie, 128.0);
        if (nearest instanceof ServerPlayer player) {
            return PlayerLevel.levelOf(player);
        }
        int sum = 0;
        int count = 0;
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            sum += PlayerLevel.levelOf(player);
            count++;
        }
        return count == 0 ? 1 : Math.max(1, sum / count);
    }

    /** くじびきでレベルをきめる（平均が 近くのプレイヤーのレベルになる）。 */
    public static int pickLevel(Zombie zombie) {
        int base = baseLevelNear(zombie);
        double spread = Math.max(1.0, base * PanicConfig.LEVEL_SPREAD);
        int level = (int) Math.round(base + random.nextGaussian() * spread);
        // ごくたまに 大きく変異する
        if (random.nextInt(PanicConfig.LEVEL_MUTATION_CHANCE) == 0) {
            level = (int) (base * (1.5 + random.nextDouble()));
        }
        return Math.max(1, Math.min(PanicConfig.LEVEL_MAX, level));
    }

    /** レベルに合った能力の段（0〜3）。 */
    public static int tierFor(int level) {
        // 災厄(Lv100以上)は呼び名だけ。能力の段は 2までにする
        if (level >= PanicConfig.LEVEL_FOR_TIER2) {
            return 2;
        }
        return level >= PanicConfig.LEVEL_FOR_TIER1 ? 1 : 0;
    }

    /** レベルに合わせて 体を強くして、名前に Lv を出す。 */
    public static void apply(Zombie zombie, int level) {
        zombie.addTag(LEVEL_TAG_PREFIX + level);
        int steps = level - 1;

        // 体力 → 攻撃 → かたさ → 足の速さ
        MobUtil.setMaxHealth(zombie, zombie.getMaxHealth() * (1.0 + PanicConfig.LEVEL_HEALTH_PER_LEVEL * steps));
        MobUtil.addBaseAttribute(zombie, Attributes.ATTACK_DAMAGE, PanicConfig.LEVEL_ATTACK_PER_LEVEL * steps);
        MobUtil.addBaseAttribute(zombie, Attributes.ARMOR, Math.min(20.0, PanicConfig.LEVEL_ARMOR_PER_LEVEL * steps));
        ZombieAbilities.multiplyBase(zombie, Attributes.MOVEMENT_SPEED, 1.0 + Math.min(0.3, PanicConfig.LEVEL_SPEED_PER_LEVEL * steps));

        // 高いレベルは ふっとばされにくく、体力ももどる
        if (level >= PanicConfig.LEVEL_FOR_TIER2) {
            MobUtil.addBaseAttribute(zombie, Attributes.KNOCKBACK_RESISTANCE, 0.3);
            MobUtil.addForeverEffect(zombie, MobEffects.REGENERATION, 0);
        }
        setLevelName(zombie);
    }

    /** 頭の上の名前を「Lv.数字 ゾンビ」にする（能力の名前は出さない）。 */
    public static void setLevelName(Zombie zombie) {
        int level = levelOf(zombie);
        ChatFormatting color = level >= PanicConfig.LEVEL_FOR_TIER3 ? ChatFormatting.DARK_RED
                : level >= PanicConfig.LEVEL_FOR_TIER2 ? ChatFormatting.LIGHT_PURPLE
                : level >= PanicConfig.LEVEL_FOR_TIER1 ? ChatFormatting.YELLOW : ChatFormatting.GRAY;
        Component name = Component.literal("Lv." + level + " ゾンビ").withStyle(color);
        if (level >= PanicConfig.LEVEL_FOR_TIER3) {
            name = Component.literal("Lv." + level + " 災厄ゾンビ").withStyle(color, ChatFormatting.BOLD);
        }
        zombie.setCustomName(name);
        // 名前（レベル）は、いつも見えるようにする
        zombie.setCustomNameVisible(true);
    }

    /** いまの大きさ。 */
    private static double scaleOf(Zombie zombie) {
        var instance = zombie.getAttribute(Attributes.SCALE);
        return instance != null ? instance.getBaseValue() : 1.0;
    }
}
