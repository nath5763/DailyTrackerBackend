package com.dailygames;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BillingServiceTest {
  private final BillingAccountRepository accounts = mock(BillingAccountRepository.class);
  private final ProfileRepository profiles = mock(ProfileRepository.class);
  private final LeaderboardRepository boards = mock(LeaderboardRepository.class);
  private final BillingService service = new BillingService(accounts, mock(ProcessedStripeEventRepository.class),
      profiles, boards, mock(UserRepository.class), "", "", "http://localhost:3000");
  private final UUID ownerId = UUID.randomUUID();
  private final Instant now = Instant.parse("2026-10-31T12:00:00Z");

  @Test void freeBoardsStayAccessibleWithoutBilling() {
    Leaderboard board = board(BoardCreationBasis.FREE);
    assertTrue(service.isBoardAccessible(board, now));
    verifyNoInteractions(accounts);
  }

  @Test void freeLeaderboardAvailabilityEndsAtFive() {
    when(profiles.findById(ownerId)).thenReturn(Optional.of(new Profile(ownerId, "Owner", "#dfff4f", now)));
    when(boards.countByOwnerIdAndCreationBasis(ownerId, BoardCreationBasis.FREE)).thenReturn(4L, 5L);

    assertTrue(service.status(ownerId).freeLeaderboardAvailable());
    assertFalse(service.status(ownerId).freeLeaderboardAvailable());
  }

  @Test void grandfatheredBoardsLockOnlyAfterAFormerSubscriberLosesAccess() {
    Leaderboard board = board(BoardCreationBasis.GRANDFATHERED);
    when(accounts.findById(ownerId)).thenReturn(Optional.empty());
    assertTrue(service.isBoardAccessible(board, now));

    BillingAccount canceled = new BillingAccount(ownerId, now.minusSeconds(100));
    canceled.everSubscribedAt = now.minusSeconds(50);
    canceled.subscriptionStatus = "canceled";
    when(accounts.findById(ownerId)).thenReturn(Optional.of(canceled));
    assertFalse(service.isBoardAccessible(board, now));
  }

  @Test void subscriptionBoardsHonorTheSevenDayPaymentGracePeriod() {
    Leaderboard board = board(BoardCreationBasis.SUBSCRIPTION);
    BillingAccount account = new BillingAccount(ownerId, now.minusSeconds(100));
    account.subscriptionStatus = "past_due";
    account.graceUntil = now.plusSeconds(60);
    when(accounts.findById(ownerId)).thenReturn(Optional.of(account));
    assertTrue(service.isBoardAccessible(board, now));

    account.graceUntil = now.minusSeconds(1);
    assertFalse(service.isBoardAccessible(board, now));
  }

  private Leaderboard board(BoardCreationBasis basis) {
    return new Leaderboard(ownerId, "Friends", UUID.randomUUID(), basis, now.minusSeconds(100));
  }
}
