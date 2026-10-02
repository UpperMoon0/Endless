package com.nstut.endless.mixin.compat;

import net.minecraft.core.Vec3i;
import com.nstut.endless.heights.EndlessLogicalHeights;
import net.minecraft.network.FriendlyByteBuf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Create 6.0.8/6.0.11 encode doubled Y as a short, independently of BlockPos packets. */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.trains.graph.TrackNodeLocation", remap = false)
public abstract class CreateTrackNodeLocationMixin {
    @Redirect(method = "send", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/FriendlyByteBuf;writeShort(I)Lnet/minecraft/network/FriendlyByteBuf;", remap = true), require = 1)
    private FriendlyByteBuf endless$escapeHeight(FriendlyByteBuf buffer, int y) {
        buffer.writeShort(EndlessLogicalHeights.isActive() && (y <= Short.MIN_VALUE || y > Short.MAX_VALUE) ? Short.MIN_VALUE : y);
        return buffer;
    }

    // Put the extension after Z so receive's native readShort/readVarInt and
    // construction remain intact. Ordinary nodes retain their native bytes.
    @Redirect(method = "send", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/FriendlyByteBuf;writeVarInt(I)Lnet/minecraft/network/FriendlyByteBuf;", ordinal = 1, remap = true), require = 1)
    private FriendlyByteBuf endless$writeExtendedHeight(FriendlyByteBuf buffer, int z) {
        buffer.writeVarInt(z);
        int y = ((Vec3i) (Object) this).getY();
        if (EndlessLogicalHeights.isActive() && (y <= Short.MIN_VALUE || y > Short.MAX_VALUE)) buffer.writeVarInt(y);
        return buffer;
    }

    @Redirect(method = "receive", at = @At(value = "NEW", target = "(III)Lnet/minecraft/core/BlockPos;", remap = true), require = 1)
    private static net.minecraft.core.BlockPos endless$readExtendedHeight(int x, int y, int z, FriendlyByteBuf buffer, @Coerce Object dimensions) {
        if (EndlessLogicalHeights.isActive() && y == Short.MIN_VALUE) y = buffer.readVarInt();
        return new net.minecraft.core.BlockPos(x, y, z);
    }
}
