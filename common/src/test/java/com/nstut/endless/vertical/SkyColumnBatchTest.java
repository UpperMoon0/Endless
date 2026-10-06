package com.nstut.endless.vertical;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class SkyColumnBatchTest {
    @Test void sustainedOverlappingEditsRefreshEachColumnOncePerFrame() {
        var batch = new SkyColumnBatch();
        for (int frame = 0; frame < 120; frame++) {
            for (int edit = 0; edit < 1000; edit++)
                assertEquals(edit == 0, batch.add(edit % 2 - 62500, -62500));
            var seen = new HashSet<String>();
            assertEquals(12, batch.drain((x, z) -> assertTrue(seen.add(x + ":" + z))));
            assertEquals(12, seen.size());
            assertEquals(0, batch.drain((x, z) -> fail("Idle frame repeated work")));
        }
    }
    @Test void notificationsDuringDrainAreRetainedForNextFrame() {
        var batch = new SkyColumnBatch();
        batch.add(0, 0);
        assertEquals(9, batch.drain((x, z) -> batch.add(10, 10)));
        assertEquals(9, batch.drain((x, z) -> assertTrue(x >= 9 && z >= 9)));
    }
}
