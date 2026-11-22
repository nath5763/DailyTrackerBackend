package com.dailygames;

import com.dailygames.ApiModels.LoldleScoreView;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.google.crypto.tink.subtle.Ed25519Verify;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

@Component
class DiscordSignatureVerifier {
  private static final Duration MAX_TIMESTAMP_SKEW = Duration.ofMinutes(5);
  private static final Pattern APPLICATION_ID = Pattern.compile("[0-9]{1,20}");
  private final String applicationId;
  private final Ed25519Verify verifier;
  private final Clock clock;

  DiscordSignatureVerifier(@Value("${app.discord.application-id:}") String applicationId,
                           @Value("${app.discord.public-key:}") String publicKey, Clock clock) {
    this.applicationId = applicationId == null ? "" : applicationId.trim();
    this.clock = clock;
    Ed25519Verify parsed = null;
    try {
      byte[] key = parseHex(publicKey);
      if (key.length == 32) parsed = new Ed25519Verify(key);
    } catch (RuntimeException ignored) {
      // A malformed deployment value is reported through configured() without exposing the value.
    }
    verifier = parsed;
  }

  boolean configured() { return APPLICATION_ID.matcher(applicationId).matches() && verifier != null; }
  String installUrl() {
    return configured() ? "https://discord.com/oauth2/authorize?client_id=" + applicationId
        + "&scope=applications.commands&integration_type=0" : null;
  }
  boolean applicationMatches(JsonNode payload) {
    return payload.path("application_id").isTextual() && applicationId.equals(payload.path("application_id").textValue());
  }

  boolean verify(String signatureHex, String timestamp, byte[] body) {
    if (!configured() || signatureHex == null || timestamp == null || body == null) return false;
    try {
      long epochSeconds = Long.parseLong(timestamp);
      Instant signedAt = Instant.ofEpochSecond(epochSeconds);
      if (Duration.between(signedAt, clock.instant()).abs().compareTo(MAX_TIMESTAMP_SKEW) > 0) return false;
      byte[] signature = parseHex(signatureHex);
      if (signature.length != 64) return false;
      byte[] prefix = timestamp.getBytes(StandardCharsets.UTF_8);
      byte[] signed = ByteBuffer.allocate(prefix.length + body.length).put(prefix).put(body).array();
      verifier.verify(signature, signed);
      return true;
    } catch (RuntimeException | GeneralSecurityException exception) {
      return false;
    }
  }

  private static byte[] parseHex(String value) {
    return value == null || value.isBlank() ? new byte[0] : HexFormat.of().parseHex(value.trim());
  }
}

@Service
class DiscordService {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final char[] CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
  private static final Pattern CODE_PATTERN = Pattern.compile("[0-9A-HJKMNP-TV-Z]{12}");
  private static final Pattern SNOWFLAKE = Pattern.compile("[0-9]{1,20}");
  private static final int IS_COMPONENTS_V2 = 32_768;
  private static final int EPHEMERAL = 64;
  private static final int OVERVIEW_GAMES_PER_PAGE = 5;
  private static final int PLAYERS_PER_PAGE = 5;
  private static final BigInteger ADMINISTRATOR = BigInteger.valueOf(8);
  private static final BigInteger MANAGE_GUILD = BigInteger.valueOf(32);
  private static final Duration CODE_LIFETIME = Duration.ofMinutes(10);

  private final ProfileRepository profiles;
  private final DiscordAccountLinkRepository links;
  private final DiscordLinkCodeRepository codes;
  private final LeaderboardRepository boards;
  private final DiscordChannelLeaderboardRepository channels;
  private final DiscordGroupDmLeaderboardRepository groupDms;
  private final DiscordCommandRegistrationStore registrations;
  private final ProcessedDiscordInteractionRepository interactions;
  private final BillingService billing;
  private final DailyGamesService dailyGames;
  private final DiscordSignupService signup;
  private final GameAdapterRegistry adapters;
  private final DiscordSignatureVerifier configuration;
  private final Clock clock;
  private final String pepper;
  private final String publicBaseUrl;

  DiscordService(ProfileRepository profiles, DiscordAccountLinkRepository links, DiscordLinkCodeRepository codes,
                 LeaderboardRepository boards, DiscordChannelLeaderboardRepository channels,
                 DiscordGroupDmLeaderboardRepository groupDms,
                 DiscordCommandRegistrationStore registrations,
                 ProcessedDiscordInteractionRepository interactions, BillingService billing,
                 DailyGamesService dailyGames, DiscordSignupService signup, GameAdapterRegistry adapters, DiscordSignatureVerifier configuration,
                 Clock clock, @Value("${app.token-pepper}") String pepper,
                 @Value("${app.public-base-url}") String publicBaseUrl) {
    this.profiles = profiles; this.links = links; this.codes = codes; this.boards = boards; this.channels = channels;
    this.groupDms = groupDms;
    this.registrations = registrations;
    this.interactions = interactions; this.billing = billing; this.dailyGames = dailyGames; this.signup = signup; this.adapters = adapters;
    this.configuration = configuration; this.clock = clock; this.pepper = pepper;
    this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
  }

  DiscordStatus status(UUID userId) {
    Optional<DiscordAccountLink> link = links.findByUserId(userId);
    return new DiscordStatus(configuration.configured(), link.isPresent(), link.map(value -> value.linkedAt).orElse(null),
        configuration.installUrl());
  }

  @Transactional
  DiscordLinkCodeResponse issueCode(UUID userId) {
    if (!configuration.configured()) {
      throw new ApiException(503, "DISCORD_NOT_CONFIGURED", "Discord linking is not available yet.");
    }
    profiles.findByIdForUpdate(userId)
        .orElseThrow(() -> new ApiException(409, "PROFILE_REQUIRED", "Finish setting up your profile."));
    Instant now = clock.instant();
    if (codes.countByUserIdAndCreatedAtAfter(userId, now.minus(Duration.ofMinutes(1))) >= 1
        || codes.countByUserIdAndCreatedAtAfter(userId, now.minus(Duration.ofHours(1))) >= 5) {
      throw new ApiException(429, "DISCORD_LINK_CODE_RATE_LIMITED", "Wait before requesting another Discord link code.");
    }
    codes.consumeOutstanding(userId, now);
    String normalized = randomCode();
    Instant expiresAt = now.plus(CODE_LIFETIME);
    codes.save(new DiscordLinkCode(userId, hashCode(normalized), now, expiresAt));
    return new DiscordLinkCodeResponse(formatCode(normalized), expiresAt);
  }

  @Transactional
  void unlinkWebsite(UUID userId) {
    disconnectOwnedChannels(userId);
    links.deleteByUserId(userId);
  }

  private void disconnectOwnedChannels(UUID userId) {
    channels.deleteOwnedChannels(userId);
    groupDms.deleteOwnedChannels(userId);
    registrations.pruneUnmappedGuilds();
  }

  @Transactional
  DiscordInteractionResult handle(JsonNode payload) {
    int type = requiredInt(payload, "type");
    if (type == 4) return autocomplete(payload);
    if (type != 2 && type != 3 && type != 5)
      return ephemeral("This interaction type is not supported.", "UNSUPPORTED_INTERACTION", null);
    String interactionId = snowflake(payload, "id");
    JsonNode member = payload.path("member");
    String discordUserId = interactionUserId(payload);
    String command = type == 3 || type == 5 ? "leaderboard"
        : requiredText(payload.path("data"), "name", 32).toLowerCase(Locale.ROOT);
    Instant now = clock.instant();
    if (interactions.insertIfAbsent(interactionId, discordUserId, type, command, now) == 0) {
      return "leaderboard".equals(command)
          ? leaderboardError("This interaction was already handled.", "DUPLICATE_INTERACTION")
          : ephemeral("This interaction was already handled.", "DUPLICATE_INTERACTION", command);
    }

    DiscordInteractionResult result;
    try {
      String guildId = optionalSnowflake(payload, "guild_id");
      String channelId = optionalSnowflake(payload, "channel_id");
      boolean groupDm = isGroupDm(payload);
      result = type == 3 ? leaderboardComponent(discordUserId, groupDm, guildId, channelId,
          payload.path("data")) : type == 5 ? leaderboardModal(discordUserId, groupDm, guildId, channelId,
          payload.path("data")) : switch (command) {
        case "help" -> help();
        case "signup" -> signup(discordUserId, guildId, channelId);
        case "my-id" -> myId(discordUserId);
        case "add-member" -> addMember(discordUserId, groupDm, guildId, channelId,
            option(payload, "uuid", 36), optionalOption(payload, "leaderboard", 36));
        case "link" -> link(discordUserId, option(payload, "code", 32), now);
        case "unlink" -> unlink(discordUserId);
        case "setup" -> setup(discordUserId, guildId, channelId, member,
            option(payload, "leaderboard", 36), now);
        case "set-leaderboard" -> setGroupDmLeaderboard(discordUserId, groupDm, guildId, channelId,
            option(payload, "leaderboard", 36), now);
        case "unset-leaderboard" -> unsetGroupDmLeaderboard(discordUserId, groupDm, guildId, channelId);
        case "daily" -> daily(discordUserId, option(payload, "result", 6_000));
        case "loldle" -> loldle(discordUserId, option(payload, "result", 6_000), option(payload, "date", 10));
        case "leaderboard" -> leaderboard(discordUserId, groupDm, guildId, channelId,
            optionalOption(payload, "game", 80), optionalOption(payload, "range", 8),
            optionalOption(payload, "leaderboard", 36));
        default -> throw new ApiException(400, "UNKNOWN_COMMAND", "That command is not supported.");
      };
    } catch (ApiException exception) {
      result = "leaderboard".equals(command)
          ? leaderboardError(discordMessage(exception), exception.code)
          : ephemeral(discordMessage(exception), exception.code, command);
    }
    interactions.updateOutcome(interactionId, result.outcome());
    return result;
  }

  private DiscordInteractionResult help() {
    return ephemeral("**Daily Tracker help**\n"
        + "Run `/signup` to create or connect your account. In a connected server channel, signup can also join that channel's leaderboard.\n"
        + "1. **Link your account:** Sign in at " + publicBaseUrl + "/dashboard, generate a link code in the Discord section, then run `/link` and enter the code. Codes expire after 10 minutes.\n"
        + "2. **Choose a Group DM leaderboard for rankings:** Its owner can run `/set-leaderboard` once. In a one-to-one DM or an unconfigured Group DM, enter the leaderboard UUID for `/leaderboard`.\n"
        + "3. **Submit a game result:** Link your account, then run `/daily` and paste the complete game share text into `result`. The bot identifies the game and saves it to your account in any chat.\n"
        + "For LoLdle, run `/loldle` with its complete five-mode share and the puzzle date (`YYYY-MM-DD`) to save Classic, Quote, Ability, Emoji, and Splash together.\n"
        + "4. **See rankings:** Run `/leaderboard` for today's card, or choose a game and optional range for detailed rankings.\n"
        + "Viewing rankings requires membership in the selected leaderboard. Server and configured Group DM channels use their saved leaderboard.",
        "SUCCESS", "help");
  }

  private DiscordInteractionResult signup(String discordUserId, String guildId, String channelId) {
    if (guildId == null) {
      return ephemeral("Create or sign in to Daily Tracker at <" + publicBaseUrl
          + "/dashboard>, finish your profile, then use the Discord section to generate a code for `/link`. "
          + "Private chats are not automatically connected to a leaderboard.", "SUCCESS", "signup");
    }
    requireChannel(channelId);
    return ephemeral("Finish creating or signing in to your account: <"
        + signup.issue(discordUserId, guildId, channelId)
        + ">\nThis private link expires in 24 hours.", "SUCCESS", "signup");
  }

  private DiscordInteractionResult myId(String discordUserId) {
    UUID userId = linkedUser(discordUserId);
    Profile profile = profiles.findById(userId)
        .orElseThrow(() -> new ApiException(409, "PROFILE_REQUIRED", "Finish setting up your profile."));
    return ephemeral("Your shareable profile UUID is `" + profile.shareId + "`.", "SUCCESS", "my-id");
  }

  private DiscordInteractionResult addMember(String discordUserId, boolean groupDm, String guildId, String channelId, String rawId,
                                             String rawLeaderboardId) {
    UUID userId = linkedUser(discordUserId);
    UUID leaderboardId = resolveLeaderboard(groupDm, guildId, channelId, rawLeaderboardId);
    requireMultiGame(leaderboardId);
    UUID shareId;
    try { shareId = UUID.fromString(rawId); }
    catch (IllegalArgumentException exception) { throw new ApiException(400, "INVALID_PROFILE_ID", "Enter a valid profile UUID."); }
    dailyGames.addMember(new CurrentUser(userId, ""), leaderboardId, shareId);
    return ephemeral(guildId == null ? "Member added to the selected leaderboard."
        : "Member added to this channel's leaderboard.", "SUCCESS", "add-member");
  }

  private DiscordInteractionResult autocomplete(JsonNode payload) {
    String command = requiredText(payload.path("data"), "name", 32);
    if (!"leaderboard".equals(command)) return emptyChoices(command);
    JsonNode options = payload.path("data").path("options");
    if (!options.isArray()) return emptyChoices(command);
    String query = null;
    for (JsonNode option : options) {
      if ("game".equals(option.path("name").asText()) && option.path("focused").asBoolean(false)
          && option.path("value").isTextual()) query = option.path("value").asText();
    }
    if (query == null || query.length() > 80) return emptyChoices(command);
    try {
      String guildId = optionalSnowflake(payload, "guild_id");
      boolean groupDm = isGroupDm(payload);
      List<ApiModels.GameView> games;
      if (guildId == null) {
        String rawLeaderboardId = optionalOption(payload, "leaderboard", 36);
        String channelId = optionalSnowflake(payload, "channel_id");
        UUID userId = linkedUser(interactionUserId(payload));
        games = dailyGames.searchDiscordGames(userId,
            resolveLeaderboard(groupDm, null, channelId, rawLeaderboardId), query,
            false);
      } else {
        String channelId = optionalSnowflake(payload, "channel_id");
        if (channelId == null) return emptyChoices(command);
        DiscordChannelLeaderboard mapping = channels.findById(
            new DiscordChannelLeaderboardId(guildId, channelId)).orElse(null);
        if (mapping == null) return emptyChoices(command);
        games = dailyGames.searchDiscordGames(mapping.leaderboardId, query, false);
      }
      List<Map<String, String>> choices = games.stream()
          .map(game -> Map.of("name", game.name(), "value", game.slug())).toList();
      return new DiscordInteractionResult(Map.of("type", 8, "data", Map.of("choices", choices)), command, "SUCCESS");
    } catch (ApiException exception) { return emptyChoices(command); }
  }

  private DiscordInteractionResult emptyChoices(String command) {
    return new DiscordInteractionResult(Map.of("type", 8, "data", Map.of("choices", List.of())), command, "SUCCESS");
  }

  private DiscordInteractionResult link(String discordUserId, String rawCode, Instant now) {
    if (interactions.countByDiscordUserIdAndCommandNameAndProcessedAtAfter(
        discordUserId, "link", now.minus(Duration.ofMinutes(15))) > 10) {
      throw new ApiException(429, "DISCORD_LINK_RATE_LIMITED", "Too many link attempts. Wait 15 minutes and try again.");
    }
    String normalized = normalizeCode(rawCode);
    DiscordLinkCode code = codes.findByCodeHashAndConsumedAtIsNullAndExpiresAtAfter(hashCode(normalized), now)
        .orElseThrow(this::invalidCode);
    DiscordAccountLink byDiscord = links.findById(discordUserId).orElse(null);
    DiscordAccountLink byProfile = links.findByUserId(code.userId).orElse(null);
    if (byDiscord != null && byProfile != null && byDiscord.discordUserId.equals(byProfile.discordUserId)) {
      byDiscord.updatedAt = now;
      links.save(byDiscord);
    } else {
      if (byDiscord != null) disconnectOwnedChannels(byDiscord.userId);
      if (byProfile != null && (byDiscord == null || !byProfile.userId.equals(byDiscord.userId)))
        disconnectOwnedChannels(byProfile.userId);
      if (byDiscord != null) links.delete(byDiscord);
      if (byProfile != null) links.delete(byProfile);
      links.flush();
      links.save(new DiscordAccountLink(discordUserId, code.userId, now));
    }
    code.consumedAt = now;
    codes.save(code);
    return ephemeral("Your Discord account is now linked to Daily Tracker.", "SUCCESS", "link");
  }

  private DiscordInteractionResult unlink(String discordUserId) {
    DiscordAccountLink link = links.findById(discordUserId)
        .orElseThrow(() -> new ApiException(404, "NOT_LINKED", "This Discord account is not linked to Daily Tracker."));
    disconnectOwnedChannels(link.userId);
    links.deleteById(discordUserId);
    return ephemeral("Your Discord account is no longer linked to Daily Tracker. Channels connected to leaderboards you own were disconnected.", "SUCCESS", "unlink");
  }

  private DiscordInteractionResult setup(String discordUserId, String guildId, String channelId, JsonNode member,
                                         String rawLeaderboardId, Instant now) {
    if (guildId == null) {
      throw new ApiException(400, "GUILD_REQUIRED",
          "Run /setup in a Discord server channel. DMs and Group DMs select a leaderboard per command instead.");
    }
    requireChannel(channelId);
    if (!canManageGuild(requiredText(member, "permissions", 40))) {
      throw new ApiException(403, "MANAGE_GUILD_REQUIRED", "You need Manage Server permission to configure this channel.");
    }
    UUID userId = linkedUser(discordUserId);
    UUID leaderboardId;
    try { leaderboardId = UUID.fromString(rawLeaderboardId); }
    catch (IllegalArgumentException exception) {
      throw new ApiException(400, "INVALID_LEADERBOARD", "Enter a valid leaderboard UUID.");
    }
    Leaderboard board = boards.findById(leaderboardId)
        .orElseThrow(() -> new ApiException(404, "LEADERBOARD_NOT_FOUND", "That leaderboard was not found."));
    if (!board.ownerId.equals(userId)) {
      throw new ApiException(403, "NOT_OWNER", "Only the leaderboard owner can connect it to a channel.");
    }
    requireMultiGame(board);
    if (billing.allGameLimit(board.ownerId, now) == 0) {
      throw new ApiException(402, "OWNER_SUBSCRIPTION_REQUIRED", "The leaderboard owner needs an active paid tier to connect a channel.");
    }
    DiscordChannelLeaderboardId id = new DiscordChannelLeaderboardId(guildId, channelId);
    DiscordChannelLeaderboard mapping = channels.findById(id)
        .orElseGet(() -> new DiscordChannelLeaderboard(guildId, channelId, leaderboardId, userId, discordUserId, now));
    mapping.leaderboardId = leaderboardId;
    mapping.configuredByUserId = userId;
    mapping.configuredByDiscordUserId = discordUserId;
    mapping.updatedAt = now;
    channels.save(mapping);
    return ephemeral("This channel is now connected to leaderboard `" + leaderboardId + "`.", "SUCCESS", "setup");
  }

  private DiscordInteractionResult setGroupDmLeaderboard(String discordUserId, boolean groupDm, String guildId,
                                                         String channelId, String rawLeaderboardId, Instant now) {
    requireGroupDm(groupDm, guildId, channelId);
    UUID userId = linkedUser(discordUserId);
    UUID leaderboardId = parseLeaderboardId(rawLeaderboardId);
    Leaderboard selected = boards.findById(leaderboardId)
        .orElseThrow(() -> new ApiException(404, "LEADERBOARD_NOT_FOUND", "That leaderboard was not found."));
    if (!selected.ownerId.equals(userId)) {
      throw new ApiException(403, "NOT_OWNER", "Only the leaderboard owner can connect it to a Group DM.");
    }
    requireMultiGame(selected);
    if (!billing.isBoardAccessible(selected, now)) {
      throw new ApiException(402, "OWNER_SUBSCRIPTION_REQUIRED",
          "This leaderboard is locked until its owner restores their subscription.");
    }
    DiscordGroupDmLeaderboard mapping = groupDms.findForUpdate(channelId).orElse(null);
    if (mapping != null) {
      Leaderboard current = boards.findById(mapping.leaderboardId)
          .orElseThrow(() -> new ApiException(404, "LEADERBOARD_NOT_FOUND", "The connected leaderboard no longer exists."));
      if (!current.ownerId.equals(userId)) {
        throw new ApiException(403, "NOT_OWNER",
            "Only the owner of the currently connected leaderboard can replace this Group DM's selection.");
      }
      mapping.leaderboardId = leaderboardId;
      mapping.configuredByUserId = userId;
      mapping.configuredByDiscordUserId = discordUserId;
      mapping.updatedAt = now;
    } else {
      mapping = new DiscordGroupDmLeaderboard(channelId, leaderboardId, userId, discordUserId, now);
    }
    groupDms.save(mapping);
    return ephemeral("This Group DM now uses leaderboard `" + leaderboardId
        + "`. Members can omit the `leaderboard` option.", "SUCCESS", "set-leaderboard");
  }

  private DiscordInteractionResult unsetGroupDmLeaderboard(String discordUserId, boolean groupDm, String guildId,
                                                           String channelId) {
    requireGroupDm(groupDm, guildId, channelId);
    UUID userId = linkedUser(discordUserId);
    DiscordGroupDmLeaderboard mapping = groupDms.findForUpdate(channelId)
        .orElseThrow(() -> new ApiException(404, "GROUP_DM_NOT_CONFIGURED",
            "This Group DM is not connected to a leaderboard."));
    Leaderboard current = boards.findById(mapping.leaderboardId)
        .orElseThrow(() -> new ApiException(404, "LEADERBOARD_NOT_FOUND", "The connected leaderboard no longer exists."));
    if (!current.ownerId.equals(userId)) {
      throw new ApiException(403, "NOT_OWNER",
          "Only the connected leaderboard's owner can disconnect this Group DM.");
    }
    groupDms.delete(mapping);
    return ephemeral("This Group DM is no longer connected to a leaderboard.", "SUCCESS", "unset-leaderboard");
  }

  private DiscordInteractionResult daily(String discordUserId, String rawText) {
    UUID userId = linkedUser(discordUserId);
    DailyGamesService.DiscordSubmissionResult saved = dailyGames.submitShare(userId, null, rawText);
    GameAdapter adapter = adapters.require(saved.game().slug);
    String score = formatScore(saved.submission().primaryScore(), adapter.scoreFormat());
    String details = formatResultDetails(adapter.resultDetails(saved.submission().metrics()));
    String content = "Saved " + saved.game().name + " #" + saved.submission().puzzleKey() + " at " + score + "."
        + (details.isEmpty() ? "" : "\n" + details);
    return publicReply(content,
        "SUCCESS", "daily");
  }

  private DiscordInteractionResult loldle(String discordUserId, String rawText, String rawDate) {
    UUID userId = linkedUser(discordUserId);
    LocalDate puzzleDate;
    try {
      if (!rawDate.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException();
      puzzleDate = LocalDate.parse(rawDate);
    } catch (RuntimeException exception) {
      throw new ApiException(422, "INVALID_LOLDLE_DATE", "Enter the LoLdle puzzle date as YYYY-MM-DD.");
    }
    List<LoldleScoreView> saved = dailyGames.submitLoldleShare(userId, rawText, puzzleDate);
    String scores = saved.stream().map(item -> item.game().substring("loldle-".length()).toUpperCase(Locale.ROOT)
        + " " + item.submission().primaryScore().toPlainString()
        + (Boolean.TRUE.equals(item.submission().metrics().get("clueUsed")) ? " ✓" : ""))
        .collect(java.util.stream.Collectors.joining(" · "));
    return publicReply("Saved LoLdle for " + puzzleDate + ": " + scores + ".", "SUCCESS", "loldle");
  }

  private DiscordInteractionResult leaderboard(String discordUserId, boolean groupDm, String guildId, String channelId,
                                               String gameSlug, String requestedRange, String rawLeaderboardId) {
    UUID userId = linkedUser(discordUserId);
    UUID leaderboardId = resolveLeaderboard(groupDm, guildId, channelId, rawLeaderboardId);
    if (guildId != null) requirePaidOwner(leaderboardId);
    requireMultiGame(leaderboardId);
    String range = requestedRange == null ? "today" : requestedRange;
    if (!Set.of("today", "7d", "30d", "all").contains(range)) {
      throw new ApiException(400, "INVALID_RANGE", "Range must be today, 7d, 30d, or all.");
    }
    List<ApiModels.GameView> games = dailyGames.discordLeaderboardGames(userId, leaderboardId);
    if (gameSlug == null) {
      return renderOverview(userId, games,
          new LeaderboardUiState(leaderboardId, 'o', -1, "t", 0, 0), 4, null);
    }
    int gameIndex = gameIndex(games, gameSlug);
    return renderGame(userId, games,
        new LeaderboardUiState(leaderboardId, 'g', gameIndex, rangeCode(range), 0, 0), 4, null);
  }

  private DiscordInteractionResult leaderboardComponent(String discordUserId, boolean groupDm, String guildId,
      String channelId, JsonNode data) {
    int componentType = requiredInt(data, "component_type");
    if (componentType != 2 && componentType != 3) throw invalidLeaderboardChoice();
    ParsedLeaderboardCustomId parsed = parseLeaderboardCustomId(requiredText(data, "custom_id", 100));
    LeaderboardUiState state = parsed.state();
    UUID current = resolveLeaderboard(groupDm, guildId, channelId,
        guildId == null ? state.boardId().toString() : null);
    if (!current.equals(state.boardId()))
      throw new ApiException(409, "MAPPING_CHANGED", "The connected leaderboard changed.");
    if (guildId != null) requirePaidOwner(state.boardId());
    requireMultiGame(state.boardId());
    UUID userId = linkedUser(discordUserId);
    List<ApiModels.GameView> games = dailyGames.discordLeaderboardGames(userId, state.boardId());

    if (parsed.action().equals("q")) {
      if (componentType != 2) throw invalidLeaderboardChoice();
      return gameSearchModal(state);
    } else if (parsed.action().equals("s")) {
      if (componentType != 3 || !data.path("values").isArray() || data.path("values").size() != 1)
        throw new ApiException(400, "INVALID_CHOICE", "Choose one game from the menu.");
      String value = data.path("values").get(0).asText("");
      if (!value.matches("g:[0-9]{1,4}")) throw invalidLeaderboardChoice();
      int gameIndex = Integer.parseInt(value.substring(2));
      requireGameIndex(games, gameIndex);
      state = new LeaderboardUiState(state.boardId(), 'g', gameIndex, state.range(), 0, 0);
    } else {
      if (componentType != 2) throw invalidLeaderboardChoice();
      state = switch (parsed.action()) {
        case "p" -> new LeaderboardUiState(state.boardId(), state.view(), state.gameIndex(), state.range(),
            state.page() - 1, state.menuPage());
        case "n" -> new LeaderboardUiState(state.boardId(), state.view(), state.gameIndex(), state.range(),
            state.page() + 1, state.menuPage());
        case "b" -> new LeaderboardUiState(state.boardId(), 'o', -1, "t", 0, state.menuPage());
        case "t" -> {
          if (state.view() != 'g') throw invalidLeaderboardChoice();
          yield new LeaderboardUiState(state.boardId(), 'g', state.gameIndex(), "t", 0, state.menuPage());
        }
        case "7" -> {
          if (state.view() != 'g') throw invalidLeaderboardChoice();
          yield new LeaderboardUiState(state.boardId(), 'g', state.gameIndex(), "7", 0, state.menuPage());
        }
        case "a" -> {
          if (state.view() == 'o')
            yield new LeaderboardUiState(state.boardId(), 'c', -1, "a", 0, state.menuPage());
          if (state.view() == 'g')
            yield new LeaderboardUiState(state.boardId(), 'g', state.gameIndex(), "a", 0, state.menuPage());
          throw invalidLeaderboardChoice();
        }
        case "mp" -> new LeaderboardUiState(state.boardId(), state.view(), state.gameIndex(), state.range(),
            state.page(), state.menuPage() - 1);
        case "mn" -> new LeaderboardUiState(state.boardId(), state.view(), state.gameIndex(), state.range(),
            state.page(), state.menuPage() + 1);
        default -> throw invalidLeaderboardChoice();
      };
    }
    return renderLeaderboardState(userId, games, state, 7);
  }

  private DiscordInteractionResult leaderboardModal(String discordUserId, boolean groupDm, String guildId,
      String channelId, JsonNode data) {
    ParsedLeaderboardCustomId parsed = parseLeaderboardCustomId(requiredText(data, "custom_id", 100));
    if (!parsed.action().equals("f")) throw invalidLeaderboardChoice();
    LeaderboardUiState state = parsed.state();
    UUID current = resolveLeaderboard(groupDm, guildId, channelId,
        guildId == null ? state.boardId().toString() : null);
    if (!current.equals(state.boardId()))
      throw new ApiException(409, "MAPPING_CHANGED", "The connected leaderboard changed.");
    if (guildId != null) requirePaidOwner(state.boardId());
    requireMultiGame(state.boardId());
    UUID userId = linkedUser(discordUserId);
    List<ApiModels.GameView> games = dailyGames.discordLeaderboardGames(userId, state.boardId());
    String query = modalValue(data, "game-query", 80);
    List<GameSearchResult> results = dailyGames.searchDiscordGames(userId, state.boardId(), query, false).stream()
        .map(game -> new GameSearchResult(gameIndex(games, game.slug()), game)).toList();
    if (results.isEmpty())
      throw new ApiException(404, "NO_GAMES_FOUND", "No games match that search.");
    return renderLeaderboardState(userId, games, state, 7, results);
  }

  private DiscordInteractionResult gameSearchModal(LeaderboardUiState state) {
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("type", 4);
    input.put("custom_id", "game-query");
    input.put("style", 1);
    input.put("label", "Game name");
    input.put("placeholder", "Name or slug");
    input.put("required", true);
    input.put("min_length", 1);
    input.put("max_length", 80);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("custom_id", customId(state, "f"));
    data.put("title", "Search games");
    data.put("components", List.of(actionRow(List.of(input))));
    return new DiscordInteractionResult(Map.of("type", 9, "data", data), "leaderboard", "SUCCESS");
  }

  private DiscordInteractionResult renderLeaderboardState(UUID userId, List<ApiModels.GameView> games,
      LeaderboardUiState state, int responseType) {
    return renderLeaderboardState(userId, games, state, responseType, null);
  }

  private DiscordInteractionResult renderLeaderboardState(UUID userId, List<ApiModels.GameView> games,
      LeaderboardUiState state, int responseType, List<GameSearchResult> searchResults) {
    return switch (state.view()) {
      case 'o' -> renderOverview(userId, games, state, responseType, searchResults);
      case 'g' -> renderGame(userId, games, state, responseType, searchResults);
      case 'c' -> renderCombined(userId, games, state, responseType, searchResults);
      default -> throw invalidLeaderboardChoice();
    };
  }

  private DiscordInteractionResult renderOverview(UUID userId, List<ApiModels.GameView> games,
      LeaderboardUiState state, int responseType, List<GameSearchResult> searchResults) {
    DailyGamesService.TodaySummary summary = dailyGames.todaySummary(new CurrentUser(userId, ""), state.boardId());
    int pages = pageCount(summary.games().size(), OVERVIEW_GAMES_PER_PAGE);
    validatePage(state.page(), pages);
    int from = state.page() * OVERVIEW_GAMES_PER_PAGE;
    int to = Math.min(from + OVERVIEW_GAMES_PER_PAGE, summary.games().size());

    List<Map<String, Object>> children = new ArrayList<>();
    children.add(textDisplay("## " + discordText(summary.boardName(), 100) + "\nDaily Leaderboards · Today"));
    children.add(separator());
    StringBuilder body = new StringBuilder();
    if (summary.games().isEmpty()) {
      body.append("Nobody has posted a score today.");
    } else {
      for (DailyGamesService.TodayGameLeaders game : summary.games().subList(from, to)) {
        if (!body.isEmpty()) body.append("\n\n");
        body.append("### ").append(discordText(game.game().name(), 80)).append('\n');
        if (game.leaders().isEmpty()) body.append("No ranked results");
        else for (ApiModels.RankingView leader : game.leaders()) {
          body.append("**").append(discordText(leader.displayName(), 60)).append("** — **")
              .append(discordText(gameScore(leader, game.game()), 50)).append("**\n");
        }
        if (!game.leaders().isEmpty()) body.setLength(body.length() - 1);
      }
      if (pages > 1) body.append("\n\n-# Page ").append(state.page() + 1).append(" of ").append(pages);
    }
    children.add(textDisplay(body.toString()));
    children.add(separator());
    addGameSearchControl(children, state, searchResults);
    if (pages > 1) children.add(actionRow(List.of(
        button("Previous", customId(state, "p"), 2, state.page() == 0),
        button("Next", customId(state, "n"), 2, state.page() + 1 == pages))));
    children.add(actionRow(List.of(
        button("View Overall", customId(state, "a"), 1, games.isEmpty()),
        linkButton("Open Full Leaderboard ↗", fullLeaderboardUrl(state.boardId(), null, "today")))));
    return leaderboardResponse(responseType, children);
  }

  private DiscordInteractionResult renderGame(UUID userId, List<ApiModels.GameView> games,
      LeaderboardUiState state, int responseType, List<GameSearchResult> searchResults) {
    requireGameIndex(games, state.gameIndex());
    ApiModels.GameView game = games.get(state.gameIndex());
    String range = rangeValue(state.range());
    ApiModels.LeaderboardResponse board = dailyGames.board(new CurrentUser(userId, ""), state.boardId(), range,
        game.slug());
    int pages = pageCount(board.rankings().size(), PLAYERS_PER_PAGE);
    validatePage(state.page(), pages);
    int from = state.page() * PLAYERS_PER_PAGE;
    int to = Math.min(from + PLAYERS_PER_PAGE, board.rankings().size());

    List<Map<String, Object>> children = new ArrayList<>();
    children.add(textDisplay("## " + discordText(game.name(), 100) + "\n"
        + discordText(board.name(), 100) + " · " + rangeLabel(range)));
    children.add(separator());
    StringBuilder body = new StringBuilder();
    if (board.rankings().isEmpty()) {
      body.append("Nobody has posted a score for this range yet.");
    } else {
      for (ApiModels.RankingView row : board.rankings().subList(from, to)) {
        if (!body.isEmpty()) body.append("\n\n");
        body.append(rankLabel(row.rank())).append(" **").append(discordText(row.displayName(), 60))
            .append("** — **").append(discordText(gameScore(row, game), 50)).append("**");
        String details = range.equals("today") ? dailyDetails(board, row.userId()) : resultCount(row.gamesPlayed());
        if (!details.isEmpty()) body.append("\n-# ").append(details);
      }
      if (pages > 1) body.append("\n\n-# Page ").append(state.page() + 1).append(" of ").append(pages);
    }
    children.add(textDisplay(body.toString()));
    children.add(separator());
    addGameSearchControl(children, state, searchResults);
    children.add(actionRow(List.of(
        button("Today", customId(state, "t"), state.range().equals("t") ? 1 : 2, false),
        button("Last 7 days", customId(state, "7"), state.range().equals("7") ? 1 : 2, false),
        button("Overall", customId(state, "a"), state.range().equals("a") ? 1 : 2, false))));
    List<Map<String, Object>> navigation = new ArrayList<>();
    if (pages > 1) {
      navigation.add(button("Previous", customId(state, "p"), 2, state.page() == 0));
      navigation.add(button("Next", customId(state, "n"), 2, state.page() + 1 == pages));
    }
    navigation.add(linkButton("Full Leaderboard ↗", fullLeaderboardUrl(state.boardId(), game.slug(), range)));
    children.add(actionRow(navigation));
    return leaderboardResponse(responseType, children);
  }

  private DiscordInteractionResult renderCombined(UUID userId, List<ApiModels.GameView> games,
      LeaderboardUiState state, int responseType, List<GameSearchResult> searchResults) {
    if (games.isEmpty()) throw new ApiException(400, "INVALID_CHOICE", "This leaderboard has no games.");
    ApiModels.LeaderboardResponse board = dailyGames.board(new CurrentUser(userId, ""), state.boardId(), "all",
        games.getFirst().slug());
    int pages = pageCount(board.combinedRankings().size(), PLAYERS_PER_PAGE);
    validatePage(state.page(), pages);
    int from = state.page() * PLAYERS_PER_PAGE;
    int to = Math.min(from + PLAYERS_PER_PAGE, board.combinedRankings().size());

    List<Map<String, Object>> children = new ArrayList<>();
    children.add(textDisplay("## Overall Standings\n" + discordText(board.name(), 100) + " · All time"));
    children.add(separator());
    StringBuilder body = new StringBuilder();
    if (board.combinedRankings().isEmpty()) {
      body.append("Nobody has an overall ranking yet.");
    } else {
      for (ApiModels.RankingView row : board.combinedRankings().subList(from, to)) {
        if (!body.isEmpty()) body.append("\n\n");
        String score = row.displayScore() == null || row.displayScore().isBlank()
            ? row.leaderboardPoints().stripTrailingZeros().toPlainString() + " leaderboard pts"
            : row.displayScore();
        body.append(rankLabel(row.rank())).append(" **").append(discordText(row.displayName(), 60))
            .append("** — **").append(discordText(score, 60)).append("**")
            .append("\n-# ").append(resultCount(row.gamesPlayed()));
      }
      if (pages > 1) body.append("\n\n-# Page ").append(state.page() + 1).append(" of ").append(pages);
    }
    children.add(textDisplay(body.toString()));
    children.add(separator());
    addGameSearchControl(children, state, searchResults);
    List<Map<String, Object>> navigation = new ArrayList<>();
    if (pages > 1) {
      navigation.add(button("Previous", customId(state, "p"), 2, state.page() == 0));
      navigation.add(button("Next", customId(state, "n"), 2, state.page() + 1 == pages));
    }
    navigation.add(linkButton("Full Leaderboard ↗", fullLeaderboardUrl(state.boardId(), null, "all")));
    children.add(actionRow(navigation));
    return leaderboardResponse(responseType, children);
  }

  private void addGameSearchControl(List<Map<String, Object>> children, LeaderboardUiState state,
      List<GameSearchResult> searchResults) {
    if (searchResults == null) {
      children.add(actionRow(List.of(button("Search games", customId(state, "q"), 2, false))));
      return;
    }
    List<Map<String, Object>> options = new ArrayList<>();
    for (GameSearchResult result : searchResults) {
      Map<String, Object> option = new LinkedHashMap<>();
      option.put("label", componentText(result.game().name(), 100));
      option.put("value", "g:" + result.gameIndex());
      if (state.view() == 'g' && state.gameIndex() == result.gameIndex()) option.put("default", true);
      options.add(option);
    }
    Map<String, Object> select = new LinkedHashMap<>();
    select.put("type", 3);
    select.put("custom_id", customId(state, "s"));
    select.put("placeholder", "Select a matching game");
    select.put("min_values", 1);
    select.put("max_values", 1);
    select.put("options", options);
    children.add(actionRow(List.of(select)));
  }

  private DiscordInteractionResult leaderboardResponse(int responseType, List<Map<String, Object>> children) {
    Map<String, Object> container = new LinkedHashMap<>();
    container.put("type", 17);
    container.put("accent_color", 0xDFFF4F);
    container.put("components", children);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("flags", IS_COMPONENTS_V2);
    data.put("components", List.of(container));
    data.put("allowed_mentions", Map.of("parse", List.of()));
    return new DiscordInteractionResult(Map.of("type", responseType, "data", data), "leaderboard", "SUCCESS");
  }

  private DiscordInteractionResult leaderboardError(String message, String outcome) {
    Map<String, Object> container = Map.of("type", 17, "components",
        List.of(textDisplay("## Leaderboard unavailable\n" + discordText(message, 500))));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("flags", IS_COMPONENTS_V2 | EPHEMERAL);
    data.put("components", List.of(container));
    data.put("allowed_mentions", Map.of("parse", List.of()));
    return new DiscordInteractionResult(Map.of("type", 4, "data", data), "leaderboard", outcome);
  }

  private Map<String, Object> textDisplay(String content) {
    return Map.of("type", 10, "content", content);
  }

  private Map<String, Object> separator() {
    return Map.of("type", 14, "divider", true, "spacing", 1);
  }

  private Map<String, Object> actionRow(List<Map<String, Object>> components) {
    return Map.of("type", 1, "components", components);
  }

  private Map<String, Object> button(String label, String customId, int style, boolean disabled) {
    Map<String, Object> button = new LinkedHashMap<>();
    button.put("type", 2);
    button.put("style", style);
    button.put("label", label);
    button.put("custom_id", customId);
    button.put("disabled", disabled);
    return button;
  }

  private Map<String, Object> linkButton(String label, String url) {
    return Map.of("type", 2, "style", 5, "label", label, "url", url);
  }

  private String dailyDetails(ApiModels.LeaderboardResponse board, UUID userId) {
    return board.dailyResults().stream().filter(result -> result.userId().equals(userId))
        .map(ApiModels.DailyResultDetailsView::details).map(this::formatResultDetails)
        .filter(value -> !value.isEmpty()).findFirst().orElse("");
  }

  private String gameScore(ApiModels.RankingView row, ApiModels.GameView game) {
    return row.displayScore() == null || row.displayScore().isBlank()
        ? formatScore(row.average(), game.scoreFormat()) : row.displayScore();
  }

  private String rankLabel(int rank) {
    return switch (rank) {
      case 1 -> "🥇";
      case 2 -> "🥈";
      case 3 -> "🥉";
      default -> "**" + rank + ".**";
    };
  }

  private String resultCount(int count) {
    return count + (count == 1 ? " result" : " results");
  }

  private String fullLeaderboardUrl(UUID boardId, String gameSlug, String range) {
    String url = publicBaseUrl + "/leaderboards/" + boardId;
    if (gameSlug == null) return range.equals("all") ? url + "?range=all" : url;
    return url + "?game=" + URLEncoder.encode(gameSlug, StandardCharsets.UTF_8)
        + "&range=" + URLEncoder.encode(range, StandardCharsets.UTF_8);
  }

  private int gameIndex(List<ApiModels.GameView> games, String slug) {
    for (int index = 0; index < games.size(); index++) if (games.get(index).slug().equals(slug)) return index;
    throw new ApiException(400, "GAME_NOT_ALLOWED", "That game is not available on this leaderboard.");
  }

  private void requireGameIndex(List<ApiModels.GameView> games, int index) {
    if (index < 0 || index >= games.size()) throw invalidLeaderboardChoice();
  }

  private void validatePage(int page, int pages) {
    if (page < 0 || page >= pages) throw invalidLeaderboardChoice();
  }

  private int pageCount(int size, int pageSize) {
    return Math.max(1, (size + pageSize - 1) / pageSize);
  }

  private String rangeLabel(String range) {
    return switch (range) {
      case "7d" -> "Last 7 days";
      case "30d" -> "Last 30 days";
      case "all" -> "Overall";
      default -> "Today";
    };
  }

  private String rangeCode(String range) {
    return switch (range) {
      case "today" -> "t";
      case "7d" -> "7";
      case "30d" -> "30";
      case "all" -> "a";
      default -> throw invalidLeaderboardChoice();
    };
  }

  private String rangeValue(String code) {
    return switch (code) {
      case "t" -> "today";
      case "7" -> "7d";
      case "30" -> "30d";
      case "a" -> "all";
      default -> throw invalidLeaderboardChoice();
    };
  }

  private String customId(LeaderboardUiState state, String action) {
    String game = state.gameIndex() < 0 ? "x" : Integer.toString(state.gameIndex());
    String value = "lb:" + state.boardId() + ":" + state.view() + ":" + game + ":" + state.range()
        + ":" + state.page() + ":" + state.menuPage() + ":" + action;
    if (value.length() > 100) throw new IllegalStateException("Leaderboard custom ID exceeds Discord's limit.");
    return value;
  }

  private ParsedLeaderboardCustomId parseLeaderboardCustomId(String customId) {
    String[] parts = customId.split(":", -1);
    if (parts.length != 8 || !parts[0].equals("lb") || !parts[2].matches("[ogc]")
        || !parts[3].matches("x|[0-9]{1,4}") || !parts[4].matches("t|7|30|a")
        || !parts[5].matches("[0-9]{1,4}") || !parts[6].matches("[0-9]{1,4}")
        || !parts[7].matches("s|q|f|p|n|b|t|7|a|mp|mn")) throw invalidLeaderboardChoice();
    UUID boardId = parseLeaderboardId(parts[1]);
    char view = parts[2].charAt(0);
    int gameIndex = parts[3].equals("x") ? -1 : Integer.parseInt(parts[3]);
    if ((view == 'g') != (gameIndex >= 0)) throw invalidLeaderboardChoice();
    return new ParsedLeaderboardCustomId(new LeaderboardUiState(boardId, view, gameIndex, parts[4],
        Integer.parseInt(parts[5]), Integer.parseInt(parts[6])), parts[7]);
  }

  private ApiException invalidLeaderboardChoice() {
    return new ApiException(400, "INVALID_CHOICE", "This leaderboard control is invalid or expired.");
  }

  private String componentText(String value, int maxLength) {
    String clean = value.replaceAll("[\\p{Cntrl}]", " ").trim();
    return clean.length() <= maxLength ? clean : clean.substring(0, maxLength - 1) + "…";
  }

  private record LeaderboardUiState(UUID boardId, char view, int gameIndex, String range, int page, int menuPage) {}
  private record ParsedLeaderboardCustomId(LeaderboardUiState state, String action) {}
  private record GameSearchResult(int gameIndex, ApiModels.GameView game) {}

  private String formatResultDetails(List<ApiModels.ResultDetailView> details) {
    if (details == null || details.isEmpty()) return "";
    return details.stream().limit(4)
        .map(detail -> discordText(detail.label(), 12) + " " + discordText(detail.value(), 24))
        .collect(java.util.stream.Collectors.joining(" · "));
  }

  private String discordText(String value, int maxLength) {
    String clean = value.replaceAll("[\\p{Cntrl}]", " ");
    if (clean.length() > maxLength) clean = clean.substring(0, maxLength - 1) + "…";
    for (String character : List.of("\\", "*", "_", "~", "`", ">", "|", "[", "]", "(", ")")) {
      clean = clean.replace(character, "\\" + character);
    }
    return clean;
  }

  private UUID linkedUser(String discordUserId) {
    return links.findById(discordUserId).map(link -> link.userId)
        .orElseThrow(() -> new ApiException(403, "NOT_LINKED", "Link your Daily Tracker account first with /link."));
  }

  private UUID resolveLeaderboard(boolean groupDm, String guildId, String channelId, String rawLeaderboardId) {
    if (guildId != null) {
      requireChannel(channelId);
      return channels.findById(new DiscordChannelLeaderboardId(guildId, channelId))
          .map(mapping -> mapping.leaderboardId)
          .orElseThrow(() -> new ApiException(404, "CHANNEL_NOT_CONFIGURED",
              "This server channel is not connected to a leaderboard."));
    }
    if (groupDm && channelId != null) {
      Optional<DiscordGroupDmLeaderboard> mapping = groupDms.findById(channelId);
      if (mapping.isPresent()) return mapping.get().leaderboardId;
    }
    if (rawLeaderboardId == null) {
      String instruction = groupDm
          ? "Run /set-leaderboard in this Group DM, or enter the leaderboard UUID in the `leaderboard` option. "
          : "Enter the leaderboard UUID in the `leaderboard` option. ";
      throw new ApiException(400, "PRIVATE_LEADERBOARD_REQUIRED",
          instruction + "Copy the UUID from the leaderboard URL in Daily Tracker.");
    }
    return parseLeaderboardId(rawLeaderboardId);
  }

  private void requireGroupDm(boolean groupDm, String guildId, String channelId) {
    if (guildId != null || !groupDm) {
      throw new ApiException(400, "GROUP_DM_REQUIRED", "Run this command in a Discord Group DM.");
    }
    requireChannel(channelId);
  }

  private UUID parseLeaderboardId(String rawLeaderboardId) {
    try { return UUID.fromString(rawLeaderboardId); }
    catch (IllegalArgumentException exception) {
      throw new ApiException(400, "INVALID_LEADERBOARD", "Enter a valid leaderboard UUID.");
    }
  }

  private void requireChannel(String channelId) {
    if (channelId == null) throw new ApiException(400, "INVALID_INTERACTION", "Discord sent an invalid interaction.");
  }

  private String option(JsonNode payload, String name, int maxLength) {
    String value = optionalOption(payload, name, maxLength);
    if (value != null) return value;
    throw new ApiException(400, "INVALID_COMMAND", "A required command option is missing or invalid.");
  }

  private String optionalOption(JsonNode payload, String name, int maxLength) {
    JsonNode options = payload.path("data").path("options");
    if (!options.isArray()) return null;
    for (JsonNode option : options) {
      if (name.equals(option.path("name").asText())) {
        if (option.path("type").asInt(-1) != 3 || !option.path("value").isTextual()) {
          throw new ApiException(400, "INVALID_COMMAND", "A command option is invalid.");
        }
        String value = option.path("value").textValue();
        if (!value.isBlank() && value.length() <= maxLength) return value;
        throw new ApiException(400, "INVALID_COMMAND", "A command option is invalid.");
      }
    }
    return null;
  }

  private String modalValue(JsonNode data, String customId, int maxLength) {
    JsonNode rows = data.path("components");
    if (rows.isArray()) {
      for (JsonNode row : rows) {
        JsonNode components = row.path("components");
        if (!components.isArray()) continue;
        for (JsonNode component : components) {
          if (customId.equals(component.path("custom_id").asText()) && component.path("value").isTextual()) {
            String value = component.path("value").textValue().trim();
            if (!value.isEmpty() && value.length() <= maxLength) return value;
          }
        }
      }
    }
    throw new ApiException(400, "INVALID_SEARCH", "Enter a game name to search.");
  }

  private String normalizeCode(String raw) {
    String normalized = raw == null ? "" : raw.replace("-", "").replaceAll("\\s", "").toUpperCase(Locale.ROOT);
    if (!CODE_PATTERN.matcher(normalized).matches()) throw invalidCode();
    return normalized;
  }

  private String randomCode() {
    char[] value = new char[12];
    for (int index = 0; index < value.length; index++) value[index] = CODE_ALPHABET[RANDOM.nextInt(CODE_ALPHABET.length)];
    return new String(value);
  }

  private String formatCode(String value) { return value.substring(0, 4) + "-" + value.substring(4, 8) + "-" + value.substring(8); }

  private String hashCode(String normalized) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update("daily-tracker:discord-link-code:v1\0".getBytes(StandardCharsets.UTF_8));
      digest.update(pepper.getBytes(StandardCharsets.UTF_8));
      digest.update((byte) 0);
      return HexFormat.of().formatHex(digest.digest(normalized.getBytes(StandardCharsets.US_ASCII)));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private boolean canManageGuild(String permissions) {
    try {
      BigInteger value = new BigInteger(permissions);
      return value.and(ADMINISTRATOR).signum() != 0 || value.and(MANAGE_GUILD).signum() != 0;
    } catch (NumberFormatException exception) {
      return false;
    }
  }

  private String formatScore(BigDecimal score, ApiModels.ScoreFormatView format) {
    BigDecimal display = score.setScale(format.maximumDecimalPlaces(), RoundingMode.HALF_UP).stripTrailingZeros();
    if (display.scale() < format.minimumDecimalPlaces()) display = display.setScale(format.minimumDecimalPlaces());
    return format.prefix() + display.toPlainString() + format.suffix();
  }

  private void requirePaidOwner(UUID leaderboardId) {
    Leaderboard board = boards.findById(leaderboardId)
        .orElseThrow(() -> new ApiException(404, "LEADERBOARD_NOT_FOUND", "That leaderboard no longer exists."));
    if (billing.allGameLimit(board.ownerId, clock.instant()) == 0) {
      throw new ApiException(402, "OWNER_SUBSCRIPTION_REQUIRED",
          "The leaderboard owner needs an active paid tier for this channel.");
    }
  }

  private void requireMultiGame(UUID leaderboardId) {
    Leaderboard board = boards.findById(leaderboardId)
        .orElseThrow(() -> new ApiException(404, "LEADERBOARD_NOT_FOUND", "That leaderboard no longer exists."));
    requireMultiGame(board);
  }

  private void requireMultiGame(Leaderboard board) {
    if (board.boardType != BoardType.MULTI_GAME) {
      throw new ApiException(400, "MULTI_GAME_REQUIRED", "Discord supports multi-game leaderboards only.");
    }
  }

  private String discordMessage(ApiException exception) {
    return switch (exception.code) {
      case "DISCORD_LINK_CODE_INVALID" -> "That link code is invalid or expired. Request a new one from Daily Tracker.";
      case "OWNER_SUBSCRIPTION_REQUIRED" -> "The leaderboard owner needs an active paid tier for this channel.";
      default -> exception.getMessage();
    };
  }

  private ApiException invalidCode() {
    return new ApiException(404, "DISCORD_LINK_CODE_INVALID", "That link code is invalid or expired.");
  }

  private static String snowflake(JsonNode node, String field) {
    String value = requiredText(node, field, 20);
    if (!SNOWFLAKE.matcher(value).matches()) throw new ApiException(400, "INVALID_INTERACTION", "Discord sent an invalid interaction.");
    return value;
  }

  private static String optionalSnowflake(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (value.isMissingNode() || value.isNull()) return null;
    return snowflake(node, field);
  }

  private static Integer optionalInt(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (value.isMissingNode() || value.isNull()) return null;
    if (!value.canConvertToInt()) throw new ApiException(400, "INVALID_INTERACTION", "Discord sent an invalid interaction.");
    return value.intValue();
  }

  private static boolean isGroupDm(JsonNode payload) {
    return Objects.equals(optionalInt(payload, "context"), 2)
        && Objects.equals(optionalInt(payload.path("channel"), "type"), 3);
  }

  private static String interactionUserId(JsonNode payload) {
    JsonNode memberUser = payload.path("member").path("user");
    if (memberUser.path("id").isTextual()) return snowflake(memberUser, "id");
    return snowflake(payload.path("user"), "id");
  }

  private static String requiredText(JsonNode node, String field, int maxLength) {
    JsonNode value = node.path(field);
    if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > maxLength) {
      throw new ApiException(400, "INVALID_INTERACTION", "Discord sent an invalid interaction.");
    }
    return value.textValue();
  }

  private static int requiredInt(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.canConvertToInt()) throw new ApiException(400, "INVALID_INTERACTION", "Discord sent an invalid interaction.");
    return value.intValue();
  }

  static DiscordInteractionResult ephemeral(String content, String outcome, String command) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("content", content);
    data.put("flags", 64);
    data.put("allowed_mentions", Map.of("parse", List.of()));
    return new DiscordInteractionResult(Map.of("type", 4, "data", data), command, outcome);
  }

  static DiscordInteractionResult publicReply(String content, String outcome, String command) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("content", content);
    data.put("allowed_mentions", Map.of("parse", List.of()));
    return new DiscordInteractionResult(Map.of("type", 4, "data", data), command, outcome);
  }

  @Scheduled(cron = "0 43 3 * * *")
  @Transactional
  void cleanup() {
    Instant now = clock.instant();
    codes.deleteByExpiresAtBefore(now.minus(Duration.ofDays(1)));
    interactions.deleteByProcessedAtBefore(now.minus(Duration.ofDays(7)));
  }
}

@RestController
@RequestMapping("/api/v1/discord")
class DiscordAccountController {
  private final DiscordService discord;
  DiscordAccountController(DiscordService discord) { this.discord = discord; }

  @GetMapping("/status") DiscordStatus status(Authentication authentication) {
    return discord.status(current(authentication).id());
  }

  @PostMapping("/link-codes") ResponseEntity<DiscordLinkCodeResponse> issue(Authentication authentication) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(discord.issueCode(current(authentication).id()));
  }

  @DeleteMapping("/link") ResponseEntity<Void> unlink(Authentication authentication) {
    discord.unlinkWebsite(current(authentication).id());
    return ResponseEntity.noContent().build();
  }

  private CurrentUser current(Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof CurrentUser current)) {
      throw new ApiException(401, "UNAUTHORIZED", "Sign in first.");
    }
    return current;
  }
}

@RestController
class DiscordInteractionController {
  private static final Logger log = LoggerFactory.getLogger(DiscordInteractionController.class);
  private static final int MAX_BODY_BYTES = 100_000;
  private final DiscordSignatureVerifier signatures;
  private final DiscordService discord;
  private final ObjectMapper json;

  DiscordInteractionController(DiscordSignatureVerifier signatures, DiscordService discord, ObjectMapper json) {
    this.signatures = signatures; this.discord = discord; this.json = json;
  }

  @PostMapping(value = "/api/v1/discord/interactions", consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<Map<String, Object>> interaction(
      @RequestBody byte[] body,
      @RequestHeader(value = "X-Signature-Ed25519", required = false) String signature,
      @RequestHeader(value = "X-Signature-Timestamp", required = false) String timestamp) {
    long started = System.nanoTime();
    if (!signatures.configured()) throw new ApiException(503, "DISCORD_NOT_CONFIGURED", "Discord is not configured.");
    if (body.length > MAX_BODY_BYTES || !signatures.verify(signature, timestamp, body)) {
      throw new ApiException(401, "INVALID_DISCORD_SIGNATURE", "Discord signature is invalid.");
    }
    JsonNode payload;
    try { payload = json.readTree(body); }
    catch (JacksonException exception) {
      return ResponseEntity.ok(DiscordService.ephemeral("Discord sent an invalid interaction.", "INVALID_INTERACTION", null).body());
    }
    if (!signatures.applicationMatches(payload)) {
      throw new ApiException(401, "INVALID_DISCORD_APPLICATION", "Discord application ID is invalid.");
    }
    if (payload.path("type").asInt(-1) == 1) return ResponseEntity.ok(Map.of("type", 1));

    String interactionId = safeLogValue(payload, "id");
    String command = payload.path("data").path("name").isTextual() ? payload.path("data").path("name").textValue() : "unknown";
    DiscordInteractionResult result;
    try {
      result = discord.handle(payload);
    } catch (RuntimeException exception) {
      log.error("Discord interaction failed interactionId={} command={}", interactionId, command, exception);
      result = DiscordService.ephemeral("Daily Tracker could not complete that command. Please try again.",
          "INTERNAL_SERVER_ERROR", command);
    }
    long durationMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
    if (durationMs > 2_000) {
      log.warn("Slow Discord interaction interactionId={} command={} outcome={} durationMs={}",
          interactionId, command, result.outcome(), durationMs);
    } else {
      log.info("Discord interaction interactionId={} command={} outcome={} durationMs={}",
          interactionId, command, result.outcome(), durationMs);
    }
    return ResponseEntity.ok(result.body());
  }

  private String safeLogValue(JsonNode payload, String field) {
    String value = payload.path(field).asText("unknown");
    return value.matches("[0-9]{1,20}") ? value : "unknown";
  }
}

record DiscordStatus(boolean configured, boolean linked, Instant linkedAt, String installUrl) {}
record DiscordLinkCodeResponse(String code, Instant expiresAt) {}
record DiscordInteractionResult(Map<String, Object> body, String command, String outcome) {}
