package com.dailygames;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class NerdleParserTest {
  private final NerdleParser parser = new NerdleParser();

  @Test void parsesClassicShareAndVariationSelectors() {
    // Format and puzzle/date anchor: https://www.reddit.com/r/wordgames/comments/1uk3bp0/nerdle_review/
    String share = "nerdlegame 1622 3/6\n🟪⬛️⬛️⬛️🟪🟩⬛️⬛️\n🟩⬛️🟩🟩⬛️🟩🟩⬛️\n🟩🟩🟩🟩🟩🟩🟩🟩";
    ParsedSubmission parsed = parser.parse(share);
    assertEquals(LocalDate.of(2026, 6, 29), parsed.playedOn());
    assertEquals(BigDecimal.valueOf(3), parsed.primaryScore());
    assertEquals("WIN", parsed.metrics().get("completionStatus"));
  }

  @Test void rejectsContradictoryAndWrongWidthGrids() {
    assertThrows(ApiException.class, () -> parser.parse("Nerdle 1622 2/6\n🟩🟩🟩🟩🟩🟩🟩🟩"));
    assertThrows(ApiException.class, () -> parser.parse("Nerdle 1622 1/6\n🟩🟩🟩🟩🟩"));
  }
}
