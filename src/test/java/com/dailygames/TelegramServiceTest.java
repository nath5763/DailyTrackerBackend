package com.dailygames;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TelegramServiceTest {
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final TelegramGateway bot = mock(TelegramGateway.class);
  private final TelegramService service = new TelegramService(jdbc, bot, mock(ProfileRepository.class),
      mock(LeaderboardRepository.class), mock(MembershipRepository.class), mock(BillingService.class),
      mock(DailyGamesService.class), mock(GameAdapterRegistry.class),
      Clock.fixed(Instant.parse("2026-11-12T12:00:00Z"), ZoneOffset.UTC), "pepper", "https://dailytracker.cc");

  @Test void duplicateUpdateHasNoSideEffects() throws Exception {
    when(jdbc.update(startsWith("insert into processed_telegram_updates"), anyLong(), any(Timestamp.class))).thenReturn(0);
    service.handle(new ObjectMapper().readTree("{\"update_id\":5,\"message\":{\"chat\":{\"id\":1,\"type\":\"private\"},\"from\":{\"id\":1},\"text\":\"/help\"}}"));
    verifyNoInteractions(bot);
  }

  @Test void malformedIdentityIsRejected() throws Exception {
    ApiException error = assertThrows(ApiException.class,
        () -> service.handle(new ObjectMapper().readTree("{\"message\":{}}")));
    assertEquals("INVALID_UPDATE", error.code);
    verifyNoInteractions(jdbc, bot);
  }

  @Test void dailyShareInGroupInfersGameAndConfirmsInGroup() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID boardId = UUID.randomUUID();
    String share = "Wordle 1,972 2/6\n⬛⬛⬛⬛⬛\n🟩🟩🟩🟩🟩";
    GameAdapterRegistry registry = new GameAdapterRegistry(List.of(new WordleParser()));
    DailyGamesService games = mock(DailyGamesService.class);
    TelegramService groupService = groupService(userId, boardId, games, registry);
    Game game = new Game();
    game.slug = "wordle";
    game.name = "Wordle";
    var parsed = registry.parseResult("wordle", share);
    when(games.submitFromDiscord(userId, boardId, "wordle", share)).thenReturn(
        new DailyGamesService.DiscordSubmissionResult(UUID.randomUUID(), game, parsed));
    when(jdbc.update(startsWith("insert into processed_telegram_updates"), anyLong(), any(Timestamp.class))).thenReturn(1);
    var json = new ObjectMapper();
    String update = "{\"update_id\":7,\"message\":{\"chat\":{\"id\":-1001,\"type\":\"supergroup\"}," +
        "\"from\":{\"id\":7},\"text\":" + json.writeValueAsString("/daily " + share) + "}}";

    groupService.handle(json.readTree(update));

    verify(games).submitFromDiscord(userId, boardId, "wordle", share);
    verify(bot).send(eq(-1001L), startsWith("Saved Wordle #1972 at 2 guesses."));
    verify(bot, never()).send(eq(-1001L), contains("private chat"), anyList());
  }

  @Test void leaderboardSearchInGroupShowsMatchingGameAndRangeFlow() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID boardId = UUID.randomUUID();
    DailyGamesService games = mock(DailyGamesService.class);
    TelegramService groupService = groupService(userId, boardId, games,
        new GameAdapterRegistry(List.of(new WordleParser())));
    var match = new ApiModels.GameView(UUID.randomUUID(), "wordle", "Wordle", "", "lower",
        new ApiModels.ScoreFormatView("", "", 0, 0), null);
    when(games.searchDiscordGames(userId, boardId, "word", false)).thenReturn(List.of(match));
    when(games.board(any(), eq(boardId), eq("today"), eq("wordle"))).thenReturn(
        new ApiModels.LeaderboardResponse(boardId, "Friends", userId, match, BoardType.MULTI_GAME,
            List.of(match), "today", false, List.of(), List.of(), List.of()));
    when(jdbc.update(startsWith("insert into processed_telegram_updates"), anyLong(), any(Timestamp.class))).thenReturn(1);
    groupService.handle(new ObjectMapper().readTree(
        "{\"update_id\":9,\"message\":{\"chat\":{\"id\":-1001,\"type\":\"supergroup\"},"
            + "\"from\":{\"id\":7},\"text\":\"/leaderboard word\"}}"));

    verify(games).searchDiscordGames(userId, boardId, "word", false);
    verify(bot).send(eq(-1001L), startsWith("Friends"));
    verify(games).board(any(), eq(boardId), eq("today"), eq("wordle"));
  }

  @Test void unrecognizedGroupShareReturnsSelectionInstructions() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID boardId = UUID.randomUUID();
    DailyGamesService games = mock(DailyGamesService.class);
    TelegramService groupService = groupService(userId, boardId, games,
        new GameAdapterRegistry(List.of(new WordleParser())));
    when(jdbc.update(startsWith("insert into processed_telegram_updates"), anyLong(), any(Timestamp.class))).thenReturn(1);
    groupService.handle(new ObjectMapper().readTree("{\"update_id\":8,\"message\":{\"chat\":{\"id\":-1001,\"type\":\"supergroup\"}," +
        "\"from\":{\"id\":7},\"text\":\"/daily unknown result\"}}"));

    verify(bot).send(eq(-1001L), contains("Could not identify one supported game"));
    verifyNoInteractions(games);
  }

  private TelegramService groupService(UUID userId, UUID boardId, DailyGamesService games,
      GameAdapterRegistry registry) {
    LeaderboardRepository boards = mock(LeaderboardRepository.class);
    BillingService billing = mock(BillingService.class);
    Leaderboard board = new Leaderboard(userId, "Friends", null, BoardType.MULTI_GAME,
        BoardCreationBasis.SUBSCRIPTION, Instant.parse("2026-11-12T12:00:00Z"));
    board.id = boardId;
    when(boards.findById(boardId)).thenReturn(Optional.of(board));
    when(billing.allGameLimit(eq(userId), any(Instant.class))).thenReturn(20);
    when(billing.isBoardAccessible(eq(board), any(Instant.class))).thenReturn(true);
    doReturn(List.of(userId)).when(jdbc).query(eq("select user_id from telegram_account_links where telegram_user_id=?"),
        any(RowMapper.class), eq(7L));
    doReturn(List.of(boardId)).when(jdbc).query(eq("select leaderboard_id from telegram_group_leaderboards where chat_id=?"),
        any(RowMapper.class), eq(-1001L));
    return new TelegramService(jdbc, bot, mock(ProfileRepository.class), boards,
        mock(MembershipRepository.class), billing, games, registry,
        Clock.fixed(Instant.parse("2026-11-12T12:00:00Z"), ZoneOffset.UTC), "pepper", "https://dailytracker.cc");
  }

  @Test void ordinaryGroupTextIsIgnored() throws Exception {
    when(jdbc.update(startsWith("insert into processed_telegram_updates"), anyLong(), any(Timestamp.class))).thenReturn(1);
    service.handle(new ObjectMapper().readTree("{\"update_id\":6,\"message\":{\"chat\":{\"id\":-1001,\"type\":\"supergroup\"},\"from\":{\"id\":7},\"text\":\"ordinary chat\"}}"));
    verifyNoInteractions(bot);
  }

  @Test void todayCardExpandsPrivatelyAndChecksAccessAgain() throws Exception {
    UUID userId = UUID.randomUUID(), boardId = UUID.randomUUID();
    DailyGamesService games = mock(DailyGamesService.class);
    TelegramService groupService = groupService(userId, boardId, games,
        new GameAdapterRegistry(List.of(new WordleParser())));
    when(games.todaySummary(any(), eq(boardId))).thenReturn(
        new DailyGamesService.TodaySummary(boardId, "Friends", List.of(), List.of()));
    when(jdbc.update(startsWith("insert into processed_telegram_updates"), anyLong(), any(Timestamp.class))).thenReturn(1);
    ObjectMapper json = new ObjectMapper();
    groupService.handle(json.readTree("{\"update_id\":10,\"message\":{\"chat\":{\"id\":-1001,\"type\":\"supergroup\"},"
        + "\"from\":{\"id\":7},\"text\":\"/leaderboard\"}}"));
    verify(bot).send(eq(-1001L), contains("Nobody has posted"), argThat(buttons ->
        buttons.stream().flatMap(List::stream).anyMatch(button -> "Expand".equals(button.get("text")))));
    String callback = "{\"callback_query\":{\"id\":\"cb\",\"from\":{\"id\":7},"
        + "\"message\":{\"chat\":{\"id\":-1001,\"type\":\"supergroup\"}},"
        + "\"data\":\"lb:" + boardId + ":0\"}}";
    groupService.handle(json.readTree("{\"update_id\":11," + callback.substring(1)));
    verify(bot).send(eq(7L), contains("Game leaders"), anyList());
    when(games.todaySummary(any(), eq(boardId))).thenThrow(new ApiException(404, "NOT_FOUND", "Access revoked."));
    groupService.handle(json.readTree("{\"update_id\":12," + callback.substring(1)));
    verify(bot).answer("cb", "Access revoked.");
  }


  @Test void todayExpansionPagesTenGames() throws Exception {
    UUID userId = UUID.randomUUID(), boardId = UUID.randomUUID();
    DailyGamesService games = mock(DailyGamesService.class);
    TelegramService groupService = groupService(userId, boardId, games,
        new GameAdapterRegistry(List.of(new WordleParser())));
    var winner = new ApiModels.RankingView(1, userId, UUID.randomUUID(), "Winner",
        java.math.BigDecimal.ONE, 1);
    List<DailyGamesService.TodayGameLeaders> leaders = new java.util.ArrayList<>();
    for (int i = 0; i < 11; i++) {
      var game = new ApiModels.GameView(UUID.randomUUID(), "game-" + i, "Game " + i,
          "", "higher", new ApiModels.ScoreFormatView("", "", 0, 0), null);
      leaders.add(new DailyGamesService.TodayGameLeaders(game, List.of(winner)));
    }
    when(games.todaySummary(any(), eq(boardId))).thenReturn(
        new DailyGamesService.TodaySummary(boardId, "Friends", List.of(winner), leaders));
    when(jdbc.update(startsWith("insert into processed_telegram_updates"), anyLong(), any(Timestamp.class))).thenReturn(1);
    String payload = "{\"update_id\":13,\"callback_query\":{\"id\":\"cb\",\"from\":{\"id\":7},"
        + "\"message\":{\"chat\":{\"id\":-1001,\"type\":\"supergroup\"}},"
        + "\"data\":\"lb:" + boardId + ":1\"}}";
    groupService.handle(new ObjectMapper().readTree(payload));
    verify(bot).send(eq(7L), contains("page 2 of 2"), argThat(buttons ->
        buttons.stream().flatMap(List::stream).anyMatch(button -> "Previous".equals(button.get("text")))));
  }


  @Test void ambiguousTelegramGameSearchOffersChoices() throws Exception {
    UUID userId = UUID.randomUUID(), boardId = UUID.randomUUID();
    DailyGamesService games = mock(DailyGamesService.class);
    TelegramService groupService = groupService(userId, boardId, games,
        new GameAdapterRegistry(List.of(new WordleParser())));
    var wordle = new ApiModels.GameView(UUID.randomUUID(), "wordle", "Wordle",
        "", "lower", new ApiModels.ScoreFormatView("", "", 0, 0), null);
    var wordSearch = new ApiModels.GameView(UUID.randomUUID(), "word-search", "Word Search",
        "", "lower", new ApiModels.ScoreFormatView("", "", 0, 0), null);
    when(games.searchDiscordGames(userId, boardId, "word", false))
        .thenReturn(List.of(wordle, wordSearch));
    when(jdbc.update(startsWith("insert into processed_telegram_updates"), anyLong(), any(Timestamp.class))).thenReturn(1);
    groupService.handle(new ObjectMapper().readTree(
        "{\"update_id\":14,\"message\":{\"chat\":{\"id\":-1001,\"type\":\"supergroup\"},"
            + "\"from\":{\"id\":7},\"text\":\"/leaderboard word\"}}"));
    verify(bot).send(eq(-1001L), eq("Choose a game:"), argThat(buttons ->
        buttons.stream().flatMap(List::stream).anyMatch(button -> "Wordle".equals(button.get("text")))
            && buttons.stream().flatMap(List::stream).anyMatch(button -> "Word Search".equals(button.get("text")))));
    verify(games, never()).board(any(), any(), anyString(), anyString());
  }

}
