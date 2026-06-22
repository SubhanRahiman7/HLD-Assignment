package com.typeahead;

import jakarta.persistence.*;

@Entity
@Table(name = "query_counts", indexes = {
 @Index(name = "idx_query_counts_query", columnList = "query", unique = true)
})
public class QueryCountEntity {

 @Id
 @GeneratedValue(strategy = GenerationType.IDENTITY)
 private Long id;

 @Column(nullable = false, length = 256)
 private String query;

 @Column(nullable = false)
 private long count;

 public QueryCountEntity() {}

 public QueryCountEntity(String query, long count) {
 this.query = query;
 this.count = count;
 }

 public String getQuery() { return query; }
 public void setQuery(String query) { this.query = query; }
 public long getCount() { return count; }
 public void setCount(long count) { this.count = count; }
}