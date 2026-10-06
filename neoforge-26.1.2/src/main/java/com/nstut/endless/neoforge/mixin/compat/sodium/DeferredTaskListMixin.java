package com.nstut.endless.neoforge.mixin.compat.sodium;

import com.nstut.endless.neoforge.compat.SodiumTaskHeight;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.lists.DeferredTaskList", remap = false)
public abstract class DeferredTaskListMixin implements SodiumTaskHeight {
    @Unique private int endless$taskHeight = -128;

    @Override public void endless$setTaskHeight(int sectionY) { endless$taskHeight = sectionY; }

    @ModifyConstant(method = "dequeueNextSectionPos", constant = @Constant(intValue = -128))
    private int endless$decodeTaskHeight(int original) { return endless$taskHeight; }
}
