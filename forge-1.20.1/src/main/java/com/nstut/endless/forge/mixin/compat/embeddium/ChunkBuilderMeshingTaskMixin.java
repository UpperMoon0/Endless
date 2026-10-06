package com.nstut.endless.forge.mixin.compat.embeddium;

import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBufferSorter;
import me.jellysquid.mods.sodium.client.util.NativeBuffer;
import net.minecraft.world.phys.Vec3;
import org.embeddedt.embeddium.render.chunk.sorting.TranslucentQuadAnalyzer;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask", remap = false)
public abstract class ChunkBuilderMeshingTaskMixin {
    @Shadow @Final private RenderSection render;
    @Shadow private Vec3 camera;

    @Redirect(method = "execute", at = @At(value = "INVOKE", target = "Lme/jellysquid/mods/sodium/client/render/chunk/compile/ChunkBufferSorter;sort(Lme/jellysquid/mods/sodium/client/util/NativeBuffer;Lorg/embeddedt/embeddium/render/chunk/sorting/TranslucentQuadAnalyzer$SortState;FFF)Lme/jellysquid/mods/sodium/client/util/NativeBuffer;"))
    private NativeBuffer endless$sort(NativeBuffer buffer, TranslucentQuadAnalyzer.SortState state, float x, float y, float z) {
        return ChunkBufferSorter.sort(buffer, state,
            (float) (camera.x - render.getOriginX()),
            (float) (camera.y - render.getOriginY()),
            (float) (camera.z - render.getOriginZ()));
    }
}
