package com.akashiic.bellow.api;

import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.LongHashMap;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import com.akashiic.bellow.gen.DeepGenerators;
import com.akashiic.bellow.gen.DeepStages;
import com.akashiic.bellow.mixin.early.common.PlayerManagerAccessor;
import com.akashiic.bellow.net.IDeepPlayerInstance;
import com.akashiic.bellow.storage.GeneratorRecords;
import com.akashiic.bellow.storage.IDeepChunkInternal;

/**
 * Stable entry points for other mods (deep cave generators, map mods, etc.).
 */
public final class BellowAPI {

    /**
     * Version of this API, raised whenever something is added or removed. 2: deep generators
     * ({@link #registerGenerator})
     * and {@link #resendDeepSections}. 3: generator stages after the world tick ({@link IDeepGenerator#onLit}, the
     * light
     * stage), per-chunk generator records and data ({@link #getGeneratorStages}, {@link #getGeneratorData}),
     * {@link IDeepGenerator#appliesTo}, {@link IDeepGenerator#version}, priorities,
     * {@link IDeepRegion#placeOre}/{@link IDeepRegion#placeBlob}/{@link IDeepRegion#genStateAt},
     * {@link #runDeepStages}.
     * 4: Bellow is only the core: it places no content below 0 (no fill, no bedrock floor, no ores), the generators
     * make
     * the deep layer and receive its sections empty; the fill and host predicates of API 3 ({@code getFillBlock},
     * {@code getFillMeta}, {@code isDeepFill}, {@code isDeepHost}, {@code isBaseStone}) are gone. 5: dimensions claimed
     * by
     * the mod that owns them ({@link #claimDimension}, {@link #getDimensionOwner}). 6: the owner of a claimed dimension
     * chooses what replaces the legacy bedrock there ({@link IDeepGenerator#legacyBedrockReplacement}). Read at
     * runtime: call
     * {@link #apiVersion()} (a mod compiled against version 2 that runs with version 1 gets a {@code NoSuchMethodError}
     * it can catch, instead of a value copied in at compile time).
     */
    public static int apiVersion() {
        return 6;
    }

    /** Same as {@link #apiVersion()}; not a compile-time constant, so javac does not copy it into other mods. */
    public static final int API_VERSION = apiVersion();

    private BellowAPI() {}

    /** Lowest valid block Y (inclusive). 0 for worlds without deep layers. */
    public static int getMinY(World world) {
        return world == null ? 0 : ((IDeepWorld) world).bellow$getMinY();
    }

    public static boolean hasDeepLayers(World world) {
        return getMinY(world) < 0;
    }

    /**
     * Returns the storage of a deep section ({@code minY >> 4 <= sectionY < 0}) or {@code null}; sections below the
     * world floor (dormant data the world itself never reads) are not exposed. Do not cache the result across ticks:
     * chunks may reload.
     */
    public static ExtendedBlockStorage getDeepSection(Chunk chunk, int sectionY) {
        int slot = DeepConstants.slot(sectionY);
        if (slot < 0 || sectionY << 4 < getMinY(chunk.worldObj)) return null;
        ExtendedBlockStorage[] deep = ((IDeepChunk) chunk).bellow$peekDeepSections();
        return deep == null ? null : deep[slot];
    }

    /**
     * Registers a deep generator under a unique id (conventionally {@code modid:name}) with priority 0. Allowed until
     * the first server starts: register during preInit, init or postInit.
     *
     * @throws IllegalArgumentException for a duplicate id
     * @throws IllegalStateException    after registration closed
     */
    public static void registerGenerator(String id, IDeepGenerator generator) {
        DeepGenerators.register(id, generator, 0);
    }

    /**
     * Registers a deep generator with a priority. Generators run by ascending priority, then by id, whatever order the
     * mods loaded in (that order can change when mods are added); the list is logged and recorded per dimension in
     * {@code akashicbellow.dat}, with a warning when it changes.
     *
     * @since API 3
     */
    public static void registerGenerator(String id, IDeepGenerator generator, int priority) {
        DeepGenerators.register(id, generator, priority);
    }

    /**
     * Claims a dimension for the mod that defines its deep layer, until the first server starts (like
     * {@link #registerGenerator}). The dimension gets deep layers with this floor whatever {@code general.dimensions}
     * and
     * {@code general.minY} say, and only the given generator runs there: no other generator is asked, not even its
     * {@link IDeepGenerator#appliesTo}. A generator that claims dimensions runs only in the dimensions it claimed. A
     * save whose dimension already has a locked floor keeps it, with a warning. While the claimed generator is not
     * registered, no generator runs in the dimension (an error is logged; new chunks get the provisional layer).
     *
     * @param minY the floor, a multiple of 16 from -512 to -16
     * @throws IllegalArgumentException when the dimension is already claimed by another generator or with another
     *                                  floor (the same claim twice is allowed), or for an invalid floor or id
     * @throws IllegalStateException    after the first server started
     * @since API 5
     */
    public static void claimDimension(int dimensionId, int minY, String generatorId) {
        DeepGenerators.claim(dimensionId, minY, generatorId);
    }

    /**
     * The generator that claimed a dimension ({@link #claimDimension}), or null.
     *
     * @since API 5
     */
    public static String getDimensionOwner(int dimensionId) {
        return DeepGenerators.owner(dimensionId);
    }

    /** Ids of the registered deep generators, in the order they run (fixed once the first server starts). */
    public static List<String> getGeneratorIds() {
        return DeepGenerators.ids();
    }

    /**
     * Runs the deep stages that normally run after each world tick (sending decorated sections, {@code onDecorated},
     * the light stage, {@code onLit}) for everything queued now, including what they queue while running. For self
     * tests and tools that load chunks outside the tick loop (a server-started handler, a pre-generator); a running
     * server does this by itself.
     *
     * @since API 3
     */
    public static void runDeepStages(WorldServer world) {
        DeepStages.drain(world);
    }

    /**
     * Whether the chunk's deep layer is decorated (any state from {@link DeepConstants#GEN_DECORATED} on).
     *
     * @since API 3
     */
    public static boolean isDecorated(Chunk chunk) {
        return ((IDeepChunk) chunk).bellow$getGenState() >= DeepConstants.GEN_DECORATED;
    }

    /**
     * The {@code DeepConstants.STAGE_*} bits of what a generator did to a chunk, saved with the chunk: 0 when the
     * generator never saw it (a chunk generated before it was installed, or in a world it does not apply to). Server
     * side.
     *
     * @since API 3
     */
    public static int getGeneratorStages(Chunk chunk, String generatorId) {
        return GeneratorRecords.stages(chunk, generatorId);
    }

    /**
     * Data a generator keeps per chunk, saved with the chunk's deep data and kept unchanged while the generator is not
     * installed. Created empty on first use. Server main thread; call {@link Chunk#setChunkModified()} after changing
     * it. Keep it small: it is part of every save of the chunk.
     *
     * @since API 3
     */
    public static NBTTagCompound getGeneratorData(Chunk chunk, String generatorId) {
        return GeneratorRecords.data(chunk, generatorId);
    }

    /**
     * Whether the chunk still waits for the light stage: it was decorated with generators present, and the 5x5 chunks
     * around it were not all loaded and decorated since.
     *
     * @since API 3
     */
    public static boolean isLightPending(Chunk chunk) {
        return ((IDeepChunkInternal) chunk).bellow$isLightPending();
    }

    /**
     * Sends deep sections of a chunk again to the players that already received it, for blocks written straight into
     * the sections (writes through {@code World.setBlock} are sent by the world itself, and
     * {@link IDeepRegion#setBlock}
     * is sent when decoration ends). Players that receive the chunk later get it whole anyway. Does not mark the chunk
     * for saving: call {@link Chunk#setChunkModified()} too.
     *
     * @param slotMask bit {@link DeepConstants#slot slot(sectionY)} set for each section to send
     */
    public static void resendDeepSections(WorldServer world, Chunk chunk, int slotMask) {
        if (slotMask == 0) return;
        LongHashMap instances = ((PlayerManagerAccessor) world.getPlayerManager()).bellow$getPlayerInstances();
        long key = (long) chunk.xPosition + 2147483647L | (long) chunk.zPosition + 2147483647L << 32;
        Object instance = instances.getValueByKey(key);
        if (instance != null) ((IDeepPlayerInstance) instance).bellow$resendDeep(slotMask);
    }
}
