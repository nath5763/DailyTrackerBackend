package com.dailygames;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DecimalPresentation {
  private static final Pattern SIMPLE_DISPLAY = Pattern.compile("^([^0-9]*)([+-]?[0-9]+(?:\\.[0-9]+)?)([^0-9]*)$");
  private DecimalPresentation() {}

  static String format(BigDecimal value) {
    return format(value, 2);
  }

  static String format(BigDecimal value, int maximumFractionDigits) {
    int scale = Math.max(0, Math.min(2, maximumFractionDigits));
    return value.setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
  }

  static String formatDisplay(String value) {
    Matcher match = SIMPLE_DISPLAY.matcher(value.strip());
    if (!match.matches()) return value;
    try {
      return match.group(1) + format(new BigDecimal(match.group(2))) + match.group(3);
    } catch (NumberFormatException ignored) {
      return value;
    }
  }
}
