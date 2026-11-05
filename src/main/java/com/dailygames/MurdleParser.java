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
class MurdleParser implements GameAdapter {
  private static final Pattern DATE = Pattern.compile("(?i)\\b(?:I solved )?Murdle for ([0-9]{1,2}/[0-9]{1,2}/[0-9]{4})\\b");
  private static final Pattern TIME = Pattern.compile("([0-9][\\uFE0F]?\\u20E3){1,2}:([0-9][\\uFE0F]?\\u20E3){2}");
  private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("M/d/uuuu");
  @Override public String slug() { return "murdle"; }
  @Override public ScoreFormatView scoreFormat() { return new ScoreFormatView("", "", 0, 0, "duration"); }
  @Override public SubmissionConfigView submissionConfig() {
    return new SubmissionConfigView("I solved Murdle for 6/25/2026 👤🔪🏡 🕰️ ✅✅✅ 2️⃣8️⃣:2️⃣9️⃣ murdle.com",
        "Puzzle date", "Time", BigDecimal.ZERO, null, BigDecimal.ONE, Set.of());
  }
  @Override public boolean matches(String rawText) { return DATE.matcher(rawText == null ? "" : rawText).find(); }
  @Override public ParsedSubmission parse(String rawText) {
    if (rawText == null || rawText.length() > 10_000) throw invalid();
    Matcher dateMatch = DATE.matcher(rawText);
    Matcher timeMatch = TIME.matcher(rawText);
    if (!dateMatch.find() || !timeMatch.find() || !rawText.contains("🕰") || !rawText.matches("(?s).*✅{3,4}.*")) throw invalid();
    LocalDate date;
    try { date = LocalDate.parse(dateMatch.group(1), DATE_FORMAT); }
    catch (DateTimeParseException exception) { throw invalid(); }
    if (date.isAfter(LocalDate.now().plusDays(1))) throw invalid();
    String keycapTime = timeMatch.group();
    String plain = keycapTime.replace("\uFE0F", "").replace("\u20E3", "");
    String[] parts = plain.split(":");
    int minutes = Integer.parseInt(parts[0]), seconds = Integer.parseInt(parts[1]);
    if (seconds > 59) throw invalid();
    int duration = minutes * 60 + seconds;
    return new ParsedSubmission(date.toString(), null, date, BigDecimal.valueOf(duration),
        Map.of("completionStatus", "WIN", "completionTimeSeconds", duration, "scoreRaw", keycapTime,
            "scoreUnit", "seconds"), 1);
  }
  @Override public ParsedSubmission validateConfirmed(ConfirmedSubmission confirmed, String rawText) {
    return ShareTextParserSupport.confirm(parse(rawText), confirmed);
  }
  private ApiException invalid() { return ShareTextParserSupport.invalid("Paste a complete dated Murdle solve result with a time."); }
}
