package com.nstut.endless.vertical;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GlobalRendererIndexTest {
    private static final class Entity {
        boolean global;
        Entity(boolean global) { this.global = global; }
        // Distinct BEs at aliased positions must never be deduplicated by equals.
        @Override public boolean equals(Object other) { return other instanceof Entity; }
        @Override public int hashCode() { return 0; }
    }
    private static List<Entity> candidates(GlobalRendererIndex<Entity> index) {
        var result = new ArrayList<Entity>();
        index.forEachCandidate(result::add);
        return result;
    }

    @Test void repeatedMainAndShadowPassesDoNotReclassifyTwentyThousandOrdinaryEntities() {
        var index = new GlobalRendererIndex<Entity>();
        for (int i = 0; i < 20_000; i++) index.track(new Entity(false), i % 100, i / 100);
        var rope = new Entity(true);
        index.track(rope, 50, 62_500);
        var checks = new AtomicInteger();
        index.refresh(entity -> { checks.incrementAndGet(); return entity.global; });
        assertEquals(20_001, checks.get());
        checks.set(0);
        for (int frame = 0; frame < 100; frame++) for (int pass = 0; pass < 3; pass++) {
            index.refresh(entity -> { checks.incrementAndGet(); return entity.global; });
            var seen = candidates(index);
            assertEquals(1, seen.size());
            assertSame(rope, seen.get(0));
        }
        assertEquals(0, checks.get(), "Stable render passes must do no full-world classification");
    }

    @Test void dirtySectionIsLocalAndCoalescedAndCanPromoteAnExistingEntity() {
        var index = new GlobalRendererIndex<Entity>();
        var changed = new Entity(false);
        var untouched = new Entity(false);
        index.track(changed, 1, 62_500);
        index.track(untouched, 1, -62_500);
        index.refresh(e -> e.global);
        changed.global = true;
        for (int i = 0; i < 100; i++) index.dirtySection(1, 62_500);
        var checks = new AtomicInteger();
        index.refresh(e -> { checks.incrementAndGet(); assertSame(changed, e); return e.global; });
        assertEquals(1, checks.get());
        assertSame(changed, candidates(index).get(0));
    }

    @Test void removalReplacementUnloadAndRejoinCannotLeaveStaleGlobals() {
        var index = new GlobalRendererIndex<Entity>();
        var old = new Entity(true);
        var replacement = new Entity(true);
        index.track(old, 1, 0);
        index.refresh(e -> e.global);
        index.track(replacement, 1, 0);
        index.remove(old);
        index.refresh(e -> e.global);
        assertEquals(1, candidates(index).size());
        assertSame(replacement, candidates(index).get(0));
        index.removeColumn(1);
        assertTrue(candidates(index).isEmpty());
        index.refresh(e -> { fail("Unloaded entities must leave pending work too"); return true; });
        index.track(replacement, 1, 0);
        index.refresh(e -> e.global);
        assertSame(replacement, candidates(index).get(0));
    }

    @Test void rendererReloadReclassifiesAllKnownEntitiesOnceIncludingPreviousNonCandidates() {
        var index = new GlobalRendererIndex<Entity>();
        var oldGlobal = new Entity(true);
        var newGlobal = new Entity(false);
        index.track(oldGlobal, 1, 0);
        index.track(newGlobal, 2, 0);
        index.refresh(e -> e.global);
        oldGlobal.global = false;
        newGlobal.global = true;
        index.dirtyAll();
        index.refresh(e -> e.global);
        assertEquals(1, candidates(index).size());
        assertSame(newGlobal, candidates(index).get(0));
        index.refresh(e -> { fail("Reload must not rescan on the next pass"); return false; });
    }

    @Test void relocationAndIdentityAreIndependentOfEqualityAndPackedVerticalAliases() {
        var index = new GlobalRendererIndex<Entity>();
        var high = new Entity(true);
        var low = new Entity(true);
        index.track(high, 1, 62_500);
        index.track(low, 1, -62_500);
        index.refresh(e -> e.global);
        assertEquals(2, candidates(index).size());
        index.track(high, 2, 0);
        index.removeColumn(1);
        index.refresh(e -> e.global);
        assertEquals(1, candidates(index).size());
        assertSame(high, candidates(index).get(0));
    }
}
