package com.nstut.endless.vertical;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class SectionLightCacheTest {
 @Test void neighboringQueriesReuseOneSectionAndEvictByAccess() {
  var c=new SectionLightCache<Integer>(2);var calls=new AtomicInteger();
  var a=new SectionLightCache.Key(0,-65,0);var b=new SectionLightCache.Key(1,-65,0);var d=new SectionLightCache.Key(2,-65,0);
  assertEquals(1,c.get(a,calls::incrementAndGet));assertEquals(1,c.get(a,calls::incrementAndGet));
  c.get(b,calls::incrementAndGet);c.get(a,calls::incrementAndGet);c.get(d,calls::incrementAndGet);
  assertEquals(1,c.get(a,calls::incrementAndGet));assertEquals(4,c.get(b,calls::incrementAndGet));assertEquals(2,c.size());
 }
 @Test void negativeSeamsAndFullHeightCoordinatesDoNotAlias() {
  var c=new SectionLightCache<Integer>(8);var low=new SectionLightCache.Key(-1,-1,-1);var high=new SectionLightCache.Key(-1,62499,-1);
  c.get(low,()->2);c.get(high,()->14);
  c.invalidateBox(-1,-1,-1,0,0,0);
  assertEquals(0,c.get(low,()->0));assertEquals(14,c.get(high,()->0));
 }
 @Test void invalidationDuringSolveCannotPublishOldLightAndDoesNotBlockWriters() throws Exception {
  var c=new SectionLightCache<Integer>(2);var k=new SectionLightCache.Key(0,0,0);
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
  var executor=Executors.newSingleThreadExecutor();
  try {
   var pending=executor.submit(()->c.get(k,()->{int n=calls.incrementAndGet();if(n==1){entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){throw new RuntimeException(e);}}return n;}));
   assertTrue(entered.await(5,TimeUnit.SECONDS));c.invalidateBox(0,0,0,15,15,15);release.countDown();
   assertEquals(2,pending.get(5,TimeUnit.SECONDS));assertEquals(2,c.get(k,()->99));
  } finally {release.countDown();executor.shutdownNow();}
 }
 @Test void disjointInvalidationDoesNotRestartInFlightSolve() throws Exception {
  var c=new SectionLightCache<Integer>(4);
  var near=new SectionLightCache.Key(-4,-20,2);
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
  var executor=Executors.newSingleThreadExecutor();
  try {
   var pending=executor.submit(()->c.get(near,()->{
    calls.incrementAndGet();entered.countDown();
    try { if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("timed out"); }
    catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}
    return 11;
   }));
   assertTrue(entered.await(5,TimeUnit.SECONDS));
   c.invalidateBox(800,800,800,815,815,815);
   release.countDown();
   assertEquals(11,pending.get(5,TimeUnit.SECONDS));
   assertEquals(1,calls.get(),"Unrelated changes must not restart target solve");
  } finally {release.countDown();executor.shutdownNow();}
 }
 @Test void clearDuringSolveCannotPublishStaleResult() throws Exception {
  var c=new SectionLightCache<Integer>(4);var key=new SectionLightCache.Key(0,0,0);
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
  var executor=Executors.newSingleThreadExecutor();
  try {
   var pending=executor.submit(()->c.get(key,()->{
    int n=calls.incrementAndGet();
    if(n==1){entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){throw new RuntimeException(e);}}
    return n;
   }));
   assertTrue(entered.await(5,TimeUnit.SECONDS));c.clear();release.countDown();
   assertEquals(2,pending.get(5,TimeUnit.SECONDS));assertEquals(2,c.get(key,()->99));
  } finally {release.countDown();executor.shutdownNow();}
 }
 @Test void editsOutsideTheHaloRetainUnrelatedCachedSections() {
  var c=new SectionLightCache<Integer>(4);var near=new SectionLightCache.Key(0,0,0);var far=new SectionLightCache.Key(5,0,0);
  c.get(near,()->15);c.get(far,()->7);c.invalidateBox(-15,-15,-15,30,30,30);
  assertEquals(0,c.get(near,()->0));assertEquals(7,c.get(far,()->0));
 }
}
