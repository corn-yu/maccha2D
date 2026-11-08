package com.maccha.zombiepanic;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * プレイヤーのレベル。ゾンビをたおすと経験値がたまり、レベルが上がると 体力・攻撃・かたさが強くなる。
 * 高いレベルのゾンビほど たくさんの経験値をくれる。
 */
public final class PlayerLevel {

    private PlayerLevel() {
    }

    /** 次のレベルまでに必要な経験値。 */
    public static int expNeeded(int level) {
        return PanicConfig.PLAYER_EXP_BASE + PanicConfig.PLAYER_EXP_PER_LEVEL * level;
    }

    /** いまのレベル（はじめは 1）。 */
    public static int levelOf(ServerPlayer player) {
        return PlayerLevelData.get(player.level().getServer()).entryOf(player.getUUID()).level;
    }

    /** 生きのびた時間でも少しずつ経験値がたまる（1分ごと）。 */
    public static void addPassiveExp(ServerPlayer player) {
        addExpForKill(player, PanicConfig.PLAYER_PASSIVE_EXP_BASE + levelOf(player) / PanicConfig.PLAYER_PASSIVE_EXP_DIVISOR);
    }

    /** ゾンビをたおして経験値をもらう（ゾンビのレベルぶん）。 */
    public static void addExpForKill(ServerPlayer player, int zombieLevel) {
        PlayerLevelData data = PlayerLevelData.get(player.level().getServer());
        PlayerLevelData.Entry entry = data.entryOf(player.getUUID());
        if (entry.level >= PanicConfig.PLAYER_LEVEL_MAX) {
            return;
        }
        entry.exp += Math.max(1, zombieLevel);
        boolean leveledUp = false;
        while (entry.level < PanicConfig.PLAYER_LEVEL_MAX && entry.exp >= expNeeded(entry.level)) {
            entry.exp -= expNeeded(entry.level);
            entry.level++;
            leveledUp = true;
        }
        data.setDirty();
        if (leveledUp) {
            applyStats(player, entry.level);
            player.setHealth(player.getMaxHealth());
            player.sendSystemMessage(Component.literal("レベルアップ！ いまは Lv." + entry.level + "（体力・攻撃・かたさが上がった）")
                    .withStyle(ChatFormatting.GOLD));
            player.level().playSound(null, player.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0f, 1.0f);
        }
    }

    /** ログインや復活のとき、いまのレベルの強さをつけなおす。 */
    public static void reapply(ServerPlayer player) {
        PlayerLevelData data = PlayerLevelData.get(player.level().getServer());
        applyStats(player, data.entryOf(player.getUUID()).level);
    }

    /** レベルに合わせて、体力・攻撃・かたさの加算をつける（つけなおしOK）。 */
    private static void applyStats(ServerPlayer player, int level) {
        int steps = level - 1;
        setBonus(player, Attributes.MAX_HEALTH, "level_health", PanicConfig.PLAYER_HEALTH_PER_LEVEL * steps);
        setBonus(player, Attributes.ATTACK_DAMAGE, "level_attack", PanicConfig.PLAYER_ATTACK_PER_LEVEL * steps);
        setBonus(player, Attributes.ARMOR, "level_armor", PanicConfig.PLAYER_ARMOR_PER_LEVEL * steps);
    }

    /** 1つの能力に、レベルぶんの加算をつける。 */
    private static void setBonus(ServerPlayer player, Holder<Attribute> attribute, String name, double amount) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        Identifier id = Identifier.fromNamespaceAndPath(ZombiePanic.MOD_ID, name);
        instance.addOrReplacePermanentModifier(new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_VALUE));
    }
}
