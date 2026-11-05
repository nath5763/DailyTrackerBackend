package com.dailygames;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
class DiscordSetupService {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Duration LIFETIME = Duration.ofMinutes(10);
  private static final BigInteger VIEW_CHANNEL = BigInteger.valueOf(1024);
  private static final BigInteger ADMINISTRATOR = BigInteger.valueOf(8);
  private static final BigInteger MANAGE_GUILD = BigInteger.valueOf(32);
  private final DiscordSetupRepository setups;
  private final LeaderboardRepository boards;
  private final MembershipRepository members;
  private final DiscordChannelLeaderboardRepository mappings;
  private final DiscordAccountLinkRepository links;
  private final BillingService billing;
  private final DiscordSetupGateway discord;
  private final DiscordCommandRegistrar commands;
  private final Clock clock;
  private final String publicBaseUrl;
  private final SecretKeySpec tokenKey;

  DiscordSetupService(DiscordSetupRepository setups, LeaderboardRepository boards, MembershipRepository members,
      DiscordChannelLeaderboardRepository mappings, DiscordAccountLinkRepository links, BillingService billing,
      DiscordSetupGateway discord, DiscordCommandRegistrar commands, Clock clock,
      @Value("${app.public-base-url}") String publicBaseUrl,
      @Value("${app.token-pepper}") String pepper) {
    this.setups = setups; this.boards = boards; this.members = members; this.mappings = mappings;
    this.links = links; this.billing = billing; this.discord = discord; this.commands = commands; this.clock = clock;
    this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
    this.tokenKey = new SecretKeySpec(hashBytes("discord-oauth-token-key:v1:" + pepper), "AES");
  }

  boolean configured() { return discord.configured(); }

  @Transactional
  Map<String, String> start(UUID userId, UUID boardId) {
    requireOwner(userId, boardId);
    if (!discord.configured()) throw new ApiException(503, "DISCORD_NOT_CONFIGURED", "Discord setup is not available yet.");
    String state = random();
    setups.save(new DiscordSetup(userId, boardId, hash(state), clock.instant().plus(LIFETIME)));
    return Map.of("url", discord.authorizationUrl(state));
  }

  @Transactional
  String callback(UUID userId, String state, String code, String error, String guildHint) {
    if (state == null || !state.matches("[A-Za-z0-9_-]{43}")) return redirect(null, "expired");
    DiscordSetup setup = setups.findByStateHash(hash(state)).orElse(null);
    if (setup == null) return redirect(null, "expired");
    if (userId == null || !setup.userId.equals(userId)) return redirect(setup.leaderboardId, "account");
    if (setup.setupHash != null || setup.consumedAt != null || !setup.expiresAt.isAfter(clock.instant()))
      return redirect(setup.leaderboardId, "expired");
    if (error != null) { setup.consumedAt = clock.instant(); return redirect(setup.leaderboardId, "cancelled"); }
    if (code == null || code.isBlank() || code.length() > 2048) return redirect(setup.leaderboardId, "failed");
    requireOwner(userId, setup.leaderboardId);
    JsonNode token = discord.exchange(code);
    String accessToken = token.path("access_token").asText("");
    if (accessToken.isBlank() || accessToken.length() > 1500) return redirect(setup.leaderboardId, "failed");
    String discordUserId = snowflake(discord.user(accessToken).path("id").asText(""));
    DiscordAccountLink existingUser = links.findByUserId(userId).orElse(null);
    DiscordAccountLink existingDiscord = links.findById(discordUserId).orElse(null);
    if (existingUser != null && !existingUser.discordUserId.equals(discordUserId)
        || existingDiscord != null && !existingDiscord.userId.equals(userId))
      return redirect(setup.leaderboardId, "account");
    String guildId = snowflake(guildHint);
    verifyUser(accessToken, guildId);
    verifyBot(guildId);
    if (existingUser == null) links.save(new DiscordAccountLink(discordUserId, userId, clock.instant()));
    String setupToken = random();
    setup.setupHash = hash(setupToken);
    setup.discordUserId = discordUserId;
    setup.guildId = guildId;
    setup.encryptedToken = encrypt(accessToken);
    setup.expiresAt = clock.instant().plus(LIFETIME);
    return redirect(setup.leaderboardId, "ready") + "&setup=" + setupToken;
  }

  @Transactional
  String failureRedirect(String state, ApiException exception) {
    UUID boardId = state != null && state.matches("[A-Za-z0-9_-]{43}")
        ? setups.findByStateHash(hash(state)).map(value -> value.leaderboardId).orElse(null) : null;
    String status = switch (exception.code) {
      case "MANAGE_GUILD_REQUIRED", "DISCORD_GUILD_REQUIRED" -> "permissions";
      case "BOT_NOT_INSTALLED" -> "bot";
      case "OWNER_SUBSCRIPTION_REQUIRED" -> "plan";
      default -> "failed";
    };
    return redirect(boardId, status);
  }

  List<DiscordChannelView> connected(UUID userId, UUID boardId) {
    Leaderboard board = boards.findById(boardId)
        .orElseThrow(() -> new ApiException(404, "LEADERBOARD_NOT_FOUND", "Leaderboard not found."));
    if (!board.ownerId.equals(userId) && !members.existsByIdLeaderboardIdAndIdUserId(boardId, userId))
      throw new ApiException(403, "NOT_MEMBER", "You cannot view this leaderboard.");
    return mappings.findByLeaderboardId(boardId).stream()
        .map(mapping -> new DiscordChannelView(mapping.id.guildId, mapping.id.channelId, mapping.channelName, boardId,
            channelUrl(mapping.id.guildId, mapping.id.channelId))).toList();
  }

  @Transactional
  void disconnect(UUID userId, UUID boardId, String guildId, String channelId) {
    Leaderboard board = boards.findById(boardId)
        .orElseThrow(() -> new ApiException(404, "LEADERBOARD_NOT_FOUND", "Leaderboard not found."));
    if (!board.ownerId.equals(userId))
      throw new ApiException(403, "NOT_OWNER", "Only the owner can disconnect this channel.");
    DiscordChannelLeaderboard mapping = mappings.findForUpdate(
        new DiscordChannelLeaderboardId(snowflake(guildId), snowflake(channelId)))
        .filter(value -> value.leaderboardId.equals(boardId))
        .orElseThrow(() -> new ApiException(404, "CHANNEL_NOT_CONNECTED", "This channel is not connected to this leaderboard."));
    mappings.delete(mapping);
  }

  @Transactional
  List<DiscordChannelView> available(UUID userId, UUID boardId, String setupToken) {
    DiscordSetup setup = requireSetup(userId, boardId, setupToken);
    verifyUser(decrypt(setup.encryptedToken), setup.guildId);
    GuildContext context = verifyBot(setup.guildId);
    return eligibleChannels(setup.guildId, context).stream().map(channel -> {
      String channelId = channel.path("id").asText();
      UUID mapped = mappings.findById(new DiscordChannelLeaderboardId(setup.guildId, channelId))
          .map(value -> value.leaderboardId).orElse(null);
      return new DiscordChannelView(setup.guildId, channelId, channel.path("name").asText(), mapped,
          channelUrl(setup.guildId, channelId));
    }).toList();
  }

  @Transactional
  DiscordChannelView save(UUID userId, UUID boardId, String setupToken, String channelId, boolean replace) {
    DiscordSetup setup = requireSetup(userId, boardId, setupToken);
    channelId = snowflake(channelId);
    verifyUser(decrypt(setup.encryptedToken), setup.guildId);
    GuildContext context = verifyBot(setup.guildId);
    final String selectedId = channelId;
    JsonNode selected = eligibleChannels(setup.guildId, context).stream()
        .filter(channel -> selectedId.equals(channel.path("id").asText())).findFirst()
        .orElseThrow(() -> new ApiException(409, "CHANNEL_UNAVAILABLE", "This channel is unavailable to the bot."));
    DiscordChannelLeaderboardId mappingId = new DiscordChannelLeaderboardId(setup.guildId, channelId);
    DiscordChannelLeaderboard mapping = mappings.findForUpdate(mappingId).orElse(null);
    if (mapping != null && !mapping.leaderboardId.equals(boardId) && !replace)
      throw new ApiException(409, "CHANNEL_REPLACEMENT_REQUIRED", "This channel is connected to another leaderboard. Confirm replacement.");
    commands.registerGuild(setup.guildId);
    Instant now = clock.instant();
    if (mapping == null) mapping = new DiscordChannelLeaderboard(setup.guildId, channelId, boardId,
        userId, setup.discordUserId, now);
    mapping.leaderboardId = boardId;
    mapping.channelName = selected.path("name").asText("");
    mapping.configuredByUserId = userId;
    mapping.configuredByDiscordUserId = setup.discordUserId;
    mapping.updatedAt = now;
    mappings.save(mapping);
    setup.consumedAt = now;
    setup.encryptedToken = null;
    setup.setupHash = null;
    return new DiscordChannelView(setup.guildId, channelId, selected.path("name").asText(), boardId,
        channelUrl(setup.guildId, channelId));
  }

  private DiscordSetup requireSetup(UUID userId, UUID boardId, String token) {
    requireOwner(userId, boardId);
    if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw expired();
    DiscordSetup setup = setups.findBySetupHash(hash(token)).orElseThrow(this::expired);
    if (!setup.userId.equals(userId) || !setup.leaderboardId.equals(boardId) || setup.consumedAt != null
        || !setup.expiresAt.isAfter(clock.instant())) throw expired();
    return setup;
  }

  private Leaderboard requireOwner(UUID userId, UUID boardId) {
    Leaderboard board = boards.findById(boardId)
        .orElseThrow(() -> new ApiException(404, "LEADERBOARD_NOT_FOUND", "Leaderboard not found."));
    if (!board.ownerId.equals(userId)) throw new ApiException(403, "NOT_OWNER", "Only the owner can connect this leaderboard.");
    if (billing.allGameLimit(userId, clock.instant()) == 0 || !billing.isBoardAccessible(board, clock.instant()))
      throw new ApiException(402, "OWNER_SUBSCRIPTION_REQUIRED", "A paid plan is required to connect this leaderboard.");
    return board;
  }

  private void verifyUser(String token, String guildId) {
    JsonNode guilds = discord.userGuilds(token);
    if (!guilds.isArray()) throw new ApiException(502, "DISCORD_API_UNAVAILABLE", "Discord could not verify server permissions.");
    for (JsonNode guild : guilds) {
      if (guildId.equals(guild.path("id").asText())) {
        if (guild.path("owner").asBoolean(false) || has(bitfield(guild.path("permissions").asText("0")), MANAGE_GUILD)
            || has(bitfield(guild.path("permissions").asText("0")), ADMINISTRATOR)) return;
        throw new ApiException(403, "MANAGE_GUILD_REQUIRED", "You need Manage Server permission in this server.");
      }
    }
    throw new ApiException(403, "DISCORD_GUILD_REQUIRED", "This Discord account cannot manage the selected server.");
  }

  private GuildContext verifyBot(String guildId) {
    JsonNode guild = discord.guild(guildId);
    if (!guildId.equals(guild.path("id").asText()))
      throw new ApiException(409, "BOT_NOT_INSTALLED", "Install the bot in this server and try again.");
    String botId = snowflake(discord.botUser().path("id").asText(""));
    JsonNode member = discord.botMember(guildId, botId);
    if (!member.path("roles").isArray()) throw new ApiException(409, "BOT_NOT_INSTALLED", "Install the bot in this server and try again.");
    BigInteger base = BigInteger.ZERO;
    Set<String> roleIds = new HashSet<>();
    roleIds.add(guildId);
    for (JsonNode role : member.path("roles")) roleIds.add(role.asText());
    for (JsonNode role : guild.path("roles"))
      if (roleIds.contains(role.path("id").asText())) base = base.or(bitfield(role.path("permissions").asText("0")));
    JsonNode channels = discord.channels(guildId);
    if (!channels.isArray()) throw new ApiException(502, "DISCORD_API_UNAVAILABLE", "Discord could not list server channels.");
    return new GuildContext(botId, roleIds, base, channels);
  }

  private List<JsonNode> eligibleChannels(String guildId, GuildContext context) {
    List<JsonNode> result = new ArrayList<>();
    for (JsonNode channel : context.channels) {
      if (channel.path("type").asInt(-1) != 0) continue;
      BigInteger permissions = context.base;
      if (!has(permissions, ADMINISTRATOR)) {
        permissions = applyOverwrites(permissions, channel.path("permission_overwrites"), guildId,
            context.botId, context.roles);
      }
      if (has(permissions, VIEW_CHANNEL) || has(permissions, ADMINISTRATOR)) result.add(channel);
    }
    result.sort(Comparator.comparing(node -> node.path("name").asText("")));
    return result;
  }

  static BigInteger applyOverwrites(BigInteger base, JsonNode overwrites, String guildId,
      String botId, Set<String> roles) {
    if (!overwrites.isArray()) return base;
    BigInteger allowEveryone = BigInteger.ZERO, denyEveryone = BigInteger.ZERO;
    BigInteger allowRoles = BigInteger.ZERO, denyRoles = BigInteger.ZERO;
    BigInteger allowMember = BigInteger.ZERO, denyMember = BigInteger.ZERO;
    for (JsonNode overwrite : overwrites) {
      String id = overwrite.path("id").asText();
      BigInteger allow = bitfield(overwrite.path("allow").asText("0"));
      BigInteger deny = bitfield(overwrite.path("deny").asText("0"));
      if (id.equals(guildId)) { allowEveryone = allow; denyEveryone = deny; }
      else if (id.equals(botId)) { allowMember = allow; denyMember = deny; }
      else if (roles.contains(id)) { allowRoles = allowRoles.or(allow); denyRoles = denyRoles.or(deny); }
    }
    return base.andNot(denyEveryone).or(allowEveryone).andNot(denyRoles).or(allowRoles)
        .andNot(denyMember).or(allowMember);
  }

  private static BigInteger bitfield(String value) {
    try { return new BigInteger(value); } catch (RuntimeException exception) { return BigInteger.ZERO; }
  }
  private static boolean has(BigInteger value, BigInteger bit) { return value.and(bit).signum() != 0; }
  private static String snowflake(String value) {
    if (value == null || !value.matches("[0-9]{1,20}"))
      throw new ApiException(400, "INVALID_DISCORD_ID", "Discord returned an invalid server or channel.");
    return value;
  }
  private static String channelUrl(String guildId, String channelId) {
    return "https://discord.com/channels/" + guildId + "/" + channelId;
  }
  private String redirect(UUID boardId, String status) {
    return publicBaseUrl + (boardId == null ? "/leaderboards" : "/leaderboards/" + boardId)
        + "?discord=" + status;
  }
  private static String random() {
    byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
  private static byte[] hashBytes(String text) {
    try { return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)); }
    catch (Exception exception) { throw new IllegalStateException(exception); }
  }
  private static String hash(String text) { return HexFormat.of().formatHex(hashBytes(text)); }
  private String encrypt(String token) {
    try {
      byte[] iv = new byte[12]; RANDOM.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, tokenKey, new GCMParameterSpec(128, iv));
      byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
      byte[] joined = new byte[iv.length + encrypted.length];
      System.arraycopy(iv, 0, joined, 0, iv.length);
      System.arraycopy(encrypted, 0, joined, iv.length, encrypted.length);
      return Base64.getEncoder().encodeToString(joined);
    } catch (Exception exception) { throw new IllegalStateException(exception); }
  }
  private String decrypt(String encoded) {
    try {
      byte[] bytes = Base64.getDecoder().decode(encoded);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, tokenKey, new GCMParameterSpec(128, bytes, 0, 12));
      return new String(cipher.doFinal(bytes, 12, bytes.length - 12), StandardCharsets.UTF_8);
    } catch (Exception exception) { throw expired(); }
  }
  private ApiException expired() { return new ApiException(410, "DISCORD_SETUP_EXPIRED", "Discord setup expired. Start again."); }

  @Scheduled(cron = "0 51 3 * * *")
  @Transactional
  void cleanup() { setups.deleteByExpiresAtBefore(clock.instant()); }

  private record GuildContext(String botId, Set<String> roles, BigInteger base, JsonNode channels) {}
}

record DiscordChannelView(String guildId, String channelId, String name, UUID leaderboardId, String url) {}
