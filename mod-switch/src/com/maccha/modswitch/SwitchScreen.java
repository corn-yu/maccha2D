package com.maccha.modswitch;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * MODスイッチの画面：mods フォルダの MOD を ボタンで オン・オフする
 */
public class SwitchScreen extends Screen {

    // 1行の高さと、ボタンのはば
    static final int ROW = 22;
    static final int ROW_WIDTH = 300;

    final Screen previous;
    List<ModFiles.Entry> entries;
    int page = 0;

    /** 前の画面（Mod Menu）をおぼえておく */
    public SwitchScreen(Screen previous) {
        super(Component.literal("MODスイッチ"));
        this.previous = previous;
    }

    /** ボタンを並べる（ページごと） */
    @Override
    protected void init() {
        if (entries == null) entries = ModFiles.scan();
        int left = width / 2 - ROW_WIDTH / 2;

        // 上の説明
        addRenderableWidget(new StringWidget(0, 8, width, 12, Component.literal("§lMODスイッチ"), font));
        long changed = entries.stream().filter(ModFiles.Entry::changed).count();
        String note = changed > 0
                ? "§e" + changed + " こ変えました。Minecraft を再起動すると反映されます"
                : "§7押すとオン・オフが切りかわります（次の起動から）";
        addRenderableWidget(new StringWidget(0, 22, width, 12, Component.literal(note), font));

        // MOD のボタン（入るぶんだけ、ページに分ける）
        int perPage = Math.max(1, (height - 40 - 40) / ROW);
        int pages = Math.max(1, (entries.size() + perPage - 1) / perPage);
        page = Math.min(page, pages - 1);
        for (int row = 0; row < perPage; row++) {
            int index = page * perPage + row;
            if (index >= entries.size()) break;
            ModFiles.Entry entry = entries.get(index);
            Button button = Button.builder(label(entry), press -> {
                        ModFiles.toggle(entry);
                        entries = ModFiles.scan();
                        rebuildWidgets();
                    })
                    .pos(left, 40 + row * ROW).size(ROW_WIDTH, 20).build();
            button.setTooltip(Tooltip.create(Component.literal(tooltip(entry))));
            if (entry.self) button.active = false;
            addRenderableWidget(button);
        }

        // 下のボタン：まえ・つぎ・もどる
        Button back = Button.builder(Component.literal("◀"), press -> { page--; rebuildWidgets(); })
                .pos(width / 2 - 154, height - 28).size(36, 20).build();
        back.active = page > 0;
        addRenderableWidget(back);
        addRenderableWidget(new StringWidget(width / 2 - 116, height - 28, 76, 20,
                Component.literal((page + 1) + " / " + pages), font));
        Button next = Button.builder(Component.literal("▶"), press -> { page++; rebuildWidgets(); })
                .pos(width / 2 - 40, height - 28).size(36, 20).build();
        next.active = page < pages - 1;
        addRenderableWidget(next);
        addRenderableWidget(Button.builder(Component.literal("もどる"), press -> onClose())
                .pos(width / 2 + 4, height - 28).size(150, 20).build());
    }

    /** ボタンの文字：オン・オフと名前（変えたものは「再起動で」をつける） */
    static Component label(ModFiles.Entry entry) {
        String state = entry.onNextStart() ? "§a● オン" : "§c○ オフ";
        String after = entry.changed() ? " §e(再起動で)" : "";
        String self = entry.self ? " §7(このMOD)" : "";
        return Component.literal(state + "§r  " + entry.name + after + self);
    }

    /** ボタンにマウスをのせたときの説明 */
    static String tooltip(ModFiles.Entry entry) {
        String text = entry.file.getFileName().toString().replace(ModFiles.OFF, "");
        if (entry.self) text += "\nMODスイッチ自身はオフにできません";
        if (entry.name.equals("Fabric API")) text += "\nほとんどの MOD が使っています。オフにすると 起動できないことがあります";
        if (entry.offWhenClosed) text += "\nMinecraft を閉じたあとにオフになります";
        return text;
    }

    /** もどるときは Mod Menu の画面へ */
    @Override
    public void onClose() {
        minecraft.gui.setScreen(previous);
    }
}
