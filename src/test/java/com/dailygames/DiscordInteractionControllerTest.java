package com.dailygames;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DiscordInteractionController.class)
@Import({SecurityConfig.class, DiscordSignatureVerifier.class})
@EnableWebSecurity
class DiscordInteractionControllerTest {
  private static final KeyPair KEY_PAIR = keyPair();
  private static final String APPLICATION_ID = "123456789012345678";

  @Autowired MockMvc mvc;
  @MockitoBean DiscordService discord;
  @MockitoBean AuthService auth;

  @DynamicPropertySource
  static void discordProperties(DynamicPropertyRegistry properties) {
    properties.add("app.discord.application-id", () -> APPLICATION_ID);
    properties.add("app.discord.public-key", DiscordInteractionControllerTest::rawPublicKeyHex);
  }

  @Test void validSignedPingIsPublicAndReturnsPong() throws Exception {
    byte[] body = ("{\"type\":1,\"application_id\":\"" + APPLICATION_ID + "\"}").getBytes(StandardCharsets.UTF_8);
    String timestamp = String.valueOf(Instant.now().getEpochSecond());
    mvc.perform(post("/api/v1/discord/interactions").contentType(MediaType.APPLICATION_JSON)
            .header("X-Signature-Timestamp", timestamp)
            .header("X-Signature-Ed25519", sign(timestamp, body)).content(body))
        .andExpect(status().isOk()).andExpect(jsonPath("$.type").value(1));
    verifyNoInteractions(discord);
  }

  @Test void rejectsTamperedBody() throws Exception {
    byte[] signedBody = ("{\"type\":1,\"application_id\":\"" + APPLICATION_ID + "\"}").getBytes(StandardCharsets.UTF_8);
    byte[] sentBody = ("{\"type\":2,\"application_id\":\"" + APPLICATION_ID + "\"}").getBytes(StandardCharsets.UTF_8);
    String timestamp = String.valueOf(Instant.now().getEpochSecond());
    mvc.perform(post("/api/v1/discord/interactions").contentType(MediaType.APPLICATION_JSON)
            .header("X-Signature-Timestamp", timestamp)
            .header("X-Signature-Ed25519", sign(timestamp, signedBody)).content(sentBody))
        .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_DISCORD_SIGNATURE"));
  }

  @Test void rejectsMissingAndMalformedHeaders() throws Exception {
    byte[] body = ("{\"type\":1,\"application_id\":\"" + APPLICATION_ID + "\"}").getBytes(StandardCharsets.UTF_8);
    mvc.perform(post("/api/v1/discord/interactions").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/api/v1/discord/interactions").contentType(MediaType.APPLICATION_JSON)
            .header("X-Signature-Timestamp", "not-a-time").header("X-Signature-Ed25519", "not-hex").content(body))
        .andExpect(status().isUnauthorized());
  }

  @Test void rejectsStaleTimestamp() throws Exception {
    byte[] body = ("{\"type\":1,\"application_id\":\"" + APPLICATION_ID + "\"}").getBytes(StandardCharsets.UTF_8);
    String timestamp = String.valueOf(Instant.now().minusSeconds(301).getEpochSecond());
    mvc.perform(post("/api/v1/discord/interactions").contentType(MediaType.APPLICATION_JSON)
            .header("X-Signature-Timestamp", timestamp)
            .header("X-Signature-Ed25519", sign(timestamp, body)).content(body))
        .andExpect(status().isUnauthorized());
  }

  @Test void rejectsSignedPayloadForAnotherApplication() throws Exception {
    byte[] body = "{\"type\":1,\"application_id\":\"999999999999999999\"}".getBytes(StandardCharsets.UTF_8);
    String timestamp = String.valueOf(Instant.now().getEpochSecond());
    mvc.perform(post("/api/v1/discord/interactions").contentType(MediaType.APPLICATION_JSON)
            .header("X-Signature-Timestamp", timestamp)
            .header("X-Signature-Ed25519", sign(timestamp, body)).content(body))
        .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_DISCORD_APPLICATION"));
  }

  @Test void neighboringDiscordRoutesStillRequireWebsiteAuthentication() throws Exception {
    mvc.perform(get("/api/v1/discord/status")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/v1/discord/link-codes")).andExpect(status().isUnauthorized());
  }

  private static KeyPair keyPair() {
    try { return KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); }
    catch (GeneralSecurityException exception) { throw new ExceptionInInitializerError(exception); }
  }

  private static String rawPublicKeyHex() {
    byte[] encoded = KEY_PAIR.getPublic().getEncoded();
    return HexFormat.of().formatHex(Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length));
  }

  private static String sign(String timestamp, byte[] body) {
    try {
      Signature signature = Signature.getInstance("Ed25519");
      signature.initSign(KEY_PAIR.getPrivate());
      signature.update(timestamp.getBytes(StandardCharsets.UTF_8));
      signature.update(body);
      return HexFormat.of().formatHex(signature.sign());
    } catch (GeneralSecurityException exception) {
      throw new AssertionError(exception);
    }
  }
}
