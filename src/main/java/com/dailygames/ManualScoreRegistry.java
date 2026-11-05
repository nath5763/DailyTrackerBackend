package com.dailygames;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static com.dailygames.ApiModels.*;

/** Defines the fields the website may collect and derives trusted ranking metrics from those fields. */
final class ManualScoreRegistry {
  private static final List<String> WIN = List.of("WIN");
  private static final List<String> WIN_LOSS = List.of("WIN", "LOSS");
  private final Map<String, ManualEntryConfigView> configs;

  ManualScoreRegistry() {
    Map<String, ManualEntryConfigView> values = new LinkedHashMap<>();
    values.put("fermi", config(WIN, decimal("score", "Final multiplier", true, "0.01", null, "0.01", "Enter the × score."),
        decimal("percentile", "Top percentile", false, "0", "100", "0.01", "Optional top percentage.")));
    values.put("krillion", config(WIN, integer("score", "Final points", true, 0, 700, "Enter the displayed final score.")));
    values.put("wordle", config(WIN_LOSS, integer("attempts", "Guesses used", false, 1, 6, "Required for a win; leave blank for a loss."),
        bool("hardMode", "Hard mode", false, "Select if the result used hard mode.")));
    values.put("pinpoint", config(WIN, integer("attempts", "Guesses used", true, 1, 5, null)));
    values.put("queens", timed("mistakes", "hints", "beginnerAssists"));
    values.put("crossclimb", timed("hints"));
    values.put("tango", timed("mistakes", "hints", "beginnerAssists"));
    values.put("zip", timed("backtracks"));
    values.put("connections", config(WIN_LOSS, integer("wrongGroups", "Wrong groups", true, 0, 4,
        "A loss must have four wrong groups; a win must have fewer than four.")));
    values.put("worldle", guessWithFailure());
    values.put("murdle", durationOnly());
    values.put("globle", guesses());
    values.put("semantle", config(WIN, integer("attempts", "Guesses used", true, 1, 100000, null),
        integer("hints", "Hints used", false, 0, 10000, null)));
    values.put("contexto", config(WIN, integer("attempts", "Guesses used", true, 1, 100000, null),
        integer("hints", "Hints used", false, 0, 10000, null)));
    values.put("redactle", config(WIN, integer("attempts", "Guesses used", true, 1, 100000, null),
        integer("hints", "Hints used", false, 0, 10000, null),
        duration("completionTimeSeconds", "Completion time", false, "Optional, as m:ss or seconds.")));
    values.put("strands", config(WIN, integer("hints", "Hints used", true, 0, 1000, null)));
    values.put("nyt-mini-crossword", durationOnly());
    values.put("nerdle", guessWithFailure());
    values.put("quordle", config(WIN_LOSS,
        board("board1", "Board 1"), board("board2", "Board 2"), board("board3", "Board 3"), board("board4", "Board 4")));
    values.put("timeguessr", config(WIN, integer("score", "Total points", true, 0, 50000, null),
        integer("round1", "Round 1 points", false, 0, 10000, null), integer("round2", "Round 2 points", false, 0, 10000, null),
        integer("round3", "Round 3 points", false, 0, 10000, null), integer("round4", "Round 4 points", false, 0, 10000, null),
        integer("round5", "Round 5 points", false, 0, 10000, "If one round is entered, all five are required.")));
    configs = Collections.unmodifiableMap(values);
  }

  ManualEntryConfigView configFor(String slug) {
    return configs.getOrDefault(slug, new ManualEntryConfigView(false, false, List.of(), List.of()));
  }

  ParsedSubmission normalize(String slug, ManualSubmissionRequest request) {
    ManualEntryConfigView config = configFor(slug);
    if (!config.supported()) throw invalid("Manual entry is not available for this game.");
    LocalDate date = request.puzzleDate();
    if (date == null) throw invalid("Choose the date on which the puzzle was published.");
    String puzzleId = normalizePuzzleId(request.puzzleId());
    if (config.puzzleIdRequired() && puzzleId == null) throw invalid("Enter the puzzle identifier.");
    String status = request.completionStatus() == null ? "" : request.completionStatus().trim().toUpperCase(Locale.ROOT);
    if (!config.completionStatuses().contains(status)) throw invalid("Choose a supported completion result.");
    Map<String, Object> raw = request.values() == null ? Map.of() : request.values();
    Set<String> allowed = config.fields().stream().map(ManualEntryFieldView::key).collect(java.util.stream.Collectors.toSet());
    if (raw.keySet().stream().anyMatch(key -> !allowed.contains(key))) throw invalid("The manual result contains unsupported fields.");
    for (ManualEntryFieldView field : config.fields()) if (field.required() && missing(raw.get(field.key())))
      throw invalid(field.label() + " is required.");

    String key = puzzleId == null ? date.toString() : puzzleId;
    Integer number = puzzleId != null && puzzleId.matches("[1-9][0-9]*") ? parsePuzzleNumber(puzzleId) : null;
    Map<String, Object> metrics = new LinkedHashMap<>();
    metrics.put("completionStatus", status);

    BigDecimal primary = switch (slug) {
      case "fermi" -> fermi(raw, metrics);
      case "krillion" -> points(raw, metrics, 700, "points");
      case "wordle" -> sixGuess(raw, metrics, status, true);
      case "worldle", "nerdle" -> sixGuess(raw, metrics, status, false);
      case "pinpoint" -> attempts(raw, metrics, 5);
      case "queens", "crossclimb", "tango", "zip" -> timed(raw, metrics);
      case "connections" -> connections(raw, metrics, status);
      case "murdle", "nyt-mini-crossword" -> duration(raw, metrics);
      case "globle" -> attempts(raw, metrics);
      case "semantle", "contexto", "redactle" -> assistedGuesses(raw, metrics);
      case "strands" -> strands(raw, metrics);
      case "quordle" -> quordle(raw, metrics, status);
      case "timeguessr" -> timeGuessr(raw, metrics);
      default -> throw invalid("Manual entry is not available for this game.");
    };
    return new ParsedSubmission(key, number, date, primary, Collections.unmodifiableMap(metrics), 1);
  }

  private BigDecimal fermi(Map<String, Object> raw, Map<String, Object> metrics) {
    BigDecimal score = decimal(raw, "score", true, new BigDecimal("0.01"), null);
    BigDecimal percentile = decimal(raw, "percentile", false, BigDecimal.ZERO, BigDecimal.valueOf(100));
    if (percentile != null) metrics.put("percentile", percentile);
    metrics.put("scoreUnit", "multiplier"); metrics.put("displayScore", score.stripTrailingZeros().toPlainString() + "×");
    return score;
  }

  private BigDecimal points(Map<String, Object> raw, Map<String, Object> metrics, int max, String unit) {
    int score = integer(raw, "score", true, 0, max);
    metrics.put("nativeScore", score); metrics.put("scoreUnit", unit);
    metrics.put("displayScore", String.format(Locale.US, "%,d pts", score));
    return BigDecimal.valueOf(score);
  }

  private BigDecimal sixGuess(Map<String, Object> raw, Map<String, Object> metrics, String status, boolean hardMode) {
    Integer attempts = optionalInteger(raw, "attempts", 1, 6);
    if ("WIN".equals(status) && attempts == null) throw invalid("Guesses used is required for a win.");
    if ("LOSS".equals(status) && attempts != null) throw invalid("Leave guesses blank for a failed puzzle.");
    int score = attempts == null ? 7 : attempts;
    metrics.put("attemptsUsed", attempts == null ? 6 : attempts); metrics.put("attemptsMaximum", 6);
    metrics.put("scoreUnit", "guesses"); metrics.put("displayScore", attempts == null ? "X/6" : attempts + "/6");
    if (hardMode && bool(raw.get("hardMode"))) metrics.put("hardMode", true);
    return BigDecimal.valueOf(score);
  }

  private BigDecimal attempts(Map<String, Object> raw, Map<String, Object> metrics) {
    return attempts(raw, metrics, 100000);
  }

  private BigDecimal attempts(Map<String, Object> raw, Map<String, Object> metrics, int maximum) {
    int attempts = integer(raw, "attempts", true, 1, maximum);
    metrics.put("attemptsUsed", attempts); metrics.put("scoreUnit", "guesses");
    metrics.put("displayScore", attempts + (attempts == 1 ? " guess" : " guesses"));
    return BigDecimal.valueOf(attempts);
  }

  private BigDecimal assistedGuesses(Map<String, Object> raw, Map<String, Object> metrics) {
    BigDecimal score = attempts(raw, metrics);
    putAssistance(raw, metrics, List.of("hints"));
    Integer seconds = optionalDuration(raw, "completionTimeSeconds");
    if (seconds != null) metrics.put("completionTimeSeconds", seconds);
    return score;
  }

  private BigDecimal timed(Map<String, Object> raw, Map<String, Object> metrics) {
    BigDecimal value = duration(raw, metrics);
    putAssistance(raw, metrics, List.of("hints", "mistakes", "beginnerAssists", "backtracks"));
    return value;
  }

  private BigDecimal duration(Map<String, Object> raw, Map<String, Object> metrics) {
    int seconds = requiredDuration(raw, "completionTimeSeconds");
    metrics.put("completionTimeSeconds", seconds); metrics.put("scoreUnit", "seconds");
    metrics.put("displayScore", formatDuration(seconds));
    return BigDecimal.valueOf(seconds);
  }

  private BigDecimal connections(Map<String, Object> raw, Map<String, Object> metrics, String status) {
    int wrong = integer(raw, "wrongGroups", true, 0, 4);
    if ("WIN".equals(status) && wrong == 4 || "LOSS".equals(status) && wrong != 4)
      throw invalid("Connections wins must have fewer than four wrong groups; losses must have four.");
    metrics.put("wrongGroups", wrong); metrics.put("solved", "WIN".equals(status)); metrics.put("scoreUnit", "mistakes");
    metrics.put("displayScore", wrong + (wrong == 1 ? " mistake" : " mistakes"));
    return BigDecimal.valueOf(wrong);
  }

  private BigDecimal strands(Map<String, Object> raw, Map<String, Object> metrics) {
    int hints = integer(raw, "hints", true, 0, 1000);
    metrics.put("hints", hints); metrics.put("scoreUnit", "hints");
    metrics.put("assistanceStatus", hints == 0 ? "unassisted" : "assisted");
    metrics.put("displayScore", hints + (hints == 1 ? " hint" : " hints"));
    return BigDecimal.valueOf(hints);
  }

  private BigDecimal quordle(Map<String, Object> raw, Map<String, Object> metrics, String status) {
    List<Integer> guesses = new ArrayList<>(); int failed = 0, total = 0;
    for (int index = 1; index <= 4; index++) {
      String value = Objects.toString(raw.get("board" + index), "").trim().toUpperCase(Locale.ROOT);
      if (value.equals("FAIL")) { guesses.add(null); failed++; total += 10; }
      else {
        int guess;
        try { guess = Integer.parseInt(value); } catch (NumberFormatException exception) { throw invalid("Each Quordle board must be 1–9 or failed."); }
        if (guess < 1 || guess > 9) throw invalid("Each Quordle board must be 1–9 or failed.");
        guesses.add(guess); total += guess;
      }
    }
    if ("WIN".equals(status) != (failed == 0)) throw invalid("The Quordle completion result does not match its boards.");
    int solved = 4 - failed;
    metrics.put("guesses", Collections.unmodifiableList(guesses)); metrics.put("solved", solved); metrics.put("failed", failed);
    metrics.put("penalizedGuessTotal", total); metrics.put("attemptsMaximum", 9); metrics.put("rankingPrimary", failed);
    metrics.put("rankingSecondary", total); metrics.put("rankingPrimaryDirection", "ASCENDING");
    metrics.put("rankingSecondaryDirection", "ASCENDING"); metrics.put("gameMode", "Daily");
    metrics.put("scoreUnit", "penalized guesses"); metrics.put("displayScore", solved + "/4 · " + total);
    return BigDecimal.valueOf(total);
  }

  private BigDecimal timeGuessr(Map<String, Object> raw, Map<String, Object> metrics) {
    int score = integer(raw, "score", true, 0, 50000);
    List<Integer> rounds = new ArrayList<>(); boolean any = false, missing = false;
    for (int index = 1; index <= 5; index++) {
      Integer round = optionalInteger(raw, "round" + index, 0, 10000);
      if (round == null) missing = true; else { any = true; rounds.add(round); }
    }
    if (any && missing) throw invalid("Enter all five round scores or leave all of them blank.");
    if (any && rounds.stream().mapToInt(Integer::intValue).sum() != score) throw invalid("TimeGuessr round scores must add up to the total.");
    metrics.put("nativeScore", score); metrics.put("rankingPrimary", score); metrics.put("rankingPrimaryDirection", "DESCENDING");
    metrics.put("gameMode", "Daily"); metrics.put("scoreUnit", "points");
    metrics.put("displayScore", String.format(Locale.US, "%,d pts", score));
    if (any) metrics.put("roundScores", List.copyOf(rounds));
    return BigDecimal.valueOf(score);
  }

  private void putAssistance(Map<String, Object> raw, Map<String, Object> metrics, List<String> keys) {
    boolean known = false, assisted = false;
    for (String key : keys) {
      Integer value = optionalInteger(raw, key, 0, 10000);
      if (value != null) { known = true; assisted |= value > 0; metrics.put(key, value); }
    }
    metrics.put("assistanceStatus", known ? assisted ? "assisted" : "unassisted" : "unknown");
  }

  private static ManualEntryConfigView config(List<String> statuses, ManualEntryFieldView... fields) {
    return new ManualEntryConfigView(true, false, statuses, List.of(fields));
  }
  private static ManualEntryConfigView guesses() { return config(WIN, integer("attempts", "Guesses used", true, 1, 100000, null)); }
  private static ManualEntryConfigView guessWithFailure() { return config(WIN_LOSS,
      integer("attempts", "Guesses used", false, 1, 6, "Required for a win; leave blank for a loss.")); }
  private static ManualEntryConfigView durationOnly() { return config(WIN,
      duration("completionTimeSeconds", "Completion time", true, "Enter m:ss or total seconds.")); }
  private static ManualEntryConfigView timed(String... assistance) {
    List<ManualEntryFieldView> fields = new ArrayList<>();
    fields.add(duration("completionTimeSeconds", "Completion time", true, "Enter m:ss or total seconds."));
    for (String key : assistance) fields.add(integer(key, label(key), false, 0, 10000, null));
    return new ManualEntryConfigView(true, false, WIN, List.copyOf(fields));
  }
  private static ManualEntryFieldView integer(String key, String label, boolean required, int min, int max, String help) {
    return new ManualEntryFieldView(key, label, "INTEGER", required, BigDecimal.valueOf(min), BigDecimal.valueOf(max), BigDecimal.ONE, List.of(), help);
  }
  private static ManualEntryFieldView decimal(String key, String label, boolean required, String min, String max, String step, String help) {
    return new ManualEntryFieldView(key, label, "DECIMAL", required, min == null ? null : new BigDecimal(min),
        max == null ? null : new BigDecimal(max), new BigDecimal(step), List.of(), help);
  }
  private static ManualEntryFieldView duration(String key, String label, boolean required, String help) {
    return new ManualEntryFieldView(key, label, "DURATION", required, BigDecimal.ONE, BigDecimal.valueOf(86399), BigDecimal.ONE, List.of(), help);
  }
  private static ManualEntryFieldView bool(String key, String label, boolean required, String help) {
    return new ManualEntryFieldView(key, label, "BOOLEAN", required, null, null, null, List.of(), help);
  }
  private static ManualEntryFieldView board(String key, String label) {
    return new ManualEntryFieldView(key, label, "ENUM", true, null, null, null,
        List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "FAIL"), "Choose failed if that board was not solved.");
  }
  private static String label(String key) {
    return switch (key) { case "hints" -> "Hints used"; case "mistakes" -> "Mistakes";
      case "beginnerAssists" -> "Beginner assists"; case "backtracks" -> "Backtracks"; default -> key; };
  }

  private String normalizePuzzleId(String value) {
    if (value == null || value.isBlank()) return null;
    String normalized = value.trim();
    if (normalized.startsWith("#")) normalized = normalized.substring(1).trim();
    if (normalized.isEmpty() || normalized.length() > 80 || normalized.chars().anyMatch(Character::isWhitespace))
      throw invalid("Puzzle ID must be a single value no longer than 80 characters.");
    return normalized;
  }
  private Integer parsePuzzleNumber(String value) {
    try { return Integer.valueOf(value); } catch (NumberFormatException exception) { throw invalid("Puzzle number is too large."); }
  }
  private int integer(Map<String, Object> values, String key, boolean required, int min, int max) {
    Integer value = optionalInteger(values, key, min, max);
    if (value == null && required) throw invalid(label(key) + " is required.");
    return value == null ? 0 : value;
  }
  private Integer optionalInteger(Map<String, Object> values, String key, int min, int max) {
    Object raw = values.get(key); if (missing(raw)) return null;
    BigDecimal value;
    try { value = new BigDecimal(raw.toString().trim()); } catch (NumberFormatException exception) { throw invalid(label(key) + " must be a whole number."); }
    if (value.stripTrailingZeros().scale() > 0 || value.compareTo(BigDecimal.valueOf(min)) < 0 || value.compareTo(BigDecimal.valueOf(max)) > 0)
      throw invalid(label(key) + " is outside the supported range.");
    return value.intValueExact();
  }
  private BigDecimal decimal(Map<String, Object> values, String key, boolean required, BigDecimal min, BigDecimal max) {
    Object raw = values.get(key); if (missing(raw)) { if (required) throw invalid(label(key) + " is required."); return null; }
    BigDecimal value;
    try { value = new BigDecimal(raw.toString().trim()); } catch (NumberFormatException exception) { throw invalid(label(key) + " must be a number."); }
    if (min != null && value.compareTo(min) < 0 || max != null && value.compareTo(max) > 0) throw invalid(label(key) + " is outside the supported range.");
    return value;
  }
  private int requiredDuration(Map<String, Object> values, String key) {
    Integer value = optionalDuration(values, key); if (value == null) throw invalid("Completion time is required."); return value;
  }
  private Integer optionalDuration(Map<String, Object> values, String key) {
    Object raw = values.get(key); if (missing(raw)) return null;
    String text = raw.toString().trim();
    int seconds;
    try {
      if (text.contains(":")) {
        String[] parts = text.split(":", -1);
        if (parts.length < 2 || parts.length > 3) throw new NumberFormatException();
        int hours = parts.length == 3 ? Integer.parseInt(parts[0]) : 0;
        int minutes = Integer.parseInt(parts[parts.length - 2]); int remainder = Integer.parseInt(parts[parts.length - 1]);
        if (minutes < 0 || (parts.length == 3 && minutes > 59) || remainder < 0 || remainder > 59) throw new NumberFormatException();
        seconds = Math.addExact(Math.multiplyExact(hours, 3600), Math.addExact(Math.multiplyExact(minutes, 60), remainder));
      } else seconds = Integer.parseInt(text);
    } catch (NumberFormatException | ArithmeticException exception) { throw invalid("Completion time must be m:ss or total seconds."); }
    if (seconds < 1 || seconds > 86399) throw invalid("Completion time is outside the supported range.");
    return seconds;
  }
  private boolean bool(Object value) { return value instanceof Boolean flag ? flag : value != null && Boolean.parseBoolean(value.toString()); }
  private static boolean missing(Object value) { return value == null || value instanceof String text && text.isBlank(); }
  private static String formatDuration(int seconds) {
    if (seconds >= 3600) return String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
  }
  private ApiException invalid(String message) { return new ApiException(422, "INVALID_MANUAL_RESULT", message); }
}
