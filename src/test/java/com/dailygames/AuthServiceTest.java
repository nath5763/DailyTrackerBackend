package com.dailygames;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthServiceTest {
  private final UserRepository users = mock(UserRepository.class);
  private final ProfileRepository profiles = mock(ProfileRepository.class);
  private final AuthActionTokenRepository tokens = mock(AuthActionTokenRepository.class);
  private final AuthLoginAttemptRepository loginAttempts = mock(AuthLoginAttemptRepository.class);
  private final AuthSessionRepository sessions = mock(AuthSessionRepository.class);
  private final BrevoEmailClient email = mock(BrevoEmailClient.class);
  private final PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
  private AuthService auth;

  @BeforeEach
  void setUp() {
    auth = new AuthService(users, profiles, tokens, loginAttempts, sessions, email, encoder,
        "https://example.com", "test-pepper", 24, 30, 30);
  }

  @Test
  void registersUnverifiedAccountWithEncodedPasswordAndVerificationEmail() {
    when(users.findByNormalizedEmail("friend@example.com")).thenReturn(Optional.empty());
    when(users.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    auth.register(" Friend@Example.com ", "a sufficiently long password", "127.0.0.1");

    ArgumentCaptor<UserAccount> accountCaptor = ArgumentCaptor.forClass(UserAccount.class);
    verify(users).save(accountCaptor.capture());
    UserAccount account = accountCaptor.getValue();
    assertEquals("friend@example.com", account.normalizedEmail);
    assertNull(account.emailVerifiedAt);
    assertTrue(encoder.matches("a sufficiently long password", account.passwordHash));
    verify(email).sendVerification(eq("friend@example.com"), contains("/api/v1/auth/verification/consume?token="), eq(24));
    verify(tokens).save(any(AuthActionToken.class));
  }

  @Test
  void verificationEmailCarriesSignupIntentAcrossBrowsers() {
    String intent = "a".repeat(43);
    when(users.findByNormalizedEmail("friend@example.com")).thenReturn(Optional.empty());
    when(users.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    auth.register("friend@example.com", "a sufficiently long password", "127.0.0.1", intent);
    verify(email).sendVerification(eq("friend@example.com"),
        contains("&signupIntent=" + intent), eq(24));
  }

  @Test
  void registeringMigratedAccountSendsPasswordSetupLinkWithoutChangingItsPassword() {
    UserAccount account = new UserAccount("friend@example.com", Instant.now());
    when(users.findByNormalizedEmail("friend@example.com")).thenReturn(Optional.of(account));

    auth.register(" Friend@Example.com ", "a sufficiently long password", "127.0.0.1");

    assertNull(account.passwordHash);
    verify(users, never()).save(any());
    verify(email).sendPasswordReset(eq("friend@example.com"),
        contains("/auth/reset-password?token="), eq(30));
    verify(tokens).save(argThat(token -> token.purpose == AuthTokenPurpose.PASSWORD_RESET));
    verify(email, never()).sendVerification(anyString(), anyString(), anyInt());
  }

  @Test
  void forgotPasswordSendsResetLinkForMigratedAccount() {
    UserAccount account = new UserAccount("friend@example.com", Instant.now());
    when(users.findByNormalizedEmail("friend@example.com")).thenReturn(Optional.of(account));

    auth.forgotPassword(" Friend@Example.com ", "127.0.0.1");

    verify(email).sendPasswordReset(eq("friend@example.com"),
        contains("/auth/reset-password?token="), eq(30));
    verify(tokens).save(argThat(token -> token.purpose == AuthTokenPurpose.PASSWORD_RESET));
  }

  @Test
  void logsInVerifiedAccountAndCreatesOpaqueSession() {
    UserAccount account = new UserAccount("friend@example.com", Instant.now());
    account.passwordHash = encoder.encode("a sufficiently long password");
    when(users.findByNormalizedEmail("friend@example.com")).thenReturn(Optional.of(account));
    when(profiles.existsById(account.id)).thenReturn(true);

    AuthService.LoginResult result = auth.login("friend@example.com", "a sufficiently long password", "127.0.0.1");

    assertTrue(result.onboarded());
    assertNotNull(result.sessionToken());
    verify(users).save(account);
    verify(sessions).save(any(AuthSession.class));
    verify(loginAttempts).deleteByNormalizedEmailHash(anyString());
  }

  @Test
  void migratedAccountIsDirectedToPasswordSetup() {
    UserAccount account = new UserAccount("friend@example.com", Instant.now());
    when(users.findByNormalizedEmail("friend@example.com")).thenReturn(Optional.of(account));

    ApiException exception = assertThrows(ApiException.class,
        () -> auth.login("friend@example.com", "a sufficiently long password", "127.0.0.1"));

    assertEquals(403, exception.status);
    assertEquals("PASSWORD_SETUP_REQUIRED", exception.code);
  }

  @Test
  void resetSetsPasswordRevokesSessionsAndSignsUserIn() {
    Instant now = Instant.now();
    UserAccount account = new UserAccount("friend@example.com", now);
    AuthActionToken token = new AuthActionToken(account.id, AuthTokenPurpose.PASSWORD_RESET,
        "stored-hash", "ip-hash", now.minusSeconds(60), now.plusSeconds(600));
    when(tokens.findByTokenHashAndPurposeAndConsumedAtIsNullAndExpiresAtAfter(
        anyString(), eq(AuthTokenPurpose.PASSWORD_RESET), any(Instant.class))).thenReturn(Optional.of(token));
    when(users.findById(account.id)).thenReturn(Optional.of(account));
    when(profiles.existsById(account.id)).thenReturn(true);

    AuthService.LoginResult result = auth.resetPassword("safe-token", "a different long password");

    assertTrue(result.onboarded());
    assertTrue(encoder.matches("a different long password", account.passwordHash));
    assertNotNull(token.consumedAt);
    verify(sessions).deleteByUserId(account.id);
    verify(sessions).save(any(AuthSession.class));
  }

  @Test
  void rejectsShortPasswords() {
    ApiException exception = assertThrows(ApiException.class,
        () -> auth.register("friend@example.com", "too short", "127.0.0.1"));
    assertEquals("INVALID_PASSWORD", exception.code);
  }

  @Test
  void oversizedLoginPasswordIsRejectedAsInvalidCredentials() {
    UserAccount account = new UserAccount("friend@example.com", Instant.now());
    account.passwordHash = encoder.encode("a sufficiently long password");
    when(users.findByNormalizedEmail("friend@example.com")).thenReturn(Optional.of(account));

    ApiException exception = assertThrows(ApiException.class,
        () -> auth.login("friend@example.com", "x".repeat(73), "127.0.0.1"));

    assertEquals(401, exception.status);
    assertEquals("INVALID_CREDENTIALS", exception.code);
    verify(loginAttempts).save(any(AuthLoginAttempt.class));
  }
}
