package com.dailygames;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QuordleParserTest {
  private final QuordleParser parser = new QuordleParser();

  @Test void parsesTheConfirmedLossFixtureAndQuantizesBothRankingValues() {
    ParsedSubmission result = parser.parse("Daily Quordle 1746\r\n5️⃣9️⃣\r\n8️⃣\r\nm-w.com/games/quordle/");
    assertEquals("1746", result.puzzleKey());
    assertEquals(LocalDate.of(2026, 11, 5), result.playedOn());
    assertEquals(new BigDecimal("32"), result.primaryScore());
    assertEquals("LOSS", result.metrics().get("completionStatus"));
    assertEquals(java.util.Arrays.asList(5, 9, null, 8), result.metrics().get("guesses"));
    assertEquals(3, result.metrics().get("solved"));
    assertEquals(1, result.metrics().get("failed"));
    assertEquals(32, result.metrics().get("penalizedGuessTotal"));
    assertEquals("3/4 · 32", result.metrics().get("displayScore"));
    assertEquals(List.of(new ApiModels.ResultDetailView("Q1", "5 guesses"),
        new ApiModels.ResultDetailView("Q2", "9 guesses"), new ApiModels.ResultDetailView("Q3", "X"),
        new ApiModels.ResultDetailView("Q4", "8 guesses")), parser.resultDetails(result.metrics()));
  }

  @Test void parsesAWinWithUnicodeVariationSelectors() {
    ParsedSubmission result = parser.parse("Daily Quordle 1745\n5️⃣9️⃣\n7️⃣8️⃣\nhttps://m-w.com/games/quordle/");
    assertEquals("WIN", result.metrics().get("completionStatus"));
    assertEquals(0, result.metrics().get("failed"));
    assertEquals(29, result.metrics().get("penalizedGuessTotal"));
  }

  @Test void supportsTheConfirmedRedFailureAndSequenceHeadersWithoutMixingModes() {
    ParsedSubmission daily = parser.parse("🙂Daily Quordle 1190\n🟥8️⃣\n4️⃣5️⃣\nm-w.com/games/quordle/");
    assertEquals(java.util.Arrays.asList(null, 8, 4, 5), daily.metrics().get("guesses"));
    assertEquals(27, daily.metrics().get("penalizedGuessTotal"));
    ParsedSubmission sequence = parser.parse("Daily Sequence Quordle 1190\n4️⃣5️⃣\n6️⃣7️⃣\nm-w.com/games/quordle/");
    assertEquals("Sequence:1190", sequence.puzzleKey());
    assertEquals("Sequence", sequence.metrics().get("gameMode"));
  }

  @Test void rejectsAmbiguousAndContradictoryScoreLayouts() {
    assertThrows(ApiException.class, () -> parser.parse("Daily Quordle 1746\n5️⃣\n8️⃣\nm-w.com/games/quordle/"));
    assertThrows(ApiException.class, () -> parser.parse("Daily Quordle 1746\n5️⃣9️⃣\n8️⃣\nexample.com"));
    assertThrows(ApiException.class, () -> parser.parse("5️⃣9️⃣\n8️⃣"));
  }
}
