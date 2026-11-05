package com.dailygames;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;

import static com.dailygames.ApiModels.*;

@Component
class StrandsParser implements GameAdapter {
  private static final Pattern HEADER = Pattern.compile("^Strands #([1-9][0-9]*)$");
  private static final Pattern THEME = Pattern.compile("^[“\"](.+)[”\"]$");
  private static final Pattern GRID = Pattern.compile("[🔵🟡💡]+", Pattern.UNICODE_CHARACTER_CLASS);
  private static final LocalDate ANCHOR_DATE = LocalDate.of(2026, 11, 2);
  private static final int ANCHOR_PUZZLE = 974;
  @Override public String slug() { return "strands"; }
  @Override public ScoreFormatView scoreFormat() { return new ScoreFormatView("", " hints", 0, 0); }
  @Override public SubmissionConfigView submissionConfig() {
    return new SubmissionConfigView("Strands #974\n“High and mighty”\n💡🔵🟡🔵🔵🔵🔵🔵🔵",
        "Puzzle number", "Hints", BigDecimal.ZERO, null, BigDecimal.ONE, Set.of());
  }
  @Override public ParsedSubmission parse(String rawText) {
    String[] lines = ShareText.lines(rawText);
    if (lines.length < 3) throw invalid();
    Matcher header = HEADER.matcher(lines[0]), theme = THEME.matcher(lines[1]);
    if (!header.matches() || !theme.matches()) throw invalid();
    int puzzle = ShareTextParserSupport.number(header.group(1));
    StringBuilder grid = new StringBuilder();
    for (int i = 2; i < lines.length && GRID.matcher(lines[i]).matches(); i++) grid.append(lines[i]);
    if (grid.isEmpty()) throw invalid();
    long spangrams = grid.codePoints().filter(value -> value == 0x1F7E1).count();
    long words = grid.codePoints().filter(value -> value == 0x1F535).count();
    long hints = grid.codePoints().filter(value -> value == 0x1F4A1).count();
    if (spangrams != 1 || words < 1) throw invalid();
    LocalDate date = ANCHOR_DATE.plusDays(puzzle - ANCHOR_PUZZLE);
    if (date.isAfter(LocalDate.now().plusDays(1))) throw invalid();
    return new ParsedSubmission(String.valueOf(puzzle), puzzle, date, BigDecimal.valueOf(hints),
        Map.of("completionStatus", "WIN", "hints", hints, "theme", theme.group(1), "resultSequence", grid.toString(),
            "themeWords", words, "scoreRaw", hints + " hints", "scoreUnit", "hints",
            "assistanceStatus", hints > 0 ? "assisted" : "unassisted"), 1);
  }
  @Override public ParsedSubmission validateConfirmed(ConfirmedSubmission confirmed, String rawText) {
    return ShareTextParserSupport.confirm(parse(rawText), confirmed);
  }
  private ApiException invalid() { return ShareTextParserSupport.invalid("Paste a complete Strands share result."); }
}
