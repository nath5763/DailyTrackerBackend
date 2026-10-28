package com.dailygames;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Repository interface UserRepository extends JpaRepository<UserAccount, UUID> { Optional<UserAccount> findByNormalizedEmail(String email); }
@Repository interface ProfileRepository extends JpaRepository<Profile, UUID> { Optional<Profile> findByShareId(UUID shareId); }
@Repository interface GameRepository extends JpaRepository<Game, UUID> { Optional<Game> findBySlugAndActiveTrue(String slug); }
@Repository interface LeaderboardRepository extends JpaRepository<Leaderboard, UUID> {
  @Query("select l from Leaderboard l where l.id in (select m.id.leaderboardId from Membership m where m.id.userId = :userId) order by l.createdAt")
  List<Leaderboard> findForUser(@Param("userId") UUID userId);
}
@Repository interface MembershipRepository extends JpaRepository<Membership, MembershipId> {
  List<Membership> findByIdLeaderboardId(UUID leaderboardId);
  boolean existsByIdLeaderboardIdAndIdUserId(UUID leaderboardId, UUID userId);
  @Query("select count(mine) > 0 from Membership mine, Membership theirs where mine.id.leaderboardId = theirs.id.leaderboardId and mine.id.userId = :viewer and theirs.id.userId = :other")
  boolean sharesLeaderboard(@Param("viewer") UUID viewer, @Param("other") UUID other);
}
@Repository interface SubmissionRepository extends JpaRepository<Submission, UUID> {
  List<Submission> findTop30ByUserIdOrderByPlayedOnDescCreatedAtDesc(UUID userId);
  List<Submission> findByUserIdOrderByPlayedOnDescCreatedAtDesc(UUID userId);
  List<Submission> findByUserIdInAndGameIdAndPlayedOnBetween(Collection<UUID> userIds, UUID gameId, LocalDate from, LocalDate to);
  List<Submission> findByUserIdInAndGameId(Collection<UUID> userIds, UUID gameId);
  boolean existsByUserIdAndGameIdAndPuzzleKey(UUID userId, UUID gameId, String puzzleKey);
}
@Repository interface MagicLinkRepository extends JpaRepository<MagicLink, UUID> {
  long countByNormalizedEmailAndCreatedAtAfter(String email, Instant after);
  long countByRequestIpHashAndCreatedAtAfter(String ipHash, Instant after);
  Optional<MagicLink> findTopByNormalizedEmailOrderByCreatedAtDesc(String email);
  @Lock(LockModeType.PESSIMISTIC_WRITE) Optional<MagicLink> findByTokenHash(String tokenHash);
  @Modifying @Query("update MagicLink m set m.consumedAt = :now where m.normalizedEmail = :email and m.consumedAt is null")
  int consumeOutstanding(@Param("email") String email, @Param("now") Instant now);
  void deleteByExpiresAtBefore(Instant before);
}
@Repository interface AuthSessionRepository extends JpaRepository<AuthSession, UUID> {
  Optional<AuthSession> findByTokenHashAndExpiresAtAfter(String tokenHash, Instant now);
  void deleteByTokenHash(String tokenHash);
  void deleteByExpiresAtBefore(Instant before);
}
