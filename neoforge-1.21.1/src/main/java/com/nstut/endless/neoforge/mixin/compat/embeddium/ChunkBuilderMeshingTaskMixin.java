package com.nstut.endless.neoforge.mixin.compat.embeddium;

import org.embeddedt.embeddium.impl.render.chunk.RenderSection;
import org.embeddedt.embeddium.impl.render.chunk.compile.ChunkBufferSorter;
import org.embeddedt.embeddium.impl.util.NativeBuffer;
import net.minecraft.world.phys.Vec3;
import org.embeddedt.embeddium.impl.render.chunk.sorting.TranslucentQuadAnalyzer;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

@Pseudo
@Mixin(targets = "org.embeddedt.embeddium.impl.render.chunk.compile.tasks.ChunkBuilderMeshingTask", remap = false)
public abstract class ChunkBuilderMeshingTaskMixin {
    @Shadow @Final private RenderSection render;
    @Shadow private Vec3 camera;

    @Redirect(method = "execute", at = @At(value = "INVOKE", target = "Lorg/embeddedt/embeddium/impl/render/chunk/compile/ChunkBufferSorter;sort(Lorg/embeddedt/embeddium/impl/util/NativeBuffer;Lorg/embeddedt/embeddium/impl/render/chunk/sorting/TranslucentQuadAnalyzer$SortState;FFF)Lorg/embeddedt/embeddium/impl/util/NativeBuffer;"))
    private NativeBuffer endless$sort(NativeBuffer buffer, TranslucentQuadAnalyzer.SortState state, float x, float y, float z) {
        return ChunkBufferSorter.sort(buffer, state,
            (float) (camera.x - render.getOriginX()),
            (float) (camera.y - render.getOriginY()),
            (float) (camera.z - render.getOriginZ()));
    }
}
