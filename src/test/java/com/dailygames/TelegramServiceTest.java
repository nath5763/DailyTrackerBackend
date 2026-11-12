package com.dailygames;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.sql.Timestamp;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TelegramServiceTest {
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final TelegramGateway bot = mock(TelegramGateway.class);
  private final TelegramService service = new TelegramService(jdbc, bot, mock(ProfileRepository.class),
      mock(LeaderboardRepository.class), mock(MembershipRepository.class), mock(BillingService.class),
      mock(DailyGamesService.class), mock(GameAdapterRegistry.class),
      Clock.fixed(Instant.parse("2026-11-12T12:00:00Z"), ZoneOffset.UTC), "pepper", "https://dailytracker.cc");

  @Test void duplicateUpdateHasNoSideEffects() throws Exception {
    when(jdbc.update(startsWith("insert into processed_telegram_updates"), anyLong(), any(Timestamp.class))).thenReturn(0);
    service.handle(new ObjectMapper().readTree("{\"update_id\":5,\"message\":{\"chat\":{\"id\":1,\"type\":\"private\"},\"from\":{\"id\":1},\"text\":\"/help\"}}"));
    verifyNoInteractions(bot);
  }

  @Test void malformedIdentityIsRejected() throws Exception {
    ApiException error = assertThrows(ApiException.class,
        () -> service.handle(new ObjectMapper().readTree("{\"message\":{}}")));
    assertEquals("INVALID_UPDATE", error.code);
    verifyNoInteractions(jdbc, bot);
  }

  @Test void ordinaryGroupTextIsIgnored() throws Exception {
    when(jdbc.update(startsWith("insert into processed_telegram_updates"), anyLong(), any(Timestamp.class))).thenReturn(1);
    service.handle(new ObjectMapper().readTree("{\"update_id\":6,\"message\":{\"chat\":{\"id\":-1001,\"type\":\"supergroup\"},\"from\":{\"id\":7},\"text\":\"ordinary chat\"}}"));
    verifyNoInteractions(bot);
  }
}
