package com.dailygames;

import org.junit.jupiter.api.Test;

import java.util.*;

import static com.dailygames.ApiModels.ResultDetailView;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ResultDetailFormatterTest {
  @Test void formatsGuessGridsAndBoards() {
    assertEquals(List.of(new ResultDetailView("G1", "⬛🟨⬛⬛⬛"),
            new ResultDetailView("G2", "🟩🟩🟩🟩🟩")),
        ResultDetailFormatter.fromMetrics("wordle", Map.of("rows", List.of("⬛🟨⬛⬛⬛", "🟩🟩🟩🟩🟩"))));
    assertEquals(List.of(new ResultDetailView("Q1", "5 guesses"),
            new ResultDetailView("Q2", "9 guesses"), new ResultDetailView("Q3", "X"),
            new ResultDetailView("Q4", "8 guesses")),
        ResultDetailFormatter.fromMetrics("quordle", Map.of("guesses", Arrays.asList(5, 9, null, 8))));
    assertEquals(List.of(new ResultDetailView("R1", "8734 pts"), new ResultDetailView("R2", "6874 pts")),
        ResultDetailFormatter.fromMetrics("timeguessr", Map.of("roundScores", List.of(8734, 6874))));
  }

  @Test void formatsGameSpecificCategoriesAndSequences() {
    assertEquals(List.of(new ResultDetailView("Green", "4 guesses"),
            new ResultDetailView("Yellow", "0 guesses"), new ResultDetailView("Red", "1 guesses")),
        ResultDetailFormatter.fromMetrics("contexto", Map.of(
            "greenGuesses", 4, "yellowGuesses", 0, "redGuesses", 1)));
    assertEquals(List.of(new ResultDetailView("Path", "🟨🟥🟥🟩")),
        ResultDetailFormatter.fromMetrics("globle", Map.of("proximityGrid", "🟨🟥🟥🟩")));
    assertEquals(List.of(new ResultDetailView("Path", "💡🔵🟡🔵")),
        ResultDetailFormatter.fromMetrics("strands", Map.of("resultSequence", "💡🔵🟡🔵")));
    assertEquals(List.of(new ResultDetailView("Count", "13"), new ResultDetailView("Track", "93"),
            new ResultDetailView("Angle", "93"), new ResultDetailView("Tempo", "11"),
            new ResultDetailView("Split", "98")),
        ResultDetailFormatter.fromMetrics("perceptle", Map.of("countScore", 13, "trackScore", 93,
            "angleScore", 93, "tempoScore", 11, "splitScore", 98)));
    assertEquals(List.of(new ResultDetailView("M1", "gold"), new ResultDetailView("M2", "miss")),
        ResultDetailFormatter.fromMetrics("gazillion", Map.of("resultTiers", List.of("gold", "miss"))));
  }

  @Test void formatsVerifiedSecondaryDetailsAndOmitsUnavailableOnes() {
    assertEquals(List.of(new ResultDetailView("Time", "14:23"), new ResultDetailView("Hints", "0")),
        ResultDetailFormatter.fromMetrics("redactle", Map.of("completionTimeSeconds", 863,
            "hints", 0, "assistanceStatus", "unassisted")));
    assertEquals(List.of(new ResultDetailView("Best guess", "116"),
            new ResultDetailView("Closest", "996/1000"), new ResultDetailView("Hints", "2")),
        ResultDetailFormatter.fromMetrics("semantle", Map.of("bestPriorGuess", 116,
            "closestPriorRank", 996, "hints", 2, "assistanceStatus", "assisted")));
    assertEquals(List.of(new ResultDetailView("Hints", "0"), new ResultDetailView("Mistakes", "0"),
            new ResultDetailView("Backtracks", "3")),
        ResultDetailFormatter.fromMetrics("zip", Map.of("hints", 0, "mistakes", 0,
            "backtracks", 3, "assistanceStatus", "unassisted")));
    assertEquals(List.of(), ResultDetailFormatter.fromMetrics("nyt-mini-crossword",
        Map.of("completionTimeSeconds", 59)));
  }
}
