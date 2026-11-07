package com.maccha.zombiepanic.structure;

import com.maccha.zombiepanic.ModContent;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;

import java.util.Optional;

/**
 * 廃都市（こわれたビルが ならぶ町のあと）。宝箱と研究日誌がある。
 */
public class RuinedCityStructure extends Structure {

    public static final MapCodec<RuinedCityStructure> CODEC = simpleCodec(RuinedCityStructure::new);

    // 1区画の広さ（ブロック）
    private static final int CELL_SIZE = 17;

    /** 設定を受けとって作る。 */
    public RuinedCityStructure(StructureSettings settings) {
        super(settings);
    }

    /** 町を作る場所を決める（水の上には作らない）。 */
    @Override
    protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
        ChunkPos chunkPos = context.chunkPos();
        int centerX = chunkPos.getMiddleBlockX();
        int centerZ = chunkPos.getMiddleBlockZ();
        if (StructureHelper.isWater(context, centerX, centerZ)) {
            return Optional.empty();
        }
        int centerY = StructureHelper.surfaceY(context, centerX, centerZ);
        long layoutSeed = context.random().nextLong();
        return Optional.of(new GenerationStub(new BlockPos(centerX, centerY, centerZ),
                builder -> addBuildings(builder, context, centerX, centerZ, layoutSeed)));
    }

    /** 区画ごとに ビルを1つずつ ならべる（まん中ほど高い）。 */
    private static void addBuildings(StructurePiecesBuilder builder, GenerationContext context, int centerX, int centerZ, long layoutSeed) {
        RandomSource random = RandomSource.create(layoutSeed);
        int gridSize = 3 + random.nextInt(3);
        int half = gridSize * CELL_SIZE / 2;

        for (int gridX = 0; gridX < gridSize; gridX++) {
            for (int gridZ = 0; gridZ < gridSize; gridZ++) {
                // ところどころ 空き地
                if (random.nextFloat() < 0.18f) {
                    continue;
                }
                int width = 7 + random.nextInt(7);
                int depth = 7 + random.nextInt(7);
                int x = centerX - half + gridX * CELL_SIZE + (CELL_SIZE - width) / 2;
                int z = centerZ - half + gridZ * CELL_SIZE + (CELL_SIZE - depth) / 2;

                // 水の上には建てない
                int middleX = x + width / 2;
                int middleZ = z + depth / 2;
                if (StructureHelper.isWater(context, middleX, middleZ)) {
                    continue;
                }
                int y = StructureHelper.surfaceY(context, middleX, middleZ);

                // まん中に近いほど 高いビル
                boolean nearCenter = Math.abs(gridX * 2 - (gridSize - 1)) <= 1 && Math.abs(gridZ * 2 - (gridSize - 1)) <= 1;
                int floors = 1 + random.nextInt(2) + (nearCenter ? 2 + random.nextInt(3) : random.nextInt(2));

                int material = random.nextInt(5);
                int lecternPage = random.nextFloat() < 0.45f ? LoreBooks.CITY_PAGES[random.nextInt(LoreBooks.CITY_PAGES.length)] : 0;
                boolean hasChest = random.nextFloat() < 0.7f;
                builder.addPiece(new RuinPiece(RuinPiece.KIND_CITY, x, y, z, width, depth, floors,
                        material, lecternPage, hasChest, random.nextLong()));
            }
        }
    }

    /** この構造物の種類。 */
    @Override
    public StructureType<?> type() {
        return ModContent.RUINED_CITY_TYPE.get();
    }
}
