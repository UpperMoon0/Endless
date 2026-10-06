package com.nstut.endless.neoforge.mixin.compat.embeddium;

import com.nstut.endless.neoforge.compat.EmbeddiumSortCamera;
import org.embeddedt.embeddium.impl.render.chunk.RenderSection;
import org.embeddedt.embeddium.impl.render.chunk.compile.ChunkBufferSorter;
import org.embeddedt.embeddium.impl.util.NativeBuffer;
import net.minecraft.world.phys.Vec3;
import org.embeddedt.embeddium.impl.render.chunk.sorting.TranslucentQuadAnalyzer;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

@Pseudo
@Mixin(targets = "org.embeddedt.embeddium.impl.render.chunk.compile.tasks.ChunkBuilderSortTask", remap = false)
public abstract class ChunkBuilderSortTaskMixin implements EmbeddiumSortCamera {
    @Shadow @Final private RenderSection render;
    @Unique private Vec3 endless$camera;

    @Override public void endless$setSortCamera(Vec3 camera) { endless$camera = camera; }

    @Redirect(method = "execute", at = @At(value = "INVOKE", target = "Lorg/embeddedt/embeddium/impl/render/chunk/compile/ChunkBufferSorter;sort(Lorg/embeddedt/embeddium/impl/util/NativeBuffer;Lorg/embeddedt/embeddium/impl/render/chunk/sorting/TranslucentQuadAnalyzer$SortState;FFF)Lorg/embeddedt/embeddium/impl/util/NativeBuffer;"))
    private NativeBuffer endless$sort(NativeBuffer buffer, TranslucentQuadAnalyzer.SortState state, float x, float y, float z) {
        if (endless$camera == null) return ChunkBufferSorter.sort(buffer, state, x, y, z);
        return ChunkBufferSorter.sort(buffer, state,
            (float) (endless$camera.x - render.getOriginX()),
            (float) (endless$camera.y - render.getOriginY()),
            (float) (endless$camera.z - render.getOriginZ()));
    }
}
