package com.dailygames;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service
class SlipOutService {
  record Result(String puzzleId, LocalDate puzzleDate, UUID playerId, boolean completed, int moves,
                Long completionTimeMs, long remainingTimeMs, String completedAt) {}
  record Today(SlipOutPuzzle.Puzzle puzzle, Result best) {}
  record Started(UUID attemptId, long startedAt, long serverNow) {}
  record Finished(Result result, Result best) {}
  private record Attempt(LocalDate date, String puzzleId, long startedAt, Long endedAt, Boolean completed,
                         Integer moves, Long completionTimeMs, Long remainingTimeMs) {}

  private static final UUID GAME_ID = UUID.fromString("a3aa273b-fdd6-4e91-843a-710c2f69657e");
  private static final ObjectMapper JSON = new ObjectMapper();
  private final JdbcTemplate db;
  SlipOutService(JdbcTemplate db) { this.db = db; }
  private static long now() { return System.currentTimeMillis(); }
  private static LocalDate today() { return LocalDate.now(ZoneOffset.UTC); }

  Today today(UUID user) {
    LocalDate date = today();
    return new Today(SlipOutPuzzle.forDate(date), user == null ? null : best(user, date));
  }

  @Transactional
  Started start(UUID user) {
    long now = now(); LocalDate date = today(); UUID id = UUID.randomUUID();
    db.update("""
        update slipout_attempts set ended_at = ?, completed = false, moves = 0,
          completion_time_ms = null, remaining_time_ms = greatest(0, 90000 - (? - extract(epoch from started_at) * 1000)::bigint),
          history = '[]'::jsonb
        where user_id = ? and puzzle_date = ? and ended_at is null
        """, Timestamp.from(Instant.ofEpochMilli(now)), now, user, date);
    var puzzle = SlipOutPuzzle.forDate(date);
    db.update("insert into slipout_attempts (id, user_id, puzzle_date, puzzle_id, started_at) values (?, ?, ?, ?, ?)",
        id, user, date, puzzle.id(), Timestamp.from(Instant.ofEpochMilli(now)));
    return new Started(id, now, now());
  }

  @Transactional
  Finished finish(UUID user, UUID attemptId, List<SlipOutPuzzle.Move> moves) {
    long now = now();
    var attempts = db.query("""
        select puzzle_date, puzzle_id, started_at, ended_at, completed, moves,
          completion_time_ms, remaining_time_ms
        from slipout_attempts where id = ? and user_id = ? for update
        """, (rs, row) -> new Attempt(rs.getDate("puzzle_date").toLocalDate(), rs.getString("puzzle_id"),
        rs.getTimestamp("started_at").toInstant().toEpochMilli(),
        rs.getTimestamp("ended_at") == null ? null : rs.getTimestamp("ended_at").toInstant().toEpochMilli(),
        rs.getObject("completed", Boolean.class), rs.getObject("moves", Integer.class),
        rs.getObject("completion_time_ms", Long.class), rs.getObject("remaining_time_ms", Long.class)), attemptId, user);
    if (attempts.isEmpty()) throw new ApiException(404, "ATTEMPT_NOT_FOUND", "Start a SlipOut attempt first.");
    Attempt attempt = attempts.getFirst();
    var puzzle = SlipOutPuzzle.forDate(attempt.date());
    if (!puzzle.id().equals(attempt.puzzleId())) throw new ApiException(409, "PUZZLE_VERSION_CHANGED", "This puzzle version is no longer available.");
    if (attempt.endedAt() != null) return new Finished(result(attempt, user), best(user, attempt.date()));
    var replay = SlipOutPuzzle.replay(puzzle, moves);
    long elapsed = Math.max(0, now - attempt.startedAt());
    boolean completed = replay.completed() && elapsed < SlipOutPuzzle.LIMIT_MS;
    long remaining = Math.max(0, SlipOutPuzzle.LIMIT_MS - elapsed);
    Result result = new Result(puzzle.id(), attempt.date(), user, completed, replay.moves(),
        completed ? elapsed : null, remaining, Instant.ofEpochMilli(now).toString());
    db.update("""
        update slipout_attempts set ended_at = ?, completed = ?, moves = ?, completion_time_ms = ?,
          remaining_time_ms = ?, history = ?::jsonb where id = ?
        """, Timestamp.from(Instant.ofEpochMilli(now)), completed, replay.moves(), result.completionTimeMs(),
        remaining, JSON.writeValueAsString(moves), attemptId);
    if (completed) recordBest(result);
    return new Finished(result, best(user, attempt.date()));
  }

  private static Result result(Attempt attempt, UUID user) {
    return new Result(attempt.puzzleId(), attempt.date(), user, Boolean.TRUE.equals(attempt.completed()),
        attempt.moves() == null ? 0 : attempt.moves(), attempt.completionTimeMs(),
        attempt.remainingTimeMs() == null ? 0 : attempt.remainingTimeMs(),
        Instant.ofEpochMilli(attempt.endedAt()).toString());
  }

  private void recordBest(Result result) {
    Map<String, Object> metrics = new LinkedHashMap<>();
    metrics.put("completionStatus", "WIN");
    metrics.put("rankingPrimary", result.moves());
    metrics.put("rankingSecondary", result.completionTimeMs());
    metrics.put("rankingPrimaryDirection", "ASCENDING");
    metrics.put("rankingSecondaryDirection", "ASCENDING");
    metrics.put("completionTimeMs", result.completionTimeMs());
    metrics.put("remainingTimeMs", result.remainingTimeMs());
    metrics.put("displayScore", result.moves() + " moves · " + result.completionTimeMs() + " ms");
    metrics.put("puzzleId", result.puzzleId());
    db.update("""
        insert into submissions (id, user_id, game_id, puzzle_key, played_on, primary_score, metrics,
          raw_text, parser_version, submission_method, created_at)
        values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, 1, 'IN_APP', ?)
        on conflict (user_id, game_id, puzzle_key) do update
          set primary_score = excluded.primary_score, metrics = excluded.metrics,
              raw_text = excluded.raw_text, created_at = excluded.created_at
        where submissions.primary_score > excluded.primary_score
           or (submissions.primary_score = excluded.primary_score and
               (submissions.metrics ->> 'completionTimeMs')::bigint > (excluded.metrics ->> 'completionTimeMs')::bigint)
        """, UUID.randomUUID(), result.playerId(), GAME_ID, result.puzzleId(), result.puzzleDate(),
        BigDecimal.valueOf(result.moves()), JSON.writeValueAsString(metrics),
        "SlipOut in-app result " + result.puzzleDate(), Timestamp.from(Instant.parse(result.completedAt())));
  }

  private Result best(UUID user, LocalDate date) {
    var rows = db.query("""
        select primary_score, metrics, created_at from submissions
        where user_id = ? and game_id = ? and puzzle_key = ?
        """, (rs, row) -> {
      Map<?, ?> metrics = JSON.readValue(rs.getString("metrics"), Map.class);
      long ms = ((Number) metrics.get("completionTimeMs")).longValue();
      return new Result(SlipOutPuzzle.forDate(date).id(), date, user, true, rs.getBigDecimal("primary_score").intValue(),
          ms, Math.max(0, SlipOutPuzzle.LIMIT_MS - ms), rs.getTimestamp("created_at").toInstant().toString());
    }, user, GAME_ID, SlipOutPuzzle.forDate(date).id());
    return rows.isEmpty() ? null : rows.getFirst();
  }
}
