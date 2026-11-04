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
  private final BillingAccountRepository accounts = mock(BillingAccountRepository.class);
  private final ProcessedStripeEventRepository events = mock(ProcessedStripeEventRepository.class);
  private final ProfileRepository profiles = mock(ProfileRepository.class);
  private final LeaderboardRepository boards = mock(LeaderboardRepository.class);
  private final UserRepository users = mock(UserRepository.class);
  private final StripeGateway stripe = mock(StripeGateway.class);
  private final BillingService service = new BillingService(accounts, events, profiles, boards, users, stripe,
      "sk_test_configured", "price_test", "http://localhost:3000");
  private final UUID ownerId = UUID.randomUUID();
  private final Instant now = Instant.parse("2026-10-31T12:00:00Z");

  @Test void freeBoardsStayAccessibleWithoutBilling() {
    Leaderboard board = board(BoardCreationBasis.FREE);
    assertTrue(service.isBoardAccessible(board, now));
    verifyNoInteractions(accounts);
  }

  @Test void freeLeaderboardAvailabilityEndsAtFive() {
    when(profiles.findById(ownerId)).thenReturn(Optional.of(new Profile(ownerId, "Owner", "#dfff4f", now)));
    when(boards.countByOwnerIdAndCreationBasis(ownerId, BoardCreationBasis.FREE)).thenReturn(4L, 5L);

    assertTrue(service.status(ownerId).freeLeaderboardAvailable());
    assertFalse(service.status(ownerId).freeLeaderboardAvailable());
  }

  @Test void grandfatheredBoardsLockOnlyAfterAFormerSubscriberLosesAccess() {
    Leaderboard board = board(BoardCreationBasis.GRANDFATHERED);
    when(accounts.findById(ownerId)).thenReturn(Optional.empty());
    assertTrue(service.isBoardAccessible(board, now));

    BillingAccount canceled = new BillingAccount(ownerId, now.minusSeconds(100));
    canceled.everSubscribedAt = now.minusSeconds(50);
    canceled.subscriptionStatus = "canceled";
    when(accounts.findById(ownerId)).thenReturn(Optional.of(canceled));
    assertFalse(service.isBoardAccessible(board, now));
  }

  @Test void subscriptionBoardsHonorTheSevenDayPaymentGracePeriod() {
    Leaderboard board = board(BoardCreationBasis.SUBSCRIPTION);
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

    assertFalse(service.hasUnlimitedLeaderboards(ownerId, now));
  }

  @Test void accountWithoutAStatusCanCreateCheckout() throws Exception {
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

    assertEquals("https://checkout.stripe.test/session", service.createCheckout(ownerId).url());
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
        Map.of("dailyTrackerUserId", ownerId.toString())));
    when(accounts.findByStripeSubscriptionId("sub_test")).thenReturn(Optional.empty());
    when(accounts.findByStripeCustomerId("cus_test")).thenReturn(Optional.empty());
    when(accounts.findById(ownerId)).thenReturn(Optional.empty());

    service.process(event, eventSubscription);

    verify(accounts).save(argThat(account -> "active".equals(account.subscriptionStatus)
        && "cus_test".equals(account.stripeCustomerId)
        && "sub_test".equals(account.stripeSubscriptionId)
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

  private Leaderboard board(BoardCreationBasis basis) {
    return new Leaderboard(ownerId, "Friends", UUID.randomUUID(), basis, now.minusSeconds(100));
  }
}
