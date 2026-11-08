package com.maccha.ikioiken;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 武器合体台のルール（合体とレベルアップの結果と、かかる代金を決める）。画面や持ち物には触らない。
 */
public final class ForgeRules {

    private ForgeRules() {
    }

    /** 結果の見本（できる物、経験値レベルの代金、材料の数、プレイヤーに見せる一言）。 */
    public record Preview(ItemStack result, int xpCost, int materialCount, boolean fusion, String message) {
        /** 受けとれる結果があれば true。 */
        public boolean ready() {
            return !result.isEmpty();
        }
    }

    /** 結果なしの見本を作る。 */
    private static Preview none(String message) {
        return new Preview(ItemStack.EMPTY, 0, 0, false, message);
    }

    /** 左の枠の武器と右の枠の物から、合体かレベルアップの結果を調べる。 */
    public static Preview preview(ItemStack weapon, ItemStack second) {
        // 左の枠が空か、能力をつけられない物なら始められない
        if (weapon.isEmpty() || !WeaponAbilities.canHaveAbilities(weapon)) {
            return none("左の枠に武器か道具を入れてね");
        }
        if (second.isEmpty()) {
            return none("右の枠に、合体させる武器か、レベルアップの材料を入れてね");
        }

        // 右の枠も武器なら合体、その武器の材料ならレベルアップ
        if (WeaponAbilities.canHaveAbilities(second)) {
            return previewFusion(weapon, second);
        }
        if (isMaterialFor(weapon, second)) {
            return previewLevelUp(weapon, second);
        }
        return none("この物は材料にならないよ（必要: " + materialLabel(weapon) + "）");
    }

    /** 合体の結果を調べる。左の武器が能力を引きつぎ、右の武器の能力が足される。 */
    private static Preview previewFusion(ItemStack weapon, ItemStack other) {
        List<WeaponAbilities.Entry> merged = new ArrayList<>(WeaponAbilities.abilitiesOf(weapon));
        List<WeaponAbilities.Entry> otherEntries = WeaponAbilities.abilitiesOf(other);
        if (otherEntries.isEmpty()) {
            return none("右の武器に能力がないよ");
        }

        boolean changed = false;
        for (WeaponAbilities.Entry added : otherEntries) {
            int foundIndex = -1;
            for (int index = 0; index < merged.size(); index++) {
                if (merged.get(index).ability().id().equals(added.ability().id())) {
                    foundIndex = index;
                }
            }
            if (foundIndex >= 0) {
                // 同じ能力は強さが 1 上がる
                WeaponAbilities.Entry current = merged.get(foundIndex);
                int newLevel = Math.min(WeaponAbilities.MAX_ABILITY_LEVEL, Math.max(current.level(), added.level()) + 1);
                if (newLevel > current.level()) {
                    merged.set(foundIndex, new WeaponAbilities.Entry(current.ability(), newLevel));
                    changed = true;
                }
            } else if (merged.size() < WeaponAbilities.MAX_ABILITIES) {
                merged.add(added);
                changed = true;
            }
        }
        if (!changed) {
            return none("合体しても変わらないよ（能力がいっぱいか、もう最強）");
        }

        ItemStack result = weapon.copy();
        result.setCount(1);
        WeaponAbilities.write(result, merged, WeaponAbilities.weaponLevel(weapon));
        repairLikeAnvilFusion(result, other);

        // 代金：能力 1 つにつき、2 + 合体後のその能力の強さ（強い能力ほど高い）
        int cost = 2;
        for (WeaponAbilities.Entry added : otherEntries) {
            int resultLevel = 1;
            for (WeaponAbilities.Entry entry : merged) {
                if (entry.ability().id().equals(added.ability().id())) {
                    resultLevel = entry.level();
                }
            }
            cost += 2 + resultLevel;
        }
        return new Preview(result, cost, 0, true, "合体できるよ！ 耐久は金床と同じだけ回復。経験値レベル " + cost + " 必要");
    }

    /** レベルアップの結果を調べる。経験値レベルと、武器の材料を払う。 */
    private static Preview previewLevelUp(ItemStack weapon, ItemStack material) {
        int level = WeaponAbilities.weaponLevel(weapon);
        // 材料の数は 1 + レベル÷2（ただし 1 スタックの 64 個まで）
        int need = Math.min(64, 1 + level / 2);
        int cost = 3 + level * 2;
        if (material.getCount() < need) {
            return none("材料が足りないよ（" + materialLabel(weapon) + " を " + need + " 個）");
        }

        ItemStack result = weapon.copy();
        result.setCount(1);
        WeaponAbilities.write(result, WeaponAbilities.abilitiesOf(weapon), level + 1);
        repairLikeAnvilMaterial(result, need);
        return new Preview(result, cost, need, false,
                "Lv" + level + " → Lv" + (level + 1) + "！ 耐久は金床と同じだけ回復。経験値レベル " + cost + " と " + materialLabel(weapon) + " " + need + " 個");
    }

    /** 金床で二つを合わせるときと同じ回復：二つの残りの耐久をたして、最大耐久の 12% をおまけする。 */
    private static void repairLikeAnvilFusion(ItemStack result, ItemStack other) {
        if (!result.isDamageableItem() || !other.isDamageableItem()) {
            return;
        }
        int maxDurability = result.getMaxDamage();
        int remainingA = maxDurability - result.getDamageValue();
        int remainingB = other.getMaxDamage() - other.getDamageValue();
        int bonus = maxDurability * 12 / 100;
        int newRemaining = Math.min(maxDurability, remainingA + remainingB + bonus);
        result.setDamageValue(Math.max(0, maxDurability - newRemaining));
    }

    /** 金床で材料を使うときと同じ回復：材料 1 個につき、最大耐久の 25% ずつ回復する。 */
    private static void repairLikeAnvilMaterial(ItemStack result, int materialCount) {
        if (!result.isDamageableItem()) {
            return;
        }
        int perMaterial = result.getMaxDamage() / 4;
        result.setDamageValue(Math.max(0, result.getDamageValue() - perMaterial * materialCount));
    }

    /** その物が、武器のレベルアップに使える材料なら true。 */
    public static boolean isMaterialFor(ItemStack weapon, ItemStack material) {
        String name = BuiltInRegistries.ITEM.getKey(weapon.getItem()).getPath();
        if (name.startsWith("wooden_")) {
            return material.is(ItemTags.PLANKS);
        }
        if (name.startsWith("stone_")) {
            return material.is(ItemTags.STONE_TOOL_MATERIALS);
        }
        if (name.startsWith("golden_")) {
            return material.is(Items.GOLD_INGOT);
        }
        if (name.startsWith("copper_")) {
            return material.is(Items.COPPER_INGOT);
        }
        if (name.startsWith("diamond_")) {
            return material.is(Items.DIAMOND);
        }
        if (name.startsWith("netherite_")) {
            return material.is(Items.NETHERITE_INGOT);
        }
        if (name.equals("mace")) {
            return material.is(Items.BREEZE_ROD);
        }
        if (name.equals("trident")) {
            return material.is(Items.PRISMARINE_CRYSTALS);
        }
        return material.is(Items.IRON_INGOT);
    }

    /** 武器のレベルアップに必要な材料の名前（プレイヤーに見せる文）。 */
    public static String materialLabel(ItemStack weapon) {
        String name = BuiltInRegistries.ITEM.getKey(weapon.getItem()).getPath();
        if (name.startsWith("wooden_")) {
            return "板材";
        }
        if (name.startsWith("stone_")) {
            return "丸石（石の道具の材料）";
        }
        if (name.startsWith("golden_")) {
            return "金インゴット";
        }
        if (name.startsWith("copper_")) {
            return "銅インゴット";
        }
        if (name.startsWith("diamond_")) {
            return "ダイヤモンド";
        }
        if (name.startsWith("netherite_")) {
            return "ネザライトインゴット";
        }
        if (name.equals("mace")) {
            return "ブレイズロッド（ブリーズロッド）";
        }
        if (name.equals("trident")) {
            return "プリズマリンクリスタル";
        }
        return "鉄インゴット";
    }
}
