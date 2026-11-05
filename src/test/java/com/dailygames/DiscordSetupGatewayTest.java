package com.dailygames;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

class DiscordSetupGatewayTest {
  @Test void authorizationRequestsBotUserAndGuildScopesWithOnlyViewChannel() {
    DiscordSetupGateway gateway = new DiscordSetupGateway(new ObjectMapper(), "123", "secret", "bot-token",
        "https://dailytracker.cc/api/v1/discord/oauth/callback", "https://dailytracker.cc");
    assertTrue(gateway.configured());
    String url = gateway.authorizationUrl("state-token");
    assertTrue(url.contains("scope=bot+applications.commands+identify+guilds"));
    assertTrue(url.contains("permissions=1024"));
    assertTrue(url.contains("redirect_uri=https%3A%2F%2Fdailytracker.cc%2Fapi%2Fv1%2Fdiscord%2Foauth%2Fcallback"));
    assertTrue(url.contains("state=state-token"));
    assertFalse(new DiscordSetupGateway(new ObjectMapper(), "123", "secret", "bot-token",
        "http://localhost:3000/api/v1/discord/oauth/callback", "https://dailytracker.cc").configured());
  }
}
