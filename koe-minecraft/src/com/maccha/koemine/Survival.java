package com.maccha.koemine;

import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.CraftingMenu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.util.context.ContextMap;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * サバイバルの「名前で言う」動き（クラフト・焼く・持つ・チェスト）をする
 */
public class Survival {

    /** 名前と、そのもの（アイテム・ブロック） */
    record Named<T>(String key, String raw, T thing) {}

    final KoeMineMod mod;
    final List<Named<Item>> items = new ArrayList<>();
    boolean namesReady = false;

    // 今しているしごと（null ならなし）
    String job = null;
    int jobTicks = 0;
    RecipeDisplayEntry craftRecipe = null;
    boolean craftSmelt = false;
    String craftName = "";

    /** 作る */
    public Survival(KoeMineMod mod) {
        this.mod = mod;
    }

    /** ゲームの言葉（日本語）で、アイテムの名前の一覧を作る */
    List<String> buildNames() {
        items.clear();
        Set<String> raws = new LinkedHashSet<>();
        // アイテム
        for (Item item : BuiltInRegistries.ITEM) {
            String raw = new ItemStack(item).getHoverName().getString();
            items.add(new Named<>(CommandTable.normalize(raw), raw, item));
            raws.add(raw);
        }
        namesReady = true;
        // 空白や記号の入った名前は、聞く言葉にしない
        List<String> phrases = new ArrayList<>();
        for (String raw : raws) {
            String clean = raw.replace(" ", "").replace("　", "");
            if (!clean.isEmpty() && clean.length() <= 12 && !clean.contains(".")) phrases.add(clean);
        }
        return phrases;
    }

    /** 名前の一覧から、聞こえた名前にいちばん合うものをさがす */
    static <T> T findByName(List<Named<T>> list, String heard) {
        if (heard.isEmpty()) return null;
        // ぴったり同じ名前
        for (Named<T> named : list) if (named.key().equals(heard)) return named.thing();
        // 聞こえた文の中にある、いちばん長い名前
        Named<T> best = null;
        for (Named<T> named : list) {
            if (named.key().length() >= 2 && heard.contains(named.key()) && (best == null || named.key().length() > best.key().length())) best = named;
        }
        if (best != null) return best.thing();
        // 聞こえた名前を ふくむ、いちばん短い名前
        for (Named<T> named : list) {
            if (named.key().contains(heard) && (best == null || named.key().length() < best.key().length())) best = named;
        }
        return best == null ? null : best.thing();
    }

    /** あだ名（*木 = _log など）なら、英語の名前の一部を返す。聞きまちがい（木ざー → 木材）も読みでさがす */
    String nickname(String heard) {
        for (var entry : mod.table.nicknames.entrySet()) {
            if (CommandTable.normalize(entry.getKey()).equals(heard)) return entry.getValue();
        }
        if (heard.length() < 2 || findByName(items, heard) != null) return null;
        // 読みが1文字ちがいまでの あだ名をさがす
        Yomi yomi = Yomi.get();
        List<String> heardReadings = yomi.readings(heard, 64);
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (var entry : mod.table.nicknames.entrySet()) {
            String target = yomi.reading(CommandTable.normalize(entry.getKey()));
            if (target.length() < 3) continue;
            for (String reading : heardReadings) {
                int distance = Yomi.distance(reading, target);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = entry.getValue();
                }
            }
        }
        return bestDistance <= 1 ? best : null;
    }

    /** 英語の名前（minecraft:oak_log の oak_log）を返す */
    static String idOf(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }

    // ---------------- しごとの始まり ----------------

    /** 「〇〇つくって」：レシピ本を使ってクラフトする（かまどが開いていれば焼く） */
    void startCraft(Minecraft minecraft, LocalPlayer player, String heard, boolean smelt) {
        // あだ名（「木材」→ 〜_planks など）なら、その仲間のどれか（今の材料で作れるもの）を作る
        String nick = nickname(heard);
        Item item = nick == null ? findItem(heard) : null;
        if (item == null && nick == null) {
            say(player, "「" + heard + "」というアイテムが見つからないよ");
            return;
        }
        // かまどが開いていたら、焼くレシピにする
        boolean furnaceOpen = player.containerMenu instanceof AbstractFurnaceMenu;
        java.util.function.Predicate<Item> wanted = item != null ? (made -> made == item) : (made -> idOf(made).contains(nick));
        RecipeDisplayEntry recipe = findRecipe(minecraft, player, wanted, smelt || furnaceOpen);
        String name = item != null ? new ItemStack(item).getHoverName().getString() : heard;
        if (item == null && recipe != null) {
            ContextMap context = SlotDisplayContext.fromLevel(minecraft.level);
            for (ItemStack stack : recipe.resultItems(context)) if (!stack.isEmpty()) name = stack.getHoverName().getString();
        }
        if (recipe == null) {
            say(player, name + "のレシピをまだ知らないよ（材料を一度手に入れると おぼえるよ）");
            return;
        }
        craftRecipe = recipe;
        craftSmelt = smelt || furnaceOpen;
        craftName = name;
        jobTicks = 0;
        // 焼くならかまど、3x3なら作業台、それ以外は手もとの2x2で作る
        // （作業台やかまどは、自分で前に立って「開けて」で開く。自動で向いたりはしない）
        if (craftSmelt && !furnaceOpen) {
            say(player, "かまどの前で「開けて」と言って、かまどを開いてから言ってね");
            job = null;
        } else if (craftSmelt) {
            job = "craft_place";
        } else if (gridNeed(recipe.display()) > 2 && !(player.containerMenu instanceof CraftingMenu)) {
            // 3x3 は作業台だけ
            say(player, name + "は作業台がいるよ。作業台の前で「開けて」と言って、開いてから言ってね");
            job = null;
        } else if (!(player.containerMenu instanceof CraftingMenu) && !(minecraft.gui.screen() instanceof InventoryScreen)) {
            // 2x2 は、インベントリか作業台を開いているときだけ（何も開いていないと作れない）
            say(player, name + "を作るときは、「インベントリ」で開くか、作業台を「開けて」から言ってね");
            job = null;
        } else {
            job = "craft_place";
        }
    }

    /** 「〇〇を持って」：ホットバーか持ち物から、そのアイテムを手に持つ */
    void hold(Minecraft minecraft, LocalPlayer player, String heard) {
        Item item = findItem(heard);
        String nick = nickname(heard);
        if (item == null && nick == null) {
            say(player, "「" + heard + "」というアイテムが見つからないよ");
            return;
        }
        int slot = findInInventory(player.getInventory(), stack -> item != null ? stack.getItem() == item : idOf(stack.getItem()).contains(nick));
        selectSlot(minecraft, player, slot, heard);
    }

    /** 「剣」「たべもの」など、種類で道具をえらぶ */
    void holdKind(Minecraft minecraft, LocalPlayer player, String kind) {
        int slot = findInInventory(player.getInventory(), stack -> switch (kind) {
            case "food" -> stack.has(DataComponents.FOOD);
            case "block" -> stack.getItem() instanceof BlockItem;
            case "bow" -> idOf(stack.getItem()).equals("bow");
            default -> idOf(stack.getItem()).contains(kind);
        });
        selectSlot(minecraft, player, slot, kind);
    }

    /** 「足元に置いて」：真下を見て、ジャンプして、足もとにブロックを置く */
    void startPillar(LocalPlayer player) {
        player.setXRot(90f);
        mod.pendingPitch = 0f;
        mod.jumpTicks = 3;
        job = "pillar";
        jobTicks = 0;
    }

    /** 「ジャンプして置いて」「ジャンプして攻撃」：ジャンプして、ちょうどいいときに置く・たたく（向きは変えない） */
    void startJumpCombo(LocalPlayer player, String kind) {
        // もうとんでいるとき（「ジャンプ」が先に聞こえたとき）は、もう一度はとばない
        if (player.onGround()) mod.jumpTicks = 3;
        job = kind;
        jobTicks = 0;
    }

    /** チェスト・かまどなどで、自分の持ち物を全部入れる（put=true）か、全部取る */
    void moveAll(Minecraft minecraft, LocalPlayer player, boolean put) {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == player.inventoryMenu) {
            say(player, "チェストやかまどを開いてから言ってね");
            return;
        }
        int moved = 0;
        for (Slot slot : menu.slots) {
            boolean mine = slot.container instanceof Inventory;
            if (slot.hasItem() && mine == put) {
                minecraft.gameMode.handleContainerInput(menu.containerId, slot.index, 0, ContainerInput.QUICK_MOVE, player);
                moved++;
            }
        }
        say(player, moved + "こ動かしたよ");
    }

    /** できあがったもの（作業台・かまどの右の場所）を取る */
    void takeResult(Minecraft minecraft, LocalPlayer player) {
        AbstractContainerMenu menu = player.containerMenu;
        int index = -1;
        if (menu instanceof AbstractCraftingMenu crafting) index = crafting.getResultSlot().index;
        if (menu instanceof AbstractFurnaceMenu) index = 2;
        if (index < 0 || !menu.getSlot(index).hasItem()) {
            say(player, "まだ何もできていないよ");
            return;
        }
        minecraft.gameMode.handleContainerInput(menu.containerId, index, 0, ContainerInput.QUICK_MOVE, player);
    }

    /** しごとをやめる */
    void cancel() {
        job = null;
    }

    // ---------------- 毎tick ----------------

    /** 毎tick：今のしごとを少しずつ進める */
    void tick(Minecraft minecraft, LocalPlayer player) {
        if (job == null) return;
        jobTicks++;
        switch (job) {
            case "pillar" -> {
                // ジャンプのいちばん上で、足もとに置く
                if (jobTicks == 6) {
                    mod.click(minecraft.options.keyUse);
                    job = null;
                }
            }
            case "jump_use" -> {
                // いちばん高いところ（上がるのが止まったとき）で置く
                if ((jobTicks >= 3 || !player.onGround()) && (player.getDeltaMovement().y <= 0.0 || jobTicks >= 8)) {
                    mod.click(minecraft.options.keyUse);
                    job = null;
                }
            }
            case "jump_attack" -> {
                // 落ちはじめたら たたく（クリティカルになる）
                if ((player.getDeltaMovement().y < -0.08 && !player.onGround()) || jobTicks >= 12) {
                    mod.click(minecraft.options.keyAttack);
                    job = null;
                }
            }
            case "craft_place" -> tickPlace(minecraft, player);
            case "craft_take" -> tickTake(minecraft, player);
            default -> job = null;
        }
    }

    /** レシピの材料を置く */
    void tickPlace(Minecraft minecraft, LocalPlayer player) {
        AbstractContainerMenu menu = player.containerMenu;
        minecraft.gameMode.handlePlaceRecipe(menu.containerId, craftRecipe.id(), craftSmelt);
        if (craftSmelt) {
            say(player, craftName + "を焼きはじめたよ。できたら「できたのとって」");
            job = null;
            return;
        }
        job = "craft_take";
        jobTicks = 0;
    }

    /** できたものを取って、あまった材料を持ち物にもどす */
    void tickTake(Minecraft minecraft, LocalPlayer player) {
        if (jobTicks < 3) return;
        AbstractContainerMenu menu = player.containerMenu;
        if (!(menu instanceof AbstractCraftingMenu crafting)) {
            job = null;
            return;
        }
        Slot result = crafting.getResultSlot();
        if (result.hasItem()) {
            minecraft.gameMode.handleContainerInput(menu.containerId, result.index, 0, ContainerInput.QUICK_MOVE, player);
            say(player, craftName + "を作ったよ！");
        } else {
            say(player, craftName + "の材料がたりないよ");
        }
        // あまった材料を持ち物にもどす
        for (Slot slot : crafting.getInputGridSlots()) {
            if (slot.hasItem()) minecraft.gameMode.handleContainerInput(menu.containerId, slot.index, 0, ContainerInput.QUICK_MOVE, player);
        }
        job = null;
    }

    // ---------------- さがす・むく ----------------

    /** 名前からアイテムをさがす（あだ名もつかう） */
    Item findItem(String heard) {
        Item item = findByName(items, heard);
        if (item != null) return item;
        String nick = nickname(heard);
        if (nick == null) return null;
        for (Named<Item> named : items) if (idOf(named.thing()).contains(nick)) return named.thing();
        return null;
    }

    /** 持ち物の中で、条件に合うものの場所（ホットバーを先に見る。なければ -1） */
    static int findInInventory(Inventory inventory, java.util.function.Predicate<ItemStack> test) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && test.test(stack)) return slot;
        }
        return -1;
    }

    /** その場所のアイテムを手に持つ（ホットバーの外なら、今のホットバーと入れかえる） */
    void selectSlot(Minecraft minecraft, LocalPlayer player, int slot, String heard) {
        if (slot < 0) {
            say(player, "「" + heard + "」を持っていないよ");
            return;
        }
        Inventory inventory = player.getInventory();
        if (slot < 9) {
            inventory.setSelectedSlot(slot);
            return;
        }
        // 持ち物の画面の番号は、持ち物の 9〜35 がそのまま 9〜35
        minecraft.gameMode.handleContainerInput(player.inventoryMenu.containerId, slot, inventory.getSelectedSlot(), ContainerInput.SWAP, player);
    }

    /** その場所の方を向く */
    static void aimAt(LocalPlayer player, Vec3 target) {
        Vec3 eye = player.getEyePosition();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));
        player.setYRot(yaw);
        player.setXRot(Math.max(-90f, Math.min(90f, pitch)));
    }

    /** レシピの大きさ（2 なら手もと、3 なら作業台） */
    static int gridNeed(RecipeDisplay display) {
        if (display instanceof ShapedCraftingRecipeDisplay shaped) return Math.max(shaped.width(), shaped.height()) > 2 ? 3 : 2;
        if (display instanceof ShapelessCraftingRecipeDisplay shapeless) return shapeless.ingredients().size() > 4 ? 3 : 2;
        return 3;
    }

    /** そのアイテムができるレシピを、知っているレシピからさがす（作れるものを先に） */
    RecipeDisplayEntry findRecipe(Minecraft minecraft, LocalPlayer player, java.util.function.Predicate<Item> wanted, boolean smelt) {
        ContextMap context = SlotDisplayContext.fromLevel(minecraft.level);
        StackedItemContents contents = new StackedItemContents();
        player.getInventory().fillStackedContents(contents);
        RecipeDisplayEntry fallback = null;
        for (RecipeCollection collection : player.getRecipeBook().getCollections()) {
            for (RecipeDisplayEntry entry : collection.getRecipes()) {
                RecipeDisplay display = entry.display();
                boolean isSmelt = display instanceof FurnaceRecipeDisplay;
                boolean isCraft = display instanceof ShapedCraftingRecipeDisplay || display instanceof ShapelessCraftingRecipeDisplay;
                if (smelt ? !isSmelt : !isCraft) continue;
                boolean makesItem = false;
                for (ItemStack stack : entry.resultItems(context)) if (!stack.isEmpty() && wanted.test(stack.getItem())) makesItem = true;
                if (!makesItem) continue;
                if (entry.canCraft(contents)) return entry;
                if (fallback == null) fallback = entry;
            }
        }
        return fallback;
    }

    /** 画面の下に、お知らせを出す */
    static void say(LocalPlayer player, String text) {
        player.sendSystemMessage(Component.literal("§eこえマイクラ：" + text));
    }
}
