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
  record IdResponse(UUID id) {}
  record UrlResponse(String url) {}
  record ErrorResponse(String error, String code) {}

  record UserView(UUID id, String email) {}
  record ProfileView(UUID userId, UUID shareId, String displayName, String avatarColor, Instant createdAt) {}
  record MeResponse(UserView user, ProfileView profile) {}
  record ScoreFormatView(String prefix, String suffix, int minimumDecimalPlaces, int maximumDecimalPlaces) {}
  record SubmissionConfigView(String exampleShareText, String puzzleLabel, String scoreLabel, BigDecimal scoreMin,
                              BigDecimal scoreMax, BigDecimal scoreStep, Set<String> editableFields) {}
  record GameView(UUID id, String slug, String name, String sourceUrl, String rankingDirection,
                  ScoreFormatView scoreFormat, SubmissionConfigView submission) {}
  record BoardSummary(UUID id, String name, UUID ownerId, boolean owner, GameView game, BoardType boardType,
                      List<GameView> games, boolean locked, Instant createdAt) {}
  record ScoreView(UUID id, String puzzleKey, LocalDate playedOn, BigDecimal primaryScore, String gameName, String gameSlug,
                   GameView game) {}
  record StatsView(BigDecimal average, BigDecimal best, int currentStreak, int results) {}
  record GameStatsView(GameView game, BigDecimal average, BigDecimal best, int currentStreak, int results) {}
  record DashboardResponse(ProfileView profile, StatsView stats, List<GameStatsView> statsByGame,
                           List<ScoreView> recentScores, List<BoardSummary> leaderboards) {}
  record PreviewResponse(ParsedSubmission submission) {}
  record MemberView(UUID userId, UUID shareId, String displayName, String avatarColor, boolean owner) {}
  record RankingView(int rank, UUID userId, UUID shareId, String displayName, BigDecimal average, int gamesPlayed) {}
  record LeaderboardResponse(UUID id, String name, UUID ownerId, GameView game, BoardType boardType,
                             List<GameView> games, String range, boolean owner, List<MemberView> members,
                             List<RankingView> rankings, List<MemberView> absent) {}
  record ProfileResponse(ProfileView profile, boolean self, StatsView stats, List<GameStatsView> statsByGame,
                         List<ScoreView> scores) {}
  record BillingStatusResponse(boolean unlimitedLeaderboards, boolean freeLeaderboardAvailable, String status,
                               boolean cancelAtPeriodEnd, Instant currentPeriodEnd, Instant graceUntil) {}
}
