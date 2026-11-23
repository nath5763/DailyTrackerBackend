package com.dailygames;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.dailygames.ApiModels.*;

@Component
class FarflungParser implements GameAdapter {
  private static final Pattern HEADER = Pattern.compile("^Farflung #([1-9][0-9]*) · ([0-9]{1,3})/500 km$");
  private static final Pattern SIGNATURE = Pattern.compile("(?m)^Farflung #[1-9][0-9]* ·");
  private static final Pattern SHARE_URL = Pattern.compile("^https://farflung\\.game/s/[a-z0-9]{6}\\?e=([1-9][0-9]*)$");
  private static final LocalDate FIRST_EXPEDITION_DATE = LocalDate.of(2026, 10, 5);
  private static final Map<String, Integer> DISTANCES = Map.of(
      "Miss", 0,
      "Common", 40,
      "Uncommon", 55,
      "Rare", 70,
      "Elusive", 85,
      "Farflung", 100);

  @Override public String slug() { return "farflung"; }
  @Override public String scoreDirection() { return "HIGHER_IS_BETTER"; }
  @Override public ScoreFormatView scoreFormat() { return new ScoreFormatView("", " km", 0, 0, "number"); }
  @Override public SubmissionConfigView submissionConfig() {
    return new SubmissionConfigView(
        "Farflung #50 · 195/500 km\nMiss · Elusive · Rare · Miss · Common\nhttps://farflung.game/s/qtrtpp?e=50",
        "Expedition", "Distance", BigDecimal.ZERO, BigDecimal.valueOf(500), BigDecimal.ONE, Set.of());
  }
  @Override public boolean matches(String rawText) {
    return SIGNATURE.matcher(rawText == null ? "" : rawText).find();
  }
  @Override public ParsedSubmission parse(String rawText) {
    String[] lines = ShareTextParserSupport.lines(rawText);
    if (lines.length != 3) throw invalid();

    Matcher header = HEADER.matcher(lines[0]);
    if (!header.matches()) throw invalid();
    int expedition = ShareTextParserSupport.number(header.group(1));
    int distance = Integer.parseInt(header.group(2));

    String[] rarityTokens = lines[1].split(" · ", -1);
    if (rarityTokens.length != 5) throw invalid();
    List<String> rarities = List.of(rarityTokens);
    if (!rarities.stream().allMatch(DISTANCES::containsKey)) throw invalid();
    int expectedDistance = rarities.stream().mapToInt(DISTANCES::get).sum();
    if (distance != expectedDistance) {
      throw ShareTextParserSupport.invalid("Farflung distance does not match the five rarity results.");
    }

    Matcher shareUrl = SHARE_URL.matcher(lines[2]);
    if (!shareUrl.matches() || Integer.parseInt(shareUrl.group(1)) != expedition) {
      throw ShareTextParserSupport.invalid("Farflung needs its matching official result link.");
    }

    LocalDate playedOn = FIRST_EXPEDITION_DATE.plusDays((long) expedition - 1);
    if (playedOn.isAfter(LocalDate.now(ZoneOffset.UTC).plusDays(1))) {
      throw ShareTextParserSupport.invalid("That Farflung expedition appears to be dated in the future.");
    }

    Map<String, Object> metrics = new LinkedHashMap<>();
    metrics.put("completionStatus", "WIN");
    metrics.put("distanceKm", distance);
    metrics.put("rarities", rarities);
    metrics.put("gameMode", "Daily");
    metrics.put("scoreRaw", distance + "/500 km");
    metrics.put("scoreUnit", "km");
    metrics.put("displayScore", distance + "/500 km");
    return new ParsedSubmission(String.valueOf(expedition), expedition, playedOn, BigDecimal.valueOf(distance),
        metrics, 1);
  }
  @Override public ParsedSubmission validateConfirmed(ConfirmedSubmission confirmed, String rawText) {
    return ShareTextParserSupport.confirm(parse(rawText), confirmed);
  }
  private ApiException invalid() {
    return ShareTextParserSupport.invalid("Paste a complete standard Farflung daily result.");
  }
}
