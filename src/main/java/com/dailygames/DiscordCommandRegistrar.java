package com.dailygames;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Component
class DiscordCommandRegistrar {
  private static final Logger log = LoggerFactory.getLogger(DiscordCommandRegistrar.class);
  private final HttpClient http;
  private final ObjectMapper json;
  private final DiscordChannelLeaderboardRepository mappings;
  private final DiscordCommandRegistrationStore registrations;
  private final URI apiBase;
  private final String applicationId;
  private final String botToken;

  @Autowired
  DiscordCommandRegistrar(ObjectMapper json, DiscordChannelLeaderboardRepository mappings,
      DiscordCommandRegistrationStore registrations,
      @Value("${app.discord.application-id:}") String applicationId,
      @Value("${app.discord.bot-token:}") String botToken) {
    this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), json, mappings, registrations,
        URI.create("https://discord.com/api/v10"), applicationId, botToken);
  }

  DiscordCommandRegistrar(HttpClient http, ObjectMapper json, DiscordChannelLeaderboardRepository mappings,
      DiscordCommandRegistrationStore registrations, URI apiBase, String applicationId, String botToken) {
    this.http = http; this.json = json; this.mappings = mappings; this.registrations = registrations; this.apiBase = apiBase;
    this.applicationId = applicationId; this.botToken = botToken;
  }

  // Global commands cover every installed server, including installs made before this feature.
  // Guild commands become visible immediately when an owner connects a channel.
  @Scheduled(initialDelay = 1000, fixedDelay = 600000)
  synchronized void reconcile() {
    if (!configured()) return;
    registrations.pruneUnmappedGuilds();
    try { registerGlobal(); }
    catch (ApiException exception) { log.warn("Discord global command registration failed: {}", exception.code); }
    for (String guildId : mappings.findDistinctGuildIds()) {
      try { registerGuild(guildId); }
      catch (ApiException exception) { log.warn("Discord command registration failed for guild {}: {}", guildId, exception.code); }
    }
  }

  synchronized void registerGuild(String guildId) {
    if (!configured()) throw unavailable();
    if (!guildId.matches("[0-9]{1,20}")) throw new IllegalArgumentException("Invalid Discord guild ID");
    if (register("/applications/" + applicationId + "/guilds/" + guildId + "/commands", guildId, false))
      log.info("Registered Discord commands for guild {}", guildId);
  }

  private synchronized void registerGlobal() {
    if (register("/applications/" + applicationId + "/commands", "global", true))
      log.info("Registered global Discord commands");
  }

  private boolean register(String path, String scopeId, boolean global) {
    boolean changed = false;
    for (Map<String, Object> command : definitions(global)) {
      String name = (String) command.get("name");
      String hash = definitionHash(command);
      if (registrations.isCurrent(applicationId, scopeId, name, hash)) continue;
      try {
        HttpRequest request = HttpRequest.newBuilder(apiBase.resolve(apiBase.getPath() + path))
            .timeout(Duration.ofSeconds(8))
            .header("Authorization", "Bot " + botToken)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(command))).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 && response.statusCode() != 201) {
          log.warn("Discord rejected command {} with HTTP {}", command.get("name"), response.statusCode());
          throw unavailable();
        }
        registrations.markCurrent(applicationId, scopeId, name, hash);
        changed = true;
      } catch (ApiException exception) {
        throw exception;
      } catch (Exception exception) {
        if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
        throw unavailable();
      }
    }
    return changed;
  }

  private String definitionHash(Map<String, Object> command) {
    try {
      byte[] bytes = json.writeValueAsBytes(canonical(command));
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception exception) { throw new IllegalStateException("Cannot fingerprint Discord command", exception); }
  }

  private Object canonical(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> sorted = new TreeMap<>();
      map.forEach((key, entry) -> sorted.put((String) key, canonical(entry)));
      return sorted;
    }
    if (value instanceof List<?> list) return list.stream().map(this::canonical).toList();
    return value;
  }

  private boolean configured() {
    return applicationId.matches("[0-9]{1,20}") && !botToken.isBlank();
  }

  private static ApiException unavailable() {
    return new ApiException(502, "DISCORD_COMMANDS_UNAVAILABLE", "Discord commands could not be enabled. Try again shortly.");
  }

  static List<Map<String, Object>> definitions(boolean global) {
    List<Map<String, Object>> commands = new ArrayList<>();
    commands.add(command("help", "How to link your account, submit games, and view rankings", List.of()));
    commands.add(command("signup", "Create or connect your account and join this channel's leaderboard", List.of()));
    commands.add(command("my-id", "Show your shareable profile UUID privately", List.of()));
    commands.add(command("add-member", "Add a profile to this channel's leaderboard",
        List.of(stringOption("uuid", "Shareable profile UUID", true, 36, 36))));
    commands.add(command("link", "Link your Discord identity to your Daily Tracker profile",
        List.of(stringOption("code", "One-time code from your Daily Tracker dashboard", true, 12, 14))));
    commands.add(command("unlink", "Unlink your Discord identity from Daily Tracker", List.of()));
    Map<String, Object> setup = command("setup", "Connect this channel to a Daily Tracker leaderboard",
        List.of(stringOption("leaderboard", "Leaderboard UUID", true, 36, 36)));
    setup.put("default_member_permissions", "32");
    commands.add(setup);
    Map<String, Object> game = stringOption("game", "Game whose result you are submitting", true, 1, 80);
    game.put("autocomplete", true);
    commands.add(command("daily", "Submit a daily-game share result to this channel's leaderboard",
        List.of(game, stringOption("result", "Complete game share text", true, 1, 6000))));
    Map<String, Object> leaderboardGame = stringOption("game", "Game to rank", true, 1, 80);
    leaderboardGame.put("autocomplete", true);
    Map<String, Object> range = choiceOption("range", "Period to rank (defaults to today)", false,
        List.of(Map.of("name", "Today", "value", "today"), Map.of("name", "Last 7 days", "value", "7d"),
            Map.of("name", "Last 30 days", "value", "30d"), Map.of("name", "All time", "value", "all")));
    commands.add(command("leaderboard", "Show this channel's leaderboard rankings", List.of(leaderboardGame, range)));
    if (global) commands.forEach(command -> {
      command.put("contexts", List.of(0));
      command.put("integration_types", List.of(0));
    });
    return commands;
  }

  private static Map<String, Object> command(String name, String description, List<Map<String, Object>> options) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("name", name); result.put("type", 1); result.put("description", description);
    if (!options.isEmpty()) result.put("options", options);
    return result;
  }

  private static Map<String, Object> stringOption(String name, String description, boolean required,
      int minLength, int maxLength) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("type", 3); result.put("name", name); result.put("description", description);
    result.put("required", required); result.put("min_length", minLength); result.put("max_length", maxLength);
    return result;
  }

  private static Map<String, Object> choiceOption(String name, String description, boolean required,
      List<Map<String, String>> choices) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("type", 3); result.put("name", name); result.put("description", description);
    result.put("required", required); result.put("choices", choices);
    return result;
  }
}
