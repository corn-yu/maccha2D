package com.maccha.ikioiken;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * スキルツリー。プレイヤーが覚える特殊能力で、「時間」の道を下へ進むほど強くなる。
 * 覚えるには経験値レベルを使う。覚えたスキルはプレイヤー本人に記録され、死んでも消えない。
 */
public final class SkillTree {

    private SkillTree() {
    }

    /** 道の種類。 */
    public enum Branch {
        TIME("時間");

        private final String label;

        Branch(String label) {
            this.label = label;
        }

        /** 道の名前（画面に出す）。 */
        public String label() {
            return label;
        }
    }

    /** スキル 1 つ分（段が上がるほど強く、使う経験値レベルも多い）。 */
    public record Node(String id, Branch branch, int tier, String name, String description, int cost) {
    }

    public static final List<Node> NODES = new ArrayList<>();

    // 覚えたスキルをプレイヤーに記録する(ワールドに保存・死んでも残る・本人の画面にも届ける)
    public static final AttachmentType<List<String>> LEARNED = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath(IkioiKen.MOD_ID, "learned_skills"),
            builder -> builder.persistent(Codec.STRING.listOf())
                    .copyOnDeath()
                    .syncWith(ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), AttachmentSyncPredicate.targetOnly()));

    static {
        // 時間の道：スロー → 自分以外がスロー → 時間停止
        add("time1", Branch.TIME, 1, "ゆっくり時間", "武器を速く振っている間、近く(8マス)の敵が少し遅くなる");
        add("time2", Branch.TIME, 2, "自分以外スロー", "武器を速く振っている間、自分以外の時間がぐっと遅くなる(16マス)");
        add("time3", Branch.TIME, 3, "ザ・ワールド", "武器を速く振っている間、自分以外の時間が止まる(20マス)");

    }

    /** スキルを登録する（使う経験値レベルは 5・10・20）。 */
    private static void add(String id, Branch branch, int tier, String name, String description) {
        NODES.add(new Node(id, branch, tier, name, description, tier == 1 ? 5 : tier == 2 ? 10 : 20));
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

    /** プレイヤーが覚えているスキルの名前一覧を返す。 */
    public static Set<String> unlockedIds(Player player) {
        List<String> saved = ((net.fabricmc.fabric.api.attachment.v1.AttachmentTarget) player).getAttached(LEARNED);
        return saved == null ? new LinkedHashSet<>() : new LinkedHashSet<>(saved);
    }

    /** その道で、どこまで覚えたか（0〜3）を返す。 */
    public static int tierOf(Player player, Branch branch) {
        Set<String> ids = unlockedIds(player);
        int best = 0;
        for (Node node : NODES) {
            if (node.branch() == branch && ids.contains(node.id())) {
                best = Math.max(best, node.tier());
            }
        }
        return best;
    }

    /** 覚えられない理由を返す(覚えられるなら null)。 */
    public static String whyNot(Player player, Node node) {
        if (unlockedIds(player).contains(node.id())) {
            return "もう覚えているよ";
        }
        if (node.tier() > 1 && tierOf(player, node.branch()) < node.tier() - 1) {
            return "ひとつ上のスキルを先に覚えてね";
        }
        if (!player.getAbilities().instabuild && player.experienceLevel < node.cost()) {
            return "経験値レベルが足りないよ(あと " + (node.cost() - player.experienceLevel) + " レベル)";
        }
        return null;
    }

    /** スキルを覚えて、経験値レベルを使う(だめな理由があれば何もしない)。 */
    public static boolean unlock(ServerPlayer player, Node node) {
        if (whyNot(player, node) != null) {
            return false;
        }
        if (!player.getAbilities().instabuild) {
            player.giveExperienceLevels(-node.cost());
        }
        Set<String> ids = unlockedIds(player);
        ids.add(node.id());
        ((net.fabricmc.fabric.api.attachment.v1.AttachmentTarget) player).setAttached(LEARNED, new ArrayList<>(ids));
        return true;
    }
}
