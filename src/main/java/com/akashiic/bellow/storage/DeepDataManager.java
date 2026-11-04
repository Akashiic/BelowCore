package com.akashiic.bellow.storage;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.akashiic.bellow.AkashicBellow;
import com.akashiic.bellow.api.BellowAPI;
import com.akashiic.bellow.api.DeepConstants;
import com.akashiic.bellow.api.IDeepChunk;
import com.akashiic.bellow.api.IDeepWorld;
import com.akashiic.bellow.compat.angelica.AngelicaTiles;
import com.akashiic.bellow.core.Compat;
import com.akashiic.bellow.net.DeepNet;
import com.falsepattern.chunk.api.DataManager;
import com.falsepattern.chunk.internal.DataRegistryImpl;

/**
 * Persists and transports the deep sections through ChunkAPI.
 * <p>
 * NBT: {@code akashicbellow.deep = {V, Gen, Sections:[{Y:signed byte, <sub-chunk managers>}]}}. Sections are written by
 * the same sub-chunk managers that write vanilla sections (vanilla ids/meta/light, EndlessIDs), so the format of a deep
 * section is identical to a vanilla one. They are never stored in the vanilla {@code Sections} list: the vanilla and
 * ChunkAPI readers index an array with {@code Y} and would throw on negative values.
 * <p>
 * Packet: {@code short minY, int slotMask, then per set bit: int length, ChunkAPI cubic blob}. Empty for chunks without
 * deep data and for the "unload" form of S21 ({@code forceUpdate && mask == 0} on a column with vanilla blocks).
 */
public final class DeepDataManager implements DataManager.ChunkDataManager, DataManager.PacketDataManager {

    public static final String DOMAIN = AkashicBellow.MODID;
    public static final String ID = "deep";
    private static final byte NBT_VERSION = 1;

    /** Upper bound for one section blob when the registry reports less (EndlessIDs worst case is ~25 KB). */
    private static final int SECTION_BOUND = 32 * 1024;

    // The budget is read by ChunkAPI when the manager is registered and used by the client to size its reads, so it
    // must not depend on the configured floor (client and server configs may differ): it covers all 32 slots.

    private final int maxPacketSize;
    private final ThreadLocal<byte[]> scratch;
    private boolean warnedOverflow;

    public DeepDataManager() {
        int perSection = Math.max(DataRegistryImpl.maxPacketSizeCubic(), SECTION_BOUND);
        this.maxPacketSize = 2 + 4 + DeepConstants.MAX_DEEP_SECTIONS * (4 + perSection);
        this.scratch = ThreadLocal.withInitial(() -> new byte[perSection]);
    }

    @Override
    public @NotNull String domain() {
        return DOMAIN;
    }

    @Override
    public @NotNull String id() {
        return ID;
    }

    /** The NBT format version: ChunkAPI records it in level.dat and asks {@link #versionChangeMessage} on a change. */
    @Override
    public @NotNull String version() {
        return Integer.toString(NBT_VERSION);
    }

    @Override
    public @Nullable String newInstallDescription() {
        return "Akashic Bellow stores the blocks below Y 0 of each chunk under the 'akashicbellow' tag."
            + " Chunks saved before the install receive their deep layer from the deep generators when they are loaded.";
    }

    @Override
    public @NotNull String uninstallMessage() {
        return "Akashic Bellow removed: every block below Y 0 is discarded when its chunk is saved again."
            + " Natural bedrock that the mod replaced at Y 0..4 is not restored.";
    }

    @Override
    public @Nullable String versionChangeMessage(String priorVersion) {
        int prior;
        try {
            prior = Integer.parseInt(priorVersion);
        } catch (NumberFormatException e) {
            return null;
        }
        if (prior <= NBT_VERSION) return null;
        return "This world was saved by a newer Akashic Bellow (deep data format " + prior
            + ", this version reads "
            + NBT_VERSION
            + "). Its chunks keep their deep data on disk, but nothing below Y 0 is loaded: no blocks, tile entities"
            + " or entities there until the newer version is installed again.";
    }

    // ------------------------------------------------------------------ NBT

    /**
     * Deep data written by a newer format version, kept verbatim and written back unchanged (a downgrade must not
     * rewrite data it cannot read). Such chunks show no deep layer in this session and are not retro-generated.
     */
    private static final Map<Chunk, NBTTagCompound> NEWER = Collections.synchronizedMap(new WeakHashMap<>());
    private static boolean warnedNewer;

    private static volatile boolean anyNewer;

    /** Deep data of this chunk is of a newer format: nothing below Y 0 is loaded (see {@link DeepTiles}). */
    public static boolean isNewer(Chunk chunk) {
        return anyNewer && NEWER.containsKey(chunk);
    }

    /** Self test only: treats a loaded chunk as one of a newer format (true), or not any more (false). */
    public static void markNewerForTests(Chunk chunk, boolean newer) {
        if (newer) {
            NEWER.put(chunk, new NBTTagCompound());
            anyNewer = true;
        } else {
            NEWER.remove(chunk);
        }
    }

    /** Whether any chunk of a newer format was read in this session (keeps the World write check free otherwise). */
    public static boolean anyNewer() {
        return anyNewer;
    }

    /**
     * Sections that could not be read, per slot, kept verbatim and written back while the slot stays empty: one bad
     * section must not cost the chunk (an exception here fails the whole chunk load, and Forge regenerates it).
     */
    private static final Map<Chunk, NBTTagCompound[]> UNREADABLE = Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public void writeChunkToNBT(Chunk chunk, NBTTagCompound nbt) {
        NBTTagCompound newer = NEWER.get(chunk);
        if (newer != null) {
            for (Object key : newer.func_150296_c()) nbt.setTag(
                (String) key,
                newer.getTag((String) key)
                    .copy());
            return;
        }
        IDeepChunk deep = (IDeepChunk) chunk;
        IDeepChunkInternal state = (IDeepChunkInternal) chunk;
        nbt.setByte("V", NBT_VERSION);
        nbt.setByte("Gen", deep.bellow$getGenState());
        // added in format 1 without raising it: older versions ignore these keys (and drop them on save)
        if (state.bellow$isLightPending()) nbt.setByte("L", (byte) 1);
        // the core's provisional layer, still to be replaced by a generator (also added without raising the format)
        if (state.bellow$isProvisional()) nbt.setByte("P", (byte) 1);
        // legacy bedrock replaced with air or a fluid by a dimension's owner, still to be lit (same)
        if (state.bellow$isLegacyRelight()) nbt.setByte("R", (byte) 1);
        NBTTagCompound records = state.bellow$getGeneratorRecords();
        // a copy: the compound is written later on the file IO thread, while the main thread keeps updating records
        if (records != null && !records.hasNoTags()) nbt.setTag("Gens", records.copy());
        ExtendedBlockStorage[] sections = deep.bellow$peekDeepSections();
        NBTTagCompound[] unreadable = UNREADABLE.get(chunk);
        if (sections == null && unreadable == null) return;
        NBTTagList list = new NBTTagList();
        for (int slot = 0; slot < DeepConstants.MAX_DEEP_SECTIONS; slot++) {
            ExtendedBlockStorage ebs = sections == null ? null : sections[slot];
            if (ebs == null) {
                if (unreadable != null && unreadable[slot] != null) list.appendTag(unreadable[slot].copy());
                continue;
            }
            NBTTagCompound tag = new NBTTagCompound();
            tag.setByte("Y", (byte) (slot - DeepConstants.MAX_DEEP_SECTIONS));
            DataRegistryImpl.writeSubChunkToNBT(chunk, ebs, tag);
            list.appendTag(tag);
        }
        nbt.setTag("Sections", list);
    }

    @Override
    public void readChunkFromNBT(Chunk chunk, NBTTagCompound nbt) {
        // May run on the chunk IO thread: touch nothing but this chunk.
        IDeepChunkInternal deep = (IDeepChunkInternal) chunk;
        deep.bellow$setFromDisk(true);
        if (nbt.hasKey("V") && nbt.getByte("V") > NBT_VERSION) {
            NEWER.put(chunk, (NBTTagCompound) nbt.copy());
            anyNewer = true;
            deep.bellow$setGenState(DeepConstants.GEN_DECORATED);
            if (!warnedNewer) {
                warnedNewer = true;
                AkashicBellow.LOG.error(
                    "Chunk [{}, {}] has deep data in format {} (this version reads {}): it is kept unchanged on disk but"
                        + " not loaded; install the Akashic Bellow version that wrote it",
                    chunk.xPosition,
                    chunk.zPosition,
                    nbt.getByte("V"),
                    NBT_VERSION);
            }
            return;
        }
        deep.bellow$setGenState(nbt.getByte("Gen"));
        deep.bellow$setLightPending(nbt.getByte("L") != 0);
        deep.bellow$setProvisional(nbt.getByte("P") != 0);
        deep.bellow$setLegacyRelight(nbt.getByte("R") != 0);
        // copied: a chunk loaded again while its save is still queued is read from the very compound being written
        deep.bellow$setGeneratorRecords(
            nbt.hasKey("Gens", 10) ? (NBTTagCompound) nbt.getCompoundTag("Gens")
                .copy() : null);
        if (!nbt.hasKey("Sections", 9)) return;
        NBTTagList list = nbt.getTagList("Sections", 10);
        if (list.tagCount() == 0) return;
        boolean hasSky = !chunk.worldObj.provider.hasNoSky;
        ExtendedBlockStorage[] sections = new ExtendedBlockStorage[DeepConstants.MAX_DEEP_SECTIONS];
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound tag = list.getCompoundTagAt(i);
            int sectionY = tag.getByte("Y");
            int slot = DeepConstants.slot(sectionY);
            if (slot < 0) {
                AkashicBellow.LOG.error(
                    "Chunk [{}, {}]: ignoring deep section with invalid Y {}",
                    chunk.xPosition,
                    chunk.zPosition,
                    sectionY);
                continue;
            }
            ExtendedBlockStorage ebs = new ExtendedBlockStorage(sectionY << 4, hasSky);
            try {
                DataRegistryImpl.readSubChunkFromNBT(chunk, ebs, tag);
                ebs.removeInvalidBlocks();
            } catch (RuntimeException e) {
                AkashicBellow.LOG.error(
                    "Chunk [{}, {}]: deep section Y {} cannot be read; it is kept unchanged on disk and loads as empty",
                    chunk.xPosition,
                    chunk.zPosition,
                    sectionY,
                    e);
                NBTTagCompound[] bad = UNREADABLE.get(chunk);
                if (bad == null) UNREADABLE.put(chunk, bad = new NBTTagCompound[DeepConstants.MAX_DEEP_SECTIONS]);
                bad[slot] = (NBTTagCompound) tag.copy();
                continue;
            }
            sections[slot] = ebs;
        }
        deep.bellow$setDeepSections(sections);
    }

    @Override
    public void cloneChunk(Chunk from, Chunk to) {
        NBTTagCompound newer = NEWER.get(from);
        if (newer != null) {
            NEWER.put(to, (NBTTagCompound) newer.copy());
            anyNewer = true;
        }
        NBTTagCompound[] unreadable = UNREADABLE.get(from);
        if (unreadable != null) UNREADABLE.put(to, unreadable.clone());
        IDeepChunk src = (IDeepChunk) from;
        IDeepChunkInternal srcState = (IDeepChunkInternal) from;
        IDeepChunkInternal dst = (IDeepChunkInternal) to;
        dst.bellow$setGenState(src.bellow$getGenState());
        dst.bellow$setFromDisk(src.bellow$isFromDisk());
        dst.bellow$setLightPending(srcState.bellow$isLightPending());
        dst.bellow$setProvisional(srcState.bellow$isProvisional());
        dst.bellow$setLegacyRelight(srcState.bellow$isLegacyRelight());
        NBTTagCompound records = srcState.bellow$getGeneratorRecords();
        dst.bellow$setGeneratorRecords(records == null ? null : (NBTTagCompound) records.copy());
        ExtendedBlockStorage[] sections = src.bellow$peekDeepSections();
        if (sections == null) {
            dst.bellow$setDeepSections(null);
            return;
        }
        boolean hasSky = !to.worldObj.provider.hasNoSky;
        ExtendedBlockStorage[] copy = new ExtendedBlockStorage[sections.length];
        for (int slot = 0; slot < sections.length; slot++) {
            ExtendedBlockStorage ebs = sections[slot];
            if (ebs == null) continue;
            ExtendedBlockStorage c = new ExtendedBlockStorage(ebs.getYLocation(), hasSky);
            DataRegistryImpl.cloneSubChunk(from, ebs, c);
            c.removeInvalidBlocks();
            copy[slot] = c;
        }
        dst.bellow$setDeepSections(copy);
    }

    // ------------------------------------------------------------------ packets

    @Override
    public int maxPacketSize() {
        return this.maxPacketSize;
    }

    @Override
    public void writeToBuffer(Chunk chunk, int subChunkMask, boolean forceUpdate, ByteBuffer buffer) {
        // S21 "unload chunk" form (forceUpdate, mask 0). S26 also sends mask 0 for a column whose vanilla sections are
        // all empty (a mined-out column above a deep base): that one must still carry the deep layer.
        if (forceUpdate && subChunkMask == 0 && hasVanillaBlocks(chunk)) return;
        int minY = BellowAPI.getMinY(chunk.worldObj);
        if (minY >= 0) return;
        ExtendedBlockStorage[] sections = ((IDeepChunk) chunk).bellow$peekDeepSections();
        if (sections == null) return;

        int wanted = forceUpdate ? -1 : DeepNet.pendingSlotMask();
        int firstSlot = (minY >> 4) + DeepConstants.MAX_DEEP_SECTIONS;
        int mask = 0;
        for (int slot = firstSlot; slot < sections.length; slot++) {
            if (sections[slot] != null && (wanted & 1 << slot) != 0) mask |= 1 << slot;
        }
        if (mask == 0) return;
        // The budget (32 * (4 + scratch)) always holds the sections, unless a sub-chunk manager registered after this
        // one made a section blob larger than the scratch buffer: then send no deep data rather than throw.
        if (DataRegistryImpl.maxPacketSizeCubic() > this.scratch.get().length) {
            if (!this.warnedOverflow) {
                this.warnedOverflow = true;
                AkashicBellow.LOG.error(
                    "A sub-chunk data manager needs {} bytes per section, more than the {} reserved when Akashic Bellow"
                        + " registered; deep chunk data is not sent to clients",
                    DataRegistryImpl.maxPacketSizeCubic(),
                    this.scratch.get().length);
            }
            return;
        }

        buffer.putShort((short) minY);
        buffer.putInt(mask);
        byte[] tmp = this.scratch.get();
        for (int slot = firstSlot; slot < sections.length; slot++) {
            if ((mask & 1 << slot) == 0) continue;
            int len = DataRegistryImpl.writeToBufferCubic(chunk, sections[slot], tmp);
            buffer.putInt(len);
            buffer.put(tmp, 0, len);
        }
    }

    private static boolean hasVanillaBlocks(Chunk chunk) {
        for (ExtendedBlockStorage ebs : chunk.getBlockStorageArray()) {
            if (ebs != null && !ebs.isEmpty()) return true;
        }
        return false;
    }

    @Override
    public void readFromBuffer(Chunk chunk, int subChunkMask, boolean forceUpdate, ByteBuffer buffer) {
        IDeepChunkInternal deep = (IDeepChunkInternal) chunk;
        if (!buffer.hasRemaining()) {
            if (forceUpdate) deep.bellow$setDeepSections(null);
            return;
        }
        int minY = buffer.getShort();
        World world = chunk.worldObj;
        if (((IDeepWorld) world).bellow$getMinY() != minY)
            ((com.akashiic.bellow.world.IDeepWorldInternal) world).bellow$setMinY(minY);

        int mask = buffer.getInt();
        ExtendedBlockStorage[] sections = deep.bellow$getDeepSections();
        boolean hasSky = !world.provider.hasNoSky;
        byte[] tmp = this.scratch.get();
        for (int slot = 0; slot < sections.length; slot++) {
            if ((mask & 1 << slot) == 0) {
                if (forceUpdate) sections[slot] = null;
                continue;
            }
            int len = buffer.getInt();
            byte[] data = len <= tmp.length ? tmp : new byte[len];
            buffer.get(data, 0, len);
            ExtendedBlockStorage ebs = sections[slot];
            if (ebs == null)
                ebs = sections[slot] = new ExtendedBlockStorage((slot - DeepConstants.MAX_DEEP_SECTIONS) << 4, hasSky);
            DataRegistryImpl.readFromBufferCubic(chunk, ebs, data);
            ebs.removeInvalidBlocks();
        }
        if (world.isRemote) {
            createTileEntities(chunk, sections, mask);
            int x = chunk.xPosition << 4;
            int z = chunk.zPosition << 4;
            world.markBlockRangeForRenderUpdate(x, minY, z, x + 15, -1, z + 15);
        }
    }

    /**
     * Client side: creates the tile entities of the received deep sections, as vanilla does lazily and Angelica eagerly
     * for Y >= 0 at the end of {@code Chunk.fillChunk}. Angelica's chunk meshing reads tile entities from a snapshot
     * and
     * forbids creating them on its worker threads, so a deep tile entity that is not created here is never rendered.
     */
    private static void createTileEntities(Chunk chunk, ExtendedBlockStorage[] sections, int mask) {
        for (int slot = 0; slot < sections.length; slot++) {
            ExtendedBlockStorage ebs = sections[slot];
            if ((mask & 1 << slot) == 0 || ebs == null || ebs.isEmpty()) continue;
            int baseY = (slot - DeepConstants.MAX_DEEP_SECTIONS) << 4;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                Block block = ebs.getBlockByExtId(x, y, z);
                int meta = ebs.getExtBlockMetadata(x, y, z);
                if (block == Blocks.air || !block.hasTileEntity(meta)) continue;
                TileEntity existing = (TileEntity) chunk.chunkTileEntityMap.get(new ChunkPosition(x, baseY + y, z));
                TileEntity te = block.createTileEntity(chunk.worldObj, meta);
                if (te == null) continue;
                // keep a live tile entity of the same kind (a partial update of a block that stayed a chest); replace
                // one left over from a different block (chest -> furnace in a bulk change)
                if (existing != null && !existing.isInvalid() && existing.getClass() == te.getClass()) continue;
                te.xCoord = (chunk.xPosition << 4) + x;
                te.yCoord = baseY + y;
                te.zCoord = (chunk.zPosition << 4) + z;
                // ChunkAPI's fillChunk ends by invalidating every tile entity whose cached block or metadata differs
                // from the chunk (blockMetadata starts at -1), which Forge's shouldRefresh confirms for modded ones
                te.blockType = block;
                te.blockMetadata = meta;
                if (Compat.angelica) AngelicaTiles.awaitDescriptor(te);
                chunk.addTileEntity(te);
            }
        }
    }
}
