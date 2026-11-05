package com.dailygames;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class WorldleParserTest {
  private final WorldleParser parser = new WorldleParser();
  // Player result: https://www.reddit.com/r/Worldle/comments/1iacl1a/
  private static final String SHARE = "#Worldle #1103 (28.01.2025) 1/6 (100%)\n🟩🟩🟩🟩🟩🎉\n🧭⭐🚩🗿📜🛡️🔤🗣️🪙🏙📦🔄💯";

  @Test void parsesDatedShareAndIgnoresBonusGames() {
    ParsedSubmission parsed = parser.parse(SHARE);
    assertEquals("1103", parsed.puzzleKey());
    assertEquals(LocalDate.of(2025, 1, 28), parsed.playedOn());
    assertEquals(BigDecimal.ONE, parsed.primaryScore());
    assertEquals("WIN", parsed.metrics().get("completionStatus"));
  }

  @Test void requiresDatedHeader() {
    assertThrows(ApiException.class, () -> parser.parse("#Worldle #24 1/6\n🟩🟩🟩🟩🟩"));
  }

  @Test void parsesSharedLoss() {
    // Player result: https://www.reddit.com/r/Worldle/comments/1udqk33/worldle_tuesday_june_23_2026/
    String loss = "#Worldle #1614 (23.06.2026) X/6 (83%)\n🔥 Current Win Streak: 0 days\n" +
        "🟩⬜⬜⬜⬜↘️\n🟩🟨⬜⬜⬜⬇️\n🟩🟩🟩🟩⬜⬇️\n" +
        "🟩🟩🟩⬜⬜➡️\n🟩⬜⬜⬜⬜↘️\n🟩⬜⬜⬜⬜↘️";
    ParsedSubmission parsed = parser.parse(loss);
    assertEquals("LOSS", parsed.metrics().get("completionStatus"));
    assertEquals(BigDecimal.valueOf(7), parsed.primaryScore());
  }
}
