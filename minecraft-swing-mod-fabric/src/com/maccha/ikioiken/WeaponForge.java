package com.maccha.ikioiken;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
import net.minecraft.core.BlockPos;
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
    public static ExtendedMenuType<WeaponForgeMenu, BlockPos> menuType;

    // 自動合成機
    public static Block autoBlock;
    public static Item autoItem;
    public static ExtendedMenuType<WeaponAutoMenu, BlockPos> autoMenuType;

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

        // 画面の種類（どの場所のブロックかを画面に伝える）
        menuType = Registry.register(BuiltInRegistries.MENU, id,
                new ExtendedMenuType<>(WeaponForgeMenu::new, BlockPos.STREAM_CODEC));

        // 自動合成機（ブロック・アイテム・画面の種類）
        Identifier autoId = Identifier.fromNamespaceAndPath(IkioiKen.MOD_ID, "auto_weapon_forge");
        ResourceKey<Block> autoBlockKey = ResourceKey.create(Registries.BLOCK, autoId);
        autoBlock = Registry.register(BuiltInRegistries.BLOCK, autoBlockKey,
                new WeaponAutoForgeBlock(BlockBehaviour.Properties.of().strength(4.0f).sound(SoundType.ANVIL).setId(autoBlockKey)));
        ResourceKey<Item> autoItemKey = ResourceKey.create(Registries.ITEM, autoId);
        autoItem = Registry.register(BuiltInRegistries.ITEM, autoItemKey,
                new BlockItem(autoBlock, new Item.Properties().setId(autoItemKey).useBlockDescriptionPrefix()));
        autoMenuType = Registry.register(BuiltInRegistries.MENU, autoId,
                new ExtendedMenuType<>(WeaponAutoMenu::new, BlockPos.STREAM_CODEC));

        // クリエイティブのタブに並べる
        ResourceKey<CreativeModeTab> tabKey = ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.parse("functional_blocks"));
        CreativeModeTabEvents.modifyOutputEvent(tabKey).register(output -> {
            output.accept(item);
            output.accept(autoItem);
        });
    }
}
