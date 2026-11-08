package com.maccha.ikioiken;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * スキルツリー。武器ごとに「時間・力・命・風」の4本の道があり、下へ進むほど特殊能力が強くなる。
 * スキルポイントは武器レベルが1上がるごとに1点。覚えたスキルは武器の中に記録する。
 */
public final class SkillTree {

    private SkillTree() {
    }

    /** 道の種類。 */
    public enum Branch {
        TIME("時間"), POWER("力"), LIFE("命"), WIND("風");

        private final String label;

        Branch(String label) {
            this.label = label;
        }

        /** 道の名前（画面に出す）。 */
        public String label() {
            return label;
        }
    }

    /** スキル 1 つ分（段が上がるほど強く、ポイントも多くいる）。 */
    public record Node(String id, Branch branch, int tier, String name, String description, int cost) {
    }

    public static final List<Node> NODES = new ArrayList<>();

    static {
        // 時間の道：スロー → 自分以外がスロー → 時間停止
        add("time1", Branch.TIME, 1, "ゆっくり時間", "武器を速く振っている間、近く(8マス)の敵が少し遅くなる");
        add("time2", Branch.TIME, 2, "自分以外スロー", "武器を速く振っている間、自分以外の時間がぐっと遅くなる(16マス)");
        add("time3", Branch.TIME, 3, "ザ・ワールド", "武器を速く振っている間、自分以外の時間が止まる(20マス)");

        // 力の道：当てたときのダメージが上がる
        add("power1", Branch.POWER, 1, "力の目覚め", "当てたダメージが 1.2 倍");
        add("power2", Branch.POWER, 2, "剛力", "当てたダメージが 1.5 倍");
        add("power3", Branch.POWER, 3, "破壊神", "当てたダメージが 2 倍");

        // 命の道：振っている間、体が守られる
        add("life1", Branch.LIFE, 1, "ねばり強さ", "武器を速く振っている間、ダメージを少し減らす");
        add("life2", Branch.LIFE, 2, "鉄の心", "さらにダメージを減らし、体力がじわじわ回復する");
        add("life3", Branch.LIFE, 3, "不死身", "ダメージをぐっと減らし、回復が速くなり、黄色いハートがつく");

        // 風の道：振っている間、身軽になる
        add("wind1", Branch.WIND, 1, "軽い足", "武器を速く振っている間、足が速くなる");
        add("wind2", Branch.WIND, 2, "疾風", "足がもっと速くなり、高くジャンプできる");
        add("wind3", Branch.WIND, 3, "瞬歩", "足がとても速くなり、高く跳べて、落ちてもダメージなし");
    }

    /** スキルを登録する（段が上がるほどポイントが多い）。 */
    private static void add(String id, Branch branch, int tier, String name, String description) {
        NODES.add(new Node(id, branch, tier, name, description, tier));
    }

    /** 名前からスキルを探す（なければ null）。 */
    public static Node find(String id) {
        for (Node node : NODES) {
            if (node.id().equals(id)) {
                return node;
            }
        }
        return null;
    }

    /** 武器に記録されている、覚えたスキルの名前一覧を返す。 */
    public static Set<String> unlockedIds(ItemStack stack) {
        Set<String> ids = new LinkedHashSet<>();
        if (stack.isEmpty()) {
            return ids;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return ids;
        }
        ListTag list = data.copyTag().getCompoundOrEmpty(WeaponAbilities.ROOT).getListOrEmpty("sk");
        for (int index = 0; index < list.size(); index++) {
            ids.add(list.getStringOr(index, ""));
        }
        return ids;
    }

    /** その道で、どこまで覚えたか（0〜3）を返す。 */
    public static int tierOf(ItemStack stack, Branch branch) {
        Set<String> ids = unlockedIds(stack);
        int best = 0;
        for (Node node : NODES) {
            if (node.branch() == branch && ids.contains(node.id())) {
                best = Math.max(best, node.tier());
            }
        }
        return best;
    }

    /** 使ったポイントの合計。 */
    public static int spentPoints(ItemStack stack) {
        int total = 0;
        Set<String> ids = unlockedIds(stack);
        for (Node node : NODES) {
            if (ids.contains(node.id())) {
                total += node.cost();
            }
        }
        return total;
    }

    /** のこりのスキルポイント（武器レベル − 1 − 使った分）。 */
    public static int freePoints(ItemStack stack) {
        long total = (long) WeaponAbilities.weaponLevel(stack) - 1L - spentPoints(stack);
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, total));
    }

    /** 覚えられるかを調べて、だめな理由を返す(覚えられるなら null)。 */
    public static String whyNot(ItemStack stack, Node node) {
        if (!WeaponAbilities.canHaveAbilities(stack)) {
            return "手に武器を持ってね";
        }
        if (unlockedIds(stack).contains(node.id())) {
            return "もう覚えているよ";
        }
        if (node.tier() > 1 && tierOf(stack, node.branch()) < node.tier() - 1) {
            return "ひとつ上のスキルを先に覚えてね";
        }
        if (freePoints(stack) < node.cost()) {
            return "スキルポイントが足りないよ(あと " + (node.cost() - freePoints(stack)) + " 点)";
        }
        return null;
    }

    /** スキルを覚えて武器に記録する(だめな理由があれば何もしない)。 */
    public static boolean unlock(ItemStack stack, Node node) {
        if (whyNot(stack, node) != null) {
            return false;
        }
        Set<String> ids = unlockedIds(stack);
        ids.add(node.id());
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            CompoundTag root = tag.getCompoundOrEmpty(WeaponAbilities.ROOT);
            ListTag list = new ListTag();
            for (String id : ids) {
                list.add(StringTag.valueOf(id));
            }
            root.put("sk", list);
            tag.put(WeaponAbilities.ROOT, root);
        });
        return true;
    }

    /** 当てたダメージにかける倍率(力の道)。 */
    public static double damageMultiplier(ItemStack stack) {
        return switch (tierOf(stack, Branch.POWER)) {
            case 1 -> 1.2;
            case 2 -> 1.5;
            case 3 -> 2.0;
            default -> 1.0;
        };
    }
}
