package com.dailygames;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Entity @Table(name = "app_users")
class UserAccount {
  @Id UUID id;
  @Column(name = "normalized_email", nullable = false, unique = true) String normalizedEmail;
  @Column(name = "created_at", nullable = false) Instant createdAt;
  @Column(name = "last_login_at", nullable = false) Instant lastLoginAt;
  protected UserAccount() {}
  UserAccount(String email, Instant now) { id = UUID.randomUUID(); normalizedEmail = email; createdAt = now; lastLoginAt = now; }
}

@Entity @Table(name = "profiles")
class Profile {
  @Id @Column(name = "user_id") UUID userId;
  @Column(name = "share_id", nullable = false, unique = true) UUID shareId;
  @Column(name = "display_name", nullable = false) String displayName;
  @Column(name = "avatar_color", nullable = false) String avatarColor;
  @Column(name = "created_at", nullable = false) Instant createdAt;
  @Column(name = "updated_at", nullable = false) Instant updatedAt;
  protected Profile() {}
  Profile(UUID userId, String displayName, String avatarColor, Instant now) {
    this.userId = userId; this.shareId = UUID.randomUUID(); this.displayName = displayName;
    this.avatarColor = avatarColor; this.createdAt = now; this.updatedAt = now;
  }
}

@Entity @Table(name = "games")
class Game {
  @Id UUID id;
  @Column(nullable = false, unique = true) String slug;
  @Column(nullable = false) String name;
  @Column(name = "source_url", nullable = false) String sourceUrl;
  @Column(name = "release_timezone", nullable = false) String releaseTimezone;
  @Column(name = "ranking_direction", nullable = false) String rankingDirection;
  @Column(nullable = false) boolean active;
  @Column(name = "created_at", nullable = false) Instant createdAt;
  protected Game() {}
}

@Entity @Table(name = "leaderboards")
class Leaderboard {
  @Id UUID id;
  @Column(name = "owner_id", nullable = false) UUID ownerId;
  @Column(nullable = false) String name;
  @Column(name = "created_at", nullable = false) Instant createdAt;
  protected Leaderboard() {}
  Leaderboard(UUID ownerId, String name, Instant now) { id = UUID.randomUUID(); this.ownerId = ownerId; this.name = name; createdAt = now; }
}

@Embeddable
class MembershipId implements Serializable {
  @Column(name = "leaderboard_id") UUID leaderboardId;
  @Column(name = "user_id") UUID userId;
  protected MembershipId() {}
  MembershipId(UUID leaderboardId, UUID userId) { this.leaderboardId = leaderboardId; this.userId = userId; }
  @Override public boolean equals(Object value) { return value instanceof MembershipId other && Objects.equals(leaderboardId, other.leaderboardId) && Objects.equals(userId, other.userId); }
  @Override public int hashCode() { return Objects.hash(leaderboardId, userId); }
}

@Entity @Table(name = "leaderboard_members")
class Membership {
  @EmbeddedId MembershipId id;
  @Column(name = "added_by", nullable = false) UUID addedBy;
  @Column(name = "created_at", nullable = false) Instant createdAt;
  protected Membership() {}
  Membership(UUID leaderboardId, UUID userId, UUID addedBy, Instant now) { id = new MembershipId(leaderboardId, userId); this.addedBy = addedBy; createdAt = now; }
}

@Entity @Table(name = "submissions")
class Submission {
  @Id UUID id;
  @Column(name = "user_id", nullable = false) UUID userId;
  @Column(name = "game_id", nullable = false) UUID gameId;
  @Column(name = "puzzle_key", nullable = false) String puzzleKey;
  @Column(name = "played_on", nullable = false) LocalDate playedOn;
  @Column(name = "primary_score", nullable = false) BigDecimal primaryScore;
  @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") Map<String, Object> metrics = new LinkedHashMap<>();
  @Column(name = "raw_text", nullable = false) String rawText;
  @Column(name = "parser_version", nullable = false) int parserVersion;
  @Column(name = "created_at", nullable = false) Instant createdAt;
  protected Submission() {}
  Submission(UUID userId, UUID gameId, FermiParser.Parsed value, String rawText, Instant now) {
    id = UUID.randomUUID(); this.userId = userId; this.gameId = gameId; puzzleKey = value.puzzleKey();
    playedOn = value.playedOn(); primaryScore = value.primaryScore(); metrics = value.metrics();
    this.rawText = rawText; parserVersion = value.parserVersion(); createdAt = now;
  }
}

@Entity @Table(name = "auth_magic_links")
class MagicLink {
  @Id UUID id;
  @Column(name = "normalized_email", nullable = false) String normalizedEmail;
  @Column(name = "token_hash", nullable = false, unique = true, length = 64) String tokenHash;
  @Column(name = "request_ip_hash", nullable = false, length = 64) String requestIpHash;
  @Column(name = "created_at", nullable = false) Instant createdAt;
  @Column(name = "expires_at", nullable = false) Instant expiresAt;
  @Column(name = "consumed_at") Instant consumedAt;
  protected MagicLink() {}
  MagicLink(String email, String tokenHash, String ipHash, Instant now, Instant expiresAt) {
    id = UUID.randomUUID(); normalizedEmail = email; this.tokenHash = tokenHash; requestIpHash = ipHash;
    createdAt = now; this.expiresAt = expiresAt;
  }
}

@Entity @Table(name = "auth_sessions")
class AuthSession {
  @Id UUID id;
  @Column(name = "user_id", nullable = false) UUID userId;
  @Column(name = "token_hash", nullable = false, unique = true, length = 64) String tokenHash;
  @Column(name = "created_at", nullable = false) Instant createdAt;
  @Column(name = "expires_at", nullable = false) Instant expiresAt;
  @Column(name = "last_seen_at", nullable = false) Instant lastSeenAt;
  protected AuthSession() {}
  AuthSession(UUID userId, String tokenHash, Instant now, Instant expiresAt) {
    id = UUID.randomUUID(); this.userId = userId; this.tokenHash = tokenHash; createdAt = now; this.expiresAt = expiresAt; lastSeenAt = now;
  }
}
