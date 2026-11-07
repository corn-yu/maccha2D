package com.maccha.zombiepanic;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 特殊能力ゾンビ（10体に1体・100体に1体・1000体に1体）。
 */
public final class ZombieAbilities {

    // 一度だけ能力をきめた、という目じるし
    public static final String ROLLED_TAG = "zp_rolled";
    private static final String TAG_PREFIX = "zp_ab_";

    private static final RandomSource random = RandomSource.create();

    /** 能力の一覧（tier 1 = 特殊、2 = 強い特殊、3 = 最強）。 */
    public enum Ability {
        // ---- 特殊（10体に1体） ----
        SWIFT("swift", "俊足ゾンビ", 1),
        JUMPER("jumper", "跳躍ゾンビ", 1),
        BRUTE("brute", "怪力ゾンビ", 1),
        TANK("tank", "鉄壁ゾンビ", 1),
        REGEN("regen", "再生ゾンビ", 1),
        POISON("poison", "毒の手ゾンビ", 1),
        FIRE("fire", "炎の手ゾンビ", 1),
        VAMPIRE("vampire", "吸血ゾンビ", 1),
        INVISIBLE("invisible", "透明ゾンビ", 1),
        EXPLODER("exploder", "爆発ゾンビ", 1),
        SPLITTER("splitter", "分裂ゾンビ", 1),
        HUNGER("hunger", "腐敗ゾンビ", 1),
        // ---- 強い特殊（100体に1体） ----
        GIANT("giant", "巨人ゾンビ", 2),
        SUMMONER("summoner", "召喚ゾンビ", 2),
        TELEPORTER("teleporter", "瞬間移動ゾンビ", 2),
        THUNDER("thunder", "雷ゾンビ", 2),
        FREEZER("freezer", "凍結ゾンビ", 2),
        PULLER("puller", "引きよせゾンビ", 2),
        GUARDIAN("guardian", "守護ゾンビ", 2),
        // ---- 最強（1000体に1体） ----
        CALAMITY("calamity", "災厄ゾンビ", 3);

        public final String id;
        public final String displayName;
        public final int tier;

        /** 能力の名前と強さの段を決める。 */
        Ability(String id, String displayName, int tier) {
            this.id = id;
            this.displayName = displayName;
            this.tier = tier;
        }

        /** ゾンビにつける目じるしの名前。 */
        public String tag() {
            return TAG_PREFIX + id;
        }
    }

    private ZombieAbilities() {
    }

    // ---------------- 能力をきめる ----------------

    /** 新しく出てきたゾンビに、くじびきで能力をきめる（1体につき1回だけ）。 */
    public static void rollAbility(Zombie zombie) {
        if (zombie.getTags().contains(ROLLED_TAG)) {
            return;
        }
        zombie.addTag(ROLLED_TAG);

        // ボスは自分の能力を持っているので、くじはひかない
        if (zombie.getTags().contains(BossZombies.BOSS_TAG)) {
            return;
        }

        // 1000体に1体 → 100体に1体 → 10体に1体
        if (random.nextInt(PanicConfig.STRONGEST_ABILITY_CHANCE) == 0) {
            giveAbility(zombie, Ability.CALAMITY);
        } else if (random.nextInt(PanicConfig.STRONG_ABILITY_CHANCE) == 0) {
            giveAbility(zombie, pickRandomOfTier(2));
        } else if (random.nextInt(PanicConfig.ABILITY_CHANCE) == 0) {
            giveAbility(zombie, pickRandomOfTier(1));
        }
    }

    /** ふつうの特殊能力（段1）をどれか1つあたえる（「特殊ゾンビの日」用）。 */
    public static void giveRandomTierOne(Zombie zombie) {
        if (!hasAnyAbility(zombie)) {
            giveAbility(zombie, pickRandomOfTier(1));
        }
    }

    /** その段の能力をランダムに1つえらぶ。 */
    private static Ability pickRandomOfTier(int tier) {
        java.util.List<Ability> candidates = new java.util.ArrayList<>();
        for (Ability ability : Ability.values()) {
            if (ability.tier == tier) {
                candidates.add(ability);
            }
        }
        return candidates.get(random.nextInt(candidates.size()));
    }

    /** 能力をあたえて、名前と体の強さを変える。 */
    public static void giveAbility(Zombie zombie, Ability ability) {
        zombie.addTag(ability.tag());
        applyAbilityStats(zombie, ability);
        setAbilityName(zombie, ability);

        // 最強はみんなに知らせる
        if (ability == Ability.CALAMITY && zombie.level() instanceof ServerLevel level) {
            BlockPos pos = zombie.blockPosition();
            for (ServerPlayer player : level.players()) {
                player.sendSystemMessage(Component.literal("……空気がふるえた。「災厄ゾンビ」が あらわれた！（X " + pos.getX() + " / Z " + pos.getZ() + " あたり）")
                        .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
                player.playNotifySound(SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 0.5f, 0.6f);
            }
        }
    }

    /** 能力に合わせて、効果や体の強さを変える。 */
    private static void applyAbilityStats(Zombie zombie, Ability ability) {
        switch (ability) {
            case SWIFT -> MobUtil.addForeverEffect(zombie, MobEffects.MOVEMENT_SPEED, 1);
            case JUMPER -> MobUtil.addForeverEffect(zombie, MobEffects.JUMP, 3);
            case BRUTE -> MobUtil.addForeverEffect(zombie, MobEffects.DAMAGE_BOOST, 1);
            case TANK -> {
                MobUtil.addForeverEffect(zombie, MobEffects.DAMAGE_RESISTANCE, 0);
                MobUtil.addBaseAttribute(zombie, Attributes.ARMOR, 8.0);
            }
            case REGEN -> MobUtil.addForeverEffect(zombie, MobEffects.REGENERATION, 0);
            case INVISIBLE -> MobUtil.addForeverEffect(zombie, MobEffects.INVISIBILITY, 0);
            case GIANT -> {
                MobUtil.setBaseAttribute(zombie, Attributes.SCALE, 2.0);
                MobUtil.setMaxHealth(zombie, 60.0);
                MobUtil.addBaseAttribute(zombie, Attributes.ATTACK_DAMAGE, 4.0);
                MobUtil.setBaseAttribute(zombie, Attributes.KNOCKBACK_RESISTANCE, 0.6);
            }
            case GUARDIAN -> {
                MobUtil.setMaxHealth(zombie, 40.0);
                MobUtil.addBaseAttribute(zombie, Attributes.ARMOR, 10.0);
            }
            case SUMMONER, TELEPORTER, THUNDER, FREEZER, PULLER -> MobUtil.setMaxHealth(zombie, 40.0);
            case CALAMITY -> applyCalamityStats(zombie);
            default -> {
            }
        }
    }

    /** 災厄ゾンビの体（大きく・かたく・速く）。 */
    private static void applyCalamityStats(Zombie zombie) {
        zombie.setBaby(false);
        zombie.setPersistenceRequired();
        MobUtil.setBaseAttribute(zombie, Attributes.SCALE, 2.5);
        MobUtil.setMaxHealth(zombie, 250.0);
        MobUtil.setBaseAttribute(zombie, Attributes.ATTACK_DAMAGE, 14.0);
        MobUtil.setBaseAttribute(zombie, Attributes.MOVEMENT_SPEED, 0.3);
        MobUtil.setBaseAttribute(zombie, Attributes.KNOCKBACK_RESISTANCE, 1.0);
        MobUtil.addBaseAttribute(zombie, Attributes.ARMOR, 10.0);
        MobUtil.addForeverEffect(zombie, MobEffects.REGENERATION, 1);
        MobUtil.addForeverEffect(zombie, MobEffects.GLOWING, 0);
    }

    /** 頭の上の名前をつける（段で色を変える）。 */
    private static void setAbilityName(Zombie zombie, Ability ability) {
        Component name = switch (ability.tier) {
            case 3 -> Component.literal("【最強】" + ability.displayName).withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD);
            case 2 -> Component.literal("【強】" + ability.displayName).withStyle(ChatFormatting.LIGHT_PURPLE);
            default -> Component.literal(ability.displayName).withStyle(ChatFormatting.YELLOW);
        };
        zombie.setCustomName(name);
        // 段2と3はいつも名前を見せる
        zombie.setCustomNameVisible(ability.tier >= 2);
    }

    // ---------------- 能力をしらべる ----------------

    /** その能力を持っているか。 */
    public static boolean has(Entity entity, Ability ability) {
        return entity.getTags().contains(ability.tag());
    }

    /** なにか能力を持っているか。 */
    public static boolean hasAnyAbility(Entity entity) {
        for (String tag : entity.getTags()) {
            if (tag.startsWith(TAG_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    /** 持っている能力のいちばん高い段（なければ0）。 */
    public static int highestTier(Entity entity) {
        int tier = 0;
        for (Ability ability : Ability.values()) {
            if (has(entity, ability)) {
                tier = Math.max(tier, ability.tier);
            }
        }
        return tier;
    }

    // ---------------- たたかいのとき ----------------

    /** 能力ゾンビがなぐったときの おまけの効果。 */
    public static void onZombieHits(Zombie attacker, LivingEntity target) {
        if (has(attacker, Ability.POISON)) {
            MobUtil.addTimedEffect(target, MobEffects.POISON, 100, 0);
        }
        if (has(attacker, Ability.FIRE)) {
            target.igniteForSeconds(4);
        }
        if (has(attacker, Ability.VAMPIRE)) {
            attacker.heal(3.0f);
        }
        if (has(attacker, Ability.HUNGER)) {
            MobUtil.addTimedEffect(target, MobEffects.HUNGER, 200, 1);
        }
        if (has(attacker, Ability.FREEZER)) {
            MobUtil.addTimedEffect(target, MobEffects.MOVEMENT_SLOWDOWN, 80, 2);
            MobUtil.addTimedEffect(target, MobEffects.DIG_SLOWDOWN, 80, 1);
            target.setTicksFrozen(Math.max(target.getTicksFrozen(), 160));
        }
        if (has(attacker, Ability.CALAMITY)) {
            MobUtil.addTimedEffect(target, MobEffects.WITHER, 80, 1);
        }
    }

    /** 能力ゾンビがダメージを受けたときの へらし方（1 = そのまま）。 */
    public static float damageTakenMultiplier(Zombie victim) {
        if (has(victim, Ability.GUARDIAN)) {
            return 0.5f;
        }
        if (has(victim, Ability.CALAMITY)) {
            return 0.7f;
        }
        return 1.0f;
    }

    // ---------------- ときどき使う能力 ----------------

    /** 段2・3の能力を、ときどき使う（ゾンビ1体ごと、毎tick呼ばれる）。 */
    public static void tickAbilities(Zombie zombie) {
        if (!(zombie.level() instanceof ServerLevel level) || !zombie.isAlive()) {
            return;
        }
        // 1秒に1回だけしらべる
        if (zombie.tickCount % 20 != 0) {
            return;
        }
        boolean calamity = has(zombie, Ability.CALAMITY);
        LivingEntity target = zombie.getTarget();

        if ((calamity || has(zombie, Ability.SUMMONER)) && zombie.tickCount % 200 == 0) {
            summonHelpers(level, zombie, calamity ? 3 : 2);
        }
        if (target == null || !target.isAlive()) {
            return;
        }
        if ((calamity || has(zombie, Ability.TELEPORTER)) && zombie.tickCount % 100 == 0) {
            teleportNearTarget(level, zombie, target);
        }
        if ((calamity || has(zombie, Ability.THUNDER)) && zombie.tickCount % 160 == 0) {
            strikeLightning(level, zombie, target);
        }
        if ((calamity || has(zombie, Ability.PULLER)) && zombie.tickCount % 80 == 0) {
            pullTarget(zombie, target);
        }
    }

    /** まわりに なかまのゾンビを呼ぶ。 */
    private static void summonHelpers(ServerLevel level, Zombie zombie, int count) {
        // 多すぎるときは呼ばない
        int nearCount = level.getEntitiesOfClass(Zombie.class, zombie.getBoundingBox().inflate(16.0)).size();
        if (nearCount > 30) {
            return;
        }
        for (int i = 0; i < count; i++) {
            Zombie helper = ZombieSpawner.spawnZombieCloseTo(level, zombie.blockPosition(), 2, 5, null);
            if (helper != null && zombie.getTarget() != null) {
                helper.setTarget(zombie.getTarget());
            }
        }
        level.sendParticles(ParticleTypes.SOUL, zombie.getX(), zombie.getY() + 1.0, zombie.getZ(), 20, 1.0, 1.0, 1.0, 0.02);
        level.playSound(null, zombie.blockPosition(), SoundEvents.EVOKER_PREPARE_SUMMON, SoundSource.HOSTILE, 1.0f, 0.7f);
    }

    /** 遠くにいる相手のそばへ 瞬間移動する。 */
    private static void teleportNearTarget(ServerLevel level, Zombie zombie, LivingEntity target) {
        if (zombie.distanceTo(target) < 8.0f || zombie.distanceTo(target) > 40.0f) {
            return;
        }
        BlockPos landing = ZombieSpawner.findStandPosAround(level, target.blockPosition(), 2, 4);
        if (landing == null) {
            return;
        }
        level.sendParticles(ParticleTypes.PORTAL, zombie.getX(), zombie.getY() + 1.0, zombie.getZ(), 30, 0.5, 1.0, 0.5, 0.1);
        zombie.teleportTo(landing.getX() + 0.5, landing.getY(), landing.getZ() + 0.5);
        level.playSound(null, landing, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.0f, 0.8f);
    }

    /** 相手に雷を落とす。 */
    private static void strikeLightning(ServerLevel level, Zombie zombie, LivingEntity target) {
        if (zombie.distanceTo(target) > 24.0f) {
            return;
        }
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
        if (bolt == null) {
            return;
        }
        bolt.moveTo(target.getX(), target.getY(), target.getZ());
        level.addFreshEntity(bolt);
    }

    /** 相手を ぐいっと引きよせる。 */
    private static void pullTarget(Zombie zombie, LivingEntity target) {
        double distance = zombie.distanceTo(target);
        if (distance < 3.0 || distance > 16.0) {
            return;
        }
        Vec3 toZombie = zombie.position().subtract(target.position()).normalize().scale(0.9);
        target.setDeltaMovement(target.getDeltaMovement().add(toZombie.x, 0.35, toZombie.z));
        target.hurtMarked = true;
        zombie.level().playSound(null, target.blockPosition(), SoundEvents.FISHING_BOBBER_RETRIEVE, SoundSource.HOSTILE, 1.0f, 0.6f);
    }

    // ---------------- たおされたとき ----------------

    /** 能力ゾンビがたおされたときの ばくはつ・分裂・ごほうび。 */
    public static void onZombieDeath(ServerLevel level, Zombie zombie) {
        if (has(zombie, Ability.EXPLODER)) {
            level.explode(zombie, zombie.getX(), zombie.getY() + 0.5, zombie.getZ(), 2.5f, Level.ExplosionInteraction.NONE);
        }
        if (has(zombie, Ability.SPLITTER)) {
            splitIntoBabies(level, zombie);
        }
        dropAbilityRewards(zombie);
    }

    /** ちびゾンビ2体に分かれる。 */
    private static void splitIntoBabies(ServerLevel level, Zombie zombie) {
        for (int i = 0; i < 2; i++) {
            Zombie baby = EntityType.ZOMBIE.create(level);
            if (baby == null) {
                continue;
            }
            // 分かれた子は能力なし（くじは引いたことにする）
            baby.addTag(ROLLED_TAG);
            baby.setBaby(true);
            baby.moveTo(zombie.getX() + (i == 0 ? 0.4 : -0.4), zombie.getY(), zombie.getZ(), zombie.getYRot(), 0.0f);
            if (zombie.getTarget() != null) {
                baby.setTarget(zombie.getTarget());
            }
            level.addFreshEntity(baby);
        }
    }

    /** 段に合わせて 呪鉄などを落とす。ふつうのゾンビも たまに かけらを落とす。 */
    private static void dropAbilityRewards(Zombie zombie) {
        int tier = highestTier(zombie);
        if (tier == 3) {
            zombie.spawnAtLocation(new ItemStack(ModContent.CURSED_IRON_INGOT.get(), 6));
            zombie.spawnAtLocation(new ItemStack(Items.DIAMOND, 3));
            zombie.spawnAtLocation(new ItemStack(Items.ENDER_EYE, 2));
            zombie.spawnAtLocation(new ItemStack(Items.GOLDEN_APPLE, 2));
            zombie.spawnAtLocation(new ItemStack(Items.TOTEM_OF_UNDYING, 1));
        } else if (tier == 2) {
            zombie.spawnAtLocation(new ItemStack(ModContent.CURSED_IRON_INGOT.get(), 1 + random.nextInt(2)));
            if (random.nextInt(4) == 0) {
                zombie.spawnAtLocation(new ItemStack(Items.ENDER_EYE, 1));
            }
        } else if (tier == 1) {
            zombie.spawnAtLocation(new ItemStack(ModContent.CURSED_IRON_NUGGET.get(), 1 + random.nextInt(3)));
        } else if (random.nextInt(25) == 0) {
            zombie.spawnAtLocation(new ItemStack(ModContent.CURSED_IRON_NUGGET.get(), 1));
        }
    }

    /** プレイヤーがゾンビをなぐったとき、呪鉄の武器ならダメージを1.5倍にする。 */
    public static float cursedWeaponMultiplier(Player attacker) {
        ItemStack held = attacker.getMainHandItem();
        if (held.is(ModContent.CURSED_IRON_SWORD.get()) || held.is(ModContent.CURSED_IRON_AXE.get())) {
            return 1.5f;
        }
        return 1.0f;
    }
}
