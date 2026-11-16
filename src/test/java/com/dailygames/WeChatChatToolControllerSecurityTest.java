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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WeChatChatToolController.class)
@Import(SecurityConfig.class)
@EnableWebSecurity
class WeChatChatToolControllerSecurityTest {
  @Autowired MockMvc mvc;
  @MockitoBean AuthService auth;
  @MockitoBean WeChatService wechat;
  @MockitoBean WeChatChatToolService service;

  @Test void contextAndCardOperationsRequireAuthentication() throws Exception {
    mvc.perform(get("/api/v1/wechat/chat-tool/capabilities"))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/api/v1/wechat/chat-tool/context").contentType(MediaType.APPLICATION_JSON)
        .content("{\"encryptedData\":\"proof\",\"iv\":\"iv\"}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/api/v1/wechat/chat-tool/cards/00000000-0000-0000-0000-000000000000"))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/api/v1/wechat/chat-tool/cards/00000000-0000-0000-0000-000000000000/shared"))
        .andExpect(status().isUnauthorized());
  }
}
