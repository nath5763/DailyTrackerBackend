package com.dailygames;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.dailygames.ApiModels.ConfirmedSubmission;
import static org.junit.jupiter.api.Assertions.*;

class KrillionParserTest {
  private final KrillionParser parser = new KrillionParser();

  @Test void parsesAndValidatesDailyShare() {
    String share = "Krillion #82 🦐\n180\n\n🐟🫧🫧🐟🦑🫧🐟\n\nhttps://krillion.io";
    ParsedSubmission parsed = parser.parse(share);

    assertEquals("82", parsed.puzzleKey());
    assertEquals(new BigDecimal("180"), parsed.primaryScore());
    assertEquals(LocalDate.of(2026, 10, 5), parsed.playedOn());
    assertEquals("WIN", parsed.metrics().get("completionStatus"));
    assertEquals(List.of("schooler", "plankton", "plankton", "schooler", "rare", "plankton", "schooler"),
        parsed.metrics().get("tiers"));
    assertEquals(parsed, parser.validateConfirmed(confirmed(parsed), share));
  }

  @Test void acceptsZeroAndPerfectScores() {
    assertEquals(BigDecimal.ZERO, parser.parse("Krillion #1 🦐\n0\n\n⬛⬛⬛⬛⬛⬛⬛").primaryScore());
    assertEquals(new BigDecimal("700"), parser.parse("Krillion #2 🦐\n700\n\n🌟🌟🌟🌟🌟🌟🌟").primaryScore());
  }

  @Test void rejectsOtherModesAndInvalidSymbols() {
    assertEquals("INVALID_SHARE_TEXT", assertThrows(ApiException.class,
        () -> parser.parse("Krillion ⟲ #82 🦐\n180\n\n🐟🫧🫧🐟🦑🫧🐟")).code);
    assertEquals("INVALID_SHARE_TEXT", assertThrows(ApiException.class,
        () -> parser.parse("Krillion ∞ #82 🦐\n180\n\n🐟🫧🫧🐟🦑🫧🐟")).code);
    assertTrue(assertThrows(ApiException.class,
        () -> parser.parse("Krillion #82 🦐\n181\n\n🐟🫧🫧🐟🦑🫧🐟")).getMessage().contains("does not match"));
    assertTrue(assertThrows(ApiException.class,
        () -> parser.parse("Krillion #82 🦐\n150\n\n🐟🫧🫧🐟🦑🫧")).getMessage().contains("seven"));
  }

  @Test void rejectsChangedConfirmation() {
    String share = "Krillion #82 🦐\n180\n\n🐟🫧🫧🐟🦑🫧🐟";
    ParsedSubmission parsed = parser.parse(share);
    ConfirmedSubmission changed = new ConfirmedSubmission(parsed.puzzleKey(), parsed.puzzleNumber(), parsed.playedOn(),
        new BigDecimal("181"), parsed.metrics(), parsed.parserVersion());
    assertEquals("INVALID_SUBMISSION", assertThrows(ApiException.class,
        () -> parser.validateConfirmed(changed, share)).code);
  }

  private ConfirmedSubmission confirmed(ParsedSubmission parsed) {
    return new ConfirmedSubmission(parsed.puzzleKey(), parsed.puzzleNumber(), parsed.playedOn(), parsed.primaryScore(),
        parsed.metrics(), parsed.parserVersion());
  }
}
