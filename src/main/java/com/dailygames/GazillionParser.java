package com.dailygames;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.dailygames.ApiModels.*;

@Component
class GazillionParser implements GameAdapter {
  private static final Pattern HEADER = Pattern.compile(
      "^Gazillion #([1-9][0-9]*) · ((?:0|[1-9][0-9]{0,2}(?:,[0-9]{3})?))/5,000(?: · 🔥([1-9][0-9]*))?$");
  private static final Pattern SIGNATURE = Pattern.compile("(?m)^Gazillion #[1-9][0-9]* ·");
  private static final Pattern RESULT_LINE = Pattern.compile(
      "^([🏆🥇🥈🥉✅🤏❌]{5})(?:  top ([1-9][0-9]?)%)?$");
  private static final Pattern TRAVEL_LINE = Pattern.compile(
      "^([🏙🏰🐪🔥🦣🦖🌋💥])\\uFE0F? Travelled back to (.+)$");
  private static final Pattern CHALLENGE_LINE = Pattern.compile(
      "^Can you beat ((?:0|[1-9][0-9]{0,2}(?:,[0-9]{3})?))\\?\\s+(?:👉\\s+)?(https://gazillion\\.world/?(?:\\?[^\\s#]+)?)$");
  private static final String PERFECT_BANNER = "💎 PERFECT 5,000 · ONE IN A GAZILLION 💎";
  private static final LocalDate FIRST_PUZZLE_DATE = LocalDate.of(2026, 10, 23);
  private static final Set<String> SHARE_SOURCES = Set.of("cp", "sh", "wa", "x", "tg", "sms", "bs");
  private static final Map<String, Tier> TIERS = Map.of(
      "🏆", new Tier("perfect", 950, 1000),
      "🥇", new Tier("gold", 800, 949),
      "🥈", new Tier("silver", 600, 799),
      "🥉", new Tier("bronze", 400, 599),
      "✅", new Tier("caught", 1, 399),
      "🤏", new Tier("close", 1, 399),
      "❌", new Tier("miss", 0, 0));
  private static final int[] ERA_MINIMUMS = {0, 700, 1400, 2100, 2800, 3500, 4200, 4800};
  private static final String[] ERA_EMOJIS = {"🏙", "🏰", "🐪", "🔥", "🦣", "🦖", "🌋", "💥"};

  @Override public String slug() { return "gazillion"; }
  @Override public String scoreDirection() { return "HIGHER_IS_BETTER"; }
  @Override public ScoreFormatView scoreFormat() { return new ScoreFormatView("", " pts", 0, 0, "number"); }
  @Override public SubmissionConfigView submissionConfig() {
    return new SubmissionConfigView(
        "Gazillion #32 · 2,037/5,000\n🥇🥉✅🤏❌  top 45%\n🐪 Travelled back to the Ancient Egypt (2,792 BC)\n" +
            "Can you beat 2,037? 👉 https://gazillion.world/?s=cp&r=spd7ywd9sp&c=2037&d=32",
        "Daily number", "Points", BigDecimal.ZERO, BigDecimal.valueOf(5_000), BigDecimal.ONE, Set.of());
  }
  @Override public boolean matches(String rawText) {
    return SIGNATURE.matcher(rawText == null ? "" : rawText).find();
  }
  @Override public ParsedSubmission parse(String rawText) {
    String[] lines = ShareTextParserSupport.lines(rawText);
    boolean perfectBanner = lines.length == 5 && PERFECT_BANNER.equals(lines[0]);
    if (lines.length != 4 && !perfectBanner) throw invalid();
    int offset = perfectBanner ? 1 : 0;

    Matcher header = HEADER.matcher(lines[offset]);
    Matcher results = RESULT_LINE.matcher(lines[offset + 1]);
    Matcher travel = TRAVEL_LINE.matcher(lines[offset + 2]);
    Matcher challenge = CHALLENGE_LINE.matcher(lines[offset + 3]);
    if (!header.matches() || !results.matches() || !travel.matches() || !challenge.matches()) throw invalid();

    int puzzle = ShareTextParserSupport.number(header.group(1));
    int score = points(header.group(2));
    if (score > 5_000 || points(challenge.group(1)) != score) {
      throw ShareTextParserSupport.invalid("Gazillion score details do not match.");
    }
    if (perfectBanner != (score == 5_000)) {
      throw ShareTextParserSupport.invalid("Gazillion's perfect-score banner does not match the score.");
    }

    Integer streak = header.group(3) == null ? null : ShareTextParserSupport.number(header.group(3));
    if (streak != null && streak < 2) throw invalid();
    Integer percentile = results.group(2) == null ? null : Integer.parseInt(results.group(2));
    if (percentile != null && percentile > 50) throw invalid();

    List<String> resultTiers = new ArrayList<>();
    int minimum = 0;
    int maximum = 0;
    for (int index = 0; index < results.group(1).length();) {
      int codePoint = results.group(1).codePointAt(index);
      String emoji = new String(Character.toChars(codePoint));
      Tier tier = TIERS.get(emoji);
      if (tier == null) throw invalid();
      resultTiers.add(tier.id());
      minimum += tier.minimum();
      maximum += tier.maximum();
      index += Character.charCount(codePoint);
    }
    if (resultTiers.size() != 5 || score < minimum || score > maximum) {
      throw ShareTextParserSupport.invalid("Gazillion score is not possible for the five result tiers.");
    }
    if (!expectedEraEmoji(score).equals(travel.group(1))) {
      throw ShareTextParserSupport.invalid("Gazillion's travelled-back era does not match the score.");
    }

    validateUrl(challenge.group(2), score, puzzle);
    LocalDate playedOn = FIRST_PUZZLE_DATE.plusDays((long) puzzle - 1);
    if (playedOn.isAfter(LocalDate.now(ZoneOffset.UTC).plusDays(1))) {
      throw ShareTextParserSupport.invalid("That Gazillion daily appears to be dated in the future.");
    }

    Map<String, Object> metrics = new LinkedHashMap<>();
    metrics.put("completionStatus", "WIN");
    metrics.put("totalScore", score);
    metrics.put("maximumScore", 5_000);
    metrics.put("resultTiers", List.copyOf(resultTiers));
    if (percentile != null) metrics.put("percentile", percentile);
    if (streak != null) metrics.put("streak", streak);
    metrics.put("travelledBackTo", travel.group(2));
    metrics.put("gameMode", "Daily");
    metrics.put("scoreRaw", format(score) + "/5,000");
    metrics.put("scoreUnit", "points");
    metrics.put("displayScore", format(score) + "/5,000");
    return new ParsedSubmission(String.valueOf(puzzle), puzzle, playedOn, BigDecimal.valueOf(score), metrics, 1);
  }
  @Override public ParsedSubmission validateConfirmed(ConfirmedSubmission confirmed, String rawText) {
    return ShareTextParserSupport.confirm(parse(rawText), confirmed);
  }

  private void validateUrl(String rawUrl, int score, int puzzle) {
    int queryStart = rawUrl.indexOf('?');
    if (queryStart < 0) return;
    Map<String, String> parameters = new LinkedHashMap<>();
    for (String token : rawUrl.substring(queryStart + 1).split("&", -1)) {
      String[] pair = token.split("=", 2);
      if (pair.length != 2 || pair[0].isBlank() || pair[1].isBlank() || parameters.put(pair[0], pair[1]) != null) {
        throw ShareTextParserSupport.invalid("Gazillion needs a valid official result link.");
      }
    }
    if (!parameters.keySet().stream().allMatch(Set.of("s", "r", "c", "d", "by")::contains) ||
        !SHARE_SOURCES.contains(parameters.get("s")) || !parameters.containsKey("c") || !parameters.containsKey("d") ||
        !parameters.get("c").equals(String.valueOf(score)) || !parameters.get("d").equals(String.valueOf(puzzle)) ||
        parameters.containsKey("r") && !parameters.get("r").matches("[a-z0-9]{8,12}")) {
      throw ShareTextParserSupport.invalid("Gazillion needs its matching official result link.");
    }
  }

  private int points(String value) {
    try { return Integer.parseInt(value.replace(",", "")); }
    catch (NumberFormatException exception) { throw invalid(); }
  }
  private String expectedEraEmoji(int score) {
    String result = ERA_EMOJIS[0];
    for (int index = 1; index < ERA_MINIMUMS.length; index++) {
      if (score >= ERA_MINIMUMS[index]) result = ERA_EMOJIS[index];
    }
    return result;
  }
  private String format(int score) { return String.format(Locale.US, "%,d", score); }
  private ApiException invalid() {
    return ShareTextParserSupport.invalid("Paste a complete standard English Gazillion daily result.");
  }
  private record Tier(String id, int minimum, int maximum) {}
}
