package com.dailygames;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FoxiMaxParserTest {
  private static final String SHARE = "🦊#FoxiMax #1549 6/8 (19 letters)\nhttps://foximax.com/\n\n"
      + "🟩🟩🟩🟩🟩\n🟩🟩🟩🟩⬜\n🟩🟩⬜🟩🟩\n"
      + "⬜🟩🟩🟩🟩\n🟩⬜🟩🟩🟩\n🟩🟩🟩⬜🟩";
  private final FoxiMaxParser parser = new FoxiMaxParser();

  @Test void parsesWordsLettersRowsAndAnchorDate() {
    ParsedSubmission result = parser.parse(SHARE);
    assertEquals("1549", result.puzzleKey());
    assertEquals(1549, result.puzzleNumber());
    assertEquals(LocalDate.of(2026, 11, 23), result.playedOn());
    assertEquals(0, BigDecimal.valueOf(6).compareTo(result.primaryScore()));
    assertEquals(6, result.metrics().get("wordsUsed"));
    assertEquals(19, result.metrics().get("lettersGuessed"));
    assertEquals("6/8", result.metrics().get("displayScore"));
    assertEquals(List.of(new ApiModels.ResultDetailView("Letters", "19 letters")),
        parser.resultDetails(result.metrics()));
  }

  @Test void acceptsTheOptionalFoxAndDecorativeRowSymbols() {
    String share = "#FoxiMax #1547 1/8 (5 letters)\nhttps://foximax.com\n🟩🟩🟩🟩🟩🦊";
    assertEquals(BigDecimal.ONE, parser.parse(share).primaryScore());
  }

  @Test void revalidatesConfirmedPreviewExactly() {
    ParsedSubmission parsed = parser.parse(SHARE);
    var confirmed = new ApiModels.ConfirmedSubmission(parsed.puzzleKey(), parsed.puzzleNumber(), parsed.playedOn(),
        parsed.primaryScore(), parsed.metrics(), parsed.parserVersion());
    assertEquals(parsed, parser.validateConfirmed(confirmed, SHARE));
    var changed = new ApiModels.ConfirmedSubmission(parsed.puzzleKey(), parsed.puzzleNumber(), parsed.playedOn(),
        BigDecimal.ONE, parsed.metrics(), parsed.parserVersion());
    assertThrows(ApiException.class, () -> parser.validateConfirmed(changed, SHARE));
  }

  @Test void ranksFewerWordsFirstAndDoesNotUseLettersAsATiebreaker() {
    GameAdapterRegistry registry = new GameAdapterRegistry(List.of(parser));
    String fourWordShare = "🦊#FoxiMax #1549 4/8 (16 letters)\nhttps://foximax.com/\n"
        + "🟩🟩🟩🟩🟩\n🟩🟩🟩🟩⬜\n🟩🟩⬜🟩🟩\n⬜🟩🟩🟩🟩";
    ParsedSubmission fourWords = registry.parseResult("foximax", fourWordShare);
    ParsedSubmission sixWords = registry.parseResult("foximax", SHARE);
    ParsedSubmission sixWordsFewerLetters = registry.parseResult("foximax", SHARE.replace("19 letters", "18 letters"));
    NormalizedScore fourScore = registry.normalizedScore("foximax", fourWords.primaryScore(), fourWords.metrics(),
        fourWords.puzzleKey(), fourWords.playedOn(), fourWords.parserVersion(), "lower");
    NormalizedScore sixScore = registry.normalizedScore("foximax", sixWords.primaryScore(), sixWords.metrics(),
        sixWords.puzzleKey(), sixWords.playedOn(), sixWords.parserVersion(), "lower");
    NormalizedScore tiedScore = registry.normalizedScore("foximax", sixWordsFewerLetters.primaryScore(),
        sixWordsFewerLetters.metrics(), sixWordsFewerLetters.puzzleKey(), sixWordsFewerLetters.playedOn(),
        sixWordsFewerLetters.parserVersion(), "lower");

    assertTrue(registry.compareScores(fourScore, sixScore) < 0);
    assertTrue(registry.sameRank(sixScore, tiedScore));
  }

  @Test void rejectsMalformedAndIncompleteShares() {
    List<String> invalid = List.of(
        "#FoxiMax #1549 6/8 (19 letters)\nhttps://foximax.com/\n⬜",
        SHARE.replace("6/8", "7/8"),
        SHARE.replace("https://foximax.com/", "https://example.com/"),
        SHARE.replace("\n🟩🟩🟩🟩🟩\n", "\n🟩🟩🟩🟩\n"),
        SHARE.replace("#1549", "#0"),
        SHARE + "\nWordle 1983 2/6");
    invalid.forEach(share -> assertThrows(ApiException.class, () -> parser.parse(share), share));
  }
}
