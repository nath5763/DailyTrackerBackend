package com.dailygames;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DiscordCommandRegistrationStoreTest {
  @Test void databaseGeneratesRegistrationTimestampWithoutBindingJavaInstant() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    DiscordCommandRegistrationStore store = new DiscordCommandRegistrationStore(jdbc);

    store.markCurrent("123456789012345678", "global", "daily", "definition-hash");

    verify(jdbc).update(argThat(sql -> sql.contains("current_timestamp") && !sql.contains("values (?, ?, ?, ?, ?)")),
        eq("123456789012345678"), eq("global"), eq("daily"), eq("definition-hash"));
  }
}
