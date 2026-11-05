package com.dailygames;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
class DiscordCommandRegistrationStore {
  private final JdbcTemplate jdbc;

  DiscordCommandRegistrationStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  boolean isCurrent(String applicationId, String scopeId, String name, String hash) {
    return Boolean.TRUE.equals(jdbc.queryForObject(
        "select exists(select 1 from discord_command_registrations where application_id = ? and scope_id = ? and command_name = ? and definition_hash = ?)",
        Boolean.class, applicationId, scopeId, name, hash));
  }

  void markCurrent(String applicationId, String scopeId, String name, String hash) {
    jdbc.update("insert into discord_command_registrations(application_id, scope_id, command_name, definition_hash, registered_at) " +
        "values (?, ?, ?, ?, ?) on conflict (application_id, scope_id, command_name) do update " +
        "set definition_hash = excluded.definition_hash, registered_at = excluded.registered_at",
        applicationId, scopeId, name, hash, Instant.now());
  }
}
