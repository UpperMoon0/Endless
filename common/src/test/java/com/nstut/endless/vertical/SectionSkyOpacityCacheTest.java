package com.nstut.endless.vertical;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SectionSkyOpacityCacheTest {
    @Test void everyRayRetainsItsAttenuationWhileSharedVoxelsAreSampledOnlyOnce() {
        for(int fixture=0;fixture<3;fixture++) {
            final int mode=fixture;AtomicInteger reads=new AtomicInteger();
            SectionSkyOpacityCache.Sampler world=(x,y,z)->mode==0?0:mode==1?(y==19&&x%7!=0?15:0):Math.floorMod(x*13+y*3+z*7,5);
            SectionSkyOpacityCache cache=new SectionSkyOpacityCache(-15,0,-15,(x,y,z)->{reads.incrementAndGet();return world.sample(x,y,z);});
            int raw=0;
            for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                for(int[] ray:new int[][]{{0,1,0},{1,0,0},{-1,0,0},{0,0,1},{0,0,-1}}) {
                    int original=0,cached=0;
                    for(int d=1;d<=15&&original<15;d++) {
                        int sx=x+d*ray[0],sy=y+d*ray[1],sz=z+d*ray[2];
                        original+=Math.max(1,world.sample(sx,sy,sz));cached+=cache.get(sx,sy,sz);raw++;
                        assertEquals(Math.min(15,original),Math.min(15,cached));
                    }
                }
            }
            assertTrue(reads.get()<=SectionSkyOpacityCache.CAPACITY);
            assertTrue(reads.get()<raw/2,"Repeated block reads were not eliminated: "+reads+" / "+raw);
        }
    }
    @Test void extremeNegativeCoordinatesRemainBoundedAndIndependentAcrossSolves() {
        AtomicInteger calls=new AtomicInteger();
        var one=new SectionSkyOpacityCache(-8_000_015,-8_000_000,-15,(x,y,z)->{calls.incrementAndGet();return 0;});
        assertEquals(1,one.get(-8_000_000,-7_999_970,15));assertEquals(1,one.get(-8_000_000,-7_999_970,15));assertEquals(1,calls.get());
        var two=new SectionSkyOpacityCache(-8_000_015,-8_000_000,-15,(x,y,z)->15);
        assertEquals(15,two.get(-8_000_000,-7_999_970,15));
        assertThrows(IllegalArgumentException.class,()->one.get(-8_000_000,-7_999_969,15));
    }
}
