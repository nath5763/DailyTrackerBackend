package com.dailygames;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

final class ApiModels {
  private ApiModels() {}

  record EmailRequest(@NotBlank @Email @Size(max = 320) String email) {}
  record CredentialsRequest(@NotBlank @Email @Size(max = 320) String email,
                            @NotBlank @Size(max = 200) String password) {}
  record ResetPasswordRequest(@NotBlank @Size(max = 200) String token,
                              @NotBlank @Size(max = 200) String password) {}
  record AuthSessionResponse(boolean onboarded) {}
  record MessageResponse(String message) {}
  record OnboardingRequest(@NotBlank @Size(min = 2, max = 32) String displayName, @NotBlank String avatarColor) {}
  record CreateLeaderboardRequest(@NotBlank @Size(min = 2, max = 48) String name,
                                  @Size(max = 80) String gameSlug, BoardType boardType) {
    CreateLeaderboardRequest(String name, String gameSlug) { this(name, gameSlug, null); }
  }
  record CopyLeaderboardRequest(@NotBlank @Size(min = 2, max = 48) String name,
                                @NotBlank @Size(max = 80) String gameSlug) {}
  record AddMemberRequest(@NotNull UUID shareId) {}
  record PreviewSubmissionRequest(@NotBlank String game, @NotBlank @Size(max = 10_000) String rawText) {}
  record ConfirmedSubmission(String puzzleKey, Integer puzzleNumber, LocalDate playedOn, BigDecimal primaryScore,
                             Map<String, Object> metrics, Integer parserVersion) {}
  record CreateSubmissionRequest(@NotBlank String game, @NotBlank @Size(max = 10_000) String rawText,
                                 @NotNull ConfirmedSubmission confirmed) {}
  record ManualSubmissionRequest(@NotBlank @Size(max = 80) String game,
                                 @Size(max = 80) String puzzleId,
                                 @NotNull LocalDate puzzleDate,
                                 @NotBlank @Size(max = 20) String completionStatus,
                                 @NotNull @Size(max = 40) Map<String, Object> values) {}
  record IdResponse(UUID id) {}
  record UrlResponse(String url) {}
  record CheckoutRequest(@NotNull BillingTier tier) {}
  record ErrorResponse(String error, String code) {}

  record UserView(UUID id, String email) {}
  record ProfileView(UUID userId, UUID shareId, String displayName, String avatarColor, Instant createdAt) {}
  record MeResponse(UserView user, ProfileView profile) {}
  record ScoreFormatView(String prefix, String suffix, int minimumDecimalPlaces, int maximumDecimalPlaces, String kind) {
    ScoreFormatView(String prefix, String suffix, int minimumDecimalPlaces, int maximumDecimalPlaces) {
      this(prefix, suffix, minimumDecimalPlaces, maximumDecimalPlaces, "number");
    }
  }
  record SubmissionConfigView(String exampleShareText, String puzzleLabel, String scoreLabel, BigDecimal scoreMin,
                              BigDecimal scoreMax, BigDecimal scoreStep, Set<String> editableFields) {}
  record ManualEntryFieldView(String key, String label, String type, boolean required, BigDecimal min,
                              BigDecimal max, BigDecimal step, List<String> options, String helpText) {}
  record ManualEntryConfigView(boolean supported, boolean puzzleIdRequired, List<String> completionStatuses,
                               List<ManualEntryFieldView> fields) {}
  record GameView(UUID id, String slug, String name, String sourceUrl, String rankingDirection,
                  ScoreFormatView scoreFormat, SubmissionConfigView submission, ManualEntryConfigView manualEntry,
                  Set<String> supportedFormats) {
    GameView(UUID id, String slug, String name, String sourceUrl, String rankingDirection,
             ScoreFormatView scoreFormat, SubmissionConfigView submission, ManualEntryConfigView manualEntry) {
      this(id, slug, name, sourceUrl, rankingDirection, scoreFormat, submission, manualEntry, Set.of("share-text"));
    }
    GameView(UUID id, String slug, String name, String sourceUrl, String rankingDirection,
             ScoreFormatView scoreFormat, SubmissionConfigView submission) {
      this(id, slug, name, sourceUrl, rankingDirection, scoreFormat, submission,
          new ManualEntryConfigView(false, false, List.of(), List.of()), Set.of("share-text"));
    }
  }
  record CatalogGameView(String slug, String name, String description, String category, String sourceUrl,
                         boolean scoreTrackingAvailable) {}
  record ParserCapabilityView(String gameId, String status, Set<String> supportedFormats) {}
  record ScoreStrategyView(String gameId, String rankingRule, String primaryMetric, String primaryDirection,
                           String secondaryMetric, String secondaryDirection, List<String> compatibilityFields,
                           boolean completionOnlyAllowed, String completionPolicy) {}
  record BoardSummary(UUID id, String name, UUID ownerId, boolean owner, GameView game, BoardType boardType,
                      List<GameView> games, boolean locked, Instant createdAt) {}
  record ScoreView(UUID id, String puzzleKey, LocalDate playedOn, BigDecimal primaryScore, String gameName, String gameSlug,
                   GameView game, Map<String, Object> metrics, String submissionMethod) {}
  record StatsView(BigDecimal average, BigDecimal best, int currentStreak, int results) {}
  record GameStatsView(GameView game, BigDecimal average, BigDecimal best, int currentStreak, int results) {}
  record DashboardResponse(ProfileView profile, StatsView stats, List<GameStatsView> statsByGame,
                           List<ScoreView> recentScores, List<BoardSummary> leaderboards) {}
  record PreviewResponse(ParsedSubmission submission) {}
  record MemberView(UUID userId, UUID shareId, String displayName, String avatarColor, boolean owner) {}
  record RankingView(int rank, UUID userId, UUID shareId, String displayName, BigDecimal average, int gamesPlayed,
                     int assistedResults, int unknownAssistanceResults, int hints, int mistakes, int beginnerAssists,
                     String displayScore, BigDecimal leaderboardPoints, int participationPoints, int manualResults) {
    RankingView(int rank, UUID userId, UUID shareId, String displayName, BigDecimal average, int gamesPlayed) {
      this(rank, userId, shareId, displayName, average, gamesPlayed, 0, 0, 0, 0, 0, null, null, 0, 0);
    }
  }
  record LeaderboardResponse(UUID id, String name, UUID ownerId, GameView game, BoardType boardType,
                             List<GameView> games, String range, boolean owner, List<MemberView> members,
                             List<RankingView> rankings, List<MemberView> absent, List<RankingView> combinedRankings) {
    LeaderboardResponse(UUID id, String name, UUID ownerId, GameView game, BoardType boardType,
                        List<GameView> games, String range, boolean owner, List<MemberView> members,
                        List<RankingView> rankings, List<MemberView> absent) {
      this(id, name, ownerId, game, boardType, games, range, owner, members, rankings, absent, List.of());
    }
  }
  record LeaderboardMemberScoresResponse(MemberView member, GameView game, String period,
                                         RankingView ranking, int rankedPlayerCount, List<ScoreView> scores) {}
  record RankPoint(LocalDate date, Integer rank) {}
  record RankSeries(UUID userId, String displayName, String avatarColor, List<RankPoint> points) {}
  record DailyPerformance(LocalDate date, BigDecimal score, int assistanceTier) {}
  record LeaderboardInsightsResponse(LocalDate from, LocalDate to, List<RankSeries> ranks,
                                     List<DailyPerformance> personalScores) {}
  record ProfileResponse(ProfileView profile, boolean self, StatsView stats, List<GameStatsView> statsByGame,
                         List<ScoreView> scores) {}
  record BillingStatusResponse(BillingTier tier, long singleGameCount, int singleGameLimit,
                               long allGameCount, int allGameLimit, String status,
                               boolean cancelAtPeriodEnd, Instant currentPeriodEnd, Instant graceUntil) {}
}
