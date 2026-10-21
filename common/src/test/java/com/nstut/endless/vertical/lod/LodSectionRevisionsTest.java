package com.nstut.endless.vertical.lod;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LodSectionRevisionsTest {
    @Test void blockEditsInvalidateOnlyTheirSectionAcrossTheEntireHeightEnvelope() {
        var index=new LodSectionRevisions();
        for(int y:new int[]{-500000,-1,0,499999}) {
            index.touchSection(-12,y,17);
            long revision=index.revision(-12,y,17);
            assertTrue(revision>0);
            assertEquals(0,index.revision(-12,y,18));
            assertEquals(0,index.revision(-13,y,17));
            assertEquals(0,index.revision(-12,y+1,17));
        }
    }
    @Test void pageReplacementIncludesRemovedSectionsAndUnloadKeepsOtherColumns() {
        var index=new LodSectionRevisions();
        index.touchPage(7,-2,9);
        long first=index.revision(7,-64,9);
        assertEquals(first,index.revision(7,-33,9));
        assertEquals(0,index.revision(7,-32,9));
        index.touchSection(7,-40,9);
        assertTrue(index.revision(7,-40,9)>first);
        assertEquals(first,index.revision(7,-41,9));
        index.touchPage(7,-2,9);
        assertTrue(index.revision(7,-64,9)>first);
        index.touchSection(8,-40,9);
        long other=index.revision(8,-40,9),before=index.generation();
        index.unloadColumn(7,9);
        assertTrue(index.generation()>before);
        assertEquals(0,index.revision(7,-40,9));
        assertEquals(other,index.revision(8,-40,9));
        index.clear(); assertEquals(0,index.revision(8,-40,9));
    }
}
