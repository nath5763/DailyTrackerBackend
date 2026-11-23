package com.dailygames;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PerceptleParserTest {
  private static final String SHARE = "Perceptle #129 - C (62)\n" +
      "🔢 13  🎯 93  📐 93  🥁 11  ✂️ 98\n" +
      "https://perceptle.io/";
  private final PerceptleParser parser = new PerceptleParser();

  @Test void parsesAndValidatesTheSuppliedDailyResult() {
    ParsedSubmission result = parser.parse(SHARE);
    assertEquals("129", result.puzzleKey());
    assertEquals(129, result.puzzleNumber());
    assertEquals(LocalDate.of(2026, 11, 23), result.playedOn());
    assertEquals(0, BigDecimal.valueOf(62).compareTo(result.primaryScore()));
    assertEquals(62, result.metrics().get("overallScore"));
    assertEquals(13, result.metrics().get("countScore"));
    assertEquals(93, result.metrics().get("trackScore"));
    assertEquals(93, result.metrics().get("angleScore"));
    assertEquals(11, result.metrics().get("tempoScore"));
    assertEquals(98, result.metrics().get("splitScore"));
    assertEquals("C", result.metrics().get("grade"));
    assertEquals("C (62)", result.metrics().get("displayScore"));
    assertEquals(List.of(
        new ApiModels.ResultDetailView("Count", "13"),
        new ApiModels.ResultDetailView("Track", "93"),
        new ApiModels.ResultDetailView("Angle", "93"),
        new ApiModels.ResultDetailView("Tempo", "11"),
        new ApiModels.ResultDetailView("Split", "98")), parser.resultDetails(result.metrics()));
  }

  @Test void acceptsScoreBoundariesEveryGradeThresholdAndFlexibleSpacing() {
    assertEquals("F", parser.parse(share(1, 0, 0, 0, 0, 0, 0)).metrics().get("grade"));
    assertEquals("S", parser.parse(share(1, 100, 100, 100, 100, 100, 100)).metrics().get("grade"));
    int[] thresholds = {40, 55, 70, 85, 95};
    String[] grades = {"D", "C", "B", "A", "S"};
    for (int index = 0; index < thresholds.length; index++) {
      ParsedSubmission result = parser.parse(share(1, thresholds[index], thresholds[index], thresholds[index],
          thresholds[index], thresholds[index], thresholds[index]));
      assertEquals(grades[index], result.metrics().get("grade"));
    }
    assertEquals(62, parser.parse(SHARE.replace("  ", "\t   ").replace("✂️ 98", "✂  98"))
        .primaryScore().intValueExact());
  }

  @Test void roundsTheMeanAcrossEveryPossibleRemainder() {
    int[] expected = {50, 50, 50, 51, 51};
    for (int remainder = 0; remainder < 5; remainder++) {
      ParsedSubmission result = parser.parse(share(1, expected[remainder], 50 + remainder, 50, 50, 50, 50));
      assertEquals(expected[remainder], result.primaryScore().intValueExact());
    }
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

  @Test void rejectsIncorrectScoresGradesCountsModesLinksAndExtraContent() {
    List<String> invalid = List.of(
        SHARE.replace("C (62)", "C (63)"),
        SHARE.replace("C (62)", "B (62)"),
        SHARE.replace("✂️ 98", ""),
        SHARE.replace("✂️ 98", "✂️ 98  🔢 13"),
        SHARE.replace("🔢 13", "🔢 101"),
        SHARE.replace("#129", "#0"),
        SHARE.replace("#129", "#0129"),
        SHARE.replace("#129", "#100000"),
        SHARE.replace("perceptle.io", "example.com"),
        SHARE.replace("https://perceptle.io/", "https://perceptle.io/unlimited/"),
        SHARE.replace("#129 -", "#129 🃏 -"),
        SHARE.replace("C (62)", "C (62) ★"),
        SHARE + "\nWordle 1 1/6");
    invalid.forEach(share -> assertThrows(ApiException.class, () -> parser.parse(share), share));
  }

  private String share(int puzzle, int overall, int count, int track, int angle, int tempo, int split) {
    return "Perceptle #" + puzzle + " - " + grade(overall) + " (" + overall + ")\n" +
        "🔢 " + count + "  🎯 " + track + "  📐 " + angle + "  🥁 " + tempo + "  ✂️ " + split + "\n" +
        "https://perceptle.io/";
  }

  private String grade(int score) {
    if (score >= 95) return "S";
    if (score >= 85) return "A";
    if (score >= 70) return "B";
    if (score >= 55) return "C";
    if (score >= 40) return "D";
    return "F";
  }
}
