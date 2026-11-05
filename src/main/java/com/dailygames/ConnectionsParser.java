package com.dailygames;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;

import static com.dailygames.ApiModels.*;

@Component
class ConnectionsParser implements GameAdapter {
  private static final Pattern HEADER = Pattern.compile("(?i)^Puzzle #([1-9][0-9]*)$");
  private static final Set<String> COLORS = Set.of("🟨", "🟩", "🟦", "🟪");
  @Override public String slug() { return "connections"; }
  @Override public ScoreFormatView scoreFormat() { return new ScoreFormatView("", " mistakes", 0, 0); }
  @Override public SubmissionConfigView submissionConfig() {
    return new SubmissionConfigView("Connections\nPuzzle #1240\n🟨🟨🟨🟨\n🟩🟩🟩🟩\n🟦🟦🟦🟦\n🟪🟪🟪🟪",
        "Puzzle number", "Mistakes", BigDecimal.ZERO, BigDecimal.valueOf(4), BigDecimal.ONE, Set.of());
  }
  @Override public ParsedSubmission parse(String rawText) {
    String[] lines = ShareText.lines(rawText);
    if (rawText == null || rawText.length() > 10_000 || lines.length < 3 || !lines[0].equals("Connections")) throw invalid();
    Matcher header = HEADER.matcher(lines[1]);
    if (!header.matches()) throw invalid();
    int number;
    try { number = Integer.parseInt(header.group(1)); }
    catch (NumberFormatException exception) { throw invalid(); }
    if (number < 1 || lines.length > 9) throw invalid();
    Set<String> solvedColors = new HashSet<>();
    List<String> rows = new ArrayList<>();
    int mistakes = 0;
    for (int i = 2; i < lines.length; i++) {
      String row = lines[i];
      List<String> cells = row.codePoints().mapToObj(Character::toString).toList();
      if (cells.size() != 4 || !COLORS.containsAll(cells)) throw invalid();
      rows.add(row);
      if (cells.stream().distinct().count() == 1) {
        if (!solvedColors.add(cells.getFirst())) throw invalid();
      } else mistakes++;
      if (mistakes > 4 || (mistakes == 4 && i != lines.length - 1)) throw invalid();
    }
    boolean solved = solvedColors.size() == 4;
    if (!solved && mistakes != 4) throw invalid();
    if (solved && mistakes == 4) throw invalid();
    Map<String, Object> metrics = Map.of("rows", rows, "solved", solved, "completionStatus", solved ? "WIN" : "LOSS",
        "wrongGroups", mistakes, "scoreRaw", mistakes + " mistakes", "scoreUnit", "mistakes");
    return new ParsedSubmission(String.valueOf(number), number, LocalDate.of(2026, 11, 2).plusDays(number - 1240L),
        BigDecimal.valueOf(mistakes), metrics, 1);
  }
  @Override public ParsedSubmission validateConfirmed(ConfirmedSubmission confirmed, String rawText) {
    ParsedSubmission parsed = parse(rawText);
    if (confirmed == null || !Objects.equals(parsed.puzzleKey(), confirmed.puzzleKey()) ||
        !Objects.equals(parsed.playedOn(), confirmed.playedOn()) ||
        !Objects.equals(parsed.primaryScore(), confirmed.primaryScore()))
      throw new ApiException(422, "INVALID_SUBMISSION", "Connections result must match the shared grid.");
    return parsed;
  }
  private ApiException invalid() { return new ApiException(422, "INVALID_SHARE_TEXT", "Paste a complete Connections share result."); }
}
