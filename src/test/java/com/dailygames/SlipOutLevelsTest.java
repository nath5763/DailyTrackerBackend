package com.dailygames;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SlipOutLevelsTest {
  private static final java.util.Set<Integer> RETAINED = java.util.Set.of(1, 2, 3, 4, 6, 7, 8, 9, 10,
      11, 13, 14, 15, 16, 17, 18, 19, 31, 32, 33, 34, 61, 62, 63, 64, 65, 68, 76, 79);
  private SlipOutLevels.Level simple(String side, int cell, int x, int y, String axis) {
    return new SlipOutLevels.Level("test", 1, "easy", 2, axis,
        new SlipOutLevels.Exit(side, cell), List.of(new SlipOutLevels.Cell(2, 2)),
        List.of(new SlipOutLevels.Block("X", x, y, 1, 1, "B")));
  }
  @Test void catalogHasTutorialAndNinetyLevelsInThreeTiers() {
    assertEquals(91, SlipOutLevels.ALL.size());
    var tutorial = SlipOutLevels.level(0);
    assertEquals("tutorial", tutorial.difficulty());
    assertEquals(1, tutorial.blocks().size());
    assertEquals(2, tutorial.optimalMoves());
    assertTrue(SlipOutLevels.replay(tutorial, solution(0)).completed());
    for (int index = 0; index < 15; index++) {
      var level = SlipOutLevels.level(index + 1);
      assertEquals(index + 1, level.number());
      assertEquals("slipout-level-v%d-%02d".formatted(RETAINED.contains(index + 1) ? 3 : 6, index + 1), level.id());
      assertEquals(index < 5 ? "easy" : index < 10 ? "medium" : "hard", level.difficulty());
      assertTrue(level.optimalMoves() >= (index < 5 ? 6 : index < 10 ? 13 : 20));
      if (index < 10) assertTrue(level.optimalMoves() <= (index < 5 ? 12 : 19));
      assertFalse(level.switches().isEmpty());
    }
    for (String tier : List.of("easy", "medium", "hard")) {
      var ordered = SlipOutLevels.ALL.stream().filter(level -> level.difficulty().equals(tier))
          .sorted(java.util.Comparator.comparingInt(SlipOutLevels.Level::optimalMoves)
              .thenComparingInt(SlipOutLevels.Level::number)).toList();
      assertEquals(30, ordered.size());
      assertEquals(0, SlipOutLevels.predecessor(ordered.getFirst().number()));
      for (int index = 1; index < 30; index++)
        assertEquals(ordered.get(index - 1).number(), SlipOutLevels.predecessor(ordered.get(index).number()));
    }
    for (int number = 61; number <= 90; number++) {
      var level = SlipOutLevels.level(number);
      assertEquals("slipout-level-v%d-%02d".formatted(RETAINED.contains(number) ? 5 : 6, number), level.id());
      String tier = number <= 70 ? "easy" : number <= 80 ? "medium" : "hard";
      assertEquals(tier, level.difficulty());
      assertTrue(level.optimalMoves() >= (tier.equals("easy") ? 6 : tier.equals("medium") ? 13 : 20));
      if (!tier.equals("hard")) assertTrue(level.optimalMoves() <= (tier.equals("easy") ? 12 : 19));
    }
  }
  @Test void switchEntryCrossingAndReentryAgreeWithClient() {
    var level = simple("top", 2, 0, 2, "H");
    assertThrows(ApiException.class, () -> SlipOutLevels.replay(level,
        List.of(new SlipOutLevels.Move("X", "V", 1))));
    assertThrows(ApiException.class, () -> SlipOutLevels.replay(level, List.of(
        new SlipOutLevels.Move("X", "H", 4), new SlipOutLevels.Move("X", "V", 1))));
    var replay = SlipOutLevels.replay(level, List.of(
        new SlipOutLevels.Move("X", "H", 4), new SlipOutLevels.Move("X", "H", 2),
        new SlipOutLevels.Move("X", "V", 3), new SlipOutLevels.Move("X", "V", 2),
        new SlipOutLevels.Move("X", "H", 3)));
    assertFalse(replay.completed());
  }
  @Test void exitsWorkOnAllFourSidesAndRejectWrongOpening() {
    assertTrue(SlipOutLevels.replay(simple("left", 0, 3, 0, "H"),
        List.of(new SlipOutLevels.Move("X", "H", -1))).completed());
    assertTrue(SlipOutLevels.replay(simple("right", 1, 1, 1, "H"),
        List.of(new SlipOutLevels.Move("X", "H", 6))).completed());
    assertTrue(SlipOutLevels.replay(simple("top", 3, 3, 4, "V"),
        List.of(new SlipOutLevels.Move("X", "V", -1))).completed());
    assertTrue(SlipOutLevels.replay(simple("bottom", 4, 4, 1, "V"),
        List.of(new SlipOutLevels.Move("X", "V", 6))).completed());
    assertThrows(ApiException.class, () -> SlipOutLevels.replay(simple("right", 2, 0, 1, "H"),
        List.of(new SlipOutLevels.Move("X", "H", 6))));
  }
  @Test void twoCellTargetTogglesOnceWhenNewCellEntersSwitches() {
    var level = new SlipOutLevels.Level("test", 1, "easy", 2, "H",
        new SlipOutLevels.Exit("right", 2),
        List.of(new SlipOutLevels.Cell(2, 2), new SlipOutLevels.Cell(3, 2)),
        List.of(new SlipOutLevels.Block("X", 1, 2, 2, 1, "B")));
    assertThrows(ApiException.class, () -> SlipOutLevels.replay(level, List.of(
        new SlipOutLevels.Move("X", "H", 2), new SlipOutLevels.Move("X", "H", 3))));
    assertFalse(SlipOutLevels.replay(level, List.of(new SlipOutLevels.Move("X", "H", 2))).completed());
  }
  static List<SlipOutLevels.Move> firstSolution() {
    return solution(1);
  }
  static List<SlipOutLevels.Move> solution(int number) {
    try (var stream = SlipOutLevelsTest.class.getResourceAsStream("/slipout-level-solutions.json")) {
      Map<?, ?> all = new ObjectMapper().readValue(new String(stream.readAllBytes(), StandardCharsets.UTF_8), Map.class);
      List<SlipOutLevels.Move> result = new ArrayList<>();
      for (Object value : (List<?>) all.get(String.valueOf(number))) {
        Map<?, ?> move = (Map<?, ?>) value;
        result.add(new SlipOutLevels.Move((String) move.get("id"), (String) move.get("axis"),
            ((Number) move.get("to")).intValue()));
      }
      return result;
    } catch (Exception error) { throw new IllegalStateException("Invalid test solution", error); }
  }
  @Test void everyCuratedLevelReplaysAtItsVerifiedOptimum() {
    for (int number = 1; number <= 90; number++) {
      var replay = SlipOutLevels.replay(SlipOutLevels.level(number), solution(number));
      assertTrue(replay.completed(), "Level " + number);
      assertEquals(SlipOutLevels.level(number).optimalMoves(), replay.moves());
    }
  }
}
