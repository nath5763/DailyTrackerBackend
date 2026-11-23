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
class DailyOrbsParser implements GameAdapter {
  private static final Pattern HEADER = Pattern.compile("^DailyOrbs ([A-Z][a-z]+) ([1-9]|[12][0-9]|3[01]) ([0-9]{4})$");
  private static final Pattern URL = Pattern.compile(
      "(?i)^https://dailyorbs\\.com/(\\d{4}-\\d{2}-\\d{2})\\?utm_source=share$");
  private static final Pattern SYMBOL = Pattern.compile("🟠|🟢|🔵|🟣|🧡|💚|💙|💜|🏆");
  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMMM d uuuu", Locale.ENGLISH)
      .withResolverStyle(ResolverStyle.STRICT);
  private static final ZoneId RELEASE_ZONE = ZoneId.of("America/Toronto");
  private static final LinkedHashMap<String, String> ORBS = new LinkedHashMap<>();
  private static final LinkedHashMap<String, String> HEARTS = new LinkedHashMap<>();
  static {
    ORBS.put("🟠", "orange"); ORBS.put("🟢", "green"); ORBS.put("🔵", "blue"); ORBS.put("🟣", "purple");
    HEARTS.put("🧡", "orange"); HEARTS.put("💚", "green"); HEARTS.put("💙", "blue"); HEARTS.put("💜", "purple");
  }

  @Override public String slug() { return "daily-orbs"; }
  @Override public String scoreDirection() { return "HIGHER_IS_BETTER"; }
  @Override public ScoreFormatView scoreFormat() { return new ScoreFormatView("", "/12 orbs", 0, 0, "orbs"); }
  @Override public SubmissionConfigView submissionConfig() {
    return new SubmissionConfigView("DailyOrbs November 23 2026\nhttps://DailyOrbs.com/2026-11-23?utm_source=share\n" +
        "🟠🧡🟠🟢💚🟢🔵🔵🏆🟣", "Puzzle date", "Orbs", BigDecimal.valueOf(6),
        BigDecimal.valueOf(12), BigDecimal.ONE, Set.of());
  }
  @Override public boolean matches(String rawText) {
    String[] lines = ShareText.lines(rawText);
    return lines.length > 0 && HEADER.matcher(lines[0]).matches();
  }
  @Override public ParsedSubmission parse(String rawText) {
    String[] lines = ShareText.lines(rawText);
    if (lines.length != 3) throw invalid();
    Matcher header = HEADER.matcher(lines[0]);
    Matcher url = URL.matcher(lines[1]);
    if (!header.matches() || !url.matches()) throw invalid();

    LocalDate displayed;
    LocalDate playedOn;
    try {
      displayed = LocalDate.parse(header.group(1) + " " + header.group(2) + " " + header.group(3), DATE);
      playedOn = LocalDate.parse(url.group(1));
    } catch (DateTimeException exception) { throw invalid(); }
    if (!displayed.equals(playedOn) || playedOn.isAfter(LocalDate.now(RELEASE_ZONE))) throw invalid();

    Matcher symbols = SYMBOL.matcher(lines[2]);
    Map<String, Integer> orbCounts = zeroColors();
    Map<String, Integer> heartCounts = zeroColors();
    int cursor = 0;
    int orbs = 0;
    int trophies = 0;
    int trophyOrbPosition = -1;
    while (symbols.find()) {
      if (symbols.start() != cursor) throw invalid();
      cursor = symbols.end();
      String symbol = symbols.group();
      if (ORBS.containsKey(symbol)) {
        orbs++;
        increment(orbCounts, ORBS.get(symbol));
      } else if (HEARTS.containsKey(symbol)) {
        increment(heartCounts, HEARTS.get(symbol));
      } else {
        trophies++;
        trophyOrbPosition = orbs;
      }
    }
    if (cursor != lines[2].length() || orbs < 6 || orbs > 12 || trophies != 1 || trophyOrbPosition != 6 ||
        orbCounts.values().stream().anyMatch(value -> value > 3) ||
        heartCounts.values().stream().anyMatch(value -> value > 3)) throw invalid();

    int heartLosses = heartCounts.values().stream().mapToInt(Integer::intValue).sum();
    Map<String, Object> metrics = new LinkedHashMap<>();
    metrics.put("completionStatus", "WIN");
    metrics.put("orbTotal", orbs);
    metrics.put("orangeOrbs", orbCounts.get("orange"));
    metrics.put("greenOrbs", orbCounts.get("green"));
    metrics.put("blueOrbs", orbCounts.get("blue"));
    metrics.put("purpleOrbs", orbCounts.get("purple"));
    metrics.put("eventSequence", lines[2]);
    metrics.put("gameMode", "Daily");
    if (heartLosses > 0) {
      metrics.put("heartsDisclosed", true);
      metrics.put("heartLosses", heartLosses);
      metrics.put("orangeHeartsLost", heartCounts.get("orange"));
      metrics.put("greenHeartsLost", heartCounts.get("green"));
      metrics.put("blueHeartsLost", heartCounts.get("blue"));
      metrics.put("purpleHeartsLost", heartCounts.get("purple"));
    } else metrics.put("heartsDisclosed", false);
    metrics.put("scoreRaw", orbs + "/12 orbs");
    metrics.put("scoreUnit", "orbs");
    metrics.put("displayScore", orbs + "/12 orbs");
    return new ParsedSubmission(playedOn.toString(), null, playedOn, BigDecimal.valueOf(orbs),
        Collections.unmodifiableMap(metrics), 1);
  }
  @Override public ParsedSubmission validateConfirmed(ConfirmedSubmission confirmed, String rawText) {
    return ShareTextParserSupport.confirm(parse(rawText), confirmed);
  }

  private Map<String, Integer> zeroColors() {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (String color : ORBS.values()) counts.put(color, 0);
    return counts;
  }
  private void increment(Map<String, Integer> values, String key) { values.put(key, values.get(key) + 1); }
  private ApiException invalid() {
    return ShareTextParserSupport.invalid("Paste a completed winning Daily Orbs result with its matching dated link.");
  }
}
