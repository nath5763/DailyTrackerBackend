package com.dailygames;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SlipOutPuzzleTest {
  private static final String[] SOLUTIONS = {
      "X0 C3 H0 B0 A3 F3 I3 X6",
      "I0 K1 L0 X1 H2 E0 A2 B3 X6",
      "A3 D0 G3 E3 B1 H0 J0 X3 E1 G2 F3 X6",
      "B0 E0 F0 H2 C3 J3 X3 J1 C2 H3 X6",
      "A3 B0 I1 G0 X2 J2 D1 C3 X6",
      "F0 G3 H2 J3 C3 D0 B0 K0 X3 C1 J2 H3 X6",
      "A0 X0 F2 J3 H0 C3 F3 X1 A1 D0 L0 X6",
      "X3 C0 G0 X1 J2 F1 B3 J3 X6"
  };
  static List<SlipOutPuzzle.Move> solutionForDate(LocalDate date) {
    return Arrays.stream(SOLUTIONS[Math.floorMod(date.toEpochDay(), SOLUTIONS.length)].split(" "))
        .map(value -> new SlipOutPuzzle.Move(value.substring(0, 1), Integer.parseInt(value.substring(1))))
        .toList();
  }
  private final SlipOutPuzzle.Puzzle simple = new SlipOutPuzzle.Puzzle("test", LocalDate.of(2026, 11, 13), 1,
      List.of(new SlipOutPuzzle.Block("X", 0, 2, 2, "H"),
          new SlipOutPuzzle.Block("A", 3, 1, 2, "V")), 2);

  @Test void dailyPuzzleIsStableAndVersioned() {
    var first = SlipOutPuzzle.forDate(LocalDate.of(2026, 11, 13));
    assertEquals(first, SlipOutPuzzle.forDate(first.date()));
    assertEquals("slipout-v1-2026-11-13", first.id());
    assertTrue(first.optimalMoves() >= 8 && first.optimalMoves() <= 20);
  }

  @Test void serverCanReplayACompletePathForEveryTemplate() {
    for (int index = 0; index < SOLUTIONS.length; index++) {
      var puzzle = SlipOutPuzzle.forDate(LocalDate.ofEpochDay(index));
      var replay = SlipOutPuzzle.replay(puzzle, solutionForDate(puzzle.date()));
      assertTrue(replay.completed(), puzzle.id());
      assertEquals(puzzle.optimalMoves(), replay.moves(), puzzle.id());
    }
  }

  @Test void serverReplaysSlidesAndExit() {
    var result = SlipOutPuzzle.replay(simple, List.of(
        new SlipOutPuzzle.Move("A", 3), new SlipOutPuzzle.Move("X", 6)));
    assertTrue(result.completed());
    assertEquals(2, result.moves());
  }

  @Test void serverRejectsCollisionsNoOpsAndFalseWins() {
    assertThrows(ApiException.class, () -> SlipOutPuzzle.replay(simple, List.of(new SlipOutPuzzle.Move("X", 6))));
    assertThrows(ApiException.class, () -> SlipOutPuzzle.replay(simple, List.of(new SlipOutPuzzle.Move("A", 1))));
    assertThrows(ApiException.class, () -> SlipOutPuzzle.replay(simple, List.of(new SlipOutPuzzle.Move("X", 5))));
    assertThrows(ApiException.class, () -> SlipOutPuzzle.replay(simple, List.of(new SlipOutPuzzle.Move("A", 6))));
  }
}
