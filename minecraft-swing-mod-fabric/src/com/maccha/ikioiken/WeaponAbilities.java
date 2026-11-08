package com.maccha.ikioiken;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 武器につく能力（約 100 種類）と、その効果。能力はアイテムの「カスタムデータ」に入れて、説明文（ロア）にも名前を出す。
 */
public final class WeaponAbilities {

    private WeaponAbilities() {
    }

    // ===== 能力の種類（効き方）=====
    public enum Kind {
        // 当てたとき
        EFFECT, IGNITE, FREEZE, EXPLODE, LIGHTNING, LIFESTEAL, KNOCK, LAUNCH, PULL, SHOCK, CHAIN, EXECUTE,
        BANE_UNDEAD, BANE_ARTHROPOD, BANE_AQUATIC, BANE_ILLAGER, BANE_FLYING, BANE_BOSS, BANE_NETHER, BANE_END,
        CRIT, DOUBLE, ARMOR_PIERCE, SWEEP,
        // 倒したとき
        KILL_HEAL, KILL_XP, KILL_EXPLODE, KILL_EFFECT, KILL_ABSORB, KILL_MEND, KILL_FOOD,
        // 持っているあいだずっと
        PASSIVE
    }

    /** 能力 1 つぶんの決まり（chance＝出る確率、a・b＝効果ごとの数字、effect＝状態異常・状態の種類）。 */
    public record Ability(String id, String name, Kind kind, double chance, double a, double b,
                          Holder<MobEffect> effect) {
    }

    // 能力の一覧（id → 決まり）
    public static final Map<String, Ability> ALL = new LinkedHashMap<>();
    private static final List<Ability> LIST = new ArrayList<>();

    /** 能力を 1 つ登録する。 */
    private static void add(String id, String name, Kind kind, double chance, double a, double b, Holder<MobEffect> effect) {
        Ability ability = new Ability(id, name, kind, chance, a, b, effect);
        ALL.put(id, ability);
        LIST.add(ability);
    }

    static {
        // --- 状態異常をあたえる（秒・強さ）---
        add("poison", "毒の刃", Kind.EFFECT, 0.35, 5, 0, MobEffects.POISON);
        add("venom", "猛毒", Kind.EFFECT, 0.25, 6, 1, MobEffects.POISON);
        add("wither", "枯れの刃", Kind.EFFECT, 0.25, 5, 0, MobEffects.WITHER);
        add("slow", "鈍足", Kind.EFFECT, 0.4, 4, 1, MobEffects.SLOWNESS);
        add("weak", "衰弱", Kind.EFFECT, 0.4, 6, 1, MobEffects.WEAKNESS);
        add("blind", "盲目", Kind.EFFECT, 0.3, 4, 0, MobEffects.BLINDNESS);
        add("nausea", "混乱", Kind.EFFECT, 0.3, 6, 0, MobEffects.NAUSEA);
        add("hunger", "飢餓", Kind.EFFECT, 0.4, 8, 2, MobEffects.HUNGER);
        add("glow", "標的", Kind.EFFECT, 0.6, 10, 0, MobEffects.GLOWING);
        add("float", "浮遊", Kind.EFFECT, 0.25, 2, 0, MobEffects.LEVITATION);
        add("stun", "麻痺", Kind.EFFECT, 0.2, 2, 4, MobEffects.SLOWNESS);
        add("darkness", "暗闇", Kind.EFFECT, 0.3, 5, 0, MobEffects.DARKNESS);
        add("infest", "寄生", Kind.EFFECT, 0.3, 8, 0, MobEffects.INFESTED);
        add("weave", "蜘蛛の糸", Kind.EFFECT, 0.3, 8, 0, MobEffects.WEAVING);
        add("ooze", "粘液", Kind.EFFECT, 0.3, 8, 0, MobEffects.OOZING);
        add("windcharge", "風圧", Kind.EFFECT, 0.3, 6, 0, MobEffects.WIND_CHARGED);
        add("poison_drug", "毒薬", Kind.EFFECT, 0.25, 1, 0, MobEffects.INSTANT_DAMAGE);

        // --- 炎・氷 ---
        add("ignite", "炎上", Kind.IGNITE, 0.4, 3, 0, null);
        add("blaze", "業火", Kind.IGNITE, 0.3, 6, 0, null);
        add("inferno", "煉獄", Kind.IGNITE, 0.2, 10, 0, null);
        add("ember", "火種", Kind.IGNITE, 0.6, 2, 0, null);
        add("freeze", "凍結", Kind.FREEZE, 0.35, 3, 0, null);
        add("ice", "氷結", Kind.FREEZE, 0.25, 5, 0, null);
        add("absolute_zero", "絶対零度", Kind.FREEZE, 0.15, 8, 0, null);

        // --- 爆発・雷 ---
        add("explode_small", "小爆発", Kind.EXPLODE, 0.5, 0.6, 0, null);
        add("explode", "爆発", Kind.EXPLODE, 0.3, 1.2, 0, null);
        add("explode_big", "大爆発", Kind.EXPLODE, 0.15, 2.0, 0, null);
        add("burst", "爆裂", Kind.EXPLODE, 0.1, 3.0, 0, null);
        add("blast_push", "衝撃爆発", Kind.EXPLODE, 0.25, 1.5, 1, null);
        add("chain_blast", "連鎖爆発", Kind.EXPLODE, 0.2, 1.0, 2, null);
        add("thunder", "雷撃", Kind.LIGHTNING, 0.2, 0, 0, null);
        add("lightning", "落雷", Kind.LIGHTNING, 0.3, 0, 0, null);
        add("thunder_god", "雷神", Kind.LIGHTNING, 0.45, 0, 1, null);

        // --- 吸収 ---
        add("lifesteal", "吸血", Kind.LIFESTEAL, 1.0, 0.1, 0, null);
        add("bloodsip", "血啜り", Kind.LIFESTEAL, 1.0, 0.2, 0, null);
        add("lifedrain", "吸命", Kind.LIFESTEAL, 1.0, 0.3, 0, null);
        add("soulsip", "魂吸い", Kind.LIFESTEAL, 0.5, 0.5, 0, null);

        // --- ふっとばし・打ち上げ・引き寄せ ---
        add("shove", "突き飛ばし", Kind.KNOCK, 0.6, 0.8, 0, null);
        add("might", "剛力", Kind.KNOCK, 0.5, 1.4, 0, null);
        add("blowaway", "吹き飛ばし", Kind.KNOCK, 0.4, 2.0, 0, null);
        add("smackout", "叩き出し", Kind.KNOCK, 0.3, 3.0, 0, null);
        add("launch", "打ち上げ", Kind.LAUNCH, 0.4, 0.7, 0, null);
        add("risingdragon", "昇竜", Kind.LAUNCH, 0.3, 1.1, 0, null);
        add("skypierce", "天衝", Kind.LAUNCH, 0.2, 1.6, 0, null);
        add("pull", "引き寄せ", Kind.PULL, 0.5, 0.8, 0, null);
        add("grapple", "鉤縄", Kind.PULL, 0.4, 1.3, 0, null);
        add("gravity", "重力", Kind.PULL, 0.3, 1.8, 0, null);

        // --- まわりへ ---
        add("shock", "衝撃波", Kind.SHOCK, 0.4, 2.5, 0.4, null);
        add("quake", "地ひびき", Kind.SHOCK, 0.3, 3.5, 0.5, null);
        add("earthsplit", "大地割り", Kind.SHOCK, 0.2, 4.5, 0.6, null);
        add("wave", "波動", Kind.SHOCK, 0.5, 2.0, 0.3, null);
        add("chain", "連鎖", Kind.CHAIN, 0.5, 2, 0.5, null);
        add("thunderchain", "雷鎖", Kind.CHAIN, 0.4, 3, 0.6, null);
        add("spread", "伝播", Kind.CHAIN, 0.3, 4, 0.5, null);
        add("rapidchain", "連斬", Kind.CHAIN, 0.6, 2, 0.3, null);
        add("sweep", "薙ぎ払い", Kind.SWEEP, 0.5, 2.5, 0.4, null);
        add("whirlwind", "旋風", Kind.SWEEP, 0.35, 3.5, 0.5, null);
        add("circleslash", "全周斬り", Kind.SWEEP, 0.25, 4.5, 0.6, null);

        // --- 追撃・特攻 ---
        add("finish", "止め", Kind.EXECUTE, 1.0, 0.3, 0.6, null);
        add("execute", "処刑", Kind.EXECUTE, 1.0, 0.4, 1.0, null);
        add("judgement", "断罪", Kind.EXECUTE, 1.0, 0.5, 1.5, null);
        add("bane_undead", "退魔", Kind.BANE_UNDEAD, 1.0, 0.6, 0, null);
        add("bane_insect", "虫殺し", Kind.BANE_ARTHROPOD, 1.0, 0.6, 0, null);
        add("bane_aquatic", "水棲特攻", Kind.BANE_AQUATIC, 1.0, 0.6, 0, null);
        add("bane_illager", "襲撃者特攻", Kind.BANE_ILLAGER, 1.0, 0.6, 0, null);
        add("bane_flying", "対空", Kind.BANE_FLYING, 1.0, 0.6, 0, null);
        add("bane_boss", "巨人殺し", Kind.BANE_BOSS, 1.0, 0.5, 0, null);
        add("bane_nether", "ネザー特攻", Kind.BANE_NETHER, 1.0, 0.5, 0, null);
        add("bane_end", "エンド特攻", Kind.BANE_END, 1.0, 0.5, 0, null);
        add("crit", "会心", Kind.CRIT, 0.2, 0.8, 0, null);
        add("greatcrit", "大会心", Kind.CRIT, 0.12, 1.5, 0, null);
        add("deathblow", "必殺", Kind.CRIT, 0.06, 3.0, 0, null);
        add("weakpoint", "急所", Kind.CRIT, 0.3, 0.5, 0, null);
        add("double", "二連", Kind.DOUBLE, 0.25, 1.0, 0, null);
        add("echo", "残響", Kind.DOUBLE, 0.2, 0.7, 0, null);
        add("triple", "三連", Kind.DOUBLE, 0.12, 1.5, 0, null);
        add("armorbreak", "鎧通し", Kind.ARMOR_PIERCE, 1.0, 0.08, 0, null);
        add("pierce", "貫通", Kind.ARMOR_PIERCE, 1.0, 0.12, 0, null);
        add("armorcrush", "破甲", Kind.ARMOR_PIERCE, 1.0, 0.18, 0, null);

        // --- 倒したとき ---
        add("kill_heal", "戦いの癒し", Kind.KILL_HEAL, 1.0, 2, 0, null);
        add("kill_heal2", "勝利の癒し", Kind.KILL_HEAL, 1.0, 5, 0, null);
        add("kill_xp", "経験の泉", Kind.KILL_XP, 1.0, 5, 0, null);
        add("kill_xp2", "賢者の心", Kind.KILL_XP, 1.0, 12, 0, null);
        add("kill_explode", "爆散", Kind.KILL_EXPLODE, 1.0, 1.5, 0, null);
        add("kill_explode2", "大爆散", Kind.KILL_EXPLODE, 1.0, 3.0, 0, null);
        add("kill_speed", "勝利の疾風", Kind.KILL_EFFECT, 1.0, 5, 1, MobEffects.SPEED);
        add("kill_hunter", "狩人の足", Kind.KILL_EFFECT, 1.0, 8, 2, MobEffects.SPEED);
        add("kill_strength", "力の覚醒", Kind.KILL_EFFECT, 1.0, 6, 0, MobEffects.STRENGTH);
        add("kill_absorb", "戦士の加護", Kind.KILL_ABSORB, 1.0, 4, 0, null);
        add("kill_mend", "自己修復", Kind.KILL_MEND, 1.0, 6, 0, null);
        add("kill_food", "戦いの糧", Kind.KILL_FOOD, 1.0, 4, 0, null);

        // --- 持っているあいだずっと（強さ = b）---
        add("passive_speed", "俊足", Kind.PASSIVE, 1.0, 0, 0, MobEffects.SPEED);
        add("passive_jump", "跳躍", Kind.PASSIVE, 1.0, 0, 1, MobEffects.JUMP_BOOST);
        add("passive_night", "暗視", Kind.PASSIVE, 1.0, 0, 0, MobEffects.NIGHT_VISION);
        add("passive_strength", "剛腕", Kind.PASSIVE, 1.0, 0, 0, MobEffects.STRENGTH);
        add("passive_guard", "守り", Kind.PASSIVE, 1.0, 0, 0, MobEffects.RESISTANCE);
        add("passive_regen", "再生", Kind.PASSIVE, 1.0, 0, 0, MobEffects.REGENERATION);
        add("passive_fire", "耐火", Kind.PASSIVE, 1.0, 0, 0, MobEffects.FIRE_RESISTANCE);
        add("passive_water", "水中呼吸", Kind.PASSIVE, 1.0, 0, 0, MobEffects.WATER_BREATHING);
        add("passive_luck", "幸運", Kind.PASSIVE, 1.0, 0, 1, MobEffects.LUCK);
        add("passive_absorb", "加護", Kind.PASSIVE, 1.0, 0, 1, MobEffects.ABSORPTION);
        add("passive_feather", "軽業", Kind.PASSIVE, 1.0, 0, 0, MobEffects.SLOW_FALLING);
        add("passive_dolphin", "海の加護", Kind.PASSIVE, 1.0, 0, 0, MobEffects.DOLPHINS_GRACE);
        add("passive_haste", "採掘加速", Kind.PASSIVE, 1.0, 0, 1, MobEffects.HASTE);
    }

    // ===== 持ち物のデータ =====

    // 持ち物のカスタムデータに入れる名前
    private static final String ROOT = "ikioiken";

    // 1 本に付けられる能力の数の上限、強さの上限、武器のレベルの上限
    // （上限はなくした。数字が大きくなりすぎないための、とても大きな技術的な上限だけ残してある）
    public static final int MAX_ABILITIES = Integer.MAX_VALUE;
    public static final int MAX_ABILITY_LEVEL = 1_000_000;
    public static final int MAX_WEAPON_LEVEL = 1_000_000;

    /** 持ち物についている能力 1 つぶん（能力と強さ）。 */
    public record Entry(Ability ability, int level) {
    }

    /** その持ち物に、能力をつけられる（武器・道具）なら true。 */
    public static boolean canHaveAbilities(ItemStack stack) {
        return !stack.isEmpty() && (SwordCheck.isWeapon(stack) || SwordCheck.isTool(stack));
    }

    /** 持ち物の能力の一覧を返す（なければ空）。 */
    public static List<Entry> abilitiesOf(ItemStack stack) {
        List<Entry> entries = new ArrayList<>();
        if (stack.isEmpty()) {
            return entries;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return entries;
        }
        ListTag list = data.copyTag().getCompoundOrEmpty(ROOT).getListOrEmpty("ab");
        for (int index = 0; index < list.size(); index++) {
            CompoundTag tag = list.getCompoundOrEmpty(index);
            Ability ability = ALL.get(tag.getStringOr("id", ""));
            if (ability != null) {
                entries.add(new Entry(ability, Mth.clamp(tag.getIntOr("lv", 1), 1, MAX_ABILITY_LEVEL)));
            }
        }
        return entries;
    }

    /** 武器のレベル（1〜10）を返す。 */
    public static int weaponLevel(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return 1;
        }
        return Mth.clamp(data.copyTag().getCompoundOrEmpty(ROOT).getIntOr("lv", 1), 1, MAX_WEAPON_LEVEL);
    }

    /** 能力と武器のレベルを持ち物に書きこんで、説明文も作りなおす。 */
    public static void write(ItemStack stack, List<Entry> entries, int weaponLevel) {
        CompoundTag root = new CompoundTag();
        root.putInt("lv", Mth.clamp(weaponLevel, 1, MAX_WEAPON_LEVEL));
        ListTag list = new ListTag();
        for (Entry entry : entries) {
            CompoundTag tag = new CompoundTag();
            tag.putString("id", entry.ability().id());
            tag.putInt("lv", entry.level());
            list.add(tag);
        }
        root.put("ab", list);
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.put(ROOT, root));
        refreshLore(stack, entries, weaponLevel);
    }

    /** 説明文（ロア）に、武器のレベルと能力の名前を出す。 */
    private static void refreshLore(ItemStack stack, List<Entry> entries, int weaponLevel) {
        List<Component> lines = new ArrayList<>();
        Style plain = Style.EMPTY.withItalic(false);
        lines.add(Component.literal("武器レベル " + weaponLevel).setStyle(plain.withColor(ChatFormatting.GOLD)));
        for (Entry entry : entries) {
            lines.add(Component.literal("◆ " + entry.ability().name() + " Lv" + entry.level())
                    .setStyle(plain.withColor(ChatFormatting.AQUA)));
        }
        stack.set(DataComponents.LORE, new ItemLore(lines));
    }

    // ===== 作ったときの付与 =====

    /** 作ったばかりの武器に、ランダムな能力を 1 つつける（すでに能力があれば何もしない）。 */
    public static void assignOnCraft(ItemStack stack, net.minecraft.util.RandomSource random) {
        if (!canHaveAbilities(stack) || !abilitiesOf(stack).isEmpty()) {
            return;
        }
        Ability ability = LIST.get(random.nextInt(LIST.size()));

        // 強さは、1 がいちばん多く、たまに 2・3
        int level = 1;
        if (random.nextDouble() < 0.2) {
            level = 2;
            if (random.nextDouble() < 0.25) {
                level = 3;
            }
        }
        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry(ability, level));
        write(stack, entries, 1);
    }

    // ===== 強さの計算 =====

    /** 能力の強さ（強さレベルと武器のレベルで上がる）の倍率。 */
    private static double power(Entry entry, int weaponLevel) {
        return (1.0 + 0.4 * (entry.level() - 1)) * (1.0 + 0.08 * (weaponLevel - 1));
    }

    /** 状態異常などの長さ（tick）を、大きくなりすぎないように 10 分までにして返す。 */
    private static int cappedTicks(double ticks) {
        return (int) Math.min(ticks, 20.0 * 60.0 * 10.0);
    }

    /** 出る確率（強さレベルで少し上がる）。 */
    private static double chanceOf(Entry entry) {
        return Math.min(1.0, entry.ability().chance() * (1.0 + 0.25 * (entry.level() - 1)));
    }

    // ===== 当てたとき =====

    /** 当てたあとに、持っている武器の能力を出す。dealt は与えたダメージ。 */
    public static void onHit(ServerPlayer player, ItemStack weapon, LivingEntity target, float dealt, Vec3 hitPoint) {
        List<Entry> entries = abilitiesOf(weapon);
        if (entries.isEmpty() || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        int weaponLevel = weaponLevel(weapon);
        for (Entry entry : entries) {
            Ability ability = entry.ability();
            double power = power(entry, weaponLevel);
            boolean always = ability.chance() >= 1.0;
            if (!always && player.getRandom().nextDouble() >= chanceOf(entry)) {
                continue;
            }
            applyHit(player, level, target, dealt, hitPoint, entry, power);
        }
    }

    /** 能力 1 つの、当てたときの効果。 */
    private static void applyHit(ServerPlayer player, ServerLevel level, LivingEntity target, float dealt,
                                 Vec3 hitPoint, Entry entry, double power) {
        Ability ability = entry.ability();
        Vec3 center = target.getBoundingBox().getCenter();
        switch (ability.kind()) {
            case EFFECT -> {
                if (target.isAlive()) {
                    target.addEffect(new MobEffectInstance(ability.effect(), cappedTicks(ability.a() * 20 * power),
                            (int) ability.b()), player);
                }
            }
            case IGNITE -> target.igniteForSeconds((float) Math.min(ability.a() * power, 60.0));
            case FREEZE -> {
                target.setTicksFrozen(target.getTicksRequiredToFreeze() + cappedTicks(ability.a() * 20 * power));
                target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, cappedTicks(ability.a() * 20 * power), 2), player);
                level.sendParticles(ParticleTypes.SNOWFLAKE, center.x, center.y, center.z, 12, 0.3, 0.4, 0.3, 0.02);
            }
            case EXPLODE -> {
                explode(player, level, center, (float) (ability.a() * Math.min(power, 2.0)));
                if (ability.b() >= 1 && target.isAlive()) {
                    // 衝撃爆発・連鎖爆発：もう一度爆発して、相手を遠くへ飛ばす
                    explode(player, level, center.add(0.0, 0.3, 0.0), (float) (ability.a() * 0.5));
                }
            }
            case LIGHTNING -> {
                LightningBolt bolt = EntityTypes.LIGHTNING_BOLT.spawn(level, target.blockPosition(), EntitySpawnReason.TRIGGERED);
                if (bolt != null) {
                    bolt.setCause(player);
                }
                if (ability.b() >= 1) {
                    extraDamage(player, level, target, dealt * 0.5f);
                }
            }
            case LIFESTEAL -> player.heal((float) (dealt * ability.a() * Math.min(power, 6.0)));
            case KNOCK -> push(player, target, ability.a() * Math.min(power, 5.0), 0.25);
            case LAUNCH -> target.setDeltaMovement(target.getDeltaMovement().add(0.0, ability.a() * Math.min(power, 1.6), 0.0));
            case PULL -> {
                Vec3 toward = player.position().subtract(target.position());
                if (toward.lengthSqr() > 1.0e-4) {
                    Vec3 direction = toward.normalize().scale(ability.a() * Math.min(power, 1.6));
                    target.setDeltaMovement(target.getDeltaMovement().add(direction.x, 0.2, direction.z));
                }
            }
            case SHOCK -> area(player, level, target, center, ability.a() * Math.min(power, 1.5), dealt * ability.b() * power, true);
            case SWEEP -> area(player, level, target, player.position().add(0.0, 1.0, 0.0), ability.a() * Math.min(power, 1.5),
                    dealt * ability.b() * power, false);
            case CHAIN -> chain(player, level, target, center, Math.min(12, (int) (ability.a() + (power - 1.0) * 2)), dealt * (float) (ability.b() * power));
            case EXECUTE -> {
                float ratio = target.getHealth() / target.getMaxHealth();
                if (target.isAlive() && ratio <= ability.a()) {
                    extraDamage(player, level, target, dealt * (float) (ability.b() * power));
                    level.sendParticles(ParticleTypes.CRIT, center.x, center.y, center.z, 16, 0.3, 0.4, 0.3, 0.2);
                }
            }
            case BANE_UNDEAD -> bane(player, level, target, dealt, ability, power, target.getType().builtInRegistryHolder().is(EntityTypeTags.UNDEAD));
            case BANE_ARTHROPOD -> bane(player, level, target, dealt, ability, power, target.getType().builtInRegistryHolder().is(EntityTypeTags.ARTHROPOD));
            case BANE_AQUATIC -> bane(player, level, target, dealt, ability, power, target.getType().builtInRegistryHolder().is(EntityTypeTags.AQUATIC));
            case BANE_ILLAGER -> bane(player, level, target, dealt, ability, power, target.getType().builtInRegistryHolder().is(EntityTypeTags.ILLAGER));
            case BANE_FLYING -> bane(player, level, target, dealt, ability, power, !target.onGround() && !target.isInWater());
            case BANE_BOSS -> bane(player, level, target, dealt, ability, power, target.getMaxHealth() >= 100.0f);
            case BANE_NETHER -> bane(player, level, target, dealt, ability, power, level.dimension() == Level.NETHER);
            case BANE_END -> bane(player, level, target, dealt, ability, power, level.dimension() == Level.END);
            case CRIT -> {
                extraDamage(player, level, target, dealt * (float) (ability.a() * power));
                level.sendParticles(ParticleTypes.CRIT, center.x, center.y, center.z, 18, 0.3, 0.4, 0.3, 0.25);
                level.playSound(null, center.x, center.y, center.z, SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 0.8f, 1.1f);
            }
            case DOUBLE -> {
                extraDamage(player, level, target, dealt * (float) (ability.a() * Math.min(power, 1.6)));
                level.sendParticles(ParticleTypes.SWEEP_ATTACK, center.x, center.y, center.z, 1, 0.0, 0.0, 0.0, 0.0);
            }
            case ARMOR_PIERCE -> extraDamage(player, level, target, (float) (target.getArmorValue() * ability.a() * power * Math.max(1.0f, dealt * 0.3f)));
            default -> {
                // 倒したとき・持っているあいだの能力は、ここでは出ない
            }
        }
    }

    /** 特攻：その相手に当てたときだけ、追加のダメージ。 */
    private static void bane(ServerPlayer player, ServerLevel level, LivingEntity target, float dealt,
                             Ability ability, double power, boolean matches) {
        if (matches) {
            extraDamage(player, level, target, dealt * (float) (ability.a() * power));
        }
    }

    /** 相手の中心で、まわりを巻きこまない（ブロックを壊さない）爆発を起こす。使っている本人にはダメージなし。 */
    private static void explode(ServerPlayer player, ServerLevel level, Vec3 center, float power) {
        ExplosionDamageCalculator calculator = new ExplosionDamageCalculator() {
            @Override
            public boolean shouldDamageEntity(Explosion explosion, Entity entity) {
                return entity != player && !(entity instanceof ServerPlayer);
            }
        };
        level.explode(player, level.damageSources().playerAttack(player), calculator, center.x, center.y, center.z,
                power, false, Level.ExplosionInteraction.NONE);
    }

    /** 無敵時間をむしして、追加のダメージをあたえる（鎧を無視。倒したら本人の手柄になる）。 */
    private static void extraDamage(ServerPlayer player, ServerLevel level, LivingEntity target, float amount) {
        if (amount <= 0.0f || !target.isAlive()) {
            return;
        }
        target.setInvulnerableTime(0);
        target.hurtServer(level, level.damageSources().indirectMagic(player, player), amount);
    }

    /** 相手を、プレイヤーの向きと反対でない方向（前）へふっとばす。 */
    private static void push(ServerPlayer player, LivingEntity target, double strength, double lift) {
        Vec3 look = player.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0.0, look.z);
        if (flat.lengthSqr() < 1.0e-4) {
            return;
        }
        double resistance = 1.0 - target.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE);
        Vec3 direction = flat.normalize().scale(strength * Math.max(resistance, 0.0));
        target.setDeltaMovement(target.getDeltaMovement().add(direction.x, lift, direction.z));
    }

    /** 中心のまわりにいる相手に、ダメージをあたえる。 */
    private static void area(ServerPlayer player, ServerLevel level, Entity mainTarget, Vec3 center, double radius,
                             double damage, boolean outward) {
        level.sendParticles(ParticleTypes.EXPLOSION, center.x, center.y, center.z, 1, 0.0, 0.0, 0.0, 0.0);
        AABB box = new AABB(center, center).inflate(radius);
        for (Entity entity : level.getEntities(player, box, e -> e != mainTarget && e instanceof LivingEntity && e.isAlive()
                && !(e instanceof ServerPlayer) && e.distanceToSqr(center) <= radius * radius)) {
            LivingEntity living = (LivingEntity) entity;
            extraDamage(player, level, living, (float) damage);
            if (outward) {
                Vec3 away = living.position().subtract(center);
                if (away.horizontalDistanceSqr() > 1.0e-4) {
                    Vec3 direction = away.normalize().scale(0.6);
                    living.setDeltaMovement(living.getDeltaMovement().add(direction.x, 0.25, direction.z));
                }
            }
        }
    }

    /** 当てた相手の近くの別の相手へ、ダメージがうつっていく。 */
    private static void chain(ServerPlayer player, ServerLevel level, LivingEntity first, Vec3 from, int count, float damage) {
        Vec3 current = from;
        List<Entity> used = new ArrayList<>();
        used.add(first);
        for (int jump = 0; jump < Math.max(1, count); jump++) {
            Vec3 origin = current;
            Entity next = null;
            double best = 36.0;
            for (Entity entity : level.getEntities(player, new AABB(origin, origin).inflate(6.0),
                    e -> e instanceof LivingEntity && e.isAlive() && !(e instanceof ServerPlayer) && !used.contains(e))) {
                double distance = entity.getBoundingBox().getCenter().distanceToSqr(origin);
                if (distance < best) {
                    best = distance;
                    next = entity;
                }
            }
            if (next == null) {
                return;
            }
            used.add(next);
            Vec3 nextCenter = next.getBoundingBox().getCenter();
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, nextCenter.x, nextCenter.y, nextCenter.z, 8, 0.2, 0.3, 0.2, 0.1);
            extraDamage(player, level, (LivingEntity) next, damage);
            current = nextCenter;
        }
    }

    // ===== 倒したとき =====

    /** 相手を倒したときの能力を出す。 */
    public static void onKill(ServerPlayer player, ItemStack weapon, LivingEntity victim) {
        List<Entry> entries = abilitiesOf(weapon);
        if (entries.isEmpty() || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        int weaponLevel = weaponLevel(weapon);
        Vec3 center = victim.getBoundingBox().getCenter();
        for (Entry entry : entries) {
            Ability ability = entry.ability();
            double power = power(entry, weaponLevel);
            switch (ability.kind()) {
                case KILL_HEAL -> player.heal((float) (ability.a() * power));
                case KILL_XP -> ExperienceOrb.award(level, center, (int) Math.min(ability.a() * power, 1000.0));
                case KILL_EXPLODE -> explode(player, level, center, (float) (ability.a() * Math.min(power, 2.0)));
                case KILL_EFFECT -> player.addEffect(new MobEffectInstance(ability.effect(), cappedTicks(ability.a() * 20 * power), (int) ability.b()));
                case KILL_ABSORB -> player.setAbsorptionAmount(Math.min(player.getAbsorptionAmount() + (float) (ability.a() * power), 20.0f));
                case KILL_MEND -> weapon.setDamageValue(Math.max(0, weapon.getDamageValue() - (int) Math.min(ability.a() * power, 100000.0)));
                case KILL_FOOD -> player.getFoodData().eat((int) Math.min(ability.a() * power, 40.0), 0.4f);
                default -> {
                }
            }
        }
    }

    // ===== 持っているあいだずっと =====

    /** 1 秒ごとに、持っている武器の「持っているだけで効く能力」を本人にかける。 */
    public static void tickPassives(ServerPlayer player) {
        for (ItemStack stack : new ItemStack[] {player.getMainHandItem(), player.getOffhandItem()}) {
            if (stack.isEmpty()) {
                continue;
            }
            List<Entry> entries = abilitiesOf(stack);
            if (entries.isEmpty()) {
                continue;
            }
            int weaponLevel = weaponLevel(stack);
            for (Entry entry : entries) {
                Ability ability = entry.ability();
                if (ability.kind() != Kind.PASSIVE) {
                    continue;
                }
                int amplifier = Math.min(9, (int) ability.b() + (entry.level() - 1) / 2 + (weaponLevel - 1) / 4);
                player.addEffect(new MobEffectInstance(ability.effect(), 80, amplifier, true, false, true));
            }
        }
    }

    /** 全部の能力の一覧（登録順）。 */
    public static List<Ability> all() {
        return LIST;
    }
}
