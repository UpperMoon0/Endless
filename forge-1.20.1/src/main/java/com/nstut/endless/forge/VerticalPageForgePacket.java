package com.nstut.endless.forge;

import com.nstut.endless.vertical.VerticalPageSnapshot;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Server-to-client play packet carrying one sparse vertical page snapshot. */
public record VerticalPageForgePacket(VerticalPageSnapshot snapshot) {
    public static void encode(VerticalPageForgePacket msg, FriendlyByteBuf buf) {
        msg.snapshot.write(buf);
    }

    public static VerticalPageForgePacket decode(FriendlyByteBuf buf) {
        return new VerticalPageForgePacket(VerticalPageSnapshot.read(buf));
    }

    public static void handle(VerticalPageForgePacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
            Dist.CLIENT,
            () -> () -> EndlessForgeClient.applyVerticalPage(msg.snapshot)));
        context.setPacketHandled(true);
    }
}
