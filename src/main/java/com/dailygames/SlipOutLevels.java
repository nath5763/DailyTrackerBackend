package com.dailygames;

import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class SlipOutLevels {
  record Block(String id, int x, int y, int width, int height, String axis) {
    Block at(String direction, int to) { return direction.equals("H")
        ? new Block(id, to, y, width, height, axis) : new Block(id, x, to, width, height, axis); }
  }
  record Level(String id, int number, String difficulty, int optimalMoves, List<Block> blocks) {}
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
        levels.add(new Level((String) row.get("id"), number(row, "number"), (String) row.get("difficulty"),
            number(row, "optimalMoves"), List.copyOf(blocks)));
      }
      if (levels.size() != 15) throw new IllegalStateException("Expected 15 levels");
      return List.copyOf(levels);
    } catch (Exception error) { throw new IllegalStateException("Invalid level catalog", error); }
  }
  private static int number(Map<?, ?> value, String key) { return ((Number) value.get(key)).intValue(); }
  static Level level(int number) {
    if (number < 1 || number > ALL.size()) throw new ApiException(404, "LEVEL_NOT_FOUND", "Unknown SlipOut level.");
    return ALL.get(number - 1);
  }
  static Replay replay(Level level, List<Move> moves) {
    if (moves == null || moves.size() > 500) throw invalid();
    List<Block> blocks = level.blocks();
    boolean completed = false;
    for (Move move : moves) {
      if (completed || move == null || move.id() == null || move.axis() == null || move.to() == null ||
          !Set.of("H", "V").contains(move.axis())) throw invalid();
      Block block = blocks.stream().filter(b -> b.id().equals(move.id())).findFirst().orElseThrow(SlipOutLevels::invalid);
      if (!block.axis().equals("B") && !block.axis().equals(move.axis())) throw invalid();
      int origin = move.axis().equals("H") ? block.x() : block.y();
      int to = move.to();
      boolean exit = block.id().equals("X") && move.axis().equals("H") && block.y() == 2 && to == 6;
      int end = exit ? 6 - block.width() : to;
      if (to == origin || to < 0 || end < 0 || end + (move.axis().equals("H") ? block.width() : block.height()) > 6 ||
          to == 6 && !exit) throw invalid();
      int direction = Integer.compare(end, origin);
      for (int position = origin + direction; direction != 0 && (direction > 0 ? position <= end : position >= end); position += direction) {
        Block shifted = block.at(move.axis(), position);
        for (Block other : blocks) if (!other.id().equals(block.id()) && overlaps(shifted, other)) throw invalid();
      }
      completed = exit;
      String id = block.id();
      blocks = blocks.stream().map(b -> b.id().equals(id) ? b.at(move.axis(), to) : b).toList();
    }
    return new Replay(completed, moves.size());
  }
  private static boolean overlaps(Block a, Block b) {
    return a.x() < b.x() + b.width() && a.x() + a.width() > b.x() &&
        a.y() < b.y() + b.height() && a.y() + a.height() > b.y();
  }
  private static ApiException invalid() { return new ApiException(400, "INVALID_MOVE", "Invalid level move log."); }
  private SlipOutLevels() {}
}
