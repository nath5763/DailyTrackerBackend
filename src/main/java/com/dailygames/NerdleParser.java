package com.dailygames;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;

import static com.dailygames.ApiModels.*;

@Component
class NerdleParser implements GameAdapter {
  private static final Pattern HEADER = Pattern.compile("(?i)^#?(?:nerdlegame|Nerdle) ([1-9][0-9]*) ([1-6X])/6$");
  private static final Pattern ROW = Pattern.compile("[🟩🟪⬛⬜]{8}", Pattern.UNICODE_CHARACTER_CLASS);
  private static final LocalDate ANCHOR_DATE = LocalDate.of(2026, 6, 29);
  private static final int ANCHOR_PUZZLE = 1622;
  @Override public String slug() { return "nerdle"; }
  @Override public ScoreFormatView scoreFormat() { return new ScoreFormatView("", " guesses", 0, 0, "guesses"); }
  @Override public SubmissionConfigView submissionConfig() {
    return new SubmissionConfigView("nerdlegame 1622 3/6\n🟪⬛⬛⬛🟪🟩⬛⬛\n🟩⬛🟩🟩⬛🟩🟩⬛\n🟩🟩🟩🟩🟩🟩🟩🟩",
        "Puzzle number", "Guesses", BigDecimal.ONE, BigDecimal.valueOf(7), BigDecimal.ONE, Set.of());
  }
  @Override public ParsedSubmission parse(String rawText) {
    String[] lines = ShareText.lines(rawText);
    if (lines.length < 2) throw invalid();
    Matcher header = HEADER.matcher(lines[0]);
    if (!header.matches()) throw invalid();
    int puzzle = ShareTextParserSupport.number(header.group(1));
    int attempts = header.group(2).equalsIgnoreCase("X") ? 7 : Integer.parseInt(header.group(2));
    int expectedRows = Math.min(attempts, 6);
    if (lines.length != expectedRows + 1) throw invalid();
    List<String> rows = Arrays.asList(Arrays.copyOfRange(lines, 1, lines.length));
    List<String> normalizedRows = rows.stream().map(row -> row.replace("\uFE0F", "")).toList();
    if (normalizedRows.stream().anyMatch(row -> !ROW.matcher(row).matches())) throw invalid();
    String solved = "🟩".repeat(8);
    boolean won = normalizedRows.getLast().equals(solved);
    if (won != (attempts != 7) || normalizedRows.subList(0, normalizedRows.size() - 1).contains(solved)) throw invalid();
    LocalDate date = ANCHOR_DATE.plusDays(puzzle - ANCHOR_PUZZLE);
    if (date.isAfter(LocalDate.now().plusDays(1))) throw invalid();
    return new ParsedSubmission(String.valueOf(puzzle), puzzle, date, BigDecimal.valueOf(attempts),
        Map.of("completionStatus", won ? "WIN" : "LOSS", "attemptsUsed", attempts == 7 ? 6 : attempts,
            "attemptsMaximum", 6, "scoreRaw", header.group(2).toUpperCase(Locale.ROOT) + "/6",
            "scoreUnit", "guesses", "rows", rows), 1);
  }
  @Override public ParsedSubmission validateConfirmed(ConfirmedSubmission confirmed, String rawText) {
    return ShareTextParserSupport.confirm(parse(rawText), confirmed);
  }
  private ApiException invalid() { return ShareTextParserSupport.invalid("Paste a complete classic Nerdle result."); }
}
