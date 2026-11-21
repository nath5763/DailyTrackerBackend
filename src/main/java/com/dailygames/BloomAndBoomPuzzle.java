package com.dailygames;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

final class BloomAndBoomPuzzle {
  static final String VERSION = "v1";
  record Position(int row, int column) {}
  record Puzzle(String id, LocalDate date, int size, List<Integer> rowHazards,
                List<Integer> columnHazards, boolean[][] hazards) {
    boolean hazard(Position position) { return hazards[position.row()][position.column()]; }
    int safeCells() { return size * size - rowHazards.stream().mapToInt(Integer::intValue).sum(); }
  }

  static Puzzle forDate(LocalDate date) {
    int size = switch (date.getDayOfWeek()) {
      case MONDAY, TUESDAY -> 4;
      case FRIDAY, SATURDAY -> 6;
      default -> 5;
    };
    SplittableRandom random = new SplittableRandom(seed(date));
    int base = switch (size) { case 4 -> 4; case 5 -> 6; default -> 9; };
    int hazards = base + random.nextInt(size == 4 ? 2 : 3);
    for (int attempt = 0; attempt < 200_000; attempt++) {
      boolean[][] board = candidate(size, hazards, random);
      if (validLineCounts(board)) return puzzle(date, board);
    }
    throw new IllegalStateException("Could not generate Bloom & Boom puzzle for " + date);
  }

  private static Puzzle puzzle(LocalDate date, boolean[][] hazards) {
    int size = hazards.length;
    List<Integer> rows = new ArrayList<>(), columns = new ArrayList<>();
    for (int row = 0; row < size; row++) {
      int count = 0;
      for (int column = 0; column < size; column++) if (hazards[row][column]) count++;
      rows.add(count);
    }
    for (int column = 0; column < size; column++) {
      int count = 0;
      for (int row = 0; row < size; row++) if (hazards[row][column]) count++;
      columns.add(count);
    }
    return new Puzzle("bloom-and-boom-" + VERSION + "-" + date, date, size,
        List.copyOf(rows), List.copyOf(columns), copy(hazards));
  }

  private static boolean[][] candidate(int size, int hazardCount, SplittableRandom random) {
    List<Integer> cells = new ArrayList<>();
    for (int index = 0; index < size * size; index++) cells.add(index);
    for (int index = cells.size() - 1; index > 0; index--) {
      int other = random.nextInt(index + 1);
      int value = cells.get(index); cells.set(index, cells.get(other)); cells.set(other, value);
    }
    boolean[][] result = new boolean[size][size];
    for (int index = 0; index < hazardCount; index++) {
      int cell = cells.get(index); result[cell / size][cell % size] = true;
    }
    return result;
  }

  private static boolean validLineCounts(boolean[][] hazards) {
    int size = hazards.length;
    for (int line = 0; line < size; line++) {
      int row = 0, column = 0;
      for (int offset = 0; offset < size; offset++) {
        if (hazards[line][offset]) row++;
        if (hazards[offset][line]) column++;
      }
      if (row == 0 || row == size || column == 0 || column == size) return false;
    }
    return true;
  }

  private static boolean[][] copy(boolean[][] source) {
    boolean[][] result = new boolean[source.length][];
    for (int row = 0; row < source.length; row++) result[row] = source[row].clone();
    return result;
  }
  private static long seed(LocalDate date) {
    try {
      byte[] bytes = MessageDigest.getInstance("SHA-256")
          .digest(("bloom-and-boom:" + VERSION + ":" + date).getBytes(StandardCharsets.UTF_8));
      long value = 0;
      for (int index = 0; index < Long.BYTES; index++) value = value << 8 | bytes[index] & 0xffL;
      return value;
    } catch (Exception exception) { throw new IllegalStateException(exception); }
  }
  private BloomAndBoomPuzzle() {}
}
