package com.nstut.endless.vertical;

import java.util.Set;
import java.util.function.Consumer;

/** Mirrors the native global-renderer acceptance and outline-before-draw order. */
public final class RecoveredGlobalRenderer<T> implements Consumer<T> {
    public interface Target<T> {
        boolean eligible(T entity);
        boolean visible(T entity);
        boolean outlined(T entity);
        void requestOutline();
        void draw(T entity);
    }
    private final boolean culling;
    private final Set<T> nativeGlobals;
    private final Target<T> target;

    public RecoveredGlobalRenderer(boolean culling, Set<T> nativeGlobals, Target<T> target) {
        this.culling = culling;
        this.nativeGlobals = nativeGlobals;
        this.target = target;
    }

    @Override public void accept(T entity) {
        if (nativeGlobals.contains(entity) || !target.eligible(entity)) return;
        if (culling && !target.visible(entity)) return;
        if (target.outlined(entity)) target.requestOutline();
        target.draw(entity);
    }
}
