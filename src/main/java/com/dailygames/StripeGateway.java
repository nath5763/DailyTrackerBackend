package com.dailygames;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

interface StripeGateway {
  StripeCustomer createCustomer(String email, UUID userId) throws StripeException;
  StripeCheckoutSession createCheckout(String customerId, UUID userId, String priceId, String successUrl,
                                       String cancelUrl, Instant expiresAt, String idempotencyKey) throws StripeException;
  StripePortalSession createPortal(String customerId, String configurationId, String returnUrl) throws StripeException;
  StripeSubscription retrieveSubscription(String subscriptionId) throws StripeException;

  record StripeCustomer(String id) {}
  record StripeCheckoutSession(String id, String url, Instant expiresAt) {}
  record StripePortalSession(String url) {}
  record StripeSubscription(String id, String customerId, String status, boolean cancelAtPeriodEnd,
                            Instant currentPeriodEnd, String priceId, Map<String, String> metadata) {}
}

@Component
class StripeSdkGateway implements StripeGateway {
  private final StripeClient stripe;

  StripeSdkGateway(@Value("${app.stripe.secret-key:}") String secretKey,
                   @Value("${app.stripe.connect-timeout-ms:5000}") int connectTimeoutMs,
                   @Value("${app.stripe.read-timeout-ms:20000}") int readTimeoutMs) {
    stripe = StripeClient.builder()
        .setApiKey(secretKey.isBlank() ? "sk_test_not_configured" : secretKey)
        .setConnectTimeout(connectTimeoutMs)
        .setReadTimeout(readTimeoutMs)
        .setMaxNetworkRetries(0)
        .build();
  }

  @Override
  public StripeCustomer createCustomer(String email, UUID userId) throws StripeException {
    CustomerCreateParams params = CustomerCreateParams.builder()
        .setEmail(email)
        .putMetadata("dailyTrackerUserId", userId.toString())
        .build();
    RequestOptions options = RequestOptions.builder()
        .setIdempotencyKey("daily-tracker-customer-" + userId)
        .build();
    Customer customer = stripe.v1().customers().create(params, options);
    return new StripeCustomer(customer.getId());
  }

  @Override
  public StripeCheckoutSession createCheckout(String customerId, UUID userId, String priceId, String successUrl,
                                              String cancelUrl, Instant expiresAt, String idempotencyKey)
      throws StripeException {
    SessionCreateParams params = SessionCreateParams.builder()
        .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
        .setCustomer(customerId)
        .setClientReferenceId(userId.toString())
        .setSuccessUrl(successUrl)
        .setCancelUrl(cancelUrl)
        .setExpiresAt(expiresAt.getEpochSecond())
        .addLineItem(SessionCreateParams.LineItem.builder().setPrice(priceId).setQuantity(1L).build())
        .setSubscriptionData(SessionCreateParams.SubscriptionData.builder()
            .putMetadata("dailyTrackerUserId", userId.toString()).build())
        .build();
    RequestOptions options = RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
    Session session = stripe.v1().checkout().sessions().create(params, options);
    return new StripeCheckoutSession(session.getId(), session.getUrl(), Instant.ofEpochSecond(session.getExpiresAt()));
  }

  @Override
  public StripePortalSession createPortal(String customerId, String configurationId, String returnUrl)
      throws StripeException {
    com.stripe.param.billingportal.SessionCreateParams params =
        com.stripe.param.billingportal.SessionCreateParams.builder()
            .setCustomer(customerId)
            .setConfiguration(configurationId)
            .setReturnUrl(returnUrl)
            .build();
    com.stripe.model.billingportal.Session session = stripe.v1().billingPortal().sessions().create(params);
    return new StripePortalSession(session.getUrl());
  }

  @Override
  public StripeSubscription retrieveSubscription(String subscriptionId) throws StripeException {
    Subscription subscription = stripe.v1().subscriptions().retrieve(subscriptionId);
    Instant periodEnd = subscription.getItems().getData().stream()
        .map(SubscriptionItem::getCurrentPeriodEnd)
        .filter(Objects::nonNull)
        .max(Long::compareTo)
        .map(Instant::ofEpochSecond)
        .orElse(null);
    String priceId = subscription.getItems().getData().size() == 1 &&
        subscription.getItems().getData().getFirst().getPrice() != null
        ? subscription.getItems().getData().getFirst().getPrice().getId() : "";
    return new StripeSubscription(subscription.getId(), subscription.getCustomer(), subscription.getStatus(),
        Boolean.TRUE.equals(subscription.getCancelAtPeriodEnd()), periodEnd, priceId, subscription.getMetadata());
  }
}
