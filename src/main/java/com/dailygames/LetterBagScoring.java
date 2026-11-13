package com.dailygames;

import java.util.Locale;

final class LetterBagScoring {
  record Breakdown(int base, int bonus, int total) {}

  static int bonus(char letter) {
    return switch (Character.toUpperCase(letter)) {
      case 'Q', 'Z' -> 2;
      case 'J', 'X', 'K', 'V' -> 1;
      default -> 0;
    };
  }

  static Breakdown score(String word) {
    String letters = word.toUpperCase(Locale.ROOT);
    int base = letters.isEmpty() ? 0 : 1 + letters.length() * (letters.length() + 1) / 2;
    int bonus = 0;
    for (int i = 0; i < letters.length(); i++) bonus += bonus(letters.charAt(i));
    return new Breakdown(base, bonus, base + bonus);
  }

  private LetterBagScoring() {}
}
