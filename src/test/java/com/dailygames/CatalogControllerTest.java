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
  @MockitoBean GameAdapterRegistry adapters;

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

  @Test void parserCapabilitiesArePublicAndExplicitAboutUnsupportedGames() throws Exception {
    when(adapters.capabilities()).thenReturn(List.of(
        new ApiModels.ParserCapabilityView("worldle", "PARTIAL", java.util.Set.of("share-text")),
        new ApiModels.ParserCapabilityView("waffle", "UNSUPPORTED", java.util.Set.of())));
    mvc.perform(get("/api/v1/catalog/games/parser-capabilities"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].gameId").value("worldle"))
        .andExpect(jsonPath("$[0].supportedFormats[0]").value("share-text"))
        .andExpect(jsonPath("$[1].status").value("UNSUPPORTED"))
        .andExpect(jsonPath("$[1].supportedFormats").isEmpty());
  }

  @Test void scoreStrategiesArePublicAndKeepPrimaryAndSecondaryDirectionsExplicit() throws Exception {
    when(adapters.scoreStrategies()).thenReturn(List.of(new ScoreStrategySpec("quordle", "failures then total",
        "failed", MetricDirection.ASCENDING, "penalizedGuessTotal", MetricDirection.ASCENDING,
        List.of("gameMode"), false)));
    mvc.perform(get("/api/v1/catalog/games/score-strategies"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].gameId").value("quordle"))
        .andExpect(jsonPath("$[0].primaryDirection").value("ASCENDING"))
        .andExpect(jsonPath("$[0].secondaryMetric").value("penalizedGuessTotal"))
        .andExpect(jsonPath("$[0].compatibilityFields[0]").value("gameMode"));
  }
}
