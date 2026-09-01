package com.nstut.endless.heights;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LegacyRegionScannerTest {

    @Test
    void nonAirBlockInBottomRawEdgeIsMeaningful() {
        CompoundTag chunk = chunkWithSection(-128, "minecraft:stone");
        LegacyRegionScanner.EdgeUsage usage = LegacyRegionScanner.inspectChunk(chunk, true, false);
        assertTrue(usage.bottomHasMeaningfulData());
        assertFalse(usage.topHasMeaningfulData());
    }

    @Test
    void airOnlyRawEdgeSectionCanBeDiscarded() {
        CompoundTag chunk = chunkWithSection(127, "minecraft:air");
        LegacyRegionScanner.EdgeUsage usage = LegacyRegionScanner.inspectChunk(chunk, false, true);
        assertFalse(usage.bottomHasMeaningfulData());
        assertFalse(usage.topHasMeaningfulData());
    }

    @Test
    void edgeBlockEntityIsMeaningfulEvenWithAirPalette() {
        CompoundTag chunk = chunkWithSection(127, "minecraft:air");
        ListTag blockEntities = new ListTag();
        CompoundTag blockEntity = new CompoundTag();
        blockEntity.putInt("y", 2032);
        blockEntities.add(blockEntity);
        chunk.put("block_entities", blockEntities);

        LegacyRegionScanner.EdgeUsage usage = LegacyRegionScanner.inspectChunk(chunk, false, true);
        assertTrue(usage.topHasMeaningfulData());
    }

    @Test
    void malformedEdgePaletteFailsClosed() {
        CompoundTag chunk = new CompoundTag();
        ListTag sections = new ListTag();
        CompoundTag section = new CompoundTag();
        section.putByte("Y", (byte) -128);
        section.put("block_states", new CompoundTag());
        sections.add(section);
        chunk.put("sections", sections);

        LegacyRegionScanner.EdgeUsage usage = LegacyRegionScanner.inspectChunk(chunk, true, false);
        assertTrue(usage.bottomHasMeaningfulData());
    }

    @Test
    void changedGlobalConfigCannotHideMeaningfulSavedSection() {
        // Current/raw config says vanilla [-64,320), but this world contains a
        // saved section around block Y=592 from an older wider configuration.
        CompoundTag chunk = chunkWithSection(37, "minecraft:stone");
        LegacyRegionScanner.WorldEvidence evidence =
            LegacyRegionScanner.inspectChunkAgainstCandidate(chunk, -64, 320, -64, 320);

        assertTrue(evidence.meaningfulDataOutsideCandidate());
        assertEquals(37, evidence.outsideSectionY());
        assertTrue(evidence.blocksMigration());
    }

    @Test
    void airOnlySavedSectionStillProvesWiderHistoricalArray() {
        // Vanilla serializes block_states/biomes for every real section in the
        // current section array. Even an air-only Y=37 tag therefore proves
        // this chunk was saved with a range wider than [-64,320).
        CompoundTag chunk = chunkWithSection(37, "minecraft:air");
        LegacyRegionScanner.WorldEvidence evidence =
            LegacyRegionScanner.inspectChunkAgainstCandidate(chunk, -64, 320, -64, 320);

        assertTrue(evidence.meaningfulDataOutsideCandidate());
        assertEquals(37, evidence.outsideSectionY());
    }

    @Test
    void saved64LongHeightmapRejectsNarrowCurrentConfig() {
        CompoundTag chunk = chunkWithSection(0, "minecraft:stone");
        CompoundTag heightmaps = new CompoundTag();
        heightmaps.putLongArray("MOTION_BLOCKING", new long[64]);
        chunk.put("Heightmaps", heightmaps);

        LegacyRegionScanner.WorldEvidence evidence =
            LegacyRegionScanner.inspectChunkAgainstCandidate(chunk, -64, 320, -64, 320);

        assertTrue(evidence.heightmapLayoutMismatch());
        assertEquals(64, evidence.savedHeightmapLongs());
        assertEquals(LegacyRegionScanner.heightmapStorageLongs(384), evidence.expectedHeightmapLongs());
        assertTrue(evidence.blocksMigration());
    }

    @Test
    void raw4096HistoryAccepts64LongHeightmapWhenGuardEdgesAreEmpty() {
        CompoundTag chunk = chunkWithSection(0, "minecraft:stone");
        CompoundTag heightmaps = new CompoundTag();
        heightmaps.putLongArray("MOTION_BLOCKING", new long[64]);
        chunk.put("Heightmaps", heightmaps);

        LegacyRegionScanner.WorldEvidence evidence =
            LegacyRegionScanner.inspectChunkAgainstCandidate(chunk, -2032, 2032, -2048, 2048);

        assertFalse(evidence.heightmapLayoutMismatch());
        assertFalse(evidence.meaningfulDataOutsideCandidate());
        assertFalse(evidence.blocksMigration());
    }

    @Test
    void airOnlyRawGuardSectionMayStillBeDiscarded() {
        CompoundTag chunk = chunkWithSection(127, "minecraft:air");
        LegacyRegionScanner.WorldEvidence evidence =
            LegacyRegionScanner.inspectChunkAgainstCandidate(chunk, -2032, 2032, -2048, 2048);

        assertFalse(evidence.meaningfulDataOutsideCandidate());
        assertFalse(evidence.blocksMigration());
    }

    @Test
    void meaningfulRawGuardSectionStillRefuses() {
        CompoundTag chunk = chunkWithSection(-128, "minecraft:stone");
        LegacyRegionScanner.WorldEvidence evidence =
            LegacyRegionScanner.inspectChunkAgainstCandidate(chunk, -2032, 2032, -2048, 2048);

        assertTrue(evidence.meaningfulDataOutsideCandidate());
        assertEquals(-128, evidence.outsideSectionY());
    }

    @Test
    void vanillaHeightmapPackingLengthsMatchKnownLayouts() {
        assertEquals(37, LegacyRegionScanner.heightmapStorageLongs(384));
        assertEquals(52, LegacyRegionScanner.heightmapStorageLongs(4064));
        assertEquals(64, LegacyRegionScanner.heightmapStorageLongs(4096));
    }

    private static CompoundTag chunkWithSection(int sectionY, String blockName) {
        CompoundTag chunk = new CompoundTag();
        ListTag sections = new ListTag();
        CompoundTag section = new CompoundTag();
        section.putByte("Y", (byte) sectionY);

        CompoundTag blockStates = new CompoundTag();
        ListTag palette = new ListTag();
        CompoundTag state = new CompoundTag();
        state.putString("Name", blockName);
        palette.add(state);
        blockStates.put("palette", palette);
        section.put("block_states", blockStates);
        sections.add(section);
        chunk.put("sections", sections);
        return chunk;
    }
}
