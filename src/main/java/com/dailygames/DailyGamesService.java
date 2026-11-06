package com.dailygames;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.*;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

import static com.dailygames.ApiModels.*;

@Service
class DailyGamesService {
  static final Set<String> COLORS = Set.of("#dfff4f", "#ff8db6", "#a8c7ff", "#ffba6b", "#bba7ff");
  private static final ObjectMapper JSON = new ObjectMapper();
  private final UserRepository users;
  private final ProfileRepository profiles;
  private final GameLookup games;
  private final LeaderboardRepository boards;
  private final MembershipRepository memberships;
  private final SubmissionRepository submissions;
  private final GameAdapterRegistry adapters;
  private final BillingService billing;

  @Autowired
  DailyGamesService(UserRepository users, ProfileRepository profiles, GameCatalog games, LeaderboardRepository boards,
                    MembershipRepository memberships, SubmissionRepository submissions, GameAdapterRegistry adapters,
                    BillingService billing) {
    this.users = users; this.profiles = profiles; this.games = games; this.boards = boards;
    this.memberships = memberships; this.submissions = submissions; this.adapters = adapters; this.billing = billing;
  }

  DailyGamesService(UserRepository users, ProfileRepository profiles, GameRepository games, LeaderboardRepository boards,
                    MembershipRepository memberships, SubmissionRepository submissions, GameAdapterRegistry adapters,
                    BillingService billing) {
    this.users = users; this.profiles = profiles; this.games = new RepositoryGameLookup(games); this.boards = boards;
    this.memberships = memberships; this.submissions = submissions; this.adapters = adapters; this.billing = billing;
  }

  MeResponse me(CurrentUser current) {
    UserAccount user = users.findById(current.id()).orElseThrow(this::unauthorized);
    return new MeResponse(new UserView(user.id, user.normalizedEmail), profiles.findById(user.id).map(this::profileView).orElse(null));
  }

  @Transactional
  ProfileView onboard(CurrentUser current, OnboardingRequest request) {
    Optional<Profile> existing = profiles.findById(current.id());
    if (existing.isPresent()) return profileView(existing.get());
    String name = request.displayName().trim();
    if (name.length() < 2 || name.length() > 32 || !COLORS.contains(request.avatarColor())) {
      throw new ApiException(400, "INVALID_PROFILE", "Choose a display name and one of the available colors.");
    }
    Instant now = Instant.now();
    Profile profile = profiles.save(new Profile(current.id(), name, request.avatarColor(), now));
    return profileView(profile);
  }

  DashboardResponse dashboard(CurrentUser current) {
    Profile profile = requireProfile(current.id());
    List<Submission> values = submissions.findByUserIdOrderByPlayedOnDescCreatedAtDesc(current.id());
    List<GameStatsView> gameStats = statsByGame(values);
    return new DashboardResponse(profileView(profile), legacyStats(gameStats), gameStats,
        values.stream().limit(7).map(this::scoreView).toList(), listBoards(current));
  }

  List<BoardSummary> listBoards(CurrentUser current) {
    requireProfile(current.id());
    return boards.findForUser(current.id()).stream().map(board -> boardSummary(board, current.id())).toList();
  }

  List<GameView> listGames() {
    return games.activeGames().stream().map(this::gameView).toList();
  }

  @Transactional
  UUID createBoard(CurrentUser current, CreateLeaderboardRequest request) {
    BoardType boardType = request.boardType() == null ? BoardType.SINGLE_GAME : request.boardType();
    String gameSlug = request.gameSlug() == null ? null : request.gameSlug().trim();
    if (boardType == BoardType.MULTI_GAME) {
      if (gameSlug != null && !gameSlug.isBlank()) {
        throw new ApiException(400, "GAME_NOT_ALLOWED", "Do not choose a game for a multi-game leaderboard.");
      }
      return createBoard(current.id(), request.name(), null, boardType, List.of(current.id()));
    }
    if (gameSlug == null || gameSlug.isBlank()) {
      throw new ApiException(400, "GAME_REQUIRED", "Choose a game for a single-game leaderboard.");
    }
    return createBoard(current.id(), request.name(), requireGame(gameSlug), boardType, List.of(current.id()));
  }

  @Transactional
  UUID copyBoard(CurrentUser current, UUID sourceId, CopyLeaderboardRequest request) {
    requireProfile(current.id());
    requireMembership(sourceId, current.id());
    Leaderboard source = boards.findById(sourceId).orElseThrow(this::notFound);
    requireUnlocked(source);
    if (boardType(source) == BoardType.MULTI_GAME) {
      throw new ApiException(400, "MULTI_GAME_COPY_NOT_ALLOWED",
          "Multi-game leaderboards already include every active game.");
    }
    Game game = requireGame(request.gameSlug());
    if (source.gameId.equals(game.id)) {
      throw new ApiException(400, "SAME_GAME", "Choose a different game for the copied leaderboard.");
    }
    List<UUID> memberIds = memberships.findByIdLeaderboardId(sourceId).stream().map(member -> member.id.userId).toList();
    return createBoard(current.id(), request.name(), game, BoardType.SINGLE_GAME, memberIds);
  }

  @Transactional
  void convertToMultiGame(CurrentUser current, UUID boardId) {
    Leaderboard board = requireOwnedBoard(boardId, current.id());
    if (boardType(board) != BoardType.MULTI_GAME) {
      requireAllGameCapacity(current.id(), Instant.now());
      board.boardType = BoardType.MULTI_GAME;
      board.gameId = null;
      board.creationBasis = BoardCreationBasis.SUBSCRIPTION;
      boards.save(board);
    }
  }

  LeaderboardResponse board(CurrentUser current, UUID boardId, String range) {
    return board(current, boardId, range, null);
  }

  LeaderboardResponse board(CurrentUser current, UUID boardId, String range, String gameSlug) {
    LeaderboardData data = leaderboardData(current, boardId, range, gameSlug);
    Leaderboard board = data.board;
    Game game = data.game;
    List<RankingView> rankings = rankScores(data.memberProfiles, data.scores, game);
    List<RankingView> combinedRankings = boardType(board) == BoardType.MULTI_GAME
        ? combinedRankings(data.memberProfiles, data.memberIds, data.allowedGames, range) : List.of();
    Set<UUID> ranked = rankings.stream().map(RankingView::userId).collect(Collectors.toSet());
    List<MemberView> members = data.memberProfiles.stream().map(profile -> memberView(profile, board.ownerId)).toList();
    List<MemberView> absent = range.equals("today") ? data.memberProfiles.stream().filter(profile -> !ranked.contains(profile.userId)).map(profile -> memberView(profile, board.ownerId)).toList() : List.of();
    List<DailyResultDetailsView> dailyResults = range.equals("today") ? data.scores.stream()
        .map(score -> new DailyResultDetailsView(score.userId, score.puzzleKey,
            adapters.resultDetails(game.slug, score.metrics)))
        .filter(result -> !result.details().isEmpty())
        .toList() : List.of();
    return new LeaderboardResponse(board.id, board.name, board.ownerId, gameView(game), boardType(board),
        data.allowedGames.stream().map(this::gameView).toList(), range, board.ownerId.equals(current.id()),
        members, rankings, absent, combinedRankings, dailyResults);
  }

  LeaderboardMemberScoresResponse memberScores(CurrentUser current, UUID boardId, UUID userId,
                                                 String range, String gameSlug) {
    LeaderboardData data = leaderboardData(current, boardId, range, gameSlug);
    Profile member = data.memberProfiles.stream().filter(profile -> profile.userId.equals(userId)).findFirst()
        .orElseThrow(this::notFound);
    List<RankingView> rankings = rankScores(data.memberProfiles, data.scores, data.game);
    RankingView ranking = rankings.stream().filter(value -> value.userId().equals(userId)).findFirst().orElse(null);
    List<ScoreView> memberScores = data.scores.stream().filter(value -> value.userId.equals(userId))
        .sorted(Comparator.comparing((Submission value) -> value.playedOn).reversed()
            .thenComparing(value -> value.createdAt, Comparator.reverseOrder()))
        .map(this::scoreView).toList();
    return new LeaderboardMemberScoresResponse(memberView(member, data.board.ownerId), gameView(data.game),
        range, ranking, rankings.size(), memberScores);
  }

  private LeaderboardData leaderboardData(CurrentUser current, UUID boardId, String range, String gameSlug) {
    requireMembership(boardId, current.id());
    Leaderboard board = boards.findById(boardId).orElseThrow(this::notFound);
    requireUnlocked(board);
    List<Game> allowedGames = allowedGames(board);
    List<Profile> memberProfiles = memberships.findByIdLeaderboardId(boardId).stream()
        .map(member -> profiles.findById(member.id.userId).orElseThrow(this::notFound)).toList();
    List<UUID> memberIds = memberProfiles.stream().map(profile -> profile.userId).toList();
    Game game = selectLeaderboardGame(board, allowedGames, gameSlug, memberIds);
    List<Submission> scores = periodScores(memberIds, game, range);
    return new LeaderboardData(board, allowedGames, memberProfiles, memberIds, game, scores);
  }

  private List<Submission> periodScores(List<UUID> memberIds, Game game, String range) {
    LocalDate today = LocalDate.now(ZoneId.of(game.releaseTimezone));
    return switch (range) {
      case "today" -> submissions.findByUserIdInAndGameIdAndPlayedOnBetween(memberIds, game.id, today, today);
      case "7d" -> submissions.findByUserIdInAndGameIdAndPlayedOnBetween(memberIds, game.id, today.minusDays(6), today);
      case "30d" -> submissions.findByUserIdInAndGameIdAndPlayedOnBetween(memberIds, game.id, today.minusDays(29), today);
      case "all" -> submissions.findByUserIdInAndGameId(memberIds, game.id);
      default -> throw new ApiException(400, "INVALID_RANGE", "Range must be today, 7d, 30d, or all.");
    };
  }

  LeaderboardInsightsResponse insights(CurrentUser current, UUID boardId, String gameSlug) {
    requireMembership(boardId, current.id());
    Leaderboard board = boards.findById(boardId).orElseThrow(this::notFound);
    requireUnlocked(board);
    Game game = selectGame(board, allowedGames(board), gameSlug);
    List<Profile> memberProfiles = memberships.findByIdLeaderboardId(boardId).stream()
        .map(member -> profiles.findById(member.id.userId).orElseThrow(this::notFound)).toList();
    List<UUID> memberIds = memberProfiles.stream().map(profile -> profile.userId).toList();
    LocalDate to = LocalDate.now(ZoneId.of(game.releaseTimezone));
    LocalDate from = to.minusDays(29);
    List<Submission> history = submissions.findByUserIdInAndGameIdAndPlayedOnBetween(memberIds, game.id,
        from.minusDays(29), to);
    Map<UUID, List<RankPoint>> points = new HashMap<>();
    for (Profile profile : memberProfiles) points.put(profile.userId, new ArrayList<>());
    for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
      LocalDate start = day.minusDays(29);
      LocalDate snapshot = day;
      List<RankingView> daily = rankScores(memberProfiles, history.stream()
          .filter(score -> !score.playedOn.isBefore(start) && !score.playedOn.isAfter(snapshot)).toList(), game);
      Map<UUID, Integer> places = daily.stream().collect(Collectors.toMap(RankingView::userId, RankingView::rank));
      for (Profile profile : memberProfiles) points.get(profile.userId).add(new RankPoint(day, places.get(profile.userId)));
    }
    List<RankSeries> ranks = memberProfiles.stream().map(profile -> new RankSeries(profile.userId,
        profile.displayName, profile.avatarColor, points.get(profile.userId))).toList();
    List<DailyPerformance> personal = history.stream().filter(score -> score.userId.equals(current.id()) &&
        !score.playedOn.isBefore(from)).sorted(Comparator.comparing(score -> score.playedOn))
        .map(score -> new DailyPerformance(score.playedOn, score.primaryScore, assistanceTier(List.of(score))))
        .toList();
    return new LeaderboardInsightsResponse(from, to, ranks, personal);
  }

  private List<RankingView> rankScores(List<Profile> memberProfiles, List<Submission> scores, Game game) {
    if (scores.stream().anyMatch(score -> score.metrics.containsKey("rankingPrimary") ||
        score.metrics.containsKey("rankingSecondary"))) {
      return rankNormalizedScores(memberProfiles, scores, game);
    }
    Map<UUID, List<Submission>> byUser = scores.stream().collect(Collectors.groupingBy(score -> score.userId));
    Comparator<RankingSeed> rankingOrder = Comparator.comparing(RankingSeed::losses)
        .thenComparing(RankingSeed::assistanceTier);
    Comparator<RankingSeed> scoreOrder = Comparator.comparing(RankingSeed::average);
    if ("higher".equals(game.rankingDirection)) scoreOrder = scoreOrder.reversed();
    rankingOrder = rankingOrder.thenComparing(scoreOrder).thenComparing(RankingSeed::gamesPlayed, Comparator.reverseOrder())
        .thenComparing(seed -> seed.profile.displayName, String.CASE_INSENSITIVE_ORDER);
    List<RankingSeed> seeds = memberProfiles.stream().filter(profile -> byUser.containsKey(profile.userId)).map(profile -> {
      List<Submission> values = byUser.get(profile.userId);
      GameAdapter adapter = adapters.require(game.slug);
      BigDecimal average = values.stream().map(value -> adapter.rankingMetric(value.primaryScore, value.metrics))
          .reduce(BigDecimal.ZERO, BigDecimal::add)
          .divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP);
      int hints = values.stream().mapToInt(value -> metricCount(value, "hints")).sum();
      int mistakes = values.stream().mapToInt(value -> metricCount(value, "mistakes")).sum();
      int beginner = values.stream().mapToInt(value -> metricCount(value, "beginnerAssists")).sum();
      int assisted = (int) values.stream().filter(value -> "assisted".equals(value.metrics.get("assistanceStatus")) ||
          metricCount(value, "hints") > 0 || metricCount(value, "mistakes") > 0 || metricCount(value, "beginnerAssists") > 0).count();
      int unknown = (int) values.stream().filter(value -> "unknown".equals(value.metrics.get("assistanceStatus"))).count();
      int losses = (int) values.stream().filter(value -> "LOSS".equals(value.metrics.get("completionStatus"))).count();
      int manual = (int) values.stream().filter(value -> value.submissionMethod == SubmissionMethod.MANUAL).count();
      return new RankingSeed(profile, average, values.size(), losses, assistanceTier(values),
          assisted, unknown, hints, mistakes, beginner, manual);
    }).sorted(rankingOrder).toList();
    List<RankingView> rankings = new ArrayList<>();
    int rank = 0;
    RankingSeed previous = null;
    for (int index = 0; index < seeds.size(); index++) {
      RankingSeed seed = seeds.get(index);
      if (previous == null || !seed.sameResult(previous)) rank = index + 1;
      rankings.add(new RankingView(rank, seed.profile.userId, seed.profile.shareId, seed.profile.displayName,
          seed.average, seed.gamesPlayed, seed.assistedResults, seed.unknownAssistanceResults,
          seed.hints, seed.mistakes, seed.beginnerAssists, null, null, seed.gamesPlayed, seed.manualResults));
      previous = seed;
    }
    return rankings;
  }

  private List<RankingView> rankNormalizedScores(List<Profile> memberProfiles, List<Submission> scores, Game game) {
    Map<UUID, Profile> profileById = memberProfiles.stream().collect(Collectors.toMap(profile -> profile.userId, profile -> profile));
    Map<Submission, NormalizedScore> normalized = new IdentityHashMap<>();
    for (Submission score : scores) normalized.put(score, adapters.normalizedScore(game.slug, score.primaryScore,
        score.metrics, score.puzzleKey, score.playedOn, score.parserVersion, game.rankingDirection));
    Set<String> contests = scores.stream().map(score -> score.puzzleKey + "|" + normalized.get(score).compatibilityKey())
        .collect(Collectors.toSet());
    if (contests.size() > 1) return rankByPlacementPoints(profileById, scores, normalized);

    List<Submission> ordered = scores.stream().sorted((left, right) -> {
      int compared = adapters.compareScores(normalized.get(left), normalized.get(right));
      if (compared != 0) return compared;
      return profileById.get(left.userId).displayName.compareToIgnoreCase(profileById.get(right.userId).displayName);
    }).toList();
    List<RankingView> results = new ArrayList<>();
    Submission previous = null;
    int rank = 0;
    for (int index = 0; index < ordered.size(); index++) {
      Submission value = ordered.get(index);
      NormalizedScore score = normalized.get(value);
      if (previous == null || !adapters.sameRank(score, normalized.get(previous))) rank = index + 1;
      Profile profile = profileById.get(value.userId);
      results.add(new RankingView(rank, profile.userId, profile.shareId, profile.displayName,
          score.rankingPrimary() == null ? value.primaryScore : score.rankingPrimary(), 1,
          assisted(value) ? 1 : 0, unknownAssistance(value) ? 1 : 0,
          metricCount(value, "hints"), metricCount(value, "mistakes"), metricCount(value, "beginnerAssists"),
          score.displayScore(), null, 1, value.submissionMethod == SubmissionMethod.MANUAL ? 1 : 0));
      previous = value;
    }
    return results;
  }

  private List<RankingView> rankByPlacementPoints(Map<UUID, Profile> profilesById, List<Submission> scores,
                                                   Map<Submission, NormalizedScore> normalized) {
    Map<String, List<Submission>> contests = scores.stream().collect(Collectors.groupingBy(score ->
        score.gameId + "|" + score.puzzleKey + "|" + normalized.get(score).compatibilityKey()));
    Map<UUID, List<BigDecimal>> pointsByUser = new HashMap<>();
    Map<UUID, List<Submission>> valuesByUser = scores.stream().collect(Collectors.groupingBy(score -> score.userId));
    for (List<Submission> contest : contests.values()) {
      List<Submission> ordered = contest.stream().sorted((left, right) ->
          adapters.compareScores(normalized.get(left), normalized.get(right))).toList();
      List<Submission> valid = ordered.stream().filter(value -> validPerformance(normalized.get(value))).toList();
      for (Submission value : ordered) {
        NormalizedScore score = normalized.get(value);
        BigDecimal points = BigDecimal.ZERO;
        if (validPerformance(score)) {
          int validRank = valid.indexOf(value) + 1;
          if (validRank > 1 && adapters.sameRank(score, normalized.get(valid.get(validRank - 2)))) {
            int cursor = validRank - 1;
            while (cursor > 0 && adapters.sameRank(score, normalized.get(valid.get(cursor - 1)))) cursor--;
            validRank = cursor + 1;
          }
          points = BigDecimal.valueOf(placementPoints(validRank));
        }
        pointsByUser.computeIfAbsent(value.userId, ignored -> new ArrayList<>()).add(points);
      }
    }
    List<RankingSeed> seeds = pointsByUser.entrySet().stream().map(entry -> {
      UUID userId = entry.getKey();
      List<Submission> values = valuesByUser.get(userId);
      BigDecimal average = entry.getValue().stream().reduce(BigDecimal.ZERO, BigDecimal::add)
          .divide(BigDecimal.valueOf(entry.getValue().size()), 4, RoundingMode.HALF_UP);
      return rankingSeed(profilesById.get(userId), values, average);
    }).sorted(Comparator.comparing(RankingSeed::average).reversed()
        .thenComparing(RankingSeed::gamesPlayed, Comparator.reverseOrder())
        .thenComparing(seed -> seed.profile.displayName, String.CASE_INSENSITIVE_ORDER)).toList();
    List<RankingView> results = new ArrayList<>();
    RankingSeed previous = null;
    int rank = 0;
    for (int index = 0; index < seeds.size(); index++) {
      RankingSeed seed = seeds.get(index);
      if (previous == null || seed.average.compareTo(previous.average) != 0 || !seed.gamesPlayed.equals(previous.gamesPlayed)) rank = index + 1;
      String display = seed.average.stripTrailingZeros().toPlainString() + " leaderboard pts";
      results.add(new RankingView(rank, seed.profile.userId, seed.profile.shareId, seed.profile.displayName,
          seed.average, seed.gamesPlayed, seed.assistedResults, seed.unknownAssistanceResults, seed.hints,
          seed.mistakes, seed.beginnerAssists, display, seed.average, seed.gamesPlayed, seed.manualResults));
      previous = seed;
    }
    return results;
  }

  static int placementPoints(int rank) {
    if (rank < 1) throw new IllegalArgumentException("Placement rank must be positive.");
    int points = 100;
    for (int place = 1; place < rank && points > 0; place++) points = points * 4 / 5;
    return points;
  }

  private List<RankingView> combinedRankings(List<Profile> memberProfiles, List<UUID> memberIds,
                                              List<Game> allowedGames, String range) {
    if (allowedGames.isEmpty()) return List.of();
    List<UUID> gameIds = allowedGames.stream().map(game -> game.id).toList();
    List<Submission> candidates;
    if (range.equals("all")) candidates = submissions.findByUserIdInAndGameIdIn(memberIds, gameIds);
    else {
      LocalDate utcToday = LocalDate.now(ZoneOffset.UTC);
      int days = range.equals("today") ? 0 : range.equals("7d") ? 6 : 29;
      candidates = submissions.findByUserIdInAndGameIdInAndPlayedOnBetween(memberIds, gameIds,
          utcToday.minusDays(days + 1L), utcToday.plusDays(1));
    }
    Map<UUID, Game> gamesById = allowedGames.stream().collect(Collectors.toMap(game -> game.id, game -> game));
    List<Submission> selected = candidates.stream().filter(value -> {
      Game candidate = gamesById.get(value.gameId);
      if (candidate == null || range.equals("all")) return candidate != null;
      LocalDate today = LocalDate.now(ZoneId.of(candidate.releaseTimezone));
      LocalDate from = range.equals("today") ? today : range.equals("7d") ? today.minusDays(6) : today.minusDays(29);
      return !value.playedOn.isBefore(from) && !value.playedOn.isAfter(today);
    }).toList();
    if (selected.isEmpty()) return List.of();
    Map<Submission, NormalizedScore> normalized = new IdentityHashMap<>();
    for (Submission value : selected) {
      Game candidate = gamesById.get(value.gameId);
      normalized.put(value, adapters.normalizedScore(candidate.slug, value.primaryScore, value.metrics,
          value.puzzleKey, value.playedOn, value.parserVersion, candidate.rankingDirection));
    }
    Map<UUID, Profile> profilesById = memberProfiles.stream().collect(Collectors.toMap(profile -> profile.userId, profile -> profile));
    return rankByPlacementPoints(profilesById, selected, normalized);
  }

  private RankingSeed rankingSeed(Profile profile, List<Submission> values, BigDecimal average) {
    int hints = values.stream().mapToInt(value -> metricCount(value, "hints")).sum();
    int mistakes = values.stream().mapToInt(value -> metricCount(value, "mistakes")).sum();
    int beginner = values.stream().mapToInt(value -> metricCount(value, "beginnerAssists")).sum();
    int assisted = (int) values.stream().filter(this::assisted).count();
    int unknown = (int) values.stream().filter(this::unknownAssistance).count();
    int losses = (int) values.stream().filter(value -> "LOSS".equals(value.metrics.get("completionStatus"))).count();
    int manual = (int) values.stream().filter(value -> value.submissionMethod == SubmissionMethod.MANUAL).count();
    return new RankingSeed(profile, average, values.size(), losses, assistanceTier(values), assisted, unknown,
        hints, mistakes, beginner, manual);
  }

  private boolean validPerformance(NormalizedScore score) {
    return score.rankingPrimary() != null && (score.completionPolicy() == CompletionPolicy.METRICS_FIRST ||
        "WIN".equals(score.completionStatus()) || "UNKNOWN".equals(score.completionStatus()));
  }

  private boolean assisted(Submission value) {
    return "assisted".equals(value.metrics.get("assistanceStatus")) || metricCount(value, "hints") > 0 ||
        metricCount(value, "mistakes") > 0 || metricCount(value, "beginnerAssists") > 0;
  }

  private boolean unknownAssistance(Submission value) {
    return "unknown".equals(value.metrics.get("assistanceStatus"));
  }

  private int assistanceTier(List<Submission> scores) {
    if (scores.stream().anyMatch(value -> "assisted".equals(value.metrics.get("assistanceStatus")) ||
        metricCount(value, "hints") > 0 || metricCount(value, "mistakes") > 0 ||
        metricCount(value, "beginnerAssists") > 0)) return 2;
    return scores.stream().anyMatch(value -> "unknown".equals(value.metrics.get("assistanceStatus"))) ? 1 : 0;
  }

  @Transactional
  void addMember(CurrentUser current, UUID boardId, UUID shareId) {
    Leaderboard board = requireOwnedBoard(boardId, current.id());
    requireUnlocked(board);
    Profile target = profiles.findByShareId(shareId).orElseThrow(() -> new ApiException(404, "ACCOUNT_NOT_FOUND", "Account not found."));
    try { memberships.saveAndFlush(new Membership(board.id, target.userId, current.id(), Instant.now())); }
    catch (DataIntegrityViolationException exception) { throw new ApiException(409, "ALREADY_MEMBER", "That account is already a member."); }
  }

  void addSignupMember(UUID boardId, UUID userId) {
    Leaderboard board = boards.findById(boardId)
        .orElseThrow(() -> new ApiException(404, "LEADERBOARD_NOT_FOUND", "That leaderboard no longer exists."));
    requireUnlocked(board);
    requireProfile(userId);
    if (!memberships.existsByIdLeaderboardIdAndIdUserId(boardId, userId)) {
      memberships.saveAndFlush(new Membership(boardId, userId, board.ownerId, Instant.now()));
    }
  }

  List<GameView> searchDiscordGames(UUID boardId, String query) {
    return searchDiscordGames(boardId, query, false);
  }

  List<GameView> searchDiscordGames(UUID boardId, String query, boolean shareTextOnly) {
    Leaderboard board = boards.findById(boardId)
        .orElseThrow(() -> new ApiException(404, "LEADERBOARD_NOT_FOUND", "That leaderboard no longer exists."));
    requireUnlocked(board);
    String term = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    return allowedGames(board).stream()
        .filter(game -> !shareTextOnly || adapters.supportsShareText(game.slug))
        .filter(game -> game.name.toLowerCase(Locale.ROOT).contains(term) || game.slug.toLowerCase(Locale.ROOT).contains(term))
        .limit(25).map(this::gameView).toList();
  }

  List<GameView> searchDiscordGames(UUID userId, UUID boardId, String query, boolean shareTextOnly) {
    requireMembership(boardId, userId);
    return searchDiscordGames(boardId, query, shareTextOnly);
  }

  @Transactional
  void removeMember(CurrentUser current, UUID boardId, UUID userId) {
    Leaderboard board = requireOwnedBoard(boardId, current.id());
    requireUnlocked(board);
    if (board.ownerId.equals(userId)) throw new ApiException(400, "OWNER_REQUIRED", "The owner cannot be removed.");
    MembershipId id = new MembershipId(boardId, userId);
    if (!memberships.existsById(id)) throw notFound();
    memberships.deleteById(id);
  }

  ParsedSubmission preview(CurrentUser current, PreviewSubmissionRequest request) {
    requireProfile(current.id());
    requireGame(request.game());
    return adapters.parseResult(request.game(), request.rawText());
  }

  @Transactional
  UUID submit(CurrentUser current, CreateSubmissionRequest request) {
    requireProfile(current.id());
    Game game = requireGame(request.game());
    ParsedSubmission value = adapters.validateResult(request.game(), request.confirmed(), request.rawText());
    return persistSubmission(current.id(), game, value, request.rawText(), SubmissionMethod.SHARE_TEXT);
  }

  @Transactional
  UUID submitManual(CurrentUser current, ManualSubmissionRequest request) {
    ParsedSubmission value = previewManual(current, request);
    Game game = requireGame(request.game());
    return persistSubmission(current.id(), game, value, manualAudit(request), SubmissionMethod.MANUAL);
  }

  ParsedSubmission previewManual(CurrentUser current, ManualSubmissionRequest request) {
    requireProfile(current.id());
    Game game = requireGame(request.game());
    LocalDate today;
    try { today = LocalDate.now(ZoneId.of(game.releaseTimezone)); }
    catch (DateTimeException exception) { today = LocalDate.now(ZoneOffset.UTC); }
    if (request.puzzleDate() == null || request.puzzleDate().isAfter(today)) {
      throw new ApiException(422, "INVALID_MANUAL_RESULT", "Puzzle date cannot be in the future.");
    }
    return adapters.normalizeManualResult(game.slug, request);
  }

  DiscordSubmissionResult submitFromDiscord(UUID userId, UUID leaderboardId, String rawText) {
    return submitFromDiscord(userId, leaderboardId, null, rawText);
  }

  DiscordSubmissionResult submitFromDiscord(UUID userId, UUID leaderboardId, String gameSlug, String rawText) {
    requireProfile(userId);
    Leaderboard board = boards.findById(leaderboardId)
        .orElseThrow(() -> new ApiException(404, "LEADERBOARD_NOT_FOUND", "That leaderboard no longer exists."));
    if (!memberships.existsByIdLeaderboardIdAndIdUserId(board.id, userId)) {
      throw new ApiException(403, "NOT_MEMBER", "Your Daily Tracker account is not a member of this leaderboard.");
    }
    requireUnlocked(board);
    if ((gameSlug == null || gameSlug.isBlank()) && boardType(board) == BoardType.MULTI_GAME) {
      throw new ApiException(400, "GAME_REQUIRED",
          "Select the game option when submitting to an all-games leaderboard.");
    }
    List<Game> allowedGames = allowedGames(board);
    Game game = selectGame(board, allowedGames, gameSlug);
    ParsedSubmission value = adapters.parseResult(game.slug, rawText);
    UUID id = persistSubmission(userId, game, value, rawText, SubmissionMethod.SHARE_TEXT);
    return new DiscordSubmissionResult(id, game, value);
  }

  UUID persistSubmission(UUID userId, Game game, ParsedSubmission value, String rawText) {
    return persistSubmission(userId, game, value, rawText, SubmissionMethod.SHARE_TEXT);
  }

  UUID persistSubmission(UUID userId, Game game, ParsedSubmission value, String rawText, SubmissionMethod method) {
    // Serialize submissions for one profile so the existence check remains race-safe without poisoning
    // the surrounding interaction transaction with a caught unique-constraint exception.
    profiles.findByIdForUpdate(userId)
        .orElseThrow(() -> new ApiException(409, "PROFILE_REQUIRED", "Finish setting up your profile."));
    Optional<Submission> existing = submissions.findByUserIdAndGameIdAndPuzzleKey(userId, game.id, value.puzzleKey());
    if (existing.isEmpty()) existing = submissions.findByUserIdAndGameIdAndPlayedOn(userId, game.id, value.playedOn());
    if (existing.isPresent()) {
      if (existing.get().submissionMethod == method && existing.get().rawText.equals(rawText)) return existing.get().id;
      throw duplicate(game);
    }
    try { return submissions.saveAndFlush(new Submission(userId, game.id, value, rawText, method, Instant.now())).id; }
    catch (DataIntegrityViolationException exception) { throw duplicate(game); }
  }

  private String manualAudit(ManualSubmissionRequest request) {
    Map<String, Object> audit = new LinkedHashMap<>();
    audit.put("game", request.game());
    if (request.puzzleId() != null && !request.puzzleId().isBlank()) audit.put("puzzleId", request.puzzleId().trim());
    audit.put("puzzleDate", request.puzzleDate().toString()); audit.put("completionStatus", request.completionStatus());
    audit.put("values", new TreeMap<>(request.values()));
    try { return JSON.writeValueAsString(audit); }
    catch (JacksonException exception) { throw new ApiException(422, "INVALID_MANUAL_RESULT", "Manual result could not be recorded."); }
  }

  ProfileResponse profile(CurrentUser current, UUID shareId) {
    Profile profile = profiles.findByShareId(shareId).orElseThrow(this::notFound);
    if (!profile.userId.equals(current.id()) && !memberships.sharesLeaderboard(current.id(), profile.userId)) throw notFound();
    List<Submission> values = submissions.findByUserIdOrderByPlayedOnDescCreatedAtDesc(profile.userId);
    List<GameStatsView> gameStats = statsByGame(values);
    return new ProfileResponse(profileView(profile), profile.userId.equals(current.id()), legacyStats(gameStats), gameStats,
        values.stream().map(this::scoreView).toList());
  }

  private Profile requireProfile(UUID userId) { return profiles.findById(userId).orElseThrow(() -> new ApiException(409, "PROFILE_REQUIRED", "Finish setting up your profile.")); }
  private Game requireGame(String slug) {
    return games.findActiveBySlug(slug).orElseThrow(() -> new ApiException(404, "GAME_NOT_FOUND", "Game not found."));
  }
  private void requireMembership(UUID boardId, UUID userId) { if (!memberships.existsByIdLeaderboardIdAndIdUserId(boardId, userId)) throw notFound(); }
  private Leaderboard requireOwnedBoard(UUID id, UUID userId) {
    Leaderboard board = boards.findById(id).orElseThrow(this::notFound);
    if (!board.ownerId.equals(userId)) throw new ApiException(403, "NOT_OWNER", "Only the leaderboard owner can do that.");
    return board;
  }
  private void requireUnlocked(Leaderboard board) {
    if (!billing.isBoardAccessible(board, Instant.now())) {
      throw new ApiException(402, "OWNER_SUBSCRIPTION_REQUIRED", "This leaderboard is locked until its owner restores their subscription.");
    }
  }
  private void requireAllGameCapacity(UUID ownerId, Instant now) {
    int limit = billing.allGameLimit(ownerId, now);
    if (limit == 0) {
      throw new ApiException(402, "SUBSCRIPTION_REQUIRED",
          "Subscribe for $2 or $5 per month to create an all-game leaderboard.");
    }
    if (boards.countByOwnerIdAndBoardType(ownerId, BoardType.MULTI_GAME) >= limit) {
      throw new ApiException(409, "ALL_GAME_LIMIT_REACHED",
          "Your tier's all-game leaderboard limit has been reached. Upgrade to the $5 tier for up to five.");
    }
  }
  private UUID createBoard(UUID ownerId, String rawName, Game game, BoardType boardType, Collection<UUID> memberIds) {
    Profile owner = profiles.findByIdForUpdate(ownerId)
        .orElseThrow(() -> new ApiException(409, "PROFILE_REQUIRED", "Finish setting up your profile."));
    String name = rawName.trim();
    if (name.length() < 2 || name.length() > 48) throw new ApiException(400, "INVALID_NAME", "Use a name between 2 and 48 characters.");
    Instant now = Instant.now();
    BoardCreationBasis basis;
    if (boardType == BoardType.MULTI_GAME) {
      requireAllGameCapacity(ownerId, now);
      basis = BoardCreationBasis.SUBSCRIPTION;
    } else if (boards.countByOwnerIdAndBoardType(ownerId, BoardType.SINGLE_GAME) < BillingService.SINGLE_GAME_LIMIT) {
      basis = BoardCreationBasis.FREE;
      if (owner.freeLeaderboardClaimedAt == null) {
        owner.freeLeaderboardClaimedAt = now;
        profiles.save(owner);
      }
    } else {
      throw new ApiException(409, "SINGLE_GAME_LIMIT_REACHED",
          "You already own five single-game leaderboards.");
    }
    Leaderboard board = boards.save(new Leaderboard(ownerId, name, game == null ? null : game.id, boardType, basis, now));
    LinkedHashSet<UUID> uniqueMembers = new LinkedHashSet<>(memberIds);
    uniqueMembers.add(ownerId);
    uniqueMembers.forEach(memberId -> memberships.save(new Membership(board.id, memberId, ownerId, now)));
    return board.id;
  }
  private ProfileView profileView(Profile p) { return new ProfileView(p.userId, p.shareId, p.displayName, p.avatarColor, p.createdAt); }
  private GameView gameView(Game game) {
    GameAdapter adapter = adapters.require(game.slug);
    return new GameView(game.id, game.slug, game.name, game.sourceUrl, game.rankingDirection,
        adapter.scoreFormat(), adapter.submissionConfig(), adapters.manualEntryConfig(game.slug),
        adapters.submissionFormats(game.slug));
  }
  private BoardSummary boardSummary(Leaderboard board, UUID viewer) {
    List<Game> allowedGames = allowedGames(board);
    if (allowedGames.isEmpty()) {
      throw new ApiException(409, "NO_ACTIVE_GAMES", "No active games are available for this leaderboard.");
    }
    return new BoardSummary(board.id, board.name, board.ownerId, board.ownerId.equals(viewer),
        gameView(allowedGames.getFirst()), boardType(board), allowedGames.stream().map(this::gameView).toList(),
        !billing.isBoardAccessible(board, Instant.now()), board.createdAt);
  }

  private BoardType boardType(Leaderboard board) {
    return board.boardType == null ? BoardType.SINGLE_GAME : board.boardType;
  }

  private List<Game> allowedGames(Leaderboard board) {
    if (boardType(board) == BoardType.MULTI_GAME) return games.activeGames();
    Game game = games.findById(board.gameId)
        .orElseThrow(() -> new ApiException(404, "GAME_NOT_FOUND", "The leaderboard's game no longer exists."));
    if (!game.active) throw new ApiException(400, "INACTIVE_GAME", "That game is not currently active.");
    return List.of(game);
  }

  private Game selectGame(Leaderboard board, List<Game> allowedGames, String rawSlug) {
    if (allowedGames.isEmpty()) {
      throw new ApiException(409, "NO_ACTIVE_GAMES", "No active games are available for this leaderboard.");
    }
    if (rawSlug == null || rawSlug.isBlank()) return allowedGames.getFirst();
    String slug = rawSlug.trim();
    return allowedGames.stream().filter(game -> game.slug.equals(slug)).findFirst().orElseThrow(() ->
        new ApiException(400, "GAME_NOT_ALLOWED", "That game is not available on this leaderboard."));
  }

  private Game selectLeaderboardGame(Leaderboard board, List<Game> allowedGames, String rawSlug,
                                     List<UUID> memberIds) {
    if (rawSlug != null && !rawSlug.isBlank() || boardType(board) != BoardType.MULTI_GAME) {
      return selectGame(board, allowedGames, rawSlug);
    }
    if (allowedGames.isEmpty()) return selectGame(board, allowedGames, rawSlug);
    Map<UUID, Game> gamesById = allowedGames.stream().collect(Collectors.toMap(game -> game.id, game -> game));
    return submissions.findFirstByUserIdInAndGameIdInOrderByPlayedOnDescCreatedAtDesc(
            memberIds, new ArrayList<>(gamesById.keySet()))
        .map(value -> gamesById.get(value.gameId))
        .filter(Objects::nonNull)
        .orElse(allowedGames.getFirst());
  }
  private MemberView memberView(Profile p, UUID ownerId) { return new MemberView(p.userId, p.shareId, p.displayName, p.avatarColor, p.userId.equals(ownerId)); }
  private ScoreView scoreView(Submission value) {
    Game game = games.findById(value.gameId).orElseThrow(this::notFound);
    return new ScoreView(value.id, value.puzzleKey, value.playedOn, value.primaryScore, game.name, game.slug,
        gameView(game), value.metrics, value.submissionMethod.name());
  }
  private List<GameStatsView> statsByGame(List<Submission> values) {
    return values.stream().collect(Collectors.groupingBy(value -> value.gameId)).entrySet().stream().map(entry -> {
      Game game = games.findById(entry.getKey()).orElseThrow(this::notFound);
      StatsView stats = stats(entry.getValue(), game);
      return new GameStatsView(gameView(game), stats.average(), stats.best(), stats.currentStreak(), stats.results());
    }).sorted(Comparator.comparing(value -> value.game().name(), String.CASE_INSENSITIVE_ORDER)).toList();
  }
  private StatsView stats(List<Submission> values, Game game) {
    if (values.isEmpty()) return new StatsView(null, null, 0, 0);
    BigDecimal average = values.stream().map(value -> value.primaryScore).reduce(BigDecimal.ZERO, BigDecimal::add)
        .divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP);
    Comparator<BigDecimal> direction = BigDecimal::compareTo;
    BigDecimal best = "higher".equals(game.rankingDirection)
        ? values.stream().map(value -> value.primaryScore).max(direction).orElse(null)
        : values.stream().map(value -> value.primaryScore).min(direction).orElse(null);
    return new StatsView(average, best, streak(values, ZoneId.of(game.releaseTimezone)), values.size());
  }
  private StatsView legacyStats(List<GameStatsView> values) {
    if (values.isEmpty()) return new StatsView(null, null, 0, 0);
    GameStatsView first = values.getFirst();
    return new StatsView(first.average(), first.best(), first.currentStreak(), first.results());
  }
  private int streak(List<Submission> values, ZoneId timezone) {
    Set<LocalDate> dates = values.stream().map(value -> value.playedOn).collect(Collectors.toSet());
    LocalDate today = LocalDate.now(timezone);
    LocalDate cursor = dates.contains(today) ? today : dates.contains(today.minusDays(1)) ? today.minusDays(1) : null;
    int streak = 0;
    while (cursor != null && dates.contains(cursor)) { streak++; cursor = cursor.minusDays(1); }
    return streak;
  }
  private ApiException unauthorized() { return new ApiException(401, "UNAUTHORIZED", "Sign in first."); }
  private ApiException notFound() { return new ApiException(404, "NOT_FOUND", "Resource not found."); }
  private ApiException duplicate(Game game) {
    return new ApiException(409, "DUPLICATE_SUBMISSION", "You already saved a result for this " + game.name + " puzzle.");
  }
  record DiscordSubmissionResult(UUID id, Game game, ParsedSubmission submission) {}
  private int metricCount(Submission value, String key) {
    Object metric = value.metrics.get(key);
    return metric instanceof Number number ? number.intValue() : 0;
  }
  private record RankingSeed(Profile profile, BigDecimal average, Integer gamesPlayed, int losses, int assistanceTier,
                             int assistedResults, int unknownAssistanceResults, int hints, int mistakes, int beginnerAssists,
                             int manualResults) {
    boolean sameResult(RankingSeed other) {
      return losses == other.losses && assistanceTier == other.assistanceTier && average.compareTo(other.average) == 0
          && gamesPlayed.equals(other.gamesPlayed);
    }
  }
  private record LeaderboardData(Leaderboard board, List<Game> allowedGames, List<Profile> memberProfiles,
                                 List<UUID> memberIds, Game game, List<Submission> scores) {}
}
