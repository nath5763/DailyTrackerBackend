package com.dailygames;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
    properties.add("app.stripe.premium-price-id", () -> "price_premium");
  }

  @Autowired DailyGamesService service;
  @Autowired UserRepository users;
  @Autowired BillingAccountRepository billingAccounts;
  @Autowired LeaderboardRepository boards;
  @Autowired JdbcTemplate jdbc;

  @Test void onboardingLeavesFreeLeaderboardForExplicitCreation() {
    UserAccount owner = users.save(new UserAccount("owner@example.com", Instant.now()));
    CurrentUser current = new CurrentUser(owner.id, owner.normalizedEmail);
    ProfileView profile = service.onboard(current, new OnboardingRequest("Owner", "#dfff4f"));
    assertNotNull(profile.shareId());
    assertTrue(service.listBoards(current).isEmpty());

    assertEquals(List.of("crossclimb", "fermi", "krillion", "pinpoint", "queens", "tango", "wordle", "zip"), service.listGames().stream().map(GameView::slug).sorted().toList());

    var boardId = service.createBoard(current, new CreateLeaderboardRequest("My Friends", "krillion"));
    assertEquals(1, service.listBoards(current).size());
    assertEquals("krillion", service.listBoards(current).getFirst().game().slug());
    assertFalse(service.listBoards(current).getFirst().locked());
    for (int index = 2; index <= 5; index++) {
      service.createBoard(current, new CreateLeaderboardRequest("Board " + index, "fermi"));
    }
    assertEquals(5, service.listBoards(current).size());
    ApiException subscriptionRequired = assertThrows(ApiException.class,
        () -> service.createBoard(current, new CreateLeaderboardRequest("Sixth board", "fermi")));
    assertEquals(409, subscriptionRequired.status);
    assertEquals("SINGLE_GAME_LIMIT_REACHED", subscriptionRequired.code);

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

  @Test void createsAndConvertsSubscriptionMultiGameBoardsAndEnforcesSchemaInvariants() {
    UserAccount owner = users.save(new UserAccount("multi-owner@example.com", Instant.now()));
    CurrentUser current = new CurrentUser(owner.id, owner.normalizedEmail);
    service.onboard(current, new OnboardingRequest("Multi Owner", "#a8c7ff"));

    UUID freeBoard = service.createBoard(current, new CreateLeaderboardRequest("Original", "fermi"));
    Leaderboard original = boards.findById(freeBoard).orElseThrow();
    assertEquals(BoardType.SINGLE_GAME, original.boardType);
    assertEquals(BoardCreationBasis.FREE, original.creationBasis);

    BillingAccount account = new BillingAccount(owner.id, Instant.now());
    account.subscriptionStatus = "active";
    account.currentPeriodEnd = Instant.now().plusSeconds(86_400);
    billingAccounts.save(account);

    service.convertToMultiGame(current, freeBoard);
    Leaderboard converted = boards.findById(freeBoard).orElseThrow();
    assertEquals(BoardType.MULTI_GAME, converted.boardType);
    assertNull(converted.gameId);
    assertEquals(BoardCreationBasis.SUBSCRIPTION, converted.creationBasis);
    assertEquals(0, boards.countByOwnerIdAndCreationBasis(owner.id, BoardCreationBasis.FREE));

    ApiException limit = assertThrows(ApiException.class, () -> service.createBoard(current,
        new CreateLeaderboardRequest("Every Game", null, BoardType.MULTI_GAME)));
    assertEquals("ALL_GAME_LIMIT_REACHED", limit.code);
    account.stripePriceId = "price_premium";
    billingAccounts.save(account);
    UUID multiId = service.createBoard(current,
        new CreateLeaderboardRequest("Every Game", null, BoardType.MULTI_GAME));
    BoardSummary summary = service.listBoards(current).stream().filter(value -> value.id().equals(multiId)).findFirst().orElseThrow();
    assertEquals(BoardType.MULTI_GAME, summary.boardType());
    assertEquals("crossclimb", summary.game().slug());
    assertEquals(List.of("crossclimb", "fermi", "krillion", "pinpoint", "queens", "tango", "wordle", "zip"), summary.games().stream().map(GameView::slug).toList());
    assertEquals("krillion", service.board(current, multiId, "all", "krillion").game().slug());

    UUID gameId = UUID.fromString("f0000000-0000-4000-8000-000000000001");
    assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
        "insert into leaderboards(id, owner_id, name, game_id, creation_basis, board_type, created_at) values (?, ?, ?, null, 'FREE', 'SINGLE_GAME', now())",
        UUID.randomUUID(), owner.id, "Bad Single"));
    assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
        "insert into leaderboards(id, owner_id, name, game_id, creation_basis, board_type, created_at) values (?, ?, ?, ?, 'SUBSCRIPTION', 'MULTI_GAME', now())",
        UUID.randomUUID(), owner.id, "Bad Multi Game", gameId));
    assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
        "insert into leaderboards(id, owner_id, name, game_id, creation_basis, board_type, created_at) values (?, ?, ?, null, 'FREE', 'MULTI_GAME', now())",
        UUID.randomUUID(), owner.id, "Bad Multi Basis"));
  }

  private ConfirmedSubmission confirmed(ParsedSubmission parsed) {
    return new ConfirmedSubmission(parsed.puzzleKey(), parsed.puzzleNumber(), parsed.playedOn(), parsed.primaryScore(),
        parsed.metrics(), parsed.parserVersion());
  }
}
