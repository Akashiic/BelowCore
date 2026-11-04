package com.akashiic.bellow.selftest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import com.akashiic.bellow.BellowConfig;
import com.akashiic.bellow.api.BellowAPI;
import com.akashiic.bellow.api.DeepConstants;
import com.akashiic.bellow.api.IDeepChunk;
import com.akashiic.bellow.api.IDeepGenerator;
import com.akashiic.bellow.gen.DeepBlocks;
import com.akashiic.bellow.gen.DeepGen;
import com.akashiic.bellow.gen.DeepGenerators;

/**
 * A dimension claimed by the mod that owns it ({@link BellowAPI#claimDimension}): the End (dimension 1), which is not
 * in {@code general.dimensions}, claimed with floor -48 while the config says -64. Phase {@code full} checks the floor,
 * that only the owner generates there (the test layer, which applies everywhere, is not asked) and that the owner
 * generates nowhere else; phase {@code verify} (a new JVM) claims it with -32, and the floor locked in the save wins.
 */
public final class ClaimProbe implements IDeepGenerator {

    public static final String ID = "akashicbellow:selftest-claim";
    static final int DIM = 1;
    static final int FLOOR = -48, LATER_FLOOR = -32;
    private static final int CX = 300;
    /** Chunk of the legacy bedrock check: bedrock at Y 0..4, column 15,15 left open at Y -1. */
    private static final int LX = 310;

    private static final ClaimProbe INSTANCE = new ClaimProbe();
    private final Set<Integer> dims = new HashSet<>();
    private int calls;
    /** What the API answered at registration: conflicting, repeated and invalid claims. */
    private static final List<String> registration = new ArrayList<>();
    private static boolean registrationOk;

    private ClaimProbe() {}

    /** Init of phases full ({@code floor} -48) and verify (-32). */
    public static void register(int floor) {
        BellowAPI.registerGenerator(ID, INSTANCE);
        BellowAPI.claimDimension(DIM, floor, ID);
        boolean ok = true;
        BellowAPI.claimDimension(DIM, floor, ID); // the same claim again
        ok &= rejected(() -> BellowAPI.claimDimension(DIM, floor, "akashicbellow:someone-else"), "another owner");
        ok &= rejected(() -> BellowAPI.claimDimension(DIM, floor - 16, ID), "another floor");
        ok &= rejected(() -> BellowAPI.claimDimension(2, -40, ID), "floor -40");
        ok &= rejected(() -> BellowAPI.claimDimension(2, -64, ""), "empty id");
        registrationOk = ok;
    }

    private static boolean rejected(Runnable claim, String what) {
        try {
            claim.run();
            registration.add(what + " accepted");
            return false;
        } catch (IllegalArgumentException e) {
            registration.add(what + " refused");
            return true;
        }
    }

    @Override
    public void generateTerrain(Chunk chunk, int minY, long seed, boolean retrogen) {
        this.dims.add(chunk.worldObj.provider.dimensionId);
        this.calls++;
        for (int sy = minY >> 4; sy < 0; sy++) {
            ExtendedBlockStorage ebs = BellowAPI.getDeepSection(chunk, sy);
            if (ebs == null) continue;
            for (int i = 0; i < 4096; i++) {
                int x = i & 15, ly = i >> 8, z = (i >> 4) & 15;
                if (ebs.getBlockByExtId(x, ly, z) != Blocks.air) continue;
                if (chunk.xPosition == LX && chunk.zPosition == LX && x == 15 && z == 15 && (sy << 4) + ly == -1)
                    continue;
                ebs.func_150818_a(x, ly, z, (sy << 4) + ly == minY ? Blocks.obsidian : Blocks.stone);
            }
        }
    }

    /**
     * One answer per height: Y 0 its own terrain (end stone, or wool 5 in the west half: metadata), Y 1 the default, Y
     * 2
     * keep the bedrock, Y 3 glass (not opaque: refused, default), Y 4 an exception (default).
     */
    @Override
    public Block legacyBedrockReplacement(Chunk chunk, int x, int y, int z) {
        switch (y) {
            case 0:
                return x < 8 ? Blocks.wool : Blocks.end_stone;
            case 2:
                return Blocks.bedrock;
            case 3:
                return Blocks.glass;
            case 4:
                throw new IllegalStateException("self test: failing replacement");
            default:
                return null;
        }
    }

    @Override
    public int legacyBedrockReplacementMeta(Chunk chunk, int x, int y, int z) {
        return y == 0 && x < 8 ? 5 : 0;
    }

    public static void run(BukkitProbe.Check check) {
        WorldServer end = MinecraftServer.getServer()
            .worldServerForDimension(DIM);
        check.check("API version", BellowAPI.apiVersion() == 6, "apiVersion " + BellowAPI.apiVersion());
        check.check(
            "dimension claims: conflicting and invalid claims refused, the same claim twice allowed",
            registrationOk,
            registration.toString());
        boolean late;
        try {
            BellowAPI.claimDimension(2, -64, ID);
            late = false;
        } catch (IllegalStateException e) {
            late = true;
        }
        check.check("a claim after the server started is refused", late, "");
        check.check(
            "the owner of a claimed dimension",
            ID.equals(BellowAPI.getDimensionOwner(DIM)) && BellowAPI.getDimensionOwner(0) == null,
            "dim 1 " + BellowAPI.getDimensionOwner(DIM) + ", dim 0 " + BellowAPI.getDimensionOwner(0));
        if (end == null) {
            check.check("claimed dimension loaded", false, "no End world");
            return;
        }
        int minY = BellowAPI.getMinY(end);
        check.check(
            "a claimed dimension gets the claimed floor, not the config",
            minY == FLOOR && !BellowConfig.isDimensionEnabled(DIM),
            "End minY " + minY
                + " (claimed "
                + FLOOR
                + ", config minY "
                + BellowConfig.minY
                + ", End in general.dimensions: "
                + BellowConfig.isDimensionEnabled(DIM)
                + ")");
        int before = INSTANCE.calls;
        for (int cx = CX - 1; cx <= CX + 1; cx++) for (int cz = CX - 1; cz <= CX + 1; cz++) end.getChunkProvider()
            .loadChunk(cx, cz);
        BellowAPI.runDeepStages(end);
        int floorBlocks = 0;
        for (int cx = CX - 1; cx <= CX + 1; cx++) for (int cz = CX - 1; cz <= CX + 1; cz++) {
            Chunk c = end.getChunkFromChunkCoords(cx, cz);
            for (int i = 0; i < 256; i++) if (c.getBlock(i & 15, FLOOR, i >> 4) == Blocks.obsidian) floorBlocks++;
        }
        Block below = end.getBlock((CX << 4) + 8, FLOOR - 1, (CX << 4) + 8);
        check.check(
            "only the owner generates a claimed dimension",
            INSTANCE.calls - before > 0 && floorBlocks == 9 * 256
                && !TestLayer.INSTANCE.dims.contains(DIM)
                && DeepGenerators.describe(end)
                    .equals(java.util.Arrays.asList(ID)),
            (INSTANCE.calls - before) + " terrain calls, "
                + floorBlocks
                + "/2304 floor columns at Y "
                + FLOOR
                + ", block below the floor "
                + Block.blockRegistry.getNameForObject(below)
                + ", generators of the End "
                + DeepGenerators.describe(end)
                + ", test layer generated in "
                + TestLayer.INSTANCE.dims);
        check.check(
            "the owner of a dimension generates nowhere else",
            INSTANCE.dims.size() == 1 && INSTANCE.dims.contains(DIM) && TestLayer.INSTANCE.dims.contains(0),
            "owner generated in " + INSTANCE.dims + ", test layer in " + TestLayer.INSTANCE.dims);
        legacyBedrock(end, check);
    }

    /**
     * API 6: a chunk of the claimed End with legacy bedrock at Y 0..4 (made with the generators hidden, as a chunk of a
     * dimension that had no deep layer yet) goes through the terrain step: the owner chooses the replacement, within
     * Bellow's rules. In the overworld (not claimed) the test layer's answer is not asked.
     */
    private static void legacyBedrock(WorldServer end, BukkitProbe.Check check) {
        Chunk c;
        DeepGenerators.hideForTests(true);
        try {
            c = end.getChunkProvider()
                .loadChunk(LX, LX);
            for (int i = 0; i < 256; i++) for (int y = 0; y <= 4; y++)
                end.setBlock((LX << 4) + (i & 15), y, (LX << 4) + (i >> 4), Blocks.bedrock, 0, 2);
            end.setBlock((LX << 4) + 15, -1, (LX << 4) + 15, Blocks.air, 0, 2);
        } finally {
            DeepGenerators.hideForTests(false);
        }
        int before = ((IDeepChunk) c).bellow$getGenState();
        DeepGen.onChunkLoad(c);
        Block def = DeepBlocks.legacyReplacement();
        int y0 = 0, y1 = 0, y2 = 0, y3 = 0, y4 = 0, open = 0;
        for (int i = 0; i < 255; i++) { // all but column 15,15
            int x = i & 15, z = i >> 4;
            boolean west = x < 8;
            if (west ? c.getBlock(x, 0, z) == Blocks.wool && c.getBlockMetadata(x, 0, z) == 5
                : c.getBlock(x, 0, z) == Blocks.end_stone) y0++;
            if (c.getBlock(x, 1, z) == def) y1++;
            if (c.getBlock(x, 2, z) == Blocks.bedrock) y2++;
            if (c.getBlock(x, 3, z) == def) y3++;
            if (c.getBlock(x, 4, z) == def) y4++;
        }
        for (int y = 0; y <= 4; y++) if (c.getBlock(15, y, 15) == Blocks.bedrock) open++;
        check.check(
            "the owner of a claimed dimension chooses what replaces the legacy bedrock",
            before == DeepConstants.GEN_NONE && y0 == 255
                && y1 == 255
                && y2 == 255
                && y3 == 255
                && y4 == 255
                && open == 5,
            "of 255 columns: Y 0 own terrain " + y0
                + ", Y 1 default "
                + y1
                + ", Y 2 kept "
                + y2
                + ", Y 3 glass refused "
                + y3
                + ", Y 4 exception "
                + y4
                + "; column open at Y -1 keeps "
                + open
                + "/5");
        Chunk overworld = MinecraftServer.getServer()
            .worldServerForDimension(0)
            .getChunkFromChunkCoords(1000, 1000);
        int defaults = 0, chosen = 0;
        for (int i = 0; i < 256; i++) {
            Block b = overworld.getBlock(i & 15, 0, i >> 4);
            if (b == def) defaults++;
            if (b == Blocks.end_stone) chosen++;
        }
        check.check(
            "a generator that owns no dimension does not choose the replacement",
            chosen == 0 && defaults > 0,
            "overworld chunk 1000,1000 at Y 0: " + defaults + " default, " + chosen + " from the test layer");
    }

    /** Phase verify: claimed with -32 now, the save keeps the -48 it locked. */
    public static void verify(BukkitProbe.Check check) {
        WorldServer end = MinecraftServer.getServer()
            .worldServerForDimension(DIM);
        int minY = end == null ? 0 : BellowAPI.getMinY(end);
        boolean kept = false;
        if (end != null) {
            Chunk c = end.getChunkProvider()
                .loadChunk(CX, CX);
            kept = c.getBlock(8, FLOOR, 8) == Blocks.obsidian;
        }
        check.check(
            "a claimed dimension keeps the floor locked in its save",
            minY == FLOOR && kept,
            "End minY " + minY + " (claimed now " + LATER_FLOOR + ", locked " + FLOOR + "), floor block kept " + kept);
    }
}
