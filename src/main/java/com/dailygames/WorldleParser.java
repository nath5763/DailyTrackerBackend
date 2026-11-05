package com.dailygames;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.*;

import static com.dailygames.ApiModels.*;

@Component
class WorldleParser implements GameAdapter {
  private static final Pattern HEADER = Pattern.compile("(?i)^#Worldle #([1-9][0-9]*) \\(([0-9]{2}\\.[0-9]{2}\\.[0-9]{4})\\) ([1-6X])/6(?: \\(([0-9]{1,3})%\\))?.*$");
  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu");
  private static final Pattern ROW = Pattern.compile("[🟩🟨⬛⬜]{5}[⬆⬇⬅➡↗↘↖↙🎉]?", Pattern.UNICODE_CHARACTER_CLASS);
  @Override public String slug() { return "worldle"; }
  @Override public ScoreFormatView scoreFormat() { return new ScoreFormatView("", " guesses", 0, 0, "guesses"); }
  @Override public SubmissionConfigView submissionConfig() {
    return new SubmissionConfigView("#Worldle #1103 (28.01.2025) 1/6 (100%)\n🟩🟩🟩🟩🟩🎉",
        "Puzzle number", "Guesses", BigDecimal.ONE, BigDecimal.valueOf(7), BigDecimal.ONE, Set.of());
  }
  @Override public ParsedSubmission parse(String rawText) {
    String[] lines = ShareText.lines(rawText);
    if (lines.length < 2) throw invalid("Paste a complete dated Worldle result.");
    Matcher header = HEADER.matcher(lines[0]);
    if (!header.matches()) throw invalid("A dated Worldle puzzle header is required.");
    int puzzle = ShareTextParserSupport.number(header.group(1));
    LocalDate date;
    try { date = LocalDate.parse(header.group(2), DATE); }
    catch (DateTimeParseException exception) { throw invalid("Worldle date is invalid."); }
    if (date.isAfter(LocalDate.now().plusDays(1))) throw invalid("Worldle date is in the future.");
    if (puzzle >= 1103 && !LocalDate.of(2025, 1, 28).plusDays(puzzle - 1103L).equals(date))
      throw invalid("Worldle puzzle number and date disagree.");
    int attempts = header.group(3).equalsIgnoreCase("X") ? 7 : Integer.parseInt(header.group(3));
    List<String> rows = new ArrayList<>();
    for (int i = 1; i < lines.length && rows.size() < Math.min(attempts, 6); i++) {
      if (rows.isEmpty() && lines[i].startsWith("🔥 Current Win Streak:")) continue;
      if (lines[i].startsWith("http")) break;
      if (!ROW.matcher(lines[i]).matches()) throw invalid("Worldle guess grid is invalid.");
      rows.add(lines[i]);
    }
    if (rows.size() != Math.min(attempts, 6)) throw invalid("Worldle grid and attempt count disagree.");
    boolean won = rows.getLast().contains("🎉");
    if (won != (attempts != 7)) throw invalid("Worldle result and grid disagree.");
    Map<String, Object> metrics = Map.of("completionStatus", won ? "WIN" : "LOSS", "attemptsUsed", attempts == 7 ? 6 : attempts,
        "attemptsMaximum", 6, "rows", rows, "scoreRaw", header.group(3) + "/6", "scoreUnit", "guesses");
    return new ParsedSubmission(String.valueOf(puzzle), puzzle, date, BigDecimal.valueOf(attempts), metrics, 1);
  }
  @Override public ParsedSubmission validateConfirmed(ConfirmedSubmission confirmed, String rawText) {
    return ShareTextParserSupport.confirm(parse(rawText), confirmed);
  }
  private ApiException invalid(String message) { return ShareTextParserSupport.invalid(message); }
}
