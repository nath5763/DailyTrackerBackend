package com.dailygames;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Service
class TelegramService {
  private static final Logger log = LoggerFactory.getLogger(TelegramService.class);
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Duration FLOW_LIFETIME = Duration.ofMinutes(15);
  private final JdbcTemplate jdbc;
  private final TelegramGateway bot;
  private final ProfileRepository profiles;
  private final LeaderboardRepository boards;
  private final MembershipRepository memberships;
  private final BillingService billing;
  private final DailyGamesService games;
  private final GameAdapterRegistry adapters;
  private final Clock clock;
  private final String pepper;
  private final String baseUrl;

  TelegramService(JdbcTemplate jdbc, TelegramGateway bot, ProfileRepository profiles,
      LeaderboardRepository boards, MembershipRepository memberships, BillingService billing,
      DailyGamesService games, GameAdapterRegistry adapters, Clock clock,
      @Value("${app.token-pepper}") String pepper, @Value("${app.public-base-url}") String baseUrl) {
    this.jdbc = jdbc; this.bot = bot; this.profiles = profiles; this.boards = boards;
    this.memberships = memberships; this.billing = billing; this.games = games; this.adapters = adapters;
    this.clock = clock; this.pepper = pepper; this.baseUrl = baseUrl.replaceAll("/+$", "");
  }

  Map<String, Object> status(UUID userId) {
    boolean linked = jdbc.queryForObject("select exists(select 1 from telegram_account_links where user_id=?)",
        Boolean.class, userId);
    return Map.of("configured", bot.configured(), "linked", linked, "botUrl", bot.deepLink("start"),
        "botUsername", bot.username());
  }

  @Transactional
  Map<String, Object> issueLink(UUID userId) {
    if (!bot.configured()) throw new ApiException(503, "TELEGRAM_NOT_CONFIGURED", "Telegram is unavailable.");
    if (!profiles.existsById(userId)) throw new ApiException(409, "PROFILE_REQUIRED", "Finish your profile first.");
    Instant now = clock.instant();
    Integer recent = jdbc.queryForObject(
        "select count(*) from telegram_link_codes where user_id=? and expires_at>?", Integer.class, userId, ts(now));
    if (recent != null && recent > 0) throw new ApiException(429, "LINK_RATE_LIMITED", "Wait for your current code to expire.");
    String code = randomCode();
    jdbc.update("insert into telegram_link_codes(token_hash,user_id,expires_at) values (?,?,?)",
        hash(code), userId, ts(now.plus(Duration.ofMinutes(10))));
    return Map.of("code", code, "expiresAt", now.plus(Duration.ofMinutes(10)));
  }

  @Transactional
  void unlinkWebsite(UUID userId) {
    jdbc.update("delete from telegram_group_leaderboards where leaderboard_id in (select id from leaderboards where owner_id=?)", userId);
    jdbc.update("delete from telegram_account_links where user_id=?", userId);
  }

  List<Map<String, Object>> groups(UUID userId, UUID boardId) {
    Leaderboard board = board(boardId);
    if (!board.ownerId.equals(userId) && !memberships.existsByIdLeaderboardIdAndIdUserId(boardId, userId))
      throw new ApiException(403, "NOT_MEMBER", "You cannot view this leaderboard.");
    return jdbc.query("select chat_id,title from telegram_group_leaderboards where leaderboard_id=? order by configured_at",
        (rs, row) -> Map.of("chatId", rs.getLong(1), "title", rs.getString(2) == null ? "Telegram group" : rs.getString(2)), boardId);
  }

  @Transactional
  void disconnect(UUID userId, UUID boardId, long chatId) {
    if (!board(boardId).ownerId.equals(userId))
      throw new ApiException(403, "NOT_OWNER", "Only the owner can disconnect this group.");
    if (jdbc.update("delete from telegram_group_leaderboards where chat_id=? and leaderboard_id=?", chatId, boardId) == 0)
      throw new ApiException(404, "GROUP_NOT_CONNECTED", "This group is not connected to this leaderboard.");
  }

  @Transactional
  void handle(JsonNode update) {
    if (!update.path("update_id").canConvertToLong()) throw new ApiException(400, "INVALID_UPDATE", "Missing Telegram update ID.");
    long updateId = update.path("update_id").longValue();
    if (jdbc.update("insert into processed_telegram_updates(update_id,processed_at) values (?,?) on conflict do nothing",
        updateId, ts(clock.instant())) == 0) return;
    try {
      JsonNode message = update.path("message");
      if (!message.isMissingNode() && !message.isNull()) onMessage(message);
      else if (update.has("callback_query")) onCallback(update.path("callback_query"));
    } catch (ApiException exception) {
      log.info("Telegram update rejected updateId={} code={}", updateId, exception.code);
      JsonNode message = update.path("message");
      JsonNode callback = update.path("callback_query");
      long chatId = message.path("chat").path("id").asLong(0);
      if (chatId == 0) chatId = callback.path("message").path("chat").path("id").asLong(0);
      if (callback.path("id").isTextual()) bot.answer(callback.path("id").asText(), exception.getMessage());
      else if (chatId != 0) bot.send(chatId, exception.getMessage());
    }
  }

  private void onMessage(JsonNode message) {
    JsonNode chat = message.path("chat");
    String type = chat.path("type").asText("");
    if (!Set.of("private", "group", "supergroup").contains(type)) return;
    long chatId = id(chat.path("id"));
    if (message.path("migrate_to_chat_id").canConvertToLong()) {
      migrate(chatId, message.path("migrate_to_chat_id").longValue());
      return;
    }
    if (message.path("migrate_from_chat_id").canConvertToLong())
      migrate(message.path("migrate_from_chat_id").longValue(), chatId);
    long userId = id(message.path("from").path("id"));
    String text = message.path("text").asText("");
    if (text.startsWith("/")) {
      if (text.length() > 6000) throw new ApiException(400, "MESSAGE_TOO_LONG", "That message is too long.");
      String[] parts = text.split("\\s+", 2);
      String command = parts[0].substring(1).toLowerCase(Locale.ROOT);
      int at = command.indexOf('@');
      if (at >= 0) {
        if (!command.substring(at + 1).equalsIgnoreCase(bot.username())) return;
        command = command.substring(0, at);
      }
      command = command.replace('-', '_');
      String argument = parts.length == 2 ? parts[1].trim() : "";
      command(userId, chatId, type, command, argument);
    } else if ("private".equals(type) && !text.isBlank()) {
      Flow flow = flow(userId);
      if (flow != null && flow.expiresAt.isAfter(clock.instant())) {
        if (text.length() > 6000) throw new ApiException(400, "MESSAGE_TOO_LONG", "That message is too long.");
        onText(flow, text);
      }
      else if (flow != null) {
        clearFlow(userId);
        bot.send(chatId, "That private flow expired. Start the command again.");
      }
    }
  }

  private void migrate(long oldId, long newId) {
    if (oldId == newId) return;
    jdbc.update("update telegram_group_leaderboards set chat_id=? where chat_id=? and not exists " +
        "(select 1 from telegram_group_leaderboards where chat_id=?)", newId, oldId, newId);
    jdbc.update("delete from telegram_group_leaderboards where chat_id=?", oldId);
    jdbc.update("update telegram_pending_flows set origin_chat_id=? where origin_chat_id=?", newId, oldId);
    jdbc.update("update telegram_signup_intents set chat_id=? where chat_id=?", newId, oldId);
  }

  private void command(long userId, long chatId, String type, String command, String argument) {
    boolean privateChat = "private".equals(type);
    if (command.equals("start")) {
      if (privateChat && argument.startsWith("flow_")) {
        Flow flow = flow(userId);
        if (flow == null || !flow.token.equals(argument.substring(5)) || !flow.expiresAt.isAfter(clock.instant()))
          throw new ApiException(410, "FLOW_EXPIRED", "This private continuation expired. Start the command again.");
        if (flow.name.equals("signup")) { signup(userId, flow.originChatId, flow.boardId); clearFlow(userId); }
        else if (flow.step.equals("search_private")) {
          Flow next = flow.withStep("search");
          saveFlow(next); continueFlow(next);
        } else continueFlow(flow);
      } else bot.send(chatId, "Welcome to Daily Tracker. Use /help for commands.");
      return;
    }
    if (command.equals("help")) {
      bot.send(chatId, "Daily Tracker: /signup, /link, /unlink, /my_id, /daily, /loldle, /leaderboard. " +
          "Owners: /add_member, /setup, /set_leaderboard, /unset_leaderboard. " +
          "Send /daily followed by a complete Share result in one group message, or use /daily alone for the private flow. " +
          "Account codes stay in the bot's private chat. Groups use one connected multi-game leaderboard.");
      return;
    }
    if (command.equals("signup")) {
      if (!privateChat) { beginPrivate(userId, chatId, command); return; }
      signup(userId, null, null); return;
    }
    if (command.equals("link")) {
      if (!privateChat) { beginPrivate(userId, chatId, command); return; }
      if (!argument.isBlank()) { link(userId, argument); return; }
      saveFlow(new Flow(userId, null, "link", "code", null, null, null, null, randomShort(), clock.instant().plus(FLOW_LIFETIME)));
      bot.send(chatId, "Send your one-time code from Profile.");
      return;
    }
    if (command.equals("unlink")) {
      if (!privateChat) { bot.send(chatId, "Open the bot's private chat to use /unlink.", linkButton("Open bot", bot.deepLink("start"))); return; }
      UUID linked = linkedUser(userId);
      unlinkWebsite(linked);
      bot.send(chatId, "Your Telegram account is unlinked. Groups connected to boards you own were disconnected.");
      return;
    }
    if (command.equals("my_id")) {
      if (!privateChat) {
        bot.send(chatId, "Open the bot's private chat and run /my_id.",
            linkButton("Open private chat", bot.deepLink("start")));
        return;
      }
      UUID linked = linkedUser(userId);
      Profile profile = profiles.findById(linked).orElseThrow(() -> new ApiException(409, "PROFILE_REQUIRED", "Finish your profile."));
      bot.send(chatId, "Your account UUID: " + profile.shareId);
      return;
    }
    if (command.equals("setup") || command.equals("set_leaderboard")) {
      if (privateChat) throw new ApiException(400, "GROUP_REQUIRED", "Run /setup in a Telegram group.");
      if (argument.isBlank()) {
        requireAdmin(userId, chatId);
        UUID linked = linkedUser(userId);
        List<Leaderboard> choices = boards.findByOwnerIdOrderByCreatedAtAsc(linked).stream()
            .filter(board -> board.boardType == BoardType.MULTI_GAME)
            .filter(board -> billing.isBoardAccessible(board, clock.instant())).toList();
        if (choices.isEmpty()) throw new ApiException(404, "NO_LEADERBOARD", "You need a paid multi-game leaderboard.");
        Flow flow = new Flow(userId, chatId, "setup", "setup_board", null, null, null, null,
            randomShort(), clock.instant().plus(FLOW_LIFETIME));
        saveFlow(flow);
        bot.send(chatId, "Choose a leaderboard to connect:", choices(flow.token, "b",
            choices.stream().map(board -> board.name).toList()));
      } else setup(userId, chatId, type, argument);
      return;
    }
    if (command.equals("unset_leaderboard")) {
      if (privateChat) throw new ApiException(400, "GROUP_REQUIRED", "Run this command in a Telegram group.");
      UUID linked = linkedUser(userId);
      UUID boardId = mapped(chatId);
      if (!board(boardId).ownerId.equals(linked)) throw new ApiException(403, "NOT_OWNER", "Only the connected board's owner can disconnect it.");
      requireAdmin(userId, chatId);
      jdbc.update("delete from telegram_group_leaderboards where chat_id=?", chatId);
      bot.send(chatId, "This group is disconnected.");
      return;
    }
    if (!Set.of("daily", "loldle", "leaderboard", "add_member").contains(command)) return;
    UUID linked = linkedUser(userId);
    if (command.equals("daily") && !privateChat && !argument.isBlank()) {
      UUID boardId = mapped(chatId);
      String gameSlug = adapters.detectGame(argument).orElseThrow(() -> new ApiException(422, "GAME_UNDETECTED",
          "Could not identify one supported game in that share. Use /daily alone to choose a game in private chat."));
      submitDaily(linked, boardId, gameSlug, argument, chatId);
      return;
    }
    if (!privateChat && !command.equals("leaderboard")) { mapped(chatId); beginPrivate(userId, chatId, command); return; }
    UUID boardId = privateChat ? null : mapped(chatId);
    Flow flow = new Flow(userId, privateChat ? null : chatId, command, boardId == null ? "board" : nextStep(command),
        boardId, null, null, null, randomShort(), clock.instant().plus(FLOW_LIFETIME));
    saveFlow(flow);
    continueFlow(flow);
  }

  private void setup(long telegramUserId, long chatId, String type, String rawBoard) {
    if (!Set.of("group", "supergroup").contains(type)) throw new ApiException(400, "GROUP_REQUIRED", "Use a group or supergroup.");
    requireAdmin(telegramUserId, chatId);
    UUID userId = linkedUser(telegramUserId);
    UUID boardId = parseBoard(rawBoard);
    Leaderboard selected = board(boardId);
    if (!selected.ownerId.equals(userId)) throw new ApiException(403, "NOT_OWNER", "Only the leaderboard owner can connect it.");
    requireAccessible(selected);
    UUID previous = optionalMapped(chatId);
    if (previous != null && !board(previous).ownerId.equals(userId))
      throw new ApiException(403, "NOT_OWNER", "Only the current board's owner can replace this group's selection.");
    String title = bot.call("getChat", Map.of("chat_id", chatId)).path("title").asText("Telegram group");
    jdbc.update("insert into telegram_group_leaderboards(chat_id,leaderboard_id,title,configured_by_user_id,configured_at) " +
        "values (?,?,?,?,?) on conflict(chat_id) do update set leaderboard_id=excluded.leaderboard_id,title=excluded.title," +
        "configured_by_user_id=excluded.configured_by_user_id,configured_at=excluded.configured_at",
        chatId, boardId, title, userId, ts(clock.instant()));
    bot.send(chatId, "This group now uses leaderboard " + selected.name + ".");
  }

  private void requireAdmin(long userId, long chatId) {
    JsonNode admins = bot.call("getChatAdministrators", Map.of("chat_id", chatId));
    if (admins.isArray()) for (JsonNode admin : admins) {
      if (admin.path("user").path("id").asLong(0) == userId &&
          Set.of("creator", "administrator").contains(admin.path("status").asText(""))) return;
    }
    throw new ApiException(403, "GROUP_ADMIN_REQUIRED", "You must be a group administrator.");
  }

  private void beginPrivate(long userId, long groupId, String command) {
    UUID boardId = command.equals("signup") ? optionalMapped(groupId) :
        Set.of("daily", "loldle", "add_member").contains(command) ? mapped(groupId) : null;
    Flow flow = new Flow(userId, groupId, command, "private", boardId, null, null, null,
        randomShort(), clock.instant().plus(FLOW_LIFETIME));
    saveFlow(flow);
    bot.send(groupId, "Continue /" + command.replace('_', '-') + " in a private chat with the bot." +
        (command.equals("daily") ? " Paste the complete Share result there; the score confirmation will appear here. " +
            "You can also send /daily followed by the Share result in one group message." : ""),
        linkButton("Open private chat", bot.deepLink("flow_" + flow.token)));
  }

  private void continueFlow(Flow flow) {
    long target = flow.originChatId != null && flow.name.equals("leaderboard") ? flow.originChatId : flow.userId;
    if (flow.step.equals("private")) {
      Flow next = flow.withStep(flow.name.equals("link") ? "code" : nextStep(flow.name));
      if (flow.originChatId != null && !flow.name.equals("link") && !flow.name.equals("signup"))
        currentBoard(flow);
      saveFlow(next);
      continueFlow(next);
      return;
    }
    if (flow.step.equals("search_private")) {
      bot.send(flow.originChatId, "Continue the game search in a private chat.",
          linkButton("Open private chat", bot.deepLink("flow_" + flow.token)));
      return;
    }
    if (flow.step.equals("board")) {
      List<Leaderboard> choices = availableBoards(flow);
      if (choices.isEmpty()) throw new ApiException(404, "NO_LEADERBOARD", "Join a multi-game leaderboard first.");
      bot.send(target, "Choose a leaderboard:", choices(flow.token, "b", choices.stream().map(board -> board.name).toList()));
    } else if (flow.step.equals("game")) {
      UUID user = linkedUser(flow.userId);
      UUID boardId = currentBoard(flow);
      List<ApiModels.GameView> choices = games.searchDiscordGames(user, boardId,
          flow.shareText == null ? "" : flow.shareText, flow.name.equals("daily"));
      if (choices.isEmpty()) throw new ApiException(404, "NO_GAMES", "No supported games are available.");
      List<List<Map<String, String>>> buttons = new ArrayList<>(choices(flow.token, "g",
          choices.stream().map(ApiModels.GameView::name).toList()));
      buttons.add(List.of(Map.of("text", "Search another game", "callback_data", "f:" + flow.token + ":q")));
      bot.send(target, "Choose a game:", buttons);
    } else if (flow.step.equals("range")) {
      bot.send(target, "Choose a date range:", choices(flow.token, "r",
          List.of("Today", "Last 7 days", "Last 30 days", "All time")));
    } else if (flow.step.equals("result")) bot.send(flow.userId, "Paste the complete game share text here.");
    else if (flow.step.equals("search")) bot.send(flow.userId, "Send a game name to search.");
    else if (flow.step.equals("date")) bot.send(flow.userId, "Enter the LoLdle puzzle date as YYYY-MM-DD.");
    else if (flow.step.equals("member")) bot.send(flow.userId, "Send the member's account UUID.");
    else if (flow.step.equals("code")) bot.send(flow.userId, "Send your one-time link code from Profile.");
  }

  private void onCallback(JsonNode callback) {
    String callbackId = callback.path("id").asText("");
    long userId = id(callback.path("from").path("id"));
    String data = callback.path("data").asText("");
    if (data.startsWith("s:")) {
      confirmSignup(userId, data.substring(2));
      bot.answer(callbackId, "Identity confirmed. Return to the website.");
      return;
    }
    String[] parts = data.split(":", 3);
    if (parts.length != 3 || !parts[0].equals("f")) throw new ApiException(400, "INVALID_CHOICE", "This choice is invalid.");
    Flow flow = flow(userId);
    if (flow == null || !flow.token.equals(parts[1]) || !flow.expiresAt.isAfter(clock.instant()))
      throw new ApiException(410, "FLOW_EXPIRED", "This choice expired. Start the command again.");
    String choice = parts[2];
    if (flow.step.equals("setup_board") && choice.matches("b[0-9]+")) {
      if (flow.originChatId == null) throw new ApiException(400, "GROUP_REQUIRED", "This choice belongs in a group.");
      List<Leaderboard> boards = this.boards.findByOwnerIdOrderByCreatedAtAsc(linkedUser(userId)).stream()
          .filter(board -> board.boardType == BoardType.MULTI_GAME)
          .filter(board -> billing.isBoardAccessible(board, clock.instant())).toList();
      int index = Integer.parseInt(choice.substring(1));
      if (index >= boards.size()) throw new ApiException(400, "INVALID_CHOICE", "That board is unavailable.");
      setup(userId, flow.originChatId, "group", boards.get(index).id.toString());
      clearFlow(userId);
      bot.answer(callbackId, "Group connected.");
      return;
    } else if (flow.step.equals("board") && choice.matches("b[0-9]+")) {
      List<Leaderboard> boards = availableBoards(flow);
      int index = Integer.parseInt(choice.substring(1));
      if (index >= boards.size()) throw new ApiException(400, "INVALID_CHOICE", "That board is unavailable.");
      flow = flow.withBoard(boards.get(index).id).withStep(nextStep(flow.name));
    } else if (flow.step.equals("game") && choice.equals("q")) {
      long choiceChat = callback.path("message").path("chat").path("id").asLong(0);
      flow = flow.withStep(choiceChat == userId ? "search" : "search_private");
    } else if (flow.step.equals("game") && choice.matches("g[0-9]+")) {
      List<ApiModels.GameView> choices = games.searchDiscordGames(linkedUser(userId), currentBoard(flow),
          flow.shareText == null ? "" : flow.shareText,
          flow.name.equals("daily"));
      int index = Integer.parseInt(choice.substring(1));
      if (index >= choices.size()) throw new ApiException(400, "INVALID_CHOICE", "That game is unavailable.");
      flow = flow.withGame(choices.get(index).slug()).withStep(flow.name.equals("daily") ? "result" : "range");
    } else if (flow.step.equals("range") && choice.matches("r[0-3]")) {
      flow = flow.withRange(List.of("today", "7d", "30d", "all").get(choice.charAt(1) - '0'));
      saveFlow(flow);
      postRankings(flow);
      clearFlow(userId);
      bot.answer(callbackId, "Rankings posted.");
      return;
    } else throw new ApiException(400, "INVALID_CHOICE", "That choice is unavailable.");
    saveFlow(flow);
    bot.answer(callbackId, "Selected.");
    continueFlow(flow);
  }

  private void onText(Flow flow, String text) {
    if (flow.step.equals("search")) {
      if (text.length() > 80) throw new ApiException(400, "QUERY_TOO_LONG", "Search using fewer than 80 characters.");
      Flow next = flow.withText(text).withStep("game");
      saveFlow(next); continueFlow(next); return;
    }
    if (flow.step.equals("code")) { link(flow.userId, text); clearFlow(flow.userId); return; }
    if (flow.step.equals("member")) {
      UUID shareId = parseBoard(text);
      games.addMember(new CurrentUser(linkedUser(flow.userId), ""), currentBoard(flow), shareId);
      bot.send(flow.originChatId == null ? flow.userId : flow.originChatId, "Member added to the leaderboard.");
      clearFlow(flow.userId); return;
    }
    if (flow.step.equals("result")) {
      if (flow.name.equals("daily")) {
        submitDaily(linkedUser(flow.userId), currentBoard(flow), flow.gameSlug, text,
            flow.originChatId == null ? flow.userId : flow.originChatId);
        clearFlow(flow.userId);
      } else {
        saveFlow(flow.withText(text).withStep("date"));
        bot.send(flow.userId, "Enter the LoLdle puzzle date as YYYY-MM-DD.");
      }
      return;
    }
    if (flow.step.equals("date")) {
      LocalDate date;
      try {
        if (!text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException();
        date = LocalDate.parse(text);
      } catch (RuntimeException exception) { throw new ApiException(422, "INVALID_DATE", "Enter a date as YYYY-MM-DD."); }
      var saved = games.submitLoldleFromDiscord(linkedUser(flow.userId), currentBoard(flow), flow.shareText, date);
      String joined = saved.stream().map(item -> item.game().replace("loldle-", "") + " " +
          item.submission().primaryScore().toPlainString()).reduce((a, b) -> a + " · " + b).orElse("");
      bot.send(flow.originChatId == null ? flow.userId : flow.originChatId, "Saved LoLdle for " + date + ": " + joined + ".");
      clearFlow(flow.userId);
    }
  }

  private void submitDaily(UUID userId, UUID boardId, String gameSlug, String text, long replyChatId) {
    DailyGamesService.DiscordSubmissionResult saved = games.submitFromDiscord(userId, boardId, gameSlug, text);
    GameAdapter adapter = adapters.require(saved.game().slug);
    String score = formatScore(saved.submission().primaryScore(), adapter.scoreFormat());
    String details = adapter.resultDetails(saved.submission().metrics()).stream().limit(7)
        .map(detail -> clean(detail.label(), 12) + " " + clean(detail.value(), 24))
        .reduce((a, b) -> a + " · " + b).orElse("");
    bot.send(replyChatId,
        "Saved " + saved.game().name + " #" + saved.submission().puzzleKey() + " at " + score + "." +
            (details.isEmpty() ? "" : "\n" + details));
  }

  private void postRankings(Flow flow) {
    UUID boardId = currentBoard(flow);
    ApiModels.LeaderboardResponse board = games.board(new CurrentUser(linkedUser(flow.userId), ""),
        boardId, flow.range, flow.gameSlug);
    String label = switch (flow.range) { case "7d" -> "Last 7 days"; case "30d" -> "Last 30 days";
      case "all" -> "All time"; default -> "Today"; };
    StringBuilder text = new StringBuilder(board.name()).append(" · ").append(board.game().name())
        .append(" · ").append(label).append("\n");
    if (board.rankings().isEmpty()) text.append("Nobody has posted a score for this range yet.\n");
    else for (var row : board.rankings().stream().limit(10).toList()) {
      String line = row.rank() + ". " + clean(row.displayName(), 60) + " — " +
          clean(row.displayScore() == null ? formatScore(row.average(), board.game().scoreFormat()) : row.displayScore(), 50) +
          " (" + row.gamesPlayed() + (row.gamesPlayed() == 1 ? " score)" : " scores)") + "\n";
      if (text.length() + line.length() > 3700) break;
      text.append(line);
    }
    text.append(baseUrl).append("/leaderboards/").append(board.id()).append("?game=")
        .append(flow.gameSlug).append("&range=").append(flow.range);
    bot.send(flow.originChatId == null ? flow.userId : flow.originChatId, text.toString());
  }

  private UUID currentBoard(Flow flow) {
    UUID id = flow.originChatId == null ? flow.boardId : mapped(flow.originChatId);
    if (id == null || flow.boardId != null && !flow.boardId.equals(id))
      throw new ApiException(409, "MAPPING_CHANGED", "The connected leaderboard changed. Start again.");
    requireAccessible(board(id));
    return id;
  }

  private String nextStep(String name) {
    return switch (name) { case "leaderboard", "daily" -> "game";
      case "loldle" -> "result"; case "add_member" -> "member"; default -> "code"; };
  }

  private List<Leaderboard> eligibleBoards(UUID userId) {
    return boards.findForUser(userId).stream().filter(board -> board.boardType == BoardType.MULTI_GAME)
        .filter(board -> billing.isBoardAccessible(board, clock.instant())).toList();
  }
  private List<Leaderboard> availableBoards(Flow flow) {
    UUID userId = linkedUser(flow.userId);
    return eligibleBoards(userId).stream().filter(board -> !flow.name.equals("add_member") || board.ownerId.equals(userId)).toList();
  }

  private UUID linkedUser(long telegramUserId) {
    List<UUID> values = jdbc.query("select user_id from telegram_account_links where telegram_user_id=?",
        (rs, row) -> rs.getObject(1, UUID.class), telegramUserId);
    if (values.isEmpty()) throw new ApiException(403, "NOT_LINKED", "Link your account in the bot's private chat first.");
    return values.getFirst();
  }

  private void link(long telegramUserId, String rawCode) {
    String code = rawCode.replace("-", "").replaceAll("\\s", "").toUpperCase(Locale.ROOT);
    if (!code.matches("[0-9A-HJKMNP-TV-Z]{12}"))
      throw new ApiException(400, "INVALID_CODE", "That link code is invalid.");
    Instant now = clock.instant();
    List<UUID> users = jdbc.query("select user_id from telegram_link_codes where token_hash=? and consumed_at is null and expires_at>?",
        (rs, row) -> rs.getObject(1, UUID.class), hash(code), ts(now));
    if (users.isEmpty()) throw new ApiException(400, "INVALID_CODE", "That link code expired or was already used.");
    UUID user = users.getFirst();
    List<UUID> existing = jdbc.query("select user_id from telegram_account_links where telegram_user_id=?",
        (rs, row) -> rs.getObject(1, UUID.class), telegramUserId);
    if (!existing.isEmpty() && !existing.getFirst().equals(user))
      throw new ApiException(409, "ACCOUNT_MISMATCH", "This Telegram identity is linked to another account. Unlink it first.");
    List<Long> byUser = jdbc.query("select telegram_user_id from telegram_account_links where user_id=?",
        (rs, row) -> rs.getLong(1), user);
    if (!byUser.isEmpty() && byUser.getFirst() != telegramUserId)
      throw new ApiException(409, "ACCOUNT_MISMATCH", "This profile is linked to another Telegram identity.");
    if (jdbc.update("update telegram_link_codes set consumed_at=? where token_hash=? and consumed_at is null and expires_at>?",
        ts(now), hash(code), ts(now)) == 0)
      throw new ApiException(400, "INVALID_CODE", "That link code expired or was already used.");
    jdbc.update("insert into telegram_account_links(telegram_user_id,user_id,linked_at) values (?,?,?) on conflict do nothing",
        telegramUserId, user, ts(now));
    bot.send(telegramUserId, "Your Telegram account is linked to Daily Tracker.");
  }

  private void signup(long telegramUserId, Long groupId, UUID boardId) {
    String token = randomToken();
    jdbc.update("insert into telegram_signup_intents(token_hash,telegram_user_id,chat_id,leaderboard_id,expires_at) values (?,?,?,?,?)",
        hash(token), telegramUserId, groupId, boardId, ts(clock.instant().plus(Duration.ofHours(24))));
    bot.send(telegramUserId, "Create or sign in to Daily Tracker: " + baseUrl + "/telegram/signup?intent=" + token +
        "\nThis one-time link expires in 24 hours.");
  }

  boolean validSignup(String token) {
    if (!tokenFormat(token)) return false;
    return Boolean.TRUE.equals(jdbc.queryForObject(
        "select exists(select 1 from telegram_signup_intents where token_hash=? and consumed_at is null and expires_at>?)",
        Boolean.class, hash(token), ts(clock.instant())));
  }

  @Transactional
  void requestSignupConfirmation(UUID userId, String token) {
    if (!profiles.existsById(userId)) throw new ApiException(409, "PROFILE_REQUIRED", "Finish your profile first.");
    Signup intent = signupIntent(token);
    if (intent.userId != null && !intent.userId.equals(userId))
      throw new ApiException(409, "ACCOUNT_MISMATCH", "This signup link belongs to another account.");
    if (intent.challengeAt != null && intent.challengeAt.plus(Duration.ofMinutes(1)).isAfter(clock.instant()))
      throw new ApiException(429, "CONFIRMATION_RATE_LIMITED", "Wait a minute before sending another confirmation.");
    UUID linked = linkedUserOrNull(intent.telegramUserId);
    if (linked != null && !linked.equals(userId))
      throw new ApiException(409, "ACCOUNT_MISMATCH", "This Telegram identity is linked to another account.");
    List<Long> byUser = jdbc.query("select telegram_user_id from telegram_account_links where user_id=?",
        (rs, row) -> rs.getLong(1), userId);
    if (!byUser.isEmpty() && byUser.getFirst() != intent.telegramUserId)
      throw new ApiException(409, "ACCOUNT_MISMATCH", "Your account is linked to another Telegram identity.");
    jdbc.update("update telegram_signup_intents set user_id=?,challenge_at=?,confirmed_at=null where token_hash=?",
        userId, ts(clock.instant()), hash(token));
    String name = profiles.findById(userId).map(profile -> profile.displayName).orElse("this account");
    bot.send(intent.telegramUserId, "Confirm that you want to link this Telegram identity to Daily Tracker account " +
        clean(name, 80) + ".", List.of(List.of(Map.of("text", "Confirm identity", "callback_data", "s:" + token))));
  }

  private void confirmSignup(long telegramUserId, String token) {
    Signup intent = signupIntent(token);
    if (intent.telegramUserId != telegramUserId || intent.userId == null || intent.challengeAt == null ||
        !intent.challengeAt.plus(Duration.ofMinutes(15)).isAfter(clock.instant()))
      throw new ApiException(403, "IDENTITY_MISMATCH", "This confirmation is invalid or expired.");
    jdbc.update("update telegram_signup_intents set confirmed_at=? where token_hash=?", ts(clock.instant()), hash(token));
  }

  @Transactional
  String completeSignup(UUID userId, String token) {
    Signup intent = signupIntent(token);
    if (!userId.equals(intent.userId) || intent.confirmedAt == null ||
        !intent.confirmedAt.plus(Duration.ofMinutes(15)).isAfter(clock.instant()))
      throw new ApiException(403, "IDENTITY_NOT_CONFIRMED", "Confirm your identity in Telegram first.");
    UUID existing = linkedUserOrNull(intent.telegramUserId);
    if (existing != null && !existing.equals(userId))
      throw new ApiException(409, "ACCOUNT_MISMATCH", "This Telegram identity belongs to another account.");
    List<Long> byUser = jdbc.query("select telegram_user_id from telegram_account_links where user_id=?",
        (rs, row) -> rs.getLong(1), userId);
    if (!byUser.isEmpty() && byUser.getFirst() != intent.telegramUserId)
      throw new ApiException(409, "ACCOUNT_MISMATCH", "Your account is linked to another Telegram identity.");
    jdbc.update("insert into telegram_account_links(telegram_user_id,user_id,linked_at) values (?,?,?) on conflict do nothing",
        intent.telegramUserId, userId, ts(clock.instant()));
    if (jdbc.update("update telegram_signup_intents set consumed_at=? where token_hash=? and consumed_at is null",
        ts(clock.instant()), hash(token)) == 0)
      throw new ApiException(410, "SIGNUP_EXPIRED", "This signup link was already used.");
    if (intent.boardId == null) return "linked";
    UUID mapped = optionalMapped(intent.chatId);
    if (!intent.boardId.equals(mapped)) return "mapping";
    try { games.addSignupMember(intent.boardId, userId); return "joined"; }
    catch (ApiException exception) {
      return exception.code.equals("OWNER_SUBSCRIPTION_REQUIRED") ? "locked" : "join-failed";
    }
  }

  private Signup signupIntent(String token) {
    if (!tokenFormat(token)) throw new ApiException(410, "SIGNUP_EXPIRED", "This signup link expired.");
    List<Signup> values = jdbc.query(
        "select telegram_user_id,chat_id,leaderboard_id,user_id,challenge_at,confirmed_at from telegram_signup_intents " +
        "where token_hash=? and consumed_at is null and expires_at>?",
        (rs, row) -> new Signup(rs.getLong(1), (Long) rs.getObject(2), rs.getObject(3, UUID.class),
            rs.getObject(4, UUID.class), rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant(),
            rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant()),
        hash(token), ts(clock.instant()));
    if (values.isEmpty()) throw new ApiException(410, "SIGNUP_EXPIRED", "This signup link expired.");
    return values.getFirst();
  }

  private UUID linkedUserOrNull(long telegramUserId) {
    List<UUID> values = jdbc.query("select user_id from telegram_account_links where telegram_user_id=?",
        (rs, row) -> rs.getObject(1, UUID.class), telegramUserId);
    return values.isEmpty() ? null : values.getFirst();
  }

  private Leaderboard board(UUID id) {
    return boards.findById(id).orElseThrow(() -> new ApiException(404, "LEADERBOARD_NOT_FOUND", "Leaderboard not found."));
  }
  private void requireAccessible(Leaderboard board) {
    if (board.boardType != BoardType.MULTI_GAME)
      throw new ApiException(400, "MULTI_GAME_REQUIRED", "Telegram requires a multi-game leaderboard.");
    if (billing.allGameLimit(board.ownerId, clock.instant()) == 0 || !billing.isBoardAccessible(board, clock.instant()))
      throw new ApiException(402, "OWNER_SUBSCRIPTION_REQUIRED", "The board owner needs an active paid plan.");
  }
  private UUID optionalMapped(long chatId) {
    List<UUID> values = jdbc.query("select leaderboard_id from telegram_group_leaderboards where chat_id=?",
        (rs, row) -> rs.getObject(1, UUID.class), chatId);
    return values.isEmpty() ? null : values.getFirst();
  }
  private UUID mapped(long chatId) {
    UUID id = optionalMapped(chatId);
    if (id == null) throw new ApiException(404, "GROUP_NOT_CONFIGURED", "This group is not connected. Ask an owner to run /setup.");
    requireAccessible(board(id));
    return id;
  }
  private UUID parseBoard(String value) {
    try { return UUID.fromString(value); }
    catch (RuntimeException exception) { throw new ApiException(400, "INVALID_UUID", "Enter a valid UUID."); }
  }
  private long id(JsonNode value) {
    if (!value.canConvertToLong()) throw new ApiException(400, "INVALID_UPDATE", "Telegram sent an invalid identity.");
    return value.longValue();
  }
  private String formatScore(BigDecimal score, ApiModels.ScoreFormatView format) {
    BigDecimal display = score.setScale(format.maximumDecimalPlaces(), RoundingMode.HALF_UP).stripTrailingZeros();
    if (display.scale() < format.minimumDecimalPlaces()) display = display.setScale(format.minimumDecimalPlaces());
    return format.prefix() + display.toPlainString() + format.suffix();
  }
  private String clean(String value, int max) {
    String result = value.replaceAll("[\\p{Cntrl}]", " ");
    return result.length() <= max ? result : result.substring(0, max - 1) + "…";
  }
  private List<List<Map<String, String>>> linkButton(String label, String url) {
    return List.of(List.of(Map.of("text", label, "url", url)));
  }
  private List<List<Map<String, String>>> choices(String token, String prefix, List<String> labels) {
    List<List<Map<String, String>>> rows = new ArrayList<>();
    for (int i = 0; i < labels.size(); i++) {
      rows.add(List.of(Map.of("text", clean(labels.get(i), 55), "callback_data", "f:" + token + ":" + prefix + i)));
    }
    return rows;
  }
  private String randomToken() {
    byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
  private String randomShort() {
    byte[] bytes = new byte[12]; RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
  private String randomCode() {
    String alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    StringBuilder code = new StringBuilder();
    for (int i = 0; i < 12; i++) code.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
    return code.toString();
  }
  private String hash(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
          ("telegram:v1:" + pepper + ":" + value).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) { throw new IllegalStateException(exception); }
  }
  private java.sql.Timestamp ts(Instant value) { return java.sql.Timestamp.from(value); }
  private boolean tokenFormat(String token) { return token != null && token.matches("[A-Za-z0-9_-]{43}"); }

  private Flow flow(long userId) {
    List<Flow> values = jdbc.query("select origin_chat_id,flow,step,leaderboard_id,game_slug,range_key,share_text," +
        "callback_token,expires_at from telegram_pending_flows where telegram_user_id=?",
        (rs, row) -> new Flow(userId, (Long) rs.getObject(1), rs.getString(2), rs.getString(3),
            rs.getObject(4, UUID.class), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
            rs.getTimestamp(9).toInstant()), userId);
    return values.isEmpty() ? null : values.getFirst();
  }
  private void saveFlow(Flow flow) {
    jdbc.update("insert into telegram_pending_flows(telegram_user_id,origin_chat_id,flow,step,leaderboard_id," +
        "game_slug,range_key,share_text,callback_token,expires_at) values (?,?,?,?,?,?,?,?,?,?) " +
        "on conflict(telegram_user_id) do update set origin_chat_id=excluded.origin_chat_id,flow=excluded.flow," +
        "step=excluded.step,leaderboard_id=excluded.leaderboard_id,game_slug=excluded.game_slug," +
        "range_key=excluded.range_key,share_text=excluded.share_text,callback_token=excluded.callback_token," +
        "expires_at=excluded.expires_at", flow.userId, flow.originChatId, flow.name, flow.step, flow.boardId,
        flow.gameSlug, flow.range, flow.shareText, flow.token, ts(flow.expiresAt));
  }
  private void clearFlow(long userId) { jdbc.update("delete from telegram_pending_flows where telegram_user_id=?", userId); }
  @Scheduled(cron = "0 19 4 * * *")
  void cleanup() {
    Instant now = clock.instant();
    jdbc.update("delete from telegram_pending_flows where expires_at<?", ts(now));
    jdbc.update("delete from telegram_link_codes where expires_at<?", ts(now.minus(Duration.ofDays(1))));
    jdbc.update("delete from telegram_signup_intents where expires_at<?", ts(now.minus(Duration.ofDays(1))));
    jdbc.update("delete from processed_telegram_updates where processed_at<?", ts(now.minus(Duration.ofDays(7))));
  }
  private record Signup(long telegramUserId, Long chatId, UUID boardId, UUID userId, Instant challengeAt, Instant confirmedAt) {}
  private record Flow(long userId, Long originChatId, String name, String step, UUID boardId,
      String gameSlug, String range, String shareText, String token, Instant expiresAt) {
    Flow withStep(String next) { return new Flow(userId, originChatId, name, next, boardId, gameSlug, range, shareText, token, expiresAt); }
    Flow withBoard(UUID next) { return new Flow(userId, originChatId, name, step, next, gameSlug, range, shareText, token, expiresAt); }
    Flow withGame(String next) { return new Flow(userId, originChatId, name, step, boardId, next, range, shareText, token, expiresAt); }
    Flow withRange(String next) { return new Flow(userId, originChatId, name, step, boardId, gameSlug, next, shareText, token, expiresAt); }
    Flow withText(String next) { return new Flow(userId, originChatId, name, step, boardId, gameSlug, range, next, token, expiresAt); }
  }
}

@RestController
@RequestMapping("/api/v1/telegram")
class TelegramController {
  private final TelegramGateway bot;
  private final TelegramService service;
  private final ObjectMapper json;
  TelegramController(TelegramGateway bot, TelegramService service, ObjectMapper json) {
    this.bot = bot; this.service = service; this.json = json;
  }

  @PostMapping(value = "/webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<Void> webhook(@RequestHeader(value = "X-Telegram-Bot-Api-Secret-Token", required = false) String secret,
      @RequestBody byte[] body) {
    if (!bot.accepts(secret)) throw new ApiException(401, "INVALID_TELEGRAM_SECRET", "Invalid Telegram webhook secret.");
    if (body.length > 100_000) throw new ApiException(413, "UPDATE_TOO_LARGE", "Telegram update is too large.");
    try { service.handle(json.readTree(body)); }
    catch (JacksonException exception) { throw new ApiException(400, "INVALID_UPDATE", "Malformed Telegram update."); }
    return ResponseEntity.ok().build();
  }

  @GetMapping("/status")
  ResponseEntity<Map<String, Object>> status(Authentication auth) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.status(current(auth)));
  }
  @PostMapping("/link-codes")
  ResponseEntity<Map<String, Object>> issue(Authentication auth) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.issueLink(current(auth)));
  }
  @DeleteMapping("/link")
  ResponseEntity<Void> unlink(Authentication auth) {
    service.unlinkWebsite(current(auth)); return ResponseEntity.noContent().build();
  }
  @GetMapping("/leaderboards/{boardId}/groups")
  ResponseEntity<List<Map<String, Object>>> groups(Authentication auth, @PathVariable UUID boardId) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.groups(current(auth), boardId));
  }
  @DeleteMapping("/leaderboards/{boardId}/groups/{chatId}")
  ResponseEntity<Void> disconnect(Authentication auth, @PathVariable UUID boardId, @PathVariable long chatId) {
    service.disconnect(current(auth), boardId, chatId); return ResponseEntity.noContent().build();
  }
  @GetMapping("/signup-intents/{token}")
  ResponseEntity<Map<String, Boolean>> signup(@PathVariable String token) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("valid", service.validSignup(token)));
  }
  @PostMapping("/signup-intents/{token}/confirmation")
  ResponseEntity<Void> confirm(Authentication auth, @PathVariable String token) {
    service.requestSignupConfirmation(current(auth), token); return ResponseEntity.accepted().build();
  }
  @PostMapping("/signup-intents/{token}/complete")
  ResponseEntity<Map<String, String>> complete(Authentication auth, @PathVariable String token) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("status", service.completeSignup(current(auth), token)));
  }
  private UUID current(Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof CurrentUser user))
      throw new ApiException(401, "UNAUTHORIZED", "Sign in first.");
    return user.id();
  }
}
