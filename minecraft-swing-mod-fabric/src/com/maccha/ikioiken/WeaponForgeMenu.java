package com.maccha.ikioiken;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;

/**
 * 武器合体台の画面の中身。ふつうの 3 段のチェストの画面を使い、サーバー側で動きだけを変えている。
 * 左（10 番）に武器、真ん中右（12 番）に合体する武器かレベルアップの材料、右（16 番）に結果が出る。
 */
public class WeaponForgeMenu extends ChestMenu {

    private static final int INFO_SLOT = 4;
    private static final int WEAPON_SLOT = 10;
    private static final int SECOND_SLOT = 12;
    private static final int RESULT_SLOT = 16;
    private static final int TABLE_SIZE = 27;

    private final Container table;
    private final Level level;
    private final BlockPos pos;

    /** 決まった役目の枠（0=ロック、1=武器、2=自由に出し入れ）。 */
    private static class TableSlot extends Slot {
        private final int kind;

        TableSlot(Container container, int index, int x, int y, int kind) {
            super(container, index, x, y);
            this.kind = kind;
        }

        /** 入れてよい物だけ入れられる。 */
        @Override
        public boolean mayPlace(ItemStack stack) {
            if (kind == 1) {
                return WeaponAbilities.canHaveAbilities(stack);
            }
            return kind == 2;
        }

        /** 取り出せるのは武器の枠と自由の枠だけ。 */
        @Override
        public boolean mayPickup(Player player) {
            return kind != 0;
        }
    }

    /** 画面を作る。枠を自分用の物に差しかえて、かざりを並べる。 */
    public WeaponForgeMenu(int containerId, Inventory inventory, Level level, BlockPos pos) {
        super(MenuType.GENERIC_9x3, containerId, inventory, new SimpleContainer(TABLE_SIZE), 3);
        this.table = getContainer();
        this.level = level;
        this.pos = pos;

        // 上の 27 枠を、役目つきの枠にとりかえる
        for (int index = 0; index < TABLE_SIZE; index++) {
            int kind = 0;
            if (index == WEAPON_SLOT) {
                kind = 1;
            } else if (index == SECOND_SLOT) {
                kind = 2;
            }
            TableSlot slot = new TableSlot(table, index, 8 + (index % 9) * 18, 18 + (index / 9) * 18, kind);
            slot.index = index;
            slots.set(index, slot);
        }

        // かざりの板を並べる（名前は空白か記号）
        for (int index = 0; index < TABLE_SIZE; index++) {
            if (index != WEAPON_SLOT && index != SECOND_SLOT && index != RESULT_SLOT && index != INFO_SLOT) {
                String label = " ";
                if (index == 11) {
                    label = "＋";
                } else if (index == 14) {
                    label = "→";
                }
                table.setItem(index, decoration(Items.GLASS_PANE, label));
            }
        }
        refresh();
    }

    /** かざり用のアイテムを作る。 */
    private static ItemStack decoration(net.minecraft.world.item.Item item, String label) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(label).setStyle(Style.EMPTY.withItalic(false)));
        return stack;
    }

    /** 結果の枠と説明の本を、いまの入れ物の中身に合わせて作りなおす。 */
    private void refresh() {
        ItemStack weapon = table.getItem(WEAPON_SLOT);
        ItemStack second = table.getItem(SECOND_SLOT);
        ForgeRules.Preview preview = ForgeRules.preview(weapon, second);

        // 結果の枠（見せるだけ。受けとるのは clicked の中）
        if (preview.ready()) {
            ItemStack shown = preview.result().copy();
            List<Component> lines = new ArrayList<>();
            ItemLore old = shown.get(DataComponents.LORE);
            if (old != null) {
                lines.addAll(old.lines());
            }
            lines.add(Component.literal(" "));
            lines.add(Component.literal("クリックで受けとる（経験値レベル " + preview.xpCost() + "）")
                    .setStyle(Style.EMPTY.withItalic(false).withColor(net.minecraft.ChatFormatting.YELLOW)));
            shown.set(DataComponents.LORE, new ItemLore(lines));
            table.setItem(RESULT_SLOT, shown);
        } else {
            table.setItem(RESULT_SLOT, ItemStack.EMPTY);
        }

        // 説明の本
        ItemStack info = new ItemStack(Items.BOOK);
        info.set(DataComponents.CUSTOM_NAME, Component.literal("武器合体台").setStyle(
                Style.EMPTY.withItalic(false).withColor(net.minecraft.ChatFormatting.GOLD)));
        List<Component> infoLines = new ArrayList<>();
        Style plain = Style.EMPTY.withItalic(false).withColor(net.minecraft.ChatFormatting.GRAY);
        infoLines.add(Component.literal(preview.message()).setStyle(
                Style.EMPTY.withItalic(false).withColor(preview.ready() ? net.minecraft.ChatFormatting.GREEN : net.minecraft.ChatFormatting.WHITE)));
        infoLines.add(Component.literal("【合体】左の武器に、右の武器の能力が足される").setStyle(plain));
        infoLines.add(Component.literal("同じ能力は強さが 1 上がる（能力は最大 4 つ）").setStyle(plain));
        infoLines.add(Component.literal("【レベルアップ】右に武器の材料を入れる").setStyle(plain));
        infoLines.add(Component.literal("経験値レベルと材料を払うと、武器が強くなる（最大 Lv10）").setStyle(plain));
        info.set(DataComponents.LORE, new ItemLore(infoLines));
        table.setItem(INFO_SLOT, info);
    }

    /** クリックの処理。結果の枠は自分で受けとりを行い、ほかの枠は元の動きのあとに結果を作りなおす。 */
    @Override
    public void clicked(int slotIndex, int button, ContainerInput input, Player player) {
        // 上の枠のうち、かざり・説明は何もしない
        if (slotIndex >= 0 && slotIndex < TABLE_SIZE && input == ContainerInput.CLONE) {
            return;
        }
        if (slotIndex == RESULT_SLOT) {
            if (player instanceof ServerPlayer serverPlayer && (input == ContainerInput.PICKUP || input == ContainerInput.QUICK_MOVE)) {
                craft(serverPlayer, input == ContainerInput.QUICK_MOVE);
            }
            refresh();
            return;
        }
        super.clicked(slotIndex, button, input, player);
        refresh();
    }

    /** 結果を受けとる。代金をはらって、材料を使い、結果を渡す。 */
    private void craft(ServerPlayer player, boolean toInventory) {
        ForgeRules.Preview preview = ForgeRules.preview(table.getItem(WEAPON_SLOT), table.getItem(SECOND_SLOT));
        if (!preview.ready()) {
            return;
        }
        if (!player.hasInfiniteMaterials() && player.experienceLevel < preview.xpCost()) {
            player.sendSystemMessage(Component.literal("経験値レベルが足りないよ（" + preview.xpCost() + " 必要）"));
            return;
        }

        // 渡せる場所をたしかめて渡す
        ItemStack given = preview.result().copy();
        if (toInventory) {
            if (!player.getInventory().add(given)) {
                player.sendSystemMessage(Component.literal("持ち物がいっぱいだよ"));
                return;
            }
        } else {
            if (!getCarried().isEmpty()) {
                return;
            }
            setCarried(given);
        }

        // 材料と代金を使う
        table.setItem(WEAPON_SLOT, ItemStack.EMPTY);
        if (preview.fusion()) {
            table.setItem(SECOND_SLOT, ItemStack.EMPTY);
        } else {
            ItemStack material = table.getItem(SECOND_SLOT);
            material.shrink(preview.materialCount());
            if (material.isEmpty()) {
                table.setItem(SECOND_SLOT, ItemStack.EMPTY);
            }
        }
        if (!player.hasInfiniteMaterials()) {
            player.giveExperienceLevels(-preview.xpCost());
        }
        level.playSound(null, pos, SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 1.0f, 1.2f);
    }

    /** シフトクリックで、持ち物と台のあいだを移す。 */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) {
            return ItemStack.EMPTY;
        }
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack original = slot.getItem();
        ItemStack copy = original.copy();

        if (index >= TABLE_SIZE) {
            // 持ち物から台へ（武器なら左の枠、だめなら右の枠）
            boolean moved = false;
            if (WeaponAbilities.canHaveAbilities(original)) {
                moved = moveItemStackTo(original, WEAPON_SLOT, WEAPON_SLOT + 1, false);
            }
            if (!moved) {
                moved = moveItemStackTo(original, SECOND_SLOT, SECOND_SLOT + 1, false);
            }
            if (!moved) {
                return ItemStack.EMPTY;
            }
        } else if (index == WEAPON_SLOT || index == SECOND_SLOT) {
            // 台から持ち物へ
            if (!moveItemStackTo(original, TABLE_SIZE, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            return ItemStack.EMPTY;
        }

        if (original.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        refresh();
        return copy;
    }

    /** 画面を閉じるとき、台に入れた物を持ち物にもどす。 */
    @Override
    public void removed(Player player) {
        super.removed(player);
        if (player instanceof ServerPlayer) {
            for (int index : new int[] { WEAPON_SLOT, SECOND_SLOT }) {
                ItemStack stack = table.getItem(index);
                if (!stack.isEmpty()) {
                    player.getInventory().placeItemBackInInventory(stack, Prediction.SERVER_ONLY);
                    table.setItem(index, ItemStack.EMPTY);
                }
            }
        }
    }

    /** 台がこわれていなくて、近くにいる間だけ開いていられる。 */
    @Override
    public boolean stillValid(Player player) {
        return level.getBlockState(pos).getBlock() instanceof WeaponForgeBlock
                && player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0;
    }
}
