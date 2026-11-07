package com.maccha.zombiepanic;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * 特殊能力ゾンビ（10体に1体・100体に1体・1000体に1体）。
 * レアなほど 体も強くなり、能力を いくつも持つ。能力の動きは AbilityMoves にある。
 */
public final class ZombieAbilities {

    // 一度だけ能力をきめた、という目じるし
    public static final String ROLLED_TAG = "zp_rolled";
    // 分身・影（たおしても数えない、しばらくで消える）
    public static final String ILLUSION_TAG = "zp_illusion";
    // 体の強さの段（1・2・3）
    private static final String BODY_TAG_PREFIX = "zp_body_";
    private static final String TAG_PREFIX = "zp_ab_";

    private static final RandomSource random = RandomSource.create();

    /** 能力の一覧（tier 0 = ボス用、1 = 特殊、2 = 強い特殊、3 = 最強）。 */
    public enum Ability {
        // ---- 特殊（10体に1体） ----
        GRAPPLE("grapple", "グラップル", 1),
        ROCKET("rocket", "ロケット", 1),
        CHARGER("charger", "突進", 1),
        WIND("wind", "風弾", 1),
        CLONE("clone", "分身", 1),
        SWIFT("swift", "俊足", 1),
        JUMPER("jumper", "跳躍", 1),
        POISON("poison", "毒の手", 1),
        FIRE("fire", "炎の手", 1),
        VAMPIRE("vampire", "吸血", 1),
        INVISIBLE("invisible", "透明", 1),
        EXPLODER("exploder", "爆発", 1),
        SPLITTER("splitter", "分裂", 1),
        // ---- 強い特殊（100体に1体） ----
        GIANT("giant", "超巨大", 2),
        METEOR("meteor", "隕石", 2),
        TORNADO("tornado", "竜巻", 2),
        SHADOW("shadow", "影分身", 2),
        SUMMONER("summoner", "召喚", 2),
        TELEPORTER("teleporter", "瞬間移動", 2),
        THUNDER("thunder", "雷", 2),
        FREEZER("freezer", "凍結", 2),
        PULLER("puller", "引きよせ", 2),
        // ---- ゾンビだけの能力（プレイヤーはもらえない）: 特殊 ----
        THORNS("thorns", "とげ", 1, true),
        WEAKENER("weakener", "弱らせ", 1, true),
        BLINDER("blinder", "目つぶし", 1, true),
        DIZZY("dizzy", "ふらふら", 1, true),
        KNOCKER("knocker", "ふっとばし", 1, true),
        LEAPER("leaper", "飛びかかり", 1, true),
        SPITTER("spitter", "つばはき", 1, true),
        FIREBREATH("firebreath", "ほのおのいき", 1, true),
        HEALER("healer", "癒し", 1, true),
        SCREAMER("screamer", "さけび", 1, true),
        STONESKIN("stoneskin", "石の肌", 1, true),
        THIEF("thief", "どろぼう", 1, true),
        // ---- ゾンビだけの能力: 強い特殊 ----
        LASER("laser", "光線", 2, true),
        GRAVITY("gravity", "重力", 2, true),
        NECRO("necro", "ネクロ", 2, true),
        FROSTNOVA("frostnova", "氷の嵐", 2, true),
        FIRESTORM("firestorm", "火の雨", 2, true),
        BARRIER("barrier", "バリア", 2, true),
        BERSERK("berserk", "狂乱", 2, true),
        DRAIN("drain", "ドレイン", 2, true),
        LAUNCHER("launcher", "打ち上げ", 2, true),
        MIRROR("mirror", "反射", 2, true),
        DARKNESS("darkness", "暗闇", 2, true),
        // ---- ゾンビだけの能力: 最強 ----
        DOOMSDAY("doomsday", "終末", 3, true),
        TIMESTOP("timestop", "時間停止", 3, true),
        // ---- 最強（1000体に1体） ----
        CALAMITY("calamity", "災厄", 3),
        // ---- ボス用（くじでは出ない） ----
        BRUTE("brute", "怪力", 0),
        TANK("tank", "鉄壁", 0),
        REGEN("regen", "再生", 0),
        HUNGER("hunger", "腐敗", 0),
        GUARDIAN("guardian", "守護", 0);

        public final String id;
        public final String shortName;
        public final int tier;
        // ゾンビだけの能力か（プレイヤーはうばえない）
        public final boolean zombieOnly;

        /** 能力の名前と強さの段を決める。 */
        Ability(String id, String shortName, int tier) {
            this(id, shortName, tier, false);
        }

        /** ゾンビだけの能力かどうかも決める。 */
        Ability(String id, String shortName, int tier, boolean zombieOnly) {
            this.id = id;
            this.shortName = shortName;
            this.tier = tier;
            this.zombieOnly = zombieOnly;
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
        int tier = 0;
        if (random.nextInt(PanicConfig.STRONGEST_ABILITY_CHANCE) == 0) {
            tier = 3;
        } else if (random.nextInt(PanicConfig.STRONG_ABILITY_CHANCE) == 0) {
            tier = 2;
        } else if (random.nextInt(PanicConfig.ABILITY_CHANCE) == 0) {
            tier = 1;
        }

        // レベルをきめる（レベルが高いと、能力の段も上がる）
        int level = ZombieLevel.pickLevel(zombie);
        tier = Math.max(tier, ZombieLevel.tierFor(level));
        if (tier > 0) {
            makeRareZombie(zombie, tier);
        }
        ZombieLevel.apply(zombie, level);
    }

    /** その段のレアゾンビにする（体を強くして、能力をいくつか持たせる）。 */
    public static void makeRareZombie(Zombie zombie, int tier) {
        List<Ability> abilities = pickAbilitiesForTier(tier);
        strengthenBody(zombie, tier);
        for (Ability ability : abilities) {
            zombie.addTag(ability.tag());
            applyAbilityStats(zombie, ability);
        }
        setRareName(zombie, tier, abilities);
        if (tier == 3) {
            announceCalamity(zombie);
        }
    }

    /** 段に合わせて 能力をえらぶ（段1は1〜2こ、段2は2〜3こ、段3は4こ）。 */
    private static List<Ability> pickAbilitiesForTier(int tier) {
        List<Ability> abilities = new ArrayList<>();
        if (tier == 3) {
            abilities.add(Ability.CALAMITY);
            addRandomAbility(abilities, 3);
            addRandomAbility(abilities, 2);
            addRandomAbility(abilities, 2);
            addRandomAbility(abilities, 1);
            addRandomAbility(abilities, 1);
        } else if (tier == 2) {
            addRandomAbility(abilities, 2);
            addRandomAbility(abilities, 1);
            if (random.nextInt(2) == 0) {
                addRandomAbility(abilities, 1);
            }
        } else {
            addRandomAbility(abilities, 1);
            if (random.nextInt(4) == 0) {
                addRandomAbility(abilities, 1);
            }
        }
        return abilities;
    }

    /** その段の能力を、まだ持っていないものから1つ足す。 */
    private static void addRandomAbility(List<Ability> abilities, int tier) {
        List<Ability> candidates = new ArrayList<>();
        for (Ability ability : Ability.values()) {
            if (ability.tier == tier && !abilities.contains(ability)) {
                candidates.add(ability);
            }
        }
        if (!candidates.isEmpty()) {
            abilities.add(candidates.get(random.nextInt(candidates.size())));
        }
    }

    /** ふつうの特殊能力（段1）のゾンビにする（「特殊ゾンビの日」用）。 */
    public static void giveRandomTierOne(Zombie zombie) {
        if (!hasAnyAbility(zombie)) {
            makeRareZombie(zombie, 1);
        }
    }

    /** 能力を1つだけ あたえる（コマンドのためし用。体はその段の強さにする）。 */
    public static void giveAbility(Zombie zombie, Ability ability) {
        int tier = Math.max(1, ability.tier);
        strengthenBody(zombie, tier);
        zombie.addTag(ability.tag());
        applyAbilityStats(zombie, ability);
        setRareName(zombie, tier, List.of(ability));
        if (ability == Ability.CALAMITY) {
            announceCalamity(zombie);
        }
    }

    // ---------------- 体を強くする ----------------

    /** レアなほど 体（体力・攻撃・かたさ・速さ・大きさ）を強くする。 */
    private static void strengthenBody(Zombie zombie, int tier) {
        zombie.addTag(BODY_TAG_PREFIX + tier);
        switch (tier) {
            case 1 -> {
                MobUtil.setMaxHealth(zombie, 30.0);
                MobUtil.addBaseAttribute(zombie, Attributes.ATTACK_DAMAGE, 1.5);
                multiplyBase(zombie, Attributes.MOVEMENT_SPEED, 1.08);
            }
            case 2 -> {
                MobUtil.setMaxHealth(zombie, 70.0);
                MobUtil.addBaseAttribute(zombie, Attributes.ATTACK_DAMAGE, 4.0);
                MobUtil.addBaseAttribute(zombie, Attributes.ARMOR, 6.0);
                MobUtil.setBaseAttribute(zombie, Attributes.KNOCKBACK_RESISTANCE, 0.4);
                MobUtil.setBaseAttribute(zombie, Attributes.SCALE, 1.2);
                multiplyBase(zombie, Attributes.MOVEMENT_SPEED, 1.15);
            }
            default -> {
                zombie.setBaby(false);
                zombie.setPersistenceRequired();
                MobUtil.setMaxHealth(zombie, 300.0);
                MobUtil.setBaseAttribute(zombie, Attributes.ATTACK_DAMAGE, 15.0);
                MobUtil.addBaseAttribute(zombie, Attributes.ARMOR, 12.0);
                MobUtil.setBaseAttribute(zombie, Attributes.MOVEMENT_SPEED, 0.32);
                MobUtil.setBaseAttribute(zombie, Attributes.KNOCKBACK_RESISTANCE, 1.0);
                MobUtil.setBaseAttribute(zombie, Attributes.SCALE, 2.5);
                MobUtil.addForeverEffect(zombie, MobEffects.REGENERATION, 1);
                MobUtil.addForeverEffect(zombie, MobEffects.GLOWING, 0);
            }
        }
    }

    /** 能力の元の値を 何倍かにする。 */
    static void multiplyBase(Zombie zombie, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute, double factor) {
        var instance = zombie.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(instance.getBaseValue() * factor);
        }
    }

    /** 能力に合わせて、効果や体を変える（ずっと続くもの）。 */
    private static void applyAbilityStats(Zombie zombie, Ability ability) {
        switch (ability) {
            case SWIFT -> MobUtil.addForeverEffect(zombie, MobEffects.MOVEMENT_SPEED, 1);
            case JUMPER -> MobUtil.addForeverEffect(zombie, MobEffects.JUMP, 3);
            case BRUTE -> MobUtil.addForeverEffect(zombie, MobEffects.DAMAGE_BOOST, 1);
            case TANK -> MobUtil.addForeverEffect(zombie, MobEffects.DAMAGE_RESISTANCE, 0);
            case REGEN -> MobUtil.addForeverEffect(zombie, MobEffects.REGENERATION, 0);
            case INVISIBLE -> MobUtil.addForeverEffect(zombie, MobEffects.INVISIBILITY, 0);
            case GIANT -> {
                // 超巨大: 3.5倍の大きさ、体力も多い、足はおそい
                zombie.setBaby(false);
                MobUtil.setBaseAttribute(zombie, Attributes.SCALE, Math.max(3.5, scaleOf(zombie)));
                MobUtil.setMaxHealth(zombie, Math.max(120.0, zombie.getMaxHealth() * 1.5));
                MobUtil.addBaseAttribute(zombie, Attributes.ATTACK_DAMAGE, 5.0);
                MobUtil.setBaseAttribute(zombie, Attributes.KNOCKBACK_RESISTANCE, 1.0);
                MobUtil.setBaseAttribute(zombie, Attributes.STEP_HEIGHT, 2.0);
                multiplyBase(zombie, Attributes.MOVEMENT_SPEED, 0.85);
            }
            case GUARDIAN -> MobUtil.addBaseAttribute(zombie, Attributes.ARMOR, 10.0);
            case STONESKIN -> {
                MobUtil.addBaseAttribute(zombie, Attributes.ARMOR, 8.0);
                MobUtil.addBaseAttribute(zombie, Attributes.KNOCKBACK_RESISTANCE, 0.5);
            }
            default -> {
            }
        }
    }

    /** いまの大きさ。 */
    private static double scaleOf(Zombie zombie) {
        var instance = zombie.getAttribute(Attributes.SCALE);
        return instance != null ? instance.getBaseValue() : 1.0;
    }

    /** 頭の上の名前（例: 【強】隕石・グラップルゾンビ）。段で色を変える。 */
    private static void setRareName(Zombie zombie, int tier, List<Ability> abilities) {
        StringBuilder names = new StringBuilder();
        for (Ability ability : abilities) {
            if (ability == Ability.CALAMITY) {
                continue;
            }
            if (names.length() > 0) {
                names.append("・");
            }
            names.append(ability.shortName);
        }
        Component name = switch (tier) {
            case 3 -> Component.literal("【最強】災厄ゾンビ（" + names + "）").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD);
            case 2 -> Component.literal("【強】" + names + "ゾンビ").withStyle(ChatFormatting.LIGHT_PURPLE);
            default -> Component.literal(names + "ゾンビ").withStyle(ChatFormatting.YELLOW);
        };
        if (ZombieLevel.levelOf(zombie) > 0) {
            ZombieLevel.setLevelName(zombie);
            return;
        }
        zombie.setCustomName(name);
        // 段2と3はいつも名前を見せる
        zombie.setCustomNameVisible(tier >= 2);
    }

    /** 災厄ゾンビが出たことを、その世界のみんなに知らせる。 */
    private static void announceCalamity(Zombie zombie) {
        if (!(zombie.level() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = zombie.blockPosition();
        for (ServerPlayer player : level.players()) {
            player.sendSystemMessage(Component.literal("……空気がふるえた。「災厄ゾンビ」が あらわれた！（X " + pos.getX() + " / Z " + pos.getZ() + " あたり）")
                    .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
            player.playNotifySound(SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 0.5f, 0.6f);
        }
    }

    // ---------------- 能力をしらべる ----------------

    /** その能力を持っているか（災厄は 大技をぜんぶ使える）。 */
    public static boolean has(Entity entity, Ability ability) {
        return entity.getTags().contains(ability.tag());
    }

    /** その大技を使えるか（その能力か、災厄）。 */
    public static boolean canUse(Entity entity, Ability ability) {
        return has(entity, ability) || has(entity, Ability.CALAMITY);
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

    /** 体の強さの段（0〜3）。 */
    public static int bodyTier(Entity entity) {
        for (int tier = 3; tier >= 1; tier--) {
            if (entity.getTags().contains(BODY_TAG_PREFIX + tier)) {
                return tier;
            }
        }
        return 0;
    }

    /** 分身・影かどうか。 */
    public static boolean isIllusion(Entity entity) {
        return entity.getTags().contains(ILLUSION_TAG);
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
            attacker.heal(4.0f);
        }
        if (has(attacker, Ability.HUNGER)) {
            MobUtil.addTimedEffect(target, MobEffects.HUNGER, 200, 1);
        }
        if (has(attacker, Ability.FREEZER)) {
            MobUtil.addTimedEffect(target, MobEffects.MOVEMENT_SLOWDOWN, 80, 2);
            MobUtil.addTimedEffect(target, MobEffects.DIG_SLOWDOWN, 80, 1);
            target.setTicksFrozen(Math.max(target.getTicksFrozen(), 160));
        }
        NewAbilities.onZombieHits(attacker, target);
        if (has(attacker, Ability.CALAMITY)) {
            MobUtil.addTimedEffect(target, MobEffects.WITHER, 80, 1);
        }
    }

    /** 能力ゾンビがダメージを受けたとき。落ちたダメージを消すなら true。 */
    public static boolean shouldIgnoreDamage(Zombie victim, DamageSource source) {
        // 飛びまわる能力・ボス・災厄は 落ちても平気
        boolean flyer = canUse(victim, Ability.GRAPPLE) || has(victim, Ability.ROCKET) || has(victim, Ability.CHARGER)
                || has(victim, Ability.GIANT) || BossZombies.isBoss(victim);
        return flyer && source.is(DamageTypeTags.IS_FALL);
    }

    /** 能力ゾンビがダメージを受けたときの へらし方（1 = そのまま）。分身もここで出す。 */
    public static float damageTakenMultiplier(Zombie victim) {
        // 分身: はじめてなぐられたとき 2体に分かれる
        if (has(victim, Ability.CLONE) && !victim.getTags().contains("zp_cloned") && victim.level() instanceof ServerLevel level) {
            victim.addTag("zp_cloned");
            AbilityMoves.spawnIllusions(level, victim, 2);
        }
        if (has(victim, Ability.GUARDIAN)) {
            return 0.5f;
        }
        if (has(victim, Ability.CALAMITY)) {
            return 0.7f;
        }
        return 1.0f;
    }

    // ---------------- たおされたとき ----------------

    /** 能力ゾンビがたおされたときの ばくはつ・分裂・ごほうび。 */
    public static void onZombieDeath(ServerLevel level, Zombie zombie) {
        if (isIllusion(zombie)) {
            return;
        }
        if (has(zombie, Ability.EXPLODER)) {
            level.explode(zombie, zombie.getX(), zombie.getY() + 0.5, zombie.getZ(), 2.5f, Level.ExplosionInteraction.NONE);
        }
        if (has(zombie, Ability.SPLITTER)) {
            splitIntoBabies(level, zombie);
        }
        dropRewards(zombie);
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

    /** 体の段に合わせて 呪鉄などを落とす。ふつうのゾンビも たまに かけらを落とす。 */
    private static void dropRewards(Zombie zombie) {
        int tier = bodyTier(zombie);
        if (tier == 3) {
            zombie.spawnAtLocation(new ItemStack(ModContent.CURSED_IRON_INGOT.get(), 8));
            zombie.spawnAtLocation(new ItemStack(Items.DIAMOND, 4));
            zombie.spawnAtLocation(new ItemStack(Items.ENDER_EYE, 3));
            zombie.spawnAtLocation(new ItemStack(Items.GOLDEN_APPLE, 2));
            zombie.spawnAtLocation(new ItemStack(Items.TOTEM_OF_UNDYING, 1));
        } else if (tier == 2) {
            zombie.spawnAtLocation(new ItemStack(ModContent.CURSED_IRON_INGOT.get(), 2 + random.nextInt(2)));
            if (random.nextInt(3) == 0) {
                zombie.spawnAtLocation(new ItemStack(Items.ENDER_EYE, 1));
            }
        } else if (tier == 1) {
            zombie.spawnAtLocation(new ItemStack(ModContent.CURSED_IRON_NUGGET.get(), 2 + random.nextInt(3)));
        } else if (!BossZombies.isBoss(zombie) && random.nextInt(25) == 0) {
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

    /** ゾンビ1体ごとに、能力をときどき使う（毎tick）。 */
    public static void tickAbilities(Zombie zombie) {
        AbilityMoves.tick(zombie);
    }
}
