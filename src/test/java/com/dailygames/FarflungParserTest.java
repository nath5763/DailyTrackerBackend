package com.dailygames;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FarflungParserTest {
  private static final String SHARE = "Farflung #50 · 195/500 km\n" +
      "Miss · Elusive · Rare · Miss · Common\n" +
      "https://farflung.game/s/qtrtpp?e=50";
  private final FarflungParser parser = new FarflungParser();

  @Test void parsesAndValidatesTheSuppliedDailyResult() {
    ParsedSubmission result = parser.parse(SHARE);
    assertEquals("50", result.puzzleKey());
    assertEquals(50, result.puzzleNumber());
    assertEquals(LocalDate.of(2026, 11, 23), result.playedOn());
    assertEquals(0, BigDecimal.valueOf(195).compareTo(result.primaryScore()));
    assertEquals(195, result.metrics().get("distanceKm"));
    assertEquals(List.of("Miss", "Elusive", "Rare", "Miss", "Common"), result.metrics().get("rarities"));
    assertEquals("195/500 km", result.metrics().get("displayScore"));
    assertEquals(List.of(
        new ApiModels.ResultDetailView("P1", "Miss"),
        new ApiModels.ResultDetailView("P2", "Elusive"),
        new ApiModels.ResultDetailView("P3", "Rare"),
        new ApiModels.ResultDetailView("P4", "Miss"),
        new ApiModels.ResultDetailView("P5", "Common")), parser.resultDetails(result.metrics()));
  }

  @Test void acceptsMinimumAndMaximumDistances() {
    ParsedSubmission misses = parser.parse("Farflung #1 · 0/500 km\n" +
        "Miss · Miss · Miss · Miss · Miss\nhttps://farflung.game/s/aaaaaa?e=1");
    ParsedSubmission perfect = parser.parse("Farflung #1 · 500/500 km\n" +
        "Farflung · Farflung · Farflung · Farflung · Farflung\nhttps://farflung.game/s/zzzzzz?e=1");
    assertEquals(BigDecimal.ZERO, misses.primaryScore());
    assertEquals(0, BigDecimal.valueOf(500).compareTo(perfect.primaryScore()));
  }

  @Test void revalidatesConfirmedPreviewExactly() {
    ParsedSubmission parsed = parser.parse(SHARE);
    var confirmed = new ApiModels.ConfirmedSubmission(parsed.puzzleKey(), parsed.puzzleNumber(), parsed.playedOn(),
        parsed.primaryScore(), parsed.metrics(), parsed.parserVersion());
    assertEquals(parsed, parser.validateConfirmed(confirmed, SHARE));
    var changed = new ApiModels.ConfirmedSubmission(parsed.puzzleKey(), parsed.puzzleNumber(), parsed.playedOn(),
        BigDecimal.ZERO, parsed.metrics(), parsed.parserVersion());
    assertThrows(ApiException.class, () -> parser.validateConfirmed(changed, SHARE));
  }

  @Test void rejectsMalformedMismatchedAndStarredShares() {
    List<String> invalid = List.of(
        SHARE.replace("195/500", "196/500"),
        SHARE.replace("Miss · Common", "Common"),
        SHARE.replace("Common", "Legendary"),
        SHARE.replace("?e=50", "?e=49"),
        SHARE.replace("farflung.game", "example.com"),
        SHARE.replace("qtrtpp", "QTRTPP"),
        SHARE.replace("#50", "#050"),
        SHARE.replace("195/500 km", "195/500 km ★"),
        SHARE + "\nWordle 1 1/6",
        "Farflung #100000 · 0/500 km\nMiss · Miss · Miss · Miss · Miss\n" +
            "https://farflung.game/s/aaaaaa?e=100000");
    invalid.forEach(share -> assertThrows(ApiException.class, () -> parser.parse(share), share));
  }
}
