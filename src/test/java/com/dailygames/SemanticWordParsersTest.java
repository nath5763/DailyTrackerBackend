package com.dailygames;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class SemanticWordParsersTest {
  @Test void parsesPublishedSemantleShareAndAttemptsVariant() {
    // https://www.reddit.com/r/Semantlegameplayers/comments/1xlkxag/daily_semantle_1738/
    String share = "Semantle #1738\n✅ 119 Guesses\n🔝 Guess #116\n🥈 986/1000\n💡 1 Hint\nsemantle.com";
    ParsedSubmission parsed = new SemantleParser().parse(share);
    assertEquals(LocalDate.of(2026, 11, 2), parsed.playedOn());
    assertEquals(BigDecimal.valueOf(119), parsed.primaryScore());
    assertEquals(1, parsed.metrics().get("hints"));

    // Alternate shared wording from https://www.reddit.com/r/Semantlegameplayers/comments/1w8gpif/daily_semantle_1681/
    assertEquals(BigDecimal.valueOf(298), new SemantleParser().parse(
        "Semantle #1681\n✅ 298 Attempts\n🔝 Attempt #296\n🥈 997/1000\n💡 0 Hints\nsemantle.com").primaryScore());
  }

  @Test void rejectsIncompleteSemantleShare() {
    assertThrows(ApiException.class, () -> new SemantleParser().parse("Semantle #1738\n✅ 12 Guesses"));
  }

  @Test void parsesExplicitlyDatedContextoShare() {
    // https://www.reddit.com/r/contexto/comments/1xg63rm/my_first_time_playing_contexto_and_this_is_the/
    String share = "I played contexto.me 10/26/2026 and got it in 5 guesses.\n🟩🟩🟩🟩 4\n0\n🟥 1";
    ParsedSubmission parsed = new ContextoParser().parse(share);
    assertEquals(LocalDate.of(2026, 10, 26), parsed.playedOn());
    assertEquals(BigDecimal.valueOf(5), parsed.primaryScore());
    assertThrows(ApiException.class, () -> new ContextoParser().parse(share.replace("🟥 1", "🟥 2")));
  }

  @Test void parsesRedactleRichAndBasicShares() {
    // https://www.reddit.com/r/Redactle/comments/1xomtfq/redactle_1674_discussion_thread/
    String rich = "I cracked Redactle #1674 in 27 guesses!\n🎲 27 | 🔥 88 | Avg 53.18 | Hints: none | ⏱️ 14m23s\nhttps://redactle.net/";
    ParsedSubmission parsed = new RedactleParser().parse(rich);
    assertEquals(LocalDate.of(2026, 11, 5), parsed.playedOn());
    assertEquals(863, parsed.metrics().get("completionTimeSeconds"));
    assertEquals("unassisted", parsed.metrics().get("assistanceStatus"));

    assertEquals(BigDecimal.valueOf(6), new RedactleParser().parse("I solved Redactle #1674 in 6 guesses! ⏱️ 6m13s").primaryScore());
  }
}
