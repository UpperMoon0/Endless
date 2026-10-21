package com.nstut.endless.vertical.lod;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VerticalLodMeshTest {
    @Test void blackBlocksAndEmissionSurviveReductionWithoutChangingRgb() {
        var black=VerticalLodMesh.build((x,y,z)->0x10000000,4);
        assertEquals(1,black.boxes().size());
        assertEquals(0x10000000,black.boxes().get(0).rgb());
        var glow=VerticalLodMesh.build((x,y,z)->x==0?0x1fff0000:0x10ff0000,4);
        assertTrue(glow.boxes().stream().anyMatch(box->box.rgb()==0x1fff0000));
        assertTrue(glow.boxes().stream().allMatch(box->(box.rgb()&0xffffff)==0xff0000));
    }
    @Test void thinCableKeepsItsBoundsAtEveryDetailLevel() {
        for (int cell : new int[]{1, 2, 4, 8, 16}) {
            var mesh = VerticalLodMesh.build((x,y,z) -> x == 7 && z == 9 ? 0xff808080 : 0, cell);
            assertEquals(java.util.List.of(new VerticalLodMesh.Box(7,0,9,8,16,10,0xff808080)), mesh.boxes());
        }
    }
    @Test void airGapsAreNeverBridgedVertically() {
        var mesh = VerticalLodMesh.build((x,y,z) -> x == 0 && z == 0 && y != 8 ? 0xffffffff : 0, 4);
        assertEquals(2, mesh.boxes().size());
        assertEquals(8, mesh.boxes().get(0).y1());
        assertEquals(9, mesh.boxes().get(1).y0());
    }
    @Test void colorsAreAveragedOnlyAcrossOccupiedCellsAndRunsSplitAtMaterialChanges() {
        var mesh = VerticalLodMesh.build((x,y,z) -> z == 0 && x < 2
            ? (y < 8 ? (x == 0 ? 0xffff0000 : 0xff0000ff) : 0xff00ff00) : 0, 4);
        assertEquals(2, mesh.boxes().size());
        assertEquals(0xff7f007f, mesh.boxes().get(0).rgb());
        assertEquals(0xff00ff00, mesh.boxes().get(1).rgb());
    }
    @Test void solidSectionsReduceWithoutSamplingAwayIsolatedCells() {
        var solid = VerticalLodMesh.build((x,y,z) -> 0xff808080, 4);
        assertEquals(1, solid.boxes().size());
        assertEquals(12, solid.triangles());
        var isolated = VerticalLodMesh.build((x,y,z) -> x == 15 && y == 15 && z == 15 ? -1 : 0,16);
        assertEquals(new VerticalLodMesh.Box(15,15,15,16,16,16,-1), isolated.boxes().get(0));
        assertTrue(VerticalLodMesh.build((x,y,z) -> 0,4).boxes().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> VerticalLodMesh.build((x,y,z)->0,3));
    }
}
