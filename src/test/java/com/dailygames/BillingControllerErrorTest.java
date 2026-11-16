package com.dailygames;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BillingController.class)
@Import(SecurityConfig.class)
@EnableWebSecurity
class BillingControllerErrorTest {
  @Autowired MockMvc mvc;
  @MockitoBean BillingService billing;
  @MockitoBean AuthService auth;
  @MockitoBean WeChatService wechat;

  @Test
  void unexpectedCheckoutFailureReturnsJson500InsteadOf401() throws Exception {
    UUID userId = UUID.randomUUID();
    CurrentUser current = new CurrentUser(userId, "owner@example.com");
    when(billing.createCheckout(userId, BillingTier.STANDARD)).thenThrow(new NullPointerException("test failure"));

    mvc.perform(post("/api/v1/billing/checkout-session")
            .contentType("application/json").content("{\"tier\":\"STANDARD\"}")
            .header("Origin", "http://localhost:3000")
            .with(authentication(new UsernamePasswordAuthenticationToken(current, "session", List.of()))))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"));
  }
}
