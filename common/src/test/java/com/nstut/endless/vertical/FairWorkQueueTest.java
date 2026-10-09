package com.nstut.endless.vertical;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class FairWorkQueueTest {
 @Test void budgetsDeduplicationAndRotationDoNotStarveLaterPlayers(){var q=new FairWorkQueue<String,Integer,String>();for(int i=0;i<5;i++)q.offer("a",i,"a"+i);q.offer("a",0,"a-new");q.offer("b",0,"b");q.offer("c",0,"c");var out=new ArrayList<String>();assertEquals(2,q.drain(2,2,out::add));assertEquals(List.of("a-new","a1"),out);q.drain(2,2,out::add);assertEquals(List.of("a-new","a1","b","c"),out);assertEquals(3,q.size());}
 @Test void teleportCancelsOldWindowWithoutSpendingSendSlots() {
  var q=new FairWorkQueue<String,Integer,Integer>();for(int n=0;n<50;n++)q.offer("traveler",n,n);
  q.removeOwner("traveler");
  assertTrue(q.offerBounded("traveler",100,100,8));
  var delivered=new ArrayList<Integer>();
  assertEquals(1,q.drain(8,8,delivered::add));
  assertEquals(List.of(100),delivered);
 }
 @Test void staleEntriesPrunedBeforeBudgetAndBacklogBounded() {
  var q=new FairWorkQueue<String,Integer,Integer>();
  for(int n=0;n<100;n++)q.offerBounded("traveler",n,n,12);
  assertEquals(12,q.ownerSize("traveler"));
  q.removeIf(n->n<10);
  assertTrue(q.offerBounded("traveler",100,100,12));
  var delivered=new ArrayList<Integer>();q.drain(10,10,delivered::add);
  assertEquals(List.of(10,11,100),delivered);
 }
 @Test void drainHonorsPerTickByteBudgetAndRetainsRemainingWork() {
  var q=new FairWorkQueue<String,Integer,Integer>();
  for(int n=0;n<5;n++)q.offer("a",n,n);
  var delivered=new ArrayList<Integer>();
  assertEquals(2,q.drainBudgeted(20,20,16,1000000000L,value->{delivered.add(value);return 8;}));
  assertEquals(List.of(0,1),delivered);assertEquals(3,q.size());
 }
 @Test void shutdownAndDisconnectForgetQueuedWorldWork(){var q=new FairWorkQueue<String,Integer,Integer>();q.offer("a",0,1);q.offer("b",0,2);q.removeOwners(k->k.equals("a"));assertEquals(1,q.size());q.clear();assertEquals(0,q.drain(8,8,v->fail()));}
}
