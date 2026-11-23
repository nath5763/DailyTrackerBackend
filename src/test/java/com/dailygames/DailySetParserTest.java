package com.dailygames;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DailySetParserTest {
  private static final String REGULAR = "The Daily Set 🎯 Nov 23\n" +
      "🟩8s 🟨12s 🟨14s 🟩7s 🟥31s 🟨11s → ⏱️ 1:23\n" +
      "https://setpuzzle.com/share/2026-11-23/1-23/regular";
  private final DailySetParser parser = new DailySetParser();

  @Test void parsesRegularWithVerifiedSixSetBreakdown() {
    ParsedSubmission result = parser.parse(REGULAR);
    assertEquals("2026-11-23:regular", result.puzzleKey());
    assertEquals(LocalDate.of(2026, 11, 23), result.playedOn());
    assertEquals(BigDecimal.valueOf(83), result.primaryScore());
    assertEquals("Regular", result.metrics().get("gameMode"));
    assertEquals(List.of(8, 12, 14, 7, 31, 11), result.metrics().get("setTimesSeconds"));
    assertEquals("1:23", result.metrics().get("displayScore"));
    assertEquals(List.of(new ApiModels.ResultDetailView("Mode", "Regular"),
        new ApiModels.ResultDetailView("Set 1", "8s"), new ApiModels.ResultDetailView("Set 2", "12s"),
        new ApiModels.ResultDetailView("Set 3", "14s"), new ApiModels.ResultDetailView("Set 4", "7s"),
        new ApiModels.ResultDetailView("Set 5", "31s"), new ApiModels.ResultDetailView("Set 6", "11s")),
        parser.resultDetails(result.metrics()));
  }

  @Test void acceptsDiabloAndSharesWithoutBreakdown() {
    ParsedSubmission result = parser.parse("The Daily Set 🔥 Nov 23\n" +
        "https://setpuzzle.com/share/2026-11-23/2-05/diablo");
    assertEquals(125, result.primaryScore().intValueExact());
    assertEquals("Diablo", result.metrics().get("gameMode"));
    assertFalse(result.metrics().containsKey("setTimesSeconds"));
  }

  @Test void revalidatesConfirmedPreviewExactly() {
    ParsedSubmission parsed = parser.parse(REGULAR);
    var confirmed = new ApiModels.ConfirmedSubmission(parsed.puzzleKey(), parsed.puzzleNumber(), parsed.playedOn(),
        parsed.primaryScore(), parsed.metrics(), parsed.parserVersion());
    assertEquals(parsed, parser.validateConfirmed(confirmed, REGULAR));
    assertThrows(ApiException.class, () -> parser.validateConfirmed(new ApiModels.ConfirmedSubmission(
        parsed.puzzleKey(), null, parsed.playedOn(), BigDecimal.ONE, parsed.metrics(), 1), REGULAR));
  }

  @Test void rejectsMismatchedModesDatesTotalsBandsAndExtraContent() {
    List<String> invalid = List.of(
        REGULAR.replace("🎯", "🔥"),
        REGULAR.replace("Nov 23", "Nov 22"),
        REGULAR.replace("/1-23/", "/1-24/"),
        REGULAR.replace("→ ⏱️ 1:23", "→ ⏱️ 1:22"),
        REGULAR.replace("🟩8s", "🟨8s"),
        REGULAR.replace("🟥31s", "🟨31s"),
        REGULAR.replace("🟨11s", "🟨21s"),
        REGULAR.replace("setpuzzle.com", "example.com"),
        REGULAR + "\nWordle 1 1/6",
        "The Daily Set 🎯 Nov 24\nhttps://setpuzzle.com/share/2026-11-24/1-23/regular");
    invalid.forEach(value -> assertThrows(ApiException.class, () -> parser.parse(value), value));
  }
}
