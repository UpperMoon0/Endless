package com.nstut.endless.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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
        for (int i = sections.length - 1; i >= 0; i--) {
            LevelChunkSection sec = sections[i];
            if (sec != null && !sec.hasOnlyAir()) {
                cir.setReturnValue(i);
                return;
            }
        }
        cir.setReturnValue(-1);
    }

    @Inject(method = "isYSpaceEmpty", at = @At("HEAD"), cancellable = true, require = 0)
    private void onIsYSpaceEmpty(int minY, int maxY, CallbackInfoReturnable<Boolean> cir) {
        ChunkAccess self = (ChunkAccess) (Object) this;
        int absMin = getMinBuildHeight();
        int absMax = self.getMaxBuildHeight();
        if (minY < absMin) minY = absMin;
        if (maxY >= absMax) maxY = absMax - 1;
        for (int y = minY; y <= maxY; y += 16) {
            int idx = self.getSectionIndex(y);
            if (idx < 0 || idx >= sections.length) continue;
            LevelChunkSection sec = sections[idx];
            if (sec != null && !sec.hasOnlyAir()) {
                cir.setReturnValue(false);
                return;
            }
        }
        cir.setReturnValue(true);
    }

    @Inject(method = "isSectionEmpty", at = @At("HEAD"), cancellable = true, require = 0)
    private void onIsSectionEmpty(int sectionY, CallbackInfoReturnable<Boolean> cir) {
        ChunkAccess self = (ChunkAccess) (Object) this;
        int idx = self.getSectionIndexFromSectionY(sectionY);
        if (idx < 0 || idx >= sections.length) {
            cir.setReturnValue(true);
            return;
        }
        LevelChunkSection sec = sections[idx];
        cir.setReturnValue(sec == null || sec.hasOnlyAir());
    }

    @Inject(method = "findBlocks", at = @At("HEAD"), cancellable = true, require = 0)
    private void onFindBlocks(Predicate<BlockState> predicate, BiConsumer<BlockPos, BlockState> consumer, CallbackInfo ci) {
        ci.cancel();
        ChunkAccess self = (ChunkAccess) (Object) this;
        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection sec = sections[i];
            if (sec == null || !sec.maybeHas(predicate)) continue;
            int sy = self.getSectionYFromSectionIndex(i);
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
    }
}
