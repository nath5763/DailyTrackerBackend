package com.dailygames;

import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class SlipOutLevels {
  record Block(String id, int x, int y, int width, int height, String axis) {
    Block at(String direction, int to) { return direction.equals("H")
        ? new Block(id, to, y, width, height, axis) : new Block(id, x, to, width, height, axis); }
  }
  record Cell(int x, int y) {}
  record Exit(String side, int cell) {}
  record Level(String id, int number, String difficulty, int optimalMoves, String startAxis,
               Exit exit, List<Cell> switches, List<Block> blocks) {}
  record Move(String id, String axis, Integer to) {}
  record Replay(boolean completed, int moves) {}
  private static final ObjectMapper JSON = new ObjectMapper();
  static final List<Level> ALL = load();

  private static List<Level> load() {
    try (var stream = SlipOutLevels.class.getResourceAsStream("/slipout-levels.json")) {
      if (stream == null) throw new IllegalStateException("Missing level catalog");
      List<?> rows = JSON.readValue(new String(stream.readAllBytes(), StandardCharsets.UTF_8), List.class);
      List<Level> levels = new ArrayList<>();
      for (Object item : rows) {
        Map<?, ?> row = (Map<?, ?>) item;
        List<Block> blocks = new ArrayList<>();
        for (Object value : (List<?>) row.get("blocks")) {
          Map<?, ?> b = (Map<?, ?>) value;
          blocks.add(new Block((String) b.get("id"), number(b, "x"), number(b, "y"),
              number(b, "width"), number(b, "height"), (String) b.get("axis")));
        }
        Map<?, ?> opening = (Map<?, ?>) row.get("exit");
        List<Cell> switches = new ArrayList<>();
        for (Object value : (List<?>) row.get("switches")) {
          Map<?, ?> cell = (Map<?, ?>) value;
          switches.add(new Cell(number(cell, "x"), number(cell, "y")));
        }
        levels.add(new Level((String) row.get("id"), number(row, "number"), (String) row.get("difficulty"),
            number(row, "optimalMoves"), (String) row.get("startAxis"),
            new Exit((String) opening.get("side"), number(opening, "cell")), List.copyOf(switches), List.copyOf(blocks)));
      }
      if (levels.size() != 61) throw new IllegalStateException("Expected tutorial and 60 levels");
      return List.copyOf(levels);
    } catch (Exception error) { throw new IllegalStateException("Invalid level catalog", error); }
  }
  private static int number(Map<?, ?> value, String key) { return ((Number) value.get(key)).intValue(); }
  static Level level(int number) {
    if (number < 0 || number >= ALL.size()) throw new ApiException(404, "LEVEL_NOT_FOUND", "Unknown SlipOut level.");
    return ALL.get(number);
  }
  static int predecessor(int number) {
    Level current = level(number);
    if (number == 0) return -1;
    List<Level> tier = ALL.stream().filter(candidate -> candidate.difficulty().equals(current.difficulty()))
        .sorted(Comparator.comparingInt(Level::optimalMoves).thenComparingInt(Level::number)).toList();
    int position = tier.indexOf(current);
    return position == 0 ? 0 : tier.get(position - 1).number();
  }
  static Replay replay(Level level, List<Move> moves) {
    if (moves == null || moves.size() > 500) throw invalid();
    List<Block> blocks = level.blocks();
    String activeAxis = level.startAxis();
    boolean completed = false;
    for (Move move : moves) {
      if (completed || move == null || move.id() == null || move.axis() == null || move.to() == null ||
          !Set.of("H", "V").contains(move.axis())) throw invalid();
      Block block = blocks.stream().filter(b -> b.id().equals(move.id())).findFirst().orElseThrow(SlipOutLevels::invalid);
      if (block.id().equals("X") ? !activeAxis.equals(move.axis()) : !block.axis().equals(move.axis())) throw invalid();
      int origin = move.axis().equals("H") ? block.x() : block.y();
      int to = move.to();
      boolean leftward = to == -1;
      boolean outward = leftward || to == 6;
      boolean exit = outward && canExit(level, block, move.axis(), leftward);
      int end = exit ? leftward ? 0 : 6 - (move.axis().equals("H") ? block.width() : block.height()) : to;
      if (to == origin || outward && !exit || end < 0 ||
          end + (move.axis().equals("H") ? block.width() : block.height()) > 6) throw invalid();
      int direction = Integer.compare(end, origin);
      for (int position = origin + direction; direction != 0 && (direction > 0 ? position <= end : position >= end); position += direction) {
        Block shifted = block.at(move.axis(), position);
        for (Block other : blocks) if (!other.id().equals(block.id()) && overlaps(shifted, other)) throw invalid();
      }
      completed = exit;
      String id = block.id();
      Block destination = block.at(move.axis(), to);
      if (id.equals("X") && !exit && level.switches().stream().anyMatch(cell ->
          covers(destination, cell) && !covers(block, cell)))
        activeAxis = activeAxis.equals("H") ? "V" : "H";
      blocks = blocks.stream().map(b -> b.id().equals(id) ? b.at(move.axis(), to) : b).toList();
    }
    return new Replay(completed, moves.size());
  }
  private static boolean overlaps(Block a, Block b) {
    return a.x() < b.x() + b.width() && a.x() + a.width() > b.x() &&
        a.y() < b.y() + b.height() && a.y() + a.height() > b.y();
  }
  private static boolean covers(Block block, Cell cell) {
    return cell.x() >= block.x() && cell.x() < block.x() + block.width() &&
        cell.y() >= block.y() && cell.y() < block.y() + block.height();
  }
  private static boolean canExit(Level level, Block block, String axis, boolean leftward) {
    if (!block.id().equals("X")) return false;
    String side = level.exit().side();
    if (!side.equals(axis.equals("H") ? leftward ? "left" : "right" : leftward ? "top" : "bottom")) return false;
    return axis.equals("H") ? block.height() == 1 && block.y() == level.exit().cell()
        : block.width() == 1 && block.x() == level.exit().cell();
  }
  private static ApiException invalid() { return new ApiException(400, "INVALID_MOVE", "Invalid level move log."); }
  private SlipOutLevels() {}
}
