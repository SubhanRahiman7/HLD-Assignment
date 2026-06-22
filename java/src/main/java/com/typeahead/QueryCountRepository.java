package com.typeahead;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface QueryCountRepository extends JpaRepository<QueryCountEntity, Long> {

 Optional<QueryCountEntity> findByQuery(String query);

 @Query("SELECT q FROM QueryCountEntity q WHERE LOWER(q.query) LIKE LOWER(CONCAT(:prefix, '%')) ORDER BY q.count DESC")
 List<QueryCountEntity> findPrefixTop(@Param("prefix") String prefix, Pageable pageable);

 @Query("SELECT q FROM QueryCountEntity q ORDER BY q.count DESC")
 List<QueryCountEntity> findTopAll(Pageable pageable);

 @Modifying
 @Query("UPDATE QueryCountEntity q SET q.count = q.count + :delta WHERE q.query = :query")
 int incrementCount(@Param("query") String query, @Param("delta") long delta);
}