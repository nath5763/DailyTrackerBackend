package com.dailygames;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static com.dailygames.ApiModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class DailyGamesIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    properties.add("spring.datasource.username", POSTGRES::getUsername);
    properties.add("spring.datasource.password", POSTGRES::getPassword);
    properties.add("app.brevo.api-key", () -> "integration-test-key");
    properties.add("app.brevo.sender-email", () -> "sender@example.com");
    properties.add("app.stripe.premium-price-id", () -> "price_premium");
  }

  @Autowired DailyGamesService service;
  @Autowired LeaderboardDeletionService deletions;
  @Autowired UserRepository users;
  @Autowired BillingAccountRepository billingAccounts;
  @Autowired LeaderboardRepository boards;
  @Autowired GameRepository games;
  @Autowired SubmissionRepository submissions;
  @Autowired DiscordService discord;
  @Autowired DiscordChannelLeaderboardRepository channels;
  @Autowired DiscordGroupDmLeaderboardRepository groupDms;
  @Autowired DiscordSetupRepository setups;
  @Autowired DiscordSignupIntentRepository signupIntents;
  @Autowired DiscordCommandRegistrationStore registrations;
  @Autowired LeaderboardInviteService invites;
  @Autowired JdbcTemplate jdbc;
  @Autowired LetterBagService letterBag;
  @Autowired SlipOutService slipOut;
  @Autowired SlipOutLevelService slipOutLevels;
  @Autowired TelegramService telegram;
  @MockitoBean TelegramGateway telegramBot;

  @Test void slipOutRetriesKeepOnlyTheVerifiedBestCompletion() throws Exception {
    UserAccount player = users.save(new UserAccount("slipout-player@example.com", Instant.now()));
    service.onboard(new CurrentUser(player.id, player.normalizedEmail),
        new OnboardingRequest("SlipOut Player", "#dfff4f"));
    var puzzle = slipOut.today(player.id).puzzle();
    var game = games.findBySlugAndActiveTrue("slipout").orElseThrow();
    assertNull(slipOut.today(player.id).best());

    var failedStart = slipOut.start(player.id);
    assertEquals("INVALID_MOVE", assertThrows(ApiException.class, () -> slipOut.finish(player.id,
        failedStart.attemptId(), List.of(new SlipOutPuzzle.Move("X", 6)))).code);
    var failed = slipOut.finish(player.id, failedStart.attemptId(), List.of());
    assertFalse(failed.result().completed());
    assertNull(failed.best());

    var solution = SlipOutPuzzleTest.solutionForDate(puzzle.date());
    var firstStart = slipOut.start(player.id);
    jdbc.update("update slipout_attempts set started_at = now() - interval '95 seconds' where id = ?", firstStart.attemptId());
    var first = slipOut.finish(player.id, firstStart.attemptId(), solution);
    assertTrue(first.result().completed());
    assertEquals(puzzle.optimalMoves(), first.result().moves());
    assertNotNull(first.best());

    jdbc.update("""
        update submissions set metrics = metrics ||
          '{"rankingSecondary":10000,"rankingSecondaryDirection":"ASCENDING","completionTimeMs":10000,"remainingTimeMs":80000,"displayScore":"10 moves · 10000 ms"}'::jsonb
        where user_id = ? and game_id = ?
        """, player.id, game.id);
    try (var connection = jdbc.getDataSource().getConnection()) {
      ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V38__slipout_untimed_moves_only.sql"));
    }
    String migrated = jdbc.queryForObject("select metrics::text from submissions where user_id = ? and game_id = ?",
        String.class, player.id, game.id);
    assertFalse(migrated.contains("completionTimeMs"));
    assertFalse(migrated.contains("rankingSecondary"));
    assertFalse(migrated.contains(" ms"));

    var secondStart = slipOut.start(player.id);
    jdbc.update("update slipout_attempts set started_at = now() - interval '5 seconds' where id = ?", secondStart.attemptId());
    var second = slipOut.finish(player.id, secondStart.attemptId(), solution);
    assertTrue(second.result().completed());
    assertEquals(first.best(), second.best());
    assertEquals(second.result(), slipOut.finish(player.id, secondStart.attemptId(), solution).result());
    assertEquals(1, jdbc.queryForObject("select count(*) from submissions where user_id = ? and game_id = ?",
        Integer.class, player.id, game.id));
    assertEquals(3, jdbc.queryForObject("select count(*) from slipout_attempts where user_id = ?", Integer.class, player.id));
  }

  @Test void slipOutLevelImportVerifiesMovesAndUnlocksSequentially() {
    UserAccount player = users.save(new UserAccount("slipout-level-player@example.com", Instant.now()));
    service.onboard(new CurrentUser(player.id, player.normalizedEmail),
        new OnboardingRequest("Level Player", "#dfff4f"));
    assertEquals("LEVEL_LOCKED", assertThrows(ApiException.class, () -> slipOutLevels.start(player.id, 2)).code);
    assertThrows(ApiException.class, () -> slipOutLevels.importGuest(player.id,
        List.of(new SlipOutLevelService.ImportResult(1, List.of()))));
    assertTrue(slipOutLevels.progress(player.id).isEmpty());
    var merged = slipOutLevels.importGuest(player.id,
        List.of(new SlipOutLevelService.ImportResult(1, SlipOutLevelsTest.firstSolution())));
    assertEquals(SlipOutLevels.level(1).optimalMoves(), merged.getFirst().bestMoves());
    assertNotNull(slipOutLevels.start(player.id, 2).attemptId());
    var retry = slipOutLevels.start(player.id, 1);
    var finished = slipOutLevels.finish(player.id, retry.attemptId(), SlipOutLevelsTest.firstSolution());
    assertTrue(finished.completed());
    assertEquals(SlipOutLevels.level(1).optimalMoves(), finished.progress().getFirst().bestMoves());
  }

  @Test void telegramGroupSetupRejectsAnotherOwnerAndSurvivesSupergroupMigration() throws Exception {
    UserAccount first = users.save(new UserAccount("telegram-group-owner-1@example.com", Instant.now()));
    UserAccount second = users.save(new UserAccount("telegram-group-owner-2@example.com", Instant.now()));
    CurrentUser firstUser = new CurrentUser(first.id, first.normalizedEmail);
    CurrentUser secondUser = new CurrentUser(second.id, second.normalizedEmail);
    service.onboard(firstUser, new OnboardingRequest("Telegram Owner One", "#dfff4f"));
    service.onboard(secondUser, new OnboardingRequest("Telegram Owner Two", "#ff8db6"));
    for (UserAccount owner : List.of(first, second)) {
      BillingAccount account = new BillingAccount(owner.id, Instant.now());
      account.subscriptionStatus = "active";
      account.currentPeriodEnd = Instant.now().plusSeconds(86_400);
      billingAccounts.save(account);
    }
    UUID firstBoard = service.createBoard(firstUser, new CreateLeaderboardRequest("Telegram One", null, BoardType.MULTI_GAME));
    UUID secondBoard = service.createBoard(secondUser, new CreateLeaderboardRequest("Telegram Two", null, BoardType.MULTI_GAME));
    jdbc.update("insert into telegram_account_links(telegram_user_id,user_id,linked_at) values (?,?,now())", 441L, first.id);
    jdbc.update("insert into telegram_account_links(telegram_user_id,user_id,linked_at) values (?,?,now())", 442L, second.id);
    var json = new tools.jackson.databind.ObjectMapper();
    when(telegramBot.call(eq("getChatAdministrators"), anyMap())).thenReturn(json.readTree(
        "[{\"status\":\"administrator\",\"user\":{\"id\":441}},{\"status\":\"administrator\",\"user\":{\"id\":442}}]"));
    when(telegramBot.call(eq("getChat"), anyMap())).thenReturn(json.readTree("{\"title\":\"Friends\"}"));

    telegram.handle(json.readTree("{\"update_id\":90001,\"message\":{\"chat\":{\"id\":-20001,\"type\":\"group\"},\"from\":{\"id\":441},\"text\":\"/setup " + firstBoard + "\"}}"));
    assertEquals(firstBoard, jdbc.queryForObject("select leaderboard_id from telegram_group_leaderboards where chat_id=-20001", UUID.class));

    telegram.handle(json.readTree("{\"update_id\":90002,\"message\":{\"chat\":{\"id\":-20001,\"type\":\"group\"},\"from\":{\"id\":442},\"text\":\"/setup " + secondBoard + "\"}}"));
    assertEquals(firstBoard, jdbc.queryForObject("select leaderboard_id from telegram_group_leaderboards where chat_id=-20001", UUID.class));

    telegram.handle(json.readTree("{\"update_id\":90003,\"message\":{\"chat\":{\"id\":-20001,\"type\":\"group\"},\"migrate_to_chat_id\":-10020001}}"));
    assertEquals(firstBoard, jdbc.queryForObject("select leaderboard_id from telegram_group_leaderboards where chat_id=-10020001", UUID.class));
    assertEquals(0, jdbc.queryForObject("select count(*) from telegram_group_leaderboards where chat_id=-20001", Integer.class));

    BillingAccount expired = billingAccounts.findById(first.id).orElseThrow();
    expired.subscriptionStatus = "canceled";
    expired.currentPeriodEnd = Instant.now().minusSeconds(60);
    billingAccounts.save(expired);
    telegram.handle(json.readTree("{\"update_id\":90004,\"message\":{\"chat\":{\"id\":-10020001,\"type\":\"supergroup\"},\"from\":{\"id\":441},\"text\":\"/leaderboard\"}}"));
    verify(telegramBot).send(eq(-10020001L), contains("active paid plan"));
  }

  @Test void telegramSignupRequiresPrivateConfirmationFromOriginalIdentityAndIsSingleUse() throws Exception {
    UserAccount player = users.save(new UserAccount("telegram-signup@example.com", Instant.now()));
    CurrentUser current = new CurrentUser(player.id, player.normalizedEmail);
    service.onboard(current, new OnboardingRequest("Telegram Signup", "#a8c7ff"));
    var json = new tools.jackson.databind.ObjectMapper();
    telegram.handle(json.readTree("{\"update_id\":90011,\"message\":{\"chat\":{\"id\":551,\"type\":\"private\"},\"from\":{\"id\":551},\"text\":\"/signup\"}}"));
    ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
    verify(telegramBot).send(eq(551L), sent.capture());
    String token = sent.getValue().split("intent=")[1].split("\\n")[0];
    assertTrue(telegram.validSignup(token));
    telegram.requestSignupConfirmation(player.id, token);

    telegram.handle(json.readTree("{\"update_id\":90012,\"callback_query\":{\"id\":\"wrong\",\"from\":{\"id\":552},\"data\":\"s:" + token + "\",\"message\":{\"chat\":{\"id\":552}}}}"));
    assertNull(jdbc.queryForObject("select confirmed_at from telegram_signup_intents where telegram_user_id=551",
        java.time.OffsetDateTime.class));
    telegram.handle(json.readTree("{\"update_id\":90013,\"callback_query\":{\"id\":\"right\",\"from\":{\"id\":551},\"data\":\"s:" + token + "\",\"message\":{\"chat\":{\"id\":551}}}}"));
    assertEquals("linked", telegram.completeSignup(player.id, token));
    assertFalse(telegram.validSignup(token));
    assertEquals("SIGNUP_EXPIRED", assertThrows(ApiException.class,
        () -> telegram.completeSignup(player.id, token)).code);

    jdbc.update("insert into telegram_pending_flows(telegram_user_id,flow,step,callback_token,expires_at) " +
        "values (777,'daily','private','expired',now()-interval '1 minute')");
    telegram.handle(json.readTree("{\"update_id\":90014,\"message\":{\"chat\":{\"id\":777,\"type\":\"private\"},\"from\":{\"id\":777},\"text\":\"/start flow_expired\"}}"));
    verify(telegramBot).send(eq(777L), contains("expired"));
  }

  @Test void telegramFreeMemberUsesGuidedGroupRankingsAndPrivateScoreSubmission() throws Exception {
    UserAccount owner = users.save(new UserAccount("telegram-guided-owner@example.com", Instant.now()));
    UserAccount member = users.save(new UserAccount("telegram-guided-member@example.com", Instant.now()));
    CurrentUser ownerUser = new CurrentUser(owner.id, owner.normalizedEmail);
    CurrentUser memberUser = new CurrentUser(member.id, member.normalizedEmail);
    service.onboard(ownerUser, new OnboardingRequest("Telegram Guided Owner", "#dfff4f"));
    var memberProfile = service.onboard(memberUser, new OnboardingRequest("Telegram Guided Member", "#ff8db6"));
    BillingAccount account = new BillingAccount(owner.id, Instant.now());
    account.subscriptionStatus = "active";
    account.currentPeriodEnd = Instant.now().plusSeconds(86_400);
    billingAccounts.save(account);
    UUID boardId = service.createBoard(ownerUser, new CreateLeaderboardRequest("Guided Telegram", null, BoardType.MULTI_GAME));
    service.addMember(ownerUser, boardId, memberProfile.shareId());
    jdbc.update("insert into telegram_account_links(telegram_user_id,user_id,linked_at) values (?,?,now())", 661L, member.id);
    jdbc.update("insert into telegram_group_leaderboards(chat_id,leaderboard_id,title,configured_by_user_id,configured_at) values (?,?,?,?,now())",
        -20061L, boardId, "Guided Group", owner.id);
    when(telegramBot.deepLink(anyString())).thenAnswer(call -> "https://t.me/DailyTrackerBot?start=" + call.getArgument(0));
    var json = new tools.jackson.databind.ObjectMapper();

    telegram.handle(json.readTree("{\"update_id\":90021,\"message\":{\"chat\":{\"id\":-20061,\"type\":\"group\"},\"from\":{\"id\":661},\"text\":\"/leaderboard\"}}"));
    verify(telegramBot).send(eq(-20061L), contains("Nobody has posted a score"), anyList());
    telegram.handle(json.readTree("{\"update_id\":90022,\"callback_query\":{\"id\":\"expand\",\"from\":{\"id\":661},"
        + "\"data\":\"lb:" + boardId + ":0\",\"message\":{\"chat\":{\"id\":-20061,\"type\":\"group\"}}}}"));
    verify(telegramBot).send(eq(661L), contains("Game leaders"), anyList());

    telegram.handle(json.readTree("{\"update_id\":90024,\"message\":{\"chat\":{\"id\":-20061,\"type\":\"group\"},\"from\":{\"id\":661},\"text\":\"/daily\"}}"));
    ArgumentCaptor<List> privateButtons = ArgumentCaptor.forClass(List.class);
    verify(telegramBot).send(eq(-20061L), contains("Continue /daily"), privateButtons.capture());
    @SuppressWarnings("unchecked") String privateUrl = ((java.util.Map<String, String>) ((List<?>) privateButtons.getValue().getFirst()).getFirst()).get("url");
    String start = privateUrl.substring(privateUrl.indexOf("start=") + 6);
    telegram.handle(json.readTree("{\"update_id\":90025,\"message\":{\"chat\":{\"id\":661,\"type\":\"private\"},\"from\":{\"id\":661},\"text\":\"/start " + start + "\"}}"));
    ArgumentCaptor<List> submitButtons = ArgumentCaptor.forClass(List.class);
    verify(telegramBot).send(eq(661L), eq("Choose a game:"), submitButtons.capture());
    @SuppressWarnings("unchecked") String searchChoice = ((java.util.Map<String, String>)
        ((List<?>) submitButtons.getValue().getFirst()).getFirst()).get("callback_data");
    telegram.handle(json.readTree("{\"update_id\":90026,\"callback_query\":{\"id\":\"search\",\"from\":{\"id\":661},\"data\":\"" + searchChoice + "\",\"message\":{\"chat\":{\"id\":661}}}}"));
    telegram.handle(json.readTree("{\"update_id\":90027,\"message\":{\"chat\":{\"id\":661,\"type\":\"private\"},\"from\":{\"id\":661},\"text\":\"Fermi\"}}"));
    verify(telegramBot, times(2)).send(eq(661L), eq("Choose a game:"), submitButtons.capture());
    String fermiChoice = null;
    for (Object row : submitButtons.getValue()) {
      @SuppressWarnings("unchecked") var button = (java.util.Map<String, String>) ((List<?>) row).getFirst();
      if (button.get("text").startsWith("Fermi")) fermiChoice = button.get("callback_data");
    }
    assertNotNull(fermiChoice, submitButtons.getValue().toString());
    telegram.handle(json.readTree("{\"update_id\":90028,\"callback_query\":{\"id\":\"fermi\",\"from\":{\"id\":661},\"data\":\"" + fermiChoice + "\",\"message\":{\"chat\":{\"id\":661}}}}"));
    telegram.handle(json.readTree("{\"update_id\":90029,\"message\":{\"chat\":{\"id\":661,\"type\":\"private\"},\"from\":{\"id\":661},\"text\":\"Fermi #92\\n1.31× score\"}}"));
    verify(telegramBot).send(eq(-20061L), contains("Saved Fermi.gg #92"));

    telegram.handle(json.readTree("{\"update_id\":90030,\"message\":{\"chat\":{\"id\":-20061,\"type\":\"group\"},\"from\":{\"id\":661},\"text\":\"/loldle\"}}"));
    ArgumentCaptor<List> loldleButtons = ArgumentCaptor.forClass(List.class);
    verify(telegramBot).send(eq(-20061L), contains("Continue /loldle"), loldleButtons.capture());
    @SuppressWarnings("unchecked") String loldleUrl = ((java.util.Map<String, String>) ((List<?>) loldleButtons.getValue().getFirst()).getFirst()).get("url");
    String loldleStart = loldleUrl.substring(loldleUrl.indexOf("start=") + 6);
    telegram.handle(json.readTree("{\"update_id\":90031,\"message\":{\"chat\":{\"id\":661,\"type\":\"private\"},\"from\":{\"id\":661},\"text\":\"/start " + loldleStart + "\"}}"));
    telegram.handle(json.readTree("{\"update_id\":90032,\"message\":{\"chat\":{\"id\":661,\"type\":\"private\"},\"from\":{\"id\":661},\"text\":" + json.writeValueAsString(LoldleParser.example()) + "}}"));
    verify(telegramBot).send(eq(661L), eq("Enter the LoLdle puzzle date as YYYY-MM-DD."));
    telegram.handle(json.readTree("{\"update_id\":90033,\"message\":{\"chat\":{\"id\":661,\"type\":\"private\"},\"from\":{\"id\":661},\"text\":\"2026-06-01\"}}"));
    verify(telegramBot).send(eq(-20061L), contains("Saved LoLdle for 2026-06-01"));
  }

  @Test void letterBagRoundRecordsOnlyServerScoredResult() {
    UserAccount player = users.save(new UserAccount("letter-bag-player@example.com", Instant.now()));
    CurrentUser current = new CurrentUser(player.id, player.normalizedEmail);
    service.onboard(current, new OnboardingRequest("Bag Player", "#dfff4f"));
    var hidden = letterBag.state(player.id);
    assertEquals("ROUND_NOT_FINISHED", assertThrows(ApiException.class, () -> letterBag.replay(player.id)).code);
    assertNull(hidden.startedAt());
    assertTrue(hidden.rack().stream().allMatch(tile -> tile.letter().equals("?")));
    var started = letterBag.start(player.id);
    assertEquals(started.startedAt(), letterBag.start(player.id).startedAt());
    assertEquals(15, started.rack().size());
    assertEquals(5, started.preview().size());
    var invalid = assertThrows(ApiException.class, () -> letterBag.word(player.id, List.of(0, 0, 1)));
    assertEquals("INVALID_TILES", invalid.code);
    assertEquals(0, letterBag.state(player.id).score());
    var ended = letterBag.finish(player.id);
    assertNotNull(ended.endedAt());
    assertEquals(ended.endedAt(), letterBag.finish(player.id).endedAt());
    var replay = letterBag.replay(player.id);
    assertEquals(ended.date(), replay.date());
    assertNull(replay.startedAt());
    assertEquals(0, replay.score());
    assertEquals(LetterBagService.puzzle(ended.date()).subList(0, 15), replay.rack());
    assertEquals(LetterBagService.puzzle(ended.date()).subList(15, 30), replay.bag());
    Game game = games.findBySlugAndActiveTrue("letter-bag").orElseThrow();
    assertEquals("Letter Bag", game.name);
    assertEquals("Letter Bag", jdbc.queryForObject("select name from game_catalog where slug='letter-bag'", String.class));
    var submission = submissions.findByUserIdAndGameIdAndPuzzleKey(player.id, game.id, ended.date().toString()).orElseThrow();
    assertEquals(0, BigDecimal.ZERO.compareTo(submission.primaryScore));
    assertFalse(submission.metrics.containsKey("completionTime"));
    assertFalse(submission.metrics.containsKey("remainingTime"));
    assertEquals(SubmissionMethod.IN_APP, submission.submissionMethod);
    assertEquals(1, jdbc.queryForObject("select count(*) from submissions where user_id = ? and game_id = ?", Integer.class, player.id, game.id));
    assertEquals("ROUND_ENDED", assertThrows(ApiException.class, () -> letterBag.word(player.id, List.of(0, 1, 2))).code);

    UserAccount scorer = users.save(new UserAccount("letter-bag-scorer@example.com", Instant.now()));
    CurrentUser scorerUser = new CurrentUser(scorer.id, scorer.normalizedEmail);
    service.onboard(scorerUser, new OnboardingRequest("Bag Scorer", "#ff8db6"));
    var second = letterBag.start(scorer.id);
    List<Integer> selected = null;
    for (String candidate : List.of("CAT", "DOG", "SUN", "EAR", "NOTE", "STAR", "RAIN", "FIRE", "GAME", "LAMP")) {
      var available = new java.util.ArrayList<>(second.rack());
      var ids = new java.util.ArrayList<Integer>();
      for (char letter : candidate.toCharArray()) {
        var tile = available.stream().filter(value -> value.letter().equals(String.valueOf(letter))).findFirst().orElse(null);
        if (tile == null) break;
        ids.add(tile.id()); available.remove(tile);
      }
      if (ids.size() == candidate.length()) { selected = ids; break; }
    }
    assertNotNull(selected);
    List<Integer> selectedWord = List.copyOf(selected);
    var played = letterBag.word(scorer.id, selectedWord);
    assertEquals(LetterBagScoring.score(played.words().getFirst().word()).total(), played.score());
    assertEquals(1, played.words().size());
    assertEquals("INVALID_TILES", assertThrows(ApiException.class, () -> letterBag.word(scorer.id, selectedWord)).code);
    assertEquals(played.score(), letterBag.state(scorer.id).score());
    letterBag.finish(scorer.id);
    var scored = submissions.findByUserIdAndGameIdAndPuzzleKey(scorer.id, game.id, played.date().toString()).orElseThrow();
    assertEquals(0, BigDecimal.valueOf(played.score()).compareTo(scored.primaryScore));
    assertTrue(service.dashboard(scorerUser).recentScores().stream().anyMatch(value -> value.gameSlug().equals("letter-bag")));

    UserAccount untimed = users.save(new UserAccount("letter-bag-untimed@example.com", Instant.now()));
    service.onboard(new CurrentUser(untimed.id, untimed.normalizedEmail), new OnboardingRequest("Untimed Player", "#a8c7ff"));
    letterBag.start(untimed.id);
    jdbc.update("""
        update letter_bag_attempts set started_at = now() - interval '91 seconds',
          state = jsonb_set(state, '{startedAt}', to_jsonb(?::bigint)) where user_id = ?
        """, System.currentTimeMillis() - 91000, untimed.id);
    assertNull(letterBag.state(untimed.id).endedAt());
    assertTrue(submissions.findByUserIdAndGameIdAndPuzzleKey(untimed.id, game.id, played.date().toString()).isEmpty());
    letterBag.finish(untimed.id);
    assertTrue(submissions.findByUserIdAndGameIdAndPuzzleKey(untimed.id, game.id, played.date().toString()).isPresent());
  }

  @Test void loldleBatchIsIdempotentAndRejectsConflictsWithoutPartialWrites() {
    LocalDate date = LocalDate.now(ZoneOffset.UTC);
    String share = LoldleParser.example();
    UserAccount first = users.save(new UserAccount("loldle-first@example.com", Instant.now()));
    CurrentUser firstUser = new CurrentUser(first.id, first.normalizedEmail);
    service.onboard(firstUser, new OnboardingRequest("LoLdle First", "#dfff4f"));
    var request = new LoldleSubmissionRequest(share, date);
    List<UUID> firstIds = service.submitLoldle(firstUser, request);
    assertEquals(5, firstIds.size());
    assertEquals(firstIds, service.submitLoldle(firstUser, request));

    UserAccount second = users.save(new UserAccount("loldle-second@example.com", Instant.now()));
    CurrentUser secondUser = new CurrentUser(second.id, second.normalizedEmail);
    service.onboard(secondUser, new OnboardingRequest("LoLdle Second", "#ff8db6"));
    Game quote = games.findBySlugAndActiveTrue("loldle-quote").orElseThrow();
    ParsedSubmission old = LoldleParser.parse(share.replace("💬 Quote: 1", "💬 Quote: 2"), date).get("loldle-quote");
    submissions.saveAndFlush(new Submission(second.id, quote.id, old, "older share", Instant.now()));
    assertEquals("DUPLICATE_SUBMISSION", assertThrows(ApiException.class,
        () -> service.submitLoldle(secondUser, request)).code);
    for (String mode : LoldleParser.MODES) {
      Game game = games.findBySlugAndActiveTrue("loldle-" + mode).orElseThrow();
      assertEquals(mode.equals("quote"), submissions.findByUserIdAndGameIdAndPuzzleKey(second.id, game.id,
          date.toString()).isPresent(), mode);
    }
  }

  @Test void themePreferenceDefaultsToLightAndBelongsToItsProfile() {
    UserAccount first = users.save(new UserAccount("theme-first@example.com", Instant.now()));
    UserAccount second = users.save(new UserAccount("theme-second@example.com", Instant.now()));
    CurrentUser firstUser = new CurrentUser(first.id, first.normalizedEmail);
    CurrentUser secondUser = new CurrentUser(second.id, second.normalizedEmail);
    service.onboard(firstUser, new OnboardingRequest("First Player", "#dfff4f"));
    service.onboard(secondUser, new OnboardingRequest("Second Player", "#ff8db6"));

    assertFalse(service.me(firstUser).darkMode());
    assertTrue(service.updateTheme(firstUser, new ThemeRequest(true)).darkMode());
    assertTrue(service.me(firstUser).darkMode());
    assertFalse(service.me(secondUser).darkMode());
    assertTrue(jdbc.queryForObject("select dark_mode from profiles where user_id = ?", Boolean.class, first.id));
  }

  @Test void deletingLeaderboardCascadesConfigurationButPreservesScores() {
    UserAccount owner = users.save(new UserAccount("delete-owner@example.com", Instant.now()));
    CurrentUser current = new CurrentUser(owner.id, owner.normalizedEmail);
    service.onboard(current, new OnboardingRequest("Delete Owner", "#dfff4f"));
    UUID boardId = service.createBoard(current, new CreateLeaderboardRequest("Delete Board", "fermi"));
    String inviteUrl = invites.create(boardId, owner.id).url();
    assertNotNull(inviteUrl);

    Game game = games.findBySlugAndActiveTrue("fermi").orElseThrow();
    submissions.save(new Submission(owner.id, game.id,
        new ParsedSubmission("delete-test", 1, java.time.LocalDate.now(), BigDecimal.ONE, java.util.Map.of(), 1),
        "delete test score", Instant.now()));
    String guildId = "333333333333333334";
    String channelId = "444444444444444445";
    channels.save(new DiscordChannelLeaderboard(guildId, channelId, boardId, owner.id,
        "111111111111111112", Instant.now()));
    groupDms.save(new DiscordGroupDmLeaderboard("555555555555555556", boardId, owner.id,
        "111111111111111112", Instant.now()));
    setups.save(new DiscordSetup(owner.id, boardId, "a".repeat(64), Instant.now().plusSeconds(600)));
    signupIntents.save(new DiscordSignupIntent("b".repeat(64), "111111111111111112", guildId, channelId,
        boardId, Instant.now().plusSeconds(600)));
    registrations.markCurrent("123456789012345679", guildId, "help", "hash");

    deletions.delete(boardId, owner.id);

    assertFalse(boards.existsById(boardId));
    assertEquals(0L, jdbc.queryForObject("select count(*) from leaderboard_members where leaderboard_id = ?", Long.class, boardId));
    assertEquals(0L, jdbc.queryForObject("select count(*) from leaderboard_invites where leaderboard_id = ?", Long.class, boardId));
    assertEquals(0L, jdbc.queryForObject("select count(*) from discord_setups where leaderboard_id = ?", Long.class, boardId));
    assertEquals(0L, jdbc.queryForObject("select count(*) from discord_channel_leaderboards where leaderboard_id = ?", Long.class, boardId));
    assertEquals(0L, jdbc.queryForObject("select count(*) from discord_group_dm_leaderboards where leaderboard_id = ?", Long.class, boardId));
    assertEquals(0L, jdbc.queryForObject("select count(*) from discord_signup_intents where leaderboard_id = ?", Long.class, boardId));
    assertEquals(1, submissions.findByUserIdOrderByPlayedOnDescCreatedAtDesc(owner.id).size());
    assertFalse(registrations.isCurrent("123456789012345679", guildId, "help", "hash"));
  }

  @Test void unlinkingOwnersPrunesOnlyTheirChannelsAndLastGuildRegistration() {
    UserAccount first = users.save(new UserAccount("discord-owner-one@example.com", Instant.now()));
    UserAccount second = users.save(new UserAccount("discord-owner-two@example.com", Instant.now()));
    CurrentUser firstUser = new CurrentUser(first.id, first.normalizedEmail);
    CurrentUser secondUser = new CurrentUser(second.id, second.normalizedEmail);
    service.onboard(firstUser, new OnboardingRequest("First Owner", "#dfff4f"));
    service.onboard(secondUser, new OnboardingRequest("Second Owner", "#ff8db6"));
    UUID firstBoard = service.createBoard(firstUser, new CreateLeaderboardRequest("First Board", "fermi"));
    UUID secondBoard = service.createBoard(secondUser, new CreateLeaderboardRequest("Second Board", "fermi"));
    String guild = "333333333333333333";
    String firstChannel = "444444444444444444";
    String secondChannel = "555555555555555555";
    channels.save(new DiscordChannelLeaderboard(guild, firstChannel, firstBoard, first.id, "111111111111111111", Instant.now()));
    channels.save(new DiscordChannelLeaderboard(guild, secondChannel, secondBoard, second.id, "222222222222222222", Instant.now()));
    registrations.markCurrent("123456789012345678", guild, "help", "hash");

    discord.unlinkWebsite(first.id);
    assertFalse(channels.existsById(new DiscordChannelLeaderboardId(guild, firstChannel)));
    assertTrue(channels.existsById(new DiscordChannelLeaderboardId(guild, secondChannel)));
    assertTrue(registrations.isCurrent("123456789012345678", guild, "help", "hash"));

    discord.unlinkWebsite(second.id);
    assertFalse(channels.existsById(new DiscordChannelLeaderboardId(guild, secondChannel)));
    assertFalse(registrations.isCurrent("123456789012345678", guild, "help", "hash"));
  }

  @Test void reusableInviteJoinsProfilesAndRevocationInvalidatesOldLinks() {
    UserAccount owner = users.save(new UserAccount("invite-owner@example.com", Instant.now()));
    UserAccount first = users.save(new UserAccount("invite-first@example.com", Instant.now()));
    UserAccount second = users.save(new UserAccount("invite-second@example.com", Instant.now()));
    CurrentUser ownerUser = new CurrentUser(owner.id, owner.normalizedEmail);
    CurrentUser firstUser = new CurrentUser(first.id, first.normalizedEmail);
    CurrentUser secondUser = new CurrentUser(second.id, second.normalizedEmail);
    service.onboard(ownerUser, new OnboardingRequest("Invite Owner", "#dfff4f"));
    service.onboard(firstUser, new OnboardingRequest("Invite First", "#ff8db6"));
    UUID boardId = service.createBoard(ownerUser, new CreateLeaderboardRequest("Invite Board", "fermi"));

    assertEquals(403, assertThrows(ApiException.class, () -> invites.create(boardId, first.id)).status);
    String firstLink = invites.create(boardId, owner.id).url();
    assertEquals(firstLink, invites.create(boardId, owner.id).url());
    String token = firstLink.substring(firstLink.indexOf("invite=") + 7);
    assertEquals("Invite Board", invites.preview(token).name());
    assertEquals(boardId, invites.redeem(token, first.id));
    assertEquals(boardId, invites.redeem(token, first.id));
    assertEquals(409, assertThrows(ApiException.class, () -> invites.redeem(token, second.id)).status);
    service.onboard(secondUser, new OnboardingRequest("Invite Second", "#a8c7ff"));
    invites.redeem(token, second.id);
    assertEquals(3, service.board(ownerUser, boardId, "today").members().size());
    assertEquals("INVITE_INVALID", assertThrows(ApiException.class, () -> invites.preview(token + "x")).code);

    String replacement = invites.regenerate(boardId, owner.id).url();
    assertNotEquals(firstLink, replacement);
    assertEquals("INVITE_INVALID", assertThrows(ApiException.class, () -> invites.preview(token)).code);
    String replacementToken = replacement.substring(replacement.indexOf("invite=") + 7);
    invites.revoke(boardId, owner.id);
    assertEquals("INVITE_REVOKED", assertThrows(ApiException.class, () -> invites.preview(replacementToken)).code);
  }

  @Test void onboardingLeavesFreeLeaderboardForExplicitCreation() {
    UserAccount owner = users.save(new UserAccount("owner@example.com", Instant.now()));
    CurrentUser current = new CurrentUser(owner.id, owner.normalizedEmail);
    ProfileView profile = service.onboard(current, new OnboardingRequest("Owner", "#dfff4f"));
    assertNotNull(profile.shareId());
    assertTrue(service.listBoards(current).isEmpty());

    assertEquals(games.findByActiveTrueOrderByNameAsc().stream().map(game -> game.slug).sorted().toList(),
        service.listGames().stream().map(GameView::slug).sorted().toList());

    var boardId = service.createBoard(current, new CreateLeaderboardRequest("My Friends", "krillion"));
    assertEquals(1, service.listBoards(current).size());
    assertEquals("krillion", service.listBoards(current).getFirst().game().slug());
    assertFalse(service.listBoards(current).getFirst().locked());
    for (int index = 2; index <= 5; index++) {
      service.createBoard(current, new CreateLeaderboardRequest("Board " + index, "fermi"));
    }
    assertEquals(5, service.listBoards(current).size());
    ApiException subscriptionRequired = assertThrows(ApiException.class,
        () -> service.createBoard(current, new CreateLeaderboardRequest("Sixth board", "fermi")));
    assertEquals(409, subscriptionRequired.status);
    assertEquals("SINGLE_GAME_LIMIT_REACHED", subscriptionRequired.code);

    UserAccount stranger = users.save(new UserAccount("stranger@example.com", Instant.now()));
    CurrentUser other = new CurrentUser(stranger.id, stranger.normalizedEmail);
    service.onboard(other, new OnboardingRequest("Stranger", "#ff8db6"));
    ApiException hidden = assertThrows(ApiException.class,
        () -> service.board(other, boardId, "today"));
    assertEquals(404, hidden.status);

    service.addMember(current, boardId, service.me(other).profile().shareId());
    String ownerShare = "Krillion #82 🦐\n180\n\n🐟🫧🫧🐟🦑🫧🐟";
    String strangerShare = "Krillion #82 🦐\n700\n\n🌟🌟🌟🌟🌟🌟🌟";
    ParsedSubmission ownerPreview = service.preview(current, new PreviewSubmissionRequest("krillion", ownerShare));
    ParsedSubmission strangerPreview = service.preview(other, new PreviewSubmissionRequest("krillion", strangerShare));
    service.submit(current, new CreateSubmissionRequest("krillion", ownerShare, confirmed(ownerPreview)));
    service.submit(other, new CreateSubmissionRequest("krillion", strangerShare, confirmed(strangerPreview)));

    LeaderboardResponse leaderboard = service.board(current, boardId, "all");
    assertEquals("Stranger", leaderboard.rankings().getFirst().displayName());
    assertEquals(0, new BigDecimal("700").compareTo(leaderboard.rankings().getFirst().average()));
    assertEquals(0, new BigDecimal("180").compareTo(service.dashboard(current).statsByGame().getFirst().average()));
  }

  @Test void createsAndConvertsSubscriptionMultiGameBoardsAndEnforcesSchemaInvariants() {
    UserAccount owner = users.save(new UserAccount("multi-owner@example.com", Instant.now()));
    CurrentUser current = new CurrentUser(owner.id, owner.normalizedEmail);
    service.onboard(current, new OnboardingRequest("Multi Owner", "#a8c7ff"));

    UUID freeBoard = service.createBoard(current, new CreateLeaderboardRequest("Original", "fermi"));
    Leaderboard original = boards.findById(freeBoard).orElseThrow();
    assertEquals(BoardType.SINGLE_GAME, original.boardType);
    assertEquals(BoardCreationBasis.FREE, original.creationBasis);

    BillingAccount account = new BillingAccount(owner.id, Instant.now());
    account.subscriptionStatus = "active";
    account.currentPeriodEnd = Instant.now().plusSeconds(86_400);
    billingAccounts.save(account);

    service.convertToMultiGame(current, freeBoard);
    Leaderboard converted = boards.findById(freeBoard).orElseThrow();
    assertEquals(BoardType.MULTI_GAME, converted.boardType);
    assertNull(converted.gameId);
    assertEquals(BoardCreationBasis.SUBSCRIPTION, converted.creationBasis);
    assertEquals(0, boards.countByOwnerIdAndCreationBasis(owner.id, BoardCreationBasis.FREE));

    ApiException limit = assertThrows(ApiException.class, () -> service.createBoard(current,
        new CreateLeaderboardRequest("Every Game", null, BoardType.MULTI_GAME)));
    assertEquals("ALL_GAME_LIMIT_REACHED", limit.code);
    account.stripePriceId = "price_premium";
    billingAccounts.save(account);
    UUID multiId = service.createBoard(current,
        new CreateLeaderboardRequest("Every Game", null, BoardType.MULTI_GAME));
    BoardSummary summary = service.listBoards(current).stream().filter(value -> value.id().equals(multiId)).findFirst().orElseThrow();
    assertEquals(BoardType.MULTI_GAME, summary.boardType());
    assertEquals(summary.games().getFirst().slug(), summary.game().slug());
    assertEquals(service.listGames().stream().map(GameView::slug).toList(),
        summary.games().stream().map(GameView::slug).toList());
    assertEquals("krillion", service.board(current, multiId, "all", "krillion").game().slug());

    UUID gameId = UUID.fromString("f0000000-0000-4000-8000-000000000001");
    assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
        "insert into leaderboards(id, owner_id, name, game_id, creation_basis, board_type, created_at) values (?, ?, ?, null, 'FREE', 'SINGLE_GAME', now())",
        UUID.randomUUID(), owner.id, "Bad Single"));
    assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
        "insert into leaderboards(id, owner_id, name, game_id, creation_basis, board_type, created_at) values (?, ?, ?, ?, 'SUBSCRIPTION', 'MULTI_GAME', now())",
        UUID.randomUUID(), owner.id, "Bad Multi Game", gameId));
    assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
        "insert into leaderboards(id, owner_id, name, game_id, creation_basis, board_type, created_at) values (?, ?, ?, null, 'FREE', 'MULTI_GAME', now())",
        UUID.randomUUID(), owner.id, "Bad Multi Basis"));
  }

  private ConfirmedSubmission confirmed(ParsedSubmission parsed) {
    return new ConfirmedSubmission(parsed.puzzleKey(), parsed.puzzleNumber(), parsed.playedOn(), parsed.primaryScore(),
        parsed.metrics(), parsed.parserVersion());
  }
}
