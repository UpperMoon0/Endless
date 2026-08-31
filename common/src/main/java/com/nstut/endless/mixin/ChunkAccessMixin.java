package com.nstut.endless.mixin;

import com.nstut.endless.util.SectionMask;
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

    @Inject(method = "getHighestFilledSectionIndex", at = @At("HEAD"), cancellable = true, require = 0)
    private void onGetHighestFilledSectionIndex(CallbackInfoReturnable<Integer> cir) {
        BitSet mask = endless$buildMask();
        cir.setReturnValue(mask.isEmpty() ? -1 : mask.length() - 1);
    }

    @Inject(method = "isYSpaceEmpty", at = @At("HEAD"), cancellable = true, require = 0)
    private void onIsYSpaceEmpty(int minY, int maxY, CallbackInfoReturnable<Boolean> cir) {
        BitSet mask = endless$buildMask();
        ChunkAccess self = (ChunkAccess) (Object) this;
        int absMin = getMinBuildHeight();
        int absMax = self.getMaxBuildHeight();
        if (minY < absMin) minY = absMin;
        if (maxY >= absMax) maxY = absMax - 1;
        int minIdx = self.getSectionIndex(minY);
        int maxIdx = self.getSectionIndex(maxY);
        if (minIdx < 0) minIdx = 0;
        if (maxIdx >= sections.length) maxIdx = sections.length - 1;
        cir.setReturnValue(SectionMask.isEmptyInRange(mask, minIdx, maxIdx));
    }

    @Inject(method = "findBlocks", at = @At("HEAD"), cancellable = true, require = 0)
    private void onFindBlocks(Predicate<BlockState> predicate, BiConsumer<BlockPos, BlockState> consumer, CallbackInfo ci) {
        ci.cancel();
        BitSet mask = endless$buildMask();
        ChunkAccess self = (ChunkAccess) (Object) this;
        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
        int bitIdx = mask.nextSetBit(0);
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
            bitIdx = mask.nextSetBit(bitIdx + 1);
        }
    }

    @Unique
    private BitSet endless$buildMask() {
        BitSet mask = new BitSet(sections.length);
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection sec = sections[i];
            if (sec != null && !sec.hasOnlyAir()) {
                mask.set(i);
            }
        }
        return mask;
    }
}
