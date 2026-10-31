package com.dailygames;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

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
    properties.add("app.brevo.api-key", () -> "integration-test-key");
    properties.add("app.brevo.sender-email", () -> "sender@example.com");
  }

  @Autowired DailyGamesService service;
  @Autowired UserRepository users;

  @Test void onboardingLeavesFreeLeaderboardForExplicitCreation() {
    UserAccount owner = users.save(new UserAccount("owner@example.com", Instant.now()));
    CurrentUser current = new CurrentUser(owner.id, owner.normalizedEmail);
    ProfileView profile = service.onboard(current, new OnboardingRequest("Owner", "#dfff4f"));
    assertNotNull(profile.shareId());
    assertTrue(service.listBoards(current).isEmpty());

    assertEquals(List.of("fermi", "krillion"), service.listGames().stream().map(GameView::slug).sorted().toList());

    var boardId = service.createBoard(current, new CreateLeaderboardRequest("My Friends", "krillion"));
    assertEquals(1, service.listBoards(current).size());
    assertEquals("krillion", service.listBoards(current).getFirst().game().slug());
    assertFalse(service.listBoards(current).getFirst().locked());
    ApiException subscriptionRequired = assertThrows(ApiException.class,
        () -> service.createBoard(current, new CreateLeaderboardRequest("Second board", "fermi")));
    assertEquals(402, subscriptionRequired.status);
    assertEquals("SUBSCRIPTION_REQUIRED", subscriptionRequired.code);

    UserAccount stranger = users.save(new UserAccount("stranger@example.com", Instant.now()));
    CurrentUser other = new CurrentUser(stranger.id, stranger.normalizedEmail);
    service.onboard(other, new OnboardingRequest("Stranger", "#ff8db6"));
    ApiException hidden = assertThrows(ApiException.class,
        () -> service.board(other, boardId, "today"));
    assertEquals(404, hidden.status);

    service.addMember(current, boardId, service.me(other).profile().shareId());
    String ownerShare = "Krillion #82 🦐\n180\n\n🐟🫧🫧🐟🦑🫧🐟";
    String strangerShare = "Krillion #82 🦐\n700\n\n🌟🌟🌟🌟🌟🌟🌟";
    ParsedSubmission ownerPreview = service.preview(current, new PreviewSubmissionRequest("krillion", ownerShare));
    ParsedSubmission strangerPreview = service.preview(other, new PreviewSubmissionRequest("krillion", strangerShare));
    service.submit(current, new CreateSubmissionRequest("krillion", ownerShare, confirmed(ownerPreview)));
    service.submit(other, new CreateSubmissionRequest("krillion", strangerShare, confirmed(strangerPreview)));

    LeaderboardResponse leaderboard = service.board(current, boardId, "all");
    assertEquals("Stranger", leaderboard.rankings().getFirst().displayName());
    assertEquals(new BigDecimal("700.0000"), leaderboard.rankings().getFirst().average());
    assertEquals(new BigDecimal("180.0000"), service.dashboard(current).statsByGame().getFirst().average());
  }

  private ConfirmedSubmission confirmed(ParsedSubmission parsed) {
    return new ConfirmedSubmission(parsed.puzzleKey(), parsed.puzzleNumber(), parsed.playedOn(), parsed.primaryScore(),
        parsed.metrics(), parsed.parserVersion());
  }
}
