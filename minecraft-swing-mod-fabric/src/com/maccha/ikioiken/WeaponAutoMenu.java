package com.maccha.ikioiken;

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
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 自動合成機の画面の中身。0 番=ベースの武器、1〜9 番=合体する武器や材料を入れる 3×3、10〜45 番=持ち物。
 * 入れると自動で、ベースの武器に全部の合体・レベルアップをする（経験値レベルがつづくかぎり）。
 */
public class WeaponAutoMenu extends AbstractContainerMenu {

    public static final int BASE_SLOT = 0;
    public static final int INPUT_START = 1;
    public static final int INPUT_END = 10;
    private static final int TABLE_SIZE = 10;

    /** 画面の大きさと枠の場所（サーバーも画面も同じ数字を使う）。 */
    public static final int IMAGE_WIDTH = 192;
    public static final int IMAGE_HEIGHT = 192;
    public static final int BASE_X = 26;
    public static final int BASE_Y = 40;
    public static final int INPUT_X = 66;
    public static final int INPUT_Y = 22;
    private static final int INVENTORY_X = 15;
    private static final int INVENTORY_Y = 110;
    private static final int HOTBAR_Y = 168;

    private final Container table = new SimpleContainer(TABLE_SIZE);
    private final Level level;
    private final BlockPos pos;

    // 自動で合成するか（1=する、0=しない）。画面にも伝わる
    private final DataSlot autoSwitch = DataSlot.standalone();

    /** 決まった役目の枠（ベースの武器の枠は武器だけ入れられる）。 */
    private static class TableSlot extends Slot {
        private final boolean weaponOnly;

        TableSlot(Container container, int index, int x, int y, boolean weaponOnly) {
            super(container, index, x, y);
            this.weaponOnly = weaponOnly;
        }

        /** 入れてよい物だけ入れられる。 */
        @Override
        public boolean mayPlace(ItemStack stack) {
            return !weaponOnly || WeaponAbilities.canHaveAbilities(stack);
        }
    }

    /** 画面の中身を作る。 */
    public WeaponAutoMenu(int containerId, Inventory inventory, BlockPos pos) {
        super(WeaponForge.autoMenuType, containerId);
        this.level = inventory.player.level();
        this.pos = pos;
        autoSwitch.set(1);
        addDataSlot(autoSwitch);

        addSlot(new TableSlot(table, BASE_SLOT, BASE_X, BASE_Y, true));
        for (int index = 0; index < 9; index++) {
            addSlot(new TableSlot(table, INPUT_START + index, INPUT_X + (index % 3) * 18, INPUT_Y + (index / 3) * 18, false));
        }

        // 持ち物（3 段）とホットバー
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, 9 + col + row * 9, INVENTORY_X + col * 18, INVENTORY_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, INVENTORY_X + col * 18, HOTBAR_Y));
        }
    }

    /** 自動で合成する設定なら true（画面でも使う）。 */
    public boolean isAuto() {
        return autoSwitch.get() != 0;
    }

    /** クリックの処理。元の動きのあと、自動なら合成する。 */
    @Override
    public void clicked(int slotIndex, int button, ContainerInput input, Player player) {
        super.clicked(slotIndex, button, input, player);
        if (isAuto() && player instanceof ServerPlayer serverPlayer) {
            autoCraft(serverPlayer);
        }
    }

    /** ボタン：0=自動のオンオフ、1=いま合成する。 */
    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (id == 0) {
            autoSwitch.set(isAuto() ? 0 : 1);
            if (isAuto() && player instanceof ServerPlayer serverPlayer) {
                autoCraft(serverPlayer);
            }
        } else if (id == 1 && player instanceof ServerPlayer serverPlayer) {
            autoCraft(serverPlayer);
        }
        return true;
    }

    /** ベースの武器に、入っている武器を全部合体し、材料でレベルアップできるだけする。 */
    private void autoCraft(ServerPlayer player) {
        ItemStack base = table.getItem(BASE_SLOT);
        if (base.isEmpty() || !WeaponAbilities.canHaveAbilities(base)) {
            return;
        }
        int fusions = 0;
        int levelUps = 0;
        long spent = 0;
        boolean lackXp = false;

        // 進めなくなるまでくりかえす（念のため回数に上限）
        for (int turn = 0; turn < 5000; turn++) {
            boolean progressed = false;
            for (int index = INPUT_START; index < INPUT_END && !progressed; index++) {
                ItemStack stack = table.getItem(index);
                if (stack.isEmpty()) {
                    continue;
                }
                ForgeRules.Preview preview = ForgeRules.preview(base, stack);
                if (!preview.ready()) {
                    continue;
                }
                if (!player.hasInfiniteMaterials() && player.experienceLevel < preview.xpCost()) {
                    lackXp = true;
                    continue;
                }

                // 結果をベースにして、使った物をへらし、経験値レベルを払う
                base = preview.result().copy();
                if (preview.fusion()) {
                    table.setItem(index, ItemStack.EMPTY);
                    fusions++;
                } else {
                    stack.shrink(preview.materialCount());
                    if (stack.isEmpty()) {
                        table.setItem(index, ItemStack.EMPTY);
                    }
                    levelUps++;
                }
                if (!player.hasInfiniteMaterials()) {
                    player.giveExperienceLevels(-preview.xpCost());
                }
                spent += preview.xpCost();
                progressed = true;
            }
            if (!progressed) {
                break;
            }
        }

        if (fusions + levelUps > 0) {
            table.setItem(BASE_SLOT, base);
            level.playSound(null, pos, SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 1.0f, 1.2f);
            player.sendSystemMessage(Component.literal("自動合成：合体 " + fusions + " 回・レベルアップ " + levelUps
                    + " 回（経験値レベル " + spent + " 使用）" + (lackXp ? "。経験値レベルが足りなくて止まったよ" : "")));
        } else if (lackXp) {
            player.sendSystemMessage(Component.literal("経験値レベルが足りなくて、合成できなかったよ"));
        }
    }

    /** シフトクリックで、持ち物と機械のあいだを移す。 */
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
            // 持ち物から機械へ（ベースが空いていて武器ならベースへ、それ以外は 3×3 へ）
            boolean moved = false;
            if (!table.getItem(BASE_SLOT).isEmpty() || !WeaponAbilities.canHaveAbilities(original)) {
                moved = moveItemStackTo(original, INPUT_START, INPUT_END, false);
            } else {
                moved = moveItemStackTo(original, BASE_SLOT, BASE_SLOT + 1, false);
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
        if (isAuto() && player instanceof ServerPlayer serverPlayer) {
            autoCraft(serverPlayer);
        }
        return copy;
    }

    /** 画面を閉じるとき、入れた物を持ち物にもどす。 */
    @Override
    public void removed(Player player) {
        super.removed(player);
        if (player instanceof ServerPlayer) {
            for (int index = 0; index < TABLE_SIZE; index++) {
                ItemStack stack = table.getItem(index);
                if (!stack.isEmpty()) {
                    player.getInventory().placeItemBackInInventory(stack, Prediction.SERVER_ONLY);
                    table.setItem(index, ItemStack.EMPTY);
                }
            }
        }
    }

    /** 機械がこわれていなくて、近くにいる間だけ開いていられる。 */
    @Override
    public boolean stillValid(Player player) {
        return level.getBlockState(pos).getBlock() instanceof WeaponAutoForgeBlock
                && player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0;
    }
}
