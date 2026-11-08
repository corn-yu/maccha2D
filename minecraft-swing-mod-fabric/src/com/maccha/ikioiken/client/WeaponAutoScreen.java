package com.maccha.ikioiken.client;

import com.maccha.ikioiken.ForgeRules;
import com.maccha.ikioiken.WeaponAbilities;
import com.maccha.ikioiken.WeaponAutoMenu;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 自動合成機の専用画面。ベースの武器・3×3 の入れ場所・自動のオンオフ・いま合成するボタン・いまの様子を描く。
 */
public class WeaponAutoScreen extends AbstractContainerScreen<WeaponAutoMenu> {

    // 色（ARGB）
    private static final int COLOR_BACK = 0xFF1B1B26;
    private static final int COLOR_BACK_LOW = 0xFF22303A;
    private static final int COLOR_EDGE = 0xFF4FB8D9;
    private static final int COLOR_EDGE_DARK = 0xFF1E5A70;
    private static final int COLOR_INSET = 0xFF0E0E16;
    private static final int COLOR_INSET_LINE = 0xFF3A3A55;
    private static final int COLOR_SLOT_DARK = 0xFF373737;
    private static final int COLOR_SLOT = 0xFF8B8B8B;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_TEXT_DIM = 0xFFA8A8B8;
    private static final int COLOR_GREEN = 0xFF55FF55;
    private static final int COLOR_YELLOW = 0xFFFFD84A;
    private static final int COLOR_AQUA = 0xFF55E6E6;

    private Button autoButton;
    private Button runButton;

    /** 画面を作る。 */
    public WeaponAutoScreen(WeaponAutoMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WeaponAutoMenu.IMAGE_WIDTH, WeaponAutoMenu.IMAGE_HEIGHT);
    }

    /** 画面を開いたとき、ボタンを置く。 */
    @Override
    protected void init() {
        super.init();
        autoButton = addRenderableWidget(Button.builder(Component.literal("自動: ON"), button -> pressMenuButton(0))
                .bounds(leftPos + 130, topPos + 26, 54, 16).build());
        runButton = addRenderableWidget(Button.builder(Component.literal("いま合成"), button -> pressMenuButton(1))
                .bounds(leftPos + 130, topPos + 46, 54, 16).build());
    }

    /** メニューのボタンを押したことをサーバーへ伝える。 */
    private void pressMenuButton(int id) {
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
        }
    }

    /** 背景と中身を描く。 */
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        autoButton.setMessage(Component.literal(menu.isAuto() ? "自動: ON" : "自動: OFF"));

        int x = leftPos;
        int y = topPos;
        int w = WeaponAutoMenu.IMAGE_WIDTH;
        int h = WeaponAutoMenu.IMAGE_HEIGHT;

        // 外側の板
        g.fill(x - 2, y - 2, x + w + 2, y + h + 2, COLOR_EDGE_DARK);
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, COLOR_EDGE);
        g.fillGradient(x, y, x + w, y + h, COLOR_BACK_LOW, COLOR_BACK);
        g.fill(x, y, x + w, y + 20, 0xFF14141F);
        g.fill(x, y + 20, x + w, y + 21, COLOR_EDGE_DARK);

        // 持ち物のくぼみ
        g.fill(x + 10, y + 104, x + w - 10, y + h - 4, COLOR_INSET);
        g.outline(x + 10, y + 104, w - 20, h - 108, COLOR_INSET_LINE);

        // 枠
        for (int index = 0; index < menu.slots.size(); index++) {
            Slot slot = menu.slots.get(index);
            int slotX = x + slot.x - 1;
            int slotY = y + slot.y - 1;
            if (index == WeaponAutoMenu.BASE_SLOT) {
                g.fill(slotX - 1, slotY - 1, slotX + 19, slotY + 19, COLOR_YELLOW);
            }
            g.fill(slotX, slotY, slotX + 18, slotY + 18, COLOR_SLOT_DARK);
            g.fill(slotX + 1, slotY + 1, slotX + 17, slotY + 17, index < WeaponAutoMenu.INPUT_END ? 0xFF2E2E3A : COLOR_SLOT);
        }

        // 文字
        g.text(font, "自動合成機", x + 8, y + 6, COLOR_AQUA, true);
        g.text(font, "ベース", x + 24, y + 28, COLOR_TEXT_DIM, false);
        g.text(font, "武器・材料を入れる", x + 62, y + 10 + 0, COLOR_TEXT_DIM, false);
        g.text(font, "＋", x + 46, y + 44, COLOR_TEXT, true);

        // いまの様子（ベースの武器と、入っている物の数）
        ItemStack base = menu.slots.get(WeaponAutoMenu.BASE_SLOT).getItem();
        int weapons = 0;
        int materials = 0;
        for (int index = WeaponAutoMenu.INPUT_START; index < WeaponAutoMenu.INPUT_END; index++) {
            ItemStack stack = menu.slots.get(index).getItem();
            if (stack.isEmpty()) {
                continue;
            }
            if (WeaponAbilities.canHaveAbilities(stack)) {
                weapons++;
            } else if (!base.isEmpty() && ForgeRules.isMaterialFor(base, stack)) {
                materials += stack.getCount();
            }
        }
        if (base.isEmpty()) {
            g.text(font, "左にベースの武器を入れてね", x + 8, y + 86, COLOR_TEXT_DIM, false);
        } else {
            g.text(font, "武器Lv " + WeaponAbilities.weaponLevel(base) + "  能力 " + WeaponAbilities.abilitiesOf(base).size() + " 個",
                    x + 8, y + 70, COLOR_YELLOW, false);
            g.text(font, "合体できる武器 " + weapons + " 本・材料 " + materials + " 個 (" + ForgeRules.materialLabel(base) + ")",
                    x + 8, y + 84, weapons + materials > 0 ? COLOR_GREEN : COLOR_TEXT_DIM, false);
        }
        g.text(font, "入れると自動で合体・レベルアップ(経験値レベルを使う)", x + 8, y + 94, COLOR_TEXT_DIM, false);
    }

    /** 標準の文字は使わない（自分で描いている）。 */
    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
    }
}
