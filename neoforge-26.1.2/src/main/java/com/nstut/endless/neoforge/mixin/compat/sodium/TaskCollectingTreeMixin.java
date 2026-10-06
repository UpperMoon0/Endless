package com.nstut.endless.neoforge.mixin.compat.sodium;

import com.nstut.endless.heights.EndlessLogicalHeights;
import com.nstut.endless.neoforge.compat.SodiumTaskHeight;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.DeferredTaskList;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.SectionTree;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.CullType;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.lists.TaskCollectingTree", remap = false)
public abstract class TaskCollectingTreeMixin extends SectionTree {
    protected TaskCollectingTreeMixin(Viewport viewport, float distance, int frame, CullType type, Level level) {
        super(viewport, distance, frame, type, level);
    }

    @ModifyConstant(method = "addPendingSection", constant = @Constant(intValue = -128))
    private int endless$relativeTaskHeight(int original) {
        return EndlessLogicalHeights.isActive() ? baseOffsetY : original;
    }

    @Inject(method = "getPendingTaskLists", at = @At("RETURN"))
    private void endless$carryTaskHeight(CallbackInfoReturnable<DeferredTaskList> cir) {
        if (EndlessLogicalHeights.isActive())
            ((SodiumTaskHeight) cir.getReturnValue()).endless$setTaskHeight(baseOffsetY);
    }
}
