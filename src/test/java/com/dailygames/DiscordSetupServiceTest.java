package com.dailygames;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DiscordSetupServiceTest {
  private static final String USER_ID = "222222222222222222";
  private static final String GUILD_ID = "333333333333333333";
  private static final String BOT_ID = "444444444444444444";
  private static final String VISIBLE_ID = "555555555555555555";
  private static final String HIDDEN_ID = "666666666666666666";
  private final Instant now = Instant.parse("2026-11-04T12:00:00Z");
  private final UUID ownerId = UUID.randomUUID();
  private final UUID otherId = UUID.randomUUID();
  private final Leaderboard board = new Leaderboard(ownerId, "Club", UUID.randomUUID(), BoardCreationBasis.FREE, now);
  private final ObjectMapper json = new ObjectMapper();
  private DiscordSetupRepository setups;
  private LeaderboardRepository boards;
  private MembershipRepository members;
  private DiscordChannelLeaderboardRepository mappings;
  private DiscordAccountLinkRepository links;
  private BillingService billing;
  private DiscordSetupGateway discord;
  private DiscordCommandRegistrar commands;
  private DiscordSetupService service;
  private DiscordSetup stored;

  @BeforeEach void setUp() throws Exception {
    setups = mock(DiscordSetupRepository.class); boards = mock(LeaderboardRepository.class);
    members = mock(MembershipRepository.class); mappings = mock(DiscordChannelLeaderboardRepository.class);
    links = mock(DiscordAccountLinkRepository.class); billing = mock(BillingService.class);
    discord = mock(DiscordSetupGateway.class);
    commands = mock(DiscordCommandRegistrar.class);
    service = new DiscordSetupService(setups, boards, members, mappings, links, billing, discord, commands,
        Clock.fixed(now, ZoneOffset.UTC), "https://dailytracker.cc/", "test-pepper");
    when(boards.findById(board.id)).thenReturn(Optional.of(board));
    when(billing.allGameLimit(ownerId, now)).thenReturn(1);
    when(billing.isBoardAccessible(board, now)).thenReturn(true);
    when(discord.configured()).thenReturn(true);
    when(discord.authorizationUrl(anyString())).thenAnswer(call -> "https://discord.com/oauth2/authorize?state=" + call.getArgument(0));
    when(setups.save(any())).thenAnswer(call -> { stored = call.getArgument(0); return stored; });
    when(setups.findByStateHash(anyString())).thenAnswer(call -> Optional.ofNullable(stored));
    when(setups.findBySetupHash(anyString())).thenAnswer(call -> Optional.ofNullable(stored));
    when(discord.exchange("code")).thenReturn(node("{\"access_token\":\"secret-access-token\"}"));
    when(discord.user("secret-access-token")).thenReturn(node("{\"id\":\"" + USER_ID + "\"}"));
    when(discord.userGuilds("secret-access-token")).thenReturn(node("[{\"id\":\"" + GUILD_ID + "\",\"permissions\":\"32\"}]"));
    when(discord.guild(GUILD_ID)).thenReturn(node("{\"id\":\"" + GUILD_ID + "\",\"roles\":[{\"id\":\"" + GUILD_ID + "\",\"permissions\":\"1024\"}]}"));
    when(discord.botUser()).thenReturn(node("{\"id\":\"" + BOT_ID + "\"}"));
    when(discord.botMember(GUILD_ID, BOT_ID)).thenReturn(node("{\"roles\":[]}"));
    when(discord.channels(GUILD_ID)).thenReturn(node("["
        + "{\"id\":\"" + VISIBLE_ID + "\",\"name\":\"scores\",\"type\":0,\"permission_overwrites\":[]},"
        + "{\"id\":\"" + HIDDEN_ID + "\",\"name\":\"private\",\"type\":0,\"permission_overwrites\":[{\"id\":\"" + GUILD_ID + "\",\"deny\":\"1024\",\"allow\":\"0\"}]},"
        + "{\"id\":\"777777777777777777\",\"name\":\"thread\",\"type\":11}]"));
  }

  @Test void ownerAndPaidTierAreRequired() {
    assertEquals("NOT_OWNER", assertThrows(ApiException.class, () -> service.start(otherId, board.id)).code);
    verifyNoInteractions(discord);
    when(billing.allGameLimit(ownerId, now)).thenReturn(0);
    assertEquals("OWNER_SUBSCRIPTION_REQUIRED", assertThrows(ApiException.class,
        () -> service.start(ownerId, board.id)).code);
    when(billing.allGameLimit(ownerId, now)).thenReturn(1);
    when(billing.isBoardAccessible(board, now)).thenReturn(false);
    assertEquals("OWNER_SUBSCRIPTION_REQUIRED", assertThrows(ApiException.class,
        () -> service.start(ownerId, board.id)).code);
  }

  @Test void callbackLinksOwnerAndOffersOnlyVisibleTextChannels() {
    String state = start();
    String url = service.callback(ownerId, state, "code", null, GUILD_ID);
    assertTrue(url.contains("discord=ready&setup="));
    verify(links).save(argThat(link -> link.userId.equals(ownerId) && link.discordUserId.equals(USER_ID)));
    String token = url.substring(url.indexOf("&setup=") + 7);
    List<DiscordChannelView> available = service.available(ownerId, board.id, token);
    assertEquals(1, available.size());
    assertEquals(VISIBLE_ID, available.getFirst().channelId());
    assertFalse(stored.encryptedToken.contains("secret-access-token"));
    assertEquals("expired", service.callback(ownerId, state, "code", null, GUILD_ID).split("discord=")[1]);
  }

  @Test void callbackRejectsWrongAccountAndMissingManageServer() throws Exception {
    String state = start();
    when(links.findByUserId(ownerId)).thenReturn(Optional.of(new DiscordAccountLink("999999999999999999", ownerId, now)));
    assertTrue(service.callback(ownerId, state, "code", null, GUILD_ID).contains("discord=account"));
    verify(links, never()).save(any());
    when(links.findByUserId(ownerId)).thenReturn(Optional.empty());
    when(discord.userGuilds("secret-access-token")).thenReturn(node("[{\"id\":\"" + GUILD_ID + "\",\"permissions\":\"0\"}]"));
    assertEquals("MANAGE_GUILD_REQUIRED", assertThrows(ApiException.class,
        () -> service.callback(ownerId, state, "code", null, GUILD_ID)).code);
    assertTrue(service.failureRedirect(state, new ApiException(403, "MANAGE_GUILD_REQUIRED", ""))
        .contains("/leaderboards/" + board.id + "?discord=permissions"));
  }

  @Test void cancelAndExpiredStateCannotBeCompleted() {
    String state = start();
    assertTrue(service.callback(ownerId, state, null, "access_denied", null).contains("discord=cancelled"));
    assertTrue(service.callback(ownerId, state, "code", null, GUILD_ID).contains("discord=expired"));
    stored.consumedAt = null;
    stored.expiresAt = now.minusSeconds(1);
    assertTrue(service.callback(ownerId, state, "code", null, GUILD_ID).contains("discord=expired"));
  }

  @Test void unavailableChannelIsRejectedAndReplacementRequiresConfirmation() {
    String token = readyToken();
    assertEquals("CHANNEL_UNAVAILABLE", assertThrows(ApiException.class,
        () -> service.save(ownerId, board.id, token, HIDDEN_ID, false)).code);
    DiscordChannelLeaderboard existing = new DiscordChannelLeaderboard(GUILD_ID, VISIBLE_ID, otherId,
        otherId, USER_ID, now);
    when(mappings.findForUpdate(new DiscordChannelLeaderboardId(GUILD_ID, VISIBLE_ID)))
        .thenReturn(Optional.of(existing));
    assertEquals("CHANNEL_REPLACEMENT_REQUIRED", assertThrows(ApiException.class,
        () -> service.save(ownerId, board.id, token, VISIBLE_ID, false)).code);
    DiscordChannelView saved = service.save(ownerId, board.id, token, VISIBLE_ID, true);
    verify(commands).registerGuild(GUILD_ID);
    assertEquals(board.id, existing.leaderboardId);
    assertEquals("https://discord.com/channels/" + GUILD_ID + "/" + VISIBLE_ID, saved.url());
    assertNull(stored.encryptedToken);
    assertEquals("DISCORD_SETUP_EXPIRED", assertThrows(ApiException.class,
        () -> service.save(ownerId, board.id, token, VISIBLE_ID, true)).code);
  }

  @Test void failedCommandRegistrationDoesNotConnectChannel() {
    String token = readyToken();
    doThrow(new ApiException(502, "DISCORD_COMMANDS_UNAVAILABLE", "Discord commands could not be enabled."))
        .when(commands).registerGuild(GUILD_ID);
    assertEquals("DISCORD_COMMANDS_UNAVAILABLE", assertThrows(ApiException.class,
        () -> service.save(ownerId, board.id, token, VISIBLE_ID, false)).code);
    verify(mappings, never()).save(any());
    assertNull(stored.consumedAt);
  }

  private String readyToken() {
    String url = service.callback(ownerId, start(), "code", null, GUILD_ID);
    return url.substring(url.indexOf("&setup=") + 7);
  }
  private String start() {
    service.start(ownerId, board.id);
    var captor = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(discord).authorizationUrl(captor.capture());
    return captor.getValue();
  }
  private JsonNode node(String value) throws Exception { return json.readTree(value); }
}
