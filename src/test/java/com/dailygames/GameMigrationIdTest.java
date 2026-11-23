package com.dailygames;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.fail;

class GameMigrationIdTest {
  private static final Pattern GAME_INSERT = Pattern.compile(
      "(?is)insert\\s+into\\s+games\\s*\\([^;]+?values\\s*(.+?);");
  private static final Pattern UUID = Pattern.compile(
      "'([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})'",
      Pattern.CASE_INSENSITIVE);

  @Test void everyMigratedGameUsesAUniquePrimaryKey() throws IOException {
    Map<String, Path> owners = new HashMap<>();
    try (var migrations = Files.list(Path.of("src/main/resources/db/migration"))) {
      for (Path migration : migrations.filter(path -> path.toString().endsWith(".sql")).sorted().toList()) {
        var inserts = GAME_INSERT.matcher(Files.readString(migration));
        while (inserts.find()) {
          var ids = UUID.matcher(inserts.group(1));
          while (ids.find()) {
            String id = ids.group(1).toLowerCase();
            Path previous = owners.putIfAbsent(id, migration);
            if (previous != null) fail("Duplicate game id " + id + " in " + previous + " and " + migration);
          }
        }
      }
    }
  }
}
