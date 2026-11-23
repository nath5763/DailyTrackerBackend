package com.dailygames;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DailyOrbsParserTest {
  private static final String SHARE = "DailyOrbs November 23 2026\n" +
      "https://DailyOrbs.com/2026-11-23?utm_source=share\n" +
      "🟠🧡🟠🟢💚🟢🔵🔵🏆🟣🟣";
  private final DailyOrbsParser parser = new DailyOrbsParser();

  @Test void parsesHeartInclusiveWinningNarrativeAndPostWinOrbs() {
    ParsedSubmission result = parser.parse(SHARE);
    assertEquals("2026-11-23", result.puzzleKey());
    assertEquals(LocalDate.of(2026, 11, 23), result.playedOn());
    assertEquals(BigDecimal.valueOf(8), result.primaryScore());
    assertEquals(2, result.metrics().get("orangeOrbs"));
    assertEquals(2, result.metrics().get("greenOrbs"));
    assertEquals(2, result.metrics().get("blueOrbs"));
    assertEquals(2, result.metrics().get("purpleOrbs"));
    assertEquals(2, result.metrics().get("heartLosses"));
    assertEquals("8/12 orbs", result.metrics().get("displayScore"));
    assertEquals(List.of(
        new ApiModels.ResultDetailView("Orange", "2 orbs · 1 heart lost"),
        new ApiModels.ResultDetailView("Green", "2 orbs · 1 heart lost"),
        new ApiModels.ResultDetailView("Blue", "2 orbs · 0 hearts lost"),
        new ApiModels.ResultDetailView("Purple", "2 orbs · 0 hearts lost")), parser.resultDetails(result.metrics()));
  }

  @Test void acceptsOrbsOnlySixOrbAndPerfectShares() {
    ParsedSubmission six = parser.parse("DailyOrbs November 23 2026\n" +
        "https://dailyorbs.com/2026-11-23?utm_source=share\n🟠🟠🟠🟢🟢🟢🏆");
    assertEquals(6, six.primaryScore().intValueExact());
    assertEquals(false, six.metrics().get("heartsDisclosed"));

    ParsedSubmission perfect = parser.parse("DailyOrbs November 23 2026\n" +
        "https://DailyOrbs.com/2026-11-23?utm_source=share\n" +
        "🟠🟠🟠🟢🟢🟢🏆🔵🔵🔵🟣🟣🟣");
    assertEquals(12, perfect.primaryScore().intValueExact());
  }

  @Test void rejectsLossesBadDatesTrophiesCountsAndMixedText() {
    List<String> invalid = List.of(
        SHARE.replace("November 23", "November 22"),
        SHARE.replace("/2026-11-23?", "/2026-11-22?"),
        SHARE.replace("utm_source=share", "utm_source=x"),
        SHARE.replace("🔵🔵🏆", "🔵🏆🔵"),
        SHARE.replace("🏆", ""),
        SHARE.replace("🏆", "🏆🏆"),
        "DailyOrbs November 23 2026\nhttps://DailyOrbs.com/2026-11-23?utm_source=share\n🟠🟠🟢🟢🔵🏆",
        "DailyOrbs November 23 2026\nhttps://DailyOrbs.com/2026-11-23?utm_source=share\n🟠🟠🟠🟠🟢🟢🏆",
        SHARE + "\nWordle 1 1/6",
        "DailyOrbs November 24 2026\nhttps://DailyOrbs.com/2026-11-24?utm_source=share\n🟠🟠🟠🟢🟢🟢🏆");
    invalid.forEach(value -> assertThrows(ApiException.class, () -> parser.parse(value), value));
  }
}
