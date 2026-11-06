package com.dailygames;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;

import static com.dailygames.ApiModels.*;

@Component
class KrillionParser implements GameAdapter {
  static final int VERSION = 1;
  private static final LocalDate FIRST_PUZZLE = LocalDate.of(2026, 7, 16);
  private static final Pattern HEADER = Pattern.compile("^Krillion\\s+#\\s*(\\d+)\\s*🦐$");
  private static final Pattern SCORE = Pattern.compile("^(\\d{1,3})$");
  private static final Pattern LINK = Pattern.compile("^https://krillion\\.io/?$");
  private static final Pattern SYMBOL = Pattern.compile("⬛|🫧|🤡|🐟|🦑|🏮|🌟");
  private static final Map<String, Tier> TIERS = Map.of(
      "⬛", new Tier("miss", 0),
      "🫧", new Tier("plankton", 10),
      "🤡", new Tier("too-clever", 15),
      "🐟", new Tier("schooler", 30),
      "🦑", new Tier("rare", 60),
      "🏮", new Tier("deep-cut", 85),
      "🌟", new Tier("krillion", 100));

  @Override public String slug() { return "krillion"; }
  @Override public String scoreDirection() { return "HIGHER_IS_BETTER"; }
  @Override public ScoreFormatView scoreFormat() { return new ScoreFormatView("", " pts", 0, 2); }
  @Override public SubmissionConfigView submissionConfig() {
    return new SubmissionConfigView("Krillion #82 🦐\n180\n\n🐟🫧🫧🐟🦑🫧🐟", "Dive number", "Final score",
        BigDecimal.ZERO, BigDecimal.valueOf(700), BigDecimal.ONE, Set.of());
  }

  @Override public ParsedSubmission parse(String rawText) {
    String text = rawText == null ? "" : rawText.trim();
    if (text.isEmpty()) throw invalid("Paste your Krillion result first.");
    if (text.length() > 10_000) throw invalid("Result text is too long.");

    List<String> lines = text.lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
    if (lines.size() < 3 || lines.size() > 4 || lines.size() == 4 && !LINK.matcher(lines.get(3)).matches()) {
      throw invalid("Paste a complete Krillion daily result.");
    }
    Matcher header = HEADER.matcher(lines.get(0));
    if (!header.matches()) throw invalid("Only numbered Krillion daily results are supported.");
    int puzzle;
    try { puzzle = Integer.parseInt(header.group(1)); }
    catch (NumberFormatException exception) { throw invalid("Could not find a valid Krillion dive number."); }
    if (puzzle < 1 || puzzle > 100_000) throw invalid("Could not find a valid Krillion dive number.");

    Matcher scoreMatch = SCORE.matcher(lines.get(1));
    if (!scoreMatch.matches()) throw invalid("Could not find a valid Krillion score.");
    int score = Integer.parseInt(scoreMatch.group(1));
    if (score > 700) throw invalid("Krillion scores must be between 0 and 700 points.");

    String symbols = lines.get(2).replace("\uFE0F", "");
    Matcher symbolMatch = SYMBOL.matcher(symbols);
    List<String> tierIds = new ArrayList<>();
    int calculated = 0;
    while (symbolMatch.find()) {
      Tier tier = TIERS.get(symbolMatch.group());
      tierIds.add(tier.id());
      calculated += tier.score();
    }
    String remainder = SYMBOL.matcher(symbols).replaceAll("").replaceAll("\\s", "");
    if (!remainder.isEmpty() || tierIds.size() != 7) throw invalid("Krillion results must contain exactly seven result symbols.");
    if (calculated != score) throw invalid("The Krillion score does not match its seven result symbols.");

    return new ParsedSubmission(String.valueOf(puzzle), puzzle, FIRST_PUZZLE.plusDays(puzzle - 1L),
        BigDecimal.valueOf(score), Map.of("completionStatus", "WIN", "tiers", List.copyOf(tierIds)), VERSION);
  }

  @Override public ParsedSubmission validateConfirmed(ConfirmedSubmission confirmed, String rawText) {
    ParsedSubmission parsed = parse(rawText);
    if (confirmed == null || !Objects.equals(parsed.puzzleKey(), confirmed.puzzleKey()) ||
        !Objects.equals(parsed.playedOn(), confirmed.playedOn()) ||
        confirmed.primaryScore() == null || parsed.primaryScore().compareTo(confirmed.primaryScore()) != 0) {
      throw new ApiException(422, "INVALID_SUBMISSION", "Krillion puzzle, date, and score must match the shared result.");
    }
    return parsed;
  }

  private ApiException invalid(String message) { return new ApiException(422, "INVALID_SHARE_TEXT", message); }
  private record Tier(String id, int score) {}
}
