package com.maccha.zombiepanic;

import com.maccha.zombiepanic.structure.ResearchLabStructure;
import com.maccha.zombiepanic.structure.RuinPiece;
import com.maccha.zombiepanic.structure.RuinedCityStructure;
import com.maccha.zombiepanic.structure.LabBunkerPiece;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.SimpleTier;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.EnumMap;
import java.util.List;

/**
 * この Mod で増やすもの（呪鉄のアイテム・構造物）をまとめて登録する。
 */
public final class ModContent {

    private static final String MOD_ID = ZombiePanic.MOD_ID;

    // 登録係
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    public static final DeferredRegister<ArmorMaterial> ARMOR_MATERIALS = DeferredRegister.create(Registries.ARMOR_MATERIAL, MOD_ID);
    public static final DeferredRegister<StructureType<?>> STRUCTURE_TYPES = DeferredRegister.create(Registries.STRUCTURE_TYPE, MOD_ID);
    public static final DeferredRegister<StructurePieceType> STRUCTURE_PIECES = DeferredRegister.create(Registries.STRUCTURE_PIECE, MOD_ID);

    // ---------------- 呪鉄（強い素材） ----------------

    public static final DeferredItem<Item> CURSED_IRON_NUGGET = ITEMS.registerSimpleItem("cursed_iron_nugget");
    public static final DeferredItem<Item> CURSED_IRON_INGOT = ITEMS.registerSimpleItem("cursed_iron_ingot");

    // 能力の石（うばった大技を使う）
    public static final DeferredItem<AbilityStoneItem> ABILITY_STONE = ITEMS.register("ability_stone",
            () -> new AbilityStoneItem(new Item.Properties().stacksTo(1).rarity(net.minecraft.world.item.Rarity.EPIC)));

    // 呪鉄の道具の強さ（ダイヤより少し強く、ネザライトより少し弱い）
    public static final SimpleTier CURSED_TIER = new SimpleTier(BlockTags.INCORRECT_FOR_NETHERITE_TOOL,
            1800, 8.5f, 3.5f, 18, () -> Ingredient.of(CURSED_IRON_INGOT.get()));

    public static final DeferredItem<SwordItem> CURSED_IRON_SWORD = ITEMS.register("cursed_iron_sword",
            () -> new SwordItem(CURSED_TIER, new Item.Properties().attributes(SwordItem.createAttributes(CURSED_TIER, 3, -2.4f))));
    public static final DeferredItem<PickaxeItem> CURSED_IRON_PICKAXE = ITEMS.register("cursed_iron_pickaxe",
            () -> new PickaxeItem(CURSED_TIER, new Item.Properties().attributes(DiggerItem.createAttributes(CURSED_TIER, 1.0f, -2.8f))));
    public static final DeferredItem<AxeItem> CURSED_IRON_AXE = ITEMS.register("cursed_iron_axe",
            () -> new AxeItem(CURSED_TIER, new Item.Properties().attributes(DiggerItem.createAttributes(CURSED_TIER, 5.0f, -3.0f))));

    // 呪鉄のよろいの強さ
    public static final DeferredHolder<ArmorMaterial, ArmorMaterial> CURSED_ARMOR_MATERIAL = ARMOR_MATERIALS.register("cursed_iron",
            () -> new ArmorMaterial(makeArmorDefense(), 18, SoundEvents.ARMOR_EQUIP_NETHERITE,
                    () -> Ingredient.of(CURSED_IRON_INGOT.get()),
                    List.of(new ArmorMaterial.Layer(ResourceLocation.fromNamespaceAndPath(MOD_ID, "cursed_iron"))),
                    2.5f, 0.05f));

    public static final DeferredItem<ArmorItem> CURSED_IRON_HELMET = registerArmor("cursed_iron_helmet", ArmorItem.Type.HELMET);
    public static final DeferredItem<ArmorItem> CURSED_IRON_CHESTPLATE = registerArmor("cursed_iron_chestplate", ArmorItem.Type.CHESTPLATE);
    public static final DeferredItem<ArmorItem> CURSED_IRON_LEGGINGS = registerArmor("cursed_iron_leggings", ArmorItem.Type.LEGGINGS);
    public static final DeferredItem<ArmorItem> CURSED_IRON_BOOTS = registerArmor("cursed_iron_boots", ArmorItem.Type.BOOTS);

    // ---------------- 構造物（廃都市・研究所） ----------------

    public static final DeferredHolder<StructureType<?>, StructureType<RuinedCityStructure>> RUINED_CITY_TYPE =
            STRUCTURE_TYPES.register("ruined_city", () -> () -> RuinedCityStructure.CODEC);
    public static final DeferredHolder<StructureType<?>, StructureType<ResearchLabStructure>> RESEARCH_LAB_TYPE =
            STRUCTURE_TYPES.register("research_lab", () -> () -> ResearchLabStructure.CODEC);

    public static final DeferredHolder<StructurePieceType, StructurePieceType> RUIN_PIECE =
            STRUCTURE_PIECES.register("ruin", () -> (StructurePieceType.ContextlessType) RuinPiece::new);
    public static final DeferredHolder<StructurePieceType, StructurePieceType> LAB_BUNKER_PIECE =
            STRUCTURE_PIECES.register("lab_bunker", () -> (StructurePieceType.ContextlessType) LabBunkerPiece::new);

    private ModContent() {
    }

    /** 登録係を Mod のイベントにつなぐ。 */
    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
        ARMOR_MATERIALS.register(modEventBus);
        STRUCTURE_TYPES.register(modEventBus);
        STRUCTURE_PIECES.register(modEventBus);
        modEventBus.addListener(ModContent::addToCreativeTabs);
    }

    /** よろいのアイテムを1つ登録する。 */
    private static DeferredItem<ArmorItem> registerArmor(String name, ArmorItem.Type type) {
        return ITEMS.register(name, () -> new ArmorItem(CURSED_ARMOR_MATERIAL, type,
                new Item.Properties().durability(type.getDurability(37))));
    }

    /** よろいの部位ごとの守り（ダイヤ＋α）。 */
    private static EnumMap<ArmorItem.Type, Integer> makeArmorDefense() {
        EnumMap<ArmorItem.Type, Integer> defense = new EnumMap<>(ArmorItem.Type.class);
        defense.put(ArmorItem.Type.BOOTS, 3);
        defense.put(ArmorItem.Type.LEGGINGS, 6);
        defense.put(ArmorItem.Type.CHESTPLATE, 8);
        defense.put(ArmorItem.Type.HELMET, 3);
        defense.put(ArmorItem.Type.BODY, 11);
        return defense;
    }

    /** そのタブかどうか（バニラのタブの名前でくらべる）。 */
    private static boolean isTab(BuildCreativeModeTabContentsEvent event, String vanillaTabName) {
        return event.getTabKey().location().equals(ResourceLocation.withDefaultNamespace(vanillaTabName));
    }

    /** クリエイティブのタブに呪鉄のアイテムをならべる。 */
    private static void addToCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (isTab(event, "ingredients")) {
            event.accept(CURSED_IRON_NUGGET.get());
            event.accept(CURSED_IRON_INGOT.get());
        }
        if (isTab(event, "combat")) {
            event.accept(CURSED_IRON_SWORD.get());
            event.accept(CURSED_IRON_AXE.get());
            event.accept(CURSED_IRON_HELMET.get());
            event.accept(CURSED_IRON_CHESTPLATE.get());
            event.accept(CURSED_IRON_LEGGINGS.get());
            event.accept(CURSED_IRON_BOOTS.get());
            event.accept(ABILITY_STONE.get());
        }
        if (isTab(event, "tools_and_utilities")) {
            event.accept(CURSED_IRON_PICKAXE.get());
            event.accept(CURSED_IRON_AXE.get());
        }
    }
}
