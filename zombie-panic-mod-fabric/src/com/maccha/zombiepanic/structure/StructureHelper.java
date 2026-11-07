package com.maccha.zombiepanic.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * 構造物づくりで使う、小さな道具あつめ。
 */
public final class StructureHelper {

    // 宝箱の中身（loot_table のファイル）
    public static final ResourceKey<LootTable> CITY_LOOT = ResourceKey.create(Registries.LOOT_TABLE,
            Identifier.fromNamespaceAndPath("zombiepanic", "chests/ruined_city"));
    public static final ResourceKey<LootTable> LAB_LOOT = ResourceKey.create(Registries.LOOT_TABLE,
            Identifier.fromNamespaceAndPath("zombiepanic", "chests/research_lab"));

    private StructureHelper() {
    }

    /** その場所の地面の高さ（いちばん上のブロックの上）。 */
    public static int surfaceY(Structure.GenerationContext context, int x, int z) {
        return context.chunkGenerator().getFirstOccupiedHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG,
                context.heightAccessor(), context.randomState());
    }

    /** その場所が 水の上（海・川・湖）かどうか。 */
    public static boolean isWater(Structure.GenerationContext context, int x, int z) {
        int floorY = context.chunkGenerator().getFirstOccupiedHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG,
                context.heightAccessor(), context.randomState());
        return floorY < surfaceY(context, x, z);
    }

    /** 場所と seed から、いつも同じになる 0〜1 の数を作る（チャンクをまたいでも同じ形にするため）。 */
    public static float hash01(long seed, int x, int y, int z) {
        long h = seed;
        h ^= x * 0x9E3779B97F4A7C15L;
        h ^= y * 0xC2B2AE3D27D4EB4FL;
        h ^= z * 0x165667B19E3779F9L;
        h = (h ^ (h >>> 31)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 29)) * 0x94D049BB133111EBL;
        h ^= h >>> 32;
        return (float) ((h >>> 40) & 0xFFFFFF) / (float) 0x1000000;
    }

    /** 書見台を置いて、日誌をのせる（そのチャンクの中のときだけ）。 */
    public static void placeLecternWithDiary(WorldGenLevel level, BoundingBox chunkBox, BlockPos pos, int diaryNumber,
                                             net.minecraft.core.Direction facing) {
        if (!chunkBox.isInside(pos)) {
            return;
        }
        BlockState lectern = Blocks.LECTERN.defaultBlockState()
                .setValue(LecternBlock.FACING, facing)
                .setValue(LecternBlock.HAS_BOOK, true);
        level.setBlock(pos, lectern, 2);
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof LecternBlockEntity lecternEntity) {
            ItemStack diary = LoreBooks.makeDiary(diaryNumber);
            lecternEntity.setBook(diary);
        }
    }
}
