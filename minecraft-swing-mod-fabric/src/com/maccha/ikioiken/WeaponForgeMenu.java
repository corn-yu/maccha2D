package com.maccha.ikioiken;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Prediction;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 武器合体台の画面の中身（専用の画面）。
 * 0 番=武器、1 番=合体する武器かレベルアップの材料、2 番=結果、3〜38 番=プレイヤーの持ち物。
 */
public class WeaponForgeMenu extends AbstractContainerMenu {

    public static final int WEAPON_SLOT = 0;
    public static final int SECOND_SLOT = 1;
    public static final int RESULT_SLOT = 2;
    private static final int TABLE_SIZE = 3;

    /** 画面の大きさと枠の場所（サーバーも画面も同じ数字を使う）。 */
    public static final int IMAGE_WIDTH = 192;
    public static final int IMAGE_HEIGHT = 224;
    public static final int WEAPON_X = 24;
    public static final int WEAPON_Y = 34;
    public static final int SECOND_X = 24;
    public static final int SECOND_Y = 70;
    public static final int RESULT_X = 166;
    public static final int RESULT_Y = 50;
    private static final int INVENTORY_X = 15;
    private static final int INVENTORY_Y = 142;
    private static final int HOTBAR_Y = 200;

    private final Container table = new SimpleContainer(TABLE_SIZE);
    private final Level level;
    private final BlockPos pos;

    /** 決まった役目の枠（0=取り出せない、1=武器だけ、2=自由に出し入れ）。 */
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

        /** 取り出せるのは武器の枠と自由の枠だけ（結果は専用の処理で渡す）。 */
        @Override
        public boolean mayPickup(Player player) {
            return kind != 0;
        }
    }

    /** 画面の中身を作る。台の 3 枠と、持ち物の枠を並べる。 */
    public WeaponForgeMenu(int containerId, Inventory inventory, BlockPos pos) {
        super(WeaponForge.menuType, containerId);
        this.level = inventory.player.level();
        this.pos = pos;

        addSlot(new TableSlot(table, WEAPON_SLOT, WEAPON_X, WEAPON_Y, 1));
        addSlot(new TableSlot(table, SECOND_SLOT, SECOND_X, SECOND_Y, 2));
        addSlot(new TableSlot(table, RESULT_SLOT, RESULT_X, RESULT_Y, 0));

        // 持ち物（3 段）とホットバー
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, 9 + col + row * 9, INVENTORY_X + col * 18, INVENTORY_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, INVENTORY_X + col * 18, HOTBAR_Y));
        }
        refresh();
    }

    /** 結果の枠を、いまの入れ物の中身に合わせて作りなおす（サーバー側で呼ぶ）。 */
    private void refresh() {
        ForgeRules.Preview preview = ForgeRules.preview(table.getItem(WEAPON_SLOT), table.getItem(SECOND_SLOT));
        table.setItem(RESULT_SLOT, preview.ready() ? preview.result().copy() : ItemStack.EMPTY);
    }

    /** クリックの処理。結果の枠は自分で受けとりを行い、ほかの枠は元の動きのあとに結果を作りなおす。 */
    @Override
    public void clicked(int slotIndex, int button, ContainerInput input, Player player) {
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

    /** 「実行」ボタンが押されたとき、結果を持ち物に入れる。 */
    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (player instanceof ServerPlayer serverPlayer) {
            craft(serverPlayer, true);
            refresh();
        }
        return true;
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
        if (index < 0 || index >= slots.size() || index == RESULT_SLOT) {
            return ItemStack.EMPTY;
        }
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack original = slot.getItem();
        ItemStack copy = original.copy();

        if (index >= TABLE_SIZE) {
            // 持ち物から台へ（武器なら武器の枠、だめなら右の枠）
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
        } else if (!moveItemStackTo(original, TABLE_SIZE, slots.size(), true)) {
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
