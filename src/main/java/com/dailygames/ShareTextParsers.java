package com.dailygames;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;

import static com.dailygames.ApiModels.*;

@Component
class WordleParser implements GameAdapter {
  private static final Pattern HEADER = Pattern.compile("(?i)^Wordle\\s+([0-9][0-9,]*)\\s+([1-6X])/6(\\*)?$");
  private static final Pattern ROW = Pattern.compile("[⬛⬜🟨🟩]{5}");
  private static final LocalDate FIRST = LocalDate.of(2021, 6, 19);
  @Override public String slug() { return "wordle"; }
  @Override public ScoreFormatView scoreFormat() { return new ScoreFormatView("", " guesses", 0, 2, "guesses"); }
  @Override public SubmissionConfigView submissionConfig() {
    return new SubmissionConfigView("Wordle 1,961 4/6\n\n⬛🟨⬛⬛⬛\n⬛⬛🟩🟨⬛\n🟨🟩🟩⬛⬛\n🟩🟩🟩🟩🟩",
        "Puzzle number", "Guesses", BigDecimal.ONE, BigDecimal.valueOf(7), BigDecimal.ONE, Set.of());
  }
  @Override public ParsedSubmission parse(String rawText) {
    String[] lines = ShareText.lines(rawText);
    if (lines.length > 1 && lines[lines.length - 1].matches("https://www\\.nytimes\\.com/games/wordle(?:/index\\.html)?/?"))
      lines = Arrays.copyOf(lines, lines.length - 1);
    if (lines.length < 2) throw ShareTextParserSupport.invalid("Paste a complete Wordle result.");
    Matcher header = HEADER.matcher(lines[0]);
    if (!header.matches()) throw ShareTextParserSupport.invalid("Wordle puzzle number and score are required.");
    int number = ShareTextParserSupport.number(header.group(1).replace(",", ""));
    int guesses = header.group(2).equalsIgnoreCase("X") ? 7 : Integer.parseInt(header.group(2));
    if (lines.length != (guesses == 7 ? 7 : guesses + 1)) throw ShareTextParserSupport.invalid("Wordle grid must match the guess count.");
    for (int i = 1; i < lines.length; i++) {
      if (!ROW.matcher(lines[i]).matches()) throw ShareTextParserSupport.invalid("Wordle grid has an invalid row.");
      if (i < lines.length - 1 && lines[i].equals("🟩🟩🟩🟩🟩")) throw ShareTextParserSupport.invalid("Wordle ended before the stated score.");
    }
    boolean solved = lines[lines.length - 1].equals("🟩🟩🟩🟩🟩");
    if (solved != (guesses != 7)) throw ShareTextParserSupport.invalid("Wordle grid and score disagree.");
    return new ParsedSubmission(String.valueOf(number), number, FIRST.plusDays(number - 1L), BigDecimal.valueOf(guesses),
        Map.of("completionStatus", solved ? "WIN" : "LOSS",
            "displayScore", header.group(2).toUpperCase(Locale.ROOT) + "/6" + (header.group(3) == null ? "" : "*"),
            "hardMode", header.group(3) != null, "rows", List.of(lines).subList(1, lines.length)), 1);
  }
  @Override public ParsedSubmission validateConfirmed(ConfirmedSubmission confirmed, String rawText) {
    return ShareTextParserSupport.confirm(parse(rawText), confirmed);
  }
}

abstract class LinkedInParser implements GameAdapter {
  private static final Pattern TIME = Pattern.compile("\\|\\s*(\\d{1,3}):([0-5]\\d)\\b");
  private static final Pattern GUESSES = Pattern.compile("\\|\\s*([1-5])\\s+guess(?:es)?\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern HINTS = Pattern.compile("(?i)\\b(?:with\\s+)?(\\d+)\\s+hints?\\b");
  private static final Pattern MISTAKES = Pattern.compile("(?i)\\b(?:with\\s+)?(\\d+)\\s+mistakes?\\b");
  private static final Pattern PREFILLED = Pattern.compile("(?i)\\b(\\d+)\\s+(?:pre[- ]filled|starter)\\b");
  private final String slug;
  private final String name;
  private final LocalDate first;
  private final boolean timed;
  private final String example;
  LinkedInParser(String slug, String name, LocalDate first, boolean timed, String example) {
    this.slug = slug; this.name = name; this.first = first; this.timed = timed; this.example = example;
  }
  @Override public String slug() { return slug; }
  @Override public ScoreFormatView scoreFormat() { return timed
      ? new ScoreFormatView("", "", 0, 0, "duration") : new ScoreFormatView("", " guesses", 0, 2, "guesses"); }
  @Override public SubmissionConfigView submissionConfig() {
    return new SubmissionConfigView(example, "Puzzle number", timed ? "Time" : "Guesses", BigDecimal.ONE,
        timed ? BigDecimal.valueOf(59999) : BigDecimal.valueOf(5), BigDecimal.ONE, Set.of());
  }
  @Override public ParsedSubmission parse(String rawText) {
    String[] lines = ShareTextParserSupport.lines(rawText);
    if (lines.length == 0) throw ShareTextParserSupport.invalid("Paste a complete " + name + " result.");
    String text = String.join(" ", lines);
    Matcher header = Pattern.compile("(?i)^" + Pattern.quote(name) + "\\s+#\\s*(\\d+)\\s*\\|").matcher(text);
    if (!header.find()) throw ShareTextParserSupport.invalid(name + " puzzle number and score are required.");
    int number = ShareTextParserSupport.number(header.group(1));
    Matcher score = timed ? TIME.matcher(text) : GUESSES.matcher(text);
    if (!score.find() || score.start() != header.end() - 1) {
      throw ShareTextParserSupport.invalid("Could not read an unambiguous " + name + " score.");
    }
    int value = timed ? Integer.parseInt(score.group(1)) * 60 + Integer.parseInt(score.group(2)) : Integer.parseInt(score.group(1));
    if (score.find()) throw ShareTextParserSupport.invalid("Could not read an unambiguous " + name + " score.");
    if (value < 1) throw ShareTextParserSupport.invalid("Score must be positive.");
    validateBody(text, value);
    int hints = count(HINTS, text, "no hints");
    int mistakes = count(MISTAKES, text, "no mistakes");
    int beginner = count(PREFILLED, text, null);
    boolean noHints = text.toLowerCase(Locale.ROOT).contains("no hints");
    boolean noMistakes = text.toLowerCase(Locale.ROOT).contains("no mistakes") || text.toLowerCase(Locale.ROOT).contains("and flawless");
    String assistanceStatus = hints > 0 || mistakes > 0 || beginner > 0 ? "assisted" :
        noHints && (slug.equals("zip") || noMistakes) ? "unassisted" : "unknown";
    Map<String, Object> metrics = new LinkedHashMap<>();
    metrics.put("completionStatus", "WIN");
    metrics.put("hints", hints); metrics.put("mistakes", mistakes); metrics.put("beginnerAssists", beginner);
    metrics.put("assistanceStatus", assistanceStatus);
    metrics.put("displayScore", timed ? String.format(Locale.ROOT, "%d:%02d", value / 60, value % 60) : value + (value == 1 ? " guess" : " guesses"));
    if (slug.equals("zip")) {
      Integer backtracks = backtracks(text);
      if (backtracks != null) metrics.put("backtracks", backtracks);
    }
    metrics.putAll(detailMetrics(text));
    return new ParsedSubmission(String.valueOf(number), number, first.plusDays(number - 1L), BigDecimal.valueOf(value), metrics, 1);
  }
  Map<String, Object> detailMetrics(String text) { return Map.of(); }
  private int count(Pattern pattern, String text, String zeroPhrase) {
    Matcher matcher = pattern.matcher(text);
    if (matcher.find()) {
      int number = Integer.parseInt(matcher.group(1));
      if (number > 999 || matcher.find()) throw ShareTextParserSupport.invalid("Ambiguous assistance details.");
      return number;
    }
    return zeroPhrase != null && text.toLowerCase(Locale.ROOT).contains(zeroPhrase) ? 0 : 0;
  }
  private Integer backtracks(String text) {
    Matcher matcher = Pattern.compile("(?i)\\b(?:with\\s+)?(\\d+)\\s+backtracks?\\b").matcher(text);
    if (matcher.find()) {
      int number = Integer.parseInt(matcher.group(1));
      if (number > 9999 || matcher.find()) throw ShareTextParserSupport.invalid("Ambiguous backtracks.");
      return number;
    }
    if (text.toLowerCase(Locale.ROOT).contains("no backtracks")) return 0;
    return null; // Some device shares omit this detail.
  }
  abstract void validateBody(String text, int score);
  boolean hasGameLink(String text) {
    return Pattern.compile("(?i)(?:lnkd\\.in/|(?:www\\.)?linkedin\\.com/games/)" + Pattern.quote(slug) + "(?:/|\\b)")
        .matcher(text).find();
  }
  @Override public ParsedSubmission validateConfirmed(ConfirmedSubmission confirmed, String rawText) {
    return ShareTextParserSupport.confirm(parse(rawText), confirmed);
  }
}

@Component class PinpointParser extends LinkedInParser {
  PinpointParser() { super("pinpoint", "Pinpoint", LocalDate.of(2024, 5, 1), false,
      "Pinpoint #663 | 3 guesses\n1️⃣ | 1% match\n2️⃣ | 7% match\n3️⃣ | 100% match 📌\nlnkd.in/pinpoint"); }
  @Override void validateBody(String text, int score) {
    List<String> matches = Pattern.compile("(?i)\\b\\d{1,3}% match\\b").matcher(text).results().map(MatchResult::group).toList();
    if (!text.contains("📌") || !hasGameLink(text) || matches.size() != score ||
        !matches.getLast().equalsIgnoreCase("100% match"))
      throw ShareTextParserSupport.invalid("Pinpoint needs its completed share details.");
  }
  @Override Map<String, Object> detailMetrics(String text) {
    List<Integer> percentages = Pattern.compile("(?i)\\b(\\d{1,3})% match\\b").matcher(text).results()
        .map(match -> Integer.valueOf(match.group(1))).toList();
    return Map.of("matchPercentages", percentages);
  }
}
@Component class QueensParser extends LinkedInParser {
  QueensParser() { super("queens", "Queens", LocalDate.of(2024, 5, 1), true,
      "Queens #909 | 0:13 with no mistakes & no hints\nFirst 👑s: ⬜ 🟧 🟦\nlnkd.in/queens"); }
  @Override void validateBody(String text, int score) {
    if (!text.contains("First 👑s:") || !hasGameLink(text) ||
        Pattern.compile("[🟥🟧🟨🟩🟦🟪🟫⬜]").matcher(text.substring(text.indexOf("First 👑s:") + 10)).results().count() < 3)
      throw ShareTextParserSupport.invalid("Queens needs its completed share details.");
  }
  @Override Map<String, Object> detailMetrics(String text) {
    String crowns = text.substring(text.indexOf("First 👑s:") + "First 👑s:".length());
    List<String> colors = Pattern.compile("[🟥🟧🟨🟩🟦🟪🟫⬜]").matcher(crowns).results()
        .limit(8).map(MatchResult::group).toList();
    return Map.of("firstCrowns", colors);
  }
}
@Component class CrossclimbParser extends LinkedInParser {
  CrossclimbParser() { super("crossclimb", "Crossclimb", LocalDate.of(2024, 5, 1), true,
      "Crossclimb #918 | 1:38 with no hints\nFill order: 1️⃣ 2️⃣ 3️⃣ 5️⃣ 4️⃣ ⬇️ ⬆️ 🪜\nlnkd.in/crossclimb"); }
  @Override void validateBody(String text, int score) {
    if (!text.contains("Fill order:") || !text.contains("🪜") || !hasGameLink(text) ||
        !text.substring(text.indexOf("Fill order:")).contains("️⃣"))
      throw ShareTextParserSupport.invalid("Crossclimb needs its completed share details.");
  }
  @Override Map<String, Object> detailMetrics(String text) {
    String order = text.substring(text.indexOf("Fill order:"));
    List<Integer> values = Pattern.compile("[1-5]\\uFE0F?\\u20E3").matcher(order).results()
        .limit(5).map(match -> ShareText.keycapDigit(match.group())).toList();
    return Map.of("fillOrder", values);
  }
}
@Component class TangoParser extends LinkedInParser {
  TangoParser() { super("tango", "Tango", LocalDate.of(2024, 10, 8), true,
      "Tango #758 | 1:00 with no mistakes & no hints\nFirst 5 placements: 🟨🟨🟨🟨🟨🟨 🟨🟨🟨🟨🟨🟨 2️⃣🟨1️⃣🟨🟨🟨 3️⃣🟨🟨🟨🟨🟨 4️⃣🟨🟨🟨🟨🟨 5️⃣🟨🟨🟨🟨🟨\nlnkd.in/tango"); }
  @Override void validateBody(String text, int score) {
    if (!text.contains("First 5 placements:") || !hasGameLink(text) ||
        !List.of("1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣").stream().allMatch(text::contains))
      throw ShareTextParserSupport.invalid("Tango needs its completed share details.");
  }
  @Override Map<String, Object> detailMetrics(String text) {
    String board = text.substring(text.indexOf("First 5 placements:") + "First 5 placements:".length());
    int link = board.toLowerCase(Locale.ROOT).indexOf("lnkd.in/");
    if (link >= 0) board = board.substring(0, link);
    Map<Integer, String> placements = new TreeMap<>();
    String[] rows = board.trim().split("\\s+");
    Pattern cell = Pattern.compile("[1-5]\\uFE0F?\\u20E3|🟨");
    for (int row = 0; row < rows.length; row++) {
      List<String> cells = cell.matcher(rows[row]).results().map(MatchResult::group).toList();
      for (int column = 0; column < cells.size(); column++) {
        String value = cells.get(column);
        if (!value.equals("🟨")) placements.put(ShareText.keycapDigit(value), "R" + (row + 1) + "C" + (column + 1));
      }
    }
    return placements.size() == 5 ? Map.of("placementCells", List.copyOf(placements.values())) : Map.of();
  }
}
@Component class ZipParser extends LinkedInParser {
  ZipParser() { super("zip", "Zip", LocalDate.of(2025, 3, 18), true,
      "Zip #373 | 0:08 and flawless 🏁 With no backtracks 🟢\nlnkd.in/zip"); }
  @Override void validateBody(String text, int score) {
    if (!text.contains("🏁") || !hasGameLink(text))
      throw ShareTextParserSupport.invalid("Zip needs its completed share details.");
  }
}

final class ShareTextParserSupport {
  private ShareTextParserSupport() {}
  static String[] lines(String raw) {
    String text = raw == null ? "" : raw.strip();
    if (text.length() > 10_000) throw invalid("Result text is too long.");
    return text.isEmpty() ? new String[0] : text.lines().map(String::strip).filter(line -> !line.isEmpty()).toArray(String[]::new);
  }
  static int number(String value) {
    try {
      int number = Integer.parseInt(value);
      if (number > 0 && number <= 100_000) return number;
    } catch (NumberFormatException ignored) {}
    throw invalid("Puzzle number is invalid.");
  }
  static ParsedSubmission confirm(ParsedSubmission parsed, ConfirmedSubmission confirmed) {
    if (confirmed == null || !Objects.equals(parsed.puzzleKey(), confirmed.puzzleKey()) ||
        !Objects.equals(parsed.playedOn(), confirmed.playedOn()) || confirmed.primaryScore() == null ||
        parsed.primaryScore().compareTo(confirmed.primaryScore()) != 0 ||
        !Objects.equals(parsed.metrics(), confirmed.metrics()) || !Objects.equals(parsed.parserVersion(), confirmed.parserVersion()))
      throw new ApiException(422, "INVALID_SUBMISSION", "Confirmed result must match the shared result.");
    return parsed;
  }
  static ApiException invalid(String message) { return new ApiException(422, "INVALID_SHARE_TEXT", message); }
}
