package com.dailygames;

import java.time.LocalDate;
import java.util.*;

final class SlipOutPuzzle {
  record Block(String id, int x, int y, int length, String axis) {
    int coordinate() { return axis.equals("H") ? x : y; }
    Block at(int position) { return axis.equals("H") ? new Block(id, position, y, length, axis)
        : new Block(id, x, position, length, axis); }
  }
  record Move(String id, Integer to) {}
  record Puzzle(String id, LocalDate date, long number, List<Block> blocks, int optimalMoves) {}
  record Replay(List<Block> blocks, boolean completed, int moves) {}

  static final String VERSION = "v1";
  private static final LocalDate FIRST_DAY = LocalDate.of(2026, 11, 13);
  private static final String[] TEMPLATES = {
      "X1,2,2H A3,2,2V B3,4,3H C2,0,2H D0,0,2V E3,1,2H F4,2,2V G1,5,2H H2,3,2V I5,0,3V",
      "X0,2,2H A3,4,3H B5,2,2V C3,5,3H D5,0,2V E1,4,2H F3,3,2H G0,1,2H H0,4,2V I3,1,2V J0,0,2H K0,3,2H L2,1,2V",
      "X0,2,2H A2,5,3H B3,0,2H C5,0,2V D1,3,2H E2,0,3V F5,2,2V G2,4,3H H3,1,2H I1,4,2V J3,2,2V",
      "X0,2,2H A1,4,2V B3,1,2V C2,4,3H D0,0,2V E1,3,2H F4,2,2V G0,4,2V H5,4,2V I5,0,2V J2,0,3V",
      "X1,2,2H A5,1,2V B1,0,3H C4,2,2V D2,4,3H E0,4,2V F4,0,2H G3,2,2V H0,2,2V I3,1,2H J1,4,2V",
      "X0,2,2H A0,4,2H B4,2,2V C2,0,3V D3,1,2H E0,3,2H F5,1,2V G2,5,3H H5,3,2V I0,5,2H J2,4,3H K3,2,2V",
      "X1,2,2H A0,1,2V B5,3,2V C4,1,3V D1,0,3H E3,3,2V F2,3,3V G4,0,2H H3,5,2H I0,3,2V J1,4,2V K1,1,2H L3,1,2V",
      "X1,2,2H A3,5,2H B5,2,2V C1,1,2H D0,2,3V E3,0,2V F4,4,2H G2,3,2V H4,1,2H I1,5,2H J3,3,2V K4,0,2H L0,0,2H"
  };
  private static final int[] OPTIMAL = { 8, 9, 12, 11, 9, 13, 12, 9 };

  static Puzzle forDate(LocalDate date) {
    int index = Math.floorMod(date.toEpochDay(), TEMPLATES.length);
    var blocks = new ArrayList<Block>();
    for (String part : TEMPLATES[index].split(" ")) {
      var match = java.util.regex.Pattern.compile("([A-Z])(\\d),(\\d),([23])([HV])").matcher(part);
      if (!match.matches()) throw new IllegalStateException("Invalid SlipOut template");
      blocks.add(new Block(match.group(1), Integer.parseInt(match.group(2)), Integer.parseInt(match.group(3)),
          Integer.parseInt(match.group(4)), match.group(5)));
    }
    return new Puzzle("slipout-" + VERSION + "-" + date, date,
        Math.max(1, date.toEpochDay() - FIRST_DAY.toEpochDay() + 1), List.copyOf(blocks), OPTIMAL[index]);
  }

  static Replay replay(Puzzle puzzle, List<Move> moves) {
    if (moves == null || moves.size() > 500) throw invalid();
    List<Block> blocks = puzzle.blocks();
    boolean completed = false;
    for (Move move : moves) {
      if (completed || move == null || move.id() == null || move.to() == null) throw invalid();
      Block block = blocks.stream().filter(value -> value.id().equals(move.id())).findFirst().orElseThrow(SlipOutPuzzle::invalid);
      int origin = block.coordinate();
      int to = move.to();
      if (to == origin || to < 0 || to > 6 - block.length() && !(block.id().equals("X") && to == 6)) throw invalid();
      int last = to == 6 ? 6 - block.length() : to;
      for (int position = Math.min(origin, last); position <= Math.max(origin, last); position++) {
        Block shifted = block.at(position);
        for (Block other : blocks) if (!other.id().equals(block.id()) && overlaps(shifted, other)) throw invalid();
      }
      if (to == 6) {
        if (!block.id().equals("X") || !block.axis().equals("H") || block.y() != 2) throw invalid();
        completed = true;
      }
      final String id = move.id();
      blocks = blocks.stream().map(value -> value.id().equals(id) ? value.at(to) : value).toList();
    }
    return new Replay(blocks, completed, moves.size());
  }

  private static boolean overlaps(Block a, Block b) {
    int aw = a.axis().equals("H") ? a.length() : 1;
    int ah = a.axis().equals("V") ? a.length() : 1;
    int bw = b.axis().equals("H") ? b.length() : 1;
    int bh = b.axis().equals("V") ? b.length() : 1;
    return a.x() < b.x() + bw && a.x() + aw > b.x() && a.y() < b.y() + bh && a.y() + ah > b.y();
  }
  private static ApiException invalid() { return new ApiException(400, "INVALID_MOVE", "The move log is not valid for today's board."); }
  private SlipOutPuzzle() {}
}
