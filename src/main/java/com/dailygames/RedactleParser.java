package com.dailygames;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;

import static com.dailygames.ApiModels.*;

@Component
class RedactleParser implements GameAdapter {
  private static final Pattern RESULT = Pattern.compile("(?i)I (?:cracked|solved) Redactle #([1-9][0-9]*) in ([1-9][0-9]*) guesses!");
  private static final Pattern TIME = Pattern.compile("(?i)⏱\\uFE0F? ?([0-9]+)m([0-5]?[0-9])s");
  private static final Pattern HINTS = Pattern.compile("(?i)Hints(?:👆([0-9]+), ?💬([0-9]+)|: none)");
  private static final LocalDate ANCHOR_DATE = LocalDate.of(2026, 11, 5);
  private static final int ANCHOR_PUZZLE = 1674;
  @Override public String slug() { return "redactle"; }
  @Override public ScoreFormatView scoreFormat() { return new ScoreFormatView("", " guesses", 0, 0, "guesses"); }
  @Override public SubmissionConfigView submissionConfig() {
    return new SubmissionConfigView("I cracked Redactle #1674 in 27 guesses!\n🎲 27 | 🔥 88 | Avg 53.18 | Hints: none | ⏱️ 14m23s\nhttps://redactle.net/",
        "Puzzle number", "Guesses", BigDecimal.ONE, null, BigDecimal.ONE, Set.of());
  }
  @Override public ParsedSubmission parse(String rawText) {
    if (rawText == null || rawText.length() > 10_000) throw invalid();
    Matcher result = RESULT.matcher(rawText);
    if (!result.find() || !rawText.toLowerCase(Locale.ROOT).contains("redactle")) throw invalid();
    int puzzle = ShareTextParserSupport.number(result.group(1)), guesses = Integer.parseInt(result.group(2));
    LocalDate date = ANCHOR_DATE.plusDays(puzzle - ANCHOR_PUZZLE);
    if (date.isAfter(LocalDate.now().plusDays(1))) throw invalid();
    Map<String, Object> metrics = new LinkedHashMap<>();
    metrics.put("completionStatus", "WIN"); metrics.put("attemptsUsed", guesses);
    metrics.put("scoreRaw", guesses + " guesses"); metrics.put("scoreUnit", "guesses");
    Matcher time = TIME.matcher(rawText);
    if (time.find()) metrics.put("completionTimeSeconds", Integer.parseInt(time.group(1)) * 60 + Integer.parseInt(time.group(2)));
    Matcher hints = HINTS.matcher(rawText);
    if (hints.find()) {
      int hintCount = hints.group(1) == null ? 0 : Integer.parseInt(hints.group(1)) + Integer.parseInt(hints.group(2));
      metrics.put("hints", hintCount); metrics.put("assistanceStatus", hintCount == 0 ? "unassisted" : "assisted");
    } else metrics.put("assistanceStatus", "unknown");
    return new ParsedSubmission(String.valueOf(puzzle), puzzle, date, BigDecimal.valueOf(guesses), metrics, 1);
  }
  @Override public ParsedSubmission validateConfirmed(ConfirmedSubmission confirmed, String rawText) {
    return ShareTextParserSupport.confirm(parse(rawText), confirmed);
  }
  private ApiException invalid() { return ShareTextParserSupport.invalid("Paste a complete Redactle solve result."); }
}
