package com.nstut.endless.vertical;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WeightedCacheTest {
 @Test void replacementEvictionAndOversizeStayWithinByteBudget(){var c=new WeightedCache<String,Integer>(10);c.put("a",1,6);c.put("b",2,4);assertEquals(1,c.get("a"));c.put("c",3,5);assertNull(c.get("b"));assertNull(c.get("a"));assertEquals(5,c.bytes());c.put("c",4,11);assertNull(c.get("c"));assertEquals(0,c.bytes());}
 @Test void invalidationDropsExactlyOwnedSnapshots(){var c=new WeightedCache<Integer,Integer>(10);c.put(1,4,5);c.put(2,8,5);c.removeIf(k->k==1);assertEquals(5,c.bytes());assertEquals(8,c.get(2));c.clear();assertEquals(0,c.bytes());}
}
