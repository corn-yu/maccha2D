package com.maccha.zombiepanic;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

import java.util.function.Consumer;

/**
 * 能力の石: 右クリックで うばった大技を使う。しゃがんで右クリックで 大技をえらびなおす。
 */
public class AbilityStoneItem extends Item {

    /** 1こだけ持てる石にする。 */
    public AbilityStoneItem(Properties properties) {
        super(properties);
    }

    /** 右クリックしたとき。 */
    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer) {
            if (player.isShiftKeyDown()) {
                PlayerPowers.selectNextPower(serverPlayer);
            } else {
                PlayerPowers.useSelectedPower(serverPlayer);
            }
        }
        return InteractionResult.SUCCESS;
    }

    /** キラキラ光らせる。 */
    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    /** 使い方の説明。 */
    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display,
                                Consumer<Component> tooltip, TooltipFlag flag) {
        tooltip.accept(Component.literal("右クリック: うばった大技を使う").withStyle(ChatFormatting.LIGHT_PURPLE));
        tooltip.accept(Component.literal("しゃがんで右クリック: 大技をえらびなおす").withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.literal("能力ゾンビを たおすと、ときどき 能力をうばえる").withStyle(ChatFormatting.DARK_GRAY));
    }
}
