package com.dailygames;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Service
class LeaderboardInviteService {
  record InviteLink(boolean active, String url) {}
  record InvitePreview(UUID leaderboardId, String name) {}

  private final LeaderboardRepository boards;
  private final LeaderboardInviteRepository invites;
  private final MembershipRepository members;
  private final ProfileRepository profiles;
  private final BillingService billing;
  private final String baseUrl;
  private final byte[] secret;

  LeaderboardInviteService(LeaderboardRepository boards, LeaderboardInviteRepository invites,
      MembershipRepository members, ProfileRepository profiles, BillingService billing,
      @Value("${app.public-base-url}") String baseUrl, @Value("${app.token-pepper}") String pepper) {
    this.boards = boards; this.invites = invites; this.members = members; this.profiles = profiles;
    this.billing = billing; this.baseUrl = baseUrl.replaceAll("/+$", "");
    this.secret = pepper.getBytes(StandardCharsets.UTF_8);
  }

  InviteLink get(UUID boardId, UUID ownerId) {
    owned(boardId, ownerId, false, false);
    return invites.findById(boardId).filter(invite -> invite.active)
        .map(invite -> new InviteLink(true, url(invite.inviteId))).orElse(new InviteLink(false, null));
  }

  @Transactional
  InviteLink create(UUID boardId, UUID ownerId) {
    owned(boardId, ownerId, true, true);
    LeaderboardInvite invite = invites.findById(boardId).orElse(null);
    if (invite == null) invite = new LeaderboardInvite(boardId, Instant.now());
    else if (!invite.active) { invite.inviteId = UUID.randomUUID(); invite.active = true; invite.createdAt = Instant.now(); }
    invites.save(invite);
    return new InviteLink(true, url(invite.inviteId));
  }

  @Transactional
  InviteLink regenerate(UUID boardId, UUID ownerId) {
    owned(boardId, ownerId, true, true);
    LeaderboardInvite invite = invites.findById(boardId).orElse(null);
    if (invite == null) invite = new LeaderboardInvite(boardId, Instant.now());
    else { invite.inviteId = UUID.randomUUID(); invite.active = true; invite.createdAt = Instant.now(); }
    invites.save(invite);
    return new InviteLink(true, url(invite.inviteId));
  }

  @Transactional
  void revoke(UUID boardId, UUID ownerId) {
    owned(boardId, ownerId, false, true);
    invites.findById(boardId).ifPresent(invite -> { invite.active = false; invites.save(invite); });
  }

  InvitePreview preview(String token) {
    LeaderboardInvite invite = validInvite(token);
    Leaderboard board = boards.findById(invite.leaderboardId)
        .orElseThrow(() -> new ApiException(404, "LEADERBOARD_NOT_FOUND", "That leaderboard no longer exists."));
    accessible(board);
    return new InvitePreview(board.id, board.name);
  }

  @Transactional
  UUID redeem(String token, UUID userId) {
    LeaderboardInvite invite = validInvite(token);
    return redeemBoard(invite.leaderboardId, userId);
  }

  String shareableBoardName(UUID boardId, UUID ownerId) {
    return owned(boardId, ownerId, true, false).name;
  }

  UUID redeemBoard(UUID boardId, UUID userId) {
    Leaderboard board = boards.findById(boardId)
        .orElseThrow(() -> new ApiException(404, "LEADERBOARD_NOT_FOUND", "That leaderboard no longer exists."));
    accessible(board);
    if (!profiles.existsById(userId)) throw new ApiException(409, "PROFILE_REQUIRED", "Finish setting up your profile first.");
    members.insertIfAbsent(board.id, userId, board.ownerId, Instant.now());
    return board.id;
  }

  private Leaderboard owned(UUID boardId, UUID ownerId, boolean requireAccessible, boolean lock) {
    Leaderboard board = (lock ? boards.findByIdForUpdate(boardId) : boards.findById(boardId))
        .orElseThrow(() -> new ApiException(404, "LEADERBOARD_NOT_FOUND", "That leaderboard no longer exists."));
    if (!board.ownerId.equals(ownerId)) throw new ApiException(403, "NOT_OWNER", "Only the leaderboard owner can manage its link.");
    if (requireAccessible) accessible(board);
    return board;
  }

  private void accessible(Leaderboard board) {
    if (!billing.isBoardAccessible(board, Instant.now()))
      throw new ApiException(402, "OWNER_SUBSCRIPTION_REQUIRED", "This leaderboard is locked until its owner restores access.");
  }

  private LeaderboardInvite validInvite(String token) {
    if (token == null || !token.matches("[0-9a-fA-F-]{36}\\.[A-Za-z0-9_-]{43}")) throw invalid();
    String[] parts = token.split("\\.", 2);
    UUID id;
    try { id = UUID.fromString(parts[0]); } catch (IllegalArgumentException error) { throw invalid(); }
    byte[] provided;
    try { provided = Base64.getUrlDecoder().decode(parts[1]); } catch (IllegalArgumentException error) { throw invalid(); }
    if (!MessageDigest.isEqual(provided, signature(id))) throw invalid();
    LeaderboardInvite invite = invites.findByInviteId(id).orElseThrow(this::invalid);
    if (!invite.active) throw new ApiException(410, "INVITE_REVOKED", "This invitation was revoked. Ask the owner for a new link.");
    return invite;
  }

  private ApiException invalid() { return new ApiException(404, "INVITE_INVALID", "This invitation is invalid, was revoked, or its leaderboard was deleted."); }
  private String url(UUID id) { return baseUrl + "/join?invite=" + id + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature(id)); }
  private byte[] signature(UUID id) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      return mac.doFinal(("leaderboard-invite:" + id).getBytes(StandardCharsets.UTF_8));
    } catch (Exception error) { throw new IllegalStateException("Cannot sign invitation", error); }
  }
}
