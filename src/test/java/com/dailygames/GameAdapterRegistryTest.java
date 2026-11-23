package com.dailygames;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class GameAdapterRegistryTest {
  private static final Set<String> PARTIAL = Set.of("strands", "nyt-mini-crossword", "semantle", "contexto",
      "redactle", "nerdle", "worldle", "globle", "murdle", "quordle", "timeguessr", "travle", "farflung", "foximax",
      "loldle-classic", "loldle-quote", "loldle-ability", "loldle-emoji", "loldle-splash");
  private final GameAdapterRegistry registry = new GameAdapterRegistry(List.of(new ConnectionsParser(), new WordleParser(),
      new StrandsParser(), new NytMiniParser(), new SemantleParser(), new ContextoParser(), new RedactleParser(),
      new NerdleParser(), new WorldleParser(), new GlobleParser(), new MurdleParser(), new QuordleParser(),
      new TimeGuessrParser(), new TravleParser(), new FarflungParser(), new FoxiMaxParser(), new LoldleClassicAdapter(), new LoldleQuoteAdapter(),
      new LoldleAbilityAdapter(), new LoldleEmojiAdapter(), new LoldleSplashAdapter()));

  @Test void detectsOnlyNamedGames() {
    assertEquals("connections", registry.detectGame("Connections\nPuzzle #1240\n🟩🟩🟩🟩").orElseThrow());
    assertEquals("worldle", registry.detectGame("#Worldle #1103 (28.01.2025) 1/6 (100%)").orElseThrow());
    assertEquals("quordle", registry.detectGame("Daily Quordle 1746\n5️⃣9️⃣").orElseThrow());
    assertEquals("travle", registry.detectGame("#travle_challenge #38 +0\n✅✅").orElseThrow());
    assertEquals("foximax", registry.detectGame("🦊#FoxiMax #1549 6/8 (19 letters)").orElseThrow());
    assertTrue(registry.detectGame("🟩🟩🟩🟩🟩").isEmpty());
    assertTrue(registry.detectGame("Wordle 1961 4/6\n#Worldle #1103 (28.01.2025) 1/6 (100%)").isEmpty());
    assertEquals("farflung", registry.detectGame("Farflung #50 · 195/500 km").orElseThrow());
    assertTrue(registry.detectGame("Squirdle #100 3/8").isEmpty());
  }

  @Test void reportsEveryRequestedGameWithoutPretendingUnsupportedFormatsWork() {
    var all = registry.capabilities();
    assertEquals(83, all.size());
    assertEquals(83, all.stream().map(ApiModels.ParserCapabilityView::gameId).distinct().count());
    assertEquals("PARTIAL", all.stream().filter(value -> value.gameId().equals("worldle"))
        .findFirst().orElseThrow().status());
    var unsupported = all.stream().filter(value -> value.gameId().equals("waffle")).findFirst().orElseThrow();
    assertEquals("UNSUPPORTED", unsupported.status());
    assertTrue(unsupported.supportedFormats().isEmpty());

    Map<String, Set<String>> formats = registry.supportedFormats();
    assertEquals(Set.of("share-text"), formats.get("worldle"));
    assertEquals(Set.of("share-text"), formats.get("travle"));
    assertEquals(Set.of("share-text"), formats.get("farflung"));
    assertEquals(Set.of(), formats.get("waffle"));
    assertEquals(Set.of("share-text"), formats.get("connections"));
    assertEquals(Set.of("batch-share-text"), formats.get("loldle-classic"));
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
    assertEquals(gameId.startsWith("loldle-") ? Set.of("batch-share-text")
        : status.equals("PARTIAL") ? Set.of("share-text") : Set.of(), capability.supportedFormats());
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

  @Test void manualAndShareFermiResultsUseTheSameCompletionAndNumericRanking() {
    GameAdapterRegistry fermi = new GameAdapterRegistry(List.of(new FermiParser()));
    ParsedSubmission shared = fermi.parseResult("fermi", "Fermi #101\n6.32x score");
    ParsedSubmission manual = fermi.normalizeManualResult("fermi", new ApiModels.ManualSubmissionRequest(
        "fermi", "101", LocalDate.of(2026, 11, 4), "WIN", Map.of("score", "6.32")));
    NormalizedScore sharedScore = fermi.normalizedScore("fermi", shared.primaryScore(), shared.metrics(),
        shared.puzzleKey(), shared.playedOn(), shared.parserVersion(), "lower");
    NormalizedScore manualScore = fermi.normalizedScore("fermi", manual.primaryScore(), manual.metrics(),
        manual.puzzleKey(), manual.playedOn(), manual.parserVersion(), "lower");

    assertEquals("WIN", sharedScore.completionStatus());
    assertEquals("WIN", manualScore.completionStatus());
    assertEquals(0, fermi.compareScores(sharedScore, manualScore));
    assertTrue(fermi.sameRank(sharedScore, manualScore));

    ParsedSubmission fasterShare = fermi.parseResult("fermi", "Fermi #101\n4.06x score");
    NormalizedScore fasterScore = fermi.normalizedScore("fermi", fasterShare.primaryScore(), fasterShare.metrics(),
        fasterShare.puzzleKey(), fasterShare.playedOn(), fasterShare.parserVersion(), "lower");
    assertTrue(fermi.compareScores(fasterScore, manualScore) < 0);
  }

  @Test void wordleWinsOutrankLosses() {
    GameAdapterRegistry wordle = new GameAdapterRegistry(List.of(new WordleParser()));
    ParsedSubmission win = wordle.parseResult("wordle", "Wordle 1,961 2/6\n⬛⬛⬛⬛⬛\n🟩🟩🟩🟩🟩");
    ParsedSubmission loss = wordle.parseResult("wordle", "Wordle 1,962 X/6\n⬛⬛⬛⬛⬛\n⬛⬛⬛⬛⬛\n⬛⬛⬛⬛⬛\n⬛⬛⬛⬛⬛\n⬛⬛⬛⬛⬛\n⬛⬛⬛⬛⬛");
    NormalizedScore winScore = wordle.normalizedScore("wordle", win.primaryScore(), win.metrics(),
        win.puzzleKey(), win.playedOn(), win.parserVersion(), "lower");
    NormalizedScore lossScore = wordle.normalizedScore("wordle", loss.primaryScore(), loss.metrics(),
        loss.puzzleKey(), loss.playedOn(), loss.parserVersion(), "lower");

    assertTrue(wordle.compareScores(winScore, lossScore) < 0);
  }
}
