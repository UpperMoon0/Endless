package com.nstut.endless.neoforge.mixin.compat.sodium;
import com.nstut.endless.compat.create.DestructionPositionLookup;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
/** Sodium replaces vanilla block-entity extraction and supplies its own strict hook. */
@Mixin(LevelRenderer.class)
public abstract class VanillaDestructionLookupMixin {
    // NeoForge adds a Frustum overload; the three-argument entry only delegates.
    @Redirect(method="extractVisibleBlockEntities(Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/state/LevelRenderState;Lnet/minecraft/client/renderer/culling/Frustum;)V",at=@At(value="INVOKE",target="Lnet/minecraft/core/BlockPos;asLong()J"),require=1)
    private long endless$lookupCrackKey(BlockPos pos) { return ((DestructionPositionLookup)this).endless$destructionKey(pos); }
}
