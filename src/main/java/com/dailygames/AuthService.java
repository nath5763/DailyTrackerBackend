package com.dailygames;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;

@Service
class AuthService {
  static final String COOKIE_NAME = "daily_tracker_session";
  private static final int MIN_PASSWORD_BYTES = 15;
  private static final int MAX_PASSWORD_BYTES = 72;
  private final UserRepository users;
  private final ProfileRepository profiles;
  private final AuthActionTokenRepository tokens;
  private final AuthLoginAttemptRepository loginAttempts;
  private final AuthSessionRepository sessions;
  private final EmailSender emailClient;
  private final PasswordEncoder passwordEncoder;
  private final SecureRandom random = new SecureRandom();
  private final String publicBaseUrl;
  private final String pepper;
  private final int verificationHours;
  private final int resetMinutes;
  private final int sessionDays;
  private final int tokenRequestsPerIpPerHour;
  private final String dummyPasswordHash;

  AuthService(UserRepository users, ProfileRepository profiles, AuthActionTokenRepository tokens,
              AuthLoginAttemptRepository loginAttempts, AuthSessionRepository sessions,
              EmailSender emailClient, PasswordEncoder passwordEncoder,
              @Value("${app.public-base-url}") String publicBaseUrl,
              @Value("${app.token-pepper}") String pepper,
              @Value("${app.verification-hours}") int verificationHours,
              @Value("${app.password-reset-minutes}") int resetMinutes,
              @Value("${app.session-days}") int sessionDays,
              @Value("${app.token-requests-per-ip-per-hour}") int tokenRequestsPerIpPerHour) {
    this.users = users; this.profiles = profiles; this.tokens = tokens; this.loginAttempts = loginAttempts;
    this.sessions = sessions; this.emailClient = emailClient; this.passwordEncoder = passwordEncoder;
    this.publicBaseUrl = publicBaseUrl.replaceAll("/$", ""); this.pepper = pepper;
    this.verificationHours = verificationHours; this.resetMinutes = resetMinutes; this.sessionDays = sessionDays;
    if (tokenRequestsPerIpPerHour < 1) throw new IllegalArgumentException("Token request limit must be positive.");
    this.tokenRequestsPerIpPerHour = tokenRequestsPerIpPerHour;
    this.dummyPasswordHash = passwordEncoder.encode(randomToken());
  }

  @Transactional
  void register(String rawEmail, String password, String remoteIp) {
    register(rawEmail, password, remoteIp, null);
  }

  @Transactional
  void register(String rawEmail, String password, String remoteIp, String signupIntent) {
    register(rawEmail, password, remoteIp, signupIntent, null);
  }

  @Transactional
  void register(String rawEmail, String password, String remoteIp, String signupIntent, String joinInvite) {
    String email = normalizeEmail(rawEmail);
    validatePassword(password);
    Instant now = Instant.now();
    UserAccount user = users.findByNormalizedEmail(email).orElse(null);
    if (user != null && user.emailVerifiedAt != null) {
      if (user.passwordHash == null) sendPasswordReset(user, email, remoteIp);
      return;
    }
    if (user == null) user = users.save(new UserAccount(email, passwordEncoder.encode(password), now));
    issueToken(user, AuthTokenPurpose.EMAIL_VERIFICATION, remoteIp, Duration.ofHours(verificationHours), link ->
        emailClient.sendVerification(email, publicBaseUrl + "/api/v1/auth/verification/consume?token=" + link
            + (signupIntent == null ? "" : "&signupIntent=" + signupIntent)
            + (joinInvite == null ? "" : "&joinInvite=" + joinInvite),
            verificationHours));
  }

  @Transactional(noRollbackFor = ApiException.class)
  LoginResult login(String rawEmail, String password, String remoteIp) {
    String email = normalizeEmail(rawEmail);
    String emailHash = identifierHash("email", email);
    String ipHash = identifierHash("ip", Objects.toString(remoteIp, "unknown"));
    boolean encodablePassword = password != null && password.getBytes(StandardCharsets.UTF_8).length <= MAX_PASSWORD_BYTES;
    String passwordCandidate = encodablePassword ? password : "invalid password";
    Instant now = Instant.now();
    enforceLoginRateLimit(emailHash, ipHash, now);
    UserAccount user = users.findByNormalizedEmail(email).orElse(null);
    if (user == null) {
      passwordEncoder.matches(passwordCandidate, dummyPasswordHash);
      recordFailedLogin(emailHash, ipHash, now);
      throw invalidCredentials();
    }
    if (user.passwordHash == null) {
      passwordEncoder.matches(passwordCandidate, dummyPasswordHash);
      throw new ApiException(403, "PASSWORD_SETUP_REQUIRED", "Set your password using the password reset link.");
    }
    if (!encodablePassword || !passwordEncoder.matches(passwordCandidate, user.passwordHash)) {
      recordFailedLogin(emailHash, ipHash, now);
      throw invalidCredentials();
    }
    if (user.emailVerifiedAt == null) {
      throw new ApiException(403, "EMAIL_NOT_VERIFIED", "Verify your email before signing in.");
    }
    loginAttempts.deleteByNormalizedEmailHash(emailHash);
    user.lastLoginAt = now;
    users.save(user);
    return createSession(user, now);
  }

  @Transactional
  void resendVerification(String rawEmail, String remoteIp) {
    resendVerification(rawEmail, remoteIp, null);
  }

  @Transactional
  void resendVerification(String rawEmail, String remoteIp, String signupIntent) {
    resendVerification(rawEmail, remoteIp, signupIntent, null);
  }

  @Transactional
  void resendVerification(String rawEmail, String remoteIp, String signupIntent, String joinInvite) {
    String email = normalizeEmail(rawEmail);
    users.findByNormalizedEmail(email).filter(user -> user.emailVerifiedAt == null).ifPresent(user ->
        issueToken(user, AuthTokenPurpose.EMAIL_VERIFICATION, remoteIp, Duration.ofHours(verificationHours), link ->
            emailClient.sendVerification(email, publicBaseUrl + "/api/v1/auth/verification/consume?token=" + link
                + (signupIntent == null ? "" : "&signupIntent=" + signupIntent)
                + (joinInvite == null ? "" : "&joinInvite=" + joinInvite),
                verificationHours)));
  }

  @Transactional
  LoginResult verifyEmail(String rawToken) {
    Instant now = Instant.now();
    AuthActionToken token = requireToken(rawToken, AuthTokenPurpose.EMAIL_VERIFICATION, now);
    UserAccount user = users.findById(token.userId).orElseThrow(this::invalidToken);
    token.consumedAt = now;
    tokens.consumeOutstanding(user.id, AuthTokenPurpose.EMAIL_VERIFICATION, now);
    user.emailVerifiedAt = now;
    user.lastLoginAt = now;
    return createSession(user, now);
  }

  @Transactional
  void forgotPassword(String rawEmail, String remoteIp) {
    String email = normalizeEmail(rawEmail);
    users.findByNormalizedEmail(email).filter(user -> user.emailVerifiedAt != null).ifPresent(user ->
        sendPasswordReset(user, email, remoteIp));
  }

  private void sendPasswordReset(UserAccount user, String email, String remoteIp) {
    issueToken(user, AuthTokenPurpose.PASSWORD_RESET, remoteIp, Duration.ofMinutes(resetMinutes), link ->
        emailClient.sendPasswordReset(email, publicBaseUrl + "/auth/reset-password?token=" + link,
            resetMinutes));
  }

  @Transactional
  LoginResult resetPassword(String rawToken, String password) {
    validatePassword(password);
    Instant now = Instant.now();
    AuthActionToken token = requireToken(rawToken, AuthTokenPurpose.PASSWORD_RESET, now);
    UserAccount user = users.findById(token.userId).orElseThrow(this::invalidToken);
    token.consumedAt = now;
    tokens.consumeOutstanding(user.id, AuthTokenPurpose.PASSWORD_RESET, now);
    user.passwordHash = passwordEncoder.encode(password);
    if (user.emailVerifiedAt == null) user.emailVerifiedAt = now;
    user.lastLoginAt = now;
    sessions.deleteByUserId(user.id);
    return createSession(user, now);
  }

  Optional<CurrentUser> authenticate(String rawToken) {
    if (rawToken == null || rawToken.isBlank()) return Optional.empty();
    return sessions.findByTokenHashAndExpiresAtAfter(hash(rawToken), Instant.now())
        .flatMap(session -> users.findById(session.userId))
        .filter(user -> user.emailVerifiedAt != null)
        .map(user -> new CurrentUser(user.id, user.normalizedEmail));
  }

  @Transactional
  void logout(String rawToken) { if (rawToken != null && !rawToken.isBlank()) sessions.deleteByTokenHash(hash(rawToken)); }

  @Scheduled(cron = "0 17 3 * * *")
  @Transactional
  void cleanup() {
    Instant now = Instant.now();
    sessions.deleteByExpiresAtBefore(now);
    tokens.deleteByExpiresAtBefore(now.minus(Duration.ofDays(1)));
    loginAttempts.deleteByCreatedAtBefore(now.minus(Duration.ofDays(1)));
  }

  int sessionSeconds() { return Math.toIntExact(Duration.ofDays(sessionDays).toSeconds()); }

  private void issueToken(UserAccount user, AuthTokenPurpose purpose, String remoteIp, Duration lifetime,
                          Consumer<String> sender) {
    Instant now = Instant.now();
    String ipHash = identifierHash("ip", Objects.toString(remoteIp, "unknown"));
    if (tokens.countByRequestIpHashAndCreatedAtAfter(ipHash, now.minus(Duration.ofHours(1))) >= tokenRequestsPerIpPerHour) rateLimited();
    if (tokens.countByUserIdAndPurposeAndCreatedAtAfter(user.id, purpose, now.minus(Duration.ofMinutes(1))) >= 1 ||
        tokens.countByUserIdAndPurposeAndCreatedAtAfter(user.id, purpose, now.minus(Duration.ofHours(1))) >= 5) return;
    tokens.consumeOutstanding(user.id, purpose, now);
    String rawToken = randomToken();
    tokens.save(new AuthActionToken(user.id, purpose, hash(rawToken), ipHash, now, now.plus(lifetime)));
    sender.accept(rawToken);
  }

  private AuthActionToken requireToken(String rawToken, AuthTokenPurpose purpose, Instant now) {
    if (rawToken == null || rawToken.isBlank()) throw invalidToken();
    return tokens.findByTokenHashAndPurposeAndConsumedAtIsNullAndExpiresAtAfter(hash(rawToken), purpose, now)
        .orElseThrow(this::invalidToken);
  }

  private LoginResult createSession(UserAccount user, Instant now) {
    String sessionToken = randomToken();
    sessions.save(new AuthSession(user.id, hash(sessionToken), now, now.plus(Duration.ofDays(sessionDays))));
    return new LoginResult(sessionToken, profiles.existsById(user.id));
  }

  private void enforceLoginRateLimit(String emailHash, String ipHash, Instant now) {
    Instant after = now.minus(Duration.ofMinutes(15));
    if (loginAttempts.countByNormalizedEmailHashAndCreatedAtAfter(emailHash, after) >= 5 ||
        loginAttempts.countByRequestIpHashAndCreatedAtAfter(ipHash, after) >= 20) rateLimited();
  }

  private void recordFailedLogin(String emailHash, String ipHash, Instant now) {
    loginAttempts.save(new AuthLoginAttempt(emailHash, ipHash, now));
  }

  private void validatePassword(String password) {
    int bytes = password == null ? 0 : password.getBytes(StandardCharsets.UTF_8).length;
    if (bytes < MIN_PASSWORD_BYTES || bytes > MAX_PASSWORD_BYTES) {
      throw new ApiException(400, "INVALID_PASSWORD", "Use a password between 15 and 72 bytes long.");
    }
  }

  private String normalizeEmail(String email) { return email.trim().toLowerCase(Locale.ROOT); }
  private void rateLimited() { throw new ApiException(429, "RATE_LIMITED", "Please wait before trying again."); }
  private ApiException invalidCredentials() { return new ApiException(401, "INVALID_CREDENTIALS", "Email or password is incorrect."); }
  private ApiException invalidToken() { return new ApiException(400, "INVALID_OR_EXPIRED_TOKEN", "This link is invalid or has expired."); }
  private String randomToken() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
  private String identifierHash(String kind, String value) { return hash(pepper + ":" + kind + ":" + value); }
  private String hash(String value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
  }

  record LoginResult(String sessionToken, boolean onboarded) {}
}

record CurrentUser(UUID id, String email) {}
