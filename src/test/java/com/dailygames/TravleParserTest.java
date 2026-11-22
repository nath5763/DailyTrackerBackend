package com.dailygames;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TravleParserTest {
  private static final String DAILY = "#travle #1439 +2 (1 hint)\n✅🟧🟩✅\nhttps://travle.earth";
  private final TravleParser parser = new TravleParser();

  @Test void parsesDailyScoreHintsPathAndAnchorDate() {
    ParsedSubmission result = parser.parse(DAILY);
    assertEquals("Daily:1439", result.puzzleKey());
    assertEquals(1439, result.puzzleNumber());
    assertEquals(LocalDate.of(2026, 11, 22), result.playedOn());
    assertEquals(0, BigDecimal.valueOf(2).compareTo(result.primaryScore()));
    assertEquals(2, result.metrics().get("extraGuesses"));
    assertEquals(1, result.metrics().get("hints"));
    assertEquals("assisted", result.metrics().get("assistanceStatus"));
    assertEquals("✅🟧🟩✅", result.metrics().get("resultSequence"));
    assertEquals("Daily", result.metrics().get("gameMode"));
    assertEquals("+2", result.metrics().get("displayScore"));
    assertEquals(List.of(new ApiModels.ResultDetailView("Path", "✅🟧🟩✅"),
        new ApiModels.ResultDetailView("Hints", "1")), parser.resultDetails(result.metrics()));
  }

  @Test void parsesWeeklyAndKeepsItsCanonicalPuzzleKeySeparate() {
    ParsedSubmission weekly = parser.parse("#travle_challenge #38 +1✅🟥✅\nhttps://travle.earth/challenge");
    ParsedSubmission daily = parser.parse("#travle #38 +1\n✅🟥✅\nhttps://travle.earth");
    assertEquals("Weekly:38", weekly.puzzleKey());
    assertEquals(LocalDate.of(2024, 10, 21), weekly.playedOn());
    assertEquals("Daily:38", daily.puzzleKey());
    assertNotEquals(daily.puzzleKey(), weekly.puzzleKey());
    assertEquals("Weekly", weekly.metrics().get("gameMode"));
  }

  @Test void acceptsPerfectNoHintAndSingularOrPluralHints() {
    ParsedSubmission perfect = parser.parse("#travle #1439 +0 (Perfect) ✅✅✅\nhttps://travle.earth/");
    assertEquals(BigDecimal.ZERO, perfect.primaryScore());
    assertEquals(0, perfect.metrics().get("hints"));
    assertEquals("unassisted", perfect.metrics().get("assistanceStatus"));
    assertEquals(1, parser.parse("#travle #1439 +1 (1 hint)\n🟧✅\nhttps://travle.earth").metrics().get("hints"));
    assertEquals(3, parser.parse("#travle_challenge #38 +0 (3 hints)\n🟩✅\nhttps://travle.earth/challenge/").metrics().get("hints"));
  }

  @Test void revalidatesConfirmedPreviewExactly() {
    ParsedSubmission parsed = parser.parse(DAILY);
    var confirmed = new ApiModels.ConfirmedSubmission(parsed.puzzleKey(), parsed.puzzleNumber(), parsed.playedOn(),
        parsed.primaryScore(), parsed.metrics(), parsed.parserVersion());
    assertEquals(parsed, parser.validateConfirmed(confirmed, DAILY));
    var changed = new ApiModels.ConfirmedSubmission(parsed.puzzleKey(), parsed.puzzleNumber(), parsed.playedOn(),
        BigDecimal.ZERO, parsed.metrics(), parsed.parserVersion());
    assertThrows(ApiException.class, () -> parser.validateConfirmed(changed, DAILY));
  }

  @Test void rejectsUnsupportedMalformedAndMixedShares() {
    List<String> invalid = List.of(
        "#travle_practice #1439 +0\n✅✅\nhttps://travle.earth",
        "#travle #1439 +0\n✅✅\nhttps://travle.earth/challenge",
        "#travle_challenge #38 +0\n✅✅\nhttps://travle.earth",
        "#travle #1439 +7\n✅✅\nhttps://travle.earth",
        "#travle #1439 +0 (4 hints)\n✅✅\nhttps://travle.earth",
        "#travle #1439 +0 (1 hints)\n✅✅\nhttps://travle.earth",
        "#travle #1439 +1 (Perfect)\n✅✅\nhttps://travle.earth",
        "#travle #1439 +0\nhttps://travle.earth",
        "#travle #1439 +0\n✅✅\nWordle 1 1/6\nhttps://travle.earth",
        "#travle #01439 +0\n✅✅\nhttps://travle.earth",
        "#travle #100000 +0\n✅✅\nhttps://travle.earth",
        "#travle #1439 +0 (Ideal)\n✅✅\nhttps://travle.earth");
    invalid.forEach(share -> assertThrows(ApiException.class, () -> parser.parse(share), share));
  }
}
