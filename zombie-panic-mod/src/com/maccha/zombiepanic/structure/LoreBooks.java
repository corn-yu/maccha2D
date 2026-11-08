package com.maccha.zombiepanic.structure;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;

import java.util.ArrayList;
import java.util.List;

/**
 * 世界の真実が書かれた「研究日誌」（廃都市と研究所の書見台に置いてある）。
 */
public final class LoreBooks {

    // 廃都市に置く日誌の番号と、研究所に置く日誌の番号
    public static final int[] CITY_PAGES = {1, 2, 3, 4, 5, 6, 10};
    public static final int[] LAB_PAGES = {7, 8, 9};

    private static final String AUTHOR = "研究員ミドリ";

    private LoreBooks() {
    }

    /** 番号の日誌を本にする。 */
    public static ItemStack makeDiary(int number) {
        List<Filterable<Component>> pages = new ArrayList<>();
        for (String pageText : diaryPages(number)) {
            pages.add(Filterable.passThrough(Component.literal(pageText)));
        }
        WrittenBookContent content = new WrittenBookContent(Filterable.passThrough("研究日誌 その" + number), AUTHOR, 0, pages, true);
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, content);
        return book;
    }

    /** 日誌の中身（1ページずつ）。 */
    private static String[] diaryPages(int number) {
        return switch (number) {
            case 1 -> new String[]{
                    "研究日誌 その1\n\n私たちは エンドの おくで、弱りきった エンダードラゴンを 見つけた。",
                    "竜の血には、死んだものを 動かす 力がある。\n\n所長は それを「不死の薬」と よんだ。"};
            case 2 -> new String[]{
                    "研究日誌 その2\n\n竜を つかまえて、地上の 研究所へ 運んだ。",
                    "竜の血を 少しずつ とって 研究を つづける。\n\n竜は 日に日に 弱り、体が くさりはじめた。"};
            case 3 -> new String[]{
                    "研究日誌 その3\n\n竜の血を あたえた ゾンビは、お日さまの 光で 燃えなくなった。",
                    "所長は 大よろこび。\n\nでも 私は こわい。あのゾンビの 目は、竜と 同じ色を していた。"};
            case 4 -> new String[]{
                    "研究日誌 その4\n\n事故が おきた。\n血の タンクが こわれ、雨と いっしょに 世界中へ 流れだした。",
                    "死んだ 生き物が、つぎつぎと ゾンビに なって 起き上がる。\n\n町は ひと晩で しずかに なった。"};
            case 5 -> new String[]{
                    "研究日誌 その5\n\n動物も、村人も、ネザーの 者たちも、エンダーマンさえも……\nみんな ゾンビに なった。",
                    "生きているものは もう ほとんど いない。\n\nこれは 病気じゃない。「呪い」だ。"};
            case 6 -> new String[]{
                    "研究日誌 その6\n\n呪いの もとは 竜そのものだ。",
                    "竜は くさりきって「ゾンビドラゴン」に なり、エンドへ 帰っていった。\n\n竜が 生きているかぎり、呪いは とけない。"};
            case 7 -> new String[]{
                    "研究日誌 その7\n\nゾンビの 体から とれる 黒い鉄を「呪鉄（じゅてつ）」と よぶことにした。",
                    "呪鉄の 剣や おのは、ゾンビに よく効く（ふつうの 1.5倍）。\n\n呪いの 力を、呪いに ぶつけるのだ。"};
            case 8 -> new String[]{
                    "研究日誌 その8\n\nエンドへの 門は、地下深くの「要塞」に ある。竜を 運んだ 古い 門だ。",
                    "門を ひらくには、わくに エンダーアイを はめる。\n\nエンダーアイは 強いゾンビや ボス、町の あとの 箱に のこっている はずだ。要塞の 場所は エンダーアイが 教えてくれる。"};
            case 9 -> new String[]{
                    "研究日誌 その9\n\n私も もうすぐ ゾンビに なる。\nこれを 読んだ だれかへ。",
                    "エンドへ 行き、ゾンビドラゴンを たおしてくれ。\n\nそうすれば 世界の 呪いは とける。\n\n……たのんだよ。"};
            default -> new String[]{
                    "研究日誌 その10（追記）\n\n強いゾンビほど、竜の血を 多く 飲んでいる。",
                    "百体に 一体は 強い力を、千体に 一体の「災厄」は とんでもない力を 持つ。\n\n近づくな。……いや、たおせるなら たからが 手に入るだろう。"};
        };
    }
}
