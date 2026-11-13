package com.dailygames;

import com.stripe.model.Event;
import com.stripe.model.Subscription;
import com.stripe.exception.StripeException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BillingServiceTest {
  private static final String PORTAL_CONFIGURATION_ID = "bpc_1UXucYFCEcsTxLHRcDhmBYgJ";
  private final BillingAccountRepository accounts = mock(BillingAccountRepository.class);
  private final ProcessedStripeEventRepository events = mock(ProcessedStripeEventRepository.class);
  private final ProfileRepository profiles = mock(ProfileRepository.class);
  private final LeaderboardRepository boards = mock(LeaderboardRepository.class);
  private final UserRepository users = mock(UserRepository.class);
  private final StripeGateway stripe = mock(StripeGateway.class);
  private final BillingService service = new BillingService(accounts, events, profiles, boards, users, stripe,
      "sk_test_configured", "price_test", "price_premium", PORTAL_CONFIGURATION_ID, "http://localhost:3000");
  private final UUID ownerId = UUID.randomUUID();
  private final Instant now = Instant.parse("2026-10-31T12:00:00Z");

  @Test void freeBoardsStayAccessibleWithoutBilling() {
    Leaderboard board = board(BoardCreationBasis.FREE);
    assertTrue(service.isBoardAccessible(board, now));
    verifyNoInteractions(accounts);
  }

  @Test void billingStatusReportsCountsAndTierLimits() {
    when(profiles.findById(ownerId)).thenReturn(Optional.of(new Profile(ownerId, "Owner", "#dfff4f", now)));
    when(boards.countByOwnerIdAndBoardType(ownerId, BoardType.SINGLE_GAME)).thenReturn(6L);
    when(boards.countByOwnerIdAndBoardType(ownerId, BoardType.MULTI_GAME)).thenReturn(2L);
    assertEquals(BillingTier.FREE, service.status(ownerId).tier());
    assertEquals(6, service.status(ownerId).singleGameCount());
    assertEquals(5, service.status(ownerId).singleGameLimit());
    assertEquals(2, service.status(ownerId).allGameCount());
    assertEquals(0, service.status(ownerId).allGameLimit());
  }

  @Test void allSingleGameBoardsStayReadableAfterCancellation() {
    Leaderboard board = board(BoardCreationBasis.GRANDFATHERED);
    when(accounts.findById(ownerId)).thenReturn(Optional.empty());
    assertTrue(service.isBoardAccessible(board, now));

    BillingAccount canceled = new BillingAccount(ownerId, now.minusSeconds(100));
    canceled.everSubscribedAt = now.minusSeconds(50);
    canceled.subscriptionStatus = "canceled";
    when(accounts.findById(ownerId)).thenReturn(Optional.of(canceled));
    assertTrue(service.isBoardAccessible(board, now));
  }

  @Test void subscriptionBoardsHonorTheSevenDayPaymentGracePeriod() {
    Leaderboard board = new Leaderboard(ownerId, "All games", null, BoardType.MULTI_GAME,
        BoardCreationBasis.SUBSCRIPTION, now.minusSeconds(100));
    BillingAccount account = new BillingAccount(ownerId, now.minusSeconds(100));
    account.subscriptionStatus = "past_due";
    account.graceUntil = now.plusSeconds(60);
    when(accounts.findById(ownerId)).thenReturn(Optional.of(account));
    assertTrue(service.isBoardAccessible(board, now));

    account.graceUntil = now.minusSeconds(1);
    assertFalse(service.isBoardAccessible(board, now));
  }

  @Test void billingAccountWithoutAStatusIsNotEntitledAndDoesNotCrash() {
    BillingAccount account = new BillingAccount(ownerId, now);
    when(accounts.findById(ownerId)).thenReturn(Optional.of(account));

    assertEquals(0, service.allGameLimit(ownerId, now));
  }

  @Test void existingTwoDollarSubscribersAndPremiumDowngradesKeepTheirBoards() {
    BillingAccount account = new BillingAccount(ownerId, now);
    account.subscriptionStatus = "active";
    when(accounts.findById(ownerId)).thenReturn(Optional.of(account));
    assertEquals(1, service.allGameLimit(ownerId, now)); // legacy row without a stored Price
    account.stripePriceId = "price_premium";
    assertEquals(5, service.allGameLimit(ownerId, now));
    account.stripePriceId = "price_test";
    assertEquals(1, service.allGameLimit(ownerId, now));
    account.stripePriceId = "price_unknown";
    assertEquals(0, service.allGameLimit(ownerId, now));
    account.subscriptionStatus = "canceled";
    account.stripePriceId = "price_premium";
    assertEquals(0, service.allGameLimit(ownerId, now));
  }

  @Test void checkoutSelectionUsesTheRequestedPriceAndSeparateRetryKeys() throws Exception {
    UserAccount user = new UserAccount("owner@example.com", now);
    user.id = ownerId;
    BillingAccount account = new BillingAccount(ownerId, now);
    account.stripeCustomerId = "cus_test";
    when(profiles.findByIdForUpdate(ownerId)).thenReturn(Optional.of(new Profile(ownerId, "Owner", "#dfff4f", now)));
    when(users.findById(ownerId)).thenReturn(Optional.of(user));
    when(accounts.findById(ownerId)).thenReturn(Optional.of(account));
    when(stripe.createCheckout(eq("cus_test"), eq(ownerId), eq("price_premium"), anyString(), anyString(),
        any(Instant.class), anyString())).thenReturn(new StripeGateway.StripeCheckoutSession(
            "cs_premium", "https://checkout.stripe.test/premium", now.plus(Duration.ofMinutes(30))));
    assertEquals("https://checkout.stripe.test/premium", service.createCheckout(ownerId, BillingTier.PREMIUM).url());
    verify(stripe).createCheckout(eq("cus_test"), eq(ownerId), eq("price_premium"),
        eq("http://localhost:3000/billing/return?checkout=success"),
        eq("http://localhost:3000/billing/return?checkout=cancelled"),
        any(Instant.class), anyString());
    assertEquals("price_premium", account.pendingCheckoutPriceId);
    assertNotEquals(BillingService.checkoutAttempt(ownerId, BillingTier.STANDARD, now).idempotencyKey(),
        BillingService.checkoutAttempt(ownerId, BillingTier.PREMIUM, now).idempotencyKey());
    assertEquals("INVALID_BILLING_TIER", assertThrows(ApiException.class,
        () -> service.createCheckout(ownerId, BillingTier.FREE)).code);
  }

  @Test void accountWithoutAStatusCanCreateCheckoutWithoutPortalConfiguration() throws Exception {
    BillingService checkoutService = new BillingService(accounts, events, profiles, boards, users, stripe,
        "sk_test_configured", "price_test", "price_premium", "", "http://localhost:3000");
    UserAccount user = new UserAccount("owner@example.com", now);
    user.id = ownerId;
    when(profiles.findByIdForUpdate(ownerId)).thenReturn(Optional.of(new Profile(ownerId, "Owner", "#dfff4f", now)));
    when(users.findById(ownerId)).thenReturn(Optional.of(user));
    when(accounts.findById(ownerId)).thenReturn(Optional.empty());
    when(stripe.createCustomer(user.normalizedEmail, ownerId))
        .thenReturn(new StripeGateway.StripeCustomer("cus_test"));
    when(stripe.createCheckout(eq("cus_test"), eq(ownerId), eq("price_test"), anyString(), anyString(),
        any(Instant.class), anyString())).thenReturn(new StripeGateway.StripeCheckoutSession(
            "cs_test", "https://checkout.stripe.test/session", now.plus(Duration.ofMinutes(30))));

    assertEquals("https://checkout.stripe.test/session", checkoutService.createCheckout(ownerId).url());
    verify(accounts).saveAndFlush(argThat(account -> "cus_test".equals(account.stripeCustomerId)));
    verify(accounts).save(argThat(account -> "cs_test".equals(account.pendingCheckoutSessionId)));
  }

  @Test void existingNonTerminalSubscriptionMustBeManagedInsteadOfDuplicated() {
    UserAccount user = new UserAccount("owner@example.com", now);
    user.id = ownerId;
    BillingAccount account = new BillingAccount(ownerId, now);
    account.subscriptionStatus = "past_due";
    when(profiles.findByIdForUpdate(ownerId)).thenReturn(Optional.of(new Profile(ownerId, "Owner", "#dfff4f", now)));
    when(users.findById(ownerId)).thenReturn(Optional.of(user));
    when(accounts.findById(ownerId)).thenReturn(Optional.of(account));

    ApiException exception = assertThrows(ApiException.class, () -> service.createCheckout(ownerId));

    assertEquals(409, exception.status);
    assertEquals("BILLING_SUBSCRIPTION_REQUIRES_MANAGEMENT", exception.code);
    verifyNoInteractions(stripe);
  }

  @Test void customerMappingIsSavedBeforeCheckoutFailure() throws Exception {
    UserAccount user = new UserAccount("owner@example.com", now);
    user.id = ownerId;
    StripeException stripeFailure = mock(StripeException.class);
    when(profiles.findByIdForUpdate(ownerId)).thenReturn(Optional.of(new Profile(ownerId, "Owner", "#dfff4f", now)));
    when(users.findById(ownerId)).thenReturn(Optional.of(user));
    when(accounts.findById(ownerId)).thenReturn(Optional.empty());
    when(stripe.createCustomer(user.normalizedEmail, ownerId))
        .thenReturn(new StripeGateway.StripeCustomer("cus_test"));
    when(stripe.createCheckout(eq("cus_test"), eq(ownerId), eq("price_test"), anyString(), anyString(),
        any(Instant.class), anyString())).thenThrow(stripeFailure);

    ApiException exception = assertThrows(ApiException.class, () -> service.createCheckout(ownerId));

    assertEquals(502, exception.status);
    assertEquals("STRIPE_UNAVAILABLE", exception.code);
    verify(accounts).saveAndFlush(argThat(account -> "cus_test".equals(account.stripeCustomerId)));
  }

  @Test void portalUsesStoredCustomerConfiguredPortalAndLeaderboardReturnUrl() throws Exception {
    BillingService portalService = new BillingService(accounts, events, profiles, boards, users, stripe,
        "sk_test_configured", "", "price_premium", PORTAL_CONFIGURATION_ID, "http://localhost:3000");
    BillingAccount account = new BillingAccount(ownerId, now);
    account.stripeCustomerId = "cus_test";
    when(profiles.findByIdForUpdate(ownerId)).thenReturn(Optional.of(new Profile(ownerId, "Owner", "#dfff4f", now)));
    when(accounts.findById(ownerId)).thenReturn(Optional.of(account));
    when(stripe.createPortal("cus_test", PORTAL_CONFIGURATION_ID, "http://localhost:3000/billing/return?billing=returned"))
        .thenReturn(new StripeGateway.StripePortalSession("https://billing.stripe.test/session"));

    assertEquals("https://billing.stripe.test/session", portalService.createPortal(ownerId).url());

    verify(stripe).createPortal("cus_test", PORTAL_CONFIGURATION_ID, "http://localhost:3000/billing/return?billing=returned");
  }

  @Test void stripePlanChangeSessionUsesAnAutomaticCompletionRedirect() {
    String returnUrl = "https://dailytracker.cc/billing/return?billing=returned";
    var params = StripeSdkGateway.subscriptionUpdateParams("cus_test", PORTAL_CONFIGURATION_ID, "sub_test", returnUrl);
    assertEquals("cus_test", params.getCustomer());
    assertEquals(PORTAL_CONFIGURATION_ID, params.getConfiguration());
    assertEquals(returnUrl, params.getReturnUrl());
    var flow = params.getFlowData();
    assertEquals(com.stripe.param.billingportal.SessionCreateParams.FlowData.Type.SUBSCRIPTION_UPDATE, flow.getType());
    assertEquals("sub_test", flow.getSubscriptionUpdate().getSubscription());
    assertEquals(com.stripe.param.billingportal.SessionCreateParams.FlowData.AfterCompletion.Type.REDIRECT,
        flow.getAfterCompletion().getType());
    assertEquals(returnUrl, flow.getAfterCompletion().getRedirect().getReturnUrl());
  }

  @Test void planChangePortalRedirectsToTheProfileAfterCompletion() throws Exception {
    BillingAccount account = new BillingAccount(ownerId, now);
    account.stripeCustomerId = "cus_test";
    account.stripeSubscriptionId = "sub_test";
    when(profiles.findByIdForUpdate(ownerId)).thenReturn(Optional.of(new Profile(ownerId, "Owner", "#dfff4f", now)));
    when(accounts.findById(ownerId)).thenReturn(Optional.of(account));
    when(stripe.createSubscriptionUpdatePortal("cus_test", PORTAL_CONFIGURATION_ID, "sub_test",
        "http://localhost:3000/billing/return?billing=returned"))
        .thenReturn(new StripeGateway.StripePortalSession("https://billing.stripe.test/change-plan"));

    assertEquals("https://billing.stripe.test/change-plan", service.createPlanChangePortal(ownerId).url());
    verify(stripe).createSubscriptionUpdatePortal("cus_test", PORTAL_CONFIGURATION_ID, "sub_test",
        "http://localhost:3000/billing/return?billing=returned");
  }

  @Test void planChangeRequiresAnExistingSubscription() {
    BillingAccount account = new BillingAccount(ownerId, now);
    account.stripeCustomerId = "cus_test";
    when(profiles.findByIdForUpdate(ownerId)).thenReturn(Optional.of(new Profile(ownerId, "Owner", "#dfff4f", now)));
    when(accounts.findById(ownerId)).thenReturn(Optional.of(account));

    ApiException exception = assertThrows(ApiException.class, () -> service.createPlanChangePortal(ownerId));
    assertEquals("BILLING_SUBSCRIPTION_REQUIRED", exception.code);
    verifyNoInteractions(stripe);
  }

  @Test void missingPortalConfigurationReturnsUnavailableWithoutCallingStripe() {
    BillingService unconfiguredPortal = new BillingService(accounts, events, profiles, boards, users, stripe,
        "sk_test_configured", "price_test", "price_premium", "", "http://localhost:3000");

    ApiException exception = assertThrows(ApiException.class, () -> unconfiguredPortal.createPortal(ownerId));

    assertEquals(503, exception.status);
    assertEquals("BILLING_UNAVAILABLE", exception.code);
    verifyNoInteractions(stripe);
  }

  @Test void portalRequiresAnExistingStripeCustomer() {
    when(profiles.findByIdForUpdate(ownerId)).thenReturn(Optional.of(new Profile(ownerId, "Owner", "#dfff4f", now)));
    when(accounts.findById(ownerId)).thenReturn(Optional.of(new BillingAccount(ownerId, now)));

    ApiException exception = assertThrows(ApiException.class, () -> service.createPortal(ownerId));

    assertEquals(409, exception.status);
    assertEquals("BILLING_ACCOUNT_REQUIRED", exception.code);
    verifyNoInteractions(stripe);
  }

  @Test void portalStripeFailureReturnsStructuredBadGateway() throws Exception {
    BillingAccount account = new BillingAccount(ownerId, now);
    account.stripeCustomerId = "cus_test";
    StripeException stripeFailure = mock(StripeException.class);
    when(profiles.findByIdForUpdate(ownerId)).thenReturn(Optional.of(new Profile(ownerId, "Owner", "#dfff4f", now)));
    when(accounts.findById(ownerId)).thenReturn(Optional.of(account));
    when(stripe.createPortal("cus_test", PORTAL_CONFIGURATION_ID, "http://localhost:3000/billing/return?billing=returned"))
        .thenThrow(stripeFailure);

    ApiException exception = assertThrows(ApiException.class, () -> service.createPortal(ownerId));

    assertEquals(502, exception.status);
    assertEquals("STRIPE_UNAVAILABLE", exception.code);
  }

  @Test void subscriptionWebhookUsesCurrentStripeStateInsteadOfTheEventSnapshot() throws Exception {
    Event event = mock(Event.class);
    Subscription eventSubscription = mock(Subscription.class);
    when(event.getId()).thenReturn("evt_subscription_updated");
    when(event.getType()).thenReturn("customer.subscription.updated");
    when(event.getCreated()).thenReturn(now.getEpochSecond());
    when(eventSubscription.getId()).thenReturn("sub_test");
    when(events.insertIfAbsent(eq("evt_subscription_updated"), eq("customer.subscription.updated"),
        eq(now), any(Instant.class))).thenReturn(1);
    when(stripe.retrieveSubscription("sub_test")).thenReturn(new StripeGateway.StripeSubscription(
        "sub_test", "cus_test", "active", false, now.plus(Duration.ofDays(30)),
        "price_premium", Map.of("dailyTrackerUserId", ownerId.toString())));
    when(accounts.findByStripeSubscriptionId("sub_test")).thenReturn(Optional.empty());
    when(accounts.findByStripeCustomerId("cus_test")).thenReturn(Optional.empty());
    when(accounts.findById(ownerId)).thenReturn(Optional.empty());

    service.process(event, eventSubscription);

    verify(accounts).save(argThat(account -> "active".equals(account.subscriptionStatus)
        && "cus_test".equals(account.stripeCustomerId)
        && "sub_test".equals(account.stripeSubscriptionId)
        && "price_premium".equals(account.stripePriceId)
        && account.everSubscribedAt.equals(now)));
  }

  @Test void duplicateWebhookDoesNotCallStripeAgain() {
    Event event = mock(Event.class);
    when(event.getId()).thenReturn("evt_duplicate");
    when(event.getType()).thenReturn("customer.subscription.updated");
    when(event.getCreated()).thenReturn(now.getEpochSecond());
    when(events.insertIfAbsent(eq("evt_duplicate"), eq("customer.subscription.updated"),
        eq(now), any(Instant.class))).thenReturn(0);

    service.process(event, mock(Subscription.class));

    verifyNoInteractions(stripe);
    verifyNoInteractions(accounts);
  }

  @Test void checkoutRetriesUseTheSameKeyAndExpirationWithinAWindow() {
    Instant firstAttempt = Instant.parse("2026-11-04T02:31:05Z");
    Instant retry = Instant.parse("2026-11-04T02:44:08Z");

    BillingService.CheckoutAttempt first = BillingService.checkoutAttempt(ownerId, firstAttempt);
    BillingService.CheckoutAttempt second = BillingService.checkoutAttempt(ownerId, retry);

    assertEquals(first, second);
    assertTrue(first.idempotencyKey().startsWith("daily-tracker-checkout-v3-"));
    assertEquals(Instant.parse("2026-11-04T03:30:00Z"), first.expiresAt());
  }

  @Test void checkoutGetsANewKeyAfterTheRetryWindow() {
    BillingService.CheckoutAttempt first = BillingService.checkoutAttempt(ownerId,
        Instant.parse("2026-11-04T02:44:08Z"));
    BillingService.CheckoutAttempt later = BillingService.checkoutAttempt(ownerId,
        Instant.parse("2026-11-04T03:01:00Z"));

    assertNotEquals(first.idempotencyKey(), later.idempotencyKey());
    assertNotEquals(first.expiresAt(), later.expiresAt());
  }

  private Leaderboard board(BoardCreationBasis basis) {
    return new Leaderboard(ownerId, "Friends", UUID.randomUUID(), basis, now.minusSeconds(100));
  }
}
