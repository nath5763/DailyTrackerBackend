package com.dailygames;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Repository interface UserRepository extends JpaRepository<UserAccount, UUID> { Optional<UserAccount> findByNormalizedEmail(String email); }
@Repository interface ProfileRepository extends JpaRepository<Profile, UUID> {
  Optional<Profile> findByShareId(UUID shareId);
  @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select p from Profile p where p.userId = :userId")
  Optional<Profile> findByIdForUpdate(@Param("userId") UUID userId);
}
@Repository interface GameRepository extends JpaRepository<Game, UUID> {
  Optional<Game> findBySlugAndActiveTrue(String slug);
  List<Game> findByActiveTrueOrderByNameAsc();
}
@Repository interface CatalogGameRepository extends JpaRepository<CatalogGame, String> {
  List<CatalogGame> findAllByOrderByDisplayOrderAsc();
}
@Repository interface LeaderboardRepository extends JpaRepository<Leaderboard, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select l from Leaderboard l where l.id = :id")
  Optional<Leaderboard> findByIdForUpdate(@Param("id") UUID id);
  long countByOwnerIdAndCreationBasis(UUID ownerId, BoardCreationBasis creationBasis);
  long countByOwnerIdAndBoardType(UUID ownerId, BoardType boardType);
  @Query("select l from Leaderboard l where l.id in (select m.id.leaderboardId from Membership m where m.id.userId = :userId) order by l.createdAt")
  List<Leaderboard> findForUser(@Param("userId") UUID userId);
  List<Leaderboard> findByOwnerIdOrderByCreatedAtAsc(UUID ownerId);
}
@Repository interface MembershipRepository extends JpaRepository<Membership, MembershipId> {
  @Modifying @Query(value = "insert into leaderboard_members(leaderboard_id, user_id, added_by, created_at) values (:boardId, :userId, :ownerId, :now) on conflict do nothing", nativeQuery = true)
  int insertIfAbsent(@Param("boardId") UUID boardId, @Param("userId") UUID userId,
                     @Param("ownerId") UUID ownerId, @Param("now") Instant now);
  List<Membership> findByIdLeaderboardId(UUID leaderboardId);
  boolean existsByIdLeaderboardIdAndIdUserId(UUID leaderboardId, UUID userId);
  @Query("select count(mine) > 0 from Membership mine, Membership theirs where mine.id.leaderboardId = theirs.id.leaderboardId and mine.id.userId = :viewer and theirs.id.userId = :other")
  boolean sharesLeaderboard(@Param("viewer") UUID viewer, @Param("other") UUID other);
}
@Repository interface LeaderboardInviteRepository extends JpaRepository<LeaderboardInvite, UUID> {
  Optional<LeaderboardInvite> findByInviteId(UUID inviteId);
}
@Repository interface SubmissionRepository extends JpaRepository<Submission, UUID> {
  List<Submission> findTop30ByUserIdOrderByPlayedOnDescCreatedAtDesc(UUID userId);
  List<Submission> findByUserIdOrderByPlayedOnDescCreatedAtDesc(UUID userId);
  List<Submission> findByUserIdInAndGameIdAndPlayedOnBetween(Collection<UUID> userIds, UUID gameId, LocalDate from, LocalDate to);
  List<Submission> findByUserIdInAndGameId(Collection<UUID> userIds, UUID gameId);
  List<Submission> findByUserIdInAndGameIdIn(Collection<UUID> userIds, Collection<UUID> gameIds);
  List<Submission> findByUserIdInAndGameIdInAndPlayedOnBetween(Collection<UUID> userIds, Collection<UUID> gameIds,
                                                               LocalDate from, LocalDate to);
  boolean existsByUserIdAndGameIdAndPuzzleKey(UUID userId, UUID gameId, String puzzleKey);
  Optional<Submission> findByUserIdAndGameIdAndPuzzleKey(UUID userId, UUID gameId, String puzzleKey);
  Optional<Submission> findByUserIdAndGameIdAndPlayedOn(UUID userId, UUID gameId, LocalDate playedOn);
}
@Repository interface AuthActionTokenRepository extends JpaRepository<AuthActionToken, UUID> {
  long countByUserIdAndPurposeAndCreatedAtAfter(UUID userId, AuthTokenPurpose purpose, Instant after);
  long countByRequestIpHashAndCreatedAtAfter(String ipHash, Instant after);
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<AuthActionToken> findByTokenHashAndPurposeAndConsumedAtIsNullAndExpiresAtAfter(
      String tokenHash, AuthTokenPurpose purpose, Instant now);
  @Modifying @Query("update AuthActionToken t set t.consumedAt = :now where t.userId = :userId and t.purpose = :purpose and t.consumedAt is null")
  int consumeOutstanding(@Param("userId") UUID userId, @Param("purpose") AuthTokenPurpose purpose, @Param("now") Instant now);
  void deleteByExpiresAtBefore(Instant before);
}
@Repository interface AuthLoginAttemptRepository extends JpaRepository<AuthLoginAttempt, UUID> {
  long countByNormalizedEmailHashAndCreatedAtAfter(String emailHash, Instant after);
  long countByRequestIpHashAndCreatedAtAfter(String ipHash, Instant after);
  void deleteByNormalizedEmailHash(String emailHash);
  void deleteByCreatedAtBefore(Instant before);
}
@Repository interface AuthSessionRepository extends JpaRepository<AuthSession, UUID> {
  Optional<AuthSession> findByTokenHashAndExpiresAtAfter(String tokenHash, Instant now);
  void deleteByTokenHash(String tokenHash);
  void deleteByUserId(UUID userId);
  void deleteByExpiresAtBefore(Instant before);
}
@Repository interface BillingAccountRepository extends JpaRepository<BillingAccount, UUID> {
  Optional<BillingAccount> findByStripeCustomerId(String customerId);
  Optional<BillingAccount> findByStripeSubscriptionId(String subscriptionId);
}
@Repository interface ProcessedStripeEventRepository extends JpaRepository<ProcessedStripeEvent, String> {
  @Modifying @Query(value = "insert into processed_stripe_events(event_id, event_type, event_created_at, processed_at) values (:eventId, :eventType, :eventCreatedAt, :processedAt) on conflict do nothing", nativeQuery = true)
  int insertIfAbsent(@Param("eventId") String eventId, @Param("eventType") String eventType,
                     @Param("eventCreatedAt") Instant eventCreatedAt, @Param("processedAt") Instant processedAt);
}

@Repository interface DiscordAccountLinkRepository extends JpaRepository<DiscordAccountLink, String> {
  Optional<DiscordAccountLink> findByUserId(UUID userId);
  void deleteByUserId(UUID userId);
}

@Repository interface DiscordLinkCodeRepository extends JpaRepository<DiscordLinkCode, UUID> {
  long countByUserIdAndCreatedAtAfter(UUID userId, Instant after);
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<DiscordLinkCode> findByCodeHashAndConsumedAtIsNullAndExpiresAtAfter(String codeHash, Instant now);
  @Modifying
  @Query("update DiscordLinkCode c set c.consumedAt = :now where c.userId = :userId and c.consumedAt is null")
  int consumeOutstanding(@Param("userId") UUID userId, @Param("now") Instant now);
  void deleteByExpiresAtBefore(Instant before);
}

@Repository interface DiscordChannelLeaderboardRepository
    extends JpaRepository<DiscordChannelLeaderboard, DiscordChannelLeaderboardId> {
  List<DiscordChannelLeaderboard> findByLeaderboardId(UUID leaderboardId);
  @Modifying @Query(value = "delete from discord_channel_leaderboards where leaderboard_id in " +
      "(select id from leaderboards where owner_id = :ownerId)", nativeQuery = true)
  int deleteOwnedChannels(@Param("ownerId") UUID ownerId);
  @Query("select distinct c.id.guildId from DiscordChannelLeaderboard c")
  List<String> findDistinctGuildIds();
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from DiscordChannelLeaderboard c where c.id = :id")
  Optional<DiscordChannelLeaderboard> findForUpdate(@Param("id") DiscordChannelLeaderboardId id);
}

@Repository interface DiscordSetupRepository extends JpaRepository<DiscordSetup, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<DiscordSetup> findByStateHash(String stateHash);
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<DiscordSetup> findBySetupHash(String setupHash);
  void deleteByExpiresAtBefore(Instant before);
}

@Repository interface DiscordSignupIntentRepository extends JpaRepository<DiscordSignupIntent, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<DiscordSignupIntent> findByTokenHash(String tokenHash);
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<DiscordSignupIntent> findByOauthStateHash(String oauthStateHash);
  void deleteByExpiresAtBefore(Instant before);
}

@Repository interface ProcessedDiscordInteractionRepository extends JpaRepository<ProcessedDiscordInteraction, String> {
  @Modifying
  @Query(value = "insert into processed_discord_interactions(interaction_id, discord_user_id, interaction_type, command_name, outcome, processed_at) " +
      "values (:interactionId, :discordUserId, :interactionType, :commandName, 'PROCESSING', :processedAt) on conflict do nothing",
      nativeQuery = true)
  int insertIfAbsent(@Param("interactionId") String interactionId, @Param("discordUserId") String discordUserId,
                     @Param("interactionType") int interactionType, @Param("commandName") String commandName,
                     @Param("processedAt") Instant processedAt);
  @Modifying
  @Query("update ProcessedDiscordInteraction i set i.outcome = :outcome where i.interactionId = :interactionId")
  int updateOutcome(@Param("interactionId") String interactionId, @Param("outcome") String outcome);
  long countByDiscordUserIdAndCommandNameAndProcessedAtAfter(String discordUserId, String commandName, Instant after);
  void deleteByProcessedAtBefore(Instant before);
}
