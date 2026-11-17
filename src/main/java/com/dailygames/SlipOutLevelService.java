package com.dailygames;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
class SlipOutLevelService {
  record Progress(int levelNumber, int bestMoves, boolean legacy) {}
  record Catalog(List<SlipOutLevels.Level> levels, List<Progress> progress) {}
  record Started(UUID attemptId) {}
  record Finished(boolean completed, int moves, List<Progress> progress) {}
  record ImportResult(int levelNumber, List<SlipOutLevels.Move> moves) {}
  private record Attempt(int level, String catalogId, boolean ended, Boolean completed, Integer moves) {}
  private record StoredProgress(int levelNumber, int bestMoves, String catalogId) {}
  private static final ObjectMapper JSON = new ObjectMapper();
  private final JdbcTemplate db;
  SlipOutLevelService(JdbcTemplate db) { this.db = db; }

  Catalog catalog(UUID user) { return new Catalog(SlipOutLevels.ALL, user == null ? List.of() : progress(user)); }
  List<Progress> progress(UUID user) {
    var rows = db.query("select level_number, best_moves, catalog_id from slipout_level_progress where user_id = ? order by level_number",
        (rs, row) -> new StoredProgress(rs.getInt(1), rs.getInt(2), rs.getString(3)), user);
    Map<Integer, Progress> byLevel = new TreeMap<>();
    for (StoredProgress row : rows) {
      boolean legacy = !SlipOutLevels.level(row.levelNumber()).id().equals(row.catalogId());
      Progress existing = byLevel.get(row.levelNumber());
      if (existing == null || existing.legacy() && !legacy)
        byLevel.put(row.levelNumber(), new Progress(row.levelNumber(), row.bestMoves(), legacy));
    }
    return List.copyOf(byLevel.values());
  }
  private void requireUnlocked(UUID user, int level) {
    int previous = SlipOutLevels.predecessor(level);
    if (previous >= 0 && !Boolean.TRUE.equals(db.queryForObject(
        "select exists(select 1 from slipout_level_progress where user_id = ? and level_number in (?, ?))",
        Boolean.class, user, previous, level)))
      throw new ApiException(409, "LEVEL_LOCKED", "Complete the previous level in this difficulty first.");
  }
  @Transactional Started start(UUID user, int level) {
    requireUnlocked(user, level);
    Instant now = Instant.now();
    db.update("update slipout_level_attempts set ended_at = ?, completed = false where user_id = ? and ended_at is null",
        Timestamp.from(now), user);
    UUID id = UUID.randomUUID();
    db.update("insert into slipout_level_attempts(id,user_id,level_number,catalog_id,started_at) values(?,?,?,?,?)",
        id, user, level, SlipOutLevels.level(level).id(), Timestamp.from(now));
    return new Started(id);
  }
  @Transactional Finished finish(UUID user, UUID attemptId, List<SlipOutLevels.Move> moves) {
    var rows = db.query("select level_number, catalog_id, ended_at, completed, moves from slipout_level_attempts where id = ? and user_id = ? for update",
        (rs, row) -> new Attempt(rs.getInt(1), rs.getString(2), rs.getTimestamp(3) != null,
            rs.getObject(4, Boolean.class), rs.getObject(5, Integer.class)), attemptId, user);
    if (rows.isEmpty()) throw new ApiException(404, "ATTEMPT_NOT_FOUND", "Start the level first.");
    Attempt attempt = rows.getFirst();
    if (!SlipOutLevels.level(attempt.level()).id().equals(attempt.catalogId()))
      throw new ApiException(409, "LEVEL_UPDATED", "This level changed. Start a new attempt.");
    if (attempt.ended()) return new Finished(Boolean.TRUE.equals(attempt.completed()),
        attempt.moves() == null ? 0 : attempt.moves(), progress(user));
    SlipOutLevels.Replay replay = SlipOutLevels.replay(SlipOutLevels.level(attempt.level()), moves);
    db.update("update slipout_level_attempts set ended_at = now(), completed = ?, moves = ?, history = ?::jsonb where id = ?",
        replay.completed(), replay.moves(), JSON.writeValueAsString(moves), attemptId);
    if (replay.completed()) record(user, attempt.level(), moves);
    return new Finished(replay.completed(), replay.moves(), progress(user));
  }
  @Transactional List<Progress> importGuest(UUID user, List<ImportResult> results) {
    if (results == null || results.size() > 91) throw new ApiException(400, "INVALID_IMPORT", "Invalid level progress.");
    int previous = -1;
    for (ImportResult result : results) {
      if (result == null || result.levelNumber() <= previous) throw new ApiException(400, "INVALID_IMPORT", "Levels must be ordered.");
      if (!SlipOutLevels.replay(SlipOutLevels.level(result.levelNumber()), result.moves()).completed())
        throw new ApiException(400, "INVALID_IMPORT", "A level was not completed.");
      record(user, result.levelNumber(), result.moves());
      previous = result.levelNumber();
    }
    return progress(user);
  }
  private void record(UUID user, int level, List<SlipOutLevels.Move> moves) {
    db.update("""
        insert into slipout_level_progress(user_id,level_number,catalog_id,best_moves,best_history,completed_at)
        values(?,?,?,?,?::jsonb,now()) on conflict(user_id,level_number,catalog_id) do update
        set best_moves = excluded.best_moves, best_history = excluded.best_history, completed_at = excluded.completed_at
        where slipout_level_progress.best_moves > excluded.best_moves
        """, user, level, SlipOutLevels.level(level).id(), moves.size(), JSON.writeValueAsString(moves));
  }
}
