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
class ContextoParser implements GameAdapter {
  private static final Pattern HEADER = Pattern.compile("^I played contexto\\.me ([0-9]{1,2}/[0-9]{1,2}/[0-9]{4}) and got it in ([1-9][0-9]*) guesses\\.$", Pattern.CASE_INSENSITIVE);
  private static final Pattern COUNT = Pattern.compile("(?:.* )?([0-9]+)$");
  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("M/d/uuuu");
  @Override public String slug() { return "contexto"; }
  @Override public ScoreFormatView scoreFormat() { return new ScoreFormatView("", " guesses", 0, 0, "guesses"); }
  @Override public SubmissionConfigView submissionConfig() {
    return new SubmissionConfigView("I played contexto.me 10/26/2026 and got it in 5 guesses.\n🟩🟩🟩🟩 4\n0\n🟥 1",
        "Puzzle date", "Guesses", BigDecimal.ONE, null, BigDecimal.ONE, Set.of());
  }
  @Override public ParsedSubmission parse(String rawText) {
    String[] lines = ShareText.lines(rawText);
    if (lines.length < 4) throw invalid();
    Matcher header = HEADER.matcher(lines[0]);
    if (!header.matches()) throw invalid();
    LocalDate date;
    try { date = LocalDate.parse(header.group(1), DATE); }
    catch (DateTimeParseException exception) { throw invalid(); }
    if (date.isAfter(LocalDate.now().plusDays(1))) throw invalid();
    int guesses = Integer.parseInt(header.group(2));
    if (!lines[1].contains("🟩") || !(lines[2].equals("0") || lines[2].contains("🟨")) || !(lines[3].equals("0") || lines[3].contains("🟥"))) throw invalid();
    int green = count(lines[1]), yellow = count(lines[2]), red = count(lines[3]);
    if (green + yellow + red != guesses) throw invalid();
    return new ParsedSubmission(date.toString(), null, date, BigDecimal.valueOf(guesses),
        Map.of("completionStatus", "WIN", "attemptsUsed", guesses, "scoreRaw", guesses + " guesses", "scoreUnit", "guesses",
            "greenGuesses", green, "yellowGuesses", yellow, "redGuesses", red), 1);
  }
  @Override public ParsedSubmission validateConfirmed(ConfirmedSubmission confirmed, String rawText) {
    return ShareTextParserSupport.confirm(parse(rawText), confirmed);
  }
  private int count(String line) {
    Matcher match = COUNT.matcher(line);
    if (!match.matches()) throw invalid();
    return Integer.parseInt(match.group(1));
  }
  private ApiException invalid() { return ShareTextParserSupport.invalid("Paste a complete explicitly dated Contexto share result."); }
}
