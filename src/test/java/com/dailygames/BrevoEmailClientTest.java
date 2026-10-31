package com.dailygames;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class BrevoEmailClientTest {
  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) server.stop(0);
  }

  @Test
  void sendsVerificationThroughBrevoApi() throws Exception {
    AtomicReference<String> apiKey = new AtomicReference<>();
    AtomicReference<String> body = new AtomicReference<>();
    startServer(exchange -> {
      apiKey.set(exchange.getRequestHeaders().getFirst("api-key"));
      body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      respond(exchange, 201, "{\"messageId\":\"test-message\"}");
    });

    client("test-api-key").sendVerification("friend@example.com", "https://example.com/api/v1/auth/verification/consume?token=safe-token", 24);

    assertEquals("test-api-key", apiKey.get());
    assertTrue(body.get().contains("\"sender\":{\"email\":\"noreply.dailyg@gmail.com\",\"name\":\"Daily Tracker\"}"));
    assertTrue(body.get().contains("\"to\":[{\"email\":\"friend@example.com\"}]"));
    assertTrue(body.get().contains("\"subject\":\"Verify your Daily Club email\""));
    assertTrue(body.get().contains("token=safe-token"));
    assertTrue(body.get().contains("\"tags\":[\"email-verification\"]"));
  }

  @Test
  void sendsPasswordResetThroughBrevoApi() throws Exception {
    AtomicReference<String> body = new AtomicReference<>();
    startServer(exchange -> {
      body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      respond(exchange, 201, "{\"messageId\":\"test-message\"}");
    });

    client("test-api-key").sendPasswordReset("friend@example.com",
        "https://example.com/auth/reset-password?token=safe-token", 30);

    assertTrue(body.get().contains("\"subject\":\"Reset your Daily Club password\""));
    assertTrue(body.get().contains("token=safe-token"));
    assertTrue(body.get().contains("\"tags\":[\"password-reset\"]"));
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 401, 429, 500})
  void mapsBrevoRejectionToSanitizedServiceError(int status) throws Exception {
    startServer(exchange -> respond(exchange, status, "{\"message\":\"provider error\"}"));

    ApiException exception = assertThrows(ApiException.class,
        () -> client("bad-api-key").sendVerification("friend@example.com", "https://example.com/secret-token", 24));

    assertEquals(503, exception.status);
    assertEquals("EMAIL_UNAVAILABLE", exception.code);
    assertEquals("Account email is temporarily unavailable. Please try again.", exception.getMessage());
  }

  @Test
  void requiresApiKeyAndVerifiedSenderConfiguration() {
    assertThrows(IllegalStateException.class,
        () -> new BrevoEmailClient(baseUrl(), "", "sender@example.com", "Daily Tracker"));
    assertThrows(IllegalStateException.class,
        () -> new BrevoEmailClient(baseUrl(), "test-api-key", "", "Daily Tracker"));
  }

  private BrevoEmailClient client(String apiKey) {
    return new BrevoEmailClient(baseUrl(), apiKey, "noreply.dailyg@gmail.com", "Daily Tracker");
  }

  private String baseUrl() {
    return server == null ? "http://127.0.0.1" : "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private void startServer(ExchangeHandler handler) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v3/smtp/email", exchange -> {
      try {
        handler.handle(exchange);
      } finally {
        exchange.close();
      }
    });
    server.start();
  }

  private void respond(HttpExchange exchange, int status, String response) throws IOException {
    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
  }

  @FunctionalInterface
  private interface ExchangeHandler {
    void handle(HttpExchange exchange) throws IOException;
  }
}
