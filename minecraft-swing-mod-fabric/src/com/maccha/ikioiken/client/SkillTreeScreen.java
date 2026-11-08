package com.maccha.ikioiken.client;

import com.maccha.ikioiken.SkillTree;
import com.maccha.ikioiken.SkillUnlockPayload;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * スキルツリーの画面。自分の経験値レベルを使って、時間・斬撃の道を下へ進めて覚える。
 */
public class SkillTreeScreen extends Screen {

    // 色（ARGB）
    private static final int COLOR_BACK_TOP = 0xFF14141F;
    private static final int COLOR_BACK_BOTTOM = 0xFF22303A;
    private static final int COLOR_EDGE = 0xFF4FB8D9;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_TEXT_DIM = 0xFFA8A8B8;
    private static final int COLOR_YELLOW = 0xFFFFD84A;
    private static final int COLOR_GREEN = 0xFF55FF55;
    private static final int COLOR_RED = 0xFFFF7777;
    private static final int[] BRANCH_COLORS = {0xFF55E6E6, 0xFFFF6655, 0xFF66EE77, 0xFFDDDD66};

    private static final int PANEL_WIDTH = 360;
    private static final int PANEL_HEIGHT = 220;
    private static final int NODE_WIDTH = 78;
    private static final int NODE_HEIGHT = 22;

    private int panelLeft;
    private int panelTop;
    private final Map<String, Button> nodeButtons = new HashMap<>();

    /** 画面を作る。 */
    public SkillTreeScreen() {
        super(Component.literal("スキルツリー"));
    }

    /** 画面を開いたとき、スキルごとのボタンを置く。 */
    @Override
    protected void init() {
        panelLeft = (width - PANEL_WIDTH) / 2;
        panelTop = (height - PANEL_HEIGHT) / 2;
        nodeButtons.clear();
        for (SkillTree.Node node : SkillTree.NODES) {
            int column = node.branch().ordinal();
            int x = panelLeft + (PANEL_WIDTH - (SkillTree.Branch.values().length * 87 - 9)) / 2 + column * 87;
            int y = panelTop + 50 + (node.tier() - 1) * 42;
            Button button = Button.builder(Component.literal(node.name()), pressed -> ClientPlayNetworking.send(new SkillUnlockPayload(node.id())))
                    .bounds(x, y, NODE_WIDTH, NODE_HEIGHT).build();
            nodeButtons.put(node.id(), button);
            addRenderableWidget(button);
        }
    }

    /** 武器を持ちかえたり、覚えたりした結果を、毎フレームボタンに反映する。 */
    private void refreshButtons(net.minecraft.world.entity.player.Player weapon) {
        for (SkillTree.Node node : SkillTree.NODES) {
            Button button = nodeButtons.get(node.id());
            boolean learned = SkillTree.unlockedIds(weapon).contains(node.id());
            boolean canLearn = SkillTree.whyNot(weapon, node) == null;
            String mark = learned ? "✔ " : canLearn ? "▶ " : "🔒 ";
            button.setMessage(Component.literal(mark + node.name()));
            button.active = canLearn;
        }
    }

    /** 背景と文字を描く。 */
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        net.minecraft.world.entity.player.Player weapon = minecraft != null ? minecraft.player : null;
        boolean hasWeapon = weapon != null;
        if (hasWeapon) {
            refreshButtons(weapon);
        }

        // 外側の板
        g.fill(panelLeft - 2, panelTop - 2, panelLeft + PANEL_WIDTH + 2, panelTop + PANEL_HEIGHT + 2, COLOR_EDGE);
        g.fillGradient(panelLeft, panelTop, panelLeft + PANEL_WIDTH, panelTop + PANEL_HEIGHT, COLOR_BACK_TOP, COLOR_BACK_BOTTOM);

        // タイトルとポイント
        g.text(font, "スキルツリー", panelLeft + 10, panelTop + 8, COLOR_YELLOW, true);
        if (hasWeapon) {
            g.text(font, "いまの経験値レベル " + weapon.experienceLevel, panelLeft + 10, panelTop + 22, COLOR_GREEN, false);
        }
        g.text(font, "覚えるには経験値レベルを使うよ(5・10・20)", panelLeft + 170, panelTop + 22, COLOR_TEXT_DIM, false);

        // 道の名前と、スキルをつなぐ線
        for (SkillTree.Branch branch : SkillTree.Branch.values()) {
            int column = branch.ordinal();
            int x = panelLeft + (PANEL_WIDTH - (SkillTree.Branch.values().length * 87 - 9)) / 2 + column * 87;
            int color = BRANCH_COLORS[column];
            g.text(font, branch.label() + "の道", x + 4, panelTop + 38, color, true);
            for (int tier = 1; tier <= 2; tier++) {
                int lineY = panelTop + 50 + (tier - 1) * 42 + NODE_HEIGHT;
                g.fill(x + NODE_WIDTH / 2 - 1, lineY, x + NODE_WIDTH / 2 + 1, lineY + 20, color);
            }
        }

        // 説明(ポインターを合わせたスキルの)
        String help = "スキルにポインターを合わせると説明が出るよ。▶のスキルを押して覚えよう(武器ではなく自分に付くよ)。";
        int helpColor = COLOR_TEXT_DIM;
        String cost = "";
        for (SkillTree.Node node : SkillTree.NODES) {
            Button button = nodeButtons.get(node.id());
            if (button.isHoveredOrFocused()) {
                help = node.description();
                helpColor = COLOR_TEXT;
                cost = "必要な経験値レベル " + node.cost();
                if (hasWeapon) {
                    String why = SkillTree.whyNot(weapon, node);
                    if (why != null) {
                        cost += "   ← " + why;
                    }
                }
            }
        }
        g.textWithWordWrap(font, Component.literal(help), panelLeft + 12, panelTop + 188, PANEL_WIDTH - 24, helpColor, false);
        g.text(font, cost, panelLeft + 12, panelTop + 206, COLOR_YELLOW, false);
    }

    /** 画面を開いていてもゲームは止めない。 */
    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
