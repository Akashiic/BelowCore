package com.akashiic.bellow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.gen.ChunkProviderServer;

import com.akashiic.bellow.api.BellowAPI;
import com.akashiic.bellow.api.DeepConstants;
import com.akashiic.bellow.api.IDeepChunk;

/**
 * In-game verification of the deep layer on a test server world. Every check prints PASS/FAIL with the measured
 * values. Used headless with {@code -Dakashicbellow.selftest=true}, and by {@code /bellow selftest} only on servers
 * started with that flag: it generates and writes test chunks far from spawn (around chunks 1000..1100), leaves marker
 * blocks and a chest there, and on Crucible creates and removes a WorldGuard region and a GriefPreventionPlus claim.
 */
public final class SelfTest {

    private final World world;
    private final List<String> out = new ArrayList<>();
    private int failures;

    public SelfTest(World world) {
        this.world = world;
    }

    public int failures() {
        return this.failures;
    }

    private void check(String name, boolean ok, String detail) {
        if (!ok) this.failures++;
        this.out.add((ok ? "PASS " : "FAIL ") + name + (detail.isEmpty() ? "" : " - " + detail));
    }

    public List<String> run() {
        int minY = BellowAPI.getMinY(this.world);
        this.check("floor", minY < 0 && (minY & 15) == 0, "minY=" + minY + " dim=" + this.world.provider.dimensionId);
        if (minY >= 0 || !(this.world instanceof WorldServer)) {
            this.out.add("SKIP remaining checks: not a deep server world");
            return this.out;
        }
        String phase = System.getProperty("akashicbellow.selftest.phase", "full");
        if (phase.equals("bootstrap")) {
            this.out.add("BOOTSTRAP world created");
            return this.out;
        }
        try {
            if (phase.equals("verify")) {
                this.verifyPersisted(minY);
                com.akashiic.bellow.selftest.ProvisionalProbe.verify((WorldServer) this.world, minY, this::check);
                com.akashiic.bellow.selftest.GeneratorProbe.verify((WorldServer) this.world, minY, this::check);
                com.akashiic.bellow.selftest.ClaimProbe.verify(this::check);
            } else {
                this.retrogen(minY);
                this.terrain(minY);
                this.blocks(minY);
                this.light(minY);
                this.writePersistenceMarkers(minY);
                this.physics(minY);
                this.portal(minY);
                com.akashiic.bellow.selftest.GeneratorProbe.run((WorldServer) this.world, minY, this::check);
                com.akashiic.bellow.selftest.ProvisionalProbe.run((WorldServer) this.world, minY, this::check);
                com.akashiic.bellow.selftest.StorageProbe.run((WorldServer) this.world, this::check);
                com.akashiic.bellow.selftest.WaterProbe.run((WorldServer) this.world, minY, this::check);
                com.akashiic.bellow.selftest.NetworkCheckProbe.run(this::check);
                com.akashiic.bellow.selftest.ClaimProbe.run(this::check);
                if (cpw.mods.fml.common.Loader.isModLoaded("BuildCraft|Core")) {
                    com.akashiic.bellow.selftest.BuildCraftProbe.run((WorldServer) this.world, this::check);
                }
                if (com.akashiic.bellow.core.Compat.crucible) {
                    com.akashiic.bellow.selftest.BukkitProbe.run((WorldServer) this.world, this::check);
                    com.akashiic.bellow.selftest.PluginProbe.run((WorldServer) this.world, this::check);
                }
            }
        } catch (Throwable t) {
            this.check("no exception", false, t.toString());
            AkashicBellow.LOG.error("Self test crashed", t);
        }
        this.out.add(this.failures == 0 ? "RESULT PASS" : "RESULT FAIL (" + this.failures + ")");
        return this.out;
    }

    // ------------------------------------------------------------------ checks

    private Chunk load(int cx, int cz) {
        return this.world.getChunkProvider()
            .loadChunk(cx, cz);
    }

    /**
     * The deep layer of new chunks comes from the generators (here the test layer, {@link TestLayer}): Bellow hands
     * them
     * every section, empty, and afterwards replaces the legacy bedrock only where the layer is solid at Y -1.
     */
    private void terrain(int minY) {
        int cx0 = 1000, cz0 = 1000;
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) this.load(cx0 + dx, cz0 + dz);
        int columns = 0, legacyLeft = 0;
        boolean floorSolid = true, stateOk = true, keptWhereOpen = true;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                Chunk c = this.load(cx0 + dx, cz0 + dz);
                stateOk &= ((IDeepChunk) c).bellow$getGenState() >= DeepConstants.GEN_TERRAIN;
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        columns++;
                        floorSolid &= c.getBlock(x, minY, z) == Blocks.bedrock;
                        boolean open = dx == 0 && dz == 0 && x == 0 && z == 0; // the test layer left Y -1 air here
                        if (open) {
                            keptWhereOpen = c.getBlock(x, 0, z) == Blocks.bedrock;
                            continue;
                        }
                        if (GenConfig.replaceLegacyBedrock) for (int y = 0; y <= GenConfig.legacyBedrockMaxY; y++)
                            if (c.getBlock(x, y, z) == Blocks.bedrock) legacyLeft++;
                    }
                }
            }
        }
        com.akashiic.bellow.selftest.TestLayer layer = com.akashiic.bellow.selftest.TestLayer.INSTANCE;
        this.check("terrain state", stateOk, "");
        this.check(
            "generators receive every deep section, empty",
            layer.calls > 0 && layer.missing == 0 && layer.notEmpty == 0,
            layer.calls + " terrain calls, "
                + layer.missing
                + " sections missing, "
                + layer.notEmpty
                + " not empty"
                + (layer.notEmptyAt.isEmpty() ? "" : " " + layer.notEmptyAt));
        this.check("layer from the generator", floorSolid, columns + " columns with the test layer's bedrock floor");
        this.check(
            "legacy bedrock replaced where the layer is solid at Y -1, kept where it is open",
            legacyLeft == 0 && keptWhereOpen,
            legacyLeft + " legacy bedrock blocks left in solid columns; open column keeps Y 0 bedrock: "
                + keptWhereOpen);
    }

    private void blocks(int minY) {
        int x = 1000 * 16 + 8, z = 1000 * 16 + 8, y = minY + 20;
        World w = this.world;
        w.setBlock(x, y, z, Blocks.wool, 5, 3);
        this.check(
            "setBlock/getBlock",
            w.getBlock(x, y, z) == Blocks.wool && w.getBlockMetadata(x, y, z) == 5,
            CommandNames.name(w.getBlock(x, y, z)) + " meta " + w.getBlockMetadata(x, y, z));
        w.setBlockMetadataWithNotify(x, y, z, 7, 3);
        this.check("setBlockMetadata", w.getBlockMetadata(x, y, z) == 7, "meta " + w.getBlockMetadata(x, y, z));
        w.setBlockToAir(x, y, z);
        this.check("setBlockToAir", w.isAirBlock(x, y, z), "");
        w.setBlock(x, y, z, Blocks.chest, 0, 3);
        TileEntity te = w.getTileEntity(x, y, z);
        this.check("tile entity", te instanceof TileEntityChest && te.yCoord == y, String.valueOf(te));
        w.setBlockToAir(x, y, z);
        this.check("tile entity removed", w.getTileEntity(x, y, z) == null, "");
        this.check(
            "below floor is void",
            w.getBlock(x, minY - 1, z) == Blocks.air && !w.setBlock(x, minY - 1, z, Blocks.stone),
            "");
        this.check("blockExists", w.blockExists(x, y, z) && !w.blockExists(x, minY - 1, z), "");
        this.check("vanilla region untouched", w.getBlock(x, 0, z) != null, CommandNames.name(w.getBlock(x, 0, z)));
    }

    private void light(int minY) {
        World w = this.world;
        int x = 1000 * 16 + 4, z = 1000 * 16 + 4, y = minY + 30;
        for (int dx = -2; dx <= 2; dx++)
            for (int dy = -2; dy <= 2; dy++) for (int dz = -2; dz <= 2; dz++) w.setBlockToAir(x + dx, y + dy, z + dz);
        w.setBlock(x, y, z, Blocks.glowstone);
        int at1 = w.getSavedLightValue(EnumSkyBlock.Block, x + 1, y, z);
        int at2 = w.getSavedLightValue(EnumSkyBlock.Block, x, y + 2, z);
        int sky = w.getSavedLightValue(EnumSkyBlock.Sky, x + 1, y, z);
        this.check("block light propagates", at1 == 14 && at2 == 13, "d1=" + at1 + " d2=" + at2);
        this.check("no sky light underground", sky == 0, "sky=" + sky);
        w.setBlockToAir(x, y, z);
        int after = w.getSavedLightValue(EnumSkyBlock.Block, x + 1, y, z);
        this.check("block light removed", after == 0, "d1=" + after);
    }

    // Persistence is verified across two server processes: markers are written here, the world is saved on stop, and
    // the "verify" phase reads them back from disk in a fresh JVM.
    private static final int MARK_CX = 1040, MARK_CZ = 1040;

    private void writePersistenceMarkers(int minY) {
        this.load(MARK_CX, MARK_CZ);
        int x = MARK_CX * 16 + 3, z = MARK_CZ * 16 + 5;
        this.world.setBlock(x, minY + 3, z, Blocks.diamond_block);
        this.world.setBlock(x, minY + 9, z, Blocks.chest);
        TileEntity te = this.world.getTileEntity(x, minY + 9, z);
        if (te instanceof TileEntityChest)
            ((TileEntityChest) te).setInventorySlotContents(0, new net.minecraft.item.ItemStack(Blocks.dirt, 7));
        this.world.setBlock(x, -1, z, Blocks.wool, 11, 3);
        this.check("persistence markers written", this.world.getBlock(x, minY + 3, z) == Blocks.diamond_block, "");
    }

    private void verifyPersisted(int minY) throws java.io.IOException {
        ChunkProviderServer provider = (ChunkProviderServer) this.world.getChunkProvider();
        this.check("marker chunk not loaded before verify", !provider.chunkExists(MARK_CX, MARK_CZ), "");
        // On disk, deep tile entities sit outside the vanilla list (a world without the mod must still load).
        java.io.DataInputStream raw = net.minecraft.world.chunk.storage.RegionFileCache.getChunkInputStream(
            this.world.getSaveHandler()
                .getWorldDirectory(),
            MARK_CX,
            MARK_CZ);
        net.minecraft.nbt.NBTTagCompound level = raw == null ? new net.minecraft.nbt.NBTTagCompound()
            : net.minecraft.nbt.CompressedStreamTools.read(raw)
                .getCompoundTag("Level");
        if (raw != null) raw.close();
        int vanillaDeep = 0;
        net.minecraft.nbt.NBTTagList vanilla = level.getTagList("TileEntities", 10);
        for (int i = 0; i < vanilla.tagCount(); i++) if (vanilla.getCompoundTagAt(i)
            .getInteger("y") < 0) vanillaDeep++;
        int kept = level.getTagList(com.akashiic.bellow.storage.DeepTiles.KEY, 10)
            .tagCount();
        this.check(
            "deep tile entities stored apart",
            raw != null && vanillaDeep == 0 && kept == 1,
            "in TileEntities: " + vanillaDeep + ", in " + com.akashiic.bellow.storage.DeepTiles.KEY + ": " + kept);
        Chunk c = this.load(MARK_CX, MARK_CZ);
        int x = MARK_CX * 16 + 3, z = MARK_CZ * 16 + 5;
        IDeepChunk deep = (IDeepChunk) c;
        this.check("read from disk", deep.bellow$isFromDisk(), "");
        this.check(
            "gen state persisted",
            deep.bellow$getGenState() >= DeepConstants.GEN_TERRAIN,
            "gen=" + deep.bellow$getGenState());
        this.check(
            "deep block persisted",
            c.getBlock(x & 15, minY + 3, z & 15) == Blocks.diamond_block,
            CommandNames.name(c.getBlock(x & 15, minY + 3, z & 15)));
        this.check(
            "deep block meta persisted",
            this.world.getBlock(x, -1, z) == Blocks.wool && this.world.getBlockMetadata(x, -1, z) == 11,
            CommandNames.name(this.world.getBlock(x, -1, z), this.world.getBlockMetadata(x, -1, z)));
        TileEntity te = this.world.getTileEntity(x, minY + 9, z);
        net.minecraft.item.ItemStack stack = te instanceof TileEntityChest ? ((TileEntityChest) te).getStackInSlot(0)
            : null;
        this.check(
            "deep tile entity persisted",
            stack != null && stack.stackSize == 7
                && stack.getItem() == net.minecraft.item.Item.getItemFromBlock(Blocks.dirt),
            String.valueOf(te) + " " + stack);
        this.check("bedrock floor persisted", c.getBlock(0, minY, 0) == Blocks.bedrock, "");
    }

    /**
     * Server physics below 0, in a sealed cavity: an item lands on a deep stone floor (collision boxes, rewritten by
     * Spigot on Crucible) and a falling block lands as a block (CraftBukkit adds its own height test).
     */
    private void physics(int minY) {
        int cx = 1060, cz = 1060;
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) this.load(cx + dx, cz + dz);
        int x = cx * 16 + 8, z = cz * 16 + 8, floor = minY + 24;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                this.world.setBlock(x + dx, floor, z + dz, Blocks.stone, 0, 2);
                for (int y = floor + 1; y <= floor + 12; y++) this.world.setBlock(x + dx, y, z + dz, Blocks.air, 0, 2);
            }
        }
        net.minecraft.entity.item.EntityItem item = new net.minecraft.entity.item.EntityItem(
            this.world,
            x + 0.5,
            floor + 4.0,
            z + 0.5,
            new net.minecraft.item.ItemStack(Blocks.dirt));
        item.motionX = item.motionY = item.motionZ = 0;
        for (int i = 0; i < 60; i++) item.onUpdate();
        this.check(
            "entity collides below 0",
            !item.isDead && item.onGround && Math.abs(item.posY - (floor + 1)) < 0.3,
            String.format("posY=%.2f (floor block top %d) onGround=%s", item.posY, floor + 1, item.onGround));

        // Items are also pushed out of solid blocks (func_145771_j), which hides missing collision boxes: a mob is not.
        net.minecraft.entity.passive.EntityPig pig = new net.minecraft.entity.passive.EntityPig(this.world);
        pig.setLocationAndAngles(x - 0.5, floor + 3.0, z - 0.5, 0.0F, 0.0F);
        for (int i = 0; i < 60; i++) pig.onUpdate();
        this.check(
            "mob stands on a deep floor",
            !pig.isDead && Math.abs(pig.posY - (floor + 1)) < 0.3,
            String.format("posY=%.2f (floor block top %d) onGround=%s", pig.posY, floor + 1, pig.onGround));

        this.world.setBlock(x + 1, floor + 8, z, Blocks.sand, 0, 2);
        net.minecraft.entity.item.EntityFallingBlock sand = new net.minecraft.entity.item.EntityFallingBlock(
            this.world,
            x + 1.5,
            floor + 8.5,
            z + 0.5,
            Blocks.sand,
            0);
        for (int i = 0; i < 200 && !sand.isDead; i++) sand.onUpdate();
        Block landed = this.world.getBlock(x + 1, floor + 1, z);
        this.check(
            "falling block lands below 0",
            landed == Blocks.sand,
            "at floor+1: " + CommandNames.name(landed) + ", entity dead: " + sand.isDead);
        this.world.setBlock(x + 1, floor + 1, z, Blocks.air, 0, 2);
    }

    /** A nether portal built below 0 lights, survives a neighbour update and is found by the teleporter. */
    private void portal(int minY) {
        int cx = 1080, cz = 1080;
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) this.load(cx + dx, cz + dz);
        int x = cx * 16 + 4, z = cz * 16 + 8, y = minY + 30;
        for (int dx = -1; dx <= 4; dx++) for (int dy = 0; dy <= 5; dy++)
            for (int dz = -1; dz <= 1; dz++) this.world.setBlock(x + dx, y + dy, z + dz, Blocks.air, 0, 2);
        for (int dx = 0; dx <= 3; dx++) {
            this.world.setBlock(x + dx, y, z, Blocks.obsidian, 0, 2);
            this.world.setBlock(x + dx, y + 4, z, Blocks.obsidian, 0, 2);
        }
        for (int dy = 1; dy <= 3; dy++) {
            this.world.setBlock(x, y + dy, z, Blocks.obsidian, 0, 2);
            this.world.setBlock(x + 3, y + dy, z, Blocks.obsidian, 0, 2);
        }
        boolean lit = Blocks.portal.func_150000_e(this.world, x + 1, y + 1, z);
        this.check(
            "portal below 0 lights",
            lit && this.world.getBlock(x + 1, y + 1, z) == Blocks.portal,
            "created=" + lit + " at Y " + (y + 1));
        Blocks.portal.onNeighborBlockChange(this.world, x + 1, y + 2, z, Blocks.obsidian);
        this.check(
            "portal below 0 survives an update",
            this.world.getBlock(x + 1, y + 2, z) == Blocks.portal,
            CommandNames.name(this.world.getBlock(x + 1, y + 2, z)));
        net.minecraft.entity.item.EntityItem traveller = new net.minecraft.entity.item.EntityItem(
            this.world,
            x + 1.5,
            40.0,
            z + 0.5);
        boolean found = ((WorldServer) this.world).getDefaultTeleporter()
            .placeInExistingPortal(traveller, traveller.posX, traveller.posY, traveller.posZ, 0.0F);
        this.check(
            "teleporter finds a portal below 0",
            found && traveller.posY < 0,
            String.format("found=%s posY=%.1f", found, traveller.posY));

        // zombie pigmen spawn from a portal below 0 (on the obsidian under it); a random that always says "spawn"
        java.util.Random always = new java.util.Random() {

            @Override
            public int nextInt(int bound) {
                return 0;
            }
        };
        net.minecraft.util.AxisAlignedBB around = net.minecraft.util.AxisAlignedBB
            .getBoundingBox(x - 2, y - 2, z - 2, x + 5, y + 6, z + 3);
        int before = this.world.getEntitiesWithinAABB(net.minecraft.entity.monster.EntityPigZombie.class, around)
            .size();
        boolean spawning = this.world.getGameRules()
            .getGameRuleBooleanValue("doMobSpawning") && this.world.difficultySetting.getDifficultyId() > 0;
        Blocks.portal.updateTick(this.world, x + 1, y + 2, z, always);
        java.util.List<?> pigmen = this.world
            .getEntitiesWithinAABB(net.minecraft.entity.monster.EntityPigZombie.class, around);
        this.check(
            "portal below 0 spawns zombie pigmen",
            !spawning || pigmen.size() > before,
            spawning ? pigmen.size() - before + " spawned" : "mob spawning off or peaceful: not checked");
        for (Object e : pigmen) ((net.minecraft.entity.Entity) e).setDead();

        // the dragon egg falls below 0 (instant fall: straight to the ground)
        int ex = x + 1, ez = z + 3;
        for (int dy = 0; dy <= 8; dy++) this.world.setBlock(ex, y + dy, ez, dy == 0 ? Blocks.stone : Blocks.air, 0, 2);
        this.world.setBlock(ex, y + 8, ez, Blocks.dragon_egg, 0, 2);
        boolean instant = net.minecraft.block.BlockFalling.fallInstantly;
        net.minecraft.block.BlockFalling.fallInstantly = true;
        try {
            Blocks.dragon_egg.updateTick(this.world, ex, y + 8, ez, this.world.rand);
        } finally {
            net.minecraft.block.BlockFalling.fallInstantly = instant;
        }
        this.check(
            "dragon egg falls below 0",
            this.world.getBlock(ex, y + 1, ez) == Blocks.dragon_egg && this.world.isAirBlock(ex, y + 8, ez),
            "at the ground: " + CommandNames.name(this.world.getBlock(ex, y + 1, ez)));
        this.world.setBlockToAir(ex, y + 1, ez);
    }

    /** Spawn chunks of a world first generated with worldgen.enabled=false must have been filled on load. */
    private void retrogen(int minY) {
        net.minecraft.util.ChunkCoordinates spawn = this.world.getSpawnPoint();
        Chunk c = this.world.getChunkFromBlockCoords(spawn.posX, spawn.posZ);
        IDeepChunk deep = (IDeepChunk) c;
        if (!deep.bellow$isFromDisk()) {
            this.out.add("SKIP retrogen: spawn chunk was generated in this session");
            return;
        }
        boolean filled = c.getBlock(8, minY, 8) == Blocks.bedrock && c.getBlock(8, minY + 30, 8) != Blocks.air;
        boolean legacyGone = true;
        for (int y = 0; y <= GenConfig.legacyBedrockMaxY; y++)
            for (int i = 0; i < 256; i++) legacyGone &= c.getBlock(i & 15, y, i >> 4) != Blocks.bedrock;
        this.check(
            "retrogen of legacy chunk",
            filled && deep.bellow$getGenState() >= DeepConstants.GEN_TERRAIN,
            "chunk [" + c.xPosition + ", " + c.zPosition + "] gen=" + deep.bellow$getGenState());
        this.check("retrogen replaced legacy bedrock", !GenConfig.replaceLegacyBedrock || legacyGone, "");
    }

    /** Counts every block below Y 0 but air and bedrock, per 16-block band (bottom band first). */
    public static Map<String, long[]> histogram(World world, int cx0, int cz0, int radius) {
        int minY = BellowAPI.getMinY(world);
        int bands = Math.max(0, -minY >> 4);
        Map<String, long[]> counts = new HashMap<>();
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (!world.getChunkProvider()
                    .chunkExists(cx0 + dx, cz0 + dz)) continue;
                Chunk c = world.getChunkFromChunkCoords(cx0 + dx, cz0 + dz);
                for (int sy = minY >> 4; sy < 0; sy++) {
                    ExtendedBlockStorage ebs = BellowAPI.getDeepSection(c, sy);
                    if (ebs == null) continue;
                    int band = sy - (minY >> 4);
                    for (int i = 0; i < 4096; i++) {
                        int x = i & 15, y = i >> 8, z = i >> 4 & 15;
                        Block b = ebs.getBlockByExtId(x, y, z);
                        int meta = ebs.getExtBlockMetadata(x, y, z);
                        if (b == Blocks.bedrock || b == Blocks.air) continue;
                        counts.computeIfAbsent(CommandNames.name(b, meta), k -> new long[bands])[band]++;
                    }
                }
            }
        }
        return counts;
    }

    /** Block naming shared with the command. */
    static final class CommandNames {

        static String name(Block b) {
            return name(b, 0);
        }

        static String name(Block b, int meta) {
            return BellowCommand.name(b, meta);
        }
    }
}
