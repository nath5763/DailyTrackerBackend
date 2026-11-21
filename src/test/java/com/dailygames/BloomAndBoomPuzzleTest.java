package com.dailygames;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class BloomAndBoomPuzzleTest {
  @Test void generatesDeterministicScheduledSolvableGardens() {
    LocalDate start = LocalDate.of(2026, 1, 1);
    for (int day = 0; day < 366; day++) {
      LocalDate date = start.plusDays(day);
      var puzzle = BloomAndBoomPuzzle.forDate(date);
      var again = BloomAndBoomPuzzle.forDate(date);
      assertEquals(puzzle.id(), again.id());
      assertEquals(puzzle.rowHazards(), again.rowHazards());
      assertArrayEquals(puzzle.hazards(), again.hazards());
      int expectedSize = switch (date.getDayOfWeek()) {
        case MONDAY, TUESDAY -> 4; case FRIDAY, SATURDAY -> 6; default -> 5;
      };
      assertEquals(expectedSize, puzzle.size());
      assertEquals(puzzle.rowHazards().stream().mapToInt(Integer::intValue).sum(),
          puzzle.columnHazards().stream().mapToInt(Integer::intValue).sum());
      int allowance = date.getDayOfWeek() == DayOfWeek.FRIDAY ? 1 : 0;
      assertTrue(BloomAndBoomPuzzle.requiredGuesses(puzzle.hazards(), allowance) <= allowance, date.toString());
    }
  }

  @Test void formatsHundredthPrecisionTimes() {
    assertEquals("0:00.00", BloomAndBoomService.display(0));
    assertEquals("1:02.34", BloomAndBoomService.display(62_349));
  }
}
