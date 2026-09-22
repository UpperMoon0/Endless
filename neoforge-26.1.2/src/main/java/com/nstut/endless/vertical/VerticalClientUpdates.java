package com.nstut.endless.vertical;

import net.minecraft.client.Minecraft;

/** Client-thread invalidation for one received page; never recreate the whole render grid. */
public final class VerticalClientUpdates {
    private VerticalClientUpdates() {}

    public static void apply(Minecraft client, VerticalPageSnapshot snapshot) {
        EndlessVerticalEngine.world(client.level).applySnapshot(snapshot);
        VerticalPagePos pos = snapshot.pos();
        int x = pos.chunkX() << 4;
        int z = pos.chunkZ() << 4;
        // Neighbor faces and boundary light can change too. The work is bounded
        // to 3 x 34 x 3 sections regardless of the configured world height.
        client.levelRenderer.setBlocksDirty(x - 1, VerticalPageLayout.pageMinBlockY(pos.pageY()) - 1, z - 1,
            x + 16, VerticalPageLayout.pageMaxBlockY(pos.pageY()) + 1, z + 16);
    }
}
