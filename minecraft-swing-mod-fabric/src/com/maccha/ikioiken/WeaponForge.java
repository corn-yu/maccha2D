package com.maccha.ikioiken;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * 武器合体台のブロックとアイテムを登録する。
 */
public final class WeaponForge {

    public static Block block;
    public static Item item;

    private WeaponForge() {
    }

    /** ブロックとアイテムをゲームに登録し、クリエイティブの「機能的ブロック」に並べる。 */
    public static void register() {
        Identifier id = Identifier.fromNamespaceAndPath(IkioiKen.MOD_ID, "weapon_forge");

        // ブロック
        ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, id);
        block = Registry.register(BuiltInRegistries.BLOCK, blockKey,
                new WeaponForgeBlock(BlockBehaviour.Properties.of().strength(3.5f).sound(SoundType.ANVIL).setId(blockKey)));

        // 持ち歩くアイテム
        ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id);
        item = Registry.register(BuiltInRegistries.ITEM, itemKey,
                new BlockItem(block, new Item.Properties().setId(itemKey).useBlockDescriptionPrefix()));

        // クリエイティブのタブに並べる
        ResourceKey<CreativeModeTab> tabKey = ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.parse("functional_blocks"));
        CreativeModeTabEvents.modifyOutputEvent(tabKey).register(output -> output.accept(item));
    }
}
