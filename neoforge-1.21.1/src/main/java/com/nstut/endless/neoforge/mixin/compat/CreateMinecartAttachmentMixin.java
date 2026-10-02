package com.nstut.endless.neoforge.mixin.compat;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** Bind the native restored controller to the actual attachment-owning cart. */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.contraptions.minecart.capability.MinecartController$Type$2", remap = false)
public abstract class CreateMinecartAttachmentMixin {
    @ModifyArgs(method = "read(Lnet/neoforged/neoforge/attachment/IAttachmentHolder;Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)Lcom/simibubi/create/content/contraptions/minecart/capability/MinecartController;",
        at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/contraptions/minecart/capability/MinecartController;<init>(Lnet/minecraft/world/entity/vehicle/AbstractMinecart;)V"), require = 1, remap = false)
    private void endless$restoreOwner(Args arguments, IAttachmentHolder holder, CompoundTag tag, HolderLookup.Provider registries) {
        // Pinned 6.0.11 creates this attachment with null and only rebinds it
        // in the player-tracking callback. Its native tick otherwise returns
        // before registering the restored coupling in the world controller map.
        // Preserve the original constructor/NBT lifecycle with its real owner.
        if (holder instanceof AbstractMinecart cart) arguments.set(0, cart);
    }
}
