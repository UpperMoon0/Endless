package com.nstut.endless.mixin.compat;

import com.nstut.endless.compat.create.CreateFullPosition;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.simibubi.create.content.redstone.link.LinkBehaviour", remap = false)
public abstract class CreateLinkBehaviourMixin {
    @Shadow(remap = false)
    public boolean newPosition;

    @Inject(method = "write", at = @At("RETURN"), require = 1, remap = false)
    private void endless$writeExactPosition(
        CompoundTag nbt,
        HolderLookup.Provider registries,
        boolean clientPacket,
        CallbackInfo ci
    ) {
        CreateFullPosition.put(
            nbt,
            CreateFullPosition.LINK_LAST_POSITION,
            CreateFullPosition.blockPosFromBehaviour(this)
        );
    }

    @Inject(method = "read", at = @At("RETURN"), require = 1, remap = false)
    private void endless$readExactPosition(
        CompoundTag nbt,
        HolderLookup.Provider registries,
        boolean clientPacket,
        CallbackInfo ci
    ) {
        if (!CreateFullPosition.contains(nbt, CreateFullPosition.LINK_LAST_POSITION)) {
            return;
        }
        newPosition = !CreateFullPosition.get(nbt, CreateFullPosition.LINK_LAST_POSITION)
            .equals(CreateFullPosition.blockPosFromBehaviour(this));
    }
}
