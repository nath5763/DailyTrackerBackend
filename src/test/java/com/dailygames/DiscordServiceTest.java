package com.dailygames;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DiscordServiceTest {
  private final Instant now = Instant.parse("2026-11-03T21:20:00Z");
  private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
  private ProfileRepository profiles;
  private DiscordAccountLinkRepository links;
  private DiscordLinkCodeRepository codes;
  private LeaderboardRepository boards;
  private DiscordChannelLeaderboardRepository channels;
  private ProcessedDiscordInteractionRepository interactions;
  private BillingService billing;
  private DailyGamesService dailyGames;
  private GameAdapterRegistry adapters;
  private DiscordService service;
  private ObjectMapper json;

  @BeforeEach void setUp() {
    profiles = mock(ProfileRepository.class);
    links = mock(DiscordAccountLinkRepository.class);
    codes = mock(DiscordLinkCodeRepository.class);
    boards = mock(LeaderboardRepository.class);
    channels = mock(DiscordChannelLeaderboardRepository.class);
    interactions = mock(ProcessedDiscordInteractionRepository.class);
    billing = mock(BillingService.class);
    dailyGames = mock(DailyGamesService.class);
    adapters = mock(GameAdapterRegistry.class);
    DiscordSignatureVerifier configured = new DiscordSignatureVerifier("123", "00".repeat(32), clock);
    service = new DiscordService(profiles, links, codes, boards, channels, interactions, billing, dailyGames,
        adapters, configured, clock, "test-pepper-that-is-never-logged");
    json = new ObjectMapper();
  }

  @Test void issuesFormattedCodeStoresOnlyHashAndInvalidatesPriorCodes() {
    UUID userId = UUID.randomUUID();
    when(profiles.findByIdForUpdate(userId)).thenReturn(Optional.of(new Profile(userId, "Player", "#dfff4f", now)));
    DiscordLinkCodeResponse response = service.issueCode(userId);

    assertTrue(response.code().matches("[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}"));
    assertEquals(now.plus(Duration.ofMinutes(10)), response.expiresAt());
    ArgumentCaptor<DiscordLinkCode> stored = ArgumentCaptor.forClass(DiscordLinkCode.class);
    verify(codes).consumeOutstanding(userId, now);
    verify(codes).save(stored.capture());
    assertEquals(64, stored.getValue().codeHash.length());
    assertFalse(stored.getValue().codeHash.contains(response.code().replace("-", "")));
  }

  @Test void enforcesBothIssuanceWindows() {
    UUID userId = UUID.randomUUID();
    when(profiles.findByIdForUpdate(userId)).thenReturn(Optional.of(new Profile(userId, "Player", "#dfff4f", now)));
    when(codes.countByUserIdAndCreatedAtAfter(userId, now.minus(Duration.ofMinutes(1)))).thenReturn(1L);
    ApiException exception = assertThrows(ApiException.class, () -> service.issueCode(userId));
    assertEquals(429, exception.status);
    assertEquals("DISCORD_LINK_CODE_RATE_LIMITED", exception.code);
    verify(codes, never()).save(any());
  }

  @Test void linkConsumesCodeAndCreatesOneToOneMapping() throws Exception {
    UUID userId = UUID.randomUUID();
    when(profiles.findByIdForUpdate(userId)).thenReturn(Optional.of(new Profile(userId, "Player", "#dfff4f", now)));
    DiscordLinkCodeResponse issued = service.issueCode(userId);
    ArgumentCaptor<DiscordLinkCode> stored = ArgumentCaptor.forClass(DiscordLinkCode.class);
    verify(codes).save(stored.capture());
    DiscordLinkCode code = stored.getValue();
    reset(codes);
    when(interactions.insertIfAbsent(anyString(), anyString(), eq(2), eq("link"), eq(now))).thenReturn(1);
    when(interactions.countByDiscordUserIdAndCommandNameAndProcessedAtAfter(anyString(), eq("link"), any())).thenReturn(1L);
    when(codes.findByCodeHashAndConsumedAtIsNullAndExpiresAtAfter(eq(code.codeHash), eq(now))).thenReturn(Optional.of(code));
    when(links.findById("222222222222222222")).thenReturn(Optional.empty());
    when(links.findByUserId(userId)).thenReturn(Optional.empty());

    DiscordInteractionResult result = service.handle(json.readTree(command("link", "222222222222222222",
        "111111111111111111", "333333333333333333", "other", "ignored", "code", issued.code().toLowerCase(Locale.ROOT))));

    assertEquals("SUCCESS", result.outcome());
    assertEquals(now, code.consumedAt);
    verify(links).save(argThat(link -> link.userId.equals(userId) && link.discordUserId.equals("222222222222222222")));
    verify(interactions).updateOutcome("111111111111111111", "SUCCESS");
  }

  @Test void setupRequiresBothDiscordPermissionAndWebsiteOwnership() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID boardId = UUID.randomUUID();
    Leaderboard board = new Leaderboard();
    board.id = boardId; board.ownerId = userId;
    when(interactions.insertIfAbsent(anyString(), anyString(), eq(2), eq("setup"), eq(now))).thenReturn(1);
    when(links.findById("222222222222222222")).thenReturn(Optional.of(new DiscordAccountLink("222222222222222222", userId, now)));
    when(boards.findById(boardId)).thenReturn(Optional.of(board));
    when(billing.isBoardAccessible(board, now)).thenReturn(true);

    DiscordInteractionResult result = service.handle(json.readTree(commandWithPermissions("setup", "32", "leaderboard", boardId.toString())));
    assertEquals("SUCCESS", result.outcome());
    verify(channels).save(argThat(mapping -> mapping.id.guildId.equals("444444444444444444")
        && mapping.id.channelId.equals("333333333333333333") && mapping.leaderboardId.equals(boardId)));

    reset(interactions, channels);
    when(interactions.insertIfAbsent(anyString(), anyString(), eq(2), eq("setup"), eq(now))).thenReturn(1);
    DiscordInteractionResult denied = service.handle(json.readTree(commandWithPermissions("setup", "0", "leaderboard", boardId.toString())));
    assertEquals("MANAGE_GUILD_REQUIRED", denied.outcome());
    verifyNoInteractions(channels);
  }

  @Test void dailyLooksUpOptionByNameAndSuppressesMentions() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID boardId = UUID.randomUUID();
    when(interactions.insertIfAbsent(anyString(), anyString(), eq(2), eq("daily"), eq(now))).thenReturn(1);
    when(links.findById("222222222222222222")).thenReturn(Optional.of(new DiscordAccountLink("222222222222222222", userId, now)));
    when(channels.findById(any())).thenReturn(Optional.of(new DiscordChannelLeaderboard(
        "444444444444444444", "333333333333333333", boardId, userId, "222222222222222222", now)));
    Game game = new Game(); game.id = UUID.randomUUID(); game.slug = "fermi"; game.name = "Fermi.gg";
    ParsedSubmission parsed = new ParsedSubmission("92", 92, LocalDate.of(2026, 10, 26), new BigDecimal("1.31"), Map.of(), 1);
    when(dailyGames.submitFromDiscord(userId, boardId, "Fermi #92\n1.31× score")).thenReturn(
        new DailyGamesService.DiscordSubmissionResult(UUID.randomUUID(), game, parsed));
    GameAdapter adapter = mock(GameAdapter.class);
    when(adapter.scoreFormat()).thenReturn(new ApiModels.ScoreFormatView("", "×", 2, 2));
    when(adapters.require("fermi")).thenReturn(adapter);

    DiscordInteractionResult result = service.handle(json.readTree(command("daily", "222222222222222222",
        "111111111111111111", "333333333333333333", "other", "unused", "result", "Fermi #92\n1.31× score")));
    assertEquals("SUCCESS", result.outcome());
    @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) result.body().get("data");
    assertEquals("Saved Fermi.gg #92 at 1.31×.", data.get("content"));
    assertEquals(Map.of("parse", List.of()), data.get("allowed_mentions"));
  }

  @Test void dailyPassesTheNamedGameChoiceEvenWhenOptionsAreReordered() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID boardId = UUID.randomUUID();
    when(interactions.insertIfAbsent(anyString(), anyString(), eq(2), eq("daily"), eq(now))).thenReturn(1);
    when(links.findById("222222222222222222")).thenReturn(Optional.of(
        new DiscordAccountLink("222222222222222222", userId, now)));
    when(channels.findById(any())).thenReturn(Optional.of(new DiscordChannelLeaderboard(
        "444444444444444444", "333333333333333333", boardId, userId, "222222222222222222", now)));
    Game game = new Game(); game.id = UUID.randomUUID(); game.slug = "krillion"; game.name = "Krillion";
    ParsedSubmission parsed = new ParsedSubmission("82", 82, LocalDate.of(2026, 11, 4),
        new BigDecimal("700"), Map.of(), 1);
    when(dailyGames.submitFromDiscord(userId, boardId, "krillion", "Krillion share")).thenReturn(
        new DailyGamesService.DiscordSubmissionResult(UUID.randomUUID(), game, parsed));
    GameAdapter adapter = mock(GameAdapter.class);
    when(adapter.scoreFormat()).thenReturn(new ApiModels.ScoreFormatView("", "", 0, 0));
    when(adapters.require("krillion")).thenReturn(adapter);

    DiscordInteractionResult result = service.handle(json.readTree(command("daily", "222222222222222222",
        "111111111111111111", "333333333333333333", "result", "Krillion share", "game", "krillion")));

    assertEquals("SUCCESS", result.outcome());
    verify(dailyGames).submitFromDiscord(userId, boardId, "krillion", "Krillion share");
  }

  @Test void duplicateInteractionDoesNotRepeatMutation() throws Exception {
    when(interactions.insertIfAbsent(anyString(), anyString(), eq(2), eq("unlink"), eq(now))).thenReturn(0);
    DiscordInteractionResult result = service.handle(json.readTree(commandWithPermissions("unlink", "0", null, null)));
    assertEquals("DUPLICATE_INTERACTION", result.outcome());
    verifyNoInteractions(links);
  }

  private String commandWithPermissions(String name, String permissions, String optionName, String optionValue) {
    String options = optionName == null ? "[]" : "[{\"type\":3,\"name\":\"" + optionName + "\",\"value\":\"" + optionValue + "\"}]";
    return "{\"type\":2,\"id\":\"111111111111111111\",\"guild_id\":\"444444444444444444\","
        + "\"channel_id\":\"333333333333333333\",\"member\":{\"permissions\":\"" + permissions
        + "\",\"user\":{\"id\":\"222222222222222222\"}},\"data\":{\"name\":\"" + name + "\",\"options\":" + options + "}}";
  }

  private String command(String name, String userId, String interactionId, String channelId,
                         String firstName, String firstValue, String secondName, String secondValue) {
    return "{\"type\":2,\"id\":\"" + interactionId + "\",\"guild_id\":\"444444444444444444\","
        + "\"channel_id\":\"" + channelId + "\",\"member\":{\"permissions\":\"0\",\"user\":{\"id\":\""
        + userId + "\"}},\"data\":{\"name\":\"" + name + "\",\"options\":[{\"type\":3,\"name\":\""
        + firstName + "\",\"value\":\"" + escape(firstValue) + "\"},{\"type\":3,\"name\":\"" + secondName
        + "\",\"value\":\"" + escape(secondValue) + "\"}]}}";
  }

  private String escape(String value) { return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\""); }
}
