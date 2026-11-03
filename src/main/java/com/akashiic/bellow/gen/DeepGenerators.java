package com.akashiic.bellow.gen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.akashiic.bellow.api.DeepConstants;
import com.akashiic.bellow.api.IDeepGenerator;
import com.akashiic.bellow.api.IDeepRegion;
import com.akashiic.bellow.storage.GeneratorRecords;
import com.akashiic.bellow.world.LegacyGeneration;

/**
 * Generators registered through {@link com.akashiic.bellow.api.BellowAPI#registerGenerator}. The list is frozen when
 * the first server starts (or the first chunk is generated) and ordered by priority, then id, so every chunk of a
 * session sees the same generators in the same order whatever order the mods loaded in. A generator that throws is
 * logged and skipped for that chunk; the chunk itself goes on. What each generator did to a chunk is recorded with the
 * chunk ({@link GeneratorRecords}).
 * <p>
 * A dimension can be claimed by the mod that owns it ({@link com.akashiic.bellow.api.BellowAPI#claimDimension}): it
 * gets deep layers with the claimed floor, and only the claiming generator runs there; a generator that claimed
 * dimensions runs only in them.
 */
public final class DeepGenerators {

    /** The mod's logger, by name: this class must load without the mod class (unit tests). */
    private static final Logger LOG = LogManager.getLogger("AkashicBellow");
    /** Full stack traces per generator before errors are only counted. */
    private static final int TRACED_ERRORS = 3;

    private static final List<Entry> entries = new ArrayList<>();
    private static volatile boolean frozen;
    /** Generators that apply to each world ({@link IDeepGenerator#appliesTo}), asked once per world. */
    private static final Map<World, Entry[]> perWorld = new WeakHashMap<>();
    private static final Entry[] NONE = {};
    /**
     * Claimed dimensions: the floor and the generator that owns each. Written only until the list is frozen; read
     * without the class lock, because the floor is resolved under {@code DeepWorlds}' lock, and {@code appliesTo},
     * asked under this one, may resolve a floor.
     */
    private static final Map<Integer, Claim> claims = new ConcurrentHashMap<>();

    private DeepGenerators() {}

    static final class Entry {

        final String id;
        final IDeepGenerator generator;
        final int priority;
        final int order;
        int errors;

        Entry(String id, IDeepGenerator generator, int priority, int order) {
            this.id = id;
            this.generator = generator;
            this.priority = priority;
            this.order = order;
        }
    }

    private static final class Claim {

        final int minY;
        final String owner;

        Claim(int minY, String owner) {
            this.minY = minY;
            this.owner = owner;
        }
    }

    public static synchronized void claim(int dimension, int minY, String owner) {
        if (owner == null || owner.isEmpty())
            throw new IllegalArgumentException("A dimension claim needs a generator id");
        if (minY >= 0 || minY < DeepConstants.ABSOLUTE_MIN_Y || (minY & 15) != 0) {
            throw new IllegalArgumentException(
                "Floor " + minY
                    + " of dimension "
                    + dimension
                    + " is not a multiple of 16 between "
                    + DeepConstants.ABSOLUTE_MIN_Y
                    + " and -16");
        }
        if (frozen) {
            throw new IllegalStateException(
                "Dimension " + dimension + " claimed too late: claim during preInit, init or postInit");
        }
        Claim claim = claims.get(dimension);
        if (claim != null) {
            if (claim.owner.equals(owner) && claim.minY == minY) return;
            throw new IllegalArgumentException(
                "Dimension " + dimension
                    + " is already claimed by "
                    + claim.owner
                    + " (floor "
                    + claim.minY
                    + "); "
                    + owner
                    + " cannot claim it with floor "
                    + minY);
        }
        claims.put(dimension, new Claim(minY, owner));
    }

    /** The generator that claimed a dimension, or null. */
    public static String owner(int dimension) {
        Claim claim = claims.get(dimension);
        return claim == null ? null : claim.owner;
    }

    /** The claimed floor of a dimension, or null when it is not claimed. */
    public static Integer claimedFloor(int dimension) {
        Claim claim = claims.get(dimension);
        return claim == null ? null : claim.minY;
    }

    public static synchronized void register(String id, IDeepGenerator generator, int priority) {
        if (id == null || id.isEmpty() || generator == null) {
            throw new IllegalArgumentException("Deep generator needs an id and an instance");
        }
        if (frozen) {
            throw new IllegalStateException(
                "Deep generator " + id + " registered too late: register during preInit, init or postInit");
        }
        for (Entry e : entries) {
            if (e.id.equals(id)) throw new IllegalArgumentException("Deep generator " + id + " is already registered");
        }
        entries.add(new Entry(id, generator, priority, entries.size()));
    }

    /** Ends registration, fixes the order and logs the generators (once). */
    public static synchronized void freeze() {
        if (frozen) return;
        entries
            .sort((a, b) -> a.priority != b.priority ? Integer.compare(a.priority, b.priority) : a.id.compareTo(b.id));
        frozen = true;
        if (entries.isEmpty()) {
            LOG.info("Deep generators: none registered");
        } else {
            LOG.info("Deep generators, in order: {}", describe(entries.toArray(NONE)));
        }
        for (Map.Entry<Integer, Claim> c : claims.entrySet()) {
            boolean registered = false;
            for (Entry e : entries) registered |= e.id.equals(c.getValue().owner);
            if (registered) {
                LOG.info(
                    "Dimension {} is claimed by {}: floor {}, only that generator runs there",
                    c.getKey(),
                    c.getValue().owner,
                    c.getValue().minY);
            } else {
                LOG.error(
                    "Dimension {} is claimed by {}, which is not a registered deep generator: no generator runs there"
                        + " (new chunks get the provisional layer, replaced once the generator is registered)",
                    c.getKey(),
                    c.getValue().owner);
            }
        }
    }

    public static synchronized List<String> ids() {
        List<String> ids = new ArrayList<>();
        for (Entry e : entries) ids.add(e.id);
        return Collections.unmodifiableList(ids);
    }

    public static boolean any() {
        return !entries.isEmpty();
    }

    /** Self test only: while true, no generator applies anywhere (the case of a server without a provider). */
    private static volatile boolean hiddenForTests;

    public static void hideForTests(boolean hidden) {
        hiddenForTests = hidden;
    }

    /** The generators that apply to a world, in order. A null world (unit tests) gets all of them. */
    static synchronized Entry[] forWorld(World world) {
        freeze();
        if (entries.isEmpty() || hiddenForTests) return NONE;
        if (!(world instanceof WorldServer)) return world == null ? entries.toArray(NONE) : NONE;
        Entry[] cached = perWorld.get(world);
        if (cached != null) return cached;
        cached = select(world.provider.dimensionId, (WorldServer) world);
        perWorld.put(world, cached);
        return cached;
    }

    /**
     * A claimed dimension gets its owner only, without asking anyone's {@code appliesTo}; a generator that claimed
     * dimensions runs only in them; the others answer {@code appliesTo}.
     */
    private static Entry[] select(int dimension, WorldServer world) {
        Claim claim = claims.get(dimension);
        List<Entry> list = new ArrayList<>();
        for (Entry e : entries) {
            boolean applies;
            if (claim != null) {
                applies = e.id.equals(claim.owner);
            } else if (ownsAny(e.id)) {
                applies = false;
            } else {
                try {
                    applies = e.generator.appliesTo(world);
                } catch (Throwable ex) {
                    rethrowFatal(ex);
                    LOG.error("Deep generator {} failed in appliesTo; skipping it in this world", e.id, ex);
                    applies = false;
                }
            }
            if (applies) list.add(e);
        }
        return list.toArray(NONE);
    }

    private static boolean ownsAny(String id) {
        for (Claim c : claims.values()) if (c.owner.equals(id)) return true;
        return false;
    }

    /** Tests only: the ids that run in a dimension (appliesTo asked with a null world). */
    static synchronized List<String> idsForDimension(int dimension) {
        freeze();
        List<String> ids = new ArrayList<>();
        if (hiddenForTests) return ids;
        for (Entry e : select(dimension, null)) ids.add(e.id);
        return ids;
    }

    /** Whether any generator applies to this world. */
    public static boolean anyFor(World world) {
        return forWorld(world).length > 0;
    }

    /** "id@version" of the generators of a world, in order: what {@code akashicbellow.dat} records per dimension. */
    public static List<String> describe(World world) {
        return describe(forWorld(world));
    }

    private static List<String> describe(Entry[] list) {
        List<String> out = new ArrayList<>();
        for (Entry e : list) {
            String version = version(e);
            out.add(version.isEmpty() ? e.id : e.id + "@" + version);
        }
        return out;
    }

    private static String version(Entry e) {
        try {
            String v = e.generator.version();
            return v == null ? "" : v;
        } catch (Throwable ex) {
            rethrowFatal(ex);
            return "";
        }
    }

    // Each call leaves the legacy population phase: a chunk load (and so a deep generator) can happen inside another
    // chunk's population, where World writes below 0 are refused for the 1.7.10 generators.

    static void generateTerrain(Chunk chunk, int minY, long seed, boolean retrogen) {
        Entry[] list = forWorld(chunk.worldObj);
        if (list.length == 0) return;
        int legacy = LegacyGeneration.suspend();
        StrictGuard.Context outer = StrictGuard.enter(chunk.worldObj, null, minY);
        try {
            for (Entry e : list) {
                StrictGuard.generator(e.id);
                try {
                    e.generator.generateTerrain(chunk, minY, seed, retrogen);
                    GeneratorRecords.done(chunk, e.id, GeneratorRecords.TERRAIN);
                } catch (Throwable ex) {
                    rethrowFatal(ex);
                    GeneratorRecords.failedAt(chunk, e.id, GeneratorRecords.TERRAIN);
                    failed(e, "generateTerrain", chunk, ex);
                }
            }
        } finally {
            StrictGuard.exit(outer);
            LegacyGeneration.resume(legacy);
        }
    }

    static void decorate(IDeepRegion region, Chunk centre) {
        Entry[] list = forWorld(centre.worldObj);
        if (list.length == 0) return;
        int legacy = LegacyGeneration.suspend();
        StrictGuard.Context outer = StrictGuard.enter(region.world(), region, region.minY());
        try {
            for (Entry e : list) {
                StrictGuard.generator(e.id);
                GeneratorRecords.setVersion(centre, e.id, version(e));
                try {
                    e.generator.decorate(region);
                    GeneratorRecords.done(centre, e.id, GeneratorRecords.DECORATE);
                } catch (Throwable ex) {
                    rethrowFatal(ex);
                    GeneratorRecords.failedAt(centre, e.id, GeneratorRecords.DECORATE);
                    failed(e, "decorate", centre, ex);
                }
            }
        } finally {
            StrictGuard.exit(outer);
            LegacyGeneration.resume(legacy);
        }
    }

    /** {@code onDecorated} of every generator that decorated this chunk and was not called yet. */
    static void onDecorated(WorldServer world, Chunk chunk) {
        callback(world, chunk, GeneratorRecords.ON_DECORATED);
    }

    /** {@code onLit} of every generator that decorated this chunk and was not called yet. */
    static void onLit(WorldServer world, Chunk chunk) {
        callback(world, chunk, GeneratorRecords.ON_LIT);
    }

    /** Whether a generator of this world still waits for that callback on this chunk. */
    static boolean pending(World world, Chunk chunk, int stage) {
        for (Entry e : forWorld(world)) {
            int done = GeneratorRecords.stages(chunk, e.id);
            if ((done & GeneratorRecords.DECORATE) != 0 && (done & stage) == 0) return true;
        }
        return false;
    }

    private static void callback(WorldServer world, Chunk chunk, int stage) {
        Entry[] list = forWorld(world);
        if (list.length == 0) return;
        int legacy = LegacyGeneration.suspend();
        try {
            for (Entry e : list) {
                int done = GeneratorRecords.stages(chunk, e.id);
                if ((done & GeneratorRecords.DECORATE) == 0 || (done & stage) != 0) continue;
                try {
                    if (stage == GeneratorRecords.ON_DECORATED) e.generator.onDecorated(world, chunk);
                    else e.generator.onLit(world, chunk);
                    GeneratorRecords.done(chunk, e.id, stage);
                } catch (Throwable ex) {
                    rethrowFatal(ex);
                    // retried when the chunk is queued again (a later load), a few times at most
                    GeneratorRecords.failedAt(chunk, e.id, stage);
                    failed(e, stage == GeneratorRecords.ON_DECORATED ? "onDecorated" : "onLit", chunk, ex);
                }
            }
        } finally {
            LegacyGeneration.resume(legacy);
        }
    }

    /**
     * The JVM itself is in trouble, or the stack is: let it through. A stack overflow is never the generator's alone:
     * the frames it unwound may be nested chunk loads (Forge's {@code originalLoadChunk} keeps the chunk in its
     * {@code loadingChunks} set and the vanilla generator its half-written buffers), so going on would corrupt later
     * chunk loads. Crashing with a report is the safe outcome.
     */
    static void rethrowFatal(Throwable ex) {
        if (ex instanceof ThreadDeath) throw (ThreadDeath) ex;
        if (ex instanceof VirtualMachineError) throw (VirtualMachineError) ex;
    }

    private static void failed(Entry e, String step, Chunk chunk, Throwable ex) {
        int n = ++e.errors;
        String where = "chunk " + chunk.xPosition + "," + chunk.zPosition;
        if (n <= TRACED_ERRORS) {
            LOG.error("Deep generator {} failed in {} at {} (error {}); the chunk goes on", e.id, step, where, n, ex);
        } else if (Integer.bitCount(n) == 1) {
            LOG.error("Deep generator {} failed in {} at {}: {} errors so far ({})", e.id, step, where, n, ex);
        }
    }

    /** Tests only. */
    static synchronized void resetForTests() {
        entries.clear();
        perWorld.clear();
        claims.clear();
        frozen = false;
    }
}
