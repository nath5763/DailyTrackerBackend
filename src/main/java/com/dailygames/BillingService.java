package com.dailygames;

import com.stripe.exception.StripeException;
import com.stripe.model.*;
import com.stripe.model.checkout.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

import static com.dailygames.ApiModels.*;

@Service
class BillingService {
  static final int SINGLE_GAME_LIMIT = 5;
  private static final long CHECKOUT_WINDOW_SECONDS = Duration.ofMinutes(30).toSeconds();
  private static final String CHECKOUT_IDEMPOTENCY_VERSION = "v3";
  private static final Set<String> ENTITLED_STATUSES = Set.of("active", "trialing");
  private static final Set<String> MANAGED_SUBSCRIPTION_STATUSES =
      Set.of("active", "trialing", "past_due", "unpaid", "paused", "incomplete");
  private static final Set<String> SUPPORTED_EVENTS = Set.of("checkout.session.completed",
      "customer.subscription.created", "customer.subscription.updated", "customer.subscription.deleted",
      "invoice.paid", "invoice.payment_succeeded", "invoice.payment_failed");
  private static final Logger log = LoggerFactory.getLogger(BillingService.class);
  private final BillingAccountRepository accounts;
  private final ProcessedStripeEventRepository events;
  private final ProfileRepository profiles;
  private final LeaderboardRepository boards;
  private final UserRepository users;
  private final StripeGateway stripe;
  private final String secretKey;
  private final String priceId;
  private final String premiumPriceId;
  private final String portalConfigurationId;
  private final String baseUrl;

  BillingService(BillingAccountRepository accounts, ProcessedStripeEventRepository events,
                 ProfileRepository profiles, LeaderboardRepository boards, UserRepository users,
                 StripeGateway stripe,
                 @Value("${app.stripe.secret-key:}") String secretKey,
                 @Value("${app.stripe.price-id:}") String priceId,
                 @Value("${app.stripe.premium-price-id:}") String premiumPriceId,
                 @Value("${app.stripe.portal-configuration-id:}") String portalConfigurationId,
                 @Value("${app.public-base-url}") String baseUrl) {
    this.accounts = accounts; this.events = events; this.profiles = profiles; this.boards = boards; this.users = users;
    this.stripe = stripe;
    this.secretKey = secretKey; this.priceId = priceId; this.premiumPriceId = premiumPriceId;
    this.portalConfigurationId = portalConfigurationId;
    this.baseUrl = baseUrl.replaceAll("/$", "");
  }

  BillingStatusResponse status(UUID userId) {
    profiles.findById(userId).orElseThrow(() -> new ApiException(409, "PROFILE_REQUIRED", "Finish setting up your profile."));
    BillingAccount account = accounts.findById(userId).orElse(null);
    BillingTier tier = tier(account, Instant.now());
    return new BillingStatusResponse(tier,
        boards.countByOwnerIdAndBoardType(userId, BoardType.SINGLE_GAME), SINGLE_GAME_LIMIT,
        boards.countByOwnerIdAndBoardType(userId, BoardType.MULTI_GAME), allGameLimit(tier),
        account == null ? null : account.subscriptionStatus,
        account != null && account.cancelAtPeriodEnd,
        account == null ? null : account.currentPeriodEnd,
        account == null ? null : account.graceUntil);
  }

  int allGameLimit(UUID userId, Instant now) {
    return accounts.findById(userId).map(account -> allGameLimit(tier(account, now))).orElse(0);
  }

  boolean isBoardAccessible(Leaderboard board, Instant now) {
    return board.boardType != BoardType.MULTI_GAME || allGameLimit(board.ownerId, now) > 0;
  }

  @Transactional(noRollbackFor = ApiException.class)
  UrlResponse createCheckout(UUID userId, BillingTier tier) {
    String selectedPriceId = checkoutPrice(tier);
    profiles.findByIdForUpdate(userId).orElseThrow(() -> new ApiException(409, "PROFILE_REQUIRED", "Finish setting up your profile."));
    UserAccount user = users.findById(userId).orElseThrow(() -> new ApiException(401, "UNAUTHORIZED", "Sign in first."));
    Instant now = Instant.now();
    BillingAccount account = accounts.findById(userId).orElseGet(() -> new BillingAccount(userId, now));
    if (hasManagedSubscription(account.subscriptionStatus)) {
      throw new ApiException(409, "BILLING_SUBSCRIPTION_REQUIRES_MANAGEMENT",
          "Manage your existing subscription before starting a new checkout.");
    }
    if (account.pendingCheckoutExpiresAt != null && account.pendingCheckoutExpiresAt.isAfter(now) &&
        selectedPriceId.equals(account.pendingCheckoutPriceId) && account.pendingCheckoutUrl != null)
      return new UrlResponse(account.pendingCheckoutUrl);
    try {
      if (account.stripeCustomerId == null) {
        account.stripeCustomerId = stripe.createCustomer(user.normalizedEmail, userId).id();
        account.updatedAt = now;
        accounts.saveAndFlush(account);
        log.info("Created Stripe customer mapping for userId={}", userId);
      }
      CheckoutAttempt attempt = checkoutAttempt(userId, tier, now);
      StripeGateway.StripeCheckoutSession session = stripe.createCheckout(account.stripeCustomerId, userId, selectedPriceId,
          baseUrl + "/leaderboards?checkout=success", baseUrl + "/leaderboards?checkout=cancelled",
          attempt.expiresAt(), attempt.idempotencyKey());
      account.pendingCheckoutSessionId = session.id();
      account.pendingCheckoutPriceId = selectedPriceId;
      account.pendingCheckoutUrl = session.url();
      account.pendingCheckoutExpiresAt = session.expiresAt();
      account.updatedAt = now;
      accounts.save(account);
      log.info("Created Stripe checkout session for userId={} sessionId={}", userId, session.id());
      return new UrlResponse(session.url());
    } catch (StripeException exception) {
      throw stripeUnavailable("checkout", userId, exception);
    }
  }

  UrlResponse createCheckout(UUID userId) { return createCheckout(userId, BillingTier.STANDARD); }

  @Transactional
  UrlResponse createPortal(UUID userId) {
    requirePortalConfiguration();
    profiles.findByIdForUpdate(userId).orElseThrow(() -> new ApiException(409, "PROFILE_REQUIRED", "Finish setting up your profile."));
    BillingAccount account = accounts.findById(userId)
        .filter(value -> value.stripeCustomerId != null)
        .orElseThrow(() -> new ApiException(409, "BILLING_ACCOUNT_REQUIRED", "Start a subscription before opening billing settings."));
    try {
      StripeGateway.StripePortalSession session = stripe.createPortal(account.stripeCustomerId,
          portalConfigurationId, baseUrl + "/leaderboards?billing=returned");
      log.info("Created Stripe portal session for userId={}", userId);
      return new UrlResponse(session.url());
    } catch (StripeException exception) {
      throw stripeUnavailable("portal", userId, exception);
    }
  }

  @Transactional
  void process(Event event, StripeObject object) {
    Instant eventCreated = Instant.ofEpochSecond(event.getCreated());
    if (events.insertIfAbsent(event.getId(), event.getType(), eventCreated, Instant.now()) == 0) {
      log.info("Ignored duplicate Stripe event eventId={} eventType={}", event.getId(), event.getType());
      return;
    }
    try {
      switch (event.getType()) {
        case "checkout.session.completed" -> applyCheckout((Session) object, eventCreated);
        case "customer.subscription.created", "customer.subscription.updated", "customer.subscription.deleted" -> {
          Subscription subscription = (Subscription) object;
          applySubscription(stripe.retrieveSubscription(subscription.getId()), eventCreated);
        }
        case "invoice.paid", "invoice.payment_succeeded" -> applyInvoice((Invoice) object, eventCreated, true);
        case "invoice.payment_failed" -> applyInvoice((Invoice) object, eventCreated, false);
        default -> { }
      }
      log.info("Processed Stripe event eventId={} eventType={}", event.getId(), event.getType());
    } catch (StripeException exception) {
      throw stripeUnavailable("webhook-reconciliation", null, exception);
    }
  }

  boolean supports(String eventType) { return SUPPORTED_EVENTS.contains(eventType); }

  private void applyCheckout(Session session, Instant eventCreated) {
    UUID userId = parseUserId(session.getClientReferenceId());
    BillingAccount account = accounts.findById(userId).orElseGet(() -> new BillingAccount(userId, Instant.now()));
    if (stale(account, eventCreated)) return;
    account.stripeCustomerId = session.getCustomer();
    account.stripeSubscriptionId = session.getSubscription();
    account.pendingCheckoutSessionId = null;
    account.pendingCheckoutPriceId = null;
    account.pendingCheckoutUrl = null;
    account.pendingCheckoutExpiresAt = null;
    account.lastEventCreatedAt = eventCreated;
    account.updatedAt = Instant.now();
    accounts.save(account);
  }

  private void applySubscription(StripeGateway.StripeSubscription subscription, Instant eventCreated) {
    BillingAccount account = findAccount(subscription.id(), subscription.customerId(), subscription.metadata());
    if (stale(account, eventCreated)) return;
    account.stripeCustomerId = subscription.customerId();
    account.stripeSubscriptionId = subscription.id();
    account.subscriptionStatus = subscription.status();
    account.stripePriceId = subscription.priceId();
    account.cancelAtPeriodEnd = subscription.cancelAtPeriodEnd();
    account.currentPeriodEnd = subscription.currentPeriodEnd();
    if (isEntitledStatus(account.subscriptionStatus)) {
      if (account.everSubscribedAt == null) account.everSubscribedAt = eventCreated;
      account.graceUntil = null;
    } else if ("past_due".equals(account.subscriptionStatus) && account.graceUntil == null) {
      account.graceUntil = Instant.now().plus(Duration.ofDays(7));
    } else if (!"past_due".equals(account.subscriptionStatus)) {
      account.graceUntil = null;
    }
    account.pendingCheckoutSessionId = null;
    account.pendingCheckoutPriceId = null;
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
      if (isEntitledStatus(account.subscriptionStatus)) account.graceUntil = null;
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
    if (isEntitledStatus(account.subscriptionStatus)) return true;
    return "past_due".equals(account.subscriptionStatus) && account.graceUntil != null && account.graceUntil.isAfter(now);
  }

  private BillingTier tier(BillingAccount account, Instant now) {
    if (!isEntitled(account, now)) return BillingTier.FREE;
    // Rows written before Price reconciliation represent the original $2 subscription.
    if (account.stripePriceId == null || (!priceId.isBlank() && account.stripePriceId.equals(priceId)))
      return BillingTier.STANDARD;
    if (account.stripePriceId.equals(premiumPriceId) && !premiumPriceId.isBlank()) return BillingTier.PREMIUM;
    return BillingTier.FREE;
  }

  private int allGameLimit(BillingTier tier) {
    return switch (tier) { case FREE -> 0; case STANDARD -> 1; case PREMIUM -> 5; };
  }

  private boolean isEntitledStatus(String status) {
    return status != null && ENTITLED_STATUSES.contains(status);
  }

  private boolean hasManagedSubscription(String status) {
    return status != null && MANAGED_SUBSCRIPTION_STATUSES.contains(status);
  }

  static CheckoutAttempt checkoutAttempt(UUID userId, Instant now) {
    return checkoutAttempt(userId, BillingTier.STANDARD, now);
  }

  static CheckoutAttempt checkoutAttempt(UUID userId, BillingTier tier, Instant now) {
    long window = now.getEpochSecond() / CHECKOUT_WINDOW_SECONDS;
    Instant expiresAt = Instant.ofEpochSecond((window + 2) * CHECKOUT_WINDOW_SECONDS);
    String key = "daily-tracker-checkout-" + CHECKOUT_IDEMPOTENCY_VERSION + "-" + userId + "-" + tier + "-" + window;
    return new CheckoutAttempt(key, expiresAt);
  }

  record CheckoutAttempt(String idempotencyKey, Instant expiresAt) {}

  private boolean stale(BillingAccount account, Instant eventCreated) {
    return account.lastEventCreatedAt != null && eventCreated.isBefore(account.lastEventCreatedAt);
  }

  private UUID parseUserId(String value) {
    try { return UUID.fromString(value); }
    catch (RuntimeException exception) { throw new ApiException(400, "INVALID_STRIPE_EVENT", "Stripe event is missing its Daily Tracker account reference."); }
  }

  private String checkoutPrice(BillingTier tier) {
    if (tier == null || tier == BillingTier.FREE) {
      throw new ApiException(400, "INVALID_BILLING_TIER", "Choose the $2 or $5 monthly tier.");
    }
    String selected = tier == BillingTier.PREMIUM ? premiumPriceId : priceId;
    if (secretKey.isBlank() || selected.isBlank()) {
      throw new ApiException(503, "BILLING_UNAVAILABLE", "Billing has not been configured yet.");
    }
    return selected;
  }

  private void requirePortalConfiguration() {
    if (secretKey.isBlank() || portalConfigurationId.isBlank()) {
      throw new ApiException(503, "BILLING_UNAVAILABLE", "Billing has not been configured yet.");
    }
  }

  private ApiException stripeUnavailable(String operation, UUID userId, StripeException exception) {
    log.error("Stripe request failed operation={} userId={} exceptionType={} requestId={} statusCode={} message={}",
        operation, userId, exception.getClass().getSimpleName(), exception.getRequestId(),
        exception.getStatusCode(), exception.getMessage(), exception);
    return new ApiException(502, "STRIPE_UNAVAILABLE", "Stripe could not complete the billing request. Try again shortly.");
  }
}
