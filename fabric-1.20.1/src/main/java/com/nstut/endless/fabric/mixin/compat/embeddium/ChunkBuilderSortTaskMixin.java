package com.nstut.endless.fabric.mixin.compat.embeddium;

import com.nstut.endless.fabric.compat.EmbeddiumSortCamera;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBufferSorter;
import me.jellysquid.mods.sodium.client.util.NativeBuffer;
import net.minecraft.world.phys.Vec3;
import org.embeddedt.embeddium.render.chunk.sorting.TranslucentQuadAnalyzer;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderSortTask", remap = false)
public abstract class ChunkBuilderSortTaskMixin implements EmbeddiumSortCamera {
    @Shadow @Final private RenderSection render;
    @Unique private Vec3 endless$camera;

    @Override public void endless$setSortCamera(Vec3 camera) { endless$camera = camera; }

    @Redirect(method = "execute", at = @At(value = "INVOKE", target = "Lme/jellysquid/mods/sodium/client/render/chunk/compile/ChunkBufferSorter;sort(Lme/jellysquid/mods/sodium/client/util/NativeBuffer;Lorg/embeddedt/embeddium/render/chunk/sorting/TranslucentQuadAnalyzer$SortState;FFF)Lme/jellysquid/mods/sodium/client/util/NativeBuffer;"))
    private NativeBuffer endless$sort(NativeBuffer buffer, TranslucentQuadAnalyzer.SortState state, float x, float y, float z) {
        if (endless$camera == null) return ChunkBufferSorter.sort(buffer, state, x, y, z);
        return ChunkBufferSorter.sort(buffer, state,
            (float) (endless$camera.x - render.getOriginX()),
            (float) (endless$camera.y - render.getOriginY()),
            (float) (endless$camera.z - render.getOriginZ()));
    }
}
