package com.nstut.endless.mixin.compat;

import com.nstut.endless.compat.create.CreateContraptionPosition;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Exact local keys for both disk and spawn NBT; every native payload remains attached to its block. */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.contraptions.Contraption", remap = false)
public abstract class CreateContraptionMixin {
    @Shadow(remap = false) protected Map<BlockPos, StructureBlockInfo> blocks;

    @Inject(method = "writeBlocksCompound", at = @At("RETURN"), require = 1, remap = false)
    private void endless$writeExactKeys(boolean spawnPacket, CallbackInfoReturnable<CompoundTag> cir) {
        ListTag entries = cir.getReturnValue().getList("BlockList", 10);
        if (entries.size() != blocks.size()) throw new IllegalStateException("Create block serialization size changed");
        int index = 0;
        for (StructureBlockInfo info : blocks.values()) {
            CompoundTag entry = entries.getCompound(index++);
            if (entry.getLong("Pos") != info.pos().asLong())
                throw new IllegalStateException("Create block serialization order changed");
            CreateContraptionPosition.write(entry, info.pos());
        }
    }

    @Redirect(method = "readStructureBlockInfo", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;of(J)Lnet/minecraft/core/BlockPos;"), require = 1)
    private static BlockPos endless$readExactKey(long packed, CompoundTag entry, @Coerce Object palette) {
        return CreateContraptionPosition.read(entry, packed);
    }
}
