package com.dailygames;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DiscordSignupVerificationRedirectTest {
  @Test void verifiedAccountContinuesToSignupAfterProfileSetup() {
    AuthService auth = mock(AuthService.class);
    DiscordSignupService signup = mock(DiscordSignupService.class);
    String intent = "a".repeat(43);
    when(auth.verifyEmail("email-token")).thenReturn(new AuthService.LoginResult("session-token", false));
    when(signup.valid(intent)).thenReturn(true);
    var controller = new AuthController(auth, signup, mock(LeaderboardInviteService.class), "https://dailytracker.cc", true);
    var response = controller.verify("email-token", intent, null);
    assertEquals(302, response.getStatusCode().value());
    assertEquals("https://dailytracker.cc/onboarding?next=%2Fdiscord%2Fsignup%3Fintent%3D" + intent,
        response.getHeaders().getLocation().toString());
    assertTrue(response.getHeaders().getFirst("Set-Cookie").contains("daily_tracker_session="));
  }

  @Test void verifiedAccountContinuesToInviteInAnotherBrowser() {
    AuthService auth = mock(AuthService.class);
    String invite = "12345678-1234-1234-1234-123456789abc." + "a".repeat(43);
    when(auth.verifyEmail("email-token")).thenReturn(new AuthService.LoginResult("session-token", false));
    var controller = new AuthController(auth, mock(DiscordSignupService.class),
        mock(LeaderboardInviteService.class), "https://dailytracker.cc", true);
    var response = controller.verify("email-token", null, invite);
    assertEquals("https://dailytracker.cc/onboarding?next=%2Fjoin%3Finvite%3D" + invite,
        response.getHeaders().getLocation().toString());
  }
}
