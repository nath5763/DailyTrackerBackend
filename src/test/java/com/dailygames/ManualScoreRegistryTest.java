package com.dailygames;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static com.dailygames.ApiModels.ManualSubmissionRequest;
import static org.junit.jupiter.api.Assertions.*;

class ManualScoreRegistryTest {
  private final ManualScoreRegistry registry = new ManualScoreRegistry();

  @Test void exposesManualSchemasForEveryActiveGame() {
    List<String> active = List.of("fermi", "krillion", "wordle", "pinpoint", "queens", "crossclimb", "tango", "zip",
        "connections", "worldle", "murdle", "globle", "semantle", "contexto", "redactle", "strands",
        "nyt-mini-crossword", "nerdle", "quordle", "timeguessr");
    assertTrue(active.stream().allMatch(slug -> registry.configFor(slug).supported()));
    assertTrue(FandomGames.slugs().stream().allMatch(slug -> registry.configFor(slug).supported()));
    assertFalse(registry.configFor("waffle").supported());
  }

  @Test void everyActiveSchemaAcceptsItsMinimumValidShape() {
    Map<String, Map<String, Object>> values = new LinkedHashMap<>();
    values.put("fermi", Map.of("score", "4.06")); values.put("krillion", Map.of("score", 180));
    values.put("wordle", Map.of("attempts", 4)); values.put("pinpoint", Map.of("attempts", 3));
    values.put("queens", Map.of("completionTimeSeconds", "1:22")); values.put("crossclimb", Map.of("completionTimeSeconds", 98));
    values.put("tango", Map.of("completionTimeSeconds", 60)); values.put("zip", Map.of("completionTimeSeconds", 8));
    values.put("connections", Map.of("wrongGroups", 2)); values.put("worldle", Map.of("attempts", 3));
    values.put("murdle", Map.of("completionTimeSeconds", 90)); values.put("globle", Map.of("attempts", 7));
    values.put("semantle", Map.of("attempts", 118)); values.put("contexto", Map.of("attempts", 5));
    values.put("redactle", Map.of("attempts", 27)); values.put("strands", Map.of("hints", 1));
    values.put("nyt-mini-crossword", Map.of("completionTimeSeconds", 59)); values.put("nerdle", Map.of("attempts", 3));
    values.put("quordle", Map.of("board1", 5, "board2", 9, "board3", 7, "board4", 8));
    values.put("timeguessr", Map.of("score", 40693));
    values.forEach((game, result) -> assertDoesNotThrow(() -> registry.normalize(game,
        request(game, "123", "WIN", result)), game));
  }

  @Test void derivesTheDocumentedQuordleLossWithoutTrustingRankingFields() {
    ParsedSubmission result = registry.normalize("quordle", request("quordle", "1746", "LOSS", Map.of(
        "board1", "5", "board2", "9", "board3", "FAIL", "board4", "8")));
    assertEquals(new BigDecimal("32"), result.primaryScore());
    assertEquals(Arrays.asList(5, 9, null, 8), result.metrics().get("guesses"));
    assertEquals(3, result.metrics().get("solved"));
    assertEquals(1, result.metrics().get("rankingPrimary"));
    assertEquals(32, result.metrics().get("rankingSecondary"));
    assertEquals("3/4 · 32", result.metrics().get("displayScore"));
  }

  @Test void rejectsContradictoryAndMissingValues() {
    ApiException wordle = assertThrows(ApiException.class, () -> registry.normalize("wordle",
        request("wordle", null, "WIN", Map.of())));
    assertEquals("INVALID_MANUAL_RESULT", wordle.code);
    assertThrows(ApiException.class, () -> registry.normalize("connections",
        request("connections", "1240", "WIN", Map.of("wrongGroups", 4))));
    assertThrows(ApiException.class, () -> registry.normalize("quordle",
        request("quordle", "1746", "WIN", Map.of("board1", 1, "board2", 2, "board3", 3, "board4", "FAIL"))));
    assertThrows(ApiException.class, () -> registry.normalize("globle",
        request("globle", null, "WIN", Map.of("attempts", 3, "rankingPrimary", 0))));
  }

  @Test void parsesDurationsAndValidatesTimeGuessrRounds() {
    ParsedSubmission timed = registry.normalize("nyt-mini-crossword",
        request("nyt-mini-crossword", null, "WIN", Map.of("completionTimeSeconds", "1:22")));
    assertEquals(new BigDecimal("82"), timed.primaryScore());
    assertEquals("1:22", timed.metrics().get("displayScore"));

    ParsedSubmission points = registry.normalize("timeguessr", request("timeguessr", "807", "WIN", Map.of(
        "score", 21450, "round1", 4000, "round2", 4250, "round3", 4300, "round4", 4400, "round5", 4500)));
    assertEquals("21,450 pts", points.metrics().get("displayScore"));
    assertEquals("DESCENDING", points.metrics().get("rankingPrimaryDirection"));
    assertThrows(ApiException.class, () -> registry.normalize("timeguessr", request("timeguessr", "807", "WIN",
        Map.of("score", 20000, "round1", 4000))));
  }

  @Test void usesExplicitDateAsPuzzleKeyWhenIdIsUnavailable() {
    ParsedSubmission result = registry.normalize("murdle", request("murdle", null, "WIN",
        Map.of("completionTimeSeconds", 90)));
    assertEquals("2026-11-05", result.puzzleKey());
    assertEquals(LocalDate.of(2026, 11, 5), result.playedOn());
  }

  private ManualSubmissionRequest request(String game, String puzzleId, String status, Map<String, Object> values) {
    return new ManualSubmissionRequest(game, puzzleId, LocalDate.of(2026, 11, 5), status, values);
  }
}
