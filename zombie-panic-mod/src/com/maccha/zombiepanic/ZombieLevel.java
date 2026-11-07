package com.maccha.zombiepanic;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;

/**
 * ゾンビのレベル。レベルが高いほど 体力・攻撃・かたさ・速さが上がる。
 * 遊ぶ人には「Lv.数字」だけが見える（くわしい強さや能力は見えない）。
 * 時間がたつほど、高いレベルが出やすくなる。
 */
public final class ZombieLevel {

    // レベルをおぼえておく目じるし（例: zp_lv_37）
    private static final String LEVEL_TAG_PREFIX = "zp_lv_";

    private static final RandomSource random = RandomSource.create();

    private ZombieLevel() {
    }

    /** このゾンビのレベル（きまっていなければ 0）。 */
    public static int levelOf(Entity entity) {
        for (String tag : entity.getTags()) {
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

    /** あそんだ時間から、出てよいレベルの上限を出す（時間がたつほど 高くなる）。 */
    public static int maxLevelNow(Zombie zombie) {
        if (!(zombie.level() instanceof ServerLevel level)) {
            return 1;
        }
        long playedTicks = level.getServer().overworld().getGameTime();
        return 1 + (int) (playedTicks / PanicConfig.LEVEL_TICKS_PER_STEP);
    }

    /** くじびきでレベルをきめる（低いレベルが多く、高いレベルはまれ）。 */
    public static int pickLevel(Zombie zombie) {
        int maxLevel = maxLevelNow(zombie);
        double luck = Math.pow(random.nextDouble(), PanicConfig.LEVEL_LUCK_POWER);
        int level = 1 + (int) (maxLevel * luck);
        // ごくたまに 上限をこえて変異する
        if (random.nextInt(PanicConfig.LEVEL_MUTATION_CHANCE) == 0) {
            level = (int) (maxLevel * (1.0 + random.nextDouble() * 0.5));
        }
        return Math.max(1, Math.min(PanicConfig.LEVEL_MAX, level));
    }

    /** レベルに合った能力の段（0〜3）。 */
    public static int tierFor(int level) {
        if (level >= PanicConfig.LEVEL_FOR_TIER3) {
            return 3;
        }
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
        // 100レベをこえたら 見た目も大きく、とても強い
        if (level >= PanicConfig.LEVEL_FOR_TIER3) {
            MobUtil.addForeverEffect(zombie, MobEffects.DAMAGE_BOOST, 1);
            MobUtil.addForeverEffect(zombie, MobEffects.DAMAGE_RESISTANCE, 0);
            MobUtil.addForeverEffect(zombie, MobEffects.GLOWING, 0);
            MobUtil.setBaseAttribute(zombie, Attributes.SCALE, Math.max(1.6, scaleOf(zombie)));
            zombie.setPersistenceRequired();
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
        // 高いレベルは、いつも名前を見せる（ふつうは 見つめたときだけ）
        zombie.setCustomNameVisible(level >= PanicConfig.LEVEL_FOR_TIER2);
    }

    /** いまの大きさ。 */
    private static double scaleOf(Zombie zombie) {
        var instance = zombie.getAttribute(Attributes.SCALE);
        return instance != null ? instance.getBaseValue() : 1.0;
    }
}
