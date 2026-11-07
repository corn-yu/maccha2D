package com.maccha.zombiepanic;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.common.Tags;

import java.util.ArrayList;
import java.util.List;

/**
 * パニック中にプレイヤーのまわりへゾンビを出す係（ゾンビ大将も出す）。
 */
public final class ZombieSpawner {

    // パニックで出たゾンビにつける目じるし
    public static final String PANIC_TAG = "zombiepanic";
    public static final String BOSS_TAG = "zombiepanic_boss";

    private static final RandomSource random = RandomSource.create();

    private ZombieSpawner() {
    }

    /** 全員のまわりに、ゾンビの群れを1回ぶん出す（progress は 0〜1 のパニックの進みぐあい）。 */
    public static void spawnWave(MinecraftServer server, PanicType panicType, float progress, int effectTicks) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            // 見ているだけの人・オーバーワールド以外・ピースフルの世界にはださない
            if (player.isSpectator()) {
                continue;
            }
            ServerLevel level = player.serverLevel();
            if (level.dimension() != Level.OVERWORLD || level.getDifficulty() == Difficulty.PEACEFUL) {
                continue;
            }

            // まわりにいる数が上限に近ければ、足りないぶんだけ出す
            double multiplier = panicType.amountMultiplier();
            int maxNear = (int) Math.round(PanicConfig.MAX_ZOMBIES_NEAR_PLAYER * multiplier);
            int nearCount = countPanicZombiesNear(level, player);
            if (nearCount >= maxNear) {
                continue;
            }
            float waveSize = Mth.lerp(progress, PanicConfig.WAVE_SIZE_START, PanicConfig.WAVE_SIZE_END);
            int spawnCount = Math.min((int) Math.round(waveSize * multiplier), maxNear - nearCount);

            // 1体ずつ場所をさがして出す
            for (int i = 0; i < spawnCount; i++) {
                trySpawnZombieNear(level, player, panicType, effectTicks);
            }
        }
    }

    /** プレイヤーのまわりにいるパニックゾンビの数を数える。 */
    private static int countPanicZombiesNear(ServerLevel level, ServerPlayer player) {
        return level.getEntitiesOfClass(Zombie.class,
                player.getBoundingBox().inflate(PanicConfig.COUNT_RADIUS),
                zombie -> zombie.getTags().contains(PANIC_TAG)).size();
    }

    /** プレイヤーのまわりで場所をさがし、ゾンビを1体出す。出せたら true。 */
    private static boolean trySpawnZombieNear(ServerLevel level, ServerPlayer player, PanicType panicType, int effectTicks) {
        BlockPos spawnPos = findSpawnPosNear(level, player, PanicConfig.SPAWN_DISTANCE_MIN, PanicConfig.SPAWN_DISTANCE_MAX);
        if (spawnPos == null) {
            return false;
        }

        // 場所に合ったゾンビを出す
        EntityType<? extends Zombie> zombieType = chooseZombieType(level, spawnPos);
        Zombie zombie = zombieType.spawn(level, spawnPos, MobSpawnType.EVENT);
        if (zombie == null) {
            return false;
        }
        setupPanicZombie(zombie, panicType, effectTicks);
        zombie.setTarget(player);
        return true;
    }

    /** プレイヤーから min〜max ブロックはなれた、立てる場所をさがす。見つからなければ null。 */
    private static BlockPos findSpawnPosNear(ServerLevel level, ServerPlayer player, int minDistance, int maxDistance) {
        for (int attempt = 0; attempt < 10; attempt++) {
            // ランダムな向きと距離
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = minDistance + random.nextDouble() * (maxDistance - minDistance);
            int x = Mth.floor(player.getX() + Math.cos(angle) * distance);
            int z = Mth.floor(player.getZ() + Math.sin(angle) * distance);

            // 読みこまれていて、モブが動ける場所だけ
            BlockPos columnPos = new BlockPos(x, player.getBlockY(), z);
            if (!level.isLoaded(columnPos) || !level.isPositionEntityTicking(columnPos)) {
                continue;
            }

            // 立てる高さをさがし、だれかの目の前ではないか確かめる
            BlockPos standPos = findStandingY(level, x, z, player.getBlockY());
            if (standPos != null && !isTooCloseToAnyPlayer(level, standPos)) {
                return standPos;
            }
        }
        return null;
    }

    /** その列で立てる高さをさがす（地上 → だめならプレイヤーと同じくらいの高さ）。 */
    private static BlockPos findStandingY(ServerLevel level, int x, int z, int playerY) {
        // 地上がプレイヤーの近くの高さなら、地上に出す
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos surfacePos = new BlockPos(x, surfaceY, z);
        if (Math.abs(surfaceY - playerY) <= 10 && canStandAt(level, surfacePos)) {
            return surfacePos;
        }

        // 洞くつや建物の中にいるときは、プレイヤーの高さのまわりでさがす
        for (int dy = 6; dy >= -6; dy--) {
            BlockPos pos = new BlockPos(x, playerY + dy, z);
            if (canStandAt(level, pos)) {
                return pos;
            }
        }
        return null;
    }

    /** 足もとがかたくて、体が入るすき間があり、溶岩ではない場所かどうか。 */
    private static boolean canStandAt(ServerLevel level, BlockPos pos) {
        BlockPos belowPos = pos.below();
        boolean solidFloor = level.getBlockState(belowPos).isFaceSturdy(level, belowPos, Direction.UP);
        boolean feetEmpty = level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
        boolean headEmpty = level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty();
        boolean notLava = !level.getFluidState(pos).is(FluidTags.LAVA) && !level.getFluidState(pos.above()).is(FluidTags.LAVA);
        return solidFloor && feetEmpty && headEmpty && notLava;
    }

    /** だれかプレイヤーの近すぎるところかどうか。 */
    private static boolean isTooCloseToAnyPlayer(ServerLevel level, BlockPos pos) {
        double safeDistance = PanicConfig.SAFE_DISTANCE_FROM_PLAYERS;
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5) < safeDistance * safeDistance) {
                return true;
            }
        }
        return false;
    }

    /** 場所に合ったゾンビの種類を選ぶ（水 → ドラウンド、砂漠 → ハスク、たまに村人ゾンビ）。 */
    private static EntityType<? extends Zombie> chooseZombieType(ServerLevel level, BlockPos pos) {
        if (level.getFluidState(pos).is(FluidTags.WATER)) {
            return EntityType.DROWNED;
        }
        if (level.getBiome(pos).is(Tags.Biomes.IS_DESERT) || level.getBiome(pos).is(Tags.Biomes.IS_BADLANDS)) {
            return EntityType.HUSK;
        }
        if (random.nextInt(20) == 0) {
            return EntityType.ZOMBIE_VILLAGER;
        }
        return EntityType.ZOMBIE;
    }

    /** パニックゾンビの目じるし・遠くまで気づく・昼でも燃えない、をつける。 */
    private static void setupPanicZombie(Zombie zombie, PanicType panicType, int effectTicks) {
        zombie.addTag(PANIC_TAG);

        // 遠くのプレイヤーにも気づく
        AttributeInstance followRange = zombie.getAttribute(Attributes.FOLLOW_RANGE);
        if (followRange != null) {
            followRange.setBaseValue(PanicConfig.ZOMBIE_FOLLOW_RANGE);
        }

        // パニックのあいだは昼でも燃えない（終わると切れる）
        zombie.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, effectTicks, 0, false, false));

        // 走るゾンビは足が速い
        if (panicType == PanicType.RUNNERS) {
            zombie.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, effectTicks, PanicConfig.RUNNER_SPEED_LEVEL, false, false));
        }
    }

    /** ランダムなプレイヤーの近くにゾンビ大将を出す。出せたら そのゾンビ、だめなら null。 */
    public static Zombie spawnBoss(MinecraftServer server, int effectTicks) {
        // オーバーワールドにいる人の中から1人えらぶ
        List<ServerPlayer> candidates = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!player.isSpectator() && player.serverLevel().dimension() == Level.OVERWORLD
                    && player.serverLevel().getDifficulty() != Difficulty.PEACEFUL) {
                candidates.add(player);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        ServerPlayer targetPlayer = candidates.get(random.nextInt(candidates.size()));
        ServerLevel level = targetPlayer.serverLevel();

        // 少しはなれた場所に出す
        BlockPos spawnPos = findSpawnPosNear(level, targetPlayer, 18, 28);
        if (spawnPos == null) {
            return null;
        }
        Zombie boss = EntityType.ZOMBIE.spawn(level, spawnPos, MobSpawnType.EVENT);
        if (boss == null) {
            return null;
        }
        setupBoss(boss, effectTicks);
        boss.setTarget(targetPlayer);

        // みんなに知らせる
        server.getPlayerList().broadcastSystemMessage(Component.literal("ゾンビ大将が " + targetPlayer.getName().getString()
                + " の近くに あらわれた！ たおすと パニックが終わるよ！").withStyle(ChatFormatting.GOLD), false);
        return boss;
    }

    /** ゾンビ大将を強くして、見た目をととのえる。 */
    private static void setupBoss(Zombie boss, int effectTicks) {
        boss.addTag(PANIC_TAG);
        boss.addTag(BOSS_TAG);
        boss.setBaby(false);
        boss.setPersistenceRequired();
        boss.setCustomName(Component.literal("ゾンビ大将").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        boss.setCustomNameVisible(true);

        // 体力・攻撃・速さ・大きさ
        setBaseAttribute(boss, Attributes.MAX_HEALTH, PanicConfig.BOSS_HEALTH);
        setBaseAttribute(boss, Attributes.ATTACK_DAMAGE, PanicConfig.BOSS_ATTACK_DAMAGE);
        setBaseAttribute(boss, Attributes.MOVEMENT_SPEED, PanicConfig.BOSS_MOVEMENT_SPEED);
        setBaseAttribute(boss, Attributes.KNOCKBACK_RESISTANCE, 0.8);
        setBaseAttribute(boss, Attributes.SCALE, PanicConfig.BOSS_SCALE);
        setBaseAttribute(boss, Attributes.FOLLOW_RANGE, PanicConfig.ZOMBIE_FOLLOW_RANGE);
        boss.setHealth(boss.getMaxHealth());

        // そうび（落とさない）
        equipWithoutDrop(boss, EquipmentSlot.HEAD, new ItemStack(Items.NETHERITE_HELMET));
        equipWithoutDrop(boss, EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
        equipWithoutDrop(boss, EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));

        // 光って見える・燃えない
        boss.addEffect(new MobEffectInstance(MobEffects.GLOWING, effectTicks, 0, false, false));
        boss.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, effectTicks, 0, false, false));
    }

    /** 能力の元の値を変える（その能力がなければ何もしない）。 */
    private static void setBaseAttribute(Zombie zombie, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute, double value) {
        AttributeInstance instance = zombie.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    /** そうびを持たせて、たおされても落とさないようにする。 */
    private static void equipWithoutDrop(Zombie zombie, EquipmentSlot slot, ItemStack stack) {
        zombie.setItemSlot(slot, stack);
        zombie.setDropChance(slot, 0.0f);
    }

    /** パニックが終わったとき、ゾンビの「燃えない」を消して、ゾンビ大将は帰らせる。 */
    public static void calmDownPanicZombies(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            // 先にリストにしてから変える（動かしながら消さないように）
            List<Zombie> panicZombies = new ArrayList<>();
            level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(Zombie.class),
                    zombie -> zombie.getTags().contains(PANIC_TAG), panicZombies);
            for (Zombie zombie : panicZombies) {
                if (zombie.getTags().contains(BOSS_TAG)) {
                    if (zombie.isAlive()) {
                        zombie.discard();
                    }
                    continue;
                }
                zombie.removeEffect(MobEffects.FIRE_RESISTANCE);
                zombie.removeEffect(MobEffects.MOVEMENT_SPEED);
            }
        }
    }
}
