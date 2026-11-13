package com.dailygames;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SlipOutLevelServiceTest {
  private static class ProgressDb extends JdbcTemplate {
    final Map<Integer, Integer> best = new HashMap<>();
    @Override public <T> T queryForObject(String sql, Class<T> type, Object... args) {
      return type.cast(best.containsKey((Integer) args[1]));
    }
    @SuppressWarnings("unchecked")
    @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
      List<SlipOutLevelService.Progress> rows = new ArrayList<>();
      best.entrySet().stream().sorted(Map.Entry.comparingByKey())
          .forEach(entry -> rows.add(new SlipOutLevelService.Progress(entry.getKey(), entry.getValue())));
      return (List<T>) rows;
    }
    @Override public int update(String sql, Object... args) {
      if (sql.contains("insert into slipout_level_progress"))
        best.merge((Integer) args[1], (Integer) args[2], Math::min);
      return 1;
    }
  }
  @Test void guestImportVerifiesEachSolutionAndUnlocksSequentially() {
    var db = new ProgressDb();
    var service = new SlipOutLevelService(db);
    UUID user = UUID.randomUUID();
    assertEquals("LEVEL_LOCKED", assertThrows(ApiException.class, () -> service.start(user, 2)).code);
    assertThrows(ApiException.class, () -> service.importGuest(user,
        List.of(new SlipOutLevelService.ImportResult(1, List.of()))));
    assertTrue(service.progress(user).isEmpty());
    var progress = service.importGuest(user, List.of(
        new SlipOutLevelService.ImportResult(1, SlipOutLevelsTest.solution(1)),
        new SlipOutLevelService.ImportResult(2, SlipOutLevelsTest.solution(2))));
    assertEquals(2, progress.size());
    assertEquals(SlipOutLevels.level(1).optimalMoves(), progress.getFirst().bestMoves());
    assertNotNull(service.start(user, 3).attemptId());
    assertThrows(ApiException.class, () -> service.importGuest(user,
        List.of(new SlipOutLevelService.ImportResult(4, SlipOutLevelsTest.solution(4)))));
    assertEquals(2, service.progress(user).size());
  }
}
