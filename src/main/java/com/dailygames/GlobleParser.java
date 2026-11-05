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
class GlobleParser implements GameAdapter {
  private static final Pattern HEADER = Pattern.compile("^🌎 ([A-Z][a-z]{2}) ([0-9]{1,2}), ([0-9]{4}) 🌍$");
  private static final Pattern SCORE = Pattern.compile("^[🟨🟧🟥⬜]*🟩 = ([1-9][0-9]*)$");
  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.ENGLISH);
  @Override public String slug() { return "globle"; }
  @Override public ScoreFormatView scoreFormat() { return new ScoreFormatView("", " guesses", 0, 0, "guesses"); }
  @Override public SubmissionConfigView submissionConfig() {
    return new SubmissionConfigView("🌎 Aug 21, 2026 🌍\n🔥 5 | Avg. Guesses: 7.4\n🟨🟥🟥🟥🟥🟥🟩 = 7\n#globle",
        "Puzzle date", "Guesses", BigDecimal.ONE, null, BigDecimal.ONE, Set.of());
  }
  @Override public boolean matches(String rawText) {
    String[] lines = ShareText.lines(rawText);
    return lines.length > 0 && HEADER.matcher(lines[0]).matches() && Arrays.stream(lines).anyMatch(line -> line.equalsIgnoreCase("#globle"));
  }
  @Override public ParsedSubmission parse(String rawText) {
    String[] lines = ShareText.lines(rawText);
    if (lines.length < 3 || !Arrays.stream(lines).anyMatch(line -> line.equalsIgnoreCase("#globle"))) throw invalid();
    Matcher header = HEADER.matcher(lines[0]);
    if (!header.matches()) throw invalid();
    LocalDate date;
    try { date = LocalDate.parse(header.group(1) + " " + header.group(2) + ", " + header.group(3), DATE); }
    catch (DateTimeParseException exception) { throw invalid(); }
    if (date.isAfter(LocalDate.now().plusDays(1))) throw invalid();
    Matcher score = Arrays.stream(lines).map(SCORE::matcher).filter(Matcher::matches).findFirst().orElseThrow(this::invalid);
    int guesses = Integer.parseInt(score.group(1));
    long cells = score.group().codePoints().filter(value -> value == 0x1F7E8 || value == 0x1F7E7 || value == 0x1F7E5 || value == 0x2B1C || value == 0x1F7E9).count();
    if (cells != guesses) throw invalid();
    return new ParsedSubmission(date.toString(), null, date, BigDecimal.valueOf(guesses),
        Map.of("completionStatus", "WIN", "attemptsUsed", guesses, "scoreRaw", score.group(),
            "scoreUnit", "guesses", "proximityGrid", score.group().substring(0, score.group().indexOf(" ="))), 1);
  }
  @Override public ParsedSubmission validateConfirmed(ConfirmedSubmission confirmed, String rawText) {
    return ShareTextParserSupport.confirm(parse(rawText), confirmed);
  }
  private ApiException invalid() { return ShareTextParserSupport.invalid("Paste a complete dated Globle share result ending in #globle."); }
}
