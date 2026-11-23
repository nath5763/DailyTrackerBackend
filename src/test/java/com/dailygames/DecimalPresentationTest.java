package com.dailygames;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DecimalPresentationTest {
  @Test void usesAtMostTwoFractionalDigitsWithoutChangingTheValueBeingFormatted() {
    assertEquals("1", DecimalPresentation.format(new BigDecimal("1")));
    assertEquals("1.5", DecimalPresentation.format(new BigDecimal("1.50")));
    assertEquals("1.23", DecimalPresentation.format(new BigDecimal("1.234")));
    assertEquals("-1.24", DecimalPresentation.format(new BigDecimal("-1.235")));
    assertEquals("2", DecimalPresentation.format(new BigDecimal("1.6"), 0));
    assertEquals("1.23×", DecimalPresentation.formatDisplay("1.234×"));
    assertEquals("3/4 · 32", DecimalPresentation.formatDisplay("3/4 · 32"));
    assertEquals("1:22", DecimalPresentation.formatDisplay("1:22"));
  }
}
