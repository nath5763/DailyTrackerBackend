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
class NytMiniParser implements GameAdapter {
  private static final Pattern RESULT = Pattern.compile("^I solved the ([0-9]{1,2}/[0-9]{1,2}/[0-9]{4}) New York Times Mini Crossword in ([0-9]+):([0-5][0-9])!$");
  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("M/d/uuuu");
  @Override public String slug() { return "nyt-mini-crossword"; }
  @Override public ScoreFormatView scoreFormat() { return new ScoreFormatView("", "", 0, 0, "duration"); }
  @Override public SubmissionConfigView submissionConfig() {
    return new SubmissionConfigView("I solved the 6/22/2026 New York Times Mini Crossword in 0:59!\nhttps://www.nytimes.com/crosswords/game/mini",
        "Puzzle date", "Time", BigDecimal.ZERO, null, BigDecimal.ONE, Set.of());
  }
  @Override public boolean matches(String rawText) {
    String[] lines = ShareText.lines(rawText);
    return lines.length > 0 && RESULT.matcher(lines[0]).matches();
  }
  @Override public ParsedSubmission parse(String rawText) {
    String[] lines = ShareText.lines(rawText);
    if (lines.length < 1) throw invalid();
    Matcher result = RESULT.matcher(lines[0]);
    if (!result.matches()) throw invalid();
    LocalDate date;
    try { date = LocalDate.parse(result.group(1), DATE); }
    catch (DateTimeParseException exception) { throw invalid(); }
    if (date.isAfter(LocalDate.now().plusDays(1))) throw invalid();
    int seconds = Integer.parseInt(result.group(2)) * 60 + Integer.parseInt(result.group(3));
    return new ParsedSubmission(date.toString(), null, date, BigDecimal.valueOf(seconds),
        Map.of("completionStatus", "WIN", "completionTimeSeconds", seconds, "scoreRaw", result.group(2) + ":" + result.group(3),
            "scoreUnit", "seconds"), 1);
  }
  @Override public ParsedSubmission validateConfirmed(ConfirmedSubmission confirmed, String rawText) {
    return ShareTextParserSupport.confirm(parse(rawText), confirmed);
  }
  private ApiException invalid() { return ShareTextParserSupport.invalid("Paste a complete dated New York Times Mini Crossword solve result."); }
}
