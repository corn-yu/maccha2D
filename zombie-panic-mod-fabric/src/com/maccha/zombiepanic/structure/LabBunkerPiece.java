package com.maccha.zombiepanic.structure;

import com.maccha.zombiepanic.ModContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;

/**
 * 研究所の地下室（研究日誌・宝箱）と、地上へのたて穴。
 */
public class LabBunkerPiece extends StructurePiece {

    // 部屋の大きさ（まん中から はしまで）と高さ
    private static final int HALF_SIZE = 9;
    private static final int ROOM_HEIGHT = 6;

    private final int centerX;
    private final int centerZ;
    private final int surfaceY;
    private final int bunkerY;
    private final long seed;

    /** 新しく地下室を作る。 */
    public LabBunkerPiece(int centerX, int centerZ, int surfaceY, int bunkerY, long seed) {
        super(ModContent.LAB_BUNKER_PIECE, 0, new BoundingBox(centerX - HALF_SIZE, bunkerY - 1, centerZ - HALF_SIZE,
                centerX + HALF_SIZE, surfaceY, centerZ + HALF_SIZE));
        this.setOrientation(null);
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.surfaceY = surfaceY;
        this.bunkerY = bunkerY;
        this.seed = seed;
    }

    /** 保存してあった地下室を読む。 */
    public LabBunkerPiece(CompoundTag tag) {
        super(ModContent.LAB_BUNKER_PIECE, tag);
        this.setOrientation(null);
        this.centerX = tag.getIntOr("CX", 0);
        this.centerZ = tag.getIntOr("CZ", 0);
        this.surfaceY = tag.getIntOr("SY", 0);
        this.bunkerY = tag.getIntOr("BY", 0);
        this.seed = tag.getLongOr("Seed", 0L);
    }

    /** 地下室の情報を保存する。 */
    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
        tag.putInt("CX", centerX);
        tag.putInt("CZ", centerZ);
        tag.putInt("SY", surfaceY);
        tag.putInt("BY", bunkerY);
        tag.putLong("Seed", seed);
    }

    /** チャンクごとに呼ばれて、そのチャンクの中の部分を作る。 */
    @Override
    public void postProcess(WorldGenLevel level, StructureManager structureManager, ChunkGenerator generator,
                            RandomSource random, BoundingBox chunkBox, net.minecraft.world.level.ChunkPos chunkPos, BlockPos pivot) {
        buildRoom(level, chunkBox);
        buildShaft(level, chunkBox);
        placeDiariesAndChests(level, chunkBox, random);
    }

    /** かべ・ゆか・天井（あかり）と、中の空気。 */
    private void buildRoom(WorldGenLevel level, BoundingBox chunkBox) {
        int minX = centerX - HALF_SIZE;
        int maxX = centerX + HALF_SIZE;
        int minZ = centerZ - HALF_SIZE;
        int maxZ = centerZ + HALF_SIZE;
        int ceilingY = bunkerY + ROOM_HEIGHT;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                boolean isWall = x == minX || x == maxX || z == minZ || z == maxZ;
                // ゆか
                placeBlock(level, Blocks.POLISHED_DEEPSLATE.defaultBlockState(), x, bunkerY, z, chunkBox);
                // 天井（4ブロックごとに あかり）
                boolean light = !isWall && (x - centerX) % 4 == 0 && (z - centerZ) % 4 == 0;
                BlockState ceiling = light ? Blocks.SEA_LANTERN.defaultBlockState() : Blocks.DEEPSLATE_TILES.defaultBlockState();
                placeBlock(level, ceiling, x, ceilingY, z, chunkBox);
                // かべと中
                for (int y = bunkerY + 1; y < ceilingY; y++) {
                    BlockState state = isWall ? wallBlock(x, y, z) : Blocks.AIR.defaultBlockState();
                    placeBlock(level, state, x, y, z, chunkBox);
                }
            }
        }
    }

    /** 地上の建物から地下室へ おりる たて穴と はしご。 */
    private void buildShaft(WorldGenLevel level, BoundingBox chunkBox) {
        int shaftX = centerX + 4;
        int shaftZ = centerZ - 4;
        BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH);
        BlockState shaftWall = Blocks.DEEPSLATE_BRICKS.defaultBlockState();
        for (int y = bunkerY + 1; y <= surfaceY; y++) {
            // はしごを ささえる 北のかべ（いちばん下から上まで）
            placeBlock(level, shaftWall, shaftX, y, shaftZ - 1, chunkBox);
            // 天井より上は まわりもかべで かこむ
            if (y >= bunkerY + ROOM_HEIGHT && y < surfaceY) {
                placeBlock(level, shaftWall, shaftX - 1, y, shaftZ - 1, chunkBox);
                placeBlock(level, shaftWall, shaftX + 1, y, shaftZ - 1, chunkBox);
                placeBlock(level, shaftWall, shaftX - 1, y, shaftZ, chunkBox);
                placeBlock(level, shaftWall, shaftX + 1, y, shaftZ, chunkBox);
                placeBlock(level, shaftWall, shaftX - 1, y, shaftZ + 1, chunkBox);
                placeBlock(level, shaftWall, shaftX, y, shaftZ + 1, chunkBox);
                placeBlock(level, shaftWall, shaftX + 1, y, shaftZ + 1, chunkBox);
            }
            placeBlock(level, ladder, shaftX, y, shaftZ, chunkBox);
        }
    }

    /** 研究日誌（7・8・9）と、研究所の宝箱。 */
    private void placeDiariesAndChests(WorldGenLevel level, BoundingBox chunkBox, RandomSource random) {
        int y = bunkerY + 1;
        int edge = HALF_SIZE - 2;
        StructureHelper.placeLecternWithDiary(level, chunkBox, new BlockPos(centerX - edge, y, centerZ - edge), 7, Direction.SOUTH);
        StructureHelper.placeLecternWithDiary(level, chunkBox, new BlockPos(centerX - edge, y, centerZ), 8, Direction.EAST);
        StructureHelper.placeLecternWithDiary(level, chunkBox, new BlockPos(centerX, y, centerZ + edge), 9, Direction.NORTH);
        createChest(level, chunkBox, random, centerX - edge, y, centerZ + edge, StructureHelper.LAB_LOOT);
        createChest(level, chunkBox, random, centerX + edge, y, centerZ + edge, StructureHelper.LAB_LOOT);
    }

    /** かべのブロック（ひびを少しまぜる）。 */
    private BlockState wallBlock(int x, int y, int z) {
        return StructureHelper.hash01(seed + 3, x, y, z) < 0.2f
                ? Blocks.CRACKED_DEEPSLATE_BRICKS.defaultBlockState() : Blocks.DEEPSLATE_BRICKS.defaultBlockState();
    }
}
