package com.maccha.zombiepanic;

import com.maccha.zombiepanic.structure.LabBunkerPiece;
import com.maccha.zombiepanic.structure.ResearchLabStructure;
import com.maccha.zombiepanic.structure.RuinPiece;
import com.maccha.zombiepanic.structure.RuinedCityStructure;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.item.equipment.ArmorMaterial;
import net.minecraft.world.item.equipment.ArmorType;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;

import java.util.EnumMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * この Mod で増やすもの（呪鉄のアイテム・能力の石・構造物）をまとめて登録する。
 */
public final class ModContent {

    private static final String MOD_ID = ZombiePanic.MOD_ID;

    // 呪鉄を直すための材料（タグ）と、よろいの見た目の名前
    private static final TagKey<Item> CURSED_REPAIR_TAG = TagKey.create(Registries.ITEM, id("repairs_cursed_iron"));
    private static final ResourceKey<EquipmentAsset> CURSED_EQUIPMENT_ASSET =
            ResourceKey.create(EquipmentAssets_ROOT(), id("cursed_iron"));

    // 呪鉄の道具の強さ（ダイヤより少し強く、ネザライトより少し弱い）
    public static final ToolMaterial CURSED_TIER = new ToolMaterial(BlockTags.INCORRECT_FOR_NETHERITE_TOOL,
            1800, 8.5f, 3.5f, 18, CURSED_REPAIR_TAG);

    // 呪鉄のよろいの強さ
    public static final ArmorMaterial CURSED_ARMOR_MATERIAL = new ArmorMaterial(37, makeArmorDefense(), 18,
            SoundEvents.ARMOR_EQUIP_NETHERITE, 2.5f, 0.05f, CURSED_REPAIR_TAG, CURSED_EQUIPMENT_ASSET);

    // ---------------- 呪鉄（強い素材） ----------------

    public static final Supplier<Item> CURSED_IRON_NUGGET = registerItem("cursed_iron_nugget", Item::new, props -> props);
    public static final Supplier<Item> CURSED_IRON_INGOT = registerItem("cursed_iron_ingot", Item::new, props -> props);

    public static final Supplier<Item> CURSED_IRON_SWORD = registerItem("cursed_iron_sword", Item::new,
            props -> props.sword(CURSED_TIER, 3.0f, -2.4f));
    public static final Supplier<Item> CURSED_IRON_PICKAXE = registerItem("cursed_iron_pickaxe", Item::new,
            props -> props.pickaxe(CURSED_TIER, 1.0f, -2.8f));
    public static final Supplier<Item> CURSED_IRON_AXE = registerItem("cursed_iron_axe", Item::new,
            props -> props.axe(CURSED_TIER, 5.0f, -3.0f));

    public static final Supplier<Item> CURSED_IRON_HELMET = registerItem("cursed_iron_helmet", Item::new,
            props -> props.humanoidArmor(CURSED_ARMOR_MATERIAL, ArmorType.HELMET));
    public static final Supplier<Item> CURSED_IRON_CHESTPLATE = registerItem("cursed_iron_chestplate", Item::new,
            props -> props.humanoidArmor(CURSED_ARMOR_MATERIAL, ArmorType.CHESTPLATE));
    public static final Supplier<Item> CURSED_IRON_LEGGINGS = registerItem("cursed_iron_leggings", Item::new,
            props -> props.humanoidArmor(CURSED_ARMOR_MATERIAL, ArmorType.LEGGINGS));
    public static final Supplier<Item> CURSED_IRON_BOOTS = registerItem("cursed_iron_boots", Item::new,
            props -> props.humanoidArmor(CURSED_ARMOR_MATERIAL, ArmorType.BOOTS));

    // 能力の石（うばった大技を使う）
    public static final Supplier<Item> ABILITY_STONE = registerItem("ability_stone", AbilityStoneItem::new,
            props -> props.stacksTo(1).rarity(net.minecraft.world.item.Rarity.EPIC));

    // ---------------- 構造物（廃都市・研究所） ----------------

    public static final StructureType<RuinedCityStructure> RUINED_CITY_TYPE =
            Registry.register(BuiltInRegistries.STRUCTURE_TYPE, id("ruined_city"), () -> RuinedCityStructure.CODEC);
    public static final StructureType<ResearchLabStructure> RESEARCH_LAB_TYPE =
            Registry.register(BuiltInRegistries.STRUCTURE_TYPE, id("research_lab"), () -> ResearchLabStructure.CODEC);

    public static final StructurePieceType RUIN_PIECE = Registry.register(BuiltInRegistries.STRUCTURE_PIECE, id("ruin"),
            (StructurePieceType.ContextlessType) RuinPiece::new);
    public static final StructurePieceType LAB_BUNKER_PIECE = Registry.register(BuiltInRegistries.STRUCTURE_PIECE, id("lab_bunker"),
            (StructurePieceType.ContextlessType) LabBunkerPiece::new);

    private ModContent() {
    }

    /** このクラスを読みこんで、登録をおわらせる（Mod が始まるときに1回よぶ）。 */
    public static void register() {
        addToCreativeTab("ingredients", CURSED_IRON_NUGGET, CURSED_IRON_INGOT);
        addToCreativeTab("combat", CURSED_IRON_SWORD, CURSED_IRON_AXE, CURSED_IRON_HELMET, CURSED_IRON_CHESTPLATE,
                CURSED_IRON_LEGGINGS, CURSED_IRON_BOOTS, ABILITY_STONE);
        addToCreativeTab("tools_and_utilities", CURSED_IRON_PICKAXE, CURSED_IRON_AXE);
    }

    /** この Mod の名前でIDを作る。 */
    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    /** よろいの見た目の置き場の名前。 */
    private static ResourceKey<? extends Registry<EquipmentAsset>> EquipmentAssets_ROOT() {
        return net.minecraft.world.item.equipment.EquipmentAssets.ROOT_ID;
    }

    /** アイテムを1つ登録する（IDはアイテムの設定にも入れる）。 */
    private static Supplier<Item> registerItem(String name, Function<Item.Properties, Item> factory,
                                               Function<Item.Properties, Item.Properties> settings) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id(name));
        Item item = Registry.register(BuiltInRegistries.ITEM, key, factory.apply(settings.apply(new Item.Properties().setId(key))));
        return () -> item;
    }

    /** よろいの部位ごとの守り（ダイヤ＋α）。 */
    private static EnumMap<ArmorType, Integer> makeArmorDefense() {
        EnumMap<ArmorType, Integer> defense = new EnumMap<>(ArmorType.class);
        defense.put(ArmorType.BOOTS, 3);
        defense.put(ArmorType.LEGGINGS, 6);
        defense.put(ArmorType.CHESTPLATE, 8);
        defense.put(ArmorType.HELMET, 3);
        defense.put(ArmorType.BODY, 11);
        return defense;
    }

    /** クリエイティブのタブ（バニラのタブの名前）に、アイテムをならべる。 */
    @SafeVarargs
    private static void addToCreativeTab(String vanillaTabName, Supplier<Item>... items) {
        ResourceKey<CreativeModeTab> tab = ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace(vanillaTabName));
        CreativeModeTabEvents.modifyOutputEvent(tab).register(output -> {
            for (Supplier<Item> item : items) {
                output.accept(item.get());
            }
        });
    }
}
