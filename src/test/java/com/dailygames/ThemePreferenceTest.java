package com.dailygames;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static com.dailygames.ApiModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ThemePreferenceTest {
  @Test void updatesOnlyTheCurrentProfileAndIncludesPreferenceInMe() {
    UserRepository users = mock(UserRepository.class);
    ProfileRepository profiles = mock(ProfileRepository.class);
    DailyGamesService service = new DailyGamesService(users, profiles, mock(GameRepository.class),
        mock(LeaderboardRepository.class), mock(MembershipRepository.class), mock(SubmissionRepository.class),
        mock(GameAdapterRegistry.class), mock(BillingService.class));
    UserAccount first = new UserAccount("first@example.com", Instant.now());
    UserAccount second = new UserAccount("second@example.com", Instant.now());
    Profile firstProfile = new Profile(first.id, "First", "#dfff4f", Instant.now());
    Profile secondProfile = new Profile(second.id, "Second", "#dfff4f", Instant.now());
    when(users.findById(first.id)).thenReturn(Optional.of(first));
    when(users.findById(second.id)).thenReturn(Optional.of(second));
    when(profiles.findById(first.id)).thenReturn(Optional.of(firstProfile));
    when(profiles.findById(second.id)).thenReturn(Optional.of(secondProfile));
    CurrentUser firstUser = new CurrentUser(first.id, first.normalizedEmail);
    CurrentUser secondUser = new CurrentUser(second.id, second.normalizedEmail);

    assertFalse(service.me(firstUser).darkMode());
    assertTrue(service.updateTheme(firstUser, new ThemeRequest(true)).darkMode());
    assertTrue(service.me(firstUser).darkMode());
    assertFalse(service.me(secondUser).darkMode());
  }
}
