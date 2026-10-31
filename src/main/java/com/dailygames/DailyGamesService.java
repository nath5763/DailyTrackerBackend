package com.dailygames;

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
  private final UserRepository users;
  private final ProfileRepository profiles;
  private final GameRepository games;
  private final LeaderboardRepository boards;
  private final MembershipRepository memberships;
  private final SubmissionRepository submissions;
  private final GameAdapterRegistry adapters;
  private final BillingService billing;

  DailyGamesService(UserRepository users, ProfileRepository profiles, GameRepository games, LeaderboardRepository boards,
                    MembershipRepository memberships, SubmissionRepository submissions, GameAdapterRegistry adapters,
                    BillingService billing) {
    this.users = users; this.profiles = profiles; this.games = games; this.boards = boards;
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
    return games.findByActiveTrueOrderByNameAsc().stream().map(this::gameView).toList();
  }

  @Transactional
  UUID createBoard(CurrentUser current, CreateLeaderboardRequest request) {
    Game game = requireGame(request.gameSlug());
    return createBoard(current.id(), request.name(), game, List.of(current.id()));
  }

  @Transactional
  UUID copyBoard(CurrentUser current, UUID sourceId, CopyLeaderboardRequest request) {
    requireProfile(current.id());
    requireMembership(sourceId, current.id());
    Leaderboard source = boards.findById(sourceId).orElseThrow(this::notFound);
    requireUnlocked(source);
    Game game = requireGame(request.gameSlug());
    if (source.gameId.equals(game.id)) {
      throw new ApiException(400, "SAME_GAME", "Choose a different game for the copied leaderboard.");
    }
    List<UUID> memberIds = memberships.findByIdLeaderboardId(sourceId).stream().map(member -> member.id.userId).toList();
    return createBoard(current.id(), request.name(), game, memberIds);
  }

  LeaderboardResponse board(CurrentUser current, UUID boardId, String range) {
    requireMembership(boardId, current.id());
    Leaderboard board = boards.findById(boardId).orElseThrow(this::notFound);
    requireUnlocked(board);
    Game game = games.findById(board.gameId).orElseThrow(() -> new ApiException(404, "GAME_NOT_FOUND", "Game not found."));
    List<Profile> memberProfiles = memberships.findByIdLeaderboardId(boardId).stream()
        .map(member -> profiles.findById(member.id.userId).orElseThrow(this::notFound)).toList();
    List<UUID> memberIds = memberProfiles.stream().map(profile -> profile.userId).toList();
    LocalDate today = LocalDate.now(ZoneId.of(game.releaseTimezone));
    List<Submission> scores = switch (range) {
      case "today" -> submissions.findByUserIdInAndGameIdAndPlayedOnBetween(memberIds, game.id, today, today);
      case "7d" -> submissions.findByUserIdInAndGameIdAndPlayedOnBetween(memberIds, game.id, today.minusDays(6), today);
      case "30d" -> submissions.findByUserIdInAndGameIdAndPlayedOnBetween(memberIds, game.id, today.minusDays(29), today);
      case "all" -> submissions.findByUserIdInAndGameId(memberIds, game.id);
      default -> throw new ApiException(400, "INVALID_RANGE", "Range must be today, 7d, 30d, or all.");
    };
    Map<UUID, List<Submission>> byUser = scores.stream().collect(Collectors.groupingBy(score -> score.userId));
    Comparator<RankingSeed> rankingOrder = Comparator.comparing(RankingSeed::average);
    if ("higher".equals(game.rankingDirection)) rankingOrder = rankingOrder.reversed();
    rankingOrder = rankingOrder.thenComparing(RankingSeed::gamesPlayed, Comparator.reverseOrder())
        .thenComparing(seed -> seed.profile.displayName, String.CASE_INSENSITIVE_ORDER);
    List<RankingSeed> seeds = memberProfiles.stream().filter(profile -> byUser.containsKey(profile.userId)).map(profile -> {
      List<Submission> values = byUser.get(profile.userId);
      BigDecimal average = values.stream().map(value -> value.primaryScore).reduce(BigDecimal.ZERO, BigDecimal::add)
          .divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP);
      return new RankingSeed(profile, average, values.size());
    }).sorted(rankingOrder).toList();
    List<RankingView> rankings = new ArrayList<>();
    for (int index = 0; index < seeds.size(); index++) {
      RankingSeed seed = seeds.get(index);
      rankings.add(new RankingView(index + 1, seed.profile.userId, seed.profile.shareId, seed.profile.displayName, seed.average, seed.gamesPlayed));
    }
    Set<UUID> ranked = seeds.stream().map(seed -> seed.profile.userId).collect(Collectors.toSet());
    List<MemberView> members = memberProfiles.stream().map(profile -> memberView(profile, board.ownerId)).toList();
    List<MemberView> absent = range.equals("today") ? memberProfiles.stream().filter(profile -> !ranked.contains(profile.userId)).map(profile -> memberView(profile, board.ownerId)).toList() : List.of();
    return new LeaderboardResponse(board.id, board.name, board.ownerId, gameView(game), range, board.ownerId.equals(current.id()), members, rankings, absent);
  }

  @Transactional
  void addMember(CurrentUser current, UUID boardId, UUID shareId) {
    Leaderboard board = requireOwnedBoard(boardId, current.id());
    requireUnlocked(board);
    Profile target = profiles.findByShareId(shareId).orElseThrow(() -> new ApiException(404, "ACCOUNT_NOT_FOUND", "Account not found."));
    try { memberships.saveAndFlush(new Membership(board.id, target.userId, current.id(), Instant.now())); }
    catch (DataIntegrityViolationException exception) { throw new ApiException(409, "ALREADY_MEMBER", "That account is already a member."); }
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
    return adapters.require(request.game()).parse(request.rawText());
  }

  @Transactional
  UUID submit(CurrentUser current, CreateSubmissionRequest request) {
    requireProfile(current.id());
    Game game = requireGame(request.game());
    ParsedSubmission value = adapters.require(request.game()).validateConfirmed(request.confirmed(), request.rawText());
    if (submissions.existsByUserIdAndGameIdAndPuzzleKey(current.id(), game.id, value.puzzleKey())) {
      throw new ApiException(409, "DUPLICATE_SUBMISSION", "You already saved a result for this " + game.name + " puzzle.");
    }
    try { return submissions.saveAndFlush(new Submission(current.id(), game.id, value, request.rawText(), Instant.now())).id; }
    catch (DataIntegrityViolationException exception) { throw new ApiException(409, "DUPLICATE_SUBMISSION", "You already saved a result for this " + game.name + " puzzle."); }
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
    return games.findBySlugAndActiveTrue(slug).orElseThrow(() -> new ApiException(404, "GAME_NOT_FOUND", "Game not found."));
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
  private UUID createBoard(UUID ownerId, String rawName, Game game, Collection<UUID> memberIds) {
    Profile owner = profiles.findByIdForUpdate(ownerId)
        .orElseThrow(() -> new ApiException(409, "PROFILE_REQUIRED", "Finish setting up your profile."));
    String name = rawName.trim();
    if (name.length() < 2 || name.length() > 48) throw new ApiException(400, "INVALID_NAME", "Use a name between 2 and 48 characters.");
    Instant now = Instant.now();
    BoardCreationBasis basis;
    if (owner.freeLeaderboardClaimedAt == null) {
      basis = BoardCreationBasis.FREE;
      owner.freeLeaderboardClaimedAt = now;
      profiles.save(owner);
    } else if (billing.hasUnlimitedLeaderboards(ownerId, now)) {
      basis = BoardCreationBasis.SUBSCRIPTION;
    } else {
      throw new ApiException(402, "SUBSCRIPTION_REQUIRED", "Subscribe for $2 per month to create unlimited leaderboards.");
    }
    Leaderboard board = boards.save(new Leaderboard(ownerId, name, game.id, basis, now));
    LinkedHashSet<UUID> uniqueMembers = new LinkedHashSet<>(memberIds);
    uniqueMembers.add(ownerId);
    uniqueMembers.forEach(memberId -> memberships.save(new Membership(board.id, memberId, ownerId, now)));
    return board.id;
  }
  private ProfileView profileView(Profile p) { return new ProfileView(p.userId, p.shareId, p.displayName, p.avatarColor, p.createdAt); }
  private GameView gameView(Game game) {
    GameAdapter adapter = adapters.require(game.slug);
    return new GameView(game.id, game.slug, game.name, game.sourceUrl, game.rankingDirection,
        adapter.scoreFormat(), adapter.submissionConfig());
  }
  private BoardSummary boardSummary(Leaderboard board, UUID viewer) {
    Game game = games.findById(board.gameId).orElseThrow(this::notFound);
    return new BoardSummary(board.id, board.name, board.ownerId, board.ownerId.equals(viewer), gameView(game),
        !billing.isBoardAccessible(board, Instant.now()), board.createdAt);
  }
  private MemberView memberView(Profile p, UUID ownerId) { return new MemberView(p.userId, p.shareId, p.displayName, p.avatarColor, p.userId.equals(ownerId)); }
  private ScoreView scoreView(Submission value) {
    Game game = games.findById(value.gameId).orElseThrow(this::notFound);
    return new ScoreView(value.id, value.puzzleKey, value.playedOn, value.primaryScore, game.name, game.slug, gameView(game));
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
  private record RankingSeed(Profile profile, BigDecimal average, Integer gamesPlayed) {}
}
