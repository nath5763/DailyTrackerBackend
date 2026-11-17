package com.dailygames;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
class TelegramGateway {
  private static final Logger log = LoggerFactory.getLogger(TelegramGateway.class);
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final ObjectMapper json;
  private final String token;
  private final String username;
  private final String webhookUrl;
  private final String secret;

  TelegramGateway(ObjectMapper json, @Value("${app.telegram.bot-token:}") String token,
      @Value("${app.telegram.bot-username:}") String username,
      @Value("${app.telegram.webhook-url:}") String webhookUrl,
      @Value("${app.telegram.webhook-secret:}") String secret) {
    this.json = json; this.token = token.trim(); this.username = username.replaceFirst("^@", "").trim();
    this.webhookUrl = webhookUrl.trim(); this.secret = secret.trim();
  }

  boolean configured() {
    return token.matches("[0-9]+:[A-Za-z0-9_-]+") && username.matches("[A-Za-z0-9_]{5,32}")
        && webhookUrl.startsWith("https://") && secret.matches("[A-Za-z0-9_-]{16,256}");
  }

  boolean accepts(String supplied) {
    return configured() && supplied != null && java.security.MessageDigest.isEqual(
        secret.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
        supplied.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
  }

  String deepLink(String payload) { return "https://t.me/" + username + "?start=" + payload; }
  String username() { return username; }

  JsonNode call(String method, Map<String, ?> body) {
    if (!configured()) throw new ApiException(503, "TELEGRAM_NOT_CONFIGURED", "Telegram is not configured.");
    try {
      HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.telegram.org/bot" + token + "/" + method))
          .timeout(Duration.ofSeconds(12)).header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
          .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      JsonNode result = json.readTree(response.body());
      if (response.statusCode() / 100 != 2 || !result.path("ok").asBoolean(false))
        throw new ApiException(502, "TELEGRAM_API_UNAVAILABLE", "Telegram could not complete this action.");
      return result.path("result");
    } catch (ApiException exception) { throw exception; }
    catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new ApiException(502, "TELEGRAM_API_UNAVAILABLE", "Telegram could not complete this action.");
    } catch (Exception exception) {
      throw new ApiException(502, "TELEGRAM_API_UNAVAILABLE", "Telegram could not complete this action.");
    }
  }

  void send(long chatId, String text) { send(chatId, text, null); }
  void send(long chatId, String text, List<List<Map<String, String>>> buttons) {
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("chat_id", chatId);
    body.put("text", text.length() > 4000 ? text.substring(0, 3997) + "..." : text);
    body.put("link_preview_options", Map.of("is_disabled", true));
    if (buttons != null && !buttons.isEmpty()) body.put("reply_markup", Map.of("inline_keyboard", buttons));
    call("sendMessage", body);
  }

  void answer(String callbackId, String text) {
    call("answerCallbackQuery", Map.of("callback_query_id", callbackId, "text", text.length() > 200 ? text.substring(0, 200) : text));
  }

  @EventListener(ApplicationReadyEvent.class)
  @Scheduled(cron = "0 0 */6 * * *")
  void register() {
    if (!configured()) return;
    try {
      call("setMyCommands", Map.of("commands", List.of(
          command("help", "Show Daily Tracker help"), command("signup", "Create or connect an account"),
          command("link", "Link your account"), command("unlink", "Unlink your account"),
          command("my_id", "Show your account UUID"), command("add_member", "Add a board member"),
          command("setup", "Connect this group"), command("set_leaderboard", "Choose this group's board"),
          command("unset_leaderboard", "Disconnect this group"), command("daily", "Save a game to your linked account"),
          command("loldle", "Save LoLdle to your linked account"), command("leaderboard", "Show connected board rankings"))));
      call("setWebhook", Map.of("url", webhookUrl, "secret_token", secret,
          "allowed_updates", List.of("message", "callback_query")));
    } catch (RuntimeException exception) {
      log.error("Telegram command or webhook registration failed", exception);
    }
  }

  private Map<String, String> command(String name, String description) {
    return Map.of("command", name, "description", description);
  }
}
