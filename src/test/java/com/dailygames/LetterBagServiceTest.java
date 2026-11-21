package com.dailygames;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.Map;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LetterBagServiceTest {
  @Test void matchesCanonicalPuzzle() {
    assertEquals("OVRERBTRTAEITRIESSDPFAETJTOREI", LetterBagService.puzzle(LocalDate.of(2026, 11, 11))
        .stream().map(LetterBagService.Tile::letter).collect(Collectors.joining()));
    assertEquals("EPANKTUPOTANCOSTELONTGIUNAFVIR", LetterBagService.puzzle(LocalDate.of(2026, 11, 12))
        .stream().map(LetterBagService.Tile::letter).collect(Collectors.joining()));
  }

  @Test void usesExactCategoryProbabilitiesAndLetterWeights() {
    assertEquals(java.util.List.of(5, 6, 6, 7), LetterBagService.RACK_VOWEL_TARGETS);
    assertEquals(Map.of('A', 8L, 'E', 11L, 'I', 6L, 'O', 7L, 'U', 3L), counts(LetterBagService.VOWEL_POOL));
    assertEquals(Map.ofEntries(
        Map.entry('B', 1L), Map.entry('C', 1L), Map.entry('D', 1L), Map.entry('F', 1L),
        Map.entry('G', 1L), Map.entry('H', 1L), Map.entry('J', 1L), Map.entry('K', 1L),
        Map.entry('L', 1L), Map.entry('M', 1L), Map.entry('N', 2L), Map.entry('P', 1L),
        Map.entry('Q', 1L), Map.entry('R', 4L), Map.entry('S', 4L), Map.entry('T', 6L),
        Map.entry('V', 1L), Map.entry('W', 1L), Map.entry('Y', 1L), Map.entry('Z', 1L)),
        counts(LetterBagService.CONSONANT_POOL));
    assertFalse(LetterBagService.CONSONANT_POOL.contains("X"));
  }

  @Test void keepsThirtyTilesAndTwelveVowelsWithComplementaryHalves() {
    for (int day = 1; day <= 365; day++) {
      var tiles = LetterBagService.puzzle(LocalDate.ofYearDay(2026, day));
      long rackVowels = tiles.subList(0, 15).stream().filter(tile -> "AEIOU".contains(tile.letter())).count();
      long bagVowels = tiles.subList(15, 30).stream().filter(tile -> "AEIOU".contains(tile.letter())).count();
      assertEquals(30, tiles.size());
      assertEquals(12, rackVowels + bagVowels);
      assertEquals(12 - rackVowels, bagVowels);
      org.junit.jupiter.api.Assertions.assertTrue(rackVowels >= 5 && rackVowels <= 7);
    }
  }

  private static Map<Character, Long> counts(String pool) {
    return pool.chars().mapToObj(value -> (char) value)
        .collect(Collectors.groupingBy(value -> value, Collectors.counting()));
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
