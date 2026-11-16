package com.dailygames;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
@EnableWebSecurity
class AuthControllerSecurityTest {
  @Autowired MockMvc mvc;
  @MockitoBean AuthService auth;
  @MockitoBean DiscordSignupService signup;
  @MockitoBean TelegramService telegram;
  @MockitoBean LeaderboardInviteService invites;
  @MockitoBean WeChatService wechat;

  @Test
  void forgotPasswordIsPublicWithoutASession() throws Exception {
    mvc.perform(post("/api/v1/auth/password/forgot")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Origin", "http://localhost:3000")
            .content("{\"email\":\"friend@example.com\"}"))
        .andExpect(status().isAccepted());
  }

  @Test
  void forgotPasswordRejectsWrongOrigin() throws Exception {
    mvc.perform(post("/api/v1/auth/password/forgot")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Origin", "https://other.example")
            .content("{\"email\":\"friend@example.com\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void forgotPasswordAllowsRequestFromItsOwnHost() throws Exception {
    mvc.perform(post("/api/v1/auth/password/forgot")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Origin", "https://dailytracker.cc")
            .with(request -> {
              request.setScheme("https");
              request.setServerName("dailytracker.cc");
              request.setServerPort(443);
              return request;
            })
            .content("{\"email\":\"friend@example.com\"}"))
        .andExpect(status().isAccepted());
  }

  @Test
  void unrelatedEndpointStillRequiresASession() throws Exception {
    mvc.perform(get("/api/v1/me"))
        .andExpect(status().isUnauthorized());
  }
}
