package com.nstut.endless.heights;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LegacyWorldMigrationTest {

    @Test
    void playedLegacyWorldWithSafeRawConfigIsOnlyACandidate() {
        LegacyWorldMigration.Resolution result = LegacyWorldMigration.classify(-1024, 1024);
        assertEquals(LegacyWorldMigration.Status.MIGRATE, result.status());
        assertEquals(-1024, result.legacyMin());
        assertEquals(1024, result.legacyMax());
        assertEquals(-1024, result.migratedMin());
        assertEquals(1024, result.migratedMax());
    }

    @Test
    void legacyRawEdgeSectionsRequireInspection() {
        LegacyWorldMigration.Resolution result = LegacyWorldMigration.classify(-2048, 2048);
        assertEquals(LegacyWorldMigration.Status.INSPECT_EDGE_SECTIONS, result.status());
        assertTrue(result.inspectBottomEdge());
        assertTrue(result.inspectTopEdge());
        assertEquals(-2048, result.legacyMin());
        assertEquals(2048, result.legacyMax());
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
    void changedConfigWorldEvidenceRefusesNarrowMigration() {
        LegacyWorldMigration.Resolution preliminary = LegacyWorldMigration.classify(-64, 320);
        LegacyRegionScanner.WorldEvidence evidence = new LegacyRegionScanner.WorldEvidence(
            true, 37, false, -1, LegacyRegionScanner.heightmapStorageLongs(384));

        LegacyWorldMigration.Resolution result =
            LegacyWorldMigration.resolveWorldInspection(preliminary, evidence);

        assertEquals(LegacyWorldMigration.Status.REFUSE, result.status());
        assertTrue(result.reason().contains("section Y=37"));
        assertTrue(result.reason().contains("not trustworthy world history"));
    }

    @Test
    void conflictingHeightmapLayoutRefusesNarrowMigration() {
        LegacyWorldMigration.Resolution preliminary = LegacyWorldMigration.classify(-64, 320);
        LegacyRegionScanner.WorldEvidence evidence = new LegacyRegionScanner.WorldEvidence(
            false, -1, true, 64, LegacyRegionScanner.heightmapStorageLongs(384));

        LegacyWorldMigration.Resolution result =
            LegacyWorldMigration.resolveWorldInspection(preliminary, evidence);

        assertEquals(LegacyWorldMigration.Status.REFUSE, result.status());
        assertTrue(result.reason().contains("64 longs"));
        assertTrue(result.reason().contains("disagrees with the world's saved vertical layout"));
    }

    @Test
    void matchingPlayedWorldEvidenceAllowsSafeCandidate() {
        LegacyWorldMigration.Resolution preliminary = LegacyWorldMigration.classify(-64, 320);
        LegacyRegionScanner.WorldEvidence evidence =
            LegacyRegionScanner.WorldEvidence.none(LegacyRegionScanner.heightmapStorageLongs(384));

        LegacyWorldMigration.Resolution result =
            LegacyWorldMigration.resolveWorldInspection(preliminary, evidence);

        assertEquals(LegacyWorldMigration.Status.MIGRATE, result.status());
        assertEquals(-64, result.migratedMin());
        assertEquals(320, result.migratedMax());
    }

    @Test
    void legacySpanGreaterThan4096RefusesAutomaticMigration() {
        LegacyWorldMigration.Resolution result = LegacyWorldMigration.classify(-2048, 2064);
        assertEquals(LegacyWorldMigration.Status.REFUSE, result.status());
        assertTrue(result.reason().contains("cannot be reconstructed safely"));
    }

    @Test
    void offsetRangeOutsideSignedByteEnvelopeAlsoRefuses() {
        LegacyWorldMigration.Resolution result = LegacyWorldMigration.classify(-3000, 900);
        assertEquals(LegacyWorldMigration.Status.REFUSE, result.status());
    }

    @Test
    void rawNonAlignedRangeIsNormalizedBeforeClassification() {
        LegacyWorldMigration.Resolution result = LegacyWorldMigration.classify(-65, 321);
        assertEquals(LegacyWorldMigration.Status.MIGRATE, result.status());
        assertEquals(-80, result.legacyMin());
        assertEquals(336, result.legacyMax());
        assertEquals(-80, result.migratedMin());
        assertEquals(336, result.migratedMax());
    }
}
