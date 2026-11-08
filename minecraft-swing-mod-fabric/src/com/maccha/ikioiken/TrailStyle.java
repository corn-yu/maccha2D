package com.maccha.ikioiken;

import java.util.List;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/**
 * 斬撃の軌跡の色と形を、武器の種類・素材・能力から決める。
 */
public final class TrailStyle {

    /** 軌跡の形。 */
    public enum Shape {
        // 細いなめらかな弧（剣）
        SLIM,
        // 太くて重い弧（斧・メイス）
        WIDE,
        // 前へまっすぐのびる線（槍）
        STAB,
        // ギザギザにゆれる弧（雷）
        ZIGZAG,
        // 弧が 2 本ならぶ（二連・三連・連斬）
        DOUBLE,
        // 短くて小さい弧（つるはし・シャベル・クワなどの道具）
        SHORT
    }

    /** 軌跡のスタイル 1 つぶん（はじめの色・おわりの色・形・まぜる粒・粒の量）。 */
    public record Style(int colorFrom, int colorTo, Shape shape, ParticleOptions extra, float sizeBoost) {
    }

    private TrailStyle() {
    }

    /** 持っている物の軌跡のスタイルを返す。 */
    public static Style of(ItemStack weapon) {
        int skill = WeaponSkill.skillOf(weapon);

        // 形：武器の種類から
        Shape shape = switch (skill) {
            case WeaponSkill.AXE, WeaponSkill.MACE -> Shape.WIDE;
            case WeaponSkill.SPEAR -> Shape.STAB;
            case WeaponSkill.PICKAXE, WeaponSkill.SHOVEL, WeaponSkill.HOE -> Shape.SHORT;
            default -> Shape.SLIM;
        };

        // 色：素材から
        int from = materialColor(weapon, skill);
        int to = shade(from, 0.55f);
        ParticleOptions extra = null;
        float sizeBoost = 1.0f;

        // 能力があれば、いちばん強い能力に合わせて色と形をかえる
        WeaponAbilities.Entry main = strongest(WeaponAbilities.abilitiesOf(weapon));
        if (main != null) {
            WeaponAbilities.Ability ability = main.ability();
            switch (ability.kind()) {
                case IGNITE -> {
                    from = 0xFF8A1E;
                    to = 0xB01808;
                    extra = ParticleTypes.FLAME;
                }
                case FREEZE -> {
                    from = 0xBFF1FF;
                    to = 0x4FA8FF;
                    extra = ParticleTypes.SNOWFLAKE;
                }
                case LIGHTNING -> {
                    from = 0xFFF45A;
                    to = 0x8FD0FF;
                    extra = ParticleTypes.ELECTRIC_SPARK;
                    shape = Shape.ZIGZAG;
                }
                case LIFESTEAL -> {
                    from = 0xE0102E;
                    to = 0x500010;
                    extra = ParticleTypes.DAMAGE_INDICATOR;
                }
                case EXPLODE, KILL_EXPLODE -> {
                    from = 0xFF5A1A;
                    to = 0x2A2A2A;
                    extra = ParticleTypes.SMOKE;
                    sizeBoost = 1.25f;
                }
                case EFFECT -> {
                    int[] pair = effectColors(ability.id());
                    from = pair[0];
                    to = pair[1];
                }
                case KNOCK, LAUNCH, PULL -> {
                    from = 0xE8E2D0;
                    to = 0xA09880;
                    extra = ParticleTypes.CRIT;
                }
                case SHOCK -> {
                    from = 0xD6B97A;
                    to = 0x7A5A2A;
                    sizeBoost = 1.2f;
                }
                case CHAIN, SWEEP -> {
                    from = 0xB8FFD0;
                    to = 0x30C080;
                }
                case EXECUTE -> {
                    from = 0x9A0A2A;
                    to = 0x120008;
                    extra = ParticleTypes.SCULK_SOUL;
                }
                case CRIT -> {
                    from = 0xFFE066;
                    to = 0xFF3030;
                    extra = ParticleTypes.CRIT;
                    sizeBoost = 1.15f;
                }
                case DOUBLE -> {
                    from = 0xFFFFFF;
                    to = 0xB090FF;
                    shape = Shape.DOUBLE;
                }
                case ARMOR_PIERCE -> {
                    from = 0xC0D0E0;
                    to = 0x5870A0;
                    extra = ParticleTypes.END_ROD;
                }
                case BANE_UNDEAD -> {
                    from = 0xFFF8C0;
                    to = 0xE0B040;
                    extra = ParticleTypes.END_ROD;
                }
                case BANE_ARTHROPOD, BANE_AQUATIC, BANE_ILLAGER, BANE_FLYING, BANE_BOSS, BANE_NETHER, BANE_END -> {
                    from = baneColor(ability.kind());
                    to = shade(from, 0.5f);
                    extra = ParticleTypes.ENCHANT;
                }
                case KILL_HEAL, KILL_XP, KILL_ABSORB, KILL_MEND, KILL_FOOD, KILL_EFFECT -> {
                    from = 0x9CFFB0;
                    to = 0x2FB060;
                    extra = ParticleTypes.GLOW;
                }
                default -> {
                    // 持っているあいだずっと効く能力は、素材の色のまま
                }
            }

            // 能力が 3 つ以上あると、きらきらが足される
            if (WeaponAbilities.abilitiesOf(weapon).size() >= 3 && extra == null) {
                extra = ParticleTypes.END_ROD;
            }
        }
        return new Style(from, to, shape, extra, sizeBoost);
    }

    /** 能力のうち、強さがいちばん高いもの（同じなら先のもの）を返す。なければ null。 */
    private static WeaponAbilities.Entry strongest(List<WeaponAbilities.Entry> entries) {
        WeaponAbilities.Entry best = null;
        for (WeaponAbilities.Entry entry : entries) {
            if (best == null || entry.level() > best.level()) {
                best = entry;
            }
        }
        return best;
    }

    /** 素材（名前の頭）から軌跡の色を決める。 */
    private static int materialColor(ItemStack weapon, int skill) {
        String name = BuiltInRegistries.ITEM.getKey(weapon.getItem()).getPath();
        if (skill == WeaponSkill.TRIDENT) {
            return 0x39D6C8;
        }
        if (skill == WeaponSkill.MACE) {
            return 0x9AA8B8;
        }
        if (name.startsWith("wooden_")) {
            return 0xC9A06A;
        } else if (name.startsWith("stone_")) {
            return 0xC8C8C8;
        } else if (name.startsWith("golden_")) {
            return 0xFFD83A;
        } else if (name.startsWith("copper_")) {
            return 0xF09870;
        } else if (name.startsWith("diamond_")) {
            return 0x58F0E8;
        } else if (name.startsWith("netherite_")) {
            return 0xB070C0;
        }
        return 0xF2F7FF;
    }

    /** 状態異常の能力ごとの色（はじめ・おわり）。 */
    private static int[] effectColors(String id) {
        return switch (id) {
            case "poison", "venom", "poison_drug", "ooze" -> new int[] { 0x7CFF4A, 0x2A7A18 };
            case "wither", "darkness", "blind" -> new int[] { 0x606060, 0x101010 };
            case "slow", "weave", "weak" -> new int[] { 0xC8D4DC, 0x78909C };
            case "nausea", "hunger", "infest" -> new int[] { 0xC8E050, 0x607010 };
            case "glow", "float", "windcharge" -> new int[] { 0xFFFFFF, 0xA0E0FF };
            case "stun" -> new int[] { 0xFFE860, 0xFFA000 };
            default -> new int[] { 0xE0C0FF, 0x8040C0 };
        };
    }

    /** 特攻の種類ごとの色。 */
    private static int baneColor(WeaponAbilities.Kind kind) {
        return switch (kind) {
            case BANE_ARTHROPOD -> 0x80D040;
            case BANE_AQUATIC -> 0x40A0FF;
            case BANE_ILLAGER -> 0xA0A0A0;
            case BANE_FLYING -> 0xA0E0FF;
            case BANE_BOSS -> 0xFF6060;
            case BANE_NETHER -> 0xFF7030;
            case BANE_END -> 0xC080FF;
            default -> 0xFFFFFF;
        };
    }

    /** 色を暗く（factor < 1）して返す。 */
    private static int shade(int color, float factor) {
        int red = (int) (((color >> 16) & 0xFF) * factor);
        int green = (int) (((color >> 8) & 0xFF) * factor);
        int blue = (int) ((color & 0xFF) * factor);
        return (red << 16) | (green << 8) | blue;
    }
}
