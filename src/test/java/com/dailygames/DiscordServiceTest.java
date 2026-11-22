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
  private DiscordGroupDmLeaderboardRepository groupDms;
  private DiscordCommandRegistrationStore registrations;
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
    groupDms = mock(DiscordGroupDmLeaderboardRepository.class);
    registrations = mock(DiscordCommandRegistrationStore.class);
    interactions = mock(ProcessedDiscordInteractionRepository.class);
    billing = mock(BillingService.class);
    dailyGames = mock(DailyGamesService.class);
    signup = mock(DiscordSignupService.class);
    adapters = mock(GameAdapterRegistry.class);
    DiscordSignatureVerifier configured = new DiscordSignatureVerifier("123", "00".repeat(32), clock);
    service = new DiscordService(profiles, links, codes, boards, channels, groupDms, registrations, interactions, billing, dailyGames, signup,
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
    board.id = boardId; board.ownerId = userId; board.boardType = BoardType.MULTI_GAME;
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

  @Test void singleGameBoardsAndMissingGameAreRejected() throws Exception {
    UUID ownerId = UUID.randomUUID(), boardId = UUID.randomUUID();
    Leaderboard single = new Leaderboard(ownerId, "Single", UUID.randomUUID(), BoardCreationBasis.FREE, now);
    when(interactions.insertIfAbsent(anyString(), anyString(), eq(2), anyString(), eq(now))).thenReturn(1);
    when(links.findById("222222222222222222"))
        .thenReturn(Optional.of(new DiscordAccountLink("222222222222222222", ownerId, now)));
    when(boards.findById(boardId)).thenReturn(Optional.of(single));
    when(billing.allGameLimit(ownerId, now)).thenReturn(1);
    assertPrivateOutcome(service.handle(json.readTree(commandWithPermissions(
        "setup", "32", "leaderboard", boardId.toString()))), "MULTI_GAME_REQUIRED");
    assertPrivateOutcome(service.handle(json.readTree(groupDmCommand(
        "set-leaderboard", "leaderboard", boardId.toString()))), "MULTI_GAME_REQUIRED");
    when(channels.findById(any())).thenReturn(Optional.of(new DiscordChannelLeaderboard(
        "444444444444444444", "333333333333333333", boardId, ownerId, "222222222222222222", now)));
    assertPrivateOutcome(service.handle(json.readTree(commandWithPermissions(
        "leaderboard", "0", "game", "fermi"))), "MULTI_GAME_REQUIRED");
    verifyNoInteractions(dailyGames);
  }

  @Test void freeOwnerCannotSetUpOrUseAnExistingConnectedChannel() throws Exception {
    UUID ownerId = UUID.randomUUID(), boardId = UUID.randomUUID();
    Leaderboard board = new Leaderboard(ownerId, "Friends", null, BoardType.MULTI_GAME, BoardCreationBasis.SUBSCRIPTION, now);
    when(interactions.insertIfAbsent(anyString(), anyString(), eq(2), anyString(), eq(now))).thenReturn(1);
    when(links.findById("222222222222222222"))
        .thenReturn(Optional.of(new DiscordAccountLink("222222222222222222", ownerId, now)));
    when(boards.findById(boardId)).thenReturn(Optional.of(board));
    when(channels.findById(any())).thenReturn(Optional.of(new DiscordChannelLeaderboard(
        "444444444444444444", "333333333333333333", boardId, ownerId, "222222222222222222", now)));

    assertEquals("OWNER_SUBSCRIPTION_REQUIRED", service.handle(json.readTree(
        commandWithPermissions("setup", "32", "leaderboard", boardId.toString()))).outcome());
    Game game = new Game(); game.id = UUID.randomUUID(); game.slug = "fermi"; game.name = "Fermi.gg";
    ParsedSubmission parsed = new ParsedSubmission("92", 92, LocalDate.of(2026, 10, 26),
        new BigDecimal("1.31"), Map.of(), 1);
    when(dailyGames.submitShare(ownerId, null, "Fermi #92"))
        .thenReturn(new DailyGamesService.DiscordSubmissionResult(UUID.randomUUID(), game, parsed));
    GameAdapter adapter = mock(GameAdapter.class);
    when(adapter.scoreFormat()).thenReturn(new ApiModels.ScoreFormatView("", "", 2, 2));
    when(adapters.require("fermi")).thenReturn(adapter);
    assertPublicOutcome(service.handle(json.readTree(command("daily", "222222222222222222",
        "111111111111111111", "333333333333333333", "result", "Fermi #92"))), "SUCCESS");
    assertEquals("OWNER_SUBSCRIPTION_REQUIRED", service.handle(json.readTree(
        commandWithPermissions("leaderboard", "0", "game", "fermi"))).outcome());
    verify(channels, never()).save(any());
    verify(dailyGames).submitShare(ownerId, null, "Fermi #92");
  }

  @Test void freeMemberCanUsePaidOwnersConnectedChannel() throws Exception {
    UUID ownerId = UUID.randomUUID(), memberId = UUID.randomUUID(), boardId = UUID.randomUUID();
    Leaderboard board = new Leaderboard(ownerId, "Friends", null, BoardType.MULTI_GAME, BoardCreationBasis.SUBSCRIPTION, now);
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
    when(dailyGames.submitShare(memberId, null, "Fermi #92"))
        .thenReturn(new DailyGamesService.DiscordSubmissionResult(UUID.randomUUID(), game, parsed));
    GameAdapter adapter = mock(GameAdapter.class);
    when(adapter.scoreFormat()).thenReturn(new ApiModels.ScoreFormatView("", "", 2, 2));
    when(adapters.require("fermi")).thenReturn(adapter);

    assertEquals("SUCCESS", service.handle(json.readTree(
        command("daily", "222222222222222222", "111111111111111111", "333333333333333333", "result", "Fermi #92"))).outcome());
    verifyNoInteractions(billing);
    verify(billing, never()).allGameLimit(eq(memberId), any());
  }

  @Test void dailyLooksUpOptionByNameAndSuppressesMentions() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID boardId = UUID.randomUUID();
    when(interactions.insertIfAbsent(anyString(), anyString(), eq(2), eq("daily"), eq(now))).thenReturn(1);
    when(links.findById("222222222222222222")).thenReturn(Optional.of(new DiscordAccountLink("222222222222222222", userId, now)));
    when(channels.findById(any())).thenReturn(Optional.of(new DiscordChannelLeaderboard(
        "444444444444444444", "333333333333333333", boardId, userId, "222222222222222222", now)));
    Leaderboard board = new Leaderboard(userId, "Friends", null, BoardType.MULTI_GAME, BoardCreationBasis.SUBSCRIPTION, now);
    when(boards.findById(boardId)).thenReturn(Optional.of(board));
    when(billing.allGameLimit(userId, now)).thenReturn(1);
    Game game = new Game(); game.id = UUID.randomUUID(); game.slug = "fermi"; game.name = "Fermi.gg";
    ParsedSubmission parsed = new ParsedSubmission("92", 92, LocalDate.of(2026, 10, 26), new BigDecimal("1.31"), Map.of(), 1);
    when(dailyGames.submitShare(userId, null, "Fermi #92\n1.31× score")).thenReturn(
        new DailyGamesService.DiscordSubmissionResult(UUID.randomUUID(), game, parsed));
    GameAdapter adapter = mock(GameAdapter.class);
    when(adapter.scoreFormat()).thenReturn(new ApiModels.ScoreFormatView("", "×", 2, 2));
    when(adapter.resultDetails(parsed.metrics())).thenReturn(List.of(
        new ApiModels.ResultDetailView("Q1", "1.20×"),
        new ApiModels.ResultDetailView("Q2", "1.40×"),
        new ApiModels.ResultDetailView("Q3", "2.10×")));
    when(adapters.require("fermi")).thenReturn(adapter);

    DiscordInteractionResult result = service.handle(json.readTree(command("daily", "222222222222222222",
        "111111111111111111", "333333333333333333", "result", "Fermi #92\n1.31× score")));
    assertEquals("SUCCESS", result.outcome());
    @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) result.body().get("data");
    assertEquals("Saved Fermi.gg #92 at 1.31×.\nQ1 1.20× · Q2 1.40× · Q3 2.10×", data.get("content"));
    assertFalse(data.containsKey("flags"));
    assertEquals(Map.of("parse", List.of()), data.get("allowed_mentions"));
  }

  @Test void dailyIgnoresLegacyGameChoice() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID boardId = UUID.randomUUID();
    when(interactions.insertIfAbsent(anyString(), anyString(), eq(2), eq("daily"), eq(now))).thenReturn(1);
    when(links.findById("222222222222222222")).thenReturn(Optional.of(
        new DiscordAccountLink("222222222222222222", userId, now)));
    when(channels.findById(any())).thenReturn(Optional.of(new DiscordChannelLeaderboard(
        "444444444444444444", "333333333333333333", boardId, userId, "222222222222222222", now)));
    Leaderboard board = new Leaderboard(userId, "Friends", null, BoardType.MULTI_GAME, BoardCreationBasis.SUBSCRIPTION, now);
    when(boards.findById(boardId)).thenReturn(Optional.of(board));
    when(billing.allGameLimit(userId, now)).thenReturn(1);
    Game game = new Game(); game.id = UUID.randomUUID(); game.slug = "krillion"; game.name = "Krillion";
    ParsedSubmission parsed = new ParsedSubmission("82", 82, LocalDate.of(2026, 11, 4),
        new BigDecimal("700"), Map.of(), 1);
    when(dailyGames.submitShare(userId, null, "Krillion share")).thenReturn(
        new DailyGamesService.DiscordSubmissionResult(UUID.randomUUID(), game, parsed));
    GameAdapter adapter = mock(GameAdapter.class);
    when(adapter.scoreFormat()).thenReturn(new ApiModels.ScoreFormatView("", "", 0, 0));
    when(adapters.require("krillion")).thenReturn(adapter);

    DiscordInteractionResult result = service.handle(json.readTree(command("daily", "222222222222222222",
        "111111111111111111", "333333333333333333", "result", "Krillion share", "game", "fermi")));

    assertEquals("SUCCESS", result.outcome());
    verify(dailyGames).submitShare(userId, null, "Krillion share");
  }

  @Test void loldleSavesFiveScoresInServerChannelsAndDirectMessages() throws Exception {
    UUID userId = UUID.randomUUID(), boardId = UUID.randomUUID();
    String share = LoldleParser.example();
    LocalDate puzzleDate = LocalDate.of(2026, 6, 1);
    when(interactions.insertIfAbsent(anyString(), anyString(), eq(2), eq("loldle"), eq(now))).thenReturn(1);
    when(links.findById("222222222222222222"))
        .thenReturn(Optional.of(new DiscordAccountLink("222222222222222222", userId, now)));
    when(channels.findById(any())).thenReturn(Optional.of(new DiscordChannelLeaderboard(
        "444444444444444444", "333333333333333333", boardId, userId, "222222222222222222", now)));
    when(boards.findById(boardId)).thenReturn(Optional.of(
        new Leaderboard(userId, "Friends", null, BoardType.MULTI_GAME, BoardCreationBasis.SUBSCRIPTION, now)));
    when(billing.allGameLimit(userId, now)).thenReturn(1);
    List<ApiModels.LoldleScoreView> scores = LoldleParser.parse(share, puzzleDate).entrySet().stream()
        .map(entry -> new ApiModels.LoldleScoreView(entry.getKey(), entry.getValue())).toList();
    when(dailyGames.submitLoldleShare(userId, share, puzzleDate)).thenReturn(scores);

    DiscordInteractionResult server = service.handle(json.readTree(command("loldle", "222222222222222222",
        "111111111111111111", "333333333333333333", "result", share, "date", puzzleDate.toString())));
    assertPublicOutcome(server, "SUCCESS");
    @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) server.body().get("data");
    assertTrue(((String) data.get("content")).contains("ABILITY 1 ✓"));

    DiscordInteractionResult dm = service.handle(json.readTree(privateChatCommand(1, "loldle",
        "result", share, "date", puzzleDate.toString())));
    assertPublicOutcome(dm, "SUCCESS");
    assertPublicOutcome(service.handle(json.readTree(groupDmCommand("loldle",
        "result", share, "date", puzzleDate.toString()))), "SUCCESS");
    assertPublicOutcome(service.handle(json.readTree(privateChatCommand(0, "loldle",
        "result", share, "date", puzzleDate.toString()))), "SUCCESS");
    verify(dailyGames, times(4)).submitLoldleShare(userId, share, puzzleDate);
    verifyNoInteractions(billing);
  }

  @Test void linkedAccountSubmitsInUnconnectedAndLockedChatsWithoutBoardAccess() throws Exception {
    UUID userId = UUID.randomUUID();
    when(interactions.insertIfAbsent(anyString(), anyString(), eq(2), anyString(), eq(now))).thenReturn(1);
    when(links.findById("222222222222222222"))
        .thenReturn(Optional.of(new DiscordAccountLink("222222222222222222", userId, now)));
    Game game = new Game(); game.id = UUID.randomUUID(); game.slug = "fermi"; game.name = "Fermi.gg";
    ParsedSubmission parsed = new ParsedSubmission("92", 92, LocalDate.of(2026, 10, 26),
        new BigDecimal("1.31"), Map.of(), 1);
    when(dailyGames.submitShare(userId, null, "Fermi #92"))
        .thenReturn(new DailyGamesService.DiscordSubmissionResult(UUID.randomUUID(), game, parsed));
    GameAdapter adapter = mock(GameAdapter.class);
    when(adapter.scoreFormat()).thenReturn(new ApiModels.ScoreFormatView("", "", 2, 2));
    when(adapters.require("fermi")).thenReturn(adapter);

    assertPublicOutcome(service.handle(json.readTree(command("daily", "222222222222222222",
        "111111111111111111", "333333333333333333", "result", "Fermi #92"))), "SUCCESS");
    assertPublicOutcome(service.handle(json.readTree(groupDmCommand("daily", "result", "Fermi #92"))), "SUCCESS");
    assertPublicOutcome(service.handle(json.readTree(privateChatCommand(1, "daily", "result", "Fermi #92"))), "SUCCESS");
    assertPublicOutcome(service.handle(json.readTree(privateChatCommand(0, "daily", "result", "Fermi #92"))), "SUCCESS");
    verify(dailyGames, times(4)).submitShare(userId, null, "Fermi #92");
    verifyNoInteractions(channels, groupDms, boards, billing);
  }

  @Test void duplicateInteractionDoesNotRepeatMutation() throws Exception {
    when(interactions.insertIfAbsent(anyString(), anyString(), eq(2), eq("unlink"), eq(now))).thenReturn(0);
    DiscordInteractionResult result = service.handle(json.readTree(commandWithPermissions("unlink", "0", null, null)));
    assertEquals("DUPLICATE_INTERACTION", result.outcome());
    verifyNoInteractions(links);
  }

  @Test void websiteUnlinkDisconnectsOnlyBoardsOwnedByThatProfile() {
    UUID ownerId = UUID.randomUUID();
    service.unlinkWebsite(ownerId);
    verify(channels).deleteOwnedChannels(ownerId);
    verify(groupDms).deleteOwnedChannels(ownerId);
    verify(registrations).pruneUnmappedGuilds();
    verify(links).deleteByUserId(ownerId);
  }

  @Test void discordUnlinkDisconnectsBoardsOwnedByTheLinkedProfile() throws Exception {
    UUID ownerId = UUID.randomUUID();
    String discordUserId = "222222222222222222";
    when(interactions.insertIfAbsent(anyString(), anyString(), eq(2), eq("unlink"), eq(now))).thenReturn(1);
    when(links.findById(discordUserId)).thenReturn(Optional.of(new DiscordAccountLink(discordUserId, ownerId, now)));
    assertEquals("SUCCESS", service.handle(json.readTree(commandWithPermissions("unlink", "0", null, null))).outcome());
    verify(channels).deleteOwnedChannels(ownerId);
    verify(groupDms).deleteOwnedChannels(ownerId);
    verify(registrations).pruneUnmappedGuilds();
    verify(links).deleteById(discordUserId);
  }

  @Test void relinkingDiscordAccountDisconnectsPreviousOwnersChannels() throws Exception {
    UUID previousOwner = UUID.randomUUID();
    UUID newUser = UUID.randomUUID();
    when(profiles.findByIdForUpdate(newUser)).thenReturn(Optional.of(new Profile(newUser, "New User", "#dfff4f", now)));
    String issued = service.issueCode(newUser).code();
    ArgumentCaptor<DiscordLinkCode> stored = ArgumentCaptor.forClass(DiscordLinkCode.class);
    verify(codes).save(stored.capture());
    when(interactions.insertIfAbsent(anyString(), anyString(), eq(2), eq("link"), eq(now))).thenReturn(1);
    when(codes.findByCodeHashAndConsumedAtIsNullAndExpiresAtAfter(eq(stored.getValue().codeHash), eq(now)))
        .thenReturn(Optional.of(stored.getValue()));
    String discordUserId = "222222222222222222";
    when(links.findById(discordUserId)).thenReturn(Optional.of(new DiscordAccountLink(discordUserId, previousOwner, now)));
    assertEquals("SUCCESS", service.handle(json.readTree(command("link", discordUserId,
        "111111111111111111", "333333333333333333", "other", "ignored", "code", issued))).outcome());
    verify(channels).deleteOwnedChannels(previousOwner);
    verify(channels, never()).deleteOwnedChannels(newUser);
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
    DiscordService disabled = new DiscordService(profiles, links, codes, boards, channels, groupDms, registrations, interactions, billing,
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

  @Test void groupDmUsesTopLevelUserAndNeverCreatesAChannelMapping() throws Exception {
    UUID userId = UUID.randomUUID();
    when(interactions.insertIfAbsent(anyString(), anyString(), eq(2), eq("my-id"), eq(now))).thenReturn(1);
    when(links.findById("222222222222222222"))
        .thenReturn(Optional.of(new DiscordAccountLink("222222222222222222", userId, now)));
    when(profiles.findById(userId)).thenReturn(Optional.of(new Profile(userId, "Player", "#dfff4f", now)));

    DiscordInteractionResult result = service.handle(json.readTree(groupDmCommand("my-id")));

    assertPrivateOutcome(result, "SUCCESS");
    verifyNoInteractions(channels, boards, billing);
  }

  @Test void groupDmSignupExplainsLinkingWithoutCreatingAServerIntent() throws Exception {
    when(interactions.insertIfAbsent(anyString(), anyString(), eq(2), eq("signup"), eq(now))).thenReturn(1);

    DiscordInteractionResult result = service.handle(json.readTree(groupDmCommand("signup")));

    assertPrivateOutcome(result, "SUCCESS");
    @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) result.body().get("data");
    assertTrue(((String) data.get("content")).contains("/dashboard"));
    assertTrue(((String) data.get("content")).contains("/link"));
    verifyNoInteractions(signup, channels);
  }

  @Test void groupDmRequiresAnExplicitLeaderboardAndRejectsServerSetup() throws Exception {
    UUID userId = UUID.randomUUID(), boardId = UUID.randomUUID();
    when(interactions.insertIfAbsent(anyString(), anyString(), eq(2), anyString(), eq(now))).thenReturn(1);
    when(links.findById("222222222222222222"))
        .thenReturn(Optional.of(new DiscordAccountLink("222222222222222222", userId, now)));

    DiscordInteractionResult missing = service.handle(json.readTree(groupDmCommand("leaderboard", "game", "fermi")));
    assertPrivateOutcome(missing, "PRIVATE_LEADERBOARD_REQUIRED");
    assertTrue(json.writeValueAsString(missing.body()).contains("leaderboard"));

    DiscordInteractionResult setup = service.handle(json.readTree(
        groupDmCommand("setup", "leaderboard", boardId.toString())));
    assertPrivateOutcome(setup, "GUILD_REQUIRED");
    verifyNoInteractions(channels, boards, billing, dailyGames);
  }

  @Test void ownerConnectsGroupDmAndMembersUseTheSavedLeaderboard() throws Exception {
    UUID ownerId = UUID.randomUUID(), memberId = UUID.randomUUID(), boardId = UUID.randomUUID();
    when(interactions.insertIfAbsent(anyString(), anyString(), eq(2), anyString(), eq(now))).thenReturn(1);
    when(links.findById("222222222222222222"))
        .thenReturn(Optional.of(new DiscordAccountLink("222222222222222222", ownerId, now)));
    Leaderboard board = new Leaderboard(ownerId, "Friends", null, BoardType.MULTI_GAME, BoardCreationBasis.SUBSCRIPTION, now);
    when(boards.findById(boardId)).thenReturn(Optional.of(board));
    when(billing.isBoardAccessible(board, now)).thenReturn(true);
    when(groupDms.findForUpdate("333333333333333333")).thenReturn(Optional.empty());

    DiscordInteractionResult configured = service.handle(json.readTree(
        groupDmCommand("set-leaderboard", "leaderboard", boardId.toString())));

    assertPrivateOutcome(configured, "SUCCESS");
    ArgumentCaptor<DiscordGroupDmLeaderboard> savedMapping = ArgumentCaptor.forClass(DiscordGroupDmLeaderboard.class);
    verify(groupDms).save(savedMapping.capture());
    assertEquals("333333333333333333", savedMapping.getValue().channelId);
    assertEquals(boardId, savedMapping.getValue().leaderboardId);
    assertEquals(ownerId, savedMapping.getValue().configuredByUserId);

    when(links.findById("222222222222222222"))
        .thenReturn(Optional.of(new DiscordAccountLink("222222222222222222", memberId, now)));
    when(groupDms.findById("333333333333333333")).thenReturn(Optional.of(savedMapping.getValue()));
    Game game = new Game(); game.id = UUID.randomUUID(); game.slug = "fermi"; game.name = "Fermi.gg";
    ParsedSubmission parsed = new ParsedSubmission("92", 92, LocalDate.of(2026, 10, 26),
        new BigDecimal("1.31"), Map.of(), 1);
    when(dailyGames.submitShare(memberId, null, "Fermi #92"))
        .thenReturn(new DailyGamesService.DiscordSubmissionResult(UUID.randomUUID(), game, parsed));
    GameAdapter adapter = mock(GameAdapter.class);
    when(adapter.scoreFormat()).thenReturn(new ApiModels.ScoreFormatView("", "×", 2, 2));
    when(adapter.resultDetails(parsed.metrics())).thenReturn(List.of(
        new ApiModels.ResultDetailView("Q1", "1.20×"),
        new ApiModels.ResultDetailView("Q2", "1.40×"),
        new ApiModels.ResultDetailView("Q3", "2.10×")));
    when(adapters.require("fermi")).thenReturn(adapter);

    DiscordInteractionResult submitted = service.handle(json.readTree(
        groupDmCommand("daily", "result", "Fermi #92")));

    assertPublicOutcome(submitted, "SUCCESS");
    verify(dailyGames).submitShare(memberId, null, "Fermi #92");
    verifyNoInteractions(channels);
  }

  @Test void setLeaderboardRejectsOneToOnePrivateChannels() throws Exception {
    when(interactions.insertIfAbsent(anyString(), anyString(), eq(2), eq("set-leaderboard"), eq(now))).thenReturn(1);
    DiscordInteractionResult result = service.handle(json.readTree(privateChatCommand(2, 1,
        "set-leaderboard", "leaderboard", UUID.randomUUID().toString())));
    assertPrivateOutcome(result, "GROUP_DM_REQUIRED");
    verifyNoInteractions(links, boards, groupDms);
  }

  @Test void onlyCurrentLeaderboardOwnerCanReplaceOrRemoveGroupDmMapping() throws Exception {
    UUID currentOwnerId = UUID.randomUUID(), otherOwnerId = UUID.randomUUID();
    UUID currentBoardId = UUID.randomUUID(), otherBoardId = UUID.randomUUID();
    when(interactions.insertIfAbsent(anyString(), anyString(), eq(2), anyString(), eq(now))).thenReturn(1);
    when(links.findById("222222222222222222"))
        .thenReturn(Optional.of(new DiscordAccountLink("222222222222222222", otherOwnerId, now)));
    Leaderboard currentBoard = new Leaderboard(currentOwnerId, "Current", null, BoardType.MULTI_GAME, BoardCreationBasis.SUBSCRIPTION, now);
    Leaderboard otherBoard = new Leaderboard(otherOwnerId, "Other", null, BoardType.MULTI_GAME, BoardCreationBasis.SUBSCRIPTION, now);
    when(boards.findById(currentBoardId)).thenReturn(Optional.of(currentBoard));
    when(boards.findById(otherBoardId)).thenReturn(Optional.of(otherBoard));
    when(billing.isBoardAccessible(otherBoard, now)).thenReturn(true);
    DiscordGroupDmLeaderboard mapping = new DiscordGroupDmLeaderboard("333333333333333333", currentBoardId,
        currentOwnerId, "999999999999999999", now);
    when(groupDms.findForUpdate("333333333333333333")).thenReturn(Optional.of(mapping));

    assertPrivateOutcome(service.handle(json.readTree(groupDmCommand(
        "set-leaderboard", "leaderboard", otherBoardId.toString()))), "NOT_OWNER");
    assertPrivateOutcome(service.handle(json.readTree(groupDmCommand("unset-leaderboard"))), "NOT_OWNER");
    verify(groupDms, never()).save(any());
    verify(groupDms, never()).delete(any());

    when(links.findById("222222222222222222"))
        .thenReturn(Optional.of(new DiscordAccountLink("222222222222222222", currentOwnerId, now)));
    assertPrivateOutcome(service.handle(json.readTree(groupDmCommand("unset-leaderboard"))), "SUCCESS");
    verify(groupDms).delete(mapping);
  }

  @Test void privateChatsPublishSuccessfulDailySubmissionsWithoutPaidChannelChecks() throws Exception {
    UUID userId = UUID.randomUUID(), boardId = UUID.randomUUID();
    when(interactions.insertIfAbsent(anyString(), anyString(), eq(2), eq("daily"), eq(now))).thenReturn(1);
    when(links.findById("222222222222222222"))
        .thenReturn(Optional.of(new DiscordAccountLink("222222222222222222", userId, now)));
    when(boards.findById(boardId)).thenReturn(Optional.of(
        new Leaderboard(userId, "Friends", null, BoardType.MULTI_GAME, BoardCreationBasis.SUBSCRIPTION, now)));
    Game game = new Game(); game.id = UUID.randomUUID(); game.slug = "fermi"; game.name = "Fermi.gg";
    ParsedSubmission parsed = new ParsedSubmission("92", 92, LocalDate.of(2026, 10, 26),
        new BigDecimal("1.31"), Map.of(), 1);
    when(dailyGames.submitShare(userId, null, "Fermi #92\n1.31× score"))
        .thenReturn(new DailyGamesService.DiscordSubmissionResult(UUID.randomUUID(), game, parsed));
    GameAdapter adapter = mock(GameAdapter.class);
    when(adapter.scoreFormat()).thenReturn(new ApiModels.ScoreFormatView("", "×", 2, 2));
    when(adapter.resultDetails(parsed.metrics())).thenReturn(List.of(
        new ApiModels.ResultDetailView("Q1", "1.20×"),
        new ApiModels.ResultDetailView("Q2", "1.40×"),
        new ApiModels.ResultDetailView("Q3", "2.10×")));
    when(adapters.require("fermi")).thenReturn(adapter);

    for (int context : List.of(1, 2)) {
      DiscordInteractionResult result = service.handle(json.readTree(privateChatCommand(context, "daily",
          "result", "Fermi #92\n1.31× score", "leaderboard", boardId.toString())));
      assertPublicOutcome(result, "SUCCESS");
      @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) result.body().get("data");
      assertEquals("Saved Fermi.gg #92 at 1.31×.\nQ1 1.20× · Q2 1.40× · Q3 2.10×", data.get("content"));
    }

    verify(dailyGames, times(2)).submitShare(userId, null, "Fermi #92\n1.31× score");
    verifyNoInteractions(channels, billing);
  }

  @Test void groupDmViewsItsMappedLeaderboardWithoutRepeatingTheUuid() throws Exception {
    UUID userId = UUID.randomUUID(), boardId = UUID.randomUUID();
    when(interactions.insertIfAbsent(anyString(), anyString(), eq(2), eq("leaderboard"), eq(now))).thenReturn(1);
    when(links.findById("222222222222222222"))
        .thenReturn(Optional.of(new DiscordAccountLink("222222222222222222", userId, now)));
    ApiModels.GameView game = game("fermi", "Fermi.gg");
    when(groupDms.findById("333333333333333333")).thenReturn(Optional.of(
        new DiscordGroupDmLeaderboard("333333333333333333", boardId, userId, "222222222222222222", now)));
    when(boards.findById(boardId)).thenReturn(Optional.of(
        new Leaderboard(userId, "Friends", null, BoardType.MULTI_GAME, BoardCreationBasis.SUBSCRIPTION, now)));
    when(dailyGames.discordLeaderboardGames(userId, boardId)).thenReturn(List.of(game));
    when(dailyGames.board(any(), eq(boardId), eq("7d"), eq("fermi")))
        .thenReturn(board(boardId, "Private Friends", game, "7d", List.of()));

    DiscordInteractionResult result = service.handle(json.readTree(groupDmCommand("leaderboard",
        "game", "fermi", "range", "7d")));

    assertEquals("SUCCESS", result.outcome());
    assertV2Payload(result, 4, false);
    assertTrue(json.writeValueAsString(result.body()).contains("Private Friends"));
    verify(dailyGames).board(argThat(user -> user.id().equals(userId)), eq(boardId), eq("7d"), eq("fermi"));
    verifyNoInteractions(channels, billing);
  }

  @Test void groupDmAutocompleteUsesExplicitOrMappedLeaderboard() throws Exception {
    UUID userId = UUID.randomUUID(), boardId = UUID.randomUUID();
    when(links.findById("222222222222222222"))
        .thenReturn(Optional.of(new DiscordAccountLink("222222222222222222", userId, now)));
    when(dailyGames.searchDiscordGames(userId, boardId, "fe", false))
        .thenReturn(List.of(game("fermi", "Fermi.gg")));
    String payload = groupDmCommand("leaderboard", "leaderboard", boardId.toString(), "game", "fe")
        .replace("\"type\":2", "\"type\":4")
        .replace("\"name\":\"game\",\"value\"", "\"name\":\"game\",\"focused\":true,\"value\"");

    DiscordInteractionResult result = service.handle(json.readTree(payload));

    assertEquals(8, result.body().get("type"));
    @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) result.body().get("data");
    assertEquals("fermi", ((List<?>) data.get("choices")).stream()
        .map(value -> ((Map<?, ?>) value).get("value")).findFirst().orElseThrow());

    when(groupDms.findById("333333333333333333")).thenReturn(Optional.of(
        new DiscordGroupDmLeaderboard("333333333333333333", boardId, userId, "222222222222222222", now)));
    String mappedPayload = groupDmCommand("leaderboard", "game", "fe")
        .replace("\"type\":2", "\"type\":4")
        .replace("\"name\":\"game\",\"value\"", "\"name\":\"game\",\"focused\":true,\"value\"");
    DiscordInteractionResult mappedResult = service.handle(json.readTree(mappedPayload));
    assertEquals(8, mappedResult.body().get("type"));
    verify(dailyGames, times(2)).searchDiscordGames(userId, boardId, "fe", false);
    verifyNoInteractions(channels);
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
    when(boards.findById(boardId)).thenReturn(Optional.of(
        new Leaderboard(userId, "Friends", null, BoardType.MULTI_GAME, BoardCreationBasis.SUBSCRIPTION, now)));
    assertPrivateOutcome(service.handle(json.readTree(commandWithPermissions("add-member", "0", "uuid", shareId.toString()))), "SUCCESS");
    verify(dailyGames).addMember(argThat(current -> current.id().equals(userId)), eq(boardId), eq(shareId));
  }

  @Test void autocompleteLimitsResultsAndDoesNotRequireLink() throws Exception {
    UUID boardId = UUID.randomUUID();
    when(channels.findById(any())).thenReturn(Optional.of(new DiscordChannelLeaderboard(
        "444444444444444444", "333333333333333333", boardId, UUID.randomUUID(), "222222222222222222", now)));
    List<ApiModels.GameView> games = new ArrayList<>();
    for (int i = 0; i < 25; i++) games.add(game("game" + i, "Game " + i));
    when(dailyGames.searchDiscordGames(boardId, "ga", false)).thenReturn(games);
    String payload = commandWithPermissions("leaderboard", "0", "game", "ga")
        .replace("\"type\":2", "\"type\":4")
        .replace("\"name\":\"game\",\"value\"", "\"name\":\"game\",\"focused\":true,\"value\"");
    DiscordInteractionResult result = service.handle(json.readTree(payload));
    assertEquals(8, result.body().get("type"));
    @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) result.body().get("data");
    assertEquals(25, ((List<?>) data.get("choices")).size());
    verifyNoInteractions(links);
  }

  @Test void gameLeaderboardUsesComponentsV2AndFormatsScoresAndDetails() throws Exception {
    UUID userId = UUID.randomUUID(), boardId = UUID.randomUUID();
    setupLeaderboardMocks(userId, boardId);
    ApiModels.GameView game = game("fermi", "Fermi.gg");
    List<ApiModels.RankingView> ranks = new ArrayList<>();
    for (int index = 1; index <= 6; index++) {
      ranks.add(new ApiModels.RankingView(index == 2 ? 1 : index, UUID.randomUUID(), UUID.randomUUID(),
          index == 1 ? "@everyone *winner*" : "Player " + index,
          index <= 2 ? new BigDecimal("1.3150") : new BigDecimal("1.3100"), 2));
    }
    ApiModels.DailyResultDetailsView firstDetails = new ApiModels.DailyResultDetailsView(ranks.getFirst().userId(), "92",
        List.of(new ApiModels.ResultDetailView("Q1", "1.20×"),
            new ApiModels.ResultDetailView("Q2", "1.40×"),
            new ApiModels.ResultDetailView("Q3", "2.10×")));
    when(dailyGames.discordLeaderboardGames(userId, boardId)).thenReturn(List.of(game));
    when(dailyGames.board(any(), eq(boardId), eq("today"), eq("fermi")))
        .thenReturn(new ApiModels.LeaderboardResponse(boardId, "Friday [friends]", UUID.randomUUID(), game,
            BoardType.MULTI_GAME, List.of(game), "today", false, List.of(), ranks, List.of(), List.of(),
            List.of(firstDetails)));

    DiscordInteractionResult result = service.handle(json.readTree(commandWithPermissions("leaderboard", "0", "game", "fermi")));

    assertEquals("SUCCESS", result.outcome());
    @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) result.body().get("data");
    assertV2Payload(result, 4, false);
    String components = json.writeValueAsString(data.get("components"));
    assertTrue(components.contains("Friday \\\\[friends\\\\]"));
    assertTrue(components.contains("Fermi.gg"));
    assertTrue(components.contains("🥇"));
    assertEquals(2, components.split("🥇", -1).length - 1);
    assertTrue(components.contains("1.32×"));
    assertTrue(components.contains("Q1 1.20× · Q2 1.40× · Q3 2.10×"));
    assertTrue(components.contains("Player 5"));
    assertFalse(components.contains("Player 6"));
    assertTrue(components.contains("game=fermi&range=today"));
    assertTrue(components.contains("Search games"));
    assertTrue(components.contains("Last 7 days"));
    assertFalse(components.contains("Select a game"));
    assertFalse(components.contains("Back"));
    assertTrue(components.contains("Next"));
  }

  @Test void leaderboardSupportsEveryRangeAndEmptyMultiGameResults() throws Exception {
    UUID userId = UUID.randomUUID(), boardId = UUID.randomUUID();
    setupLeaderboardMocks(userId, boardId);
    ApiModels.GameView krillion = game("krillion", "Krillion");
    when(dailyGames.discordLeaderboardGames(userId, boardId)).thenReturn(List.of(krillion));
    for (String range : List.of("7d", "30d", "all")) {
      when(dailyGames.board(any(), eq(boardId), eq(range), eq("krillion")))
          .thenReturn(board(boardId, "Mixed board", krillion, range, List.of(),
              BoardType.MULTI_GAME));
      DiscordInteractionResult result = service.handle(json.readTree(command("leaderboard", "222222222222222222",
          "111111111111111111", "333333333333333333", "range", range, "game", "krillion")));
      assertEquals("SUCCESS", result.outcome());
      assertV2Payload(result, 4, false);
      @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) result.body().get("data");
      String components = json.writeValueAsString(data.get("components"));
      assertTrue(components.contains("Nobody has posted a score for this range yet."));
      assertTrue(components.contains("&range=" + range));
    }
    verify(dailyGames, times(3)).board(argThat(user -> user.id().equals(userId)), eq(boardId), anyString(), eq("krillion"));
  }

  @Test void leaderboardFailuresArePrivateAndNeverPublishRankings() throws Exception {
    UUID userId = UUID.randomUUID(), boardId = UUID.randomUUID();
    when(interactions.insertIfAbsent(anyString(), anyString(), eq(2), eq("leaderboard"), eq(now))).thenReturn(1);
    String payload = commandWithPermissions("leaderboard", "0", "game", "fermi");
    assertPrivateLeaderboardOutcome(service.handle(json.readTree(payload)), "NOT_LINKED");
    when(links.findById("222222222222222222"))
        .thenReturn(Optional.of(new DiscordAccountLink("222222222222222222", userId, now)));
    assertPrivateLeaderboardOutcome(service.handle(json.readTree(payload)), "CHANNEL_NOT_CONFIGURED");
    when(channels.findById(any())).thenReturn(Optional.of(new DiscordChannelLeaderboard(
        "444444444444444444", "333333333333333333", boardId, userId, "222222222222222222", now)));
    Leaderboard board = new Leaderboard(userId, "Friends", null, BoardType.MULTI_GAME, BoardCreationBasis.SUBSCRIPTION, now);
    when(boards.findById(boardId)).thenReturn(Optional.of(board));
    when(billing.allGameLimit(userId, now)).thenReturn(1);
    when(dailyGames.discordLeaderboardGames(userId, boardId))
        .thenReturn(List.of(game("fermi", "Fermi.gg"), game("unknown", "Unknown")));
    when(dailyGames.board(any(), eq(boardId), eq("today"), eq("fermi")))
        .thenThrow(new ApiException(404, "NOT_FOUND", "Resource not found."),
            new ApiException(402, "OWNER_SUBSCRIPTION_REQUIRED", "Locked"),
            new ApiException(400, "GAME_NOT_ALLOWED", "That game is not available on this leaderboard."));
    assertPrivateLeaderboardOutcome(service.handle(json.readTree(payload)), "NOT_FOUND");
    assertPrivateLeaderboardOutcome(service.handle(json.readTree(payload)), "OWNER_SUBSCRIPTION_REQUIRED");
    assertPrivateLeaderboardOutcome(service.handle(json.readTree(payload)), "GAME_NOT_ALLOWED");
    when(dailyGames.board(any(), eq(boardId), eq("today"), eq("unknown")))
        .thenThrow(new ApiException(400, "GAME_NOT_ALLOWED", "That game is not available on this leaderboard."));
    assertPrivateLeaderboardOutcome(service.handle(json.readTree(commandWithPermissions("leaderboard", "0", "game", "unknown"))),
        "GAME_NOT_ALLOWED");
    assertPrivateLeaderboardOutcome(service.handle(json.readTree(command("leaderboard", "222222222222222222",
        "111111111111111111", "333333333333333333", "game", "fermi", "range", "bad"))), "INVALID_RANGE");
  }

  private void setupLeaderboardMocks(UUID userId, UUID boardId) {
    when(interactions.insertIfAbsent(anyString(), anyString(), eq(2), eq("leaderboard"), eq(now))).thenReturn(1);
    when(links.findById("222222222222222222"))
        .thenReturn(Optional.of(new DiscordAccountLink("222222222222222222", userId, now)));
    when(channels.findById(any())).thenReturn(Optional.of(new DiscordChannelLeaderboard(
        "444444444444444444", "333333333333333333", boardId, userId, "222222222222222222", now)));
    Leaderboard board = new Leaderboard(userId, "Friends", null, BoardType.MULTI_GAME, BoardCreationBasis.SUBSCRIPTION, now);
    when(boards.findById(boardId)).thenReturn(Optional.of(board));
    when(billing.allGameLimit(userId, now)).thenReturn(1);
  }

  private ApiModels.GameView game(String slug, String name) {
    return new ApiModels.GameView(UUID.randomUUID(), slug, name, "", "higher",
        new ApiModels.ScoreFormatView("", "×", 2, 2), null);
  }

  private ApiModels.LeaderboardResponse board(UUID id, String name, ApiModels.GameView game, String range,
                                                List<ApiModels.RankingView> rankings) {
    return board(id, name, game, range, rankings, BoardType.MULTI_GAME);
  }

  private ApiModels.LeaderboardResponse board(UUID id, String name, ApiModels.GameView game, String range,
                                                List<ApiModels.RankingView> rankings, BoardType type) {
    return new ApiModels.LeaderboardResponse(id, name, UUID.randomUUID(), game, type,
        List.of(game), range, false, List.of(), rankings, List.of());
  }

  private void assertPrivateOutcome(DiscordInteractionResult result, String outcome) {
    assertEquals(outcome, result.outcome());
    @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) result.body().get("data");
    assertEquals("leaderboard".equals(result.command()) ? 32_832 : 64, data.get("flags"));
    assertEquals(Map.of("parse", List.of()), data.get("allowed_mentions"));
  }

  private void assertPrivateLeaderboardOutcome(DiscordInteractionResult result, String outcome) {
    assertEquals(outcome, result.outcome());
    assertV2Payload(result, 4, true);
  }

  private void assertV2Payload(DiscordInteractionResult result, int responseType, boolean ephemeral) {
    assertEquals(responseType, result.body().get("type"));
    @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) result.body().get("data");
    assertEquals(ephemeral ? 32_832 : 32_768, data.get("flags"));
    assertFalse(data.containsKey("content"));
    assertFalse(data.containsKey("embeds"));
    assertEquals(Map.of("parse", List.of()), data.get("allowed_mentions"));
    @SuppressWarnings("unchecked") List<Map<String, Object>> components =
        (List<Map<String, Object>>) data.get("components");
    assertEquals(1, components.size());
    assertEquals(17, components.getFirst().get("type"));
    assertCustomIdsWithinLimit(components);
  }

  private void assertCustomIdsWithinLimit(Object value) {
    if (value instanceof Map<?, ?> map) {
      if (map.get("custom_id") instanceof String customId) assertTrue(customId.length() <= 100);
      map.values().forEach(this::assertCustomIdsWithinLimit);
    } else if (value instanceof Iterable<?> values) {
      values.forEach(this::assertCustomIdsWithinLimit);
    }
  }

  private void assertPublicOutcome(DiscordInteractionResult result, String outcome) {
    assertEquals(outcome, result.outcome());
    @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) result.body().get("data");
    assertFalse(data.containsKey("flags"));
    assertEquals(Map.of("parse", List.of()), data.get("allowed_mentions"));
  }

  private String commandWithPermissions(String name, String permissions, String optionName, String optionValue) {
    String options = optionName == null ? "[]" : "[{\"type\":3,\"name\":\"" + optionName + "\",\"value\":\"" + optionValue + "\"}]";
    return "{\"type\":2,\"id\":\"111111111111111111\",\"guild_id\":\"444444444444444444\","
        + "\"channel_id\":\"333333333333333333\",\"member\":{\"permissions\":\"" + permissions
        + "\",\"user\":{\"id\":\"222222222222222222\"}},\"data\":{\"name\":\"" + name + "\",\"options\":" + options + "}}";
  }

  private String command(String name, String userId, String interactionId, String channelId,
                         String... optionPairs) {
    if (optionPairs.length % 2 != 0) throw new IllegalArgumentException("Options must be name/value pairs");
    StringBuilder options = new StringBuilder("[");
    for (int index = 0; index < optionPairs.length; index += 2) {
      if (index > 0) options.append(',');
      options.append("{\"type\":3,\"name\":\"").append(escape(optionPairs[index]))
          .append("\",\"value\":\"").append(escape(optionPairs[index + 1])).append("\"}");
    }
    return "{\"type\":2,\"id\":\"" + interactionId + "\",\"guild_id\":\"444444444444444444\","
        + "\"channel_id\":\"" + channelId + "\",\"member\":{\"permissions\":\"0\",\"user\":{\"id\":\""
        + userId + "\"}},\"data\":{\"name\":\"" + name + "\",\"options\":" + options + "]}}";
  }

  private String groupDmCommand(String name, String... optionPairs) {
    return privateChatCommand(2, 3, name, optionPairs);
  }

  private String privateChatCommand(int context, String name, String... optionPairs) {
    return privateChatCommand(context, context == 2 ? 3 : 1, name, optionPairs);
  }

  private String privateChatCommand(int context, int channelType, String name, String... optionPairs) {
    if (optionPairs.length % 2 != 0) throw new IllegalArgumentException("Options must be name/value pairs");
    StringBuilder options = new StringBuilder("[");
    for (int index = 0; index < optionPairs.length; index += 2) {
      if (index > 0) options.append(',');
      options.append("{\"type\":3,\"name\":\"").append(escape(optionPairs[index]))
          .append("\",\"value\":\"").append(escape(optionPairs[index + 1])).append("\"}");
    }
    return "{\"type\":2,\"id\":\"111111111111111111\",\"context\":" + context + ","
        + "\"channel_id\":\"333333333333333333\",\"channel\":{\"id\":\"333333333333333333\",\"type\":"
        + channelType + "},\"user\":{\"id\":\"222222222222222222\"},"
        + "\"data\":{\"name\":\"" + name + "\",\"options\":" + options + "]}}";
  }

  private String componentCallback(UUID boardId, String state, int componentType, String interactionId,
                                   String selectedValue) {
    String values = selectedValue == null ? "" : ",\"values\":[\"" + selectedValue + "\"]";
    return "{\"type\":3,\"id\":\"" + interactionId + "\",\"guild_id\":\"444444444444444444\","
        + "\"channel_id\":\"333333333333333333\",\"member\":{\"user\":{\"id\":\"222222222222222222\"}},"
        + "\"data\":{\"component_type\":" + componentType + ",\"custom_id\":\"lb:" + boardId + ":"
        + state + "\"" + values + "}}";
  }

  private String modalCallback(UUID boardId, String state, String interactionId, String query) {
    return "{\"type\":5,\"id\":\"" + interactionId + "\",\"guild_id\":\"444444444444444444\","
        + "\"channel_id\":\"333333333333333333\",\"member\":{\"user\":{\"id\":\"222222222222222222\"}},"
        + "\"data\":{\"custom_id\":\"lb:" + boardId + ":" + state + "\",\"components\":[{\"type\":1,"
        + "\"components\":[{\"type\":4,\"custom_id\":\"game-query\",\"value\":\"" + escape(query) + "\"}]}]}}";
  }

  private String escape(String value) { return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\""); }

  @Test void gameSearchModalUpdatesMessageAndSelectionRevalidatesMapping() throws Exception {
    UUID userId = UUID.randomUUID(), boardId = UUID.randomUUID();
    setupLeaderboardMocks(userId, boardId);
    when(interactions.insertIfAbsent(anyString(), anyString(), eq(3), eq("leaderboard"), eq(now))).thenReturn(1);
    when(interactions.insertIfAbsent(anyString(), anyString(), eq(5), eq("leaderboard"), eq(now))).thenReturn(1);
    var game = game("fermi", "Fermi");
    var winner = new ApiModels.RankingView(1, userId, UUID.randomUUID(), "Winner",
        new BigDecimal("1.25"), 1);
    when(dailyGames.discordLeaderboardGames(userId, boardId)).thenReturn(List.of(game));
    when(dailyGames.searchDiscordGames(userId, boardId, "ferm", false)).thenReturn(List.of(game));
    when(dailyGames.todaySummary(any(), eq(boardId))).thenReturn(new DailyGamesService.TodaySummary(boardId,
        "Friends", List.of(winner), List.of(new DailyGamesService.TodayGameLeaders(game, List.of(winner)))));
    when(dailyGames.board(any(), eq(boardId), eq("today"), eq("fermi")))
        .thenReturn(board(boardId, "Friends", game, "today", List.of(winner)));
    DiscordInteractionResult card = service.handle(json.readTree(commandWithPermissions("leaderboard", "0", null, null)));
    assertV2Payload(card, 4, false);
    @SuppressWarnings("unchecked") Map<String, Object> cardData = (Map<String, Object>) card.body().get("data");
    String cardComponents = json.writeValueAsString(cardData.get("components"));
    assertTrue(cardComponents.contains("Daily Leaderboards · Today"));
    assertTrue(cardComponents.contains("Winner"));
    assertTrue(cardComponents.contains("1.25×"));
    assertTrue(cardComponents.contains("Search games"));
    assertFalse(cardComponents.contains("Select a game"));

    DiscordInteractionResult modal = service.handle(json.readTree(componentCallback(boardId,
        "o:x:t:0:0:q", 2, "111111111111111112", null)));
    assertEquals(9, modal.body().get("type"));
    String modalJson = json.writeValueAsString(modal.body());
    assertTrue(modalJson.contains("Search games"));
    assertTrue(modalJson.contains("game-query"));
    assertTrue(modalJson.contains("\"min_length\":1"));
    assertTrue(modalJson.contains("\"max_length\":80"));

    DiscordInteractionResult matches = service.handle(json.readTree(modalCallback(boardId,
        "o:x:t:0:0:f", "111111111111111113", "ferm")));
    assertV2Payload(matches, 7, false);
    String matchesJson = json.writeValueAsString(matches.body());
    assertTrue(matchesJson.contains("Select a matching game"));
    assertTrue(matchesJson.contains("g:0"));
    assertFalse(matchesJson.contains("Search games"));

    String callback = componentCallback(boardId, "o:x:t:0:0:s", 3, "111111111111111114", "g:0");
    DiscordInteractionResult detail = service.handle(json.readTree(callback));
    assertV2Payload(detail, 7, false);
    @SuppressWarnings("unchecked") Map<String, Object> detailData = (Map<String, Object>) detail.body().get("data");
    String detailJson = json.writeValueAsString(detailData.get("components"));
    assertTrue(detailJson.contains("Winner"));
    assertTrue(detailJson.contains("Search games"));
    assertFalse(detailJson.contains("Select a matching game"));
    when(channels.findById(any())).thenReturn(Optional.of(new DiscordChannelLeaderboard(
        "444444444444444444", "333333333333333333", UUID.randomUUID(), userId,
        "222222222222222222", now)));
    assertPrivateLeaderboardOutcome(service.handle(json.readTree(callback)), "MAPPING_CHANGED");
  }


  @Test void overviewPaginationAndSearchResultsUseGlobalGameIndexes() throws Exception {
    UUID userId = UUID.randomUUID(), boardId = UUID.randomUUID();
    setupLeaderboardMocks(userId, boardId);
    when(interactions.insertIfAbsent(anyString(), anyString(), eq(3), eq("leaderboard"), eq(now))).thenReturn(1);
    when(interactions.insertIfAbsent(anyString(), anyString(), eq(5), eq("leaderboard"), eq(now))).thenReturn(1);
    var winner = new ApiModels.RankingView(1, userId, UUID.randomUUID(), "Winner",
        new BigDecimal("1.25"), 1);
    List<ApiModels.GameView> allGames = new ArrayList<>();
    for (int i = 0; i < 26; i++) allGames.add(game("game-" + i, "Game " + i));
    when(dailyGames.discordLeaderboardGames(userId, boardId)).thenReturn(allGames);
    when(dailyGames.searchDiscordGames(userId, boardId, "25", false)).thenReturn(List.of(allGames.get(25)));
    List<DailyGamesService.TodayGameLeaders> games = new ArrayList<>();
    for (int i = 0; i < 6; i++) games.add(new DailyGamesService.TodayGameLeaders(allGames.get(i), List.of(winner)));
    when(dailyGames.todaySummary(any(), eq(boardId))).thenReturn(
        new DailyGamesService.TodaySummary(boardId, "Friends", List.of(winner), games));
    String payload = "{\"type\":3,\"id\":\"111111111111111112\",\"guild_id\":\"444444444444444444\","
        + "\"channel_id\":\"333333333333333333\",\"member\":{\"user\":{\"id\":\"222222222222222222\"}},"
        + "\"data\":{\"component_type\":2,\"custom_id\":\"lb:" + boardId + ":o:x:t:0:0:n\"}}";
    var result = service.handle(json.readTree(payload));
    assertV2Payload(result, 7, false);
    @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) result.body().get("data");
    String components = json.writeValueAsString(data.get("components"));
    assertTrue(components.contains("Page 2 of 2"));
    assertTrue(components.contains("Game 5"));
    assertTrue(components.contains("Previous"));
    assertTrue(components.contains("Search games"));
    assertFalse(components.contains("Previous Games"));
    assertFalse(components.contains("Next Games"));

    DiscordInteractionResult matches = service.handle(json.readTree(modalCallback(boardId,
        "o:x:t:0:0:f", "111111111111111113", "25")));
    assertV2Payload(matches, 7, false);
    String matchComponents = json.writeValueAsString(matches.body());
    assertTrue(matchComponents.contains("Game 25"));
    assertTrue(matchComponents.contains("g:25"));
    assertFalse(matchComponents.contains("Game 24"));
  }

  @Test void gameSearchRejectsBlankAndZeroResultQueriesPrivately() throws Exception {
    UUID userId = UUID.randomUUID(), boardId = UUID.randomUUID();
    setupLeaderboardMocks(userId, boardId);
    when(interactions.insertIfAbsent(anyString(), anyString(), eq(5), eq("leaderboard"), eq(now))).thenReturn(1);
    ApiModels.GameView fermi = game("fermi", "Fermi");
    when(dailyGames.discordLeaderboardGames(userId, boardId)).thenReturn(List.of(fermi));

    DiscordInteractionResult blank = service.handle(json.readTree(modalCallback(boardId,
        "o:x:t:0:0:f", "111111111111111150", "   ")));
    assertPrivateLeaderboardOutcome(blank, "INVALID_SEARCH");

    DiscordInteractionResult missing = service.handle(json.readTree(modalCallback(boardId,
        "o:x:t:0:0:f", "111111111111111151", "missing")));
    assertPrivateLeaderboardOutcome(missing, "NO_GAMES_FOUND");
    verify(dailyGames).searchDiscordGames(userId, boardId, "missing", false);
  }

  @Test void gameSearchRevalidatesMappingBillingAndLinkedUser() throws Exception {
    UUID userId = UUID.randomUUID(), boardId = UUID.randomUUID();
    setupLeaderboardMocks(userId, boardId);
    when(interactions.insertIfAbsent(anyString(), anyString(), eq(5), eq("leaderboard"), eq(now))).thenReturn(1);
    String payload = modalCallback(boardId, "o:x:t:0:0:f", "111111111111111152", "fermi");

    when(channels.findById(any())).thenReturn(Optional.of(new DiscordChannelLeaderboard(
        "444444444444444444", "333333333333333333", UUID.randomUUID(), userId,
        "222222222222222222", now)));
    assertPrivateLeaderboardOutcome(service.handle(json.readTree(payload)), "MAPPING_CHANGED");

    when(channels.findById(any())).thenReturn(Optional.of(new DiscordChannelLeaderboard(
        "444444444444444444", "333333333333333333", boardId, userId,
        "222222222222222222", now)));
    when(billing.allGameLimit(userId, now)).thenReturn(0);
    assertPrivateLeaderboardOutcome(service.handle(json.readTree(payload)), "OWNER_SUBSCRIPTION_REQUIRED");

    when(billing.allGameLimit(userId, now)).thenReturn(1);
    when(links.findById("222222222222222222")).thenReturn(Optional.empty());
    assertPrivateLeaderboardOutcome(service.handle(json.readTree(payload)), "NOT_LINKED");
  }

  @Test void todayLastSevenDaysOverallAndCombinedNavigationUseBackendRankings() throws Exception {
    UUID userId = UUID.randomUUID(), boardId = UUID.randomUUID();
    setupLeaderboardMocks(userId, boardId);
    when(interactions.insertIfAbsent(anyString(), anyString(), eq(3), eq("leaderboard"), eq(now))).thenReturn(1);
    ApiModels.GameView fermi = game("fermi", "Fermi.gg");
    when(dailyGames.discordLeaderboardGames(userId, boardId)).thenReturn(List.of(fermi));
    ApiModels.RankingView daily = new ApiModels.RankingView(1, userId, UUID.randomUUID(), "Daily Winner",
        new BigDecimal("1.25"), 1);
    ApiModels.RankingView weekly = new ApiModels.RankingView(1, userId, UUID.randomUUID(), "Weekly Winner",
        new BigDecimal("1.30"), 7);
    ApiModels.RankingView overall = new ApiModels.RankingView(1, userId, UUID.randomUUID(), "Overall Winner",
        new BigDecimal("91.5"), 9, 0, 0, 0, 0, 0, "91.5 leaderboard pts",
        new BigDecimal("91.5"), 9, 0, null, null);
    when(dailyGames.board(any(), eq(boardId), eq("today"), eq("fermi")))
        .thenReturn(board(boardId, "Friends", fermi, "today", List.of(daily)));
    when(dailyGames.board(any(), eq(boardId), eq("7d"), eq("fermi")))
        .thenReturn(board(boardId, "Friends", fermi, "7d", List.of(weekly)));
    when(dailyGames.board(any(), eq(boardId), eq("all"), eq("fermi")))
        .thenReturn(new ApiModels.LeaderboardResponse(boardId, "Friends", UUID.randomUUID(), fermi,
            BoardType.MULTI_GAME, List.of(fermi), "all", false, List.of(), List.of(overall), List.of(),
            List.of(overall), List.of()));
    when(dailyGames.todaySummary(any(), eq(boardId))).thenReturn(new DailyGamesService.TodaySummary(boardId,
        "Friends", List.of(daily), List.of(new DailyGamesService.TodayGameLeaders(fermi, List.of(daily)))));

    DiscordInteractionResult gameOverall = service.handle(json.readTree(componentCallback(boardId,
        "g:0:t:0:0:a", 2, "111111111111111120", null)));
    assertV2Payload(gameOverall, 7, false);
    assertTrue(json.writeValueAsString(gameOverall.body()).contains("Overall Winner"));

    DiscordInteractionResult today = service.handle(json.readTree(componentCallback(boardId,
        "g:0:a:0:0:t", 2, "111111111111111121", null)));
    assertV2Payload(today, 7, false);
    assertTrue(json.writeValueAsString(today.body()).contains("Daily Winner"));

    DiscordInteractionResult lastSevenDays = service.handle(json.readTree(componentCallback(boardId,
        "g:0:t:0:0:7", 2, "111111111111111124", null)));
    assertV2Payload(lastSevenDays, 7, false);
    String weeklyJson = json.writeValueAsString(lastSevenDays.body());
    assertTrue(weeklyJson.contains("Weekly Winner"));
    assertTrue(weeklyJson.contains("Last 7 days"));
    assertTrue(weeklyJson.contains("game=fermi&range=7d"));
    assertFalse(weeklyJson.contains("Back"));

    DiscordInteractionResult back = service.handle(json.readTree(componentCallback(boardId,
        "g:0:t:0:0:b", 2, "111111111111111122", null)));
    assertV2Payload(back, 7, false);
    assertTrue(json.writeValueAsString(back.body()).contains("Daily Leaderboards · Today"));

    DiscordInteractionResult combined = service.handle(json.readTree(componentCallback(boardId,
        "o:x:t:0:0:a", 2, "111111111111111123", null)));
    assertV2Payload(combined, 7, false);
    String combinedJson = json.writeValueAsString(combined.body());
    assertTrue(combinedJson.contains("Overall Standings"));
    assertTrue(combinedJson.contains("91.5 leaderboard pts"));
    assertTrue(combinedJson.contains("Search games"));
    assertFalse(combinedJson.contains("Back"));
  }

  @Test void gamePaginationAndInvalidStateAreValidatedServerSide() throws Exception {
    UUID userId = UUID.randomUUID(), boardId = UUID.randomUUID();
    setupLeaderboardMocks(userId, boardId);
    when(interactions.insertIfAbsent(anyString(), anyString(), eq(3), eq("leaderboard"), eq(now))).thenReturn(1);
    ApiModels.GameView fermi = game("fermi", "Fermi.gg");
    when(dailyGames.discordLeaderboardGames(userId, boardId)).thenReturn(List.of(fermi));
    List<ApiModels.RankingView> rankings = new ArrayList<>();
    for (int rank = 1; rank <= 6; rank++) rankings.add(new ApiModels.RankingView(rank, UUID.randomUUID(),
        UUID.randomUUID(), "Player " + rank, BigDecimal.valueOf(rank), 1));
    when(dailyGames.board(any(), eq(boardId), eq("today"), eq("fermi")))
        .thenReturn(board(boardId, "Friends", fermi, "today", rankings));

    DiscordInteractionResult next = service.handle(json.readTree(componentCallback(boardId,
        "g:0:t:0:0:n", 2, "111111111111111130", null)));
    assertV2Payload(next, 7, false);
    String nextJson = json.writeValueAsString(next.body());
    assertTrue(nextJson.contains("Player 6"));
    assertFalse(nextJson.contains("Player 5"));
    assertTrue(nextJson.contains("Page 2 of 2"));

    DiscordInteractionResult invalid = service.handle(json.readTree(componentCallback(boardId,
        "g:0:t:1:0:n", 2, "111111111111111131", null)));
    assertPrivateLeaderboardOutcome(invalid, "INVALID_CHOICE");

    DiscordInteractionResult forgedGame = service.handle(json.readTree(componentCallback(boardId,
        "g:9:t:0:0:t", 2, "111111111111111132", null)));
    assertPrivateLeaderboardOutcome(forgedGame, "INVALID_CHOICE");
  }

  @Test void emptyOverviewStillProducesAValidComponentsV2Message() throws Exception {
    UUID userId = UUID.randomUUID(), boardId = UUID.randomUUID();
    setupLeaderboardMocks(userId, boardId);
    ApiModels.GameView fermi = game("fermi", "Fermi.gg");
    when(dailyGames.discordLeaderboardGames(userId, boardId)).thenReturn(List.of(fermi));
    when(dailyGames.todaySummary(any(), eq(boardId))).thenReturn(
        new DailyGamesService.TodaySummary(boardId, "Quiet Board", List.of(), List.of()));

    DiscordInteractionResult result = service.handle(
        json.readTree(commandWithPermissions("leaderboard", "0", null, null)));

    assertV2Payload(result, 4, false);
    String payload = json.writeValueAsString(result.body());
    assertTrue(payload.contains("Nobody has posted a score today."));
    assertTrue(payload.contains("Search games"));
    assertFalse(payload.contains("Select a game"));
    assertFalse(payload.contains("Previous Games"));
    assertFalse(payload.contains("Next Games"));
    assertFalse(payload.contains("Back"));
    assertFalse(payload.contains("embeds"));
  }

}
