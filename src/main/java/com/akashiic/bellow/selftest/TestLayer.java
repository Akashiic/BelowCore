package com.akashiic.bellow.selftest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import com.akashiic.bellow.api.BellowAPI;
import com.akashiic.bellow.api.IDeepGenerator;

/**
 * The deep layer of the self test worlds, in the role a provider (VitaGenesis) has in play: stone from the floor up to
 * Y -1 over a bedrock floor, in the air the chunk left. Bellow places no content itself; this generator also checks
 * that it receives every section of the layer, empty on a new chunk. In chunk 1000,1000 it leaves column 0,0 open at
 * Y -1, where the legacy bedrock above must then stay.
 */
public final class TestLayer implements IDeepGenerator {

    public static final String ID = "akashicbellow:selftest-layer";
    public static final TestLayer INSTANCE = new TestLayer();

    /** New chunks whose sections were missing or not empty when the terrain step started. */
    public int notEmpty, missing, calls;
    /** The first not empty sections found: chunk, section and one block in it. */
    public final List<String> notEmptyAt = new ArrayList<>();
    /** Retrogen calls, and those that found Bellow's provisional layer (stone at minY + 20). */
    final Set<Long> retrogen = new HashSet<>();
    final Set<Long> retrogenOnProvisional = new HashSet<>();

    private TestLayer() {}

    public static void register() {
        BellowAPI.registerGenerator(ID, INSTANCE, -100);
    }

    /**
     * The first block of a section, or null when it holds none. {@code ExtendedBlockStorage.isEmpty()} cannot tell:
     * with
     * ArchaicFix's lighting it also counts light, and a fresh section holding no block may report itself not empty.
     */
    private static String firstBlock(ExtendedBlockStorage ebs) {
        for (int i = 0; i < 4096; i++) {
            Block block = ebs.getBlockByExtId(i & 15, i >> 8, (i >> 4) & 15);
            if (block != Blocks.air) return Block.blockRegistry
                .getNameForObject(block) + " at " + (i & 15) + "," + (i >> 8) + "," + ((i >> 4) & 15);
        }
        return null;
    }

    /** Dimensions it generated in (a claimed dimension must not be among them). */
    public final Set<Integer> dims = new HashSet<>();

    /** Not an owner: never asked (the claim probe checks the overworld kept Bellow's default). */
    @Override
    public Block legacyBedrockReplacement(Chunk chunk, int x, int y, int z) {
        return Blocks.end_stone;
    }

    @Override
    public void generateTerrain(Chunk chunk, int minY, long seed, boolean retrogen) {
        this.dims.add(chunk.worldObj.provider.dimensionId);
        this.calls++;
        long key = ChunkCoordIntPair.chunkXZ2Int(chunk.xPosition, chunk.zPosition);
        for (int sy = minY >> 4; sy < 0; sy++) {
            ExtendedBlockStorage ebs = BellowAPI.getDeepSection(chunk, sy);
            if (ebs == null) this.missing++;
            else if (!retrogen) {
                String block = firstBlock(ebs);
                if (block == null) continue;
                this.notEmpty++;
                if (this.notEmptyAt.size() < 5)
                    this.notEmptyAt.add(chunk.xPosition + "," + chunk.zPosition + " section " + sy + ": " + block);
            }
        }
        if (retrogen) {
            this.retrogen.add(key);
            ExtendedBlockStorage mid = BellowAPI.getDeepSection(chunk, (minY + 20) >> 4);
            // (4, 4): away from the tunnel the provisional probe digs at (8, 8)
            if (mid != null && mid.getBlockByExtId(4, (minY + 20) & 15, 4) == Blocks.stone)
                this.retrogenOnProvisional.add(key);
        }
        for (int sy = minY >> 4; sy < 0; sy++) {
            ExtendedBlockStorage ebs = BellowAPI.getDeepSection(chunk, sy);
            if (ebs == null) continue;
            for (int ly = 0; ly < 16; ly++) for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
                int y = (sy << 4) + ly;
                if (y == -1 && lx == 0 && lz == 0 && chunk.xPosition == 1000 && chunk.zPosition == 1000) continue;
                if (ebs.getBlockByExtId(lx, ly, lz)
                    .getMaterial() != Material.air) continue;
                ebs.func_150818_a(lx, ly, lz, y == minY ? Blocks.bedrock : Blocks.stone);
            }
        }
    }
}
