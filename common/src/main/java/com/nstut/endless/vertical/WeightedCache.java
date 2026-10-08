package com.nstut.endless.vertical;
import java.util.LinkedHashMap;
import java.util.Map;
/** Owner-synchronized immutable snapshot cache with a hard byte budget. */
public final class WeightedCache<K,V> {
 private record Value<V>(V value,int bytes) {}
 private final int limit; private int bytes;
 private final Map<K,Value<V>> entries=new LinkedHashMap<>(64,.75f,true);
 public WeightedCache(int limit){if(limit<1)throw new IllegalArgumentException();this.limit=limit;}
 public V get(K key){var v=entries.get(key);return v==null?null:v.value;}
 public void remove(K key){var old=entries.remove(key);if(old!=null)bytes-=old.bytes;}
 public void put(K key,V value,int weight){if(weight<0)throw new IllegalArgumentException();remove(key);if(weight>limit)return;entries.put(key,new Value<>(value,weight));bytes+=weight;while(bytes>limit || entries.size()>256)remove(entries.keySet().iterator().next());}
 public void removeIf(java.util.function.Predicate<K> test){for(K key:new java.util.ArrayList<>(entries.keySet()))if(test.test(key))remove(key);}
 public void clear(){entries.clear();bytes=0;}
 public int bytes(){return bytes;}
}
