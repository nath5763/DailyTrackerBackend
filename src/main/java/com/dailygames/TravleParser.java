package com.dailygames;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.dailygames.ApiModels.*;

@Component
class TravleParser implements GameAdapter {
  private static final Pattern HEADER = Pattern.compile(
      "^#(travle|travle_challenge) #([1-9][0-9]*) \\+([0-6])" +
          "(?: \\((?:(Perfect)|([0-3]) (hint|hints))\\))?\\s*((?:✅|🟩|🟧|🟥)+)$");
  private static final Pattern SIGNATURE = Pattern.compile("(?m)^#travle(?:_challenge)?\\s+#");
  private static final Pattern DAILY_URL = Pattern.compile("^https://travle\\.earth/?$");
  private static final Pattern WEEKLY_URL = Pattern.compile("^https://travle\\.earth/challenge/?$");
  private static final LocalDate DAILY_ANCHOR_DATE = LocalDate.of(2026, 11, 22);
  private static final int DAILY_ANCHOR_PUZZLE = 1439;
  private static final LocalDate WEEKLY_ANCHOR_DATE = LocalDate.of(2024, 10, 21);
  private static final int WEEKLY_ANCHOR_PUZZLE = 38;

  @Override public String slug() { return "travle"; }
  @Override public ScoreFormatView scoreFormat() { return new ScoreFormatView("+", "", 0, 0, "extra guesses"); }
  @Override public SubmissionConfigView submissionConfig() {
    return new SubmissionConfigView("#travle #1439 +2 (1 hint)\n✅🟧🟩✅\nhttps://travle.earth",
        "Puzzle", "Extra guesses", BigDecimal.ZERO, BigDecimal.valueOf(6), BigDecimal.ONE, Set.of());
  }
  @Override public boolean matches(String rawText) {
    return SIGNATURE.matcher(rawText == null ? "" : rawText).find();
  }
  @Override public ParsedSubmission parse(String rawText) {
    String[] lines = ShareTextParserSupport.lines(rawText);
    if (lines.length < 2 || lines.length > 3) throw invalid();
    Matcher header = HEADER.matcher(String.join(" ", java.util.Arrays.copyOf(lines, lines.length - 1)));
    if (!header.matches()) throw invalid();

    String gameMode = header.group(1).equals("travle") ? "Daily" : "Weekly";
    Pattern expectedUrl = gameMode.equals("Daily") ? DAILY_URL : WEEKLY_URL;
    if (!expectedUrl.matcher(lines[lines.length - 1]).matches())
      throw ShareTextParserSupport.invalid("Travle " + gameMode + " results need their matching official link.");

    int puzzle = ShareTextParserSupport.number(header.group(2));
    int extraGuesses = Integer.parseInt(header.group(3));
    boolean perfect = header.group(4) != null;
    int hints = header.group(5) == null ? 0 : Integer.parseInt(header.group(5));
    String hintWord = header.group(6);
    if (perfect && extraGuesses != 0)
      throw ShareTextParserSupport.invalid("Only a +0 Travle result can be Perfect.");
    if (hintWord != null && (hints == 1) != hintWord.equals("hint"))
      throw ShareTextParserSupport.invalid("Travle hint text must use the correct singular or plural form.");

    LocalDate playedOn = gameMode.equals("Daily")
        ? DAILY_ANCHOR_DATE.plusDays((long) puzzle - DAILY_ANCHOR_PUZZLE)
        : WEEKLY_ANCHOR_DATE.plusWeeks((long) puzzle - WEEKLY_ANCHOR_PUZZLE);
    if (playedOn.isAfter(LocalDate.now(ZoneOffset.UTC)))
      throw ShareTextParserSupport.invalid("Travle puzzle " + puzzle + " appears to be dated in the future.");

    Map<String, Object> metrics = new LinkedHashMap<>();
    metrics.put("completionStatus", "WIN");
    metrics.put("extraGuesses", extraGuesses);
    metrics.put("hints", hints);
    metrics.put("assistanceStatus", hints > 0 ? "assisted" : "unassisted");
    metrics.put("resultSequence", header.group(7));
    metrics.put("gameMode", gameMode);
    metrics.put("scoreRaw", "+" + extraGuesses);
    metrics.put("scoreUnit", "extra guesses");
    metrics.put("displayScore", "+" + extraGuesses);
    return new ParsedSubmission(gameMode + ":" + puzzle, puzzle, playedOn, BigDecimal.valueOf(extraGuesses),
        metrics, 1);
  }
  @Override public ParsedSubmission validateConfirmed(ConfirmedSubmission confirmed, String rawText) {
    return ShareTextParserSupport.confirm(parse(rawText), confirmed);
  }
  private ApiException invalid() {
    return ShareTextParserSupport.invalid("Paste a complete English Travle Daily or Weekly Challenge result.");
  }
}
