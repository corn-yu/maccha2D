package com.maccha.ikioiken;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 武器ごとの特殊能力を決めるクラス（剣＝高速切り、斧＝超クリティカル、トライデント＝三本の斬撃）。
 */
public final class WeaponSkill {

    private WeaponSkill() {
    }

    // 特殊能力の種類
    public static final int NONE = 0;
    public static final int SWORD = 1;
    public static final int AXE = 2;
    public static final int TRIDENT = 3;
    public static final int PICKAXE = 4;
    public static final int SHOVEL = 5;
    public static final int HOE = 6;
    public static final int OTHER = 7;

    /** 持っている物の特殊能力を返す（ほかの Mod の武器も、タグと重さで見分ける）。 */
    public static int skillOf(ItemStack stack) {
        if (stack.isEmpty()) {
            return NONE;
        }

        // トライデント（投げられる槍）
        if (stack.is(Items.TRIDENT)) {
            return TRIDENT;
        }

        // 斧・剣（槍も剣のなかま）
        if (stack.is(ItemTags.AXES)) {
            return AXE;
        }
        if (stack.is(ItemTags.SWORDS) || stack.is(ItemTags.SPEARS)) {
            return SWORD;
        }

        // つるはし・シャベル・クワ（カタリズムのハンマーなどもここ）
        if (stack.is(ItemTags.PICKAXES)) {
            return PICKAXE;
        }
        if (stack.is(ItemTags.SHOVELS)) {
            return SHOVEL;
        }
        if (stack.is(ItemTags.HOES)) {
            return HOE;
        }

        // そのほか（ほかの Mod の槍・短剣・大剣など。重さで能力が変わる）
        return OTHER;
    }
}
