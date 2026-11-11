package com.dailygames;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

@Service
@ConditionalOnProperty(name = "app.email-provider", havingValue = "brevo", matchIfMissing = true)
class BrevoEmailClient implements EmailSender {
  private static final Logger LOGGER = LoggerFactory.getLogger(BrevoEmailClient.class);
  private static final String SEND_EMAIL_PATH = "/v3/smtp/email";

  private final RestClient client;
  private final String senderEmail;
  private final String senderName;

  BrevoEmailClient(@Value("${app.brevo.api-url}") String apiUrl,
                   @Value("${app.brevo.api-key}") String apiKey,
                   @Value("${app.brevo.sender-email}") String senderEmail,
                   @Value("${app.brevo.sender-name}") String senderName) {
    if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("BREVO_API_KEY must be configured.");
    if (senderEmail == null || senderEmail.isBlank()) throw new IllegalStateException("BREVO_SENDER_EMAIL must be configured.");

    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(Duration.ofSeconds(10));

    this.client = RestClient.builder().baseUrl(apiUrl.replaceAll("/$", ""))
        .requestFactory(requestFactory)
        .defaultHeader("api-key", apiKey)
        .defaultHeader("accept", MediaType.APPLICATION_JSON_VALUE)
        .defaultHeader("content-type", MediaType.APPLICATION_JSON_VALUE)
        .build();
    this.senderEmail = senderEmail;
    this.senderName = senderName;
  }

  public void sendVerification(String recipientEmail, String verificationLink, int linkHours) {
    send(recipientEmail, "Verify your Daily Club email",
        "<p>Verify your email to finish creating your Daily Club account:</p><p><a href=\"" + verificationLink
            + "\">Verify email address</a></p><p>This link expires in " + linkHours
            + " hours and can be used once.</p>", "email-verification");
  }

  public void sendPasswordReset(String recipientEmail, String resetLink, int linkMinutes) {
    send(recipientEmail, "Reset your Daily Club password",
        "<p>Use this link to set a new Daily Club password:</p><p><a href=\"" + resetLink
            + "\">Reset password</a></p><p>This link expires in " + linkMinutes
            + " minutes and can be used once. If you did not request this, you can ignore this email.</p>",
        "password-reset");
  }

  private void send(String recipientEmail, String subject, String html, String tag) {
    EmailRequest request = new EmailRequest(new Sender(senderEmail, senderName),
        List.of(new Recipient(recipientEmail)), subject, html, List.of(tag));

    try {
      client.post().uri(SEND_EMAIL_PATH).body(request).retrieve().toBodilessEntity();
    } catch (RestClientResponseException exception) {
      LOGGER.warn("Brevo rejected a transactional email request with status {}.", exception.getStatusCode().value());
      throw unavailable();
    } catch (RestClientException exception) {
      LOGGER.warn("Brevo transactional email request failed: {}.", exception.getClass().getSimpleName());
      throw unavailable();
    }
  }

  private ApiException unavailable() {
    return new ApiException(503, "EMAIL_UNAVAILABLE", "Account email is temporarily unavailable. Please try again.");
  }

  private record Sender(String email, String name) {}
  private record Recipient(String email) {}
  private record EmailRequest(Sender sender, List<Recipient> to, String subject, String htmlContent, List<String> tags) {}
}
