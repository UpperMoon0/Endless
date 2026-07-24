package com.nstut.endless.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.BitSet;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

@Mixin(ChunkAccess.class)
public abstract class ChunkAccessMixin {

    @Shadow @Final protected LevelChunkSection[] sections;

    @Shadow public abstract int getMinBuildHeight();

    @Shadow public abstract int getHeight();

    @Shadow public abstract ChunkPos getPos();

    @Unique
    private BitSet endless$nonEmptyMask;

    @Unique
    private int endless$highestCached = -1;

    @Unique
    private boolean endless$maskReady;

    @Unique
    private void endless$ensureMask() {
        if (endless$maskReady) return;
        endless$nonEmptyMask = new BitSet(sections.length);
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection sec = sections[i];
            if (sec != null && !sec.hasOnlyAir()) {
                endless$nonEmptyMask.set(i);
                if (i > endless$highestCached) endless$highestCached = i;
            }
        }
        endless$maskReady = true;
    }

    @Unique
    private void endless$updateSection(int idx, LevelChunkSection sec) {
        if (!endless$maskReady) return;
        if (sec != null && !sec.hasOnlyAir()) {
            endless$nonEmptyMask.set(idx);
            if (idx > endless$highestCached) endless$highestCached = idx;
        } else {
            if (endless$nonEmptyMask.get(idx)) {
                endless$nonEmptyMask.clear(idx);
                if (idx == endless$highestCached) {
                    endless$highestCached = endless$nonEmptyMask.length() - 1;
                    while (endless$highestCached >= 0 && !endless$nonEmptyMask.get(endless$highestCached))
                        endless$highestCached--;
                }
            }
        }
    }

    @Inject(method = "getHighestFilledSectionIndex", at = @At("HEAD"), cancellable = true, require = 0)
    private void onGetHighestFilledSectionIndex(CallbackInfoReturnable<Integer> cir) {
        endless$ensureMask();
        cir.setReturnValue(endless$highestCached);
    }

    @Inject(method = "isYSpaceEmpty", at = @At("HEAD"), cancellable = true, require = 0)
    private void onIsYSpaceEmpty(int minY, int maxY, CallbackInfoReturnable<Boolean> cir) {
        endless$ensureMask();
        if (endless$nonEmptyMask.isEmpty()) {
            cir.setReturnValue(true);
            return;
        }
        ChunkAccess self = (ChunkAccess) (Object) this;
        int absMin = getMinBuildHeight();
        int absMax = self.getMaxBuildHeight();
        if (minY < absMin) minY = absMin;
        if (maxY >= absMax) maxY = absMax - 1;
        int minIdx = self.getSectionIndex(minY);
        int maxIdx = self.getSectionIndex(maxY);
        if (minIdx < 0) minIdx = 0;
        if (maxIdx >= sections.length) maxIdx = sections.length - 1;
        if (minIdx > maxIdx) {
            cir.setReturnValue(true);
            return;
        }
        cir.setReturnValue(endless$nonEmptyMask.nextSetBit(minIdx) > maxIdx);
    }

    @Inject(method = "isSectionEmpty", at = @At("HEAD"), cancellable = true, require = 0)
    private void onIsSectionEmpty(int sectionY, CallbackInfoReturnable<Boolean> cir) {
        endless$ensureMask();
        ChunkAccess self = (ChunkAccess) (Object) this;
        int idx = self.getSectionIndexFromSectionY(sectionY);
        if (idx < 0 || idx >= sections.length) {
            cir.setReturnValue(true);
            return;
        }
        cir.setReturnValue(!endless$nonEmptyMask.get(idx));
    }

    @Inject(method = "findBlocks", at = @At("HEAD"), cancellable = true, require = 0)
    private void onFindBlocks(Predicate<BlockState> predicate, BiConsumer<BlockPos, BlockState> consumer, CallbackInfo ci) {
        ci.cancel();
        endless$ensureMask();
        ChunkAccess self = (ChunkAccess) (Object) this;
        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
        int bitIdx = endless$nonEmptyMask.nextSetBit(0);
        while (bitIdx >= 0) {
            LevelChunkSection sec = sections[bitIdx];
            if (sec.maybeHas(predicate)) {
                int sy = self.getSectionYFromSectionIndex(bitIdx);
                BlockPos origin = new BlockPos(getPos().getMinBlockX(), sy * 16, getPos().getMinBlockZ());
                for (int dx = 0; dx < 16; dx++) {
                    for (int dy = 0; dy < 16; dy++) {
                        for (int dz = 0; dz < 16; dz++) {
                            BlockState bs = sec.getBlockState(dx, dy, dz);
                            if (predicate.test(bs)) {
                                mpos.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                                consumer.accept(mpos, bs);
                            }
                        }
                    }
                }
            }
            bitIdx = endless$nonEmptyMask.nextSetBit(bitIdx + 1);
        }
    }

    @Inject(method = "setBlockState", at = @At("RETURN"), require = 0)
    private void onSetBlockState(BlockPos pos, BlockState state, boolean isMoving, CallbackInfoReturnable<BlockState> cir) {
        if (!endless$maskReady) return;
        endless$maskReady = false; // force rebuild on next access
    }
}
