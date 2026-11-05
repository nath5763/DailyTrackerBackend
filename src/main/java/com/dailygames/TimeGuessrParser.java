package com.dailygames;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;

import static com.dailygames.ApiModels.*;

@Component
class TimeGuessrParser implements GameAdapter {
  private static final Pattern HEADER = Pattern.compile("^TimeGuessr #([1-9][0-9]*) [—-] ([0-9][0-9,\\u202F ]*)/50[,\\u202F ]?000$");
  private static final Pattern ROUND = Pattern.compile("^[1-5]\\u20E3 🏆([0-9]{1,5})(?: .*)?$");
  private static final LocalDate ANCHOR_DATE = LocalDate.of(2025, 8, 17);
  private static final int ANCHOR_PUZZLE = 807;
  @Override public String slug() { return "timeguessr"; }
  @Override public String scoreDirection() { return "HIGHER_IS_BETTER"; }
  @Override public ScoreFormatView scoreFormat() { return new ScoreFormatView("", " pts", 0, 0, "number"); }
  @Override public SubmissionConfigView submissionConfig() {
    return new SubmissionConfigView("TimeGuessr #807 — 40,693/50,000\n1️⃣ 🏆8734 - 📅5y - 🌍16.5 km\n2️⃣ 🏆6874 - 📅13y - 🌍6.5 km\n3️⃣ 🏆9058 - 📅4y - 🌍35.5 km\n4️⃣ 🏆9794 - 📅2y - 🌍284.7 m\n5️⃣ 🏆6233 - 📅15y - 🌍267.5 km",
        "Daily number", "Points", BigDecimal.ZERO, BigDecimal.valueOf(50_000), BigDecimal.ONE, Set.of());
  }
  @Override public ParsedSubmission parse(String rawText) {
    String[] lines = ShareText.lines(rawText);
    if (lines.length < 1) throw invalid();
    Matcher header = HEADER.matcher(lines[0]);
    if (!header.matches()) throw invalid();
    int puzzle = ShareTextParserSupport.number(header.group(1));
    int score = number(header.group(2));
    if (score < 0 || score > 50_000) throw invalid();
    List<Integer> rounds = new ArrayList<>();
    for (int index = 1; index < lines.length; index++) {
      Matcher round = ROUND.matcher(lines[index]);
      if (!round.matches()) throw invalid();
      rounds.add(Integer.parseInt(round.group(1)));
    }
    if (!rounds.isEmpty() && (rounds.size() != 5 || rounds.stream().mapToInt(Integer::intValue).sum() != score))
      throw invalid();
    LocalDate date = ANCHOR_DATE.plusDays(puzzle - ANCHOR_PUZZLE);
    if (date.isAfter(LocalDate.now().plusDays(1))) throw invalid();
    Map<String, Object> metrics = new LinkedHashMap<>();
    metrics.put("completionStatus", "WIN"); metrics.put("nativeScore", score);
    metrics.put("rankingPrimary", score); metrics.put("rankingPrimaryDirection", "DESCENDING");
    metrics.put("gameMode", "Daily"); metrics.put("scoreUnit", "points");
    metrics.put("scoreRaw", score + "/50,000");
    metrics.put("displayScore", String.format(Locale.US, "%,d pts", score));
    if (!rounds.isEmpty()) metrics.put("roundScores", List.copyOf(rounds));
    return new ParsedSubmission(String.valueOf(puzzle), puzzle, date, BigDecimal.valueOf(score), metrics, 1);
  }
  @Override public ParsedSubmission validateConfirmed(ConfirmedSubmission confirmed, String rawText) {
    return ShareTextParserSupport.confirm(parse(rawText), confirmed);
  }
  private int number(String value) {
    try { return Integer.parseInt(value.replace(",", "").replace("\u202F", "").replace(" ", "")); }
    catch (NumberFormatException exception) { throw invalid(); }
  }
  private ApiException invalid() {
    return ShareTextParserSupport.invalid("Paste a complete TimeGuessr daily score result.");
  }
}
