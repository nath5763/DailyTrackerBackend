package com.dailygames;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.*;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

import static com.dailygames.ApiModels.*;

@Service
class BillingService {
  static final int FREE_LEADERBOARD_LIMIT = 5;
  private static final Set<String> ENTITLED_STATUSES = Set.of("active", "trialing");
  private final BillingAccountRepository accounts;
  private final ProcessedStripeEventRepository events;
  private final ProfileRepository profiles;
  private final LeaderboardRepository boards;
  private final UserRepository users;
  private final StripeClient stripe;
  private final String secretKey;
  private final String priceId;
  private final String baseUrl;

  BillingService(BillingAccountRepository accounts, ProcessedStripeEventRepository events,
                 ProfileRepository profiles, LeaderboardRepository boards, UserRepository users,
                 @Value("${app.stripe.secret-key:}") String secretKey,
                 @Value("${app.stripe.price-id:}") String priceId,
                 @Value("${app.public-base-url}") String baseUrl) {
    this.accounts = accounts; this.events = events; this.profiles = profiles; this.boards = boards; this.users = users;
    this.secretKey = secretKey; this.priceId = priceId; this.baseUrl = baseUrl.replaceAll("/$", "");
    stripe = new StripeClient(secretKey.isBlank() ? "sk_test_not_configured" : secretKey);
  }

  BillingStatusResponse status(UUID userId) {
    profiles.findById(userId).orElseThrow(() -> new ApiException(409, "PROFILE_REQUIRED", "Finish setting up your profile."));
    BillingAccount account = accounts.findById(userId).orElse(null);
    return new BillingStatusResponse(isEntitled(account, Instant.now()),
        boards.countByOwnerIdAndCreationBasis(userId, BoardCreationBasis.FREE) < FREE_LEADERBOARD_LIMIT,
        account == null ? null : account.subscriptionStatus,
        account != null && account.cancelAtPeriodEnd,
        account == null ? null : account.currentPeriodEnd,
        account == null ? null : account.graceUntil);
  }

  boolean hasUnlimitedLeaderboards(UUID userId, Instant now) {
    return accounts.findById(userId).map(account -> isEntitled(account, now)).orElse(false);
  }

  boolean isBoardAccessible(Leaderboard board, Instant now) {
    if (board.creationBasis == BoardCreationBasis.FREE) return true;
    BillingAccount account = accounts.findById(board.ownerId).orElse(null);
    if (board.creationBasis == BoardCreationBasis.GRANDFATHERED &&
        (account == null || account.everSubscribedAt == null)) return true;
    return isEntitled(account, now);
  }

  @Transactional
  UrlResponse createCheckout(UUID userId) {
    requireStripeConfiguration();
    profiles.findByIdForUpdate(userId).orElseThrow(() -> new ApiException(409, "PROFILE_REQUIRED", "Finish setting up your profile."));
    UserAccount user = users.findById(userId).orElseThrow(() -> new ApiException(401, "UNAUTHORIZED", "Sign in first."));
    Instant now = Instant.now();
    BillingAccount account = accounts.findById(userId).orElseGet(() -> new BillingAccount(userId, now));
    if (isEntitled(account, now)) throw new ApiException(409, "ALREADY_SUBSCRIBED", "Your unlimited leaderboard subscription is already active.");
    if (account.pendingCheckoutExpiresAt != null && account.pendingCheckoutExpiresAt.isAfter(now) &&
        account.pendingCheckoutUrl != null) return new UrlResponse(account.pendingCheckoutUrl);
    try {
      if (account.stripeCustomerId == null) {
        CustomerCreateParams customerParams = CustomerCreateParams.builder()
            .setEmail(user.normalizedEmail)
            .putMetadata("dailyTrackerUserId", userId.toString())
            .build();
        RequestOptions options = RequestOptions.builder().setIdempotencyKey("daily-tracker-customer-" + userId).build();
        Customer customer = stripe.v1().customers().create(customerParams, options);
        account.stripeCustomerId = customer.getId();
      }
      long expiresAt = now.plus(Duration.ofMinutes(30)).getEpochSecond();
      SessionCreateParams params = SessionCreateParams.builder()
          .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
          .setCustomer(account.stripeCustomerId)
          .setClientReferenceId(userId.toString())
          .setSuccessUrl(baseUrl + "/leaderboards?checkout=success")
          .setCancelUrl(baseUrl + "/leaderboards?checkout=cancelled")
          .setExpiresAt(expiresAt)
          .addLineItem(SessionCreateParams.LineItem.builder().setPrice(priceId).setQuantity(1L).build())
          .setSubscriptionData(SessionCreateParams.SubscriptionData.builder()
              .putMetadata("dailyTrackerUserId", userId.toString()).build())
          .build();
      RequestOptions options = RequestOptions.builder()
          .setIdempotencyKey("daily-tracker-checkout-" + userId + "-" + (now.getEpochSecond() / 1800)).build();
      Session session = stripe.v1().checkout().sessions().create(params, options);
      account.pendingCheckoutSessionId = session.getId();
      account.pendingCheckoutUrl = session.getUrl();
      account.pendingCheckoutExpiresAt = Instant.ofEpochSecond(session.getExpiresAt());
      account.updatedAt = now;
      accounts.save(account);
      return new UrlResponse(session.getUrl());
    } catch (StripeException exception) {
      throw stripeUnavailable(exception);
    }
  }

  @Transactional
  UrlResponse createPortal(UUID userId) {
    requireStripeConfiguration();
    profiles.findByIdForUpdate(userId).orElseThrow(() -> new ApiException(409, "PROFILE_REQUIRED", "Finish setting up your profile."));
    BillingAccount account = accounts.findById(userId)
        .filter(value -> value.stripeCustomerId != null)
        .orElseThrow(() -> new ApiException(409, "BILLING_ACCOUNT_REQUIRED", "Start a subscription before opening billing settings."));
    try {
      com.stripe.param.billingportal.SessionCreateParams params = com.stripe.param.billingportal.SessionCreateParams.builder()
          .setCustomer(account.stripeCustomerId)
          .setReturnUrl(baseUrl + "/leaderboards")
          .build();
      com.stripe.model.billingportal.Session session = stripe.v1().billingPortal().sessions().create(params);
      return new UrlResponse(session.getUrl());
    } catch (StripeException exception) {
      throw stripeUnavailable(exception);
    }
  }

  @Transactional
  void process(Event event, StripeObject object) {
    Instant eventCreated = Instant.ofEpochSecond(event.getCreated());
    if (events.insertIfAbsent(event.getId(), event.getType(), eventCreated, Instant.now()) == 0) return;
    switch (event.getType()) {
      case "checkout.session.completed" -> applyCheckout((Session) object, eventCreated);
      case "customer.subscription.created", "customer.subscription.updated", "customer.subscription.deleted" ->
          applySubscription((Subscription) object, eventCreated);
      case "invoice.paid", "invoice.payment_succeeded" -> applyInvoice((Invoice) object, eventCreated, true);
      case "invoice.payment_failed" -> applyInvoice((Invoice) object, eventCreated, false);
      default -> { }
    }
  }

  private void applyCheckout(Session session, Instant eventCreated) {
    UUID userId = parseUserId(session.getClientReferenceId());
    BillingAccount account = accounts.findById(userId).orElseGet(() -> new BillingAccount(userId, Instant.now()));
    if (stale(account, eventCreated)) return;
    account.stripeCustomerId = session.getCustomer();
    account.stripeSubscriptionId = session.getSubscription();
    account.pendingCheckoutSessionId = null;
    account.pendingCheckoutUrl = null;
    account.pendingCheckoutExpiresAt = null;
    account.lastEventCreatedAt = eventCreated;
    account.updatedAt = Instant.now();
    accounts.save(account);
  }

  private void applySubscription(Subscription subscription, Instant eventCreated) {
    BillingAccount account = findAccount(subscription.getId(), subscription.getCustomer(), subscription.getMetadata());
    if (stale(account, eventCreated)) return;
    account.stripeCustomerId = subscription.getCustomer();
    account.stripeSubscriptionId = subscription.getId();
    account.subscriptionStatus = subscription.getStatus();
    account.cancelAtPeriodEnd = Boolean.TRUE.equals(subscription.getCancelAtPeriodEnd());
    account.currentPeriodEnd = subscription.getItems().getData().stream()
        .map(SubscriptionItem::getCurrentPeriodEnd).filter(Objects::nonNull).max(Long::compareTo)
        .map(Instant::ofEpochSecond).orElse(null);
    if (ENTITLED_STATUSES.contains(account.subscriptionStatus)) {
      if (account.everSubscribedAt == null) account.everSubscribedAt = eventCreated;
      account.graceUntil = null;
    } else if ("past_due".equals(account.subscriptionStatus) && account.graceUntil == null) {
      account.graceUntil = Instant.now().plus(Duration.ofDays(7));
    }
    account.pendingCheckoutSessionId = null;
    account.pendingCheckoutUrl = null;
    account.pendingCheckoutExpiresAt = null;
    account.lastEventCreatedAt = eventCreated;
    account.updatedAt = Instant.now();
    accounts.save(account);
  }

  private void applyInvoice(Invoice invoice, Instant eventCreated, boolean paid) {
    BillingAccount account = accounts.findByStripeCustomerId(invoice.getCustomer()).orElse(null);
    if (account == null || stale(account, eventCreated)) return;
    if (paid) {
      if (ENTITLED_STATUSES.contains(account.subscriptionStatus)) account.graceUntil = null;
    } else if (account.graceUntil == null) {
      account.graceUntil = Instant.now().plus(Duration.ofDays(7));
    }
    account.lastEventCreatedAt = eventCreated;
    account.updatedAt = Instant.now();
    accounts.save(account);
  }

  private BillingAccount findAccount(String subscriptionId, String customerId, Map<String, String> metadata) {
    Optional<BillingAccount> existing = accounts.findByStripeSubscriptionId(subscriptionId);
    if (existing.isEmpty() && customerId != null) existing = accounts.findByStripeCustomerId(customerId);
    if (existing.isPresent()) return existing.get();
    UUID userId = parseUserId(metadata == null ? null : metadata.get("dailyTrackerUserId"));
    return accounts.findById(userId).orElseGet(() -> new BillingAccount(userId, Instant.now()));
  }

  private boolean isEntitled(BillingAccount account, Instant now) {
    if (account == null) return false;
    if (ENTITLED_STATUSES.contains(account.subscriptionStatus)) return true;
    return "past_due".equals(account.subscriptionStatus) && account.graceUntil != null && account.graceUntil.isAfter(now);
  }

  private boolean stale(BillingAccount account, Instant eventCreated) {
    return account.lastEventCreatedAt != null && eventCreated.isBefore(account.lastEventCreatedAt);
  }

  private UUID parseUserId(String value) {
    try { return UUID.fromString(value); }
    catch (RuntimeException exception) { throw new ApiException(400, "INVALID_STRIPE_EVENT", "Stripe event is missing its Daily Tracker account reference."); }
  }

  private void requireStripeConfiguration() {
    if (secretKey.isBlank() || priceId.isBlank()) {
      throw new ApiException(503, "BILLING_UNAVAILABLE", "Billing has not been configured yet.");
    }
  }

  private ApiException stripeUnavailable(StripeException exception) {
    return new ApiException(502, "STRIPE_UNAVAILABLE", "Stripe could not complete the billing request. Try again shortly.");
  }
}
