package com.dailygames;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LeaderboardDeletionServiceTest {
  @Mock LeaderboardRepository boards;
  @Mock DiscordSignupIntentRepository signupIntents;
  @Mock DiscordCommandRegistrationStore registrations;
  LeaderboardDeletionService service;

  @BeforeEach void setUp() {
    service = new LeaderboardDeletionService(boards, signupIntents, registrations);
  }

  @Test void ownerDeletesBoardBeforeDiscordRegistrationsArePruned() {
    UUID ownerId = UUID.randomUUID();
    Leaderboard board = new Leaderboard(ownerId, "Friends", UUID.randomUUID(), BoardCreationBasis.FREE, Instant.now());
    when(boards.findByIdForUpdate(board.id)).thenReturn(Optional.of(board));

    service.delete(board.id, ownerId);

    InOrder order = inOrder(signupIntents, boards, registrations);
    order.verify(signupIntents).deleteByLeaderboardId(board.id);
    order.verify(boards).delete(board);
    order.verify(boards).flush();
    order.verify(registrations).pruneUnmappedGuilds();
  }

  @Test void nonOwnerCannotDeleteBoard() {
    Leaderboard board = new Leaderboard(UUID.randomUUID(), "Friends", UUID.randomUUID(), BoardCreationBasis.FREE, Instant.now());
    when(boards.findByIdForUpdate(board.id)).thenReturn(Optional.of(board));

    ApiException exception = assertThrows(ApiException.class, () -> service.delete(board.id, UUID.randomUUID()));

    assertEquals(403, exception.status);
    assertEquals("NOT_OWNER", exception.code);
    verifyNoInteractions(signupIntents, registrations);
    verify(boards, never()).delete(any());
  }

  @Test void missingBoardReturnsNotFound() {
    UUID boardId = UUID.randomUUID();
    when(boards.findByIdForUpdate(boardId)).thenReturn(Optional.empty());

    ApiException exception = assertThrows(ApiException.class, () -> service.delete(boardId, UUID.randomUUID()));

    assertEquals(404, exception.status);
    assertEquals("LEADERBOARD_NOT_FOUND", exception.code);
  }
}
