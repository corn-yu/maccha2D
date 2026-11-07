package com.maccha.zombiepanic;

import net.minecraft.world.entity.EntityTypes;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ボスゾンビ（たおした数が決まった数になると出る・「ゾンビ大将の襲来」でも出る）。
 */
public final class BossZombies {

    public static final String BOSS_TAG = "zombiepanic_boss";

    private static final RandomSource random = RandomSource.create();

    // 出ているボスと、その体力バー
    private static final Map<UUID, Zombie> aliveBosses = new HashMap<>();
    private static final Map<UUID, ServerBossEvent> bossBars = new HashMap<>();

    /** ボスの種類。 */
    public enum BossKind {
        GENERAL("ゾンビ大将", ChatFormatting.GOLD),
        DESERT_KING("砂漠の王", ChatFormatting.YELLOW),
        SEA_LORD("深海の主", ChatFormatting.AQUA),
        ROTTEN_GIANT("腐った巨人", ChatFormatting.DARK_GREEN),
        NECROMANCER("死霊使いゾンビ", ChatFormatting.DARK_PURPLE),
        STORM_GENERAL("嵐の将軍", ChatFormatting.BLUE),
        HUNTER("鉤爪の狩人", ChatFormatting.RED),
        SKY_TYRANT("空の暴君", ChatFormatting.WHITE);

        public final String displayName;
        public final ChatFormatting color;

        /** ボスの名前と色を決める。 */
        BossKind(String displayName, ChatFormatting color) {
            this.displayName = displayName;
            this.color = color;
        }
    }

    private BossZombies() {
    }

    // ---------------- 出す ----------------

    /** 次に出すボスの種類（全種類を順番に。同じボスばかりにならない）。 */
    public static BossKind nextKindInTurn(WorldData data) {
        BossKind[] kinds = BossKind.values();
        BossKind kind = kinds[Math.floorMod(data.bossTurn, kinds.length)];
        data.bossTurn++;
        data.setDirty();
        return kind;
    }

    /** プレイヤーの近くにボスを出す（kind が null ならランダム）。出せたら そのボス。 */
    public static Zombie spawnBossNear(ServerPlayer targetPlayer, BossKind kind) {
        ServerLevel level = targetPlayer.level();
        BossKind chosenKind = kind != null ? kind : BossKind.values()[random.nextInt(BossKind.values().length)];

        // 少しはなれた場所に出す（だめなら近くでも）
        BlockPos spawnPos = ZombieSpawner.findSpawnPosNear(level, targetPlayer.blockPosition(), 14, 24, true);
        if (spawnPos == null) {
            spawnPos = ZombieSpawner.findSpawnPosNear(level, targetPlayer.blockPosition(), 6, 14, false);
        }
        if (spawnPos == null) {
            return null;
        }

        Zombie boss = createBossEntity(level, spawnPos, chosenKind);
        if (boss == null) {
            return null;
        }
        setupBoss(boss, chosenKind);
        boss.setTarget(targetPlayer);
        addBossBar(level.getServer(), boss, chosenKind);

        // みんなに知らせる
        level.getServer().getPlayerList().broadcastSystemMessage(Component.literal("ボス「" + chosenKind.displayName + "」が "
                + targetPlayer.getName().getString() + " の近くに あらわれた！").withStyle(chosenKind.color, ChatFormatting.BOLD), false);
        PanicEvent.showTitleToAll(level.getServer(),
                Component.literal("ボス出現！").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD),
                Component.literal(chosenKind.displayName).withStyle(chosenKind.color, ChatFormatting.BOLD));
        for (ServerPlayer player : level.players()) {
            MobUtil.playFor(player, SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 0.6f, 1.2f);
        }
        return boss;
    }

    /** ボスの種類に合ったゾンビを出す。 */
    private static Zombie createBossEntity(ServerLevel level, BlockPos spawnPos, BossKind kind) {
        EntityType<? extends Zombie> type = switch (kind) {
            case DESERT_KING -> EntityTypes.HUSK;
            case SEA_LORD -> EntityTypes.DROWNED;
            case NECROMANCER -> EntityTypes.ZOMBIE_VILLAGER;
            default -> EntityTypes.ZOMBIE;
        };
        // 先にボスの目じるしをつけてから出す（能力のくじを引かないように）
        return type.spawn(level, zombie -> {
            zombie.addTag(BOSS_TAG);
            zombie.addTag(ZombieAbilities.ROLLED_TAG);
        }, spawnPos, EntitySpawnReason.EVENT, false, false);
    }

    /** ボスを強くして、見た目と能力をととのえる。 */
    private static void setupBoss(Zombie boss, BossKind kind) {
        boss.setBaby(false);
        boss.setPersistenceRequired();
        boss.setCustomName(Component.literal(kind.displayName).withStyle(kind.color, ChatFormatting.BOLD));
        boss.setCustomNameVisible(true);
        MobUtil.setBaseAttribute(boss, Attributes.FOLLOW_RANGE, PanicConfig.ZOMBIE_FOLLOW_RANGE);
        MobUtil.setBaseAttribute(boss, Attributes.KNOCKBACK_RESISTANCE, 0.8);
        MobUtil.addForeverEffect(boss, MobEffects.FIRE_RESISTANCE, 0);
        MobUtil.addForeverEffect(boss, MobEffects.GLOWING, 0);

        switch (kind) {
            case GENERAL -> {
                setBossStats(boss, 300.0, 12.0, 0.27, 1.8);
                equipNoDrop(boss, EquipmentSlot.HEAD, Items.NETHERITE_HELMET);
                equipNoDrop(boss, EquipmentSlot.CHEST, Items.DIAMOND_CHESTPLATE);
                equipNoDrop(boss, EquipmentSlot.MAINHAND, Items.IRON_SWORD);
                boss.addTag(ZombieAbilities.Ability.CHARGER.tag());
                boss.addTag(ZombieAbilities.Ability.SUMMONER.tag());
            }
            case DESERT_KING -> {
                setBossStats(boss, 280.0, 11.0, 0.3, 1.8);
                equipNoDrop(boss, EquipmentSlot.HEAD, Items.GOLDEN_HELMET);
                equipNoDrop(boss, EquipmentSlot.CHEST, Items.GOLDEN_CHESTPLATE);
                equipNoDrop(boss, EquipmentSlot.MAINHAND, Items.GOLDEN_AXE);
                boss.addTag(ZombieAbilities.Ability.HUNGER.tag());
                boss.addTag(ZombieAbilities.Ability.FIRE.tag());
                boss.addTag(ZombieAbilities.Ability.METEOR.tag());
            }
            case SEA_LORD -> {
                setBossStats(boss, 260.0, 12.0, 0.28, 1.8);
                equipNoDrop(boss, EquipmentSlot.MAINHAND, Items.TRIDENT);
                equipNoDrop(boss, EquipmentSlot.HEAD, Items.TURTLE_HELMET);
                boss.addTag(ZombieAbilities.Ability.PULLER.tag());
                boss.addTag(ZombieAbilities.Ability.TORNADO.tag());
            }
            case ROTTEN_GIANT -> {
                setBossStats(boss, 450.0, 16.0, 0.22, 4.0);
                boss.addTag(ZombieAbilities.Ability.HUNGER.tag());
                boss.addTag(ZombieAbilities.Ability.GIANT.tag());
                MobUtil.setBaseAttribute(boss, Attributes.STEP_HEIGHT, 2.0);
            }
            case NECROMANCER -> {
                setBossStats(boss, 220.0, 9.0, 0.27, 1.6);
                equipNoDrop(boss, EquipmentSlot.HEAD, Items.WITHER_SKELETON_SKULL);
                boss.addTag(ZombieAbilities.Ability.SUMMONER.tag());
                boss.addTag(ZombieAbilities.Ability.TELEPORTER.tag());
                boss.addTag(ZombieAbilities.Ability.SHADOW.tag());
            }
            case STORM_GENERAL -> {
                setBossStats(boss, 260.0, 11.0, 0.32, 1.8);
                equipNoDrop(boss, EquipmentSlot.HEAD, Items.CHAINMAIL_HELMET);
                equipNoDrop(boss, EquipmentSlot.CHEST, Items.CHAINMAIL_CHESTPLATE);
                equipNoDrop(boss, EquipmentSlot.MAINHAND, Items.DIAMOND_SWORD);
                boss.addTag(ZombieAbilities.Ability.THUNDER.tag());
                boss.addTag(ZombieAbilities.Ability.FREEZER.tag());
                boss.addTag(ZombieAbilities.Ability.GRAPPLE.tag());
            }
            case HUNTER -> {
                setBossStats(boss, 260.0, 12.0, 0.33, 1.7);
                equipNoDrop(boss, EquipmentSlot.HEAD, Items.LEATHER_HELMET);
                equipNoDrop(boss, EquipmentSlot.MAINHAND, Items.IRON_AXE);
                boss.addTag(ZombieAbilities.Ability.GRAPPLE.tag());
                boss.addTag(ZombieAbilities.Ability.CHARGER.tag());
                boss.addTag(ZombieAbilities.Ability.CLONE.tag());
            }
            case SKY_TYRANT -> {
                setBossStats(boss, 280.0, 13.0, 0.3, 1.9);
                equipNoDrop(boss, EquipmentSlot.CHEST, Items.ELYTRA);
                equipNoDrop(boss, EquipmentSlot.MAINHAND, Items.MACE);
                boss.addTag(ZombieAbilities.Ability.ROCKET.tag());
                boss.addTag(ZombieAbilities.Ability.WIND.tag());
                MobUtil.addForeverEffect(boss, MobEffects.JUMP_BOOST, 2);
            }
        }
    }

    /** 体力・攻撃・速さ・大きさを決める。 */
    private static void setBossStats(Zombie boss, double health, double attack, double speed, double scale) {
        MobUtil.setBaseAttribute(boss, Attributes.ATTACK_DAMAGE, attack);
        MobUtil.setBaseAttribute(boss, Attributes.MOVEMENT_SPEED, speed);
        MobUtil.setBaseAttribute(boss, Attributes.SCALE, scale);
        MobUtil.setMaxHealth(boss, health);
    }

    /** そうびを持たせる（落とさない）。 */
    private static void equipNoDrop(Zombie boss, EquipmentSlot slot, net.minecraft.world.item.Item item) {
        MobUtil.equip(boss, slot, new ItemStack(item), 0.0f);
    }

    // ---------------- 体力バー ----------------

    /** ボスの体力バーを作って、みんなに見せる。 */
    private static void addBossBar(MinecraftServer server, Zombie boss, BossKind kind) {
        ServerBossEvent bar = new ServerBossEvent(java.util.UUID.randomUUID(), Component.literal(kind.displayName).withStyle(kind.color),
                BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.PROGRESS);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            bar.addPlayer(player);
        }
        aliveBosses.put(boss.getUUID(), boss);
        bossBars.put(boss.getUUID(), bar);
    }

    /** 体力バーを新しくし、いなくなったボスのバーを消す（1秒ごと）。 */
    public static void updateBars() {
        Iterator<Map.Entry<UUID, Zombie>> iterator = aliveBosses.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Zombie> entry = iterator.next();
            Zombie boss = entry.getValue();
            ServerBossEvent bar = bossBars.get(entry.getKey());
            if (boss.isAlive() && !boss.isRemoved()) {
                if (bar != null) {
                    bar.setProgress(Math.max(0.0f, boss.getHealth() / boss.getMaxHealth()));
                }
                continue;
            }
            if (bar != null) {
                bar.removeAllPlayers();
            }
            bossBars.remove(entry.getKey());
            iterator.remove();
        }
    }

    /** 途中で入ってきた人にもバーを見せる。 */
    public static void showBarsTo(ServerPlayer player) {
        for (ServerBossEvent bar : bossBars.values()) {
            bar.addPlayer(player);
        }
    }

    /** ぬけた人をバーから外す。 */
    public static void hideBarsFrom(ServerPlayer player) {
        for (ServerBossEvent bar : bossBars.values()) {
            bar.removePlayer(player);
        }
    }

    /** バーを全部消す。 */
    public static void clearAll() {
        for (ServerBossEvent bar : bossBars.values()) {
            bar.removeAllPlayers();
        }
        bossBars.clear();
        aliveBosses.clear();
    }

    /** いま生きているボスの一覧。 */
    public static List<Zombie> getAliveBosses() {
        return new ArrayList<>(aliveBosses.values());
    }

    // ---------------- たおされたとき ----------------

    /** ボスかどうか。 */
    public static boolean isBoss(Zombie zombie) {
        return zombie.entityTags().contains(BOSS_TAG);
    }

    /** ボスがたおされたら、知らせて ごほうびを落とす。 */
    public static void onBossDefeated(MinecraftServer server, Zombie boss, ServerPlayer killerPlayer) {
        String heroName = killerPlayer != null ? killerPlayer.getName().getString() : "だれか";
        String bossName = boss.getCustomName() != null ? boss.getCustomName().getString() : "ボス";
        server.getPlayerList().broadcastSystemMessage(Component.literal(heroName + " が " + bossName + " を たおした！ すごい！")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            MobUtil.playFor(player, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1.0f, 1.0f);
        }

        // ごほうび（呪鉄・エンダーアイ・金リンゴ・ダイヤ）
        MobUtil.drop(boss, new ItemStack(ModContent.CURSED_IRON_INGOT.get(), 3 + random.nextInt(3)));
        MobUtil.drop(boss, new ItemStack(Items.ENDER_EYE, 1 + random.nextInt(2)));
        MobUtil.drop(boss, new ItemStack(Items.GOLDEN_APPLE, 1));
        MobUtil.drop(boss, new ItemStack(Items.DIAMOND, 2));

        // バーを消す
        ServerBossEvent bar = bossBars.remove(boss.getUUID());
        if (bar != null) {
            bar.removeAllPlayers();
        }
        aliveBosses.remove(boss.getUUID());
    }
}
