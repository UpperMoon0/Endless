package com.nstut.endless.mixin.compat;

import com.nstut.endless.compat.create.CreateLogicalGeometry;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "com.simibubi.create.content.contraptions.elevator.ElevatorColumn", remap = false)
public abstract class CreateElevatorColumnMixin {
    @Shadow(remap = false) protected LevelAccessor level;
    @Redirect(method = "gatherAll", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;betweenClosedStream(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)Ljava/util/stream/Stream;", remap = true), require = 1)
    private Stream<BlockPos> endless$occupiedColumn(BlockPos first, BlockPos last) {
        return CreateLogicalGeometry.columnPositions(level, first, last);
    }
}
