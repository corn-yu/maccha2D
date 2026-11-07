package com.maccha.ikioiken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;

/**
 * 持っている物が「いきおい剣で振れる武器・道具」かどうかを調べるクラス（武器追加 Mod の武器にも対応）。
 */
public final class SwordCheck {

    private SwordCheck() {
    }

    // 武器リストのファイル名（config フォルダの中）
    private static final String WEAPON_LIST_FILE = "ikioiken-weapons.txt";

    // ほかの Mod がつける「近接武器」のタグ（Fabric の共通タグ）
    private static final TagKey<Item> MELEE_WEAPON_TAG =
            TagKey.create(Registries.ITEM, Identifier.parse("c:tools/melee_weapon"));

    // ファイルで「振れる」にした物・「ふつうのまま」にした物（"modid:item" か "modid:*"）
    private static Set<String> addedWeapons = null;
    private static Set<String> removedWeapons = null;

    /** いきおい剣で振れる物（武器か、掘る道具）なら true を返す。 */
    public static boolean isSwingItem(ItemStack stack) {
        return isWeapon(stack) || isTool(stack);
    }

    /** 振ってブロックを壊せる道具（つるはし・斧・シャベル・クワ・ハンマー）なら true を返す。 */
    public static boolean isTool(ItemStack stack) {
        if (stack.isEmpty() || !isDiggingTool(stack)) {
            return false;
        }
        loadWeaponListIfNeeded();
        Identifier itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return !isListed(removedWeapons, itemId) && !hasLeftClickSkill(stack.getItem());
    }

    /** 掘るための道具（つるはし・斧・シャベル・クワ・ハンマーなど。剣とメイスは入れない）なら true を返す。 */
    private static boolean isDiggingTool(ItemStack stack) {
        if (stack.is(ItemTags.SWORDS) || stack.is(Items.MACE) || stack.is(Items.TRIDENT)) {
            return false;
        }
        return stack.has(DataComponents.TOOL);
    }

    /** いきおい剣で振れる武器なら true を返す。 */
    public static boolean isWeapon(ItemStack stack) {
        // 何も持っていなければ武器ではない
        if (stack.isEmpty()) {
            return false;
        }
        Item item = stack.getItem();

        // 武器リストのファイルで決めてあれば、それにしたがう
        loadWeaponListIfNeeded();
        Identifier itemId = BuiltInRegistries.ITEM.getKey(item);
        if (isListed(removedWeapons, itemId)) {
            return false;
        }
        if (isListed(addedWeapons, itemId)) {
            return true;
        }

        // 剣・槍・トライデントならまちがいなく武器
        if (stack.is(ItemTags.SWORDS) || stack.is(ItemTags.SPEARS) || stack.is(Items.TRIDENT) || stack.is(Items.MACE)) {
            return true;
        }

        // 掘る道具は「道具」として別にあつかう。弓、左クリック技のある武器はふつうのまま（メイスは重い武器として振れる）
        if (isDiggingTool(stack) || item instanceof ProjectileWeaponItem) {
            return false;
        }
        if (hasLeftClickSkill(item)) {
            return false;
        }

        // 近接武器のタグがついていれば武器
        if (stack.is(MELEE_WEAPON_TAG)) {
            return true;
        }

        // タグがなくても、手に持つと攻撃力が上がる物は武器（槍・大鎌・刀などの追加武器）
        return hasAttackDamage(stack);
    }

    /** メインの手に持ったときに攻撃力が上がる物なら true を返す。 */
    private static boolean hasAttackDamage(ItemStack stack) {
        double[] addedDamage = {0.0};
        stack.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            if (attribute.is(Attributes.ATTACK_DAMAGE)) {
                addedDamage[0] += modifier.amount();
            }
        });
        return addedDamage[0] >= SwingConfig.MIN_WEAPON_DAMAGE;
    }

    /** カタリズムなどの「左クリックで技が出る武器」なら true を返す。 */
    private static boolean hasLeftClickSkill(Item item) {
        for (Class<?> itemInterface : item.getClass().getInterfaces()) {
            if (itemInterface.getSimpleName().equals("ILeftClick")) {
                return true;
            }
        }
        return false;
    }

    /** リストに「modid:item」か「modid:*」が入っていれば true を返す。 */
    private static boolean isListed(Set<String> list, Identifier itemId) {
        return list.contains(itemId.toString()) || list.contains(itemId.getNamespace() + ":*");
    }

    /** はじめて使うときに、config/ikioiken-weapons.txt を読む（なければ見本を作る）。 */
    private static void loadWeaponListIfNeeded() {
        if (addedWeapons != null) {
            return;
        }
        addedWeapons = new HashSet<>();
        removedWeapons = new HashSet<>();
        Path listPath = FabricLoader.getInstance().getConfigDir().resolve(WEAPON_LIST_FILE);
        try {
            // ファイルがなければ説明つきの見本を作る
            if (!Files.exists(listPath)) {
                Files.createDirectories(listPath.getParent());
                Files.writeString(listPath, sampleWeaponList(), StandardCharsets.UTF_8);
            }

            // 1 行ずつ読んで、+ は追加、- は外す
            List<String> lines = Files.readAllLines(listPath, StandardCharsets.UTF_8);
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.length() < 2 || trimmed.startsWith("#")) {
                    continue;
                }
                String itemId = trimmed.substring(1).trim().toLowerCase();
                if (trimmed.startsWith("+")) {
                    addedWeapons.add(itemId);
                } else if (trimmed.startsWith("-")) {
                    removedWeapons.add(itemId);
                }
            }
        } catch (IOException error) {
            System.out.println("[いきおい剣] 武器リストが読めませんでした: " + error.getMessage());
        }
    }

    /** 武器リストの見本の中身を返す。 */
    private static String sampleWeaponList() {
        return String.join("\n",
                "# いきおい剣の武器リスト",
                "# 剣・近接武器のタグがついた物と、持つと攻撃力が上がる物は、自動でいきおい剣で振れます。",
                "# 自動でうまくいかない武器だけ、ここに書いてください（書いたらマイクラを再起動）。",
                "#",
                "#   + modid:item_id   … この武器を振れるようにする",
                "#   - modid:item_id   … この武器はふつうの攻撃のままにする",
                "#   + modid:*         … その Mod の物を全部振れるようにする",
                "#   - modid:*         … その Mod の物は全部ふつうのまま",
                "#",
                "# 例:",
                "# + minecraft:trident",
                "# - cataclysm:meat_shredder",
                "");
    }

    /** 武器・道具の素材の重さ（1.0 が鉄くらい）。木や金は軽く、ダイヤやネザライトは重い。名前に素材が入っていないものは 1.0。 */
    public static float materialWeight(ItemStack stack) {
        if (stack.isEmpty()) {
            return 1.0f;
        }
        String itemName = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        if (itemName.startsWith("wooden_")) {
            return SwingConfig.WEIGHT_WOOD;
        } else if (itemName.startsWith("golden_")) {
            return SwingConfig.WEIGHT_GOLD;
        } else if (itemName.startsWith("stone_")) {
            return SwingConfig.WEIGHT_STONE;
        } else if (itemName.startsWith("copper_")) {
            return SwingConfig.WEIGHT_COPPER;
        } else if (itemName.startsWith("iron_")) {
            return SwingConfig.WEIGHT_IRON;
        } else if (itemName.startsWith("diamond_")) {
            return SwingConfig.WEIGHT_DIAMOND;
        } else if (itemName.startsWith("netherite_")) {
            return SwingConfig.WEIGHT_NETHERITE;
        }
        return 1.0f;
    }
}
