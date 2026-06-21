package com.typeahead;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TypeaheadApplication {

 public static void main(String[] args) {
 SpringApplication.run(TypeaheadApplication.class, args);
 }
}