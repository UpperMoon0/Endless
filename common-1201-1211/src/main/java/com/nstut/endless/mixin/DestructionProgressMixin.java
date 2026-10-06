package com.nstut.endless.mixin;

import com.nstut.endless.compat.create.CreateDestructionPositions;
import com.nstut.endless.compat.create.DestructionPositionLookup;
import com.nstut.endless.heights.EndlessLogicalHeights;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.BlockDestructionProgress;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Exact vanilla crack keys, including Create's native additional structure positions. */
@Mixin(LevelRenderer.class)
public abstract class DestructionProgressMixin implements DestructionPositionLookup {
    @Shadow @Final private Long2ObjectMap<SortedSet<BlockDestructionProgress>> destructionProgress;
    @Shadow @Final private Int2ObjectMap<BlockDestructionProgress> destroyingBlocks;
    @Unique private final CreateDestructionPositions endless$crackPositions = new CreateDestructionPositions();

    @Override public long endless$destructionKey(BlockPos pos) {
        return EndlessLogicalHeights.isActive() ? endless$crackPositions.lookup(pos) : pos.asLong();
    }

    @Redirect(method = "destroyBlockProgress", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;asLong()J"), require = 1)
    private long endless$exactCrackKey(BlockPos pos) {
        return EndlessLogicalHeights.isActive() ? endless$crackPositions.key(pos) : pos.asLong();
    }
    @Redirect(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;asLong()J"), require = 1)
    private long endless$lookupCrackKey(BlockPos pos) {
        return EndlessLogicalHeights.isActive() ? endless$crackPositions.lookup(pos) : pos.asLong();
    }
    @Redirect(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;of(J)Lnet/minecraft/core/BlockPos;"), require = 1)
    private BlockPos endless$renderExactCrack(long key) { return endless$crackPositions.position(key); }

    // Create inserts its extras immediately after updateTick, before native
    // primary insertion. Move those entries to exact keys before rendering.
    @Inject(method = "destroyBlockProgress", at = @At("RETURN"))
    private void endless$exactCreateExtras(int breaker, BlockPos pos, int stage, CallbackInfo ci) {
        if (!EndlessLogicalHeights.isActive() || stage < 0 || stage >= 10) return;
        BlockDestructionProgress progress = destroyingBlocks.get(breaker);
        for (BlockPos extra : endless$extraPositions(progress)) {
            SortedSet<BlockDestructionProgress> packed = destructionProgress.get(extra.asLong());
            if (packed != null) {
                packed.remove(progress);
                if (packed.isEmpty()) destructionProgress.remove(extra.asLong());
            }
            destructionProgress.computeIfAbsent(endless$crackPositions.key(extra), key -> new TreeSet<>()).add(progress);
        }
    }

    @Inject(method = "removeProgress", at = @At("HEAD"), cancellable = true)
    private void endless$removeExactCracks(BlockDestructionProgress progress, CallbackInfo ci) {
        if (!EndlessLogicalHeights.isActive()) return;
        endless$removeCrack(progress.getPos(), progress);
        for (BlockPos pos : endless$extraPositions(progress)) endless$removeCrack(pos, progress);
        // A reused breaker may now target a block with no Create extras.
        try { progress.getClass().getMethod("create$setExtraPositions", Set.class).invoke(progress, new Object[]{null}); }
        catch (NoSuchMethodException ignored) {}
        catch (ReflectiveOperationException e) { throw new IllegalStateException("Create destruction extension changed", e); }
        ci.cancel();
    }
    @Unique private void endless$removeCrack(BlockPos pos, BlockDestructionProgress progress) {
        long key = endless$crackPositions.lookup(pos);
        SortedSet<BlockDestructionProgress> set = destructionProgress.get(key);
        if (set == null) return;
        set.remove(progress);
        if (set.isEmpty()) { destructionProgress.remove(key); endless$crackPositions.remove(pos); }
    }
    @SuppressWarnings("unchecked")
    @Unique private static Set<BlockPos> endless$extraPositions(BlockDestructionProgress progress) {
        try {
            Set<BlockPos> positions = (Set<BlockPos>) progress.getClass().getMethod("create$getExtraPositions").invoke(progress);
            return positions == null ? Set.of() : positions;
        } catch (NoSuchMethodException ignored) { return Set.of(); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("Create destruction extension changed", e); }
    }
    @Inject(method = "setLevel", at = @At("RETURN"))
    private void endless$clearCrackKeys(ClientLevel level, CallbackInfo ci) { endless$crackPositions.clear(); }
}
