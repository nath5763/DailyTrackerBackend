package com.dailygames;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.dailygames.ApiModels.*;

@Component
class FoxiMaxParser implements GameAdapter {
  static final int VERSION = 1;
  private static final LocalDate FIRST_PUZZLE = LocalDate.of(2022, 8, 28);
  private static final Pattern HEADER = Pattern.compile(
      "(?i)^(?:🦊)?#FoxiMax\\s+#([1-9][0-9]*)\\s+([1-8])/8\\s+\\(([1-9]|1[0-9]|2[0-6]) letters\\)$");
  private static final Pattern LINK = Pattern.compile("^https://foximax\\.com/?$");
  private static final Pattern ROW = Pattern.compile("^[🟩⬜]{5}(?:🐉|🐻|🐺|🦊|🐰)?$");

  @Override public String slug() { return "foximax"; }
  @Override public String scoreDirection() { return "LOWER_IS_BETTER"; }
  @Override public ScoreFormatView scoreFormat() { return new ScoreFormatView("", "/8", 0, 0); }
  @Override public SubmissionConfigView submissionConfig() {
    return new SubmissionConfigView(
        "🦊#FoxiMax #1549 6/8 (19 letters)\nhttps://foximax.com/\n\n"
            + "🟩🟩🟩🟩🟩\n🟩🟩🟩🟩⬜\n🟩🟩⬜🟩🟩\n"
            + "⬜🟩🟩🟩🟩\n🟩⬜🟩🟩🟩\n🟩🟩🟩⬜🟩",
        "Puzzle number", "Words", BigDecimal.ONE, BigDecimal.valueOf(8), BigDecimal.ONE, Set.of());
  }

  @Override public List<ResultDetailView> resultDetails(Map<String, Object> metrics) {
    Object letters = metrics.get("lettersGuessed");
    return letters == null ? List.of() : List.of(new ResultDetailView("Letters", letters + " letters"));
  }

  @Override public ParsedSubmission parse(String rawText) {
    String text = rawText == null ? "" : rawText.trim();
    if (text.isEmpty()) throw invalid("Paste your complete FoxiMax result first.");
    if (text.length() > 10_000) throw invalid("Result text is too long.");

    List<String> lines = text.lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
    if (lines.size() < 3) throw invalid("Paste a complete FoxiMax daily result.");
    Matcher header = HEADER.matcher(lines.get(0));
    if (!header.matches()) throw invalid("FoxiMax needs a numbered Daily result header.");
    if (!LINK.matcher(lines.get(1)).matches()) throw invalid("FoxiMax results need the official game link.");

    int puzzle;
    try { puzzle = Integer.parseInt(header.group(1)); }
    catch (NumberFormatException exception) { throw invalid("Could not find a valid FoxiMax puzzle number."); }
    if (puzzle < 1 || puzzle > 100_000) throw invalid("Could not find a valid FoxiMax puzzle number.");
    int words = Integer.parseInt(header.group(2));
    int letters = Integer.parseInt(header.group(3));
    List<String> rows = lines.subList(2, lines.size());
    if (rows.size() != words || rows.stream().anyMatch(row -> !ROW.matcher(row).matches())) {
      throw invalid("FoxiMax results need one five-square row for each scored word.");
    }

    Map<String, Object> metrics = new LinkedHashMap<>();
    metrics.put("completionStatus", "WIN");
    metrics.put("wordsUsed", words);
    metrics.put("lettersGuessed", letters);
    metrics.put("rows", List.copyOf(rows));
    metrics.put("displayScore", words + "/8");
    return new ParsedSubmission(String.valueOf(puzzle), puzzle, FIRST_PUZZLE.plusDays(puzzle - 1L),
        BigDecimal.valueOf(words), Collections.unmodifiableMap(metrics), VERSION);
  }

  @Override public ParsedSubmission validateConfirmed(ConfirmedSubmission confirmed, String rawText) {
    ParsedSubmission parsed = parse(rawText);
    if (confirmed == null || !Objects.equals(parsed.puzzleKey(), confirmed.puzzleKey()) ||
        !Objects.equals(parsed.playedOn(), confirmed.playedOn()) ||
        confirmed.primaryScore() == null || parsed.primaryScore().compareTo(confirmed.primaryScore()) != 0 ||
        !Objects.equals(parsed.metrics(), confirmed.metrics())) {
      throw new ApiException(422, "INVALID_SUBMISSION", "FoxiMax puzzle, date, and score must match the shared result.");
    }
    return parsed;
  }

  private ApiException invalid(String message) {
    return new ApiException(422, "INVALID_SHARE_TEXT", message);
  }
}
