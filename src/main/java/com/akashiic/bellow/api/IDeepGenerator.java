package com.akashiic.bellow.api;

import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

/**
 * A generator that shapes the deep layer ({@code minY <= y < 0}) together with Bellow, registered with
 * {@link BellowAPI#registerGenerator}. Every method runs on the server thread, for dimensions with deep layers only,
 * and has an empty default. Generators run by priority, then id; an exception is logged and the chunk goes on (a stack
 * overflow or another {@code VirtualMachineError} is not caught: it crashes the server with a report, because the
 * frames it unwound may have been nested chunk loads).
 * <p>
 * Bellow places no content below 0: the generators make the deep layer (its fill, its floor, its ores, its caves).
 * Order for one chunk, mirroring 1.18 (noise terrain and carvers, then features, then light):
 * <ol>
 * <li>Bellow creates the sections from the floor up to Y -1, empty.</li>
 * <li>{@link #generateTerrain} of every generator. Then Bellow replaces the legacy bedrock at Y 0..4 where Y -1 is
 * solid (its only write at Y >= 0), opening the old world into the new layer.</li>
 * <li>Once the chunk and its 8 neighbours have their terrain: {@link #decorate} of every generator. The chunk is marked
 * decorated.</li>
 * <li>After the world tick: the changed sections are sent again to players who already received them, then
 * {@link #onDecorated}.</li>
 * <li>After the world tick, once the 5x5 chunks around it are loaded and decorated: Bellow lights what the generators
 * placed (block light of every light source of the chunk, below 0), then {@link #onLit}.</li>
 * </ol>
 * A dimension without any generator gets a provisional layer (stone over a bedrock floor) in chunks that stay not
 * generated; the first generator installed later receives those chunks as retrogen chunks.
 * <p>
 * Each step runs once per chunk, ever: what each generator did is saved with the chunk
 * ({@link BellowAPI#getGeneratorStages}), so a step that could not run before the chunk was unloaded (or the server
 * stopped) runs when it loads again. Chunks whose deep layer was generated before a generator was installed are never
 * handed to it, so it cannot cut through what players built there.
 */
public interface IDeepGenerator {

    /**
     * Whether this generator works in a world (a dimension with deep layers). Asked once per world, when it is first
     * generated in. Default: every such world. Not asked in a dimension claimed through
     * {@link BellowAPI#claimDimension} (only its owner runs there), nor for a generator that claimed dimensions (it
     * runs
     * only in those).
     *
     * @since API 3
     */
    default boolean appliesTo(WorldServer world) {
        return true;
    }

    /**
     * Version of what this generator produces (its settings included, when they change the shape of the world).
     * Recorded per dimension and per decorated chunk; Bellow warns when the generators of a dimension change between
     * sessions. Default: none.
     *
     * @since API 3
     */
    default String version() {
        return "";
    }

    /**
     * Terrain of one chunk, from inside {@code Chunk.onChunkLoad}: the whole deep layer of a new chunk (fill, floor,
     * carved space). Every section from {@code minY >> 4} to -1 exists and is empty, except what the chunk already held
     * below 0 on retrogen: blocks players or other mods put there, which must stay, and on a chunk made while no
     * generator was installed, Bellow's provisional layer (stone, bedrock at the floor), which is meant to be replaced.
     * Empty means no blocks: with ArchaicFix's lighting {@code ExtendedBlockStorage.isEmpty()} also counts light and
     * may be false for a section without blocks.
     * <p>
     * Contract: touch this chunk only (no neighbour, no {@code World} block or tile entity access, no entities), only
     * {@code minY..-1}, through {@link BellowAPI#getDeepSection}. Blocks only: no tile entities, no light or neighbour
     * updates (light sources are lit by the light stage, see {@link #onLit}). Keep Y -1 solid where the old world
     * should
     * open into the layer: the legacy bedrock above is replaced only there. Must be a function of the seed and the
     * chunk
     * position, so chunk borders match whatever order chunks are generated in.
     *
     * @param retrogen true when the chunk was saved before it had a deep layer (a chunk from an explored world, or one
     *                 with the provisional layer only, that gets its deep layer now); its blocks at {@code y >= 0} may
     *                 be player-built
     */
    default void generateTerrain(Chunk chunk, int minY, long seed, boolean retrogen) {}

    /**
     * Features of the centre chunk of a 3x3 region. Origins belong in the centre chunk; writes
     * may reach the 8 neighbours (all have their terrain) through {@link IDeepRegion#setBlock},
     * {@link IDeepRegion#placeOre} and {@link IDeepRegion#placeBlob}, which only write {@code minY..-1} and send the
     * changes to players. No light updates here either; no World writes (World reads past the region load chunks).
     * <p>
     * A neighbour decorated before ({@link IDeepRegion#genStateAt}) may be old and lived in. When a generator is added
     * to a world that already has deep layers, the chunks at the edge of what was generated before (terrain done, not
     * yet decorated) reach this step without having gone through this generator's {@link #generateTerrain}
     * ({@link BellowAPI#getGeneratorStages} without {@link DeepConstants#STAGE_TERRAIN}): decorate what is found rather
     * than assuming it.
     */
    default void decorate(IDeepRegion region) {}

    /**
     * After the chunk was decorated and its changed sections sent, from the end of a world tick (outside any chunk
     * load). The chunk is loaded; its neighbours usually are, but check ({@code chunkExists}) before relying on them:
     * World access to an unloaded chunk loads or generates it (which can decorate more chunks, and call this again).
     * Light of World writes is updated only when the chunks within 17 blocks are loaded: place light sources in
     * {@link #onLit}, or through {@link IDeepRegion#setBlock} and let the light stage light them.
     */
    default void onDecorated(WorldServer world, Chunk chunk) {}

    /**
     * After Bellow's light stage lit the chunk (the block light of every light source in its deep layer, spread over
     * the 3x3 around it), from the end of a world tick. At this point the 5x5 chunks around it are loaded and
     * decorated: World writes in this chunk update light and notify neighbours normally (a fluid that should start
     * flowing, a block that needs a neighbour update), and no decoration will write into the 3x3 around it any more.
     * Only for chunks this generator decorated.
     *
     * @since API 3
     */
    default void onLit(WorldServer world, Chunk chunk) {}
}
