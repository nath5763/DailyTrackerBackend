package com.dailygames;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LetterBagServiceTest {
  @Test void matchesOriginalPuzzle() {
    assertEquals("GRNQSKODOTNUDMMAEOQJAETTIOSTBA", LetterBagService.puzzle(LocalDate.of(2026, 11, 11))
        .stream().map(LetterBagService.Tile::letter).collect(Collectors.joining()));
  }
}
