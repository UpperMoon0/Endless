package com.nstut.endless.mixin;

import com.nstut.endless.vertical.lod.VerticalLodClient;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Dense packet replacement may mutate existing section objects without renderer callbacks. */
@Mixin(LevelChunk.class)
public abstract class VerticalLodChunkMixin {
    @Inject(method="replaceWithPacketData",at=@At("RETURN"))
    private void endless$invalidateLodColumn(CallbackInfo ci) {
        var pos=((LevelChunk)(Object)this).getPos();
        VerticalLodClient.invalidateColumn(pos.getMinBlockX()>>4,pos.getMinBlockZ()>>4);
    }
}
