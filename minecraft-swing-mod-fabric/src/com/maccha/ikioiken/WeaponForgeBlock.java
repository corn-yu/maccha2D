package com.maccha.ikioiken;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 武器合体台のブロック。右クリックで合体・レベルアップの画面を開く。
 */
public class WeaponForgeBlock extends Block {

    /** ブロックを作る。 */
    public WeaponForgeBlock(Properties properties) {
        super(properties);
    }

    /** 右クリックされたとき、画面を開く（開くのはサーバー側だけ）。 */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        player.openMenu(new SimpleMenuProvider(
                (containerId, inventory, menuPlayer) -> new WeaponForgeMenu(containerId, inventory, level, pos),
                Component.literal("武器合体台")));
        return InteractionResult.CONSUME;
    }
}
