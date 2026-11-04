package com.akashiic.bellow.gen;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;

import com.akashiic.bellow.AkashicBellow;
import com.akashiic.bellow.GenConfig;
import com.akashiic.bellow.api.BellowAPI;
import com.akashiic.bellow.api.DeepConstants;
import com.akashiic.bellow.api.IDeepChunk;
import com.akashiic.bellow.storage.GeneratorRecords;
import com.akashiic.bellow.storage.IDeepChunkInternal;

/**
 * The stages that run after the world tick, outside any chunk load (where a World write can be captured as a block
 * snapshot and reverted, or flow recursively inside {@code scheduledUpdatesAreImmediate}):
 * <ol>
 * <li>the deep sections changed by decorations, sent once per chunk and tick to the players that already have
 * them;</li>
 * <li>{@code onDecorated} of the generators that decorated a chunk;</li>
 * <li>the light stage ({@link DeepLight}) of chunks decorated with generators present, once the 5x5 around them is
 * loaded and decorated, then {@code onLit}.</li>
 * </ol>
 * Chunks are queued when they are decorated and when a decorated chunk loads (it may complete the 5x5 of a neighbour,
 * or bring back a stage that could not run before it was unloaded). What is still due is saved with the chunk (the
 * light flag and the generator records), so a stage interrupted by an unload or a restart runs on a later load. At most
 * {@code stageBudgetMillis} per world tick; the rest waits for the next tick.
 */
public final class DeepStages {

    private static final int TRACED_ERRORS = 3;
    private static int lightErrors;

    /** Sections to send again, per chunk. */
    interface Resend {

        void add(Chunk chunk, int slotMask);
    }

    private static final class Queue implements Resend {

        /** Chunk keys to look at (with the 5x5 around them), in order. */
        final LinkedHashSet<Long> chunks = new LinkedHashSet<>();
        final Map<Chunk, Integer> resend = new IdentityHashMap<>();

        @Override
        public void add(Chunk chunk, int slotMask) {
            this.resend.merge(chunk, slotMask, (a, b) -> a | b);
        }
    }

    private static final Map<World, Queue> QUEUES = new WeakHashMap<>();

    private DeepStages() {}

    private static Queue queueOf(World world) {
        return QUEUES.computeIfAbsent(world, w -> new Queue());
    }

    private static long key(int cx, int cz) {
        return (long) cx << 32 | cz & 0xFFFFFFFFL;
    }

    /** Sections a decoration changed; sent after the world tick (merged with other changes of that tick). */
    static synchronized void resend(WorldServer world, Chunk chunk, int slotMask) {
        if (slotMask != 0) queueOf(world).add(chunk, slotMask);
    }

    /** A chunk was decorated with generators present, or a decorated chunk loaded: look at it after the world tick. */
    static synchronized void queue(WorldServer world, Chunk chunk) {
        queueOf(world).chunks.add(key(chunk.xPosition, chunk.zPosition));
    }

    /**
     * End of a world tick: sends the pending sections, then runs the due stages for at most the configured budget.
     *
     * @return whether chunks are still queued (they run on the next tick)
     */
    public static boolean tick(WorldServer world) {
        return run(world, GenConfig.stageBudgetMillis * 1_000_000L);
    }

    /** Self tests: everything queued, as the ticks would. */
    public static void drain(WorldServer world) {
        while (run(world, Long.MAX_VALUE / 4)) {
            // a stage may load chunks, which queues more
        }
    }

    private static boolean run(WorldServer world, long budgetNanos) {
        Queue q;
        synchronized (DeepStages.class) {
            q = QUEUES.get(world);
        }
        if (q == null) return false;
        flush(world, q);
        if (q.chunks.isEmpty()) return false;
        int minY = BellowAPI.getMinY(world);
        if (minY >= 0) {
            q.chunks.clear();
            return false;
        }
        long end = System.nanoTime() + budgetNanos;
        do {
            long key;
            synchronized (DeepStages.class) {
                Iterator<Long> it = q.chunks.iterator();
                key = it.next();
                it.remove();
            }
            int cx = (int) (key >> 32), cz = (int) key;
            for (int dz = -2; dz <= 2; dz++) {
                for (int dx = -2; dx <= 2; dx++) advance(world, q, cx + dx, cz + dz, minY);
            }
        } while (!q.chunks.isEmpty() && System.nanoTime() < end);
        flush(world, q);
        return !q.chunks.isEmpty();
    }

    private static void flush(WorldServer world, Queue q) {
        Map<Chunk, Integer> pending;
        synchronized (DeepStages.class) {
            if (q.resend.isEmpty()) return;
            pending = new IdentityHashMap<>(q.resend);
            q.resend.clear();
        }
        for (Map.Entry<Chunk, Integer> e : pending.entrySet())
            BellowAPI.resendDeepSections(world, e.getKey(), e.getValue());
    }

    private static Chunk loaded(WorldServer world, int cx, int cz) {
        IChunkProvider provider = world.getChunkProvider();
        return provider.chunkExists(cx, cz) ? provider.provideChunk(cx, cz) : null;
    }

    private static boolean decorated(Chunk c) {
        return c != null && ((IDeepChunk) c).bellow$getGenState() >= DeepConstants.GEN_DECORATED;
    }

    private static void advance(WorldServer world, Queue q, int cx, int cz, int minY) {
        Chunk c = loaded(world, cx, cz);
        if (!decorated(c)) return;
        if (DeepGenerators.pending(world, c, GeneratorRecords.ON_DECORATED)) {
            flush(world, q); // the decoration's sections first: onDecorated may rely on players having them
            DeepGenerators.onDecorated(world, c);
        }
        IDeepChunkInternal state = (IDeepChunkInternal) c;
        boolean light = state.bellow$isLightPending();
        boolean legacy = state.bellow$isLegacyRelight();
        if (!light && !legacy && !DeepGenerators.pending(world, c, GeneratorRecords.ON_LIT)) return;
        Chunk[] core = new Chunk[9];
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                Chunk n = loaded(world, cx + dx, cz + dz);
                if (!decorated(n)) return;
                if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) core[(dz + 1) * 3 + dx + 1] = n;
            }
        }
        if (light) {
            try {
                DeepLight.light(world, core, minY, q);
            } catch (Throwable e) {
                DeepGenerators.rethrowFatal(e);
                // a block of another mod failing in getLightValue: the chunk stays due and is tried again when it is
                // queued again (a later load around it); the server goes on
                int n = ++lightErrors;
                if (n <= TRACED_ERRORS || Integer.bitCount(n) == 1) {
                    AkashicBellow.LOG.error(
                        "Deep light stage failed for chunk [{}, {}] in dimension {} (error {})",
                        cx,
                        cz,
                        world.provider.dimensionId,
                        n,
                        e);
                }
                return;
            }
            state.bellow$setLightPending(false);
            c.setChunkModified();
        }
        if (legacy) {
            try {
                relightLegacyBand(world, c);
            } catch (Throwable e) {
                DeepGenerators.rethrowFatal(e);
                int n = ++lightErrors;
                if (n <= TRACED_ERRORS || Integer.bitCount(n) == 1) {
                    AkashicBellow.LOG.error(
                        "Light of the replaced legacy bedrock failed for chunk [{}, {}] in dimension {} (error {})",
                        cx,
                        cz,
                        world.provider.dimensionId,
                        n,
                        e);
                }
                return;
            }
            state.bellow$setLegacyRelight(false);
            c.setChunkModified();
        }
        flush(world, q); // the light first: onLit may rely on players having it
        if (legacy) resendSections(world, c, 1); // Y 0..15: the light computed now
        DeepGenerators.onLit(world, c);
    }

    /**
     * Air and fluid sources the owner of a claimed dimension put in place of the legacy bedrock: the World lights them
     * (sky and block light, its own rules and propagation), now that the 5x5 around is loaded (light updates reach 17
     * blocks) and nothing is being loaded.
     */
    static void relightLegacyBand(WorldServer world, Chunk c) {
        int maxY = Math.min(GenConfig.legacyBedrockMaxY, 15);
        int x0 = c.xPosition << 4, z0 = c.zPosition << 4;
        for (int y = 0; y <= maxY; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            net.minecraft.block.Block b = c.getBlock(x, y, z);
            if (b == net.minecraft.init.Blocks.air || b.getMaterial()
                .isLiquid()) world.func_147451_t(x0 + x, y, z0 + z);
        }
    }

    /** Sends vanilla sections of a chunk again to the players that already received it. */
    static void resendSections(WorldServer world, Chunk chunk, int sectionMask) {
        net.minecraft.util.LongHashMap instances = ((com.akashiic.bellow.mixin.early.common.PlayerManagerAccessor) world
            .getPlayerManager()).bellow$getPlayerInstances();
        long key = (long) chunk.xPosition + 2147483647L | (long) chunk.zPosition + 2147483647L << 32;
        Object instance = instances.getValueByKey(key);
        if (instance != null)
            ((com.akashiic.bellow.net.IDeepPlayerInstance) instance).bellow$resendSections(sectionMask);
    }

    /** Self test only: the sections of a chunk waiting to be sent after the tick (0 for none). */
    public static synchronized int pendingResend(World world, Chunk chunk) {
        Queue q = QUEUES.get(world);
        Integer mask = q == null ? null : q.resend.get(chunk);
        return mask == null ? 0 : mask;
    }

    /** Self test only: chunks queued for the stages. */
    public static synchronized int queued(World world) {
        Queue q = QUEUES.get(world);
        return q == null ? 0 : q.chunks.size();
    }

    /**
     * A world unloads: its queue goes (what is due is saved with the chunks). The queued sections hold chunks, which
     * hold the world, so a weak key alone would never let the world go.
     */
    public static synchronized void unload(World world) {
        QUEUES.remove(world);
    }

    /** Server stopped: what is due is saved with the chunks and resumes when they load again. */
    public static synchronized void clear() {
        QUEUES.clear();
    }
}
