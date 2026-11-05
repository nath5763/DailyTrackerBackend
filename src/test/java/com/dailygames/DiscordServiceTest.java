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
  private DiscordSignupService signup;
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
    signup = mock(DiscordSignupService.class);
    adapters = mock(GameAdapterRegistry.class);
    DiscordSignatureVerifier configured = new DiscordSignatureVerifier("123", "00".repeat(32), clock);
    service = new DiscordService(profiles, links, codes, boards, channels, interactions, billing, dailyGames, signup,
        adapters, configured, clock, "test-pepper-that-is-never-logged", "https://dailytracker.cc/");
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
    when(billing.allGameLimit(userId, now)).thenReturn(1);

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

  @Test void freeOwnerCannotSetUpOrUseAnExistingConnectedChannel() throws Exception {
    UUID ownerId = UUID.randomUUID(), boardId = UUID.randomUUID();
    Leaderboard board = new Leaderboard(ownerId, "Friends", UUID.randomUUID(), BoardCreationBasis.FREE, now);
    when(interactions.insertIfAbsent(anyString(), anyString(), eq(2), anyString(), eq(now))).thenReturn(1);
    when(links.findById("222222222222222222"))
        .thenReturn(Optional.of(new DiscordAccountLink("222222222222222222", ownerId, now)));
    when(boards.findById(boardId)).thenReturn(Optional.of(board));
    when(channels.findById(any())).thenReturn(Optional.of(new DiscordChannelLeaderboard(
        "444444444444444444", "333333333333333333", boardId, ownerId, "222222222222222222", now)));

    assertEquals("OWNER_SUBSCRIPTION_REQUIRED", service.handle(json.readTree(
        commandWithPermissions("setup", "32", "leaderboard", boardId.toString()))).outcome());
    assertEquals("OWNER_SUBSCRIPTION_REQUIRED", service.handle(json.readTree(
        commandWithPermissions("daily", "0", "result", "Fermi #92"))).outcome());
    assertEquals("OWNER_SUBSCRIPTION_REQUIRED", service.handle(json.readTree(
        commandWithPermissions("leaderboard", "0", "game", "fermi"))).outcome());
    verify(channels, never()).save(any());
    verifyNoInteractions(dailyGames);
  }

  @Test void freeMemberCanUsePaidOwnersConnectedChannel() throws Exception {
    UUID ownerId = UUID.randomUUID(), memberId = UUID.randomUUID(), boardId = UUID.randomUUID();
    Leaderboard board = new Leaderboard(ownerId, "Friends", UUID.randomUUID(), BoardCreationBasis.FREE, now);
    when(interactions.insertIfAbsent(anyString(), anyString(), eq(2), eq("daily"), eq(now))).thenReturn(1);
    when(links.findById("222222222222222222"))
        .thenReturn(Optional.of(new DiscordAccountLink("222222222222222222", memberId, now)));
    when(channels.findById(any())).thenReturn(Optional.of(new DiscordChannelLeaderboard(
        "444444444444444444", "333333333333333333", boardId, ownerId, "333333333333333333", now)));
    when(boards.findById(boardId)).thenReturn(Optional.of(board));
    when(billing.allGameLimit(ownerId, now)).thenReturn(1);
    Game game = new Game(); game.id = UUID.randomUUID(); game.slug = "fermi"; game.name = "Fermi.gg";
    ParsedSubmission parsed = new ParsedSubmission("92", 92, LocalDate.of(2026, 10, 26),
        new BigDecimal("1.31"), Map.of(), 1);
    when(dailyGames.submitFromDiscord(memberId, boardId, "Fermi #92"))
        .thenReturn(new DailyGamesService.DiscordSubmissionResult(UUID.randomUUID(), game, parsed));
    GameAdapter adapter = mock(GameAdapter.class);
    when(adapter.scoreFormat()).thenReturn(new ApiModels.ScoreFormatView("", "", 2, 2));
    when(adapters.require("fermi")).thenReturn(adapter);

    assertEquals("SUCCESS", service.handle(json.readTree(
        commandWithPermissions("daily", "0", "result", "Fermi #92"))).outcome());
    verify(billing).allGameLimit(ownerId, now);
    verify(billing, never()).allGameLimit(eq(memberId), any());
  }

  @Test void dailyLooksUpOptionByNameAndSuppressesMentions() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID boardId = UUID.randomUUID();
    when(interactions.insertIfAbsent(anyString(), anyString(), eq(2), eq("daily"), eq(now))).thenReturn(1);
    when(links.findById("222222222222222222")).thenReturn(Optional.of(new DiscordAccountLink("222222222222222222", userId, now)));
    when(channels.findById(any())).thenReturn(Optional.of(new DiscordChannelLeaderboard(
        "444444444444444444", "333333333333333333", boardId, userId, "222222222222222222", now)));
    Leaderboard board = new Leaderboard(userId, "Friends", UUID.randomUUID(), BoardCreationBasis.FREE, now);
    when(boards.findById(boardId)).thenReturn(Optional.of(board));
    when(billing.allGameLimit(userId, now)).thenReturn(1);
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
    Leaderboard board = new Leaderboard(userId, "Friends", UUID.randomUUID(), BoardCreationBasis.FREE, now);
    when(boards.findById(boardId)).thenReturn(Optional.of(board));
    when(billing.allGameLimit(userId, now)).thenReturn(1);
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

  @Test void statusProvidesGuildInstallUrlWithoutRequiringLink() {
    UUID userId = UUID.randomUUID();
    DiscordStatus status = service.status(userId);
    assertTrue(status.configured());
    assertFalse(status.linked());
    assertEquals("https://discord.com/oauth2/authorize?client_id=123&scope=applications.commands&integration_type=0",
        status.installUrl());
    when(links.findByUserId(userId)).thenReturn(Optional.of(new DiscordAccountLink("222222222222222222", userId, now)));
    assertEquals(status.installUrl(), service.status(userId).installUrl());

    DiscordSignatureVerifier unconfigured = new DiscordSignatureVerifier("", "", clock);
    DiscordService disabled = new DiscordService(profiles, links, codes, boards, channels, interactions, billing,
        dailyGames, signup, adapters, unconfigured, clock, "pepper", "https://dailytracker.cc");
    assertNull(disabled.status(userId).installUrl());
    assertFalse(disabled.status(userId).configured());
  }

  @Test void helpIsPrivateAndWorksWithoutLinkOrConnectedChannel() throws Exception {
    when(interactions.insertIfAbsent(anyString(), anyString(), eq(2), eq("help"), eq(now))).thenReturn(1);
    DiscordInteractionResult result = service.handle(json.readTree(commandWithPermissions("help", "0", null, null)));
    assertPrivateOutcome(result, "SUCCESS");
    assertEquals("help", result.command());
    @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) result.body().get("data");
    String content = (String) data.get("content");
    assertTrue(content.contains("https://dailytracker.cc/dashboard"));
    assertTrue(content.contains("/link"));
    assertTrue(content.contains("/daily"));
    assertTrue(content.contains("/leaderboard"));
    verifyNoInteractions(links, channels, boards, billing, dailyGames);
  }

  @Test void signupIsPrivateAndUsesCurrentChannelWithoutLink() throws Exception {
    when(interactions.insertIfAbsent(anyString(), anyString(), eq(2), eq("signup"), eq(now))).thenReturn(1);
    when(signup.issue("222222222222222222", "444444444444444444", "333333333333333333"))
        .thenReturn("https://dailytracker.cc/discord/signup?intent=token");
    DiscordInteractionResult result = service.handle(json.readTree(commandWithPermissions("signup", "0", null, null)));
    assertPrivateOutcome(result, "SUCCESS");
    verify(signup).issue("222222222222222222", "444444444444444444", "333333333333333333");
  }

  @Test void myIdAndAddMemberUseLinkedProfileAndChannelBoard() throws Exception {
    UUID userId = UUID.randomUUID(), boardId = UUID.randomUUID(), shareId = UUID.randomUUID();
    when(interactions.insertIfAbsent(anyString(), anyString(), eq(2), anyString(), eq(now))).thenReturn(1);
    when(links.findById("222222222222222222"))
        .thenReturn(Optional.of(new DiscordAccountLink("222222222222222222", userId, now)));
    Profile profile = new Profile(userId, "Player", "#dfff4f", now);
    when(profiles.findById(userId)).thenReturn(Optional.of(profile));
    assertPrivateOutcome(service.handle(json.readTree(commandWithPermissions("my-id", "0", null, null))), "SUCCESS");
    when(channels.findById(any())).thenReturn(Optional.of(new DiscordChannelLeaderboard(
        "444444444444444444", "333333333333333333", boardId, userId, "222222222222222222", now)));
    assertPrivateOutcome(service.handle(json.readTree(commandWithPermissions("add-member", "0", "uuid", shareId.toString()))), "SUCCESS");
    verify(dailyGames).addMember(argThat(current -> current.id().equals(userId)), eq(boardId), eq(shareId));
  }

  @Test void autocompleteLimitsResultsAndDoesNotRequireLink() throws Exception {
    UUID boardId = UUID.randomUUID();
    when(channels.findById(any())).thenReturn(Optional.of(new DiscordChannelLeaderboard(
        "444444444444444444", "333333333333333333", boardId, UUID.randomUUID(), "222222222222222222", now)));
    List<ApiModels.GameView> games = new ArrayList<>();
    for (int i = 0; i < 25; i++) games.add(game("game" + i, "Game " + i));
    when(dailyGames.searchDiscordGames(boardId, "ga")).thenReturn(games);
    String payload = commandWithPermissions("daily", "0", "game", "ga")
        .replace("\"type\":2", "\"type\":4")
        .replace("\"name\":\"game\",\"value\"", "\"name\":\"game\",\"focused\":true,\"value\"");
    DiscordInteractionResult result = service.handle(json.readTree(payload));
    assertEquals(8, result.body().get("type"));
    @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) result.body().get("data");
    assertEquals(25, ((List<?>) data.get("choices")).size());
    verifyNoInteractions(links);
  }

  @Test void leaderboardDefaultsToTodayAndPostsTenFormattedRanksWithoutMentions() throws Exception {
    UUID userId = UUID.randomUUID(), boardId = UUID.randomUUID();
    setupLeaderboardMocks(userId, boardId);
    ApiModels.GameView game = game("fermi", "Fermi.gg");
    List<ApiModels.RankingView> ranks = new ArrayList<>();
    for (int index = 1; index <= 11; index++) {
      ranks.add(new ApiModels.RankingView(index, UUID.randomUUID(), UUID.randomUUID(),
          index == 1 ? "@everyone *winner*" : "Player " + index,
          index == 1 ? new BigDecimal("1.3150") : new BigDecimal("1.3100"), 2));
    }
    when(dailyGames.board(any(), eq(boardId), eq("today"), eq("fermi")))
        .thenReturn(board(boardId, "Friday [friends]", game, "today", ranks));

    DiscordInteractionResult result = service.handle(json.readTree(commandWithPermissions("leaderboard", "0", "game", "fermi")));

    assertEquals("SUCCESS", result.outcome());
    @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) result.body().get("data");
    String content = (String) data.get("content");
    assertTrue(content.contains("Friday \\[friends\\]"));
    assertTrue(content.contains("Fermi.gg · Today"));
    assertTrue(content.contains("1.31× (2 scores)"));
    assertTrue(content.contains("1.32× (2 scores)"));
    assertTrue(content.contains("10. Player 10"));
    assertFalse(content.contains("11. Player 11"));
    assertTrue(content.contains("https://dailytracker.cc/leaderboards/" + boardId + "?game=fermi&range=today"));
    assertTrue(content.length() <= 2_000);
    assertFalse(data.containsKey("flags"));
    assertEquals(Map.of("parse", List.of()), data.get("allowed_mentions"));
  }

  @Test void leaderboardSupportsEveryRangeAndEmptyMultiGameResults() throws Exception {
    UUID userId = UUID.randomUUID(), boardId = UUID.randomUUID();
    setupLeaderboardMocks(userId, boardId);
    for (String range : List.of("7d", "30d", "all")) {
      when(dailyGames.board(any(), eq(boardId), eq(range), eq("krillion")))
          .thenReturn(board(boardId, "Mixed board", game("krillion", "Krillion"), range, List.of(),
              BoardType.MULTI_GAME));
      DiscordInteractionResult result = service.handle(json.readTree(command("leaderboard", "222222222222222222",
          "111111111111111111", "333333333333333333", "range", range, "game", "krillion")));
      assertEquals("SUCCESS", result.outcome());
      @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) result.body().get("data");
      assertTrue(((String) data.get("content")).contains("Nobody has posted a score for this range yet."));
      assertTrue(((String) data.get("content")).contains("&range=" + range));
    }
    verify(dailyGames, times(3)).board(argThat(user -> user.id().equals(userId)), eq(boardId), anyString(), eq("krillion"));
  }

  @Test void leaderboardFailuresArePrivateAndNeverPublishRankings() throws Exception {
    UUID userId = UUID.randomUUID(), boardId = UUID.randomUUID();
    when(interactions.insertIfAbsent(anyString(), anyString(), eq(2), eq("leaderboard"), eq(now))).thenReturn(1);
    String payload = commandWithPermissions("leaderboard", "0", "game", "fermi");
    assertPrivateOutcome(service.handle(json.readTree(payload)), "NOT_LINKED");
    when(links.findById("222222222222222222"))
        .thenReturn(Optional.of(new DiscordAccountLink("222222222222222222", userId, now)));
    assertPrivateOutcome(service.handle(json.readTree(payload)), "CHANNEL_NOT_CONFIGURED");
    when(channels.findById(any())).thenReturn(Optional.of(new DiscordChannelLeaderboard(
        "444444444444444444", "333333333333333333", boardId, userId, "222222222222222222", now)));
    Leaderboard board = new Leaderboard(userId, "Friends", UUID.randomUUID(), BoardCreationBasis.FREE, now);
    when(boards.findById(boardId)).thenReturn(Optional.of(board));
    when(billing.allGameLimit(userId, now)).thenReturn(1);
    when(dailyGames.board(any(), eq(boardId), eq("today"), eq("fermi")))
        .thenThrow(new ApiException(404, "NOT_FOUND", "Resource not found."),
            new ApiException(402, "OWNER_SUBSCRIPTION_REQUIRED", "Locked"),
            new ApiException(400, "GAME_NOT_ALLOWED", "That game is not available on this leaderboard."));
    assertPrivateOutcome(service.handle(json.readTree(payload)), "NOT_FOUND");
    assertPrivateOutcome(service.handle(json.readTree(payload)), "OWNER_SUBSCRIPTION_REQUIRED");
    assertPrivateOutcome(service.handle(json.readTree(payload)), "GAME_NOT_ALLOWED");
    when(dailyGames.board(any(), eq(boardId), eq("today"), eq("unknown")))
        .thenThrow(new ApiException(400, "GAME_NOT_ALLOWED", "That game is not available on this leaderboard."));
    assertPrivateOutcome(service.handle(json.readTree(commandWithPermissions("leaderboard", "0", "game", "unknown"))),
        "GAME_NOT_ALLOWED");
    assertPrivateOutcome(service.handle(json.readTree(command("leaderboard", "222222222222222222",
        "111111111111111111", "333333333333333333", "game", "fermi", "range", "bad"))), "INVALID_RANGE");
  }

  private void setupLeaderboardMocks(UUID userId, UUID boardId) {
    when(interactions.insertIfAbsent(anyString(), anyString(), eq(2), eq("leaderboard"), eq(now))).thenReturn(1);
    when(links.findById("222222222222222222"))
        .thenReturn(Optional.of(new DiscordAccountLink("222222222222222222", userId, now)));
    when(channels.findById(any())).thenReturn(Optional.of(new DiscordChannelLeaderboard(
        "444444444444444444", "333333333333333333", boardId, userId, "222222222222222222", now)));
    Leaderboard board = new Leaderboard(userId, "Friends", UUID.randomUUID(), BoardCreationBasis.FREE, now);
    when(boards.findById(boardId)).thenReturn(Optional.of(board));
    when(billing.allGameLimit(userId, now)).thenReturn(1);
  }

  private ApiModels.GameView game(String slug, String name) {
    return new ApiModels.GameView(UUID.randomUUID(), slug, name, "", "higher",
        new ApiModels.ScoreFormatView("", "×", 2, 2), null);
  }

  private ApiModels.LeaderboardResponse board(UUID id, String name, ApiModels.GameView game, String range,
                                                List<ApiModels.RankingView> rankings) {
    return board(id, name, game, range, rankings, BoardType.SINGLE_GAME);
  }

  private ApiModels.LeaderboardResponse board(UUID id, String name, ApiModels.GameView game, String range,
                                                List<ApiModels.RankingView> rankings, BoardType type) {
    return new ApiModels.LeaderboardResponse(id, name, UUID.randomUUID(), game, type,
        List.of(game), range, false, List.of(), rankings, List.of());
  }

  private void assertPrivateOutcome(DiscordInteractionResult result, String outcome) {
    assertEquals(outcome, result.outcome());
    @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) result.body().get("data");
    assertEquals(64, data.get("flags"));
    assertEquals(Map.of("parse", List.of()), data.get("allowed_mentions"));
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
