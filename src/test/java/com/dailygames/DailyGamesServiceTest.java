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
  void placementPointsLoseTwentyPercentOfThePreviousPlaceAndRoundDown() {
    List<Integer> expected = List.of(100, 80, 64, 51, 40, 32, 25, 20, 16, 12, 9, 7, 5, 4, 3, 2, 1, 0, 0);
    List<Integer> actual = new ArrayList<>();
    for (int rank = 1; rank <= expected.size(); rank++) actual.add(DailyGamesService.placementPoints(rank));
    assertEquals(expected, actual);
    assertThrows(IllegalArgumentException.class, () -> DailyGamesService.placementPoints(0));
  }

  @Test
  void tiedPlacementPointsUseCompetitionRanksAndSkipTheNextPlace() {
    UUID firstId = UUID.randomUUID(), tiedId = UUID.randomUUID(), thirdId = UUID.randomUUID();
    Profile first = new Profile(firstId, "Alpha", "#dfff4f", Instant.now());
    Profile tied = new Profile(tiedId, "Bravo", "#a8c7ff", Instant.now());
    Profile third = new Profile(thirdId, "Charlie", "#ff8db6", Instant.now());
    Game game = game("timeguessr", "TimeGuessr", "higher");
    Leaderboard board = new Leaderboard(firstId, "All games", null, BoardType.MULTI_GAME,
        BoardCreationBasis.SUBSCRIPTION, Instant.now());
    ProfileRepository profiles = mock(ProfileRepository.class);
    GameRepository games = mock(GameRepository.class);
    LeaderboardRepository boards = mock(LeaderboardRepository.class);
    MembershipRepository memberships = mock(MembershipRepository.class);
    SubmissionRepository submissions = mock(SubmissionRepository.class);
    BillingService billing = mock(BillingService.class);
    GameAdapterRegistry adapters = new GameAdapterRegistry(List.of(new TimeGuessrParser()));
    when(boards.findById(board.id)).thenReturn(Optional.of(board));
    when(games.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(game));
    when(memberships.existsByIdLeaderboardIdAndIdUserId(board.id, firstId)).thenReturn(true);
    when(memberships.findByIdLeaderboardId(board.id)).thenReturn(List.of(
        new Membership(board.id, firstId, firstId, Instant.now()),
        new Membership(board.id, tiedId, firstId, Instant.now()),
        new Membership(board.id, thirdId, firstId, Instant.now())));
    when(profiles.findById(firstId)).thenReturn(Optional.of(first));
    when(profiles.findById(tiedId)).thenReturn(Optional.of(tied));
    when(profiles.findById(thirdId)).thenReturn(Optional.of(third));
    when(billing.isBoardAccessible(eq(board), any())).thenReturn(true);
    Submission firstScore = submission(firstId, game.id,
        adapters.parseResult("timeguessr", "TimeGuessr #807 — 45,000/50,000"));
    Submission tiedScore = submission(tiedId, game.id,
        adapters.parseResult("timeguessr", "TimeGuessr #807 — 45,000/50,000"));
    Submission thirdScore = submission(thirdId, game.id,
        adapters.parseResult("timeguessr", "TimeGuessr #807 — 40,000/50,000"));
    when(submissions.findByUserIdInAndGameId(any(), eq(game.id)))
        .thenReturn(List.of(firstScore, tiedScore, thirdScore));
    when(submissions.findByUserIdInAndGameIdIn(any(), any()))
        .thenReturn(List.of(firstScore, tiedScore, thirdScore));
    DailyGamesService service = service(profiles, games, boards, memberships, submissions, adapters, billing);

    var combined = service.board(new CurrentUser(firstId, ""), board.id, "all", game.slug).combinedRankings();
    assertEquals(List.of(1, 1, 3), combined.stream().map(ApiModels.RankingView::rank).toList());
    assertEquals(List.of(new BigDecimal("100.0000"), new BigDecimal("100.0000"), new BigDecimal("64.0000")),
        combined.stream().map(ApiModels.RankingView::leaderboardPoints).toList());
  }

  @Test
  void discordGameSearchRanksMatchesAndLimitsLargeCatalogs() {
    UUID ownerId = UUID.randomUUID();
    Leaderboard board = new Leaderboard(ownerId, "All games", null, BoardType.MULTI_GAME,
        BoardCreationBasis.SUBSCRIPTION, Instant.now());
    LeaderboardRepository boards = mock(LeaderboardRepository.class);
    GameRepository games = mock(GameRepository.class);
    GameAdapterRegistry adapters = mock(GameAdapterRegistry.class);
    BillingService billing = mock(BillingService.class);
    when(boards.findById(board.id)).thenReturn(Optional.of(board));
    when(billing.isBoardAccessible(eq(board), any())).thenReturn(true);
    List<Game> active = new ArrayList<>();
    for (int i = 0; i < 130; i++) {
      Game game = new Game(); game.id = UUID.randomUUID(); game.slug = "new-game-" + i;
      game.name = "New Game " + i; game.active = true;
      active.add(game);
      GameAdapter adapter = mock(GameAdapter.class);
      when(adapters.require(game.slug)).thenReturn(adapter);
      when(adapters.supportsShareText(game.slug)).thenReturn(i % 2 == 0);
    }
    Game partial = game("a-fermi-game", "A Fermi Game", "higher");
    Game prefix = game("fermi-clone", "Fermi Clone", "higher");
    Game exact = game("fermi", "Zed Exact", "higher");
    active.addAll(List.of(partial, prefix, exact));
    for (Game game : List.of(partial, prefix, exact)) {
      when(adapters.require(game.slug)).thenReturn(mock(GameAdapter.class));
      when(adapters.supportsShareText(game.slug)).thenReturn(true);
    }
    when(games.findByActiveTrueOrderByNameAsc()).thenReturn(active);
    DailyGamesService service = service(mock(ProfileRepository.class), games, boards,
        mock(MembershipRepository.class), mock(SubmissionRepository.class), adapters, billing);
    List<ApiModels.GameView> emptySuggestions = service.searchDiscordGames(board.id, "");
    assertEquals(25, emptySuggestions.size());
    assertEquals("a-fermi-game", emptySuggestions.getFirst().slug());
    assertEquals(25, service.searchDiscordGames(board.id, "new", true).size());
    assertEquals("new-game-129", service.searchDiscordGames(board.id, "NEW-GAME-129").getFirst().slug());
    assertEquals(List.of("fermi", "fermi-clone", "a-fermi-game"),
        service.searchDiscordGames(board.id, "FERMI").stream().map(ApiModels.GameView::slug).toList());
    when(billing.isBoardAccessible(eq(board), any())).thenReturn(false);
    assertEquals("OWNER_SUBSCRIPTION_REQUIRED", assertThrows(ApiException.class,
        () -> service.searchDiscordGames(board.id, "new")).code);
  }

  @Test
  void privateDiscordGameSearchRequiresLeaderboardMembership() {
    UUID userId = UUID.randomUUID(), boardId = UUID.randomUUID();
    MembershipRepository memberships = mock(MembershipRepository.class);
    DailyGamesService service = service(mock(ProfileRepository.class), mock(GameRepository.class),
        mock(LeaderboardRepository.class), memberships, mock(SubmissionRepository.class),
        mock(GameAdapterRegistry.class), mock(BillingService.class));

    assertEquals("NOT_FOUND", assertThrows(ApiException.class,
        () -> service.searchDiscordGames(userId, boardId, "fermi", true)).code);
    verify(memberships).existsByIdLeaderboardIdAndIdUserId(boardId, userId);
  }
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
    Submission existing = new Submission(); existing.id = UUID.randomUUID(); existing.rawText = "different share";
    when(submissions.findByUserIdAndGameIdAndPuzzleKey(userId, game.id, "1961")).thenReturn(Optional.of(existing));
    String share = "Wordle 1,961 2/6\n⬛⬛⬛⬛⬛\n🟩🟩🟩🟩🟩";
    ParsedSubmission parsed = new WordleParser().parse(share);
    when(adapters.validateResult(eq("wordle"), any(), eq(share))).thenReturn(parsed);
    DailyGamesService service = service(profiles, games, mock(LeaderboardRepository.class),
        mock(MembershipRepository.class), submissions, adapters, mock(BillingService.class));
    var confirmed = new ApiModels.ConfirmedSubmission(parsed.puzzleKey(), parsed.puzzleNumber(), parsed.playedOn(),
        parsed.primaryScore(), parsed.metrics(), parsed.parserVersion());
    assertEquals("DUPLICATE_SUBMISSION", assertThrows(ApiException.class,
        () -> service.submit(new CurrentUser(userId, ""), new ApiModels.CreateSubmissionRequest("wordle", share, confirmed))).code);
    existing.rawText = share;
    assertEquals(existing.id, service.submit(new CurrentUser(userId, ""),
        new ApiModels.CreateSubmissionRequest("wordle", share, confirmed)));
  }

  @Test
  void chatShareSavesToProfileWithoutAnyBoardAndPreservesDuplicateAndParseErrors() {
    UUID userId = UUID.randomUUID();
    String share = "Wordle 1,972 2/6\n⬛⬛⬛⬛⬛\n🟩🟩🟩🟩🟩";
    Profile profile = new Profile(userId, "Player", "#dfff4f", Instant.now());
    Game wordle = new Game(); wordle.id = UUID.randomUUID(); wordle.slug = "wordle";
    wordle.name = "Wordle"; wordle.active = true;
    ProfileRepository profiles = mock(ProfileRepository.class);
    GameRepository games = mock(GameRepository.class);
    LeaderboardRepository boards = mock(LeaderboardRepository.class);
    MembershipRepository memberships = mock(MembershipRepository.class);
    SubmissionRepository submissions = mock(SubmissionRepository.class);
    BillingService billing = mock(BillingService.class);
    when(profiles.findById(userId)).thenReturn(Optional.of(profile));
    when(profiles.findByIdForUpdate(userId)).thenReturn(Optional.of(profile));
    when(games.findBySlugAndActiveTrue("wordle")).thenReturn(Optional.of(wordle));
    when(submissions.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    DailyGamesService service = service(profiles, games, boards, memberships, submissions,
        new GameAdapterRegistry(List.of(new WordleParser())), billing);
    var saved = service.submitShare(userId, null, share);
    assertEquals("wordle", saved.game().slug);
    Submission existing = new Submission(userId, wordle.id, saved.submission(), "different share", Instant.now());
    when(submissions.findByUserIdAndGameIdAndPuzzleKey(userId, wordle.id, "1972"))
        .thenReturn(Optional.of(existing));
    assertEquals("DUPLICATE_SUBMISSION", assertThrows(ApiException.class,
        () -> service.submitShare(userId, null, share)).code);
    assertEquals("GAME_UNDETECTED", assertThrows(ApiException.class,
        () -> service.submitShare(userId, null, "invalid share")).code);
    verifyNoInteractions(boards, memberships, billing);
  }
  @Test
  void ranksUnassistedLinkedInResultsBeforeFasterAssistedResults() {
    UUID ownerId = UUID.randomUUID(), twinId = UUID.randomUUID(), friendId = UUID.randomUUID();
    Profile owner = new Profile(ownerId, "Owner", "#dfff4f", Instant.now());
    Profile twin = new Profile(twinId, "Twin", "#a8c7ff", Instant.now());
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
    when(adapter.rankingMetric(any(), anyMap())).thenAnswer(invocation -> invocation.getArgument(0));
    when(adapters.require("queens")).thenReturn(adapter);
    when(boards.findById(board.id)).thenReturn(Optional.of(board));
    when(games.findById(game.id)).thenReturn(Optional.of(game));
    when(memberships.existsByIdLeaderboardIdAndIdUserId(board.id, ownerId)).thenReturn(true);
    when(memberships.findByIdLeaderboardId(board.id)).thenReturn(List.of(
        new Membership(board.id, ownerId, ownerId, Instant.now()),
        new Membership(board.id, twinId, ownerId, Instant.now()),
        new Membership(board.id, friendId, ownerId, Instant.now())));
    when(profiles.findById(ownerId)).thenReturn(Optional.of(owner));
    when(profiles.findById(twinId)).thenReturn(Optional.of(twin));
    when(profiles.findById(friendId)).thenReturn(Optional.of(friend));
    when(billing.isBoardAccessible(eq(board), any())).thenReturn(true);
    Submission slow = new Submission(ownerId, game.id, new ParsedSubmission("1", 1, LocalDate.now(),
        BigDecimal.valueOf(80), Map.of("hints", 0, "mistakes", 0, "beginnerAssists", 0), 1), "share", Instant.now());
    slow.submissionMethod = SubmissionMethod.MANUAL;
    Submission fastAssisted = new Submission(friendId, game.id, new ParsedSubmission("1", 1, LocalDate.now(),
        BigDecimal.valueOf(20), Map.of("hints", 1, "mistakes", 0, "beginnerAssists", 0), 1), "share", Instant.now());
    Submission equallySlow = new Submission(twinId, game.id, new ParsedSubmission("1", 1, LocalDate.now(),
        BigDecimal.valueOf(80), Map.of("hints", 0, "mistakes", 0, "beginnerAssists", 0), 1), "share", Instant.now());
    when(submissions.findByUserIdInAndGameId(any(), eq(game.id))).thenReturn(List.of(slow, equallySlow, fastAssisted));
    DailyGamesService service = service(profiles, games, boards, memberships, submissions, adapters, billing);
    var rankings = service.board(new CurrentUser(ownerId, ""), board.id, "all").rankings();
    assertEquals(ownerId, rankings.get(0).userId());
    assertEquals(1, rankings.get(0).rank());
    assertEquals(twinId, rankings.get(1).userId());
    assertEquals(1, rankings.get(1).rank());
    assertEquals(friendId, rankings.get(2).userId());
    assertEquals(3, rankings.get(2).rank());
    assertEquals(1, rankings.get(0).manualResults());
    assertEquals(1, rankings.get(2).assistedResults());
    assertEquals(1, rankings.get(2).hints());
    verify(profiles).findAllById(any());
  }
  @Test
  void letterBagRanksHigherPointsFirstAndSharesExactScoreTies() {
    UUID ownerId = UUID.randomUUID(), twinId = UUID.randomUUID(), lowerId = UUID.randomUUID();
    Profile owner = new Profile(ownerId, "Owner", "#dfff4f", Instant.now());
    Profile twin = new Profile(twinId, "Twin", "#a8c7ff", Instant.now());
    Profile lower = new Profile(lowerId, "Lower", "#ff8db6", Instant.now());
    Game game = new Game(); game.id = UUID.randomUUID(); game.slug = "letter-bag"; game.name = "Letter Bag";
    game.active = true; game.releaseTimezone = "UTC"; game.rankingDirection = "higher";
    Leaderboard board = new Leaderboard(ownerId, "Letter Bag club", game.id, BoardCreationBasis.FREE, Instant.now());
    ProfileRepository profiles = mock(ProfileRepository.class);
    GameRepository games = mock(GameRepository.class);
    LeaderboardRepository boards = mock(LeaderboardRepository.class);
    MembershipRepository memberships = mock(MembershipRepository.class);
    SubmissionRepository submissions = mock(SubmissionRepository.class);
    GameAdapterRegistry adapters = mock(GameAdapterRegistry.class);
    BillingService billing = mock(BillingService.class);
    GameAdapter adapter = mock(GameAdapter.class);
    when(adapter.scoreFormat()).thenReturn(new ApiModels.ScoreFormatView("", "", 0, 0, "points"));
    when(adapter.rankingMetric(any(), anyMap())).thenAnswer(invocation -> invocation.getArgument(0));
    when(adapters.require("letter-bag")).thenReturn(adapter);
    when(boards.findById(board.id)).thenReturn(Optional.of(board));
    when(games.findById(game.id)).thenReturn(Optional.of(game));
    when(memberships.existsByIdLeaderboardIdAndIdUserId(board.id, ownerId)).thenReturn(true);
    when(memberships.findByIdLeaderboardId(board.id)).thenReturn(List.of(
        new Membership(board.id, ownerId, ownerId, Instant.now()),
        new Membership(board.id, twinId, ownerId, Instant.now()),
        new Membership(board.id, lowerId, ownerId, Instant.now())));
    when(profiles.findById(ownerId)).thenReturn(Optional.of(owner));
    when(profiles.findById(twinId)).thenReturn(Optional.of(twin));
    when(profiles.findById(lowerId)).thenReturn(Optional.of(lower));
    when(billing.isBoardAccessible(eq(board), any())).thenReturn(true);
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    Submission first = new Submission(ownerId, game.id, new ParsedSubmission(today.toString(), null, today,
        BigDecimal.valueOf(16), Map.of("wordCount", 3), 1), "Letter Bag", Instant.now());
    Submission tie = new Submission(twinId, game.id, new ParsedSubmission(today.toString(), null, today,
        BigDecimal.valueOf(16), Map.of("wordCount", 4), 1), "Letter Bag", Instant.now());
    Submission lowerScore = new Submission(lowerId, game.id, new ParsedSubmission(today.toString(), null, today,
        BigDecimal.valueOf(9), Map.of("wordCount", 2), 1), "Letter Bag", Instant.now());
    when(submissions.findByUserIdInAndGameId(any(), eq(game.id))).thenReturn(List.of(first, tie, lowerScore));
    DailyGamesService service = service(profiles, games, boards, memberships, submissions, adapters, billing);
    var rankings = service.board(new CurrentUser(ownerId, ""), board.id, "all").rankings();
    assertEquals(List.of(1, 1, 3), rankings.stream().map(ApiModels.RankingView::rank).toList());
    assertEquals(List.of(BigDecimal.valueOf(16).setScale(4), BigDecimal.valueOf(16).setScale(4),
        BigDecimal.valueOf(9).setScale(4)), rankings.stream().map(ApiModels.RankingView::average).toList());
  }
  @Test
  void insightsRankEachDaySeparatelyAndOnlyExposeViewerScores() {
    UUID ownerId = UUID.randomUUID(), friendId = UUID.randomUUID(), twinId = UUID.randomUUID();
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
    when(adapter.rankingMetric(any(), anyMap())).thenAnswer(invocation -> invocation.getArgument(0));
    when(adapters.require("queens")).thenReturn(adapter);
    when(boards.findById(board.id)).thenReturn(Optional.of(board));
    when(games.findById(game.id)).thenReturn(Optional.of(game));
    when(billing.isBoardAccessible(eq(board), any())).thenReturn(true);
    when(memberships.existsByIdLeaderboardIdAndIdUserId(board.id, ownerId)).thenReturn(true);
    when(memberships.findByIdLeaderboardId(board.id)).thenReturn(List.of(
        new Membership(board.id, ownerId, ownerId, Instant.now()),
        new Membership(board.id, friendId, ownerId, Instant.now()),
        new Membership(board.id, twinId, ownerId, Instant.now())));
    when(profiles.findById(ownerId)).thenReturn(Optional.of(new Profile(ownerId, "Owner", "#dfff4f", Instant.now())));
    when(profiles.findById(friendId)).thenReturn(Optional.of(new Profile(friendId, "Friend", "#ff8db6", Instant.now())));
    when(profiles.findById(twinId)).thenReturn(Optional.of(new Profile(twinId, "Twin", "#a8c7ff", Instant.now())));
    Submission ownerOld = new Submission(ownerId, game.id, new ParsedSubmission("old", 1, today.minusDays(29),
        BigDecimal.valueOf(80), Map.of(), 1), "share", Instant.now());
    Submission ownerNew = new Submission(ownerId, game.id, new ParsedSubmission("new", 2, today,
        BigDecimal.valueOf(80), Map.of(), 1), "share", Instant.now());
    Submission friendYesterday = new Submission(friendId, game.id, new ParsedSubmission("yesterday", 1, today.minusDays(1),
        BigDecimal.valueOf(1000), Map.of(), 1), "share", Instant.now());
    Submission friendNew = new Submission(friendId, game.id, new ParsedSubmission("new", 2, today,
        BigDecimal.valueOf(10), Map.of(), 1), "share", Instant.now());
    Submission twinNew = new Submission(twinId, game.id, new ParsedSubmission("new", 2, today,
        BigDecimal.valueOf(10), Map.of(), 1), "share", Instant.now());
    List<Submission> history = List.of(ownerOld, ownerNew, friendYesterday, friendNew, twinNew);
    when(submissions.findByUserIdInAndGameIdAndPlayedOnBetween(any(), eq(game.id), any(), any()))
        .thenAnswer(invocation -> {
          LocalDate from = invocation.getArgument(2), to = invocation.getArgument(3);
          return history.stream().filter(score -> !score.playedOn.isBefore(from) && !score.playedOn.isAfter(to)).toList();
        });
    DailyGamesService service = service(profiles, games, boards, memberships, submissions, adapters, billing);
    var weekly = service.board(new CurrentUser(ownerId, ""), board.id, "7d").rankings();
    var monthly = service.board(new CurrentUser(ownerId, ""), board.id, "30d").rankings();
    var weeklyOwner = weekly.stream().filter(row -> row.userId().equals(ownerId)).findFirst().orElseThrow();
    var monthlyOwner = monthly.stream().filter(row -> row.userId().equals(ownerId)).findFirst().orElseThrow();
    var monthlyFriend = monthly.stream().filter(row -> row.userId().equals(friendId)).findFirst().orElseThrow();
    var monthlyTwin = monthly.stream().filter(row -> row.userId().equals(twinId)).findFirst().orElseThrow();
    assertEquals(0, new BigDecimal("3").compareTo(weeklyOwner.averagePlacement()));
    assertEquals(0, new BigDecimal("2").compareTo(monthlyOwner.averagePlacement()));
    assertEquals(0, new BigDecimal("80").compareTo(monthlyOwner.averageScore()));
    assertEquals(0, new BigDecimal("505").compareTo(monthlyFriend.averageScore()));
    assertEquals(0, monthlyFriend.averagePlacement().compareTo(monthlyTwin.averagePlacement()));
    assertEquals(2, monthlyOwner.gamesPlayed());
    assertEquals(1, monthlyTwin.gamesPlayed());
    assertEquals(List.of(twinId, ownerId, friendId), monthly.stream().map(ApiModels.RankingView::userId).toList());
    var insights = service.insights(new CurrentUser(ownerId, ""), board.id, "queens");
    verify(profiles, times(3)).findAllById(any());
    assertEquals(30, insights.ranks().getFirst().points().size());
    assertEquals(1, insights.ranks().getFirst().points().getFirst().rank());
    assertNull(insights.ranks().getFirst().points().get(28).rank());
    assertEquals(3, insights.ranks().getFirst().points().getLast().rank());
    assertEquals(1, insights.ranks().get(1).points().get(28).rank());
    assertEquals(1, insights.ranks().get(1).points().getLast().rank());
    assertEquals(1, insights.ranks().get(2).points().getLast().rank());
    verify(submissions, times(2)).findByUserIdInAndGameIdAndPlayedOnBetween(
        any(), eq(game.id), eq(today.minusDays(29)), eq(today));
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
  void discordRequiresMultiGameBoardAndDetectsGame() {
    UUID userId = UUID.randomUUID();
    Profile profile = new Profile(userId, "Player", "#dfff4f", Instant.now());
    Game fermi = new Game();
    fermi.id = UUID.randomUUID(); fermi.slug = "fermi"; fermi.name = "Fermi"; fermi.active = true;
    Leaderboard board = new Leaderboard(userId, "All Games", null, BoardType.MULTI_GAME,
        BoardCreationBasis.SUBSCRIPTION, Instant.now());
    Leaderboard single = new Leaderboard(userId, "Fermi", fermi.id, BoardCreationBasis.FREE, Instant.now());
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
    when(boards.findById(single.id)).thenReturn(Optional.of(single));
    when(memberships.existsByIdLeaderboardIdAndIdUserId(board.id, userId)).thenReturn(true);
    when(memberships.existsByIdLeaderboardIdAndIdUserId(single.id, userId)).thenReturn(true);
    when(billing.isBoardAccessible(eq(board), any())).thenReturn(true);
    when(billing.isBoardAccessible(eq(single), any())).thenReturn(true);
    when(games.findById(fermi.id)).thenReturn(Optional.of(fermi));
    when(games.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(krillion));
    ParsedSubmission fermiParsed = new ParsedSubmission("92", 92, LocalDate.of(2026, 11, 4),
        new BigDecimal("1.31"), Map.of(), 1);
    ParsedSubmission parsed = new ParsedSubmission("82", 82, LocalDate.of(2026, 11, 4),
        new BigDecimal("700"), Map.of(), 1);
    when(adapters.parseResult("fermi", "Fermi share")).thenReturn(fermiParsed);
    when(adapters.parseResult("krillion", "Krillion share")).thenReturn(parsed);
    when(submissions.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    DailyGamesService service = service(profiles, games, boards, memberships, submissions, adapters, billing);

    ApiException singleGame = assertThrows(ApiException.class,
        () -> service.submitFromDiscord(userId, single.id, "fermi", "Fermi share"));
    assertEquals("MULTI_GAME_REQUIRED", singleGame.code);

    ApiException unknown = assertThrows(ApiException.class,
        () -> service.submitFromDiscord(userId, board.id, "Unknown share"));
    assertEquals("GAME_UNDETECTED", unknown.code);

    when(adapters.detectGame("Krillion share")).thenReturn(Optional.of("krillion"));
    DailyGamesService.DiscordSubmissionResult saved =
        service.submitFromDiscord(userId, board.id, "Krillion share");
    assertEquals("krillion", saved.game().slug);
    verify(adapters, never()).parseResult("fermi", "Fermi share");
    verify(adapters).detectGame("Krillion share");
    verify(adapters).parseResult("krillion", "Krillion share");
  }

  @Test
  void boardViewEnforcesMembershipLockAndGameForSingleAndMultiGameBoards() {
    UUID userId = UUID.randomUUID();
    Profile profile = new Profile(userId, "Player", "#dfff4f", Instant.now());
    Game fermi = new Game();
    fermi.id = UUID.randomUUID(); fermi.slug = "fermi"; fermi.name = "Fermi";
    fermi.active = true; fermi.releaseTimezone = "UTC"; fermi.rankingDirection = "lower";
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
    SubmissionRepository submissions = mock(SubmissionRepository.class);
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
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    Submission recent = new Submission(userId, krillion.id, new ParsedSubmission("recent", 1, today,
        BigDecimal.TEN, Map.of(), 1), "share", Instant.now());
    Submission previous = new Submission(userId, fermi.id, new ParsedSubmission("previous", 1,
        today.minusDays(1), BigDecimal.ONE, Map.of(), 1), "share", Instant.now().minusSeconds(60));
    when(submissions.findFirstByUserIdInAndGameIdInOrderByPlayedOnDescCreatedAtDesc(any(), any()))
        .thenReturn(Optional.of(recent));
    when(submissions.findByUserIdInAndGameIdInAndPlayedOnBetween(any(), any(), eq(today.minusDays(29)), eq(today)))
        .thenReturn(List.of(previous, recent));
    DailyGamesService service = service(profiles, games, boards, memberships,
        submissions, adapters, billing);
    CurrentUser current = new CurrentUser(userId, "");

    assertEquals("fermi", service.board(current, single.id, "today", "fermi").game().slug());
    assertEquals("krillion", service.board(current, multi.id, "7d", "krillion").game().slug());
    var recentBoard = service.board(current, multi.id, "today");
    assertEquals("krillion", recentBoard.game().slug());
    assertEquals(List.of("krillion", "fermi"), recentBoard.recentGameSlugs());
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

  @Test
  void todayBoardIncludesVerifiedGameDetailsButLongerRangesDoNot() {
    UUID userId = UUID.randomUUID();
    Profile profile = new Profile(userId, "Player", "#dfff4f", Instant.now());
    Game game = game("fermi", "Fermi", "lower");
    game.releaseTimezone = "UTC";
    Leaderboard board = new Leaderboard(userId, "Fermi board", game.id, BoardCreationBasis.FREE, Instant.now());
    ProfileRepository profiles = mock(ProfileRepository.class);
    GameRepository games = mock(GameRepository.class);
    LeaderboardRepository boards = mock(LeaderboardRepository.class);
    MembershipRepository memberships = mock(MembershipRepository.class);
    SubmissionRepository submissions = mock(SubmissionRepository.class);
    BillingService billing = mock(BillingService.class);
    when(boards.findById(board.id)).thenReturn(Optional.of(board));
    when(billing.isBoardAccessible(eq(board), any())).thenReturn(true);
    when(games.findById(game.id)).thenReturn(Optional.of(game));
    when(profiles.findById(userId)).thenReturn(Optional.of(profile));
    when(memberships.existsByIdLeaderboardIdAndIdUserId(board.id, userId)).thenReturn(true);
    when(memberships.findByIdLeaderboardId(board.id))
        .thenReturn(List.of(new Membership(board.id, userId, userId, Instant.now())));
    LocalDate today = LocalDate.now(ZoneId.of("UTC"));
    Submission score = new Submission(userId, game.id, new ParsedSubmission("7", 7, today,
        new BigDecimal("1.50"), Map.of("completionStatus", "WIN", "components",
        List.of(new BigDecimal("1.20"), new BigDecimal("1.40"), new BigDecimal("2.10"))), 1),
        "Fermi share", Instant.now());
    when(submissions.findByUserIdInAndGameIdAndPlayedOnBetween(any(), eq(game.id), any(), any()))
        .thenReturn(List.of(score));
    DailyGamesService service = service(profiles, games, boards, memberships, submissions,
        new GameAdapterRegistry(List.of(new FermiParser())), billing);
    CurrentUser current = new CurrentUser(userId, "");

    ApiModels.LeaderboardResponse todayBoard = service.board(current, board.id, "today", "fermi");
    assertEquals(List.of(new ApiModels.DailyResultDetailsView(userId, "7", List.of(
        new ApiModels.ResultDetailView("Q1", "1.20×"),
        new ApiModels.ResultDetailView("Q2", "1.40×"),
        new ApiModels.ResultDetailView("Q3", "2.10×")))), todayBoard.dailyResults());
    assertTrue(service.board(current, board.id, "7d", "fermi").dailyResults().isEmpty());
  }

  @Test
  void memberScoresUseBoardRankingPeriodAndNewestFirstOrdering() {
    UUID viewerId = UUID.randomUUID(), memberId = UUID.randomUUID();
    Profile viewer = new Profile(viewerId, "Viewer", "#dfff4f", Instant.now());
    Profile member = new Profile(memberId, "Member", "#ff8db6", Instant.now());
    Game game = game("fermi", "Fermi", "lower");
    game.releaseTimezone = "America/Los_Angeles";
    Leaderboard board = new Leaderboard(viewerId, "Friends", game.id, BoardCreationBasis.FREE, Instant.now());
    ProfileRepository profiles = mock(ProfileRepository.class);
    GameRepository games = mock(GameRepository.class);
    LeaderboardRepository boards = mock(LeaderboardRepository.class);
    MembershipRepository memberships = mock(MembershipRepository.class);
    SubmissionRepository submissions = mock(SubmissionRepository.class);
    BillingService billing = mock(BillingService.class);
    GameAdapterRegistry adapters = mock(GameAdapterRegistry.class);
    GameAdapter adapter = mock(GameAdapter.class);
    when(adapter.scoreFormat()).thenReturn(new ApiModels.ScoreFormatView("", "", 0, 2));
    when(adapter.rankingMetric(any(), anyMap())).thenAnswer(invocation -> invocation.getArgument(0));
    when(adapters.require(game.slug)).thenReturn(adapter);
    when(boards.findById(board.id)).thenReturn(Optional.of(board));
    when(games.findById(game.id)).thenReturn(Optional.of(game));
    when(billing.isBoardAccessible(eq(board), any())).thenReturn(true);
    when(memberships.existsByIdLeaderboardIdAndIdUserId(board.id, viewerId)).thenReturn(true);
    when(memberships.findByIdLeaderboardId(board.id)).thenReturn(List.of(
        new Membership(board.id, viewerId, viewerId, Instant.now()),
        new Membership(board.id, memberId, viewerId, Instant.now())));
    when(profiles.findById(viewerId)).thenReturn(Optional.of(viewer));
    when(profiles.findById(memberId)).thenReturn(Optional.of(member));
    LocalDate today = LocalDate.now(ZoneId.of(game.releaseTimezone));
    Submission older = new Submission(memberId, game.id, new ParsedSubmission("old", 1, today.minusDays(2),
        BigDecimal.valueOf(20), Map.of(), 1), "share", Instant.now().minusSeconds(10));
    older.submissionMethod = SubmissionMethod.MANUAL;
    Submission newest = new Submission(memberId, game.id, new ParsedSubmission("new", 2, today,
        BigDecimal.valueOf(10), Map.of("hints", 1), 1), "share", Instant.now());
    Submission viewerScore = new Submission(viewerId, game.id, new ParsedSubmission("viewer", 3, today,
        BigDecimal.valueOf(30), Map.of(), 1), "share", Instant.now());
    when(submissions.findByUserIdInAndGameIdAndPlayedOnBetween(any(), eq(game.id), any(), any()))
        .thenReturn(List.of(older, viewerScore, newest));
    DailyGamesService service = service(profiles, games, boards, memberships, submissions, adapters, billing);
    CurrentUser current = new CurrentUser(viewerId, "");

    var boardView = service.board(current, board.id, "7d", game.slug);
    var details = service.memberScores(current, board.id, memberId, "7d", game.slug);

    assertEquals(boardView.rankings().stream().filter(value -> value.userId().equals(memberId)).findFirst().orElseThrow(),
        details.ranking());
    assertEquals(boardView.rankings().size(), details.rankedPlayerCount());
    assertEquals(List.of("new", "old"), details.scores().stream().map(ApiModels.ScoreView::puzzleKey).toList());
    assertEquals(1, details.ranking().assistedResults());
    assertEquals(1, details.ranking().manualResults());
    assertEquals("7d", details.period());
    verify(submissions, times(2)).findByUserIdInAndGameIdAndPlayedOnBetween(any(), eq(game.id),
        eq(today.minusDays(6)), eq(today));

    when(submissions.findByUserIdInAndGameIdAndPlayedOnBetween(any(), eq(game.id), any(), any()))
        .thenReturn(List.of(viewerScore));
    var empty = service.memberScores(current, board.id, memberId, "today", game.slug);
    assertNull(empty.ranking());
    assertTrue(empty.scores().isEmpty());
    assertEquals(1, empty.rankedPlayerCount());
  }

  @Test
  void memberScoresEnforceViewerTargetLockGameAndRange() {
    UUID viewerId = UUID.randomUUID(), memberId = UUID.randomUUID(), outsiderId = UUID.randomUUID();
    Game game = game("fermi", "Fermi", "lower");
    Leaderboard board = new Leaderboard(viewerId, "Friends", game.id, BoardCreationBasis.FREE, Instant.now());
    ProfileRepository profiles = mock(ProfileRepository.class);
    GameRepository games = mock(GameRepository.class);
    LeaderboardRepository boards = mock(LeaderboardRepository.class);
    MembershipRepository memberships = mock(MembershipRepository.class);
    BillingService billing = mock(BillingService.class);
    GameAdapterRegistry adapters = mock(GameAdapterRegistry.class);
    GameAdapter adapter = mock(GameAdapter.class);
    when(adapter.scoreFormat()).thenReturn(new ApiModels.ScoreFormatView("", "", 0, 2));
    when(adapters.require(game.slug)).thenReturn(adapter);
    when(boards.findById(board.id)).thenReturn(Optional.of(board));
    when(games.findById(game.id)).thenReturn(Optional.of(game));
    when(billing.isBoardAccessible(eq(board), any())).thenReturn(true);
    when(memberships.existsByIdLeaderboardIdAndIdUserId(board.id, viewerId)).thenReturn(true);
    when(memberships.findByIdLeaderboardId(board.id)).thenReturn(List.of(
        new Membership(board.id, viewerId, viewerId, Instant.now()),
        new Membership(board.id, memberId, viewerId, Instant.now())));
    when(profiles.findById(viewerId)).thenReturn(Optional.of(new Profile(viewerId, "Viewer", "#dfff4f", Instant.now())));
    when(profiles.findById(memberId)).thenReturn(Optional.of(new Profile(memberId, "Member", "#ff8db6", Instant.now())));
    DailyGamesService service = service(profiles, games, boards, memberships, mock(SubmissionRepository.class), adapters, billing);
    CurrentUser current = new CurrentUser(viewerId, "");

    assertEquals("NOT_FOUND", assertThrows(ApiException.class,
        () -> service.memberScores(current, board.id, outsiderId, "today", game.slug)).code);
    assertEquals("GAME_NOT_ALLOWED", assertThrows(ApiException.class,
        () -> service.memberScores(current, board.id, memberId, "today", "krillion")).code);
    assertEquals("INVALID_RANGE", assertThrows(ApiException.class,
        () -> service.memberScores(current, board.id, memberId, "week", game.slug)).code);
    when(memberships.existsByIdLeaderboardIdAndIdUserId(board.id, viewerId)).thenReturn(false);
    assertEquals("NOT_FOUND", assertThrows(ApiException.class,
        () -> service.memberScores(current, board.id, memberId, "today", game.slug)).code);
    when(memberships.existsByIdLeaderboardIdAndIdUserId(board.id, viewerId)).thenReturn(true);
    when(billing.isBoardAccessible(eq(board), any())).thenReturn(false);
    assertEquals("OWNER_SUBSCRIPTION_REQUIRED", assertThrows(ApiException.class,
        () -> service.memberScores(current, board.id, memberId, "today", game.slug)).code);
  }

  @Test
  void multiGameBoardsUsePerPuzzlePlacementsForCombinedPoints() {
    UUID ownerId = UUID.randomUUID(), friendId = UUID.randomUUID();
    Profile owner = new Profile(ownerId, "Owner", "#dfff4f", Instant.now());
    Profile friend = new Profile(friendId, "Friend", "#ff8db6", Instant.now());
    Game quordle = game("quordle", "Quordle", "lower");
    Game timeguessr = game("timeguessr", "TimeGuessr", "higher");
    Leaderboard board = new Leaderboard(ownerId, "All games", null, BoardType.MULTI_GAME,
        BoardCreationBasis.SUBSCRIPTION, Instant.now());
    ProfileRepository profiles = mock(ProfileRepository.class);
    GameRepository games = mock(GameRepository.class);
    LeaderboardRepository boards = mock(LeaderboardRepository.class);
    MembershipRepository memberships = mock(MembershipRepository.class);
    SubmissionRepository submissions = mock(SubmissionRepository.class);
    BillingService billing = mock(BillingService.class);
    GameAdapterRegistry adapters = new GameAdapterRegistry(List.of(new QuordleParser(), new TimeGuessrParser()));
    when(boards.findById(board.id)).thenReturn(Optional.of(board));
    when(games.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(quordle, timeguessr));
    when(memberships.existsByIdLeaderboardIdAndIdUserId(board.id, ownerId)).thenReturn(true);
    when(memberships.findByIdLeaderboardId(board.id)).thenReturn(List.of(
        new Membership(board.id, ownerId, ownerId, Instant.now()),
        new Membership(board.id, friendId, ownerId, Instant.now())));
    when(profiles.findById(ownerId)).thenReturn(Optional.of(owner));
    when(profiles.findById(friendId)).thenReturn(Optional.of(friend));
    when(billing.isBoardAccessible(eq(board), any())).thenReturn(true);
    Submission ownerQ = submission(ownerId, quordle.id, adapters.parseResult("quordle",
        "Daily Quordle 1746\n5️⃣9️⃣\n7️⃣8️⃣\nm-w.com/games/quordle/"));
    Submission friendQ = submission(friendId, quordle.id, adapters.parseResult("quordle",
        "Daily Quordle 1746\n6️⃣9️⃣\n7️⃣8️⃣\nm-w.com/games/quordle/"));
    Submission ownerT = submission(ownerId, timeguessr.id, adapters.parseResult("timeguessr",
        "TimeGuessr #807 — 40,000/50,000"));
    Submission friendT = submission(friendId, timeguessr.id, adapters.parseResult("timeguessr",
        "TimeGuessr #807 — 45,000/50,000"));
    when(submissions.findByUserIdInAndGameId(any(), eq(quordle.id))).thenReturn(List.of(ownerQ, friendQ));
    when(submissions.findByUserIdInAndGameIdIn(any(), any())).thenReturn(List.of(ownerQ, friendQ, ownerT, friendT));
    DailyGamesService service = service(profiles, games, boards, memberships, submissions, adapters, billing);

    var combined = service.board(new CurrentUser(ownerId, ""), board.id, "all", "quordle").combinedRankings();
    assertEquals(2, combined.size());
    assertEquals(1, combined.get(0).rank());
    assertEquals(1, combined.get(1).rank());
    assertEquals(new BigDecimal("90.0000"), combined.get(0).leaderboardPoints());
    assertEquals(2, combined.get(0).participationPoints());
  }

  @Test
  void incompletePokedokuReceivesCombinedPointsForItsMetricsFirstPlacement() {
    UUID ownerId = UUID.randomUUID(), friendId = UUID.randomUUID();
    Profile owner = new Profile(ownerId, "Owner", "#dfff4f", Instant.now());
    Profile friend = new Profile(friendId, "Friend", "#ff8db6", Instant.now());
    Game pokedoku = game("pokedoku", "PokeDoku", "lower");
    Leaderboard board = new Leaderboard(ownerId, "All games", null, BoardType.MULTI_GAME,
        BoardCreationBasis.SUBSCRIPTION, Instant.now());
    ProfileRepository profiles = mock(ProfileRepository.class);
    GameRepository games = mock(GameRepository.class);
    LeaderboardRepository boards = mock(LeaderboardRepository.class);
    MembershipRepository memberships = mock(MembershipRepository.class);
    SubmissionRepository submissions = mock(SubmissionRepository.class);
    BillingService billing = mock(BillingService.class);
    GameAdapterRegistry adapters = new GameAdapterRegistry(List.of());
    when(boards.findById(board.id)).thenReturn(Optional.of(board));
    when(games.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(pokedoku));
    when(memberships.existsByIdLeaderboardIdAndIdUserId(board.id, ownerId)).thenReturn(true);
    when(memberships.findByIdLeaderboardId(board.id)).thenReturn(List.of(
        new Membership(board.id, ownerId, ownerId, Instant.now()),
        new Membership(board.id, friendId, ownerId, Instant.now())));
    when(profiles.findById(ownerId)).thenReturn(Optional.of(owner));
    when(profiles.findById(friendId)).thenReturn(Optional.of(friend));
    when(billing.isBoardAccessible(eq(board), any())).thenReturn(true);
    LocalDate date = LocalDate.of(2026, 11, 5);
    ParsedSubmission incomplete = adapters.normalizeManualResult("pokedoku", new ApiModels.ManualSubmissionRequest(
        "pokedoku", null, date, "LOSS", Map.of("uniquenessScore", 10, "correctCells", 8)));
    ParsedSubmission completed = adapters.normalizeManualResult("pokedoku", new ApiModels.ManualSubmissionRequest(
        "pokedoku", null, date, "WIN", Map.of("uniquenessScore", 20, "correctCells", 9)));
    Submission ownerScore = submission(ownerId, pokedoku.id, incomplete);
    Submission friendScore = submission(friendId, pokedoku.id, completed);
    when(submissions.findByUserIdInAndGameId(any(), eq(pokedoku.id))).thenReturn(List.of(ownerScore, friendScore));
    when(submissions.findByUserIdInAndGameIdIn(any(), any())).thenReturn(List.of(ownerScore, friendScore));
    DailyGamesService service = service(profiles, games, boards, memberships, submissions, adapters, billing);

    var combined = service.board(new CurrentUser(ownerId, ""), board.id, "all", "pokedoku").combinedRankings();
    assertEquals(ownerId, combined.getFirst().userId());
    assertEquals(new BigDecimal("100.0000"), combined.getFirst().leaderboardPoints());
    assertEquals(new BigDecimal("80.0000"), combined.get(1).leaderboardPoints());
  }

  @Test
  void fermiShareResultsBeatHigherManualScoresInTheSameContests() {
    UUID sharePlayerId = UUID.randomUUID(), manualPlayerId = UUID.randomUUID();
    Profile sharePlayer = new Profile(sharePlayerId, "Share Player", "#dfff4f", Instant.now());
    Profile manualPlayer = new Profile(manualPlayerId, "Manual Player", "#ff8db6", Instant.now());
    Game fermi = game("fermi", "Fermi", "lower");
    Leaderboard board = new Leaderboard(sharePlayerId, "Fermi Friends", fermi.id,
        BoardCreationBasis.FREE, Instant.now());
    ProfileRepository profiles = mock(ProfileRepository.class);
    GameRepository games = mock(GameRepository.class);
    LeaderboardRepository boards = mock(LeaderboardRepository.class);
    MembershipRepository memberships = mock(MembershipRepository.class);
    SubmissionRepository submissions = mock(SubmissionRepository.class);
    BillingService billing = mock(BillingService.class);
    GameAdapterRegistry adapters = new GameAdapterRegistry(List.of(new FermiParser()));
    when(boards.findById(board.id)).thenReturn(Optional.of(board));
    when(games.findById(fermi.id)).thenReturn(Optional.of(fermi));
    when(memberships.existsByIdLeaderboardIdAndIdUserId(board.id, sharePlayerId)).thenReturn(true);
    when(memberships.findByIdLeaderboardId(board.id)).thenReturn(List.of(
        new Membership(board.id, sharePlayerId, sharePlayerId, Instant.now()),
        new Membership(board.id, manualPlayerId, sharePlayerId, Instant.now())));
    when(profiles.findById(sharePlayerId)).thenReturn(Optional.of(sharePlayer));
    when(profiles.findById(manualPlayerId)).thenReturn(Optional.of(manualPlayer));
    when(billing.isBoardAccessible(eq(board), any())).thenReturn(true);

    List<ParsedSubmission> shared = List.of(
        adapters.parseResult("fermi", "Fermi #99\n4.06x score"),
        adapters.parseResult("fermi", "Fermi #100\n3.71x score"),
        adapters.parseResult("fermi", "Fermi #101\n2.25x score"),
        adapters.parseResult("fermi", "Fermi #102\n9.76x score"));
    List<ParsedSubmission> manual = List.of(
        adapters.normalizeManualResult("fermi", new ApiModels.ManualSubmissionRequest(
            "fermi", "101", LocalDate.of(2026, 11, 4), "WIN", Map.of("score", "6.32"))),
        adapters.normalizeManualResult("fermi", new ApiModels.ManualSubmissionRequest(
            "fermi", "102", LocalDate.of(2026, 11, 5), "WIN", Map.of("score", "18.60"))));
    List<Submission> scores = new ArrayList<>();
    shared.forEach(value -> scores.add(submission(sharePlayerId, fermi.id, value)));
    manual.forEach(value -> scores.add(new Submission(manualPlayerId, fermi.id, value, "manual",
        SubmissionMethod.MANUAL, Instant.now())));
    when(submissions.findByUserIdInAndGameId(any(), eq(fermi.id))).thenReturn(scores);

    for (int index = 0; index < manual.size(); index++) {
      ParsedSubmission sharedContest = shared.get(index + 2);
      ParsedSubmission manualContest = manual.get(index);
      NormalizedScore sharedScore = adapters.normalizedScore("fermi", sharedContest.primaryScore(),
          sharedContest.metrics(), sharedContest.puzzleKey(), sharedContest.playedOn(),
          sharedContest.parserVersion(), "lower");
      NormalizedScore manualScore = adapters.normalizedScore("fermi", manualContest.primaryScore(),
          manualContest.metrics(), manualContest.puzzleKey(), manualContest.playedOn(),
          manualContest.parserVersion(), "lower");
      assertTrue(adapters.compareScores(sharedScore, manualScore) < 0);
    }

    DailyGamesService service = service(profiles, games, boards, memberships, submissions, adapters, billing);
    List<ApiModels.RankingView> rankings = service.board(
        new CurrentUser(sharePlayerId, ""), board.id, "all").rankings();
    assertEquals(sharePlayerId, rankings.getFirst().userId());
    assertEquals(1, rankings.getFirst().rank());
    assertEquals(new BigDecimal("100.0000"), rankings.getFirst().leaderboardPoints());
    assertEquals(manualPlayerId, rankings.get(1).userId());
    assertEquals(new BigDecimal("80.0000"), rankings.get(1).leaderboardPoints());
  }

  private Game game(String slug, String name, String direction) {
    Game game = new Game(); game.id = UUID.randomUUID(); game.slug = slug; game.name = name;
    game.sourceUrl = "https://example.com"; game.releaseTimezone = "UTC";
    game.rankingDirection = direction; game.active = true;
    return game;
  }

  @Test
  void manualSubmissionPersistsProvenanceAndRetriesIdempotently() {
    UUID userId = UUID.randomUUID();
    Profile profile = new Profile(userId, "Manual Player", "#dfff4f", Instant.now());
    Game game = game("wordle", "Wordle", "lower");
    ProfileRepository profiles = mock(ProfileRepository.class);
    GameRepository games = mock(GameRepository.class);
    SubmissionRepository submissions = mock(SubmissionRepository.class);
    when(profiles.findById(userId)).thenReturn(Optional.of(profile));
    when(profiles.findByIdForUpdate(userId)).thenReturn(Optional.of(profile));
    when(games.findBySlugAndActiveTrue("wordle")).thenReturn(Optional.of(game));
    when(submissions.findByUserIdAndGameIdAndPuzzleKey(userId, game.id, "2000")).thenReturn(Optional.empty());
    when(submissions.findByUserIdAndGameIdAndPlayedOn(eq(userId), eq(game.id), any())).thenReturn(Optional.empty());
    when(submissions.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    DailyGamesService service = service(profiles, games, mock(LeaderboardRepository.class),
        mock(MembershipRepository.class), submissions, new GameAdapterRegistry(List.of(new WordleParser())), mock(BillingService.class));
    LocalDate date = LocalDate.now(ZoneOffset.UTC);
    var request = new ApiModels.ManualSubmissionRequest("wordle", "2000", date, "WIN", Map.of("attempts", 4));

    UUID id = service.submitManual(new CurrentUser(userId, ""), request);
    var captor = org.mockito.ArgumentCaptor.forClass(Submission.class);
    verify(submissions).saveAndFlush(captor.capture());
    Submission saved = captor.getValue();
    assertEquals(id, saved.id);
    assertEquals(SubmissionMethod.MANUAL, saved.submissionMethod);
    assertEquals("4/6", saved.metrics.get("displayScore"));
    assertTrue(saved.rawText.contains("\"puzzleDate\":\"" + date + "\""));

    when(submissions.findByUserIdAndGameIdAndPuzzleKey(userId, game.id, "2000")).thenReturn(Optional.of(saved));
    assertEquals(id, service.submitManual(new CurrentUser(userId, ""), request));
    verify(submissions, times(1)).saveAndFlush(any());
  }

  @Test
  void manualSubmissionRejectsFutureDatesAndSameDayDuplicates() {
    UUID userId = UUID.randomUUID();
    Profile profile = new Profile(userId, "Manual Player", "#dfff4f", Instant.now());
    Game game = game("globle", "Globle", "lower");
    ProfileRepository profiles = mock(ProfileRepository.class);
    GameRepository games = mock(GameRepository.class);
    SubmissionRepository submissions = mock(SubmissionRepository.class);
    when(profiles.findById(userId)).thenReturn(Optional.of(profile));
    when(profiles.findByIdForUpdate(userId)).thenReturn(Optional.of(profile));
    when(games.findBySlugAndActiveTrue("globle")).thenReturn(Optional.of(game));
    DailyGamesService service = service(profiles, games, mock(LeaderboardRepository.class),
        mock(MembershipRepository.class), submissions, new GameAdapterRegistry(List.of(new GlobleParser())), mock(BillingService.class));
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    var future = new ApiModels.ManualSubmissionRequest("globle", null, today.plusDays(1), "WIN", Map.of("attempts", 4));
    assertEquals("INVALID_MANUAL_RESULT", assertThrows(ApiException.class,
        () -> service.submitManual(new CurrentUser(userId, ""), future)).code);

    Submission existing = new Submission(userId, game.id,
        new ParsedSubmission("different-id", null, today, BigDecimal.valueOf(3), Map.of(), 1), "share", Instant.now());
    when(submissions.findByUserIdAndGameIdAndPuzzleKey(userId, game.id, "new-id")).thenReturn(Optional.empty());
    when(submissions.findByUserIdAndGameIdAndPlayedOn(userId, game.id, today)).thenReturn(Optional.of(existing));
    var duplicate = new ApiModels.ManualSubmissionRequest("globle", "new-id", today, "WIN", Map.of("attempts", 4));
    assertEquals("DUPLICATE_SUBMISSION", assertThrows(ApiException.class,
        () -> service.submitManual(new CurrentUser(userId, ""), duplicate)).code);
  }

  private Submission submission(UUID userId, UUID gameId, ParsedSubmission parsed) {
    return new Submission(userId, gameId, parsed, "share", Instant.now());
  }

  private DailyGamesService service(ProfileRepository profiles, GameRepository games, LeaderboardRepository boards,
                                    MembershipRepository memberships, SubmissionRepository submissions,
                                    GameAdapterRegistry adapters, BillingService billing) {
    when(profiles.findAllById(any())).thenAnswer(invocation -> {
      Iterable<UUID> ids = invocation.getArgument(0);
      List<Profile> found = new ArrayList<>();
      ids.forEach(id -> profiles.findById(id).ifPresent(found::add));
      return found;
    });
    return new DailyGamesService(mock(UserRepository.class), profiles, games, boards, memberships,
        submissions, adapters, billing);
  }

  @Test void todaySummaryFetchesOnceAndKeepsTiedFirstPlace() {
    UUID a = UUID.randomUUID(), b = UUID.randomUUID();
    Profile alpha = new Profile(a, "Alpha", "#aaaaaa", Instant.now());
    Profile bravo = new Profile(b, "Bravo", "#bbbbbb", Instant.now());
    Game game = game("timeguessr", "TimeGuessr", "higher");
    Leaderboard board = new Leaderboard(a, "Friends", null, BoardType.MULTI_GAME,
        BoardCreationBasis.SUBSCRIPTION, Instant.now());
    ProfileRepository profiles = mock(ProfileRepository.class);
    GameRepository games = mock(GameRepository.class);
    LeaderboardRepository boards = mock(LeaderboardRepository.class);
    MembershipRepository members = mock(MembershipRepository.class);
    SubmissionRepository submissions = mock(SubmissionRepository.class);
    BillingService billing = mock(BillingService.class);
    GameAdapterRegistry adapters = new GameAdapterRegistry(List.of(new TimeGuessrParser()));
    when(boards.findById(board.id)).thenReturn(Optional.of(board));
    when(games.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(game));
    when(members.existsByIdLeaderboardIdAndIdUserId(board.id, a)).thenReturn(true);
    when(members.findByIdLeaderboardId(board.id)).thenReturn(List.of(
        new Membership(board.id, a, a, Instant.now()), new Membership(board.id, b, a, Instant.now())));
    when(profiles.findById(a)).thenReturn(Optional.of(alpha));
    when(profiles.findById(b)).thenReturn(Optional.of(bravo));
    when(billing.isBoardAccessible(eq(board), any())).thenReturn(true);
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    var parsed = adapters.parseResult("timeguessr", "TimeGuessr #807 — 45,000/50,000");
    var current = new ParsedSubmission(parsed.puzzleKey(), parsed.puzzleNumber(), today,
        parsed.primaryScore(), parsed.metrics(), parsed.parserVersion());
    when(submissions.findByUserIdInAndGameIdInAndPlayedOnBetween(any(), any(), eq(today), eq(today)))
        .thenReturn(List.of(submission(a, game.id, current), submission(b, game.id, current)));
    var summary = service(profiles, games, boards, members, submissions, adapters, billing)
        .todaySummary(new CurrentUser(a, ""), board.id);
    assertEquals(List.of("Alpha", "Bravo"), summary.leaders().stream()
        .map(ApiModels.RankingView::displayName).toList());
    assertEquals(2, summary.games().getFirst().leaders().size());
    verify(submissions, times(1)).findByUserIdInAndGameIdInAndPlayedOnBetween(any(), any(), eq(today), eq(today));
  }

}
