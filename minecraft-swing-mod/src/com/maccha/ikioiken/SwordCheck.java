package com.maccha.ikioiken;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;

/**
 * 持っている物が剣かどうかを調べるクラス。
 */
public final class SwordCheck {

    private SwordCheck() {
    }

    /** 剣（ふつうの剣・カタリズムなど他の Mod の剣）なら true を返す。 */
    public static boolean isSword(ItemStack stack) {
        // 何も持っていなければ剣ではない
        if (stack.isEmpty()) {
            return false;
        }

        // 剣のクラスならまちがいなく剣
        Item item = stack.getItem();
        if (item instanceof SwordItem) {
            return true;
        }

        // 剣のタグつきでも、ハンマー（つるはしの仲間）と左クリック技のある武器はふつうのまま
        if (!stack.is(ItemTags.SWORDS) || item instanceof DiggerItem) {
            return false;
        }
        return !hasLeftClickSkill(item);
    }

    /** カタリズムの「左クリックで技が出る武器」（古代の槍・タイダルクロー など）なら true を返す。 */
    private static boolean hasLeftClickSkill(Item item) {
        for (Class<?> itemInterface : item.getClass().getInterfaces()) {
            if (itemInterface.getSimpleName().equals("ILeftClick")) {
                return true;
            }
        }
        return false;
    }
}
