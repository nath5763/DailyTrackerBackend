package com.dailygames;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TelegramController.class)
@Import(SecurityConfig.class)
@EnableWebSecurity
class TelegramControllerTest {
  @Autowired MockMvc mvc;
  @MockitoBean TelegramGateway bot;
  @MockitoBean TelegramService service;
  @MockitoBean AuthService auth;

  @Test void webhookRequiresSecretAndRejectsMalformedJson() throws Exception {
    mvc.perform(post("/api/v1/telegram/webhook").contentType(MediaType.APPLICATION_JSON)
            .content("{\"update_id\":1}"))
        .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_TELEGRAM_SECRET"));
    verifyNoInteractions(service);

    when(bot.accepts("correct-secret")).thenReturn(true);
    mvc.perform(post("/api/v1/telegram/webhook").contentType(MediaType.APPLICATION_JSON)
            .header("X-Telegram-Bot-Api-Secret-Token", "correct-secret").content("{"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_UPDATE"));
    verifyNoInteractions(service);
  }

  @Test void validWebhookIsPublicAndNeighboringRoutesNeedSession() throws Exception {
    when(bot.accepts("correct-secret")).thenReturn(true);
    mvc.perform(post("/api/v1/telegram/webhook").contentType(MediaType.APPLICATION_JSON)
            .header("X-Telegram-Bot-Api-Secret-Token", "correct-secret").content("{\"update_id\":12}"))
        .andExpect(status().isOk());
    verify(service).handle(any());
    mvc.perform(post("/api/v1/telegram/link-codes")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/telegram/status")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/telegram/signup-intents/" + "a".repeat(43))).andExpect(status().isOk());
  }
}
