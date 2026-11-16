package com.dailygames;

import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
class WeChatService {
  private static final Logger log = LoggerFactory.getLogger(WeChatService.class);
  record SessionResult(String token, boolean onboarded, boolean linkedAccount) {}
  record CodeResult(String code, Instant expiresAt) {}
  record ShareResult(String reference, String title, Instant expiresAt) {}
  record SharePreview(UUID leaderboardId, String name) {}
  record GroupResult(boolean verified, boolean mapped, UUID leaderboardId, String name, UUID linkId) {}
  record GroupLinkView(UUID id, UUID leaderboardId, Instant linkedAt) {}
  record Status(boolean enabled, boolean linked, boolean provisional) {}
  record ChatEvidence(String groupKey, String memberCipher, GroupResult group) {}
  private record Identity(UUID id, UUID userId, boolean provisional) {}
  private record Share(UUID boardId, String name) {}

  private final JdbcTemplate jdbc;
  private final AuthService auth;
  private final LeaderboardInviteService invites;
  private final WeChatGateway gateway;
  private final WeChatCrypto crypto;
  private final String appId;
  private final boolean enabled;
  private final boolean groupEnabled;
  private final boolean chatToolEnabled;
  private final boolean chatToolApproved;

  WeChatService(JdbcTemplate jdbc, AuthService auth, LeaderboardInviteService invites, WeChatGateway gateway,
      ObjectMapper json, @Value("${app.token-pepper}") String pepper,
      @Value("${app.wechat.app-id:}") String appId,
      @Value("${app.wechat.app-secret:}") String appSecret,
      @Value("${app.wechat.session-encryption-key:}") String storageKey,
      @Value("${app.wechat.enabled:false}") boolean enabled,
      @Value("${app.wechat.group-context-enabled:false}") boolean groupEnabled,
      @Value("${app.wechat.chat-tool-enabled:false}") boolean chatToolEnabled,
      @Value("${app.wechat.chat-tool-approved:false}") boolean chatToolApproved) {
    this.jdbc = jdbc; this.auth = auth; this.invites = invites; this.gateway = gateway;
    this.appId = appId; this.enabled = enabled; this.groupEnabled = groupEnabled;
    this.chatToolEnabled = chatToolEnabled;
    this.chatToolApproved = chatToolApproved;
    this.crypto = new WeChatCrypto(storageKey, pepper, appId, json);
    if (enabled && (!crypto.configured() || appSecret.isBlank()))
      throw new IllegalStateException("WeChat requires AppID, AppSecret and session encryption key");
    if (chatToolEnabled && (!enabled || !chatToolApproved))
      throw new IllegalStateException("Chat Tool Mode requires enabled Mini Program and verified console approval");
  }

  boolean available() { return enabled && crypto.configured(); }
  boolean groupAvailable() { return available() && groupEnabled; }
  boolean chatToolAvailable() { return available() && chatToolEnabled && chatToolApproved; }
  String sessionHash(String miniToken) { return auth.tokenHash(miniToken); }
  String revealChatMember(String cipher) { return crypto.reveal(cipher); }

  @Transactional(noRollbackFor = ApiException.class)
  void limitScoreAction(String token) {
    if (isMiniToken(token)) rate("score:" + auth.tokenHash(token), 120, Duration.ofHours(1));
  }

  Status status(UUID userId) {
    if (!available()) return new Status(false, false, false);
    Identity identity = identityForUser(userId);
    return new Status(true, identity != null, identity != null && identity.provisional());
  }

  boolean isMiniToken(String token) {
    if (!available() || !WeChatCrypto.validToken(token)) return false;
    return !jdbc.query("select 1 from wechat_sessions w join auth_sessions a on a.token_hash=w.token_hash "
        + "where w.token_hash=? and a.expires_at>?", (rs, row) -> 1,
        auth.tokenHash(token), ts(Instant.now())).isEmpty();
  }

  @Transactional(noRollbackFor = ApiException.class)
  SessionResult login(String code, String clientAddress) {
    requireEnabled();
    rate("login:" + clientAddress, 20, Duration.ofHours(1));
    WeChatGateway.Session platform = gateway.exchange(code);
    if (platform.openid().length() > 256 || platform.sessionKey().length() > 256)
      throw new ApiException(502, "WECHAT_LOGIN_FAILED", "WeChat sign-in failed. Please try again.");
    String openidHash = crypto.hash("openid", platform.openid());
    advisoryLock(openidHash);
    Identity identity = identityForOpenid(openidHash);
    AuthService.LoginResult session;
    boolean linked;
    if (identity == null) {
      session = auth.registerExternal();
      UUID userId = auth.authenticate(session.sessionToken()).orElseThrow().id();
      jdbc.update("insert into wechat_identities(id,app_id,openid_hash,user_id,provisional,linked_at) values (?,?,?,?,?,?)",
          UUID.randomUUID(), appId, openidHash, userId, true, ts(Instant.now()));
      linked = false;
    } else {
      session = auth.loginExternal(identity.userId());
      linked = !identity.provisional();
    }
    jdbc.update("insert into wechat_sessions(token_hash,session_key_cipher,key_expires_at) values (?,?,?)",
        auth.tokenHash(session.sessionToken()), crypto.protect(platform.sessionKey()), ts(Instant.now().plus(Duration.ofHours(2))));
    log.info("WeChat sign-in completed linkedAccount={}", linked);
    return new SessionResult(session.sessionToken(), session.onboarded(), linked);
  }

  @Transactional(noRollbackFor = ApiException.class)
  CodeResult issueCode(UUID userId, String websiteToken) {
    requireEnabled();
    if (isMiniToken(websiteToken)) throw new ApiException(403, "WECHAT_WEBSITE_REQUIRED", "Generate this code on the website.");
    rate("code:" + userId, 5, Duration.ofHours(1));
    Instant now = Instant.now();
    jdbc.update("update wechat_link_codes set consumed_at=? where user_id=? and consumed_at is null", ts(now), userId);
    String code = crypto.randomToken().substring(0, 12);
    jdbc.update("insert into wechat_link_codes(token_hash,user_id,created_at,expires_at) values (?,?,?,?)",
        crypto.hash("link-code", code), userId, ts(now), ts(now.plus(Duration.ofMinutes(10))));
    return new CodeResult(code, now.plus(Duration.ofMinutes(10)));
  }

  @Transactional(noRollbackFor = ApiException.class)
  SessionResult redeemCode(UUID userId, String miniToken, String code) {
    requireMini(miniToken);
    rate("redeem:" + userId, 10, Duration.ofHours(1));
    if (code == null || !code.matches("[A-Za-z0-9_-]{12}")) throw invalidCode();
    Identity identity = identityForUser(userId);
    if (identity == null || !identity.provisional())
      throw new ApiException(409, "WECHAT_ALREADY_LINKED", "This WeChat identity is already linked.");
    if (jdbc.queryForObject("select count(*) from profiles where user_id=?", Integer.class, userId) != 0)
      throw new ApiException(409, "WECHAT_PROFILE_EXISTS", "Link your existing account before creating a new profile.");
    Instant now = Instant.now();
    List<UUID> targets = jdbc.query("select user_id from wechat_link_codes where token_hash=? and consumed_at is null "
        + "and expires_at>? for update", (rs, row) -> rs.getObject(1, UUID.class),
        crypto.hash("link-code", code), ts(now));
    if (targets.isEmpty()) throw invalidCode();
    UUID target = targets.getFirst();
    if (target.equals(userId)) throw invalidCode();
    if (identityForUser(target) != null)
      throw new ApiException(409, "WECHAT_ACCOUNT_CONFLICT", "That account is linked to another WeChat identity.");
    String oldHash = auth.tokenHash(miniToken);
    String cipher = jdbc.queryForObject("select session_key_cipher from wechat_sessions where token_hash=?",
        String.class, oldHash);
    Timestamp keyExpiry = jdbc.queryForObject("select key_expires_at from wechat_sessions where token_hash=?",
        Timestamp.class, oldHash);
    jdbc.update("update wechat_link_codes set consumed_at=? where token_hash=? and consumed_at is null", ts(now),
        crypto.hash("link-code", code));
    jdbc.update("update wechat_identities set user_id=?,provisional=false where id=?", target, identity.id());
    jdbc.update("delete from auth_sessions where user_id=?", userId);
    jdbc.update("delete from app_users where id=?", userId);
    AuthService.LoginResult session = auth.loginExternal(target);
    jdbc.update("insert into wechat_sessions(token_hash,session_key_cipher,key_expires_at) values (?,?,?)",
        auth.tokenHash(session.sessionToken()), cipher, keyExpiry);
    audit("IDENTITY_LINKED", target, null);
    log.info("WeChat identity linked");
    return new SessionResult(session.sessionToken(), session.onboarded(), true);
  }

  @Transactional
  void unlink(UUID userId) {
    requireEnabled();
    Identity identity = identityForUser(userId);
    if (identity == null) throw new ApiException(404, "WECHAT_NOT_LINKED", "No WeChat identity is linked.");
    Instant now = Instant.now();
    jdbc.update("update wechat_identities set unlinked_at=? where id=?", ts(now), identity.id());
    jdbc.update("delete from auth_sessions where token_hash in "
        + "(select w.token_hash from wechat_sessions w join auth_sessions a on a.token_hash=w.token_hash where a.user_id=?)", userId);
    audit("IDENTITY_UNLINKED", userId, null);
    log.info("WeChat identity unlinked");
  }

  @Transactional(noRollbackFor = ApiException.class)
  ShareResult createShare(UUID userId, UUID boardId) {
    requireEnabled();
    rate("share:" + userId, 30, Duration.ofHours(1));
    String name = invites.shareableBoardName(boardId, userId);
    Instant now = Instant.now();
    String reference = crypto.randomToken();
    jdbc.update("insert into wechat_share_refs(token_hash,leaderboard_id,created_by_user_id,created_at,expires_at) "
        + "values (?,?,?,?,?)", crypto.hash("share", reference), boardId, userId, ts(now), ts(now.plus(Duration.ofHours(24))));
    return new ShareResult(reference, "Daily Tracker — " + name, now.plus(Duration.ofHours(24)));
  }

  @Transactional(noRollbackFor = ApiException.class)
  SharePreview previewShare(UUID userId, String reference) {
    requireEnabled();
    rate("preview:" + userId, 60, Duration.ofHours(1));
    Share share = share(reference);
    return new SharePreview(share.boardId(), share.name());
  }

  @Transactional(noRollbackFor = ApiException.class)
  UUID joinShare(UUID userId, String reference) {
    requireEnabled();
    rate("join:" + userId, 20, Duration.ofHours(1));
    return invites.redeemBoard(share(reference).boardId(), userId);
  }

  @Transactional(noRollbackFor = ApiException.class)
  GroupResult resolveGroup(UUID userId, String miniToken, String encryptedData, String iv) {
    requireGroup(); requireMini(miniToken);
    rate("group:" + userId, 30, Duration.ofHours(1));
    if (encryptedData == null || encryptedData.length() > 6000 || iv == null || iv.length() > 100)
      throw new ApiException(400, "WECHAT_GROUP_PROOF_INVALID", "Use the invitation instead.");
    String groupId = crypto.groupId(sessionKey(miniToken), encryptedData, iv, Instant.now());
    String groupKey = crypto.hash("group", groupId);
    saveGroupClaim(miniToken, groupKey);
    return currentGroup(userId, miniToken);
  }

  @Transactional(noRollbackFor = ApiException.class)
  ChatEvidence resolveChatEvidence(UUID userId, String miniToken, String encryptedData, String iv) {
    if (!chatToolAvailable()) throw new ApiException(503, "WECHAT_CHAT_TOOL_DISABLED", "Use a regular group card.");
    requireMini(miniToken);
    rate("chat-context:" + userId, 30, Duration.ofHours(1));
    if (encryptedData == null || encryptedData.length() > 6000 || iv == null || iv.length() > 100)
      throw new ApiException(400, "WECHAT_GROUP_PROOF_INVALID", "Could not verify this chat.");
    WeChatCrypto.GroupEvidence proof = crypto.groupEvidence(sessionKey(miniToken), encryptedData, iv, Instant.now());
    if (proof.chatType() != 3 || proof.groupOpenid().isBlank())
      throw new ApiException(400, "WECHAT_GROUP_REQUIRED", "Open this tool from a regular WeChat group.");
    String groupKey = crypto.hash("group", proof.groupId());
    saveGroupClaim(miniToken, groupKey);
    return new ChatEvidence(groupKey, crypto.protect(proof.groupOpenid()), groupResult(userId, miniToken));
  }

  private String sessionKey(String miniToken) {
    List<String> ciphers = jdbc.query("select session_key_cipher from wechat_sessions where token_hash=? and key_expires_at>?",
        (rs, row) -> rs.getString(1), auth.tokenHash(miniToken), ts(Instant.now()));
    if (ciphers.isEmpty()) throw new ApiException(401, "WECHAT_SESSION_EXPIRED", "Sign in with WeChat again.");
    return crypto.reveal(ciphers.getFirst());
  }

  private void saveGroupClaim(String miniToken, String groupKey) {
    jdbc.update("update wechat_sessions set group_claim_key=?,group_claim_expires_at=?,group_claim_consumed_at=null "
        + "where token_hash=?", groupKey, ts(Instant.now().plus(Duration.ofMinutes(5))), auth.tokenHash(miniToken));
  }

  GroupResult currentGroup(UUID userId, String miniToken) {
    if (!groupAvailable() && !chatToolAvailable())
      throw new ApiException(503, "WECHAT_GROUP_CONTEXT_DISABLED", "Use the invitation to join.");
    requireMini(miniToken);
    return groupResult(userId, miniToken);
  }

  private GroupResult groupResult(UUID userId, String miniToken) {
    String groupKey = claim(miniToken, false);
    List<GroupLinkView> links = jdbc.query("select id,leaderboard_id,linked_at from wechat_group_links "
        + "where app_id=? and group_key=? and disconnected_at is null", (rs, row) ->
        new GroupLinkView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getTimestamp(3).toInstant()), appId, groupKey);
    if (links.isEmpty()) return new GroupResult(true, false, null, null, null);
    GroupLinkView link = links.getFirst();
    boolean member = jdbc.queryForObject("select count(*) from leaderboard_members where leaderboard_id=? and user_id=?",
        Integer.class, link.leaderboardId(), userId) > 0;
    String name = member ? jdbc.queryForObject("select name from leaderboards where id=?", String.class, link.leaderboardId()) : null;
    return new GroupResult(true, true, member ? link.leaderboardId() : null, name, link.id());
  }

  @Transactional(noRollbackFor = ApiException.class)
  GroupLinkView linkGroup(UUID userId, String miniToken, UUID boardId, boolean confirmed) {
    if (!groupAvailable() && !chatToolAvailable())
      throw new ApiException(503, "WECHAT_GROUP_CONTEXT_DISABLED", "Use a regular group card.");
    requireMini(miniToken);
    if (!confirmed) throw new ApiException(400, "CONFIRMATION_REQUIRED", "Confirm the group association first.");
    rate("link-group:" + userId, 10, Duration.ofHours(1));
    invites.shareableBoardName(boardId, userId);
    String groupKey = claim(miniToken, true);
    advisoryLock(groupKey);
    List<GroupLinkView> existing = jdbc.query("select id,leaderboard_id,linked_at from wechat_group_links "
        + "where app_id=? and group_key=? and disconnected_at is null", (rs, row) ->
        new GroupLinkView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getTimestamp(3).toInstant()), appId, groupKey);
    if (!existing.isEmpty()) {
      if (!existing.getFirst().leaderboardId().equals(boardId))
        throw new ApiException(409, "WECHAT_GROUP_ALREADY_LINKED", "This group is already linked to another leaderboard.");
      return existing.getFirst();
    }
    Instant now = Instant.now();
    UUID id = UUID.randomUUID();
    jdbc.update("insert into wechat_group_links(id,app_id,group_key,leaderboard_id,linked_by_user_id,linked_at) "
        + "values (?,?,?,?,?,?)", id, appId, groupKey, boardId, userId, ts(now));
    jdbc.update("update wechat_sessions set group_claim_consumed_at=? where token_hash=?", ts(now), auth.tokenHash(miniToken));
    audit("GROUP_LINKED", userId, boardId);
    log.info("WeChat group mapping linked");
    return new GroupLinkView(id, boardId, now);
  }

  List<GroupLinkView> boardGroups(UUID userId, UUID boardId) {
    requireEnabled(); invites.shareableBoardName(boardId, userId);
    return jdbc.query("select id,leaderboard_id,linked_at from wechat_group_links where leaderboard_id=? "
        + "and disconnected_at is null order by linked_at", (rs, row) ->
        new GroupLinkView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getTimestamp(3).toInstant()), boardId);
  }

  @Transactional
  void disconnectGroup(UUID userId, UUID linkId) {
    requireEnabled();
    List<UUID> boards = jdbc.query("select leaderboard_id from wechat_group_links where id=? and disconnected_at is null",
        (rs, row) -> rs.getObject(1, UUID.class), linkId);
    if (boards.isEmpty()) throw new ApiException(404, "WECHAT_GROUP_NOT_FOUND", "That group link is unavailable.");
    UUID boardId = boards.getFirst();
    invites.shareableBoardName(boardId, userId);
    jdbc.update("update wechat_group_links set disconnected_at=? where id=? and disconnected_at is null", ts(Instant.now()), linkId);
    audit("GROUP_DISCONNECTED", userId, boardId);
    log.info("WeChat group mapping disconnected");
  }

  @Scheduled(cron = "0 23 3 * * *")
  void cleanup() {
    Instant now = Instant.now();
    jdbc.update("delete from wechat_link_codes where expires_at<?", ts(now.minus(Duration.ofDays(1))));
    jdbc.update("delete from wechat_share_refs where expires_at<?", ts(now.minus(Duration.ofDays(1))));
    jdbc.update("delete from wechat_rate_events where created_at<?", ts(now.minus(Duration.ofDays(1))));
  }

  private Identity identityForOpenid(String hash) {
    List<Identity> rows = jdbc.query("select id,user_id,provisional from wechat_identities where app_id=? "
        + "and openid_hash=? and unlinked_at is null", (rs, row) ->
        new Identity(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getBoolean(3)), appId, hash);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private Identity identityForUser(UUID userId) {
    List<Identity> rows = jdbc.query("select id,user_id,provisional from wechat_identities where app_id=? "
        + "and user_id=? and unlinked_at is null", (rs, row) ->
        new Identity(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getBoolean(3)), appId, userId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private Share share(String reference) {
    if (!WeChatCrypto.validToken(reference)) throw invalidShare();
    List<Share> rows = jdbc.query("select s.leaderboard_id,b.name from wechat_share_refs s "
        + "join leaderboards b on b.id=s.leaderboard_id where s.token_hash=? and s.expires_at>?",
        (rs, row) -> new Share(rs.getObject(1, UUID.class), rs.getString(2)),
        crypto.hash("share", reference), ts(Instant.now()));
    if (rows.isEmpty()) throw invalidShare();
    return rows.getFirst();
  }

  private String claim(String miniToken, boolean unconsumed) {
    List<String> claims = jdbc.query("select group_claim_key from wechat_sessions where token_hash=? "
        + "and group_claim_expires_at>? and group_claim_key is not null"
        + (unconsumed ? " and group_claim_consumed_at is null" : ""),
        (rs, row) -> rs.getString(1), auth.tokenHash(miniToken), ts(Instant.now()));
    if (claims.isEmpty()) throw new ApiException(410, "WECHAT_GROUP_CONTEXT_EXPIRED", "Open the card from the group again.");
    return claims.getFirst();
  }

  private void rate(String subject, int limit, Duration window) {
    String hash = crypto.hash("rate", subject);
    Instant now = Instant.now();
    int count = jdbc.queryForObject("select count(*) from wechat_rate_events where subject_hash=? and created_at>?",
        Integer.class, hash, ts(now.minus(window)));
    if (count >= limit) throw new ApiException(429, "WECHAT_RATE_LIMITED", "Wait a little before trying again.");
    jdbc.update("insert into wechat_rate_events(id,subject_hash,created_at) values (?,?,?)", UUID.randomUUID(), hash, ts(now));
  }

  private void audit(String action, UUID userId, UUID boardId) {
    jdbc.update("insert into wechat_audit_events(id,action,actor_user_id,leaderboard_id,created_at) values (?,?,?,?,?)",
        UUID.randomUUID(), action, userId, boardId, ts(Instant.now()));
  }

  private void advisoryLock(String key) {
    jdbc.execute("select pg_advisory_xact_lock(hashtext(?))", (PreparedStatementCallback<Void>) statement -> {
      statement.setString(1, key);
      statement.execute();
      return null;
    });
  }

  private void requireEnabled() {
    if (!available()) throw new ApiException(503, "WECHAT_DISABLED", "WeChat sign-in is not available yet.");
  }
  private void requireGroup() {
    requireEnabled();
    if (!groupEnabled) throw new ApiException(503, "WECHAT_GROUP_CONTEXT_DISABLED", "Use the invitation to join.");
  }
  private void requireMini(String token) {
    if (!isMiniToken(token)) throw new ApiException(401, "WECHAT_SESSION_REQUIRED", "Sign in with WeChat again.");
  }
  private ApiException invalidCode() { return new ApiException(410, "WECHAT_LINK_CODE_INVALID", "This link code is invalid or expired."); }
  private ApiException invalidShare() { return new ApiException(410, "WECHAT_SHARE_EXPIRED", "This card expired. Ask the owner to share it again."); }
  private static Timestamp ts(Instant instant) { return Timestamp.from(instant); }
}
