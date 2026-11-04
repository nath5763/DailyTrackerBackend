package com.dailygames;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.dailygames.ApiModels.CreateLeaderboardRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DailyGamesServiceTest {
  @Test
  void catalogOnlyGameCannotCreateBoardOrAcceptAScore() {
    UUID userId = UUID.randomUUID();
    Profile owner = new Profile(userId, "Owner", "#dfff4f", Instant.now());
    ProfileRepository profiles = mock(ProfileRepository.class);
    GameRepository games = mock(GameRepository.class);
    when(profiles.findById(userId)).thenReturn(Optional.of(owner));
    DailyGamesService service = service(profiles, games, mock(LeaderboardRepository.class),
        mock(MembershipRepository.class), mock(SubmissionRepository.class), mock(GameAdapterRegistry.class), mock(BillingService.class));
    CurrentUser current = new CurrentUser(userId, "");
    assertEquals("GAME_NOT_FOUND", assertThrows(ApiException.class,
        () -> service.createBoard(current, new CreateLeaderboardRequest("Waffle club", "waffle"))).code);
    assertEquals("GAME_NOT_FOUND", assertThrows(ApiException.class,
        () -> service.preview(current, new ApiModels.PreviewSubmissionRequest("waffle", "Waffle result"))).code);
  }

  @Test
  void duplicateWordlePuzzleIsRejected() {
    UUID userId = UUID.randomUUID();
    Profile owner = new Profile(userId, "Owner", "#dfff4f", Instant.now());
    Game game = new Game(); game.id = UUID.randomUUID(); game.slug = "wordle"; game.name = "Wordle"; game.active = true;
    ProfileRepository profiles = mock(ProfileRepository.class);
    GameRepository games = mock(GameRepository.class);
    SubmissionRepository submissions = mock(SubmissionRepository.class);
    GameAdapterRegistry adapters = mock(GameAdapterRegistry.class);
    when(profiles.findById(userId)).thenReturn(Optional.of(owner));
    when(profiles.findByIdForUpdate(userId)).thenReturn(Optional.of(owner));
    when(games.findBySlugAndActiveTrue("wordle")).thenReturn(Optional.of(game));
    when(adapters.require("wordle")).thenReturn(new WordleParser());
    when(submissions.existsByUserIdAndGameIdAndPuzzleKey(userId, game.id, "1961")).thenReturn(true);
    String share = "Wordle 1,961 2/6\n⬛⬛⬛⬛⬛\n🟩🟩🟩🟩🟩";
    ParsedSubmission parsed = new WordleParser().parse(share);
    DailyGamesService service = service(profiles, games, mock(LeaderboardRepository.class),
        mock(MembershipRepository.class), submissions, adapters, mock(BillingService.class));
    var confirmed = new ApiModels.ConfirmedSubmission(parsed.puzzleKey(), parsed.puzzleNumber(), parsed.playedOn(),
        parsed.primaryScore(), parsed.metrics(), parsed.parserVersion());
    assertEquals("DUPLICATE_SUBMISSION", assertThrows(ApiException.class,
        () -> service.submit(new CurrentUser(userId, ""), new ApiModels.CreateSubmissionRequest("wordle", share, confirmed))).code);
  }
  @Test
  void ranksUnassistedLinkedInResultsBeforeFasterAssistedResults() {
    UUID ownerId = UUID.randomUUID(), friendId = UUID.randomUUID();
    Profile owner = new Profile(ownerId, "Owner", "#dfff4f", Instant.now());
    Profile friend = new Profile(friendId, "Friend", "#ff8db6", Instant.now());
    Game game = new Game(); game.id = UUID.randomUUID(); game.slug = "queens"; game.name = "Queens";
    game.active = true; game.releaseTimezone = "UTC"; game.rankingDirection = "lower";
    Leaderboard board = new Leaderboard(ownerId, "Queens club", game.id, BoardCreationBasis.FREE, Instant.now());
    ProfileRepository profiles = mock(ProfileRepository.class);
    GameRepository games = mock(GameRepository.class);
    LeaderboardRepository boards = mock(LeaderboardRepository.class);
    MembershipRepository memberships = mock(MembershipRepository.class);
    SubmissionRepository submissions = mock(SubmissionRepository.class);
    GameAdapterRegistry adapters = mock(GameAdapterRegistry.class);
    BillingService billing = mock(BillingService.class);
    GameAdapter adapter = mock(GameAdapter.class);
    when(adapter.scoreFormat()).thenReturn(new ApiModels.ScoreFormatView("", "", 0, 0, "duration"));
    when(adapters.require("queens")).thenReturn(adapter);
    when(boards.findById(board.id)).thenReturn(Optional.of(board));
    when(games.findById(game.id)).thenReturn(Optional.of(game));
    when(memberships.existsByIdLeaderboardIdAndIdUserId(board.id, ownerId)).thenReturn(true);
    when(memberships.findByIdLeaderboardId(board.id)).thenReturn(List.of(
        new Membership(board.id, ownerId, ownerId, Instant.now()),
        new Membership(board.id, friendId, ownerId, Instant.now())));
    when(profiles.findById(ownerId)).thenReturn(Optional.of(owner));
    when(profiles.findById(friendId)).thenReturn(Optional.of(friend));
    when(billing.isBoardAccessible(eq(board), any())).thenReturn(true);
    Submission slow = new Submission(ownerId, game.id, new ParsedSubmission("1", 1, LocalDate.now(),
        BigDecimal.valueOf(80), Map.of("hints", 0, "mistakes", 0, "beginnerAssists", 0), 1), "share", Instant.now());
    Submission fastAssisted = new Submission(friendId, game.id, new ParsedSubmission("1", 1, LocalDate.now(),
        BigDecimal.valueOf(20), Map.of("hints", 1, "mistakes", 0, "beginnerAssists", 0), 1), "share", Instant.now());
    when(submissions.findByUserIdInAndGameId(any(), eq(game.id))).thenReturn(List.of(slow, fastAssisted));
    DailyGamesService service = service(profiles, games, boards, memberships, submissions, adapters, billing);
    var rankings = service.board(new CurrentUser(ownerId, ""), board.id, "all").rankings();
    assertEquals(ownerId, rankings.get(0).userId());
    assertEquals(friendId, rankings.get(1).userId());
    assertEquals(1, rankings.get(1).assistedResults());
    assertEquals(1, rankings.get(1).hints());
  }
  @Test
  void insightsUseRollingRankingRulesAndOnlyExposeViewerScores() {
    UUID ownerId = UUID.randomUUID(), friendId = UUID.randomUUID();
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    Game game = new Game(); game.id = UUID.randomUUID(); game.slug = "queens"; game.name = "Queens";
    game.active = true; game.releaseTimezone = "UTC"; game.rankingDirection = "lower";
    Leaderboard board = new Leaderboard(ownerId, "Queens club", game.id, BoardCreationBasis.FREE, Instant.now());
    ProfileRepository profiles = mock(ProfileRepository.class);
    GameRepository games = mock(GameRepository.class);
    LeaderboardRepository boards = mock(LeaderboardRepository.class);
    MembershipRepository memberships = mock(MembershipRepository.class);
    SubmissionRepository submissions = mock(SubmissionRepository.class);
    BillingService billing = mock(BillingService.class);
    GameAdapterRegistry adapters = mock(GameAdapterRegistry.class);
    GameAdapter adapter = mock(GameAdapter.class);
    when(adapter.scoreFormat()).thenReturn(new ApiModels.ScoreFormatView("", "", 0, 0, "duration"));
    when(adapters.require("queens")).thenReturn(adapter);
    when(boards.findById(board.id)).thenReturn(Optional.of(board));
    when(games.findById(game.id)).thenReturn(Optional.of(game));
    when(billing.isBoardAccessible(eq(board), any())).thenReturn(true);
    when(memberships.existsByIdLeaderboardIdAndIdUserId(board.id, ownerId)).thenReturn(true);
    when(memberships.findByIdLeaderboardId(board.id)).thenReturn(List.of(
        new Membership(board.id, ownerId, ownerId, Instant.now()),
        new Membership(board.id, friendId, ownerId, Instant.now())));
    when(profiles.findById(ownerId)).thenReturn(Optional.of(new Profile(ownerId, "Owner", "#dfff4f", Instant.now())));
    when(profiles.findById(friendId)).thenReturn(Optional.of(new Profile(friendId, "Friend", "#ff8db6", Instant.now())));
    Submission ownerOld = new Submission(ownerId, game.id, new ParsedSubmission("old", 1, today.minusDays(29),
        BigDecimal.valueOf(80), Map.of(), 1), "share", Instant.now());
    Submission ownerNew = new Submission(ownerId, game.id, new ParsedSubmission("new", 2, today,
        BigDecimal.valueOf(80), Map.of(), 1), "share", Instant.now());
    Submission friendAssisted = new Submission(friendId, game.id, new ParsedSubmission("old", 1, today.minusDays(30),
        BigDecimal.valueOf(20), Map.of("hints", 1), 1), "share", Instant.now());
    Submission friendNew = new Submission(friendId, game.id, new ParsedSubmission("new", 2, today,
        BigDecimal.valueOf(10), Map.of(), 1), "share", Instant.now());
    when(submissions.findByUserIdInAndGameIdAndPlayedOnBetween(any(), eq(game.id), any(), any()))
        .thenReturn(List.of(ownerOld, ownerNew, friendAssisted, friendNew));
    DailyGamesService service = service(profiles, games, boards, memberships, submissions, adapters, billing);
    var insights = service.insights(new CurrentUser(ownerId, ""), board.id, "queens");
    assertEquals(30, insights.ranks().getFirst().points().size());
    assertEquals(1, insights.ranks().getFirst().points().getFirst().rank());
    assertEquals(2, insights.ranks().getFirst().points().getLast().rank());
    assertEquals(1, insights.ranks().get(1).points().getLast().rank());
    assertEquals(2, insights.personalScores().size());
    assertTrue(insights.personalScores().stream().allMatch(score -> score.score().compareTo(BigDecimal.valueOf(80)) == 0));
    when(memberships.existsByIdLeaderboardIdAndIdUserId(board.id, ownerId)).thenReturn(false);
    assertEquals("NOT_FOUND", assertThrows(ApiException.class,
        () -> service.insights(new CurrentUser(ownerId, ""), board.id, "queens")).code);
  }
  @Test
  void allowsFiveFreeLeaderboardsAndRejectsTheSixth() {
    UUID ownerId = UUID.randomUUID();
    Profile owner = new Profile(ownerId, "Owner", "#dfff4f", Instant.now());
    Game game = new Game();
    game.id = UUID.randomUUID();
    game.slug = "fermi";
    ProfileRepository profiles = mock(ProfileRepository.class);
    GameRepository games = mock(GameRepository.class);
    LeaderboardRepository boards = mock(LeaderboardRepository.class);
    MembershipRepository memberships = mock(MembershipRepository.class);
    BillingService billing = mock(BillingService.class);
    AtomicInteger freeCount = new AtomicInteger();
    when(profiles.findByIdForUpdate(ownerId)).thenReturn(Optional.of(owner));
    when(games.findBySlugAndActiveTrue("fermi")).thenReturn(Optional.of(game));
    when(boards.countByOwnerIdAndBoardType(ownerId, BoardType.SINGLE_GAME))
        .thenAnswer(invocation -> (long) freeCount.get());
    when(boards.save(any(Leaderboard.class))).thenAnswer(invocation -> {
      Leaderboard board = invocation.getArgument(0);
      if (board.creationBasis == BoardCreationBasis.FREE) freeCount.incrementAndGet();
      return board;
    });
    DailyGamesService service = new DailyGamesService(mock(UserRepository.class), profiles, games, boards,
        memberships, mock(SubmissionRepository.class), mock(GameAdapterRegistry.class), billing);
    CurrentUser current = new CurrentUser(ownerId, "owner@example.com");

    for (int index = 1; index <= 5; index++) {
      assertNotNull(service.createBoard(current, new CreateLeaderboardRequest("Board " + index, "fermi")));
    }
    ApiException exception = assertThrows(ApiException.class,
        () -> service.createBoard(current, new CreateLeaderboardRequest("Board 6", "fermi")));
    assertEquals(409, exception.status);
    assertEquals("SINGLE_GAME_LIMIT_REACHED", exception.code);
    verify(boards, times(5)).save(argThat(board -> board.creationBasis == BoardCreationBasis.FREE));
  }

  @Test
  void multiGameCreationRequiresEntitlementAndNeverConsumesAFreeSlot() {
    UUID ownerId = UUID.randomUUID();
    Profile owner = new Profile(ownerId, "Owner", "#dfff4f", Instant.now());
    ProfileRepository profiles = mock(ProfileRepository.class);
    LeaderboardRepository boards = mock(LeaderboardRepository.class);
    MembershipRepository memberships = mock(MembershipRepository.class);
    BillingService billing = mock(BillingService.class);
    when(profiles.findByIdForUpdate(ownerId)).thenReturn(Optional.of(owner));
    when(billing.allGameLimit(eq(ownerId), any())).thenReturn(1);
    when(boards.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    DailyGamesService service = service(profiles, mock(GameRepository.class), boards, memberships,
        mock(SubmissionRepository.class), mock(GameAdapterRegistry.class), billing);

    UUID id = service.createBoard(new CurrentUser(ownerId, "owner@example.com"),
        new CreateLeaderboardRequest("All Games", null, BoardType.MULTI_GAME));

    assertNotNull(id);
    verify(boards, never()).countByOwnerIdAndBoardType(ownerId, BoardType.SINGLE_GAME);
    verify(boards).save(argThat(board -> board.boardType == BoardType.MULTI_GAME && board.gameId == null
        && board.creationBasis == BoardCreationBasis.SUBSCRIPTION));
    verify(memberships).save(argThat(member -> member.id.userId.equals(ownerId)));

    reset(boards, memberships);
    when(billing.allGameLimit(eq(ownerId), any())).thenReturn(0);
    ApiException exception = assertThrows(ApiException.class, () -> service.createBoard(
        new CurrentUser(ownerId, "owner@example.com"),
        new CreateLeaderboardRequest("All Games", null, BoardType.MULTI_GAME)));
    assertEquals("SUBSCRIPTION_REQUIRED", exception.code);
    verify(boards, never()).save(any());
  }

  @Test
  void premiumAllowsFiveAllGameBoardsAndStopsAtTheLimit() {
    UUID ownerId = UUID.randomUUID();
    ProfileRepository profiles = mock(ProfileRepository.class);
    LeaderboardRepository boards = mock(LeaderboardRepository.class);
    BillingService billing = mock(BillingService.class);
    AtomicInteger count = new AtomicInteger();
    when(profiles.findByIdForUpdate(ownerId)).thenReturn(Optional.of(new Profile(ownerId, "Owner", "#dfff4f", Instant.now())));
    when(billing.allGameLimit(eq(ownerId), any())).thenReturn(5);
    when(boards.countByOwnerIdAndBoardType(ownerId, BoardType.MULTI_GAME)).thenAnswer(value -> (long) count.get());
    when(boards.save(any(Leaderboard.class))).thenAnswer(value -> { count.incrementAndGet(); return value.getArgument(0); });
    DailyGamesService service = service(profiles, mock(GameRepository.class), boards, mock(MembershipRepository.class),
        mock(SubmissionRepository.class), mock(GameAdapterRegistry.class), billing);
    CurrentUser owner = new CurrentUser(ownerId, "owner@example.com");
    for (int index = 0; index < 5; index++) {
      service.createBoard(owner, new CreateLeaderboardRequest("All " + index, null, BoardType.MULTI_GAME));
    }
    assertEquals("ALL_GAME_LIMIT_REACHED", assertThrows(ApiException.class, () ->
        service.createBoard(owner, new CreateLeaderboardRequest("Sixth", null, BoardType.MULTI_GAME))).code);
  }

  @Test
  void conversionIsOwnerOnlyIdempotentAndRestoresTheFreeBasis() {
    UUID ownerId = UUID.randomUUID();
    Leaderboard board = new Leaderboard(ownerId, "Friends", UUID.randomUUID(), BoardCreationBasis.FREE, Instant.now());
    LeaderboardRepository boards = mock(LeaderboardRepository.class);
    MembershipRepository memberships = mock(MembershipRepository.class);
    BillingService billing = mock(BillingService.class);
    when(boards.findById(board.id)).thenReturn(Optional.of(board));
    when(billing.allGameLimit(eq(ownerId), any())).thenReturn(1);
    DailyGamesService service = service(mock(ProfileRepository.class), mock(GameRepository.class), boards,
        memberships, mock(SubmissionRepository.class), mock(GameAdapterRegistry.class), billing);

    service.convertToMultiGame(new CurrentUser(ownerId, "owner@example.com"), board.id);
    assertEquals(BoardType.MULTI_GAME, board.boardType);
    assertNull(board.gameId);
    assertEquals(BoardCreationBasis.SUBSCRIPTION, board.creationBasis);
    verify(boards).save(board);

    service.convertToMultiGame(new CurrentUser(ownerId, "owner@example.com"), board.id);
    verify(boards, times(1)).save(board);
    verifyNoInteractions(memberships);

    Leaderboard unsubscribed = new Leaderboard(ownerId, "Needs Access", UUID.randomUUID(),
        BoardCreationBasis.FREE, Instant.now());
    when(boards.findById(unsubscribed.id)).thenReturn(Optional.of(unsubscribed));
    when(billing.allGameLimit(eq(ownerId), any())).thenReturn(0);
    ApiException paymentRequired = assertThrows(ApiException.class,
        () -> service.convertToMultiGame(new CurrentUser(ownerId, "owner@example.com"), unsubscribed.id));
    assertEquals("SUBSCRIPTION_REQUIRED", paymentRequired.code);
    assertEquals(BoardType.SINGLE_GAME, unsubscribed.boardType);

    Leaderboard someoneElses = new Leaderboard(UUID.randomUUID(), "Other", UUID.randomUUID(),
        BoardCreationBasis.FREE, Instant.now());
    when(boards.findById(someoneElses.id)).thenReturn(Optional.of(someoneElses));
    ApiException forbidden = assertThrows(ApiException.class,
        () -> service.convertToMultiGame(new CurrentUser(ownerId, "owner@example.com"), someoneElses.id));
    assertEquals("NOT_OWNER", forbidden.code);
  }

  @Test
  void discordLegacyInferenceFailsForMultiGameButExplicitActiveGameSucceeds() {
    UUID userId = UUID.randomUUID();
    Profile profile = new Profile(userId, "Player", "#dfff4f", Instant.now());
    Leaderboard board = new Leaderboard(userId, "All Games", null, BoardType.MULTI_GAME,
        BoardCreationBasis.SUBSCRIPTION, Instant.now());
    Game krillion = new Game();
    krillion.id = UUID.randomUUID(); krillion.slug = "krillion"; krillion.name = "Krillion"; krillion.active = true;
    ProfileRepository profiles = mock(ProfileRepository.class);
    GameRepository games = mock(GameRepository.class);
    LeaderboardRepository boards = mock(LeaderboardRepository.class);
    MembershipRepository memberships = mock(MembershipRepository.class);
    SubmissionRepository submissions = mock(SubmissionRepository.class);
    GameAdapterRegistry adapters = mock(GameAdapterRegistry.class);
    BillingService billing = mock(BillingService.class);
    when(profiles.findById(userId)).thenReturn(Optional.of(profile));
    when(profiles.findByIdForUpdate(userId)).thenReturn(Optional.of(profile));
    when(boards.findById(board.id)).thenReturn(Optional.of(board));
    when(memberships.existsByIdLeaderboardIdAndIdUserId(board.id, userId)).thenReturn(true);
    when(billing.isBoardAccessible(eq(board), any())).thenReturn(true);
    when(games.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(krillion));
    ParsedSubmission parsed = new ParsedSubmission("82", 82, LocalDate.of(2026, 11, 4),
        new BigDecimal("700"), Map.of(), 1);
    GameAdapter adapter = mock(GameAdapter.class);
    when(adapters.require("krillion")).thenReturn(adapter);
    when(adapter.parse("Krillion share")).thenReturn(parsed);
    when(submissions.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    DailyGamesService service = service(profiles, games, boards, memberships, submissions, adapters, billing);

    ApiException missing = assertThrows(ApiException.class,
        () -> service.submitFromDiscord(userId, board.id, "Krillion share"));
    assertEquals("GAME_REQUIRED", missing.code);

    DailyGamesService.DiscordSubmissionResult saved =
        service.submitFromDiscord(userId, board.id, "krillion", "Krillion share");
    assertEquals("krillion", saved.game().slug);
    verify(adapter).parse("Krillion share");
  }

  @Test
  void boardViewEnforcesMembershipLockAndGameForSingleAndMultiGameBoards() {
    UUID userId = UUID.randomUUID();
    Profile profile = new Profile(userId, "Player", "#dfff4f", Instant.now());
    Game fermi = new Game();
    fermi.id = UUID.randomUUID(); fermi.slug = "fermi"; fermi.name = "Fermi";
    fermi.active = true; fermi.releaseTimezone = "UTC"; fermi.rankingDirection = "higher";
    Game krillion = new Game();
    krillion.id = UUID.randomUUID(); krillion.slug = "krillion"; krillion.name = "Krillion";
    krillion.active = true; krillion.releaseTimezone = "UTC"; krillion.rankingDirection = "higher";
    Leaderboard single = new Leaderboard(userId, "Fermi board", fermi.id, BoardCreationBasis.FREE, Instant.now());
    Leaderboard multi = new Leaderboard(userId, "All games", null, BoardType.MULTI_GAME,
        BoardCreationBasis.SUBSCRIPTION, Instant.now());
    ProfileRepository profiles = mock(ProfileRepository.class);
    GameRepository games = mock(GameRepository.class);
    LeaderboardRepository boards = mock(LeaderboardRepository.class);
    MembershipRepository memberships = mock(MembershipRepository.class);
    BillingService billing = mock(BillingService.class);
    GameAdapterRegistry adapters = mock(GameAdapterRegistry.class);
    when(boards.findById(single.id)).thenReturn(Optional.of(single));
    when(boards.findById(multi.id)).thenReturn(Optional.of(multi));
    when(billing.isBoardAccessible(eq(single), any())).thenReturn(true);
    when(billing.isBoardAccessible(eq(multi), any())).thenReturn(true);
    when(games.findById(fermi.id)).thenReturn(Optional.of(fermi));
    when(games.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(fermi, krillion));
    when(profiles.findById(userId)).thenReturn(Optional.of(profile));
    for (Game game : List.of(fermi, krillion)) {
      GameAdapter adapter = mock(GameAdapter.class);
      when(adapter.scoreFormat()).thenReturn(new ApiModels.ScoreFormatView("", "", 0, 0));
      when(adapters.require(game.slug)).thenReturn(adapter);
    }
    for (UUID boardId : List.of(single.id, multi.id)) {
      when(memberships.existsByIdLeaderboardIdAndIdUserId(boardId, userId)).thenReturn(true);
      when(memberships.findByIdLeaderboardId(boardId)).thenReturn(List.of(new Membership(boardId, userId, userId, Instant.now())));
    }
    DailyGamesService service = service(profiles, games, boards, memberships,
        mock(SubmissionRepository.class), adapters, billing);
    CurrentUser current = new CurrentUser(userId, "");

    assertEquals("fermi", service.board(current, single.id, "today", "fermi").game().slug());
    assertEquals("krillion", service.board(current, multi.id, "7d", "krillion").game().slug());
    assertEquals("GAME_NOT_ALLOWED", assertThrows(ApiException.class,
        () -> service.board(current, single.id, "today", "krillion")).code);
    when(memberships.existsByIdLeaderboardIdAndIdUserId(single.id, userId)).thenReturn(false);
    assertEquals("NOT_FOUND", assertThrows(ApiException.class,
        () -> service.board(current, single.id, "today", "fermi")).code);
    assertEquals("NOT_FOUND", assertThrows(ApiException.class,
        () -> service.insights(current, single.id, "fermi")).code);
    when(billing.isBoardAccessible(eq(multi), any())).thenReturn(false);
    assertEquals("OWNER_SUBSCRIPTION_REQUIRED", assertThrows(ApiException.class,
        () -> service.board(current, multi.id, "today", "fermi")).code);
    assertEquals("OWNER_SUBSCRIPTION_REQUIRED", assertThrows(ApiException.class,
        () -> service.insights(current, multi.id, "fermi")).code);
  }

  private DailyGamesService service(ProfileRepository profiles, GameRepository games, LeaderboardRepository boards,
                                    MembershipRepository memberships, SubmissionRepository submissions,
                                    GameAdapterRegistry adapters, BillingService billing) {
    return new DailyGamesService(mock(UserRepository.class), profiles, games, boards, memberships,
        submissions, adapters, billing);
  }
}
