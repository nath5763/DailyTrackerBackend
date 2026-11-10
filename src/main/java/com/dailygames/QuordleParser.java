package com.dailygames;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;

import static com.dailygames.ApiModels.*;

@Component
class QuordleParser implements GameAdapter {
  private static final Pattern DAILY = Pattern.compile("^(?:🙂)?Daily Quordle ([1-9][0-9]*)$");
  private static final Pattern SEQUENCE = Pattern.compile("^Daily Sequence Quordle ([1-9][0-9]*)$");
  private static final String RESULT_TOKEN = "(?:[1-9](?:\\u20E3)?|🟥|⬛|❌|X)";
  private static final Pattern TOKEN = Pattern.compile(RESULT_TOKEN);
  private static final Pattern SCORE_LINE = Pattern.compile(RESULT_TOKEN + "(?:\\s*" + RESULT_TOKEN + ")?");
  private static final Pattern LINK = Pattern.compile("^(?:https?://)?(?:www\\.)?m-w\\.com/games/quordle/?$");
  private static final LocalDate ANCHOR_DATE = LocalDate.of(2026, 11, 5);
  private static final int ANCHOR_PUZZLE = 1746;
  @Override public String slug() { return "quordle"; }
  @Override public ScoreFormatView scoreFormat() { return new ScoreFormatView("", "", 0, 0, "number"); }
  @Override public SubmissionConfigView submissionConfig() {
    return new SubmissionConfigView("Daily Quordle 1746\n5️⃣9️⃣\n8️⃣\nm-w.com/games/quordle/",
        "Puzzle number", "Penalized guess total", BigDecimal.valueOf(4), BigDecimal.valueOf(40),
        BigDecimal.ONE, Set.of());
  }
  @Override public ParsedSubmission parse(String rawText) {
    if (rawText != null && rawText.length() > 10_000) throw invalid();
    // Clipboard shares can carry invisible format characters around keycap emoji or the URL.
    String cleaned = rawText == null ? null : rawText
        .replaceAll("[\\u200B\\u200C\\u200D\\u2060\\uFEFF]", "")
        .replace('\u00A0', ' ');
    String[] lines = ShareText.lines(cleaned);
    if (lines.length != 4)
      throw ShareTextParserSupport.invalid("Quordle needs a header, two score rows, and the Merriam-Webster link.");
    Matcher daily = DAILY.matcher(lines[0]), sequence = SEQUENCE.matcher(lines[0]);
    boolean dailyMatch = daily.matches(), sequenceMatch = sequence.matches();
    if (!dailyMatch && !sequenceMatch)
      throw ShareTextParserSupport.invalid("Quordle needs a Daily Quordle puzzle header.");
    if (!SCORE_LINE.matcher(lines[1]).matches() || !SCORE_LINE.matcher(lines[2]).matches())
      throw ShareTextParserSupport.invalid("Quordle score rows must contain one or two results from 1–9 or X.");
    if (!LINK.matcher(lines[3]).matches())
      throw ShareTextParserSupport.invalid("Quordle needs its m-w.com/games/quordle/ link.");
    int puzzle = ShareTextParserSupport.number((dailyMatch ? daily : sequence).group(1));
    String mode = dailyMatch ? "Daily" : "Sequence";
    List<Integer> top = tokens(lines[1]), bottom = tokens(lines[2]);
    if (top.size() != 2 || bottom.isEmpty() || bottom.size() > 2) throw invalid();
    List<Integer> guesses = new ArrayList<>(4);
    guesses.addAll(top);
    if (bottom.size() == 1) { guesses.add(null); guesses.add(bottom.getFirst()); }
    else guesses.addAll(bottom);
    int failed = (int) guesses.stream().filter(Objects::isNull).count();
    int solved = 4 - failed;
    int penalized = guesses.stream().mapToInt(value -> value == null ? 10 : value).sum();
    LocalDate date = ANCHOR_DATE.plusDays(puzzle - ANCHOR_PUZZLE);
    if (date.isAfter(LocalDate.now().plusDays(1)))
      throw ShareTextParserSupport.invalid("Quordle puzzle " + puzzle + " appears to be dated in the future.");
    Map<String, Object> metrics = new LinkedHashMap<>();
    metrics.put("completionStatus", failed == 0 ? "WIN" : "LOSS");
    metrics.put("guesses", Collections.unmodifiableList(guesses));
    metrics.put("solved", solved); metrics.put("failed", failed);
    metrics.put("penalizedGuessTotal", penalized); metrics.put("attemptsMaximum", 9);
    metrics.put("rankingPrimary", failed); metrics.put("rankingSecondary", penalized);
    metrics.put("rankingPrimaryDirection", "ASCENDING");
    metrics.put("rankingSecondaryDirection", "ASCENDING");
    metrics.put("gameMode", mode); metrics.put("scoreUnit", "penalized guesses");
    metrics.put("scoreRaw", lines[1] + " / " + lines[2]);
    metrics.put("displayScore", solved + "/4 · " + penalized);
    String puzzleKey = mode.equals("Daily") ? String.valueOf(puzzle) : mode + ":" + puzzle;
    return new ParsedSubmission(puzzleKey, puzzle, date, BigDecimal.valueOf(penalized), metrics, 1);
  }
  @Override public ParsedSubmission validateConfirmed(ConfirmedSubmission confirmed, String rawText) {
    return ShareTextParserSupport.confirm(parse(rawText), confirmed);
  }
  private List<Integer> tokens(String line) {
    List<Integer> values = new ArrayList<>();
    Matcher matcher = TOKEN.matcher(line);
    while (matcher.find()) {
      String token = matcher.group();
      if (token.charAt(0) >= '1' && token.charAt(0) <= '9') values.add(ShareText.keycapDigit(token));
      else values.add(null);
    }
    return values;
  }
  private ApiException invalid() {
    return ShareTextParserSupport.invalid("Paste a complete Merriam-Webster Daily Quordle result.");
  }
}
