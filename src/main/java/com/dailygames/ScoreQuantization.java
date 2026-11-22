package com.dailygames;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

enum MetricDirection { ASCENDING, DESCENDING }
enum CompletionPolicy { COMPLETION_FIRST, METRICS_FIRST }

record NormalizedScore(String gameId, String puzzleId, String completionStatus, BigDecimal nativeScore,
                       String scoreUnit, String scoreDirection, BigDecimal rankingPrimary,
                       BigDecimal rankingSecondary, MetricDirection rankingPrimaryDirection,
                       MetricDirection rankingSecondaryDirection, String displayScore, String gameMode,
                       String difficulty, String compatibilityKey, CompletionPolicy completionPolicy) {}

record ScoreStrategySpec(String gameId, String rankingRule, String primaryMetric,
                         MetricDirection primaryDirection, String secondaryMetric,
                         MetricDirection secondaryDirection, List<String> compatibilityFields,
                         boolean completionOnlyAllowed, CompletionPolicy completionPolicy) {}

interface ScoreQuantizer {
  ScoreStrategySpec spec();
  NormalizedScore quantize(ParsedSubmission submission, String fallbackDirection);
}

@Component
class ScoreQuantizerRegistry {
  private static final MetricDirection ASC = MetricDirection.ASCENDING;
  private static final MetricDirection DESC = MetricDirection.DESCENDING;
  private static final List<ScoreStrategySpec> CORE_SPECS = List.of(
      spec("spelling-bee", "percentage of verified daily maximum; otherwise ordinal rank", "percentOfMaximum", DESC, "rankOrdinal", ASC, List.of(), true),
      spec("quordle", "fewest failed boards, then lowest total with failed boards worth 10", "failed", ASC, "penalizedGuessTotal", ASC, List.of("gameMode"), false),
      spec("octordle", "verified native mode score; otherwise failures then penalized total", "failed", ASC, "penalizedGuessTotal", ASC, List.of("gameMode"), false),
      spec("dordle", "fewest failures, then penalized total using the verified attempt limit", "failed", ASC, "penalizedGuessTotal", ASC, List.of("gameMode"), false),
      spec("waffle", "fewest swaps, or highest stars when swaps are absent", "swapsUsed", ASC, "earnedStars", DESC, List.of("gameMode"), false),
      spec("squaredle", "highest percentage of required words; bonus words are separate", "requiredWordPercentage", DESC, null, null, List.of("gameMode"), false),
      spec("weaver", "fewest valid word transitions", "transitions", ASC, null, null, List.of(), false),
      spec("letter-boxed", "fewest words in a completed chain", "wordsUsed", ASC, null, null, List.of(), false),
      spec("guardian-quick-crossword", "lowest explicitly shared completion time", "completionTimeSeconds", ASC, null, null, List.of(), true),
      spec("nyt-sudoku", "lowest completion time within difficulty", "completionTimeSeconds", ASC, null, null, List.of("difficulty"), false),
      spec("nyt-tiles", "highest verified native streak; otherwise completion only", "nativeStreak", DESC, null, null, List.of("gameMode"), true),
      spec("mathler", "fewest guesses", "attemptsUsed", ASC, null, null, List.of("gameMode"), false),
      spec("kenken", "lowest completion time within grid size and difficulty", "completionTimeSeconds", ASC, null, null, List.of("gridSize", "difficulty"), false),
      spec("kakuro", "lowest completion time within size and difficulty", "completionTimeSeconds", ASC, null, null, List.of("puzzleSize", "difficulty"), false),
      spec("nonograms-picross", "lowest completion time, then fewest mistakes", "completionTimeSeconds", ASC, "mistakes", ASC, List.of("puzzleSize", "difficulty"), false),
      spec("geoguessr-daily-challenge", "highest native points out of 25,000", "nativeScore", DESC, null, null, List.of("gameMode"), false),
      spec("travle", "fewest verified extra guesses, then fewest hints", "extraGuesses", ASC, "hints", ASC, List.of("gameMode"), false),
      spec("flagle", "fewest guesses", "attemptsUsed", ASC, null, null, List.of(), false),
      spec("statele", "fewest guesses", "attemptsUsed", ASC, null, null, List.of(), false),
      spec("timeguessr", "highest native point total", "nativeScore", DESC, null, null, List.of("gameMode"), false),
      spec("framed", "fewest frames revealed", "framesRevealed", ASC, null, null, List.of(), false),
      spec("moviedle", "fewest verified guesses or clues", "attemptsUsed", ASC, null, null, List.of("gameMode"), false),
      spec("costcodle", "fewest guesses; price accuracy retained separately", "attemptsUsed", ASC, "priceError", ASC, List.of(), false),
      spec("heardle-decades", "fewest audio snippets", "snippetsUsed", ASC, null, null, List.of("gameMode"), false),
      spec("bandle", "fewest instruments or clues", "cluesUsed", ASC, null, null, List.of("gameMode"), false),
      spec("immaculate-grid", "highest correct cells, then lowest rarity when present", "correctCells", DESC, "rarityScore", ASC, List.of("sport", "gameMode"), false),
      spec("weddle", "fewest guesses", "attemptsUsed", ASC, null, null, List.of("gameMode"), false),
      spec("poeltl", "fewest guesses", "attemptsUsed", ASC, null, null, List.of("gameMode"), false));
  private static final List<ScoreStrategySpec> SPECS = fandomSpecs();
  private final Map<String, ScoreQuantizer> quantizers;

  ScoreQuantizerRegistry() {
    Map<String, ScoreQuantizer> values = new LinkedHashMap<>();
    SPECS.forEach(spec -> values.put(spec.gameId(), new ConfiguredScoreQuantizer(spec)));
    quantizers = Collections.unmodifiableMap(values);
  }

  private static List<ScoreStrategySpec> fandomSpecs() {
    List<ScoreStrategySpec> values = new ArrayList<>(CORE_SPECS);
    for (String mode : LoldleParser.MODES)
      values.add(spec("loldle-" + mode, "fewest guesses, with unassisted results first",
          "attemptsUsed", ASC, null, null, List.of(), false));
    for (FandomGameDefinition game : FandomGames.ALL) {
      List<String> compatibility = game.hasModes() ? List.of("gameMode") : List.of();
      values.add(switch (game.scoreKind()) {
        case ATTEMPTS -> spec(game.slug(), "fewest native guesses or revealed clues", "attemptsUsed", ASC,
            null, null, compatibility, false);
        case CONNECTION_MISTAKES -> spec(game.slug(), "fewest incorrect group submissions", "mistakes", ASC,
            null, null, compatibility, false);
        case GRID_RARITY -> spec(game.slug(), "most correct cells, then lowest available rarity", "correctCells", DESC,
            "rarityScore", ASC, compatibility, false);
        case POKEDOKU_UNIQUENESS -> spec(game.slug(), "lowest UNIQ score, then most correct cells", "uniquenessScore", ASC,
            "correctCells", DESC, compatibility, false, CompletionPolicy.METRICS_FIRST);
      });
    }
    return List.copyOf(values);
  }

  Collection<ScoreStrategySpec> strategies() { return quantizers.values().stream().map(ScoreQuantizer::spec).toList(); }
  Optional<ScoreStrategySpec> strategy(String gameId) { return Optional.ofNullable(quantizers.get(gameId)).map(ScoreQuantizer::spec); }

  NormalizedScore quantize(String gameId, ParsedSubmission submission, String fallbackDirection) {
    ScoreQuantizer quantizer = quantizers.get(gameId);
    if (quantizer != null) return quantizer.quantize(submission, fallbackDirection);
    return quantizeConfigured(gameId, null, submission, fallbackDirection);
  }

  private NormalizedScore quantizeConfigured(String gameId, ScoreStrategySpec spec, ParsedSubmission submission,
                                              String fallbackDirection) {
    Map<String, Object> metrics = submission.metrics();
    BigDecimal primary = spec == null ? decimal(metrics.get("rankingPrimary")) : decimal(metrics.get(spec.primaryMetric()));
    BigDecimal secondary = spec == null ? decimal(metrics.get("rankingSecondary")) :
        spec.secondaryMetric() == null ? null : decimal(metrics.get(spec.secondaryMetric()));
    MetricDirection primaryDirection = spec == null
        ? databaseDirection(fallbackDirection)
        : spec.primaryDirection();
    MetricDirection secondaryDirection = spec == null
        ? direction(metrics.get("rankingSecondaryDirection"), null)
        : spec.secondaryDirection();
    if (primary == null && spec == null) primary = submission.primaryScore();
    BigDecimal nativeScore = decimal(metrics.get("nativeScore"));
    if (nativeScore == null) nativeScore = submission.primaryScore();
    String mode = string(metrics.get("gameMode"));
    String difficulty = string(metrics.get("difficulty"));
    String compatibility = compatibility(spec, metrics, mode, difficulty);
    return new NormalizedScore(gameId, submission.puzzleKey(),
        Objects.toString(metrics.getOrDefault("completionStatus", "UNKNOWN")), nativeScore,
        Objects.toString(metrics.getOrDefault("scoreUnit", "number")),
        primaryDirection == MetricDirection.DESCENDING ? "HIGHER_IS_BETTER" : "LOWER_IS_BETTER",
        primary, secondary, primaryDirection, secondaryDirection,
        Objects.toString(metrics.getOrDefault("displayScore", submission.primaryScore().toPlainString())),
        mode, difficulty, compatibility, spec == null ? CompletionPolicy.COMPLETION_FIRST : spec.completionPolicy());
  }

  private final class ConfiguredScoreQuantizer implements ScoreQuantizer {
    private final ScoreStrategySpec spec;
    private ConfiguredScoreQuantizer(ScoreStrategySpec spec) { this.spec = spec; }
    @Override public ScoreStrategySpec spec() { return spec; }
    @Override public NormalizedScore quantize(ParsedSubmission submission, String fallbackDirection) {
      return quantizeConfigured(spec.gameId(), spec, submission, fallbackDirection);
    }
  }

  int compare(NormalizedScore left, NormalizedScore right) {
    if (left.completionPolicy() == CompletionPolicy.COMPLETION_FIRST) {
      int completion = compareCompletion(left, right);
      if (completion != 0) return completion;
    }
    int primary = compareNullable(left.rankingPrimary(), right.rankingPrimary(), left.rankingPrimaryDirection());
    if (primary != 0) return primary;
    int secondary = compareNullable(left.rankingSecondary(), right.rankingSecondary(), left.rankingSecondaryDirection());
    if (secondary != 0) return secondary;
    return left.completionPolicy() == CompletionPolicy.METRICS_FIRST ? compareCompletion(left, right) : 0;
  }

  boolean sameRank(NormalizedScore left, NormalizedScore right) {
    return completionTier(left.completionStatus()) == completionTier(right.completionStatus()) &&
        numericallyEqual(left.rankingPrimary(), right.rankingPrimary()) &&
        numericallyEqual(left.rankingSecondary(), right.rankingSecondary());
  }

  private static ScoreStrategySpec spec(String id, String rule, String primary, MetricDirection primaryDirection,
                                        String secondary, MetricDirection secondaryDirection,
                                        List<String> compatibility, boolean completionOnly) {
    return new ScoreStrategySpec(id, rule, primary, primaryDirection, secondary, secondaryDirection,
        List.copyOf(compatibility), completionOnly, CompletionPolicy.COMPLETION_FIRST);
  }
  private static ScoreStrategySpec spec(String id, String rule, String primary, MetricDirection primaryDirection,
                                        String secondary, MetricDirection secondaryDirection,
                                        List<String> compatibility, boolean completionOnly, CompletionPolicy completionPolicy) {
    return new ScoreStrategySpec(id, rule, primary, primaryDirection, secondary, secondaryDirection,
        List.copyOf(compatibility), completionOnly, completionPolicy);
  }
  private String compatibility(ScoreStrategySpec spec, Map<String, Object> metrics, String mode, String difficulty) {
    if (spec == null || spec.compatibilityFields().isEmpty()) return "default";
    List<String> values = new ArrayList<>();
    for (String field : spec.compatibilityFields()) {
      Object value = metrics.get(field);
      values.add(field + "=" + (value == null ? "UNKNOWN" : value));
    }
    return String.join("|", values);
  }
  private int compareNullable(BigDecimal left, BigDecimal right, MetricDirection direction) {
    if (left == null && right == null) return 0;
    if (left == null) return 1;
    if (right == null) return -1;
    int compared = left.compareTo(right);
    return direction == MetricDirection.DESCENDING ? -compared : compared;
  }
  private boolean numericallyEqual(BigDecimal left, BigDecimal right) {
    return left == null ? right == null : right != null && left.compareTo(right) == 0;
  }
  private int completionTier(String status) {
    return switch (status) { case "WIN" -> 0; case "UNKNOWN" -> 1; case "LOSS", "INCOMPLETE" -> 2; default -> 3; };
  }
  private int compareCompletion(NormalizedScore left, NormalizedScore right) {
    return Integer.compare(completionTier(left.completionStatus()), completionTier(right.completionStatus()));
  }
  private BigDecimal decimal(Object value) {
    if (value instanceof BigDecimal decimal) return decimal;
    if (value instanceof Number number) return new BigDecimal(number.toString());
    if (value instanceof String text) try { return new BigDecimal(text); } catch (NumberFormatException ignored) {}
    return null;
  }
  private String string(Object value) { return value == null ? null : value.toString(); }
  private MetricDirection direction(Object value, MetricDirection fallback) {
    if (value == null) return fallback == null ? MetricDirection.ASCENDING : fallback;
    try { return MetricDirection.valueOf(value.toString()); }
    catch (IllegalArgumentException exception) { return fallback == null ? MetricDirection.ASCENDING : fallback; }
  }
  private MetricDirection databaseDirection(String direction) {
    return "higher".equalsIgnoreCase(direction) ? MetricDirection.DESCENDING : MetricDirection.ASCENDING;
  }
}
