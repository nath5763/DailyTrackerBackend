package com.dailygames;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ScoreQuantizerRegistryTest {
  private final ScoreQuantizerRegistry registry = new ScoreQuantizerRegistry();

  @Test void definesASeparateStrategyForEveryRequestedGame() {
    assertEquals(74, registry.strategies().size());
    assertEquals(74, registry.strategies().stream().map(ScoreStrategySpec::gameId).distinct().count());
    for (String mode : LoldleParser.MODES) {
      var strategy = registry.strategy("loldle-" + mode).orElseThrow();
      assertEquals("attemptsUsed", strategy.primaryMetric());
      assertEquals(MetricDirection.ASCENDING, strategy.primaryDirection());
    }
  }

  @Test void comparesFarflungByHighestDistanceAndProducesTies() {
    NormalizedScore shortTrip = score("farflung", 195, Map.of(
        "completionStatus", "WIN", "distanceKm", 195, "gameMode", "Daily"));
    NormalizedScore longTrip = score("farflung", 350, Map.of(
        "completionStatus", "WIN", "distanceKm", 350, "gameMode", "Daily"));
    NormalizedScore tied = score("farflung", 350, Map.of(
        "completionStatus", "WIN", "distanceKm", 350, "gameMode", "Daily"));
    assertTrue(registry.compare(longTrip, shortTrip) < 0);
    assertTrue(registry.sameRank(longTrip, tied));
  }

  @Test void comparesQuordleByFailuresBeforePenalizedTotalAndProducesTies() {
    NormalizedScore win = score("quordle", 29, Map.of("completionStatus", "WIN", "failed", 0,
        "penalizedGuessTotal", 29, "gameMode", "Daily"));
    NormalizedScore loss = score("quordle", 20, Map.of("completionStatus", "LOSS", "failed", 1,
        "penalizedGuessTotal", 20, "gameMode", "Daily"));
    NormalizedScore tied = score("quordle", 29, Map.of("completionStatus", "WIN", "failed", 0,
        "penalizedGuessTotal", 29, "gameMode", "Daily"));
    assertTrue(registry.compare(win, loss) < 0);
    assertTrue(registry.sameRank(win, tied));
  }

  @Test void comparesTravleByExtraGuessesThenHints() {
    NormalizedScore plusOne = score("travle", 1, Map.of("completionStatus", "WIN", "extraGuesses", 1,
        "hints", 3, "gameMode", "Daily"));
    NormalizedScore plusTwo = score("travle", 2, Map.of("completionStatus", "WIN", "extraGuesses", 2,
        "hints", 0, "gameMode", "Daily"));
    NormalizedScore fewerHints = score("travle", 1, Map.of("completionStatus", "WIN", "extraGuesses", 1,
        "hints", 0, "gameMode", "Daily"));
    NormalizedScore same = score("travle", 1, Map.of("completionStatus", "WIN", "extraGuesses", 1,
        "hints", 0, "gameMode", "Daily"));
    assertTrue(registry.compare(plusOne, plusTwo) < 0);
    assertTrue(registry.compare(fewerHints, plusOne) < 0);
    assertTrue(registry.sameRank(fewerHints, same));
    var strategy = registry.strategy("travle").orElseThrow();
    assertEquals("extraGuesses", strategy.primaryMetric());
    assertEquals("hints", strategy.secondaryMetric());
  }

  @Test void missingImmaculateGridRarityRemainsNullInsteadOfBecomingZero() {
    NormalizedScore missing = score("immaculate-grid", 8,
        Map.of("completionStatus", "WIN", "correctCells", 8, "sport", "baseball"));
    NormalizedScore known = score("immaculate-grid", 8,
        Map.of("completionStatus", "WIN", "correctCells", 8, "rarityScore", 45, "sport", "baseball"));
    assertNull(missing.rankingSecondary());
    assertEquals(new BigDecimal("45"), known.rankingSecondary());
    assertTrue(registry.compare(known, missing) < 0);
  }

  @Test void completionOnlyStrategiesDoNotFabricateRankingValues() {
    NormalizedScore completion = score("guardian-quick-crossword", 0, Map.of("completionStatus", "WIN"));
    assertNull(completion.rankingPrimary());
  }

  @Test void pokedokuRanksUniquenessThenCellsThenCompletion() {
    NormalizedScore incompleteRare = score("pokedoku", 10, Map.of(
        "completionStatus", "LOSS", "uniquenessScore", 10, "correctCells", 8));
    NormalizedScore completedCommon = score("pokedoku", 20, Map.of(
        "completionStatus", "WIN", "uniquenessScore", 20, "correctCells", 9));
    assertTrue(registry.compare(incompleteRare, completedCommon) < 0);

    NormalizedScore eightCells = score("pokedoku", 10, Map.of(
        "completionStatus", "LOSS", "uniquenessScore", 10, "correctCells", 8));
    NormalizedScore sevenCells = score("pokedoku", 10, Map.of(
        "completionStatus", "LOSS", "uniquenessScore", 10, "correctCells", 7));
    assertTrue(registry.compare(eightCells, sevenCells) < 0);

    NormalizedScore completed = score("pokedoku", 10, Map.of(
        "completionStatus", "WIN", "uniquenessScore", 10, "correctCells", 9));
    NormalizedScore incomplete = score("pokedoku", 10, Map.of(
        "completionStatus", "LOSS", "uniquenessScore", 10, "correctCells", 9));
    NormalizedScore tied = score("pokedoku", 10, Map.of(
        "completionStatus", "WIN", "uniquenessScore", "10.0", "correctCells", 9));
    assertTrue(registry.compare(completed, incomplete) < 0);
    assertTrue(registry.sameRank(completed, tied));
  }

  @Test void configuredNativeMetricsOverrideStaleDerivedFieldsAndMissingNativeMetricsStayNull() {
    NormalizedScore historical = score("pokedoku", 999, Map.of(
        "completionStatus", "WIN", "uniquenessScore", "42.50", "correctCells", 9,
        "rankingPrimary", 1, "rankingSecondary", 0,
        "rankingPrimaryDirection", "DESCENDING", "rankingSecondaryDirection", "ASCENDING"));
    assertEquals(new BigDecimal("42.50"), historical.rankingPrimary());
    assertEquals(new BigDecimal("9"), historical.rankingSecondary());
    assertEquals(MetricDirection.ASCENDING, historical.rankingPrimaryDirection());
    assertEquals(MetricDirection.DESCENDING, historical.rankingSecondaryDirection());

    NormalizedScore missing = score("pokedoku", 7, Map.of(
        "completionStatus", "LOSS", "correctCells", 7, "rankingPrimary", 7));
    assertNull(missing.rankingPrimary());
  }

  @Test void currentGameDirectionOverridesStaleDerivedDirection() {
    NormalizedScore low = registry.quantize("fermi", new ParsedSubmission("1", 1, LocalDate.of(2026, 1, 1),
        BigDecimal.ONE, Map.of("rankingPrimary", 1, "rankingPrimaryDirection", "DESCENDING"), 1), "lower");
    NormalizedScore high = registry.quantize("fermi", new ParsedSubmission("1", 1, LocalDate.of(2026, 1, 1),
        BigDecimal.TEN, Map.of("rankingPrimary", 10, "rankingPrimaryDirection", "DESCENDING"), 1), "lower");
    assertEquals(MetricDirection.ASCENDING, low.rankingPrimaryDirection());
    assertTrue(registry.compare(low, high) < 0);
  }

  @Test void allFandomAttemptProfilesRemainCompletionFirstAndLowerAttemptsWins() {
    Stream<String> attemptsGames = FandomGames.ALL.stream()
        .filter(game -> game.scoreKind() == FandomScoreKind.ATTEMPTS).map(FandomGameDefinition::slug);
    attemptsGames.forEach(gameId -> {
      ScoreStrategySpec strategy = registry.strategy(gameId).orElseThrow();
      assertEquals(CompletionPolicy.COMPLETION_FIRST, strategy.completionPolicy(), gameId);
      assertEquals(MetricDirection.ASCENDING, strategy.primaryDirection(), gameId);
      NormalizedScore win = score(gameId, 8, Map.of("completionStatus", "WIN", "attemptsUsed", 8));
      NormalizedScore loss = score(gameId, 1, Map.of("completionStatus", "LOSS", "attemptsUsed", 1));
      NormalizedScore fast = score(gameId, 2, Map.of("completionStatus", "WIN", "attemptsUsed", 2));
      assertTrue(registry.compare(win, loss) < 0, gameId);
      assertTrue(registry.compare(fast, win) < 0, gameId);
    });
  }

  @Test void pokegridKeepsItsCorrectCellsFirstCompletionFirstStrategy() {
    ScoreStrategySpec strategy = registry.strategy("pokegrid").orElseThrow();
    assertEquals("correctCells", strategy.primaryMetric());
    assertEquals(MetricDirection.DESCENDING, strategy.primaryDirection());
    assertEquals("rarityScore", strategy.secondaryMetric());
    assertEquals(MetricDirection.ASCENDING, strategy.secondaryDirection());
    assertEquals(CompletionPolicy.COMPLETION_FIRST, strategy.completionPolicy());
  }

  @Test void slipOutRanksMovesOnlyAndWinsBeforeFailures() {
    NormalizedScore elevenMoves = score("slipout", 11, Map.of("completionStatus", "WIN",
        "rankingPrimary", 11));
    NormalizedScore twelveMoves = score("slipout", 12, Map.of("completionStatus", "WIN",
        "rankingPrimary", 12));
    NormalizedScore twelveMovesTied = score("slipout", 12, Map.of("completionStatus", "WIN",
        "rankingPrimary", 12));
    NormalizedScore failed = score("slipout", 1, Map.of("completionStatus", "LOSS",
        "rankingPrimary", 1));
    assertTrue(registry.compare(elevenMoves, twelveMoves) < 0);
    assertEquals(0, registry.compare(twelveMoves, twelveMovesTied));
    assertTrue(registry.sameRank(twelveMoves, twelveMovesTied));
    assertTrue(registry.compare(twelveMovesTied, failed) < 0);
  }

  private NormalizedScore score(String gameId, int primary, Map<String, Object> metrics) {
    return registry.quantize(gameId, new ParsedSubmission("1", 1, LocalDate.of(2026, 1, 1),
        BigDecimal.valueOf(primary), metrics, 1), "lower");
  }
}
