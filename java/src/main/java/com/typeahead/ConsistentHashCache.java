package com.typeahead;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Real consistent-hash ring.
 *
 * Each of N logical nodes is hashed VIRTUAL_NODES times onto a 2^32 ring.
 * Lookup: hash(key) -> walk clockwise -> first node >= hash owns the key.
 *
 * Properties:
 * - Adding a node only moves ~1/N of keys (the ones between it and its predecessor).
 * - Removing a node only moves ~1/N of keys.
 * - Each node owns ~equal total keys (because of virtual nodes).
 *
 * Modulo-N would NOT have these properties; every key reshuffles on N change.
 */
@org.springframework.stereotype.Component
public class ConsistentHashCache {

 private static final List<String> NODES = List.of("cache-a", "cache-b", "cache-c", "cache-d");
 private static final int VIRTUAL_NODES = 200;

 private final NavigableMap<Long, String> ring = new ConcurrentSkipListMap<>();
 private final Map<String, Map<String, Entry>> perNode = new ConcurrentHashMap<>();

 public ConsistentHashCache() {
 for (String n : NODES) perNode.put(n, new ConcurrentHashMap<>());
 for (String n : NODES) {
 for (int i = 0; i < VIRTUAL_NODES; i++) {
 ring.put(fnv1a(n + "#" + i), n);
 }
 }
 }

 public static List<String> nodes() { return NODES; }

 /** Walk clockwise from the key's hash to the first node. */
 public String nodeFor(String prefix) {
 String p = prefix == null ? "" : prefix;
 if (p.isEmpty()) return NODES.get(0);
 Long h = fnv1a(p);
 var entry = ring.ceilingEntry(h);
 if (entry == null) entry = ring.firstEntry();
 return entry.getValue();
 }

 public Map<String, Map<String, Entry>> perNode() { return perNode; }

 public void invalidateAll() {
 for (Map<String, Entry> m : perNode.values()) m.clear();
 }

 public boolean debugHas(String prefix) {
 return perNode.get(nodeFor(prefix)).containsKey(prefix);
 }

 public static long fnv1a(String s) {
 byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
 long h = 0x811c9dc5L;
 for (byte b : bytes) {
 h ^= (b & 0xff);
 h *= 0x01000193L;
 }
 return h & 0x7fffffffL;
 }

 public static final class Entry {
 public final long at;
 public final List<Suggestion> list;
 public Entry(long at, List<Suggestion> list) {
 this.at = at;
 this.list = list;
 }
 }
}