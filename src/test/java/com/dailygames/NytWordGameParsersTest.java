package com.dailygames;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class NytWordGameParsersTest {
  @Test void parsesStrandsWithAndWithoutHint() {
    // https://www.reddit.com/r/NYTStrands/comments/1xl2jj5/monday_nov_2_2026_strands_daily_thread/
    String hinted = "Strands #974\n“High and mighty”\n💡🔵🟡🔵🔵🔵🔵🔵🔵";
    ParsedSubmission parsed = new StrandsParser().parse(hinted);
    assertEquals(LocalDate.of(2026, 11, 2), parsed.playedOn());
    assertEquals(BigDecimal.ONE, parsed.primaryScore());
    assertEquals("assisted", parsed.metrics().get("assistanceStatus"));

    // Same published thread, no-hint result split across clipboard lines.
    assertEquals(BigDecimal.ZERO, new StrandsParser().parse(
        "Strands #974\n“High and mighty”\n🟡🔵🔵🔵\n🔵🔵🔵🔵").primaryScore());
  }

  @Test void rejectsIncompleteStrandsGrid() {
    assertThrows(ApiException.class, () -> new StrandsParser().parse("Strands #974\n“High and mighty”\n🔵🔵🔵"));
  }

  @Test void parsesPublishedMiniCrosswordShare() {
    // Indexed at https://www.pi.fyi/u/donnadean
    String share = "I solved the 6/22/2026 New York Times Mini Crossword in 0:59!\nhttps://www.nytimes.com/crosswords/game/mini";
    ParsedSubmission parsed = new NytMiniParser().parse(share);
    assertEquals(LocalDate.of(2026, 6, 22), parsed.playedOn());
    assertEquals(BigDecimal.valueOf(59), parsed.primaryScore());
    assertEquals(59, parsed.metrics().get("completionTimeSeconds"));
  }

  @Test void rejectsMiniWithoutExplicitDateOrTime() {
    assertThrows(ApiException.class, () -> new NytMiniParser().parse("I solved the New York Times Mini Crossword!"));
  }
}
