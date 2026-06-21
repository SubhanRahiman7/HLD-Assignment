package com.typeahead;

import java.util.Objects;

/** A primary-store row: query + all-time count. */
public class QueryCount {
 private final String q;
 private long c;

 public QueryCount(String q, long c) { this.q = q; this.c = c; }
 public String q() { return q; }
 public long c() { return c; }
 public void increment(long delta) { this.c += delta; }

 @Override public boolean equals(Object o) {
 return o instanceof QueryCount qc && Objects.equals(q, qc.q);
 }
 @Override public int hashCode() { return Objects.hash(q); }
}
