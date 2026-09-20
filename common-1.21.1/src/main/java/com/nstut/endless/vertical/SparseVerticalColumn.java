package com.nstut.endless.vertical;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntFunction;

/**
 * Sparse page index for one horizontal chunk column.
 *
 * <p>Only pages that contain data are retained. This class intentionally does
 * not expose an array sized from world height.</p>
 */
public final class SparseVerticalColumn<S> {

    private final Map<Integer, VerticalPage<S>> pages = new HashMap<>();

    public int pageCount() {
        return pages.size();
    }

    public boolean isEmpty() {
        return pages.isEmpty();
    }

    public VerticalPage<S> getPage(int pageY) {
        return pages.get(pageY);
    }

    public VerticalPage<S> getOrCreatePage(int pageY) {
        return pages.computeIfAbsent(pageY, VerticalPage::new);
    }

    public VerticalPage<S> getOrCreatePage(int pageY, IntFunction<VerticalPage<S>> factory) {
        Objects.requireNonNull(factory, "factory");
        return pages.computeIfAbsent(pageY, key -> {
            VerticalPage<S> page = Objects.requireNonNull(factory.apply(key), "factory returned null");
            if (page.pageY() != key) {
                throw new IllegalArgumentException(
                        "factory returned page " + page.pageY() + " for requested page " + key);
            }
            return page;
        });
    }

    public S getSection(int absoluteSectionY) {
        int pageY = VerticalPageLayout.pageYForSectionY(absoluteSectionY);
        VerticalPage<S> page = pages.get(pageY);
        return page == null ? null : page.getSection(absoluteSectionY);
    }

    public S putSection(int absoluteSectionY, S section) {
        Objects.requireNonNull(section, "section");
        int pageY = VerticalPageLayout.pageYForSectionY(absoluteSectionY);
        return getOrCreatePage(pageY).putSection(absoluteSectionY, section);
    }

    public S removeSection(int absoluteSectionY) {
        int pageY = VerticalPageLayout.pageYForSectionY(absoluteSectionY);
        VerticalPage<S> page = pages.get(pageY);
        if (page == null) {
            return null;
        }

        S removed = page.removeSection(absoluteSectionY);
        if (page.isEmpty()) {
            pages.remove(pageY);
        }
        return removed;
    }

    public VerticalPage<S> removePage(int pageY) {
        return pages.remove(pageY);
    }

    /**
     * Sorted snapshot used by persistence/debug code. The returned list is not
     * backed by the live column.
     */
    public List<Integer> pageYs() {
        List<Integer> pageYs = new ArrayList<>(pages.keySet());
        Collections.sort(pageYs);
        return Collections.unmodifiableList(pageYs);
    }

    public void clear() {
        pages.clear();
    }
}
