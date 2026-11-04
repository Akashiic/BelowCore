package com.akashiic.bellow.mixin.early.common;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.akashiic.bellow.api.DeepConstants;
import com.akashiic.bellow.api.IDeepChunk;
import com.akashiic.bellow.api.IDeepWorld;
import com.akashiic.bellow.storage.DeepDataManager;
import com.akashiic.bellow.storage.IDeepChunkInternal;
import com.akashiic.bellow.storage.IDormantData;

/**
 * Routes {@code storageArrays[y >> 4]} to the deep sections when the index is negative.
 * <p>
 * The block/metadata/light reads only redirect array element accesses (no {@code CallbackInfo} allocation on those
 * hot paths); writes and light updates get a HEAD callback that creates the deep section first. The surrounding
 * vanilla logic keeps working unchanged because {@code y & 15} already yields the correct local Y for negative Y and
 * the heightmap of a column is never below 0, so deep writes never touch skylight bookkeeping.
 */
@Mixin(value = Chunk.class, priority = 1100)
public abstract class MixinChunk_Deep implements IDeepChunk, IDeepChunkInternal, IDormantData {

    @Shadow
    public World worldObj;

    @Unique
    private ExtendedBlockStorage[] bellow$deep;

    @Unique
    private byte bellow$gen;

    @Unique
    private boolean bellow$fromDisk;

    @Unique
    private boolean bellow$lightPending;

    @Unique
    private boolean bellow$provisional;

    @Unique
    private boolean bellow$legacyRelight;

    @Unique
    private NBTTagCompound bellow$generators;

    @Unique
    private NBTTagList bellow$dormantTiles;

    @Unique
    private NBTTagList bellow$dormantEntities;

    // ---------------------------------------------------------------- IDormantData

    @Override
    public NBTTagList bellow$getDormantTiles() {
        return this.bellow$dormantTiles;
    }

    @Override
    public NBTTagList bellow$getDormantEntities() {
        return this.bellow$dormantEntities;
    }

    @Override
    public void bellow$setDormant(NBTTagList tiles, NBTTagList entities) {
        this.bellow$dormantTiles = tiles;
        this.bellow$dormantEntities = entities;
    }

    // ---------------------------------------------------------------- IDeepChunk, IDeepChunkInternal

    @Override
    public ExtendedBlockStorage[] bellow$getDeepSections() {
        ExtendedBlockStorage[] deep = this.bellow$deep;
        if (deep == null) {
            deep = this.bellow$deep = new ExtendedBlockStorage[DeepConstants.MAX_DEEP_SECTIONS];
        }
        return deep;
    }

    @Override
    public ExtendedBlockStorage[] bellow$peekDeepSections() {
        return this.bellow$deep;
    }

    @Override
    public void bellow$setDeepSections(ExtendedBlockStorage[] sections) {
        if (sections != null && sections.length != DeepConstants.MAX_DEEP_SECTIONS) {
            throw new IllegalArgumentException(
                "deep section array must have " + DeepConstants.MAX_DEEP_SECTIONS + " slots");
        }
        this.bellow$deep = sections;
    }

    @Override
    public byte bellow$getGenState() {
        return this.bellow$gen;
    }

    @Override
    public void bellow$setGenState(byte state) {
        this.bellow$gen = state;
    }

    @Override
    public boolean bellow$isFromDisk() {
        return this.bellow$fromDisk;
    }

    @Override
    public void bellow$setFromDisk(boolean fromDisk) {
        this.bellow$fromDisk = fromDisk;
    }

    @Override
    public boolean bellow$isProvisional() {
        return this.bellow$provisional;
    }

    @Override
    public void bellow$setProvisional(boolean provisional) {
        this.bellow$provisional = provisional;
    }

    @Override
    public boolean bellow$isLegacyRelight() {
        return this.bellow$legacyRelight;
    }

    @Override
    public void bellow$setLegacyRelight(boolean relight) {
        this.bellow$legacyRelight = relight;
    }

    @Override
    public boolean bellow$isLightPending() {
        return this.bellow$lightPending;
    }

    @Override
    public void bellow$setLightPending(boolean pending) {
        this.bellow$lightPending = pending;
    }

    @Override
    public NBTTagCompound bellow$getGeneratorRecords() {
        return this.bellow$generators;
    }

    @Override
    public void bellow$setGeneratorRecords(NBTTagCompound records) {
        this.bellow$generators = records;
    }

    // ---------------------------------------------------------------- section lookup

    @Unique
    private ExtendedBlockStorage bellow$deepGet(int sectionY) {
        ExtendedBlockStorage[] deep = this.bellow$deep;
        if (deep == null) return null;
        int slot = sectionY + DeepConstants.MAX_DEEP_SECTIONS;
        if (slot < 0) return null;
        // Below the floor of the world is void, even if dormant data exists there.
        if (sectionY << 4 < ((IDeepWorld) this.worldObj).bellow$getMinY()) return null;
        return deep[slot];
    }

    /*
     * storageArrays[y >> 4] reads. The ordinal counts every access to the field: getBlock/getBlockMetadata first read
     * storageArrays.length, so their indexed read is the second access. getAreLevelsEmpty clamps its lower bound to 0,
     * so its read never sees a negative index (its deep part is answered by bellow$deepLevelsEmpty); the redirect is
     * kept as a guard should another mod remove that clamp.
     */
    @Redirect(
        method = { "func_150807_a(IIILnet/minecraft/block/Block;I)Z", "setBlockMetadata(IIII)Z",
            "setLightValue(Lnet/minecraft/world/EnumSkyBlock;IIII)V", "getBlockLightValue(IIII)I",
            "getAreLevelsEmpty(II)Z" },
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/world/chunk/Chunk;storageArrays:[Lnet/minecraft/world/chunk/storage/ExtendedBlockStorage;",
            args = "array=get",
            ordinal = 0),
        require = 5,
        allow = 5)
    private ExtendedBlockStorage bellow$readFirst(ExtendedBlockStorage[] array, int index) {
        return index >= 0 ? array[index] : this.bellow$deepGet(index);
    }

    @Redirect(
        method = { "getBlock(III)Lnet/minecraft/block/Block;", "getBlockMetadata(III)I" },
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/world/chunk/Chunk;storageArrays:[Lnet/minecraft/world/chunk/storage/ExtendedBlockStorage;",
            args = "array=get",
            ordinal = 1),
        require = 2,
        allow = 2)
    private ExtendedBlockStorage bellow$readSecond(ExtendedBlockStorage[] array, int index) {
        return index >= 0 ? array[index] : this.bellow$deepGet(index);
    }

    /*
     * Section creation. Vanilla does 'ebs = storageArrays[y >> 4] = new ExtendedBlockStorage(...)' when the section is
     * missing; that store cannot be redirected (DUP_X2 idiom) and a negative index would throw. Creating the deep
     * section up front means the vanilla creation branch is never taken below Y 0; where no section may exist (below
     * the floor, or a chunk whose deep data is of a newer format and read-only) the call is cancelled instead, as
     * World does for those positions. A new deep section has no skylight, which is correct: the heightmap never goes
     * below 0, so no column sees the sky below Y 0.
     */
    @Inject(
        method = "func_150807_a(IIILnet/minecraft/block/Block;I)Z",
        at = @At("HEAD"),
        cancellable = true,
        require = 1)
    private void bellow$prepareForBlock(int x, int y, int z, Block block, int meta,
        CallbackInfoReturnable<Boolean> cir) {
        if (y < 0 && block != Blocks.air && !this.bellow$ensureDeepSection(y)) cir.setReturnValue(false);
    }

    @Inject(
        method = "setLightValue(Lnet/minecraft/world/EnumSkyBlock;IIII)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 1)
    private void bellow$prepareForLight(EnumSkyBlock type, int x, int y, int z, int value, CallbackInfo ci) {
        if (y < 0 && !this.bellow$ensureDeepSection(y)) ci.cancel();
    }

    /**
     * Vanilla answers a missing section with full sky light, right for the empty air above the terrain. A missing deep
     * section (a void deep layer: generation off, or nothing placed yet) is dark, as every other light path below Y 0
     * already answers.
     */
    @Inject(method = "getBlockLightValue(IIII)I", at = @At("HEAD"), cancellable = true, require = 1)
    private void bellow$darkWithoutSection(int x, int y, int z, int skylightSubtracted,
        CallbackInfoReturnable<Integer> cir) {
        if (y < 0 && this.bellow$deepGet(y >> 4) == null) cir.setReturnValue(0);
    }

    /** Whether the deep section of {@code y} exists now (created if it may). */
    @Unique
    private boolean bellow$ensureDeepSection(int y) {
        int sectionY = y >> 4;
        int slot = sectionY + DeepConstants.MAX_DEEP_SECTIONS;
        if (slot < 0 || y < ((IDeepWorld) this.worldObj).bellow$getMinY()) return false;
        // deep data of a newer format is read-only: a section created here would be dropped on save (and sent)
        if (DeepDataManager.isNewer((Chunk) (Object) this)) return false;
        ExtendedBlockStorage[] deep = this.bellow$getDeepSections();
        if (deep[slot] == null) deep[slot] = new ExtendedBlockStorage(sectionY << 4, !this.worldObj.provider.hasNoSky);
        return true;
    }

    /**
     * Vanilla clamps the lower bound to 0. Check the deep part here and let vanilla answer for Y >= 0
     * (for an all-negative range vanilla's loop does not execute and returns true).
     */
    @Inject(method = "getAreLevelsEmpty(II)Z", at = @At("HEAD"), cancellable = true, require = 1)
    private void bellow$deepLevelsEmpty(int minY, int maxY, CallbackInfoReturnable<Boolean> cir) {
        if (minY >= 0 || this.bellow$deep == null) return;
        int floor = ((IDeepWorld) this.worldObj).bellow$getMinY();
        int from = Math.max(minY, floor);
        int to = Math.min(maxY, -1);
        for (int y = from & ~15; y <= to; y += 16) {
            ExtendedBlockStorage ebs = this.bellow$deepGet(y >> 4);
            if (ebs != null && !ebs.isEmpty()) {
                cir.setReturnValue(false);
                return;
            }
        }
    }
}
