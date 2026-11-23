package com.dailygames;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;

import static com.dailygames.ApiModels.*;

@Component
class FermiParser implements GameAdapter {
  static final int VERSION = 1;
  private static final LocalDate FIRST_PUZZLE = LocalDate.of(2026, 7, 27);
  private static final Pattern PUZZLE = Pattern.compile("(?i)(?:fermi\\s*)?#\\s*(\\d+)");
  private static final Pattern NUMBERED = Pattern.compile("(?i)\\bNo\\.?\\s*(\\d+)");
  private static final Pattern SCORE = Pattern.compile("(?i)(\\d+(?:[.,]\\d+)*)\\s*[×x]\\s*score\\b");
  private static final Pattern PERCENTILE = Pattern.compile("(?i)top\\s*(\\d+(?:\\.\\d+)?)\\s*%");
  private static final Pattern MULTIPLIER = Pattern.compile("(?i)(\\d+(?:[.,]\\d+)*)\\s*[×x]");

  @Override public String slug() { return "fermi"; }
  @Override public String scoreDirection() { return "LOWER_IS_BETTER"; }
  @Override public ScoreFormatView scoreFormat() { return new ScoreFormatView("", "×", 0, 2); }
  @Override public SubmissionConfigView submissionConfig() {
    return new SubmissionConfigView("Fermi #92\n1.31× score\ntop 8%", "Puzzle number", "Final score",
        BigDecimal.ONE, null, new BigDecimal("0.01"), Set.of("puzzleKey", "playedOn", "primaryScore"));
  }

  @Override public List<ResultDetailView> resultDetails(Map<String, Object> metrics) {
    Object rawComponents = metrics.get("components");
    List<ResultDetailView> details = new ArrayList<>();
    if (rawComponents instanceof Collection<?> components) {
      for (Object raw : components) {
        if (details.size() == 3) break;
        try {
          BigDecimal value = raw instanceof BigDecimal decimal ? decimal : new BigDecimal(raw.toString());
          details.add(new ResultDetailView("Q" + (details.size() + 1), displayMultiplier(value)));
        } catch (RuntimeException ignored) {
          // Ignore malformed historical presentation-only metrics without affecting the saved result.
        }
      }
    }
    Object rawPercentile = metrics.get("percentile");
    if (rawPercentile != null) {
      try {
        BigDecimal percentile = rawPercentile instanceof BigDecimal decimal ? decimal
            : new BigDecimal(rawPercentile.toString());
        details.add(new ResultDetailView("Top", DecimalPresentation.format(percentile) + "%"));
      } catch (RuntimeException ignored) {
        // Ignore malformed historical presentation-only metrics without affecting the saved result.
      }
    }
    return List.copyOf(details);
  }

  @Override public ParsedSubmission parse(String rawText) {
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
    boolean foundLabeledScore = scoreMatch.find();
    BigDecimal score = foundLabeledScore ? decimal(scoreMatch.group(1)) : fallbackScore(text);
    if (score == null || score.compareTo(BigDecimal.ONE) < 0) errors.add("Could not find a valid final score of 1× or greater.");
    if (!errors.isEmpty()) throw new ApiException(422, "INVALID_SHARE_TEXT", String.join(" ", errors));

    Map<String, Object> metrics = new LinkedHashMap<>();
    metrics.put("completionStatus", "WIN");
    metrics.put("scoreRaw", score.toPlainString());
    metrics.put("displayScore", displayMultiplier(score));
    Matcher percentile = PERCENTILE.matcher(text);
    if (percentile.find()) metrics.put("percentile", decimal(percentile.group(1)));
    List<BigDecimal> components = new ArrayList<>();
    Matcher values = MULTIPLIER.matcher(text);
    boolean skippedFallbackScore = false;
    while (values.find() && components.size() < 3) {
      BigDecimal value = decimal(values.group(1));
      boolean finalScoreMatch = foundLabeledScore && values.start() >= scoreMatch.start() && values.end() <= scoreMatch.end();
      if (finalScoreMatch || !foundLabeledScore && !skippedFallbackScore && value.compareTo(score) == 0) {
        skippedFallbackScore = true;
      } else {
        components.add(value);
      }
    }
    if (!components.isEmpty()) metrics.put("components", components);
    return new ParsedSubmission(String.valueOf(puzzle), puzzle, FIRST_PUZZLE.plusDays(puzzle - 1L), score, metrics, VERSION);
  }

  @Override public ParsedSubmission validateConfirmed(ConfirmedSubmission confirmed, String rawText) {
    ParsedSubmission original = parse(rawText);
    Integer puzzleNumber;
    try { puzzleNumber = Integer.valueOf(confirmed.puzzleKey()); }
    catch (RuntimeException exception) { puzzleNumber = null; }
    if (puzzleNumber == null || puzzleNumber < 1 || confirmed.playedOn() == null ||
        confirmed.primaryScore() == null || confirmed.primaryScore().compareTo(BigDecimal.ONE) < 0) {
      throw new ApiException(422, "INVALID_SUBMISSION", "Check the confirmed puzzle, date, and score.");
    }
    Map<String, Object> metrics = new LinkedHashMap<>(confirmed.metrics() == null ? original.metrics() : confirmed.metrics());
    metrics.put("completionStatus", "WIN");
    metrics.put("scoreRaw", confirmed.primaryScore().toPlainString());
    metrics.put("displayScore", displayMultiplier(confirmed.primaryScore()));
    return new ParsedSubmission(confirmed.puzzleKey(), puzzleNumber, confirmed.playedOn(), confirmed.primaryScore(), metrics, VERSION);
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

  private BigDecimal decimal(String value) {
    String normalized = value.matches("[0-9]{1,3}(,[0-9]{3})+(?:\\.[0-9]+)?")
        ? value.replace(",", "") : value.replace(',', '.');
    return new BigDecimal(normalized);
  }

  private String displayMultiplier(BigDecimal value) {
    return DecimalPresentation.format(value) + "×";
  }
}
