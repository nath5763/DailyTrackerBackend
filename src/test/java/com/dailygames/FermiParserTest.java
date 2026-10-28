package com.dailygames;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class FermiParserTest {
  private final FermiParser parser = new FermiParser();

  @Test void parsesShareText() {
    FermiParser.Parsed parsed = parser.parse("Fermi #92\n1.31× score\ntop 8%");
    assertEquals("92", parsed.puzzleKey());
    assertEquals(new BigDecimal("1.31"), parsed.primaryScore());
    assertEquals(LocalDate.of(2026, 10, 26), parsed.playedOn());
    assertEquals(new BigDecimal("8"), parsed.metrics().get("percentile"));
  }

  @Test void acceptsCommaAndComponents() {
    FermiParser.Parsed parsed = parser.parse("Fermi #7\n1,50x score\nQ1 1.20x\nQ2 1.40x\nQ3 2.10x");
    assertEquals(new BigDecimal("1.50"), parsed.primaryScore());
    assertEquals(3, ((java.util.List<?>) parsed.metrics().get("components")).size());
  }

  @Test void rejectsWrongGameAndInvalidScore() {
    ApiException exception = assertThrows(ApiException.class, () -> parser.parse("Wordle #92\n0.5× score"));
    assertEquals(422, exception.status);
    assertTrue(exception.getMessage().contains("Fermi"));
  }
}
