package com.dailygames;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers(disabledWithoutDocker = true)
class CompletionStatusMigrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Test
  void backfillsOnlyMissingAndUnknownCompletionStatuses() throws Exception {
    migrateThroughVersion25();
    UUID userId = UUID.randomUUID();
    try (Connection connection = connection()) {
      insertProfile(connection, userId);
      insertSubmission(connection, userId, "fermi", "fermi-missing", new BigDecimal("4.06"), "{}");
      insertSubmission(connection, userId, "fermi", "fermi-unknown", new BigDecimal("3.71"),
          "{\"completionStatus\":\"UNKNOWN\"}");
      insertSubmission(connection, userId, "fermi", "fermi-loss", new BigDecimal("2.25"),
          "{\"completionStatus\":\"LOSS\"}");
      insertSubmission(connection, userId, "queens", "queens-incomplete", new BigDecimal("191"),
          "{\"completionStatus\":\"INCOMPLETE\"}");
      insertSubmission(connection, userId, "wordle", "wordle-x", new BigDecimal("7"),
          "{\"displayScore\":\"X/6\"}");
      insertSubmission(connection, userId, "wordle", "wordle-failed", new BigDecimal("4"),
          "{\"completionStatus\":\"UNKNOWN\",\"failed\":true}");
      insertSubmission(connection, userId, "wordle", "wordle-win", new BigDecimal("4"),
          "{\"completionStatus\":\"UNKNOWN\",\"displayScore\":\"4/6\"}");
      insertSubmission(connection, userId, "wordle", "wordle-explicit-win", new BigDecimal("7"),
          "{\"completionStatus\":\"WIN\",\"displayScore\":\"X/6\"}");
      insertSubmission(connection, userId, "wordle", "wordle-explicit-loss", new BigDecimal("4"),
          "{\"completionStatus\":\"LOSS\",\"displayScore\":\"4/6\"}");
      insertSubmission(connection, userId, "wordle", "wordle-incomplete", new BigDecimal("7"),
          "{\"completionStatus\":\"INCOMPLETE\",\"displayScore\":\"X/6\"}");
    }

    Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .load().migrate();

    assertEquals(Map.of(
        "fermi-loss", "LOSS",
        "fermi-missing", "WIN",
        "fermi-unknown", "WIN",
        "queens-incomplete", "INCOMPLETE",
        "wordle-explicit-loss", "LOSS",
        "wordle-explicit-win", "WIN",
        "wordle-failed", "LOSS",
        "wordle-incomplete", "INCOMPLETE",
        "wordle-win", "WIN",
        "wordle-x", "LOSS"), completionStatuses());
  }

  private void migrateThroughVersion25() {
    Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .target("25").load().migrate();
  }

  private Connection connection() throws Exception {
    return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private void insertProfile(Connection connection, UUID userId) throws Exception {
    try (PreparedStatement user = connection.prepareStatement(
        "insert into app_users(id, normalized_email, created_at) values (?, ?, now())")) {
      user.setObject(1, userId);
      user.setString(2, "migration-" + userId + "@example.com");
      user.executeUpdate();
    }
    try (PreparedStatement profile = connection.prepareStatement(
        "insert into profiles(user_id, share_id, display_name, avatar_color, created_at, updated_at) " +
            "values (?, ?, 'Migration Test', '#dfff4f', now(), now())")) {
      profile.setObject(1, userId);
      profile.setObject(2, UUID.randomUUID());
      profile.executeUpdate();
    }
  }

  private void insertSubmission(Connection connection, UUID userId, String gameSlug, String puzzleKey,
                                BigDecimal score, String metrics) throws Exception {
    try (PreparedStatement submission = connection.prepareStatement(
        "insert into submissions(id, user_id, game_id, puzzle_key, played_on, primary_score, metrics, raw_text, " +
            "parser_version, created_at) select ?, ?, id, ?, ?, ?, cast(? as jsonb), 'legacy share', 1, now() " +
            "from games where slug = ?")) {
      submission.setObject(1, UUID.randomUUID());
      submission.setObject(2, userId);
      submission.setString(3, puzzleKey);
      submission.setObject(4, LocalDate.of(2026, 11, 4));
      submission.setBigDecimal(5, score);
      submission.setString(6, metrics);
      submission.setString(7, gameSlug);
      assertEquals(1, submission.executeUpdate());
    }
  }

  private Map<String, String> completionStatuses() throws Exception {
    Map<String, String> statuses = new LinkedHashMap<>();
    try (Connection connection = connection();
         PreparedStatement query = connection.prepareStatement(
             "select puzzle_key, metrics ->> 'completionStatus' from submissions order by puzzle_key");
         ResultSet results = query.executeQuery()) {
      while (results.next()) statuses.put(results.getString(1), results.getString(2));
    }
    return statuses;
  }
}
