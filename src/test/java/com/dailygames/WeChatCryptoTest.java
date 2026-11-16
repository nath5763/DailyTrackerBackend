package com.dailygames;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class WeChatCryptoTest {
  private static final String APP = "wx-test-app";
  private static final String SESSION = Base64.getEncoder().encodeToString("0123456789abcdef".getBytes(StandardCharsets.UTF_8));
  private static final String STORAGE = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
  private final WeChatCrypto crypto = new WeChatCrypto(STORAGE, "private-pepper", APP, new ObjectMapper());

  @Test void storageCipherIsRandomizedAndTamperResistant() {
    String first = crypto.protect(SESSION);
    String second = crypto.protect(SESSION);
    assertNotEquals(first, second);
    assertEquals(SESSION, crypto.reveal(first));
    byte[] changed = Base64.getDecoder().decode(first);
    changed[changed.length - 1] ^= 1;
    assertEquals("WECHAT_SESSION_EXPIRED", assertThrows(ApiException.class,
        () -> crypto.reveal(Base64.getEncoder().encodeToString(changed))).code);
  }

  @Test void groupEvidenceRequiresCorrectAppAndFreshWatermark() throws Exception {
    Instant now = Instant.parse("2026-11-16T12:00:00Z");
    String valid = encrypted("group-A", APP, now.getEpochSecond());
    assertEquals("group-A", crypto.groupId(SESSION, valid, iv(), now));
    assertEquals("member-A", crypto.groupEvidence(SESSION, valid, iv(), now).groupOpenid());
    assertEquals(3, crypto.groupEvidence(SESSION, valid, iv(), now).chatType());
    assertEquals("WECHAT_GROUP_PROOF_INVALID", assertThrows(ApiException.class,
        () -> crypto.groupId(SESSION, encrypted("group-A", "another-app", now.getEpochSecond()), iv(), now)).code);
    assertEquals("WECHAT_GROUP_PROOF_INVALID", assertThrows(ApiException.class,
        () -> crypto.groupId(SESSION, encrypted("group-A", APP, now.minusSeconds(900).getEpochSecond()), iv(), now)).code);
    assertNotEquals(crypto.hash("group", "group-A"), crypto.hash("group", "group-B"));
    assertNotEquals(crypto.hash("group", "group-A"), crypto.hash("openid", "group-A"));
  }

  private static String iv() { return Base64.getEncoder().encodeToString("fedcba9876543210".getBytes(StandardCharsets.UTF_8)); }
  private static String encrypted(String group, String app, long timestamp) throws Exception {
    String body = "{\"opengid\":\"" + group + "\",\"chat_type\":3,\"group_openid\":\"member-A\",\"watermark\":{\"appid\":\"" + app + "\",\"timestamp\":" + timestamp + "}}";
    Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(Base64.getDecoder().decode(SESSION), "AES"),
        new IvParameterSpec(Base64.getDecoder().decode(iv())));
    return Base64.getEncoder().encodeToString(cipher.doFinal(body.getBytes(StandardCharsets.UTF_8)));
  }
}
