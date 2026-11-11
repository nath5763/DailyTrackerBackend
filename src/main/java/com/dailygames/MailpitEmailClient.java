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
@ConditionalOnProperty(name = "app.email-provider", havingValue = "mailpit")
class MailpitEmailClient implements EmailSender {
  private static final Logger LOGGER = LoggerFactory.getLogger(MailpitEmailClient.class);
  private final RestClient client;
  private final Address sender;

  MailpitEmailClient(@Value("${app.mailpit.api-url}") String apiUrl,
                     @Value("${app.mailpit.send-auth}") String sendAuth,
                     @Value("${app.mailpit.sender-email}") String senderEmail,
                     @Value("${app.mailpit.sender-name}") String senderName) {
    if (apiUrl == null || apiUrl.isBlank()) throw new IllegalStateException("MAILPIT_API_URL must be configured.");
    if (sendAuth == null || !sendAuth.matches("[^:\\s]+:.+"))
      throw new IllegalStateException("MAILPIT_SEND_AUTH must contain username:password.");
    if (senderEmail == null || senderEmail.isBlank())
      throw new IllegalStateException("MAILPIT_SENDER_EMAIL must be configured.");

    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(Duration.ofSeconds(10));
    String[] credentials = sendAuth.split(":", 2);
    client = RestClient.builder().baseUrl(apiUrl.replaceAll("/$", ""))
        .requestFactory(requestFactory)
        .defaultHeaders(headers -> headers.setBasicAuth(credentials[0], credentials[1]))
        .defaultHeader("accept", MediaType.APPLICATION_JSON_VALUE)
        .defaultHeader("content-type", MediaType.APPLICATION_JSON_VALUE)
        .build();
    sender = new Address(senderEmail, senderName);
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
    Message request = new Message(sender, List.of(new Address(recipientEmail, null)), subject, html, List.of(tag));
    try {
      client.post().uri("/api/v1/send").body(request).retrieve().toBodilessEntity();
    } catch (RestClientResponseException exception) {
      LOGGER.warn("Mailpit rejected an email request with status {}.", exception.getStatusCode().value());
      throw unavailable();
    } catch (RestClientException exception) {
      LOGGER.warn("Mailpit email request failed: {}.", exception.getClass().getSimpleName());
      throw unavailable();
    }
  }

  private ApiException unavailable() {
    return new ApiException(503, "EMAIL_UNAVAILABLE", "Account email is temporarily unavailable. Please try again.");
  }

  private record Address(String Email, String Name) {}
  private record Message(Address From, List<Address> To, String Subject, String HTML, List<String> Tags) {}
}
