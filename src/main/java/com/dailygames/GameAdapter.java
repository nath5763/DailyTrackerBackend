package com.dailygames;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

import static com.dailygames.ApiModels.*;

record ParsedSubmission(String puzzleKey, Integer puzzleNumber, LocalDate playedOn, BigDecimal primaryScore,
                        Map<String, Object> metrics, int parserVersion) {}

interface GameAdapter {
  String slug();
  ScoreFormatView scoreFormat();
  SubmissionConfigView submissionConfig();
  ParsedSubmission parse(String rawText);
  ParsedSubmission validateConfirmed(ConfirmedSubmission confirmed, String rawText);
  default boolean matches(String rawText) {
    String[] lines = ShareText.lines(rawText);
    Pattern signature = Pattern.compile("(?i)^#?" + Pattern.quote(slug()) + "(?:\\b|[ ·#])");
    return Arrays.stream(lines).anyMatch(line -> signature.matcher(line).find());
  }
  default BigDecimal rankingMetric(BigDecimal primaryScore, Map<String, Object> metrics) { return primaryScore; }
  default List<ResultDetailView> resultDetails(Map<String, Object> metrics) {
    return ResultDetailFormatter.fromMetrics(slug(), metrics);
  }
  default String scoreDirection() { return "LOWER_IS_BETTER"; }
  default Set<String> supportedFormats() { return Set.of("share-text"); }
  default String parserStatus() { return "PARTIAL"; }
}

@Component
class GameAdapterRegistry {
  private final Map<String, GameAdapter> adapters;
  private final ScoreQuantizerRegistry quantizers = new ScoreQuantizerRegistry();
  private final ManualScoreRegistry manualScores = new ManualScoreRegistry();

  @Autowired
  GameAdapterRegistry(List<GameAdapter> adapters, FandomGameAdapterProvider fandom) {
    this(merge(adapters, fandom.adapters()), true);
  }

  GameAdapterRegistry(List<GameAdapter> adapters) {
    this(merge(adapters, new FandomGameAdapterProvider().adapters()), true);
  }

  private GameAdapterRegistry(List<GameAdapter> adapters, boolean merged) {
    this.adapters = adapters.stream().collect(Collectors.toUnmodifiableMap(GameAdapter::slug, Function.identity(),
        (left, right) -> { throw new IllegalStateException("Duplicate game adapter for " + left.slug()); }));
  }

  private static List<GameAdapter> merge(List<GameAdapter> nativeAdapters, List<GameAdapter> fandomAdapters) {
    List<GameAdapter> merged = new ArrayList<>(nativeAdapters);
    merged.addAll(fandomAdapters);
    return merged;
  }

  GameAdapter require(String slug) {
    GameAdapter adapter = adapters.get(slug);
    if (adapter == null) throw new ApiException(503, "GAME_UNAVAILABLE", "Game configuration is missing.");
    return adapter;
  }

  Optional<String> detectGame(String rawText) {
    Set<String> matches = adapters.values().stream().filter(adapter -> adapter.supportedFormats().contains("share-text"))
        .filter(adapter -> adapter.matches(rawText))
        .map(GameAdapter::slug).collect(Collectors.toSet());
    return matches.size() == 1 ? Optional.of(matches.iterator().next()) : Optional.empty();
  }

  ParsedSubmission parseResult(String gameId, String text) {
    GameAdapter adapter = require(gameId);
    return decorate(adapter, validateParsed(adapter.parse(text)));
  }

  Map<String, ParsedSubmission> parseLoldle(String text, LocalDate puzzleDate) {
    Map<String, ParsedSubmission> parsed = LoldleParser.parse(text, puzzleDate);
    Map<String, ParsedSubmission> decorated = new LinkedHashMap<>();
    parsed.forEach((slug, value) -> decorated.put(slug, decorate(require(slug), validateParsed(value))));
    return Collections.unmodifiableMap(decorated);
  }

  ParsedSubmission validateResult(String gameId, ConfirmedSubmission result, String text) {
    GameAdapter adapter = require(gameId);
    ParsedSubmission nativePreview = validateParsed(adapter.parse(text));
    ConfirmedSubmission nativeResult = result == null ? null : new ConfirmedSubmission(result.puzzleKey(),
        result.puzzleNumber(), result.playedOn(), result.primaryScore(),
        nativeMetrics(result.metrics(), nativePreview.metrics().keySet()), result.parserVersion());
    return decorate(adapter, validateParsed(adapter.validateConfirmed(nativeResult, text)));
  }

  ManualEntryConfigView manualEntryConfig(String gameId) { return manualScores.configFor(gameId); }

  ParsedSubmission normalizeManualResult(String gameId, ManualSubmissionRequest request) {
    GameAdapter adapter = require(gameId);
    return decorate(adapter, validateParsed(manualScores.normalize(gameId, request)));
  }

  BigDecimal getRankingMetric(String gameId, ParsedSubmission result) {
    return require(gameId).rankingMetric(result.primaryScore(), result.metrics());
  }

  List<ResultDetailView> resultDetails(String gameId, Map<String, Object> metrics) {
    List<ResultDetailView> details = require(gameId).resultDetails(metrics == null ? Map.of() : metrics);
    return details == null ? List.of() : List.copyOf(details);
  }

  NormalizedScore normalizedScore(String gameId, BigDecimal primaryScore, Map<String, Object> metrics,
                                  String puzzleKey, LocalDate playedOn, int parserVersion, String fallbackDirection) {
    return quantizers.quantize(gameId,
        new ParsedSubmission(puzzleKey, null, playedOn, primaryScore, metrics, parserVersion), fallbackDirection);
  }

  int compareScores(NormalizedScore left, NormalizedScore right) { return quantizers.compare(left, right); }
  boolean sameRank(NormalizedScore left, NormalizedScore right) { return quantizers.sameRank(left, right); }
  Collection<ScoreStrategySpec> scoreStrategies() { return quantizers.strategies(); }
  boolean supportsShareText(String gameId) {
    GameAdapter adapter = adapters.get(gameId);
    return adapter != null && adapter.supportedFormats().contains("share-text");
  }

  Set<String> submissionFormats(String gameId) {
    GameAdapter adapter = require(gameId);
    Set<String> formats = new LinkedHashSet<>(adapter.supportedFormats());
    if (manualScores.configFor(gameId).supported()) formats.add("manual-entry");
    return Collections.unmodifiableSet(formats);
  }

  Map<String, Set<String>> supportedFormats() {
    Map<String, Set<String>> formats = new LinkedHashMap<>();
    GameParserCapabilities.REQUESTED_GAME_IDS.forEach(gameId -> formats.put(gameId, Set.of()));
    adapters.values().forEach(adapter -> formats.put(adapter.slug(), adapter.supportedFormats()));
    return Collections.unmodifiableMap(formats);
  }

  List<ParserCapabilityView> capabilities() {
    return GameParserCapabilities.REQUESTED_GAME_IDS.stream().map(gameId -> {
      GameAdapter adapter = adapters.get(gameId);
      return new ParserCapabilityView(gameId, adapter == null ? "UNSUPPORTED" : adapter.parserStatus(),
          adapter == null ? Set.of() : adapter.supportedFormats());
    }).toList();
  }

  private ParsedSubmission validateParsed(ParsedSubmission parsed) {
    if (parsed == null || parsed.puzzleKey() == null || parsed.puzzleKey().isBlank() || parsed.playedOn() == null ||
        parsed.primaryScore() == null || parsed.primaryScore().signum() < 0 || parsed.metrics() == null ||
        parsed.parserVersion() < 1 || parsed.puzzleNumber() != null && parsed.puzzleNumber() < 1 ||
        parsed.playedOn().isAfter(LocalDate.now().plusDays(1))) {
      throw new ApiException(422, "INVALID_SHARE_TEXT", "The parsed result contains invalid or unsupported fields.");
    }
    Object status = parsed.metrics().get("completionStatus");
    if (status != null && !Set.of("WIN", "LOSS", "INCOMPLETE", "UNKNOWN").contains(status.toString())) {
      throw new ApiException(422, "INVALID_SHARE_TEXT", "The parsed completion status is invalid.");
    }
    return parsed;
  }

  private ParsedSubmission decorate(GameAdapter adapter, ParsedSubmission parsed) {
    Map<String, Object> metrics = new LinkedHashMap<>(parsed.metrics());
    metrics.put("gameId", adapter.slug());
    metrics.put("puzzleId", parsed.puzzleKey());
    metrics.put("puzzleDate", parsed.playedOn().toString());
    metrics.putIfAbsent("completionStatus", "UNKNOWN");
    metrics.putIfAbsent("scoreRaw", parsed.primaryScore().toPlainString());
    metrics.put("scoreValue", parsed.primaryScore());
    metrics.putIfAbsent("scoreUnit", adapter.scoreFormat().kind());
    metrics.put("scoreDirection", adapter.scoreDirection());
    metrics.put("parserVersion", parsed.parserVersion());
    ParsedSubmission intermediate = new ParsedSubmission(parsed.puzzleKey(), parsed.puzzleNumber(), parsed.playedOn(),
        parsed.primaryScore(), metrics, parsed.parserVersion());
    NormalizedScore score = quantizers.quantize(adapter.slug(), intermediate,
        "HIGHER_IS_BETTER".equals(adapter.scoreDirection()) ? "higher" : "lower");
    if (score.nativeScore() != null) metrics.put("nativeScore", score.nativeScore());
    if (score.rankingPrimary() != null) metrics.put("rankingPrimary", score.rankingPrimary());
    if (score.rankingSecondary() != null) metrics.put("rankingSecondary", score.rankingSecondary());
    metrics.put("rankingPrimaryDirection", score.rankingPrimaryDirection().name());
    if (score.rankingSecondaryDirection() != null)
      metrics.put("rankingSecondaryDirection", score.rankingSecondaryDirection().name());
    metrics.putIfAbsent("displayScore", score.displayScore());
    metrics.putIfAbsent("compatibilityKey", score.compatibilityKey());
    return new ParsedSubmission(parsed.puzzleKey(), parsed.puzzleNumber(), parsed.playedOn(), parsed.primaryScore(),
        Collections.unmodifiableMap(metrics), parsed.parserVersion());
  }

  private Map<String, Object> nativeMetrics(Map<String, Object> metrics, Set<String> nativeKeys) {
    if (metrics == null) return null;
    Map<String, Object> nativeMetrics = new LinkedHashMap<>();
    nativeKeys.forEach(key -> {
      if (metrics.containsKey(key)) nativeMetrics.put(key, metrics.get(key));
    });
    return nativeMetrics;
  }
}
