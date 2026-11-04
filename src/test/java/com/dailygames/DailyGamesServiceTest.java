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
    when(boards.countByOwnerIdAndCreationBasis(ownerId, BoardCreationBasis.FREE))
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
    assertEquals(402, exception.status);
    assertEquals("SUBSCRIPTION_REQUIRED", exception.code);
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
    when(billing.hasUnlimitedLeaderboards(eq(ownerId), any())).thenReturn(true);
    when(boards.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    DailyGamesService service = service(profiles, mock(GameRepository.class), boards, memberships,
        mock(SubmissionRepository.class), mock(GameAdapterRegistry.class), billing);

    UUID id = service.createBoard(new CurrentUser(ownerId, "owner@example.com"),
        new CreateLeaderboardRequest("All Games", null, BoardType.MULTI_GAME));

    assertNotNull(id);
    verify(boards, never()).countByOwnerIdAndCreationBasis(any(), any());
    verify(boards).save(argThat(board -> board.boardType == BoardType.MULTI_GAME && board.gameId == null
        && board.creationBasis == BoardCreationBasis.SUBSCRIPTION));
    verify(memberships).save(argThat(member -> member.id.userId.equals(ownerId)));

    reset(boards, memberships);
    when(billing.hasUnlimitedLeaderboards(eq(ownerId), any())).thenReturn(false);
    ApiException exception = assertThrows(ApiException.class, () -> service.createBoard(
        new CurrentUser(ownerId, "owner@example.com"),
        new CreateLeaderboardRequest("All Games", null, BoardType.MULTI_GAME)));
    assertEquals("SUBSCRIPTION_REQUIRED", exception.code);
    verify(boards, never()).save(any());
  }

  @Test
  void conversionIsOwnerOnlyIdempotentAndRestoresTheFreeBasis() {
    UUID ownerId = UUID.randomUUID();
    Leaderboard board = new Leaderboard(ownerId, "Friends", UUID.randomUUID(), BoardCreationBasis.FREE, Instant.now());
    LeaderboardRepository boards = mock(LeaderboardRepository.class);
    MembershipRepository memberships = mock(MembershipRepository.class);
    BillingService billing = mock(BillingService.class);
    when(boards.findById(board.id)).thenReturn(Optional.of(board));
    when(billing.hasUnlimitedLeaderboards(eq(ownerId), any())).thenReturn(true);
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
    when(billing.hasUnlimitedLeaderboards(eq(ownerId), any())).thenReturn(false);
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
    when(billing.isBoardAccessible(eq(multi), any())).thenReturn(false);
    assertEquals("OWNER_SUBSCRIPTION_REQUIRED", assertThrows(ApiException.class,
        () -> service.board(current, multi.id, "today", "fermi")).code);
  }

  private DailyGamesService service(ProfileRepository profiles, GameRepository games, LeaderboardRepository boards,
                                    MembershipRepository memberships, SubmissionRepository submissions,
                                    GameAdapterRegistry adapters, BillingService billing) {
    return new DailyGamesService(mock(UserRepository.class), profiles, games, boards, memberships,
        submissions, adapters, billing);
  }
}
