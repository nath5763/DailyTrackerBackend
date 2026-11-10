package com.dailygames;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LoldleSubmissionTest {
  private static final String SHARE = "I've completed all the modes of #LoLdle today:\n"
      + "❓ Classic: 8\n💬 Quote: 1\n🔥 Ability: 1 ✓\n😀 Emoji: 1\n🎨 Splash: 2 ✓\nhttps://loldle.net";
  private final LocalDate date = LocalDate.of(2026, 6, 1);

  @Test void previewsFiveAndRepeatsIdentically() {
    Fixture fixture = new Fixture();
    var request = new ApiModels.LoldleSubmissionRequest(SHARE, date);
    var preview = fixture.service.previewLoldle(fixture.user, request);
    assertEquals(5, preview.size());
    assertEquals("loldle-classic", preview.getFirst().game());
    List<UUID> first = fixture.service.submitLoldle(fixture.user, request);
    assertEquals(5, first.size());
    assertEquals(first, fixture.service.submitLoldle(fixture.user, request));
    verify(fixture.submissions, times(5)).saveAndFlush(any());
  }

  @Test void conflictOnOneModePreventsEveryInsert() {
    Fixture fixture = new Fixture();
    Game quote = fixture.games.get("loldle-quote");
    ParsedSubmission old = LoldleParser.parse(SHARE.replace("💬 Quote: 1", "💬 Quote: 2"), date).get("loldle-quote");
    fixture.stored.put(quote.id, new Submission(fixture.user.id(), quote.id, old, "different share", Instant.now()));
    var request = new ApiModels.LoldleSubmissionRequest(SHARE, date);
    assertEquals("DUPLICATE_SUBMISSION", assertThrows(ApiException.class,
        () -> fixture.service.submitLoldle(fixture.user, request)).code);
    verify(fixture.submissions, never()).saveAndFlush(any());
  }

  @Test void everyModeRanksUnassistedBeforeFewerAssistedGuesses() {
    Fixture fixture = new Fixture();
    ScoreQuantizerRegistry quantizers = new ScoreQuantizerRegistry();
    for (String mode : LoldleParser.MODES) {
      String slug = "loldle-" + mode;
      Game game = fixture.games.get(slug);
      ParsedSubmission clear = new ParsedSubmission(date.toString(), null, date, BigDecimal.valueOf(5),
          Map.of("attemptsUsed", 5, "assistanceStatus", "unassisted", "hints", 0, "completionStatus", "WIN"), 1);
      ParsedSubmission assisted = new ParsedSubmission(date.toString(), null, date, BigDecimal.ONE,
          Map.of("attemptsUsed", 1, "assistanceStatus", "assisted", "hints", 1, "completionStatus", "WIN"), 1);
      Submission clearScore = new Submission(fixture.userId, game.id, clear, SHARE, Instant.now());
      Submission assistedScore = new Submission(UUID.randomUUID(), game.id, assisted, SHARE, Instant.now());
      NormalizedScore clearRank = quantizers.quantize(slug, clear, "lower");
      NormalizedScore assistedRank = quantizers.quantize(slug, assisted, "lower");
      assertTrue(fixture.service.compareContestScores(clearScore, assistedScore, clearRank, assistedRank) < 0, slug);
      assertFalse(fixture.service.sameContestRank(clearScore, assistedScore, clearRank, assistedRank), slug);
    }
  }

  private static final class Fixture {
    final UUID userId = UUID.randomUUID();
    final CurrentUser user = new CurrentUser(userId, "");
    final Map<String, Game> games = new LinkedHashMap<>();
    final Map<UUID, Submission> stored = new ConcurrentHashMap<>();
    final SubmissionRepository submissions = mock(SubmissionRepository.class);
    final DailyGamesService service;

    Fixture() {
      ProfileRepository profiles = mock(ProfileRepository.class);
      GameRepository gameRepository = mock(GameRepository.class);
      Profile profile = new Profile(userId, "Player", "#dfff4f", Instant.now());
      when(profiles.findById(userId)).thenReturn(Optional.of(profile));
      when(profiles.findByIdForUpdate(userId)).thenReturn(Optional.of(profile));
      for (String mode : LoldleParser.MODES) {
        String slug = "loldle-" + mode;
        Game game = new Game(); game.id = UUID.randomUUID(); game.slug = slug; game.name = slug;
        game.sourceUrl = "https://loldle.net/"; game.releaseTimezone = "UTC";
        game.rankingDirection = "lower"; game.active = true;
        games.put(slug, game);
        when(gameRepository.findBySlugAndActiveTrue(slug)).thenReturn(Optional.of(game));
      }
      when(submissions.findByUserIdAndGameIdAndPuzzleKey(eq(userId), any(), anyString()))
          .thenAnswer(call -> Optional.ofNullable(stored.get(call.getArgument(1))));
      when(submissions.saveAndFlush(any())).thenAnswer(call -> {
        Submission value = call.getArgument(0);
        stored.put(value.gameId, value);
        return value;
      });
      service = new DailyGamesService(mock(UserRepository.class), profiles, gameRepository,
          mock(LeaderboardRepository.class), mock(MembershipRepository.class), submissions,
          new GameAdapterRegistry(List.of(new LoldleClassicAdapter(), new LoldleQuoteAdapter(),
              new LoldleAbilityAdapter(), new LoldleEmojiAdapter(), new LoldleSplashAdapter())),
          mock(BillingService.class));
    }
  }
}
