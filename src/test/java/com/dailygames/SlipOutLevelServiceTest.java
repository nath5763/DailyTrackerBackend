package com.dailygames;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.sql.ResultSet;
import java.lang.reflect.Proxy;
import static org.junit.jupiter.api.Assertions.*;

class SlipOutLevelServiceTest {
  private static class ProgressDb extends JdbcTemplate {
    final Map<String, Integer> best = new HashMap<>();
    @Override public <T> T queryForObject(String sql, Class<T> type, Object... args) {
      return type.cast(best.keySet().stream().anyMatch(key -> key.startsWith(args[1] + ":") ||
          key.startsWith(args[2] + ":")));
    }
    @SuppressWarnings("unchecked")
    @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
      List<T> rows = new ArrayList<>();
      best.entrySet().stream().sorted(java.util.Comparator.comparingInt(entry ->
          Integer.parseInt(entry.getKey().split(":", 2)[0]))).forEach(entry -> {
        String[] key = entry.getKey().split(":", 2);
        ResultSet rs = (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
            new Class<?>[]{ResultSet.class}, (proxy, method, values) -> switch (method.getName()) {
              case "getInt" -> (Integer) values[0] == 1 ? Integer.parseInt(key[0]) : entry.getValue();
              case "getString" -> key[1];
              default -> throw new UnsupportedOperationException(method.getName());
            });
        try { rows.add(mapper.mapRow(rs, rows.size())); }
        catch (java.sql.SQLException error) { throw new RuntimeException(error); }
      });
      return (List<T>) rows;
    }
    @Override public int update(String sql, Object... args) {
      if (sql.contains("insert into slipout_level_progress"))
        best.merge(args[1] + ":" + args[2], (Integer) args[3], Math::min);
      return 1;
    }
  }
  @Test void guestImportVerifiesSolutionsAndTiersUnlockIndependently() {
    var db = new ProgressDb();
    var service = new SlipOutLevelService(db);
    UUID user = UUID.randomUUID();
    assertEquals("LEVEL_LOCKED", assertThrows(ApiException.class, () -> service.start(user, 1)).code);
    assertEquals("LEVEL_LOCKED", assertThrows(ApiException.class, () -> service.start(user, 6)).code);
    assertThrows(ApiException.class, () -> service.importGuest(user,
        List.of(new SlipOutLevelService.ImportResult(1, List.of()))));
    assertTrue(service.progress(user).isEmpty());
    var progress = service.importGuest(user, List.of(
        new SlipOutLevelService.ImportResult(0, SlipOutLevelsTest.solution(0)),
        new SlipOutLevelService.ImportResult(1, SlipOutLevelsTest.solution(1)),
        new SlipOutLevelService.ImportResult(2, SlipOutLevelsTest.solution(2))));
    assertEquals(3, progress.size());
    assertEquals(SlipOutLevels.level(0).optimalMoves(), progress.getFirst().bestMoves());
    int firstMedium = SlipOutLevels.ALL.stream().filter(level -> level.difficulty().equals("medium") &&
        SlipOutLevels.predecessor(level.number()) == 0).findFirst().orElseThrow().number();
    int firstHard = SlipOutLevels.ALL.stream().filter(level -> level.difficulty().equals("hard") &&
        SlipOutLevels.predecessor(level.number()) == 0).findFirst().orElseThrow().number();
    assertNotNull(service.start(user, firstMedium).attemptId());
    assertNotNull(service.start(user, firstHard).attemptId());
    assertThrows(ApiException.class, () -> service.start(user, 45));
    assertThrows(ApiException.class, () -> service.importGuest(user,
        List.of(new SlipOutLevelService.ImportResult(4, List.of()))));
    assertEquals(3, service.progress(user).size());
    service.importGuest(user, List.of(new SlipOutLevelService.ImportResult(4, SlipOutLevelsTest.solution(4))));
    assertNotNull(service.start(user, 4).attemptId());
  }

  @Test void guestImportAcceptsTheFullNinetyLevelCatalog() {
    var db = new ProgressDb();
    var service = new SlipOutLevelService(db);
    UUID user = UUID.randomUUID();
    var completions = java.util.stream.IntStream.rangeClosed(0, 90)
        .mapToObj(number -> new SlipOutLevelService.ImportResult(number, SlipOutLevelsTest.solution(number)))
        .toList();
    var progress = service.importGuest(user, completions);
    assertEquals(91, progress.size());
    assertEquals(90, progress.getLast().levelNumber());
    assertNotNull(service.start(user, 90).attemptId());
  }

  @Test void historicalBoardScoresRemainStoredAndUnlockRevisedBoards() {
    var db = new ProgressDb();
    var service = new SlipOutLevelService(db);
    UUID user = UUID.randomUUID();
    db.best.put("5:slipout-level-v3-05", 8);
    assertTrue(service.progress(user).getFirst().legacy());
    assertNotNull(service.start(user, 5).attemptId());
    assertEquals(8, db.best.get("5:slipout-level-v3-05"));
    service.importGuest(user, List.of(new SlipOutLevelService.ImportResult(5, SlipOutLevelsTest.solution(5))));
    assertEquals(2, db.best.size());
    assertEquals(SlipOutLevels.level(5).id(), "slipout-level-v6-05");
    assertEquals(1, service.progress(user).size());
    assertFalse(service.progress(user).getFirst().legacy());
  }
}
