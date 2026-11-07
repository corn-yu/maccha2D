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
 * こわれたビル1つ（廃都市の建物・研究所の地上の建物）。
 * 座標はそのままの場所（向きなし）で、こわれ方は場所から決まるので チャンクをまたいでも同じ形になる。
 */
public class RuinPiece extends StructurePiece {

    // 建物の種類
    public static final int KIND_CITY = 0;
    public static final int KIND_LAB = 1;

    // 1階ぶんの高さ
    private static final int FLOOR_HEIGHT = 4;

    private final int kind;
    private final int originX;
    private final int originY;
    private final int originZ;
    private final int width;
    private final int depth;
    private final int floors;
    private final int material;
    private final int lecternPage;
    private final boolean hasChest;
    private final long seed;

    /** 新しく建物を作る。 */
    public RuinPiece(int kind, int originX, int originY, int originZ, int width, int depth, int floors,
                     int material, int lecternPage, boolean hasChest, long seed) {
        super(ModContent.RUIN_PIECE, 0, new BoundingBox(originX, originY - 8, originZ,
                originX + width - 1, originY + floors * FLOOR_HEIGHT + 1, originZ + depth - 1));
        this.setOrientation(null);
        this.kind = kind;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.width = width;
        this.depth = depth;
        this.floors = floors;
        this.material = material;
        this.lecternPage = lecternPage;
        this.hasChest = hasChest;
        this.seed = seed;
    }

    /** 保存してあった建物を読む。 */
    public RuinPiece(CompoundTag tag) {
        super(ModContent.RUIN_PIECE, tag);
        this.setOrientation(null);
        this.kind = tag.getIntOr("Kind", 0);
        this.originX = tag.getIntOr("OX", 0);
        this.originY = tag.getIntOr("OY", 0);
        this.originZ = tag.getIntOr("OZ", 0);
        this.width = tag.getIntOr("W", 0);
        this.depth = tag.getIntOr("D", 0);
        this.floors = tag.getIntOr("Floors", 0);
        this.material = tag.getIntOr("Mat", 0);
        this.lecternPage = tag.getIntOr("Page", 0);
        this.hasChest = tag.getBooleanOr("Chest", false);
        this.seed = tag.getLongOr("Seed", 0L);
    }

    /** 建物の情報を保存する。 */
    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
        tag.putInt("Kind", kind);
        tag.putInt("OX", originX);
        tag.putInt("OY", originY);
        tag.putInt("OZ", originZ);
        tag.putInt("W", width);
        tag.putInt("D", depth);
        tag.putInt("Floors", floors);
        tag.putInt("Mat", material);
        tag.putInt("Page", lecternPage);
        tag.putBoolean("Chest", hasChest);
        tag.putLong("Seed", seed);
    }

    /** チャンクごとに呼ばれて、そのチャンクの中の部分を建てる。 */
    @Override
    public void postProcess(WorldGenLevel level, StructureManager structureManager, ChunkGenerator generator,
                            RandomSource random, BoundingBox chunkBox, net.minecraft.world.level.ChunkPos chunkPos, BlockPos pivot) {
        buildFoundation(level, chunkBox);
        for (int floor = 0; floor < floors; floor++) {
            buildFloor(level, chunkBox, floor);
        }
        buildRoof(level, chunkBox);
        buildLadder(level, chunkBox);
        decorate(level, chunkBox, random);
    }

    // ---------------- 建てる ----------------

    /** 地面との すき間をうめる土台と、1階のゆか。 */
    private void buildFoundation(WorldGenLevel level, BoundingBox chunkBox) {
        BlockState foundation = kind == KIND_LAB ? Blocks.POLISHED_ANDESITE.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState();
        for (int x = originX; x < originX + width; x++) {
            for (int z = originZ; z < originZ + depth; z++) {
                fillColumnDown(level, foundation, x, originY - 1, z, chunkBox);
                placeBlock(level, floorBlock(x, originY, z), x, originY, z, chunkBox);
            }
        }
    }

    /** 1つの階（かべ・まど・中の空気・ゆか）を建てる。 */
    private void buildFloor(WorldGenLevel level, BoundingBox chunkBox, int floor) {
        int baseY = originY + floor * FLOOR_HEIGHT;
        boolean topFloor = floor == floors - 1;

        // 上の階ほど こわれている
        float damage = kind == KIND_LAB ? 0.04f : 0.1f + 0.07f * floor;

        for (int x = originX; x < originX + width; x++) {
            for (int z = originZ; z < originZ + depth; z++) {
                boolean isWall = x == originX || x == originX + width - 1 || z == originZ || z == originZ + depth - 1;

                // 2階より上は ゆかをはる（ところどころ穴）
                if (floor > 0 && !isWall && StructureHelper.hash01(seed, x, baseY, z) > damage) {
                    placeBlock(level, floorBlock(x, baseY, z), x, baseY, z, chunkBox);
                }

                // かべ（いちばん上の階は くずれて低くなる）
                int wallTop = topFloor ? FLOOR_HEIGHT - 1 - (int) (StructureHelper.hash01(seed, x, 7, z) * 3) : FLOOR_HEIGHT;
                for (int dy = 1; dy <= FLOOR_HEIGHT; dy++) {
                    int y = baseY + dy;
                    if (isWall) {
                        placeWallBlock(level, chunkBox, x, y, z, dy, wallTop, damage);
                    } else {
                        placeBlock(level, Blocks.AIR.defaultBlockState(), x, y, z, chunkBox);
                    }
                }
            }
        }

        // 1階の入り口
        if (floor == 0) {
            int doorX = originX + width / 2;
            placeBlock(level, Blocks.AIR.defaultBlockState(), doorX, baseY + 1, originZ, chunkBox);
            placeBlock(level, Blocks.AIR.defaultBlockState(), doorX, baseY + 2, originZ, chunkBox);
        }
    }

    /** かべの1ブロック（まど・こわれた穴・くずれた上の部分）。 */
    private void placeWallBlock(WorldGenLevel level, BoundingBox chunkBox, int x, int y, int z, int dy, int wallTop, float damage) {
        // くずれて なくなった上の部分
        if (dy > wallTop) {
            placeBlock(level, Blocks.AIR.defaultBlockState(), x, y, z, chunkBox);
            return;
        }
        // まど（3つに1つ・まん中の高さ）
        boolean windowSpot = dy == 2 && ((x + z) % 3 == 0) && !isCorner(x, z);
        if (windowSpot) {
            BlockState window = kind == KIND_LAB ? Blocks.GLASS.defaultBlockState() : Blocks.AIR.defaultBlockState();
            placeBlock(level, window, x, y, z, chunkBox);
            return;
        }
        // こわれた穴
        if (!isCorner(x, z) && StructureHelper.hash01(seed, x, y, z) < damage) {
            placeBlock(level, Blocks.AIR.defaultBlockState(), x, y, z, chunkBox);
            return;
        }
        placeBlock(level, wallBlock(x, y, z), x, y, z, chunkBox);
    }

    /** 屋上（穴だらけ）。 */
    private void buildRoof(WorldGenLevel level, BoundingBox chunkBox) {
        int roofY = originY + floors * FLOOR_HEIGHT;
        float holes = kind == KIND_LAB ? 0.05f : 0.35f;
        for (int x = originX + 1; x < originX + width - 1; x++) {
            for (int z = originZ + 1; z < originZ + depth - 1; z++) {
                if (StructureHelper.hash01(seed, x, roofY, z) > holes) {
                    placeBlock(level, floorBlock(x, roofY, z), x, roofY, z, chunkBox);
                }
            }
        }
    }

    /** 上の階へ のぼる はしご（西のかべにつける）。 */
    private void buildLadder(WorldGenLevel level, BoundingBox chunkBox) {
        if (floors < 2) {
            return;
        }
        int ladderX = originX + 1;
        int ladderZ = originZ + depth - 2;
        BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.EAST);
        int topY = originY + (floors - 1) * FLOOR_HEIGHT + 1;
        for (int y = originY + 1; y <= topY; y++) {
            placeBlock(level, wallBlock(originX, y, ladderZ), originX, y, ladderZ, chunkBox);
            placeBlock(level, ladder, ladderX, y, ladderZ, chunkBox);
        }
    }

    /** 宝箱・書見台・クモの巣・がれき を置く。 */
    private void decorate(WorldGenLevel level, BoundingBox chunkBox, RandomSource random) {
        // 1階の宝箱
        if (hasChest) {
            createChest(level, chunkBox, random, originX + width - 2, originY + 1, originZ + 1,
                    kind == KIND_LAB ? StructureHelper.LAB_LOOT : StructureHelper.CITY_LOOT);
        }
        // 高いビルは 屋上の下にも宝箱
        if (kind == KIND_CITY && floors >= 4) {
            int topBaseY = originY + (floors - 1) * FLOOR_HEIGHT;
            placeBlock(level, floorBlock(originX + width - 2, topBaseY, originZ + depth - 2), originX + width - 2, topBaseY, originZ + depth - 2, chunkBox);
            createChest(level, chunkBox, random, originX + width - 2, topBaseY + 1, originZ + depth - 2, StructureHelper.CITY_LOOT);
        }
        // 研究日誌をのせた書見台
        if (lecternPage > 0) {
            BlockPos lecternPos = new BlockPos(originX + width / 2, originY + 1, originZ + depth - 2);
            StructureHelper.placeLecternWithDiary(level, chunkBox, lecternPos, lecternPage, Direction.NORTH);
        }
        placeCobwebsAndRubble(level, chunkBox);
    }

    /** 天井のクモの巣と、ゆかの がれき。 */
    private void placeCobwebsAndRubble(WorldGenLevel level, BoundingBox chunkBox) {
        for (int floor = 0; floor < floors; floor++) {
            int baseY = originY + floor * FLOOR_HEIGHT;
            for (int x = originX + 2; x < originX + width - 2; x++) {
                for (int z = originZ + 2; z < originZ + depth - 2; z++) {
                    float roll = StructureHelper.hash01(seed + 99, x, baseY, z);
                    if (roll < 0.05f) {
                        placeBlock(level, Blocks.COBWEB.defaultBlockState(), x, baseY + FLOOR_HEIGHT - 1, z, chunkBox);
                    } else if (roll > 0.96f && kind == KIND_CITY) {
                        BlockState rubble = roll > 0.98f ? Blocks.GRAVEL.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState();
                        placeBlock(level, rubble, x, baseY + 1, z, chunkBox);
                    }
                }
            }
        }
    }

    // ---------------- ブロックえらび ----------------

    /** 角かどうか（角は こわさない）。 */
    private boolean isCorner(int x, int z) {
        boolean edgeX = x == originX || x == originX + width - 1;
        boolean edgeZ = z == originZ || z == originZ + depth - 1;
        return edgeX && edgeZ;
    }

    /** かべのブロック（建物ごとの材料に、よごれ・ひびを まぜる）。 */
    private BlockState wallBlock(int x, int y, int z) {
        float roll = StructureHelper.hash01(seed + 7, x, y, z);
        if (kind == KIND_LAB) {
            return roll < 0.15f ? Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.LIGHT_GRAY).defaultBlockState() : Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.WHITE).defaultBlockState();
        }
        return switch (material) {
            case 0 -> roll < 0.3f ? Blocks.CRACKED_STONE_BRICKS.defaultBlockState()
                    : roll < 0.5f ? Blocks.MOSSY_STONE_BRICKS.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState();
            case 1 -> roll < 0.2f ? Blocks.MOSSY_COBBLESTONE.defaultBlockState() : Blocks.BRICKS.defaultBlockState();
            case 2 -> roll < 0.3f ? Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.LIGHT_GRAY).defaultBlockState()
                    : roll < 0.4f ? Blocks.DYED_TERRACOTTA.pick(net.minecraft.world.item.DyeColor.CYAN).defaultBlockState() : Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.GRAY).defaultBlockState();
            case 3 -> roll < 0.3f ? Blocks.ANDESITE.defaultBlockState() : Blocks.POLISHED_ANDESITE.defaultBlockState();
            default -> roll < 0.25f ? Blocks.DYED_TERRACOTTA.pick(net.minecraft.world.item.DyeColor.BROWN).defaultBlockState() : Blocks.TERRACOTTA.defaultBlockState();
        };
    }

    /** ゆかのブロック。 */
    private BlockState floorBlock(int x, int y, int z) {
        float roll = StructureHelper.hash01(seed + 13, x, y, z);
        if (kind == KIND_LAB) {
            return Blocks.SMOOTH_STONE.defaultBlockState();
        }
        if (roll < 0.15f) {
            return Blocks.MOSSY_COBBLESTONE.defaultBlockState();
        }
        return roll < 0.4f ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.POLISHED_ANDESITE.defaultBlockState();
    }
}
