package com.nstut.endless.mixin.compat;

import it.unimi.dsi.fastutil.longs.LongList;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Retain native merging/sampling, using per-entry tickets instead of packed positions. */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.fluids.tank.SoundPool", remap = false)
public abstract class CreateSoundPoolMixin {
    @Shadow @Final private LongList queuedPositions;
    @Unique private final Map<Long, BlockPos> endless$positions = new HashMap<>();
    @Unique private long endless$nextTicket;

    @Unique private void endless$queue(BlockPos position) {
        long ticket = endless$nextTicket++;
        endless$positions.put(ticket, position.immutable());
        queuedPositions.add(ticket);
    }

    @Inject(method = "queueAt(Lnet/minecraft/core/BlockPos;)V", at = @At("HEAD"), cancellable = true, require = 1)
    private void endless$queueFullPosition(BlockPos position, CallbackInfo ci) {
        endless$queue(position);
        ci.cancel();
    }

    @Inject(method = "queueAt(J)V", at = @At("HEAD"), cancellable = true, require = 1)
    private void endless$queueLegacyPosition(long position, CallbackInfo ci) {
        // Explicit packed callers retain native decoding; tickets never collide
        // with such callers because both public overloads allocate a new entry.
        endless$queue(BlockPos.of(position));
        ci.cancel();
    }

    @Redirect(method = "playAt", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/core/BlockPos$MutableBlockPos;set(J)Lnet/minecraft/core/BlockPos$MutableBlockPos;", remap = true), require = 1)
    private BlockPos.MutableBlockPos endless$decodeTicket(BlockPos.MutableBlockPos target, long ticket) {
        BlockPos position = endless$positions.get(ticket);
        if (position == null) throw new IllegalStateException("Missing Create sound position ticket " + ticket);
        return target.set(position.getX(), position.getY(), position.getZ());
    }

    @Inject(method = "play", at = @At("TAIL"), require = 1)
    private void endless$clearPlayedPositions(net.minecraft.world.level.Level level, CallbackInfo ci) {
        // A merge-delay return retains both the native queue and full positions.
        if (queuedPositions.isEmpty()) endless$positions.clear();
    }
}
