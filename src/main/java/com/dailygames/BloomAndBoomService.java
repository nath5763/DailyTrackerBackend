package com.dailygames;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service
class BloomAndBoomService {
  record Owner(UUID userId, String guestTokenHash) {
    String lockKey() { return userId == null ? "guest:" + guestTokenHash : "user:" + userId; }
  }
  record Cell(int row, int column, String kind) {}
  record Today(String puzzleId, LocalDate puzzleDate, int size, List<Integer> rowHazards,
               List<Integer> columnHazards, String status, String startedAt, String endedAt,
               Long elapsedMs, List<Cell> revealed, List<Cell> finalBoard) {}
  private record Attempt(UUID id, String puzzleId, Instant startedAt, Instant endedAt,
                         String outcome, Long elapsedMs, String revealed) {}

  private static final UUID GAME_ID = UUID.fromString("d9e37311-4ad4-48c6-a892-efce99a1c3c2");
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TypeReference<List<BloomAndBoomPuzzle.Position>> POSITIONS = new TypeReference<>() {};
  private final JdbcTemplate db;
  BloomAndBoomService(JdbcTemplate db) { this.db = db; }

  Today today(Owner owner) {
    LocalDate date = today();
    return view(owner, BloomAndBoomPuzzle.forDate(date), find(owner, date, false));
  }

  @Transactional Today start(Owner owner) {
    lock(owner);
    LocalDate date = today();
    BloomAndBoomPuzzle.Puzzle puzzle = BloomAndBoomPuzzle.forDate(date);
    Attempt existing = find(owner, date, false);
    if (existing == null) {
      UUID id = UUID.randomUUID(); Instant started = Instant.now();
      db.update("""
          insert into bloom_and_boom_attempts
            (id,user_id,guest_token_hash,puzzle_date,puzzle_id,started_at,revealed)
          values (?,?,?,?,?,?, '[]'::jsonb)
          """, id, owner.userId(), owner.guestTokenHash(), date, puzzle.id(), Timestamp.from(started));
      existing = new Attempt(id, puzzle.id(), started, null, null, null, "[]");
    }
    return view(owner, puzzle, existing);
  }

  @Transactional Today reveal(Owner owner, BloomAndBoomPuzzle.Position position) {
    lock(owner);
    LocalDate date = today();
    BloomAndBoomPuzzle.Puzzle puzzle = BloomAndBoomPuzzle.forDate(date);
    if (position == null || position.row() < 0 || position.column() < 0 ||
        position.row() >= puzzle.size() || position.column() >= puzzle.size())
      throw new ApiException(400, "INVALID_CELL", "Choose a tile inside today’s garden.");
    Attempt attempt = find(owner, date, true);
    if (attempt == null) throw new ApiException(409, "ATTEMPT_NOT_STARTED", "Start today’s puzzle first.");
    if (!attempt.puzzleId().equals(puzzle.id()))
      throw new ApiException(409, "PUZZLE_VERSION_CHANGED", "Today’s garden changed. Reload the page.");
    if (attempt.endedAt() != null) return view(owner, puzzle, attempt);
    List<BloomAndBoomPuzzle.Position> revealed = positions(attempt.revealed());
    if (revealed.contains(position)) return view(owner, puzzle, attempt);

    Instant ended = null; String outcome = null; Long elapsed = null;
    if (puzzle.hazard(position)) { outcome = "LOST"; ended = Instant.now(); }
    else {
      revealed = new ArrayList<>(revealed); revealed.add(position);
      if (revealed.size() == puzzle.safeCells()) { outcome = "WON"; ended = Instant.now(); }
    }
    if (ended != null) elapsed = Math.max(0, Duration.between(attempt.startedAt(), ended).toMillis());
    db.update("""
        update bloom_and_boom_attempts set revealed = ?::jsonb, ended_at = ?, outcome = ?, elapsed_ms = ? where id = ?
        """, json(revealed), ended == null ? null : Timestamp.from(ended), outcome, elapsed, attempt.id());
    Attempt updated = new Attempt(attempt.id(), attempt.puzzleId(), attempt.startedAt(), ended, outcome, elapsed, json(revealed));
    if ("WON".equals(outcome) && owner.userId() != null) record(owner.userId(), puzzle, elapsed, ended);
    return view(owner, puzzle, updated);
  }

  private Today view(Owner owner, BloomAndBoomPuzzle.Puzzle puzzle, Attempt attempt) {
    List<BloomAndBoomPuzzle.Position> positions = attempt == null ? List.of() : positions(attempt.revealed());
    List<Cell> revealed = positions.stream().map(position -> new Cell(position.row(), position.column(), "SAFE")).toList();
    String status = attempt == null ? "READY" : attempt.endedAt() == null ? "PLAYING" : attempt.outcome();
    List<Cell> finalBoard = attempt != null && attempt.endedAt() != null ? board(puzzle) : null;
    return new Today(puzzle.id(), puzzle.date(), puzzle.size(), puzzle.rowHazards(), puzzle.columnHazards(), status,
        attempt == null ? null : attempt.startedAt().toString(), attempt == null || attempt.endedAt() == null ? null : attempt.endedAt().toString(),
        attempt == null ? null : attempt.elapsedMs(), revealed, finalBoard);
  }

  private Attempt find(Owner owner, LocalDate date, boolean forUpdate) {
    String ownership = owner.userId() == null ? "guest_token_hash = ?" : "user_id = ?";
    Object value = owner.userId() == null ? owner.guestTokenHash() : owner.userId();
    List<Attempt> rows = db.query("""
        select id,puzzle_id,started_at,ended_at,outcome,elapsed_ms,revealed::text
        from bloom_and_boom_attempts where %s and puzzle_date = ? %s
        """.formatted(ownership, forUpdate ? "for update" : ""), (rs, row) -> new Attempt(
        rs.getObject("id", UUID.class), rs.getString("puzzle_id"), rs.getTimestamp("started_at").toInstant(),
        rs.getTimestamp("ended_at") == null ? null : rs.getTimestamp("ended_at").toInstant(), rs.getString("outcome"),
        rs.getObject("elapsed_ms", Long.class), rs.getString("revealed")), value, date);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private void lock(Owner owner) {
    db.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {}, owner.lockKey());
  }
  private static LocalDate today() { return LocalDate.now(ZoneOffset.UTC); }
  private static List<BloomAndBoomPuzzle.Position> positions(String json) {
    try { return JSON.readValue(json, POSITIONS); }
    catch (Exception exception) { throw new IllegalStateException("Stored Bloom & Boom state is invalid.", exception); }
  }
  private static String json(Object value) {
    try { return JSON.writeValueAsString(value); }
    catch (Exception exception) { throw new IllegalStateException(exception); }
  }
  private static List<Cell> board(BloomAndBoomPuzzle.Puzzle puzzle) {
    List<Cell> cells = new ArrayList<>();
    for (int row = 0; row < puzzle.size(); row++) for (int column = 0; column < puzzle.size(); column++)
      cells.add(new Cell(row, column, puzzle.hazards()[row][column] ? "HAZARD" : "SAFE"));
    return List.copyOf(cells);
  }
  private void record(UUID user, BloomAndBoomPuzzle.Puzzle puzzle, long elapsedMs, Instant completedAt) {
    BigDecimal seconds = BigDecimal.valueOf(elapsedMs, 3);
    Map<String, Object> metrics = new LinkedHashMap<>();
    metrics.put("completionStatus", "WIN"); metrics.put("completionTimeMs", elapsedMs);
    metrics.put("rankingPrimary", seconds); metrics.put("rankingPrimaryDirection", "ASCENDING");
    metrics.put("displayScore", display(elapsedMs)); metrics.put("puzzleId", puzzle.id());
    db.update("""
        insert into submissions (id,user_id,game_id,puzzle_key,played_on,primary_score,metrics,
          raw_text,parser_version,submission_method,created_at)
        values (?,?,?,?,?,?,?::jsonb,?,1,'IN_APP',?) on conflict (user_id,game_id,puzzle_key) do nothing
        """, UUID.randomUUID(), user, GAME_ID, puzzle.id(), puzzle.date(), seconds, json(metrics),
        "Bloom & Boom in-app result " + puzzle.date(), Timestamp.from(completedAt));
  }
  static String display(long elapsedMs) {
    long minutes = elapsedMs / 60_000, seconds = elapsedMs % 60_000 / 1_000, hundredths = elapsedMs % 1_000 / 10;
    return String.format(Locale.ROOT, "%d:%02d.%02d", minutes, seconds, hundredths);
  }
}
