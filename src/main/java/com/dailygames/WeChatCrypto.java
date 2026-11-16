package com.dailygames;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

final class WeChatCrypto {
  record GroupEvidence(String groupId, String groupOpenid, int chatType) {}
  private final byte[] storageKey;
  private final byte[] hmacKey;
  private final String appId;
  private final ObjectMapper json;
  private final SecureRandom random = new SecureRandom();

  WeChatCrypto(String encodedStorageKey, String pepper, String appId, ObjectMapper json) {
    this.storageKey = encodedStorageKey.isBlank() ? new byte[0] : Base64.getDecoder().decode(encodedStorageKey);
    if (storageKey.length != 0 && storageKey.length != 32)
      throw new IllegalArgumentException("WECHAT_SESSION_ENCRYPTION_KEY must contain 32 base64-encoded bytes");
    this.hmacKey = pepper.getBytes(StandardCharsets.UTF_8);
    this.appId = appId;
    this.json = json;
  }

  boolean configured() { return storageKey.length == 32 && !appId.isBlank(); }

  String protect(String sessionKey) {
    try {
      byte[] nonce = new byte[12]; random.nextBytes(nonce);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(storageKey, "AES"), new GCMParameterSpec(128, nonce));
      byte[] encrypted = cipher.doFinal(sessionKey.getBytes(StandardCharsets.UTF_8));
      byte[] combined = new byte[nonce.length + encrypted.length];
      System.arraycopy(nonce, 0, combined, 0, nonce.length);
      System.arraycopy(encrypted, 0, combined, nonce.length, encrypted.length);
      return Base64.getEncoder().encodeToString(combined);
    } catch (Exception error) { throw new IllegalStateException("Cannot protect WeChat session", error); }
  }

  String reveal(String encrypted) {
    try {
      byte[] combined = Base64.getDecoder().decode(encrypted);
      if (combined.length < 29) throw new IllegalArgumentException();
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(storageKey, "AES"),
          new GCMParameterSpec(128, combined, 0, 12));
      return new String(cipher.doFinal(combined, 12, combined.length - 12), StandardCharsets.UTF_8);
    } catch (Exception error) { throw new ApiException(400, "WECHAT_SESSION_EXPIRED", "Sign in with WeChat again."); }
  }

  String groupId(String sessionKey, String encryptedData, String iv, Instant now) {
    return groupEvidence(sessionKey, encryptedData, iv, now).groupId();
  }

  GroupEvidence groupEvidence(String sessionKey, String encryptedData, String iv, Instant now) {
    try {
      byte[] key = Base64.getDecoder().decode(sessionKey);
      byte[] initialization = Base64.getDecoder().decode(iv);
      byte[] ciphertext = Base64.getDecoder().decode(encryptedData);
      if (key.length != 16 || initialization.length != 16 || ciphertext.length == 0 || ciphertext.length > 4096)
        throw new IllegalArgumentException();
      Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
      cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(initialization));
      JsonNode payload = json.readTree(cipher.doFinal(ciphertext));
      String group = payload.path("opengid").asText("");
      if (group.isBlank()) group = payload.path("openGId").asText("");
      String member = payload.path("group_openid").asText("");
      int chatType = payload.path("chat_type").asInt(0);
      JsonNode watermark = payload.path("watermark");
      long timestamp = watermark.path("timestamp").asLong(0);
      if (group.isBlank() || group.length() > 256 || (chatType != 0 && chatType != 3)
          || member.length() > 256 || !appId.equals(watermark.path("appid").asText())
          || Math.abs(now.getEpochSecond() - timestamp) > 600)
        throw new IllegalArgumentException();
      return new GroupEvidence(group, member, chatType);
    } catch (Exception error) {
      throw new ApiException(400, "WECHAT_GROUP_PROOF_INVALID", "We could not verify this group launch. Use the invitation instead.");
    }
  }

  String hash(String purpose, String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(("wechat:" + purpose + ":" + appId + ":" + value).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception error) { throw new IllegalStateException("Cannot hash WeChat identifier", error); }
  }

  String randomToken() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
  static boolean validToken(String token) { return token != null && token.matches("[A-Za-z0-9_-]{43}"); }
}
