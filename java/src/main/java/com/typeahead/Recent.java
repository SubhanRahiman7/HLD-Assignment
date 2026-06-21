package com.typeahead;

import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** User-recent search list with case-insensitive dedupe, capped at 6. */
@Component
public class Recent {
 public final List<String> items = Collections.synchronizedList(new ArrayList<>());
 private static final int CAP = 6;

 public List<String> get() {
 synchronized (items) { return new ArrayList<>(items.subList(0, Math.min(CAP, items.size()))); }
 }

 public void add(String q) {
 if (q == null || q.isBlank()) return;
 String k = q.toLowerCase();
 synchronized (items) {
 items.removeIf(r -> r.toLowerCase().equals(k));
 items.add(0, q);
 while (items.size() > CAP) items.remove(items.size() - 1);
 }
 }

 public void clear() { synchronized (items) { items.clear(); } }
}
