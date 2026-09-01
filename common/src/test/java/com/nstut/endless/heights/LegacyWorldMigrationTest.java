package com.nstut.endless.heights;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LegacyWorldMigrationTest {

    @Test
    void playedLegacyWorldWithSafeRawConfigMigrates() {
        LegacyWorldMigration.Resolution result = LegacyWorldMigration.classify(-1024, 1024);
        assertEquals(LegacyWorldMigration.Status.MIGRATE, result.status());
        assertEquals(-1024, result.migratedMin());
        assertEquals(1024, result.migratedMax());
    }

    @Test
    void legacyRawEdgeSectionsRequireInspection() {
        LegacyWorldMigration.Resolution result = LegacyWorldMigration.classify(-2048, 2048);
        assertEquals(LegacyWorldMigration.Status.INSPECT_EDGE_SECTIONS, result.status());
        assertTrue(result.inspectBottomEdge());
        assertTrue(result.inspectTopEdge());
        assertEquals(-2032, result.migratedMin());
        assertEquals(2032, result.migratedMax());
    }

    @Test
    void meaningfulLegacyEdgeDataRefusesAutomaticMigration() {
        LegacyWorldMigration.Resolution preliminary = LegacyWorldMigration.classify(-2048, 2048);
        LegacyWorldMigration.Resolution result = LegacyWorldMigration.resolveEdgeInspection(
            preliminary, true, false);
        assertEquals(LegacyWorldMigration.Status.REFUSE, result.status());
        assertTrue(result.reason().contains("Y=-128"));
    }

    @Test
    void emptyLegacyEdgeSectionsMayClampToGuardedEnvelope() {
        LegacyWorldMigration.Resolution preliminary = LegacyWorldMigration.classify(-2048, 2048);
        LegacyWorldMigration.Resolution result = LegacyWorldMigration.resolveEdgeInspection(
            preliminary, false, false);
        assertEquals(LegacyWorldMigration.Status.MIGRATE, result.status());
        assertEquals(-2032, result.migratedMin());
        assertEquals(2032, result.migratedMax());
    }

    @Test
    void legacySpanGreaterThan4096RefusesAutomaticMigration() {
        LegacyWorldMigration.Resolution result = LegacyWorldMigration.classify(-2048, 2064);
        assertEquals(LegacyWorldMigration.Status.REFUSE, result.status());
        assertTrue(result.reason().contains("cannot be reconstructed safely"));
    }

    @Test
    void offsetRangeOutsideSignedByteEnvelopeAlsoRefuses() {
        // Span is below 4096, but section Y would still wrap because the range
        // itself extends below the signed-byte section coordinate envelope.
        LegacyWorldMigration.Resolution result = LegacyWorldMigration.classify(-3000, 900);
        assertEquals(LegacyWorldMigration.Status.REFUSE, result.status());
    }

    @Test
    void rawNonAlignedRangeIsNormalizedBeforeClassification() {
        LegacyWorldMigration.Resolution result = LegacyWorldMigration.classify(-65, 321);
        assertEquals(LegacyWorldMigration.Status.MIGRATE, result.status());
        assertEquals(-80, result.migratedMin());
        assertEquals(336, result.migratedMax());
    }
}
