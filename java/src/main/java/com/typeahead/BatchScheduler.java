package com.typeahead;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class BatchScheduler {
 private final Store store;
 public BatchScheduler(Store store) { this.store = store; }

 /** Flush the write buffer every 2 seconds. */
 @Scheduled(fixedDelay = 2000L)
 public void tick() { store.flush(); }
}
