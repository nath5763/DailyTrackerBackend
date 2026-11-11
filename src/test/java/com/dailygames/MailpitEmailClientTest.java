package com.dailygames;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MailpitEmailClientTest {
  private HttpServer server;

  @AfterEach void stopServer() { if (server != null) server.stop(0); }

  @Test void sendsVerificationAndResetToMailpitWebrootWithDedicatedCredentials() throws Exception {
    AtomicReference<String> auth = new AtomicReference<>();
    AtomicReference<String> body = new AtomicReference<>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/mailpit/api/v1/send", exchange -> {
      auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
      body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      respond(exchange, 200);
    });
    server.start();
    MailpitEmailClient client = client("sender:password");

    client.sendVerification("friend@example.com", "https://staging.example/api/v1/auth/verification/consume?token=safe", 24);
    assertEquals("Basic " + Base64.getEncoder().encodeToString("sender:password".getBytes(StandardCharsets.UTF_8)), auth.get());
    assertTrue(body.get().contains("\"From\":{\"Email\":\"load-test@example.com\""));
    assertTrue(body.get().contains("\"To\":[{\"Email\":\"friend@example.com\""));
    assertTrue(body.get().contains("token=safe"));
    assertTrue(body.get().contains("\"Tags\":[\"email-verification\"]"));

    client.sendPasswordReset("friend@example.com", "https://staging.example/auth/reset-password?token=reset", 30);
    assertTrue(body.get().contains("token=reset"));
    assertTrue(body.get().contains("\"Tags\":[\"password-reset\"]"));
  }

  @Test void rejectsMissingSendCredentials() {
    assertThrows(IllegalStateException.class, () -> new MailpitEmailClient("http://localhost:8025/mailpit", "", "load-test@example.com", "Daily Tracker"));
  }

  @Test void sanitizesMailpitFailure() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/mailpit/api/v1/send", exchange -> respond(exchange, 401));
    server.start();
    ApiException exception = assertThrows(ApiException.class,
        () -> client("sender:wrong").sendVerification("friend@example.com", "https://staging.example/secret", 24));
    assertEquals(503, exception.status);
    assertEquals("EMAIL_UNAVAILABLE", exception.code);
  }

  private MailpitEmailClient client(String auth) {
    return new MailpitEmailClient("http://127.0.0.1:" + server.getAddress().getPort() + "/mailpit",
        auth, "load-test@example.com", "Daily Tracker");
  }

  private void respond(HttpExchange exchange, int status) throws IOException {
    byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
