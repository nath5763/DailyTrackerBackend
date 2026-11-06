package com.dailygames;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GameCatalogTest {
  @Test void springUsesTheRepositoryBackedConstructor() {
    Game active = game("wordle", "Wordle", true);
    GameRepository repository = mock(GameRepository.class);
    when(repository.findAll()).thenReturn(List.of(active));

    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.registerBean(GameRepository.class, () -> repository);
      context.register(GameCatalog.class);
      context.refresh();

      assertEquals(List.of("wordle"), context.getBean(GameCatalog.class).activeGames().stream()
          .map(game -> game.slug).toList());
    }
  }

  @Test void loadsEveryGameOnceAndKeepsAnImmutableDeploymentSnapshot() {
    Game active = game("wordle", "Wordle", true);
    Game inactive = game("archived", "Archived", false);
    GameRepository repository = mock(GameRepository.class);
    when(repository.findAll()).thenReturn(List.of(active, inactive), List.of());

    GameCatalog catalog = new GameCatalog(repository);

    assertEquals(List.of("Archived", "Wordle"), catalog.allGames().stream().map(game -> game.name).toList());
    assertEquals(List.of("wordle"), catalog.activeGames().stream().map(game -> game.slug).toList());
    assertEquals(active, catalog.findById(active.id).orElseThrow());
    assertTrue(catalog.findById(UUID.randomUUID()).isEmpty());
    assertEquals(active, catalog.findActiveBySlug("wordle").orElseThrow());
    assertTrue(catalog.findActiveBySlug("archived").isEmpty());

    // Runtime reads use only the startup snapshot, even if the repository would now return something else.
    assertEquals(List.of("wordle"), catalog.activeGames().stream().map(game -> game.slug).toList());
    verify(repository).findAll();
    verify(repository, never()).findByActiveTrueOrderByNameAsc();
    verify(repository, never()).findBySlugAndActiveTrue("wordle");
  }

  private Game game(String slug, String name, boolean active) {
    Game game = new Game();
    game.id = UUID.randomUUID();
    game.slug = slug;
    game.name = name;
    game.active = active;
    return game;
  }
}
