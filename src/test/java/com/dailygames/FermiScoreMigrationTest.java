package com.dailygames;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.*;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class FermiScoreMigrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Test void repairsOnlyThousandsGroupedFermiScoresFromTheirOriginalShareText() throws Exception {
    Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .target("27").load().migrate();
    UUID userId = UUID.randomUUID();
    try (Connection connection = connection()) {
      insertProfile(connection, userId);
      insertSubmission(connection, userId, "grouped", new BigDecimal("1.9600"),
          "Fermi #103\n1,960x score\ntop 99%",
          "{\"completionStatus\":\"WIN\",\"scoreRaw\":\"1.960\",\"scoreValue\":1.960," +
              "\"nativeScore\":1.960,\"rankingPrimary\":1.960,\"displayScore\":\"1.960\",\"custom\":\"keep\"}");
      insertSubmission(connection, userId, "decimal-comma", new BigDecimal("1.5000"),
          "Fermi #104\n1,50x score", "{\"completionStatus\":\"WIN\",\"displayScore\":\"1.50×\"}");
      insertSubmission(connection, userId, "decimal-dot", new BigDecimal("1.9600"),
          "Fermi #105\n1.960x score", "{\"completionStatus\":\"WIN\",\"displayScore\":\"1.960×\"}");
    }

    Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .load().migrate();

    try (Connection connection = connection(); PreparedStatement query = connection.prepareStatement(
        "select primary_score, metrics ->> 'scoreRaw', metrics ->> 'scoreValue', metrics ->> 'nativeScore', " +
            "metrics ->> 'rankingPrimary', metrics ->> 'displayScore', metrics ->> 'custom' " +
            "from submissions where puzzle_key = ?")) {
      query.setString(1, "grouped");
      try (ResultSet result = query.executeQuery()) {
        assertTrue(result.next());
        assertEquals(0, new BigDecimal("1960").compareTo(result.getBigDecimal(1)));
        assertEquals("1960", result.getString(2));
        assertEquals("1960", result.getString(3));
        assertEquals("1960", result.getString(4));
        assertEquals("1960", result.getString(5));
        assertEquals("1,960×", result.getString(6));
        assertEquals("keep", result.getString(7));
      }
      assertUnchanged(query, "decimal-comma", "1.5000", "1.50×");
      assertUnchanged(query, "decimal-dot", "1.9600", "1.960×");
    }
  }

  private void assertUnchanged(PreparedStatement query, String puzzleKey, String score, String display) throws Exception {
    query.setString(1, puzzleKey);
    try (ResultSet result = query.executeQuery()) {
      assertTrue(result.next());
      assertEquals(0, new BigDecimal(score).compareTo(result.getBigDecimal(1)));
      assertEquals(display, result.getString(6));
    }
  }

  private Connection connection() throws Exception {
    return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private void insertProfile(Connection connection, UUID userId) throws Exception {
    try (PreparedStatement user = connection.prepareStatement(
        "insert into app_users(id, normalized_email, created_at) values (?, ?, now())")) {
      user.setObject(1, userId);
      user.setString(2, "fermi-migration-" + userId + "@example.com");
      user.executeUpdate();
    }
    try (PreparedStatement profile = connection.prepareStatement(
        "insert into profiles(user_id, share_id, display_name, avatar_color, created_at, updated_at) " +
            "values (?, ?, 'Fermi Migration', '#dfff4f', now(), now())")) {
      profile.setObject(1, userId);
      profile.setObject(2, UUID.randomUUID());
      profile.executeUpdate();
    }
  }

  private void insertSubmission(Connection connection, UUID userId, String puzzleKey, BigDecimal score,
                                String rawText, String metrics) throws Exception {
    try (PreparedStatement submission = connection.prepareStatement(
        "insert into submissions(id, user_id, game_id, puzzle_key, played_on, primary_score, metrics, raw_text, " +
            "parser_version, created_at) select ?, ?, id, ?, date '2026-11-06', ?, cast(? as jsonb), ?, 1, now() " +
            "from games where slug = 'fermi'")) {
      submission.setObject(1, UUID.randomUUID());
      submission.setObject(2, userId);
      submission.setString(3, puzzleKey);
      submission.setBigDecimal(4, score);
      submission.setString(5, metrics);
      submission.setString(6, rawText);
      assertEquals(1, submission.executeUpdate());
    }
  }
}
