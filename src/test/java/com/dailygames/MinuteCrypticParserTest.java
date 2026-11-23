package com.dailygames;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MinuteCrypticParserTest {
  private static final String CURRENT = "Minute Cryptic - 23 November, 2026\n" +
      "\"Kitchen cleaners yelled 'You left sugar on the counter!'\" (4)\n" +
      "🟣🟣🟣🟣🟣🟣🟣\n" +
      "🏆 0 hints – 1 under the community par (101,965 solvers so far). Time: 1m 59s.\n" +
      "https://www.minutecryptic.com/?utm_source=share";
  private final MinuteCrypticParser parser = new MinuteCrypticParser();

  @Test void parsesCurrentTimedShareAndSecondaryDetails() {
    ParsedSubmission result = parser.parse(CURRENT);
    assertEquals("2026-11-23", result.puzzleKey());
    assertEquals(LocalDate.of(2026, 11, 23), result.playedOn());
    assertEquals(BigDecimal.ZERO, result.primaryScore());
    assertEquals(-1, result.metrics().get("communityParDelta"));
    assertEquals(101965, result.metrics().get("solverCount"));
    assertEquals(119, result.metrics().get("completionTimeSeconds"));
    assertEquals("0 hints", result.metrics().get("displayScore"));
    assertEquals(List.of(
        new ApiModels.ResultDetailView("Clue", "Kitchen cleaners yelled 'You left sugar on the counter!' (4)"),
        new ApiModels.ResultDetailView("Symbols", "🟣🟣🟣🟣🟣🟣🟣"),
        new ApiModels.ResultDetailView("Community par", "1 under"),
        new ApiModels.ResultDetailView("Solvers", "101965"),
        new ApiModels.ResultDetailView("Time", "1:59")), parser.resultDetails(result.metrics()));
  }

  @Test void acceptsMatchedOverParUntimedAndLegacyVariants() {
    String matched = CURRENT.replace("🟣🟣🟣🟣🟣🟣🟣", "⚪️⚪️🟣🟣🟣🟣🟣")
        .replace("🏆 0 hints – 1 under", "🤝 2 hints – matched")
        .replace(". Time: 1m 59s.", ".");
    ParsedSubmission matchedResult = parser.parse(matched);
    assertEquals(2, matchedResult.primaryScore().intValueExact());
    assertEquals(0, matchedResult.metrics().get("communityParDelta"));
    assertFalse(matchedResult.metrics().containsKey("completionTimeSeconds"));

    String over = CURRENT.replace("🟣🟣🟣🟣🟣🟣🟣", "⚪️🟡🟡🟣🟣🟣🟣")
        .replace("🏆 0 hints – 1 under", "🏋 3 hints – 1 over")
        .replace("1m 59s", "2h 8m");
    ParsedSubmission overResult = parser.parse(over);
    assertEquals(3, overResult.primaryScore().intValueExact());
    assertEquals(1, overResult.metrics().get("communityParDelta"));
    assertEquals(7680, overResult.metrics().get("completionTimeSeconds"));

    String legacy = "Minute Cryptic - 30 June, 2024\n\"Tell us what you think of 1 divided by 3.14\" (5)\n" +
        "🟪🟨🟪🟪🟪\nI scored: 1 under par\nhttps://www.minutecryptic.com";
    ParsedSubmission legacyResult = parser.parse(legacy);
    assertEquals(1, legacyResult.primaryScore().intValueExact());
    assertEquals("legacy", legacyResult.metrics().get("shareFormat"));
  }

  @Test void rejectsInconsistentHintsIconsDatesUrlsTimesAndExtraContent() {
    List<String> invalid = List.of(
        CURRENT.replace("0 hints", "1 hints"),
        CURRENT.replace("🏆 0 hints – 1 under", "🤝 0 hints – 1 under"),
        CURRENT.replace("🏆 0 hints – 1 under", "🤝 0 hints – 0 under"),
        CURRENT.replace("23 November", "24 November"),
        CURRENT.replace("minutecryptic.com", "example.com"),
        CURRENT.replace("1m 59s", "1m 60s"),
        CURRENT.replace("🟣", "🟩"),
        CURRENT.replace("(101,965 solvers", "(0 solvers"),
        CURRENT + "\nWordle 1 1/6");
    invalid.forEach(value -> assertThrows(ApiException.class, () -> parser.parse(value), value));
  }
}
