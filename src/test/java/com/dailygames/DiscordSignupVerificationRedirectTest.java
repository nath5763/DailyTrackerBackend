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
    var controller = new AuthController(auth, signup, "https://dailytracker.cc", true);
    var response = controller.verify("email-token", intent);
    assertEquals(302, response.getStatusCode().value());
    assertEquals("https://dailytracker.cc/onboarding?next=%2Fdiscord%2Fsignup%3Fintent%3D" + intent,
        response.getHeaders().getLocation().toString());
    assertTrue(response.getHeaders().getFirst("Set-Cookie").contains("daily_tracker_session="));
  }
}
