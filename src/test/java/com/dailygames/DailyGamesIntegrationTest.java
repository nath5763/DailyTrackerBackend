package com.dailygames;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
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
