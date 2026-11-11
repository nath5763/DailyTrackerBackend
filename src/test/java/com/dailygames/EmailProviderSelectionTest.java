package com.dailygames;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.*;

class EmailProviderSelectionTest {
  @Configuration
  @Import({BrevoEmailClient.class, MailpitEmailClient.class})
  static class EmailConfiguration {}

  private final ApplicationContextRunner context = new ApplicationContextRunner()
      .withUserConfiguration(EmailConfiguration.class);

  @Test void stagingStartsWithMailpitWithoutBrevoKey() {
    context.withPropertyValues("app.email-provider=mailpit", "app.mailpit.api-url=http://localhost:8025/mailpit",
        "app.mailpit.send-auth=sender:password", "app.mailpit.sender-email=load-test@example.com",
        "app.mailpit.sender-name=Daily Tracker").run(ctx -> {
          assertNull(ctx.getStartupFailure());
          assertInstanceOf(MailpitEmailClient.class, ctx.getBean(EmailSender.class));
          assertFalse(ctx.containsBean("brevoEmailClient"));
        });
  }

  @Test void productionDefaultsToBrevo() {
    context.withPropertyValues("app.brevo.api-url=https://api.brevo.com", "app.brevo.api-key=test-key",
        "app.brevo.sender-email=sender@example.com", "app.brevo.sender-name=Daily Tracker")
        .run(ctx -> {
          assertNull(ctx.getStartupFailure());
          assertInstanceOf(BrevoEmailClient.class, ctx.getBean(EmailSender.class));
          assertFalse(ctx.containsBean("mailpitEmailClient"));
        });
  }
}
