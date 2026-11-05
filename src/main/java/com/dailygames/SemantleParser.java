package com.dailygames;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;

import static com.dailygames.ApiModels.*;

@Component
class SemantleParser implements GameAdapter {
  private static final Pattern HEADER = Pattern.compile("^Semantle #([1-9][0-9]*)$", Pattern.CASE_INSENSITIVE);
  private static final Pattern GUESSES = Pattern.compile("^✅ ([1-9][0-9]*) (?:Guesses|Attempts)$", Pattern.CASE_INSENSITIVE);
  private static final Pattern TOP = Pattern.compile("^🔝 (?:Guess|Attempt) #([1-9][0-9]*)$", Pattern.CASE_INSENSITIVE);
  private static final Pattern CLOSEST = Pattern.compile("^🥈 ([0-9]{1,3}|1000)/1000$");
  private static final Pattern HINTS = Pattern.compile("^💡 ?([0-9]+) Hints?$", Pattern.CASE_INSENSITIVE);
  private static final LocalDate ANCHOR_DATE = LocalDate.of(2026, 11, 2);
  private static final int ANCHOR_PUZZLE = 1738;
  @Override public String slug() { return "semantle"; }
  @Override public ScoreFormatView scoreFormat() { return new ScoreFormatView("", " guesses", 0, 0, "guesses"); }
  @Override public SubmissionConfigView submissionConfig() {
    return new SubmissionConfigView("Semantle #1738\n✅ 118 Guesses\n🔝 Guess #116\n🥈 996/1000\n💡 0 Hints\nsemantle.com",
        "Puzzle number", "Guesses", BigDecimal.ONE, null, BigDecimal.ONE, Set.of());
  }
  @Override public ParsedSubmission parse(String rawText) {
    String[] lines = ShareText.lines(rawText);
    if (lines.length < 4) throw invalid();
    Matcher header = HEADER.matcher(lines[0]);
    if (!header.matches() || Arrays.stream(lines).noneMatch(line -> line.equalsIgnoreCase("semantle.com"))) throw invalid();
    int puzzle = ShareTextParserSupport.number(header.group(1));
    int guesses = find(lines, GUESSES, "Semantle guess count is missing.");
    int hints = find(lines, HINTS, "Semantle hint count is missing.");
    Integer top = optional(lines, TOP), closest = optional(lines, CLOSEST);
    if (top != null && top >= guesses || closest != null && closest > 1000) throw invalid();
    LocalDate date = ANCHOR_DATE.plusDays(puzzle - ANCHOR_PUZZLE);
    if (date.isAfter(LocalDate.now().plusDays(1))) throw invalid();
    Map<String, Object> metrics = new LinkedHashMap<>();
    metrics.put("completionStatus", "WIN"); metrics.put("attemptsUsed", guesses);
    metrics.put("hints", hints); metrics.put("scoreRaw", guesses + " guesses"); metrics.put("scoreUnit", "guesses");
    metrics.put("assistanceStatus", hints > 0 ? "assisted" : "unassisted");
    if (top != null) metrics.put("bestPriorGuess", top);
    if (closest != null) metrics.put("closestPriorRank", closest);
    return new ParsedSubmission(String.valueOf(puzzle), puzzle, date, BigDecimal.valueOf(guesses), metrics, 1);
  }
  @Override public ParsedSubmission validateConfirmed(ConfirmedSubmission confirmed, String rawText) {
    return ShareTextParserSupport.confirm(parse(rawText), confirmed);
  }
  private int find(String[] lines, Pattern pattern, String message) {
    Integer value = optional(lines, pattern);
    return value == null ? fail(message) : value;
  }
  private Integer optional(String[] lines, Pattern pattern) {
    return Arrays.stream(lines).map(pattern::matcher).filter(Matcher::matches).map(match -> Integer.valueOf(match.group(1))).findFirst().orElse(null);
  }
  private int fail(String message) { throw ShareTextParserSupport.invalid(message); }
  private ApiException invalid() { return ShareTextParserSupport.invalid("Paste a complete Semantle share result."); }
}
