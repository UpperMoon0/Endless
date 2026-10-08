package com.nstut.endless.vertical;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class FairWorkQueueTest {
 @Test void budgetsDeduplicationAndRotationDoNotStarveLaterPlayers(){var q=new FairWorkQueue<String,Integer,String>();for(int i=0;i<5;i++)q.offer("a",i,"a"+i);q.offer("a",0,"a-new");q.offer("b",0,"b");q.offer("c",0,"c");var out=new ArrayList<String>();assertEquals(2,q.drain(2,2,out::add));assertEquals(List.of("a-new","a1"),out);q.drain(2,2,out::add);assertEquals(List.of("a-new","a1","b","c"),out);assertEquals(3,q.size());}
 @Test void shutdownAndDisconnectForgetQueuedWorldWork(){var q=new FairWorkQueue<String,Integer,Integer>();q.offer("a",0,1);q.offer("b",0,2);q.removeOwners(k->k.equals("a"));assertEquals(1,q.size());q.clear();assertEquals(0,q.drain(8,8,v->fail()));}
}
