package com.dailygames;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DiscordCommandRegistrarTest {
  private static final String GUILD_ID = "333333333333333333";
  private final ObjectMapper json = new ObjectMapper();
  private final List<String> paths = new ArrayList<>();
  private final List<JsonNode> bodies = new ArrayList<>();
  private DiscordChannelLeaderboardRepository mappings;
  private HttpServer server;
  private DiscordCommandRegistrar registrar;
  private int status = 201;

  @BeforeEach void setUp() throws Exception {
    mappings = mock(DiscordChannelLeaderboardRepository.class);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/v10", exchange -> {
      paths.add(exchange.getRequestURI().getPath());
      assertEquals("POST", exchange.getRequestMethod());
      assertEquals("Bot bot-token", exchange.getRequestHeaders().getFirst("Authorization"));
      bodies.add(json.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
      byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    server.start();
    registrar = new DiscordCommandRegistrar(HttpClient.newHttpClient(), json, mappings,
        URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v10"),
        "123456789012345678", "bot-token");
  }

  @AfterEach void tearDown() { server.stop(0); }

  @Test void registersGlobalAndExistingGuildCommandsWithoutReplacingOtherCommands() {
    when(mappings.findDistinctGuildIds()).thenReturn(List.of(GUILD_ID));
    registrar.reconcile();
    assertEquals(18, paths.size());
    assertEquals(9, paths.stream().filter(path -> path.endsWith("/applications/123456789012345678/commands")).count());
    assertEquals(9, paths.stream().filter(path -> path.endsWith("/guilds/" + GUILD_ID + "/commands")).count());
    assertEquals(List.of("help", "signup", "my-id", "add-member", "link", "unlink", "setup", "daily", "leaderboard"),
        bodies.subList(0, 9).stream().map(body -> body.path("name").asText()).toList());
    assertEquals("32", bodies.get(6).path("default_member_permissions").asText());
    assertEquals("result", bodies.get(7).path("options").get(1).path("name").asText());
    assertTrue(bodies.get(7).path("options").get(0).path("autocomplete").asBoolean());
    assertEquals(0, bodies.get(7).path("contexts").get(0).asInt());
    assertTrue(bodies.get(16).path("contexts").isMissingNode());
    registrar.reconcile();
    registrar.registerGuild(GUILD_ID);
    assertEquals(18, paths.size());
  }

  @Test void failedRegistrationIsRetried() {
    status = 500;
    assertEquals("DISCORD_COMMANDS_UNAVAILABLE", assertThrows(ApiException.class,
        () -> registrar.registerGuild(GUILD_ID)).code);
    status = 201;
    registrar.registerGuild(GUILD_ID);
    assertEquals(10, paths.size());
  }
}
