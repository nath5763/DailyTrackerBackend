package com.dailygames;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

@Service
class AuthService {
  static final String COOKIE_NAME = "daily_tracker_session";
  private final UserRepository users;
  private final ProfileRepository profiles;
  private final MagicLinkRepository links;
  private final AuthSessionRepository sessions;
  private final JavaMailSender mailSender;
  private final SecureRandom random = new SecureRandom();
  private final String publicBaseUrl;
  private final String mailFrom;
  private final String pepper;
  private final int linkMinutes;
  private final int sessionDays;

  AuthService(UserRepository users, ProfileRepository profiles, MagicLinkRepository links, AuthSessionRepository sessions,
              JavaMailSender mailSender, @Value("${app.public-base-url}") String publicBaseUrl,
              @Value("${app.mail-from}") String mailFrom, @Value("${app.token-pepper}") String pepper,
              @Value("${app.magic-link-minutes}") int linkMinutes, @Value("${app.session-days}") int sessionDays) {
    this.users = users; this.profiles = profiles; this.links = links; this.sessions = sessions; this.mailSender = mailSender;
    this.publicBaseUrl = publicBaseUrl.replaceAll("/$", ""); this.mailFrom = mailFrom; this.pepper = pepper;
    this.linkMinutes = linkMinutes; this.sessionDays = sessionDays;
  }

  @Transactional
  void requestMagicLink(String rawEmail, String remoteIp) {
    String email = normalizeEmail(rawEmail);
    Instant now = Instant.now();
    String ipHash = hash(pepper + ":ip:" + Objects.toString(remoteIp, "unknown"));
    Optional<MagicLink> latest = links.findTopByNormalizedEmailOrderByCreatedAtDesc(email);
    if (latest.isPresent() && latest.get().createdAt.isAfter(now.minusSeconds(60))) rateLimited();
    if (links.countByNormalizedEmailAndCreatedAtAfter(email, now.minus(Duration.ofHours(1))) >= 5) rateLimited();
    if (links.countByRequestIpHashAndCreatedAtAfter(ipHash, now.minus(Duration.ofHours(1))) >= 20) rateLimited();

    links.consumeOutstanding(email, now);
    String token = randomToken();
    links.save(new MagicLink(email, hash(token), ipHash, now, now.plus(Duration.ofMinutes(linkMinutes))));
    sendLink(email, token);
  }

  @Transactional
  LoginResult consume(String token) {
    if (token == null || token.isBlank()) throw invalidLink();
    Instant now = Instant.now();
    MagicLink link = links.findByTokenHash(hash(token)).orElseThrow(this::invalidLink);
    if (link.consumedAt != null || !link.expiresAt.isAfter(now)) throw invalidLink();
    link.consumedAt = now;
    UserAccount user = users.findByNormalizedEmail(link.normalizedEmail).orElseGet(() -> users.save(new UserAccount(link.normalizedEmail, now)));
    user.lastLoginAt = now;
    String sessionToken = randomToken();
    sessions.save(new AuthSession(user.id, hash(sessionToken), now, now.plus(Duration.ofDays(sessionDays))));
    return new LoginResult(sessionToken, profiles.existsById(user.id));
  }

  Optional<CurrentUser> authenticate(String rawToken) {
    if (rawToken == null || rawToken.isBlank()) return Optional.empty();
    return sessions.findByTokenHashAndExpiresAtAfter(hash(rawToken), Instant.now())
        .flatMap(session -> users.findById(session.userId))
        .map(user -> new CurrentUser(user.id, user.normalizedEmail));
  }

  @Transactional
  void logout(String rawToken) { if (rawToken != null && !rawToken.isBlank()) sessions.deleteByTokenHash(hash(rawToken)); }

  @Scheduled(cron = "0 17 3 * * *")
  @Transactional
  void cleanup() {
    Instant now = Instant.now();
    sessions.deleteByExpiresAtBefore(now);
    links.deleteByExpiresAtBefore(now.minus(Duration.ofDays(1)));
  }

  int sessionSeconds() { return Math.toIntExact(Duration.ofDays(sessionDays).toSeconds()); }

  private void sendLink(String email, String token) {
    String url = publicBaseUrl + "/api/v1/auth/magic-link/consume?token=" + token;
    try {
      MimeMessage message = mailSender.createMimeMessage();
      message.setFrom(mailFrom);
      message.setRecipients(MimeMessage.RecipientType.TO, email);
      message.setSubject("Your Daily Club sign-in link", StandardCharsets.UTF_8.name());
      message.setContent("<p>Use this link to sign in to Daily Club:</p><p><a href=\"" + url + "\">Sign in to Daily Club</a></p><p>This link expires in " + linkMinutes + " minutes and can be used once.</p>", "text/html; charset=UTF-8");
      mailSender.send(message);
    } catch (MailException | jakarta.mail.MessagingException exception) {
      throw new ApiException(503, "EMAIL_UNAVAILABLE", "Sign-in email is temporarily unavailable. Please try again.");
    }
  }

  private String normalizeEmail(String email) { return email.trim().toLowerCase(Locale.ROOT); }
  private void rateLimited() { throw new ApiException(429, "RATE_LIMITED", "Please wait before requesting another sign-in link."); }
  private ApiException invalidLink() { return new ApiException(401, "INVALID_OR_EXPIRED_LINK", "This sign-in link is invalid or has expired."); }
  private String randomToken() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
  private String hash(String value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
  }

  record LoginResult(String sessionToken, boolean onboarded) {}
}

record CurrentUser(UUID id, String email) {}
