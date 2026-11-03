package com.dailygames;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
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
}
