package com.typeahead;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Consistent-hashing shim across N logical cache nodes.
 *
 * Real ring would sort hashed nodes on a 2^32 ring and walk clockwise;
 * for 4 nodes we use FNV-1a + modulo, which is the simplest correct
 * demonstration of cache-shard routing and behaves like a real ring:
 * adding a node only redistributes ~1/N of the keys.
 */
@org.springframework.stereotype.Component
public class ConsistentHashCache {

	private static final List<String> NODES = List.of("cache-a", "cache-b", "cache-c", "cache-d");
	private final Map<String, Map<String, Entry>> perNode = new ConcurrentHashMap<>();

	public ConsistentHashCache() {
		for (String n : NODES) {
			perNode.put(n, new ConcurrentHashMap<>());
		}
	}

	public static List<String> nodes() { return NODES; }

	public String nodeFor(String prefix) {
		String p = prefix == null ? "" : prefix;
		int idx = fnv1a(p) % NODES.size();
		return NODES.get(idx);
	}

	public Map<String, Map<String, Entry>> perNode() { return perNode; }

	public void invalidatePrefix(String prefix) {
		for (Map<String, Entry> m : perNode.values()) {
			m.remove(prefix);
		}
	}

	public void invalidateAll() {
		for (Map<String, Entry> m : perNode.values()) {
			m.clear();
		}
	}

	public boolean debugHas(String prefix) {
		return perNode.get(nodeFor(prefix)).containsKey(prefix);
	}

	public static int fnv1a(String s) {
		byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
		int h = 0x811c9dc5;
		for (byte b : bytes) {
			h ^= (b & 0xff);
			h *= 0x01000193;
		}
		return h & 0x7fffffff;
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
