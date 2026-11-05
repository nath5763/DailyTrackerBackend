package com.dailygames;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionsParserTest {
  private final ConnectionsParser parser = new ConnectionsParser();
  // Shared by a player on November 2, 2026: https://www.reddit.com/r/NYTConnections/comments/1xkzbnv/monday_november_2_2026/
  private static final String PERFECT = "Connections\nPuzzle #1240\n🟩🟩🟩🟩\n🟨🟨🟨🟨\n🟦🟦🟦🟦\n🟪🟪🟪🟪";

  @Test void parsesCompleteResultAndDate() {
    ParsedSubmission result = parser.parse(PERFECT);
    assertEquals(new BigDecimal("0"), result.primaryScore());
    assertEquals(LocalDate.of(2026, 11, 2), result.playedOn());
    assertEquals(4, ((java.util.List<?>) result.metrics().get("rows")).size());
    assertEquals(result, parser.validateConfirmed(new ApiModels.ConfirmedSubmission(result.puzzleKey(),
        result.puzzleNumber(), result.playedOn(), result.primaryScore(), result.metrics(), result.parserVersion()), PERFECT));
  }

  @Test void rejectsIncompleteAndChangedResults() {
    assertThrows(ApiException.class, () -> parser.parse("Connections\nPuzzle #1240\n🟨🟨🟨🟨"));
    ParsedSubmission result = parser.parse(PERFECT);
    assertThrows(ApiException.class, () -> parser.validateConfirmed(new ApiModels.ConfirmedSubmission(result.puzzleKey(),
        result.puzzleNumber(), result.playedOn(), BigDecimal.ONE, result.metrics(), result.parserVersion()), PERFECT));
  }
}
