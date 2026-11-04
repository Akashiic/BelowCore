package com.akashiic.bellow.gen;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import com.akashiic.bellow.GenConfig;
import com.akashiic.bellow.api.DeepConstants;
import com.akashiic.bellow.storage.GeneratorRecords;
import com.akashiic.bellow.storage.IDeepChunkInternal;

/**
 * What the core itself does to a chunk's deep layer. It places no content: the deep layer is made by the registered
 * generators ({@link com.akashiic.bellow.api.IDeepGenerator}). Chunk-local, needs no neighbour.
 */
public final class DeepTerrain {

    private DeepTerrain() {}

    /**
     * Creates every section from the floor up to Y -1, empty where none existed (the generators' terrain step finds
     * them all; what a chunk saved before its deep layer already held below 0 is kept).
     */
    public static void prepare(Chunk chunk, int minY) {
        boolean hasSky = !chunk.worldObj.provider.hasNoSky;
        ExtendedBlockStorage[] deep = ((IDeepChunkInternal) chunk).bellow$getDeepSections();
        for (int sy = minY >> 4; sy < 0; sy++) {
            int slot = DeepConstants.slot(sy);
            if (deep[slot] == null) deep[slot] = new ExtendedBlockStorage(sy << 4, hasSky);
        }
        chunk.setChunkModified();
    }

    /**
     * Without any generator for the dimension: a provisional layer of stone over a bedrock floor (no ores, no caves),
     * so nothing below 0 is void next to chunks that have a deep layer. Only air is written. The chunk is not marked
     * generated and keeps its legacy bedrock: a generator installed later receives it as a retrogen chunk and replaces
     * the stone (see {@link DeepGen}).
     */
    public static void provisional(Chunk chunk, int minY) {
        prepare(chunk, minY);
        ExtendedBlockStorage[] deep = ((IDeepChunkInternal) chunk).bellow$getDeepSections();
        for (int sy = minY >> 4; sy < 0; sy++) {
            ExtendedBlockStorage ebs = deep[DeepConstants.slot(sy)];
            for (int ly = 0; ly < 16; ly++) {
                Block block = (sy << 4) + ly == minY ? Blocks.bedrock : Blocks.stone;
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        if (ebs.getBlockByExtId(lx, ly, lz)
                            .getMaterial() == Material.air) ebs.func_150818_a(lx, ly, lz, block);
                    }
                }
            }
        }
        ((IDeepChunkInternal) chunk).bellow$setProvisional(true);
    }

    /**
     * After the generators' terrain step: the natural bedrock at Y 0..legacyBedrockMaxY that used to be the floor
     * becomes deepslate (or stone), opening the old world into the new layer (what 1.18 did when upgrading worlds). The
     * only write of the core at Y >= 0, and only where the generators left Y -1 solid: a generator that failed or left
     * the layer empty must not open a hole into the void. Only {@code Blocks.bedrock} is touched.
     * <p>
     * In a claimed dimension the owner chooses the replacement
     * ({@link com.akashiic.bellow.api.IDeepGenerator#legacyBedrockReplacement}), also where Y -1 is open when its
     * terrain
     * step succeeded in this chunk (it knows a cave from the void); there, no answer keeps the bedrock. Air and fluid
     * sources are lit by the World at the light stage ({@link IDeepChunkInternal#bellow$isLegacyRelight}).
     */
    public static void replaceLegacyBedrock(Chunk chunk) {
        if (!GenConfig.replaceLegacyBedrock) return;
        ExtendedBlockStorage bottom = chunk.getBlockStorageArray()[0];
        ExtendedBlockStorage below = ((IDeepChunkInternal) chunk).bellow$getDeepSections()[DeepConstants.slot(-1)];
        if (bottom == null || below == null) return;
        Block replacement = DeepBlocks.legacyReplacement();
        // in a claimed dimension the owner decides what replaces it (its terrain at Y 0, not deepslate)
        DeepGenerators.Entry owner = DeepGenerators.ownerOf(chunk.worldObj);
        // its terrain step ran without throwing (a failed step also counts as done, in F as well)
        boolean ownerTerrain = owner != null
            && (GeneratorRecords.stages(chunk, owner.id) & GeneratorRecords.TERRAIN) != 0
            && (GeneratorRecords.failed(chunk, owner.id) & GeneratorRecords.TERRAIN) == 0;
        int maxY = Math.min(GenConfig.legacyBedrockMaxY, 15);
        boolean changed = false, relight = false;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                boolean solid = below.getBlockByExtId(x, 15, z)
                    .getMaterial() != Material.air;
                if (!solid && !ownerTerrain) continue;
                for (int y = 0; y <= maxY; y++) {
                    if (bottom.getBlockByExtId(x, y, z) != Blocks.bedrock) continue;
                    Block block = solid ? replacement : null;
                    int meta = 0;
                    boolean lit = false;
                    if (owner != null) {
                        Block chosen = null;
                        int chosenMeta = 0;
                        try {
                            chosen = owner.generator.legacyBedrockReplacement(chunk, x, y, z);
                            if (chosen != null)
                                chosenMeta = owner.generator.legacyBedrockReplacementMeta(chunk, x, y, z);
                        } catch (Throwable ex) {
                            DeepGenerators.rethrowFatal(ex);
                            DeepGenerators.failed(owner, "legacyBedrockReplacement", chunk, ex);
                            chosen = null;
                        }
                        if (chosen == Blocks.bedrock) continue;
                        if (chosen != null) {
                            int kind = kind(chosen, chosenMeta);
                            if (kind != REFUSED) {
                                block = chosen;
                                meta = chosenMeta;
                                lit = kind == LIT_BY_WORLD;
                            } else if (!owner.warnedReplacement) {
                                owner.warnedReplacement = true;
                                DeepGenerators.LOG.warn(
                                    "Deep generator {} asked for {}:{} in place of the legacy bedrock of chunk {},{};"
                                        + " only opaque full blocks without light or tile entity, air and fluid sources"
                                        + " are written there: using the default. Logged once.",
                                    owner.id,
                                    Block.blockRegistry.getNameForObject(chosen),
                                    chosenMeta,
                                    chunk.xPosition,
                                    chunk.zPosition);
                            }
                        }
                    }
                    if (block == null) continue; // open column, no answer: the bedrock stays
                    bottom.func_150818_a(x, y, z, block);
                    bottom.setExtBlockMetadata(x, y, z, meta);
                    changed = true;
                    if (lit) {
                        relight = true;
                        lowerHeight(chunk, x, z, maxY);
                    }
                }
            }
        }
        // light follows at the light stage
        if (relight) ((IDeepChunkInternal) chunk).bellow$setLegacyRelight(true);
        if (changed) chunk.setChunkModified();
    }

    /**
     * A column whose top light-blocking block was that bedrock: its height map goes down to the next one (0 at most:
     * the height map never goes below 0), as {@code Chunk.relightBlock} does; the precipitation height is recomputed
     * when asked. Columns covered higher up are unchanged. ({@code generateHeightMap} is client only, and
     * {@code generateSkylightMap} would also rewrite the sky light of the whole chunk.)
     */
    private static void lowerHeight(Chunk chunk, int x, int z, int maxY) {
        int i = z << 4 | x;
        int height = chunk.heightMap[i];
        if (height > maxY + 1) return;
        int y = height - 1;
        while (y >= 0 && chunk.func_150808_b(x, y, z) == 0) y--;
        chunk.heightMap[i] = y + 1;
        if (y + 1 < chunk.heightMapMinimum) chunk.heightMapMinimum = y + 1;
        chunk.precipitationHeightMap[i] = -999;
    }

    private static final int REFUSED = 0, WRITTEN = 1, LIT_BY_WORLD = 2;

    /**
     * Written straight into the section: an opaque full block without light or tile entity needs nothing more; air and
     * a fluid source (metadata 0) need the World's light, given at the light stage. Nothing else.
     */
    private static int kind(Block block, int meta) {
        if (meta < 0 || meta > 15 || block.hasTileEntity(meta)) return REFUSED;
        if (block == Blocks.air) return LIT_BY_WORLD;
        if (block.getMaterial()
            .isLiquid()) return meta == 0 ? LIT_BY_WORLD : REFUSED;
        return block.isOpaqueCube() && block.getLightValue() == 0 ? WRITTEN : REFUSED;
    }
}
