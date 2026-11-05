package com.dailygames;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Service
class DiscordSignupService {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Duration LIFETIME = Duration.ofHours(24);
  private final DiscordSignupIntentRepository intents;
  private final DiscordChannelLeaderboardRepository channels;
  private final DiscordAccountLinkRepository links;
  private final ProfileRepository profiles;
  private final DailyGamesService games;
  private final DiscordSetupGateway discord;
  private final Clock clock;
  private final String baseUrl;
  private final String pepper;

  DiscordSignupService(DiscordSignupIntentRepository intents, DiscordChannelLeaderboardRepository channels,
      DiscordAccountLinkRepository links, ProfileRepository profiles, DailyGamesService games,
      DiscordSetupGateway discord, Clock clock, @Value("${app.public-base-url}") String baseUrl,
      @Value("${app.token-pepper}") String pepper) {
    this.intents = intents; this.channels = channels; this.links = links; this.profiles = profiles;
    this.games = games; this.discord = discord; this.clock = clock;
    this.baseUrl = baseUrl.replaceAll("/+$", ""); this.pepper = pepper;
  }

  @Transactional
  String issue(String discordUserId, String guildId, String channelId) {
    if (!discord.configured()) throw new ApiException(503, "DISCORD_NOT_CONFIGURED", "Discord signup is unavailable.");
    String token = random();
    UUID boardId = channels.findById(new DiscordChannelLeaderboardId(guildId, channelId))
        .map(mapping -> mapping.leaderboardId).orElse(null);
    intents.save(new DiscordSignupIntent(hash(token), discordUserId, guildId, channelId,
        boardId, clock.instant().plus(LIFETIME)));
    return baseUrl + "/discord/signup?intent=" + token;
  }

  @Transactional
  boolean valid(String token) {
    if (!tokenFormat(token)) return false;
    return intents.findByTokenHash(hash(token)).filter(intent -> intent.consumedAt == null
        && intent.expiresAt.isAfter(clock.instant())).isPresent();
  }

  @Transactional
  Map<String, String> authorize(UUID userId, String token) {
    if (!discord.configured()) throw new ApiException(503, "DISCORD_NOT_CONFIGURED", "Discord signup is unavailable.");
    if (!profiles.existsById(userId)) throw new ApiException(409, "PROFILE_REQUIRED", "Finish setting up your profile.");
    DiscordSignupIntent intent = require(token);
    if (intent.userId != null && !intent.userId.equals(userId)) throw new ApiException(409, "ACCOUNT_MISMATCH", "This signup belongs to a different account.");
    String state = random();
    intent.userId = userId;
    intent.oauthStateHash = hash(state);
    return Map.of("url", discord.identityAuthorizationUrl(state));
  }

  @Transactional
  String callback(UUID userId, String state, String code, String error) {
    if (!tokenFormat(state)) return null;
    DiscordSignupIntent intent = intents.findByOauthStateHash(hash(state)).orElse(null);
    if (intent == null) return null;
    String destination = baseUrl + "/discord/signup?status=";
    if (intent.consumedAt != null || !intent.expiresAt.isAfter(clock.instant())) return destination + "expired";
    if (userId == null || !userId.equals(intent.userId)) return destination + "account";
    if (error != null) return destination + "cancelled";
    if (code == null || code.isBlank() || code.length() > 2048) return destination + "failed";
    String accessToken = discord.exchange(code).path("access_token").asText("");
    if (accessToken.isBlank()) return destination + "failed";
    String actualDiscordId = discord.user(accessToken).path("id").asText("");
    if (!intent.discordUserId.equals(actualDiscordId)) return destination + "identity";
    DiscordAccountLink byDiscord = links.findById(actualDiscordId).orElse(null);
    DiscordAccountLink byUser = links.findByUserId(userId).orElse(null);
    if (byDiscord != null && !byDiscord.userId.equals(userId)
        || byUser != null && !byUser.discordUserId.equals(actualDiscordId)) return destination + "account";
    if (byDiscord == null) links.save(new DiscordAccountLink(actualDiscordId, userId, clock.instant()));
    intent.consumedAt = clock.instant();
    if (intent.leaderboardId == null) return destination + "linked";
    return destination + "pending";
  }

  @Transactional
  String join(UUID userId, String state) {
    String destination = baseUrl + "/discord/signup?status=";
    if (!tokenFormat(state)) return destination + "expired";
    DiscordSignupIntent intent = intents.findByOauthStateHash(hash(state)).orElse(null);
    if (intent == null || intent.consumedAt == null || !userId.equals(intent.userId)
        || intent.leaderboardId == null) return destination + "expired";
    UUID currentBoard = channels.findById(new DiscordChannelLeaderboardId(intent.guildId, intent.channelId))
        .map(mapping -> mapping.leaderboardId).orElse(null);
    if (!intent.leaderboardId.equals(currentBoard)) return destination + "mapping";
    try {
      games.addSignupMember(currentBoard, userId);
      return destination + "joined";
    } catch (ApiException exception) {
      return destination + ("OWNER_SUBSCRIPTION_REQUIRED".equals(exception.code) ? "locked" : "join-failed");
    }
  }

  @Transactional
  String failureRedirect(String state) {
    if (!tokenFormat(state)) return null;
    return intents.findByOauthStateHash(hash(state))
        .map(intent -> baseUrl + "/discord/signup?status=" + (intent.consumedAt == null ? "failed" : "join-failed"))
        .orElse(null);
  }

  private DiscordSignupIntent require(String token) {
    if (!tokenFormat(token)) throw expired();
    return intents.findByTokenHash(hash(token)).filter(intent -> intent.consumedAt == null
        && intent.expiresAt.isAfter(clock.instant())).orElseThrow(this::expired);
  }
  private ApiException expired() { return new ApiException(410, "SIGNUP_EXPIRED", "This Discord signup link has expired. Run /signup again."); }
  private boolean tokenFormat(String token) { return token != null && token.matches("[A-Za-z0-9_-]{43}"); }
  private String random() { byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
  private String hash(String value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
        ("discord-signup:v1:" + pepper + ":" + value).getBytes(StandardCharsets.UTF_8))); }
    catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
  }

  @Scheduled(cron = "0 11 4 * * *")
  @Transactional
  void cleanup() { intents.deleteByExpiresAtBefore(clock.instant().minus(Duration.ofDays(1))); }
}
