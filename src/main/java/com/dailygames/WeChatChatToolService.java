package com.dailygames;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
class WeChatChatToolService {
  static final String COMPLETE_TEMPLATE = "4A68CBB88A92B0A9311848DBA1E94A199B166463";
  record Capabilities(boolean enabled, boolean dynamicCardsEnabled) {}
  record ChatShare(String title, String path, UUID cardId, String activityId,
                   String templateId, Instant expiresAt) {}
  private record Context(String groupKey, String memberCipher) {}
  private record Pending(UUID id, String activityId, String memberCipher, Instant expiresAt) {}

  private static final Logger log = LoggerFactory.getLogger(WeChatChatToolService.class);
  private final JdbcTemplate jdbc;
  private final WeChatService wechat;
  private final LeaderboardInviteService invites;
  private final WeChatActivityGateway gateway;
  private final boolean dynamicEnabled;
  private final int versionType;

  WeChatChatToolService(JdbcTemplate jdbc, WeChatService wechat, LeaderboardInviteService invites,
      WeChatActivityGateway gateway,
      @Value("${app.wechat.dynamic-cards-enabled:false}") boolean dynamicEnabled,
      @Value("${app.wechat.chat-tool-version-type:0}") int versionType) {
    this.jdbc = jdbc; this.wechat = wechat; this.invites = invites; this.gateway = gateway;
    this.dynamicEnabled = dynamicEnabled; this.versionType = versionType;
    if (dynamicEnabled && !wechat.chatToolAvailable())
      throw new IllegalStateException("Dynamic cards require approved Chat Tool Mode");
    if (versionType < 0 || versionType > 2)
      throw new IllegalArgumentException("WECHAT_CHAT_TOOL_VERSION_TYPE must be 0, 1, or 2");
  }

  Capabilities capabilities() { return new Capabilities(wechat.chatToolAvailable(), dynamicEnabled && wechat.chatToolAvailable()); }

  @Transactional
  WeChatService.GroupResult resolve(UUID userId, String miniToken, String encryptedData, String iv) {
    requireEnabled();
    WeChatService.ChatEvidence evidence = wechat.resolveChatEvidence(userId, miniToken, encryptedData, iv);
    jdbc.update("insert into wechat_chat_tool_contexts(token_hash,group_key,member_cipher,expires_at) values (?,?,?,?) "
            + "on conflict (token_hash) do update set group_key=excluded.group_key,member_cipher=excluded.member_cipher,"
            + "expires_at=excluded.expires_at", wechat.sessionHash(miniToken), evidence.groupKey(),
        evidence.memberCipher(), ts(Instant.now().plus(Duration.ofMinutes(30))));
    return evidence.group();
  }

  @Transactional
  ChatShare prepareCard(UUID userId, String miniToken, UUID boardId) {
    requireEnabled();
    Context context = context(miniToken);
    invites.shareableBoardName(boardId, userId);
    if (jdbc.queryForObject("select count(*) from wechat_group_links where group_key=? and leaderboard_id=? "
        + "and disconnected_at is null", Integer.class, context.groupKey(), boardId) == 0)
      throw new ApiException(409, "WECHAT_GROUP_NOT_LINKED", "Link this verified group to the leaderboard first.");
    WeChatService.ShareResult share = wechat.createShare(userId, boardId);
    String path = "/chat-tool/pages/index/index?boardId=" + boardId + "&ref=" + share.reference();
    if (!dynamicEnabled) return new ChatShare(share.title(), path, null, null, null, share.expiresAt());
    int recent = jdbc.queryForObject("select count(*) from wechat_chat_tool_cards where created_by_user_id=? "
        + "and created_at>?", Integer.class, userId, ts(Instant.now().minus(Duration.ofHours(1))));
    if (recent >= 10) throw new ApiException(429, "WECHAT_CARD_RATE_LIMITED", "Wait before creating another card.");
    WeChatActivityGateway.Activity activity = gateway.createActivity();
    Instant expires = share.expiresAt().isBefore(activity.expiresAt()) ? share.expiresAt() : activity.expiresAt();
    UUID cardId = UUID.randomUUID();
    jdbc.update("insert into wechat_chat_tool_cards(id,leaderboard_id,group_key,activity_id,created_by_user_id,created_at,expires_at) "
            + "values (?,?,?,?,?,?,?)", cardId, boardId, context.groupKey(), activity.id(), userId,
        ts(Instant.now()), ts(expires));
    return new ChatShare(share.title(), path, cardId, activity.id(), COMPLETE_TEMPLATE, expires);
  }

  @Transactional
  void shared(UUID userId, String miniToken, UUID cardId) {
    requireEnabled();
    Context context = context(miniToken);
    int updated = jdbc.update("update wechat_chat_tool_cards set shared_at=coalesce(shared_at,?) "
            + "where id=? and created_by_user_id=? and group_key=? and expires_at>?", ts(Instant.now()),
        cardId, userId, context.groupKey(), ts(Instant.now()));
    if (updated == 0) throw new ApiException(404, "WECHAT_CARD_NOT_FOUND", "That card is unavailable.");
  }

  void afterSubmission(UUID userId, String miniToken, List<UUID> submissionIds) {
    if (!dynamicEnabled || !wechat.isMiniToken(miniToken) || submissionIds.isEmpty()) return;
    try { enqueue(userId, miniToken, submissionIds); }
    catch (RuntimeException error) { log.warn("WeChat dynamic update could not be queued"); }
  }

  @Transactional
  void enqueue(UUID userId, String miniToken, List<UUID> submissionIds) {
    Context context;
    try { context = context(miniToken); } catch (ApiException error) { return; }
    Instant now = Instant.now();
    for (UUID submissionId : submissionIds) {
      List<UUID> cards = jdbc.query("select c.id from wechat_chat_tool_cards c "
          + "join wechat_group_links g on g.group_key=c.group_key and g.leaderboard_id=c.leaderboard_id "
          + "and g.disconnected_at is null join leaderboards b on b.id=c.leaderboard_id "
          + "join submissions s on s.id=? and s.user_id=? "
          + "where c.group_key=? and c.shared_at is not null and c.expires_at>? "
          + "and (b.board_type='MULTI_GAME' or b.game_id=s.game_id) "
          + "and exists (select 1 from leaderboard_members m where m.leaderboard_id=b.id and m.user_id=?)",
          (rs, row) -> rs.getObject(1, UUID.class), submissionId, userId,
          context.groupKey(), ts(now), userId);
      for (UUID cardId : cards) jdbc.update("insert into wechat_chat_tool_updates(id,card_id,submission_id,member_cipher,created_at) "
              + "values (?,?,?,?,?) on conflict (card_id,submission_id) do nothing", UUID.randomUUID(),
          cardId, submissionId, context.memberCipher(), ts(now));
    }
  }

  @Scheduled(fixedDelay = 30_000)
  @Transactional
  void processPending() {
    if (!dynamicEnabled || !wechat.chatToolAvailable()) return;
    List<Pending> pending = jdbc.query("select u.id,c.activity_id,u.member_cipher,c.expires_at "
        + "from wechat_chat_tool_updates u join wechat_chat_tool_cards c on c.id=u.card_id "
        + "where u.processed_at is null and u.attempts<5 order by u.created_at limit 10 for update of u skip locked",
        (rs, row) -> new Pending(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
            rs.getTimestamp(4).toInstant()));
    for (Pending update : pending) {
      Instant now = Instant.now();
      if (!update.expiresAt().isAfter(now)) {
        jdbc.update("update wechat_chat_tool_updates set processed_at=?,last_error_code='EXPIRED' where id=?", ts(now), update.id());
        continue;
      }
      try {
        gateway.markComplete(update.activityId(), wechat.revealChatMember(update.memberCipher()),
            COMPLETE_TEMPLATE, versionType);
        jdbc.update("update wechat_chat_tool_updates set processed_at=?,last_error_code=null where id=?", ts(now), update.id());
      } catch (RuntimeException error) {
        jdbc.update("update wechat_chat_tool_updates set attempts=attempts+1,last_error_code='PLATFORM_FAILURE' where id=?", update.id());
        log.warn("WeChat dynamic update failed; retry scheduled");
      }
    }
  }

  @Scheduled(cron = "0 41 3 * * *")
  void cleanup() {
    Instant before = Instant.now().minus(Duration.ofDays(1));
    jdbc.update("delete from wechat_chat_tool_contexts where expires_at<?", ts(before));
    jdbc.update("delete from wechat_chat_tool_cards where expires_at<?", ts(before));
  }

  private Context context(String miniToken) {
    if (!wechat.isMiniToken(miniToken))
      throw new ApiException(401, "WECHAT_SESSION_REQUIRED", "Sign in with WeChat again.");
    List<Context> rows = jdbc.query("select group_key,member_cipher from wechat_chat_tool_contexts "
        + "where token_hash=? and expires_at>?", (rs, row) -> new Context(rs.getString(1), rs.getString(2)),
        wechat.sessionHash(miniToken), ts(Instant.now()));
    if (rows.isEmpty()) throw new ApiException(410, "WECHAT_CHAT_CONTEXT_EXPIRED", "Open the chat tool from the group again.");
    return rows.getFirst();
  }

  private void requireEnabled() {
    if (!wechat.chatToolAvailable())
      throw new ApiException(503, "WECHAT_CHAT_TOOL_DISABLED", "Use a regular group card.");
  }
  private static Timestamp ts(Instant instant) { return Timestamp.from(instant); }
}
