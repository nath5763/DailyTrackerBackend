package com.dailygames;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.*;

class FermiParserTest {
  private final FermiParser parser = new FermiParser();

  @Test void lowerMultiplierIsBetter() {
    assertEquals("LOWER_IS_BETTER", parser.scoreDirection());
  }

  @Test void parsesShareText() {
    ParsedSubmission parsed = parser.parse("Fermi #92\n1.31× score\ntop 8%");
    assertEquals("92", parsed.puzzleKey());
    assertEquals(new BigDecimal("1.31"), parsed.primaryScore());
    assertEquals(LocalDate.of(2026, 10, 26), parsed.playedOn());
    assertEquals("WIN", parsed.metrics().get("completionStatus"));
    assertEquals(new BigDecimal("8"), parsed.metrics().get("percentile"));
    assertEquals(java.util.List.of(new ApiModels.ResultDetailView("Top", "8%")),
        parser.resultDetails(parsed.metrics()));
  }

  @Test void acceptsCommaAndComponents() {
    ParsedSubmission parsed = parser.parse("Fermi #7\n1,50x score\nQ1 1.50x\nQ2 1.40x\nQ3 2.10x");
    assertEquals(new BigDecimal("1.50"), parsed.primaryScore());
    assertEquals(3, ((java.util.List<?>) parsed.metrics().get("components")).size());
    assertEquals(java.util.List.of(
        new ApiModels.ResultDetailView("Q1", "1.50×"),
        new ApiModels.ResultDetailView("Q2", "1.40×"),
        new ApiModels.ResultDetailView("Q3", "2.10×")), parser.resultDetails(parsed.metrics()));
  }

  @Test void treatsGroupedLargeMultipliersAsThousandsNotDecimalFractions() {
    ParsedSubmission parsed = parser.parse(
        "Fermi #103\n1,960x score\nQ1 1,200x\nQ2 2.50x\nQ3 4,000x");

    assertEquals(new BigDecimal("1960"), parsed.primaryScore());
    assertEquals("1,960×", parsed.metrics().get("displayScore"));
    assertEquals(java.util.List.of(new BigDecimal("1200"), new BigDecimal("2.50"), new BigDecimal("4000")),
        parsed.metrics().get("components"));
    assertEquals(java.util.List.of(
        new ApiModels.ResultDetailView("Q1", "1,200×"),
        new ApiModels.ResultDetailView("Q2", "2.50×"),
        new ApiModels.ResultDetailView("Q3", "4,000×")), parser.resultDetails(parsed.metrics()));
  }

  @Test void confirmedShareCannotChangeItsCompletionStatus() {
    String share = "Fermi #92\n1.31× score\ntop 8%";
    ParsedSubmission parsed = parser.parse(share);
    var metrics = new LinkedHashMap<>(parsed.metrics());
    metrics.put("completionStatus", "LOSS");
    ParsedSubmission confirmed = parser.validateConfirmed(new ApiModels.ConfirmedSubmission(parsed.puzzleKey(),
        parsed.puzzleNumber(), parsed.playedOn(), parsed.primaryScore(), metrics, parsed.parserVersion()), share);

    assertEquals("WIN", confirmed.metrics().get("completionStatus"));
  }

  @Test void rejectsWrongGameAndInvalidScore() {
    ApiException exception = assertThrows(ApiException.class, () -> parser.parse("Wordle #92\n0.5× score"));
    assertEquals(422, exception.status);
    assertTrue(exception.getMessage().contains("Fermi"));
  }
}
