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

import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DailyGamesController.class)
@Import(SecurityConfig.class)
@EnableWebSecurity
class DashboardControllerSecurityTest {
  @Autowired MockMvc mvc;
  @MockitoBean DailyGamesService service;
  @MockitoBean LeaderboardInviteService invites;
  @MockitoBean LeaderboardDeletionService deletions;
  @MockitoBean WeChatService wechat;
  @MockitoBean WeChatChatToolService chatTool;
  @MockitoBean AuthService auth;

  @Test
  void privateHistoryAndSubmissionDetailsRequireAuthentication() throws Exception {
    mvc.perform(get("/api/v1/dashboard/games/wordle/submissions"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/dashboard/submissions/{id}", UUID.randomUUID()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void submissionDetailsUseTheAuthenticatedAccount() throws Exception {
    UUID userId = UUID.randomUUID(), submissionId = UUID.randomUUID();
    CurrentUser current = new CurrentUser(userId, "player@example.com");
    mvc.perform(get("/api/v1/dashboard/submissions/{id}", submissionId)
            .with(authentication(new UsernamePasswordAuthenticationToken(current, "session", List.of()))))
        .andExpect(status().isOk());
    verify(service).submissionDetail(current, submissionId);
  }
}
