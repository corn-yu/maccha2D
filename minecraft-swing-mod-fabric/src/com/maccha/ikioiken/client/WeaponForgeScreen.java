package com.maccha.ikioiken.client;

import java.util.List;

import com.maccha.ikioiken.ForgeRules;
import com.maccha.ikioiken.WeaponAbilities;
import com.maccha.ikioiken.WeaponForgeMenu;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 武器合体台の専用画面。背景・枠・能力の一覧・レベルのメーター・代金・「実行」ボタンを自分で描く。
 */
public class WeaponForgeScreen extends AbstractContainerScreen<WeaponForgeMenu> {

    // 色（ARGB）
    private static final int COLOR_BACK = 0xFF1B1B26;
    private static final int COLOR_BACK_LOW = 0xFF2A2A3C;
    private static final int COLOR_GOLD = 0xFFD9A62E;
    private static final int COLOR_GOLD_DARK = 0xFF7A5A14;
    private static final int COLOR_INSET = 0xFF0E0E16;
    private static final int COLOR_INSET_LINE = 0xFF3A3A55;
    private static final int COLOR_SLOT_DARK = 0xFF373737;
    private static final int COLOR_SLOT = 0xFF8B8B8B;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_TEXT_DIM = 0xFFA8A8B8;
    private static final int COLOR_GREEN = 0xFF55FF55;
    private static final int COLOR_RED = 0xFFFF5555;
    private static final int COLOR_YELLOW = 0xFFFFD84A;
    private static final int COLOR_AQUA = 0xFF55E6E6;

    // 真ん中の黒い板の場所（画面の左上からの位置）
    private static final int PANEL_X = 52;
    private static final int PANEL_Y = 24;
    private static final int PANEL_W = 102;
    private static final int PANEL_H = 100;

    private Button executeButton;
    private ItemStack lastWeapon = ItemStack.EMPTY;
    private ItemStack lastSecond = ItemStack.EMPTY;
    private ForgeRules.Preview cached = new ForgeRules.Preview(ItemStack.EMPTY, 0, 0, false, "");

    /** 画面を作る。 */
    public WeaponForgeScreen(WeaponForgeMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WeaponForgeMenu.IMAGE_WIDTH, WeaponForgeMenu.IMAGE_HEIGHT);
    }

    /** 画面を開いたとき、「実行」ボタンを置く。 */
    @Override
    protected void init() {
        super.init();
        executeButton = addRenderableWidget(Button.builder(Component.literal("実行"), button -> {
            if (minecraft != null && minecraft.gameMode != null) {
                minecraft.gameMode.handleInventoryButtonClick(menu.containerId, 0);
            }
        }).bounds(leftPos + 156, topPos + 76, 30, 16).build());
    }

    /** 今の入れ物の中身から、結果の見本を出す（中身が変わったときだけ計算しなおす）。 */
    private ForgeRules.Preview currentPreview() {
        ItemStack weapon = menu.slots.get(WeaponForgeMenu.WEAPON_SLOT).getItem();
        ItemStack second = menu.slots.get(WeaponForgeMenu.SECOND_SLOT).getItem();
        if (!ItemStack.matches(weapon, lastWeapon) || !ItemStack.matches(second, lastSecond)) {
            lastWeapon = weapon.copy();
            lastSecond = second.copy();
            cached = ForgeRules.preview(weapon, second);
        }
        return cached;
    }

    /** 背景と中身を描く。 */
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        ForgeRules.Preview preview = currentPreview();
        ItemStack weapon = lastWeapon;
        boolean enough = preview.ready()
                && (minecraft.player.hasInfiniteMaterials() || minecraft.player.experienceLevel >= preview.xpCost());
        executeButton.active = enough;

        drawPanel(g);
        drawSlotFrames(g);
        drawTexts(g, preview, weapon, enough);
        drawAbilityPanel(g, preview, weapon);
    }

    /** 外側の板（金のふちつき）を描く。 */
    private void drawPanel(GuiGraphicsExtractor g) {
        int x = leftPos;
        int y = topPos;
        int w = WeaponForgeMenu.IMAGE_WIDTH;
        int h = WeaponForgeMenu.IMAGE_HEIGHT;
        g.fill(x - 2, y - 2, x + w + 2, y + h + 2, COLOR_GOLD_DARK);
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, COLOR_GOLD);
        g.fillGradient(x, y, x + w, y + h, COLOR_BACK_LOW, COLOR_BACK);
        // 上のタイトル帯
        g.fill(x, y, x + w, y + 20, 0xFF14141F);
        g.fill(x, y + 20, x + w, y + 21, COLOR_GOLD_DARK);
        // 持ち物のくぼみ
        g.fill(x + 10, topPos + 136, x + w - 10, topPos + 222 - 4, COLOR_INSET);
        g.outline(x + 10, topPos + 136, w - 20, 82, COLOR_INSET_LINE);
        // 真ん中の黒い板
        g.fill(x + PANEL_X, y + PANEL_Y, x + PANEL_X + PANEL_W, y + PANEL_Y + PANEL_H, COLOR_INSET);
        g.outline(x + PANEL_X, y + PANEL_Y, PANEL_W, PANEL_H, COLOR_INSET_LINE);
    }

    /** すべての枠にくぼみを描く（結果の枠は金色）。 */
    private void drawSlotFrames(GuiGraphicsExtractor g) {
        for (int index = 0; index < menu.slots.size(); index++) {
            Slot slot = menu.slots.get(index);
            int x = leftPos + slot.x - 1;
            int y = topPos + slot.y - 1;
            if (index == WeaponForgeMenu.RESULT_SLOT) {
                g.fill(x - 2, y - 2, x + 20, y + 20, COLOR_GOLD);
                g.fill(x - 1, y - 1, x + 19, y + 19, COLOR_GOLD_DARK);
            } else if (index < 3) {
                g.fill(x - 1, y - 1, x + 19, y + 19, COLOR_AQUA);
            }
            g.fill(x, y, x + 18, y + 18, COLOR_SLOT_DARK);
            g.fill(x + 1, y + 1, x + 17, y + 17, index < 3 ? 0xFF2E2E3A : COLOR_SLOT);
        }
        // 「＋」と矢印
        g.text(font, "＋", leftPos + 28, topPos + 55, COLOR_TEXT, true);
        int arrowY = topPos + WeaponForgeMenu.RESULT_Y + 8;
        int arrowX = leftPos + 156;
        g.fill(arrowX - 2, arrowY - 1, arrowX + 4, arrowY + 2, COLOR_GOLD);
        g.fill(arrowX + 4, arrowY - 3, arrowX + 5, arrowY + 4, COLOR_GOLD);
        g.fill(arrowX + 5, arrowY - 2, arrowX + 6, arrowY + 3, COLOR_GOLD);
        g.fill(arrowX + 6, arrowY - 1, arrowX + 7, arrowY + 2, COLOR_GOLD);
    }

    /** タイトル・枠の名前・メッセージ・代金を描く。 */
    private void drawTexts(GuiGraphicsExtractor g, ForgeRules.Preview preview, ItemStack weapon, boolean enough) {
        g.text(font, "武器合体台", leftPos + 8, topPos + 6, COLOR_YELLOW, true);
        String mode = preview.ready() ? (preview.fusion() ? "【合体】" : "【レベルアップ】") : "";
        g.text(font, mode, leftPos + WeaponForgeMenu.IMAGE_WIDTH - 8 - font.width(mode), topPos + 6, COLOR_AQUA, true);

        g.text(font, "武器", leftPos + 24, topPos + 24, COLOR_TEXT_DIM, false);
        g.text(font, "相手・材料", leftPos + 12, topPos + 60, COLOR_TEXT_DIM, false);
        g.text(font, "結果", leftPos + 169, topPos + 38, COLOR_YELLOW, false);
        g.text(font, "持ち物", leftPos + 14, topPos + 128, COLOR_TEXT_DIM, false);

        // 結果の下の代金
        if (preview.ready()) {
            int have = minecraft.player.experienceLevel;
            g.text(font, "Lv" + preview.xpCost(), leftPos + 160, topPos + 96, enough ? COLOR_GREEN : COLOR_RED, true);
            g.text(font, "(" + have + ")", leftPos + 160, topPos + 106, COLOR_TEXT_DIM, false);
        }

        // 下のメッセージ
        int color = preview.ready() ? (enough ? COLOR_GREEN : COLOR_RED) : COLOR_TEXT_DIM;
        String message = preview.message();
        if (preview.ready() && !enough) {
            message = message + "（経験値レベルが足りないよ）";
        }
        g.textWithWordWrap(font, Component.literal(message), leftPos + 8, topPos + 124 - 10, 176, color);
    }

    /** 真ん中の板に、能力の一覧とレベルのメーターを描く。 */
    private void drawAbilityPanel(GuiGraphicsExtractor g, ForgeRules.Preview preview, ItemStack weapon) {
        int x = leftPos + PANEL_X + 4;
        int y = topPos + PANEL_Y + 4;
        ItemStack shown = preview.ready() ? preview.result() : weapon;

        if (shown.isEmpty() || !WeaponAbilities.canHaveAbilities(shown)) {
            g.text(font, "武器を入れてね", x, y + 30, COLOR_TEXT_DIM, false);
            return;
        }

        // 武器の名前
        g.text(font, shown.getHoverName().getString().length() > 9
                ? shown.getHoverName().getString().substring(0, 9) + "…" : shown.getHoverName().getString(),
                x, y, COLOR_TEXT, true);
        y += 13;

        // 能力 4 つぶん（空きは薄く）
        List<WeaponAbilities.Entry> now = WeaponAbilities.abilitiesOf(shown);
        List<WeaponAbilities.Entry> before = WeaponAbilities.abilitiesOf(weapon);
        for (int row = 0; row < WeaponAbilities.MAX_ABILITIES; row++) {
            int rowY = y + row * 14;
            g.fill(x - 2, rowY - 2, x + PANEL_W - 6, rowY + 11, 0xFF181826);
            if (row >= now.size()) {
                g.text(font, "（空き）", x + 2, rowY, 0xFF55556A, false);
                continue;
            }
            WeaponAbilities.Entry entry = now.get(row);
            // 合体・レベルアップで変わった能力は色をかえる
            int oldLevel = 0;
            for (WeaponAbilities.Entry old : before) {
                if (old.ability().id().equals(entry.ability().id())) {
                    oldLevel = old.level();
                }
            }
            boolean changed = preview.ready() && preview.fusion() && entry.level() != oldLevel;
            String name = entry.ability().name();
            if (name.length() > 6) {
                name = name.substring(0, 6) + "…";
            }
            g.text(font, name, x + 2, rowY, changed ? COLOR_GREEN : COLOR_TEXT, false);
            // 強さの目もり（5 つ）
            for (int pip = 0; pip < WeaponAbilities.MAX_ABILITY_LEVEL; pip++) {
                int pipX = x + PANEL_W - 6 - 5 * 6 - 1 + pip * 6;
                int pipColor = pip < entry.level() ? (pip >= oldLevel && changed ? COLOR_GREEN : COLOR_YELLOW) : 0xFF33334A;
                g.fill(pipX, rowY + 2, pipX + 5, rowY + 8, pipColor);
            }
        }

        // 武器レベルのメーター（10 目もり）
        int meterY = y + WeaponAbilities.MAX_ABILITIES * 14 + 6;
        int level = WeaponAbilities.weaponLevel(shown);
        int oldWeaponLevel = WeaponAbilities.weaponLevel(weapon);
        g.text(font, "武器Lv " + level + (level != oldWeaponLevel ? "  ↑" : ""), x, meterY, level != oldWeaponLevel ? COLOR_GREEN : COLOR_YELLOW, false);
        for (int step = 0; step < WeaponAbilities.MAX_WEAPON_LEVEL; step++) {
            int stepX = x + step * 9;
            int stepColor = step < oldWeaponLevel ? COLOR_YELLOW : (step < level ? COLOR_GREEN : 0xFF33334A);
            g.fill(stepX, meterY + 11, stepX + 8, meterY + 16, stepColor);
        }
    }

    /** 標準の「持ち物」「タイトル」の文字は使わない（自分で描いている）。 */
    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
    }
}
