package com.dailygames;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.dailygames.ApiModels.*;

@Component
class PerceptleParser implements GameAdapter {
  private static final Pattern HEADER = Pattern.compile(
      "^Perceptle #([1-9][0-9]*) - ([SABCDF]) \\(([0-9]{1,3})\\)$");
  private static final Pattern SIGNATURE = Pattern.compile("(?m)^Perceptle #[1-9][0-9]* - [SABCDF] \\(");
  private static final Pattern SCORES = Pattern.compile(
      "^🔢\\s*([0-9]{1,3})\\s*🎯\\s*([0-9]{1,3})\\s*📐\\s*([0-9]{1,3})" +
          "\\s*🥁\\s*([0-9]{1,3})\\s*✂\\uFE0F?\\s*([0-9]{1,3})$");
  private static final LocalDate FIRST_PUZZLE_DATE = LocalDate.of(2026, 7, 18);

  @Override public String slug() { return "perceptle"; }
  @Override public String scoreDirection() { return "HIGHER_IS_BETTER"; }
  @Override public ScoreFormatView scoreFormat() { return new ScoreFormatView("", "/100", 0, 0, "number"); }
  @Override public SubmissionConfigView submissionConfig() {
    return new SubmissionConfigView(
        "Perceptle #129 - C (62)\n🔢 13  🎯 93  📐 93  🥁 11  ✂️ 98\nhttps://perceptle.io/",
        "Puzzle number", "Score", BigDecimal.ZERO, BigDecimal.valueOf(100), BigDecimal.ONE, Set.of());
  }
  @Override public boolean matches(String rawText) {
    return SIGNATURE.matcher(rawText == null ? "" : rawText).find();
  }
  @Override public ParsedSubmission parse(String rawText) {
    String[] lines = ShareTextParserSupport.lines(rawText);
    if (lines.length != 3) throw invalid();

    Matcher header = HEADER.matcher(lines[0]);
    if (!header.matches()) throw invalid();
    int puzzle = ShareTextParserSupport.number(header.group(1));
    String grade = header.group(2);
    int overallScore = Integer.parseInt(header.group(3));
    if (overallScore > 100) throw invalid();

    Matcher scores = SCORES.matcher(lines[1]);
    if (!scores.matches()) {
      throw ShareTextParserSupport.invalid("Perceptle results must contain exactly five valid event scores.");
    }
    int[] eventScores = new int[5];
    int total = 0;
    for (int index = 0; index < eventScores.length; index++) {
      eventScores[index] = Integer.parseInt(scores.group(index + 1));
      if (eventScores[index] > 100) {
        throw ShareTextParserSupport.invalid("Perceptle event scores must be between 0 and 100.");
      }
      total += eventScores[index];
    }
    int expectedOverall = Math.round(total / 5.0f);
    if (overallScore != expectedOverall) {
      throw ShareTextParserSupport.invalid("Perceptle score does not match the rounded event average.");
    }
    if (!grade.equals(gradeFor(overallScore))) {
      throw ShareTextParserSupport.invalid("Perceptle grade does not match the overall score.");
    }
    if (!lines[2].equals("https://perceptle.io/")) {
      throw ShareTextParserSupport.invalid("Perceptle needs its official result link.");
    }

    LocalDate playedOn = FIRST_PUZZLE_DATE.plusDays((long) puzzle - 1);
    if (playedOn.isAfter(LocalDate.now(ZoneOffset.UTC))) {
      throw ShareTextParserSupport.invalid("That Perceptle puzzle appears to be dated in the future.");
    }

    Map<String, Object> metrics = new LinkedHashMap<>();
    metrics.put("completionStatus", "WIN");
    metrics.put("overallScore", overallScore);
    metrics.put("countScore", eventScores[0]);
    metrics.put("trackScore", eventScores[1]);
    metrics.put("angleScore", eventScores[2]);
    metrics.put("tempoScore", eventScores[3]);
    metrics.put("splitScore", eventScores[4]);
    metrics.put("grade", grade);
    metrics.put("gameMode", "Daily");
    metrics.put("scoreRaw", String.valueOf(overallScore));
    metrics.put("scoreUnit", "points");
    metrics.put("displayScore", grade + " (" + overallScore + ")");
    return new ParsedSubmission(String.valueOf(puzzle), puzzle, playedOn, BigDecimal.valueOf(overallScore),
        metrics, 1);
  }
  @Override public ParsedSubmission validateConfirmed(ConfirmedSubmission confirmed, String rawText) {
    return ShareTextParserSupport.confirm(parse(rawText), confirmed);
  }

  private String gradeFor(int score) {
    if (score >= 95) return "S";
    if (score >= 85) return "A";
    if (score >= 70) return "B";
    if (score >= 55) return "C";
    if (score >= 40) return "D";
    return "F";
  }

  private ApiException invalid() {
    return ShareTextParserSupport.invalid("Paste a complete standard Perceptle daily result.");
  }
}
