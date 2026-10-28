package com.dailygames;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

final class ApiModels {
  private ApiModels() {}

  record MagicLinkRequest(@NotBlank @Email @Size(max = 320) String email) {}
  record MessageResponse(String message) {}
  record OnboardingRequest(@NotBlank @Size(min = 2, max = 32) String displayName, @NotBlank String avatarColor) {}
  record CreateLeaderboardRequest(@NotBlank @Size(min = 2, max = 48) String name) {}
  record AddMemberRequest(@NotNull UUID shareId) {}
  record PreviewSubmissionRequest(@NotBlank String game, @NotBlank @Size(max = 10_000) String rawText) {}
  record ConfirmedSubmission(String puzzleKey, Integer puzzleNumber, LocalDate playedOn, BigDecimal primaryScore,
                             Map<String, Object> metrics, Integer parserVersion) {}
  record CreateSubmissionRequest(@NotBlank String game, @NotBlank @Size(max = 10_000) String rawText,
                                 @NotNull ConfirmedSubmission confirmed) {}
  record IdResponse(UUID id) {}
  record ErrorResponse(String error, String code) {}

  record UserView(UUID id, String email) {}
  record ProfileView(UUID userId, UUID shareId, String displayName, String avatarColor, Instant createdAt) {}
  record MeResponse(UserView user, ProfileView profile) {}
  record BoardSummary(UUID id, String name, UUID ownerId, boolean owner, Instant createdAt) {}
  record ScoreView(UUID id, String puzzleKey, LocalDate playedOn, BigDecimal primaryScore, String gameName, String gameSlug) {}
  record StatsView(BigDecimal average, BigDecimal best, int currentStreak, int results) {}
  record DashboardResponse(ProfileView profile, StatsView stats, List<ScoreView> recentScores, List<BoardSummary> leaderboards) {}
  record PreviewResponse(FermiParser.Parsed submission) {}
  record MemberView(UUID userId, UUID shareId, String displayName, String avatarColor, boolean owner) {}
  record RankingView(int rank, UUID userId, UUID shareId, String displayName, BigDecimal average, int gamesPlayed) {}
  record LeaderboardResponse(UUID id, String name, UUID ownerId, String gameSlug, String range, boolean owner,
                             List<MemberView> members, List<RankingView> rankings, List<MemberView> absent) {}
  record ProfileResponse(ProfileView profile, boolean self, StatsView stats, List<ScoreView> scores) {}
}
