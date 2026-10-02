package com.nstut.endless.mixin.compat;

import com.nstut.endless.compat.create.CreateFullPosition;
import com.nstut.endless.compat.create.CreateAssemblyPositionAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.simibubi.create.content.contraptions.AssemblyException", remap = false)
public abstract class CreateAssemblyExceptionMixin implements CreateAssemblyPositionAccess {
    @Shadow(remap = false) private BlockPos position;

    public BlockPos endless$getAssemblyPosition() { return position; }
    public void endless$setAssemblyPosition(BlockPos pos) { position = pos; }

    @Inject(method = "write", at = @At("RETURN"), require = 1)
    private static void endless$writePosition(CompoundTag compound, HolderLookup.Provider registries, @Coerce Object exception, CallbackInfo ci) {
        if (exception != null && ((CreateAssemblyPositionAccess) exception).endless$getAssemblyPosition() != null)
            CreateFullPosition.put(compound.getCompound("LastException"), "EndlessPosition", ((CreateAssemblyPositionAccess) exception).endless$getAssemblyPosition());
    }

    @Inject(method = "read", at = @At("RETURN"), require = 1)
    private static void endless$readPosition(CompoundTag compound, HolderLookup.Provider registries, CallbackInfoReturnable<Object> cir) {
        CompoundTag saved = compound.getCompound("LastException");
        if (cir.getReturnValue() != null && CreateFullPosition.contains(saved, "EndlessPosition"))
            ((CreateAssemblyPositionAccess) cir.getReturnValue()).endless$setAssemblyPosition(CreateFullPosition.get(saved, "EndlessPosition"));
    }
}
