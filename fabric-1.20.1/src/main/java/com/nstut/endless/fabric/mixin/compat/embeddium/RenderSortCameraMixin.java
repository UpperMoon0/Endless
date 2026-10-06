package com.nstut.endless.fabric.mixin.compat.embeddium;
import com.nstut.endless.fabric.compat.EmbeddiumSortCamera;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderSortTask;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager", remap = false)
public abstract class RenderSortCameraMixin {
    @Shadow private Vec3 cameraPosition;
    @Inject(method = "createSortTask", at = @At("RETURN"))
    private void endless$preserveSortCamera(RenderSection render, int frame, CallbackInfoReturnable<ChunkBuilderSortTask> cir) {
        if (cir.getReturnValue() != null) ((EmbeddiumSortCamera) cir.getReturnValue()).endless$setSortCamera(cameraPosition);
    }

}
