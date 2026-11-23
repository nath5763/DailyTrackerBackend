package com.dailygames;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.*;
import java.util.regex.*;

import static com.dailygames.ApiModels.*;

@Component
class MinuteCrypticParser implements GameAdapter {
  private static final Pattern HEADER = Pattern.compile("^Minute Cryptic - ([1-9]|[12][0-9]|3[01]) ([A-Z][a-z]+), ([0-9]{4})$");
  private static final Pattern CLUE = Pattern.compile("^(?:\"(.+)\"|“(.+)”) \\(([1-9][0-9]*(?:[ ,-][1-9][0-9]*)*)\\)$");
  private static final Pattern CURRENT = Pattern.compile(
      "^(🏆|🤝|🏋(?:️)?) ([0-9]+) hints – (?:(?:([0-9]+) under)|matched|(?:([0-9]+) over)) the community par \\(([1-9][0-9]{0,2}(?:,[0-9]{3})*) solvers so far\\)\\.(?: Time: ((?:(?:[0-9]+h)(?: [0-9]+m)?(?: [0-9]+s)?|(?:[0-9]+m)(?: [0-9]+s)?|[0-9]+s))\\.)?$");
  private static final Pattern LEGACY = Pattern.compile(
      "^I scored: (?:(?:([0-9]+) under)|even|(?:([0-9]+) over)) par$");
  private static final Pattern URL = Pattern.compile("^https://www\\.minutecryptic\\.com/?(?:\\?utm_source=share)?$");
  private static final Pattern CURRENT_SYMBOL = Pattern.compile("🟣|🟡|⚪(?:️)?");
  private static final Pattern LEGACY_SYMBOL = Pattern.compile("🟪|🟨");
  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.ENGLISH)
      .withResolverStyle(ResolverStyle.STRICT);

  @Override public String slug() { return "minute-cryptic"; }
  @Override public ScoreFormatView scoreFormat() { return new ScoreFormatView("", " hints", 0, 0, "hints"); }
  @Override public SubmissionConfigView submissionConfig() {
    return new SubmissionConfigView("Minute Cryptic - 23 November, 2026\n\"Kitchen cleaners yelled 'You left sugar on the counter!'\" (4)\n" +
        "🟣🟣🟣🟣🟣🟣🟣\n🏆 0 hints – 1 under the community par (101,965 solvers so far). Time: 1m 59s.\n" +
        "https://www.minutecryptic.com/?utm_source=share", "Puzzle date", "Hints", BigDecimal.ZERO,
        null, BigDecimal.ONE, Set.of());
  }
  @Override public boolean matches(String rawText) {
    String[] lines = ShareText.lines(rawText);
    return lines.length > 0 && HEADER.matcher(lines[0]).matches();
  }
  @Override public ParsedSubmission parse(String rawText) {
    String[] lines = ShareText.lines(rawText);
    if (lines.length != 5) throw invalid();
    Matcher header = HEADER.matcher(lines[0]);
    Matcher clue = CLUE.matcher(lines[1]);
    Matcher current = CURRENT.matcher(lines[3]);
    Matcher legacy = LEGACY.matcher(lines[3]);
    if (!header.matches() || !clue.matches() || !URL.matcher(lines[4]).matches()) throw invalid();

    LocalDate playedOn;
    try { playedOn = LocalDate.parse(header.group(1) + " " + header.group(2) + " " + header.group(3), DATE); }
    catch (DateTimeException exception) { throw invalid(); }
    if (playedOn.isAfter(LocalDate.now(ZoneOffset.UTC))) throw invalid();

    boolean currentFormat = current.matches();
    if (!currentFormat && !legacy.matches()) throw invalid();
    SymbolCounts symbols = parseSymbols(lines[2], currentFormat);
    int hints = currentFormat ? parseInt(current.group(2)) : symbols.hints();
    if (hints != symbols.hints()) throw invalid();

    int delta;
    Integer solvers = null;
    Integer elapsedSeconds = null;
    if (currentFormat) {
      delta = delta(current.group(3), current.group(4));
      String expectedIcon = delta < 0 ? "🏆" : delta == 0 ? "🤝" : "🏋";
      if (!current.group(1).replace("️", "").equals(expectedIcon)) throw invalid();
      solvers = parseGrouped(current.group(5));
      if (current.group(6) != null) elapsedSeconds = parseTime(current.group(6));
    } else delta = delta(legacy.group(1), legacy.group(2));

    String clueText = clue.group(1) == null ? clue.group(2) : clue.group(1);
    Map<String, Object> metrics = new LinkedHashMap<>();
    metrics.put("completionStatus", "WIN");
    metrics.put("hints", hints);
    metrics.put("communityParDelta", delta);
    metrics.put("clue", clueText + " (" + clue.group(3) + ")");
    metrics.put("symbolRow", lines[2]);
    metrics.put("shareFormat", currentFormat ? "current" : "legacy");
    if (solvers != null) metrics.put("solverCount", solvers);
    if (elapsedSeconds != null) metrics.put("completionTimeSeconds", elapsedSeconds);
    metrics.put("scoreRaw", hints + " hints");
    metrics.put("scoreUnit", "hints");
    metrics.put("displayScore", hints + " hints");
    return new ParsedSubmission(playedOn.toString(), null, playedOn, BigDecimal.valueOf(hints),
        Collections.unmodifiableMap(metrics), 1);
  }
  @Override public ParsedSubmission validateConfirmed(ConfirmedSubmission confirmed, String rawText) {
    return ShareTextParserSupport.confirm(parse(rawText), confirmed);
  }

  private SymbolCounts parseSymbols(String line, boolean current) {
    Pattern pattern = current ? CURRENT_SYMBOL : LEGACY_SYMBOL;
    Matcher matcher = pattern.matcher(line);
    int cursor = 0;
    int total = 0;
    int hints = 0;
    while (matcher.find()) {
      if (matcher.start() != cursor) throw invalid();
      cursor = matcher.end();
      total++;
      if (!matcher.group().startsWith(current ? "🟣" : "🟪")) hints++;
    }
    if (cursor != line.length() || total < 1 || total > 64) throw invalid();
    return new SymbolCounts(total, hints);
  }
  private int delta(String under, String over) {
    if (under != null) {
      int value = parseInt(under);
      if (value == 0) throw invalid();
      return -value;
    }
    if (over != null) {
      int value = parseInt(over);
      if (value == 0) throw invalid();
      return value;
    }
    return 0;
  }
  private int parseGrouped(String value) {
    try { return Integer.parseInt(value.replace(",", "")); }
    catch (NumberFormatException exception) { throw invalid(); }
  }
  private int parseInt(String value) {
    try { return Integer.parseInt(value); }
    catch (NumberFormatException exception) { throw invalid(); }
  }
  private int parseTime(String value) {
    Matcher matcher = Pattern.compile("^(?:(\\d+)h)?(?: ?(\\d+)m)?(?: ?(\\d+)s)?$").matcher(value);
    if (!matcher.matches()) throw invalid();
    int hours = matcher.group(1) == null ? 0 : parseInt(matcher.group(1));
    int minutes = matcher.group(2) == null ? 0 : parseInt(matcher.group(2));
    int seconds = matcher.group(3) == null ? 0 : parseInt(matcher.group(3));
    if (minutes >= 60 || seconds >= 60 || hours == 0 && minutes == 0 && seconds == 0) throw invalid();
    long total = hours * 3600L + minutes * 60L + seconds;
    if (total > Integer.MAX_VALUE) throw invalid();
    return (int) total;
  }
  private ApiException invalid() {
    return ShareTextParserSupport.invalid("Paste a complete dated Minute Cryptic result with its official link.");
  }
  private record SymbolCounts(int total, int hints) {}
}
