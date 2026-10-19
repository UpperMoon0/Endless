package com.nstut.endless.vertical;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SparseControllerPageReceiptTest {
    @Test void emptyReceiptIsKnownButUnseenAndUnloadedControllersAreNot() {
        // Empty snapshots do not require a registry-bearing Level to decode.
        var world = new MinecraftVerticalWorld(null);
        var positive = new VerticalPagePos(36, 4, 7);
        var negative = new VerticalPagePos(36, -4, 7);
        assertFalse(world.hasPageSnapshot(positive));
        assertFalse(world.hasPageSnapshot(negative));
        world.applySnapshot(new VerticalPageSnapshot(36, 4, 7, 1, List.of()));
        assertTrue(world.hasPageSnapshot(positive), "Known empty page must allow native missing-controller removal");
        assertFalse(world.pageExists(positive), "Receipt must not be confused with occupied storage");
        assertFalse(world.hasPageSnapshot(negative));
        world.applySnapshot(new VerticalPageSnapshot(36, -4, 7, 2, List.of()));
        assertTrue(world.hasPageSnapshot(negative));
        world.unloadColumn(36, 7);
        assertFalse(world.hasPageSnapshot(positive));
        assertFalse(world.hasPageSnapshot(negative));
    }
}
