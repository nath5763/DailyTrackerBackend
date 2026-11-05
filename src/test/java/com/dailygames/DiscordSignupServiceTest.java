package com.dailygames;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DiscordSignupServiceTest {
  private static final String DISCORD_ID = "222222222222222222";
  private static final String GUILD = "333333333333333333";
  private static final String CHANNEL = "444444444444444444";
  private final Instant now = Instant.parse("2026-11-04T12:00:00Z");
  private DiscordSignupIntentRepository intents;
  private DiscordChannelLeaderboardRepository channels;
  private DiscordAccountLinkRepository links;
  private ProfileRepository profiles;
  private DailyGamesService games;
  private DiscordSetupGateway discord;
  private DiscordSignupService service;
  private DiscordSignupIntent saved;
  private UUID userId;
  private UUID boardId;
  private String token;

  @BeforeEach void setUp() throws Exception {
    intents = mock(DiscordSignupIntentRepository.class);
    channels = mock(DiscordChannelLeaderboardRepository.class);
    links = mock(DiscordAccountLinkRepository.class);
    profiles = mock(ProfileRepository.class);
    games = mock(DailyGamesService.class);
    discord = mock(DiscordSetupGateway.class);
    when(discord.configured()).thenReturn(true);
    when(discord.identityAuthorizationUrl(anyString())).thenAnswer(call -> "identity:" + call.getArgument(0));
    when(discord.exchange("oauth-code")).thenReturn(new ObjectMapper().readTree("{\"access_token\":\"access\"}"));
    when(discord.user("access")).thenReturn(new ObjectMapper().readTree("{\"id\":\"" + DISCORD_ID + "\"}"));
    service = new DiscordSignupService(intents, channels, links, profiles, games, discord,
        Clock.fixed(now, ZoneOffset.UTC), "https://dailytracker.cc", "pepper");
    userId = UUID.randomUUID(); boardId = UUID.randomUUID();
    when(profiles.existsById(userId)).thenReturn(true);
    when(channels.findById(any())).thenReturn(Optional.of(new DiscordChannelLeaderboard(
        GUILD, CHANNEL, boardId, userId, DISCORD_ID, now)));
    when(intents.save(any())).thenAnswer(call -> { saved = call.getArgument(0); return saved; });
    String url = service.issue(DISCORD_ID, GUILD, CHANNEL);
    token = url.substring(url.indexOf("intent=") + 7);
    when(intents.findByTokenHash(anyString())).thenAnswer(call -> Optional.of(saved));
    when(intents.findByOauthStateHash(anyString())).thenAnswer(call ->
        saved.oauthStateHash == null ? Optional.empty() : Optional.of(saved));
  }

  private String authorize() { return service.authorize(userId, token).get("url").substring("identity:".length()); }

  @Test void storesOnlyHashedIntentAndJoinsExactMappedBoardOnce() {
    assertEquals(boardId, saved.leaderboardId);
    assertEquals(now.plusSeconds(24 * 3600), saved.expiresAt);
    assertFalse(saved.tokenHash.contains(token));
    assertTrue(service.valid(token));
    String state = authorize();
    assertEquals("https://dailytracker.cc/discord/signup?status=pending",
        service.callback(userId, state, "oauth-code", null));
    assertEquals("https://dailytracker.cc/discord/signup?status=joined", service.join(userId, state));
    verify(links).save(argThat(link -> link.userId.equals(userId) && link.discordUserId.equals(DISCORD_ID)));
    verify(games).addSignupMember(boardId, userId);
    assertFalse(service.valid(token));
    assertTrue(service.callback(userId, state, "oauth-code", null).endsWith("status=expired"));
    assertEquals("SIGNUP_EXPIRED", assertThrows(ApiException.class, () -> service.authorize(userId, token)).code);
  }

  @Test void identityMismatchCannotLinkOrJoin() throws Exception {
    String state = authorize();
    when(discord.user("access")).thenReturn(new ObjectMapper().readTree("{\"id\":\"999999999999999999\"}"));
    assertTrue(service.callback(userId, state, "oauth-code", null).endsWith("status=identity"));
    verifyNoInteractions(links, games);
    assertNull(saved.consumedAt);
  }

  @Test void changedMappingPreservesAccountWithoutJoining() {
    String state = authorize();
    when(channels.findById(any())).thenReturn(Optional.of(new DiscordChannelLeaderboard(
        GUILD, CHANNEL, UUID.randomUUID(), userId, DISCORD_ID, now)));
    assertTrue(service.callback(userId, state, "oauth-code", null).endsWith("status=pending"));
    assertTrue(service.join(userId, state).endsWith("status=mapping"));
    verify(links).save(any());
    verifyNoInteractions(games);
  }

  @Test void lockedBoardPreservesLinkAndExplainsFailure() {
    String state = authorize();
    doThrow(new ApiException(402, "OWNER_SUBSCRIPTION_REQUIRED", "Locked"))
        .when(games).addSignupMember(boardId, userId);
    assertTrue(service.callback(userId, state, "oauth-code", null).endsWith("status=pending"));
    assertTrue(service.join(userId, state).endsWith("status=locked"));
    verify(links).save(any());
    assertNotNull(saved.consumedAt);
  }

  @Test void unconnectedChannelLinksWithoutJoining() {
    when(channels.findById(any())).thenReturn(Optional.empty());
    String second = service.issue(DISCORD_ID, GUILD, CHANNEL);
    String secondToken = second.substring(second.indexOf("intent=") + 7);
    String state = service.authorize(userId, secondToken).get("url").substring("identity:".length());
    assertTrue(service.callback(userId, state, "oauth-code", null).endsWith("status=linked"));
    verifyNoInteractions(games);
  }
}
