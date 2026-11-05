package com.dailygames;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

@Component
class DiscordSetupGateway {
  private static final String API = "https://discord.com/api/v10";
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final ObjectMapper json;
  private final String applicationId;
  private final String clientSecret;
  private final String botToken;
  private final String callbackUrl;
  private final String expectedCallbackUrl;

  DiscordSetupGateway(ObjectMapper json,
      @Value("${app.discord.application-id:}") String applicationId,
      @Value("${app.discord.client-secret:}") String clientSecret,
      @Value("${app.discord.bot-token:}") String botToken,
      @Value("${app.discord.callback-url:}") String callbackUrl,
      @Value("${app.public-base-url}") String publicBaseUrl) {
    this.json = json; this.applicationId = applicationId; this.clientSecret = clientSecret;
    this.botToken = botToken; this.callbackUrl = callbackUrl;
    this.expectedCallbackUrl = publicBaseUrl.replaceAll("/+$", "") + "/api/v1/discord/oauth/callback";
  }

  boolean configured() {
    return applicationId.matches("[0-9]{1,20}") && !clientSecret.isBlank() && !botToken.isBlank()
        && callbackUrl.equals(expectedCallbackUrl)
        && (callbackUrl.startsWith("https://") || callbackUrl.startsWith("http://localhost:"));
  }

  String authorizationUrl(String state) {
    return "https://discord.com/oauth2/authorize?response_type=code&client_id=" + applicationId
        + "&scope=" + enc("bot applications.commands identify guilds") + "&permissions=1024&integration_type=0"
        + "&redirect_uri=" + enc(callbackUrl) + "&state=" + enc(state);
  }

  String identityAuthorizationUrl(String state) {
    return "https://discord.com/oauth2/authorize?response_type=code&client_id=" + applicationId
        + "&scope=identify&redirect_uri=" + enc(callbackUrl) + "&state=" + enc(state);
  }

  JsonNode exchange(String code) {
    String body = "grant_type=authorization_code&code=" + enc(code) + "&redirect_uri=" + enc(callbackUrl);
    String credentials = Base64.getEncoder().encodeToString(
        (applicationId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
    HttpRequest request = HttpRequest.newBuilder(URI.create(API + "/oauth2/token"))
        .timeout(Duration.ofSeconds(15)).header("Content-Type", MediaType.APPLICATION_FORM_URLENCODED_VALUE)
        .header("Authorization", "Basic " + credentials)
        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    return send(request);
  }

  JsonNode user(String accessToken) { return get("/users/@me", "Bearer " + accessToken); }
  JsonNode userGuilds(String accessToken) { return get("/users/@me/guilds", "Bearer " + accessToken); }
  JsonNode botUser() { return get("/users/@me", "Bot " + botToken); }
  JsonNode guild(String guildId) { return getBot("/guilds/" + guildId); }
  JsonNode botMember(String guildId, String botId) {
    return getBot("/guilds/" + guildId + "/members/" + botId);
  }
  JsonNode channels(String guildId) { return getBot("/guilds/" + guildId + "/channels"); }

  private JsonNode getBot(String path) {
    return send(HttpRequest.newBuilder(URI.create(API + path)).timeout(Duration.ofSeconds(15))
        .header("Authorization", "Bot " + botToken).GET().build(), true);
  }

  private JsonNode get(String path, String authorization) {
    return send(HttpRequest.newBuilder(URI.create(API + path)).timeout(Duration.ofSeconds(15))
        .header("Authorization", authorization).GET().build());
  }

  private JsonNode send(HttpRequest request) { return send(request, false); }
  private JsonNode send(HttpRequest request, boolean botAccess) {
    try {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (botAccess && (response.statusCode() == 403 || response.statusCode() == 404))
        throw new ApiException(409, "BOT_NOT_INSTALLED", "Install the bot in this server and try again.");
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new ApiException(502, "DISCORD_API_UNAVAILABLE", "Discord could not verify this server. Try again shortly.");
      }
      return json.readTree(response.body());
    } catch (ApiException exception) {
      throw exception;
    } catch (Exception exception) {
      if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new ApiException(502, "DISCORD_API_UNAVAILABLE", "Discord could not verify this server. Try again shortly.");
    }
  }

  private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
