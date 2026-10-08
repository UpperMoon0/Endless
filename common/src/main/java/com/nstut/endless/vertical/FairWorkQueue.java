package com.nstut.endless.vertical;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
/** Deduplicated round-robin work. Both total and per-owner work per drain are bounded. */
public final class FairWorkQueue<O,K,V> {
 private final Map<O,LinkedHashMap<K,V>> owners=new LinkedHashMap<>();
 public void offer(O owner,K key,V value){owners.computeIfAbsent(owner,k->new LinkedHashMap<>()).put(key,value);}
 public int drain(int total,int perOwner,Consumer<V> work){
  int done=0,visits=owners.size();
  while(done<total && visits-->0 && !owners.isEmpty()){
   O owner=owners.keySet().iterator().next();var queue=owners.remove(owner);
   for(int n=0;n<perOwner && done<total && !queue.isEmpty();n++){
    K key=queue.keySet().iterator().next();V value=queue.remove(key);work.accept(value);done++;
   }
   if(!queue.isEmpty())owners.put(owner,queue);
  }
  return done;
 }
 public void removeOwners(Predicate<O> test){owners.keySet().removeIf(test);}
 public void clear(){owners.clear();}
 public int size(){return owners.values().stream().mapToInt(Map::size).sum();}
}
