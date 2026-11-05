package com.dailygames;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class GlobleParserTest {
  private final GlobleParser parser = new GlobleParser();

  @Test void parsesPublishedShare() {
    // Player result: https://www.reddit.com/r/Reformed/comments/1vu7uc9/free_for_all_friday_post_on_any_topic_in_this/
    String share = "🌎 Aug 21, 2026 🌍\n🔥 5 | Avg. Guesses: 7.4\n🟨🟥🟥🟥🟥🟥🟩 = 7\nhttps://globle-game.com/\n#globle";
    ParsedSubmission parsed = parser.parse(share);
    assertEquals(LocalDate.of(2026, 8, 21), parsed.playedOn());
    assertEquals(BigDecimal.valueOf(7), parsed.primaryScore());
    assertEquals("WIN", parsed.metrics().get("completionStatus"));
  }

  @Test void rejectsGridScoreContradictionAndEmojiOnlyText() {
    assertThrows(ApiException.class, () -> parser.parse("🌎 Aug 21, 2026 🌍\n🟨🟥🟩 = 7\n#globle"));
    assertFalse(parser.matches("🟨🟥🟩"));
  }
}
