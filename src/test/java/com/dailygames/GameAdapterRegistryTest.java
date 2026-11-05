package com.dailygames;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.math.BigDecimal;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class GameAdapterRegistryTest {
  private static final Set<String> PARTIAL = Set.of("strands", "nyt-mini-crossword", "semantle", "contexto",
      "redactle", "nerdle", "worldle", "globle", "murdle", "quordle", "timeguessr");
  private final GameAdapterRegistry registry = new GameAdapterRegistry(List.of(new ConnectionsParser(), new WordleParser(),
      new StrandsParser(), new NytMiniParser(), new SemantleParser(), new ContextoParser(), new RedactleParser(),
      new NerdleParser(), new WorldleParser(), new GlobleParser(), new MurdleParser(), new QuordleParser(),
      new TimeGuessrParser()));

  @Test void detectsOnlyNamedGames() {
    assertEquals("connections", registry.detectGame("Connections\nPuzzle #1240\n🟩🟩🟩🟩").orElseThrow());
    assertEquals("worldle", registry.detectGame("#Worldle #1103 (28.01.2025) 1/6 (100%)").orElseThrow());
    assertTrue(registry.detectGame("🟩🟩🟩🟩🟩").isEmpty());
    assertTrue(registry.detectGame("Wordle 1961 4/6\n#Worldle #1103 (28.01.2025) 1/6 (100%)").isEmpty());
    assertTrue(registry.detectGame("Squirdle #100 3/8").isEmpty());
  }

  @Test void reportsEveryRequestedGameWithoutPretendingUnsupportedFormatsWork() {
    var all = registry.capabilities();
    assertEquals(76, all.size());
    assertEquals(76, all.stream().map(ApiModels.ParserCapabilityView::gameId).distinct().count());
    assertEquals("PARTIAL", all.stream().filter(value -> value.gameId().equals("worldle"))
        .findFirst().orElseThrow().status());
    var unsupported = all.stream().filter(value -> value.gameId().equals("waffle")).findFirst().orElseThrow();
    assertEquals("UNSUPPORTED", unsupported.status());
    assertTrue(unsupported.supportedFormats().isEmpty());

    Map<String, Set<String>> formats = registry.supportedFormats();
    assertEquals(Set.of("share-text"), formats.get("worldle"));
    assertEquals(Set.of(), formats.get("waffle"));
    assertEquals(Set.of("share-text"), formats.get("connections"));
    var fandom = all.stream().filter(value -> value.gameId().equals("squirdle")).findFirst().orElseThrow();
    assertEquals("MANUAL_ONLY", fandom.status());
    assertEquals(Set.of(), fandom.supportedFormats());
    assertEquals(Set.of("manual-entry"), registry.submissionFormats("squirdle"));
  }

  @ParameterizedTest(name = "{0} is explicitly {1}")
  @MethodSource("requestedGames")
  void everyRequestedGameHasAnExplicitCapability(String gameId, String status) {
    var capability = registry.capabilities().stream().filter(value -> value.gameId().equals(gameId))
        .findFirst().orElseThrow();
    assertEquals(status, capability.status());
    assertEquals(status.equals("PARTIAL") ? Set.of("share-text") : Set.of(), capability.supportedFormats());
  }

  static Stream<Arguments> requestedGames() {
    return GameParserCapabilities.REQUESTED_GAME_IDS.stream()
        .map(gameId -> Arguments.of(gameId, PARTIAL.contains(gameId) ? "PARTIAL" :
            FandomGames.find(gameId).isPresent() ? "MANUAL_ONLY" : "UNSUPPORTED"));
  }

  @Test void decoratesNativeMetricsAndRevalidatesTheConfirmedPreview() {
    String share = "#Worldle #1103 (28.01.2025) 1/6 (100%)\n🟩🟩🟩🟩🟩🎉";
    ParsedSubmission preview = registry.parseResult("worldle", share);
    assertEquals("worldle", preview.metrics().get("gameId"));
    assertEquals("1103", preview.metrics().get("puzzleId"));
    assertEquals("2025-01-28", preview.metrics().get("puzzleDate"));
    assertEquals("WIN", preview.metrics().get("completionStatus"));
    assertEquals(new BigDecimal("1"), preview.metrics().get("scoreValue"));
    assertEquals("LOWER_IS_BETTER", preview.metrics().get("scoreDirection"));
    assertEquals(1, preview.metrics().get("parserVersion"));
    var confirmed = new ApiModels.ConfirmedSubmission(preview.puzzleKey(), preview.puzzleNumber(), preview.playedOn(),
        preview.primaryScore(), preview.metrics(), preview.parserVersion());
    assertEquals(preview, registry.validateResult("worldle", confirmed, share));
  }
}
