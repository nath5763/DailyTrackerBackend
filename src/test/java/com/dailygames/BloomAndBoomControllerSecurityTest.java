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

@WebMvcTest(BloomAndBoomController.class)
@Import(SecurityConfig.class)
@EnableWebSecurity
class BloomAndBoomControllerSecurityTest {
  @Autowired MockMvc mvc;
  @MockitoBean BloomAndBoomService bloomAndBoom;
  @MockitoBean AuthService auth;
  @MockitoBean WeChatService wechat;

  @Test void guestCanLoadStartAndRevealWithAProtectedDeviceCookie() throws Exception {
    String cookie = mvc.perform(get("/api/v1/bloom-and-boom/today"))
        .andExpect(status().isOk()).andExpect(cookie().httpOnly("bloom_boom_guest", true))
        .andReturn().getResponse().getCookie("bloom_boom_guest").getValue();
    mvc.perform(post("/api/v1/bloom-and-boom/today/start")
            .cookie(new jakarta.servlet.http.Cookie("bloom_boom_guest", cookie))
            .header("Origin", "http://localhost:3000"))
        .andExpect(status().isOk());
    mvc.perform(post("/api/v1/bloom-and-boom/today/reveal")
            .cookie(new jakarta.servlet.http.Cookie("bloom_boom_guest", cookie))
            .header("Origin", "http://localhost:3000")
            .contentType(MediaType.APPLICATION_JSON).content("{\"row\":0,\"column\":0}"))
        .andExpect(status().isOk());
  }

  @Test void revealRejectsACrossSiteOrigin() throws Exception {
    mvc.perform(post("/api/v1/bloom-and-boom/today/reveal")
            .header("Origin", "https://other.example")
            .contentType(MediaType.APPLICATION_JSON).content("{\"row\":0,\"column\":0}"))
        .andExpect(status().isForbidden());
  }
}
