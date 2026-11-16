package com.dailygames;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
class WeChatGateway {
  record Session(String openid, String sessionKey) {}

  private final String appId;
  private final String appSecret;
  private final ObjectMapper json;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  WeChatGateway(@Value("${app.wechat.app-id:}") String appId,
                @Value("${app.wechat.app-secret:}") String appSecret, ObjectMapper json) {
    this.appId = appId; this.appSecret = appSecret; this.json = json;
  }

  Session exchange(String code) {
    if (code == null || code.isBlank() || code.length() > 128)
      throw new ApiException(400, "WECHAT_CODE_INVALID", "Try signing in with WeChat again.");
    try {
      String url = "https://api.weixin.qq.com/sns/jscode2session?appid=" + encode(appId)
          + "&secret=" + encode(appSecret) + "&js_code=" + encode(code) + "&grant_type=authorization_code";
      HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) throw new IllegalStateException("WeChat returned a non-success status");
      JsonNode body = json.readTree(response.body());
      String openid = body.path("openid").asText("");
      String sessionKey = body.path("session_key").asText("");
      if (openid.isBlank() || sessionKey.isBlank()) throw new IllegalStateException("WeChat did not return a session");
      return new Session(openid, sessionKey);
    } catch (Exception error) {
      if (error instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new ApiException(502, "WECHAT_LOGIN_FAILED", "WeChat sign-in failed. Please try again.");
    }
  }

  private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
