package com.maccha.zombiepanic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.MobSpawnType;
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
 * プレイヤーのまわりへゾンビを出す係（いつものゾンビ・パニックのゾンビ）。
 */
public final class ZombieSpawner {

    // パニックで出たゾンビにつける目じるし
    public static final String PANIC_TAG = "zombiepanic";
    // たおすと たからを落とす（ゴールドラッシュ）
    public static final String GOLD_TAG = "zp_gold";

    private static final RandomSource random = RandomSource.create();

    private ZombieSpawner() {
    }

    // ---------------- 群れを出す ----------------

    /** いつものゾンビを 全員のまわりに少し出す（どのディメンションでも）。 */
    public static void spawnBaseWave(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!canSpawnAround(player)) {
                continue;
            }
            ServerLevel level = player.serverLevel();
            int nearCount = countZombiesNear(level, player);
            int spawnCount = Math.min(PanicConfig.BASE_WAVE_SIZE, PanicConfig.BASE_MAX_NEAR_PLAYER - nearCount);
            for (int i = 0; i < spawnCount; i++) {
                spawnAroundPlayer(level, player, PanicConfig.SPAWN_DISTANCE_MIN, PanicConfig.SPAWN_DISTANCE_MAX, null, 0);
            }
        }
    }

    /** パニックのゾンビを 全員のまわりに出す（progress は 0〜1 の進みぐあい）。 */
    public static void spawnPanicWave(MinecraftServer server, PanicType panicType, float progress, int effectTicks) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!canSpawnAround(player)) {
                continue;
            }
            ServerLevel level = player.serverLevel();

            // まわりにいる数が上限に近ければ、足りないぶんだけ出す
            double multiplier = panicType.amountMultiplier();
            int maxNear = (int) Math.round(PanicConfig.MAX_ZOMBIES_NEAR_PLAYER * multiplier);
            int nearCount = countZombiesNear(level, player);
            if (nearCount >= maxNear) {
                continue;
            }
            float waveSize = Mth.lerp(progress, PanicConfig.WAVE_SIZE_START, PanicConfig.WAVE_SIZE_END);
            int spawnCount = Math.min(Math.max(1, (int) Math.round(waveSize * multiplier)), maxNear - nearCount);

            // 「地の底から」は近くにわく
            int minDistance = panicType == PanicType.DIGGERS ? 5 : PanicConfig.SPAWN_DISTANCE_MIN;
            int maxDistance = panicType == PanicType.DIGGERS ? 11 : PanicConfig.SPAWN_DISTANCE_MAX;
            for (int i = 0; i < spawnCount; i++) {
                spawnAroundPlayer(level, player, minDistance, maxDistance, panicType, effectTicks);
            }
        }
    }

    /** そのプレイヤーのまわりに出してよいか（見ているだけの人・ピースフルはだめ）。 */
    private static boolean canSpawnAround(ServerPlayer player) {
        return !player.isSpectator() && player.serverLevel().getDifficulty() != Difficulty.PEACEFUL;
    }

    /** プレイヤーのまわりにいるゾンビの数を数える。 */
    private static int countZombiesNear(ServerLevel level, ServerPlayer player) {
        return level.getEntitiesOfClass(Zombie.class, player.getBoundingBox().inflate(PanicConfig.COUNT_RADIUS)).size();
    }

    /** プレイヤーのまわりで場所をさがし、ゾンビを1体出す。 */
    private static Zombie spawnAroundPlayer(ServerLevel level, ServerPlayer player, int minDistance, int maxDistance,
                                            PanicType panicType, int effectTicks) {
        BlockPos spawnPos = findSpawnPosNear(level, player.blockPosition(), minDistance, maxDistance, true);
        if (spawnPos == null) {
            return null;
        }
        Zombie zombie = spawnZombieAt(level, spawnPos, panicType);
        if (zombie == null) {
            return null;
        }
        if (panicType != null) {
            applyPanicStyle(level, zombie, panicType, effectTicks);
        }
        zombie.setTarget(player);
        return zombie;
    }

    /** ある場所の近く（min〜max ブロック）にゾンビを1体出す（能力の呼び出し・ドラゴン用）。 */
    public static Zombie spawnZombieCloseTo(ServerLevel level, BlockPos center, int minDistance, int maxDistance, PanicType panicType) {
        BlockPos spawnPos = findSpawnPosNear(level, center, minDistance, maxDistance, false);
        if (spawnPos == null) {
            return null;
        }
        return spawnZombieAt(level, spawnPos, panicType);
    }

    /** その場所に合ったゾンビを出し、遠くまで気づくようにする。 */
    private static Zombie spawnZombieAt(ServerLevel level, BlockPos spawnPos, PanicType panicType) {
        EntityType<? extends Zombie> zombieType = chooseZombieType(level, spawnPos, panicType);
        Zombie zombie = zombieType.spawn(level, spawnPos, MobSpawnType.EVENT);
        if (zombie == null) {
            return null;
        }
        MobUtil.setBaseAttribute(zombie, Attributes.FOLLOW_RANGE, PanicConfig.ZOMBIE_FOLLOW_RANGE);
        return zombie;
    }

    // ---------------- 場所をさがす ----------------

    /** center から min〜max ブロックはなれた、立てる場所をさがす。見つからなければ null。 */
    public static BlockPos findSpawnPosNear(ServerLevel level, BlockPos center, int minDistance, int maxDistance, boolean keepAwayFromPlayers) {
        for (int attempt = 0; attempt < 10; attempt++) {
            // ランダムな向きと距離
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = minDistance + random.nextDouble() * (maxDistance - minDistance);
            int x = Mth.floor(center.getX() + 0.5 + Math.cos(angle) * distance);
            int z = Mth.floor(center.getZ() + 0.5 + Math.sin(angle) * distance);

            // 読みこまれていて、モブが動ける場所だけ
            BlockPos columnPos = new BlockPos(x, center.getY(), z);
            if (!level.isLoaded(columnPos) || !level.isPositionEntityTicking(columnPos)) {
                continue;
            }

            // 立てる高さをさがし、だれかの目の前ではないか確かめる
            BlockPos standPos = findStandingY(level, x, z, center.getY());
            if (standPos == null) {
                continue;
            }
            if (keepAwayFromPlayers && isTooCloseToAnyPlayer(level, standPos)) {
                continue;
            }
            return standPos;
        }
        return null;
    }

    /** center のまわり（min〜max）で立てる場所を1つさがす（プレイヤーの近くでもよい）。 */
    public static BlockPos findStandPosAround(ServerLevel level, BlockPos center, int minDistance, int maxDistance) {
        return findSpawnPosNear(level, center, minDistance, maxDistance, false);
    }

    /** その列で立てる高さをさがす（地上 → だめならプレイヤーと同じくらいの高さ）。 */
    private static BlockPos findStandingY(ServerLevel level, int x, int z, int baseY) {
        // 地上が近くの高さなら、地上に出す（ネザーの天井は 高さがちがうので使わない）
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos surfacePos = new BlockPos(x, surfaceY, z);
        if (Math.abs(surfaceY - baseY) <= 10 && canStandAt(level, surfacePos)) {
            return surfacePos;
        }

        // 洞くつ・建物の中・ネザーでは、同じくらいの高さでさがす
        for (int dy = 6; dy >= -6; dy--) {
            BlockPos pos = new BlockPos(x, baseY + dy, z);
            if (canStandAt(level, pos)) {
                return pos;
            }
        }
        return null;
    }

    /** 足もとがかたくて、体が入るすき間があり、溶岩ではない場所かどうか。 */
    private static boolean canStandAt(ServerLevel level, BlockPos pos) {
        if (!level.isInWorldBounds(pos) || !level.getWorldBorder().isWithinBounds(pos)) {
            return false;
        }
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

    // ---------------- ゾンビの種類 ----------------

    /** 場所とパニックに合ったゾンビの種類を選ぶ。 */
    private static EntityType<? extends Zombie> chooseZombieType(ServerLevel level, BlockPos pos, PanicType panicType) {
        // パニックで決まっているもの
        if (panicType == PanicType.DROWNED_RAID) {
            return EntityType.DROWNED;
        }
        if (panicType == PanicType.VILLAGERS) {
            return EntityType.ZOMBIE_VILLAGER;
        }

        // ネザーはハスク（あつさに強い）、エンドはふつうのゾンビ
        if (level.dimension() == Level.NETHER) {
            return EntityType.HUSK;
        }
        if (level.dimension() == Level.END) {
            return EntityType.ZOMBIE;
        }

        // 水 → ドラウンド、砂漠・荒野 → ハスク、たまに村人ゾンビ
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

    // ---------------- パニックごとの見た目・強さ ----------------

    /** パニックの種類に合わせて、ゾンビを変える。 */
    private static void applyPanicStyle(ServerLevel level, Zombie zombie, PanicType panicType, int effectTicks) {
        zombie.addTag(PANIC_TAG);
        switch (panicType) {
            case RUNNERS -> addTimed(zombie, MobEffects.MOVEMENT_SPEED, effectTicks, 1);
            case JUMPERS -> addTimed(zombie, MobEffects.JUMP, effectTicks, 3);
            case INVISIBLE -> addTimed(zombie, MobEffects.INVISIBILITY, effectTicks, 0);
            case BLOOD_MOON -> addTimed(zombie, MobEffects.DAMAGE_BOOST, effectTicks, 0);
            case BABIES -> zombie.setBaby(true);
            case ARMORED -> giveIronArmor(zombie);
            case GIANTS -> makeGiant(zombie);
            case DROWNED_RAID -> MobUtil.equip(zombie, EquipmentSlot.MAINHAND, new ItemStack(Items.TRIDENT), 0.08f);
            case EXPLODERS -> zombie.addTag(ZombieAbilities.Ability.EXPLODER.tag());
            case SPLITTERS -> zombie.addTag(ZombieAbilities.Ability.SPLITTER.tag());
            case GOLD_RUSH -> makeGoldZombie(zombie);
            case ELITE -> ZombieAbilities.giveRandomTierOne(zombie);
            case THUNDER -> strikeVisualLightning(level, zombie.blockPosition());
            case DIGGERS -> showDigOutEffect(level, zombie.blockPosition());
            default -> {
            }
        }
    }

    /** 時間つきの効果をつける（つぶつぶは見せない）。 */
    private static void addTimed(Zombie zombie, net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect, int ticks, int amplifier) {
        zombie.addEffect(new MobEffectInstance(effect, ticks, amplifier, false, false));
    }

    /** 鉄のよろいと剣を着せる（たまに落とす）。 */
    private static void giveIronArmor(Zombie zombie) {
        MobUtil.equip(zombie, EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET), 0.05f);
        MobUtil.equip(zombie, EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE), 0.05f);
        MobUtil.equip(zombie, EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS), 0.05f);
        MobUtil.equip(zombie, EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS), 0.05f);
        MobUtil.equip(zombie, EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD), 0.05f);
    }

    /** 大きくて強いゾンビにする。 */
    private static void makeGiant(Zombie zombie) {
        zombie.setBaby(false);
        MobUtil.setBaseAttribute(zombie, Attributes.SCALE, 1.6);
        MobUtil.setMaxHealth(zombie, 40.0);
        MobUtil.addBaseAttribute(zombie, Attributes.ATTACK_DAMAGE, 3.0);
    }

    /** 金のかぶとをかぶせ、たからを落とすゾンビにする。 */
    private static void makeGoldZombie(Zombie zombie) {
        MobUtil.equip(zombie, EquipmentSlot.HEAD, new ItemStack(Items.GOLDEN_HELMET), 0.0f);
        zombie.addTag(GOLD_TAG);
    }

    /** 見た目だけの雷を落とす。 */
    private static void strikeVisualLightning(ServerLevel level, BlockPos pos) {
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
        if (bolt != null) {
            bolt.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            bolt.setVisualOnly(true);
            level.addFreshEntity(bolt);
        }
    }

    /** 地面からはい出てくる けむりと音。 */
    private static void showDigOutEffect(ServerLevel level, BlockPos pos) {
        level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, pos.getX() + 0.5, pos.getY() + 0.2, pos.getZ() + 0.5, 6, 0.3, 0.1, 0.3, 0.01);
        level.playSound(null, pos, SoundEvents.ROOTED_DIRT_BREAK, SoundSource.HOSTILE, 1.0f, 0.6f);
    }

    /** ゴールドラッシュのゾンビがたおされたら、たからを落とす。 */
    public static void dropGoldRushReward(Zombie zombie) {
        zombie.spawnAtLocation(new ItemStack(Items.GOLD_INGOT, 1 + random.nextInt(3)));
        if (random.nextInt(3) == 0) {
            zombie.spawnAtLocation(new ItemStack(Items.EMERALD, 1 + random.nextInt(2)));
        }
        if (random.nextInt(10) == 0) {
            zombie.spawnAtLocation(new ItemStack(Items.DIAMOND, 1));
        }
    }

    // ---------------- パニックが終わったとき ----------------

    /** パニックの効果（走る・見えない など）をはずす。 */
    public static void calmDownPanicZombies(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Zombie zombie : collectZombies(level, PANIC_TAG)) {
                zombie.removeEffect(MobEffects.MOVEMENT_SPEED);
                zombie.removeEffect(MobEffects.INVISIBILITY);
                zombie.removeEffect(MobEffects.JUMP);
                zombie.removeEffect(MobEffects.DAMAGE_BOOST);
            }
        }
    }

    /** 呪いがとけたとき、ゾンビの「燃えない」をはずす（また日光で燃えるように）。 */
    public static void removeCurseFromAllZombies(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Zombie zombie : collectZombies(level, null)) {
                zombie.removeEffect(MobEffects.FIRE_RESISTANCE);
            }
        }
    }

    /** その世界のゾンビを集める（tag が null なら全部）。 */
    private static List<Zombie> collectZombies(ServerLevel level, String tag) {
        List<Zombie> zombies = new ArrayList<>();
        level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(Zombie.class),
                zombie -> tag == null || zombie.getTags().contains(tag), zombies);
        return zombies;
    }
}
