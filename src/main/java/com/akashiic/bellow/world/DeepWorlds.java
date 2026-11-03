package com.akashiic.bellow.world;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.ISaveHandler;

import com.akashiic.bellow.AkashicBellow;
import com.akashiic.bellow.BellowConfig;
import com.akashiic.bellow.api.DeepConstants;
import com.akashiic.bellow.gen.DeepGenerators;

/**
 * Resolves the floor of a world, and records the deep generators each dimension was generated with.
 * <p>
 * Server worlds: the first time an enabled dimension of a save is opened, the configured floor is written to
 * {@code <save>/akashicbellow.dat}. From then on the stored value wins over the config, so a world can never end up
 * with chunks generated for two different floors. A locked dimension keeps its floor even if it is later removed from
 * the config: running it with floor 0 would drop its deep tile entities and entities on the next save.
 * <p>
 * A dimension claimed by its owning mod ({@link com.akashiic.bellow.api.BellowAPI#claimDimension}) is enabled with the
 * claimed floor instead of the config; a lock already in the save still wins.
 * <p>
 * Client worlds start at 0 and are updated by the server ({@code DimensionInfoMessage}).
 */
public final class DeepWorlds {

    private static final String FILE_NAME = "akashicbellow.dat";
    private static final Map<File, NBTTagCompound> LOCKS = new HashMap<>();

    private DeepWorlds() {}

    public static synchronized int resolve(World world) {
        if (world.provider == null) return 0;
        // Client worlds: floor announced by the server (0 until then; the chunk packets also carry it).
        if (!(world instanceof WorldServer)) {
            Integer announced = world.isRemote ? ClientFloors.get(world.provider.dimensionId) : null;
            return announced == null ? 0 : announced;
        }
        int dim = world.provider.dimensionId;
        // a dimension claimed by its owning mod has deep layers with the claimed floor, whatever the config says
        Integer claimed = DeepGenerators.claimedFloor(dim);
        boolean enabled = claimed != null || BellowConfig.isDimensionEnabled(dim);
        int configured = claimed != null ? claimed : DeepConstants.sanitizeMinY(BellowConfig.minY);
        String source = claimed != null ? "claimed by " + DeepGenerators.owner(dim) : "configured";

        ISaveHandler handler = world.getSaveHandler();
        File dir = handler == null ? null : handler.getWorldDirectory();
        if (dir == null) {
            if (!enabled || configured >= 0) return 0;
            AkashicBellow.LOG
                .warn("Dimension {} has no save directory; using configured floor {} without locking", dim, configured);
            return configured;
        }

        NBTTagCompound lock = LOCKS.computeIfAbsent(dir.getAbsoluteFile(), DeepWorlds::readLock);
        NBTTagCompound floors = lock.getCompoundTag("Floors");
        String key = Integer.toString(dim);
        if (floors.hasKey(key)) {
            if (floors.func_150299_b(key) != 3) { // TAG_Int; any other type would read as a floor of 0
                throw new IllegalStateException(
                    "The deep floor lock of dimension " + dim
                        + " in "
                        + new File(dir, FILE_NAME)
                        + " is not an integer; refusing to guess the deep floor of this world");
            }
            int locked = DeepConstants.sanitizeMinY(floors.getInteger(key));
            if (!enabled) {
                AkashicBellow.LOG.warn(
                    "Dimension {} of '{}' is not in the config, but its deep layer is locked to minY {}; keeping it"
                        + " (a floor of 0 would discard its deep tile entities and entities when chunks are saved)",
                    dim,
                    dir.getName(),
                    locked);
            } else if (locked != configured) {
                AkashicBellow.LOG.warn(
                    "Dimension {} of '{}' is locked to minY {}; ignoring {} {} (delete {} only together with the deep chunk data)",
                    dim,
                    dir.getName(),
                    locked,
                    source,
                    configured,
                    FILE_NAME);
            }
            return locked;
        }
        if (!enabled || configured >= 0) return 0;

        // written first, cached after: a failed write must not leave a lock that exists only in memory
        NBTTagCompound updated = (NBTTagCompound) lock.copy();
        NBTTagCompound updatedFloors = updated.getCompoundTag("Floors");
        updatedFloors.setInteger(key, configured);
        updated.setTag("Floors", updatedFloors);
        writeLock(dir, updated);
        LOCKS.put(dir.getAbsoluteFile(), updated);
        AkashicBellow.LOG.info("Dimension {} of '{}' locked to minY {} ({})", dim, dir.getName(), configured, source);
        return configured;
    }

    /**
     * Records the deep generators of a dimension ({@code id@version}, in order) next to its floor and warns when they
     * changed since the last session: chunks generated from now on use the new list, so cave systems and borders may
     * not match the chunks generated before (generators decide the shape of a chunk from the seed and the position).
     */
    public static synchronized void recordGenerators(WorldServer world, List<String> generators) {
        ISaveHandler handler = world.getSaveHandler();
        File dir = handler == null ? null : handler.getWorldDirectory();
        if (dir == null) return;
        int dim = world.provider.dimensionId;
        NBTTagCompound lock = LOCKS.computeIfAbsent(dir.getAbsoluteFile(), DeepWorlds::readLock);
        NBTTagCompound all = lock.getCompoundTag("Generators");
        String key = Integer.toString(dim);
        List<String> before = null;
        if (all.hasKey(key, 9)) {
            before = new ArrayList<>();
            NBTTagList list = all.getTagList(key, 8);
            for (int i = 0; i < list.tagCount(); i++) before.add(list.getStringTagAt(i));
        }
        if (generators.equals(before)) return;
        if (before != null) {
            AkashicBellow.LOG.warn(
                "Deep generators of dimension {} of '{}' changed since the last session: {} -> {}. Chunks generated from"
                    + " now on use the new list; caves may not continue across the border with chunks generated before.",
                dim,
                dir.getName(),
                before,
                generators);
        } else if (!generators.isEmpty()) {
            AkashicBellow.LOG.info("Deep generators of dimension {} of '{}': {}", dim, dir.getName(), generators);
        }
        NBTTagList list = new NBTTagList();
        for (String g : generators) list.appendTag(new NBTTagString(g));
        NBTTagCompound updated = (NBTTagCompound) lock.copy();
        NBTTagCompound updatedAll = updated.getCompoundTag("Generators");
        updatedAll.setTag(key, list);
        updated.setTag("Generators", updatedAll);
        writeLock(dir, updated);
        LOCKS.put(dir.getAbsoluteFile(), updated);
    }

    /** The recorded generators of a dimension, or null (self test). */
    public static synchronized List<String> recordedGenerators(WorldServer world) {
        File dir = world.getSaveHandler() == null ? null
            : world.getSaveHandler()
                .getWorldDirectory();
        if (dir == null) return null;
        NBTTagCompound all = LOCKS.computeIfAbsent(dir.getAbsoluteFile(), DeepWorlds::readLock)
            .getCompoundTag("Generators");
        String key = Integer.toString(world.provider.dimensionId);
        if (!all.hasKey(key, 9)) return null;
        List<String> out = new ArrayList<>();
        NBTTagList list = all.getTagList(key, 8);
        for (int i = 0; i < list.tagCount(); i++) out.add(list.getStringTagAt(i));
        return out;
    }

    /** Forget cached lock files, e.g. when the integrated server stops and another save is opened. */
    public static synchronized void clearCache() {
        LOCKS.clear();
    }

    private static NBTTagCompound readLock(File dir) {
        File file = new File(dir, FILE_NAME);
        if (!file.isFile()) return new NBTTagCompound();
        try (FileInputStream in = new FileInputStream(file)) {
            return CompressedStreamTools.readCompressed(in);
        } catch (IOException e) {
            // A corrupt lock must not silently change the floor of an existing world.
            throw new IllegalStateException(
                "Cannot read " + file + "; refusing to guess the deep floor of this world",
                e);
        }
    }

    private static void writeLock(File dir, NBTTagCompound lock) {
        File file = new File(dir, FILE_NAME);
        File tmp = new File(dir, FILE_NAME + ".tmp");
        try {
            // compressed in memory: writeCompressed closes its stream, and the file must be synced before it closes
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            CompressedStreamTools.writeCompressed(lock, bytes);
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                bytes.writeTo(out);
                out.getFD()
                    .sync(); // on disk before the rename: a power loss must not leave an empty lock behind
            }
            // atomic where the file system allows it: a crash must never leave the save without its locks
            try {
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write " + file, e);
        }
    }
}
