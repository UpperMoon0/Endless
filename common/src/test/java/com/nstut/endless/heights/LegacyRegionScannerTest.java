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
