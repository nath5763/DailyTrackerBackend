package com.dailygames;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScoreQuantizerRegistryTest {
  private final ScoreQuantizerRegistry registry = new ScoreQuantizerRegistry();

  @Test void definesASeparateStrategyForEveryRequestedGame() {
    assertEquals(28, registry.strategies().size());
    assertEquals(28, registry.strategies().stream().map(ScoreStrategySpec::gameId).distinct().count());
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

  private NormalizedScore score(String gameId, int primary, Map<String, Object> metrics) {
    return registry.quantize(gameId, new ParsedSubmission("1", 1, LocalDate.of(2026, 1, 1),
        BigDecimal.valueOf(primary), metrics, 1), "lower");
  }
}
