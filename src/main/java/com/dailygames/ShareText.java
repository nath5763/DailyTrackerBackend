package com.dailygames;

import java.text.Normalizer;
import java.util.Arrays;

final class ShareText {
  private ShareText() {}

  static String[] lines(String raw) {
    String text = raw == null ? "" : Normalizer.normalize(raw, Normalizer.Form.NFC)
        .replace("\uFE0F", "").replace("\uFE0E", "").strip();
    if (text.length() > 10_000) throw ShareTextParserSupport.invalid("Result text is too long.");
    return text.isEmpty() ? new String[0] : Arrays.stream(text.split("\\R"))
        .map(String::strip).filter(line -> !line.isEmpty()).toArray(String[]::new);
  }

  static int keycapDigit(String value) {
    String normalized = value.replace("\uFE0F", "").replace("\u20E3", "");
    if (normalized.length() != 1 || normalized.charAt(0) < '0' || normalized.charAt(0) > '9')
      throw ShareTextParserSupport.invalid("Invalid keycap digit.");
    return normalized.charAt(0) - '0';
  }
}
