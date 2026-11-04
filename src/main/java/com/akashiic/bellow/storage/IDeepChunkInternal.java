package com.akashiic.bellow.storage;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/**
 * The writable side of a chunk's deep state, implemented by {@link net.minecraft.world.chunk.Chunk} through a mixin.
 * Internal: the API ({@link com.akashiic.bellow.api.IDeepChunk}) is read-only, because these setters bypass every rule
 * it enforces (the floor, read-only chunks of a newer format, the generation stages).
 */
public interface IDeepChunkInternal {

    /** Never null; allocates on first call. Includes the dormant slots below the floor. */
    ExtendedBlockStorage[] bellow$getDeepSections();

    void bellow$setDeepSections(ExtendedBlockStorage[] sections);

    void bellow$setGenState(byte state);

    void bellow$setFromDisk(boolean fromDisk);

    /**
     * The deep layer is the core's provisional one (stone over bedrock, made while no generator applied): the chunk is
     * still not generated, and the first generator to see it receives it as a retrogen chunk.
     */
    boolean bellow$isProvisional();

    void bellow$setProvisional(boolean provisional);

    /**
     * The owner of a claimed dimension replaced legacy bedrock at Y 0..4 with air or a fluid: those positions are lit
     * by
     * the World's light engine at the light stage, and the section sent again.
     */
    boolean bellow$isLegacyRelight();

    void bellow$setLegacyRelight(boolean relight);

    /** The chunk still waits for the light stage (it was decorated with deep generators present). */
    boolean bellow$isLightPending();

    void bellow$setLightPending(boolean pending);

    /**
     * Per-generator records ({@code id -> compound}), null when the chunk has none. Records of generators that are not
     * installed now are kept as they are; see {@link GeneratorRecords}.
     */
    NBTTagCompound bellow$getGeneratorRecords();

    void bellow$setGeneratorRecords(NBTTagCompound records);
}
