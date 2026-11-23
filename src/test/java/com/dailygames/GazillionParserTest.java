package com.dailygames;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GazillionParserTest {
  private static final String SHARE = "Gazillion #32 · 2,037/5,000\n" +
      "🥇🥉✅🤏❌  top 45%\n" +
      "🐪 Travelled back to the Ancient Egypt (2,792 BC)\n" +
      "Can you beat 2,037?  https://gazillion.world/?s=cp&r=spd7ywd9sp&c=2037&d=32";
  private final GazillionParser parser = new GazillionParser();

  @Test void parsesAndValidatesTheSuppliedDailyResult() {
    ParsedSubmission result = parser.parse(SHARE);
    assertEquals("32", result.puzzleKey());
    assertEquals(32, result.puzzleNumber());
    assertEquals(LocalDate.of(2026, 11, 23), result.playedOn());
    assertEquals(0, BigDecimal.valueOf(2_037).compareTo(result.primaryScore()));
    assertEquals(2_037, result.metrics().get("totalScore"));
    assertEquals(5_000, result.metrics().get("maximumScore"));
    assertEquals(45, result.metrics().get("percentile"));
    assertEquals("the Ancient Egypt (2,792 BC)", result.metrics().get("travelledBackTo"));
    assertEquals(List.of("gold", "bronze", "caught", "close", "miss"),
        result.metrics().get("resultTiers"));
    assertEquals("2,037/5,000", result.metrics().get("displayScore"));
    assertEquals(List.of(
        new ApiModels.ResultDetailView("M1", "gold"),
        new ApiModels.ResultDetailView("M2", "bronze"),
        new ApiModels.ResultDetailView("M3", "caught"),
        new ApiModels.ResultDetailView("M4", "close"),
        new ApiModels.ResultDetailView("M5", "miss")), parser.resultDetails(result.metrics()));
  }

  @Test void acceptsOfficialDecorationsPlainLinksAndScoreBoundaries() {
    ParsedSubmission zero = parser.parse("Gazillion #1 · 0/5,000\n❌❌❌❌❌\n" +
        "🏙️ Travelled back to this year\nCan you beat 0? 👉 https://gazillion.world");
    assertEquals(0, zero.primaryScore().intValueExact());

    ParsedSubmission perfect = parser.parse("💎 PERFECT 5,000 · ONE IN A GAZILLION 💎\n" +
        "Gazillion #1 · 5,000/5,000 · 🔥12\n🏆🏆🏆🏆🏆  top 1%\n" +
        "💥 Travelled back to the Big Bang (13.8 bya)\n" +
        "Can you beat 5,000? 👉 https://gazillion.world/");
    assertEquals(5_000, perfect.primaryScore().intValueExact());
    assertEquals(12, perfect.metrics().get("streak"));
    assertEquals(1, perfect.metrics().get("percentile"));

    ParsedSubmission sourced = parser.parse(SHARE.replace("s=cp&r=spd7ywd9sp", "s=wa"));
    assertEquals(2_037, sourced.primaryScore().intValueExact());
  }

  @Test void acceptsEveryTravelEraBoundary() {
    assertEra(699, "✅✅✅✅✅", "🏙️");
    assertEra(700, "🥉✅✅✅✅", "🏰");
    assertEra(1_400, "🥈🥈✅✅✅", "🐪");
    assertEra(2_100, "🥇🥉🥉🥉✅", "🔥");
    assertEra(2_800, "🥇🥇🥉🥉🥉", "🦣");
    assertEra(3_500, "🥇🥇🥈🥈🥈", "🦖");
    assertEra(4_200, "🏆🏆🥇🥇🥈", "🌋");
    assertEra(4_800, "🏆🏆🏆🏆🏆", "💥");
  }

  @Test void revalidatesConfirmedPreviewExactly() {
    ParsedSubmission parsed = parser.parse(SHARE);
    var confirmed = new ApiModels.ConfirmedSubmission(parsed.puzzleKey(), parsed.puzzleNumber(), parsed.playedOn(),
        parsed.primaryScore(), parsed.metrics(), parsed.parserVersion());
    assertEquals(parsed, parser.validateConfirmed(confirmed, SHARE));
    var changed = new ApiModels.ConfirmedSubmission(parsed.puzzleKey(), parsed.puzzleNumber(), parsed.playedOn(),
        BigDecimal.ZERO, parsed.metrics(), parsed.parserVersion());
    assertThrows(ApiException.class, () -> parser.validateConfirmed(changed, SHARE));
  }

  @Test void rejectsMalformedMismatchedUnsupportedAndFutureResults() {
    List<String> invalid = List.of(
        SHARE.replace("2,037/5,000", "2037/5,000"),
        SHARE.replace("Can you beat 2,037?", "Can you beat 2,038?"),
        SHARE.replace("c=2037", "c=2038"),
        SHARE.replace("d=32", "d=31"),
        SHARE.replace("s=cp", "s=unknown"),
        SHARE.replace("r=spd7ywd9sp", "r=bad"),
        SHARE.replace("top 45%", "top 51%"),
        SHARE.replace("🐪 Travelled", "🔥 Travelled"),
        SHARE.replace("🥇🥉✅🤏❌", "🏆🏆🏆🏆🏆"),
        SHARE.replace("#32", "#100000").replace("d=32", "d=100000"),
        SHARE.replace("Gazillion #32", "Gazillion ∞"),
        SHARE.replace("Travelled back to", "He viajado hasta:"),
        "💎 PERFECT 5,000 · ONE IN A GAZILLION 💎\n" + SHARE,
        SHARE + "\nWordle 1 1/6");
    invalid.forEach(share -> assertThrows(ApiException.class, () -> parser.parse(share), share));
  }

  private void assertEra(int score, String tiers, String emoji) {
    String formatted = String.format(java.util.Locale.US, "%,d", score);
    String share = "Gazillion #1 · " + formatted + "/5,000\n" + tiers + "\n" + emoji +
        " Travelled back to somewhere\nCan you beat " + formatted + "? https://gazillion.world";
    assertEquals(score, parser.parse(share).primaryScore().intValueExact());
  }
}
