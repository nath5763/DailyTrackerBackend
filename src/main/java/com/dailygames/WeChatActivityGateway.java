package com.dailygames;

import org.springframework.beans.factory.annotation.Value;
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
import java.time.Instant;
import java.util.Map;

@Component
class WeChatActivityGateway {
  record Activity(String id, Instant expiresAt) {}
  private record AccessToken(String value, Instant refreshAt) {}

  private final String appId;
  private final String appSecret;
  private final ObjectMapper json;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private volatile AccessToken cachedToken;

  WeChatActivityGateway(@Value("${app.wechat.app-id:}") String appId,
      @Value("${app.wechat.app-secret:}") String appSecret, ObjectMapper json) {
    this.appId = appId; this.appSecret = appSecret; this.json = json;
  }

  Activity createActivity() {
    JsonNode result = send("GET", "/cgi-bin/message/wxopen/activityid/create?access_token=" + encode(accessToken()), null);
    String id = result.path("activity_id").asText("");
    long expiration = result.path("expiration_time").asLong(0);
    if (id.isBlank() || id.length() > 256 || expiration <= Instant.now().getEpochSecond()) throw platformError();
    return new Activity(id, Instant.ofEpochSecond(expiration));
  }

  void markComplete(String activityId, String memberOpenid, String templateId, int versionType) {
    JsonNode result = send("POST", "/cgi-bin/message/wxopen/chattoolmsg/send?access_token=" + encode(accessToken()),
        Map.of("activity_id", activityId, "target_state", 1, "template_id", templateId,
            "version_type", versionType, "participator_info_list",
            java.util.List.of(Map.of("group_openid", memberOpenid, "state", 1))));
    if (result.path("errcode").asInt(-1) != 0) throw platformError();
  }

  private synchronized String accessToken() {
    AccessToken current = cachedToken;
    if (current != null && current.refreshAt().isAfter(Instant.now())) return current.value();
    JsonNode result = send("POST", "/cgi-bin/stable_token",
        Map.of("grant_type", "client_credential", "appid", appId, "secret", appSecret));
    String value = result.path("access_token").asText("");
    int seconds = result.path("expires_in").asInt(0);
    if (value.isBlank() || seconds < 60) throw platformError();
    cachedToken = new AccessToken(value, Instant.now().plusSeconds(Math.max(30, seconds - 300)));
    return value;
  }

  private JsonNode send(String method, String path, Object body) {
    try {
      HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("https://api.weixin.qq.com" + path))
          .timeout(Duration.ofSeconds(8));
      if ("POST".equals(method)) request.header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
      else request.GET();
      HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) throw platformError();
      JsonNode result = json.readTree(response.body());
      int code = result.path("errcode").asInt(0);
      if (code == 40001 || code == 42001) cachedToken = null;
      if (code != 0) throw platformError();
      return result;
    } catch (ApiException error) { throw error; }
    catch (Exception error) {
      if (error instanceof InterruptedException) Thread.currentThread().interrupt();
      throw platformError();
    }
  }

  private ApiException platformError() {
    return new ApiException(502, "WECHAT_PLATFORM_ERROR", "WeChat could not complete this action. Try again later.");
  }
  private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
