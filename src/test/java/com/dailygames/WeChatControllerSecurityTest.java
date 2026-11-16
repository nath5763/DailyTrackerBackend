package com.dailygames;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.*;

@WebMvcTest(WeChatController.class)
@Import(SecurityConfig.class)
@EnableWebSecurity
class WeChatControllerSecurityTest {
  @Autowired MockMvc mvc;
  @MockitoBean AuthService auth;
  @MockitoBean WeChatService wechat;

  @Test void sessionIsPublicButMutationsRequireAuthentication() throws Exception {
    when(wechat.login(eq("valid-code"), anyString()))
        .thenReturn(new WeChatService.SessionResult("mini-token", false, false));
    mvc.perform(post("/api/v1/wechat/session").contentType(MediaType.APPLICATION_JSON)
        .content("{\"code\":\"valid-code\"}")).andExpect(status().isOk());
    mvc.perform(post("/api/v1/wechat/group-context/resolve").contentType(MediaType.APPLICATION_JSON)
        .content("{\"encryptedData\":\"abc\",\"iv\":\"abc\"}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(delete("/api/v1/wechat/identity")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/v1/wechat/shares/00000000-0000-0000-0000-000000000000"))
        .andExpect(status().isUnauthorized());
  }

  @Test void arbitraryBearerTokenCannotAuthenticate() throws Exception {
    mvc.perform(get("/api/v1/wechat/status").header("Authorization", "Bearer " + "x".repeat(43)))
        .andExpect(status().isUnauthorized());
  }
}
