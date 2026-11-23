package com.dailygames;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.*;
import java.util.regex.*;

import static com.dailygames.ApiModels.*;

@Component
class DailySetParser implements GameAdapter {
  private static final Pattern HEADER = Pattern.compile("^The Daily Set ([🎯🔥]) ([A-Z][a-z]{2}) ([1-9]|[12][0-9]|3[01])$");
  private static final Pattern URL = Pattern.compile(
      "^https://setpuzzle\\.com/share/(\\d{4}-\\d{2}-\\d{2})/((?:0|[1-9][0-9]*)-([0-5][0-9]))/(regular|diablo)$");
  private static final Pattern SPLITS = Pattern.compile(
      "^(.+) → ⏱ ((?:0|[1-9][0-9]*):[0-5][0-9])$");
  private static final Pattern SPLIT = Pattern.compile("^(🟩|🟨|🟥)(\\d+)s$");
  private static final DateTimeFormatter HEADER_DATE = DateTimeFormatter.ofPattern("MMM d uuuu", Locale.ENGLISH)
      .withResolverStyle(ResolverStyle.STRICT);
  private static final ZoneId RELEASE_ZONE = ZoneId.of("America/Los_Angeles");

  @Override public String slug() { return "daily-set"; }
  @Override public ScoreFormatView scoreFormat() { return new ScoreFormatView("", "", 0, 0, "duration"); }
  @Override public SubmissionConfigView submissionConfig() {
    return new SubmissionConfigView("The Daily Set 🎯 Nov 23\n\n🟩8s 🟨12s 🟨14s 🟩7s 🟥31s 🟨11s → ⏱️ 1:23\n\n" +
        "https://setpuzzle.com/share/2026-11-23/1-23/regular", "Puzzle date", "Time",
        BigDecimal.ONE, null, BigDecimal.ONE, Set.of());
  }
  @Override public boolean matches(String rawText) {
    String[] lines = ShareText.lines(rawText);
    return lines.length > 0 && HEADER.matcher(lines[0]).matches();
  }
  @Override public ParsedSubmission parse(String rawText) {
    String[] lines = ShareText.lines(rawText);
    if (lines.length != 2 && lines.length != 3) throw invalid();
    Matcher header = HEADER.matcher(lines[0]);
    Matcher url = URL.matcher(lines[lines.length - 1]);
    if (!header.matches() || !url.matches()) throw invalid();

    LocalDate playedOn;
    try { playedOn = LocalDate.parse(url.group(1)); }
    catch (DateTimeException exception) { throw invalid(); }
    LocalDate displayed;
    try { displayed = LocalDate.parse(header.group(2) + " " + header.group(3) + " " + playedOn.getYear(), HEADER_DATE); }
    catch (DateTimeException exception) { throw invalid(); }
    if (!displayed.equals(playedOn) || playedOn.isAfter(LocalDate.now(RELEASE_ZONE))) throw invalid();

    String modeToken = url.group(4);
    String expectedIcon = modeToken.equals("diablo") ? "🔥" : "🎯";
    if (!header.group(1).equals(expectedIcon)) throw invalid();
    long minutes;
    try { minutes = Long.parseLong(url.group(2).substring(0, url.group(2).indexOf('-'))); }
    catch (NumberFormatException exception) { throw invalid(); }
    long elapsed = minutes * 60L + Integer.parseInt(url.group(3));
    if (elapsed < 1 || elapsed > Integer.MAX_VALUE) throw invalid();
    String display = formatDuration((int) elapsed);

    Map<String, Object> metrics = new LinkedHashMap<>();
    metrics.put("completionStatus", "WIN");
    metrics.put("elapsedSeconds", (int) elapsed);
    metrics.put("gameMode", modeToken.equals("diablo") ? "Diablo" : "Regular");
    metrics.put("scoreRaw", display);
    metrics.put("scoreUnit", "seconds");
    metrics.put("displayScore", display);
    if (lines.length == 3) metrics.put("setTimesSeconds", parseSplits(lines[1], (int) elapsed, display));
    return new ParsedSubmission(playedOn + ":" + modeToken, null, playedOn, BigDecimal.valueOf(elapsed),
        Collections.unmodifiableMap(metrics), 1);
  }
  @Override public ParsedSubmission validateConfirmed(ConfirmedSubmission confirmed, String rawText) {
    return ShareTextParserSupport.confirm(parse(rawText), confirmed);
  }

  private List<Integer> parseSplits(String line, int elapsed, String display) {
    Matcher match = SPLITS.matcher(line);
    if (!match.matches() || !match.group(2).equals(display)) throw invalid();
    String[] tokens = match.group(1).split(" ", -1);
    if (tokens.length != 6) throw invalid();
    List<Integer> values = new ArrayList<>();
    int sum = 0;
    for (String token : tokens) {
      Matcher split = SPLIT.matcher(token);
      if (!split.matches()) throw invalid();
      int value;
      try { value = Integer.parseInt(split.group(2)); }
      catch (NumberFormatException exception) { throw invalid(); }
      if (!validBand(split.group(1), value)) throw invalid();
      values.add(value);
      sum += value;
    }
    if (Math.abs(sum - elapsed) > 3) throw invalid();
    return List.copyOf(values);
  }

  private boolean validBand(String band, int seconds) {
    return switch (band) {
      case "🟩" -> seconds < 10;
      case "🟨" -> seconds >= 10 && seconds <= 30;
      case "🟥" -> seconds > 30;
      default -> false;
    };
  }

  private String formatDuration(int seconds) {
    return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
  }
  private ApiException invalid() {
    return ShareTextParserSupport.invalid("Paste a complete Daily Set Regular or Diablo result with its matching official link.");
  }
}
