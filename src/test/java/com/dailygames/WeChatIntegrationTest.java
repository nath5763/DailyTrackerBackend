package com.dailygames;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class WeChatIntegrationTest {
  @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
  private static final String APP = "wx-test-app";
  private static final String SESSION = Base64.getEncoder().encodeToString("0123456789abcdef".getBytes(StandardCharsets.UTF_8));
  private static final String IV = Base64.getEncoder().encodeToString("fedcba9876543210".getBytes(StandardCharsets.UTF_8));

  @DynamicPropertySource static void config(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    properties.add("spring.datasource.username", POSTGRES::getUsername);
    properties.add("spring.datasource.password", POSTGRES::getPassword);
    properties.add("app.wechat.enabled", () -> "true");
    properties.add("app.wechat.group-context-enabled", () -> "true");
    properties.add("app.wechat.chat-tool-approved", () -> "true");
    properties.add("app.wechat.chat-tool-enabled", () -> "true");
    properties.add("app.wechat.app-id", () -> APP);
    properties.add("app.wechat.app-secret", () -> "test-only-secret");
    properties.add("app.wechat.session-encryption-key", () -> Base64.getEncoder().encodeToString(
        "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
  }

  @Autowired WeChatService wechat;
  @Autowired AuthService auth;
  @Autowired DailyGamesService daily;
  @Autowired UserRepository users;
  @Autowired WeChatChatToolService chatTool;
  @MockitoBean WeChatGateway gateway;

  @Test void accountLinkInviteVerifiedGroupAndUnlinkReuseExistingRules() throws Exception {
    UserAccount existing = users.save(new UserAccount("wechat-existing@example.com", Instant.now()));
    daily.onboard(new CurrentUser(existing.id, existing.normalizedEmail),
        new ApiModels.OnboardingRequest("Existing Friend", "#dfff4f"));
    String websiteToken = auth.loginExternal(existing.id).sessionToken();
    when(gateway.exchange("first-code")).thenReturn(new WeChatGateway.Session("first-openid", SESSION));
    WeChatService.SessionResult initial = wechat.login("first-code", "127.0.0.1");
    UUID provisional = auth.authenticate(initial.token()).orElseThrow().id();
    assertFalse(initial.linkedAccount());
    assertNotEquals(existing.id, provisional);

    String linkCode = wechat.issueCode(existing.id, websiteToken).code();
    WeChatService.SessionResult linked = wechat.redeemCode(provisional, initial.token(), linkCode);
    assertEquals(existing.id, auth.authenticate(linked.token()).orElseThrow().id());
    assertTrue(linked.linkedAccount());
    assertTrue(auth.authenticate(initial.token()).isEmpty());
    assertEquals("WECHAT_ALREADY_LINKED", assertThrows(ApiException.class,
        () -> wechat.redeemCode(existing.id, linked.token(), linkCode)).code);

    UUID boardId = daily.createBoard(new CurrentUser(existing.id, existing.normalizedEmail),
        new ApiModels.CreateLeaderboardRequest("WeChat Friends", "fermi"));
    WeChatService.ShareResult card = wechat.createShare(existing.id, boardId);
    assertEquals(boardId, wechat.previewShare(existing.id, card.reference()).leaderboardId());
    assertEquals("WECHAT_SHARE_EXPIRED", assertThrows(ApiException.class,
        () -> wechat.previewShare(existing.id, card.reference() + "x")).code);

    when(gateway.exchange("second-code")).thenReturn(new WeChatGateway.Session("second-openid", SESSION));
    WeChatService.SessionResult friend = wechat.login("second-code", "127.0.0.2");
    UUID friendId = auth.authenticate(friend.token()).orElseThrow().id();
    assertEquals("WECHAT_LINK_CODE_INVALID", assertThrows(ApiException.class,
        () -> wechat.redeemCode(friendId, friend.token(), linkCode)).code);
    daily.onboard(new CurrentUser(friendId, "wechat-friend@accounts.dailytracker.invalid"),
        new ApiModels.OnboardingRequest("Second Friend", "#ff8db6"));

    String proof = groupProof("group-one");
    assertFalse(wechat.resolveGroup(existing.id, linked.token(), proof, IV).mapped());
    WeChatService.GroupLinkView mapping = wechat.linkGroup(existing.id, linked.token(), boardId, true);
    WeChatService.GroupResult beforeJoin = wechat.resolveGroup(friendId, friend.token(), proof, IV);
    assertTrue(beforeJoin.mapped());
    assertNull(beforeJoin.leaderboardId());
    assertEquals(boardId, wechat.joinShare(friendId, card.reference()));
    assertEquals(boardId, wechat.currentGroup(friendId, friend.token()).leaderboardId());
    assertEquals("NOT_OWNER", assertThrows(ApiException.class,
        () -> wechat.disconnectGroup(friendId, mapping.id())).code);
    wechat.disconnectGroup(existing.id, mapping.id());
    assertFalse(wechat.resolveGroup(friendId, friend.token(), proof, IV).mapped());

    wechat.unlink(existing.id);
    assertFalse(wechat.isMiniToken(linked.token()));
    assertTrue(auth.authenticate(websiteToken).isPresent());
    assertNotNull(daily.board(new CurrentUser(existing.id, existing.normalizedEmail), boardId, "today", "fermi"));
  }

  @Test void chatToolUsesVerifiedGroupAndExistingOwnerRules() throws Exception {
    when(gateway.exchange("chat-owner-code"))
        .thenReturn(new WeChatGateway.Session("chat-owner-openid", SESSION));
    WeChatService.SessionResult owner = wechat.login("chat-owner-code", "127.0.0.3");
    UUID ownerId = auth.authenticate(owner.token()).orElseThrow().id();
    daily.onboard(new CurrentUser(ownerId, "chat-owner@accounts.dailytracker.invalid"),
        new ApiModels.OnboardingRequest("Chat Owner", "#dfff4f"));
    UUID boardId = daily.createBoard(new CurrentUser(ownerId, "chat-owner@accounts.dailytracker.invalid"),
        new ApiModels.CreateLeaderboardRequest("Chat Friends", "fermi"));

    assertTrue(chatTool.capabilities().enabled());
    assertFalse(chatTool.capabilities().dynamicCardsEnabled());
    assertFalse(chatTool.resolve(ownerId, owner.token(), groupProof("chat-group"), IV).mapped());
    assertEquals("WECHAT_GROUP_NOT_LINKED", assertThrows(ApiException.class,
        () -> chatTool.prepareCard(ownerId, owner.token(), boardId)).code);
    wechat.linkGroup(ownerId, owner.token(), boardId, true);
    WeChatChatToolService.ChatShare card = chatTool.prepareCard(ownerId, owner.token(), boardId);
    assertNull(card.activityId());
    assertTrue(card.path().startsWith("/chat-tool/pages/index/index?boardId=" + boardId + "&ref="));
    assertEquals(boardId, chatTool.resolve(ownerId, owner.token(), groupProof("chat-group"), IV).leaderboardId());
    assertEquals("WECHAT_GROUP_REQUIRED", assertThrows(ApiException.class,
        () -> chatTool.resolve(ownerId, owner.token(), directChatProof(), IV)).code);
  }

  private static String groupProof(String group) throws Exception {
    String body = "{\"opengid\":\"" + group + "\",\"chat_type\":3,\"group_openid\":\"member-A\",\"watermark\":{\"appid\":\"" + APP
        + "\",\"timestamp\":" + Instant.now().getEpochSecond() + "}}";
    Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(Base64.getDecoder().decode(SESSION), "AES"),
        new IvParameterSpec(Base64.getDecoder().decode(IV)));
    return Base64.getEncoder().encodeToString(cipher.doFinal(body.getBytes(StandardCharsets.UTF_8)));
  }

  private static String directChatProof() throws Exception {
    String body = "{\"opengid\":\"direct-chat\",\"chat_type\":0,\"group_openid\":\"member-A\",\"watermark\":{\"appid\":\"" + APP
        + "\",\"timestamp\":" + Instant.now().getEpochSecond() + "}}";
    Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(Base64.getDecoder().decode(SESSION), "AES"),
        new IvParameterSpec(Base64.getDecoder().decode(IV)));
    return Base64.getEncoder().encodeToString(cipher.doFinal(body.getBytes(StandardCharsets.UTF_8)));
  }
}
