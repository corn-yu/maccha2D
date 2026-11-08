package com.maccha.ikioiken.client;

import java.util.List;

import com.maccha.ikioiken.SwingConfig;
import com.maccha.ikioiken.SwordCheck;
import com.maccha.ikioiken.WeaponAbilities;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * 武器の説明文（マウスを乗せたときの文）に、攻撃力・いきおいダメージ・重さ・掘る速さを足す。
 */
public final class WeaponTooltip {

    private WeaponTooltip() {
    }

    /** 振れる武器・道具の説明文の終わりに、数字をつけたす。 */
    public static void onTooltip(ItemStack stack, Item.TooltipContext context, TooltipFlag flag, List<Component> lines) {
        if (!SwordCheck.isSwingItem(stack)) {
            return;
        }

        // 攻撃力 = 素手の 1 + 持ち物がくれる分
        double[] added = {0.0};
        stack.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            if (attribute.is(Attributes.ATTACK_DAMAGE)) {
                added[0] += modifier.amount();
            }
        });
        double attack = 1.0 + added[0];
        double levelFactor = 1.0 + SwingConfig.WEAPON_LEVEL_DAMAGE_BONUS * (WeaponAbilities.weaponLevel(stack) - 1);
        double normal = attack * levelFactor;
        double full = normal * SwingConfig.MAX_SPEED_FACTOR;

        ChatFormatting label = ChatFormatting.GRAY;
        lines.add(Component.literal("── いきおい剣 ──").withStyle(ChatFormatting.DARK_AQUA));
        lines.add(Component.literal("攻撃力 ").withStyle(label)
                .append(Component.literal(format(attack)).withStyle(ChatFormatting.WHITE)));
        lines.add(Component.literal("振ったダメージ ").withStyle(label)
                .append(Component.literal(format(normal) + " 〜 " + format(full)).withStyle(ChatFormatting.RED))
                .append(Component.literal("（いきおいしだい）").withStyle(label)));
        lines.add(Component.literal("重さ ").withStyle(label)
                .append(Component.literal("×" + format(SwordCheck.materialWeight(stack))).withStyle(ChatFormatting.WHITE)));
        if (SwordCheck.isTool(stack)) {
            lines.add(Component.literal("掘る速さ ").withStyle(label)
                    .append(Component.literal("×" + format(SwordCheck.materialDigSpeed(stack))).withStyle(ChatFormatting.WHITE)));
        }
        if (WeaponAbilities.canHaveAbilities(stack)) {
            lines.add(Component.literal("武器レベル " + WeaponAbilities.weaponLevel(stack)
                    + "  能力 " + WeaponAbilities.abilitiesOf(stack).size() + " 個")
                    .withStyle(ChatFormatting.GOLD));
        }
    }

    /** 数字を小数 1 けたで見せる（.0 は消す）。 */
    private static String format(double value) {
        double rounded = Math.round(value * 10.0) / 10.0;
        if (rounded == Math.floor(rounded)) {
            return String.valueOf((long) rounded);
        }
        return String.valueOf(rounded);
    }
}
