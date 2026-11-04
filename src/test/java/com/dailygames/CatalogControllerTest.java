package com.dailygames;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CatalogController.class)
@Import(SecurityConfig.class)
@EnableWebSecurity
class CatalogControllerTest {
  @Autowired MockMvc mvc;
  @MockitoBean AuthService auth;
  @MockitoBean CatalogGameRepository catalog;
  @MockitoBean GameRepository games;

  @Test void directoryIsPublicAndCatalogOnlyGamesStayUntracked() throws Exception {
    CatalogGame wordle = new CatalogGame(); wordle.slug = "wordle"; wordle.name = "Wordle";
    wordle.description = "Guess a word"; wordle.category = "Word and Vocabulary";
    wordle.sourceUrl = "https://www.nytimes.com/games/wordle/index.html"; wordle.trackedGameSlug = "wordle";
    CatalogGame waffle = new CatalogGame(); waffle.slug = "waffle"; waffle.name = "Waffle";
    waffle.description = "Swap letters"; waffle.category = "Word and Vocabulary";
    waffle.sourceUrl = "https://wafflegame.net/";
    Game tracked = new Game(); tracked.slug = "wordle";
    when(catalog.findAllByOrderByDisplayOrderAsc()).thenReturn(List.of(wordle, waffle));
    when(games.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(tracked));
    mvc.perform(get("/api/v1/catalog/games"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].slug").value("wordle"))
        .andExpect(jsonPath("$[0].scoreTrackingAvailable").value(true))
        .andExpect(jsonPath("$[1].slug").value("waffle"))
        .andExpect(jsonPath("$[1].scoreTrackingAvailable").value(false));
    mvc.perform(get("/api/v1/games")).andExpect(status().isUnauthorized());
  }
}
