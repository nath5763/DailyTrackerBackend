package com.dailygames;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
class BrevoEmailClient {
  private static final Logger LOGGER = LoggerFactory.getLogger(BrevoEmailClient.class);
  private static final String SEND_EMAIL_PATH = "/v3/smtp/email";

  private final RestClient client;
  private final String senderEmail;
  private final String senderName;

  BrevoEmailClient(RestClient.Builder builder,
                   @Value("${app.brevo.api-url}") String apiUrl,
                   @Value("${app.brevo.api-key}") String apiKey,
                   @Value("${app.brevo.sender-email}") String senderEmail,
                   @Value("${app.brevo.sender-name}") String senderName) {
    if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("BREVO_API_KEY must be configured.");
    if (senderEmail == null || senderEmail.isBlank()) throw new IllegalStateException("BREVO_SENDER_EMAIL must be configured.");

    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(Duration.ofSeconds(10));

    this.client = builder.baseUrl(apiUrl.replaceAll("/$", ""))
        .requestFactory(requestFactory)
        .defaultHeader("api-key", apiKey)
        .defaultHeader("accept", MediaType.APPLICATION_JSON_VALUE)
        .defaultHeader("content-type", MediaType.APPLICATION_JSON_VALUE)
        .build();
    this.senderEmail = senderEmail;
    this.senderName = senderName;
  }

  void sendMagicLink(String recipientEmail, String magicLink, int linkMinutes) {
    String html = "<p>Use this link to sign in to Daily Club:</p><p><a href=\"" + magicLink
        + "\">Sign in to Daily Club</a></p><p>This link expires in " + linkMinutes
        + " minutes and can be used once.</p>";
    EmailRequest request = new EmailRequest(
        new Sender(senderEmail, senderName),
        List.of(new Recipient(recipientEmail)),
        "Your Daily Club sign-in link",
        html,
        List.of("magic-link"));

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
    return new ApiException(503, "EMAIL_UNAVAILABLE", "Sign-in email is temporarily unavailable. Please try again.");
  }

  private record Sender(String email, String name) {}
  private record Recipient(String email) {}
  private record EmailRequest(Sender sender, List<Recipient> to, String subject, String htmlContent, List<String> tags) {}
}
