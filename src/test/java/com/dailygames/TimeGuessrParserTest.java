package com.dailygames;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TimeGuessrParserTest {
  private final TimeGuessrParser parser = new TimeGuessrParser();
  private static final String DETAILED = """
      TimeGuessr #807 — 40,693/50,000
      1️⃣ 🏆8734 - 📅5y - 🌍16.5 km
      2️⃣ 🏆6874 - 📅13y - 🌍6.5 km
      3️⃣ 🏆9058 - 📅4y - 🌍35.5 km
      4️⃣ 🏆9794 - 📅2y - 🌍284.7 m
      5️⃣ 🏆6233 - 📅15y - 🌍267.5 km
      """;

  @Test void parsesNativeTotalAndVerifiedRoundBreakdown() {
    ParsedSubmission result = parser.parse(DETAILED);
    assertEquals(LocalDate.of(2025, 8, 17), result.playedOn());
    assertEquals(new BigDecimal("40693"), result.primaryScore());
    assertEquals(List.of(8734, 6874, 9058, 9794, 6233), result.metrics().get("roundScores"));
    assertEquals("40,693 pts", result.metrics().get("displayScore"));
    assertEquals("DESCENDING", result.metrics().get("rankingPrimaryDirection"));
  }

  @Test void acceptsTheSummaryOnlyVariantAndNarrowNoBreakSpaces() {
    ParsedSubmission result = parser.parse("TimeGuessr #808 — 39 119/50 000");
    assertEquals(new BigDecimal("39119"), result.primaryScore());
    assertFalse(result.metrics().containsKey("roundScores"));
  }

  @Test void rejectsOutOfRangeAndContradictoryBreakdowns() {
    assertThrows(ApiException.class, () -> parser.parse("TimeGuessr #807 — 50,001/50,000"));
    assertThrows(ApiException.class, () -> parser.parse(DETAILED.replace("6233", "6232")));
  }
}
