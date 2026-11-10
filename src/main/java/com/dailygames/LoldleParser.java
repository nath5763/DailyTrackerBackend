package com.dailygames;

import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static com.dailygames.ApiModels.*;

/** Parses only a complete five-mode LoLdle share. The share itself has no date. */
final class LoldleParser {
  static final List<String> MODES = List.of("classic", "quote", "ability", "emoji", "splash");
  private static final String EXAMPLE = "I've completed all the modes of #LoLdle today:\n"
      + "❓ Classic: 8\n💬 Quote: 1\n🔥 Ability: 1 ✓\n😀 Emoji: 1\n🎨 Splash: 2 ✓\nhttps://loldle.net";
  private static final Pattern HEADER = Pattern.compile("(?i)^I've completed all the modes of #LoLdle today:$");
  private static final Pattern[] RESULTS = {
      Pattern.compile("^❓\\s*Classic:\\s*([1-9][0-9]{0,2})(\\s*✓)?$", Pattern.CASE_INSENSITIVE),
      Pattern.compile("^💬\\s*Quote:\\s*([1-9][0-9]{0,2})(\\s*✓)?$", Pattern.CASE_INSENSITIVE),
      Pattern.compile("^🔥\\s*Ability:\\s*([1-9][0-9]{0,2})(\\s*✓)?$", Pattern.CASE_INSENSITIVE),
      Pattern.compile("^😀\\s*Emoji:\\s*([1-9][0-9]{0,2})(\\s*✓)?$", Pattern.CASE_INSENSITIVE),
      Pattern.compile("^🎨\\s*Splash:\\s*([1-9][0-9]{0,2})(\\s*✓)?$", Pattern.CASE_INSENSITIVE)
  };
  private LoldleParser() {}
  static String example() { return EXAMPLE; }

  static Map<String, ParsedSubmission> parse(String rawText, LocalDate puzzleDate) {
    if (puzzleDate == null || puzzleDate.isAfter(LocalDate.now(ZoneOffset.UTC)))
      throw invalid("Choose a valid LoLdle puzzle date that is not in the future.");
    String[] lines = ShareText.lines(rawText);
    if (lines.length != 7 || !HEADER.matcher(lines[0]).matches()
        || !lines[6].matches("(?i)https://(?:www\\.)?loldle\\.net/?"))
      throw invalid("Paste the complete five-mode LoLdle share, including its header and link.");
    Map<String, ParsedSubmission> scores = new LinkedHashMap<>();
    for (int index = 0; index < MODES.size(); index++) {
      Matcher result = RESULTS[index].matcher(lines[index + 1]);
      if (!result.matches()) throw invalid("LoLdle needs one unambiguous result for each of its five modes.");
      int guesses = Integer.parseInt(result.group(1));
      boolean clueUsed = result.group(2) != null;
      Map<String, Object> metrics = new LinkedHashMap<>();
      metrics.put("attemptsUsed", guesses);
      metrics.put("clueUsed", clueUsed);
      metrics.put("hints", clueUsed ? 1 : 0);
      metrics.put("assistanceStatus", clueUsed ? "assisted" : "unassisted");
      metrics.put("completionStatus", "WIN");
      metrics.put("displayScore", guesses + (guesses == 1 ? " guess" : " guesses"));
      scores.put("loldle-" + MODES.get(index), new ParsedSubmission(puzzleDate.toString(), null,
          puzzleDate, BigDecimal.valueOf(guesses), metrics, 1));
    }
    return Collections.unmodifiableMap(scores);
  }
  private static ApiException invalid(String message) { return new ApiException(422, "INVALID_LOLDLE_SHARE", message); }
}

abstract class LoldleModeAdapter implements GameAdapter {
  private final String mode;
  LoldleModeAdapter(String mode) { this.mode = mode; }
  @Override public String slug() { return "loldle-" + mode; }
  @Override public ScoreFormatView scoreFormat() { return new ScoreFormatView("", " guesses", 0, 0, "guesses"); }
  @Override public SubmissionConfigView submissionConfig() {
    return new SubmissionConfigView(LoldleParser.example(), "Puzzle date", "Guesses",
        BigDecimal.ONE, new BigDecimal("999"), BigDecimal.ONE, Set.of());
  }
  @Override public Set<String> supportedFormats() { return Set.of("batch-share-text"); }
  @Override public String parserStatus() { return "PARTIAL"; }
  @Override public ParsedSubmission parse(String rawText) {
    throw new ApiException(422, "LOLDLE_DATE_REQUIRED", "Paste all five LoLdle results and choose their puzzle date.");
  }
  @Override public ParsedSubmission validateConfirmed(ConfirmedSubmission confirmed, String rawText) { return parse(rawText); }
  @Override public boolean matches(String rawText) { return false; }
  @Override public List<ResultDetailView> resultDetails(Map<String, Object> metrics) {
    return Boolean.TRUE.equals(metrics.get("clueUsed")) ? List.of(new ResultDetailView("Clue", "Used ✓")) : List.of();
  }
}
@Component class LoldleClassicAdapter extends LoldleModeAdapter { LoldleClassicAdapter() { super("classic"); } }
@Component class LoldleQuoteAdapter extends LoldleModeAdapter { LoldleQuoteAdapter() { super("quote"); } }
@Component class LoldleAbilityAdapter extends LoldleModeAdapter { LoldleAbilityAdapter() { super("ability"); } }
@Component class LoldleEmojiAdapter extends LoldleModeAdapter { LoldleEmojiAdapter() { super("emoji"); } }
@Component class LoldleSplashAdapter extends LoldleModeAdapter { LoldleSplashAdapter() { super("splash"); } }
