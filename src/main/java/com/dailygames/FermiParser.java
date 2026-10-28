package com.dailygames;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;

@Component
class FermiParser {
  static final int VERSION = 1;
  private static final LocalDate FIRST_PUZZLE = LocalDate.of(2026, 7, 27);
  private static final Pattern PUZZLE = Pattern.compile("(?i)(?:fermi\\s*)?#\\s*(\\d+)");
  private static final Pattern NUMBERED = Pattern.compile("(?i)\\bNo\\.?\\s*(\\d+)");
  private static final Pattern SCORE = Pattern.compile("(?i)(\\d+(?:[.,]\\d+)?)\\s*[×x]\\s*score\\b");
  private static final Pattern PERCENTILE = Pattern.compile("(?i)top\\s*(\\d+(?:\\.\\d+)?)\\s*%");
  private static final Pattern MULTIPLIER = Pattern.compile("(?i)(\\d+(?:[.,]\\d+)?)\\s*[×x]");

  record Parsed(String puzzleKey, int puzzleNumber, LocalDate playedOn, BigDecimal primaryScore,
                Map<String, Object> metrics, int parserVersion) {}

  Parsed parse(String rawText) {
    String text = rawText == null ? "" : rawText.trim();
    List<String> errors = new ArrayList<>();
    if (text.isEmpty()) throw new ApiException(422, "INVALID_SHARE_TEXT", "Paste your Fermi result first.");
    if (text.length() > 10_000) throw new ApiException(422, "INVALID_SHARE_TEXT", "Result text is too long.");
    if (!text.toLowerCase(Locale.ROOT).contains("fermi")) errors.add("This does not look like a Fermi result.");

    Matcher puzzleMatch = PUZZLE.matcher(text);
    if (!puzzleMatch.find()) puzzleMatch = NUMBERED.matcher(text);
    Integer puzzle = puzzleMatch.find(0) ? Integer.valueOf(puzzleMatch.group(1)) : null;
    if (puzzle == null || puzzle < 1) errors.add("Could not find a valid Fermi puzzle number.");

    Matcher scoreMatch = SCORE.matcher(text);
    BigDecimal score = scoreMatch.find() ? decimal(scoreMatch.group(1)) : fallbackScore(text);
    if (score == null || score.compareTo(BigDecimal.ONE) < 0) errors.add("Could not find a valid final score of 1× or greater.");
    if (!errors.isEmpty()) throw new ApiException(422, "INVALID_SHARE_TEXT", String.join(" ", errors));

    Map<String, Object> metrics = new LinkedHashMap<>();
    Matcher percentile = PERCENTILE.matcher(text);
    if (percentile.find()) metrics.put("percentile", decimal(percentile.group(1)));
    List<BigDecimal> components = new ArrayList<>();
    Matcher values = MULTIPLIER.matcher(text);
    while (values.find() && components.size() < 3) {
      BigDecimal value = decimal(values.group(1));
      if (value.compareTo(score) != 0) components.add(value);
    }
    if (!components.isEmpty()) metrics.put("components", components);
    return new Parsed(String.valueOf(puzzle), puzzle, FIRST_PUZZLE.plusDays(puzzle - 1L), score, metrics, VERSION);
  }

  Parsed validateConfirmed(ApiModels.ConfirmedSubmission confirmed, String rawText) {
    Parsed original = parse(rawText);
    if (confirmed.puzzleNumber() == null || confirmed.puzzleNumber() < 1 || confirmed.playedOn() == null ||
        confirmed.primaryScore() == null || confirmed.primaryScore().compareTo(BigDecimal.ONE) < 0) {
      throw new ApiException(422, "INVALID_SUBMISSION", "Check the confirmed puzzle, date, and score.");
    }
    if (!Objects.equals(confirmed.puzzleKey(), String.valueOf(confirmed.puzzleNumber()))) {
      throw new ApiException(422, "INVALID_SUBMISSION", "Puzzle key must match the puzzle number.");
    }
    Map<String, Object> metrics = confirmed.metrics() == null ? original.metrics() : confirmed.metrics();
    return new Parsed(confirmed.puzzleKey(), confirmed.puzzleNumber(), confirmed.playedOn(), confirmed.primaryScore(), metrics, VERSION);
  }

  private BigDecimal fallbackScore(String text) {
    for (String line : text.split("\\R")) {
      if (line.toLowerCase(Locale.ROOT).contains("score")) {
        Matcher match = MULTIPLIER.matcher(line);
        if (match.find()) return decimal(match.group(1));
      }
    }
    return null;
  }

  private BigDecimal decimal(String value) { return new BigDecimal(value.replace(',', '.')); }
}
