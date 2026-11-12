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
}
