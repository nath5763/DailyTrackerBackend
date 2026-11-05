package com.dailygames;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class MurdleParserTest {
  private final MurdleParser parser = new MurdleParser();

  @Test void parsesPublishedTimedResult() {
    // Shared by a player and indexed at https://rx-sns.jp/bsky/did%3Aplc%3Aoqiphqj7uiyb5nqeotsjvtsl
    String share = "MURDER AT THE TINY RESORT TOWN I solved Murdle for 6/25/2026 👤🔪🏡 🕰️ ✅✅✅ 2️⃣8️⃣:2️⃣9️⃣ murdle.com";
    ParsedSubmission result = parser.parse(share);
    assertEquals(LocalDate.of(2026, 6, 25), result.playedOn());
    assertEquals(BigDecimal.valueOf(1709), result.primaryScore());
    assertEquals("WIN", result.metrics().get("completionStatus"));
  }

  @Test void rejectsIncompleteShare() {
    assertThrows(ApiException.class, () -> parser.parse("Murdle for 6/25/2026 murdle.com"));
  }
}
