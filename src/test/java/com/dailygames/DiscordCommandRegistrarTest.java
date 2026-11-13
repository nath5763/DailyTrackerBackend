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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DiscordCommandRegistrarTest {
  private static final String GUILD_ID = "333333333333333333";
  private final ObjectMapper json = new ObjectMapper();
  private final List<String> paths = new ArrayList<>();
  private final List<JsonNode> bodies = new ArrayList<>();
  private DiscordChannelLeaderboardRepository mappings;
  private DiscordCommandRegistrationStore registrations;
  private final Map<String, String> persisted = new HashMap<>();
  private HttpServer server;
  private DiscordCommandRegistrar registrar;
  private int status = 201;

  @BeforeEach void setUp() throws Exception {
    mappings = mock(DiscordChannelLeaderboardRepository.class);
    registrations = mock(DiscordCommandRegistrationStore.class);
    when(registrations.isCurrent(anyString(), anyString(), anyString(), anyString())).thenAnswer(call ->
        call.getArgument(3).equals(persisted.get(call.getArgument(0) + ":" + call.getArgument(1) + ":" + call.getArgument(2))));
    doAnswer(call -> {
      persisted.put(call.getArgument(0) + ":" + call.getArgument(1) + ":" + call.getArgument(2), call.getArgument(3));
      return null;
    }).when(registrations).markCurrent(anyString(), anyString(), anyString(), anyString());
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/v10", exchange -> {
      paths.add(exchange.getRequestURI().getPath());
      assertEquals("POST", exchange.getRequestMethod());
      assertEquals("Bot bot-token", exchange.getRequestHeaders().getFirst("Authorization"));
      assertEquals("DiscordBot (https://dailytracker.cc, 0.1.0)", exchange.getRequestHeaders().getFirst("User-Agent"));
      bodies.add(json.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
      byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    server.start();
    registrar = new DiscordCommandRegistrar(HttpClient.newHttpClient(), json, mappings, registrations,
        URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v10"),
        "123456789012345678", "bot-token");
  }

  @AfterEach void tearDown() { server.stop(0); }

  @Test void registersGlobalAndExistingGuildCommandsWithoutReplacingOtherCommands() {
    when(mappings.findDistinctGuildIds()).thenReturn(List.of(GUILD_ID));
    registrar.reconcile();
    assertEquals(22, paths.size());
    assertEquals(12, paths.stream().filter(path -> path.endsWith("/applications/123456789012345678/commands")).count());
    assertEquals(10, paths.stream().filter(path -> path.endsWith("/guilds/" + GUILD_ID + "/commands")).count());
    assertEquals(List.of("help", "signup", "my-id", "add-member", "link", "unlink", "setup", "daily", "loldle", "leaderboard"),
        bodies.subList(0, 10).stream().map(body -> body.path("name").asText()).toList());
    assertEquals("32", bodies.get(6).path("default_member_permissions").asText());
    assertEquals("result", bodies.get(7).path("options").get(0).path("name").asText());
    assertTrue(bodies.get(7).path("options").get(0).path("required").asBoolean());
    assertEquals("leaderboard", bodies.get(7).path("options").get(1).path("name").asText());
    assertFalse(bodies.get(7).path("options").get(1).path("required").asBoolean());
    assertEquals(2, bodies.get(7).path("options").size());
    assertEquals("[0,1,2]", bodies.get(7).path("contexts").toString());
    assertEquals("[0,1]", bodies.get(7).path("integration_types").toString());
    assertEquals("result", bodies.get(8).path("options").get(0).path("name").asText());
    assertEquals("date", bodies.get(8).path("options").get(1).path("name").asText());
    assertTrue(bodies.get(8).path("options").get(1).path("required").asBoolean());
    assertEquals("game", bodies.get(9).path("options").get(0).path("name").asText());
    assertFalse(bodies.get(9).path("options").get(0).path("required").asBoolean());
    assertTrue(bodies.get(9).path("options").get(0).path("autocomplete").asBoolean());
    assertTrue(bodies.subList(0, 10).stream().allMatch(body ->
        "[0,1,2]".equals(body.path("contexts").toString())
            && "[0,1]".equals(body.path("integration_types").toString())));
    assertTrue(bodies.subList(0, 10).stream().noneMatch(body -> body.has("dm_permission")));
    assertEquals("set-leaderboard", bodies.get(10).path("name").asText());
    assertEquals("[2]", bodies.get(10).path("contexts").toString());
    assertEquals("[1]", bodies.get(10).path("integration_types").toString());
    assertEquals("unset-leaderboard", bodies.get(11).path("name").asText());
    assertEquals("[2]", bodies.get(11).path("contexts").toString());
    assertEquals("[1]", bodies.get(11).path("integration_types").toString());
    assertTrue(bodies.get(20).path("contexts").isMissingNode());
    assertTrue(bodies.get(20).path("integration_types").isMissingNode());
    registrar.reconcile();
    registrar.registerGuild(GUILD_ID);
    assertEquals(22, paths.size());
    DiscordCommandRegistrar restarted = new DiscordCommandRegistrar(HttpClient.newHttpClient(), json, mappings,
        registrations, URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v10"),
        "123456789012345678", "bot-token");
    restarted.reconcile();
    assertEquals(22, paths.size(), "A restart must not call Discord for unchanged commands");
  }

  @Test void failedRegistrationIsRetried() {
    status = 500;
    assertEquals("DISCORD_COMMANDS_UNAVAILABLE", assertThrows(ApiException.class,
        () -> registrar.registerGuild(GUILD_ID)).code);
    status = 201;
    registrar.registerGuild(GUILD_ID);
    assertEquals(11, paths.size());
  }

  @Test void onlyAChangedCommandIsRegisteredAgain() {
    registrar.registerGuild(GUILD_ID);
    assertEquals(10, paths.size());
    persisted.remove("123456789012345678:" + GUILD_ID + ":help");
    registrar.registerGuild(GUILD_ID);
    assertEquals(11, paths.size());
    assertEquals("help", bodies.getLast().path("name").asText());
  }

  @Test void removedGuildIsNotRetriedAndReconnectionRegistersAgain() {
    when(mappings.findDistinctGuildIds()).thenReturn(List.of(GUILD_ID), List.of(), List.of(GUILD_ID));
    registrar.reconcile();
    assertEquals(22, paths.size());
    registrar.reconcile();
    assertEquals(22, paths.size());
    persisted.keySet().removeIf(key -> key.contains(":" + GUILD_ID + ":"));
    registrar.reconcile();
    assertEquals(32, paths.size());
  }

  @Test void diagnosticTextRedactsSecretsNormalizesLinesAndTruncates() {
    String secret = "super-secret-bot-token";
    String diagnostic = DiscordCommandRegistrar.sanitizeDiagnostic(
        "first\nAuthorization: Bot " + secret + "\t" + "x".repeat(1600), secret);
    assertFalse(diagnostic.contains(secret));
    assertFalse(diagnostic.contains("\n"));
    assertFalse(diagnostic.contains("\t"));
    assertTrue(diagnostic.contains("[REDACTED]"));
    assertTrue(diagnostic.endsWith("…[truncated]"));
    assertEquals("<empty>", DiscordCommandRegistrar.sanitizeDiagnostic(null, secret));
  }
}
