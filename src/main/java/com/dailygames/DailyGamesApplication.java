package com.dailygames;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DailyGamesApplication {
  public static void main(String[] args) {
    SpringApplication.run(DailyGamesApplication.class, args);
  }
}
