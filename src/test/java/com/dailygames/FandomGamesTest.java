package com.dailygames;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.stream.Stream;

import static com.dailygames.ApiModels.ManualSubmissionRequest;
import static org.junit.jupiter.api.Assertions.*;

class FandomGamesTest {
  private final ManualScoreRegistry manual = new ManualScoreRegistry();
  private final GameAdapterRegistry adapters = new GameAdapterRegistry(java.util.List.of());

  @Test void containsThirtyNineUniqueAvailableDirectLinkGames() {
    assertEquals(39, FandomGames.ALL.size());
    assertEquals(39, FandomGames.slugs().stream().distinct().count());
    assertTrue(FandomGames.ALL.stream().allMatch(game -> game.sourceUrl().startsWith("https://")));
    assertFalse(FandomGames.slugs().contains("itemdle"));
    assertFalse(FandomGames.slugs().contains("cinenerdle"));
  }

  @ParameterizedTest @MethodSource("games")
  void everyFandomGameSupportsNativeManualEntryButNotUnverifiedClipboardParsing(FandomGameDefinition game) {
    assertTrue(manual.configFor(game.slug()).supported());
    assertEquals("MANUAL_ONLY", adapters.capabilities().stream()
        .filter(value -> value.gameId().equals(game.slug())).findFirst().orElseThrow().status());
    ApiException error = assertThrows(ApiException.class, () -> adapters.parseResult(game.slug(), game.name() + " result"));
    assertEquals("UNSUPPORTED_SHARE_FORMAT", error.code);

    Map<String, Object> nativeValues = switch (game.scoreKind()) {
      case ATTEMPTS -> Map.of("attempts", 3);
      case CONNECTION_MISTAKES -> Map.of("mistakes", 1);
      case GRID_RARITY -> Map.of("correctCells", 9, "rarityScore", "42.5");
      case POKEDOKU_UNIQUENESS -> Map.of("uniquenessScore", "42.5", "correctCells", 9);
    };
    Map<String, Object> values = new java.util.LinkedHashMap<>(nativeValues);
    if (game.hasModes()) values.put("gameMode", "Classic");
    ParsedSubmission result = manual.normalize(game.slug(), request(game.slug(), "WIN", values));
    assertEquals("WIN", result.metrics().get("completionStatus"));
    assertNotNull(result.metrics().get("rankingPrimary"));
    assertNotNull(result.metrics().get("displayScore"));
  }

  @Test void modeSeparatesPuzzleIdentityAndLeaderboardCompatibility() {
    ParsedSubmission result = manual.normalize("valodle", request("valodle", "WIN",
        Map.of("attempts", 4, "gameMode", "Ability Daily")));
    assertEquals("ability-daily:2026-11-05", result.puzzleKey());
    ParsedSubmission decorated = adapters.normalizeManualResult("valodle", request("valodle", "WIN",
        Map.of("attempts", 4, "gameMode", "Ability Daily")));
    assertEquals("ability-daily", decorated.metrics().get("gameMode"));
    assertEquals("gameMode=ability-daily", decorated.metrics().get("compatibilityKey"));
  }

  @Test void pokedokuRequiresNativeUniquenessAndConsistentCompletion() {
    ParsedSubmission result = adapters.normalizeManualResult("pokedoku", request("pokedoku", "LOSS",
        Map.of("uniquenessScore", "42.5", "correctCells", 8)));
    assertEquals(new BigDecimal("42.5"), result.primaryScore());
    assertEquals(new BigDecimal("42.5"), result.metrics().get("nativeScore"));
    assertEquals(new BigDecimal("42.5"), result.metrics().get("rankingPrimary"));
    assertEquals(new BigDecimal("8"), result.metrics().get("rankingSecondary"));
    assertEquals("UNIQ 42.5 · 8/9", result.metrics().get("displayScore"));

    assertInvalidPokedoku(Map.of("correctCells", 8), "LOSS");
    assertInvalidPokedoku(Map.of("uniquenessScore", "900.01", "correctCells", 8), "LOSS");
    assertInvalidPokedoku(Map.of("uniquenessScore", 42, "correctCells", 8), "WIN");
    assertInvalidPokedoku(Map.of("uniquenessScore", 42, "correctCells", 9), "LOSS");
    assertInvalidPokedoku(Map.of("uniquenessScore", 42, "correctCells", 9, "rankingPrimary", 0), "WIN");
  }

  private void assertInvalidPokedoku(Map<String, Object> values, String status) {
    assertEquals("INVALID_MANUAL_RESULT", assertThrows(ApiException.class,
        () -> manual.normalize("pokedoku", request("pokedoku", status, values))).code);
  }

  static Stream<FandomGameDefinition> games() { return FandomGames.ALL.stream(); }

  private ManualSubmissionRequest request(String slug, String status, Map<String, Object> values) {
    return new ManualSubmissionRequest(slug, null, LocalDate.of(2026, 11, 5), status, values);
  }
}
