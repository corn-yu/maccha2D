package com.maccha.zombiepanic.structure;

import com.maccha.zombiepanic.ModContent;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;

import java.util.Optional;

/**
 * 研究所（地上の白い建物と、エンドへの門がある地下室）。世界の真実の さいごの日誌がある。
 */
public class ResearchLabStructure extends Structure {

    public static final MapCodec<ResearchLabStructure> CODEC = simpleCodec(ResearchLabStructure::new);

    // 地上の建物の広さと、地下室までの深さ
    private static final int BUILDING_SIZE = 15;
    private static final int BUNKER_DEPTH = 22;

    /** 設定を受けとって作る。 */
    public ResearchLabStructure(StructureSettings settings) {
        super(settings);
    }

    /** 研究所を作る場所を決める（水の上には作らない）。 */
    @Override
    protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
        ChunkPos chunkPos = context.chunkPos();
        int centerX = chunkPos.getMiddleBlockX();
        int centerZ = chunkPos.getMiddleBlockZ();
        if (StructureHelper.isWater(context, centerX, centerZ)) {
            return Optional.empty();
        }
        int surfaceY = StructureHelper.surfaceY(context, centerX, centerZ);
        int bunkerY = Math.max(surfaceY - BUNKER_DEPTH, context.heightAccessor().getMinBuildHeight() + 8);
        long seed = context.random().nextLong();

        return Optional.of(new GenerationStub(new BlockPos(centerX, surfaceY, centerZ), builder -> {
            // 地上の建物（先に建てて、あとから たて穴を ほる）
            int half = BUILDING_SIZE / 2;
            builder.addPiece(new RuinPiece(RuinPiece.KIND_LAB, centerX - half, surfaceY, centerZ - half,
                    BUILDING_SIZE, BUILDING_SIZE, 2, 0, 0, true, seed));
            // 地下室
            builder.addPiece(new LabBunkerPiece(centerX, centerZ, surfaceY, bunkerY, seed));
        }));
    }

    /** この構造物の種類。 */
    @Override
    public StructureType<?> type() {
        return ModContent.RESEARCH_LAB_TYPE.get();
    }
}
