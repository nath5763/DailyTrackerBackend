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
  static final int FREE_LEADERBOARD_LIMIT = 5;
  private static final long CHECKOUT_WINDOW_SECONDS = Duration.ofMinutes(30).toSeconds();
  private static final String CHECKOUT_IDEMPOTENCY_VERSION = "v2";
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
  private final String portalConfigurationId;
  private final String baseUrl;

  BillingService(BillingAccountRepository accounts, ProcessedStripeEventRepository events,
                 ProfileRepository profiles, LeaderboardRepository boards, UserRepository users,
                 StripeGateway stripe,
                 @Value("${app.stripe.secret-key:}") String secretKey,
                 @Value("${app.stripe.price-id:}") String priceId,
                 @Value("${app.stripe.portal-configuration-id:}") String portalConfigurationId,
                 @Value("${app.public-base-url}") String baseUrl) {
    this.accounts = accounts; this.events = events; this.profiles = profiles; this.boards = boards; this.users = users;
    this.stripe = stripe;
    this.secretKey = secretKey; this.priceId = priceId; this.portalConfigurationId = portalConfigurationId;
    this.baseUrl = baseUrl.replaceAll("/$", "");
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

  @Transactional(noRollbackFor = ApiException.class)
  UrlResponse createCheckout(UUID userId) {
    requireCheckoutConfiguration();
    profiles.findByIdForUpdate(userId).orElseThrow(() -> new ApiException(409, "PROFILE_REQUIRED", "Finish setting up your profile."));
    UserAccount user = users.findById(userId).orElseThrow(() -> new ApiException(401, "UNAUTHORIZED", "Sign in first."));
    Instant now = Instant.now();
    BillingAccount account = accounts.findById(userId).orElseGet(() -> new BillingAccount(userId, now));
    if (hasManagedSubscription(account.subscriptionStatus)) {
      throw new ApiException(409, "BILLING_SUBSCRIPTION_REQUIRES_MANAGEMENT",
          "Manage your existing subscription before starting a new checkout.");
    }
    if (account.pendingCheckoutExpiresAt != null && account.pendingCheckoutExpiresAt.isAfter(now) &&
        account.pendingCheckoutUrl != null) return new UrlResponse(account.pendingCheckoutUrl);
    try {
      if (account.stripeCustomerId == null) {
        account.stripeCustomerId = stripe.createCustomer(user.normalizedEmail, userId).id();
        account.updatedAt = now;
        accounts.saveAndFlush(account);
        log.info("Created Stripe customer mapping for userId={}", userId);
      }
      CheckoutAttempt attempt = checkoutAttempt(userId, now);
      StripeGateway.StripeCheckoutSession session = stripe.createCheckout(account.stripeCustomerId, userId, priceId,
          baseUrl + "/leaderboards?checkout=success", baseUrl + "/leaderboards?checkout=cancelled",
          attempt.expiresAt(), attempt.idempotencyKey());
      account.pendingCheckoutSessionId = session.id();
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

  @Transactional
  UrlResponse createPortal(UUID userId) {
    requirePortalConfiguration();
    profiles.findByIdForUpdate(userId).orElseThrow(() -> new ApiException(409, "PROFILE_REQUIRED", "Finish setting up your profile."));
    BillingAccount account = accounts.findById(userId)
        .filter(value -> value.stripeCustomerId != null)
        .orElseThrow(() -> new ApiException(409, "BILLING_ACCOUNT_REQUIRED", "Start a subscription before opening billing settings."));
    try {
      StripeGateway.StripePortalSession session = stripe.createPortal(account.stripeCustomerId,
          portalConfigurationId, baseUrl + "/leaderboards");
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

  private boolean isEntitledStatus(String status) {
    return status != null && ENTITLED_STATUSES.contains(status);
  }

  private boolean hasManagedSubscription(String status) {
    return status != null && MANAGED_SUBSCRIPTION_STATUSES.contains(status);
  }

  static CheckoutAttempt checkoutAttempt(UUID userId, Instant now) {
    long window = now.getEpochSecond() / CHECKOUT_WINDOW_SECONDS;
    Instant expiresAt = Instant.ofEpochSecond((window + 2) * CHECKOUT_WINDOW_SECONDS);
    String key = "daily-tracker-checkout-" + CHECKOUT_IDEMPOTENCY_VERSION + "-" + userId + "-" + window;
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

  private void requireCheckoutConfiguration() {
    if (secretKey.isBlank() || priceId.isBlank()) {
      throw new ApiException(503, "BILLING_UNAVAILABLE", "Billing has not been configured yet.");
    }
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
