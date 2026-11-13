package com.dailygames;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SlipOutLevelsTest {
  @Test void catalogHasFifteenVersionedLevelsInThreeTiers() {
    assertEquals(15, SlipOutLevels.ALL.size());
    for (int index = 0; index < 15; index++) {
      var level = SlipOutLevels.ALL.get(index);
      assertEquals(index + 1, level.number());
      assertEquals("slipout-level-v1-%02d".formatted(index + 1), level.id());
      assertEquals(index < 5 ? "easy" : index < 10 ? "medium" : "hard", level.difficulty());
      assertTrue(level.optimalMoves() >= (index < 5 ? 6 : index < 10 ? 13 : 20));
      if (index < 10) assertTrue(level.optimalMoves() <= (index < 5 ? 12 : 19));
    }
  }
  @Test void rectangularTargetMustAlignWithExit() {
    var level = new SlipOutLevels.Level("test", 1, "easy", 2,
        List.of(new SlipOutLevels.Block("X", 0, 1, 2, 2, "B")));
    assertThrows(ApiException.class, () -> SlipOutLevels.replay(level,
        List.of(new SlipOutLevels.Move("X", "H", 6))));
    var solved = SlipOutLevels.replay(level, List.of(
        new SlipOutLevels.Move("X", "V", 2), new SlipOutLevels.Move("X", "H", 6)));
    assertTrue(solved.completed());
    assertEquals(2, solved.moves());
  }
  static List<SlipOutLevels.Move> firstSolution() {
    return List.of(new SlipOutLevels.Move("X", "V", 3), new SlipOutLevels.Move("A", "V", 4),
        new SlipOutLevels.Move("E", "V", 4), new SlipOutLevels.Move("H", "V", 4),
        new SlipOutLevels.Move("X", "H", 4), new SlipOutLevels.Move("X", "V", 2),
        new SlipOutLevels.Move("X", "H", 6));
  }
  @Test void firstCuratedLevelReplaysInSevenMoves() {
    var replay = SlipOutLevels.replay(SlipOutLevels.level(1), firstSolution());
    assertTrue(replay.completed());
    assertEquals(7, replay.moves());
  }
}
