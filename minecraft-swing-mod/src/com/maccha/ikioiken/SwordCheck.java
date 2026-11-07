package com.maccha.ikioiken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.SwordItem;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.Tags;

/**
 * 持っている物が「いきおい剣で振れる武器」かどうかを調べるクラス（武器追加 Mod の武器にも対応）。
 */
public final class SwordCheck {

    private SwordCheck() {
    }

    // 武器リストのファイル名（config フォルダの中）
    private static final String WEAPON_LIST_FILE = "ikioiken-weapons.txt";

    // ファイルで「振れる」にした物・「ふつうのまま」にした物（"modid:item" か "modid:*"）
    private static Set<String> addedWeapons = null;
    private static Set<String> removedWeapons = null;

    /** いきおい剣で振れる武器なら true を返す。 */
    public static boolean isSword(ItemStack stack) {
        // 何も持っていなければ武器ではない
        if (stack.isEmpty()) {
            return false;
        }
        Item item = stack.getItem();

        // 武器リストのファイルで決めてあれば、それにしたがう
        loadWeaponListIfNeeded();
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
        if (isListed(removedWeapons, itemId)) {
            return false;
        }
        if (isListed(addedWeapons, itemId)) {
            return true;
        }

        // 剣のクラスならまちがいなく武器
        if (item instanceof SwordItem) {
            return true;
        }

        // 掘る道具（つるはし・斧・シャベル・クワ・ハンマー）、弓、メイス、左クリック技のある武器はふつうのまま
        if (item instanceof DiggerItem || item instanceof ProjectileWeaponItem || item instanceof MaceItem) {
            return false;
        }
        if (hasLeftClickSkill(item)) {
            return false;
        }

        // 剣のタグ・近接武器のタグがついていれば武器
        if (stack.is(ItemTags.SWORDS) || stack.is(Tags.Items.MELEE_WEAPON_TOOLS)) {
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
    private static boolean isListed(Set<String> list, ResourceLocation itemId) {
        return list.contains(itemId.toString()) || list.contains(itemId.getNamespace() + ":*");
    }

    /** はじめて使うときに、config/ikioiken-weapons.txt を読む（なければ見本を作る）。 */
    private static void loadWeaponListIfNeeded() {
        if (addedWeapons != null) {
            return;
        }
        addedWeapons = new HashSet<>();
        removedWeapons = new HashSet<>();
        Path listPath = FMLPaths.CONFIGDIR.get().resolve(WEAPON_LIST_FILE);
        try {
            // ファイルがなければ説明つきの見本を作る
            if (!Files.exists(listPath)) {
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
}
