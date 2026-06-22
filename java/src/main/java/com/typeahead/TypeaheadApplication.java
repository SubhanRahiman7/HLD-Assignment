package com.typeahead;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TypeaheadApplication {

 public static void main(String[] args) {
 // Auto-enable fallback profile when REDIS_URL is empty so Lettuce
 // auto-config does not try to connect to a non-running Redis.
 if (System.getenv("REDIS_URL") == null || System.getenv("REDIS_URL").isBlank()) {
 if (System.getProperty("spring.profiles.active") == null
 && System.getenv("SPRING_PROFILES_ACTIVE") == null) {
 System.setProperty("spring.profiles.active", "fallback");
 }
 }
 SpringApplication.run(TypeaheadApplication.class, args);
 }
}
