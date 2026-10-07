package com.nstut.endless.forge.mixin.compat.embeddium;

import com.nstut.endless.forge.compat.LoadedColumnBlockEntities;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla installation/replacement paths, also used by sparse LevelChunk entities. */
@Mixin(BlockEntity.class)
public abstract class GlobalBlockEntityLifecycleMixin {
    @Inject(method = {"setLevel", "clearRemoved", "setRemoved", "setBlockState", "load"}, at = @At("RETURN"), remap = true)
    private void endless$updateGlobalCandidate(CallbackInfo ci) {
        var entity = (BlockEntity) (Object) this;
        if (entity.getLevel() == null || !entity.getLevel().isClientSide) return;
        var mc = Minecraft.getInstance();
        // Never retain the entity in a task after a disconnect or world change.
        Runnable update = () -> {
            if (entity.getLevel() != mc.level) return;
            var renderer = SodiumWorldRenderer.instanceNullable();
            if (renderer != null) ((LoadedColumnBlockEntities) renderer).endless$trackBlockEntity(entity);
        };
        if (mc.isSameThread()) update.run();
        else mc.execute(update);
    }
}
