package com.nstut.endless.mixin.compat;

import com.nstut.endless.compat.create.CreateEjectorCacheKey;
import java.lang.ref.WeakReference;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.simibubi.create.content.logistics.depot.EjectorTargetHandler", remap = false)
public abstract class CreateEjectorTargetHandlerMixin {
    @Shadow(remap = false) private static long lastHoveredBlockPos;
    @Shadow(remap = false) private static BlockPos currentSelection;
    @Shadow(remap = false) private static ItemStack currentItem;
    @Unique private static CreateEjectorCacheKey endless$lastCacheKey;
    @Unique private static WeakReference<Object> endless$lastWorld = new WeakReference<>(null);

    @Inject(method = "tick", at = @At("HEAD"), require = 1, remap = false)
    private static void endless$invalidatePackedAlias(CallbackInfo ci) {
        Object world = Minecraft.getInstance().level;
        if (world != endless$lastWorld.get()) {
            currentSelection = null;
            currentItem = null;
            endless$lastWorld = new WeakReference<>(world);
            endless$lastCacheKey = null;
        }
        CreateEjectorCacheKey key = endless$cacheKey();
        if (key == null || !key.sameContext(endless$lastCacheKey)) {
            // Placement can legitimately pack to -1 too. Use a guaranteed
            // different value there; wrench mode specifically recognizes -1.
            lastHoveredBlockPos = key != null && !key.wrench() ? key.position().asLong() ^ 1L : -1;
        }
    }

    @Inject(method = "tick", at = @At("RETURN"), require = 1, remap = false)
    private static void endless$rememberExactHover(CallbackInfo ci) {
        CreateEjectorCacheKey key = endless$cacheKey();
        endless$lastCacheKey = key != null && lastHoveredBlockPos == key.position().asLong() ? key : null;
    }

    @Unique
    private static CreateEjectorCacheKey endless$cacheKey() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return null;
        ResourceLocation item = BuiltInRegistries.ITEM.getKey(mc.player.getMainHandItem().getItem());
        if (!"create".equals(item.getNamespace())) return null;
        boolean wrench = "wrench".equals(item.getPath());
        if (!wrench && !"weighted_ejector".equals(item.getPath())) return null;
        return CreateEjectorCacheKey.of(mc.level, mc.hitResult, wrench);
    }
}
