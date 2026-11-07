package com.maccha.ikioiken;

import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.neoforged.neoforge.common.ItemAbilities;

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

    /** 持っている物の特殊能力を返す（ほかの Mod の武器も、できること（投げる・木を切る・なぎ払う）で見分ける）。 */
    public static int skillOf(ItemStack stack) {
        if (stack.isEmpty()) {
            return NONE;
        }

        // トライデント（投げられる槍）
        if (stack.getItem() instanceof TridentItem || stack.canPerformAction(ItemAbilities.TRIDENT_THROW)) {
            return TRIDENT;
        }

        // 斧（木を切れる道具）
        if (stack.getItem() instanceof AxeItem || stack.canPerformAction(ItemAbilities.AXE_DIG)) {
            return AXE;
        }

        // 剣（なぎ払いができる武器）
        if (stack.getItem() instanceof SwordItem || stack.canPerformAction(ItemAbilities.SWORD_SWEEP)) {
            return SWORD;
        }

        // つるはし・シャベル・クワ（カタリズムのハンマーなどもここ）
        if (stack.canPerformAction(ItemAbilities.PICKAXE_DIG)) {
            return PICKAXE;
        }
        if (stack.canPerformAction(ItemAbilities.SHOVEL_DIG)) {
            return SHOVEL;
        }
        if (stack.canPerformAction(ItemAbilities.HOE_DIG)) {
            return HOE;
        }

        // そのほか（ほかの Mod の槍・短剣・大剣など。重さで能力が変わる）
        return OTHER;
    }
}
