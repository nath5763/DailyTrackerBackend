package com.dailygames;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static com.dailygames.ApiModels.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class DailyGamesIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    properties.add("spring.datasource.username", POSTGRES::getUsername);
    properties.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired DailyGamesService service;
  @Autowired UserRepository users;

  @Test void onboardingCreatesDefaultPrivateBoard() {
    UserAccount owner = users.save(new UserAccount("owner@example.com", Instant.now()));
    CurrentUser current = new CurrentUser(owner.id, owner.normalizedEmail);
    ProfileView profile = service.onboard(current, new OnboardingRequest("Owner", "#dfff4f"));
    assertNotNull(profile.shareId());
    assertEquals(1, service.listBoards(current).size());
    assertEquals("My Friends", service.listBoards(current).getFirst().name());

    UserAccount stranger = users.save(new UserAccount("stranger@example.com", Instant.now()));
    CurrentUser other = new CurrentUser(stranger.id, stranger.normalizedEmail);
    service.onboard(other, new OnboardingRequest("Stranger", "#ff8db6"));
    ApiException hidden = assertThrows(ApiException.class,
        () -> service.board(other, service.listBoards(current).getFirst().id(), "today", "fermi"));
    assertEquals(404, hidden.status);
  }
}
