package com.akashiic.bellow.net;

/** Implemented by {@code PlayerManager$PlayerInstance} through a mixin. */
public interface IDeepPlayerInstance {

    /** Sends the given deep slots to the players that already received this chunk. */
    void bellow$resendDeep(int slotMask);

    /**
     * Sends the given vanilla sections (bit n: Y 16n..16n+15) to the players that already received this chunk, as
     * Forge does when many blocks of a chunk changed in one tick, with the description of their tile entities.
     */
    void bellow$resendSections(int sectionMask);
}
