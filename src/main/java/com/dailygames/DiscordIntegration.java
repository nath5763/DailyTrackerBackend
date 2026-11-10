package com.dailygames;

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
    if (type != 2) return ephemeral("This interaction type is not supported.", "UNSUPPORTED_INTERACTION", null);
    String interactionId = snowflake(payload, "id");
    JsonNode member = payload.path("member");
    String discordUserId = interactionUserId(payload);
    String command = requiredText(payload.path("data"), "name", 32).toLowerCase(Locale.ROOT);
    Instant now = clock.instant();
    if (interactions.insertIfAbsent(interactionId, discordUserId, type, command, now) == 0) {
      return ephemeral("This interaction was already handled.", "DUPLICATE_INTERACTION", command);
    }

    DiscordInteractionResult result;
    try {
      String guildId = optionalSnowflake(payload, "guild_id");
      String channelId = optionalSnowflake(payload, "channel_id");
      boolean groupDm = isGroupDm(payload);
      result = switch (command) {
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
        case "daily" -> daily(discordUserId, groupDm, guildId, channelId,
            option(payload, "game", 80), option(payload, "result", 6_000),
            optionalOption(payload, "leaderboard", 36));
        case "leaderboard" -> leaderboard(discordUserId, groupDm, guildId, channelId,
            option(payload, "game", 80), optionalOption(payload, "range", 8),
            optionalOption(payload, "leaderboard", 36));
        default -> throw new ApiException(400, "UNKNOWN_COMMAND", "That command is not supported.");
      };
    } catch (ApiException exception) {
      result = ephemeral(discordMessage(exception), exception.code, command);
    }
    interactions.updateOutcome(interactionId, result.outcome());
    return result;
  }

  private DiscordInteractionResult help() {
    return ephemeral("**Daily Tracker help**\n"
        + "Run `/signup` to create or connect your account. In a connected server channel, signup can also join that channel's leaderboard.\n"
        + "1. **Link your account:** Sign in at " + publicBaseUrl + "/dashboard, generate a link code in the Discord section, then run `/link` and enter the code. Codes expire after 10 minutes.\n"
        + "2. **Choose a Group DM leaderboard:** Its owner can run `/set-leaderboard` once. In a one-to-one DM or an unconfigured Group DM, enter the leaderboard UUID on each command.\n"
        + "3. **Submit a game result:** Run `/daily`, select the required `game`, and paste the complete game share text into `result`. Discord supports multi-game leaderboards only.\n"
        + "4. **See rankings:** Run `/leaderboard`, choose a game, and optionally choose a range (today, 7 days, 30 days, or all time).\n"
        + "Each player links their own account and must belong to the selected leaderboard. Server and configured Group DM channels use their saved leaderboard.",
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
    if (!Set.of("daily", "leaderboard").contains(command)) return emptyChoices(command);
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
            "daily".equals(command));
      } else {
        String channelId = optionalSnowflake(payload, "channel_id");
        if (channelId == null) return emptyChoices(command);
        DiscordChannelLeaderboard mapping = channels.findById(
            new DiscordChannelLeaderboardId(guildId, channelId)).orElse(null);
        if (mapping == null) return emptyChoices(command);
        games = dailyGames.searchDiscordGames(mapping.leaderboardId, query, "daily".equals(command));
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

  private DiscordInteractionResult daily(String discordUserId, boolean groupDm, String guildId, String channelId,
                                          String gameSlug, String rawText, String rawLeaderboardId) {
    UUID userId = linkedUser(discordUserId);
    UUID leaderboardId = resolveLeaderboard(groupDm, guildId, channelId, rawLeaderboardId);
    if (guildId != null) requirePaidOwner(leaderboardId);
    requireMultiGame(leaderboardId);
    DailyGamesService.DiscordSubmissionResult saved = dailyGames.submitFromDiscord(userId, leaderboardId, gameSlug, rawText);
    GameAdapter adapter = adapters.require(saved.game().slug);
    String score = formatScore(saved.submission().primaryScore(), adapter.scoreFormat());
    String details = formatResultDetails(adapter.resultDetails(saved.submission().metrics()));
    String content = "Saved " + saved.game().name + " #" + saved.submission().puzzleKey() + " at " + score + "."
        + (details.isEmpty() ? "" : "\n" + details);
    return publicReply(content,
        "SUCCESS", "daily");
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
    ApiModels.LeaderboardResponse board = dailyGames.board(new CurrentUser(userId, ""), leaderboardId,
        range, gameSlug);
    String url = publicBaseUrl + "/leaderboards/" + board.id() + "?game=" + gameSlug + "&range=" + range;
    String label = switch (range) {
      case "7d" -> "Last 7 days";
      case "30d" -> "Last 30 days";
      case "all" -> "All time";
      default -> "Today";
    };
    String footer = "[View the full leaderboard](<" + url + ">)";
    if (footer.length() > 1_500) {
      throw new ApiException(500, "DISCORD_MESSAGE_TOO_LONG", "The leaderboard link is too long to post in Discord.");
    }
    StringBuilder content = new StringBuilder("**").append(discordText(board.name(), 60)).append("** · ")
        .append(discordText(board.game().name(), 40)).append(" · ").append(label).append('\n');
    if (board.rankings().isEmpty()) {
      content.append("Nobody has posted a score for this range yet.\n");
    } else {
      for (ApiModels.RankingView row : board.rankings().stream().limit(10).toList()) {
        String score = formatScore(row.average(), board.game().scoreFormat());
        String line = row.rank() + ". " + discordText(row.displayName(), 60) + " — " + discordText(score, 40)
            + " (" + row.gamesPlayed() + (row.gamesPlayed() == 1 ? " score)\n" : " scores)\n");
        if (range.equals("today")) {
          String details = board.dailyResults().stream()
              .filter(result -> result.userId().equals(row.userId()))
              .map(ApiModels.DailyResultDetailsView::details)
              .map(this::formatResultDetails)
              .filter(value -> !value.isEmpty())
              .findFirst().orElse("");
          if (!details.isEmpty()) line += "   " + details + "\n";
        }
        if (content.length() + line.length() + footer.length() > 2_000) break;
        content.append(line);
      }
    }
    content.append(footer);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("content", content.toString());
    data.put("allowed_mentions", Map.of("parse", List.of()));
    return new DiscordInteractionResult(Map.of("type", 4, "data", data), "leaderboard", "SUCCESS");
  }

  private String formatResultDetails(List<ApiModels.ResultDetailView> details) {
    if (details == null || details.isEmpty()) return "";
    return details.stream().limit(7)
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
