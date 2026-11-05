package com.dailygames;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LeaderboardInviteServiceTest {
  private final LeaderboardRepository boards = mock(LeaderboardRepository.class);
  private final LeaderboardInviteRepository invites = mock(LeaderboardInviteRepository.class);
  private final MembershipRepository members = mock(MembershipRepository.class);
  private final ProfileRepository profiles = mock(ProfileRepository.class);
  private final BillingService billing = mock(BillingService.class);
  private final LeaderboardInviteService service = new LeaderboardInviteService(
      boards, invites, members, profiles, billing, "https://dailytracker.cc", "test-secret");
  private final UUID ownerId = UUID.randomUUID();
  private final Leaderboard board = new Leaderboard(ownerId, "Friends", UUID.randomUUID(),
      BoardCreationBasis.FREE, Instant.now());

  @Test void onlyOwnerCanCreateOrManageLink() {
    when(boards.findByIdForUpdate(board.id)).thenReturn(Optional.of(board));
    assertEquals("NOT_OWNER", assertThrows(ApiException.class,
        () -> service.create(board.id, UUID.randomUUID())).code);
    assertEquals("NOT_OWNER", assertThrows(ApiException.class,
        () -> service.regenerate(board.id, UUID.randomUUID())).code);
    assertEquals("NOT_OWNER", assertThrows(ApiException.class,
        () -> service.revoke(board.id, UUID.randomUUID())).code);
    verifyNoInteractions(invites);
  }

  @Test void signedLinkCanBeRedeemedRepeatedlyAndTamperingIsRejected() {
    when(boards.findByIdForUpdate(board.id)).thenReturn(Optional.of(board));
    when(boards.findById(board.id)).thenReturn(Optional.of(board));
    when(billing.isBoardAccessible(eq(board), any())).thenReturn(true);
    when(profiles.existsById(any())).thenReturn(true);
    when(invites.findById(board.id)).thenAnswer(call -> Optional.empty());
    LeaderboardInviteService.InviteLink link = service.create(board.id, ownerId);
    String token = link.url().substring(link.url().indexOf("invite=") + 7);
    LeaderboardInvite invite = new LeaderboardInvite(board.id, Instant.now());
    invite.inviteId = UUID.fromString(token.substring(0, 36));
    when(invites.findByInviteId(invite.inviteId)).thenReturn(Optional.of(invite));
    UUID memberId = UUID.randomUUID();
    assertEquals("Friends", service.preview(token).name());
    assertEquals(board.id, service.redeem(token, memberId));
    assertEquals(board.id, service.redeem(token, memberId));
    verify(members, times(2)).insertIfAbsent(eq(board.id), eq(memberId), eq(ownerId), any());
    int signatureStart = token.indexOf('.') + 1;
    String tampered = token.substring(0, signatureStart)
        + (token.charAt(signatureStart) == 'A' ? "B" : "A") + token.substring(signatureStart + 1);
    assertEquals("INVITE_INVALID", assertThrows(ApiException.class, () -> service.preview(tampered)).code);
    invite.active = false;
    assertEquals("INVITE_REVOKED", assertThrows(ApiException.class, () -> service.preview(token)).code);
  }

  @Test void lockedOrDeletedLeaderboardCannotBeJoined() {
    LeaderboardInvite invite = new LeaderboardInvite(board.id, Instant.now());
    when(boards.findByIdForUpdate(board.id)).thenReturn(Optional.of(board));
    when(billing.isBoardAccessible(eq(board), any())).thenReturn(false);
    assertEquals("OWNER_SUBSCRIPTION_REQUIRED", assertThrows(ApiException.class,
        () -> service.create(board.id, ownerId)).code);
    when(billing.isBoardAccessible(eq(board), any())).thenReturn(true);
    String token = service.create(board.id, ownerId).url().split("invite=")[1];
    invite.inviteId = UUID.fromString(token.substring(0, 36));
    when(invites.findByInviteId(invite.inviteId)).thenReturn(Optional.of(invite));
    assertEquals("LEADERBOARD_NOT_FOUND", assertThrows(ApiException.class,
        () -> service.preview(token)).code);
  }
}
