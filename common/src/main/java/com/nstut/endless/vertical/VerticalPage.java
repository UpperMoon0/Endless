package com.nstut.endless.vertical;

import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * One sparse vertical page containing a fixed number of section slots.
 *
 * <p>The generic section payload lets the page/container logic be tested
 * without Minecraft bootstrap. Runtime integration can use
 * {@code LevelChunkSection} as the payload type.</p>
 */
public final class VerticalPage<S> {

    private final int pageY;
    private final Object[] sections = new Object[VerticalPageLayout.SECTIONS_PER_PAGE];
    private int occupiedSectionCount;

    public VerticalPage(int pageY) {
        this.pageY = pageY;
    }

    public int pageY() {
        return pageY;
    }

    public int occupiedSectionCount() {
        return occupiedSectionCount;
    }

    public boolean isEmpty() {
        return occupiedSectionCount == 0;
    }

    @SuppressWarnings("unchecked")
    public S getLocalSection(int localSectionY) {
        VerticalPageLayout.checkLocalSectionY(localSectionY);
        return (S) sections[localSectionY];
    }

    public S getSection(int absoluteSectionY) {
        checkOwnsSection(absoluteSectionY);
        return getLocalSection(VerticalPageLayout.localSectionY(absoluteSectionY));
    }

    @SuppressWarnings("unchecked")
    public S putLocalSection(int localSectionY, S section) {
        VerticalPageLayout.checkLocalSectionY(localSectionY);
        Objects.requireNonNull(section, "section");

        S previous = (S) sections[localSectionY];
        sections[localSectionY] = section;
        if (previous == null) {
            occupiedSectionCount++;
        }
        return previous;
    }

    public S putSection(int absoluteSectionY, S section) {
        checkOwnsSection(absoluteSectionY);
        return putLocalSection(VerticalPageLayout.localSectionY(absoluteSectionY), section);
    }

    @SuppressWarnings("unchecked")
    public S removeLocalSection(int localSectionY) {
        VerticalPageLayout.checkLocalSectionY(localSectionY);
        S previous = (S) sections[localSectionY];
        if (previous != null) {
            sections[localSectionY] = null;
            occupiedSectionCount--;
        }
        return previous;
    }

    public S removeSection(int absoluteSectionY) {
        checkOwnsSection(absoluteSectionY);
        return removeLocalSection(VerticalPageLayout.localSectionY(absoluteSectionY));
    }

    @SuppressWarnings("unchecked")
    public void forEachOccupiedSection(BiConsumer<Integer, S> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        for (int localSectionY = 0; localSectionY < sections.length; localSectionY++) {
            Object section = sections[localSectionY];
            if (section != null) {
                consumer.accept(
                        VerticalPageLayout.sectionY(pageY, localSectionY),
                        (S) section);
            }
        }
    }

    private void checkOwnsSection(int absoluteSectionY) {
        int actualPageY = VerticalPageLayout.pageYForSectionY(absoluteSectionY);
        if (actualPageY != pageY) {
            throw new IllegalArgumentException(
                    "section Y " + absoluteSectionY + " belongs to page " + actualPageY
                            + ", not page " + pageY);
        }
    }
}
