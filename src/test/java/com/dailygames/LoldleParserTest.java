package com.dailygames;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LoldleParserTest {
  private static final String SHARE = "I've completed all the modes of #LoLdle today:\n"
      + "❓ Classic: 8\n💬 Quote: 1\n🔥 Ability: 1 ✓\n😀 Emoji: 1\n🎨 Splash: 2 ✓\nhttps://loldle.net";
  private final LocalDate date = LocalDate.of(2026, 6, 1);

  @Test void parsesEveryModeAndClueMarker() {
    Map<String, ParsedSubmission> scores = LoldleParser.parse(SHARE, date);
    assertEquals(List.of("loldle-classic", "loldle-quote", "loldle-ability", "loldle-emoji", "loldle-splash"),
        scores.keySet().stream().toList());
    assertEquals(List.of(8, 1, 1, 1, 2), scores.values().stream().map(value -> value.primaryScore().intValue()).toList());
    assertTrue((Boolean) scores.get("loldle-ability").metrics().get("clueUsed"));
    assertEquals("assisted", scores.get("loldle-splash").metrics().get("assistanceStatus"));
    assertEquals("unassisted", scores.get("loldle-quote").metrics().get("assistanceStatus"));
    scores.values().forEach(score -> { assertEquals(date, score.playedOn()); assertEquals(date.toString(), score.puzzleKey()); });
  }

  @Test void rejectsMissingMalformedAndAmbiguousShares() {
    assertEquals("INVALID_LOLDLE_SHARE", assertThrows(ApiException.class,
        () -> LoldleParser.parse(SHARE.replace("😀 Emoji: 1\n", ""), date)).code);
    assertThrows(ApiException.class, () -> LoldleParser.parse(SHARE.replace("🔥 Ability: 1 ✓", "🔥 Ability: X"), date));
    assertThrows(ApiException.class, () -> LoldleParser.parse(SHARE.replace("💬 Quote: 1", "💬 Quote: 1\n💬 Quote: 2"), date));
    assertThrows(ApiException.class, () -> LoldleParser.parse(SHARE.replace("🎨 Splash: 2 ✓", "🎨 Splash: 2 ✓ ✓"), date));
    assertThrows(ApiException.class, () -> LoldleParser.parse(SHARE.replace("https://loldle.net", "https://example.com"), date));
  }

  @Test void requiresConfirmedNonFutureDate() {
    assertThrows(ApiException.class, () -> LoldleParser.parse(SHARE, null));
    assertThrows(ApiException.class, () -> LoldleParser.parse(SHARE, LocalDate.now(ZoneOffset.UTC).plusDays(1)));
  }
}
