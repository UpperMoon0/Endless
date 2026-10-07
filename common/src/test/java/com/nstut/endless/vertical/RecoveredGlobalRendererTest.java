package com.nstut.endless.vertical;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecoveredGlobalRendererTest {
    private static final class Target implements RecoveredGlobalRenderer.Target<Object> {
        boolean eligible = true, visible, outlined;
        final List<String> events = new ArrayList<>();
        @Override public boolean eligible(Object e) { events.add("eligible"); return eligible; }
        @Override public boolean visible(Object e) { events.add("frustum"); return visible; }
        @Override public boolean outlined(Object e) { events.add("outline-check"); return outlined; }
        @Override public void requestOutline() { events.add("outline-request"); }
        @Override public void draw(Object e) { events.add("draw"); }
    }
    @Test void disabledCullingRendersOutsideFrustumWithoutRequestingBounds() {
        var target = new Target();
        new RecoveredGlobalRenderer<>(false, Set.of(), target).accept(new Object());
        assertEquals(List.of("eligible", "outline-check", "draw"), target.events);
    }
    @Test void enabledCullingRejectsOutsideFrustumWithoutOutlineOrDraw() {
        var target = new Target();
        target.outlined = true;
        new RecoveredGlobalRenderer<>(true, Set.of(), target).accept(new Object());
        assertEquals(List.of("eligible", "frustum"), target.events);
    }
    @Test void customOutlineIsRequestedBeforeRenderingRecoveredEntity() {
        var target = new Target();
        target.visible = target.outlined = true;
        new RecoveredGlobalRenderer<>(true, Set.of(), target).accept(new Object());
        assertEquals(List.of("eligible", "frustum", "outline-check", "outline-request", "draw"), target.events);
    }
    @Test void nativeIdentityAndIneligibleEntitiesAreNeverRenderedTwice() {
        var target = new Target();
        var entity = new Object();
        Set<Object> nativeGlobals = Collections.newSetFromMap(new IdentityHashMap<>());
        nativeGlobals.add(entity);
        new RecoveredGlobalRenderer<>(false, nativeGlobals, target).accept(entity);
        assertTrue(target.events.isEmpty());
        target.eligible = false;
        new RecoveredGlobalRenderer<>(false, Set.of(), target).accept(new Object());
        assertEquals(List.of("eligible"), target.events);
    }
}
