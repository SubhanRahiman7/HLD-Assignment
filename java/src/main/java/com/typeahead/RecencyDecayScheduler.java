package com.typeahead;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs Store.decayRecency() periodically so old 5m activity fades and
 * queries that were hot only briefly stop dominating trending over time.
 */
@Component
public class RecencyDecayScheduler {

 private final Store store;
 public RecencyDecayScheduler(Store store) { this.store = store; }

 @Scheduled(fixedDelay = 5_000L)
 public void tick() { store.decayRecency(); }
}