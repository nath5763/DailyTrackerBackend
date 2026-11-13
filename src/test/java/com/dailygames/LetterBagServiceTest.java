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

  @Test void scoresLengthAndEveryRareLetterOccurrence() {
    assertEquals(new LetterBagScoring.Breakdown(7, 0, 7), LetterBagScoring.score("CAT"));
    assertEquals(8, LetterBagScoring.score("JAM").total());
    assertEquals(9, LetterBagScoring.score("ZIP").total());
    assertEquals(new LetterBagScoring.Breakdown(11, 5, 16), LetterBagScoring.score("JAZZ"));
    assertEquals(new LetterBagScoring.Breakdown(11, 4, 15), LetterBagScoring.score("QUIZ"));
    assertEquals(16, LetterBagScoring.score("PLANT").total());
  }
}
